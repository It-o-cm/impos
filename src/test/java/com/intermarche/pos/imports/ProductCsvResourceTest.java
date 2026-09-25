package com.intermarche.pos.imports;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.catalog.ProductType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductCsvResource}.
 * <p>
 * The resource is the {@link Product}-specific concretion of the abstract
 * {@link ImporterCsvResource}: for a chunk it bulk-fetches the products the
 * file names, then for each line creates a product or, on an existing one,
 * compares checksums through {@code changedFrom} to decide the update. Its
 * finders resolve to {@link PanacheEntityBase} under plain {@code mvn test}
 * and are intercepted with {@link org.mockito.Mockito#mockStatic} inside a
 * try-with-resources block; {@code Panache.getEntityManager()} is stubbed the
 * same way so {@code persist} is observable, and
 * {@link ProductFamily#fileUnderNomenclature} is stubbed as a static so the
 * filing side-call is verifiable. Private helpers are reached by reflection so
 * both arms of every guard carry absolute expected values. No database and no
 * Quarkus context is booted.
 */
class ProductCsvResourceTest {

    /** The resource under test. */
    private final ProductCsvResource resource = new ProductCsvResource();

    /**
     * Builds a parsed line over the given ordered column → value cells; the
     * header declares exactly the named columns, which is what
     * {@code LineData.has} keys on.
     *
     * @param cells the column-name → cell-value pairs, in order
     * @return the parsed line, keyed on EAN
     */
    private ImporterCsvResource.LineData line(Map<String, String> cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        String[] parts = new String[cells.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : cells.entrySet()) {
            header.put(entry.getKey(), index);
            parts[index] = entry.getValue();
            index++;
        }
        return new ImporterCsvResource.LineData(1, header, parts, ProductCsvResource.COL_EAN);
    }

    /**
     * Builds a line declaring every column this importer knows, all filled.
     *
     * @return a fully-populated parsed line
     */
    private ImporterCsvResource.LineData fullLine() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_DESCRIPTION, "Whole milk");
        cells.put(ProductCsvResource.COL_BRAND, "DairyCo");
        cells.put(ProductCsvResource.COL_REFERENCE_WEIGHT, "1.000");
        cells.put(ProductCsvResource.COL_REFERENCE_VOLUME, "1.000");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "unit");
        cells.put(ProductCsvResource.COL_UNIT_NAME, "L");
        cells.put(ProductCsvResource.COL_ACTIVE, "true");
        cells.put(ProductCsvResource.COL_PLU, "1234");
        cells.put(ProductCsvResource.COL_ICON, "milk");
        cells.put(ProductCsvResource.COL_FORBIDDEN_TO_SALE, "true");
        cells.put(ProductCsvResource.COL_CHECKOUT_LABEL, "MILK 1L");
        cells.put(ProductCsvResource.COL_INTERNAL_CODE, "INT-1");
        cells.put(ProductCsvResource.COL_VARIABLE_WEIGHT, "true");
        cells.put(ProductCsvResource.COL_ATTRIBUTES, "MEAL_VOUCHER_ELIGIBLE=true;DISCOUNT_FORBIDDEN=false");
        return line(cells);
    }

    /**
     * Builds a line declaring only the required columns, so every optional
     * column reports absent and the checksum falls back to the existing row.
     *
     * @return a required-columns-only parsed line
     */
    private ImporterCsvResource.LineData requiredOnlyLine() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000002");
        cells.put(ProductCsvResource.COL_NAME, "Bread");
        cells.put(ProductCsvResource.COL_DESCRIPTION, "Baguette");
        cells.put(ProductCsvResource.COL_BRAND, "Boulangerie");
        cells.put(ProductCsvResource.COL_REFERENCE_WEIGHT, "0.250");
        cells.put(ProductCsvResource.COL_REFERENCE_VOLUME, "0.000");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "unit");
        cells.put(ProductCsvResource.COL_UNIT_NAME, "pcs");
        cells.put(ProductCsvResource.COL_ACTIVE, "true");
        return line(cells);
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
        Method method = ProductCsvResource.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    /**
     * {@code importProducts} delegates to the base stream importer with the
     * EAN key and the required columns: a header missing them is rejected
     * with a BAD_REQUEST before any collaborator is touched.
     */
    @Test
    void importProductsDelegatesToTheBaseStreamImporter() {
        ByteArrayInputStream stream = new ByteArrayInputStream(
                "EAN".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importProducts(stream);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    /**
     * The importer names the PRODUCTS engine feed, so the raw file is retained
     * verbatim for the valuation engines.
     */
    @Test
    void importerNamesTheProductsEngineFeed() {
        assertEquals("PRODUCTS", resource.feedCode());
    }

    /**
     * {@code processChunkWithFallback} answers an empty map on an empty chunk
     * without querying (true arm of the emptiness guard).
     */
    @Test
    void processChunkWithFallbackAnswersEmptyOnEmptyChunk() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(), Set.of(), new int[]{0, 0}, new java.util.ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * A non-empty chunk that names no key skips the bulk fetch and answers an
     * empty context (false arm of the chunk guard, false arm of the key
     * guard).
     */
    @Test
    void processChunkWithFallbackSkipsFetchWhenNoKey() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(fullLine()), Set.of(), new int[]{0, 0}, new java.util.ArrayList<>());
            assertTrue(context.isEmpty());
            panache.verifyNoInteractions();
        }
    }

    /**
     * A non-empty chunk with keys bulk-fetches the products and indexes them
     * by their EAN (false arm of the chunk guard, true arm of the key guard,
     * loop body).
     */
    @Test
    void processChunkWithFallbackIndexesTheFetchedProducts() {
        Product existing = new Product();
        existing.ean = "3000000000001";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.list(eq("ean IN ?1"),
                    org.mockito.ArgumentMatchers.<Object>any()))
                    .thenReturn(List.of(existing));
            Map<String, Object> context = resource.processChunkWithFallback(
                    List.of(fullLine()), Set.of("3000000000001"),
                    new int[]{0, 0}, new java.util.ArrayList<>());
            assertEquals(1, context.size());
            assertSame(existing, context.get("3000000000001"));
        }
    }

    /**
     * A code the referential does not carry yet creates a product, counted as
     * a creation and persisted (true arm of the novelty guard), with no
     * nomenclature filing when the column is absent (false arm of that guard).
     */
    @Test
    void processLineLogicCreatesAnUnknownProduct() {
        int[] counters = new int[]{0, 0};
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(fullLine(), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(em).persist(captor.capture());
            Product created = (Product) captor.getValue();
            assertEquals("3000000000001", created.ean);
            assertEquals("Milk", created.name);
            assertEquals("1234", created.plu);
        }
    }

    /**
     * A known code whose checksum moved is reloaded and refed, counted as an
     * update (false arm of the novelty guard, true arm of the change guard).
     */
    @Test
    void processLineLogicUpdatesAChangedProduct() {
        Product existing = new Product();
        existing.id = 5L;
        existing.ean = "3000000000001";
        existing.name = "Old milk";
        existing.checksum = 0;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(5L)).thenReturn(existing);
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", existing.name);
        }
    }

    /**
     * A known code whose stored checksum is null is treated as changed and
     * updated (true arm of {@code changedFrom}'s null branch at the call
     * site).
     */
    @Test
    void processLineLogicUpdatesAProductWithNoStoredChecksum() {
        Product existing = new Product();
        existing.id = 6L;
        existing.ean = "3000000000001";
        existing.checksum = null;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.findById(6L)).thenReturn(existing);
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
        }
    }

    /**
     * A known code whose checksum matches the incoming one is left untouched
     * and counted neither way (false arm of the change guard); no reload is
     * issued.
     */
    @Test
    void processLineLogicSkipsAnUnchangedProduct() throws Exception {
        Product existing = new Product();
        existing.id = 7L;
        existing.ean = "3000000000001";
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        int incoming = (int) compute.invoke(resource, fullLine(), existing);
        existing.checksum = incoming;
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            resource.processLineLogic(fullLine(), entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            panache.verify(() -> Product.findById(any()), never());
        }
    }

    /**
     * When the feed declares the NOMENCLATURE column, the article is filed
     * under the named node even on the skip path — the filing is outside the
     * checksum shortcut (true arm of the nomenclature guard).
     */
    @Test
    void processLineLogicFilesUnderNomenclatureWhenDeclared() throws Exception {
        Product existing = new Product();
        existing.id = 8L;
        existing.ean = "3000000000001";
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_NOMENCLATURE, "SF01");
        ImporterCsvResource.LineData data = line(cells);
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        existing.checksum = (int) compute.invoke(resource, data, existing);
        Map<String, Object> entityMap = new HashMap<>();
        entityMap.put("3000000000001", existing);
        int[] counters = new int[]{0, 0};
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.fileUnderNomenclature(existing, "SF01"))
                    .thenReturn(true);
            resource.processLineLogic(data, entityMap, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            families.verify(() -> ProductFamily.fileUnderNomenclature(existing, "SF01"));
        }
    }

    /**
     * {@code findEntityForLine} resolves the line's product through the EAN
     * finder.
     */
    @Test
    void findEntityForLineResolvesThroughTheEanFinder() {
        Product existing = new Product();
        existing.ean = "3000000000001";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(existing);
            panache.when(() -> Product.find("ean", "3000000000001")).thenReturn(query);
            assertSame(existing, resource.findEntityForLine(fullLine()));
        }
    }

    /**
     * {@code feedProduct} copies every declared column onto the product,
     * including all optional register-only ones (true arm of every
     * {@code has} guard) and the non-empty PLU (false arm of the PLU
     * ternary).
     */
    @Test
    void feedProductPopulatesEveryDeclaredColumn() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product product = new Product();
        method.invoke(resource, fullLine(), product);
        assertEquals("Milk", product.name);
        assertEquals("Whole milk", product.description);
        assertEquals("DairyCo", product.brand);
        assertEquals(new BigDecimal("1.000"), product.referenceWeight);
        assertEquals(new BigDecimal("1.000"), product.referenceVolume);
        assertEquals(ProductType.UNIT, product.productType);
        assertEquals("L", product.unitName);
        assertTrue(product.active);
        assertEquals("1234", product.plu);
        assertEquals("milk", product.icon);
        assertTrue(product.forbiddenToSale);
        assertEquals("MILK 1L", product.checkoutLabel);
        assertEquals("INT-1", product.internalCode);
        assertTrue(product.variableWeight);
        assertEquals("true", product.attributes.get("MEAL_VOUCHER_ELIGIBLE"));
        assertEquals("false", product.attributes.get("DISCOUNT_FORBIDDEN"));
    }

    /**
     * {@code feedProduct} leaves the optional register-only fields untouched
     * when the feed declares none of their columns (false arm of every
     * {@code has} guard).
     */
    @Test
    void feedProductLeavesOptionalFieldsUntouchedWhenAbsent() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product product = new Product();
        product.plu = "9999";
        product.icon = "kept";
        product.forbiddenToSale = true;
        product.checkoutLabel = "KEPT";
        product.internalCode = "KEPT-IC";
        product.variableWeight = true;
        product.attributes.put("KEEP", "yes");
        method.invoke(resource, requiredOnlyLine(), product);
        assertEquals("Bread", product.name);
        assertEquals("9999", product.plu);
        assertEquals("kept", product.icon);
        assertTrue(product.forbiddenToSale);
        assertEquals("KEPT", product.checkoutLabel);
        assertEquals("KEPT-IC", product.internalCode);
        assertTrue(product.variableWeight);
        assertEquals("yes", product.attributes.get("KEEP"));
    }

    /**
     * A declared but empty PLU cell lands as null, never the empty string
     * (right operand of the PLU disjunction: null false, isEmpty true).
     */
    @Test
    void feedProductNullsAnEmptyPlu() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_PLU, "");
        Product product = new Product();
        product.plu = "before";
        method.invoke(resource, line(cells), product);
        assertNull(product.plu);
    }

    /**
     * A declared PLU column whose cell is null lands as null (left operand of
     * the PLU disjunction: plu is null).
     */
    @Test
    void feedProductNullsAMissingPluCell() throws Exception {
        Method method = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_NAME, "Milk");
        cells.put(ProductCsvResource.COL_PLU, null);
        Product product = new Product();
        product.plu = "before";
        method.invoke(resource, line(cells), product);
        assertNull(product.plu);
    }

    /**
     * {@code mergeAttributes} merges nothing from a null cell (left operand of
     * the emptiness disjunction).
     */
    @Test
    void mergeAttributesIgnoresANullCell() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        target.put("KEEP", "yes");
        method.invoke(resource, target, (String) null);
        assertEquals(1, target.size());
        assertEquals("yes", target.get("KEEP"));
    }

    /**
     * {@code mergeAttributes} merges nothing from an empty cell (right operand
     * of the emptiness disjunction).
     */
    @Test
    void mergeAttributesIgnoresAnEmptyCell() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        target.put("KEEP", "yes");
        method.invoke(resource, target, "");
        assertEquals(1, target.size());
    }

    /**
     * {@code mergeAttributes} poses the well-formed pairs, skips a token with
     * no {@code =} (true arm of the eq guard) and a token with a blank code
     * (true arm of the code guard), covering the false arms via the valid
     * pairs.
     */
    @Test
    void mergeAttributesPosesValidPairsAndSkipsMalformedTokens() throws Exception {
        Method method = privateMethod("mergeAttributes", Map.class, String.class);
        Map<String, String> target = new HashMap<>();
        method.invoke(resource, target, "A=1;novalue;=orphan; B =2");
        assertEquals(2, target.size());
        assertEquals("1", target.get("A"));
        assertEquals("2", target.get("B"));
        assertFalse(target.containsKey("novalue"));
    }

    /**
     * {@code incomingAttributes} starts from an empty map when the product
     * carries no attributes and adds nothing when the column is absent (true
     * arm of the null-attributes ternary, false arm of the column guard).
     */
    @Test
    void incomingAttributesHandlesNullAttributesAndAbsentColumn() throws Exception {
        Method method = privateMethod("incomingAttributes",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.attributes = null;
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(
                resource, requiredOnlyLine(), existing);
        assertTrue(result.isEmpty());
    }

    /**
     * {@code incomingAttributes} copies the product's current attributes and
     * merges the declared cell on top (false arm of the null-attributes
     * ternary, true arm of the column guard).
     */
    @Test
    void incomingAttributesCopiesCurrentAndMergesDeclaredCell() throws Exception {
        Method method = privateMethod("incomingAttributes",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.attributes = new HashMap<>();
        existing.attributes.put("OLD", "1");
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_ATTRIBUTES, "NEW=2");
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(
                resource, line(cells), existing);
        assertEquals(2, result.size());
        assertEquals("1", result.get("OLD"));
        assertEquals("2", result.get("NEW"));
    }

    /**
     * With every optional column declared, {@code computeIncomingChecksum}
     * reproduces the checksum the product would carry after {@code feedProduct}
     * (true arm of every column ternary, false arm of the plu-null ternary).
     */
    @Test
    void computeIncomingChecksumMirrorsFeedProductWhenAllColumnsDeclared() throws Exception {
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        Method feed = privateMethod("feedProduct",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        int incoming = (int) compute.invoke(resource, fullLine(), existing);
        Product expected = new Product();
        feed.invoke(resource, fullLine(), expected);
        expected.ean = "3000000000001";
        assertEquals(expected.getChecksum(), incoming);
    }

    /**
     * With no optional column declared, {@code computeIncomingChecksum} falls
     * back to the existing row's optional fields (false arm of every column
     * ternary) and hashes the empty stored PLU as "" (true arm of the plu-null
     * ternary).
     */
    @Test
    void computeIncomingChecksumFallsBackToExistingWhenColumnsAbsent() throws Exception {
        Method compute = privateMethod("computeIncomingChecksum",
                ImporterCsvResource.LineData.class, Product.class);
        Product existing = new Product();
        existing.plu = null;
        existing.forbiddenToSale = true;
        existing.checkoutLabel = "CL";
        existing.internalCode = "IC";
        existing.variableWeight = true;
        existing.attributes = new HashMap<>();
        existing.attributes.put("K", "V");
        int incoming = (int) compute.invoke(resource, requiredOnlyLine(), existing);
        Product expected = new Product();
        expected.ean = "3000000000002";
        expected.plu = null;
        expected.name = "Bread";
        expected.description = "Baguette";
        expected.brand = "Boulangerie";
        expected.referenceWeight = new BigDecimal("0.250");
        expected.referenceVolume = new BigDecimal("0.000");
        expected.productType = ProductType.UNIT;
        expected.unitName = "pcs";
        expected.active = true;
        expected.forbiddenToSale = true;
        expected.checkoutLabel = "CL";
        expected.internalCode = "IC";
        expected.variableWeight = true;
        expected.attributes = new HashMap<>();
        expected.attributes.put("K", "V");
        assertEquals(expected.getChecksum(), incoming);
    }

    /**
     * {@code safeParseProductType} answers null on an absent column, whose
     * cell reads null (left operand of the disjunction).
     */
    @Test
    void safeParseProductTypeAnswersNullOnAbsentColumn() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} answers null on an empty cell (right operand
     * of the disjunction).
     */
    @Test
    void safeParseProductTypeAnswersNullOnEmptyCell() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} upper-cases and resolves a well-formed cell
     * (the nominal arm).
     */
    @Test
    void safeParseProductTypeResolvesAWellFormedCell() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "weight");
        assertEquals(ProductType.WEIGHT,
                resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }

    /**
     * {@code safeParseProductType} answers null on an unknown value (the catch
     * arm).
     */
    @Test
    void safeParseProductTypeAnswersNullOnUnknownValue() {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put(ProductCsvResource.COL_EAN, "3000000000001");
        cells.put(ProductCsvResource.COL_PRODUCT_TYPE, "BOGUS");
        assertNull(resource.safeParseProductType(line(cells), ProductCsvResource.COL_PRODUCT_TYPE));
    }
}
