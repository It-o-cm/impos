package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.Product;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Entity
@Table(name = "ticket_lines")
public class TicketLine extends BaseEntity {

    @Column(name = "line_number", nullable = false)
    public int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @NotNull
    public Product product;

    @Column(name = "product_label", nullable = false)
    @NotNull
    public String productLabel;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 3)
    @NotNull
    public BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4)
    @NotNull
    public BigDecimal vatRate;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalPrice;

    @Column(name = "is_deposit")
    public boolean deposit;

    public String getTotalFormatted() {
        if (this.totalPrice == null) return "0,00";
        BigDecimal rounded = this.totalPrice.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

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

    @Override
    public int getChecksum() {
        return Objects.hash(lineNumber, product.id, quantity, totalPrice);
    }
}