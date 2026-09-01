package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
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
 * {@code Product} instances are built and wired by hand so the tree shape and
 * the touch configuration, not persistence, drive every branch. The order mode
 * and page size are read from a mocked {@link PosSettingsService}, or defaulted
 * when the service is left unwired (the null-guard arms).
 */
class ManualServiceTest {

    /**
     * Builds a {@link ProductFamily} with empty product and sub-family sets and
     * the default touch configuration.
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
        f.pinned = false;
        f.buttonSize = "NORMAL";
        f.displayOrder = 0;
        f.salesVolume = 0L;
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
     * Builds a {@link ManualService} whose settings return the given order mode
     * and page size (exercising the non-null arms of the settings guards).
     *
     * @param mode the display-order mode
     * @param perPage the page size
     * @return the wired service
     */
    private ManualService serviceWith(String mode, int perPage) {
        ManualService service = new ManualService();
        service.posSettings = mock(PosSettingsService.class);
        when(service.posSettings.touchDisplayOrder()).thenReturn(mode);
        when(service.posSettings.touchGroupsPerPage()).thenReturn(perPage);
        return service;
    }

    /**
     * Stubs {@code ProductFamily.findByCode(code)} on the given static mock to
     * resolve to the supplied family (or null).
     *
     * @param mocked the active PanacheEntityBase static mock
     * @param code the looked-up code
     * @param family the family to return, or null
     */
    private void stubFindByCode(MockedStatic<PanacheEntityBase> mocked, String code, ProductFamily family) {
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(family);
        mocked.when(() -> ProductFamily.find("code", code)).thenReturn(query);
    }

