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
     * The outcome of the LAST holder lookup (addendum §3), rendered by the
     * fidelity page after the post/redirect/get hop — storing it here is what
     * makes the result list survive a browser refresh (the refresh re-GETs
     * /fidelity instead of replaying the POST). Transient: never persisted,
     * dropped with the process. Cleared when a card attaches or the ticket
     * ends.
     */
    public transient FidelityService.LookupView lastLookup = null;

    /** The search mode of the last lookup (card | tel | email | name). */
    public String lastLookupMode = "card";

    /** The criterion typed for the last lookup, echoed back to the page. */
    public String lastLookupValue = "";

    /** Matches shown per lookup page — sized so the fidelity page chrome plus
     * one page and its pager always fit the 600px screen. */
    private static final int LOOKUP_PAGE_SIZE = 4;

    /** The 0-based current page of the lookup result list. */
    public int lastLookupPage = 0;

    /**
     * Returns the lookup matches visible on the current page, clamping the
     * page index inside the list (same invariant as the refund detail page).
     *
     * @return the sublist of matches for the current page, or an empty list
     */
    public java.util.List<ImfidClient.LookupMatch> getVisibleMatches() {
        if (lastLookup == null || lastLookup.matches == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<ImfidClient.LookupMatch> all = lastLookup.matches;
        int maxPage = Math.max(0, (all.size() - 1) / LOOKUP_PAGE_SIZE);
        if (lastLookupPage > maxPage) lastLookupPage = maxPage;
        if (lastLookupPage < 0) lastLookupPage = 0;
        int from = lastLookupPage * LOOKUP_PAGE_SIZE;
        int to = Math.min(from + LOOKUP_PAGE_SIZE, all.size());
        return all.subList(from, to);
    }

    /**
     * Indicates whether a previous lookup page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasLookupPrev() {
        return lastLookupPage > 0;
    }

    /**
     * Indicates whether a next lookup page exists.
     *
     * @return true if more matches follow the current page
     */
    public boolean isHasLookupNext() {
        if (lastLookup == null || lastLookup.matches == null) return false;
        return (lastLookupPage + 1) * LOOKUP_PAGE_SIZE < lastLookup.matches.size();
    }

    /**
     * Returns the 1-based current lookup page number for display.
     *
     * @return the current page number
     */
    public int getLookupPageDisplay() {
        return lastLookupPage + 1;
    }

    /**
     * Returns the total number of lookup pages (at least one).
     *
     * @return the page count
     */
    public int getLookupPageCount() {
        if (lastLookup == null || lastLookup.matches == null || lastLookup.matches.isEmpty()) {
            return 1;
        }
        return (lastLookup.matches.size() - 1) / LOOKUP_PAGE_SIZE + 1;
    }

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
            clearLookup();
        }
    }

    /**
     * Drops the stored lookup outcome and resets the search echo to the
     * default card mode (a fresh fidelity page shows no stale result list).
     */
    public void clearLookup() {
        lastLookup = null;
        lastLookupMode = "card";
        lastLookupValue = "";
        lastLookupPage = 0;
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
        clearLookup();
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