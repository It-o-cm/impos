package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.ticket.Ticket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link InvoiceState}: the three steps, the two shortlists and the
 * clearing that must leave nothing of one document on the next.
 */
class InvoiceStateTest {

    /**
     * Builds a list of tickets of the given size.
     *
     * @param count how many
     * @return the tickets
     */
    private List<Ticket> tickets(int count) {
        List<Ticket> list = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Ticket ticket = new Ticket();
            ticket.ticketNumber = "T" + index;
            list.add(ticket);
        }
        return list;
    }

    /**
     * Builds a list of customers of the given size.
     *
     * @param count how many
     * @return the customers
     */
    private List<AccountCustomer> customers(int count) {
        List<AccountCustomer> list = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            AccountCustomer customer = new AccountCustomer();
            customer.companyName = "C" + index;
            list.add(customer);
        }
        return list;
    }

    /**
     * A fresh state opens on the ticket step, and only that one.
     */
    @Test
    void opensOnTheTicketStep() {
        InvoiceState state = new InvoiceState();
        assertTrue(state.isOnTicketStep());
        assertFalse(state.isOnCustomerStep());
        assertFalse(state.isOnPreviewStep());
    }

    /**
     * Each step reports itself and excludes the two others.
     */
    @Test
    void reportsTheCustomerStepAndThenThePreviewStep() {
        InvoiceState state = new InvoiceState();
        state.step = InvoiceState.Step.CUSTOMER;
        assertFalse(state.isOnTicketStep());
        assertTrue(state.isOnCustomerStep());
        assertFalse(state.isOnPreviewStep());
        state.step = InvoiceState.Step.PREVIEW;
        assertFalse(state.isOnCustomerStep());
        assertTrue(state.isOnPreviewStep());
    }

    /**
     * A shortlist shorter than the screen is shown whole — the false arm of the cap.
     */
    @Test
    void showsAShortListWhole() {
        InvoiceState state = new InvoiceState();
        state.tickets = tickets(2);
        state.customers = customers(2);
        assertEquals(2, state.getVisibleTickets().size());
        assertEquals(2, state.getVisibleCustomers().size());
    }

    /**
     * A shortlist exactly the size of the screen is still shown whole, the boundary
     * of the cap.
     */
    @Test
    void showsAFullScreenWhole() {
        InvoiceState state = new InvoiceState();
        state.tickets = tickets(InvoiceState.TICKET_ROWS);
        state.customers = customers(InvoiceState.CUSTOMER_ROWS);
        assertEquals(InvoiceState.TICKET_ROWS, state.getVisibleTickets().size());
        assertEquals(InvoiceState.CUSTOMER_ROWS, state.getVisibleCustomers().size());
    }

    /**
     * A longer shortlist is capped to what the screen shows — the true arm.
     */
    @Test
    void capsALongList() {
        InvoiceState state = new InvoiceState();
        state.tickets = tickets(InvoiceState.TICKET_ROWS + 5);
        state.customers = customers(InvoiceState.CUSTOMER_ROWS + 5);
        assertEquals(InvoiceState.TICKET_ROWS, state.getVisibleTickets().size());
        assertEquals(InvoiceState.CUSTOMER_ROWS, state.getVisibleCustomers().size());
        assertEquals("T0", state.getVisibleTickets().get(0).ticketNumber);
    }

    /**
     * Clearing leaves nothing of the previous document: no ticket, no customer, no
     * search, no error, and the step back at the start.
     */
    @Test
    void clearingLeavesNothingBehind() {
        InvoiceState state = new InvoiceState();
        state.step = InvoiceState.Step.PREVIEW;
        state.tickets = tickets(3);
        state.ticketNumber = "T1";
        state.ticket = new Ticket();
        state.customerSearch = "BOU";
        state.customers = customers(3);
        state.customer = new AccountCustomer();
        state.creatingCustomer = true;
        state.searched = true;
        state.issuedInvoiceId = 7L;
        state.error = "KO";

        state.clear();

        assertTrue(state.isOnTicketStep());
        assertTrue(state.tickets.isEmpty());
        assertEquals("", state.ticketNumber);
        assertNull(state.ticket);
        assertEquals("", state.customerSearch);
        assertTrue(state.customers.isEmpty());
        assertNull(state.customer);
        assertFalse(state.creatingCustomer);
        assertFalse(state.searched);
        assertNull(state.issuedInvoiceId);
        assertEquals("", state.error);
    }

    /**
     * A state that has not searched yet does NOT report an empty search — the two
     * mean different things and the screen shows different words for them.
     */
    @Test
    void reportsNoEmptySearchBeforeSearching() {
        InvoiceState state = new InvoiceState();
        assertFalse(state.isSearchWithoutMatch());
    }

    /**
     * A search that came back with nothing is reported — the leg that puts the
     * "aucun client" message on the screen.
     */
    @Test
    void reportsAnEmptySearch() {
        InvoiceState state = new InvoiceState();
        state.searched = true;
        assertTrue(state.isSearchWithoutMatch());
    }

    /**
     * A search that found somebody is not an empty search, the second leg of the
     * compound guard.
     */
    @Test
    void doesNotReportASearchThatFoundSomebody() {
        InvoiceState state = new InvoiceState();
        state.searched = true;
        state.customers = customers(1);
        assertFalse(state.isSearchWithoutMatch());
    }

    /**
     * The visible list is a view of the same tickets, not a copy of other ones.
     */
    @Test
    void showsTheTicketsItWasGiven() {
        InvoiceState state = new InvoiceState();
        List<Ticket> given = tickets(3);
        state.tickets = given;
        assertSame(given.get(1), state.getVisibleTickets().get(1));
    }
}
