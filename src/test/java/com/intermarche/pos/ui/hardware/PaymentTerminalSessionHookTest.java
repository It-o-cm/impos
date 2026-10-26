package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link PaymentTerminalSessionHook}, the register's
 * payment-terminal step of the cash session lifecycle (LC-01-02-09,
 * LC-13-04-05). Both collaborators are mocked.
 */
class PaymentTerminalSessionHookTest {

    /**
     * Builds a hook wired with mocked collaborators.
     *
     * @return the hook under test
     */
    private PaymentTerminalSessionHook hook() {
        PaymentTerminalSessionHook hook = new PaymentTerminalSessionHook();
        hook.terminalClient = mock(PaymentTerminalClient.class);
        hook.moneticMaintenance = mock(MoneticMaintenanceService.class);
        return hook;
    }

    /**
     * LC-01-02-09: an opening only tells the terminal, and never touches the
     * maintenance plan.
     */
    @Test
    void openingTellsTheTerminal() {
        PaymentTerminalSessionHook hook = hook();
        hook.onSessionOpened();
        verify(hook.terminalClient).onRegisterOpened();
        verifyNoInteractions(hook.moneticMaintenance);
    }

    /**
     * LC-01-02-09 then LC-13-04-05: a closing tells the terminal first, then
     * renews the maintenance plan.
     */
    @Test
    void closingTellsTheTerminalThenRenewsThePlan() {
        PaymentTerminalSessionHook hook = hook();
        hook.onSessionClosed();
        InOrder order = inOrder(hook.terminalClient, hook.moneticMaintenance);
        order.verify(hook.terminalClient).onRegisterClosed();
        order.verify(hook.moneticMaintenance).resetPlan();
    }

    /**
     * A terminal failure on closing propagates to the service, which contains
     * it, and the plan is not renewed — the pre-hook behaviour.
     */
    @Test
    void aTerminalFailureSkipsTheRenewal() {
        PaymentTerminalSessionHook hook = hook();
        doThrow(new IllegalStateException("terminal unavailable"))
                .when(hook.terminalClient).onRegisterClosed();
        assertThrows(IllegalStateException.class, hook::onSessionClosed);
        verify(hook.moneticMaintenance, never()).resetPlan();
    }
}
