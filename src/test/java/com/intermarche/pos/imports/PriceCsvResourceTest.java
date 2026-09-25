package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Unit tests for {@link PriceCsvResource}.
 * <p>
 * The resource is the {@link Price}-specific concretion of the abstract
 * {@link ImporterCsvResource}: per chunk it bulk-fetches the products and
 * prices the file names, then for each line either skips a non-DEFAULT usage
 * row, creates a price or compares checksums on an existing one. Every static
 * finder inherited from {@link PanacheEntityBase} ({@code list}, {@code find},
 * {@code findById}) resolves to that base class under plain {@code mvn test}
 * and is intercepted with {@link org.mockito.Mockito#mockStatic} in
 * try-with-resources blocks; {@code Panache.getEntityManager()} is stubbed the
 * same way so {@code persist} is observable. The private helpers are reached by
 * reflection so both arms of every guard carry absolute expected values. No
 * database and no Quarkus context is booted.
 */
class PriceCsvResourceTest {

    /** Context-map key under which the pre-fetched product map is stored. */
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";
    /** Context-map key under which the pre-fetched price map is stored. */
    private static final String CTX_PRICES = "__CTX_PRICES__";

    /** The resource under test. */
    private final PriceCsvResource resource = new PriceCsvResource();

    /**
     * Builds a parsed line over an ordered set of declared columns.
     * <p>
     * A column present in the map is DECLARED by the header (so {@code has}
     * answers true) even when its value is null; a column absent from the map
     * is not declared at all.
     *
     * @param cells the ordered column-name to cell-value pairs
     * @return the parsed line, keyed on the EAN column
     */
    private ImporterCsvResource.LineData line(LinkedHashMap<String, String> cells) {
        Map<String, Integer> header = new HashMap<>();
        String[] parts = new String[cells.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : cells.entrySet()) {
            header.put(entry.getKey(), index);
            parts[index] = entry.getValue();
            index++;
        }
        return new ImporterCsvResource.LineData(1, header, parts, PriceCsvResource.COL_EAN);
    }

    /**
     * Builds a standard proceed-friendly line: an EAN, both prices, no VAT
     * column and no priority or dates, plus the given PRICE_USAGE handling.
     *
     * @param ean the EAN cell
     * @param withUsageColumn whether the header declares the PRICE_USAGE column
     * @param usage the PRICE_USAGE cell (used only when the column is declared)
     * @return the parsed line
     */
    private ImporterCsvResource.LineData saleLine(String ean, boolean withUsageColumn, String usage) {
        LinkedHashMap<String, String> cells = new LinkedHashMap<>();
        cells.put(PriceCsvResource.COL_EAN, ean);
        cells.put(PriceCsvResource.COL_PRICE_EXCL_TAX, "10.0000");
        cells.put(PriceCsvResource.COL_PRICE_INCL_TAX, "12.0000");
        cells.put(PriceCsvResource.COL_PRIORITY, "");
        cells.put(PriceCsvResource.COL_START_DATE, "");
        cells.put(PriceCsvResource.COL_END_DATE, "");
        if (withUsageColumn) {
            cells.put(PriceCsvResource.COL_PRICE_USAGE, usage);
        }
        return line(cells);
    }

    /**
     * Builds a product carrying an EAN and an id.
     *
     * @param ean the EAN
     * @param id the primary key
     * @return the product
     */
    private Product product(String ean, Long id) {
        Product product = new Product();
        product.ean = ean;
        product.id = id;
        return product;
    }

    /**
     * Wraps a single result into a mocked Panache query.
     *
     * @param result the value {@code firstResult} returns
     * @param <T> the entity type
     * @return the mocked query
     */
    private <T> PanacheQuery<T> query(T result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<T> q = mock(PanacheQuery.class);
        when(q.firstResult()).thenReturn(result);
        return q;
    }

    /**
     * Invokes a private method of the resource by reflection.
     *
     * @param name the method name
     * @param types the parameter types
     * @return the accessible method
     * @throws Exception when the method does not exist
     */
    private Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = PriceCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * The importer names the PRICES engine feed captured verbatim for the
     * valuation engines.
     */
    @Test
    void feedCodeIsPrices() {
        assertEquals("PRICES", resource.feedCode());
    }

    /**
     * {@code importPrices} on an empty stream reads no header, opens no
     * transaction and answers a 200 summary, exercising the delegation to the
     * base class without any collaborator.
     */
    @Test
    void importPricesReturnsOkOnEmptyStream() {
        Response response = resource.importPrices(new ByteArrayInputStream(new byte[0]));
        assertEquals(200, response.getStatus());
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
     * {@code processChunkWithFallback} bulk-fetches products and prices and
     * indexes both (false arm of the emptiness guard). The two prices exercise
     * both arms of every {@code buildPriceKey} ternary: one carries a start and
     * a priority, the other neither.
     */
    @Test
    void processChunkWithFallbackBuildsBothContextMaps() {
        Product prod = product("111", 7L);
        Price dated = new Price();
        dated.product = prod;
        dated.startDateTime = LocalDateTime.of(2024, 1, 1, 0, 0);
        dated.priority = 1;
        Price open = new Price();
        open.product = prod;
        open.startDateTime = null;
        open.priority = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(prod));
            panache.when(() -> Price.list(eq("product.ean IN ?1 "), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of(dated, open));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(saleLine("111", false, null)), Set.of("111"),
                    new int[]{0, 0}, new ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, Product> products = (Map<String, Product>) context.get(CTX_PRODUCTS);
            @SuppressWarnings("unchecked")
            Map<String, Price> prices = (Map<String, Price>) context.get(CTX_PRICES);
            assertEquals(1, products.size());
            assertSame(prod, products.get("111"));
            assertEquals(2, prices.size());
            assertSame(dated, prices.get("111:2024-01-01T00:00:1"));
            assertSame(open, prices.get("111:NULL:NULL"));
        }
    }

    /**
     * An empty target-code set skips the product query altogether (false arm
     * of the {@code !targetCodes.isEmpty()} guard) while the price query still
     * runs, so the product map comes back empty.
     */
    @Test
    void processChunkWithFallbackSkipsProductQueryWhenNoCode() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.list(eq("product.ean IN ?1 "), org.mockito.ArgumentMatchers.<Object>any())).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(saleLine("111", false, null)), Set.of(),
                    new int[]{0, 0}, new ArrayList<>());
            @SuppressWarnings("unchecked")
            Map<String, Product> products = (Map<String, Product>) context.get(CTX_PRODUCTS);
            assertTrue(products.isEmpty());
            panache.verify(() -> Product.list(eq("ean IN ?1"), org.mockito.ArgumentMatchers.<Object>any()), org.mockito.Mockito.never());
        }
    }

    /**
     * A row declaring a non-DEFAULT usage is a discount base for the engine and
     * is skipped silently (true arm of the has-column guard and true arm of
     * every leg of the compound usage guard), touching no collaborator.
     */
    @Test
    void processLineLogicSkipsNonDefaultUsage() {
        int[] counters = new int[]{0, 0};
        resource.processLineLogic(saleLine("111", true, "BASE_FOR_DISCOUNT"), new HashMap<>(), counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * A declared but null usage cell does not skip the row (false arm of the
     * {@code usage != null} leg): the line proceeds and creates a price.
     */
    @Test
    void processLineLogicProceedsWhenUsageIsNull() {
        assertCreatesPrice(saleLine("111", true, null));
    }

    /**
     * A declared but empty usage cell does not skip the row (false arm of the
     * {@code !usage.isEmpty()} leg): the line proceeds and creates a price.
     */
    @Test
    void processLineLogicProceedsWhenUsageIsEmpty() {
        assertCreatesPrice(saleLine("111", true, ""));
    }

    /**
     * A DEFAULT usage cell does not skip the row (false arm of the
     * {@code !USAGE_DEFAULT.equals(usage)} leg): the line proceeds and creates
     * a price.
     */
    @Test
    void processLineLogicProceedsWhenUsageIsDefault() {
        assertCreatesPrice(saleLine("111", true, "DEFAULT"));
    }

    /**
     * A feed that never declares the PRICE_USAGE column treats every row as a
     * selling price (false arm of the has-column guard): the line proceeds and
     * creates a price.
     */
    @Test
    void processLineLogicProceedsWhenNoUsageColumn() {
        assertCreatesPrice(saleLine("111", false, null));
    }

    /**
     * Drives {@code processLineLogic} on a line whose key names no existing
     * price and asserts a single creation is persisted. The context carries the
     * product and an empty price map, so the create branch of
     * {@code processPriceLogic} runs with no VAT resolved.
     *
     * @param data the parsed line to process
     */
    private void assertCreatesPrice(ImporterCsvResource.LineData data) {
        Map<String, Object> context = new HashMap<>();
        Map<String, Product> products = new HashMap<>();
        products.put("111", product("111", 7L));
        context.put(CTX_PRODUCTS, products);
        context.put(CTX_PRICES, new HashMap<String, Price>());
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            verify(em).persist(any(Price.class));
        }
    }

    /**
     * A known price whose stored checksum is null is reported as changed
     * (true arm of {@code changedFrom}): it is reloaded, refed and counted as
     * an update.
     */
    @Test
    void processLineLogicUpdatesChangedPrice() {
        Product prod = product("111", 7L);
        Price existing = new Price();
        existing.id = 55L;
        existing.checksum = null;
        Price reloaded = new Price();
        reloaded.id = 55L;
        Map<String, Price> priceMap = new HashMap<>();
        priceMap.put("111:NULL:5", existing);
        Map<String, Product> products = new HashMap<>();
        products.put("111", prod);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, products);
        context.put(CTX_PRICES, priceMap);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.findById(55L)).thenReturn(reloaded);
            panache.when(() -> Product.findById(7L)).thenReturn(prod);
            resource.processLineLogic(updateLine(), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertSame(prod, reloaded.product);
            assertEquals(new BigDecimal("10.0000"), reloaded.priceExcludingTax);
            assertEquals(new BigDecimal("12.0000"), reloaded.priceIncludingTax);
            assertEquals(5, reloaded.priority);
        }
    }

    /**
     * A known price whose stored checksum equals the incoming one is left
     * untouched (false arm of {@code changedFrom}): neither counter moves and
     * no reload happens.
     */
    @Test
    void processLineLogicSkipsUnchangedPrice() {
        Product prod = product("111", 7L);
        Price existing = new Price();
        existing.id = 55L;
        existing.checksum = Objects.hash("111", new BigDecimal("10.0000"),
                new BigDecimal("12.0000"), null, 5, null, null);
        Map<String, Price> priceMap = new HashMap<>();
        priceMap.put("111:NULL:5", existing);
        Map<String, Product> products = new HashMap<>();
        products.put("111", prod);
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRODUCTS, products);
        context.put(CTX_PRICES, priceMap);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.processLineLogic(updateLine(), context, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            panache.verifyNoInteractions();
        }
    }

    /**
     * Builds the line used by the update tests: EAN 111, both prices, priority
     * 5, no dates and no VAT column, so its composite key is 111:NULL:5.
     *
     * @return the parsed line
     */
    private ImporterCsvResource.LineData updateLine() {
        LinkedHashMap<String, String> cells = new LinkedHashMap<>();
        cells.put(PriceCsvResource.COL_EAN, "111");
        cells.put(PriceCsvResource.COL_PRICE_EXCL_TAX, "10.0000");
        cells.put(PriceCsvResource.COL_PRICE_INCL_TAX, "12.0000");
        cells.put(PriceCsvResource.COL_PRIORITY, "5");
        cells.put(PriceCsvResource.COL_START_DATE, "");
        cells.put(PriceCsvResource.COL_END_DATE, "");
        return line(cells);
    }

    /**
     * {@code getProduct} resolves the product straight from a supplied map
     * (false arm of the {@code productMap == null} guard, false arm of the
     * not-found guard).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void getProductReturnsFromSuppliedMap() throws Exception {
        Product prod = product("111", 7L);
        Map<String, Product> products = new HashMap<>();
        products.put("111", prod);
        Method method = privateMethod("getProduct", ImporterCsvResource.LineData.class, Map.class);
        Object result = method.invoke(null, saleLine("111", false, null), products);
        assertSame(prod, result);
    }

    /**
     * {@code getProduct} throws when a supplied non-null map has no entry for
     * the line's EAN (true arm of the not-found guard).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void getProductThrowsWhenMapHasNoEntry() throws Exception {
        Method method = privateMethod("getProduct", ImporterCsvResource.LineData.class, Map.class);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, saleLine("999", false, null), new HashMap<String, Product>()));
        assertTrue(thrown.getCause() instanceof IllegalArgumentException);
    }

    /**
     * {@code getProduct} falls back on a database lookup when the map is null
     * (true arm of the {@code productMap == null} guard) and indexes the found
     * product (true arm of the {@code p != null} guard).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void getProductFallsBackToDatabase() throws Exception {
        Product prod = product("111", 7L);
        Method method = privateMethod("getProduct", ImporterCsvResource.LineData.class, Map.class);
        PanacheQuery<Product> found = query(prod);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("ean", "111")).thenReturn(found);
            Object result = method.invoke(null, saleLine("111", false, null), null);
            assertSame(prod, result);
        }
    }

    /**
     * {@code getProduct} throws when the null-map fallback finds nothing
     * (false arm of the {@code p != null} guard, then true arm of the
     * not-found guard).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void getProductThrowsWhenFallbackFindsNothing() throws Exception {
        Method method = privateMethod("getProduct", ImporterCsvResource.LineData.class, Map.class);
        PanacheQuery<Product> empty = query((Product) null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("ean", "111")).thenReturn(empty);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(null, saleLine("111", false, null), null));
            assertTrue(thrown.getCause() instanceof IllegalArgumentException);
        }
    }

    /**
     * {@code retrievePrices} returns the context map untouched when it is
     * present (false arm of the {@code priceMap == null} guard).
     */
    @Test
    void retrievePricesReturnsContextMap() {
        Map<String, Price> priceMap = new HashMap<>();
        Map<String, Object> context = new HashMap<>();
        context.put(CTX_PRICES, priceMap);
        Map<String, Price> result = resource.retrievePrices(saleLine("111", false, null), context);
        assertSame(priceMap, result);
    }

    /**
     * {@code retrievePrices} falls back on a single-price lookup when the
     * context has none (true arm of the guard) and indexes the found price
     * (true arm of the {@code existing != null} guard).
     */
    @Test
    void retrievePricesFallbackFindsExisting() {
        Price existing = new Price();
        PanacheQuery<Price> found = query(existing);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(
                    "product.ean = ?1 and priceUsage = ?2 and startDateTime = ?3 and priority = ?4",
                    "111", null, null)).thenReturn(found);
            Map<String, Price> result = resource.retrievePrices(saleLine("111", false, null), new HashMap<>());
            assertEquals(1, result.size());
            assertSame(existing, result.get("111:NULL:NULL"));
        }
    }

    /**
     * {@code retrievePrices} answers an empty map when the fallback lookup
     * finds nothing (false arm of the {@code existing != null} guard).
     */
    @Test
    void retrievePricesFallbackEmptyWhenNoneFound() {
        PanacheQuery<Price> empty = query((Price) null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(
                    "product.ean = ?1 and priceUsage = ?2 and startDateTime = ?3 and priority = ?4",
                    "111", null, null)).thenReturn(empty);
            Map<String, Price> result = resource.retrievePrices(saleLine("111", false, null), new HashMap<>());
            assertTrue(result.isEmpty());
        }
    }

    /**
     * {@code findEntityForLine} resolves the line's price through the composite
     * finder.
     */
    @Test
    void findEntityForLineResolvesThroughFinder() {
        Price existing = new Price();
        PanacheQuery<Price> found = query(existing);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Price.find(
                    "product.ean = ?1 and startDateTime = ?2 and priority = ?3",
                    "111", null, null)).thenReturn(found);
            assertSame(existing, resource.findEntityForLine(saleLine("111", false, null)));
        }
    }

    /**
     * {@code resolveVat} names its regime by number when the file declares one
     * (true arm of the {@code number != null} guard).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void resolveVatByNumber() throws Exception {
        VatRate vat = new VatRate(1, new BigDecimal("0.2000"), "Taux normal");
        Method method = privateMethod("resolveVat", ImporterCsvResource.LineData.class);
        LinkedHashMap<String, String> cells = new LinkedHashMap<>();
        cells.put(PriceCsvResource.COL_EAN, "111");
        cells.put(PriceCsvResource.COL_VAT_NUMBER, "1");
        PanacheQuery<VatRate> found = query(vat);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.find("number", 1)).thenReturn(found);
            Object result = method.invoke(resource, line(cells));
            assertSame(vat, result);
        }
    }

    /**
     * {@code resolveVat} answers null when the file names neither a VAT number
     * nor a VAT rate (false arm of the {@code number != null} guard, then the
     * null-rate short-circuit of the referential finder).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void resolveVatAnswersNullWhenNeitherDeclared() throws Exception {
        Method method = privateMethod("resolveVat", ImporterCsvResource.LineData.class);
        assertNull(method.invoke(resource, saleLine("111", false, null)));
    }

    /**
     * {@code computeIncomingChecksum} folds the resolved regime's number into
     * the hash when a regime is found (non-null arm of the ternary).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void computeIncomingChecksumWithVat() throws Exception {
        VatRate vat = new VatRate(1, new BigDecimal("0.2000"), "Taux normal");
        Method method = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        LinkedHashMap<String, String> cells = new LinkedHashMap<>();
        cells.put(PriceCsvResource.COL_EAN, "111");
        cells.put(PriceCsvResource.COL_PRICE_EXCL_TAX, "10.0000");
        cells.put(PriceCsvResource.COL_PRICE_INCL_TAX, "12.0000");
        cells.put(PriceCsvResource.COL_VAT_NUMBER, "1");
        cells.put(PriceCsvResource.COL_PRIORITY, "5");
        PanacheQuery<VatRate> found = query(vat);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> VatRate.find("number", 1)).thenReturn(found);
            int incoming = (int) method.invoke(resource, line(cells), product("111", 7L));
            assertEquals(Objects.hash("111", new BigDecimal("10.0000"),
                    new BigDecimal("12.0000"), 1, 5, null, null), incoming);
        }
    }

    /**
     * {@code computeIncomingChecksum} folds a null regime number into the hash
     * when no regime is resolved (null arm of the ternary).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void computeIncomingChecksumWithoutVat() throws Exception {
        Method method = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        int incoming = (int) method.invoke(resource, saleLine("111", false, null), product("111", 7L));
        assertEquals(Objects.hash("111", new BigDecimal("10.0000"),
                new BigDecimal("12.0000"), null, null, null, null), incoming);
    }

    /**
     * {@code buildPriceKey} renders the start and priority when both are
     * present (non-null arm of each ternary).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void buildPriceKeyRendersPresentValues() throws Exception {
        Method method = privateMethod("buildPriceKey", String.class, LocalDateTime.class, Integer.class);
        Object key = method.invoke(resource, "111", LocalDateTime.of(2024, 1, 1, 0, 0), 3);
        assertEquals("111:2024-01-01T00:00:3", key);
    }

    /**
     * {@code buildPriceKey} substitutes NULL for a missing start and a missing
     * priority (null arm of each ternary).
     *
     * @throws Exception on reflective failure
     */
    @Test
    void buildPriceKeySubstitutesNull() throws Exception {
        Method method = privateMethod("buildPriceKey", String.class, LocalDateTime.class, Integer.class);
        Object key = method.invoke(resource, "111", null, null);
        assertEquals("111:NULL:NULL", key);
    }
}
