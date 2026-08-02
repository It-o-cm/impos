package com.intermarche.pos.imports;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductCsvResource}.
 * <p>
 * The resource is the {@link Product}-specific concretion of the abstract
 * {@link ImporterCsvResource}: it bulk-fetches products for a chunk keyed by
 * EAN, then creates or updates each {@link Product} using a checksum
 * optimisation before persisting through the Panache entity manager. Every
 * collaborator is a Panache active-record static finder which, under plain
 * {@code mvn test}, resolves to {@link PanacheEntityBase}
 * ({@code list}/{@code find}/{@code findById}) — each is intercepted with
 * {@link org.mockito.Mockito#mockStatic} inside a try-with-resources block —
 * and {@code Panache.getEntityManager()} is stubbed the same way so
 * {@code persist} is observable. The private {@code computeIncomingChecksum}
 * helper is reached by reflection so the update-vs-skip checksum arms carry
 * byte-exact expected values; {@code feedProduct} is asserted through the
 * captured persisted entity and the freshly re-read entity. No database and no
 * Quarkus context is booted, every entity field is set directly, and every
 * branch is asserted against absolute expected values.
 */
class ProductCsvResourceTest {

    /** The exact JPQL fragment issued by {@code processChunkWithFallback}. */
    private static final String LIST_QUERY = "ean IN ?1";
    /** The exact JPQL fragment issued by {@code findEntityForLine}. */
    private static final String FIND_QUERY = "ean";

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
     * Builds the canonical 9-column product CSV row used across the line tests.
     *
     * @return a fresh parts array
     *         {EAN, Name, Description, Brand, Weight, Volume, Type, Unit, Active}
     */
    private String[] fullParts() {
        return new String[]{"111", "Milk", "Fresh milk", "BrandX", "1.500", "2.000", "unit", "kg", "true"};
    }

    /**
     * Invokes the private {@code computeIncomingChecksum} helper by reflection.
     *
     * @param resource the resource under test
     * @param data     the parsed line
     * @return the checksum of the incoming CSV data
     * @throws Exception on reflection failure
     */
    private static int incomingChecksum(ProductCsvResource resource, ImporterCsvResource.LineData data) throws Exception {
        Method method = ProductCsvResource.class.getDeclaredMethod("computeIncomingChecksum", ImporterCsvResource.LineData.class);
        method.setAccessible(true);
        return (Integer) method.invoke(resource, data);
    }

    /**
     * {@code importProducts} forwards the stream to the base importer with the
     * 9-column contract; a header-only body produces a zero-count JSON with a
     * 200 status.
     */
    @Test
    void importProductsDelegatesToBaseImporterWithNineColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        Response response = resource.importProducts(stream("EAN|Name|Desc|Brand|W|V|Type|Unit|Active\n"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * {@code processChunkWithFallback} short-circuits to an empty map when the
     * chunk holds no lines (the {@code parsedLines.isEmpty} true arm).
     */
    @Test
    void processChunkWithFallbackReturnsEmptyMapForEmptyChunk() {
        ProductCsvResource resource = new ProductCsvResource();
        Map<String, Object> context = resource.processChunkWithFallback(new ArrayList<>(), new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processChunkWithFallback} bulk-fetches and indexes existing
     * products by EAN for a populated chunk (parsedLines non-empty arm,
     * targetEans non-empty arm, loop entered).
     */
    @Test
    void processChunkWithFallbackIndexesExistingProducts() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", fullParts()));
        Set<String> eans = new HashSet<>();
        eans.add("111");
        Product product = new Product();
        product.ean = "111";
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(LIST_QUERY, eans)).thenReturn(List.of(product));
            Map<String, Object> context = resource.processChunkWithFallback(lines, eans, counters, errors);
            assertEquals(1, context.size());
            assertSame(product, context.get("111"));
        }
    }

    /**
     * {@code processChunkWithFallback} queries but indexes nothing when no
     * existing products match (targetEans non-empty arm, loop-not-entered arm).
     */
    @Test
    void processChunkWithFallbackHandlesNoExistingProducts() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", fullParts()));
        Set<String> eans = new HashSet<>();
        eans.add("111");
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(LIST_QUERY, eans)).thenReturn(List.of());
            Map<String, Object> context = resource.processChunkWithFallback(lines, eans, counters, errors);
            assertTrue(context.isEmpty());
        }
    }

    /**
     * {@code processChunkWithFallback} with an empty EAN set skips the product
     * query entirely (the {@code !targetEans.isEmpty} false arm) and returns an
     * empty context.
     */
    @Test
    void processChunkWithFallbackSkipsQueryForEmptyEans() {
        ProductCsvResource resource = new ProductCsvResource();
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(1, "111", fullParts()));
        Map<String, Object> context = resource.processChunkWithFallback(lines, new HashSet<>(), new int[]{0, 0}, new ArrayList<>());
        assertTrue(context.isEmpty());
    }

    /**
     * {@code processLineLogic} creates a fresh product when the context map
     * lacks the line's EAN ({@code product == null} true arm), populating every
     * field via {@code feedProduct}, incrementing the created counter and
     * persisting through the entity manager.
     */
    @Test
    void processLineLogicCreatesNewProductWhenAbsent() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("111", persisted.ean);
            assertEquals("Milk", persisted.name);
            assertEquals("Fresh milk", persisted.description);
            assertEquals("BrandX", persisted.brand);
            assertEquals(new BigDecimal("1.500"), persisted.referenceWeight);
            assertEquals(new BigDecimal("2.000"), persisted.referenceVolume);
            assertEquals(ProductType.UNIT, persisted.productType);
            assertEquals("kg", persisted.unitName);
            assertTrue(persisted.active);
        }
    }

    /**
     * {@code processLineLogic} updates an existing product when the stored
     * checksum differs from the incoming one ({@code product == null} false arm,
     * checksum-mismatch true arm): it re-reads the entity fresh by id, re-feeds
     * it and increments the updated counter.
     */
    @Test
    void processLineLogicUpdatesExistingProductWhenChecksumDiffers() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Product existing = new Product();
        existing.id = 42L;
        existing.ean = "111";
        existing.checksum = incomingChecksum(resource, data) + 1;
        Map<String, Object> context = new HashMap<>();
        context.put("111", existing);
        Product fresh = new Product();
        fresh.ean = "111";
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(42L)).thenReturn(fresh);
            resource.processLineLogic(data, context, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", fresh.name);
            assertEquals("Fresh milk", fresh.description);
            assertEquals("BrandX", fresh.brand);
            assertEquals(new BigDecimal("1.500"), fresh.referenceWeight);
            assertEquals(new BigDecimal("2.000"), fresh.referenceVolume);
            assertEquals(ProductType.UNIT, fresh.productType);
            assertEquals("kg", fresh.unitName);
            assertTrue(fresh.active);
        }
    }

    /**
     * {@code processLineLogic} leaves the counters untouched and re-reads
     * nothing when the existing product already matches the incoming checksum
     * (checksum-mismatch false arm).
     */
    @Test
    void processLineLogicSkipsUpdateWhenChecksumMatches() throws Exception {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Product existing = new Product();
        existing.id = 42L;
        existing.ean = "111";
        existing.checksum = incomingChecksum(resource, data);
        Map<String, Object> context = new HashMap<>();
        context.put("111", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, context, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * {@code findEntityForLine} performs the EAN lookup used by the 1-by-1
     * fallback and returns the first matching product.
     */
    @Test
    void findEntityForLineLooksUpProductByEan() {
        ProductCsvResource resource = new ProductCsvResource();
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "111", fullParts());
        Product product = new Product();
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find(FIND_QUERY, "111")).thenReturn(query);
            when(query.firstResult()).thenReturn(product);
            assertSame(product, resource.findEntityForLine(data));
        }
    }

    /**
     * {@code safeParseProductType} resolves a known enum constant
     * case-insensitively (both guards false, {@code valueOf} succeeds).
     */
    @Test
    void safeParseProductTypeResolvesKnownConstant() {
        ProductCsvResource resource = new ProductCsvResource();
        assertEquals(ProductType.WEIGHT, resource.safeParseProductType(new String[]{"111", " weight "}, 1));
    }

    /**
     * {@code safeParseProductType} returns null for an unknown value, exercising
     * the {@link IllegalArgumentException} catch arm (both guards false,
     * {@code valueOf} throws).
     */
    @Test
    void safeParseProductTypeReturnsNullForUnknownValue() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(new String[]{"111", "GAS"}, 1));
    }

    /**
     * {@code safeParseProductType} returns null when the index is out of bounds
     * (the {@code index >= parts.length} true arm).
     */
    @Test
    void safeParseProductTypeReturnsNullForOutOfBoundsIndex() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(new String[]{"111"}, 6));
    }

    /**
     * {@code safeParseProductType} returns null when the cell trims to empty
     * (index in bounds, {@code val.isEmpty} true arm).
     */
    @Test
    void safeParseProductTypeReturnsNullForBlankValue() {
        ProductCsvResource resource = new ProductCsvResource();
        assertNull(resource.safeParseProductType(new String[]{"111", "   "}, 1));
    }

    /**
     * {@code processLineLogic} defaults {@code active} to false and leaves
     * optional columns null when they are blank, confirming the create path
     * routes blanks through the parent safe-parse helpers.
     */
    @Test
    void processLineLogicCreatesProductWithBlankOptionalColumns() {
        ProductCsvResource resource = new ProductCsvResource();
        String[] parts = {"222", "Bread", "", "", "", "", "", "", ""};
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, "222", parts);
        Map<String, Object> context = new HashMap<>();
        int[] counters = {0, 0};
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(data, context, counters);
            ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
            verify(em).persist(captor.capture());
            Product persisted = captor.getValue();
            assertEquals("222", persisted.ean);
            assertEquals("Bread", persisted.name);
            assertEquals("", persisted.description);
            assertEquals("", persisted.brand);
            assertNull(persisted.referenceWeight);
            assertNull(persisted.referenceVolume);
            assertNull(persisted.productType);
            assertEquals("", persisted.unitName);
            assertFalse(persisted.active);
        }
    }
}
