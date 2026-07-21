package com.intermarche.pos.ui.fidelity;

import java.io.Serializable;

public class FidelityState implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean active = false;
    public String label = "";

    public void assignCard(String card) {
        if (card != null && card.length() > 2) {
            this.active = true;
            this.label = card;
        }
    }

    public void clear() {
        active = false;
        label = "";
    }
}