package com.intermarche.pos.domain.attribute;

import com.intermarche.pos.domain.Product;

/**
 * The typed reader of a product's declared attributes (BO-02-03-18) — the
 * single façade the sale surface consults so no scan handler parses the raw
 * attribute map itself.
 * <p>
 * Reading rules, applied uniformly:
 * <ul>
 *   <li>a null product or a product with no attribute map yields the catalog
 *       default (or false / null when the code is not well-known);</li>
 *   <li>a value explicitly carried by the product wins over the default;</li>
 *   <li>a well-known code with no product value falls back to its declared
 *       default in {@link ProductAttributeCatalog}.</li>
 * </ul>
 * The named accessors ({@link #vatExempt}, {@link #discountForbidden}, …) are
 * the vocabulary the register speaks; {@link #raw} is the generic escape hatch
 * for attributes integrated from the Gestion Commerciale that the register does
 * not act upon. Pure and static: no CDI, no database — everything reads the
 * in-memory {@link Product#attributes} map, which the scan chain has already
 * loaded with the product.
 * <p>
 * Non-instantiable.
 */
public final class ProductAttributes {

    /**
     * Non-instantiable utility.
     */
    private ProductAttributes() {
    }

    /**
     * Returns the raw text value of an attribute: the product's own value when
     * present, otherwise the catalog default of a well-known code, otherwise
     * null.
     *
     * @param product the product, or null
     * @param code the attribute code
     * @return the resolved text value, or null when neither the product nor the
     *         catalog supplies one
     */
    public static String raw(Product product, String code) {
        if (product != null && product.attributes != null) {
            String value = product.attributes.get(code);
            if (value != null) {
                return value;
            }
        }
        return ProductAttributeCatalog.defaultValue(code);
    }

    /**
     * Reads an attribute as a boolean flag. A missing value resolves through
     * {@link #raw} (catalog default or null); a null resolved value is false.
     *
     * @param product the product, or null
     * @param code the attribute code
     * @return true when the resolved value is {@code "true"} (case-insensitive)
     */
    public static boolean flag(Product product, String code) {
        String value = raw(product, code);
        return value != null && Boolean.parseBoolean(value);
    }

    /**
     * Whether discounts and rebates are forbidden on the article (BO-02-03-09).
     *
     * @param product the product, or null
     * @return true when the article bans price reductions
     */
    public static boolean discountForbidden(Product product) {
        return flag(product, ProductAttributeCatalog.DISCOUNT_FORBIDDEN);
    }

    /**
     * Whether the article is VAT-exempt — its ticket line is ventilated at rate
     * 0 (BO-02-03-26/27).
     *
     * @param product the product, or null
     * @return true when the article is VAT-exempt
     */
    public static boolean vatExempt(Product product) {
        return flag(product, ProductAttributeCatalog.VAT_EXEMPT);
    }

    /**
     * Whether the article is under a product recall and must be refused at the
     * register (BO-02-03-11).
     *
     * @param product the product, or null
     * @return true when the article is recalled
     */
    public static boolean recall(Product product) {
        return flag(product, ProductAttributeCatalog.RECALL);
    }

    /**
     * Whether the article is eligible for meal-voucher tender (BO-02-03-06).
     *
     * @param product the product, or null
     * @return true when the article is meal-voucher eligible
     */
    public static boolean mealVoucherEligible(Product product) {
        return flag(product, ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE);
    }

    /**
     * Whether the article is bulky (BO-02-03-25).
     *
     * @param product the product, or null
     * @return true when the article is bulky
     */
    public static boolean bulky(Product product) {
        return flag(product, ProductAttributeCatalog.BULKY);
    }

    /**
     * Whether the price must be entered at the register (BO-02-03-21).
     *
     * @param product the product, or null
     * @return true when the price is to be entered
     */
    public static boolean priceToEnter(Product product) {
        return flag(product, ProductAttributeCatalog.PRICE_TO_ENTER);
    }

    /**
     * Whether a quantity must be entered at the register (BO-02-03-22).
     *
     * @param product the product, or null
     * @return true when a quantity is to be entered
     */
    public static boolean quantityToEnter(Product product) {
        return flag(product, ProductAttributeCatalog.QUANTITY_TO_ENTER);
    }

    /**
     * Whether the article is covered by the legal conformity guarantee (GLC):
     * a ticket carrying one is printed whatever the cashier chose, when the
     * back office forces it (LC-08-03-09).
     *
     * @param product the product, or null
     * @return true when the article is under the legal conformity guarantee
     */
    public static boolean legalWarranty(Product product) {
        return flag(product, ProductAttributeCatalog.LEGAL_WARRANTY);
    }
}
