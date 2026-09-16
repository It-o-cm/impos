package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.ticket.ManualService.ManualItem;
import com.intermarche.pos.ui.ticket.ManualService.ManualViewData;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import com.intermarche.pos.domain.setting.TouchGroupSetting;

/**
 * Unit tests for {@link ManualService}.
 * <p>
 * The service reads the family tree through {@link ManualRepository}, which is the
 * only part of the screen needing a database. Here it is replaced by an in-memory
 * implementation built from the very {@link ProductFamily} graph each test wires by
 * hand, so the fake answers what a database would answer of that graph and the tests
 * exercise the real subject: which branches qualify, in what order, how the tiles are
 * paged and how many a page may hold. The two Panache finders the service still calls
 * directly, {@code listAll()} and {@code findByCode(...)}, are intercepted with
 * {@link org.mockito.Mockito#mockStatic}. The order mode and page size come from a
 * mocked {@link PosSettingsService}, or are defaulted when it is left unwired.
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
        touches.put(code, TouchGroupSetting.defaults(code));
        return f;
    }

    /**
     * The touch configuration this test administers, by group code. It stands
     * where the four fields used to sit on the family itself.
     */
    private final Map<String, TouchGroupSetting> touches = new HashMap<>();

    /**
     * Returns the touch row of a family, so a test can administer its pinning,
     * size, rank or volume.
     *
     * @param family the family to configure
     * @return its touch row
     */
    private TouchGroupSetting touch(ProductFamily family) {
        return touches.computeIfAbsent(family.code, TouchGroupSetting::defaults);
    }

    /**
     * Stubs the touch referential on an active static mock so the service reads
     * what this test administered.
     *
     * @param mocked the active PanacheEntityBase static mock
     */
    private void stubTouches(MockedStatic<PanacheEntityBase> mocked) {
        mocked.when(() -> TouchGroupSetting.list("order by familyCode"))
                .thenReturn(new ArrayList<>(touches.values()));
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
     * Builds the repository the service reads through, answering from a graph held in
     * memory exactly what the queries would answer from a database: parents, families
     * holding an EAN-only product, and one ordered page of such products.
     *
     * @param world every family the "database" knows
     * @return the fake repository
     */
    private ManualRepository repositoryOf(List<ProductFamily> world) {
        return new ManualRepository() {

            /** {@inheritDoc} */
            @Override
            public Map<Long, Long> parentByChild() {
                Map<Long, Long> parents = new HashMap<>();
                for (ProductFamily f : world) {
                    if (f.productFamilies == null) {
                        continue;
                    }
                    for (ProductFamily child : f.productFamilies) {
                        parents.put(child.id, f.id);
                    }
                }
                return parents;
            }

            /** {@inheritDoc} */
            @Override
            public Set<Long> familiesHoldingProducts() {
                Set<Long> holders = new HashSet<>();
                for (ProductFamily f : world) {
                    if (!eanProducts(f).isEmpty()) {
                        holders.add(f.id);
                    }
                }
                return holders;
            }

            /** {@inheritDoc} */
            @Override
            public long countProducts(Long familyId) {
                return eanProducts(byId(familyId)).size();
            }

            /** {@inheritDoc} */
            @Override
            public List<Product> products(Long familyId, int offset, int limit) {
                List<Product> ordered = eanProducts(byId(familyId));
                int from = Math.min(offset, ordered.size());
                return new ArrayList<>(ordered.subList(from, Math.min(from + limit, ordered.size())));
            }

            /**
             * Finds a family of the world by id.
             *
             * @param familyId the id looked for
             * @return the family, or null when the world does not hold it
             */
            private ProductFamily byId(Long familyId) {
                for (ProductFamily f : world) {
                    if (f.id != null && f.id.equals(familyId)) {
                        return f;
                    }
                }
                return null;
            }

            /**
             * The EAN-only products of a family, in the order the query imposes.
             *
             * @param family the family, possibly null or holding no collection
             * @return its EAN-only products, ordered by name then EAN
             */
            private List<Product> eanProducts(ProductFamily family) {
                List<Product> ordered = new ArrayList<>();
                if (family == null || family.products == null) {
                    return ordered;
                }
                for (Product p : family.products) {
                    if (p.plu == null) {
                        ordered.add(p);
                    }
                }
                ordered.sort(Comparator.comparing((Product p) -> p.name).thenComparing(p -> p.ean));
                return ordered;
            }
        };
    }

    /**
     * Builds a service reading the given world, with no settings wired so the
     * null-guard arms of the order mode and page size are exercised.
     *
     * @param world every family the "database" knows
     * @return the wired service
     */
    private ManualService serviceOver(List<ProductFamily> world) {
        ManualService service = new ManualService();
        service.repository = repositoryOf(world);
        return service;
    }

    /**
     * Builds a service whose settings return the given order mode and page size
     * (exercising the non-null arms of the settings guards).
     *
     * @param mode the display-order mode
     * @param perPage the page size
     * @param world every family the "database" knows
     * @return the wired service
     */
    private ManualService serviceWith(String mode, int perPage, List<ProductFamily> world) {
        ManualService service = serviceOver(world);
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
     * the child-id arm, an empty family by the qualifying arm, and the three
     * qualifying top families are ordered by description.
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
            stubTouches(mocked);
            ManualViewData data = serviceOver(all).getManualRootData(1);
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
     * A family qualifies through a DEEP descendant, not only a direct child: the
     * qualifying answer is carried up the whole parent chain.
     */
    @Test
    void getManualRootDataQualifiesThroughDeepDescendant() {
        ProductFamily grand = fam(1L, "G", "Grand");
        grand.products = new HashSet<>(List.of(prod("DeepEan", "901", null)));
        ProductFamily middle = fam(2L, "M", "Middle");
        middle.productFamilies = new HashSet<>(List.of(grand));
        ProductFamily top = fam(3L, "T", "Top");
        top.productFamilies = new HashSet<>(List.of(middle));
        List<ProductFamily> all = List.of(top, middle, grand);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceOver(all).getManualRootData(1);
            assertEquals(1, data.items.size());
            assertEquals("Top", data.items.get(0).label);
        }
    }

    /**
     * A cycle in the parent chain terminates instead of looping: the walk upwards
     * stops as soon as an ancestor is already known to qualify.
     */
    @Test
    void getManualRootDataSurvivesAParentCycle() {
        ProductFamily first = fam(1L, "F1", "First");
        first.products = new HashSet<>(List.of(prod("CycleEan", "902", null)));
        ProductFamily second = fam(2L, "F2", "Second");
        first.productFamilies = new HashSet<>(List.of(second));
        second.productFamilies = new HashSet<>(List.of(first));
        List<ProductFamily> all = List.of(first, second);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceOver(all).getManualRootData(1);
            assertTrue(data.items.isEmpty());
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
        touch(one).displayOrder = 1;
        ProductFamily two = fam(2L, "O2", "Two");
        two.products = new HashSet<>(List.of(prod("E2", "202", null)));
        touch(two).displayOrder = 2;
        ProductFamily three = fam(3L, "O3", "Three");
        three.products = new HashSet<>(List.of(prod("E3", "203", null)));
        touch(three).displayOrder = 3;
        List<ProductFamily> all = List.of(three, one, two);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceWith("CUSTOM", 1, all).getManualRootData(2);
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
        touch(pinnedHigh).pinned = true;
        touch(pinnedHigh).salesVolume = 10L;
        touch(pinnedHigh).buttonSize = "LARGE";
        ProductFamily pinnedLow = fam(2L, "PL", "PinnedLow");
        pinnedLow.products = new HashSet<>(List.of(prod("E2", "302", null)));
        touch(pinnedLow).pinned = true;
        touch(pinnedLow).salesVolume = 5L;
        ProductFamily restHigh = fam(3L, "RH", "RestHigh");
        restHigh.products = new HashSet<>(List.of(prod("E3", "303", null)));
        touch(restHigh).salesVolume = 10L;
        ProductFamily restLow = fam(4L, "RL", "RestLow");
        restLow.products = new HashSet<>(List.of(prod("E4", "304", null)));
        touch(restLow).salesVolume = 5L;
        List<ProductFamily> all = List.of(pinnedLow, pinnedHigh, restLow, restHigh);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceWith("VOLUME", 2, all).getManualRootData(99);
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
     * The page budget deducts what is permanent: with three pinned groups, the
     * eleven free tiles leave eight for the page, so nine remaining groups no
     * longer fit on one page even though the configured size is twelve.
     */
    @Test
    void getManualRootDataBudgetDeductsPinnedAndFixedTile() {
        List<ProductFamily> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ProductFamily pinned = fam((long) i, "P" + i, "Pinned" + i);
            pinned.products = new HashSet<>(List.of(prod("PE" + i, "10" + i, null)));
            touch(pinned).pinned = true;
            all.add(pinned);
        }
        for (int i = 0; i < 9; i++) {
            ProductFamily rest = fam((long) (10 + i), "R" + i, "Rest" + i);
            rest.products = new HashSet<>(List.of(prod("RE" + i, "20" + i, null)));
            all.add(rest);
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceWith("ALPHA", 12, all).getManualRootData(1);
            assertEquals(2, data.totalPages);
            assertEquals(11, data.items.size());
            assertEquals("/manual?page=2", data.nextUrl);
        }
    }

    /**
     * A till pinned to saturation still turns pages: the budget floors at one
     * tile instead of falling to zero or below.
     */
    @Test
    void getManualRootDataBudgetFloorsAtOneTile() {
        List<ProductFamily> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ProductFamily pinned = fam((long) i, "P" + i, "Pinned" + String.format("%02d", i));
            pinned.products = new HashSet<>(List.of(prod("PE" + i, "30" + i, null)));
            touch(pinned).pinned = true;
            all.add(pinned);
        }
        ProductFamily restA = fam(90L, "RA", "RestA");
        restA.products = new HashSet<>(List.of(prod("RAE", "401", null)));
        ProductFamily restB = fam(91L, "RB", "RestB");
        restB.products = new HashSet<>(List.of(prod("RBE", "402", null)));
        all.add(restA);
        all.add(restB);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceWith("ALPHA", 12, all).getManualRootData(1);
            assertEquals(2, data.totalPages);
            assertEquals(13, data.items.size());
            assertEquals("RestA", data.items.get(12).label);
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
        touch(large).buttonSize = "LARGE";
        ProductFamily small = fam(2L, "B", "BsmallX");
        small.products = new HashSet<>(List.of(prod("E2", "402", null)));
        touch(small).buttonSize = "SMALL";
        ProductFamily normal = fam(3L, "C", "CnormalX");
        normal.products = new HashSet<>(List.of(prod("E3", "403", null)));
        touch(normal).buttonSize = "NORMAL";
        ProductFamily weird = fam(4L, "D", "DweirdX");
        weird.products = new HashSet<>(List.of(prod("E4", "404", null)));
        touch(weird).buttonSize = "HUGE";
        List<ProductFamily> all = List.of(large, small, normal, weird);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceOver(all).getManualRootData(0);
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
        touch(pinnedA).pinned = true;
        ProductFamily pinnedB = fam(2L, "B", "Bbb");
        pinnedB.products = new HashSet<>(List.of(prod("E2", "502", null)));
        touch(pinnedB).pinned = true;
        List<ProductFamily> all = List.of(pinnedA, pinnedB);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            ManualViewData data = serviceOver(all).getManualRootData(1);
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
        touch(pinnedQual).pinned = true;
        ProductFamily pinnedChild = fam(2L, "PC", "PinnedChild");
        pinnedChild.products = new HashSet<>(List.of(prod("E2", "602", null)));
        touch(pinnedChild).pinned = true;
        ProductFamily parentOfChild = fam(3L, "PP", "Parent");
        parentOfChild.products = new HashSet<>(List.of(prod("E3", "603", null)));
        parentOfChild.productFamilies = new HashSet<>(List.of(pinnedChild));
        ProductFamily pinnedEmpty = fam(4L, "PE", "PinnedEmpty");
        touch(pinnedEmpty).pinned = true;
        ProductFamily notPinned = fam(5L, "NP", "NotPinned");
        notPinned.products = new HashSet<>(List.of(prod("E5", "605", null)));
        List<ProductFamily> all = List.of(pinnedQual, pinnedChild, parentOfChild, pinnedEmpty, notPinned);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            stubFindByCode(mocked, "NONE", null);
            ManualViewData data = serviceOver(all).getManualCategoryData("NONE", 1);
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
     * sub-families (a child qualifying only through a deeper descendant), then
     * the category's own EAN-only products, skipping PLU products.
     */
    @Test
    void getManualCategoryDataListsPinnedChildrenThenProducts() {
        ProductFamily pinnedQual = fam(10L, "PQ", "PinnedQual");
        pinnedQual.products = new HashSet<>(List.of(prod("EP", "700", null)));
        touch(pinnedQual).pinned = true;
        ProductFamily grandQual = fam(11L, "G1", "Grand");
        grandQual.products = new HashSet<>(List.of(prod("GrandEan", "701", null)));
        grandQual.productFamilies = null;
        ProductFamily childRecurse = fam(12L, "CR", "ChildRec");
        childRecurse.products = null;
        childRecurse.productFamilies = new HashSet<>(List.of(grandQual));
        ProductFamily family = fam(13L, "CAT", "Cat");
        family.productFamilies = new HashSet<>(List.of(childRecurse));
        family.products = new HashSet<>(List.of(prod("Ean", "702", null), prod("Plu", "703", "1234")));
        List<ProductFamily> all = List.of(pinnedQual, childRecurse, grandQual, family);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            stubFindByCode(mocked, "CAT", family);
            ManualViewData data = serviceOver(all).getManualCategoryData("CAT", 1);
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
     * Sub-families and products are paged as ONE sequence: with two tiles per
     * page and one qualifying sub-family, the first page carries that sub-family
     * and the first product, and the second page the remaining products.
     */
    @Test
    void getManualCategoryDataPagesChildrenAndProductsAsOneSequence() {
        ProductFamily child = fam(40L, "CH", "Child");
        child.products = new HashSet<>(List.of(prod("ChildEan", "800", null)));
        ProductFamily family = fam(41L, "CAT5", "Cat5");
        family.productFamilies = new HashSet<>(List.of(child));
        family.products = new HashSet<>(List.of(
                prod("Article A", "801", null),
                prod("Article B", "802", null),
                prod("Article C", "803", null)));
        List<ProductFamily> all = List.of(family, child);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            stubFindByCode(mocked, "CAT5", family);
            ManualService service = serviceWith("ALPHA", 2, all);
            ManualViewData first = service.getManualCategoryData("CAT5", 1);
            assertEquals(1, first.page);
            assertEquals(2, first.totalPages);
            assertNull(first.prevUrl);
            assertEquals("/manual/cat/CAT5?page=2", first.nextUrl);
            assertEquals(2, first.items.size());
            assertTrue(first.items.get(0).isCategory);
            assertEquals("Child", first.items.get(0).label);
            assertEquals("Article A", first.items.get(1).label);
            ManualViewData second = service.getManualCategoryData("CAT5", 2);
            assertEquals(2, second.page);
            assertEquals("/manual/cat/CAT5?page=1", second.prevUrl);
            assertNull(second.nextUrl);
            assertEquals(2, second.items.size());
            assertEquals("Article B", second.items.get(0).label);
            assertEquals("Article C", second.items.get(1).label);
        }
    }

    /**
     * A page beyond the last is clamped back to the last, and a non-positive one
     * up to the first, on the category level too.
     */
    @Test
    void getManualCategoryDataClampsPages() {
        ProductFamily family = fam(50L, "CAT6", "Cat6");
        family.products = new HashSet<>(List.of(
                prod("Article A", "810", null), prod("Article B", "811", null)));
        List<ProductFamily> all = List.of(family);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            stubFindByCode(mocked, "CAT6", family);
            ManualService service = serviceWith("ALPHA", 1, all);
            assertEquals(2, service.getManualCategoryData("CAT6", 99).page);
            assertEquals("Article B", service.getManualCategoryData("CAT6", 99).items.get(0).label);
            assertEquals(1, service.getManualCategoryData("CAT6", 0).page);
            assertEquals("Article A", service.getManualCategoryData("CAT6", 0).items.get(0).label);
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
        List<ProductFamily> all = List.of(family);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(List.of());
            stubFindByCode(mocked, "CAT3", family);
            ManualViewData data = serviceOver(all).getManualCategoryData("CAT3", 1);
            assertEquals("Accueil > Cat3", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertEquals(1, data.totalPages);
            assertTrue(data.items.isEmpty());
        }
    }

    /**
     * {@code getManualCategoryData} excludes a sub-family whose whole branch
     * carries only PLU products, and a sibling holding nothing at all.
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
        List<ProductFamily> all = List.of(family, childFalse, childNull, grandFalse);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(List.of());
            stubFindByCode(mocked, "CAT4", family);
            ManualViewData data = serviceOver(all).getManualCategoryData("CAT4", 1);
            assertEquals("Accueil > Cat4", data.breadcrumb);
            assertFalse(data.isRoot);
            assertEquals("/manual", data.parentUrl);
            assertTrue(data.items.isEmpty());
        }
    }

    /**
     * {@code getManualCategoryData} on a page whose whole window falls inside the
     * sub-families reaches the false arm of the product guard (line 222): the
     * page is filled by children alone and no product range is opened even
     * though the category holds articles on a later page.
     */
    @Test
    void getManualCategoryDataProductGuardFalseWhenPageIsAllChildren() {
        ProductFamily childA = fam(60L, "CA", "ChildA");
        childA.products = new HashSet<>(List.of(prod("ChildAEan", "820", null)));
        ProductFamily childB = fam(61L, "CB", "ChildB");
        childB.products = new HashSet<>(List.of(prod("ChildBEan", "821", null)));
        ProductFamily family = fam(62L, "CAT7", "Cat7");
        family.productFamilies = new HashSet<>(List.of(childA, childB));
        family.products = new HashSet<>(List.of(prod("Article A", "822", null)));
        List<ProductFamily> all = List.of(family, childA, childB);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.listAll()).thenReturn(all);
            stubTouches(mocked);
            stubFindByCode(mocked, "CAT7", family);
            ManualViewData data = serviceWith("ALPHA", 1, all).getManualCategoryData("CAT7", 1);
            assertEquals(1, data.page);
            assertEquals(3, data.totalPages);
            assertNull(data.prevUrl);
            assertEquals("/manual/cat/CAT7?page=2", data.nextUrl);
            assertEquals(1, data.items.size());
            assertTrue(data.items.get(0).isCategory);
            assertEquals("ChildA", data.items.get(0).label);
        }
    }
}
