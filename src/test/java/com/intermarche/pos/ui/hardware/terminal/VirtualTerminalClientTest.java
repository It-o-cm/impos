package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link VirtualTerminalClient}: the pending discipline is
 * driven by the REAL {@link PosState} pending flag plus the stored callback,
 * so every decision arm (accept, refuse, nothing pending, stale pending
 * flag, double decision, abort) is pinned against a real state object.
 */
class VirtualTerminalClientTest {

    /** The client under test. */
    private VirtualTerminalClient client;

    /** The real POS state carrying the pending flag. */
    private PosState state;

    /** The mocked callback of the in-flight transaction. */
    private TerminalTransactionCallback callback;

    /**
     * Wires a fresh client on a fresh real state and a mocked callback.
     */
    @BeforeEach
    void setUp() {
        client = new VirtualTerminalClient();
        state = new PosState();
        client.state = state;
        callback = mock(TerminalTransactionCallback.class);
    }

    /**
     * Parks the debit and mirrors the register's pending flag, as
     * {@code PaymentService.processCard} does before requesting.
     *
     * @param amount the pending amount
     */
    private void park(String amount) {
        state.payment.pendingCardAmount = new BigDecimal(amount);
        client.requestDebit(new BigDecimal(amount), callback);
    }

    /**
     * {@code accept} fires the accept leg once with the parked amount
     * (pending arm).
     */
    @Test
    void acceptFiresCallbackOnce() {
        park("12.00");
        assertTrue(client.accept());
        verify(callback).onAccepted(org.mockito.ArgumentMatchers.argThat(
                o -> new BigDecimal("12.00").compareTo(o.amount) == 0));
    }

    /**
     * {@code accept} returns a 6-digit authorization number in the outcome, as
     * a real monetique would (BO-04-01-08).
     */
    @Test
    void acceptReturnsAuthorizationNumber() {
        park("12.00");
        org.mockito.ArgumentCaptor<TerminalOutcome> captor =
                org.mockito.ArgumentCaptor.forClass(TerminalOutcome.class);
        assertTrue(client.accept());
        verify(callback).onAccepted(captor.capture());
        String auth = captor.getValue().authorizationNumber;
        assertTrue(auth != null && auth.matches("\\d{6}"));
    }

    /**
     * {@code refuse} returns no authorization number: a refused transaction
     * reached no monetique acceptance (BO-04-01-08 negative case).
     */
    @Test
    void refuseReturnsNoAuthorizationNumber() {
        park("12.00");
        org.mockito.ArgumentCaptor<TerminalOutcome> captor =
                org.mockito.ArgumentCaptor.forClass(TerminalOutcome.class);
        assertTrue(client.refuse());
        verify(callback).onRefused(captor.capture());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().authorizationNumber);
    }

    /**
     * {@code refuse} fires the refuse leg once with the parked amount
     * (pending arm).
     */
    @Test
    void refuseFiresCallbackOnce() {
        park("12.00");
        assertTrue(client.refuse());
        verify(callback).onRefused(org.mockito.ArgumentMatchers.argThat(
                o -> new BigDecimal("12.00").compareTo(o.amount) == 0));
    }

    /**
     * {@code accept} without any request returns false and fires nothing
     * (no-callback arm).
     */
    @Test
    void acceptWithoutRequestReturnsFalse() {
        assertFalse(client.accept());
        verifyNoInteractions(callback);
    }

    /**
     * A decision after the register cleared the pending flag is rejected —
     * a late simulator click must not resurrect a cancelled payment (stale
     * arm of {@code hasPending}).
     */
    @Test
    void acceptAfterPendingClearedReturnsFalse() {
        park("12.00");
        state.payment.pendingCardAmount = null;
        assertFalse(client.accept());
        verifyNoInteractions(callback);
    }

    /**
     * The second decision on the same transaction finds no callback left —
     * a double click fires once (consumed arm of {@code takePendingCallback}).
     */
    @Test
    void secondDecisionIsIgnored() {
        park("12.00");
        assertTrue(client.accept());
        assertFalse(client.accept());
        assertFalse(client.refuse());
        verify(callback).onAccepted(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoMoreInteractions(callback);
    }

    /**
     * {@code abort} drops the stored callback: a later decision is rejected.
     */
    @Test
    void abortDropsCallback() {
        park("12.00");
        client.abort();
        assertFalse(client.accept());
        verifyNoInteractions(callback);
    }

    /**
     * {@code requestCredit} shares the debit mechanism (same parking).
     */
    @Test
    void requestCreditSharesDebitMechanism() {
        state.payment.pendingCardAmount = new BigDecimal("5.00");
        client.requestCredit(new BigDecimal("5.00"), callback);
        assertTrue(client.hasPending());
        assertTrue(client.refuse());
    }

    /**
     * The session hooks and the name are inert plumbing (no terminal
     * session on the simulator).
     */
    @Test
    void hooksAndNameAreInert() {
        client.onRegisterOpened();
        client.onRegisterClosed();
        assertEquals("virtual", client.name());
    }
}
