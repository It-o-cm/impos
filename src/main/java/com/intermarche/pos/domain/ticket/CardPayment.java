package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

@Entity
@DiscriminatorValue("CARD")
public class CardPayment extends TicketPayment {

    protected CardPayment() {}
    public CardPayment(BigDecimal amount) { super(amount); }

    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {
        @Override
        public String getKey() { return "CARD"; }

        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CardPayment(amount);
        }
    }
}