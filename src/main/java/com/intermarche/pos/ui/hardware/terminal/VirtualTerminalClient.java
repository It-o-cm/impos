package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

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

    /**
     * One parked decision: the receiver and the amount it was parked with.
     *
     * <p>The two travel together because they mean nothing apart. Held as two
     * loose fields, a decision taken between the two writes of a new request
     * fired the PREVIOUS transaction's callback with the NEW amount, and a
     * taken callback left its amount behind for the next reader.
     *
     * @param callback the decision receiver
     * @param amount the amount the transaction was parked with
     */
    private record Pending(TerminalTransactionCallback callback, BigDecimal amount) {
    }

    /**
     * The decision awaited by the simulator, or null when none is in flight.
     *
     * <p>Every write happens under this instance's monitor; the field stays
     * {@code volatile} for {@link #hasPending()}, which the UI polls without
     * taking the lock.
     */
    private volatile Pending pending;

    /**
     * Parks the debit until the simulator decides; the request replaces any
     * stale one left by a transaction whose pending flag was cleared elsewhere.
     *
     * <p>Synchronized on the same monitor as {@link #takePending()}: the
     * receiver and its amount are posted in ONE step, so a decision taken
     * concurrently sees either the whole previous request or the whole new
     * one, never a half of each.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public synchronized void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        this.pending = new Pending(callback, amount);
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
     * Drops the in-flight request; the register side already cleared the
     * pending flag.
     */
    @Override
    public synchronized void abort() {
        pending = null;
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
        return pending != null && state.payment.pendingCardAmount != null;
    }

    /**
     * Applies the simulator's ACCEPT decision: fires the callback once.
     *
     * @return true when a pending transaction was accepted, false when
     *         nothing was pending (the caller answers 409)
     */
    public boolean accept() {
        Pending taken = takePending();
        if (taken == null) return false;
        TerminalOutcome outcome = TerminalOutcome.ofAmount(taken.amount());
        outcome.authorizationNumber = generateAuthorizationNumber();
        taken.callback().onAccepted(outcome);
        return true;
    }

    /**
     * Produces the 6-digit authorization number a real monetique returns on an
     * accepted transaction (BO-04-01-08): the simulator stands in for the
     * terminal, so it mints one here. A refused transaction carries none.
     *
     * @return a zero-padded 6-digit authorization number
     */
    private String generateAuthorizationNumber() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /**
     * Applies the simulator's REFUSE decision: fires the callback once.
     *
     * @return true when a pending transaction was refused, false when
     *         nothing was pending (the caller answers 409)
     */
    public boolean refuse() {
        Pending taken = takePending();
        if (taken == null) return false;
        taken.callback().onRefused(TerminalOutcome.ofAmount(taken.amount()));
        return true;
    }

    /**
     * Atomically consumes the parked request, or returns null when no decision
     * is expected — a double click on the simulator fires once.
     *
     * <p>The receiver AND its amount leave together, so the amount can no
     * longer survive its own transaction and be echoed into the next outcome.
     * The callback is fired by the caller OUTSIDE this monitor: the register's
     * payment processing must not run while the lock is held.
     *
     * @return the parked request to fire, or null
     */
    private synchronized Pending takePending() {
        if (!hasPending()) return null;
        Pending taken = pending;
        pending = null;
        return taken;
    }
}
