package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Country;
import com.intermarche.pos.domain.EchelonLevel;
import com.intermarche.pos.domain.EchelonSetting;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Enseigne;
import com.intermarche.pos.domain.Pdv;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductType;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.domain.RefState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Register-side application of a referential snapshot (phase 6 lot 3), one
 * transaction per domain. Rows are upserted by natural key; rows absent from
 * the snapshot are deactivated, never deleted (ticket lines hold product
 * foreign keys) — except prices, replaced as a whole (nothing references
 * them: ticket lines snapshot their values). Employee lockout counters are
 * local operational state and survive the upsert.
 * <p>
 * Failure semantics: each domain commits alone, and the fingerprint is
 * recorded in a SEPARATE transaction after the apply — a crash between the
 * two merely re-pulls and re-applies the same snapshot on the next cycle
 * (upserts converge, the price replacement is wholesale: harmless). Known
 * edge: two products SWAPPING their PLUs inside one snapshot can trip the
 * unique constraint depending on row order (same exposure as the CSV
 * import). Snapshots are applied from memory one full domain at a time,
 * which is deliberate and bounded by catalog size.
 */
@ApplicationScoped
public class RefApplyService {

    private static final Logger LOG = Logger.getLogger(RefApplyService.class);

    /**
     * Applies a family snapshot: upsert by code (families carry no active
     * flag; absents are left in place), then rebuilds the GROUP TREE and the
     * article memberships the snapshot describes.
     * <p>
     * The edges matter as much as the rows: without them a register receives
     * flat, empty groups, and the touch grid shows buttons with nothing behind
     * them — everything the back office administered would stop at the store
     * node. They are rebuilt in three passes because a snapshot ordered by code
     * puts parents and children in any order, and because clearing a collection
     * while wiring another would erase what an earlier row had just received.
     * Articles are resolved by EAN and are already applied at this point:
     * PRODUCTS comes before FAMILIES in {@code DOMAINS} for that reason.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyFamilies(List<RefPayloads.FamilyDto> dtos) {
        // FIRST PASS: the rows themselves. The edges cannot be wired here —
        // a parent may appear after its child in a snapshot ordered by code.
        Map<String, ProductFamily> byCode = new HashMap<>();
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = ProductFamily.find("code", dto.code).firstResult();
            if (family == null) {
                family = new ProductFamily();
                family.code = dto.code;
            }
            family.description = dto.description;
            family.flags = dto.flags;
            family.pinned = dto.pinned;
            family.buttonSize = dto.buttonSize;
            family.displayOrder = dto.displayOrder;
            family.salesVolume = dto.salesVolume;
            family.persist();
            byCode.put(dto.code, family);
        }
        // SECOND PASS: empty every edge collection BEFORE wiring any of them.
        // Clearing inside the wiring loop would erase the children a family
        // received from a row processed earlier — the snapshot is applied in
        // code order, a parent can come after its child.
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = byCode.get(dto.code);
            family.productFamilies.clear();
            family.products.clear();
        }
        // THIRD PASS: the tree and the memberships. Both collections are
        // REPLACED, never merged: the snapshot is the truth, so a family
        // removed from a group upstream leaves it here too.
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = byCode.get(dto.code);
            for (String parentCode : dto.parentCodes) {
                ProductFamily parent = byCode.get(parentCode);
                if (parent != null) {
                    parent.productFamilies.add(family);
                }
            }
            for (String ean : dto.productEans) {
                Product product = Product.find("ean", ean).firstResult();
                if (product != null) {
                    family.products.add(product);
                }
            }
            family.persist();
        }
        LOG.infof("Référentiel familles appliqué: %d ligne(s)", dtos.size());
    }

    /**
     * Applies a product snapshot: upsert by EAN, absents deactivated.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyProducts(List<RefPayloads.ProductDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.ProductDto dto : dtos) {
            Product product = Product.find("ean", dto.ean).firstResult();
            if (product == null) {
                product = new Product();
                product.ean = dto.ean;
            }
            product.plu = dto.plu;
            product.name = dto.name;
            product.description = dto.description;
            product.icon = dto.icon;
            product.imageData = dto.imageData;
            product.brand = dto.brand;
            product.referenceWeight = dto.referenceWeight;
            product.referenceVolume = dto.referenceVolume;
            product.productType = dto.productType != null ? ProductType.valueOf(dto.productType) : null;
            product.unitName = dto.unitName;
            product.active = dto.active;
            product.forbiddenToSale = dto.forbiddenToSale;
            product.ageRestriction = dto.ageRestriction;
            product.checkoutLabel = dto.checkoutLabel;
            product.internalCode = dto.internalCode;
            product.variableWeight = dto.variableWeight;
            product.attributes = dto.attributes != null
                    ? new java.util.HashMap<>(dto.attributes) : new java.util.HashMap<>();
            product.persist();
            seen.add(dto.ean);
        }
        int deactivated = deactivateAbsentProducts(seen);
        LOG.infof("Référentiel produits appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
    }

    /**
     * Applies a price snapshot: full replacement (nothing references price
     * rows). Rows pointing to a product unknown on this register are skipped
     * and counted.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyPrices(List<RefPayloads.PriceDto> dtos) {
        Price.deleteAll();
        int skipped = 0;
        for (RefPayloads.PriceDto dto : dtos) {
            Product product = dto.productEan != null
                    ? Product.<Product>find("ean", dto.productEan).firstResult()
                    : null;
            if (product == null) {
                skipped++;
                continue;
            }
            Price price = new Price();
            price.product = product;
            price.priceExcludingTax = dto.priceExcludingTax;
            price.priceIncludingTax = dto.priceIncludingTax;
            price.vatRate = dto.vatRate;
            price.priority = dto.priority != null ? dto.priority : 0;
            price.startDateTime = parse(dto.startDateTime);
            price.endDateTime = parse(dto.endDateTime);
            price.persist();
        }
        LOG.infof("Référentiel prix remplacé: %d ligne(s), %d orpheline(s) ignorée(s)",
                dtos.size() - skipped, skipped);
    }

    /**
     * Applies an employee snapshot: upsert by login, absents deactivated,
     * local lockout counters preserved.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyEmployees(List<RefPayloads.EmployeeDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.EmployeeDto dto : dtos) {
            Employee employee = Employee.find("loginName", dto.loginName).firstResult();
            if (employee == null) {
                employee = new Employee();
                employee.loginName = dto.loginName;
            }
            employee.firstName = dto.firstName;
            employee.lastName = dto.lastName;
            employee.password = dto.password;
            employee.email = dto.email;
            employee.role = Employee.EmployeeRole.valueOf(dto.role);
            employee.badgeId = dto.badgeId;
            // A theme chosen AT THE REGISTER wins over the pulled one: the
            // register-side selector must not be mysteriously undone by the
            // next referential pull. The store's value only seeds employees
            // who never chose.
            if (employee.theme == null) {
                employee.theme = dto.theme;
            }
            employee.active = dto.active;
            // failedAttempts / lockedUntil are local state: untouched
            employee.persist();
            seen.add(dto.loginName);
        }
        long deactivated = 0;
        for (Employee employee : Employee.<Employee>listAll()) {
            if (!seen.contains(employee.loginName) && employee.active) {
                employee.active = false;
                employee.persist();
                deactivated++;
            }
        }
        LOG.infof("Référentiel employés appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
    }

    /**
     * Applies a coupon-type snapshot: upsert by code, absents deactivated.
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyCouponTypes(List<RefPayloads.CouponTypeDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CouponTypeDto dto : dtos) {
            CouponType type = CouponType.find("code", dto.code).firstResult();
            if (type == null) {
                type = new CouponType();
                type.code = dto.code;
            }
            type.label = dto.label;
            type.matchPattern = dto.matchPattern;
            type.amountSource = CouponType.AmountSource.valueOf(dto.amountSource);
            type.amountPattern = dto.amountPattern;
            type.priority = dto.priority;
            type.active = dto.active;
            type.depositLine = dto.depositLine;
            type.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (CouponType type : CouponType.<CouponType>listAll()) {
            if (!seen.contains(type.code) && type.active) {
                type.active = false;
                type.persist();
                deactivated++;
            }
        }
        LOG.infof("Référentiel types de bons appliqué: %d ligne(s), %d désactivé(s)", dtos.size(), deactivated);
    }

    /**
     * Applies the foreign-currency snapshot ({@code LC-07-14}): upsert by ISO code,
     * and the currencies absent from the snapshot are DEACTIVATED rather than
     * deleted — a receipt from last month still names one, and a code that comes
     * back keeps its history.
     *
     * @param dtos the full currency snapshot
     */
    @Transactional
    public void applyCurrencies(List<RefPayloads.CurrencyDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.CurrencyDto dto : dtos) {
            com.intermarche.pos.domain.Currency currency =
                    com.intermarche.pos.domain.Currency.find("code", dto.code).firstResult();
            if (currency == null) {
                currency = new com.intermarche.pos.domain.Currency();
                currency.code = dto.code;
            }
            currency.label = dto.label;
            currency.symbol = dto.symbol;
            java.math.BigDecimal rate = amount(dto.euroPerUnit);
            currency.euroPerUnit = rate == null ? java.math.BigDecimal.ONE : rate;
            currency.active = dto.active;
            currency.displayOrder = dto.displayOrder;
            currency.persist();
            seen.add(dto.code);
        }
        long deactivated = 0;
        for (com.intermarche.pos.domain.Currency currency
                : com.intermarche.pos.domain.Currency.<com.intermarche.pos.domain.Currency>listAll()) {
            if (!seen.contains(currency.code) && currency.active) {
                currency.active = false;
                currency.persist();
                deactivated++;
            }
        }
        LOG.infof("Référentiel devises appliqué: %d ligne(s), %d désactivée(s)",
                dtos.size(), deactivated);
    }

    /**
     * Applies the account-customer snapshot ({@code LC-07-09}): upsert by account
     * number, credit ceiling and outstanding balance included.
     *
     * <p>UNLIKE THE OTHER DOMAINS, nothing is deactivated or deleted for the
     * customers absent from the snapshot. A customer created AT THE TILL — the
     * invoice flow does exactly that — has not reached the commercial management
     * yet at the next pull, and wiping it would destroy, on the way back, the
     * declaration the register just made. The commercial management remains the
     * authority on the credit figures; it is not the authority on the existence
     * of a customer the register itself opened five minutes ago.
     *
     * @param dtos the full customer snapshot
     */
    @Transactional
    public void applyCustomers(List<RefPayloads.CustomerDto> dtos) {
        for (RefPayloads.CustomerDto dto : dtos) {
            com.intermarche.pos.domain.AccountCustomer customer =
                    com.intermarche.pos.domain.AccountCustomer
                            .find("accountNumber", dto.accountNumber).firstResult();
            if (customer == null) {
                customer = new com.intermarche.pos.domain.AccountCustomer();
                customer.accountNumber = dto.accountNumber;
            }
            customer.companyName = dto.companyName;
            customer.lastName = dto.lastName;
            customer.firstName = dto.firstName;
            if (customer.address == null) {
                customer.address = new com.intermarche.pos.domain.Address();
            }
            customer.address.streetLine1 = dto.street;
            customer.address.postalCode = dto.postalCode;
            customer.address.city = dto.city;
            customer.siret = dto.siret;
            customer.vatNumber = dto.vatNumber;
            customer.phone = dto.phone;
            customer.email = dto.email;
            customer.creditLimit = amount(dto.creditLimit);
            java.math.BigDecimal balance = amount(dto.creditBalance);
            customer.creditBalance = balance == null ? java.math.BigDecimal.ZERO : balance;
            customer.persist();
        }
        LOG.infof("Référentiel clients en compte appliqué: %d ligne(s)", dtos.size());
    }

    /**
     * Reads a decimal amount carried as text by a snapshot row.
     *
     * @param text the amount as exported, possibly null or blank
     * @return the amount, or null when nothing readable was sent
     */
    private static java.math.BigDecimal amount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The settings cache to drop after an apply. */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Applies the back-office parameters snapshot: upsert by key, and the
     * keys ABSENT from the store are deleted locally — the register then
     * reverts to the catalog defaults, so "not administered" means the same
     * thing on every node. The settings cache is dropped last.
     *
     * @param dtos the full parameters snapshot
     */
    @jakarta.transaction.Transactional
    public void applySettings(List<RefPayloads.SettingDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.SettingDto dto : dtos) {
            com.intermarche.pos.domain.PosSetting row =
                    com.intermarche.pos.domain.PosSetting.findByKey(dto.key);
            if (row == null) {
                row = new com.intermarche.pos.domain.PosSetting();
                row.settingKey = dto.key;
            }
            row.settingValue = dto.value;
            row.persist();
            seen.add(dto.key);
        }
        long removed = com.intermarche.pos.domain.PosSetting.delete(
                "settingKey not in ?1", seen.isEmpty() ? java.util.List.of("") : seen);
        posSettingsService.invalidate();
        LOG.infof("Paramètres appliqués: %d ligne(s), %d supprimé(s)", dtos.size(), removed);
    }

    /**
     * Applies the verbatim engine feeds snapshot: upsert by code when the
     * version differs — the engine acknowledgement columns are PRESERVED,
     * the local delivery loop owns them — and the codes absent from the
     * store are deleted locally (a withdrawn feed stops being delivered).
     *
     * @param dtos the full engine-feeds snapshot
     */
    @Transactional
    public void applyEngineFeeds(List<RefPayloads.EngineFeedDto> dtos) {
        Set<String> seen = new HashSet<>();
        for (RefPayloads.EngineFeedDto dto : dtos) {
            com.intermarche.pos.domain.EngineFeed row =
                    com.intermarche.pos.domain.EngineFeed.findByCode(dto.code);
            if (row == null) {
                row = new com.intermarche.pos.domain.EngineFeed();
                row.code = dto.code;
            } else if (dto.version != null && dto.version.equals(row.version)) {
                seen.add(dto.code);
                continue;
            }
            row.content = dto.content;
            row.version = dto.version;
            row.receivedAt = java.time.LocalDateTime.now();
            row.persist();
            seen.add(dto.code);
        }
        long removed = com.intermarche.pos.domain.EngineFeed.delete(
                "code not in ?1", seen.isEmpty() ? java.util.List.of("") : seen);
        LOG.infof("Flux moteur appliqués: %d ligne(s), %d supprimé(s)", dtos.size(), removed);
    }

    /**
     * Applies a country snapshot (store node, route A): the echelon tree is
     * fully owned by the central node, and nothing on the store references a
     * country by foreign key, so the table is replaced as a whole — the same
     * wholesale strategy as prices.
     *
     * @param dtos the full countries snapshot
     */
    @Transactional
    public void applyCountries(List<RefPayloads.CountryDto> dtos) {
        Country.deleteAll();
        for (RefPayloads.CountryDto dto : dtos) {
            Country row = new Country();
            row.code = dto.code;
            row.name = dto.name;
            row.defaultLanguage = dto.defaultLanguage;
            row.persist();
        }
        LOG.infof("Référentiel pays appliqué: %d ligne(s)", dtos.size());
    }

    /**
     * Applies an enseigne snapshot (store node, route A): wholesale replacement,
     * like the countries — no foreign key on the store points at an enseigne.
     *
     * @param dtos the full enseignes snapshot
     */
    @Transactional
    public void applyEnseignes(List<RefPayloads.EnseigneDto> dtos) {
        Enseigne.deleteAll();
        for (RefPayloads.EnseigneDto dto : dtos) {
            Enseigne row = new Enseigne();
            row.code = dto.code;
            row.name = dto.name;
            row.countryCode = dto.countryCode;
            row.defaultLanguage = dto.defaultLanguage;
            row.persist();
        }
        LOG.infof("Référentiel enseignes appliqué: %d ligne(s)", dtos.size());
    }

    /**
     * Applies a PDV snapshot (store node, route A): wholesale replacement. The
     * enseigne link and the adhérent grouping travel by code, so a re-parenting
     * done centrally lands as a plain field change here.
     *
     * @param dtos the full PDVs snapshot
     */
    @Transactional
    public void applyPdvs(List<RefPayloads.PdvDto> dtos) {
        Pdv.deleteAll();
        for (RefPayloads.PdvDto dto : dtos) {
            Pdv row = new Pdv();
            row.pdvNumber = dto.pdvNumber;
            row.name = dto.name;
            row.enseigneCode = dto.enseigneCode;
            row.adherentCode = dto.adherentCode;
            row.active = dto.active;
            row.persist();
        }
        LOG.infof("Référentiel PDV appliqué: %d ligne(s)", dtos.size());
    }

    /**
     * Applies an echelon-parameters snapshot (store node, route A): wholesale
     * replacement, then the settings cache is dropped so this store re-resolves
     * its own PDV's effective values on the next read — this is what carries a
     * value posed at the enseigne down to the registers, and what makes a
     * future-dated value apply on its own once its day comes (BO-03-12-03/04).
     * The store's OWN local overrides live in a different table ({@code
     * pos_settings}) and are untouched, so they survive this apply.
     *
     * @param dtos the full echelon-settings snapshot
     */
    @Transactional
    public void applyEchelonSettings(List<RefPayloads.EchelonSettingDto> dtos) {
        EchelonSetting.deleteAll();
        for (RefPayloads.EchelonSettingDto dto : dtos) {
            EchelonSetting row = new EchelonSetting();
            row.level = EchelonLevel.valueOf(dto.level);
            row.echelonCode = dto.echelonCode;
            row.settingKey = dto.settingKey;
            row.settingValue = dto.settingValue;
            row.effectiveDate = dto.effectiveDate != null ? LocalDate.parse(dto.effectiveDate) : null;
            row.persist();
        }
        posSettingsService.invalidate();
        LOG.infof("Paramètres d'échelon appliqués: %d ligne(s)", dtos.size());
    }

    /**
     * Records the applied fingerprint of a domain.
     *
     * @param domain the referential domain
     * @param fingerprint the remote fingerprint just applied
     */
    @Transactional
    public void recordApplied(String domain, String fingerprint) {
        RefState state = RefState.find("domain", domain).firstResult();
        if (state == null) {
            state = new RefState();
            state.domain = domain;
        }
        state.fingerprint = fingerprint;
        state.appliedAt = LocalDateTime.now();
        state.persist();
    }

    /**
     * Returns the last applied fingerprint of a domain.
     * <p>
     * Transactional like every other database method here, and for a reason
     * that is not cosmetic: the pull loop runs on its own scheduler thread,
     * where no request context and no transaction exist. This read is the
     * FIRST database touch of a cycle, so without a transaction of its own the
     * whole cycle dies before pulling anything — silently for the user, once
     * every pull interval, in the log only.
     *
     * @param domain the referential domain
     * @return the fingerprint, or null when never applied
     */
    @Transactional
    public String lastApplied(String domain) {
        RefState state = RefState.find("domain", domain).firstResult();
        return state != null ? state.fingerprint : null;
    }

    /**
     * Deactivates the products absent from the applied snapshot.
     *
     * @param seenEans the EANs present in the snapshot
     * @return the number of deactivated products
     */
    private int deactivateAbsentProducts(Set<String> seenEans) {
        int deactivated = 0;
        for (Product product : Product.<Product>listAll()) {
            if (!seenEans.contains(product.ean) && product.active) {
                product.active = false;
                product.persist();
                deactivated++;
            }
        }
        return deactivated;
    }

    /**
     * Parses an ISO-8601 timestamp, tolerating null.
     *
     * @param value the ISO string, or null
     * @return the timestamp, or null
     */
    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
