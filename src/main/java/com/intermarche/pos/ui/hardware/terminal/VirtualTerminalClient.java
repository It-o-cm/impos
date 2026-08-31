package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.math.BigDecimal;

/**
 * The simulator's payment terminal: the transaction stays pending until a
 * human clicks ACCEPTER or REFUSER on the virtual TPE panel, whose buttons
 * land on the accept/refuse endpoints of {@code PosHardwareResource} (and
 * their mock-profile relays), which call {@link #accept()} / {@link #refuse()}.
 * <p>
 * The pending flag shown to the UI remains {@code state.payment.pendingCardAmount}
 * — owned by {@code PaymentService} — so this client only keeps the callback
 * of the in-flight transaction and re-reads the state to decide whether a
 * decision is still expected. This mirrors the state exactly: if the pending
 * amount was cleared elsewhere (ticket cancelled, payments cancelled), a
 * late simulator click is rejected instead of resurrecting the payment.
 * <p>
 * {@code @Typed} restricts this bean to its concrete type: the
 * {@link PaymentTerminalClient} injection point must resolve to the
 * {@link TerminalClientProducer} producer ALONE — without this restriction
 * the container sees two candidates for the port and refuses to start
 * (AmbiguousResolutionException).
 */
@ApplicationScoped
@Typed(VirtualTerminalClient.class)
public class VirtualTerminalClient implements PaymentTerminalClient {

    /** The register's composition root — source of truth for the pending flag. */
    @Inject
    PosState state;

    /** The callback of the transaction awaiting a simulator decision, or null. */
    private volatile TerminalTransactionCallback callback;

    /** The amount of the awaited transaction, echoed in the outcome. */
    private volatile BigDecimal amount;

    /**
     * Parks the debit until the simulator decides; the callback replaces any
     * stale one left by a transaction whose pending flag was cleared elsewhere.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        this.amount = amount;
        this.callback = callback;
    }

    /**
     * Parks the credit (refund) until the simulator decides — same mechanism
     * as the debit, the virtual terminal does not discriminate.
     *
     * @param amount the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        requestDebit(amount, callback);
    }

    /**
     * Drops the in-flight callback; the register side already cleared the
     * pending flag.
     */
    @Override
    public void abort() {
        callback = null;
        amount = null;
    }

    /** No terminal session to open on the simulator. */
    @Override
    public void onRegisterOpened() {
        // Nothing: the virtual terminal has no session protocol.
    }

    /** No terminal session to close on the simulator. */
    @Override
    public void onRegisterClosed() {
        // Nothing: the virtual terminal has no session protocol.
    }

    /**
     * Returns the implementation name.
     *
     * @return "virtual"
     */
    @Override
    public String name() {
        return "virtual";
    }

    /**
     * Indicates whether a simulator decision is still expected: a callback
     * is held AND the register still shows the pending amount.
     *
     * @return true when accept/refuse would fire the callback
     */
    public boolean hasPending() {
        return callback != null && state.payment.pendingCardAmount != null;
    }

    /**
     * Applies the simulator's ACCEPT decision: fires the callback once.
     *
     * @return true when a pending transaction was accepted, false when
     *         nothing was pending (the caller answers 409)
     */
    public boolean accept() {
        TerminalTransactionCallback cb = takePendingCallback();
        if (cb == null) return false;
        cb.onAccepted(TerminalOutcome.ofAmount(amount));
        return true;
    }

    /**
     * Applies the simulator's REFUSE decision: fires the callback once.
     *
     * @return true when a pending transaction was refused, false when
     *         nothing was pending (the caller answers 409)
     */
    public boolean refuse() {
        TerminalTransactionCallback cb = takePendingCallback();
        if (cb == null) return false;
        cb.onRefused(TerminalOutcome.ofAmount(amount));
        return true;
    }

    /**
     * Atomically consumes the pending callback, or returns null when no
     * decision is expected — a double click on the simulator fires once.
     *
     * @return the callback to fire, or null
     */
    private synchronized TerminalTransactionCallback takePendingCallback() {
        if (!hasPending()) return null;
        TerminalTransactionCallback cb = callback;
        callback = null;
        return cb;
    }
}
