package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefundService}.
 * <p>
 * The service is a plain CDI bean orchestrating the refund flow over the
 * in-memory {@link PosState}/{@link RefundState} (used as real value objects so
 * their mutations and computations are exercised for real) and seven mocked
 * collaborators. Under plain {@code mvn test} the Panache static finders resolve
 * to {@link PanacheEntityBase}: {@code Ticket.find}/{@code Ticket.findById},
 * {@code RefundLine.list} (double-refund guard) and {@code Refund.list} (ticket
 * cap) are intercepted with {@link org.mockito.Mockito#mockStatic}. The refund
 * created inside {@link RefundService#performRefund} is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} so {@code persist()} is a no-op
 * and its {@code lines}/{@code id} fields are seeded; the constructed instance
 * is then asserted field by field. Every branch of every method is covered with
 * absolute expected values.
 */
class RefundServiceTest {

    /** The exact JPQL fragment issued by the ticket search. */
    private static final String SEARCH_QUERY =
            "status = ?1 and lower(ticketNumber) like lower(?2) and creationDate > ?3";

    /**
     * Builds a {@link RefundService} whose seven collaborators are fresh mocks
     * wired onto its package-private injection fields.
     *
     * @return a service with fully mocked collaborators
     */
    private RefundService newService() {
        RefundService s = new RefundService();
        s.endorsementService = mock(EndorsementService.class);
        s.ticketPrinterService = mock(TicketPrinterService.class);
        s.cashSessionService = mock(CashSessionService.class);
        s.ticketNumberService = mock(TicketNumberService.class);
        s.technicalEventService = mock(TechnicalEventService.class);
        s.hardwareService = mock(HardwareService.class);
        s.syncOutboxService = mock(SyncOutboxService.class);
        return s;
    }

    /**
     * Builds a real ticket line with the given identity and financials.
     *
     * @param id the line database id
     * @param qty the sold quantity
     * @param unitPrice the tax-included unit price
     * @param vat the VAT rate
     * @param label the product label
     * @return the ticket line
     */
    private TicketLine line(long id, String qty, String unitPrice, String vat, String label) {
        TicketLine l = new TicketLine();
        l.id = id;
        l.quantity = new BigDecimal(qty);
        l.unitPrice = new BigDecimal(unitPrice);
        l.vatRate = new BigDecimal(vat);
        l.productLabel = label;
        return l;
    }

    /**
     * Builds a real ticket carrying the given lines.
     *
     * @param id the ticket database id
     * @param number the ticket number
     * @param totalIncl the tax-included ticket total
     * @param lines the ticket lines
     * @return the ticket
     */
    private Ticket ticket(long id, String number, String totalIncl, TicketLine... lines) {
        Ticket t = new Ticket();
        t.id = id;
        t.ticketNumber = number;
        t.totalIncludingTax = new BigDecimal(totalIncl);
        t.lines = new ArrayList<>(List.of(lines));
        return t;
    }

    /**
     * Builds a real refund line carrying only a quantity (double-refund stub).
     *
     * @param qty the already-refunded quantity
     * @return the refund line
     */
    private RefundLine refundLineQty(String qty) {
        RefundLine rl = new RefundLine();
        rl.quantity = new BigDecimal(qty);
        return rl;
    }

    // --- searchTickets ---

    /**
     * {@code searchTickets} clears the results and returns without querying when
     * the pattern is null (first arm of the guard's OR).
     */
    @Test
    void searchTicketsClearsOnNullPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = null;
        state.refund.foundTickets = new ArrayList<>(List.of(mock(Ticket.class)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            s.searchTickets(state);
            panache.verifyNoInteractions();
        }
        assertTrue(state.refund.foundTickets.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * {@code searchTickets} clears the results and returns without querying when
     * the trimmed pattern is shorter than three characters (second arm of the
     * OR: non-null pattern, length below the minimum).
     */
    @Test
    void searchTicketsClearsOnShortPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = "  ab  ";
        state.refund.foundTickets = new ArrayList<>(List.of(mock(Ticket.class)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            s.searchTickets(state);
            panache.verifyNoInteractions();
        }
        assertTrue(state.refund.foundTickets.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * {@code searchTickets} runs the closed-ticket query and stores the results
     * when the pattern reaches the minimum length (both arms of the OR false).
     */
    @Test
    void searchTicketsStoresResultsOnValidPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = "abc";
        List<Ticket> results = List.of(mock(Ticket.class), mock(Ticket.class));
        @SuppressWarnings("unchecked")
        PanacheQuery<Ticket> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(results);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(eq(SEARCH_QUERY), eq(Ticket.TicketStatus.CLOSED),
                    eq("%abc%"), any(LocalDateTime.class))).thenReturn(query);
            s.searchTickets(state);
        }
        assertSame(results, state.refund.foundTickets);
        assertEquals(1L, state.version);
    }

    // --- selectTicket ---

    /**
     * {@code selectTicket} selects the found ticket and resets the refund
     * selection (non-null arm of the lookup guard).
     */
    @Test
    void selectTicketSelectsAndResets() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.returnQuantities.put(9L, BigDecimal.ONE);
        state.refund.detailPage = 3;
        state.refund.selectedLineId = 9L;
        state.refund.isEditingAmount = true;
        state.refund.manualTotalAmount = BigDecimal.TEN;
        Ticket found = ticket(10L, "T-1", "50.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(0, state.refund.detailPage);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertNull(state.refund.manualTotalAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectTicket} leaves the state untouched but still refreshes the UI
     * when the ticket is not found (null arm of the lookup guard).
     */
    @Test
    void selectTicketDoesNothingWhenNotFound() {
        RefundService s = newService();
        PosState state = new PosState();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(99L)).thenReturn(null);
            s.selectTicket(state, 99L);
        }
        assertNull(state.refund.selectedTicket);
        assertEquals(1L, state.version);
    }

    // --- selectLine ---

    /**
     * {@code selectLine} deselects the line when the given id is already the
     * selected one (both arms of the AND true).
     */
    @Test
    void selectLineTogglesOffWhenAlreadySelected() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        state.refund.isEditingAmount = true;
        s.selectLine(state, 5L);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectLine} selects the line when a different id is given (first
     * arm true, second arm false of the AND).
     */
    @Test
    void selectLineSelectsWhenDifferent() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.selectLine(state, 6L);
        assertEquals(6L, state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectLine} sets a null selection when the id is null (first arm of
     * the AND false — short-circuit).
     */
    @Test
    void selectLineSetsNullWhenIdNull() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.selectLine(state, null);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- startAmountEdit ---

    /**
     * {@code startAmountEdit} switches to global-amount edition and clears the
     * line selection.
     */
    @Test
    void startAmountEditSwitchesToAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.startAmountEdit(state);
        assertNull(state.refund.selectedLineId);
        assertTrue(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- submitLineQuantity ---

    /**
     * {@code submitLineQuantity} parses the comma-decimal value and applies it
     * as a refund quantity (try arm), then clears the line selection.
     */
    @Test
    void submitLineQuantityAppliesParsedValue() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.selectedLineId = 1L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.submitLineQuantity(state, 1L, "2,5");
        }
        assertEquals(new BigDecimal("2.5"), state.refund.returnQuantities.get(1L));
        assertNull(state.refund.selectedLineId);
    }

    /**
     * {@code submitLineQuantity} swallows a parse failure (catch arm) and only
     * clears the line selection.
     */
    @Test
    void submitLineQuantityIgnoresInvalidValue() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 1L;
        s.submitLineQuantity(state, 1L, "not-a-number");
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertNull(state.refund.selectedLineId);
        assertEquals(1L, state.version);
    }

    // --- submitManualAmount ---

    /**
     * {@code submitManualAmount} parses the comma-decimal amount (try arm) and
     * leaves amount edition.
     */
    @Test
    void submitManualAmountStoresParsedAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.isEditingAmount = true;
        s.submitManualAmount(state, "12,50");
        assertEquals(new BigDecimal("12.50"), state.refund.manualTotalAmount);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code submitManualAmount} falls back to zero on a parse failure (catch
     * arm) and leaves amount edition.
     */
    @Test
    void submitManualAmountFallsBackToZero() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.isEditingAmount = true;
        s.submitManualAmount(state, "xyz");
        assertEquals(BigDecimal.ZERO, state.refund.manualTotalAmount);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- setReturnQuantity ---

    /**
     * {@code setReturnQuantity} returns immediately when no ticket is selected
     * (null-ticket guard true arm).
     */
    @Test
    void setReturnQuantityReturnsWithoutTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(0L, state.version);
    }

    /**
     * {@code setReturnQuantity} stores nothing when the line id matches no line
     * (line-not-found arm).
     */
    @Test
    void setReturnQuantityIgnoresUnknownLine() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 99L)).thenReturn(List.of());
            s.setReturnQuantity(state, 99L, BigDecimal.ONE);
        }
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * {@code setReturnQuantity} stores a positive quantity within the refundable
     * cap unchanged (refundable non-negative, quantity non-negative, quantity
     * not above the cap — all three false arms).
     */
    @Test
    void setReturnQuantityStoresWithinCap() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.manualTotalAmount = BigDecimal.TEN;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("3"));
        }
        assertEquals(new BigDecimal("3"), state.refund.returnQuantities.get(1L));
        assertNull(state.refund.manualTotalAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code setReturnQuantity} caps a quantity above the refundable amount
     * (quantity-above-cap true arm).
     */
    @Test
    void setReturnQuantityCapsAboveRefundable() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("10"));
        }
        assertEquals(new BigDecimal("5"), state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code setReturnQuantity} clamps a negative quantity to zero
     * (negative-quantity true arm).
     */
    @Test
    void setReturnQuantityClampsNegativeToZero() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("-2"));
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code setReturnQuantity} floors an over-refunded line's refundable amount
     * to zero (refundable-negative true arm): with more already refunded than
     * sold, any positive request is capped to zero.
     */
    @Test
    void setReturnQuantityFloorsNegativeRefundable() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "2", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(refundLineQty("3")));
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    // --- incrementQty / decrementQty ---

    /**
     * {@code incrementQty} adds one unit to the current quantity of a line.
     */
    @Test
    void incrementQtyAddsOne() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.incrementQty(state, 1L);
        }
        assertEquals(new BigDecimal("3"), state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code decrementQty} removes one unit from the current quantity of a line,
     * clamped to zero.
     */
    @Test
    void decrementQtyRemovesOne() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.decrementQty(state, 1L);
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    // --- requestRefund ---

    /**
     * {@code requestRefund} refuses the refund in training mode (training guard
     * true arm) without contacting the endorsement service.
     */
    @Test
    void requestRefundBlockedInTraining() {
        RefundService s = newService();
        PosState state = new PosState();
        state.trainingMode = true;
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertEquals("RETOURS INDISPONIBLES EN FORMATION", state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(1L, state.version);
    }

    /**
     * {@code requestRefund} returns silently when no ticket is selected
     * (training guard false arm, ticket guard true arm).
     */
    @Test
    void requestRefundReturnsWithoutTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertNull(state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(0L, state.version);
    }

    /**
     * {@code requestRefund} refuses an empty refund (amount guard true arm)
     * without contacting the endorsement service.
     */
    @Test
    void requestRefundRefusesEmptyAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertEquals("RIEN À REMBOURSER", state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(1L, state.version);
    }

    /**
     * {@code requestRefund} requests a manager endorsement carrying the method
     * and ticket id when the refund is non-empty (all guards false arm).
     */
    @Test
    void requestRefundRequestsEndorsement() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertNull(state.refund.errorMessage);
        verify(s.endorsementService).requestAuthorization(state, "REFUND_CASH_10");
        assertEquals(1L, state.version);
    }

    // --- performRefund ---

    /**
     * {@code performRefund} returns immediately when no ticket is selected
     * (null-original guard true arm): nothing is persisted or printed.
     */
    @Test
    void performRefundReturnsWithoutOriginal() {
        RefundService s = newService();
        PosState state = new PosState();
        try (MockedConstruction<Refund> mc = mockConstruction(Refund.class)) {
            s.performRefund(state, Refund.RefundMethod.CASH);
            assertTrue(mc.constructed().isEmpty());
        }
        verifyNoInteractions(s.ticketNumberService, s.cashSessionService, s.technicalEventService,
                s.syncOutboxService, s.ticketPrinterService, s.hardwareService);
    }

    /**
     * {@code performRefund} for a cash refund persists the document with its
     * method, VAT restitution and session, enqueues the sync, opens the drawer
     * (CASH switch arm), prints the refund and clears the state. Exercises the
     * positive-quantity, line-found and within-cap arms, a non-empty
     * already-refunded history, and the HT/VAT branch (lines present, no manual
     * amount).
     */
    @Test
    void performRefundCashPersistsAndOpensDrawer() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(refundLineQty("1")));
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("R-1", refund.refundNumber);
            assertEquals(10L, refund.originalTicketId);
            assertEquals(Refund.RefundMethod.CASH, refund.refundMethod);
            assertEquals("C04", refund.terminalId);
            assertSame(session, refund.session);
            assertEquals(Refund.RefundStatus.CLOSED, refund.status);
            assertNotNull(refund.creationDate);
            assertEquals(new BigDecimal("20.00"), refund.totalAmount);
            assertEquals(new BigDecimal("16.67"), refund.totalExcludingTax);
            assertEquals(new BigDecimal("3.33"), refund.totalVat);
            assertEquals(1, refund.lines.size());
            RefundLine rl = refund.lines.get(0);
            assertEquals(1L, rl.originalLineId);
            assertEquals("MILK", rl.productLabel);
            assertEquals(new BigDecimal("2"), rl.quantity);
            assertEquals(new BigDecimal("10.00"), rl.price);
            assertEquals(new BigDecimal("0.20"), rl.vatRate);
            verify(refund).persist();
        }
        verify(s.technicalEventService).log(TechnicalEvent.EventType.REFUND_CREATED, "T-1 CASH 20.00");
        verify(s.syncOutboxService).enqueue(SyncOutbox.EntityType.REFUND, 55L);
        verify(s.hardwareService).openDrawer();
        verify(s.ticketPrinterService).printRefund(55L);
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), eq(true));
        assertNull(state.refund.selectedTicket);
    }

    /**
     * {@code performRefund} for a voucher refund whose amount fits the encodable
     * limit prints a scannable store voucher (VOUCHER switch arm, encodable true
     * arm).
     */
    @Test
    void performRefundVoucherEncodable() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.VOUCHER);
            Refund refund = mc.constructed().get(0);
            verify(s.ticketPrinterService).printRefundVoucher(refund, true);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a voucher refund driven by a manual amount above
     * the encodable limit prints a non-scannable voucher (encodable false arm)
     * and skips the VAT restitution (HT/VAT branch: lines present but a manual
     * amount is set).
     */
    @Test
    void performRefundVoucherNotEncodableWithManualAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "500.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.manualTotalAmount = new BigDecimal("150.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.VOUCHER);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("150.00"), refund.totalAmount);
            assertNull(refund.totalExcludingTax);
            assertNull(refund.totalVat);
            verify(s.ticketPrinterService).printRefundVoucher(refund, false);
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a loyalty refund only journals the credit
     * (LOYALTY switch arm): no drawer, no voucher.
     */
    @Test
    void performRefundLoyaltyJournalsOnly() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.LOYALTY);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), any(Boolean.class));
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a card refund only logs the terminal gesture
     * (CARD switch arm): no drawer, no voucher.
     */
    @Test
    void performRefundCardLogsOnly() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), any(Boolean.class));
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} skips a zero-quantity entry (positive-quantity guard
     * false arm) and, with no line kept, skips the VAT restitution (lines-empty
     * arm) while still persisting the empty refund.
     */
    @Test
    void performRefundSkipsZeroQuantityLine() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ZERO);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertTrue(refund.lines.isEmpty());
            assertEquals(BigDecimal.ZERO, refund.totalAmount);
            assertNull(refund.totalExcludingTax);
            assertNull(refund.totalVat);
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} skips a quantity keyed on a line absent from the
     * original ticket (line-not-found guard true arm) and keeps no refund line.
     */
    @Test
    void performRefundSkipsUnknownLine() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(99L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 99L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertTrue(refund.lines.isEmpty());
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} re-validates the double-refund cap inside the
     * transaction: a quantity exceeding the still-refundable amount surfaces the
     * refusal message and rolls back (per-line cap true arm), persisting and
     * printing nothing.
     */
    @Test
    void performRefundThrowsOnDoubleRefund() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "2", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("5"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            assertThrows(IllegalStateException.class,
                    () -> s.performRefund(state, Refund.RefundMethod.CASH));
            verify(mc.constructed().get(0), never()).persist();
        }
        assertEquals("QUANTITÉ DÉJÀ REMBOURSÉE (MILK)", state.refund.errorMessage);
        verifyNoInteractions(s.technicalEventService, s.syncOutboxService,
                s.ticketPrinterService, s.hardwareService);
    }

    /**
     * {@code performRefund} re-validates the ticket-level cap inside the
     * transaction: when past refunds plus the new one exceed the ticket total,
     * it surfaces the plafond message and rolls back (ticket-cap true arm),
     * persisting and printing nothing. Exercises a non-empty refund history.
     */
    @Test
    void performRefundThrowsOnTicketCapExceeded() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "5.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        Refund prior = mock(Refund.class);
        prior.totalAmount = new BigDecimal("3.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of(prior));
            assertThrows(IllegalStateException.class,
                    () -> s.performRefund(state, Refund.RefundMethod.CASH));
            verify(mc.constructed().get(0), never()).persist();
        }
        assertEquals("PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 3.00 €)", state.refund.errorMessage);
        verifyNoInteractions(s.technicalEventService, s.syncOutboxService,
                s.ticketPrinterService, s.hardwareService);
    }
}
