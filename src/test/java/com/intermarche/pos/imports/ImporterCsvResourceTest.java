package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.EngineFeedService;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the abstract base {@link ImporterCsvResource}.
 * <p>
 * The class is exercised through a minimal concrete subclass ({@link TestImporter})
 * whose abstract hooks are simple, controllable stubs; the JTA transaction wrapper
 * is driven with a mocked {@link TransactionManager} injected into the {@code tm}
 * field and a {@code mockStatic(Panache.class)} standing in for the entity manager.
 * No Quarkus context and no database are booted. Every parsing helper, the header
 * builder, the missing-column logic, the JSON answer builder, the staged-fallback
 * recursion, the {@link ImporterCsvResource.Executor} container and the
 * {@link ImporterCsvResource.LineData} accessor are covered arm by arm.
 */
class ImporterCsvResourceTest {

    /**
     * A concrete, controllable importer used to reach the protected/abstract flow.
     */
    static class TestImporter extends ImporterCsvResource {
        /** When true, the chunk hook throws to exercise the outer Throwable catch. */
        boolean chunkThrows = false;
        /** When true, the per-line logic throws to exercise transaction failure. */
        boolean lineLogicThrows = false;
        /** The entity the fallback lookup returns (null models "not found"). */
        Object entity = null;
        /** The engine-feed code this importer captures (null models no capture). */
        String feed = null;

        /**
         * Names the engine feed captured by this importer.
         *
         * @return the configured feed code, possibly null
         */
        @Override
        protected String feedCode() {
            return feed;
        }

        /**
         * Chunk hook stub: throws on demand, otherwise returns a fresh empty map.
         *
         * @param parsedLines the chunk lines
         * @param targetCodes the chunk key codes
         * @param counters the running counters
         * @param errors the error sink
         * @return an empty pre-fetched map
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            if (chunkThrows) throw new RuntimeException("chunk boom");
            return new HashMap<>();
        }

        /**
         * Per-line logic stub: throws on demand, otherwise counts one creation.
         *
         * @param data the parsed row
         * @param entityMap the context map
         * @param counters the running counters
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            if (lineLogicThrows) throw new RuntimeException("line boom");
            counters[0]++;
        }

        /**
         * Fallback lookup stub returning the configured entity.
         *
         * @param data the parsed row
         * @return the configured entity, possibly null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return entity;
        }
    }

    /**
     * A subclass that does NOT override {@link ImporterCsvResource#feedCode()},
     * used to exercise the base default return.
     */
    static class DefaultImporter extends ImporterCsvResource {
        /**
         * Chunk hook stub returning an empty map.
         *
         * @param parsedLines the chunk lines
         * @param targetCodes the chunk key codes
         * @param counters the running counters
         * @param errors the error sink
         * @return an empty pre-fetched map
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            return new HashMap<>();
        }

        /**
         * Per-line logic stub doing nothing.
         *
         * @param data the parsed row
         * @param entityMap the context map
         * @param counters the running counters
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        }

        /**
         * Fallback lookup stub returning null.
         *
         * @param data the parsed row
         * @return null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return null;
        }
    }

    /**
     * Builds a {@link ImporterCsvResource.LineData} from column names and cells,
     * the first column being the key.
     *
     * @param columns the header names, in column order
     * @param cells the cells of the row
     * @return the parsed row
     */
    private ImporterCsvResource.LineData rowOf(List<String> columns, String... cells) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            header.put(columns.get(index), index);
        }
        return new ImporterCsvResource.LineData(1, header, cells, columns.get(0));
    }

    /**
     * Builds a fresh {@link TestImporter} with a mocked transaction manager wired
     * into its {@code tm} field.
     *
     * @return the wired importer
     */
    private TestImporter importerWithTm() {
        TestImporter importer = new TestImporter();
        importer.tm = mock(TransactionManager.class);
        return importer;
    }

    // ---------------------------------------------------------------
    // feedCode default
    // ---------------------------------------------------------------

