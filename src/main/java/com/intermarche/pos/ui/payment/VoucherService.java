package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Handles voucher payments: resolving a voucher number to its {@link CouponType}
 * and registering the resulting payment in the current payment state.
 */
@ApplicationScoped
public class VoucherService {

    private static final Logger LOG = Logger.getLogger(VoucherService.class);

    @Inject
    PaymentService paymentService;

    /**
     * Resolves a voucher number to the first active type whose pattern matches it.
     *
     * @param number the voucher number, scanned or typed
     * @return the matching type, or null if no active type recognizes the number
     */
    public CouponType resolveType(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        List<CouponType> types = CouponType.listActiveByPriority();
        for (CouponType type : types) {
            if (type.matches(number)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Registers a voucher payment for a recognized type whose amount is encoded in the number.
     *
     * @param state the current POS state
     * @param type the recognized coupon type
     * @param number the voucher number
     * @return true if the amount could be extracted and the payment was registered
     */
    public boolean applyEncodedVoucher(PosState state, CouponType type, String number) {
        if (type == null) {
            return false;
        }
        BigDecimal amount = type.extractAmount(number);
        if (amount == null) {
            LOG.warnf("Montant non extractible pour le bon %s (type %s)", number, type.code);
            return false;
        }
        registerPayment(state, type, number, amount.doubleValue());
        return true;
    }

    /**
     * Registers a voucher payment whose amount was entered manually by the cashier.
     *
     * @param state the current POS state
     * @param type the coupon type (may carry a number or be numberless)
     * @param number the voucher number, or null for a numberless voucher
     * @param amount the amount entered by the cashier
     */
    public void applyManualVoucher(PosState state, CouponType type, String number, double amount) {
        if (amount <= 0) {
            return;
        }
        registerPayment(state, type, number, amount);
    }

    /**
     * Registers the voucher payment into the payment state, capped at the remaining due.
     *
     * @param state the current POS state
     * @param type the coupon type used for the payment label
     * @param number the voucher number, or null when there is none
     * @param amount the requested voucher amount
     */
    private void registerPayment(PosState state, CouponType type, String number, double amount) {
        double remaining = state.getRemaining();
        double amountToPay = Math.min(amount, remaining);
        amountToPay = BigDecimal.valueOf(amountToPay).setScale(2, RoundingMode.HALF_UP).doubleValue();
        if (amountToPay <= 0) {
            return;
        }
        String label = (type != null) ? type.label : "Bon d'achat";
        paymentService.processVoucher(state, label, number, amountToPay);
    }
}
