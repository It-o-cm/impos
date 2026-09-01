package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Country;
import com.intermarche.pos.domain.EchelonSetting;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Enseigne;
import com.intermarche.pos.domain.Pdv;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store-side half of the centralized referentials (phase 6 lot 3): maps each
 * referential to its snapshot payloads and computes a SHA-256 fingerprint
 * per domain over the canonical row stream. The fingerprint is cached for a
 * short TTL so the registers' cheap polling never rescans the tables more
 * than once per minute — and no hook in the CSV imports is needed: any
 * change (insert, update, delete) changes the fingerprint by construction.
 * <p>
 * Maintenance invariant: {@code canonical(row)} and the DTO mappings MUST
 * evolve together — a field added to a payload but forgotten in its
 * canonical string would propagate on the next unrelated change only,
 * silently breaking the "any change changes the fingerprint" guarantee.
 * The register never recomputes a fingerprint: it stores the remote value
 * verbatim after applying, so both sides always compare the store node's
 * own arithmetic.
 */
@ApplicationScoped
public class RefExportService {

    /** The referential domains a REGISTER pulls from its store node, in apply order. */
    public static final List<String> DOMAINS =
            List.of("FAMILIES", "PRODUCTS", "PRICES", "EMPLOYEES", "COUPON_TYPES", "SETTINGS",
                    "ENGINE_FEEDS");

    /**
     * The echelon domains a STORE node pulls from the CENTRAL node (route A),
     * in apply order: the organisation tree top-down (a PDV references its
     * enseigne, an enseigne its country) then the parameters posed on it. A
     * register never pulls these — it receives only the resolved SETTINGS.
     */
    public static final List<String> ECHELON_DOMAINS =
            List.of("COUNTRIES", "ENSEIGNES", "PDVS", "ECHELON_SETTINGS");

    /** Fingerprint cache TTL in milliseconds. */
    private static final long FINGERPRINT_TTL_MS = 60_000;

    /** Cached fingerprint per domain. */
    private final Map<String, String> fingerprintCache = new HashMap<>();

    /** Cache timestamp per domain (epoch millis). */
    private final Map<String, Long> fingerprintCachedAt = new HashMap<>();

    /** Resolves the effective SETTINGS values a store publishes to its registers. */
    @Inject
    PosSettingsService posSettingsService;

    /**
     * Returns the fingerprint of every REGISTER-facing domain, recomputing the
     * expired ones.
     *
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints() {
        return getFingerprints(DOMAINS);
    }

    /**
     * Returns the fingerprint of every domain in the given set, recomputing the
     * expired ones — the store node passes {@link #DOMAINS}, the central node
     * passes {@link #ECHELON_DOMAINS}.
     *
     * @param domains the domains to fingerprint
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints(List<String> domains) {
        Map<String, String> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String domain : domains) {
            Long cachedAt = fingerprintCachedAt.get(domain);
            if (cachedAt == null || now - cachedAt > FINGERPRINT_TTL_MS) {
                fingerprintCache.put(domain, computeFingerprint(domain));
                fingerprintCachedAt.put(domain, now);
            }
            result.put(domain, fingerprintCache.get(domain));
        }
        return result;
    }

    /**
     * Returns one page of a domain's snapshot, in canonical order.
     *
     * @param domain the referential domain
     * @param page the 0-based page index
     * @param size the page size
     * @return the page payloads, empty past the end
     * @throws IllegalArgumentException on an unknown domain
     */
    public List<?> getPage(String domain, int page, int size) {
        return switch (domain) {
            case "FAMILIES" -> ProductFamily.<ProductFamily>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PRODUCTS" -> Product.<Product>find("order by ean")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PRICES" -> Price.<Price>find("order by id")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "EMPLOYEES" -> Employee.<Employee>find("order by loginName")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "COUPON_TYPES" -> CouponType.<CouponType>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "SETTINGS" -> settingsPage(page, size);
            case "ENGINE_FEEDS" -> com.intermarche.pos.domain.EngineFeed
                    .<com.intermarche.pos.domain.EngineFeed>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "COUNTRIES" -> Country.<Country>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ENSEIGNES" -> Enseigne.<Enseigne>find("order by code")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "PDVS" -> Pdv.<Pdv>find("order by pdvNumber")
                    .page(page, size).list().stream().map(this::toDto).toList();
            case "ECHELON_SETTINGS" -> EchelonSetting
                    .<EchelonSetting>find("order by level, echelonCode, settingKey")
                    .page(page, size).list().stream().map(this::toDto).toList();
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        };
    }

