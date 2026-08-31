package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.service.PosSettingsService;

import java.math.BigDecimal;

/**
 * Degraded-mode gate in front of the configured payment terminal
 * (BO-03-12-05): a manual back-office toggle
 * ({@code payment.degraded-mode}, {@link PosSettingsService}) lets the store
 * switch every card transaction to immediate acceptance in case of a
 * monetique outage, without redeploying or restarting.
 * <p>
 * The setting is read on every transaction (not once at construction), so
 * the toggle takes effect on the very next payment. The terminal-session
 * lifecycle hooks ({@link #onRegisterOpened()}/{@link #onRegisterClosed()})
 * always target the CONFIGURED terminal regardless of the toggle: the
 * physical handshake with the hardware does not depend on how individual
 * transactions are currently routed.
 * <p>
 * Wraps whichever implementation {@link TerminalClientProducer} resolved
 * from {@code pos.tpe.mode} — the port stays {@link PaymentTerminalClient}
 * throughout, so {@code PaymentService} never knows this gate exists.
 */
public class DegradedModePaymentTerminalClient implements PaymentTerminalClient {

    /** The terminal implementation selected by {@code pos.tpe.mode}. */
    private final PaymentTerminalClient configured;

    /** The immediate-acceptance implementation used while degraded. */
    private final PaymentTerminalClient autoAccept;

    /** The catalog holding the live value of the degraded-mode toggle. */
    private final PosSettingsService posSettingsService;

    /**
     * Wraps a configured terminal with the degraded-mode gate.
     *
     * @param configured the terminal resolved from {@code pos.tpe.mode}
     * @param autoAccept the immediate-acceptance implementation
     * @param posSettingsService the settings catalog holding the toggle
     */
    public DegradedModePaymentTerminalClient(PaymentTerminalClient configured, PaymentTerminalClient autoAccept,
                                              PosSettingsService posSettingsService) {
        this.configured = configured;
        this.autoAccept = autoAccept;
        this.posSettingsService = posSettingsService;
    }

    /**
     * Returns the terminal that manual requests reach outside degraded
     * mode — exposed for the producer's own tests, which assert the
     * resolved implementation per {@code pos.tpe.mode}.
     *
     * @return the configured (non-degraded) terminal
     */
    public PaymentTerminalClient configured() {
        return configured;
    }

    /**
     * Returns the terminal currently reached by a transaction: the
     * immediate-acceptance implementation while degraded mode is on, the
     * configured one otherwise.
     *
     * @return the currently active terminal
     */
    private PaymentTerminalClient active() {
        return posSettingsService.paymentDegradedMode() ? autoAccept : configured;
    }

    /**
     * Starts a debit on the currently active terminal.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        active().requestDebit(amount, callback);
    }

    /**
     * Starts a credit on the currently active terminal.
     *
     * @param amount the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        active().requestCredit(amount, callback);
    }

    /**
     * Aborts the in-flight transaction on the currently active terminal.
     */
    @Override
    public void abort() {
        active().abort();
    }

    /**
     * Register-session opening hook, always forwarded to the configured
     * terminal (see class documentation).
     */
    @Override
    public void onRegisterOpened() {
        configured.onRegisterOpened();
    }

    /**
     * Register-session closing hook, always forwarded to the configured
     * terminal (see class documentation).
     */
    @Override
    public void onRegisterClosed() {
        configured.onRegisterClosed();
    }

    /**
     * Returns the name of the currently active terminal.
     *
     * @return the active implementation's name
     */
    @Override
    public String name() {
        return active().name();
    }
}
