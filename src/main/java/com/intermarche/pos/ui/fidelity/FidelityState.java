package com.intermarche.pos.ui.fidelity;

import java.io.Serializable;

/**
 * In-memory fidelity state of the current ticket: at most one card,
 * attached by scan or manual entry.
 * <p>
 * Semantic contract: {@code active} is what enables the CAGNOTTE payment
 * button; the card is copied onto the draft at every sync
 * ({@code Ticket.fidelityCard}), so it SURVIVES a register restart, follows
 * a parked ticket, and travels to the store node — this state is the
 * working copy, the draft is the durable one. Cleared with the ticket; a
 * second card simply replaces the first (last presented wins). Until the
 * phase 7 valuation engine, the card is identification only: no real
 * balance is read or credited.
 */
public class FidelityState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True once a card is attached; enables the CAGNOTTE payment button. */
    public boolean active = false;

    /** The attached card number, or an empty string. */
    public String label = "";

    /** Displayed earn projection total (imfid /api/earn), or null when none. */
    public java.math.BigDecimal earnTotal = null;

    /** Per-rule projection lines (label + amount) — printed labels come from here. */
    public java.util.List<EarnLine> earnEntries = new java.util.ArrayList<>();

    /** Burnable base of the current ticket (caps the fidelity payment), or null. */
    public java.math.BigDecimal burnableBase = null;

    /**
     * The last /valuation couple, VERBATIM (spec §1) — captured on the
     * valuation path, fed to /api/earn and re-sent in the ticket-closed
     * event. Never reconstructed.
     */
    public String lastValuationRequestJson = null;

    /** The matching raw response JSON. */
    public String lastValuationResponseJson = null;

    /** One displayed earn line: the rule label and its amount. */
    public static class EarnLine implements Serializable {
        private static final long serialVersionUID = 1L;
        /** The rule code (displayedEarn trace at close). */
        public String ruleCode;
        /** The label to display and print. */
        public String label;
        /** The earn amount. */
        public java.math.BigDecimal amount;

        /**
         * Creates a displayed earn line.
         *
         * @param ruleCode the rule code
         * @param label the printable label
         * @param amount the earn amount
         */
        public EarnLine(String ruleCode, String label, java.math.BigDecimal amount) {
            this.ruleCode = ruleCode;
            this.label = label;
            this.amount = amount;
        }
    }

    /** Clears the displayed projection (degraded imfid, card removed, new sale). */
    public void clearEarn() {
        earnTotal = null;
        earnEntries = new java.util.ArrayList<>();
        burnableBase = null;
    }

    /**
     * Attaches a card; silently ignores null or too-short values (a guard
     * against empty submits, not a format check — the format check lives in
     * the scan handler's pattern).
     *
     * @param card the card number
     */
    public void assignCard(String card) {
        if (card != null && card.length() > 2) {
            this.active = true;
            this.label = card;
        }
    }

    /**
     * Detaches the card (called when the ticket is cleared).
     */
    public void clear() {
        active = false;
        label = "";
    }
}