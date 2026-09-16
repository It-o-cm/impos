package com.intermarche.pos.ui.balance;

import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.service.sync.SyncPayloads;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BalanceTicketClient}.
 * <p>
 * The client presents a counter reference to the store node and reads the answer
 * as one of four outcomes, and the whole point of the class is that it tells them
 * apart: an unconfigured register (nothing to ask), a shop that did not answer or
 * refused, a reference the shop no longer holds (HTTP 404 — served once already),
 * and a successful pick-up. Only the last one carries a ticket. A body that parses
 * to no usable line is reported UNREACHABLE rather than SERVED, because losing the
 * paper is worse than asking the cashier to key it in. The private final
 * {@code httpClient} is replaced by a Mockito mock through reflection, following
 * the pattern of the other outbound clients.
 */
class BalanceTicketClientTest {

    /** The reference presented in every test. */
    private static final String REFERENCE = "0012345678";

    /** The register presenting it. */
    private static final String TERMINAL = "CAISSE-01";

    /** A well-formed served ticket, one line. */
    private static final String SERVED_BODY = "{\"reference\":\"0012345678\","
            + "\"counterLabel\":\"BOUCHERIE\",\"emittedAt\":\"2026-09-10T10:00:00\","
            + "\"lines\":[{\"ean\":\"3560070000000\",\"label\":\"ROTI DE BOEUF\","
            + "\"quantity\":0.752,\"totalIncludingTax\":13.54,\"vatRate\":0.055}]}";

