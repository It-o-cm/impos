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
     * The holder's last name, known ONLY when the card was attached through
     * the identity lookup (addendum §3.3) — a scanned card carries no
     * identity, by the pseudonymity doctrine. Operator-facing display only.
     */
    public String holderLastName = null;

    /** The holder's first name — same provenance and rules as the last name. */
    public String holderFirstName = null;

    /** ACTIVE | PENDING_ACTIVATION | RESILIATED, read at attachment, or null. */
    public String accountStatus = null;

    /**
     * The AVAILABLE balance read at attachment (spec §4 — net of leases),
     * or null when imfid could not answer (degraded = show nothing).
     */
    public java.math.BigDecimal availableBalance = null;

    /**
     * Attaches a card; silently ignores null or too-short values (a guard
     * against empty submits, not a format check — the format check lives in
     * the scan handler's pattern). Any holder identity or account data of a
     * PREVIOUS card is dropped: re-selecting a card REPLACES the former one
     * entirely, and a scanned card must never inherit another holder's name.
     *
     * @param card the card number
     */
    public void assignCard(String card) {
        if (card != null && card.length() > 2) {
            this.active = true;
            this.label = card;
            this.holderLastName = null;
            this.holderFirstName = null;
            this.accountStatus = null;
            this.availableBalance = null;
        }
    }

    /**
     * Detaches the card (called when the ticket is cleared).
     */
    public void clear() {
        active = false;
        label = "";
        holderLastName = null;
        holderFirstName = null;
        accountStatus = null;
        availableBalance = null;
    }

    /**
     * Returns the operator-facing summary of the attached card: holder (when
     * known from a lookup), card number and available balance — the line the
     * main screen shows next to the fidelity icon.
     *
     * @return the display line, or null when no card is attached
     */
    public String getDisplaySummary() {
        if (!active) return null;
        StringBuilder sb = new StringBuilder();
        if (holderLastName != null) {
            sb.append(holderLastName.toUpperCase());
            if (holderFirstName != null && !holderFirstName.isBlank()) {
                sb.append(' ').append(holderFirstName);
            }
            sb.append(" \u00b7 ");
        }
        sb.append(label);
        if (availableBalance != null) {
            sb.append(" \u00b7 ").append(String.format("%.2f", availableBalance).replace('.', ',')).append(" \u20ac");
        }
        return sb.toString();
    }
}