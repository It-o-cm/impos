package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {

    public enum RefundStatus {
        OPEN, CLOSED
    }

    @Column(name = "status", nullable = false)
    @NotNull
    public RefundStatus status = RefundStatus.OPEN;

    @Column(name = "original_ticket_id", nullable = false)
    public Long originalTicketId;

    @Column(name = "creation_date", nullable = false)
    public LocalDateTime creationDate;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    public BigDecimal totalAmount;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "refund_id", nullable = false)
    public List<RefundLine> lines = new ArrayList<>();

    // Méthode helper pour calculer le total (côté serveur)
    public void calculateTotal() {
        this.totalAmount = lines.stream()
                .map(l -> l.price.multiply(l.quantity))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public int getChecksum() {
        return Objects.hash(originalTicketId, totalAmount, status);
    }
}