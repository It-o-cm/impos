package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AutoAcceptTerminalClient}: every transaction is
 * accepted synchronously, and the plumbing methods are inert.
 */
class AutoAcceptTerminalClientTest {

    /** The client under test — stateless, one instance suffices. */
    private final AutoAcceptTerminalClient client = new AutoAcceptTerminalClient();

    /**
     * {@code requestDebit} fires the accept leg synchronously with the
     * requested amount.
     */
    @Test
    void debitAcceptsSynchronously() {
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        client.requestDebit(new BigDecimal("10.00"), callback);
        verify(callback).onAccepted(argThat(o -> new BigDecimal("10.00").compareTo(o.amount) == 0
                && "1".equals(o.server)));
        verifyNoMoreInteractions(callback);
    }

    /**
     * {@code requestCredit} fires the accept leg synchronously with the
     * requested amount.
     */
    @Test
    void creditAcceptsSynchronously() {
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        client.requestCredit(new BigDecimal("4.00"), callback);
        verify(callback).onAccepted(argThat(o -> new BigDecimal("4.00").compareTo(o.amount) == 0
                && "1".equals(o.server)));
        verifyNoMoreInteractions(callback);
    }

    /**
     * The abort, session hooks and name are inert plumbing.
     */
    @Test
    void plumbingIsInert() {
        client.abort();
        client.onRegisterOpened();
        client.onRegisterClosed();
        assertEquals("auto", client.name());
    }
    /** LC-07-06-02: a simple terminal falls back from combined debit to one ordinary debit for the full requested amount. */
    @Test
    void combinedDebitFallsBackToOneOrdinaryDecision() {
        var callback = mock(TerminalTransactionCallback.class);
        client.requestCombinedDebit(new BigDecimal("12.50"), new BigDecimal("5.00"), callback);
        verify(callback).onAccepted(argThat(outcome -> new BigDecimal("12.50").equals(outcome.amount)));
        verifyNoMoreInteractions(callback);
    }

    /**
     * BO-04-01-48: the client standing in for the terminal behind the degraded
     * gate answers on the server it was built for — the secondary server the
     * degraded mode forces — for a debit and for a credit alike.
     */
    @Test
    void aForcedServerClientReportsItsServer() {
        AutoAcceptTerminalClient forced = new AutoAcceptTerminalClient(VerifoneTags.DEGRADED_SERVER);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        forced.requestDebit(new BigDecimal("3.00"), callback);
        forced.requestCredit(new BigDecimal("2.00"), callback);
        verify(callback).onAccepted(argThat(o -> new BigDecimal("3.00").compareTo(o.amount) == 0
                && "4".equals(o.server)));
        verify(callback).onAccepted(argThat(o -> new BigDecimal("2.00").compareTo(o.amount) == 0
                && "4".equals(o.server)));
        assertEquals("4", forced.server());
        assertEquals("1", client.server());
    }
}
