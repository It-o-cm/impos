package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

@Entity
@DiscriminatorValue("CHEQUE")
public class ChequePayment extends TicketPayment {

    protected ChequePayment() {}
    public ChequePayment(BigDecimal amount) { super(amount); }

    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {
        @Override
        public String getKey() { return "CHEQUE"; }

        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new ChequePayment(amount);
        }
    }
}