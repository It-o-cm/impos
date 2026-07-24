package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A bank-cheque payment.
 * <p>
 * Semantic contract: the drawer opens at registration so the cashier can
 * store the cheque; the amount is taken as typed (a cheque above the
 * remaining due is a cashier decision, change is rendered in cash and
 * computed by the shared change logic of {@code PaymentService}). Carries no
 * field of its own: identity is the discriminator value "CHEQUE".
 */
@Entity
@DiscriminatorValue("CHEQUE")
public class ChequePayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected ChequePayment() {}

    /**
     * Creates a cheque payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public ChequePayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "CHEQUE" key, used by the draft
     * persistence and the store-node sync ingestion.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the cheque payment method.
         *
         * @return the method key "CHEQUE"
         */
        @Override
        public String getKey() { return "CHEQUE"; }

        /**
         * Creates a cheque payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (the applied amount is the cheque amount)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new ChequePayment(amount);
        }
    }
}
