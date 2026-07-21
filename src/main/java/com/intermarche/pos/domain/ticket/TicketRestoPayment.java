package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

@Entity
@DiscriminatorValue("TR")
public class TicketRestoPayment extends TicketPayment {

    protected TicketRestoPayment() {}
    public TicketRestoPayment(BigDecimal amount) { super(amount); }

    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {
        @Override
        public String getKey() { return "TR"; }

        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new TicketRestoPayment(amount);
        }
    }
}