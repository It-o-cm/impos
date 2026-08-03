package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.ui.ticket.ManualService.ManualItem;
import com.intermarche.pos.ui.ticket.ManualService.ManualViewData;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ManualService}.
 * <p>
 * The service walks the {@link ProductFamily} tree through the Panache
 * finders {@code listAll()} and {@code findByCode(...)} (the latter delegating
 * to {@code find("code", ...)}). Under plain {@code mvn test} those static
 * finders resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Real {@code ProductFamily}/
 * {@code Product} instances are built and wired by hand so the tree shape,
 * not persistence, drives every branch.
 */
class ManualServiceTest {

    /**
     * Builds a {@link ProductFamily} with empty product and sub-family sets.
     *
     * @param id the entity id, or null
     * @param code the family code
     * @param description the family description
     * @return the wired family
     */
    private ProductFamily fam(Long id, String code, String description) {
        ProductFamily f = new ProductFamily();
        f.id = id;
        f.code = code;
        f.description = description;
        f.products = new HashSet<>();
        f.productFamilies = new HashSet<>();
        return f;
    }

    /**
     * Builds a {@link Product}.
     *
     * @param name the product name
     * @param ean the product EAN
     * @param plu the product PLU, or null for an EAN-only product
     * @return the wired product
     */
    private Product prod(String name, String ean, String plu) {
        Product p = new Product();
        p.name = name;
        p.ean = ean;
        p.plu = plu;
        return p;
    }

    /**
     * {@code getManualRootData} keeps only top families (never a child of
     * another) that lead to an EAN-only product: the parent qualifies and is
     * added, the child is excluded by the child filter, and the empty family
     * is excluded by {@code hasManualProducts}.
     */
    @Test
    void getManualRootDataKeepsQualifyingTopFamiliesOnly() {
        ProductFamily parent = fam(1L, "P1", "Parent");
        parent.products = new HashSet<>(List.of(prod("EanProd", "111", null)));
        ProductFamily child = fam(2L, "C1", "Child");
        ProductFamily empty = fam(3L, "E1", "Empty");
        parent.productFamilies = new HashSet<>(List.of(child));
        List<ProductFamily> all = List.of(parent, child, empty);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = new ManualService().getManualRootData();
            assertEquals("Accueil", data.breadcrumb);
            assertTrue(data.isRoot);
            assertNull(data.parentUrl);
            assertEquals(1, data.items.size());
            ManualItem item = data.items.get(0);
            assertEquals("Parent", item.label);
            assertTrue(item.isCategory);
            assertEquals("/manual/cat/P1", item.url);
            assertNull(item.ean);
        }
    }

    /**
     * {@code getManualCategoryData} returns a bare non-root level with the
     * default breadcrumb when the code resolves to no family.
     */
    @Test
    void getManualCategoryDataReturnsEmptyLevelWhenFamilyNotFound() {
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            mocked.when(() -> ProductFamily.find("code", "NONE")).thenReturn(query);
            ManualViewData data = new ManualService().getManualCategoryData("NONE");
            assertEquals("Accueil", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }

    /**
     * {@code getManualCategoryData} lists qualifying sub-families first (a
     * child that qualifies only through a deeper descendant, exercising the
     * recursive true arm) then the category's own EAN-only products, skipping
     * PLU products.
     */
    @Test
    void getManualCategoryDataListsQualifyingChildrenThenEanProducts() {
        ProductFamily grandQual = fam(null, "G1", "Grand");
        grandQual.products = new HashSet<>(List.of(prod("GrandEan", "300", null)));
        grandQual.productFamilies = null;
        ProductFamily childRecurse = fam(null, "CR", "ChildRec");
        childRecurse.products = null;
        childRecurse.productFamilies = new HashSet<>(List.of(grandQual));
        ProductFamily family = fam(null, "CAT", "Cat");
        family.productFamilies = new HashSet<>(List.of(childRecurse));
        family.products = new HashSet<>(List.of(prod("Ean", "200", null), prod("Plu", "201", "1234")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(family);
            mocked.when(() -> ProductFamily.find("code", "CAT")).thenReturn(query);
            ManualViewData data = new ManualService().getManualCategoryData("CAT");
            assertEquals("Accueil > Cat", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertEquals(2, data.items.size());
            ManualItem cat = data.items.get(0);
            assertTrue(cat.isCategory);
            assertEquals("ChildRec", cat.label);
            assertEquals("/manual/cat/CR", cat.url);
            assertNull(cat.ean);
            ManualItem leaf = data.items.get(1);
            assertFalse(leaf.isCategory);
            assertEquals("Ean", leaf.label);
            assertNull(leaf.url);
            assertEquals("200", leaf.ean);
        }
    }

    /**
     * {@code getManualCategoryData} handles a found family whose product and
     * sub-family collections are both null, yielding no tiles but the
     * family-qualified breadcrumb.
     */
    @Test
    void getManualCategoryDataHandlesNullCollections() {
        ProductFamily family = fam(null, "CAT3", "Cat3");
        family.products = null;
        family.productFamilies = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(family);
            mocked.when(() -> ProductFamily.find("code", "CAT3")).thenReturn(query);
            ManualViewData data = new ManualService().getManualCategoryData("CAT3");
            assertEquals("Accueil > Cat3", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }

    /**
     * {@code getManualCategoryData} excludes a sub-family whose whole branch
     * carries only PLU products: the recursive probe descends, its inner
     * check is false for every descendant, and the branch falls through to
     * {@code false}, so no tile is emitted. A sibling with both collections
     * null exercises the null sub-family arm reached after the products arm
     * declines.
     */
    @Test
    void getManualCategoryDataExcludesBranchWithoutEanProducts() {
        ProductFamily grandFalse = fam(null, "GF", "GrandFalse");
        grandFalse.products = new HashSet<>(List.of(prod("GrandPlu", "400", "5")));
        ProductFamily childFalse = fam(null, "CF", "ChildFalse");
        childFalse.productFamilies = new HashSet<>(List.of(grandFalse));
        ProductFamily childNull = fam(null, "CN", "ChildNull");
        childNull.products = null;
        childNull.productFamilies = null;
        ProductFamily family = fam(null, "CAT4", "Cat4");
        family.products = null;
        family.productFamilies = new HashSet<>(List.of(childFalse, childNull));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(family);
            mocked.when(() -> ProductFamily.find("code", "CAT4")).thenReturn(query);
            ManualViewData data = new ManualService().getManualCategoryData("CAT4");
            assertEquals("Accueil > Cat4", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }
}
