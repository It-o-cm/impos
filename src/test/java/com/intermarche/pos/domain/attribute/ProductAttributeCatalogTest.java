package com.intermarche.pos.domain.attribute;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductAttributeCatalog}, targeting 100% branch coverage.
 * <p>
 * Exercises the lookup helpers on both arms of every null and known/unknown
 * guard, and asserts the catalog's declared content. Each test is fully isolated
 * and asserts absolute expected values.
 */
class ProductAttributeCatalogTest {

    /**
     * The catalog declares the seven well-known behavioural attributes, in order,
     * every one a BOOL defaulting to false.
     */
    @Test
    void catalogDeclaresSevenWellKnownBooleanAttributes() {
        Assertions.assertEquals(7, ProductAttributeCatalog.CATALOG.size());
        Assertions.assertEquals(ProductAttributeCatalog.DISCOUNT_FORBIDDEN,
                ProductAttributeCatalog.CATALOG.get(0).code());
        Assertions.assertEquals(ProductAttributeCatalog.VAT_EXEMPT,
                ProductAttributeCatalog.CATALOG.get(1).code());
        Assertions.assertEquals(ProductAttributeCatalog.RECALL,
                ProductAttributeCatalog.CATALOG.get(2).code());
        for (ProductAttributeDef def : ProductAttributeCatalog.CATALOG) {
            Assertions.assertEquals(ProductAttributeType.BOOL, def.type());
            Assertions.assertEquals("false", def.defaultValue());
        }
    }

    /**
     * def returns the matching definition for a well-known code.
     */
    @Test
    void defResolvesKnownCode() {
        ProductAttributeDef def = ProductAttributeCatalog.def(ProductAttributeCatalog.VAT_EXEMPT);
        Assertions.assertNotNull(def);
        Assertions.assertEquals(ProductAttributeCatalog.VAT_EXEMPT, def.code());
    }

    /**
     * def returns null for a code the catalog does not declare (unknown arm).
     */
    @Test
    void defReturnsNullForUnknownCode() {
        Assertions.assertNull(ProductAttributeCatalog.def("SOME_GESCOM_ATTR"));
    }

    /**
     * def returns null for a null code (null arm), never throwing.
     */
    @Test
    void defReturnsNullForNullCode() {
        Assertions.assertNull(ProductAttributeCatalog.def(null));
    }

    /**
     * isKnown is true for a declared code and false for an undeclared one.
     */
    @Test
    void isKnownDistinguishesDeclaredCodes() {
        Assertions.assertTrue(ProductAttributeCatalog.isKnown(ProductAttributeCatalog.BULKY));
        Assertions.assertFalse(ProductAttributeCatalog.isKnown("SOME_GESCOM_ATTR"));
        Assertions.assertFalse(ProductAttributeCatalog.isKnown(null));
    }

    /**
     * defaultValue returns the declared default for a known code.
     */
    @Test
    void defaultValueReturnsDeclaredDefaultForKnownCode() {
        Assertions.assertEquals("false",
                ProductAttributeCatalog.defaultValue(ProductAttributeCatalog.PRICE_TO_ENTER));
    }

    /**
     * defaultValue returns null for an unknown code (unknown arm).
     */
    @Test
    void defaultValueReturnsNullForUnknownCode() {
        Assertions.assertNull(ProductAttributeCatalog.defaultValue("SOME_GESCOM_ATTR"));
    }
}
