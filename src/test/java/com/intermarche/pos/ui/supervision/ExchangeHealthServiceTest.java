package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.EngineFeedService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExchangeHealthService}: the read-only assembly of the
 * "Santé des échanges" panel over the two outbox tables (read directly) and the
 * engine-feed keeper (mocked). The Panache statics resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test} and are intercepted with
 * {@code mockStatic}; the rows are Mockito mocks whose public fields are driven
 * directly. Every arm of the timestamp/error ternaries, the feed-status branches
 * and the {@code healthy} composition is covered.
 */
class ExchangeHealthServiceTest {

    /** The service under test. */
    private final ExchangeHealthService service = newService();

    /**
     * Builds the service with a resolver reading the French bundle: the feed
     * statuses are bundle keys, so the assertions below keep the literal
     * wording the supervision screen shows (LC-13-06).
     *
     * @return the service under test
     */
    private static ExchangeHealthService newService() {
        ExchangeHealthService built = new ExchangeHealthService();
        built.i18n = TestMessages.french();
        return built;
    }

    /** The mocked engine feed keeper. */
    private final EngineFeedService engineFeedService = mock(EngineFeedService.class);

    /** The mocked referential pull loop (per-domain errors, last cycle, cadence). */
    private final RefPullService refPullService = mock(RefPullService.class);

    /**
     * Wires the mocked collaborators onto the service and leaves the pull loop
     * clean by default (no per-domain error), so the tests that do not exercise
     * the pull table need not stub it.
     */
    ExchangeHealthServiceTest() {
        service.engineFeedService = engineFeedService;
        service.refPullService = refPullService;
        when(refPullService.getLastErrors()).thenReturn(Map.of());
    }

    /**
     * Builds a mocked Panache query yielding one first result.
     *
     * @param result the entity the query returns, or null
     * @param <T> the entity type
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private static <T extends PanacheEntityBase> PanacheQuery<T> queryOf(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a SyncOutbox row with a creation time and an error.
     *
     * @param createdAt the enqueue time
     * @param lastError the last push error, or null
     * @return the row
     */
    private static SyncOutbox syncRow(LocalDateTime createdAt, String lastError) {
        SyncOutbox row = mock(SyncOutbox.class);
        row.createdAt = createdAt;
        row.lastError = lastError;
        return row;
    }

    /**
     * Both queues empty and no feed error: every channel is at zero, the oldest
     * and last-error ternaries take their null arms, and the panel is healthy
     * (all three {@code &&} legs true).
     */
    @Test
    void buildReportsHealthyWhenEverythingEmpty() {
        when(engineFeedService.feedStates()).thenReturn(List.of());
        PanacheQuery<SyncOutbox> emptyOldest = queryOf((SyncOutbox) null);
        PanacheQuery<SyncOutbox> emptyError = queryOf((SyncOutbox) null);
        PanacheQuery<ConsumptionOutbox> emptyConsumption = queryOf((ConsumptionOutbox) null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(SyncOutbox::count).thenReturn(0L);
            mocked.when(() -> SyncOutbox.find("order by createdAt")).thenReturn(emptyOldest);
            mocked.when(() -> SyncOutbox.find("lastError is not null order by id desc")).thenReturn(emptyError);
            mocked.when(() -> ConsumptionOutbox.count("applied = false")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.count("applied = false and lastError is not null")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.find("applied = false and lastError is not null order by id desc"))
                    .thenReturn(emptyConsumption);
            ExchangeHealthService.HealthView view = service.build();
            assertEquals(0L, view.uplink.count);
            assertEquals("—", view.uplink.oldest);
            assertNull(view.uplink.lastError);
            assertEquals(0L, view.consumption.pending);
            assertEquals(0L, view.consumption.failed);
            assertNull(view.consumption.lastError);
            assertTrue(view.feeds.isEmpty());
            assertTrue(view.healthy);
        }
    }

