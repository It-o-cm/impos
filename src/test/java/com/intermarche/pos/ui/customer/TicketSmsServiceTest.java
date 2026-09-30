package com.intermarche.pos.ui.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketSmsService} (EXP-48), targeting 100% branch coverage.
 * <p>
 * The service journals the send whatever the gateway state and returns whether a
 * message actually left. With a gateway it posts to the SMS provider, falling back
 * to "journalled only" on any transport failure. The private final
 * {@code httpClient} is swapped by reflection so the HTTP branches run without a
 * socket; a real {@link ObjectMapper} makes the (de)serialization genuine.
 * Coverage exercises all three arms of {@code isConfigured} (absent, blank,
 * present), the token filter (blank vs non-blank), every condition of the
 * status/body compound, the sent true/false answers and the catch arm — and the
 * journal is verified even on the exception path.
 */
class TicketSmsServiceTest {

    /**
     * Builds a service with a mocked journal, a real mapper, the given gateway URL
     * and sync token, and the mocked HTTP client forced onto the private field.
     *
     * @param gatewayUrl the configured gateway URL Optional
     * @param syncToken the shared-token Optional
     * @param http the mocked HTTP client
     * @return the wired service
     * @throws Exception if reflection fails
     */
    private TicketSmsService service(Optional<String> gatewayUrl, Optional<String> syncToken,
            HttpClient http) throws Exception {
        TicketSmsService service = new TicketSmsService();
        service.gatewayUrl = gatewayUrl;
        service.syncToken = syncToken;
        service.technicalEventService = mock(TechnicalEventService.class);
        service.objectMapper = new ObjectMapper();
        Field field = TicketSmsService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, http);
        return service;
    }

    /**
     * A freshly built ticket carrying a number, for the journal detail and body.
     *
     * @return a ticket with a number
     */
    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-0001";
        return ticket;
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
     * {@code isConfigured} is false when no gateway URL is set (absent arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void isConfiguredFalseWhenNoGateway() throws Exception {
        assertFalse(service(Optional.empty(), Optional.empty(), mock(HttpClient.class)).isConfigured());
    }

    /**
     * {@code isConfigured} is false when the gateway URL is present but blank.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void isConfiguredFalseWhenGatewayBlank() throws Exception {
        assertFalse(service(Optional.of("   "), Optional.empty(), mock(HttpClient.class)).isConfigured());
    }

    /**
     * {@code isConfigured} is true when a non-blank gateway URL is set.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void isConfiguredTrueWhenGatewaySet() throws Exception {
        assertTrue(service(Optional.of("https://sms.example"), Optional.empty(), mock(HttpClient.class))
                .isConfigured());
    }

    /**
     * {@code send} journals the request and returns false when no gateway is
     * configured — the message is journalled only (isConfigured false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendJournalsAndReturnsFalseWithoutGateway() throws Exception {
        TicketSmsService service = service(Optional.empty(), Optional.empty(), mock(HttpClient.class));
        boolean left = service.send(ticket(), "0612345678");
        assertFalse(left);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), detail.capture());
        assertTrue(detail.getValue().contains("SMS 0612345678"));
    }

    /**
     * {@code send} posts and returns true on a 200 with sent=true (success arm;
     * token filter true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsTrueOnA200Sent() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"sent\":true}")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.of("TOK"), http);
        assertTrue(service.send(ticket(), "0612345678"));
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code send} returns false on a 200 with sent=false (answer false arm); a
     * blank present token is filtered out (token filter false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnA200NotSent() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"sent\":false}")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.of("   "), http);
        assertFalse(service.send(ticket(), "0612345678"));
    }

    /**
     * A status below 200 is journalled only (compound first condition true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnStatusBelow200() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.empty(), http);
        assertFalse(service.send(ticket(), "0612345678"));
    }

    /**
     * A non-2xx answer is journalled only (status &ge; 300 arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.empty(), http);
        assertFalse(service.send(ticket(), "0612345678"));
    }

    /**
     * A 200 answer with a null body is journalled only (null-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnNullBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, null)).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.empty(), http);
        assertFalse(service.send(ticket(), "0612345678"));
    }

    /**
     * A 200 answer with a blank body is journalled only (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.empty(), http);
        assertFalse(service.send(ticket(), "0612345678"));
    }

    /**
     * A thrown transport error is journalled only (catch arm); the journal is still
     * written first, so the customer's request is recorded.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void sendReturnsFalseOnExceptionButStillJournals() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        TicketSmsService service = service(Optional.of("https://sms.example"), Optional.empty(), http);
        assertFalse(service.send(ticket(), "0612345678"));
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), org.mockito.ArgumentMatchers.anyString());
    }
}