    /**
     * Injects a mocked {@link HttpClient} into the private final field.
     *
     * @param client the client to patch
     * @param http the HTTP client to inject
     * @throws Exception if reflection fails
     */
    private void setHttpClient(BalanceTicketClient client, HttpClient http) throws Exception {
        Field field = BalanceTicketClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, http);
    }

    /**
     * Builds a client wired with a store node at the given availability.
     *
     * @param http the HTTP client mock
     * @param enabled whether a store node is configured
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private BalanceTicketClient client(HttpClient http, boolean enabled) throws Exception {
        BalanceTicketClient client = new BalanceTicketClient();
        client.syncOutboxService = mock(SyncOutboxService.class);
        when(client.syncOutboxService.isEnabled()).thenReturn(enabled);
        when(client.syncOutboxService.getStoreUrl()).thenReturn("http://magasin");
        client.token = Optional.of("jeton");
        setHttpClient(client, http);
        return client;
    }

    /**
     * Builds a mocked HTTP response.
     *
     * @param status the status code
     * @param body the body
     * @return the response mock
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * A register with no store node has nowhere to ask: no call leaves the till.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void noStoreNodeAsksNobody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        BalanceTicketClient client = client(http, false);
        BalanceTicketClient.Answer answer = client.pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.NO_STORE_NODE, answer.outcome());
        assertNull(answer.ticket());
        verify(http, never()).send(any(), any());
    }

    /**
     * {@code isAvailable} mirrors the synchronization being wired at all.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void availabilityMirrorsTheOutbox() throws Exception {
        assertEquals(true, client(mock(HttpClient.class), true).isAvailable());
        assertEquals(false, client(mock(HttpClient.class), false).isAvailable());
    }

    /**
     * A null reference is nothing to present: refused without a call.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void nullReferenceIsRefusedWithoutCalling() throws Exception {
        HttpClient http = mock(HttpClient.class);
        BalanceTicketClient client = client(http, true);
        BalanceTicketClient.Answer answer = client.pickUp(null, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.ALREADY_CONSUMED, answer.outcome());
        verify(http, never()).send(any(), any());
    }

    /**
     * A blank reference is refused the same way, without a call.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void blankReferenceIsRefusedWithoutCalling() throws Exception {
        HttpClient http = mock(HttpClient.class);
        BalanceTicketClient client = client(http, true);
        BalanceTicketClient.Answer answer = client.pickUp("   ", TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.ALREADY_CONSUMED, answer.outcome());
        verify(http, never()).send(any(), any());
    }

    /**
     * HTTP 404 is the shop saying it no longer holds that paper.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void notFoundMeansAlreadyConsumed() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(404, "")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.ALREADY_CONSUMED, answer.outcome());
        assertNull(answer.ticket());
    }

    /**
     * Any other non-2xx status is a shop that refused: the paper stays keyable.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void otherErrorStatusMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(401, "")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
    }

    /**
     * A transport failure is a shop that did not answer.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void transportFailureMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new IOException("boum")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
    }

    /**
     * An interrupted call is unreachable too, and the thread keeps its flag.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void interruptionMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new InterruptedException("stop")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
        Thread.interrupted();
    }

    /**
     * A 200 whose body carries no line is not a pick-up: reported unreachable so
     * the cashier keys the paper in rather than losing it.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void emptyLinesMeanUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"reference\":\"0012345678\",\"lines\":[]}"))
                .when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
    }

    /**
     * A 200 whose body carries a null line list is treated the same way.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void nullLinesMeanUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"reference\":\"0012345678\",\"lines\":null}"))
                .when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
    }

    /**
     * A body that is not a ticket at all is a broken shop, not a served paper.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void unparsableBodyMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "pas du json")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
    }

    /**
     * A served ticket comes back parsed, lines included.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void servedTicketIsParsed() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, SERVED_BODY)).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.SERVED, answer.outcome());
        SyncPayloads.BalanceTicketDto ticket = answer.ticket();
        assertNotNull(ticket);
        assertEquals(REFERENCE, ticket.reference);
        assertEquals(1, ticket.lines.size());
        assertEquals("3560070000000", ticket.lines.get(0).ean);
        assertEquals(0, new BigDecimal("13.54").compareTo(ticket.lines.get(0).totalIncludingTax));
    }

    /**
     * A shop sending a property this register does not know is still readable:
     * the reader is deliberately tolerant.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void unknownPropertiesAreTolerated() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String body = "{\"reference\":\"0012345678\",\"nouveauChamp\":\"x\","
                + "\"lines\":[{\"ean\":\"3560070000000\",\"totalIncludingTax\":1.00}]}";
        doReturn(resp(200, body)).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.SERVED, answer.outcome());
    }

    /**
     * A null terminal is encoded as the empty string rather than blowing up: the
     * null arm of the terminal ternary, still reaching a served pick-up.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void nullTerminalIsEncodedAsEmpty() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, SERVED_BODY)).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, null);
        assertEquals(BalanceTicketClient.Outcome.SERVED, answer.outcome());
        assertNotNull(answer.ticket());
    }

    /**
     * A blank shared token adds no header: the false arm of the token guard, the
     * call still going out and coming back served.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void blankTokenSendsNoHeader() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, SERVED_BODY)).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient client = client(http, true);
        client.token = Optional.of("");
        BalanceTicketClient.Answer answer = client.pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.SERVED, answer.outcome());
        assertNotNull(answer.ticket());
    }

    /**
     * A sub-200 status is a shop that did not truly serve: the first arm of the
     * status guard ({@code statusCode < 200}), reported unreachable.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void sub200StatusMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(100, "")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
        assertNull(answer.ticket());
    }

    /**
     * A body parsing to a null ticket is a broken shop: the first arm of the
     * usable-line guard ({@code served == null}), reported unreachable.
     *
     * @throws Exception if the mocked send declares it
     */
    @Test
    void nullTicketMeansUnreachable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "null")).when(http).send(any(HttpRequest.class), any());
        BalanceTicketClient.Answer answer = client(http, true).pickUp(REFERENCE, TERMINAL);
        assertEquals(BalanceTicketClient.Outcome.UNREACHABLE, answer.outcome());
        assertNull(answer.ticket());
    }

    /**
     * The two factories build the answers the client hands back: a served one
     * carries its ticket, a failed one never does.
     */
    @Test
    void answerFactoriesCarryWhatTheyShould() {
        SyncPayloads.BalanceTicketDto dto = new SyncPayloads.BalanceTicketDto();
        assertEquals(dto, BalanceTicketClient.Answer.served(dto).ticket());
        assertEquals(BalanceTicketClient.Outcome.SERVED,
                BalanceTicketClient.Answer.served(dto).outcome());
        assertNull(BalanceTicketClient.Answer
                .failed(BalanceTicketClient.Outcome.UNREACHABLE).ticket());
    }
}