    /**
     * A ticket-uplink backlog with an error: the oldest and last-error ternaries
     * take their non-null arms, the count is non-zero so the panel is unhealthy
     * (first {@code &&} leg false, short-circuit).
     */
    @Test
    void buildReportsUplinkBacklogWithError() {
        when(engineFeedService.feedStates()).thenReturn(List.of());
        PanacheQuery<SyncOutbox> oldest = queryOf(syncRow(LocalDateTime.of(2026, 9, 26, 8, 5, 0), null));
        PanacheQuery<SyncOutbox> errored = queryOf(syncRow(LocalDateTime.of(2026, 9, 26, 8, 6, 0), "push boom"));
        PanacheQuery<ConsumptionOutbox> emptyConsumption = queryOf((ConsumptionOutbox) null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(SyncOutbox::count).thenReturn(3L);
            mocked.when(() -> SyncOutbox.find("order by createdAt")).thenReturn(oldest);
            mocked.when(() -> SyncOutbox.find("lastError is not null order by id desc")).thenReturn(errored);
            mocked.when(() -> ConsumptionOutbox.count("applied = false")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.count("applied = false and lastError is not null")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.find("applied = false and lastError is not null order by id desc"))
                    .thenReturn(emptyConsumption);
            ExchangeHealthService.HealthView view = service.build();
            assertEquals(3L, view.uplink.count);
            assertEquals("26/09 08:05:00", view.uplink.oldest);
            assertEquals("push boom", view.uplink.lastError);
            assertFalse(view.healthy);
        }
    }

