package com.intermarche.pos.ui.cash;

public class CashItem {
    public String id;
    public String label;
    public double value;

    public CashItem(String id, String label, double value) {
        this.id = id;
        this.label = label;
        this.value = value;
    }
}