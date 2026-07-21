package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "ticket_payments")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "payment_method", discriminatorType = DiscriminatorType.STRING, length = 20)
public abstract class TicketPayment extends BaseEntity {

    @Column(name = "payment_index", nullable = false)
    public int paymentIndex;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal amount;

    protected TicketPayment() {}

    public TicketPayment(BigDecimal amount) {
        this.amount = amount;
    }

    @Override
    public int getChecksum() {
        return Objects.hash(paymentIndex, amount);
    }

    // --- Interface Factory imbriquée ---

    public interface Factory {
        /**
         * La clé unique identifiant ce mode de paiement (ex: "CARD").
         */
        String getKey();

        /**
         * Crée l'entité JPA correspondante.
         */
        TicketPayment create(BigDecimal amount, BigDecimal tendered);
    }
}