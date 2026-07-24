package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;

/**
 * A card (CB) payment.
 * <p>
 * Semantic contract:
 * <ul>
 *   <li>Registered through {@code PaymentService.processCard}. Since phase 6,
 *       when {@code pos.tpe.virtual} is true (default), the amount first
 *       waits on the virtual payment terminal ({@code pendingCardAmount});
 *       this entity is only created on the terminal's <em>accept</em>
 *       decision — a refuse or a register-side cancel never creates one.</li>
 *   <li>Card payments never open the drawer (no cash handled).</li>
 *   <li>An overpayment by card is not rendered as change: card amounts are
 *       capped by the caller at the remaining due.</li>
 * </ul>
 * Carries no field of its own: the applied amount and registration order
 * live on {@link TicketPayment}, and the method identity is the JPA
 * discriminator value "CARD" (single-table inheritance).
 */
@Entity
@DiscriminatorValue("CARD")
public class CardPayment extends TicketPayment {

    /**
     * Default constructor for JPA.
     */
    protected CardPayment() {}

    /**
     * Creates a card payment with its applied amount.
     *
     * @param amount the amount applied to the ticket
     */
    public CardPayment(BigDecimal amount) { super(amount); }

    /**
     * Factory discovered by CDI under the "CARD" key, used by the draft
     * persistence when saving payment entries and by the store-node sync
     * ingestion when rebuilding pushed tickets.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the card payment method.
         *
         * @return the method key "CARD"
         */
        @Override
        public String getKey() { return "CARD"; }

        /**
         * Creates a card payment entity.
         *
         * @param amount the amount applied to the ticket
         * @param tendered ignored (no change on card payments)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new CardPayment(amount);
        }
    }
}
