package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ActivationCodeScanHandler} (BO-03-06-44).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database. The ticket is a REAL
 * {@link TicketState}, because the behaviour under test is precisely what
 * {@code storePendingActivation} does to it; only the register state around it
 * is a mock. The operator messages come from the French bundle, so every
 * assertion keeps the literal sentence the register shows (LC-13-06).
 *
 * <p>Branch enumeration: the already-handled short circuit; a null state and a
 * null ticket; no pending activation; a pending activation answered by a null
 * code and by a blank one, neither of which spends the wait; the ordinary
 * answer with a provider named and without one; and an answer whose line has
 * disappeared.
 */
class ActivationCodeScanHandlerTest {

    /**
     * Builds a handler wired with the French bundle.
     *
     * @return a ready-to-test handler
     */
    private ActivationCodeScanHandler newHandler() {
        ActivationCodeScanHandler handler = new ActivationCodeScanHandler();
        handler.i18n = TestMessages.french();
        handler.dematerialisedService = mock(com.intermarche.pos.ui.ticket.DematerialisedService.class);
        handler.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        return handler;
    }

    /**
     * LC-02-09-01: with a payment hub, the code is sent for activation and the
     * line, accepted, stays; nothing is cancelled and the plain acknowledgement
     * gives way to the hub's.
     */
    @Test
    void anAcceptedActivationKeepsTheLine() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        PosState state = newState(ticket);
        ActivationCodeScanHandler handler = newHandler();
        when(handler.dematerialisedService.thirdPartyActivation(state, "ACT-123")).thenReturn(true);
        when(handler.dematerialisedService.activate(state, line)).thenReturn(null);
        ScanContext ctx = new ScanContext("ACT-123", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("ACT-123", line.activationCode);
        verify(handler.ticketService, never()).cancelItemById(any(), any());
        assertNull(ticket.transientError);
    }

    /**
     * LC-02-09-01: a refused activation cancels the line and shows the hub's
     * refusal.
     */
    @Test
    void aRefusedActivationCancelsTheLine() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        PosState state = newState(ticket);
        ActivationCodeScanHandler handler = newHandler();
        when(handler.dematerialisedService.thirdPartyActivation(state, "ACT-123")).thenReturn(true);
        when(handler.dematerialisedService.activate(state, line)).thenReturn("REFUS");
        ScanContext ctx = new ScanContext("ACT-123", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(handler.ticketService).cancelItemById(state, line.uid);
        assertEquals("REFUS", ticket.transientError);
        assertFalse(ticket.transientOk);
    }

    /**
     * Assembles a mock register state over a real ticket.
     *
     * @param ticket the real ticket
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        return state;
    }

    /**
     * Adds one line to the ticket and returns it.
     *
     * @param ticket the ticket
     * @return the added line
     */
    private TicketState.TicketItem addLine(TicketState ticket) {
        TicketState.TicketItem item = new TicketState.TicketItem("3560070000000", null,
                "CARTE CADEAU", new BigDecimal("50.00"), BigDecimal.ONE,
                new BigDecimal("0.20"));
        ticket.items.add(item);
        return item;
    }

    /**
     * An already-handled context short-circuits, and the wait stays open.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        ScanContext ctx = new ScanContext("ACT-123", newState(ticket));
        ctx.handled = true;
        newHandler().handle(ctx);
        assertEquals(line.uid, ticket.pendingActivationUid);
        assertNull(line.activationCode);
    }

    /**
     * A context carrying no state, or a state carrying no ticket, is not this
     * handler's business and raises nothing (both legs of the second guard).
     */
    @Test
    void aContextWithoutATicketIsNotRecognized() {
        ActivationCodeScanHandler handler = newHandler();
        ScanContext noState = new ScanContext("ACT-123", null);
        handler.handle(noState);
        assertFalse(noState.handled);
        PosState state = mock(PosState.class);
        state.ticket = null;
        ScanContext noTicket = new ScanContext("ACT-123", state);
        handler.handle(noTicket);
        assertFalse(noTicket.handled);
    }

