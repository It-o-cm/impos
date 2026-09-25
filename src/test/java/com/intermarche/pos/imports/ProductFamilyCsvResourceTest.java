package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductFamilyCsvResource}.
 * <p>
 * The resource is the {@link ProductFamily}-specific concretion of the
 * abstract {@link ImporterCsvResource}: for a chunk it bulk-fetches the
 * families, products and sub-families the file names, then for each line
 * creates a family or re-attaches and compares checksums on an existing one,
 * rebuilding its product and sub-family links from the file. Every Panache
 * finder resolves to {@link PanacheEntityBase} under plain {@code mvn test}
 * and is intercepted with {@link org.mockito.Mockito#mockStatic} inside
 * try-with-resources blocks; {@code Panache.getEntityManager()} is stubbed
 * the same way so {@code persist} is observable. Private helpers reached only
 * through the public surface are exercised there; {@code productsByEan} is
 * reached by reflection to hit its batch-boundary. No database and no Quarkus
 * context is booted.
 */
class ProductFamilyCsvResourceTest {

    /** Context-map key under which the pre-fetched product map is stored. */
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";

    /** Context-map key under which the pre-fetched sub-family map is stored. */
    private static final String CTX_SUB_FAMILIES = "__CTX_SUB_FAMILIES__";

    /** The resource under test. */
    private final ProductFamilyCsvResource resource = new ProductFamilyCsvResource();

    /**
     * Builds a parsed line over the five declared columns.
     *
     * @param code the CODE cell (natural key)
     * @param description the DESCRIPTION cell
     * @param flags the FLAGS cell
     * @param eans the PRODUCT_EANS cell
     * @param subCodes the SUBFAMILY_CODES cell
     * @return the parsed line
     */
    private ImporterCsvResource.LineData line(String code, String description, String flags, String eans, String subCodes) {
        Map<String, Integer> header = new HashMap<>();
        header.put(ProductFamilyCsvResource.COL_CODE, 0);
        header.put(ProductFamilyCsvResource.COL_DESCRIPTION, 1);
        header.put(ProductFamilyCsvResource.COL_FLAGS, 2);
        header.put(ProductFamilyCsvResource.COL_PRODUCT_EANS, 3);
        header.put(ProductFamilyCsvResource.COL_SUBFAMILY_CODES, 4);
        return new ImporterCsvResource.LineData(1, header,
                new String[]{code, description, flags, eans, subCodes}, ProductFamilyCsvResource.COL_CODE);
    }

    /**
     * Builds a family carrying a code and a stored checksum.
     *
     * @param code the family code
     * @param id the persistence id
     * @param checksum the stored checksum, possibly null
     * @return the family
     */
    private ProductFamily family(String code, Long id, Integer checksum) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        f.id = id;
        f.checksum = checksum;
        return f;
    }

    /**
     * Builds a product carrying an EAN.
     *
     * @param ean the product EAN
     * @return the product
     */
    private Product product(String ean) {
        Product p = new Product();
        p.ean = ean;
        return p;
    }

    /**
     * Invokes a private static method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = ProductFamilyCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * The importer names the FAMILIES engine feed retained verbatim for the
     * valuation engines.
     */
    @Test
    void feedCodeNamesTheFamiliesFeed() {
        assertEquals("FAMILIES", resource.feedCode());
    }

    /**
     * {@code processChunkWithFallback} answers an empty map on an empty chunk
     * without querying (true arm of the emptiness guard).
     */
    @Test
    void processChunkWithFallbackAnswersEmptyOnEmptyChunk() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(), Set.of(), new int[]{0, 0}, new ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches families, products and
     * sub-families and indexes them (false arm of the emptiness guard, both
     * the non-empty EAN and non-empty sub-code arms).
     */
    @Test
    void processChunkWithFallbackPopulatesEveryMap() {
        ProductFamily parent = family("F1", 1L, 0);
        ProductFamily sub = family("S1", 2L, 0);
        Product prod = product("E1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.list(eq("code IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenAnswer(inv -> {
                Collection<?> codes = (Collection<?>) inv.getArgument(1);
                return codes.contains("S1") ? List.of(sub) : List.of(parent);
            });
            panache.when(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(prod));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(line("F1", "Fruits", "FRUIT", "E1", "S1")),
                    Set.of("F1"), new int[]{0, 0}, new ArrayList<>());
            assertSame(parent, context.get("F1"));
            @SuppressWarnings("unchecked")
            Map<String, Product> products = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertSame(prod, products.get("E1"));
            @SuppressWarnings("unchecked")
            Map<String, ProductFamily> subs = (Map<String, ProductFamily>) context.get(CTX_SUB_FAMILIES);
            assertSame(sub, subs.get("S1"));
        }
    }

    /**
     * A chunk whose lines carry neither EANs nor sub-codes skips both bulk
     * fetches (false arm of the non-empty sub-code guard, empty-batch arm of
     * the product fetch).
     */
    @Test
    void processChunkWithFallbackWithoutLinks() {
        ProductFamily parent = family("F2", 3L, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.list(eq("code IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(parent));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(line("F2", "Legumes", "VEG", "", "")),
                    Set.of("F2"), new int[]{0, 0}, new ArrayList<>());
            assertSame(parent, context.get("F2"));
            @SuppressWarnings("unchecked")
            Map<String, Product> products = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertTrue(products.isEmpty());
            @SuppressWarnings("unchecked")
            Map<String, ProductFamily> subs = (Map<String, ProductFamily>) context.get(CTX_SUB_FAMILIES);
            assertTrue(subs.isEmpty());
        }
    }

    /**
     * {@code productsByEan} splits its lookup once the running batch reaches
     * the batch size (true arm of the batch-boundary guard).
     */
    @Test
    void productsByEanSplitsOnTheBatchBoundary() throws Exception {
        Method method = privateMethod("productsByEan", Collection.class);
        List<String> eans = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            eans.add("E" + i);
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of());
            @SuppressWarnings("unchecked")
            Map<String, Product> map = (Map<String, Product>) method.invoke(null, eans);
            assertTrue(map.isEmpty());
            panache.verify(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any()));
        }
    }

    /**
     * A family absent from the context is created, counted as a creation and
     * persisted; its products and sub-families are linked from the file (true
     * arm of the novelty guard, non-null arms of both link guards).
     */
    @Test
    void processLineLogicCreatesAndLinksANewFamily() {
        Product prod = product("E1");
        ProductFamily sub = family("S1", 2L, 0);
        Map<String, Product> productMap = new HashMap<>();
        productMap.put("E1", prod);
        Map<String, ProductFamily> subMap = new HashMap<>();
        subMap.put("S1", sub);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, productMap);
        context.put(CTX_SUB_FAMILIES, subMap);
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("F1", "Fruits", "FRUIT", "E1", "S1"), context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            ProductFamily created = (ProductFamily) captor.getValue();
            assertEquals("F1", created.code);
            assertEquals("Fruits", created.description);
            assertEquals("FRUIT", created.flags);
            assertTrue(created.products.contains(prod));
            assertTrue(created.productFamilies.contains(sub));
        }
    }

    /**
     * A created family with a blank description and flags carries null on both
     * fields (null arm of the trimmed-cell read).
     */
    @Test
    void processLineLogicCreatesWithNullDescriptionAndFlags() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("F9", null, null, "", ""), context, counters);
            assertEquals(1, counters[0]);
            org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            ProductFamily created = (ProductFamily) captor.getValue();
            assertNull(created.description);
            assertNull(created.flags);
            assertTrue(created.products.isEmpty());
            assertTrue(created.productFamilies.isEmpty());
        }
    }

    /**
     * A created family with no context maps and no links resolves its (null)
     * maps without a lookup (false arm of both compound retrieval guards,
     * empty link loops).
     */
    @Test
    void processLineLogicCreatesWithoutContextMapsWhenNoLinks() {
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("F3", "Bio", "ORGANIC", "", ""), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(any(ProductFamily.class));
        }
    }

    /**
     * With no context maps and real links, both retrieval helpers fall back on
     * a database lookup (true arm of both compound retrieval guards, including
     * the {@code productsByEan} fallback).
     */
    @Test
    void processLineLogicFallsBackOnDatabaseLookups() {
        Product prod = product("E7");
        ProductFamily sub = family("S7", 7L, 0);
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Panache> statics = mockStatic(Panache.class)) {
            panache.when(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(prod));
            panache.when(() -> ProductFamily.list(eq("code IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(sub));
            statics.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("FZ", "Frais", "FRESH", "E7", "S7"), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(any(ProductFamily.class));
        }
    }

    /**
     * A known family whose checksum moved is re-attached and re-fed, counted
     * as an update (false arm of the novelty guard, changed arm of the
     * checksum guard).
     */
    @Test
    void processLineLogicUpdatesAChangedFamily() {
        ProductFamily existing = family("EF", 5L, 0);
        ProductFamily reattached = family("EF", 5L, 0);
        Map<String, Object> context = new HashMap<>();
        context.put("EF", existing);
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(5L)).thenReturn(reattached);
            resource.processLineLogic(line("EF", "New", "F", "", ""), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("New", reattached.description);
            assertEquals("F", reattached.flags);
        }
    }

    /**
     * A known family whose checksum matches the incoming data is left
     * uncounted (unchanged arm of the checksum guard).
     */
    @Test
    void processLineLogicSkipsAnUnchangedFamily() {
        ProductFamily existing = family("EF", 5L, 0);
        int match = Objects.hash("EF", "Same", "F");
        ProductFamily reattached = family("EF", 5L, match);
        Map<String, Object> context = new HashMap<>();
        context.put("EF", existing);
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(5L)).thenReturn(reattached);
            resource.processLineLogic(line("EF", "Same", "F", "", ""), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
        }
    }

    /**
     * A known family whose stored checksum is null is always reported as
     * changed, counted as an update (null-checksum arm of the checksum guard).
     */
    @Test
    void processLineLogicUpdatesAFamilyWithNullChecksum() {
        ProductFamily existing = family("EF", 5L, 0);
        ProductFamily reattached = family("EF", 5L, null);
        Map<String, Object> context = new HashMap<>();
        context.put("EF", existing);
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ProductFamily.findById(5L)).thenReturn(reattached);
            resource.processLineLogic(line("EF", "Anything", "F", "", ""), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
        }
    }

    /**
     * A referenced product missing from the map fails the line, which is what
     * triggers the rollback and the one-by-one fallback of the base class
     * (null arm of the product guard).
     */
    @Test
    void processLineLogicRefusesAMissingProduct() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("F1", "Fruits", "FRUIT", "E1", ""), context, new int[]{0, 0}));
        assertEquals("Product EAN 'E1' not found.", ex.getMessage());
    }

    /**
     * A referenced sub-family missing from the map fails the line (null arm of
     * the sub-family guard).
     */
    @Test
    void processLineLogicRefusesAMissingSubFamily() {
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, new HashMap<String, ProductFamily>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("F1", "Fruits", "FRUIT", "", "X"), context, new int[]{0, 0}));
        assertEquals("SubFamily code 'X' not found.", ex.getMessage());
    }

    /**
     * A family listing itself as a sub-family fails the line (self-reference
     * arm of the sub-family guard).
     */
    @Test
    void processLineLogicRefusesASelfReference() {
        ProductFamily selfSub = family("SELF", 8L, 0);
        Map<String, ProductFamily> subMap = new HashMap<>();
        subMap.put("SELF", selfSub);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, new HashMap<String, Product>());
        context.put(CTX_SUB_FAMILIES, subMap);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("SELF", "Loop", "F", "", "SELF"), context, new int[]{0, 0}));
        assertEquals("Family 'SELF' cannot contain itself.", ex.getMessage());
    }

    /**
     * {@code findEntityForLine} resolves the line's family through the
     * referential finder (non-null result).
     */
    @Test
    void findEntityForLineResolvesThroughTheFinder() {
        ProductFamily existing = family("F1", 1L, 0);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> ProductFamily.find("code", "F1")).thenReturn(query);
            assertSame(existing, resource.findEntityForLine(line("F1", "Fruits", "FRUIT", "", "")));
        }
    }

    /**
     * {@code findEntityForLine} answers null when the referential carries no
     * such family (null result).
     */
    @Test
    void findEntityForLineAnswersNullWhenAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ProductFamily> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> ProductFamily.find("code", "GHOST")).thenReturn(query);
            assertNull(resource.findEntityForLine(line("GHOST", "", "", "", "")));
        }
    }

    /**
     * {@code importProductFamilies} delegates to the base stream import; an
     * empty stream names no engine feed target that fails, so the call returns
     * a successful summary without touching the transaction machinery.
     */
    @Test
    void importProductFamiliesDelegatesToTheStreamImport() {
        try (java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(new byte[0])) {
            jakarta.ws.rs.core.Response response = resource.importProductFamilies(stream);
            assertEquals(200, response.getStatus());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
