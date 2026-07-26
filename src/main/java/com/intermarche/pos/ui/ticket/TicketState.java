package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/**
 * In-memory state of the ticket being built at the register.
 * <p>
 * All monetary amounts and quantities are held as {@link BigDecimal} (phase 0):
 * unit prices and totals use up to 4 decimals internally, displays are rounded
 * to 2 decimals. Physical weights coming from the scale remain double at the
 * hardware boundary and are converted to BigDecimal when a line is created.
 * <p>
 * Phase 1: each line carries the VAT rate captured at sale time, and the
 * ticket total is the sum of the line totals rounded to the cent — the same
 * rule the fiscal persistence applies, so the displayed, charged and persisted
 * amounts are identical.
 * <p>
 * The item uid minted here is the FIRST LIFE of the line identity: it
 * becomes the persisted lineUid, the sync key and the future valuation
 * lineId — same string end to end. Merge policy lives at addItem: only an
 * unmodified unit EAN line at the same price absorbs a new scan; anything
 * weighed, negative, price-modified or code-less starts its own line. The
 * transient scanned-sticker set (anti double-scan of price-embedded 2x
 * labels) is deliberately in-memory only: a restart forgets it, an
 * accepted limit.
 */
public class TicketState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Parent POS state, used to propagate change notifications. Transient: not serialized. */
    private transient PosState parent;

    /** The ordered list of ticket lines. */
    public List<TicketItem> items = new ArrayList<>();

    /**
     * Price-embedded scale-label codes already scanned on this ticket, to
     * refuse the accidental double scan of one physical sticker (in-memory
     * only: cleared with the ticket, empty again after a restart recovery).
     */
    public transient Set<String> scannedStickerCodes = new HashSet<>();

    /** Running total of the ticket (tax included), unrounded accumulation. */
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /** Last weight reported by the scale (kg). Hardware boundary value, kept as double. */
    public double currentWeight = 0.0;

    /** Last weight actually used for a weighed line, for duplicate-weighing detection. */
    public double lastRecordedWeight = Double.NaN;

    /** Transient error message shown once on the ticket area. */
    public String transientError = null;

    /**
     * Attaches the parent POS state so that mutations can bump its version.
     *
     * @param parent the owning POS state
     */
    public void setParent(PosState parent) {
        this.parent = parent;
    }

    /**
     * Notifies the parent state that this ticket changed (polling version bump).
     */
    void onChange() {
        if (this.parent != null) {
            this.parent.touch();
        }
    }

    /**
     * Adds an item to the ticket.
     * <p>
     * Phase 3 merge rules: only unit EAN lines merge, and only into a line
     * that carries no price modification, is not negative, and has the same
     * unit price as the incoming item. Weighed lines (PLU) never merge — each
     * weighing is its own line — and neither do deposit or in-store sticker
     * lines (no code carried).
     *
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param label the display label of the line
     * @param unitPrice the unit price including tax
     * @param qtyToAdd the quantity to add (units, or kg for weighed items)
     * @param vatRate the VAT rate captured at sale time (e.g. 0.2000), or null for 0%
     */
    public void addItem(String ean, String plu, String label, BigDecimal unitPrice, BigDecimal qtyToAdd, BigDecimal vatRate) {
        // Only unit EAN sales are mergeable (weighed lines: one line per weighing)
        boolean mergeable = (plu == null || plu.isEmpty()) && ean != null && !ean.isEmpty();

        if (mergeable) {
            for (TicketItem item : items) {
                boolean sameEan = item.ean != null && item.ean.equals(ean)
                        && (item.plu == null || item.plu.isEmpty());
                if (!sameEan) continue;

                boolean unmodified = item.modifierLabel == null
                        && item.unitPrice.compareTo(item.originalUnitPrice) == 0;
                boolean samePrice = item.unitPrice.compareTo(unitPrice) == 0;
                boolean positive = item.getTotalPrice().signum() >= 0;

                if (unmodified && samePrice && positive) {
                    item.quantity = item.quantity.add(qtyToAdd);
                    recomputeTotal();
                    if (parent != null) parent.lastEnteredItemId = item.uid;
                    onChange();
                    return;
                }
                // A matching EAN that cannot merge (modified price, markdown...)
                // falls through: a distinct line is created below.
                break;
            }
        }

        TicketItem newItem = new TicketItem(ean, plu, label, unitPrice, qtyToAdd, vatRate);
        items.add(newItem);
        recomputeTotal();

        if (parent != null) parent.lastEnteredItemId = newItem.uid;

        onChange();
    }

    /**
     * Removes the last line of the ticket, if any, and recomputes the total.
     */
    public void removeLastItem() {
        if (!items.isEmpty()) {
            items.remove(items.size() - 1);
            recomputeTotal();
            onChange();
        }
    }

    /**
     * Removes the line identified by its uid, if present, and recomputes the total.
     *
     * @param uid the unique identifier of the line to remove
     */
    public void removeItemById(String uid) {
        if (uid == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uid.equals(uid)) {
                items.remove(i);
                recomputeTotal();
                onChange();
                return;
            }
        }
    }

    /**
     * Recomputes the ticket total as the sum of the line totals rounded to the
     * cent — the fiscal aggregation rule shared with the persistence layer.
     */
    public void recomputeTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (TicketItem item : items) {
            total = total.add(item.getTotalPrice().setScale(2, RoundingMode.HALF_UP));
        }
        this.totalAmount = total;
    }

    /**
     * Clears the whole ticket state (lines, total, weights, error).
     */
    public void clear() {
        items.clear();
        scannedStickerCodes.clear();
        totalAmount = BigDecimal.ZERO;
        currentWeight = 0.0;
        lastRecordedWeight = Double.NaN;
        transientError = null;
        onChange();
    }

    /**
     * Sets a transient error message shown on the ticket area.
     *
     * @param err the error message
     */
    public void setError(String err) {
        this.transientError = err;
        onChange();
    }

    /**
     * Records the current weight reported by the scale.
     *
     * @param w the weight in kg
     */
    public void setWeight(double w) {
        this.currentWeight = w;
        onChange();
    }

    /**
     * Returns the ticket total formatted for display (2 decimals, French comma).
     *
     * @return the formatted total
     */
    public String getTotalFormatted() { return formatPrice(totalAmount); }

    /**
     * Returns the running total of the ticket (tax included).
     *
     * @return the total amount
     */
    public BigDecimal getTotalAmount() { return totalAmount; }

    /**
     * Returns the ticket lines.
     *
     * @return the list of lines
     */
    public List<TicketItem> getItems() { return items; }

    /**
     * Formats a monetary value with 2 decimals and a French comma separator.
     *
     * @param value the value to format
     * @return the formatted string
     */
    private String formatPrice(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    // --- INNER CLASS: TICKET ITEM ---

    /**
     * A single line of the in-memory ticket.
     */
    public static class TicketItem implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Unique, stable identifier of the line — also the contractual lineId toward external services. */
        public String uid;

        /** The EAN code, or null (direct entry, deposit return). */
        public String ean;

        /** The PLU code, or null. A non-empty PLU denotes a weighed line. */
        public String plu;

        /** The display label. */
        public String label;

        /** The unit price including tax. */
        public BigDecimal unitPrice;

        /** The quantity (units, or kg for weighed lines). */
        public BigDecimal quantity;

        /** The unit price before any price modification, for display and audit. */
        public BigDecimal originalUnitPrice = BigDecimal.ZERO;

        /** The VAT rate captured at sale time (e.g. 0.2000 for 20%). */
        public BigDecimal vatRate = BigDecimal.ZERO;

        /** The label of the applied price modification, or null. */
        public String modifierLabel = null;

        /**
         * Creates a ticket line.
         *
         * @param ean the EAN code, or null
         * @param plu the PLU code, or null
         * @param label the display label
         * @param unitPrice the unit price including tax
         * @param quantity the quantity
         * @param vatRate the VAT rate captured at sale time, or null for 0%
         */
        public TicketItem(String ean, String plu, String label, BigDecimal unitPrice, BigDecimal quantity, BigDecimal vatRate) {
            this.uid = UUID.randomUUID().toString();
            this.ean = ean;
            this.plu = plu;
            this.label = label;
            this.unitPrice = unitPrice;
            this.originalUnitPrice = unitPrice;
            this.quantity = quantity;
            this.vatRate = vatRate != null ? vatRate : BigDecimal.ZERO;
        }

        /**
         * Default constructor for serialization frameworks.
         */
        public TicketItem() {}

        /**
         * Returns the line total (unit price times quantity).
         *
         * @return the line total including tax
         */
        public BigDecimal getTotalPrice() { return unitPrice.multiply(quantity); }

        /**
         * Returns the HTML fragment displaying the quantity and label of the line.
         * <p>
         * A line carrying a PLU is displayed as a weighed line (kg, 3 decimals);
         * otherwise as a unit line (integer quantity when whole).
         *
         * @return the HTML fragment for the ticket display
         */
        public String getHtml() {
            if (ean == null && label.contains("<span")) return label;

            // A filled PLU denotes a weighed sale
            if (plu != null && !plu.isEmpty()) {
                String weightFormatted = String.format("%.3f", quantity).replace(".", ",");
                return String.format("<span class='qty'>%s kg</span> %s", weightFormatted, label);
            }
            // Unit sale
            else {
                boolean isOne = quantity.compareTo(BigDecimal.ONE) == 0;
                String qtyClass = isOne ? "qty unit-qty" : "qty";
                boolean isWhole = quantity.stripTrailingZeros().scale() <= 0;
                String qtyDisplay = isWhole
                        ? quantity.stripTrailingZeros().toPlainString()
                        : String.format("%.2f", quantity).replace(".", ",");
                return String.format("<span class='%s'>x%s</span> %s", qtyClass, qtyDisplay, label);
            }
        }

        /**
         * Returns the line total formatted for display (2 decimals, French comma).
         *
         * @return the formatted line total
         */
        public String getPriceFormatted() {
            BigDecimal rounded = getTotalPrice().setScale(2, RoundingMode.HALF_UP);
            return String.format("%.2f", rounded).replace(".", ",");
        }

        /**
         * Returns the label of the applied price modification, or null.
         *
         * @return the modifier label
         */
        public String getModifierLabel() {
            return modifierLabel;
        }

        /**
         * Indicates whether the line total is negative (deposit return, etc.).
         *
         * @return true if the line total is strictly negative
         */
        public boolean isNegative() {
            return getTotalPrice().signum() < 0;
        }

        /**
         * Qute-friendly alias of {@link #isNegative()}.
         *
         * @return true if the line total is strictly negative
         */
        public boolean getNegative() {
            return isNegative();
        }
    }
}
