package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TicketState implements Serializable {
    private static final long serialVersionUID = 1L;

    private transient PosState parent;

    public List<TicketItem> items = new ArrayList<>();
    public double totalAmount = 0.0;
    public double currentWeight = 0.0;
    public double lastRecordedWeight = Double.NaN;
    public String transientError = null;

    public void setParent(PosState parent) {
        this.parent = parent;
    }

    void onChange() {
        if (this.parent != null) {
            this.parent.touch();
        }
    }

    // CORRECTION : Logique de fusion améliorée
    public void addItem(String ean, String plu, String label, double unitPrice, double qtyToAdd) {
        // On détermine une clé de fusion : PLU prioritaire, sinon EAN
        String mergeKey = null;
        if (plu != null && !plu.isEmpty()) {
            mergeKey = "PLU:" + plu;
        } else if (ean != null && !ean.isEmpty()) {
            mergeKey = "EAN:" + ean;
        }

        if (mergeKey != null) {
            for (TicketItem item : items) {
                String itemKey = null;
                if (item.plu != null && !item.plu.isEmpty()) itemKey = "PLU:" + item.plu;
                else if (item.ean != null && !item.ean.isEmpty()) itemKey = "EAN:" + item.ean;

                if (mergeKey.equals(itemKey)) {
                    item.quantity += qtyToAdd;
                    this.totalAmount += (unitPrice * qtyToAdd);
                    if (parent != null) parent.lastEnteredItemId = item.uid;
                    onChange();
                    return;
                }
            }
        }

        TicketItem newItem = new TicketItem(ean, plu, label, unitPrice, qtyToAdd);
        items.add(newItem);
        this.totalAmount += (unitPrice * qtyToAdd);

        if (parent != null) parent.lastEnteredItemId = newItem.uid;

        onChange();
    }

    public void removeLastItem() {
        if (!items.isEmpty()) {
            TicketItem last = items.get(items.size() - 1);
            totalAmount -= (last.unitPrice * last.quantity);
            items.remove(items.size() - 1);
            onChange();
        }
    }

    public void removeItemById(String uid) {
        if (uid == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uid.equals(uid)) {
                TicketItem item = items.get(i);
                totalAmount -= (item.unitPrice * item.quantity);
                items.remove(i);
                onChange();
                return;
            }
        }
    }

    public void clear() {
        items.clear();
        totalAmount = 0.0;
        currentWeight = 0.0;
        lastRecordedWeight = Double.NaN;
        transientError = null;
        onChange();
    }

    public void setError(String err) {
        this.transientError = err;
        onChange();
    }

    public void setWeight(double w) {
        this.currentWeight = w;
        onChange();
    }

    public String getTotalFormatted() { return formatPrice(totalAmount); }
    public double getTotalAmount() { return totalAmount; }
    public List<TicketItem> getItems() { return items; }

    private String formatPrice(double value) { return String.format("%.2f", value).replace(".", ","); }

    // --- CLASSE INTERNE TICKET ITEM ---

    public static class TicketItem implements Serializable {
        private static final long serialVersionUID = 1L;

        public String uid;
        public String ean;
        public String plu; // Le champ PLU est déjà présent
        public String label;
        public double unitPrice;
        public double quantity;

        public double originalUnitPrice = 0.0;
        public String modifierLabel = null;

        public TicketItem(String ean, String plu, String label, double unitPrice, double quantity) {
            this.uid = UUID.randomUUID().toString();
            this.ean = ean;
            this.plu = plu;
            this.label = label;
            this.unitPrice = unitPrice;
            this.originalUnitPrice = unitPrice;
            this.quantity = quantity;
        }
        public TicketItem() {}

        public double getTotalPrice() { return unitPrice * quantity; }

        // CORRECTION : Logique d'affichage basée sur le champ PLU
        public String getHtml() {
            if (ean == null && label.contains("<span")) return label;

            // Si PLU est renseigné, c'est une vente au poids
            if (plu != null && !plu.isEmpty()) {
                String weightFormatted = String.format("%.3f", quantity).replace(".", ",");
                return String.format("<span class='qty'>%s kg</span> %s", weightFormatted, label);
            }
            // Sinon, vente à l'unité
            else {
                String qtyClass = (quantity == 1.0) ? "qty unit-qty" : "qty";
                String qtyDisplay = (quantity == Math.floor(quantity)) ? String.format("%.0f", quantity) : String.format("%.2f", quantity).replace(".", ",");
                return String.format("<span class='%s'>x%s</span> %s", qtyClass, qtyDisplay, label);
            }
        }

        public String getPriceFormatted() {
            return String.format("%.2f", getTotalPrice()).replace(".", ",");
        }

        public String getModifierLabel() {
            return modifierLabel;
        }

        public boolean isNegative() {
            return getTotalPrice() < 0;
        }

        public boolean getNegative() {
            return isNegative();
        }
    }
}