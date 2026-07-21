package com.intermarche.pos.ui;

import java.io.Serializable;

public class PriceModState implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean active = false;
    public String type = null; // "REMISE", "DISCOUNT", "FORCE_PRICE"
    public String targetUid = null;
    public String targetLabel = null;

    public void set(String type, String uid, String label) {
        this.active = true;
        this.type = type;
        this.targetUid = uid;
        this.targetLabel = label;
    }

    public void clear() {
        this.active = false;
        this.type = null;
        this.targetUid = null;
        this.targetLabel = null;
    }

    public String getTypeLabel() {
        if ("REMISE".equals(type)) return "SAISIE REMISE (€)";
        if ("DISCOUNT".equals(type)) return "SAISIE DISCOUNT (%)";
        if ("FORCE_PRICE".equals(type)) return "NOUVEAU PRIX (€)";
        return "MODIFICATION";
    }
}