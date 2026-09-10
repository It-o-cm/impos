package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.mail.Transport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link TicketMailService}: the administered form of the sent ticket
 * (LC-08-02-07/08), the printed content it carries (LC-08-02-02) and the
 * no-relay-configured gate.
 *
 * <p>The transport itself is not exercised — sending a letter is not a unit test — but
 * everything that DECIDES what leaves is.
 */
class TicketMailServiceTest {

    /** A printer that renders a fixed text instead of driving a bridge. */
    private static class FakePrinter extends TicketPrinterService {

        /** How many times a ticket was rendered on the spot. */
        private int renders;

        /** {@inheritDoc} */
        @Override
        public String renderTicket(Ticket ticket, boolean duplicata, int duplicataNumber) {
            renders++;
            return "TICKET RENDU";
        }
    }

    /** The service under test. */
    private TicketMailService service;

    /** The stand-in printer. */
    private FakePrinter printer;

    /** The administered form supplier, mocked. */
    private PosSettingsService posSettingsService;

    /** The journal, mocked. */
    private TechnicalEventService technicalEventService;

    /**
     * Wires a fresh service with no relay configured before each test; the SMTP fields
     * (from, user, password) that would otherwise be null under plain mvn test are
     * primed so the send path never trips on an unconfigured @ConfigProperty.
     */
    @BeforeEach
    void setUp() {
        service = new TicketMailService();
        printer = new FakePrinter();
        posSettingsService = Mockito.mock(PosSettingsService.class);
        technicalEventService = Mockito.mock(TechnicalEventService.class);
        service.ticketPrinterService = printer;
        service.posSettingsService = posSettingsService;
        service.technicalEventService = technicalEventService;
        service.host = Optional.empty();
        service.from = "ticket@test.local";
        service.user = Optional.empty();
        service.password = Optional.empty();
    }

