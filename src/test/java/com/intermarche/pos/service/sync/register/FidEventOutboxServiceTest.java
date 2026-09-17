package com.intermarche.pos.service.sync.register;

import com.intermarche.pos.domain.sync.FidEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link FidEventOutboxService}.
 * <p>
 * The service owns its {@link java.net.http.HttpClient} as a private final
 * field, so the drain is exercised against a real JDK {@link HttpServer} on an
 * ephemeral port — which is the point: what deserves testing here is the WIRE
 * and the RETRY POLICY, both of which a mocked client would hide.
 * <p>
 * The pending rows are supplied by intercepting {@code FidEvent.findPending}
 * ({@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}, since
 * entities are un-enhanced under plain {@code mvn test}), and {@code drain()}
 * is called directly rather than through the scheduler — the loop mechanics
 * are covered separately by the lifecycle tests.
 */
class FidEventOutboxServiceTest {

    /** The stub loyalty service. */
    private HttpServer server;

    /** The service under test, pointed at the stub. */
    private FidEventOutboxService service;

    /** The bodies received by the stub, in order. */
    private final List<String> receivedBodies = new ArrayList<>();

    /** The paths received by the stub, in order. */
    private final List<String> receivedPaths = new ArrayList<>();

    /** The Authorization header of the last call, or null. */
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    /** The status the stub answers next. */
    private final AtomicInteger answeredStatus = new AtomicInteger(202);

    /**
     * Starts the stub server and wires a configured service onto it.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/events", this::record);
        server.start();
        service = new FidEventOutboxService();
        service.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        service.user = Optional.of("pos");
        service.password = Optional.of("pos-password");
    }

    /**
     * Stops the stub server and the service loop.
     */
    @AfterEach
    void tearDown() {
        service.onStop();
        server.stop(0);
    }

    /**
     * Records the incoming request and answers the canned status.
     *
     * @param exchange the HTTP exchange
     * @throws IOException on write failure
     */
    private void record(HttpExchange exchange) throws IOException {
        receivedPaths.add(exchange.getRequestURI().getPath());
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(answeredStatus.get(), -1);
        try (OutputStream out = exchange.getResponseBody()) {
            out.flush();
        }
        exchange.close();
    }

    /**
     * Builds a pending event.
     *
     * @param id the row id
     * @param type the event kind
     * @param payload the frozen payload
     * @return the event
     */
    private FidEvent event(long id, FidEvent.EventType type, String payload) {
        FidEvent event = new FidEvent();
        event.id = id;
        event.eventType = type;
        event.payloadJson = payload;
        return event;
    }

