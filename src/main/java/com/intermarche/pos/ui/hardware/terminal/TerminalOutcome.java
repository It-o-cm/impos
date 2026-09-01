package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;

/**
 * Result of a payment-terminal transaction, as reported by a
 * {@link PaymentTerminalClient} implementation to its callback.
 * <p>
 * The virtual terminal fills only the amount; the Verifone client will add
 * the fields carried by the monetique response once the protocol
 * specification is available: the real payment mean (tags {D16}/{D46},
 * LC-07-07-04), the print frames to output verbatim (LC-07-04-10) and the
 * TNA frame of a failed transaction (LC-07-04-12).
 */
public class TerminalOutcome {

    /** The transaction amount, as submitted to the terminal. */
    public BigDecimal amount;

    /**
     * The payment-mean code reported by the terminal ({D16}, or {D46} when
     * {D16} is unknown), or null when the terminal does not discriminate
     * (virtual terminal, specification pending).
     */
    public String meanCode;

    /** The customer receipt frame to print verbatim, or null when none. */
    public String customerReceiptFrame;

    /** The merchant receipt frame to print verbatim, or null when none. */
    public String merchantReceiptFrame;

    /**
     * The not-completed-transaction (TNA) frame to print when the exchange
     * failed, or null when none.
     */
    public String tnaFrame;

    /** The raw terminal response, kept verbatim for the journal, or null. */
    public String rawResponse;

    /**
     * The authorization number returned by the monetique for an accepted
     * transaction (BO-04-01-08), or null when the terminal returns none — the
     * degraded gate accepts without a monetique server, so its outcome carries
     * no authorization number.
     */
    public String authorizationNumber;

    /**
     * True when the transaction was accepted in degraded mode (BO-04-01-47/49):
     * the manual back-office toggle (BO-03-12-05) routed it to immediate
     * acceptance instead of the configured terminal. The register only knows
     * this MANUAL degraded mode; a secondary-monetique-server acceptance
     * (BO-04-01-48) is not something it can observe. Defaults to false.
     */
    public boolean degradedMode;

    /**
     * Builds an outcome carrying only the amount — what the virtual
     * terminal can report.
     *
     * @param amount the transaction amount
     * @return the outcome
     */
    public static TerminalOutcome ofAmount(BigDecimal amount) {
        TerminalOutcome outcome = new TerminalOutcome();
        outcome.amount = amount;
        return outcome;
    }
}
