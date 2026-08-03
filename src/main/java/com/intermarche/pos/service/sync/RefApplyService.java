package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductType;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.domain.RefState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
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
     * flag; absents are left in place).
     *
     * @param dtos the snapshot rows
     */
    @Transactional
    public void applyFamilies(List<RefPayloads.FamilyDto> dtos) {
        for (RefPayloads.FamilyDto dto : dtos) {
            ProductFamily family = ProductFamily.find("code", dto.code).firstResult();
            if (family == null) {
                family = new ProductFamily();
                family.code = dto.code;
            }
            family.description = dto.description;
            family.flags = dto.flags;
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
            product.brand = dto.brand;
            product.referenceWeight = dto.referenceWeight;
            product.referenceVolume = dto.referenceVolume;
            product.productType = dto.productType != null ? ProductType.valueOf(dto.productType) : null;
            product.unitName = dto.unitName;
            product.active = dto.active;
            product.forbiddenToSale = dto.forbiddenToSale;
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
     *
     * @param domain the referential domain
     * @return the fingerprint, or null when never applied
     */
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
