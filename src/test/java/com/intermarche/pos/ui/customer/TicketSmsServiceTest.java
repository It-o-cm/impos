package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TicketSmsService} (EXP-48).
 * <p>
 * The service journals the send whatever the gateway state and returns whether a
 * message actually left. Every collaborator is a Mockito mock and the config
 * fields are set directly; the {@code TechnicalEventService.log} call is verified.
 * Both arms of the {@code isConfigured} guard (absent gateway, present gateway)
 * and both arms of {@code send} (journalled-only vs sent) are covered. Each test
 * is isolated and asserts absolute expected values.
 */
class TicketSmsServiceTest {

    /**
     * Builds a service with a mocked journal and the given gateway URL.
     *
     * @param gatewayUrl the configured gateway URL Optional
     * @return the wired service
     */
    private TicketSmsService service(Optional<String> gatewayUrl) {
        TicketSmsService service = new TicketSmsService();
        service.gatewayUrl = gatewayUrl;
        service.technicalEventService = mock(TechnicalEventService.class);
        return service;
    }

    /**
     * A freshly built ticket carrying a number, for the journal detail.
     *
     * @return a ticket with a number
     */
    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-0001";
        return ticket;
    }

    /**
     * {@code isConfigured} is false when no gateway URL is set (absent arm).
     */
    @Test
    void isConfiguredFalseWhenNoGateway() {
        assertFalse(service(Optional.empty()).isConfigured());
    }

    /**
     * {@code isConfigured} is false when the gateway URL is present but blank.
     */
    @Test
    void isConfiguredFalseWhenGatewayBlank() {
        assertFalse(service(Optional.of("   ")).isConfigured());
    }

    /**
     * {@code isConfigured} is true when a non-blank gateway URL is set.
     */
    @Test
    void isConfiguredTrueWhenGatewaySet() {
        assertTrue(service(Optional.of("https://sms.example")).isConfigured());
    }

    /**
     * {@code send} journals the request and returns false when no gateway is
     * configured — the message is journalled only.
     */
    @Test
    void sendJournalsAndReturnsFalseWithoutGateway() {
        TicketSmsService service = service(Optional.empty());
        boolean left = service.send(ticket(), "0612345678");
        assertFalse(left);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), detail.capture());
        assertTrue(detail.getValue().contains("SMS 0612345678"));
    }

    /**
     * {@code send} journals the request and returns true when a gateway is
     * configured — a message actually leaves.
     */
    @Test
    void sendJournalsAndReturnsTrueWithGateway() {
        TicketSmsService service = service(Optional.of("https://sms.example"));
        boolean left = service.send(ticket(), "0612345678");
        assertTrue(left);
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), org.mockito.ArgumentMatchers.anyString());
    }
}
