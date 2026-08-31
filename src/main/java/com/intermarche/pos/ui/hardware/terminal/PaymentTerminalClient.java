package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;

/**
 * Port of the electronic payment terminal (TPE): the payment layer talks to
 * this interface only, never to a concrete terminal.
 * <p>
 * Implementations: {@link VirtualTerminalClient} (the simulator's terminal,
 * default), {@link AutoAcceptTerminalClient} (no terminal — immediate
 * registration, the legacy {@code pos.tpe.virtual=false} behavior) and
 * {@link VerifoneTerminalClient} (integrated monetique, skeleton pending
 * the protocol specification). The active implementation is selected by
 * {@code pos.tpe.mode} through {@link TerminalClientProducer}.
 * <p>
 * The contract is asynchronous: {@code requestDebit}/{@code requestCredit}
 * return immediately and the decision arrives later on the callback — the
 * register UI keeps polling its state meanwhile, exactly like the rest of
 * the reactive contract of this application.
 */
public interface PaymentTerminalClient {

    /**
     * Starts a debit (sale payment) of the given amount on the terminal.
     * Callers guard against concurrent requests: at most one transaction is
     * in flight per register.
     *
     * @param amount the amount to debit, already scaled to 2 decimals
     * @param callback the decision receiver (exactly one method fired)
     */
    void requestDebit(BigDecimal amount, TerminalTransactionCallback callback);

    /**
     * Starts a credit (refund) of the given amount on the terminal
     * (LC-05-06-03: refund as a "credit" operation).
     *
     * @param amount the amount to credit, already scaled to 2 decimals
     * @param callback the decision receiver (exactly one method fired)
     */
    void requestCredit(BigDecimal amount, TerminalTransactionCallback callback);

    /**
     * Abandons the in-flight transaction from the register side. Repeated
     * calls are serialized by the implementation to avoid desynchronizing
     * the terminal (LC-07-07-02); a call with nothing in flight is a no-op.
     */
    void abort();

    /**
     * Register-session opening hook. The Verifone client will use it for
     * the c18 serial-number request and the conditional c2g maintenance
     * (LC-01-01-06/07); other implementations do nothing.
     */
    void onRegisterOpened();

    /**
     * Register-session closing hook. The Verifone client will use it for
     * the c2h logoff request (LC-01-02-09); other implementations do
     * nothing.
     */
    void onRegisterClosed();

    /**
     * Returns the human-readable name of the implementation, for logs and
     * the technical journal.
     *
     * @return the implementation name
     */
    String name();
}
