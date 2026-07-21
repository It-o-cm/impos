package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.util.Objects;

@Entity
@DiscriminatorValue("CASH")
public class CashPayment extends TicketPayment {

    @Column(name = "tendered_amount", precision = 19, scale = 4)
    public BigDecimal tenderedAmount;

    protected CashPayment() {}

    public CashPayment(BigDecimal amount, BigDecimal tenderedAmount) {
        super(amount);
        this.tenderedAmount = tenderedAmount;
    }

    @Override
    public int getChecksum() {
        return Objects.hash(super.getChecksum(), tenderedAmount);
    }

    // --- Factory Interne ---

    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {
        @Override
        public String getKey() {
            return "CASH";
        }

        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CashPayment(amount, tendered != null ? tendered : amount);
        }
    }
}