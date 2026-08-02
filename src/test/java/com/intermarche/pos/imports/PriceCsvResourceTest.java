package com.intermarche.pos.imports;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceCsvResource}.
 * <p>
 * The resource is the {@link Price}-specific concretion of the abstract
 * {@link ImporterCsvResource}: it bulk-fetches products and prices for a chunk,
 * then creates or updates each {@link Price} using a composite key
 * (EAN + StartDateTime + Priority) and a checksum optimisation. Every
 * collaborator is a Panache active-record static finder, which under plain
 * {@code mvn test} resolves to {@link PanacheEntityBase}
 * ({@code list}/{@code find}/{@code findById}) or to the declaring entity
 * ({@link Product#findByEan}, {@link Store#findByCode}); each is intercepted
 * with {@link org.mockito.Mockito#mockStatic} in a try-with-resources block.
 * The {@code __CTX_PRODUCTS__}/{@code __CTX_PRICES__} constants and the private
 * {@code buildPriceKey}/{@code computeIncomingChecksum} helpers are reached by
 * reflection so context maps carry byte-exact keys; the three otherwise-dead
 * store helpers ({@code getStoreMap}, {@code getTargetStoreCodes},
 * {@code getStore}) are exercised the same way. No database and no Quarkus
 * context is booted, every entity field is set directly, and every branch is
 * asserted against absolute expected values.
 */
class PriceCsvResourceTest {

    /** The exact JPQL fragment issued by {@code getProductMap}. */
    private static final String PRODUCT_LIST_QUERY = "ean IN ?1";
    /** The exact JPQL fragment issued by {@code getPriceMap} (trailing space intentional). */
    private static final String PRICE_LIST_QUERY = "product.ean IN ?1 ";
    /** The exact JPQL fragment issued by {@code getStoreMap}. */
    private static final String STORE_LIST_QUERY = "code IN ?1";
    /** The exact JPQL fragment issued by {@code retrievePrices} in fallback mode. */
    private static final String RETRIEVE_QUERY =
            "product.ean = ?1 and priceUsage = ?2 and startDateTime = ?3 and priority = ?4";
    /** The exact JPQL fragment issued by {@code findEntityForLine}. */
    private static final String FIND_ENTITY_QUERY =
            "product.ean = ?1 and startDateTime = ?2 and priority = ?3";

    /**
     * Wraps a string as a UTF-8 input stream.
     *
     * @param content the CSV content
     * @return the input stream
     */
    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the canonical 7-column price CSV row used across the line tests.
     *
     * @return a fresh parts array {EAN, HT, TTC, VAT, col4, col5, col6}
     */
    private String[] fullParts() {
        return new String[]{"111", "1.5", "2.5", "0.2", "5", "2020-12-31T00:00:00", "2021-01-31T00:00:00"};
    }

    /**
     * Reads a private static {@code String} constant of the resource by field name.
     *
     * @param name the field name
     * @return the constant value
     * @throws Exception on reflection failure
     */
    private static String constant(String name) throws Exception {
        Field field = PriceCsvResource.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /**
     * Returns the private context key under which the product map is stored.
     *
     * @return the {@code __CTX_PRODUCTS__} literal
     * @throws Exception on reflection failure
     */
    private static String ctxProducts() throws Exception {
        return constant("CTX_PRODUCTS");
    }

    /**
     * Returns the private context key under which the price map is stored.
     *
     * @return the {@code __CTX_PRICES__} literal
     * @throws Exception on reflection failure
     */
    private static String ctxPrices() throws Exception {
        return constant("CTX_PRICES");
    }

    /**
     * Invokes the private {@code buildPriceKey} composite-key builder.
     *
     * @param resource the resource under test
     * @param ean      the product EAN
     * @param start    the start date-time (may be null)
     * @param priority the priority (may be null)
     * @return the composite key
     * @throws Exception on reflection failure
     */
    private static String buildKey(PriceCsvResource resource, String ean, LocalDateTime start, Integer priority) throws Exception {
        Method method = PriceCsvResource.class.getDeclaredMethod("buildPriceKey", String.class, LocalDateTime.class, Integer.class);
        method.setAccessible(true);
        return (String) method.invoke(resource, ean, start, priority);
    }

    /**
     * Invokes the private {@code computeIncomingChecksum} helper.
     *
     * @param resource the resource under test
     * @param data     the parsed line
     * @param product  the associated product
     * @return the checksum of the incoming CSV data
     * @throws Exception on reflection failure
     */
    private static int incomingChecksum(PriceCsvResource resource, ImporterCsvResource.LineData data, Product product) throws Exception {
        Method method = PriceCsvResource.class.getDeclaredMethod("computeIncomingChecksum", ImporterCsvResource.LineData.class, Product.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, data, product);
    }

    /**
     * Invokes the private static {@code getStore} helper via reflection.
     *
     * @param storeMap the store lookup map (may be null to force fallback)
     * @param code     the store code (may be null)
     * @return the resolved store
     * @throws Exception the wrapped {@link InvocationTargetException} on business failure
     */
    private static Object invokeGetStore(Map<String, Store> storeMap, String code) throws Exception {
        Method method = PriceCsvResource.class.getDeclaredMethod("getStore", Map.class, String.class);
        method.setAccessible(true);
        return method.invoke(null, new Object[]{storeMap, code});
    }

    /**
     * {@code importPrices} forwards the stream to the base importer with the
     * 7-column contract; a header-only body produces a zero-count JSON.
     */
    @Test
    void importPricesDelegatesToBaseImporterWithSevenColumns() {
        PriceCsvResource resource = new PriceCsvResource();
        Response response = resource.importPrices(stream("EAN|HT|TTC|VAT|P|START|END\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map when the
     * chunk holds no lines (the {@code isEmpty} true arm).
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        PriceCsvResource resource = new PriceCsvResource();
        Map<String, Object> context = resource.processChunkWithFallback(new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches products and prices for a
     * populated chunk (isEmpty false arm), driving {@code getProductMap} and
     * {@code getPriceMap} with both a fully-populated price (start/priority
     * non-null arms) and a bare price (both null arms).
     */
    @Test
    void processChunkWithFallbackBuildsContextForPopulatedChunk() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", fullParts()));
        Set<String> codes = new HashSet<>();
        codes.add("111");
        Product product = new Product();
        product.ean = "111";
        Price full = new Price();
        full.product = product;
        full.startDateTime = LocalDateTime.of(2021, 1, 1, 0, 0);
        full.priority = 0;
        Price bare = new Price();
        bare.product = product;
        bare.startDateTime = null;
        bare.priority = null;
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(PRODUCT_LIST_QUERY, codes)).thenReturn(List.of(product));
            panache.when(() -> Price.list(PRICE_LIST_QUERY, codes)).thenReturn(List.of(full, bare));
            Map<String, Object> context = resource.processChunkWithFallback(lines, codes, counters, errors);
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) context.get(ctxProducts());
            @SuppressWarnings("unchecked")
            Map<String, Price> priceMap = (Map<String, Price>) context.get(ctxPrices());
            assertEquals(1, productMap.size());
            assertSame(product, productMap.get("111"));
            assertEquals(2, priceMap.size());
            assertSame(full, priceMap.get(buildKey(resource, "111", LocalDateTime.of(2021, 1, 1, 0, 0), 0)));
            assertSame(bare, priceMap.get(buildKey(resource, "111", null, null)));
        }
    }

    /**
     * {@code processChunkWithFallback} with an empty target-code set skips the
     * product query (getProductMap guard false arm) and yields an empty price
     * map (getPriceMap loop not entered).
     */
    @Test
    void processChunkWithFallbackHandlesEmptyTargetCodes() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", fullParts()));
        Set<String> codes = new HashSet<>();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.list(PRICE_LIST_QUERY, codes)).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(lines, codes, counters, errors);
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) context.get(ctxProducts());
            @SuppressWarnings("unchecked")
            Map<String, Price> priceMap = (Map<String, Price>) context.get(ctxPrices());
            assertTrue(productMap.isEmpty());
            assertTrue(priceMap.isEmpty());
        }
    }

    /**
     * {@code processLineLogic} creates a fresh price when no existing entry
     * matches the composite key (processPriceLogic isNew true arm), persisting
     * it through the entity manager and incrementing the created counter.
     */
    @Test
    void processLineLogicCreatesNewPriceWhenAbsent() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        Product product = new Product();
        product.ean = "111";
        Map<String, Object> productMap = new HashMap<>();
        productMap.put("111", product);
        Map<String, Object> context = new HashMap<>();
        context.put(ctxProducts(), productMap);
        context.put(ctxPrices(), new HashMap<String, Price>());
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(any(Price.class));
        }
    }

    /**
     * {@code processLineLogic} updates an existing price when the checksum
     * differs (processPriceLogic isNew false + checksum-mismatch true arms),
     * re-reading both entities by id and incrementing the updated counter.
     */
    @Test
    void processLineLogicUpdatesExistingPriceWhenChecksumDiffers() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        Product product = new Product();
        product.id = 7L;
        product.ean = "111";
        Price existing = new Price();
        existing.id = 42L;
        existing.checksum = incomingChecksum(resource, data, product) + 1;
        Map<String, Object> productMap = new HashMap<>();
        productMap.put("111", product);
        Map<String, Object> priceMap = new HashMap<>();
        priceMap.put(buildKey(resource, "111", resource.safeParseDateTime(parts, 6), resource.safeParseInt(parts, 5)), existing);
        Map<String, Object> context = new HashMap<>();
        context.put(ctxProducts(), productMap);
        context.put(ctxPrices(), priceMap);
        Price fresh = new Price();
        Product freshProduct = new Product();
        freshProduct.ean = "111";
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(42L)).thenReturn(fresh);
            panache.when(() -> Product.findById(7L)).thenReturn(freshProduct);
            resource.processLineLogic(data, context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertSame(freshProduct, fresh.product);
        }
    }

    /**
     * {@code processLineLogic} leaves the counters untouched when the existing
     * price already matches the incoming checksum (checksum-mismatch false arm).
     */
    @Test
    void processLineLogicSkipsUpdateWhenChecksumMatches() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        Product product = new Product();
        product.id = 7L;
        product.ean = "111";
        Price existing = new Price();
        existing.id = 42L;
        existing.checksum = incomingChecksum(resource, data, product);
        Map<String, Object> productMap = new HashMap<>();
        productMap.put("111", product);
        Map<String, Object> priceMap = new HashMap<>();
        priceMap.put(buildKey(resource, "111", resource.safeParseDateTime(parts, 6), resource.safeParseInt(parts, 5)), existing);
        Map<String, Object> context = new HashMap<>();
        context.put(ctxProducts(), productMap);
        context.put(ctxPrices(), priceMap);
        int[] counters = {0, 0};
        resource.processLineLogic(data, context, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * {@code processLineLogic} throws when the pre-fetched product map is
     * present but lacks the line's EAN (getProduct map-non-null arm,
     * product-null throw arm).
     */
    @Test
    void processLineLogicThrowsWhenProductMissingFromContext() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Map<String, Object> context = new HashMap<>();
        context.put(ctxProducts(), new HashMap<String, Product>());
        context.put(ctxPrices(), new HashMap<String, Price>());
        int[] counters = {0, 0};
        assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(data, context, counters));
    }

    /**
     * {@code processLineLogic} in 1-by-1 fallback (no product map in context)
     * fetches the product by EAN (getProduct map-null true arm, p-non-null arm)
     * and creates the price.
     */
    @Test
    void processLineLogicFetchesProductByEanWhenContextMissing() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Product product = new Product();
        product.ean = "111";
        Map<String, Object> context = new HashMap<>();
        context.put(ctxPrices(), new HashMap<String, Price>());
        int[] counters = {0, 0};
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            products.when(() -> Product.findByEan("111")).thenReturn(product);
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
        }
    }

    /**
     * {@code processLineLogic} in 1-by-1 fallback throws when the EAN lookup
     * yields nothing (getProduct p-null arm, product-null throw arm).
     */
    @Test
    void processLineLogicThrowsWhenEanLookupReturnsNull() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Map<String, Object> context = new HashMap<>();
        context.put(ctxPrices(), new HashMap<String, Price>());
        int[] counters = {0, 0};
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("111")).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> resource.processLineLogic(data, context, counters));
        }
    }

    /**
     * {@code retrievePrices} returns the context price map verbatim when it is
     * present (the {@code priceMap == null} false arm).
     */
    @Test
    void retrievePricesReturnsContextMapWhenPresent() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Map<String, Price> inner = new HashMap<>();
        Map<String, Object> context = new HashMap<>();
        context.put(ctxPrices(), inner);
        assertSame(inner, resource.retrievePrices(data, context));
    }

    /**
     * {@code retrievePrices} falls back to a database lookup when the context
     * lacks a price map (priceMap null arm) and maps a found price under its
     * composite key (existing non-null arm).
     */
    @Test
    void retrievePricesFallbackMapsFoundPrice() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        LocalDateTime start = resource.safeParseDateTime(parts, 5);
        Integer priority = resource.safeParseInt(parts, 4);
        Price existing = new Price();
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(RETRIEVE_QUERY, "111", start, priority)).thenReturn(query);
            when(query.firstResult()).thenReturn(existing);
            Map<String, Price> result = resource.retrievePrices(data, new HashMap<>());
            assertEquals(1, result.size());
            assertSame(existing, result.get(buildKey(resource, "111", start, priority)));
        }
    }

    /**
     * {@code retrievePrices} fallback yields an empty map when the database
     * lookup finds nothing (existing null arm).
     */
    @Test
    void retrievePricesFallbackReturnsEmptyMapWhenNotFound() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        LocalDateTime start = resource.safeParseDateTime(parts, 5);
        Integer priority = resource.safeParseInt(parts, 4);
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(RETRIEVE_QUERY, "111", start, priority)).thenReturn(query);
            when(query.firstResult()).thenReturn(null);
            Map<String, Price> result = resource.retrievePrices(data, new HashMap<>());
            assertTrue(result.isEmpty());
        }
    }

    /**
     * {@code findEntityForLine} performs the composite-key lookup and returns
     * the first matching price.
     */
    @Test
    void findEntityForLineLooksUpPriceByComposite() {
        PriceCsvResource resource = new PriceCsvResource();
        String[] parts = fullParts();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", parts);
        LocalDateTime start = resource.safeParseDateTime(parts, 5);
        Integer priority = resource.safeParseInt(parts, 4);
        Price existing = new Price();
        PanacheQuery<Price> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(FIND_ENTITY_QUERY, "111", start, priority)).thenReturn(query);
            when(query.firstResult()).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(data));
        }
    }

    /**
     * The private {@code getStoreMap} helper fetches and indexes stores when
     * codes are present (guard true arm, loop entered).
     */
    @Test
    void getStoreMapIndexesStoresForNonEmptyCodes() throws Exception {
        Set<String> codes = new HashSet<>();
        codes.add("S1");
        codes.add("S2");
        Store s1 = new Store();
        s1.code = "S1";
        Store s2 = new Store();
        s2.code = "S2";
        Method method = PriceCsvResource.class.getDeclaredMethod("getStoreMap", Set.class);
        method.setAccessible(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Store.list(STORE_LIST_QUERY, codes)).thenReturn(List.of(s1, s2));
            @SuppressWarnings("unchecked")
            Map<String, Store> map = (Map<String, Store>) method.invoke(null, codes);
            assertEquals(2, map.size());
            assertSame(s1, map.get("S1"));
            assertSame(s2, map.get("S2"));
        }
    }

    /**
     * The private {@code getStoreMap} helper returns an empty map without
     * querying when the code set is empty (guard false arm).
     */
    @Test
    void getStoreMapReturnsEmptyMapForNoCodes() throws Exception {
        Method method = PriceCsvResource.class.getDeclaredMethod("getStoreMap", Set.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Store> map = (Map<String, Store>) method.invoke(null, new HashSet<String>());
        assertTrue(map.isEmpty());
    }

    /**
     * The private {@code getTargetStoreCodes} helper collects the non-null
     * store code in column 1 and skips a row that has no such column (loop plus
     * both arms of the null guard).
     */
    @Test
    void getTargetStoreCodesCollectsPresentCodesOnly() throws Exception {
        PriceCsvResource resource = new PriceCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", new String[]{"111", "S1"}));
        lines.add(new ImporterCsvResource.LineData(2, "222", new String[]{"222"}));
        Method method = PriceCsvResource.class.getDeclaredMethod("getTargetStoreCodes", List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) method.invoke(resource, lines);
        assertEquals(1, result.size());
        assertTrue(result.contains("S1"));
    }

    /**
     * The private {@code getStore} helper returns the mapped store when present
     * (storeMap non-null arm, store non-null arm).
     */
    @Test
    void getStoreReturnsMappedStore() throws Exception {
        Store store = new Store();
        store.code = "S1";
        Map<String, Store> map = new HashMap<>();
        map.put("S1", store);
        assertSame(store, invokeGetStore(map, "S1"));
    }

    /**
     * The private {@code getStore} helper throws when the store is absent from a
     * present map (store-null throw arm).
     */
    @Test
    void getStoreThrowsWhenMissingFromMap() {
        Map<String, Store> map = new HashMap<>();
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> invokeGetStore(map, "S1"));
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }

    /**
     * The private {@code getStore} helper fetches a store by code in fallback
     * mode (storeMap null arm, storeCode non-null arm, s non-null arm).
     */
    @Test
    void getStoreFallbackFetchesByCode() throws Exception {
        Store store = new Store();
        store.code = "S1";
        try (MockedStatic<Store> stores = mockStatic(Store.class)) {
            stores.when(() -> Store.findByCode("S1")).thenReturn(store);
            assertSame(store, invokeGetStore(null, "S1"));
        }
    }

    /**
     * The private {@code getStore} helper throws when the fallback code lookup
     * finds nothing (s-null arm, store-null throw arm).
     */
    @Test
    void getStoreFallbackThrowsWhenCodeLookupReturnsNull() {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> {
            try (MockedStatic<Store> stores = mockStatic(Store.class)) {
                stores.when(() -> Store.findByCode("S1")).thenReturn(null);
                invokeGetStore(null, "S1");
            }
        });
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }

    /**
     * The private {@code getStore} helper throws in fallback mode when the store
     * code itself is null (storeCode-null arm, store-null throw arm).
     */
    @Test
    void getStoreFallbackThrowsForNullCode() {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> invokeGetStore(null, null));
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }
}
