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