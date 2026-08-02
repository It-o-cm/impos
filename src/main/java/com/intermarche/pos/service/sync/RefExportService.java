package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import jakarta.enterprise.context.ApplicationScoped;

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

    /** The referential domains, in register apply order. */
    public static final List<String> DOMAINS =
            List.of("FAMILIES", "PRODUCTS", "PRICES", "EMPLOYEES", "COUPON_TYPES");

    /** Fingerprint cache TTL in milliseconds. */
    private static final long FINGERPRINT_TTL_MS = 60_000;

    /** Cached fingerprint per domain. */
    private final Map<String, String> fingerprintCache = new HashMap<>();

    /** Cache timestamp per domain (epoch millis). */
    private final Map<String, Long> fingerprintCachedAt = new HashMap<>();

    /**
     * Returns the fingerprint of every domain, recomputing the expired ones.
     *
     * @return an ordered map domain to fingerprint
     */
    public synchronized Map<String, String> getFingerprints() {
        Map<String, String> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String domain : DOMAINS) {
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
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        };
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
                    n(p.unitName), String.valueOf(p.active), String.valueOf(p.forbiddenToSale));
        }
        if (row instanceof RefPayloads.PriceDto p) {
            return String.join("|", n(p.productEan), n(p.priceExcludingTax), n(p.priceIncludingTax),
                    n(p.vatRate), n(p.priority), n(p.startDateTime), n(p.endDateTime));
        }
        if (row instanceof RefPayloads.EmployeeDto e) {
            return String.join("|", n(e.loginName), n(e.firstName), n(e.lastName), n(e.password),
                    n(e.email), n(e.role), n(e.badgeId), n(e.theme), String.valueOf(e.active));
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
