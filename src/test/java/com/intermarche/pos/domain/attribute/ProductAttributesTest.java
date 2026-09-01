package com.intermarche.pos.domain.attribute;

import com.intermarche.pos.domain.Product;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributes}, targeting 100% branch coverage.
 * <p>
 * The resolver is pure and reads only the in-memory {@link Product#attributes}
 * map, so no Panache or CDI machinery is needed. Every null guard and every
 * present/absent branch of {@code raw} and {@code flag} is exercised on both
 * arms, and each named accessor is asserted true and false. Each test is fully
 * isolated and asserts absolute expected values.
 */
class ProductAttributesTest {

    /**
     * Builds a product carrying one attribute value.
     *
     * @param code the attribute code
     * @param value the raw value
     * @return the product
     */
    private Product productWith(String code, String value) {
        Product product = new Product();
        product.attributes.put(code, value);
        return product;
    }

    /**
     * raw returns the product's own value when the map carries it.
     */
    @Test
    void rawReturnsProductValueWhenPresent() {
        Product product = productWith(ProductAttributeCatalog.VAT_EXEMPT, "true");
        Assertions.assertEquals("true",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw falls back to the catalog default when the product carries no value
     * for a well-known code.
     */
    @Test
    void rawFallsBackToCatalogDefaultWhenAbsent() {
        Product product = new Product();
        Assertions.assertEquals("false",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw returns null for an unknown code the product does not carry (no
     * product value, no catalog default).
     */
    @Test
    void rawReturnsNullForUnknownAbsentCode() {
        Product product = new Product();
        Assertions.assertNull(ProductAttributes.raw(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * raw returns an unknown code's own value when the product carries it —
     * the open map stores attributes the register does not act upon.
     */
    @Test
    void rawReturnsUnknownCodeOwnValue() {
        Product product = productWith("SOME_GESCOM_ATTR", "42");
        Assertions.assertEquals("42", ProductAttributes.raw(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * raw tolerates a null product (null-product arm), yielding the catalog
     * default.
     */
    @Test
    void rawToleratesNullProduct() {
        Assertions.assertEquals("false",
                ProductAttributes.raw(null, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * raw tolerates a product whose attribute map is null (null-map arm),
     * yielding the catalog default.
     */
    @Test
    void rawToleratesNullMap() {
        Product product = new Product();
        product.attributes = null;
        Assertions.assertEquals("false",
                ProductAttributes.raw(product, ProductAttributeCatalog.VAT_EXEMPT));
    }

    /**
     * flag is true when the resolved value is "true".
     */
    @Test
    void flagIsTrueWhenValueIsTrue() {
        Product product = productWith(ProductAttributeCatalog.BULKY, "true");
        Assertions.assertTrue(ProductAttributes.flag(product, ProductAttributeCatalog.BULKY));
    }

    /**
     * flag is false when the resolved value is "false".
     */
    @Test
    void flagIsFalseWhenValueIsFalse() {
        Product product = productWith(ProductAttributeCatalog.BULKY, "false");
        Assertions.assertFalse(ProductAttributes.flag(product, ProductAttributeCatalog.BULKY));
    }

    /**
     * flag is false when the resolved value is null (unknown, uncarried code).
     */
    @Test
    void flagIsFalseWhenValueIsNull() {
        Product product = new Product();
        Assertions.assertFalse(ProductAttributes.flag(product, "SOME_GESCOM_ATTR"));
    }

    /**
     * discountForbidden reflects the DISCOUNT_FORBIDDEN attribute, both arms.
     */
    @Test
    void discountForbiddenReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.discountForbidden(
                productWith(ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true")));
        Assertions.assertFalse(ProductAttributes.discountForbidden(new Product()));
    }

    /**
     * vatExempt reflects the VAT_EXEMPT attribute, both arms.
     */
    @Test
    void vatExemptReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.vatExempt(
                productWith(ProductAttributeCatalog.VAT_EXEMPT, "true")));
        Assertions.assertFalse(ProductAttributes.vatExempt(new Product()));
    }

    /**
     * recall reflects the RECALL attribute, both arms.
     */
    @Test
    void recallReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.recall(
                productWith(ProductAttributeCatalog.RECALL, "true")));
        Assertions.assertFalse(ProductAttributes.recall(new Product()));
    }

    /**
     * mealVoucherEligible reflects the MEAL_VOUCHER_ELIGIBLE attribute, both arms.
     */
    @Test
    void mealVoucherEligibleReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.mealVoucherEligible(
                productWith(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE, "true")));
        Assertions.assertFalse(ProductAttributes.mealVoucherEligible(new Product()));
    }

    /**
     * bulky reflects the BULKY attribute, both arms.
     */
    @Test
    void bulkyReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.bulky(
                productWith(ProductAttributeCatalog.BULKY, "true")));
        Assertions.assertFalse(ProductAttributes.bulky(new Product()));
    }

    /**
     * priceToEnter reflects the PRICE_TO_ENTER attribute, both arms.
     */
    @Test
    void priceToEnterReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.priceToEnter(
                productWith(ProductAttributeCatalog.PRICE_TO_ENTER, "true")));
        Assertions.assertFalse(ProductAttributes.priceToEnter(new Product()));
    }

    /**
     * quantityToEnter reflects the QUANTITY_TO_ENTER attribute, both arms.
     */
    @Test
    void quantityToEnterReflectsAttribute() {
        Assertions.assertTrue(ProductAttributes.quantityToEnter(
                productWith(ProductAttributeCatalog.QUANTITY_TO_ENTER, "true")));
        Assertions.assertFalse(ProductAttributes.quantityToEnter(new Product()));
    }
}