    /**
     * Builds one page of the SETTINGS snapshot from the RESOLVED effective
     * values (route A, BO-02-05-04): the store node publishes the result of the
     * echelon resolution for its own PDV, not the raw {@code pos_settings} rows,
     * so a register receives a parameter, never the echelon chain. Ordered by
     * key, paged in memory (the administered set is small and bounded).
     *
     * @param page the 0-based page index
     * @param size the page size
     * @return the page of setting payloads, empty past the end
     */
    private List<RefPayloads.SettingDto> settingsPage(int page, int size) {
        List<RefPayloads.SettingDto> all = new ArrayList<>();
        for (Map.Entry<String, String> entry : posSettingsService.administeredValues().entrySet()) {
            RefPayloads.SettingDto dto = new RefPayloads.SettingDto();
            dto.key = entry.getKey();
            dto.value = entry.getValue();
            all.add(dto);
        }
        int from = page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + size, all.size()));
    }

    /**
     * Maps a country echelon to its snapshot payload.
     *
     * @param country the country entity
     * @return the transport DTO
     */
    private RefPayloads.CountryDto toDto(Country country) {
        RefPayloads.CountryDto dto = new RefPayloads.CountryDto();
        dto.code = country.code;
        dto.name = country.name;
        dto.defaultLanguage = country.defaultLanguage;
        return dto;
    }

    /**
     * Maps an enseigne echelon to its snapshot payload.
     *
     * @param enseigne the enseigne entity
     * @return the transport DTO
     */
    private RefPayloads.EnseigneDto toDto(Enseigne enseigne) {
        RefPayloads.EnseigneDto dto = new RefPayloads.EnseigneDto();
        dto.code = enseigne.code;
        dto.name = enseigne.name;
        dto.countryCode = enseigne.countryCode;
        dto.defaultLanguage = enseigne.defaultLanguage;
        return dto;
    }

    /**
     * Maps a point de vente to its snapshot payload.
     *
     * @param pdv the PDV entity
     * @return the transport DTO
     */
    private RefPayloads.PdvDto toDto(Pdv pdv) {
        RefPayloads.PdvDto dto = new RefPayloads.PdvDto();
        dto.pdvNumber = pdv.pdvNumber;
        dto.name = pdv.name;
        dto.enseigneCode = pdv.enseigneCode;
        dto.adherentCode = pdv.adherentCode;
        dto.active = pdv.active;
        return dto;
    }

    /**
     * Maps an echelon parameter to its snapshot payload, its effect date as an
     * ISO date string.
     *
     * @param setting the echelon setting entity
     * @return the transport DTO
     */
    private RefPayloads.EchelonSettingDto toDto(EchelonSetting setting) {
        RefPayloads.EchelonSettingDto dto = new RefPayloads.EchelonSettingDto();
        dto.level = setting.level != null ? setting.level.name() : null;
        dto.echelonCode = setting.echelonCode;
        dto.settingKey = setting.settingKey;
        dto.settingValue = setting.settingValue;
        dto.effectiveDate = setting.effectiveDate != null ? setting.effectiveDate.toString() : null;
        return dto;
    }

    /**
     * Maps a verbatim engine feed to its snapshot payload (content shipped
     * unopened).
     *
     * @param feed the stored feed row
     * @return the transport DTO
     */
    private RefPayloads.EngineFeedDto toDto(com.intermarche.pos.domain.EngineFeed feed) {
        RefPayloads.EngineFeedDto dto = new RefPayloads.EngineFeedDto();
        dto.code = feed.code;
        dto.version = feed.version;
        dto.content = feed.content;
        return dto;
    }

    /**
     * Computes the SHA-256 fingerprint of a domain over its canonical row
     * stream.
     *
     * @param domain the referential domain
     * @return the fingerprint, hex encoded
     */
    private String computeFingerprint(String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int page = 0;
            List<?> rows;
            do {
                rows = getPage(domain, page++, 1000);
                for (Object row : rows) {
                    digest.update(canonical(row).getBytes(StandardCharsets.UTF_8));
                }
            } while (!rows.isEmpty());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Empreinte incalculable pour " + domain, e);
        }
    }

    /**
     * Builds the canonical string of a payload row (field order fixed by the
     * mapping methods).
     *
     * @param row the payload row
     * @return the canonical string
     */
    private String canonical(Object row) {
        if (row instanceof RefPayloads.FamilyDto f) {
            return String.join("|", n(f.code), n(f.description), n(f.flags));
        }
        if (row instanceof RefPayloads.ProductDto p) {
            return String.join("|", n(p.ean), n(p.plu), n(p.name), n(p.description), n(p.icon),
                    n(p.brand), n(p.referenceWeight), n(p.referenceVolume), n(p.productType),
                    n(p.unitName), String.valueOf(p.active), String.valueOf(p.forbiddenToSale),
                    n(p.ageRestriction), n(p.checkoutLabel), n(p.internalCode), attributes(p.attributes));
        }
        if (row instanceof RefPayloads.PriceDto p) {
            return String.join("|", n(p.productEan), n(p.priceExcludingTax), n(p.priceIncludingTax),
                    n(p.vatRate), n(p.priority), n(p.startDateTime), n(p.endDateTime));
        }
        if (row instanceof RefPayloads.EmployeeDto e) {
            return String.join("|", n(e.loginName), n(e.firstName), n(e.lastName), n(e.password),
                    n(e.email), n(e.role), n(e.badgeId), n(e.theme), String.valueOf(e.active));
        }
        if (row instanceof RefPayloads.SettingDto s) {
            return String.join("|", n(s.key), n(s.value));
        }
        if (row instanceof RefPayloads.CountryDto c) {
            return String.join("|", n(c.code), n(c.name), n(c.defaultLanguage));
        }
        if (row instanceof RefPayloads.EnseigneDto e) {
            return String.join("|", n(e.code), n(e.name), n(e.countryCode), n(e.defaultLanguage));
        }
        if (row instanceof RefPayloads.PdvDto p) {
            return String.join("|", n(p.pdvNumber), n(p.name), n(p.enseigneCode), n(p.adherentCode),
                    String.valueOf(p.active));
        }
        if (row instanceof RefPayloads.EchelonSettingDto s) {
            return String.join("|", n(s.level), n(s.echelonCode), n(s.settingKey), n(s.settingValue),
                    n(s.effectiveDate));
        }
        if (row instanceof RefPayloads.EngineFeedDto f) {
            // The version IS the SHA-256 of the content: hashing code and
            // version covers the whole parcel without streaming its body
            // through the fingerprint a second time.
            return String.join("|", n(f.code), n(f.version));
        }
        if (row instanceof RefPayloads.CouponTypeDto c) {
            return String.join("|", n(c.code), n(c.label), n(c.matchPattern), n(c.amountSource),
                    n(c.amountPattern), String.valueOf(c.priority), String.valueOf(c.active),
                    String.valueOf(c.depositLine));
        }
        return String.valueOf(row);
    }

    /**
     * Null-safe canonical rendering of a field.
     *
     * @param value the field value, or null
     * @return the canonical string, empty for null
     */
    private String n(Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Canonical rendering of a product's declared attributes: entries sorted by
     * code and joined {@code code=value}, so the fingerprint is stable whatever
     * the map's iteration order. An empty or null map renders as the empty
     * string.
     *
     * @param attributes the attribute map, or null
     * @return the ordered canonical rendering
     */
    private String attributes(java.util.Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        return new java.util.TreeMap<>(attributes).entrySet().stream()
                .map(e -> e.getKey() + "=" + n(e.getValue()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    // --------------------------------------------------
    // Entity to DTO mapping
    // --------------------------------------------------

    /**
     * Maps a product family to its payload.
     *
     * @param family the family entity
     * @return the payload
     */
    private RefPayloads.FamilyDto toDto(ProductFamily family) {
        RefPayloads.FamilyDto dto = new RefPayloads.FamilyDto();
        dto.code = family.code;
        dto.description = family.description;
        dto.flags = family.flags;
        return dto;
    }

    /**
     * Maps a product to its payload.
     *
     * @param product the product entity
     * @return the payload
     */
    private RefPayloads.ProductDto toDto(Product product) {
        RefPayloads.ProductDto dto = new RefPayloads.ProductDto();
        dto.ean = product.ean;
        dto.plu = product.plu;
        dto.name = product.name;
        dto.description = product.description;
        dto.icon = product.icon;
        dto.brand = product.brand;
        dto.referenceWeight = product.referenceWeight;
        dto.referenceVolume = product.referenceVolume;
        dto.productType = product.productType != null ? product.productType.name() : null;
        dto.unitName = product.unitName;
        dto.active = product.active;
        dto.forbiddenToSale = product.forbiddenToSale;
        dto.ageRestriction = product.ageRestriction;
        dto.checkoutLabel = product.checkoutLabel;
        dto.internalCode = product.internalCode;
        dto.attributes = product.attributes != null
                ? new java.util.TreeMap<>(product.attributes) : new java.util.TreeMap<>();
        return dto;
    }

    /**
     * Maps a price row to its payload.
     *
     * @param price the price entity
     * @return the payload
     */
    private RefPayloads.PriceDto toDto(Price price) {
        RefPayloads.PriceDto dto = new RefPayloads.PriceDto();
        dto.productEan = price.product != null ? price.product.ean : null;
        dto.priceExcludingTax = price.priceExcludingTax;
        dto.priceIncludingTax = price.priceIncludingTax;
        dto.vatRate = price.vatRate;
        dto.priority = price.priority;
        dto.startDateTime = iso(price.startDateTime);
        dto.endDateTime = iso(price.endDateTime);
        return dto;
    }

    /**
     * Maps an employee to its payload.
     *
     * @param employee the employee entity
     * @return the payload
     */
    private RefPayloads.EmployeeDto toDto(Employee employee) {
        RefPayloads.EmployeeDto dto = new RefPayloads.EmployeeDto();
        dto.loginName = employee.loginName;
        dto.firstName = employee.firstName;
        dto.lastName = employee.lastName;
        dto.password = employee.password;
        dto.email = employee.email;
        dto.role = employee.role.name();
        dto.badgeId = employee.badgeId;
        dto.theme = employee.theme;
        dto.active = employee.active;
        return dto;
    }

    /**
     * Maps a coupon type to its payload.
     *
     * @param type the coupon type entity
     * @return the payload
     */
    private RefPayloads.CouponTypeDto toDto(CouponType type) {
        RefPayloads.CouponTypeDto dto = new RefPayloads.CouponTypeDto();
        dto.code = type.code;
        dto.label = type.label;
        dto.matchPattern = type.matchPattern;
        dto.amountSource = type.amountSource.name();
        dto.amountPattern = type.amountPattern;
        dto.priority = type.priority;
        dto.active = type.active;
        dto.depositLine = type.depositLine;
        return dto;
    }

    /**
     * Formats a timestamp as ISO-8601, tolerating null.
     *
     * @param dateTime the timestamp, or null
     * @return the ISO string, or null
     */
    private String iso(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
