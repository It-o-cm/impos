package com.intermarche.pos.ui.fidelity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ImfidClient}.
 * <p>
 * The client owns its {@link java.net.http.HttpClient} as a private final
 * field, so it cannot be mocked — and should not be: what deserves testing
 * here is exactly what a mock would hide, namely the WIRE. Each test therefore
 * starts a JDK {@link HttpServer} on an ephemeral port, points the client's
 * configuration at it, and asserts both what the client SENDS (the verbatim
 * couple, the Basic header, the query string) and what it MAKES of the answer
 * (typed reservation outcomes, tolerant parsing, 404 as null, non-200 as an
 * exception the caller's breaker turns into the degraded display).
 * <p>
 * The configuration fields are package-private {@link Optional}s, injected by
 * Quarkus in production and set directly here.
 */
class ImfidClientTest {

    /** The stub loyalty service. */
    private HttpServer server;

    /** The client under test, pointed at the stub. */
    private ImfidClient client;

    /** The body received by the stub on the last call, for wire assertions. */
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    /** The request line (method + URI) received by the stub. */
    private final AtomicReference<String> receivedRequestLine = new AtomicReference<>();

    /** The Authorization header received by the stub, or null. */
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    /**
     * Starts the stub server and wires a configured client onto it.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new ImfidClient();
        client.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        client.user = Optional.of("pos");
        client.password = Optional.of("pos-password");
        client.posSettingsService = settings();
        org.mockito.Mockito.when(client.posSettingsService.fidelityExternalEnabled()).thenReturn(true);
    }

    /**
     * Stops the stub server.
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * Builds an administered-settings mock answering the loyalty endpoint keys
     * (BO-11-04-04) with an empty value, so the deployment properties apply and
     * the pre-existing cases are unchanged.
     *
     * @return the settings mock
     */
    private com.intermarche.pos.service.PosSettingsService settings() {
        com.intermarche.pos.service.PosSettingsService settings =
                org.mockito.Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        org.mockito.Mockito.when(settings.fidelityUrl()).thenReturn("");
        org.mockito.Mockito.when(settings.fidelityUser()).thenReturn("");
        return settings;
    }

    /**
     * Registers a stub answer on a path, recording what the client sent.
     *
     * @param path the path to serve
     * @param status the HTTP status to answer
     * @param body the body to answer (may be empty)
     */
    private void stub(String path, int status, String body) {
        server.createContext(path, exchange -> respond(exchange, status, body));
    }

    /**
     * Records the incoming request and writes the canned answer.
     *
     * @param exchange the HTTP exchange
     * @param status the status to answer
     * @param body the body to answer
     * @throws IOException on write failure
     */
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        receivedRequestLine.set(exchange.getRequestMethod() + " "
                + exchange.getRequestURI().toString());
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
        exchange.close();
    }

    // --- configuration ---

    /**
     * An absent base URL means fidelity is DISABLED — the flag every caller
     * checks before spending a millisecond on the network.
     */
    @Test
    void isConfiguredFollowsTheBaseUrl() {
        assertTrue(client.isConfigured());
        // BO-10-03-07: a present URL is not enough — the back office must also
        // leave the external loyalty switched on, else the service is treated
        // as absent (the operational off switch beside the credentials).
        org.mockito.Mockito.when(client.posSettingsService.fidelityExternalEnabled()).thenReturn(false);
        assertFalse(client.isConfigured());
        ImfidClient bare = new ImfidClient();
        bare.posSettingsService = settings();
        bare.url = Optional.empty();
        assertFalse(bare.isConfigured());
        assertNull(bare.targetUrl());
    }

    // --- health ---

    /**
     * A 200 on the public health endpoint means the service is up.
     */
    @Test
    void healthIsTrueOn200() {
        stub("/q/health", 200, "{\"status\":\"UP\"}");
        assertTrue(client.health());
    }

    /**
     * Any non-200 answer means DOWN — no exception escapes: a health probe
     * that throws would be worse than one that says no.
     */
    @Test
    void healthIsFalseOnErrorStatus() {
        stub("/q/health", 503, "");
        assertFalse(client.health());
    }

    /**
     * An unreachable service is DOWN, not an exception (the port is closed
     * after the server stops).
     */
    @Test
    void healthIsFalseWhenUnreachable() {
        server.stop(0);
        assertFalse(client.health());
    }

    // --- earn ---

    /**
     * THE doctrinal test: the {@code /valuation} couple travels VERBATIM.
     * The body must embed the two raw JSON strings byte for byte — no
     * reserialization, no reordering, no re-quoting — because imfid replays
     * the engine's own answer and must see exactly what the engine said.
     */
    @Test
    void earnSendsTheValuationCoupleVerbatim() throws Exception {
        stub("/api/earn", 200, "{\"total\":1.03,\"burnableBase\":47.11,\"entries\":[]}");
        String request = "{\"lines\":[{\"lineId\":\"U-1\"}],\"customerCode\":\"2990000000019\"}";
        String response = "{\"total\":42.00,\"lines\":[{\"lineId\":\"U-1\",\"valuedTotal\":10.00}]}";

        client.earn(request, response);

        assertEquals("{\"valuationRequest\":" + request + ",\"valuationResponse\":" + response + "}",
                receivedBody.get());
    }

    /**
     * The projection is parsed into the three figures the register displays:
     * the total, the burnable base (which caps a fidelity payment) and the
     * per-rule entries whose labels are the ONLY rule data the POS prints.
     */
    @Test
    void earnParsesTotalBaseAndEntries() throws Exception {
        stub("/api/earn", 200, "{\"total\":1.03,\"burnableBase\":47.11,\"entries\":["
                + "{\"ruleCode\":\"SOCLE\",\"label\":\"Cagnotte socle\",\"amount\":0.28},"
                + "{\"ruleCode\":\"F&L-SAM\",\"label\":\"Fruits & légumes samedi\",\"amount\":0.75}]}");

        ImfidClient.EarnProjection projection = client.earn("{}", "{}");

        assertEquals(0, new BigDecimal("1.03").compareTo(projection.total));
        assertEquals(0, new BigDecimal("47.11").compareTo(projection.burnableBase));
        assertEquals(2, projection.entries.size());
        assertEquals("F&L-SAM", projection.entries.get(1).ruleCode);
        assertEquals("Fruits & légumes samedi", projection.entries.get(1).label);
        assertEquals(0, new BigDecimal("0.75").compareTo(projection.entries.get(1).amount));
    }

    /**
     * UNKNOWN properties are ignored (spec §2, forward compatibility): imfid
     * may enrich its payload without breaking a register that has not been
     * redeployed.
     */
    @Test
    void earnIgnoresUnknownProperties() throws Exception {
        stub("/api/earn", 200, "{\"total\":1.03,\"futureField\":\"whatever\","
                + "\"entries\":[{\"ruleCode\":\"SOCLE\",\"label\":\"Socle\",\"amount\":1.03,"
                + "\"extra\":{\"nested\":true}}]}");

        ImfidClient.EarnProjection projection = client.earn("{}", "{}");

        assertEquals(0, new BigDecimal("1.03").compareTo(projection.total));
        assertEquals(1, projection.entries.size());
    }

    /**
     * A ZERO projection is a legitimate answer, not an error: a cart entirely
     * absorbed by an offer earns nothing (imfid's invariant I2).
     */
    @Test
    void earnAcceptsAZeroProjection() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        ImfidClient.EarnProjection projection = client.earn("{}", "{}");
        assertEquals(0, BigDecimal.ZERO.compareTo(projection.total));
        assertTrue(projection.entries.isEmpty());
    }

    /**
     * A non-200 answer THROWS: the caller's breaker turns it into the
     * degraded display (badge hidden) rather than a fake zero, which would
     * lie to the customer.
     */
    @Test
    void earnThrowsOnErrorStatus() {
        stub("/api/earn", 500, "boom");
        assertThrows(IllegalStateException.class, () -> client.earn("{}", "{}"));
    }

    /**
     * The POS machine account travels as a Basic header on every API call.
     */
    @Test
    void earnCarriesTheBasicCredentials() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.earn("{}", "{}");
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("pos:pos-password".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, receivedAuthorization.get());
    }

    /**
     * With NO credentials configured, no Authorization header is sent — a
     * dev imfid without security stays usable.
     */
    @Test
    void noCredentialsMeansNoAuthorizationHeader() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.user = Optional.empty();
        client.password = Optional.empty();
        client.earn("{}", "{}");
        assertNull(receivedAuthorization.get());
    }

    // --- account ---

    /**
     * The account exposes the AVAILABLE balance — balance minus active
     * leases — which is the figure that caps a fidelity payment.
     */
    @Test
    void accountParsesStatusBalanceAndAvailable() throws Exception {
        stub("/api/accounts/2990000000019", 200,
                "{\"status\":\"ACTIVE\",\"balance\":56.70,\"availableBalance\":46.70}");

        ImfidClient.AccountInfo account = client.account("2990000000019");

        assertEquals("ACTIVE", account.status);
        assertEquals(0, new BigDecimal("56.70").compareTo(account.balance));
        assertEquals(0, new BigDecimal("46.70").compareTo(account.availableBalance));
    }

    /**
     * An UNKNOWN card is null, not an exception: "this card does not exist"
     * is an answer the register displays, not a failure.
     */
    @Test
    void accountIsNullOnUnknownCard() throws Exception {
        stub("/api/accounts/2990000000404", 404, "");
        assertNull(client.account("2990000000404"));
    }

    /**
     * An unexpected status throws — the breaker path.
     */
    @Test
    void accountThrowsOnUnexpectedStatus() {
        stub("/api/accounts/2990000000019", 500, "");
        assertThrows(IllegalStateException.class, () -> client.account("2990000000019"));
    }

    // --- reserve ---

    /**
     * A GRANTED lease returns its id and expiry, and the raw status is kept
     * so the caller can tell 200 from 201.
     */
    @Test
    void reserveGrantedReturnsIdAndExpiry() throws Exception {
        stub("/api/burn/reservations", 201,
                "{\"reservationId\":77,\"expiresAt\":\"2026-08-12T15:30:00\"}");

        ImfidClient.ReservationResult result =
                client.reserve("2990000000019", new BigDecimal("10.00"), "D-1");

        assertEquals(201, result.httpStatus);
        assertEquals(77L, result.reservationId);
        assertEquals("2026-08-12T15:30:00", result.expiresAt);
        assertNull(result.reason);
    }

    /**
     * A 200 is a GRANT too — the answer to a RENEWAL, where an identical
     * re-POST refreshes an existing lease instead of creating a second one.
     * It shares the grant arm with 201, so the refreshed expiry is parsed the
     * same way; treating it as anything else would make the register believe
     * its lease had lapsed.
     */
    @Test
    void reserveGrantedOn200RefreshesTheLease() throws Exception {
        stub("/api/burn/reservations", 200,
                "{\"reservationId\":77,\"expiresAt\":\"2026-08-12T15:45:00\"}");

        ImfidClient.ReservationResult result =
                client.reserve("2990000000019", new BigDecimal("10.00"), "D-1");

        assertEquals(200, result.httpStatus);
        assertEquals(77L, result.reservationId);
        assertEquals("2026-08-12T15:45:00", result.expiresAt);
        assertNull(result.reason);
    }

    /**
     * A configured client exposes its target — the address announced at boot
     * and shown when diagnosing a silent loyalty service.
     */
    @Test
    void targetUrlExposesTheConfiguredAddress() {
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort(), client.targetUrl());
    }

    /**
     * The reservation body names the card, the amount and the ticket
     * reference — the renewal key that lets an identical re-POST refresh the
     * lease instead of stacking a second one.
     */
    @Test
    void reserveSendsCardAmountAndTicketRef() throws Exception {
        stub("/api/burn/reservations", 201, "{\"reservationId\":77}");
        client.reserve("2990000000019", new BigDecimal("10.00"), "D-42");
        assertTrue(receivedBody.get().contains("\"card\":\"2990000000019\""));
        assertTrue(receivedBody.get().contains("\"amount\":10.00"));
        assertTrue(receivedBody.get().contains("\"ticketRef\":\"D-42\""));
    }

    /**
     * A BUSINESS REFUSAL is a typed result, never an exception: the reason
     * comes back for the register to map onto its exact cashier message.
     */
    @Test
    void reserveRefusalCarriesTheReason() throws Exception {
        stub("/api/burn/reservations", 422, "{\"reason\":\"DAILY_RULE\"}");

        ImfidClient.ReservationResult result =
                client.reserve("2990000000019", new BigDecimal("10.00"), "D-1");

        assertEquals(422, result.httpStatus);
        assertEquals("DAILY_RULE", result.reason);
        assertNull(result.reservationId);
    }

    /**
     * A 404 (unknown card) and a 409 (lease held elsewhere) come back as bare
     * statuses — no body to read, the status alone says it all.
     */
    @Test
    void reserveUnknownCardAndBusyAreBareStatuses() throws Exception {
        stub("/api/burn/reservations", 409, "");
        ImfidClient.ReservationResult busy =
                client.reserve("2990000000088", new BigDecimal("5.00"), "D-1");
        assertEquals(409, busy.httpStatus);
        assertNull(busy.reservationId);
        assertNull(busy.reason);
    }

    /**
     * An UNKNOWN CARD (404) is a bare status too — the second entry of the
     * default arm. Nothing is read from the body: there is no lease to name
     * and no business reason to map, so the caller decides on the status
     * alone.
     */
    @Test
    void reserveUnknownCardIsABareStatus() throws Exception {
        stub("/api/burn/reservations", 404, "");
        ImfidClient.ReservationResult unknown =
                client.reserve("2990000000404", new BigDecimal("5.00"), "D-1");
        assertEquals(404, unknown.httpStatus);
        assertNull(unknown.reservationId);
        assertNull(unknown.reason);
        assertNull(unknown.expiresAt);
    }

    /**
     * An UNFORESEEN status (a 500, a future code) also lands on the default
     * arm and is reported as-is: the client never invents a verdict, and the
     * service above turns anything it does not recognise into the generic
     * refusal rather than into a grant.
     */
    @Test
    void reserveUnforeseenStatusIsReportedAsIs() throws Exception {
        stub("/api/burn/reservations", 500, "internal error");
        ImfidClient.ReservationResult failure =
                client.reserve("2990000000019", new BigDecimal("5.00"), "D-1");
        assertEquals(500, failure.httpStatus);
        assertNull(failure.reservationId);
        assertNull(failure.reason);
    }

    /**
     * A default-arm answer WITH a body leaves it unread: the status is the
     * whole message, and parsing an unexpected payload could only fail.
     */
    @Test
    void reserveDefaultArmIgnoresTheBody() throws Exception {
        stub("/api/burn/reservations", 409, "{\"reservationId\":999,\"reason\":\"NOPE\"}");
        ImfidClient.ReservationResult busy =
                client.reserve("2990000000088", new BigDecimal("5.00"), "D-1");
        assertEquals(409, busy.httpStatus);
        assertNull(busy.reservationId);
        assertNull(busy.reason);
    }

    // --- confirm / release / events ---

    /**
     * Confirming posts the FISCAL DATE and returns the raw status: 200
     * confirmed, and 410 (lease expired) is a status the caller tolerates
     * rather than an error.
     */
    @Test
    void confirmPostsTheFiscalDateAndReturnsTheStatus() throws Exception {
        stub("/api/burn/reservations/77/confirm", 410, "");
        int status = client.confirm(77L, "2026-08-12");
        assertEquals(410, status);
        assertTrue(receivedBody.get().contains("\"fiscalDate\":\"2026-08-12\""));
    }

    /**
     * Releasing is a DELETE and swallows failures: an unreleased lease simply
     * expires by TTL, so a network hiccup on cancellation must never surface.
     */
    @Test
    void releaseIsADeleteAndSwallowsFailures() {
        stub("/api/burn/reservations/77", 204, "");
        client.release(77L);
        assertEquals("DELETE /api/burn/reservations/77", receivedRequestLine.get());
    }

    /**
     * An UNREACHABLE service swallows the failure: the cancellation of a
     * payment must never surface a network error to the cashier, and the
     * unreleased lease simply expires by TTL — the safety net that makes this
     * catch legitimate rather than negligent.
     */
    @Test
    void releaseSwallowsAnUnreachableService() {
        server.stop(0);
        assertDoesNotThrow(() -> client.release(77L));
    }

    /**
     * A REFUSED release is swallowed too (404, 409, 500…): the endpoint is
     * idempotent by spec, so a lease already gone is not an error either.
     */
    @Test
    void releaseSwallowsAnErrorStatus() {
        stub("/api/burn/reservations/77", 500, "boom");
        assertDoesNotThrow(() -> client.release(77L));
    }

    /**
     * An UNCONFIGURED service releases nothing without blowing up: the base
     * URL is absent, the request cannot even be built, and the caller — which
     * clears the lease locally right after — must not be interrupted.
     */
    @Test
    void releaseSwallowsAnAbsentConfiguration() {
        ImfidClient bare = new ImfidClient();
        bare.posSettingsService = settings();
        bare.url = Optional.empty();
        bare.user = Optional.empty();
        bare.password = Optional.empty();
        assertDoesNotThrow(() -> bare.release(77L));
    }

    /**
     * An event is posted as-is to the given path, and the status is returned
     * (202 = accepted by the ingestion).
     */
    @Test
    void postEventSendsThePayloadAsIsAndReturnsTheStatus() throws Exception {
        stub("/api/events/ticket-closed", 202, "");
        String payload = "{\"ticketRef\":\"2026-C04-000001\",\"card\":\"2990000000019\"}";
        int status = client.postEvent("/api/events/ticket-closed", payload);
        assertEquals(202, status);
        assertEquals(payload, receivedBody.get());
    }

    // --- movements ---

    /**
     * The movements page is fetched with its PAGING in the query string and
     * parsed into the rows the in-store consultation displays.
     */
    @Test
    void movementsParsesThePageAndSendsThePaging() throws Exception {
        stub("/api/accounts/2990000000019/movements", 200,
                "{\"items\":[{\"type\":\"EARN\",\"amount\":1.03,\"fiscalDate\":\"2026-08-12\","
                        + "\"ruleCode\":\"SOCLE\"},{\"type\":\"BURN\",\"amount\":-10.00}]}");

        ImfidClient.MovementsPage page = client.movements("2990000000019", 0, 15);

        assertNotNull(page);
        assertEquals(2, page.items.size());
        assertEquals("EARN", page.items.get(0).type);
        assertEquals("SOCLE", page.items.get(0).ruleCode);
        assertEquals(0, new BigDecimal("-10.00").compareTo(page.items.get(1).amount));
        assertTrue(receivedRequestLine.get().contains("page=0"));
        assertTrue(receivedRequestLine.get().contains("size=15"));
    }

    /**
     * An unknown card yields null here too — the consultation shows its own
     * message rather than an error page.
     */
    @Test
    void movementsIsNullOnUnknownCard() throws Exception {
        stub("/api/accounts/2990000000404/movements", 404, "");
        assertNull(client.movements("2990000000404", 0, 15));
    }

    /**
     * An unexpected status throws — the consultation degrades.
     */
    @Test
    void movementsThrowsOnUnexpectedStatus() {
        stub("/api/accounts/2990000000019/movements", 503, "");
        assertThrows(IllegalStateException.class, () -> client.movements("2990000000019", 0, 15));
    }

    // --- unconfigured service ---

    /**
     * With NO base URL, {@code health()} answers false WITHOUT touching the
     * network: an unconfigured loyalty service is simply down, and probing a
     * null address would throw.
     */
    @Test
    void healthIsFalseWhenNoUrlIsConfigured() {
        ImfidClient bare = new ImfidClient();
        bare.posSettingsService = settings();
        bare.url = Optional.empty();
        assertFalse(bare.health());
    }

    // --- interruption ---

    /**
     * An INTERRUPTED health probe answers false and — the point of the
     * dedicated catch — RE-ARMS the thread's interrupt flag, which
     * {@code send} cleared when it threw. Swallowing it would leave a
     * shutting-down register unable to notice it was asked to stop.
     */
    @Test
    void healthRestoresTheInterruptFlagAndAnswersFalse() throws Exception {
        stub("/q/health", 200, "");
        AtomicReference<Boolean> answer = new AtomicReference<>();
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            answer.set(client.health());
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join();
        assertFalse(answer.get());
        assertTrue(stillInterrupted.get(), "le drapeau d'interruption doit être ré-armé");
    }

    /**
     * An INTERRUPTED release swallows the interruption the same way — the
     * lease expires by TTL — but re-arms the flag so the shutdown carries on.
     */
    @Test
    void releaseRestoresTheInterruptFlag() throws Exception {
        stub("/api/burn/reservations/77", 204, "");
        AtomicReference<Boolean> stillInterrupted = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            client.release(77L);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        worker.join();
        assertTrue(stillInterrupted.get(), "le drapeau d'interruption doit être ré-armé");
    }

    // --- earn error message ---

    /**
     * The failure message names the STATUS and appends the service's own
     * body: a 500 whose body says why is worth far more in a register log
     * than a bare "call failed".
     */
    @Test
    void earnFailureMessageCarriesStatusAndBody() {
        stub("/api/earn", 503, "maintenance window");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.earn("{}", "{}"));
        assertTrue(failure.getMessage().contains("503"), failure.getMessage());
        assertTrue(failure.getMessage().contains("maintenance window"), failure.getMessage());
    }

    /**
     * An EMPTY body still yields a usable message: the status alone is
     * appended, with no trailing noise (the body arm is present but empty —
     * the JDK client returns "" rather than null for a bodiless answer).
     */
    @Test
    void earnFailureMessageOnEmptyBodyKeepsTheStatus() {
        stub("/api/earn", 500, "");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client.earn("{}", "{}"));
        assertEquals("imfid /api/earn answered 500 ", failure.getMessage());
    }

    // --- credentials ---

    /**
     * BOTH credentials present: the Basic header is sent (already covered on
     * the nominal path, asserted here as the true/true leg of the guard).
     */
    @Test
    void authorizationIsSentWhenBothCredentialsArePresent() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.earn("{}", "{}");
        assertNotNull(receivedAuthorization.get());
    }

    /**
     * A USER without a password sends NO header: half a credential is not a
     * credential, and an incomplete Basic string would fail the whole call
     * rather than degrade to anonymous.
     */
    @Test
    void noAuthorizationWhenThePasswordIsMissing() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.password = Optional.empty();
        client.earn("{}", "{}");
        assertNull(receivedAuthorization.get());
    }

    /**
     * A PASSWORD without a user sends no header either (the mirror leg).
     */
    @Test
    void noAuthorizationWhenTheUserIsMissing() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.user = Optional.empty();
        client.earn("{}", "{}");
        assertNull(receivedAuthorization.get());
    }

    /**
     * The Basic header travels on EVERY authenticated endpoint, not just the
     * earn projection — the machine account identifies the register on the
     * account read, the reservation and the events alike.
     */
    @Test
    void authorizationTravelsOnTheOtherEndpointsToo() throws Exception {
        stub("/api/accounts/2990000000019", 200, "{\"status\":\"ACTIVE\",\"balance\":1}");
        client.account("2990000000019");
        assertNotNull(receivedAuthorization.get());

        receivedAuthorization.set(null);
        stub("/api/events/ticket-closed", 202, "");
        client.postEvent("/api/events/ticket-closed", "{}");
        assertNotNull(receivedAuthorization.get());
    }

    // --- lookup ---

    /**
     * A 200 on the lookup endpoint yields the parsed matches (non-empty list
     * arm), no CRM flag and no refusal reason.
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupReturnsMatchesOn200() throws Exception {
        stub("/api/cards/lookup", 200,
                "[{\"card\":\"2990000000019\",\"status\":\"ACTIVE\","
                + "\"lastName\":\"Dupont\",\"firstName\":\"Jean\"}]");
        ImfidClient.LookupResult result = client.lookup(null, "jean@x.fr", null, null);
        assertNotNull(result.matches);
        assertEquals(1, result.matches.size());
        assertEquals("2990000000019", result.matches.get(0).card);
        assertEquals("Dupont", result.matches.get(0).lastName);
        assertFalse(result.crmManaged);
        assertNull(result.refusalReason);
    }

    /**
     * A 200 with an empty array yields an empty match list (empty-list arm).
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupReturnsEmptyListOn200() throws Exception {
        stub("/api/cards/lookup", 200, "[]");
        ImfidClient.LookupResult result = client.lookup("0612345678", null, null, null);
        assertNotNull(result.matches);
        assertTrue(result.matches.isEmpty());
    }

    /**
     * A 422 whose reason names the CRM sets the CRM flag (reason-non-null and
     * startsWith arm) and leaves the matches null.
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupFlagsCrmOn422() throws Exception {
        stub("/api/cards/lookup", 422,
                "{\"reason\":\"Holder identity is managed by the CRM system\"}");
        ImfidClient.LookupResult result = client.lookup(null, null, "Dupont", "Jean");
        assertTrue(result.crmManaged);
        assertEquals("Holder identity is managed by the CRM system", result.refusalReason);
        assertNull(result.matches);
    }

    /**
     * A 422 whose reason does not name the CRM leaves the flag false
     * (reason-non-null but not startsWith arm) while echoing the reason.
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupDoesNotFlagCrmOnOther422() throws Exception {
        stub("/api/cards/lookup", 422, "{\"reason\":\"SOME_OTHER_REASON\"}");
        ImfidClient.LookupResult result = client.lookup(null, null, "Dupont", null);
        assertFalse(result.crmManaged);
        assertEquals("SOME_OTHER_REASON", result.refusalReason);
    }

    /**
     * A 422 with no reason leaves the flag false (reason-null arm).
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupDoesNotFlagCrmOnReasonlessRefusal() throws Exception {
        stub("/api/cards/lookup", 422, "{}");
        ImfidClient.LookupResult result = client.lookup(null, null, "Dupont", null);
        assertFalse(result.crmManaged);
        assertNull(result.refusalReason);
    }

    /**
     * Any other status is an unexpected answer the caller's breaker must see
     * as an exception (default throw arm).
     */
    @Test
    void lookupThrowsOnUnexpectedStatus() {
        stub("/api/cards/lookup", 503, "");
        assertThrows(IllegalStateException.class,
                () -> client.lookup(null, "jean@x.fr", null, null));
    }

    /**
     * Each supplied criterion is URL-encoded onto the query string as typed
     * (encoding arm); a blank or null criterion is omitted.
     *
     * @throws Exception on transport failure
     */
    @Test
    void lookupEncodesCriteria() throws Exception {
        stub("/api/cards/lookup", 200, "[]");
        client.lookup("06 12 34 56 78", "  ", null, "Éric");
        String line = receivedRequestLine.get();
        assertTrue(line.contains("phone=06+12+34+56+78"), line);
        assertTrue(line.contains("firstName=%C3%89ric"), line);
        assertFalse(line.contains("email="), line);
        assertFalse(line.contains("name=Dupont"), line);
    }

    // --- BO-11-04-04 : point d'appel fidélité administré ---

    /**
     * The ADMINISTERED base URL wins over the deployment property, so an
     * echelon can point its registers at another endpoint without a
     * redeployment (administered arm).
     */
    @Test
    void theAdministeredUrlWinsOverTheDeploymentProperty() {
        org.mockito.Mockito.when(client.posSettingsService.fidelityUrl())
                .thenReturn("http://imfid.echelon:8080");
        assertEquals("http://imfid.echelon:8080", client.targetUrl());
    }

    /**
     * A SECOND administered value lands as itself, which is what proves the
     * setting is read rather than a literal returned; padding is trimmed.
     */
    @Test
    void aSecondAdministeredUrlLandsTrimmed() {
        org.mockito.Mockito.when(client.posSettingsService.fidelityUrl())
                .thenReturn("  http://imfid.other:9090  ");
        assertEquals("http://imfid.other:9090", client.targetUrl());
    }

    /**
     * A BLANK administered URL leaves the deployment property in charge (blank
     * arm — the leg a null check alone would miss).
     */
    @Test
    void aBlankAdministeredUrlKeepsTheDeploymentProperty() {
        org.mockito.Mockito.when(client.posSettingsService.fidelityUrl()).thenReturn("   ");
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort(), client.targetUrl());
    }

    /**
     * A NULL administered URL behaves like a blank one (null arm).
     */
    @Test
    void aNullAdministeredUrlKeepsTheDeploymentProperty() {
        org.mockito.Mockito.when(client.posSettingsService.fidelityUrl()).thenReturn(null);
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort(), client.targetUrl());
    }

    /**
     * An administered URL with no deployment property at all still configures
     * the service: administering the endpoint is enough on its own.
     */
    @Test
    void anAdministeredUrlAloneConfiguresTheService() {
        ImfidClient bare = new ImfidClient();
        bare.posSettingsService = settings();
        bare.url = Optional.empty();
        org.mockito.Mockito.when(bare.posSettingsService.fidelityUrl())
                .thenReturn("http://imfid.echelon:8080");
        org.mockito.Mockito.when(bare.posSettingsService.fidelityExternalEnabled()).thenReturn(true);
        assertTrue(bare.isConfigured());
        assertEquals("http://imfid.echelon:8080", bare.targetUrl());
    }

    /**
     * The ADMINISTERED machine account is the one sent in the Basic header, the
     * deployment property being only its fallback (administered arm).
     *
     * @throws Exception on transport
     */
    @Test
    void theAdministeredUserIsSentInTheBasicHeader() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        org.mockito.Mockito.when(client.posSettingsService.fidelityUser()).thenReturn("caisse-12");
        client.earn("{}", "{}");
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("caisse-12:pos-password".getBytes()),
                receivedAuthorization.get());
    }

    /**
     * A SECOND administered account lands as itself, padding trimmed.
     *
     * @throws Exception on transport
     */
    @Test
    void aSecondAdministeredUserIsSentTrimmed() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        org.mockito.Mockito.when(client.posSettingsService.fidelityUser()).thenReturn("  caisse-7  ");
        client.earn("{}", "{}");
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("caisse-7:pos-password".getBytes()),
                receivedAuthorization.get());
    }

    /**
     * A BLANK administered account leaves the deployment property in charge
     * (blank arm).
     *
     * @throws Exception on transport
     */
    @Test
    void aBlankAdministeredUserKeepsTheDeploymentProperty() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        org.mockito.Mockito.when(client.posSettingsService.fidelityUser()).thenReturn("   ");
        client.earn("{}", "{}");
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("pos:pos-password".getBytes()),
                receivedAuthorization.get());
    }

    /**
     * An administered account with NO deployment user still authenticates: the
     * administered half is a complete credential once the password is on file.
     *
     * @throws Exception on transport
     */
    @Test
    void anAdministeredUserAloneAuthenticates() throws Exception {
        stub("/api/earn", 200, "{\"total\":0,\"entries\":[]}");
        client.user = Optional.empty();
        org.mockito.Mockito.when(client.posSettingsService.fidelityUser()).thenReturn("caisse-12");
        client.earn("{}", "{}");
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString("caisse-12:pos-password".getBytes()),
                receivedAuthorization.get());
    }
}
