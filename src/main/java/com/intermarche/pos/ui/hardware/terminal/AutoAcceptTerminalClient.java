package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;

/**
 * No-terminal implementation: every transaction is accepted immediately and
 * synchronously — the legacy {@code pos.tpe.virtual=false} behavior where a
 * card payment registered without any terminal exchange. Selected with
 * {@code pos.tpe.mode=auto}.
 * <p>
 * Not a CDI bean on its own: instantiated by {@link TerminalClientProducer}.
 * <p>
 * It answers like the real monetique (BO-04-01-48): every outcome names the
 * server it was accepted on through the {@code VWAY} value — the primary
 * server for the {@code auto} mode, the secondary server the degraded mode
 * forces when it stands in for the terminal behind the degraded gate.
 */
public class AutoAcceptTerminalClient implements PaymentTerminalClient {

    /** The {@code VWAY} server code every outcome of this client reports. */
    private final String server;

    /**
     * Builds the client of the {@code auto} mode, accepting on the primary
     * monetique server.
     */
    public AutoAcceptTerminalClient() {
        this(VerifoneTags.PRIMARY_SERVER);
    }

    /**
     * Builds a client accepting on the given monetique server (BO-04-01-48).
     *
     * @param server the {@code VWAY} server code its outcomes report
     */
    public AutoAcceptTerminalClient(String server) {
        this.server = server;
    }

    /**
     * Accepts the debit immediately (synchronous callback).
     *
     * @param amount the amount to debit
     * @param callback the decision receiver, fired before returning
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        callback.onAccepted(outcome(amount));
    }

    /**
     * Accepts the credit immediately (synchronous callback).
     *
     * @param amount the amount to credit
     * @param callback the decision receiver, fired before returning
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        callback.onAccepted(outcome(amount));
    }

    /**
     * Builds the outcome of an immediate acceptance: the amount and the server
     * it was accepted on (BO-04-01-48).
     *
     * @param amount the transaction amount
     * @return the outcome
     */
    private TerminalOutcome outcome(BigDecimal amount) {
        TerminalOutcome outcome = TerminalOutcome.ofAmount(amount);
        outcome.server = server;
        return outcome;
    }

    /**
     * Returns the {@code VWAY} server code this client's outcomes report.
     *
     * @return the server code
     */
    public String server() {
        return server;
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
