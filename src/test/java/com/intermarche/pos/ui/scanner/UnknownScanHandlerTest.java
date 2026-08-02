package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UnknownScanHandler}.
 * <p>
 * The handler is the {@code @Priority(100)} tail of the scan chain — the only
 * link allowed to answer "I don't know". It has two guards and one effect: it
 * bails when the context is already {@code handled}, it bails silently when the
 * register is locked (so no "CODE INCONNU" leaks onto the lock screen), and
 * otherwise it stamps {@code "CODE INCONNU: " + code} onto the ticket's
 * {@code transientError} and consumes the context. Both collaborators are
 * Mockito mocks: the {@link PosState} whose {@code isLocked()} decides the
 * second guard and whose public {@code ticket} sub-state is a
 * {@link TicketState} mock carrying the {@code transientError} field. Two
 * decision points, four branches, are exercised by three isolated cases.
 */
class UnknownScanHandlerTest {

    /** An unrecognized code reaching the tail of the chain. */
    private static final String CODE = "ABC999";

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock and whose lock flag is set as requested.
     *
     * @param ticket the ticket mailbox mock
     * @param locked the value {@code isLocked()} must report
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket, boolean locked) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        when(state.isLocked()).thenReturn(locked);
        return state;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * consulting the lock flag or touching the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        ScanContext ctx = new ScanContext(CODE, state);
        ctx.handled = true;
        new UnknownScanHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertNull(ticket.transientError);
        verifyNoInteractions(ticket);
        verify(state, org.mockito.Mockito.never()).isLocked();
    }

    /**
     * A locked register swallows the unknown code: the handler returns without
     * setting an error and leaves the context unhandled for no further link.
     */
    @Test
    void lockedRegisterStaysSilent() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, true);
        ScanContext ctx = new ScanContext(CODE, state);
        new UnknownScanHandler().handle(ctx);
        assertFalse(ctx.handled);
        assertNull(ticket.transientError);
    }

    /**
     * An unlocked register with an unrecognized code stamps the "CODE INCONNU"
     * error onto the ticket and consumes the context.
     */
    @Test
    void unlockedRegisterStampsUnknownError() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket, false);
        ScanContext ctx = new ScanContext(CODE, state);
        new UnknownScanHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("CODE INCONNU: " + CODE, ticket.transientError);
    }
}