    /**
     * A subclass that does not override {@code feedCode} names no feed.
     */
    @Test
    void baseFeedCodeIsNull() {
        assertNull(new DefaultImporter().feedCode());
    }

    // ---------------------------------------------------------------
    // LineData accessor: get / has
    // ---------------------------------------------------------------

    /**
     * A column absent from the header, a cell beyond the row, a null cell and a
     * present cell exercise each arm of {@link ImporterCsvResource.LineData#get}.
     */
    @Test
    void lineDataGetCoversEveryArm() {
        Map<String, Integer> header = new LinkedHashMap<>();
        header.put("C", 0);
        header.put("D", 1);
        ImporterCsvResource.LineData data = new ImporterCsvResource.LineData(1, header, new String[]{" x "}, "C");
        assertEquals("x", data.get("C"));
        assertNull(data.get("Z"));
        assertNull(data.get("D"));
        Map<String, Integer> h2 = new LinkedHashMap<>();
        h2.put("C", 0);
        ImporterCsvResource.LineData nullCell = new ImporterCsvResource.LineData(1, h2, new String[]{null}, "C");
        assertNull(nullCell.get("C"));
        assertNull(nullCell.code);
    }

    /**
     * {@link ImporterCsvResource.LineData#has} reports declared and undeclared
     * columns.
     */
    @Test
    void lineDataHasReportsDeclaration() {
        ImporterCsvResource.LineData data = rowOf(List.of("C", "D"), "1", "2");
        assertTrue(data.has("C"));
        assertFalse(data.has("Z"));
    }

    // ---------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------

    /**
     * {@code safeGet} returns the trimmed cell or null when absent.
     */
    @Test
    void safeGetReturnsCellOrNull() {
        TestImporter importer = new TestImporter();
        assertEquals("v", importer.safeGet(rowOf(List.of("C"), " v "), "C"));
        assertNull(importer.safeGet(rowOf(List.of("C"), "v"), "Z"));
    }