    /**
     * With no activation awaited, the code walks on untouched — which is the
     * ordinary case of every scan of every sale.
     */
    @Test
    void withoutAPendingActivationNothingIsClaimed() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ScanContext ctx = new ScanContext("3560070000000", newState(ticket));
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        assertNull(line.activationCode);
    }

    /**
     * A null and a blank code answer nothing: the wait stays OPEN rather than
     * being spent on a code the provider never printed (both legs of the third
     * guard).
     */
    @Test
    void aNullOrBlankCodeDoesNotSpendTheWait() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        ActivationCodeScanHandler handler = newHandler();
        ScanContext nullCode = new ScanContext(null, newState(ticket));
        handler.handle(nullCode);
        ScanContext blankCode = new ScanContext("   ", newState(ticket));
        handler.handle(blankCode);
        assertFalse(nullCode.handled);
        assertFalse(blankCode.handled);
        assertEquals(line.uid, ticket.pendingActivationUid);
        assertNull(line.activationCode);
    }

    /**
     * The ordinary answer: the code is stored on the awaited line, trimmed, the
     * wait is closed, and the provider the referential named is part of the
     * acknowledgement.
     */
    @Test
    void theAwaitedCodeIsStoredOnItsLine() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        line.activationProvider = "TRANSACTIS";
        ticket.pendingActivationUid = line.uid;
        ScanContext ctx = new ScanContext("  ACT-123  ", newState(ticket));
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("ACT-123", line.activationCode);
        assertNull(ticket.pendingActivationUid);
        assertEquals("CODE D'ACTIVATION ENREGISTRE (TRANSACTIS)", ticket.transientError);
        assertTrue(ticket.transientOk);
    }

    /**
     * A line whose provider the referential did not name is acknowledged all
     * the same: the code is what matters, the provider is a convenience (the
     * other arm of the message choice).
     */
    @Test
    void aLineWithoutAProviderIsAcknowledgedPlainly() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        ScanContext ctx = new ScanContext("ACT-123", newState(ticket));
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("ACT-123", line.activationCode);
        assertEquals("CODE D'ACTIVATION ENREGISTRE", ticket.transientError);
        assertTrue(ticket.transientOk);
    }

    /**
     * The awaited line has gone (cancelled under the operator's hand): the wait
     * is closed all the same and the operator is told, rather than left with a
     * code that went nowhere and a register that swallows every scan.
     */
    @Test
    void anAnswerWhoseLineDisappearedIsRefusedAndClosesTheWait() {
        TicketState ticket = new TicketState();
        ticket.pendingActivationUid = "a-line-that-is-no-longer-there";
        ScanContext ctx = new ScanContext("ACT-123", newState(ticket));
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        assertNull(ticket.pendingActivationUid);
        assertEquals("LIGNE A ACTIVER INTROUVABLE", ticket.transientError);
        assertFalse(ticket.transientOk);
    }

    /**
     * BO-03-02-37: a type of prepaid card forbidding the activation cancels
     * the line with its refusal, and the hub is not asked.
     */
    @Test
    void aForbiddenActivationCancelsTheLine() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        PosState state = newState(ticket);
        ActivationCodeScanHandler handler = newHandler();
        when(handler.dematerialisedService.activationRefusal("ACT-123")).thenReturn("INTERDIT");
        ScanContext ctx = new ScanContext("ACT-123", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(handler.ticketService).cancelItemById(state, line.uid);
        assertEquals("INTERDIT", ticket.transientError);
        verify(handler.dematerialisedService, never()).activate(any(), any());
    }

    /**
     * BO-11-03-04: where the NEXO exchange is administered at payment, the code
     * is stored on its line and NOT sent: the article is announced as activated
     * on entering payment.
     */
    @Test
    void anActivationDeferredToPaymentIsOnlyRegistered() {
        TicketState ticket = new TicketState();
        TicketState.TicketItem line = addLine(ticket);
        ticket.pendingActivationUid = line.uid;
        PosState state = newState(ticket);
        ActivationCodeScanHandler handler = newHandler();
        when(handler.dematerialisedService.thirdPartyActivation(state, "ACT-123")).thenReturn(true);
        when(handler.dematerialisedService.activationAtPayment()).thenReturn(true);
        ScanContext ctx = new ScanContext("ACT-123", state);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        assertEquals("ACT-123", line.activationCode);
        verify(handler.dematerialisedService, never()).activate(any(), any());
        assertEquals("PRODUIT DÉMATÉRIALISÉ ENREGISTRÉ : ACTIVATION À L'ENTRÉE EN PAIEMENT", ticket.transientError);
        assertTrue(ticket.transientOk);
    }
}
