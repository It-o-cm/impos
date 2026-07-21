package com.intermarche.pos.domain.ticket;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.math.BigDecimal;

/**
 * A payment made with a voucher (gift voucher, loyalty cheque, Catalina, generic coupon).
 */
@Entity
@DiscriminatorValue("VOUCHER")
public class VoucherPayment extends TicketPayment {

    /**
     * The label of the voucher type as shown to the cashier (for example, "Chèque cadeau").
     */
    @Column(name = "voucher_label", length = 100)
    public String voucherLabel;

    /**
     * The voucher number, or null for a numberless voucher.
     */
    @Column(name = "voucher_number", length = 64)
    public String voucherNumber;

    /**
     * Default constructor required by JPA.
     */
    protected VoucherPayment() {}

    /**
     * Creates a voucher payment with its amount, type label and number.
     *
     * @param amount the paid amount
     * @param voucherLabel the voucher type label
     * @param voucherNumber the voucher number, or null when there is none
     */
    public VoucherPayment(BigDecimal amount, String voucherLabel, String voucherNumber) {
        super(amount);
        this.voucherLabel = voucherLabel;
        this.voucherNumber = voucherNumber;
    }

    /**
     * Factory creating {@link VoucherPayment} instances for the persistence layer.
     */
    @ApplicationScoped
    public static class Factory implements TicketPayment.Factory {

        /**
         * Returns the unique key identifying the voucher payment method.
         *
         * @return the method key "VOUCHER"
         */
        @Override
        public String getKey() {
            return "VOUCHER";
        }

        /**
         * Creates a voucher payment entity from an amount.
         *
         * @param amount the paid amount
         * @param tendered the tendered amount (unused for vouchers)
         * @return the created payment entity
         */
        @Override
        public TicketPayment create(BigDecimal amount, BigDecimal tendered) {
            return new VoucherPayment(amount, null, null);
        }
    }
}
