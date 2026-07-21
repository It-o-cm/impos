package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

@Entity
@DiscriminatorValue("FIDELITY")
public class FidelityPayment extends TicketPayment {

    protected FidelityPayment() {}
    public FidelityPayment(BigDecimal amount) { super(amount); }

    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {
        @Override
        public String getKey() { return "FIDELITY"; }

        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new FidelityPayment(amount);
        }
    }
}