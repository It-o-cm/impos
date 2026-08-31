package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.ticket.TicketParkingService;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
        return handler;
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
}
