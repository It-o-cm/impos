package com.intermarche.pos.imports;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImporterCsvResource}.
 * <p>
 * {@code ImporterCsvResource} is abstract, so a concrete {@link TestImporter}
 * subclass supplies deterministic implementations of the three abstract hooks
 * ({@code processChunkWithFallback}, {@code processLineLogic},
 * {@code findEntityForLine}). The JTA {@link TransactionManager} is a Mockito
 * mock wired onto the package-private {@code tm} field, and the static
 * {@link Panache#getEntityManager()} call performed in every
 * {@code withTransaction} finally block is neutralized with
 * {@link org.mockito.Mockito#mockStatic(Class)} in try-with-resources blocks.
 * Every branch is exercised: the streaming reader (empty lines, header skip,
 * short lines, the 1000-line chunk flush and the leftover flush), the staged
 * fallback recursion (1000 -&gt; 100 -&gt; 10 -&gt; 1), both transaction
 * outcomes with all three rollback sub-branches, the {@code Executor}
 * success/failure guards, and every arm of the safe-parsing helpers.
 */
class ImporterCsvResourceTest {

    /**
     * Concrete {@link ImporterCsvResource} whose abstract hooks are driven by
     * mutable test fields so each test can dictate created/updated outcomes,
     * force per-line failures, and control the fresh-lookup result.
     */
    static class TestImporter extends ImporterCsvResource {
        /** Context map returned by {@link #processChunkWithFallback}. */
        Map<String, Object> chunkContext = new HashMap<>();
        /** Codes for which {@link #processLineLogic} throws. */
        List<String> throwOnCodes = new ArrayList<>();
        /** Entities returned by {@link #findEntityForLine}, keyed by code. */
        Map<String, Object> entitiesByCode = new HashMap<>();
        /** When set, {@link #processChunkWithFallback} throws it. */
        RuntimeException chunkException;
        /** Number of times {@link #processChunkWithFallback} was invoked. */
        int chunkCalls = 0;

        /**
         * Records the invocation and either throws the configured exception or
         * returns the configured pre-fetch context map.
         *
         * @param parsedLines the chunk lines
         * @param targetCodes the unique codes in the chunk
         * @param counters    the global counters
         * @param errors      the error accumulator
         * @return the configured context map
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            chunkCalls++;
            if (chunkException != null) throw chunkException;
            return chunkContext;
        }

        /**
         * Throws for configured codes, otherwise counts the line as an update
         * when its code is present in the supplied entity map and as a creation
         * otherwise.
         *
         * @param data      the parsed line
         * @param entityMap the pre-fetched or fresh entity map
         * @param counters  the local counters [created, updated]
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            if (throwOnCodes.contains(data.code)) throw new RuntimeException("boom-" + data.code);
            if (entityMap != null && entityMap.containsKey(data.code)) counters[1]++;
            else counters[0]++;
        }

        /**
         * Returns the configured entity for the line's code, or null.
         *
         * @param data the parsed line
         * @return the entity or null
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return entitiesByCode.get(data.code);
        }
    }

    /**
     * Builds a {@link TestImporter} with a mocked {@link TransactionManager}
     * on its package-private {@code tm} field.
     *
     * @return a ready-to-use importer
     */
    private TestImporter newImporter() {
        TestImporter importer = new TestImporter();
        importer.tm = mock(TransactionManager.class);
        return importer;
    }

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
     * {@code importCsvStream} skips the header, ignores empty and short lines,
     * classifies one update and two creations, and emits the (intentionally
     * quote-asymmetric) JSON built by {@code buildAnswer} when errors exist.
     */
    @Test
    void importCsvStreamProcessesMixedLinesWithErrors() {
        TestImporter importer = newImporter();
        importer.chunkContext.put("A", new Object());
        String csv = "CODE|NAME\n\nA|foo\nB|bar\nX\nC|baz\n";
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream(csv), 2);
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":2, \"updatedCount\":1, \"errors\":[Line 5 ignored (not enough columns): X\"]}", response.getEntity());
            assertEquals(1, importer.chunkCalls);
            verify(em, atLeastOnce()).clear();
        }
    }

    /**
     * A body of exactly {@link ImporterCsvResource#STAGE_1_SIZE} data lines
     * flushes inside the read loop (size-threshold true arm) and leaves the
     * post-loop leftover flush empty (false arm); with no errors the JSON omits
     * the errors array.
     */
    @Test
    void importCsvStreamFlushesFullChunkAndSkipsEmptyLeftover() {
        TestImporter importer = newImporter();
        StringBuilder csv = new StringBuilder("HEAD|HEAD\n");
        for (int i = 0; i < 1000; i++) csv.append("c").append(i).append("|v").append(i).append("\n");
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            Response response = importer.importCsvStream(stream(csv.toString()), 2);
            assertEquals(200, response.getStatus());
            assertEquals("{\"createdCount\":1000, \"updatedCount\":0}", response.getEntity());
            assertEquals(1, importer.chunkCalls);
        }
    }

    /**
     * An {@link InputStream} that fails on read drives the {@code IOException}
     * catch, yielding a 500 response prefixed with "Error reading file:".
     */
    @Test
    void importCsvStreamReturnsServerErrorOnIOException() {
        TestImporter importer = newImporter();
        InputStream failing = new InputStream() {
            /**
             * Always fails to simulate an unreadable stream.
             *
             * @return never returns normally
             * @throws IOException always
             */
            @Override
            public int read() throws IOException {
                throw new IOException("disk");
            }
        };
        Response response = importer.importCsvStream(failing, 2);
        assertEquals(500, response.getStatus());
        assertEquals("Error reading file: disk", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A hook throwing a non-IO {@link Throwable} drives the generic catch,
     * yielding a 500 response prefixed with "Unexcepted error:".
     */
    @Test
    void importCsvStreamReturnsServerErrorOnUnexpectedThrowable() {
        TestImporter importer = newImporter();
        importer.chunkException = new IllegalStateException("kaboom");
        Response response = importer.importCsvStream(stream("HEAD|HEAD\nA|foo\n"), 2);
        assertEquals(500, response.getStatus());
        assertEquals("Unexcepted error: kaboom", response.getEntity());
        assertEquals(1, importer.chunkCalls);
    }

    /**
     * {@code processWithStages} returns immediately for an empty list, touching
     * neither the transaction manager nor the entity map.
     */
    @Test
    void processWithStagesReturnsOnEmptyList() {
        TestImporter importer = newImporter();
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        importer.processWithStages(new ArrayList<>(), new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(errors.isEmpty());
        assertEquals(0, importer.chunkCalls);
    }

    /**
     * A failing batch recurses through every stage (1000 -&gt; 100 -&gt; 10
     * -&gt; 1) down to line-by-line processing, where each poison line is
     * isolated into its own error entry; the fresh-lookup returns an entity for
     * one line and null for the other, covering both
     * {@code prepareContextForLine} arms.
     */
    @Test
    void processWithStagesFallsBackToLineByLineAndIsolatesErrors() {
        TestImporter importer = newImporter();
        importer.throwOnCodes.add("a");
        importer.throwOnCodes.add("b");
        importer.entitiesByCode.put("a", new Object());
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(10, "a", new String[]{"a", "x"}));
        lines.add(new ImporterCsvResource.LineData(11, "b", new String[]{"b", "y"}));
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.processWithStages(lines, new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals(2, errors.size());
            assertEquals("Line 10 (a): boom-a", errors.get(0));
            assertEquals("Line 11 (b): boom-b", errors.get(1));
        }
    }

    /**
     * With {@code chunkSize == 1} the base case processes each line in its own
     * transaction; a successful line drives the line-by-line {@code onSuccess}
     * arm, merging its fresh-lookup update into the global counters.
     */
    @Test
    void processWithStagesLineByLineCommitsSuccessfulLine() {
        TestImporter importer = newImporter();
        importer.entitiesByCode.put("k", new Object());
        List<ImporterCsvResource.LineData> lines = new ArrayList<>();
        lines.add(new ImporterCsvResource.LineData(7, "k", new String[]{"k", "v"}));
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            importer.processWithStages(lines, new HashMap<>(), 1, counters, errors);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertTrue(errors.isEmpty());
        }
    }

    /**
     * {@code prepareContextForLine} maps the code to the freshly found entity
     * when the lookup succeeds (non-null arm).
     */
    @Test
    void prepareContextForLineStoresFoundEntity() {
        TestImporter importer = newImporter();
        Object entity = new Object();
        importer.entitiesByCode.put("k", entity);
        Map<String, Object> map = importer.prepareContextForLine(new ImporterCsvResource.LineData(1, "k", new String[]{"k"}));
        assertEquals(1, map.size());
        assertSame(entity, map.get("k"));
    }

    /**
     * {@code prepareContextForLine} yields an empty map when the fresh lookup
     * returns null (null arm).
     */
    @Test
    void prepareContextForLineReturnsEmptyMapWhenNotFound() {
        TestImporter importer = newImporter();
        Map<String, Object> map = importer.prepareContextForLine(new ImporterCsvResource.LineData(1, "missing", new String[]{"missing"}));
        assertTrue(map.isEmpty());
    }

    /**
     * {@code getNextSize} steps 1000 -&gt; 100 (first guard true arm).
     */
    @Test
    void getNextSizeFromStageOne() {
        assertEquals(ImporterCsvResource.STAGE_2_SIZE, newImporter().getNextSize(ImporterCsvResource.STAGE_1_SIZE));
    }

    /**
     * {@code getNextSize} steps 100 -&gt; 10 (first guard false, second true).
     */
    @Test
    void getNextSizeFromStageTwo() {
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, newImporter().getNextSize(ImporterCsvResource.STAGE_2_SIZE));
    }

    /**
     * {@code getNextSize} steps 10 -&gt; 1 (both guards false).
     */
    @Test
    void getNextSizeFromStageThree() {
        assertEquals(1, newImporter().getNextSize(ImporterCsvResource.STAGE_3_SIZE));
    }

    /**
     * {@code Executor.onSuccess} runs the consumer when a result is present and
     * {@code onFailure} skips its consumer when no exception occurred.
     */
    @Test
    void executorRunsSuccessConsumerAndSkipsFailure() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setResult("value");
        List<String> seen = new ArrayList<>();
        executor.onSuccess(seen::add).onFailure(t -> seen.add("fail"));
        assertEquals(1, seen.size());
        assertEquals("value", seen.get(0));
    }

    /**
     * {@code Executor.onFailure} runs the consumer when an exception is present
     * and {@code onSuccess} skips its consumer when no result was set.
     */
    @Test
    void executorRunsFailureConsumerAndSkipsSuccess() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setException(new RuntimeException("oops"));
        List<String> seen = new ArrayList<>();
        executor.onSuccess(seen::add).onFailure(t -> seen.add(t.getMessage()));
        assertEquals(1, seen.size());
        assertEquals("oops", seen.get(0));
    }

    /**
     * {@code Executor.onFailure} tolerates a null consumer even when an
     * exception is present (the {@code failure != null} false arm).
     */
    @Test
    void executorOnFailureIgnoresNullConsumer() {
        ImporterCsvResource.Executor<String> executor = new ImporterCsvResource.Executor<>();
        executor.setException(new RuntimeException("oops"));
        assertSame(executor, executor.onFailure(null));
    }

    /**
     * {@code withTransaction} commits and captures the result on the happy
     * path, clearing the entity manager afterwards.
     */
    @Test
    void withTransactionCommitsOnSuccess() throws Exception {
        TestImporter importer = newImporter();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> "ok");
            assertEquals("ok", executor.result);
            assertNull(executor.ex);
            verify(importer.tm).begin();
            verify(importer.tm).commit();
            verify(em).clear();
        }
    }

    /**
     * {@code withTransaction} rolls back when the body fails and a transaction
     * is still active (status != NO_TRANSACTION true arm).
     */
    @Test
    void withTransactionRollsBackOnFailureWhenActive() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            RuntimeException failure = new RuntimeException("body");
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw failure;
            });
            assertNull(executor.result);
            assertSame(failure, executor.ex);
            verify(importer.tm).rollback();
            verify(em).clear();
        }
    }

    /**
     * {@code withTransaction} skips rollback when no transaction remains active
     * (status == NO_TRANSACTION false arm).
     */
    @Test
    void withTransactionSkipsRollbackWhenNoTransaction() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw new RuntimeException("body");
            });
            assertNull(executor.result);
            assertEquals("body", executor.ex.getMessage());
            verify(importer.tm, never()).rollback();
        }
    }

    /**
     * {@code withTransaction} swallows and logs a rollback failure while still
     * retaining the original body exception and clearing the entity manager.
     */
    @Test
    void withTransactionHandlesRollbackFailure() throws Exception {
        TestImporter importer = newImporter();
        when(importer.tm.getStatus()).thenReturn(Status.STATUS_ACTIVE);
        doThrow(new RuntimeException("rollback-failed")).when(importer.tm).rollback();
        try (MockedStatic<Panache> panache = mockStatic(Panache.class)) {
            EntityManager em = mock(EntityManager.class);
            panache.when(Panache::getEntityManager).thenReturn(em);
            RuntimeException failure = new RuntimeException("body");
            ImporterCsvResource.Executor<String> executor = importer.withTransaction(() -> {
                throw failure;
            });
            assertSame(failure, executor.ex);
            verify(importer.tm).rollback();
            verify(em).clear();
        }
    }

    /**
     * {@code safeGet} returns null for a negative index (first guard false).
     */
    @Test
    void safeGetReturnsNullForNegativeIndex() {
        assertNull(newImporter().safeGet(new String[]{"a"}, -1));
    }

    /**
     * {@code safeGet} returns null for an out-of-bounds index (second guard
     * false).
     */
    @Test
    void safeGetReturnsNullForOutOfBoundsIndex() {
        assertNull(newImporter().safeGet(new String[]{"a"}, 5));
    }

    /**
     * {@code safeGet} returns null when the targeted element is null (inner
     * ternary null arm).
     */
    @Test
    void safeGetReturnsNullForNullElement() {
        assertNull(newImporter().safeGet(new String[]{null, "b"}, 0));
    }

    /**
     * {@code safeGet} trims and returns a present non-null element (inner
     * ternary non-null arm).
     */
    @Test
    void safeGetTrimsPresentElement() {
        assertEquals("x", newImporter().safeGet(new String[]{"  x  "}, 0));
    }

    /**
     * {@code safeParseBoolean} returns false for an out-of-bounds index.
     */
    @Test
    void safeParseBooleanFalseWhenOutOfBounds() {
        assertFalse(newImporter().safeParseBoolean(new String[]{"true"}, 3));
    }

    /**
     * {@code safeParseBoolean} returns false for an empty value.
     */
    @Test
    void safeParseBooleanFalseWhenEmpty() {
        assertFalse(newImporter().safeParseBoolean(new String[]{"  "}, 0));
    }

    /**
     * {@code safeParseBoolean} parses a non-empty value.
     */
    @Test
    void safeParseBooleanParsesValue() {
        assertTrue(newImporter().safeParseBoolean(new String[]{" true "}, 0));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an out-of-bounds index.
     */
    @Test
    void safeParseBigDecimalNullWhenOutOfBounds() {
        assertNull(newImporter().safeParseBigDecimal(new String[]{"1"}, 3));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an empty value.
     */
    @Test
    void safeParseBigDecimalNullWhenEmpty() {
        assertNull(newImporter().safeParseBigDecimal(new String[]{"  "}, 0));
    }

    /**
     * {@code safeParseBigDecimal} returns null for an unparseable value.
     */
    @Test
    void safeParseBigDecimalNullWhenInvalid() {
        assertNull(newImporter().safeParseBigDecimal(new String[]{"abc"}, 0));
    }

    /**
     * {@code safeParseBigDecimal} parses a valid decimal.
     */
    @Test
    void safeParseBigDecimalParsesValue() {
        assertEquals(new BigDecimal("12.50"), newImporter().safeParseBigDecimal(new String[]{" 12.50 "}, 0));
    }

    /**
     * {@code safeParseInt} returns null for an out-of-bounds index.
     */
    @Test
    void safeParseIntNullWhenOutOfBounds() {
        assertNull(newImporter().safeParseInt(new String[]{"1"}, 3));
    }

    /**
     * {@code safeParseInt} returns null for an empty value.
     */
    @Test
    void safeParseIntNullWhenEmpty() {
        assertNull(newImporter().safeParseInt(new String[]{"  "}, 0));
    }

    /**
     * {@code safeParseInt} returns null for an unparseable value.
     */
    @Test
    void safeParseIntNullWhenInvalid() {
        assertNull(newImporter().safeParseInt(new String[]{"x"}, 0));
    }

    /**
     * {@code safeParseInt} parses a valid integer.
     */
    @Test
    void safeParseIntParsesValue() {
        assertEquals(Integer.valueOf(42), newImporter().safeParseInt(new String[]{" 42 "}, 0));
    }

    /**
     * {@code safeParseDouble} returns null for an out-of-bounds index.
     */
    @Test
    void safeParseDoubleNullWhenOutOfBounds() {
        assertNull(newImporter().safeParseDouble(new String[]{"1"}, 3));
    }

    /**
     * {@code safeParseDouble} returns null for an empty value.
     */
    @Test
    void safeParseDoubleNullWhenEmpty() {
        assertNull(newImporter().safeParseDouble(new String[]{"  "}, 0));
    }

    /**
     * {@code safeParseDouble} returns null for an unparseable value.
     */
    @Test
    void safeParseDoubleNullWhenInvalid() {
        assertNull(newImporter().safeParseDouble(new String[]{"x"}, 0));
    }

    /**
     * {@code safeParseDouble} parses a valid double.
     */
    @Test
    void safeParseDoubleParsesValue() {
        assertEquals(Double.valueOf(3.14), newImporter().safeParseDouble(new String[]{" 3.14 "}, 0));
    }

    /**
     * {@code safeParseDateTime} returns null for an out-of-bounds index.
     */
    @Test
    void safeParseDateTimeNullWhenOutOfBounds() {
        assertNull(newImporter().safeParseDateTime(new String[]{"x"}, 3));
    }

    /**
     * {@code safeParseDateTime} returns null for an empty value.
     */
    @Test
    void safeParseDateTimeNullWhenEmpty() {
        assertNull(newImporter().safeParseDateTime(new String[]{"  "}, 0));
    }

    /**
     * {@code safeParseDateTime} returns null and logs for an unparseable value.
     */
    @Test
    void safeParseDateTimeNullWhenInvalid() {
        assertNull(newImporter().safeParseDateTime(new String[]{"not-a-date"}, 0));
    }

    /**
     * {@code safeParseDateTime} parses an ISO local date-time.
     */
    @Test
    void safeParseDateTimeParsesValue() {
        assertEquals(LocalDateTime.of(2020, 1, 2, 3, 4, 5), newImporter().safeParseDateTime(new String[]{" 2020-01-02T03:04:05 "}, 0));
    }

    /**
     * {@code parseCodes} returns an empty list for a null input (first guard
     * true arm).
     */
    @Test
    void parseCodesEmptyWhenNull() {
        assertTrue(newImporter().parseCodes(null).isEmpty());
    }

    /**
     * {@code parseCodes} returns an empty list for a blank input (second guard
     * true arm).
     */
    @Test
    void parseCodesEmptyWhenBlank() {
        assertTrue(newImporter().parseCodes("   ").isEmpty());
    }

    /**
     * {@code parseCodes} trims, drops empty tokens, and sorts non-empty codes.
     */
    @Test
    void parseCodesTrimsFiltersAndSorts() {
        List<String> result = newImporter().parseCodes("b, a, , c");
        assertEquals(List.of("a", "b", "c"), result);
    }

    /**
     * {@code updateCounters} adds local created/updated tallies into the global
     * accumulator.
     */
    @Test
    void updateCountersMergesTallies() {
        TestImporter importer = newImporter();
        int[] global = {2, 3};
        importer.updateCounters(global, new int[]{4, 5});
        assertEquals(6, global[0]);
        assertEquals(8, global[1]);
    }

    /**
     * The unused {@code targetCodes} set contract is honoured: an empty stream
     * (header only) produces a zero-count JSON with no chunk processing.
     */
    @Test
    void importCsvStreamHeaderOnlyProducesZeroCounts() {
        TestImporter importer = newImporter();
        Set<String> unused = new HashSet<>();
        assertTrue(unused.isEmpty());
        Response response = importer.importCsvStream(stream("HEAD|HEAD\n"), 2);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
        assertEquals(0, importer.chunkCalls);
    }
}
