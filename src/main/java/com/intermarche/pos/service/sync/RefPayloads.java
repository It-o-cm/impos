package com.intermarche.pos.service.sync;

import java.math.BigDecimal;

/**
 * Referential snapshot payloads pulled by the registers from the store node
 * (phase 6 lot 3). Natural keys only, like the document synchronization:
 * family code, product EAN, employee login, coupon-type code; prices
 * reference their product by EAN. Dates travel as ISO-8601 strings.
 * <p>
 * Any field added here must also be added to the canonical string of
 * {@code RefExportService} (fingerprint input) and to the corresponding
 * apply method — the three evolve as one.
 */
public final class RefPayloads {

    /**
     * Non-instantiable payload container.
     */
    private RefPayloads() {}

    /**
     * A product family (upsert by code).
     */
    public static class FamilyDto {
        /** The family code (upsert key). */
        public String code;
        /** The family description. */
        public String description;
        /** The display flags. */
        public String flags;
    }

    /**
     * A product (upsert by EAN).
     */
    public static class ProductDto {
        /** The EAN (upsert key). */
        public String ean;
        /** The PLU, or null. */
        public String plu;
        /** The product name. */
        public String name;
        /** The description, or null. */
        public String description;
        /** The display icon, or null. */
        public String icon;
        /** The brand, or null. */
        public String brand;
        /** The reference weight, or null. */
        public BigDecimal referenceWeight;
        /** The reference volume, or null. */
        public BigDecimal referenceVolume;
        /** The product type name. */
        public String productType;
        /** The unit name, or null. */
        public String unitName;
        /** Whether the product is active. */
        public boolean active;
        /** Whether the product is forbidden to sale. */
        public boolean forbiddenToSale;
    }

    /**
     * A price row (prices are replaced as a whole, no upsert key).
     */
    public static class PriceDto {
        /** The EAN of the priced product. */
        public String productEan;
        /** The price excluding tax. */
        public BigDecimal priceExcludingTax;
        /** The price including tax. */
        public BigDecimal priceIncludingTax;
        /** The VAT rate. */
        public BigDecimal vatRate;
        /** The selection priority. */
        public Integer priority;
        /** The validity start, ISO-8601, or null. */
        public String startDateTime;
        /** The validity end, ISO-8601, or null. */
        public String endDateTime;
    }

    /**
     * An employee (upsert by login; local lockout counters are preserved).
     */
    public static class EmployeeDto {
        /** The login (upsert key). */
        public String loginName;
        /** The first name. */
        public String firstName;
        /** The last name. */
        public String lastName;
        /** The password hash. */
        public String password;
        /** The email. */
        public String email;
        /** The role name. */
        public String role;
        /** The badge id, or null. */
        public String badgeId;
        /** Whether the employee is active. */
        public boolean active;
    }

    /**
     * A coupon type (upsert by code).
     */
    public static class CouponTypeDto {
        /** The type code (upsert key). */
        public String code;
        /** The display label. */
        public String label;
        /** The recognition regex, or empty for numberless types. */
        public String matchPattern;
        /** The amount source name (ENCODED or MANUAL). */
        public String amountSource;
        /** The amount extraction regex, or null. */
        public String amountPattern;
        /** The matching priority. */
        public int priority;
        /** Whether the type is active. */
        public boolean active;
        /** Whether the type is a deposit-return line type. */
        public boolean depositLine;
    }
}
