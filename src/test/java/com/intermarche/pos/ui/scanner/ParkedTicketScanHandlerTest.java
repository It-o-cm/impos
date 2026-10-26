package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketParkingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ParkedTicketScanHandler}.
 * <p>
 * The handler is a plain recognizer: it filters on the already-handled flag,
 * the lock state and the {@code <terminal>-<8 digits>} pattern, then delegates
 * to a mocked {@link TicketParkingService}. The register state is a real
 * {@link PosState} (its {@code touch} and {@code ticket.setError} run for
 * real); the parking service is a Mockito mock. No database and no Quarkus
 * context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): the already-handled guard
 * (true / false), the lock guard (true / false), the null-code arm, the
 * pattern non-match arm (no dash, wrong digit count), the pattern match arm,
 * and the resume outcome (error relayed / null-success). 5 two-way decision
 * points — every arm exercised.
 */
class ParkedTicketScanHandlerTest {

    /**
     * Builds a handler over a mocked parking service.
     *
     * @param parking the parking service mock
     * @return the wired handler
     */
    private ParkedTicketScanHandler newHandler(TicketParkingService parking) {
        ParkedTicketScanHandler handler = new ParkedTicketScanHandler();
        handler.ticketParkingService = parking;
        // EXP-37: the handler now asks the return service whether the number is a
        // CLOSED sale ticket before resuming a parked one. Default false keeps the
        // pre-existing tests on the parked path; the return tests override it.
        handler.refundService = mock(com.intermarche.pos.ui.returnprocess.RefundService.class);
        when(handler.refundService.isReturnableTicket(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        return handler;
    }

    /**
     * A matching code that names a CLOSED sale ticket routes to the return
     * (EXP-37): the return search is populated and the one-shot navigation flag
     * is raised, without touching the parked-resume path.
     */
    @Test
    void routesClosedTicketToReturn() {
        TicketParkingService parking = mock(TicketParkingService.class);
        ParkedTicketScanHandler handler = newHandler(parking);
        when(handler.refundService.isReturnableTicket("C04-00000042")).thenReturn(true);
        PosState state = unlocked();
        ScanContext ctx = new ScanContext("C04-00000042", state);
        handler.handle(ctx);
        assertEquals("C04-00000042", state.refund.searchPattern);
        assertTrue(state.returnByScanRequested);
        assertTrue(ctx.handled);
        verify(handler.refundService).searchTickets(state);
        verify(parking, never()).resumeByNumber(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * Builds an unlocked register state.
     *
     * @return the unlocked state
     */
    private PosState unlocked() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        return state;
    }

    /**
     * An already-handled context is left untouched (already-handled arm true):
     * the parking service is never consulted.
     */
    @Test
    void ignoresAlreadyHandled() {
        TicketParkingService parking = mock(TicketParkingService.class);
        ScanContext ctx = new ScanContext("C04-00000001", unlocked());
        ctx.handled = true;
        newHandler(parking).handle(ctx);
        verifyNoInteractions(parking);
    }

    /**
     * A locked register short-circuits (lock arm true): the parking service is
     * never consulted and the context stays unhandled.
     */
    @Test
    void ignoresWhenLocked() {
        TicketParkingService parking = mock(TicketParkingService.class);
        PosState state = new PosState();
        state.auth.isLocked = true;
        ScanContext ctx = new ScanContext("C04-00000001", state);
        newHandler(parking).handle(ctx);
        verifyNoInteractions(parking);
        assertFalse(ctx.handled);
    }

    /**
     * A null code short-circuits (null arm): the parking service is never
     * consulted.
     */
    @Test
    void ignoresNullCode() {
        TicketParkingService parking = mock(TicketParkingService.class);
        ScanContext ctx = new ScanContext(null, unlocked());
        newHandler(parking).handle(ctx);
        verifyNoInteractions(parking);
        assertFalse(ctx.handled);
    }

    /**
     * A code without the parked-number shape is ignored (pattern non-match
     * arm): neither a plain EAN nor a wrong digit count is resumed.
     */
    @Test
    void ignoresNonMatchingPattern() {
        TicketParkingService parking = mock(TicketParkingService.class);
        ScanContext plain = new ScanContext("3560070123456", unlocked());
        newHandler(parking).handle(plain);
        ScanContext wrongDigits = new ScanContext("C04-123", unlocked());
        newHandler(parking).handle(wrongDigits);
        verify(parking, never()).resumeByNumber(org.mockito.ArgumentMatchers.anyString());
        assertFalse(plain.handled);
        assertFalse(wrongDigits.handled);
    }

    /**
     * A matching code whose resume fails relays the error onto the ticket
     * (match arm, error-relayed arm) and consumes the scan.
     */
    @Test
    void relaysResumeError() {
        TicketParkingService parking = mock(TicketParkingService.class);
        when(parking.resumeByNumber("C04-00000009")).thenReturn("TICKET INTROUVABLE");
        PosState state = unlocked();
        long before = state.version;
        ScanContext ctx = new ScanContext("C04-00000009", state);
        newHandler(parking).handle(ctx);
        verify(parking).resumeByNumber("C04-00000009");
        assertEquals("TICKET INTROUVABLE", state.ticket.transientError);
        assertTrue(ctx.handled);
        assertTrue(state.version > before);
    }

    /**
     * A matching code whose resume succeeds sets no error (match arm,
     * null-success arm) and consumes the scan.
     */
    @Test
    void resumesSuccessfully() {
        TicketParkingService parking = mock(TicketParkingService.class);
        when(parking.resumeByNumber("C04-00000009")).thenReturn(null);
        PosState state = unlocked();
        long before = state.version;
        ScanContext ctx = new ScanContext("C04-00000009", state);
        newHandler(parking).handle(ctx);
        verify(parking).resumeByNumber("C04-00000009");
        assertNull(state.ticket.transientError);
        assertTrue(ctx.handled);
        assertTrue(state.version > before);
    }

    /**
     * BO-03-13-04: the barcode of a receipt carries the administered prefix
     * before the ticket number; it is stripped, and the ONE ticket found is
     * selected so the return opens on its detail. A code without the prefix,
     * a blank prefix and no prefix read the code as it is; several results
     * select nothing.
     */
    @Test
    void theReceiptBarcodeSelectsItsTicketForTheReturn() {
        TicketParkingService parking = mock(TicketParkingService.class);
        ParkedTicketScanHandler handler = newHandler(parking);
        handler.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(handler.posSettingsService.ticketBarcodePrefix()).thenReturn(" TK ");
        when(handler.refundService.isReturnableTicket("C04-00000042")).thenReturn(true);
        com.intermarche.pos.domain.sale.Ticket found = new com.intermarche.pos.domain.sale.Ticket();
        found.id = 42L;
        PosState state = unlocked();
        org.mockito.Mockito.doAnswer(call -> {
            state.refund.foundTickets = java.util.List.of(found);
            return null;
        }).when(handler.refundService).searchTickets(state);
        ScanContext ctx = new ScanContext("TKC04-00000042", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(handler.refundService).selectTicket(state, 42L);
        ScanContext plain = new ScanContext("C04-00000042", state);
        handler.handle(plain);
        assertTrue(plain.handled);
        when(handler.posSettingsService.ticketBarcodePrefix()).thenReturn(" ");
        org.mockito.Mockito.doAnswer(call -> {
            state.refund.foundTickets = java.util.List.of(found, found);
            return null;
        }).when(handler.refundService).searchTickets(state);
        ScanContext several = new ScanContext("C04-00000042", state);
        handler.handle(several);
        when(handler.posSettingsService.ticketBarcodePrefix()).thenReturn(null);
        handler.handle(new ScanContext("C04-00000042", state));
        ScanContext nothing = new ScanContext(null, state);
        handler.handle(nothing);
        assertFalse(nothing.handled);
        verify(handler.refundService, org.mockito.Mockito.times(2)).selectTicket(state, 42L);
    }
}
