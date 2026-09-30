package com.intermarche.pos.ui.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sale.Ticket;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InterTillResumeClient} (EXP-46), targeting 100% branch
 * coverage.
 * <p>
 * The client is the seam to the store node's inter-till parked-ticket resume.
 * With no {@code pos.parking.node-url} it is a LOCAL stub that reaches nothing, so
 * every lookup resolves to null. With a URL it issues an HTTP GET and maps the
 * node's summary onto a {@link Ticket}. The private final {@code httpClient} is
 * swapped by reflection so the HTTP branches run without a socket; a real
 * {@link ObjectMapper} makes the deserialization genuine. Coverage exercises both
 * legs of the blank-input and no-URL guards, the token filter (blank vs
 * non-blank), every condition of the status/body compound, the catch arm, and all
 * arms of the status mapping (valid, unknown, null, blank).
 */
class InterTillResumeClientTest {

    /**
     * Builds a client with the given node URL and token, a real mapper and the
     * mocked HTTP client forced onto the private final field.
     *
     * @param nodeUrl the node URL Optional
     * @param token the shared-token Optional
     * @param http the mocked HTTP client
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private InterTillResumeClient client(Optional<String> nodeUrl, Optional<String> token,
            HttpClient http) throws Exception {
        InterTillResumeClient client = new InterTillResumeClient();
        client.nodeUrl = nodeUrl;
        client.token = token;
        client.objectMapper = new ObjectMapper();
        Field field = InterTillResumeClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, http);
        return client;
    }

    /**
     * Builds a mocked string HTTP response with the given status and body.
     *
     * @param status the HTTP status
     * @param body the response body
     * @return the mocked response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * A null ticket number resolves to null (blank-guard null arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void nullNumberResolvesToNull() throws Exception {
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetchParkedTicket(null));
    }

    /**
     * A blank ticket number resolves to null (blank-guard isBlank arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void blankNumberResolvesToNull() throws Exception {
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetchParkedTicket("   "));
    }

    /**
     * With no node URL configured, a real number still resolves to null (empty arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void noNodeUrlResolvesToNull() throws Exception {
        InterTillResumeClient client = client(Optional.empty(), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetchParkedTicket("C04-00000001"));
    }

    /**
     * With a blank node URL, a real number still resolves to null (isBlank arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void blankNodeUrlResolvesToNull() throws Exception {
        InterTillResumeClient client = client(Optional.of("   "), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetchParkedTicket("C04-00000001"));
    }

    /**
     * A 200 answer with a PARKED summary and a non-blank token maps onto a Ticket
     * (success arm; status/body all false; token filter true arm; status VALID arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchMapsA200ParkedSummary() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"terminalId\":\"C05\",\"status\":\"PARKED\",\"grandTotal\":42.50}"))
                .when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.of("TOK"), http);
        Ticket ticket = client.fetchParkedTicket("C05-00000007");
        Assertions.assertEquals("C05-00000007", ticket.ticketNumber);
        Assertions.assertEquals("C05", ticket.terminalId);
        Assertions.assertEquals(0, new BigDecimal("42.50").compareTo(ticket.grandTotal));
        Assertions.assertEquals(Ticket.TicketStatus.PARKED, ticket.status);
    }

    /**
     * An unknown status string leaves the ticket at its default OPEN (status
     * mapping unknown/catch arm); a blank present token is filtered out (token
     * filter false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchWithUnknownStatusLeavesDefault() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"terminalId\":\"C05\",\"status\":\"FOO\",\"grandTotal\":1.00}"))
                .when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.of("   "), http);
        Ticket ticket = client.fetchParkedTicket("C05-00000007");
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, ticket.status);
    }

    /**
     * A missing status field maps to a null status (status mapping null arm),
     * leaving the ticket at its default OPEN.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchWithMissingStatusLeavesDefault() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"terminalId\":\"C05\",\"grandTotal\":1.00}"))
                .when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Ticket ticket = client.fetchParkedTicket("C05-00000007");
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, ticket.status);
    }

    /**
     * A blank status string maps to a null status (status mapping blank arm),
     * leaving the ticket at its default OPEN.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchWithBlankStatusLeavesDefault() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"terminalId\":\"C05\",\"status\":\"  \",\"grandTotal\":1.00}"))
                .when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Ticket ticket = client.fetchParkedTicket("C05-00000007");
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, ticket.status);
    }

    /**
     * A status below 200 resolves to null (compound first condition true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnStatusBelow200() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Assertions.assertNull(client.fetchParkedTicket("C05-00000007"));
    }

    /**
     * A non-2xx answer resolves to null (status &ge; 300 arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Assertions.assertNull(client.fetchParkedTicket("C05-00000007"));
    }

    /**
     * A 200 answer with a null body resolves to null (null-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnNullBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, null)).when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Assertions.assertNull(client.fetchParkedTicket("C05-00000007"));
    }

    /**
     * A 200 answer with a blank body resolves to null (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Assertions.assertNull(client.fetchParkedTicket("C05-00000007"));
    }

    /**
     * A thrown transport error resolves to null (catch arm) rather than
     * propagating — a node down never disturbs the lane.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        InterTillResumeClient client = client(Optional.of("http://node"), Optional.empty(), http);
        Assertions.assertNull(client.fetchParkedTicket("C05-00000007"));
    }
}