    /**
     * {@code safeParseBoolean} is false for a null column, an empty cell and an
     * unparseable value, and true for {@code true}.
     */
    @Test
    void safeParseBooleanCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), "v"), "Z"));
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), ""), "C"));
        assertFalse(importer.safeParseBoolean(rowOf(List.of("C"), "notabool"), "C"));
        assertTrue(importer.safeParseBoolean(rowOf(List.of("C"), "true"), "C"));
    }

    /**
     * {@code safeParseBigDecimal} is null for null/empty/malformed input and
     * parses a valid decimal.
     */
    @Test
    void safeParseBigDecimalCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseBigDecimal(rowOf(List.of("C"), "abc"), "C"));
        assertEquals(new BigDecimal("12.5"), importer.safeParseBigDecimal(rowOf(List.of("C"), "12.5"), "C"));
    }

    /**
     * {@code safeParseInt} is null for null/empty/malformed input and parses a
     * valid integer.
     */
    @Test
    void safeParseIntCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseInt(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseInt(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseInt(rowOf(List.of("C"), "x"), "C"));
        assertEquals(Integer.valueOf(42), importer.safeParseInt(rowOf(List.of("C"), "42"), "C"));
    }

    /**
     * {@code safeParseDateTime} is null for null/empty/malformed input and parses
     * a valid ISO date-time.
     */
    @Test
    void safeParseDateTimeCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseDateTime(rowOf(List.of("C"), "not-a-date"), "C"));
        assertEquals(LocalDateTime.of(2025, 1, 1, 10, 15, 30),
                importer.safeParseDateTime(rowOf(List.of("C"), "2025-01-01T10:15:30"), "C"));
    }

    /**
     * {@code safeParseDouble} is null for null/empty/malformed input and parses a
     * valid double.
     */
    @Test
    void safeParseDoubleCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), "v"), "Z"));
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), ""), "C"));
        assertNull(importer.safeParseDouble(rowOf(List.of("C"), "x"), "C"));
        assertEquals(Double.valueOf(1.5), importer.safeParseDouble(rowOf(List.of("C"), "1.5"), "C"));
    }

    /**
     * {@code parseCodes} yields an empty list for null and blank input, and a
     * trimmed, filtered, sorted list otherwise.
     */
    @Test
    void parseCodesCoversEveryArm() {
        TestImporter importer = new TestImporter();
        assertTrue(importer.parseCodes(null).isEmpty());
        assertTrue(importer.parseCodes("   ").isEmpty());
        assertEquals(List.of("A", "B", "C"), importer.parseCodes(" C , A ,, B "));
    }

    // ---------------------------------------------------------------
    // updateCounters
    // ---------------------------------------------------------------

    /**
     * {@code updateCounters} adds the local counters into the global ones.
     */
    @Test
    void updateCountersAddsBothSlots() {
        TestImporter importer = new TestImporter();
        int[] global = {2, 3};
        importer.updateCounters(global, new int[]{4, 5});
        assertEquals(6, global[0]);
        assertEquals(8, global[1]);
    }

    // ---------------------------------------------------------------
    // getNextSize
    // ---------------------------------------------------------------

    /**
     * {@code getNextSize} steps 1000 -> 100 -> 10 -> 1 across all three returns.
     */
    @Test
    void getNextSizeStepsDown() {
        TestImporter importer = new TestImporter();
        assertEquals(100, importer.getNextSize(1000));
        assertEquals(10, importer.getNextSize(100));
        assertEquals(1, importer.getNextSize(10));
    }

    // ---------------------------------------------------------------
    // Executor
    // ---------------------------------------------------------------

    /**
     * {@code onSuccess} fires only when a result is present.
     */
    @Test
    void executorOnSuccessFiresOnlyWithResult() {
        ImporterCsvResource.Executor<String> withResult = new ImporterCsvResource.Executor<>();
        withResult.setResult("ok");
        AtomicReference<String> seen = new AtomicReference<>(null);
        withResult.onSuccess(seen::set);
        assertEquals("ok", seen.get());
        ImporterCsvResource.Executor<String> noResult = new ImporterCsvResource.Executor<>();
        AtomicReference<String> seen2 = new AtomicReference<>("untouched");
        noResult.onSuccess(seen2::set);
        assertEquals("untouched", seen2.get());
    }

    /**
     * {@code onFailure} fires only when both an exception and a consumer are
     * present, covering the two false arms and the true arm.
     */
    @Test
    void executorOnFailureFiresOnlyWithExceptionAndConsumer() {
        ImporterCsvResource.Executor<String> withEx = new ImporterCsvResource.Executor<>();
        withEx.setException(new RuntimeException("boom"));
        AtomicReference<Throwable> seen = new AtomicReference<>(null);
        withEx.onFailure(seen::set);
        assertNotNull(seen.get());
        withEx.onFailure(null);
        ImporterCsvResource.Executor<String> noEx = new ImporterCsvResource.Executor<>();
        AtomicReference<Throwable> seen3 = new AtomicReference<>(null);
        noEx.onFailure(seen3::set);
        assertNull(seen3.get());
    }

    // ---------------------------------------------------------------
    // withTransaction
    // ---------------------------------------------------------------

    /**
     * A successful supplier commits, sets the result and clears the entity manager.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionCommitsOnSuccess() throws Exception {
        TestImporter importer = importerWithTm();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> "done");
            assertEquals("done", executor.result);
            assertNull(executor.ex);
            verify(importer.tm).commit();
            verify(em).clear();
        }
    }

    /**
     * A throwing supplier with an ACTIVE transaction rolls back and records the
     * exception.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionRollsBackActiveTransaction() throws Exception {
        TestImporter importer = importerWithTm();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor =
                    importer.<String>withTransaction(() -> { throw new RuntimeException("boom"); });
            assertNull(executor.result);
            assertNotNull(executor.ex);
            verify(importer.tm).rollback();
            verify(em).clear();
        }
    }

    /**
     * A throwing supplier with NO active transaction skips the rollback.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionSkipsRollbackWhenNoTransaction() throws Exception {
        TestImporter importer = importerWithTm();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor =
                    importer.<String>withTransaction(() -> { throw new RuntimeException("boom"); });
            assertNotNull(executor.ex);
            verify(importer.tm, never()).rollback();
        }
    }

    /**
     * A rollback that itself throws is swallowed while the original exception is
     * still recorded.
     *
     * @throws Exception never (declared by the transaction manager stubs)
     */
    @Test
    void withTransactionSwallowsRollbackFailure() throws Exception {
        TestImporter importer = importerWithTm();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        doThrow(new RuntimeException("rb")).when(importer.tm).rollback();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor =
                    importer.<String>withTransaction(() -> { throw new RuntimeException("boom"); });
            assertNotNull(executor.ex);
            assertEquals("boom", executor.ex.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // prepareContextForLine
    // ---------------------------------------------------------------

    /**
     * A found entity is keyed by its code; a missing one leaves the map empty.
     */
    @Test
    void prepareContextForLineCoversBothArms() {
        TestImporter importer = new TestImporter();
        importer.entity = "E";
        ImporterCsvResource.LineData data = rowOf(List.of("C"), "K1");
        Map<String, Object> withEntity = importer.prepareContextForLine(data);
        assertEquals(1, withEntity.size());
        assertEquals("E", withEntity.get("K1"));
        importer.entity = null;
        assertTrue(importer.prepareContextForLine(data).isEmpty());
    }

    // ---------------------------------------------------------------
    // processWithStages
    // ---------------------------------------------------------------

    /**
     * An empty line list is a no-op.
     */
    @Test
    void processWithStagesIgnoresEmpty() {
        TestImporter importer = new TestImporter();
        int[] counters = {0, 0};
        importer.processWithStages(List.of(), new HashMap<>(), 1000, counters, new ArrayList<>());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * A successful chunk of the batch size updates the global counters.
     */
    @Test
    void processWithStagesSucceedsForABatch() {
        TestImporter importer = importerWithTm();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            importer.processWithStages(List.of(rowOf(List.of("C"), "A"), rowOf(List.of("C"), "B")),
                    new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, new ArrayList<>());
            assertEquals(2, counters[0]);
        }
    }

    /**
     * A chunk that keeps failing recurses 1000 -> 100 -> 10 -> 1 and finally
     * records a per-line error in its own transaction.
     */
    @Test
    void processWithStagesRecursesToLineByLineOnPersistentFailure() {
        TestImporter importer = importerWithTm();
        importer.lineLogicThrows = true;
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            List<String> errors = new ArrayList<>();
            importer.processWithStages(List.of(rowOf(List.of("C"), "A")),
                    new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("line boom"));
        }
    }

    /**
     * The atomic base case (chunk size 1) updates the counters on success.
     */
    @Test
    void processWithStagesAtomicSuccessUpdatesCounters() {
        TestImporter importer = importerWithTm();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            int[] counters = {0, 0};
            importer.processWithStages(List.of(rowOf(List.of("C"), "A")),
                    new HashMap<>(), 1, counters, new ArrayList<>());
            assertEquals(1, counters[0]);
        }
    }

    // ---------------------------------------------------------------
    // importCsvStream
    // ---------------------------------------------------------------

    /**
     * A stream that cannot be read yields a server error naming the failure.
     */
    @Test
    void importFailsWhenStreamCannotBeRead() {
        TestImporter importer = new TestImporter();
        InputStream bad = new InputStream() {
            /**
             * Always fails to read.
             *
             * @return never
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("io");
            }
        };
        Response response = importer.importCsvStream(bad, "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Error reading file"));
    }

    /**
     * A header missing the key and a required column is rejected naming both, the
     * dedup arm skipping the key already listed and the present column being kept.
     */
    @Test
    void importRejectsMissingColumns() {
        TestImporter importer = new TestImporter();
        InputStream stream = new java.io.ByteArrayInputStream("NAME".getBytes(StandardCharsets.UTF_8));
        Response response = importer.importCsvStream(stream, "CODE", List.of("CODE", "NAME", "EXTRA"));
        assertEquals(400, response.getStatus());
        String body = response.getEntity().toString();
        assertTrue(body.contains("CODE"));
        assertTrue(body.contains("EXTRA"));
        assertFalse(body.contains("NAME\""));
    }

    /**
     * An unexpected error raised by the chunk hook is caught and reported.
     */
    @Test
    void importCatchesUnexpectedError() {
        TestImporter importer = new TestImporter();
        importer.chunkThrows = true;
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
        assertEquals(500, response.getStatus());
        assertTrue(response.getEntity().toString().contains("Unexcepted error"));
    }

    /**
     * A file with a duplicate header column, an empty header column, a blank line,
     * a truncated row and an empty-key row imports the valid rows, reports the two
     * anomalies and captures the raw file into the named engine feed.
     */
    @Test
    void importProcessesValidRowsReportsAnomaliesAndCapturesFeed() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = mock(EngineFeedService.class);
        when(importer.engineFeedService.store("PRODUCTS", "")).thenReturn("v1");
        String content = String.join("\n",
                "CODE|CODE|NAME|",
                "",
                "A1|dup|Acme|extra",
                "A2",
                "|Acme",
                "A3|dup|Beta|z");
        InputStream stream = new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            String body = response.getEntity().toString();
            assertTrue(body.contains("\"createdCount\":2"));
            assertTrue(body.contains("\"errors\":["));
            assertTrue(body.contains("fewer cells than the header"));
            assertTrue(body.contains("empty key"));
        }
        verify(importer.engineFeedService).store("PRODUCTS", content);
    }

    /**
     * A clean file imports without errors and, naming no feed, captures nothing.
     */
    @Test
    void importCleanFileHasNoErrorsAndNoCapture() {
        TestImporter importer = importerWithTm();
        importer.engineFeedService = mock(EngineFeedService.class);
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme\nA2|Beta".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            String body = response.getEntity().toString();
            assertTrue(body.contains("\"createdCount\":2"));
            assertFalse(body.contains("errors"));
        }
        verify(importer.engineFeedService, never()).store(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * A named feed with no engine-feed service wired captures nothing, exercising
     * the null-service arm of the capture guard.
     */
    @Test
    void importWithNamedFeedButNoServiceCapturesNothing() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = null;
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * A capture whose store throws never fails the import, exercising the catch
     * arm of the feed capture.
     */
    @Test
    void importSurvivesAFeedCaptureFailure() {
        TestImporter importer = importerWithTm();
        importer.feed = "PRODUCTS";
        importer.engineFeedService = mock(EngineFeedService.class);
        when(importer.engineFeedService.store(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("store boom"));
        InputStream stream = new java.io.ByteArrayInputStream(
                "CODE|NAME\nA1|Acme".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
        }
    }

    /**
     * A file of exactly the batch size flushes the chunk mid-loop, so the tail
     * chunk is empty, exercising the size-threshold arm and the empty-tail arm.
     */
    @Test
    void importFlushesAtBatchSize() {
        TestImporter importer = importerWithTm();
        StringBuilder content = new StringBuilder("CODE|NAME");
        for (int i = 0; i < ImporterCsvResource.STAGE_1_SIZE; i++) {
            content.append("\nA").append(i).append("|N").append(i);
        }
        InputStream stream = new java.io.ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream, "CODE", List.of("NAME"));
            assertEquals(200, response.getStatus());
            assertTrue(response.getEntity().toString().contains(
                    "\"createdCount\":" + ImporterCsvResource.STAGE_1_SIZE));
        }
    }

    /**
     * Sanity that the parse of a comma list is stable regardless of instance,
     * proving no shared state leaks across the parsing helpers.
     */
    @Test
    void parseCodesIsStateless() {
        assertEquals(List.of("X"), new TestImporter().parseCodes("X"));
        assertEquals(Arrays.asList("A", "B"), new TestImporter().parseCodes("B,A"));
    }
}
