package com.intermarche.pos.ui.endorsement;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * State of a pending manager-endorsement request (ticket/line cancellation,
 * price modification).
 * <p>
 * Phase 0: the pending price-modification value is a {@link BigDecimal}.
 * <p>
 * This is the PARKED-GESTURE half of the endorsement pattern: the guarded
 * action is encoded as a string ({@code requestedAction}) or as the
 * price-mod triple, the modal opens, and NOTHING has happened yet — the
 * gesture only executes when the dispatch validates a manager PIN. One
 * pending request at a time by construction (a new request overwrites the
 * previous one); {@code scannedBadge} mirrors the lock-page mailbox so a
 * manager can badge instead of typing their login on the modal.
 */
public class EndorsementState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True while an endorsement request is active. */
    public boolean active = false;

    /** The requested action code, or null. */
    public String requestedAction = null;

    /** The manager badge scanned during the endorsement, or null. */
    public String scannedBadge = null;

    /** The current endorsement error, or null. */
    public String error = null;

    /** The pending price-modification type (REMISE, DISCOUNT, FORCE_PRICE), or null. */
    public String pendingPriceType = null;

    /** The uid of the targeted ticket line, or null. */
    public String pendingTargetUid = null;

    /** The pending price-modification value (euros or percent depending on the type). */
    public BigDecimal pendingValue = BigDecimal.ZERO;

    /**
     * Opens an endorsement request for the given action.
     *
     * @param action the action code requiring authorization
     */
    public void request(String action) {
        this.active = true;
        this.requestedAction = action;
        this.error = null;
        this.scannedBadge = null;
        clearPendingPrice();
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(String type, String uid, BigDecimal value) {
        this.active = true;
        this.requestedAction = "PRICE_MODIFICATION";
        this.pendingPriceType = type;
        this.pendingTargetUid = uid;
        this.pendingValue = value != null ? value : BigDecimal.ZERO;
        this.error = null;
        this.scannedBadge = null;
    }

    /**
     * Clears the endorsement request and any pending price modification.
     */
    public void clear() {
        this.active = false;
        this.requestedAction = null;
        this.error = null;
        this.scannedBadge = null;
        clearPendingPrice();
    }

    /**
     * Clears the pending price-modification fields.
     */
    private void clearPendingPrice() {
        this.pendingPriceType = null;
        this.pendingTargetUid = null;
        this.pendingValue = BigDecimal.ZERO;
    }

    /**
     * Records the manager badge scanned during the endorsement.
     *
     * @param badge the scanned badge id
     */
    public void setScannedBadge(String badge) { this.scannedBadge = badge; }

    /**
     * Clears the scanned manager badge.
     */
    public void clearScannedBadge() { this.scannedBadge = null; }
}