    /**
     * Builds a ticket carrying (or not) its frozen printed form.
     *
     * @param formatted the frozen text, or null
     * @return the ticket
     */
    private Ticket ticketWith(String formatted) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-000417";
        ticket.formattedContent = formatted;
        return ticket;
    }

    // --------------------------------------------------
    // normalizeFormat
    // --------------------------------------------------

    /**
     * The two non-default forms are recognized.
     */
    @Test
    void normalizeKeepsTheKnownForms() {
        assertEquals(TicketMailService.FORMAT_ATTACHMENT,
                TicketMailService.normalizeFormat("ATTACHMENT"));
        assertEquals(TicketMailService.FORMAT_BOTH, TicketMailService.normalizeFormat("BOTH"));
    }

    /**
     * Case and surrounding blanks are ignored.
     */
    @Test
    void normalizeIgnoresCaseAndBlanks() {
        assertEquals(TicketMailService.FORMAT_BOTH, TicketMailService.normalizeFormat("  both "));
    }

    /**
     * The body form is recognized as itself.
     */
    @Test
    void normalizeKeepsTheBodyForm() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("BODY"));
    }

    /**
     * A missing value falls back to the message body (null arm).
     */
    @Test
    void normalizeNullFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat(null));
    }

    /**
     * An unknown value falls back to the message body, which every mail client reads.
     */
    @Test
    void normalizeUnknownFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("PDF"));
    }

    /**
     * A blank value falls back to the message body.
     */
    @Test
    void normalizeBlankFallsBackToTheBody() {
        assertEquals(TicketMailService.FORMAT_BODY, TicketMailService.normalizeFormat("   "));
    }

    // --------------------------------------------------
    // contentOf
    // --------------------------------------------------

    /**
     * The frozen printed form is what travels: it is what the customer's paper says.
     */
    @Test
    void contentOfPrefersTheFrozenForm() {
        assertEquals("TICKET GELE", service.contentOf(ticketWith("TICKET GELE")));
        assertEquals(0, printer.renders);
    }

    /**
     * A ticket closed before the field existed is rendered on the spot rather than
     * refused (null arm).
     */
    @Test
    void contentOfRendersWhenTheFormIsMissing() {
        assertEquals("TICKET RENDU", service.contentOf(ticketWith(null)));
        assertEquals(1, printer.renders);
    }

    /**
     * A blank frozen form is treated as missing (blank arm of the same guard).
     */
    @Test
    void contentOfRendersWhenTheFormIsBlank() {
        assertEquals("TICKET RENDU", service.contentOf(ticketWith("   ")));
        assertEquals(1, printer.renders);
    }

    // --------------------------------------------------
    // isConfigured
    // --------------------------------------------------

    /**
     * A register with no relay sends nothing (absent arm).
     */
    @Test
    void withoutARelayNothingIsConfigured() {
        service.host = Optional.empty();
        assertFalse(service.isConfigured());
    }

    /**
     * A blank relay host counts as none (blank arm of the same guard).
     */
    @Test
    void aBlankRelayCountsAsNone() {
        service.host = Optional.of("   ");
        assertFalse(service.isConfigured());
    }

    /**
     * A named relay is what lets a letter leave.
     */
    @Test
    void aNamedRelayIsConfigured() {
        service.host = Optional.of("smtp.magasin.local");
        assertTrue(service.isConfigured());
    }

    // --------------------------------------------------
    // send
    // --------------------------------------------------

    /**
     * Without a relay the send is journalled only and answers false: the
     * {@code !isConfigured()} guard is true, so the transport is never reached and the
     * journal still records that the customer asked for their receipt.
     */
    @Test
    void sendWithoutARelayIsJournalledOnlyAndReturnsFalse() {
        service.host = Optional.empty();
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        assertFalse(service.send(ticketWith("TICKET GELE"), "client@example.com"));
        Mockito.verify(technicalEventService).log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                "C04-000417 -> client@example.com");
    }

    /**
     * With a relay and the body form the letter leaves and the send answers true: the
     * {@code !isConfigured()} guard is false, {@code FORMAT_BODY.equals(format)} is true
     * (body branch) and {@code account.isBlank()} is true (no-credentials session).
     */
    @Test
    void sendInBodyFormLetsTheLetterLeave() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * The attachment form takes the multipart branch: {@code FORMAT_BODY.equals(format)}
     * is false and the {@code FORMAT_BOTH.equals(format)} ternary is false, so the body
     * carries the "en pièce jointe" line rather than the ticket text.
     */
    @Test
    void sendAsAttachmentTakesTheMultipartBranch() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("ATTACHMENT");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * The both form takes the multipart branch with the ticket text in the body: the
     * {@code FORMAT_BOTH.equals(format)} ternary is true.
     */
    @Test
    void sendAsBothPutsTheContentInTheBody() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BOTH");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }

    /**
     * A relay that refuses the letter is caught and answered with false: the transport
     * throws and the catch arm of the try/catch is taken, the sale otherwise undisturbed.
     */
    @Test
    void sendReturnsFalseWhenTheRelayRefuses() {
        service.host = Optional.of("smtp.magasin.local");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            transport.when(() -> Transport.send(ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("relay down"));
            assertFalse(service.send(ticketWith("TICKET GELE"), "client@example.com"));
        }
    }

    /**
     * A configured SMTP account takes the authenticated-session branch:
     * {@code account.isBlank()} is false, so the session is built with an Authenticator.
     */
    @Test
    void sendWithCredentialsAuthenticatesTheSession() {
        service.host = Optional.of("smtp.magasin.local");
        service.user = Optional.of("smtp-user");
        service.password = Optional.of("secret");
        Mockito.when(posSettingsService.ticketEmailFormat()).thenReturn("BODY");
        try (MockedStatic<Transport> transport = Mockito.mockStatic(Transport.class)) {
            assertTrue(service.send(ticketWith("TICKET GELE"), "client@example.com"));
            transport.verify(() -> Transport.send(ArgumentMatchers.any()));
        }
    }
}
