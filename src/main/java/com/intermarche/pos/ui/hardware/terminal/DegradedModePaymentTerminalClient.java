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

    /** The register state carrying the operator's own forcing (LC-07-08-02). */
    private final com.intermarche.pos.ui.PosState state;

    /**
     * Wraps a configured terminal with the degraded-mode gate.
     *
     * @param configured the terminal resolved from {@code pos.tpe.mode}
     * @param autoAccept the immediate-acceptance implementation
     * @param posSettingsService the settings catalog holding the toggle
     */
    public DegradedModePaymentTerminalClient(PaymentTerminalClient configured, PaymentTerminalClient autoAccept,
                                              PosSettingsService posSettingsService,
                                              com.intermarche.pos.ui.PosState state) {
        this.configured = configured;
        this.autoAccept = autoAccept;
        this.posSettingsService = posSettingsService;
        this.state = state;
    }

    /**
     * Tells whether transactions currently bypass the monetics — because the shop
     * administered it, or because THIS operator forced it ({@code LC-07-08-02}).
     *
     * <p>Two independent sources, one answer: a shop-wide parameter that survives a
     * restart, and a per-register forcing that expires on its own. Either is enough,
     * because both mean the same thing to a card request — do not wait for an
     * authorization that is not coming.
     *
     * @return true while the terminal is bypassed
     */
    private boolean degraded() {
        return posSettingsService.paymentDegradedMode()
                || (state != null && state.isMoneticsDegradedForced());
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
        return degraded() ? autoAccept : configured;
    }

    /**
     * Starts a debit. While degraded, it goes to the immediate-acceptance
     * terminal and its outcome is stamped degraded (BO-04-01-47/49) so the
     * card payment records that it was accepted without the monetique;
     * otherwise it reaches the configured terminal untouched. The degraded
     * decision is read once here, so the routing and the stamping can never
     * disagree even if the toggle flips mid-transaction.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        if (degraded()) {
            autoAccept.requestDebit(amount, new DegradedOutcomeCallback(callback));
        } else {
            configured.requestDebit(amount, callback);
        }
    }

    /**
     * Starts a credit, with the same degraded routing and stamping as
     * {@link #requestDebit(BigDecimal, TerminalTransactionCallback)}.
     *
     * @param amount the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        if (degraded()) {
            autoAccept.requestCredit(amount, new DegradedOutcomeCallback(callback));
        } else {
            configured.requestCredit(amount, callback);
        }
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

    /**
     * Callback wrapper that stamps every terminal outcome as degraded before
     * forwarding it, so a card payment accepted (or refused) through the
     * degraded gate records that no monetique server was reached
     * (BO-04-01-47/49). The error leg carries no outcome and is forwarded
     * unchanged.
     */
    private static final class DegradedOutcomeCallback implements TerminalTransactionCallback {

        /** The real decision receiver the stamped outcome is forwarded to. */
        private final TerminalTransactionCallback delegate;

        /**
         * Wraps the register's callback.
         *
         * @param delegate the real decision receiver
         */
        DegradedOutcomeCallback(TerminalTransactionCallback delegate) {
            this.delegate = delegate;
        }

        /**
         * Stamps the accepted outcome degraded and forwards it.
         *
         * @param outcome the accepted outcome
         */
        @Override
        public void onAccepted(TerminalOutcome outcome) {
            outcome.degradedMode = true;
            delegate.onAccepted(outcome);
        }

        /**
         * Stamps the refused outcome degraded and forwards it.
         *
         * @param outcome the refused outcome
         */
        @Override
        public void onRefused(TerminalOutcome outcome) {
            outcome.degradedMode = true;
            delegate.onRefused(outcome);
        }

        /**
         * Forwards a terminal failure unchanged (no outcome to stamp).
         *
         * @param message the operator-facing failure message
         */
        @Override
        public void onError(String message) {
            delegate.onError(message);
        }
    }
}
