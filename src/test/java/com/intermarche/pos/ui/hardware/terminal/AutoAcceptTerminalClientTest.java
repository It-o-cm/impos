package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
        verify(callback).onAccepted(argThat(o -> new BigDecimal("10.00").compareTo(o.amount) == 0));
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
        verify(callback).onAccepted(argThat(o -> new BigDecimal("4.00").compareTo(o.amount) == 0));
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
}
