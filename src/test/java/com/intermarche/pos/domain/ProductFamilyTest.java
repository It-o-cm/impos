package com.intermarche.pos.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductFamily}, targeting 100% branch coverage.
 * <p>
 * The four static finders resolve the Panache {@code find} query, which under
 * plain {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. Instance methods (flag management, checksum) and the
 * package-private recursion helper are exercised directly on plain instances;
 * every compound guard, ternary and short-circuit is covered on both arms.
 * Each test is fully isolated and asserts absolute expected values.
 */
class ProductFamilyTest {

    /**
     * Builds a ProductFamily with the given id and flags string, leaving the
     * remaining fields at their declared defaults.
     *
     * @param id    the entity id, possibly null
     * @param flags the comma-separated flags string, possibly null
     * @return the configured family
     */
    private ProductFamily family(Long id, String flags) {
        ProductFamily f = new ProductFamily();
        f.id = id;
        f.flags = flags;
        return f;
    }

    /**
     * Builds a plain Product with the given id.
     *
     * @param id the product id, possibly null
     * @return the configured product
     */
    private Product product(Long id) {
        Product p = new Product();
        p.id = id;
        return p;
    }

    /**
     * A fresh family exposes empty, non-null child collections.
     */
    @Test
    void fieldDefaults() {
        ProductFamily f = new ProductFamily();
        Assertions.assertNotNull(f.products);
        Assertions.assertTrue(f.products.isEmpty());
        Assertions.assertNotNull(f.productFamilies);
        Assertions.assertTrue(f.productFamilies.isEmpty());
    }

    /**
     * findByCode returns the single result of the Panache code query.
     */
    @Test
    void findByCodeReturnsFirstResult() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily expected = family(1L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> ProductFamily.find("code", "FRUITS")).thenReturn(query);
            Assertions.assertSame(expected, ProductFamily.findByCode("FRUITS"));
        }
    }

    /**
     * findAllFamiliesForProduct returns an empty set for a null product
     * (first OR arm true).
     */
    @Test
    void findAllFamiliesForProductNull() {
        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(null);
        Assertions.assertTrue(result.isEmpty());
    }

    /**
     * findAllFamiliesForProduct returns an empty set for a product without an
     * id (first OR arm false, second OR arm true).
     */
    @Test
    void findAllFamiliesForProductNullId() {
        Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(null));
        Assertions.assertTrue(result.isEmpty());
    }

    /**
     * findAllFamiliesForProduct returns an empty set when the product has no
     * direct parents (both guard arms false, for loop not entered).
     */
    @Test
    void findAllFamiliesForProductNoDirectParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(query);
            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(1L));
            Assertions.assertTrue(result.isEmpty());
        }
    }

    /**
     * findAllFamiliesForProduct walks up the hierarchy, collecting direct
     * parents and their ancestors (for loop entered, recursion followed).
     */
    @Test
    void findAllFamiliesForProductWithHierarchy() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, null);
            ProductFamily grandParent = family(20L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfDirect = mock(PanacheQuery.class);
            when(parentsOfDirect.list()).thenReturn(List.of(grandParent));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfGrand = mock(PanacheQuery.class);
            when(parentsOfGrand.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsOfDirect);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 20L))
                    .thenReturn(parentsOfGrand);
            Set<ProductFamily> result = ProductFamily.findAllFamiliesForProduct(product(1L));
            Assertions.assertEquals(Set.of(direct, grandParent), result);
        }
    }

    /**
     * findAncestorsRecursive returns immediately and leaves the accumulator
     * untouched when the family is already present (contains arm true).
     */
    @Test
    void findAncestorsRecursiveAlreadyVisited() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily f = family(10L, null);
            Set<ProductFamily> ancestors = new HashSet<>();
            ancestors.add(f);
            ProductFamily.findAncestorsRecursive(f, ancestors);
            Assertions.assertEquals(Set.of(f), ancestors);
            panache.verifyNoInteractions();
        }
    }

    /**
     * findAncestorsRecursive adds a family with no parents (contains arm
     * false, for loop not entered).
     */
    @Test
    void findAncestorsRecursiveNoParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily f = family(10L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(query);
            Set<ProductFamily> ancestors = new HashSet<>();
            ProductFamily.findAncestorsRecursive(f, ancestors);
            Assertions.assertEquals(Set.of(f), ancestors);
        }
    }

    /**
     * findAncestorsRecursive recurses into each parent (contains arm false,
     * for loop entered).
     */
    @Test
    void findAncestorsRecursiveWithParents() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily child = family(10L, null);
            ProductFamily parent = family(20L, null);
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfChild = mock(PanacheQuery.class);
            when(parentsOfChild.list()).thenReturn(List.of(parent));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsOfParent = mock(PanacheQuery.class);
            when(parentsOfParent.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsOfChild);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 20L))
                    .thenReturn(parentsOfParent);
            Set<ProductFamily> ancestors = new HashSet<>();
            ProductFamily.findAncestorsRecursive(child, ancestors);
            Assertions.assertEquals(Set.of(child, parent), ancestors);
        }
    }

    /**
     * productHasFlag returns false for a null product (first OR arm true).
     */
    @Test
    void productHasFlagNullProduct() {
        Assertions.assertFalse(ProductFamily.productHasFlag(null, "ORGANIC"));
    }

    /**
     * productHasFlag returns false for a null flag (second OR arm true).
     */
    @Test
    void productHasFlagNullFlag() {
        Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), null));
    }

    /**
     * productHasFlag returns false for a blank flag (third OR arm true).
     */
    @Test
    void productHasFlagBlankFlag() {
        Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), "   "));
    }

    /**
     * productHasFlag returns true when a family in the hierarchy carries the
     * flag (guard arms false, inner if arm true).
     */
    @Test
    void productHasFlagFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, "ORGANIC");
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsQuery = mock(PanacheQuery.class);
            when(parentsQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsQuery);
            Assertions.assertTrue(ProductFamily.productHasFlag(product(1L), "ORGANIC"));
        }
    }

    /**
     * productHasFlag returns false when no family in the hierarchy carries the
     * flag (inner if arm false, loop exhausted).
     */
    @Test
    void productHasFlagNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            ProductFamily direct = family(10L, "SEASONAL");
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> directQuery = mock(PanacheQuery.class);
            when(directQuery.list()).thenReturn(List.of(direct));
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> parentsQuery = mock(PanacheQuery.class);
            when(parentsQuery.list()).thenReturn(List.of());
            panache.when(() -> ProductFamily.find(
                    "select pf from ProductFamily pf join pf.products p where p.id = ?1", 1L))
                    .thenReturn(directQuery);
            panache.when(() -> ProductFamily.find(
                    "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1", 10L))
                    .thenReturn(parentsQuery);
            Assertions.assertFalse(ProductFamily.productHasFlag(product(1L), "ORGANIC"));
        }
    }

    /**
     * addFlag ignores a null token (first OR arm true).
     */
    @Test
    void addFlagNullToken() {
        ProductFamily f = family(1L, "A");
        f.addFlag(null);
        Assertions.assertEquals("A", f.flags);
    }

    /**
     * addFlag ignores a blank token (second OR arm true).
     */
    @Test
    void addFlagBlankToken() {
        ProductFamily f = family(1L, "A");
        f.addFlag("   ");
        Assertions.assertEquals("A", f.flags);
    }

    /**
     * addFlag appends a new, trimmed token (guard false, add arm true).
     */
    @Test
    void addFlagNewToken() {
        ProductFamily f = family(1L, "EXISTING");
        f.addFlag("  NEW  ");
        Assertions.assertEquals(Set.of("EXISTING", "NEW"),
                Set.of(f.flags.split(",")));
    }

    /**
     * addFlag is a no-op when the token is already present (add arm false).
     */
    @Test
    void addFlagExistingToken() {
        ProductFamily f = family(1L, "EXISTING");
        f.addFlag("EXISTING");
        Assertions.assertEquals("EXISTING", f.flags);
    }

    /**
     * removeFlag returns false for a null token (null arm true).
     */
    @Test
    void removeFlagNullToken() {
        ProductFamily f = family(1L, "A");
        Assertions.assertFalse(f.removeFlag(null));
        Assertions.assertEquals("A", f.flags);
    }

    /**
     * removeFlag nulls the flags string when the last token is removed
     * (remove arm true, isEmpty arm true).
     */
    @Test
    void removeFlagLastToken() {
        ProductFamily f = family(1L, "ONLY");
        Assertions.assertTrue(f.removeFlag("ONLY"));
        Assertions.assertNull(f.flags);
    }

    /**
     * removeFlag keeps the remaining tokens when others survive
     * (remove arm true, isEmpty arm false).
     */
    @Test
    void removeFlagKeepsOthers() {
        ProductFamily f = family(1L, "A,B");
        Assertions.assertTrue(f.removeFlag("A"));
        Assertions.assertEquals("B", f.flags);
    }

    /**
     * removeFlag returns false when the token is absent (remove arm false).
     */
    @Test
    void removeFlagAbsentToken() {
        ProductFamily f = family(1L, "A,B");
        Assertions.assertFalse(f.removeFlag("Z"));
        Assertions.assertEquals(Set.of("A", "B"), Set.of(f.flags.split(",")));
    }

    /**
     * hasFlag returns false for a null token (first OR arm true).
     */
    @Test
    void hasFlagNullToken() {
        Assertions.assertFalse(family(1L, "A").hasFlag(null));
    }

    /**
     * hasFlag returns false for a blank token (second OR arm true).
     */
    @Test
    void hasFlagBlankToken() {
        Assertions.assertFalse(family(1L, "A").hasFlag("   "));
    }

    /**
     * hasFlag returns false when the flags string is null (third OR arm true).
     */
    @Test
    void hasFlagNullFlags() {
        Assertions.assertFalse(family(1L, null).hasFlag("A"));
    }

    /**
     * hasFlag returns false when the flags string is blank (fourth OR arm true).
     */
    @Test
    void hasFlagBlankFlags() {
        Assertions.assertFalse(family(1L, "   ").hasFlag("A"));
    }

    /**
     * hasFlag returns true for a present token, ignoring empty tokens in the
     * stored string (all guard arms false, filter both arms, contains true).
     */
    @Test
    void hasFlagPresent() {
        Assertions.assertTrue(family(1L, "A,,B").hasFlag(" A "));
    }

    /**
     * hasFlag returns false for an absent token (contains false).
     */
    @Test
    void hasFlagAbsent() {
        Assertions.assertFalse(family(1L, "A,B").hasFlag("C"));
    }

    /**
     * getChecksum hashes code, description and flags, excluding children.
     */
    @Test
    void getChecksum() {
        ProductFamily f = new ProductFamily();
        f.code = "FRUITS";
        f.description = "Fruits and vegetables";
        f.flags = "ORGANIC";
        Assertions.assertEquals(Objects.hash("FRUITS", "Fruits and vegetables", "ORGANIC"),
                f.getChecksum());
    }
}
