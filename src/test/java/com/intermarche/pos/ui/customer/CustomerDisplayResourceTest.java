package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerDisplayResource}, the read-only customer-facing
 * second screen. Both endpoints are thin projections of {@link PosState}:
 * {@code customerPage()} renders a template and {@code customerData()} builds a
 * version-gated JSON snapshot delegating to the private {@code digitalPath()}
 * and {@code quantityDisplay()} helpers. All collaborators are mocked; the
 * {@code Ticket.findById} static finder resolves to {@link PanacheEntityBase}
 * under plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. Tests cover the version gate (both
 * arms and the null client version), the change-amount ternary, the empty and
 * populated item loop, the three digital-path guards and the three quantity
 * display formats (20 branches).
 */
class CustomerDisplayResourceTest {

    /**
     * Builds a {@link CustomerDisplayResource} whose collaborators are fresh
     * mocks wired onto its package-private fields, including mocked
     * {@link PosState#ticket} and {@link PosState#payment} sub-states with an
     * empty item list so no direct field access hits a null.
     *
     * @return a resource with fully mocked state and template
     */
    private CustomerDisplayResource newResource() {
        CustomerDisplayResource resource = new CustomerDisplayResource();
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.state.ticket.items = new ArrayList<>();
        resource.state.payment = mock(PaymentState.class);
        return resource;
    }

    /**
     * Builds a mocked ticket item carrying the given quantity fields and a
     * stubbed formatted price.
     *
     * @param plu the PLU code, or null
     * @param quantity the quantity
     * @param label the display label
     * @param priceFormatted the value returned by {@code getPriceFormatted()}
     * @return the mocked ticket item
     */
    private TicketState.TicketItem item(String plu, BigDecimal quantity, String label, String priceFormatted) {
        TicketState.TicketItem it = mock(TicketState.TicketItem.class);
        it.plu = plu;
        it.quantity = quantity;
        it.label = label;
        when(it.getPriceFormatted()).thenReturn(priceFormatted);
        return it;
    }

    // --- customerPage ---

    /**
     * {@code customerPage()} renders the customer template bound to the state.
     */
    @Test
    void customerPageRendersCustomerTemplate() {
        CustomerDisplayResource resource = newResource();
        resource.customer = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.customer.data("state", resource.state)).thenReturn(view);
        assertSame(view, resource.customerPage());
    }

    // --- customerData: version gate ---

    /**
     * {@code customerData()} reports no change when a non-null client version
     * matches the current state version (guard true / true).
     */
    @Test
    void customerDataUnchangedWhenVersionMatches() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 7L;
        Map<String, Object> result = resource.customerData(7L);
        assertEquals(1, result.size());
        assertEquals(false, result.get("changed"));
        verifyNoInteractions(resource.state.ticket);
    }

    /**
     * {@code customerData()} builds a full snapshot when a non-null client
     * version differs from the current one (guard true / false), with no
     * change amount (ternary false), an empty ticket (loop not entered) and a
     * null draft id (digital path short-circuit).
     */
    @Test
    void customerDataChangedWhenVersionDiffers() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 2L;
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.paymentInProgress = false;
        resource.state.payment.transactionComplete = false;
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = null;
        Map<String, Object> result = resource.customerData(1L);
        assertEquals(12, result.size());
        assertEquals(true, result.get("changed"));
        assertEquals(2L, result.get("version"));
        assertEquals(false, result.get("locked"));
        assertEquals(false, result.get("training"));
        assertEquals(true, result.get("empty"));
        assertEquals("0,00", result.get("total"));
        assertEquals("0,00", result.get("remaining"));
        assertEquals(false, result.get("paying"));
        assertEquals(false, result.get("complete"));
        assertEquals("", result.get("change"));
        assertEquals("", result.get("digitalPath"));
        List<?> items = (List<?>) result.get("items");
        assertTrue(items.isEmpty());
    }

    /**
     * {@code customerData()} builds a full snapshot when the client version is
     * null (guard short-circuit false), carrying a change amount (ternary
     * true), a populated ticket (loop entered) covering the three quantity
     * formats, and a resolvable digital path (all three guards false).
     */
    @Test
    void customerDataFullSnapshotWithItemsAndChangeAndDigitalPath() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 9L;
        resource.state.trainingMode = true;
        when(resource.state.isLocked()).thenReturn(true);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,34");
        resource.state.payment.paymentInProgress = true;
        resource.state.payment.transactionComplete = true;
        resource.state.payment.lastChangeAmount = new BigDecimal("2.5");
        resource.state.payment.ticketDbId = 42L;
        resource.state.ticket.items.add(item("100", new BigDecimal("1.5"), "Bananas", "3,00"));
        resource.state.ticket.items.add(item(null, new BigDecimal("2"), "Milk", "2,00"));
        resource.state.ticket.items.add(item("", new BigDecimal("1.25"), "Nails", "4,00"));
        Ticket ticket = mock(Ticket.class);
        ticket.id = 42L;
        ticket.digitalKey = "ABCDEF0123456789";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(42L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals(12, result.size());
            assertEquals(true, result.get("changed"));
            assertEquals(9L, result.get("version"));
            assertEquals(true, result.get("locked"));
            assertEquals(true, result.get("training"));
            assertEquals(false, result.get("empty"));
            assertEquals("12,34", result.get("total"));
            assertEquals(true, result.get("paying"));
            assertEquals(true, result.get("complete"));
            assertEquals("2,50", result.get("change"));
            assertEquals("/t/42/ABCDEF0123456789", result.get("digitalPath"));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> items = (List<Map<String, String>>) result.get("items");
            assertEquals(3, items.size());
            assertEquals("Bananas", items.get(0).get("label"));
            assertEquals("1,500 kg", items.get(0).get("qty"));
            assertEquals("3,00", items.get(0).get("amount"));
            assertEquals("Milk", items.get(1).get("label"));
            assertEquals("x2", items.get(1).get("qty"));
            assertEquals("2,00", items.get(1).get("amount"));
            assertEquals("Nails", items.get(2).get("label"));
            assertEquals("x1,25", items.get(2).get("qty"));
            assertEquals("4,00", items.get(2).get("amount"));
        }
    }

    // --- digitalPath guards ---

    /**
     * {@code digitalPath()} yields an empty string when the draft id resolves
     * to no ticket (first arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenTicketNotFound() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 5L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
        }
    }

    /**
     * {@code digitalPath()} yields an empty string when the found ticket has no
     * digital key (second arm of the null guard true).
     */
    @Test
    void customerDataDigitalPathEmptyWhenDigitalKeyNull() {
        CustomerDisplayResource resource = newResource();
        resource.state.version = 1L;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.getRemainingFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        resource.state.payment.lastChangeAmount = null;
        resource.state.payment.ticketDbId = 6L;
        Ticket ticket = mock(Ticket.class);
        ticket.id = 6L;
        ticket.digitalKey = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(6L)).thenReturn(ticket);
            Map<String, Object> result = resource.customerData(null);
            assertEquals("", result.get("digitalPath"));
            assertFalse(result.containsKey("nonexistent"));
        }
    }
}
