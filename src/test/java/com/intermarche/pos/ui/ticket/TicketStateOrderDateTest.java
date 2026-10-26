package com.intermarche.pos.ui.ticket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the ORDER DATE a ticket remembers (BO-10-03-17).
 *
 * <p>Branch enumeration of {@code noteOrderDate}: a date already recorded, a
 * null date, a blank date, a date recorded (trimmed); and the reset by
 * {@code clear}.
 */
class TicketStateOrderDateTest {

    /**
     * The first order date loaded is recorded trimmed; a later one does not
     * replace it.
     */
    @Test
    void theFirstOrderDateWins() {
        TicketState ticket = new TicketState();
        ticket.noteOrderDate(" 2026-09-30T10:15:00 ");
        ticket.noteOrderDate("2026-10-01T08:00:00");
        assertEquals("2026-09-30T10:15:00", ticket.orderDate);
    }

    /**
     * A null or blank date records nothing.
     */
    @Test
    void aMissingDateRecordsNothing() {
        TicketState ticket = new TicketState();
        ticket.noteOrderDate(null);
        assertNull(ticket.orderDate);
        ticket.noteOrderDate("  ");
        assertNull(ticket.orderDate);
    }

    /**
     * Clearing the ticket forgets the order date, so it never reaches the next
     * customer's basket.
     */
    @Test
    void clearForgetsTheOrderDate() {
        TicketState ticket = new TicketState();
        ticket.noteOrderDate("2026-09-30T10:15:00");
        ticket.clear();
        assertNull(ticket.orderDate);
    }
}