    /**
     * A consumption queue with a failed row: its error ternary takes the non-null
     * arm and the panel is unhealthy through the SECOND {@code &&} leg (uplink
     * empty, pending non-zero).
     */
    @Test
    void buildReportsConsumptionFailure() {
        when(engineFeedService.feedStates()).thenReturn(List.of());
        PanacheQuery<SyncOutbox> emptyOldest = queryOf((SyncOutbox) null);
        PanacheQuery<SyncOutbox> emptyError = queryOf((SyncOutbox) null);
        ConsumptionOutbox erroredRow = mock(ConsumptionOutbox.class);
        erroredRow.lastError = "engine 503";
        PanacheQuery<ConsumptionOutbox> consumptionError = queryOf(erroredRow);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(SyncOutbox::count).thenReturn(0L);
            mocked.when(() -> SyncOutbox.find("order by createdAt")).thenReturn(emptyOldest);
            mocked.when(() -> SyncOutbox.find("lastError is not null order by id desc")).thenReturn(emptyError);
            mocked.when(() -> ConsumptionOutbox.count("applied = false")).thenReturn(2L);
            mocked.when(() -> ConsumptionOutbox.count("applied = false and lastError is not null")).thenReturn(1L);
            mocked.when(() -> ConsumptionOutbox.find("applied = false and lastError is not null order by id desc"))
                    .thenReturn(consumptionError);
            ExchangeHealthService.HealthView view = service.build();
            assertEquals(2L, view.consumption.pending);
            assertEquals(1L, view.consumption.failed);
            assertEquals("engine 503", view.consumption.lastError);
            assertFalse(view.healthy);
        }
    }

    /**
     * Engine-feed statuses: an acknowledged feed ("À jour"), a lagging clean feed
     * ("En attente") and a failing feed ("Erreur") — all three {@code feedStatus}
     * arms — with the queues empty, so the panel is unhealthy only through the
     * feed-error leg (both first legs true, {@code !feedError} false).
     */
    @Test
    void buildProjectsFeedStatuses() {
        when(engineFeedService.feedStates()).thenReturn(List.of(
                new EngineFeedService.FeedState("PRODUCTS", "v1", "v1", true, null,
                        LocalDateTime.of(2026, 9, 26, 8, 0), LocalDateTime.of(2026, 9, 26, 8, 1)),
                new EngineFeedService.FeedState("PRICES", "v2", "v1", false, null,
                        LocalDateTime.of(2026, 9, 26, 8, 0), null),
                new EngineFeedService.FeedState("OFFERS", "v3", null, false, "feed boom",
                        LocalDateTime.of(2026, 9, 26, 8, 0), null)));
        PanacheQuery<SyncOutbox> emptyOldest = queryOf((SyncOutbox) null);
        PanacheQuery<SyncOutbox> emptyError = queryOf((SyncOutbox) null);
        PanacheQuery<ConsumptionOutbox> emptyConsumption = queryOf((ConsumptionOutbox) null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(SyncOutbox::count).thenReturn(0L);
            mocked.when(() -> SyncOutbox.find("order by createdAt")).thenReturn(emptyOldest);
            mocked.when(() -> SyncOutbox.find("lastError is not null order by id desc")).thenReturn(emptyError);
            mocked.when(() -> ConsumptionOutbox.count("applied = false")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.count("applied = false and lastError is not null")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.find("applied = false and lastError is not null order by id desc"))
                    .thenReturn(emptyConsumption);
            ExchangeHealthService.HealthView view = service.build();
            assertEquals(3, view.feeds.size());
            assertEquals("PRODUCTS", view.feeds.get(0).code);
            assertEquals("À jour", view.feeds.get(0).status);
            assertNull(view.feeds.get(0).lastError);
            assertEquals("En attente", view.feeds.get(1).status);
            assertEquals("Erreur", view.feeds.get(2).status);
            assertEquals("feed boom", view.feeds.get(2).lastError);
            assertFalse(view.healthy);
        }
    }

    /**
     * Builds a RefState row with a domain, fingerprint and applied time.
     *
     * @param domain the referential domain
     * @param fingerprint the stored fingerprint, or null
     * @param appliedAt the apply time, or null
     * @return the row
     */
    private static RefState pullRow(String domain, String fingerprint, LocalDateTime appliedAt) {
        RefState row = mock(RefState.class);
        row.domain = domain;
        row.fingerprint = fingerprint;
        row.appliedAt = appliedAt;
        return row;
    }

    /**
     * Assembles the referential-pull table, covering every arm of
     * {@code pulls}, {@code shortHash} and {@code stamp}: an applied clean
     * domain (long fingerprint truncated, timestamp formatted, no error), an
     * applied domain still carrying an error (short fingerprint kept, null
     * timestamp dashed, error from {@code getLastError}), a domain that failed
     * before ever applying (synthetic row, both cells dashed) and the
     * already-listed short-circuit that keeps that synthetic from duplicating an
     * applied row. The last-pull stamp and cadence are surfaced too.
     */
    @Test
    void buildAssemblesPullRows() {
        when(engineFeedService.feedStates()).thenReturn(List.of());
        when(refPullService.getLastError("COUNTRIES")).thenReturn(null);
        when(refPullService.getLastError("PDVS")).thenReturn("pdv boom");
        when(refPullService.getLastErrors()).thenReturn(Map.of("PDVS", "pdv boom", "ENSEIGNES", "ens down"));
        when(refPullService.getLastSuccessfulPull()).thenReturn(LocalDateTime.of(2026, 9, 26, 9, 30, 0));
        when(refPullService.getPullSeconds()).thenReturn(300L);
        PanacheQuery<SyncOutbox> emptyOldest = queryOf((SyncOutbox) null);
        PanacheQuery<SyncOutbox> emptyError = queryOf((SyncOutbox) null);
        PanacheQuery<ConsumptionOutbox> emptyConsumption = queryOf((ConsumptionOutbox) null);
        RefState countries = pullRow("COUNTRIES", "abcdef1234567890", LocalDateTime.of(2026, 9, 26, 9, 0, 0));
        RefState pdvs = pullRow("PDVS", "short", null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(SyncOutbox::count).thenReturn(0L);
            mocked.when(() -> SyncOutbox.find("order by createdAt")).thenReturn(emptyOldest);
            mocked.when(() -> SyncOutbox.find("lastError is not null order by id desc")).thenReturn(emptyError);
            mocked.when(() -> ConsumptionOutbox.count("applied = false")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.count("applied = false and lastError is not null")).thenReturn(0L);
            mocked.when(() -> ConsumptionOutbox.find("applied = false and lastError is not null order by id desc"))
                    .thenReturn(emptyConsumption);
            mocked.when(() -> RefState.list("order by domain")).thenReturn(List.of(countries, pdvs));
            ExchangeHealthService.HealthView view = service.build();
            assertEquals(3, view.pulls.size());
            assertEquals("COUNTRIES", view.pulls.get(0).domain);
            assertEquals("abcdef123456", view.pulls.get(0).fingerprint);
            assertEquals("26/09 09:00:00", view.pulls.get(0).appliedAt);
            assertNull(view.pulls.get(0).lastError);
            assertEquals("PDVS", view.pulls.get(1).domain);
            assertEquals("short", view.pulls.get(1).fingerprint);
            assertEquals("—", view.pulls.get(1).appliedAt);
            assertEquals("pdv boom", view.pulls.get(1).lastError);
            assertEquals("ENSEIGNES", view.pulls.get(2).domain);
            assertEquals("—", view.pulls.get(2).fingerprint);
            assertEquals("—", view.pulls.get(2).appliedAt);
            assertEquals("ens down", view.pulls.get(2).lastError);
            assertEquals("26/09 09:30:00", view.lastPull);
            assertEquals(300L, view.pullSeconds);
        }
    }

    /** LC-13-05: an absent reference fingerprint is displayed as a dash rather than dereferenced. */
    @Test
    void absentFingerprintUsesADash() throws Exception {
        var method = ExchangeHealthService.class.getDeclaredMethod("shortHash", String.class);
        method.setAccessible(true);
        assertEquals("—", method.invoke(new ExchangeHealthService(), (Object) null));
    }

}
