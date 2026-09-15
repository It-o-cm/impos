package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.service.CouponCheckService;
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
 * <p>
 * All monetary amounts are {@link BigDecimal} end to end (phase 0).
 * <p>
 * Resolution walks the ACTIVE non-deposit coupon types by ascending
 * priority and the first matching pattern wins — priorities are therefore
 * the disambiguation order of overlapping formats, not decoration. ENCODED
 * types extract their amount from the number (first regex group = cents);
 * MANUAL types (Catalina) take the typed amount; the numberless GENERIC
 * type is the catch-all last resort. An overpaying voucher is capped at the
 * remaining due: vouchers never render change by store rule.
 */
@ApplicationScoped
public class VoucherService {

    private static final Logger LOGGER = Logger.getLogger(VoucherService.class);

    @Inject
    PaymentService paymentService;

    /** The administered controls of the barcode ranges (BO-03-06). */
    @Inject
    CouponCheckService couponCheckService;

    /**
     * Resolves a voucher number to the first active payment type whose
     * pattern matches it (deposit-return types are handled at scan time as
     * negative ticket lines, never as payments).
     *
     * @param number the voucher number, scanned or typed
     * @return the matching type, or null if no active type recognizes the number
     */
    public CouponType resolveType(String number) {
        LOGGER.info("Entering method resolveType with number: " + number);
        if (number == null || number.isBlank()) {
            LOGGER.info("Exiting method resolveType");
            return null;
        }
        List<CouponType> types = CouponType.listActivePaymentTypes();
        for (CouponType type : types) {
            if (type.matches(number)) {
                LOGGER.info("Exiting method resolveType");
                return type;
            }
        }
        LOGGER.info("Exiting method resolveType");
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
        LOGGER.info("Entering method applyEncodedVoucher with state: " + state + ", type: " + type + ", number: " + number);
        if (type == null) {
            LOGGER.info("Exiting method applyEncodedVoucher");
            return false;
        }
        BigDecimal amount = type.extractAmount(number);
        if (amount == null) {
            LOGGER.warnf("Montant non extractible pour le bon %s (type %s)", number, type.code);
            LOGGER.info("Exiting method applyEncodedVoucher");
            return false;
        }
        registerPayment(state, type, number, amount);
        LOGGER.info("Exiting method applyEncodedVoucher");
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
    public void applyManualVoucher(PosState state, CouponType type, String number, BigDecimal amount) {
        LOGGER.info("Entering method applyManualVoucher with state: " + state + ", type: " + type + ", number: " + number + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method applyManualVoucher");
            return;
        }
        registerPayment(state, type, number, amount);
        LOGGER.info("Exiting method applyManualVoucher");
    }

    /**
     * Registers the voucher payment into the payment state, capped at the remaining due.
     *
     * @param state the current POS state
     * @param type the coupon type used for the payment label
     * @param number the voucher number, or null when there is none
     * @param amount the requested voucher amount
     */
    /**
     * Redeems a registry-backed instrument (credit note or gift card): the
     * live balance is read from the registry, {@code min(balance, remaining
     * due)} is registered as the payment, and the balance is only DEBITED at
     * the fiscal moment of this sale (validateTicket) — a cancelled payment
     * never burns stored value. Refusals: unknown number, exhausted balance,
     * number already scanned on this sale (phase: credit notes & gift cards).
     *
     * @param state the current POS state
     * @param type the matched registry coupon type
     * @param number the scanned registry number
     */
    public void applyRegistryVoucher(PosState state, CouponType type, String number) {
        LOGGER.info("Entering method applyRegistryVoucher with state: " + state + ", type: " + type + ", number: " + number);
        com.intermarche.pos.domain.payment.StoredValue instrument =
                com.intermarche.pos.domain.payment.StoredValue.findByNumber(number);
        if (instrument == null) {
            state.ticket.setError("BON INCONNU AU REGISTRE");
            state.touch();
            LOGGER.info("Exiting method applyRegistryVoucher");
            return;
        }
        if (instrument.status == com.intermarche.pos.domain.payment.StoredValue.Status.EXHAUSTED
                || instrument.balance.signum() <= 0) {
            state.ticket.setError("BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ");
            state.touch();
            LOGGER.info("Exiting method applyRegistryVoucher");
            return;
        }
        boolean alreadyScanned = state.payment.payments.stream()
                .anyMatch(p -> number.equals(p.voucherNumber));
        if (alreadyScanned) {
            state.ticket.setError("BON DÉJÀ SCANNÉ SUR CETTE VENTE");
            state.touch();
            LOGGER.info("Exiting method applyRegistryVoucher");
            return;
        }
        registerPayment(state, type, number, instrument.balance);
        LOGGER.info("Exiting method applyRegistryVoucher");
    }

    /**
     * Registers the voucher payment into the payment state, capped at the
     * remaining due, and records the code so the duplicate control can refuse
     * it next time (BO-03-06-39/49).
     *
     * @param state the current POS state
     * @param type the coupon type used for the payment label, or null
     * @param number the voucher number, or null when there is none
     * @param amount the requested voucher amount
     */
    private void registerPayment(PosState state, CouponType type, String number, BigDecimal amount) {
        BigDecimal remaining = state.getRemaining();
        BigDecimal amountToPay = amount.min(remaining).setScale(2, RoundingMode.HALF_UP);
        if (amountToPay.signum() <= 0) {
            return;
        }
        String label = (type != null) ? type.label : "Bon d'achat";
        paymentService.processVoucher(state, label, number, amountToPay);
        couponCheckService.record(type, number);
    }
}
