package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    /**
     * Wires a fresh service with no relay configured before each test.
     */
    @BeforeEach
    void setUp() {
        service = new TicketMailService();
        printer = new FakePrinter();
        service.ticketPrinterService = printer;
        service.host = Optional.empty();
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
}
