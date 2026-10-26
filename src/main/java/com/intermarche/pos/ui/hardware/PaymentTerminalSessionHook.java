package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.SessionLifecycleHook;
import com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The register's payment-terminal step of the cash session lifecycle: the
 * terminal follows the register's opening and closing (LC-01-02-09), and the
 * end of a period renews the scheduled random maintenance instant
 * (LC-13-04-05).
 *
 * <p>Plugged into the shared {@link com.intermarche.pos.service.CashSessionService}
 * through {@link SessionLifecycleHook}, which keeps that service identical on
 * the store node — a node with no payment terminal and no implementation of
 * the hook. A failure here is contained by the service.
 */
@ApplicationScoped
public class PaymentTerminalSessionHook implements SessionLifecycleHook {

    /** LC-01-02-09: forwards the session lifecycle to the configured terminal. */
    @Inject
    PaymentTerminalClient terminalClient;

    /** LC-13-04-05: end of period renews the scheduled random maintenance instant. */
    @Inject
    MoneticMaintenanceService moneticMaintenance;

    /**
     * Tells the terminal the register has opened (LC-01-02-09).
     */
    @Override
    public void onSessionOpened() {
        terminalClient.onRegisterOpened();
    }

    /**
     * Tells the terminal the register has closed (LC-01-02-09), then renews the
     * maintenance plan (LC-13-04-05); a terminal failure skips the renewal.
     */
    @Override
    public void onSessionClosed() {
        terminalClient.onRegisterClosed();
        moneticMaintenance.resetPlan();
    }
}
