package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.Product;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A persisted ticket line.
 * <p>
 * Phase 0 lot 2: each line carries the stable {@code lineUid} of its in-memory
 * counterpart (the future contractual lineId toward the valuation engine) and
 * snapshots of the EAN / PLU as they were entered, so a draft can be restored
 * faithfully after a register restart. The product reference becomes nullable:
 * unknown-item and deposit-return lines have no catalog product.
 * <p>
 * The {@code lineUid} is a single identity with three lives: in-memory cart
 * uid (TicketItem.uid), store-sync key (refund lines reference their original
 * line by uid, since database ids are register-local), and the contractual
 * lineId of the phase 7 valuation exchanges. The draft sync reconciles lines
 * by uid (update in place, orphan removal for the gone ones).
 * <p>
 * Merge policy at cart level (phase 3): only unmodified unit EAN lines at the
 * same price merge; weighed lines (one weighing = one line), price-embedded
 * scale stickers (no code carried), deposit returns and negative lines never
 * do. {@link #modifierLabel} and {@link #originalUnitPrice} persist an
 * endorsed price modification so it survives a restart without being asked
 * again (debt sweep).
 */
@Entity
@Table(name = "ticket_lines")
public class TicketLine extends BaseEntity {

    /** The 1-based position of the line on the ticket. */
    @Column(name = "line_number", nullable = false)
    public int lineNumber;

    /**
     * The stable unique identifier of the line, shared with the in-memory cart
     * (TicketItem.uid) and reused as the contractual lineId in phase 7.
     */
    @Column(name = "line_uid", length = 36)
    public String lineUid;

    /**
     * The catalog product, or null for unknown-item and deposit-return lines.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    public Product product;

    /** The EAN code as entered at sale time, or null. */
    @Column(name = "ean", length = 13)
    public String ean;

    /** The PLU code as entered at sale time (weighed sale), or null. */
    @Column(name = "plu", length = 10)
    public String plu;

    /** The label of the line as shown on the ticket. */
    @Column(name = "product_label", nullable = false)
    @NotNull
    public String productLabel;

    /** The quantity (units, or kg for weighed lines). */
    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    @NotNull
    public BigDecimal quantity;

    /** The unit price including tax. */
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal unitPrice;

    /** The VAT rate captured at sale time (e.g. 0.2000 for 20%). */
    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    @NotNull
    public BigDecimal vatRate;

    /** The price-modification label ("REMISE -10%"...), or null when untouched. */
    @Column(name = "modifier_label", length = 50)
    public String modifierLabel;

    /** The catalog unit price before modification, or null when untouched. */
    @Column(name = "original_unit_price", precision = 19, scale = 4)
    public BigDecimal originalUnitPrice;

    /** The line total including tax. */
    @Column(name = "total_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalPrice;

    /** True for deposit-return lines. */
    @Column(name = "is_deposit")
    public boolean deposit;

    /**
     * Returns the line total formatted for display (2 decimals, French comma).
     *
     * @return the formatted line total
     */
    public String getTotalFormatted() {
        if (this.totalPrice == null) return "0,00";
        BigDecimal rounded = this.totalPrice.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    /**
     * Returns the quantity formatted for display: kilograms for weighed lines,
     * a unit count otherwise.
     *
     * @return the formatted quantity
     */
    public String getFormattedQuantity() {
        if (this.quantity == null) return "";

        boolean isWeight = (this.product != null && this.product.plu != null && !this.product.plu.isEmpty());

        if (isWeight) {
            return String.format("%.3f kg", this.quantity).replace(".", ",");
        } else {
            if (this.quantity.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                return String.format("x%.0f", this.quantity);
            }
            return "x" + this.quantity.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * Returns the audit checksum of the line, tolerant to the absence of a
     * catalog product (unknown items, deposit returns).
     *
     * @return a hash of the identifying and financial fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(lineNumber, product != null ? product.id : null, quantity, totalPrice);
    }
}