    /**
     * Runs one drain cycle with the given pending rows.
     *
     * @param pending the rows the registry hands back
     */
    private void drainWith(List<FidEvent> pending) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id")).thenReturn(pending);
            // The drain opens its transaction explicitly (a self-call from the
            // executor thread would bypass the interceptor). Outside Quarkus
            // there is no transaction manager, so the runner is intercepted
            // and simply RUNS the body — what the test observes is the drain
            // itself, not the JTA plumbing.
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });
            service.drain();
        }
    }

    // --- lifecycle ---

    /**
     * An UNCONFIGURED service starts NO thread at all: loyalty is disabled,
     * and a register that does not talk to imfid must not carry a background
     * loop for nothing.
     */
    @Test
    void onStartCreatesNoLoopWhenUnconfigured() {
        FidEventOutboxService bare = new FidEventOutboxService();
        bare.url = Optional.empty();
        bare.onStart(null);
        assertDoesNotThrow(bare::onStop);
    }

    /**
     * A CONFIGURED service starts its daemon loop, and stopping it twice is
     * harmless — shutdown must never be a source of errors.
     */
    @Test
    void onStartAndOnStopAreSafe() {
        service.onStart(null);
        assertDoesNotThrow(service::onStop);
        assertDoesNotThrow(service::onStop);
    }

    // --- enqueue ---

    /**
     * Enqueuing persists a PENDING row carrying the kind, the frozen payload
     * and the creation instant. The payload is stored as given: it embeds the
     * verbatim valuation couple, which only exists in memory at the fiscal
     * moment and could not be rebuilt later.
     */
    @Test
    void enqueuePersistsAPendingRow() {
        try (MockedConstruction<FidEvent> rows = mockConstruction(FidEvent.class)) {
            service.enqueue(FidEvent.EventType.TICKET_CLOSED, "{\"ticketRef\":\"2026-C04-1\"}");

            assertEquals(1, rows.constructed().size());
            FidEvent row = rows.constructed().get(0);
            assertEquals(FidEvent.EventType.TICKET_CLOSED, row.eventType);
            assertEquals("{\"ticketRef\":\"2026-C04-1\"}", row.payloadJson);
            assertNotNull(row.createdAt);
            assertNull(row.sentAt);
            verify(row).persist();
        }
    }

    /**
     * A return event is enqueued the same way — the kind is what will pick
     * the endpoint at drain time, nothing else differs.
     */
    @Test
    void enqueuePersistsAReturnEventToo() {
        try (MockedConstruction<FidEvent> rows = mockConstruction(FidEvent.class)) {
            service.enqueue(FidEvent.EventType.TICKET_RETURN, "{\"originTicketRef\":\"2026-C04-1\"}");
            assertEquals(FidEvent.EventType.TICKET_RETURN, rows.constructed().get(0).eventType);
        }
    }

    // --- drain ---

    /**
     * An UNCONFIGURED service drains nothing: no registry read, no request.
     */
    @Test
    void drainDoesNothingWhenUnconfigured() {
        FidEventOutboxService bare = new FidEventOutboxService();
        bare.url = Optional.empty();
        bare.drain();
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * A 202 marks the row SENT and clears any previous error: the ingestion
     * accepted it, and the next cycle must not post it again.
     */
    @Test
    void drainMarksAnAcceptedEventAsSent() {
        FidEvent closed = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        closed.lastError = "HTTP 500";
        answeredStatus.set(202);

        drainWith(List.of(closed));

        assertNotNull(closed.sentAt);
        assertNull(closed.lastError);
        assertEquals(1, closed.attempts);
        assertEquals("{\"a\":1}", receivedBodies.get(0));
    }

    /**
     * The KIND picks the endpoint: a close goes to {@code ticket-closed}, a
     * return to {@code ticket-return}. Sending one to the other's endpoint
     * would have imfid recompute the wrong movement.
     */
    @Test
    void drainRoutesEachKindToItsEndpoint() {
        answeredStatus.set(202);
        drainWith(List.of(
                event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}"),
                event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}")));

        assertEquals(List.of("/api/events/ticket-closed", "/api/events/ticket-return"),
                receivedPaths);
    }

    /**
     * A REFUSED event (any status but 202) stays PENDING with its error
     * recorded, and the drain CARRIES ON with the queue: one bad row must not
     * hold back the ones behind it.
     */
    @Test
    void drainKeepsARefusedEventPendingAndContinues() {
        FidEvent refused = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        FidEvent next = event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}");
        answeredStatus.set(400);

        drainWith(List.of(refused, next));

        assertNull(refused.sentAt);
        assertEquals("HTTP 400", refused.lastError);
        assertEquals(1, refused.attempts);
        assertEquals(2, receivedPaths.size());
        assertEquals(1, next.attempts);
    }

    /**
     * A TRANSPORT failure stops the cycle at once: imfid is down, so
     * hammering the rest of the queue would only waste the register's time.
     * The row stays PENDING and counted as attempted, and the events behind
     * it are NOT even attempted — they go out on the next cycle.
     * <p>
     * Note the error itself is not asserted: the row keeps {@code
     * e.getMessage()}, which several connection failures leave NULL. What
     * matters fiscally is that the event is neither lost nor marked sent.
     */
    @Test
    void drainStopsTheCycleWhenImfidIsUnreachable() {
        FidEvent first = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        FidEvent second = event(2L, FidEvent.EventType.TICKET_RETURN, "{\"b\":2}");
        server.stop(0);

        drainWith(List.of(first, second));

        assertNull(first.sentAt);
        assertEquals(1, first.attempts);
        assertNull(second.sentAt);
        assertEquals(0, second.attempts);
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * ATTEMPTS accumulate across cycles: a row retried three times says so,
     * which is what lets an operator tell a transient hiccup from an event
     * imfid keeps refusing.
     */
    @Test
    void drainAccumulatesAttemptsAcrossCycles() {
        FidEvent refused = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(500);

        drainWith(List.of(refused));
        drainWith(List.of(refused));
        drainWith(List.of(refused));

        assertEquals(3, refused.attempts);
        assertNull(refused.sentAt);
    }

    /**
     * An event ACCEPTED after failures is cleaned up: the error disappears
     * with the send, so a later reader does not mistake a delivered event for
     * a broken one.
     */
    @Test
    void drainClearsTheErrorOnceTheEventGoesThrough() {
        FidEvent event = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(503);
        drainWith(List.of(event));
        assertEquals("HTTP 503", event.lastError);

        answeredStatus.set(202);
        drainWith(List.of(event));

        assertNull(event.lastError);
        assertNotNull(event.sentAt);
        assertEquals(2, event.attempts);
    }

    /**
     * An EMPTY queue drains silently — the common case, ten times a minute.
     */
    @Test
    void drainOnAnEmptyQueuePostsNothing() {
        drainWith(List.of());
        assertTrue(receivedPaths.isEmpty());
    }

    /**
     * The POS machine account travels on every event: the ingestion must know
     * which register is declaring.
     */
    @Test
    void drainCarriesTheBasicCredentials() {
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));

        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("pos:pos-password".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, receivedAuthorization.get());
    }

    /**
     * WITHOUT credentials no header is sent — a dev imfid without security
     * stays usable, and half a credential is never sent as a whole one.
     */
    @Test
    void drainSendsNoAuthorizationWithoutCredentials() {
        service.password = Optional.empty();
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));
        assertNull(receivedAuthorization.get());
    }

    /**
     * A USER without a password sends no header either (the mirror leg): half
     * a credential is never sent as a whole one — an incomplete Basic string
     * would fail the call outright instead of degrading to anonymous.
     */
    @Test
    void drainSendsNoAuthorizationWithoutAUser() {
        service.user = Optional.empty();
        answeredStatus.set(202);
        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}")));
        assertNull(receivedAuthorization.get());
    }

    // --- the loop never dies ---

    /**
     * The loop's SAFETY WRAPPER: a cycle that throws is logged and swallowed
     * so the scheduled task survives. Without it, a single unexpected error
     * would kill the daemon thread for good — the register would keep taking
     * sales while silently never declaring another one to imfid, and nothing
     * on screen would say so.
     * <p>
     * The failure is forced through the registry read, which the wrapper sits
     * above; the drain is then invoked the way the scheduler does, through
     * the private wrapper.
     */
    @Test
    void aCycleThatThrowsNeverKillsTheLoop() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenThrow(new IllegalStateException("registre indisponible"));
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            assertDoesNotThrow(() -> drainSafely.invoke(service));
        }
    }

    /**
     * A cycle whose failure carries NO message is swallowed just as well: the
     * wrapper formats {@code e.getMessage()}, and a null there must not turn
     * the log line into a second failure.
     */
    @Test
    void aCycleThrowingWithoutAMessageIsSwallowedToo() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenThrow(new NullPointerException());
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            assertDoesNotThrow(() -> drainSafely.invoke(service));
        }
    }

    /**
     * A NOMINAL cycle passes through the wrapper untouched: the safety net
     * must not swallow the work itself.
     */
    @Test
    void aNominalCycleGoesThroughTheWrapper() throws Exception {
        java.lang.reflect.Method drainSafely =
                FidEventOutboxService.class.getDeclaredMethod("drainSafely");
        drainSafely.setAccessible(true);
        FidEvent pending = event(1L, FidEvent.EventType.TICKET_CLOSED, "{\"a\":1}");
        answeredStatus.set(202);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            panache.when(() -> FidEvent.list("sentAt is null order by id"))
                    .thenReturn(List.of(pending));
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .run(org.mockito.ArgumentMatchers.any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        ((Runnable) invocation.getArgument(0)).run();
                        return null;
                    });

            drainSafely.invoke(service);
        }

        assertNotNull(pending.sentAt);
        assertEquals(1, receivedPaths.size());
    }

    /**
     * The payload is posted BYTE FOR BYTE: it carries the engine's verbatim
     * valuation couple, which imfid replays — a reserialization would change
     * what the ingestion recomputes.
     */
    @Test
    void drainPostsThePayloadVerbatim() {
        String payload = "{\"ticketRef\":\"2026-C04-000001\",\"valuationResponse\":{\"total\":42.00}}";
        answeredStatus.set(202);

        drainWith(List.of(event(1L, FidEvent.EventType.TICKET_CLOSED, payload)));

        assertEquals(payload, receivedBodies.get(0));
    }
}
