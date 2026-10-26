package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;

/**
 * Ephemeral terminal decision, including documented WCL metadata and receipts.
 * LC-07-04-10/-12 and LC-07-07-03/-04: only accounting metadata is persisted;
 * print frames and raw vendor responses remain on the current operation.
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

    /**
     * The real CARD ENTRY MODE the terminal reported (EXP-54, LC-07-07-04):
     * {@code "SANS_CONTACT"} for a contactless tap, {@code "CODE"} for a
     * chip-and-PIN transaction, or null when the terminal does not
     * discriminate (virtual terminal, specification pending). The register only
     * DELEGATED one flow to the TPE before; now the mode it returns is traced.
     */
    public String entryMode;

    /** The customer receipt frame to print verbatim, or null when none. */
    public String customerReceiptFrame;

    /** The merchant receipt frame to print verbatim, or null when none. */
    public String merchantReceiptFrame;

    /** LC-07-17-05: additional WCL receipts, including terminal-controlled donation proofs. */
    public java.util.List<String> additionalReceiptFrames = new java.util.ArrayList<>();

    /**
     * The not-completed-transaction (TNA) frame to print when the exchange
     * failed, or null when none.
     */
    public String tnaFrame;

    /** The ephemeral raw terminal response; never an archived receipt. */
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
     * True when the accepted transaction asks for the customer's handwritten
     * signature, which forces the card receipt to print (LC-08-03-11). Only a
     * real monetique reports it; the virtual, auto-accept and degraded clients
     * leave it false, exactly as they leave the print frames null.
     */
    public boolean signatureRequired;

    /** Server selected by WCL VWAY (LC-07-07-03), absent when not reported. */
    public String server;

    /** Application subtype used when D16 is unknown (LC-07-07-04). */
    public String alternateMeanCode;

    /** Decodes documented WCL tags without interpreting D17 transaction numbers as entry modes.
     * @param response complete WCL final response
     */
    public void readVerifone(String response) {
        rawResponse = response;
        if (response == null) {
            return;
        }
        String settledCents = tag(response, "D10");
        if (settledCents != null) {
            if (!settledCents.matches("[0-9]+")) {
                throw new IllegalArgumentException("invalid WCL settled amount");
            }
            amount = new BigDecimal(settledCents).movePointLeft(2);
        }
        meanCode = tag(response, "D16");
        alternateMeanCode = tag(response, "D46");
        server = tag(response, "VWAY");
        authorizationNumber = tag(response, "D1C");
        String offline = tag(response, "D32");
        degradedMode = offline != null && !"0".equals(offline);
        String conditions = tag(response, "D1S");
        if (conditions != null && conditions.length() >= 4) {
            char signature = conditions.charAt(3);
            signatureRequired = signature == '5' || signature == '8';
        }
        // LC-07-17-05: retain additional documented ticket tags without creating donation logic in the POS.
        int cursor = response.indexOf("{D3L}");
        boolean firstReceipt = true;
        additionalReceiptFrames.clear();
        while (cursor >= 0) {
            int from = cursor + 5;
            int end = response.indexOf('{', from);
            String frame = end < 0 ? response.substring(from) : response.substring(from, end);
            if (firstReceipt) {
                firstReceipt = false;
            } else {
                additionalReceiptFrames.add(frame);
            }
            cursor = response.indexOf("{D3L}", from);
        }
        String receipt = tag(response, "D3L");
        if (receipt != null) {
            int separator = receipt.indexOf("--/--");
            if (separator < 0) {
                customerReceiptFrame = receipt;
            } else {
                customerReceiptFrame = receipt.substring(0, separator);
                merchantReceiptFrame = receipt.substring(separator + 5);
            }
            String result = tag(response, "RS");
            try {
                int code = Integer.parseInt(result);
                if (code < 0 || code > 9) {
                    tnaFrame = receipt;
                }
            } catch (NumberFormatException ignored) {
                // LC-07-04-12: an absent decision never creates a synthetic TNA frame.
            }
        }
    }

    /** Reads an optional flat WCL tag, preserving receipt whitespace (LC-07-04-10).
     * @param response complete response
     * @param name tag name without braces
     * @return exact value, or null when absent
     */
    private static String tag(String response, String name) {
        String marker = "{" + name + "}";
        int start = response.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int from = start + marker.length();
        int end = response.indexOf('{', from);
        return end < 0 ? response.substring(from) : response.substring(from, end);
    }

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
