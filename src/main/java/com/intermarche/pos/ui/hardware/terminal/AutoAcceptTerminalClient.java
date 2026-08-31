package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;

/**
 * No-terminal implementation: every transaction is accepted immediately and
 * synchronously — the legacy {@code pos.tpe.virtual=false} behavior where a
 * card payment registered without any terminal exchange. Selected with
 * {@code pos.tpe.mode=auto}.
 * <p>
 * Not a CDI bean on its own: instantiated by {@link TerminalClientProducer}.
 */
public class AutoAcceptTerminalClient implements PaymentTerminalClient {

    /**
     * Accepts the debit immediately (synchronous callback).
     *
     * @param amount the amount to debit
     * @param callback the decision receiver, fired before returning
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        callback.onAccepted(TerminalOutcome.ofAmount(amount));
    }

    /**
     * Accepts the credit immediately (synchronous callback).
     *
     * @param amount the amount to credit
     * @param callback the decision receiver, fired before returning
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        callback.onAccepted(TerminalOutcome.ofAmount(amount));
    }

    /** Nothing in flight, ever. */
    @Override
    public void abort() {
        // Nothing: transactions complete synchronously.
    }

    /** No terminal session. */
    @Override
    public void onRegisterOpened() {
        // Nothing: there is no terminal.
    }

    /** No terminal session. */
    @Override
    public void onRegisterClosed() {
        // Nothing: there is no terminal.
    }

    /**
     * Returns the implementation name.
     *
     * @return "auto"
     */
    @Override
    public String name() {
        return "auto";
    }
}
