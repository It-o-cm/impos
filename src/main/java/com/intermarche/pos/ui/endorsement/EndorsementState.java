package com.intermarche.pos.ui.endorsement;

import java.io.Serializable;

public class EndorsementState implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean active = false;
    public String requestedAction = null;
    public String scannedBadge = null;
    public String error = null;

    // NOUVEAU
    public String pendingPriceType = null;
    public String pendingTargetUid = null;
    public double pendingValue = 0.0;

    public void request(String action) {
        this.active = true;
        this.requestedAction = action;
        this.error = null;
        this.scannedBadge = null;
        clearPendingPrice();
    }

    // NOUVEAU
    public void requestPriceModification(String type, String uid, double value) {
        this.active = true;
        this.requestedAction = "PRICE_MODIFICATION";
        this.pendingPriceType = type;
        this.pendingTargetUid = uid;
        this.pendingValue = value;
        this.error = null;
        this.scannedBadge = null;
    }

    public void clear() {
        this.active = false;
        this.requestedAction = null;
        this.error = null;
        this.scannedBadge = null;
        clearPendingPrice();
    }

    private void clearPendingPrice() {
        this.pendingPriceType = null;
        this.pendingTargetUid = null;
        this.pendingValue = 0.0;
    }

    public void setScannedBadge(String badge) { this.scannedBadge = badge; }
    public void clearScannedBadge() { this.scannedBadge = null; }
}