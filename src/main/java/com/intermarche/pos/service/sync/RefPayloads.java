package com.intermarche.pos.service.sync;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

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
        /** Whether the group touch is pinned (BO-03-01-07). */
        public boolean pinned;
        /** The touch size SMALL/NORMAL/LARGE (BO-03-01-08), never null. */
        public String buttonSize;
        /** The custom display rank (BO-03-01-11). */
        public int displayOrder;
        /** The sales volume for the volume order (BO-03-01-13). */
        public long salesVolume;
        /**
         * The codes of the families this one hangs under (BO-03-01-01).
         * <p>
         * A LIST, not a single parent: the model allows a family to hang under
         * several, and picking one would invent a rule the product has never
         * written. Empty for a root family.
         */
        public java.util.List<String> parentCodes = new java.util.ArrayList<>();
        /**
         * The EANs of the articles this family contains (BO-03-01-04/16).
         * <p>
         * Carried here rather than on the article, because the family owns the
         * association. Without it the register receives flat, empty groups —
         * the touch grid of a freshly pulled register would show buttons with
         * nothing behind them.
         */
        public java.util.List<String> productEans = new java.util.ArrayList<>();
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
        /** The touch image as a base64 data URI, or null (BO-03-01-15). */
        public String imageData;
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
        /** The minimum age required to sell the article, or null (BO-02-03-08). */
        public Integer ageRestriction;
        /** The checkout label, or null (BO-02-03-02). */
        public String checkoutLabel;
        /** The internal code, or null (BO-02-03-04). */
        public String internalCode;
        /** True when the product is sold loose (bulk), weight known only at weighing time. */
        public boolean variableWeight;
        /**
         * The declared attributes, code&nbsp;→&nbsp;text value (BO-02-03-18).
         * A {@link TreeMap} so JSON and the canonical fingerprint are ordered
         * by key; never null (empty when the article carries no attribute).
         */
        public Map<String, String> attributes = new TreeMap<>();
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
        /** The personal display theme, or null (follows the store). */
        public String theme;
        /** Whether the employee is active. */
        public boolean active;
    }

    /**
     * A back-office parameter (upsert by key; an absent key means the
     * register reverts to the catalog default).
     */
    public static class SettingDto {
        /** The parameter key (upsert key). */
        public String key;
        /** The parameter value, as text. */
        public String value;
    }

    /**
     * A verbatim engine feed (upsert by code): the raw CSV parcel shipped
     * unopened from the store node to the registers for their local
     * valuation engine. The version is the SHA-256 of the content.
     */
    public static class EngineFeedDto {
        /** The feed code (upsert key). */
        public String code;
        /** The content version (SHA-256 hex). */
        public String version;
        /** The raw CSV content, verbatim. */
        public String content;
    }

    /**
     * A country echelon (upsert by code) pulled by a store node from the
     * central node (domain COUNTRIES). Top of the organisation tree.
     */
    public static class CountryDto {
        /** The country code (upsert key). */
        public String code;
        /** The country name. */
        public String name;
        /** The default language, or null. */
        public String defaultLanguage;
    }

    /**
     * An enseigne echelon (upsert by code) pulled by a store node from the
     * central node (domain ENSEIGNES). Attached to a country by code.
     */
    public static class EnseigneDto {
        /** The enseigne code (upsert key). */
        public String code;
        /** The enseigne name. */
        public String name;
        /** The code of the country it hangs under, or null. */
        public String countryCode;
        /** The default language, or null. */
        public String defaultLanguage;
    }

    /**
     * A point de vente (upsert by number) pulled by a store node from the
     * central node (domain PDVS). Attached to an enseigne by code.
     */
    public static class PdvDto {
        /** The five-digit PDV number (upsert key). */
        public String pdvNumber;
        /** The PDV name. */
        public String name;
        /** The code of the enseigne it hangs under, or null. */
        public String enseigneCode;
        /** The adhérent grouping code, or null. */
        public String adherentCode;
        /** Whether the PDV is active. */
        public boolean active;
    }

    /**
     * A back-office parameter posed at an echelon (upsert by the triplet
     * level/code/key) pulled by a store node from the central node (domain
     * ECHELON_SETTINGS). Carries the effect date the value applies from.
     */
    public static class EchelonSettingDto {
        /** The echelon level name (COUNTRY, ENSEIGNE or PDV). */
        public String level;
        /** The echelon code at that level (part of the upsert key). */
        public String echelonCode;
        /** The catalog key (part of the upsert key). */
        public String settingKey;
        /** The value, as text. */
        public String settingValue;
        /** The effect date (ISO-8601 yyyy-MM-dd), or null for immediate effect. */
        public String effectiveDate;
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

    /**
     * An account customer as the commercial management holds it, pulled by the
     * register ({@code LC-07-09}).
     *
     * <p>The register can CREATE such a customer at the till (the invoice flow does)
     * and pushes it upward on the outbox, but the credit ceiling and the outstanding
     * balance travel the other way only: they are the shop's figures, and a register
     * that could raise its own ceiling would not be a control.
     */
    public static class CustomerDto {
        /** The account number (upsert key). */
        public String accountNumber;
        /** The business name. */
        public String companyName;
        /** The contact's family name, or null. */
        public String lastName;
        /** The contact's given name, or null. */
        public String firstName;
        /** The street line of the billing address, or null. */
        public String street;
        /** The postal code of the billing address, or null. */
        public String postalCode;
        /** The city of the billing address, or null. */
        public String city;
        /** The SIRET, or null. */
        public String siret;
        /** The intra-community VAT number, or null. */
        public String vatNumber;
        /** The telephone number, or null. */
        public String phone;
        /** The electronic address, or null. */
        public String email;
        /** The administered credit ceiling, or null when credit is not granted. */
        public String creditLimit;
        /** The outstanding balance, or null for none. */
        public String creditBalance;
    }

    /**
     * A foreign currency the shop accepts, with the rate it applies
     * ({@code LC-07-14}).
     */
    public static class CurrencyDto {
        /** The ISO code (upsert key). */
        public String code;
        /** The name shown to the cashier. */
        public String label;
        /** The symbol printed beside an amount, or null. */
        public String symbol;
        /** How many euros one unit is worth, as text to keep the row canonical. */
        public String euroPerUnit;
        /** Whether the till offers it. */
        public boolean active;
        /** The order it appears in on the payment screen. */
        public int displayOrder;
    }
}