    /**
     * {@code getManualRootData} with no settings wired defaults to alphabetical
     * order (null-description first) and a single page: a child is excluded by
     * the child filter, an empty family by {@code hasManualProducts}, and the
     * three qualifying top families are ordered by description.
     */
    @Test
    void getManualRootDataDefaultsToAlphabeticalSinglePage() {
        ProductFamily bravo = fam(1L, "B", "Bravo");
        bravo.products = new HashSet<>(List.of(prod("BravoEan", "111", null)));
        ProductFamily child = fam(2L, "C", "Child");
        child.products = new HashSet<>(List.of(prod("ChildEan", "112", null)));
        bravo.productFamilies = new HashSet<>(List.of(child));
        ProductFamily alpha = fam(3L, "A", "Alpha");
        alpha.products = new HashSet<>(List.of(prod("AlphaEan", "113", null)));
        ProductFamily noName = fam(4L, "Z", null);
        noName.products = new HashSet<>(List.of(prod("ZEan", "114", null)));
        ProductFamily empty = fam(5L, "E", "Empty");
        List<ProductFamily> all = List.of(bravo, child, alpha, noName, empty);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = new ManualService().getManualRootData(1);
            assertEquals("Accueil", data.breadcrumb);
            assertTrue(data.isRoot);
            assertNull(data.parentUrl);
            assertEquals(1, data.page);
            assertEquals(1, data.totalPages);
            assertNull(data.prevUrl);
            assertNull(data.nextUrl);
            assertEquals(3, data.items.size());
            assertNull(data.items.get(0).label);
            assertEquals("/manual/cat/Z", data.items.get(0).url);
            assertTrue(data.items.get(0).isCategory);
            assertEquals("size-normal", data.items.get(0).sizeClass);
            assertFalse(data.items.get(0).pinned);
            assertEquals("Alpha", data.items.get(1).label);
            assertEquals("Bravo", data.items.get(2).label);
        }
    }

    /**
     * {@code getManualRootData} in CUSTOM order paginates the non-pinned groups
     * by the configured page size and clamps an in-range page: with one group
     * per page, requesting page two returns the second-ranked group and both
     * pager links.
     */
    @Test
    void getManualRootDataCustomOrderPaginates() {
        ProductFamily one = fam(1L, "O1", "One");
        one.products = new HashSet<>(List.of(prod("E1", "201", null)));
        one.displayOrder = 1;
        ProductFamily two = fam(2L, "O2", "Two");
        two.products = new HashSet<>(List.of(prod("E2", "202", null)));
        two.displayOrder = 2;
        ProductFamily three = fam(3L, "O3", "Three");
        three.products = new HashSet<>(List.of(prod("E3", "203", null)));
        three.displayOrder = 3;
        List<ProductFamily> all = List.of(three, one, two);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = serviceWith("CUSTOM", 1).getManualRootData(2);
            assertEquals(2, data.page);
            assertEquals(3, data.totalPages);
            assertEquals("/manual?page=1", data.prevUrl);
            assertEquals("/manual?page=3", data.nextUrl);
            assertEquals(1, data.items.size());
            assertEquals("Two", data.items.get(0).label);
        }
    }

    /**
     * {@code getManualRootData} in VOLUME order shows the pinned groups first
     * (descending volume), prepended to the non-pinned page, and clamps a page
     * beyond the last back to the last (here the single page).
     */
    @Test
    void getManualRootDataVolumeOrderPinnedFirstClampsHigh() {
        ProductFamily pinnedHigh = fam(1L, "PH", "PinnedHigh");
        pinnedHigh.products = new HashSet<>(List.of(prod("E1", "301", null)));
        pinnedHigh.pinned = true;
        pinnedHigh.salesVolume = 10L;
        pinnedHigh.buttonSize = "LARGE";
        ProductFamily pinnedLow = fam(2L, "PL", "PinnedLow");
        pinnedLow.products = new HashSet<>(List.of(prod("E2", "302", null)));
        pinnedLow.pinned = true;
        pinnedLow.salesVolume = 5L;
        ProductFamily restHigh = fam(3L, "RH", "RestHigh");
        restHigh.products = new HashSet<>(List.of(prod("E3", "303", null)));
        restHigh.salesVolume = 10L;
        ProductFamily restLow = fam(4L, "RL", "RestLow");
        restLow.products = new HashSet<>(List.of(prod("E4", "304", null)));
        restLow.salesVolume = 5L;
        List<ProductFamily> all = List.of(pinnedLow, pinnedHigh, restLow, restHigh);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = serviceWith("VOLUME", 2).getManualRootData(99);
            assertEquals(1, data.page);
            assertEquals(1, data.totalPages);
            assertNull(data.prevUrl);
            assertNull(data.nextUrl);
            assertEquals(4, data.items.size());
            assertEquals("PinnedHigh", data.items.get(0).label);
            assertTrue(data.items.get(0).pinned);
            assertEquals("size-large", data.items.get(0).sizeClass);
            assertEquals("PinnedLow", data.items.get(1).label);
            assertEquals("RestHigh", data.items.get(2).label);
            assertFalse(data.items.get(2).pinned);
            assertEquals("RestLow", data.items.get(3).label);
        }
    }

    /**
     * {@code getManualRootData} clamps a non-positive page up to one and maps
     * each button size to its CSS class, the unknown size falling to normal.
     */
    @Test
    void getManualRootDataClampsLowAndMapsSizes() {
        ProductFamily large = fam(1L, "A", "AlargeX");
        large.products = new HashSet<>(List.of(prod("E1", "401", null)));
        large.buttonSize = "LARGE";
        ProductFamily small = fam(2L, "B", "BsmallX");
        small.products = new HashSet<>(List.of(prod("E2", "402", null)));
        small.buttonSize = "SMALL";
        ProductFamily normal = fam(3L, "C", "CnormalX");
        normal.products = new HashSet<>(List.of(prod("E3", "403", null)));
        normal.buttonSize = "NORMAL";
        ProductFamily weird = fam(4L, "D", "DweirdX");
        weird.products = new HashSet<>(List.of(prod("E4", "404", null)));
        weird.buttonSize = "HUGE";
        List<ProductFamily> all = List.of(large, small, normal, weird);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = new ManualService().getManualRootData(0);
            assertEquals(1, data.page);
            assertEquals(4, data.items.size());
            assertEquals("size-large", data.items.get(0).sizeClass);
            assertEquals("size-small", data.items.get(1).sizeClass);
            assertEquals("size-normal", data.items.get(2).sizeClass);
            assertEquals("size-normal", data.items.get(3).sizeClass);
        }
    }

    /**
     * {@code getManualRootData} with every qualifying top family pinned leaves
     * the non-pinned pool empty, so the total-pages floor is one and only the
     * pinned tiles are shown.
     */
    @Test
    void getManualRootDataAllPinnedEmptyRest() {
        ProductFamily pinnedA = fam(1L, "A", "Aaa");
        pinnedA.products = new HashSet<>(List.of(prod("E1", "501", null)));
        pinnedA.pinned = true;
        ProductFamily pinnedB = fam(2L, "B", "Bbb");
        pinnedB.products = new HashSet<>(List.of(prod("E2", "502", null)));
        pinnedB.pinned = true;
        List<ProductFamily> all = List.of(pinnedA, pinnedB);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            ManualViewData data = new ManualService().getManualRootData(1);
            assertEquals(1, data.totalPages);
            assertNull(data.prevUrl);
            assertNull(data.nextUrl);
            assertEquals(2, data.items.size());
            assertTrue(data.items.get(0).pinned);
            assertTrue(data.items.get(1).pinned);
        }
    }

    /**
     * {@code getManualCategoryData} returns a bare non-root level with the
     * default breadcrumb and only the permanently pinned groups when the code
     * resolves to no family — the pinned filter here also exercises its three
     * exclusion arms (a child, a childless family without products, a
     * qualifying pinned family) and its inclusion arm.
     */
    @Test
    void getManualCategoryDataNotFoundStillShowsPinned() {
        ProductFamily pinnedQual = fam(1L, "PQ", "PinnedQual");
        pinnedQual.products = new HashSet<>(List.of(prod("E1", "601", null)));
        pinnedQual.pinned = true;
        ProductFamily pinnedChild = fam(2L, "PC", "PinnedChild");
        pinnedChild.products = new HashSet<>(List.of(prod("E2", "602", null)));
        pinnedChild.pinned = true;
        ProductFamily parentOfChild = fam(3L, "PP", "Parent");
        parentOfChild.products = new HashSet<>(List.of(prod("E3", "603", null)));
        parentOfChild.productFamilies = new HashSet<>(List.of(pinnedChild));
        ProductFamily pinnedEmpty = fam(4L, "PE", "PinnedEmpty");
        pinnedEmpty.pinned = true;
        ProductFamily notPinned = fam(5L, "NP", "NotPinned");
        notPinned.products = new HashSet<>(List.of(prod("E5", "605", null)));
        List<ProductFamily> all = List.of(pinnedQual, pinnedChild, parentOfChild, pinnedEmpty, notPinned);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubFindByCode(mocked, "NONE", null);
            ManualViewData data = new ManualService().getManualCategoryData("NONE");
            assertEquals("Accueil", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertEquals(1, data.items.size());
            assertEquals("PinnedQual", data.items.get(0).label);
            assertTrue(data.items.get(0).pinned);
        }
    }

    /**
     * {@code getManualCategoryData} lists the pinned groups, then qualifying
     * sub-families (a child qualifying only through a deeper descendant,
     * exercising the recursive true arm) in order, then the category's own
     * EAN-only products, skipping PLU products.
     */
    @Test
    void getManualCategoryDataListsPinnedChildrenThenProducts() {
        ProductFamily pinnedQual = fam(10L, "PQ", "PinnedQual");
        pinnedQual.products = new HashSet<>(List.of(prod("EP", "700", null)));
        pinnedQual.pinned = true;
        ProductFamily grandQual = fam(11L, "G1", "Grand");
        grandQual.products = new HashSet<>(List.of(prod("GrandEan", "701", null)));
        grandQual.productFamilies = null;
        ProductFamily childRecurse = fam(12L, "CR", "ChildRec");
        childRecurse.products = null;
        childRecurse.productFamilies = new HashSet<>(List.of(grandQual));
        ProductFamily family = fam(13L, "CAT", "Cat");
        family.productFamilies = new HashSet<>(List.of(childRecurse));
        family.products = new HashSet<>(List.of(prod("Ean", "702", null), prod("Plu", "703", "1234")));
        List<ProductFamily> all = List.of(pinnedQual);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubFindByCode(mocked, "CAT", family);
            ManualViewData data = new ManualService().getManualCategoryData("CAT");
            assertEquals("Accueil > Cat", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertEquals(1, data.page);
            assertEquals(1, data.totalPages);
            assertEquals(3, data.items.size());
            assertEquals("PinnedQual", data.items.get(0).label);
            assertTrue(data.items.get(0).pinned);
            ManualItem cat = data.items.get(1);
            assertTrue(cat.isCategory);
            assertEquals("ChildRec", cat.label);
            assertEquals("/manual/cat/CR", cat.url);
            assertNull(cat.ean);
            ManualItem leaf = data.items.get(2);
            assertFalse(leaf.isCategory);
            assertEquals("Ean", leaf.label);
            assertNull(leaf.url);
            assertEquals("702", leaf.ean);
            assertEquals("size-normal", leaf.sizeClass);
        }
    }

    /**
     * {@code getManualCategoryData} handles a found family whose product and
     * sub-family collections are both null, yielding no tiles (no pinned groups
     * either) but the family-qualified breadcrumb.
     */
    @Test
    void getManualCategoryDataHandlesNullCollections() {
        ProductFamily family = fam(20L, "CAT3", "Cat3");
        family.products = null;
        family.productFamilies = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(List.of());
            stubFindByCode(mocked, "CAT3", family);
            ManualViewData data = new ManualService().getManualCategoryData("CAT3");
            assertEquals("Accueil > Cat3", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }

    /**
     * {@code getManualCategoryData} excludes a sub-family whose whole branch
     * carries only PLU products: the recursive probe descends, its inner check
     * is false for every descendant, and the branch falls through to false, so
     * no tile is emitted. A sibling with both collections null exercises the
     * null sub-family arm reached after the products arm declines.
     */
    @Test
    void getManualCategoryDataExcludesBranchWithoutEanProducts() {
        ProductFamily grandFalse = fam(30L, "GF", "GrandFalse");
        grandFalse.products = new HashSet<>(List.of(prod("GrandPlu", "800", "5")));
        ProductFamily childFalse = fam(31L, "CF", "ChildFalse");
        childFalse.productFamilies = new HashSet<>(List.of(grandFalse));
        ProductFamily childNull = fam(32L, "CN", "ChildNull");
        childNull.products = null;
        childNull.productFamilies = null;
        ProductFamily family = fam(33L, "CAT4", "Cat4");
        family.products = null;
        family.productFamilies = new HashSet<>(List.of(childFalse, childNull));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(List.of());
            stubFindByCode(mocked, "CAT4", family);
            ManualViewData data = new ManualService().getManualCategoryData("CAT4");
            assertEquals("Accueil > Cat4", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }
}
