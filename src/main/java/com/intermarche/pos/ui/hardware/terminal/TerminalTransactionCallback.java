package com.intermarche.pos.ui.hardware.terminal;

/**
 * Callback through which a {@link PaymentTerminalClient} reports the end of
 * a transaction to the payment layer.
 * <p>
 * Exactly one method is invoked per requested transaction. Implementations
 * are provided by {@code PaymentService} and mutate the register state; they
 * may be invoked from an HTTP worker thread (virtual terminal: the
 * simulator's accept/refuse endpoint) or from the terminal client's own
 * exchange thread (Verifone), so they must only touch thread-safe state.
 */
public interface TerminalTransactionCallback {

    /**
     * The terminal accepted the transaction.
     *
     * @param outcome the transaction outcome (amount, mean, print frames)
     */
    void onAccepted(TerminalOutcome outcome);

    /**
     * The terminal refused the transaction.
     *
     * @param outcome the refusal outcome (amount, possible TNA frame)
     */
    void onRefused(TerminalOutcome outcome);

    /**
     * The exchange failed before a decision (terminal unreachable, timeout,
     * protocol error): the payment must not be registered and the operator
     * is told why.
     *
     * @param message the operator-facing message (French, uppercase)
     */
    void onError(String message);
}
