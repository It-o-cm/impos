package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.service.CouponCheckService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.scanner.ScanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Recognizes a scanned voucher during an active payment.
 * <p>
 * A scan is only treated as a payment voucher when a payment is in progress —
 * detected by the explicit {@code paymentInProgress} flag (the previous
 * {@code ticketDbId} test broke when the draft started being created at the
 * first article: a Catalina scanned mid-cart would have registered a payment).
 * If the voucher amount is encoded in the number, the payment is registered
 * automatically; otherwise (Catalina) the amount must be entered, so the UI
 * is switched to amount entry.
 * <p>
 * Outside the payment phase, a code matching a payment voucher pattern is
 * consumed with the explicit message "BON VALABLE EN PHASE PAIEMENT" instead
 * of falling through to the misleading generic "CODE INCONNU".
 * <p>
 * Before any of that, the controls the range administers are applied
 * (BO-03-06-27/28/29/39/46/47/49/67): validity dates, point of sale, minimum
 * ticket total, already-used code. A range carrying no control says nothing and
 * changes nothing, which is why an unadministered register behaves exactly as
 * it did before.
 */
@ApplicationScoped
@Priority(2)
public class VoucherScanHandler implements ScanContext.ScanHandler {

    @Inject
    VoucherService voucherService;

    /** The administered controls of the barcode ranges (BO-03-06). */
    @Inject
    CouponCheckService couponCheckService;

    /**
     * Handles a scan by attempting to recognize and apply a payment voucher.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        PosState state = ctx.state;
        if (state.isLocked()) return;

        // A scan is treated as a payment voucher only while a payment is in progress.
        // Outside the payment phase, a code matching a payment voucher pattern is
        // still recognized, but only to tell the cashier the right moment: the
        // generic "CODE INCONNU" would be misleading (the code IS known).
        if (!state.payment.paymentInProgress) {
            CouponType matched = voucherService.resolveType(ctx.code);
            if (matched != null) {
                state.ticket.setError("BON VALABLE EN PHASE PAIEMENT");
                ctx.handled = true;
            }
            return;
        }

        CouponType type = voucherService.resolveType(ctx.code);
        if (type == null) return;

        if (refusedByControls(state, type, ctx)) return;

        if (type.amountSource == CouponType.AmountSource.REGISTRY) {
            // Registry-backed instruments never ask for an amount: the
            // registry knows the balance (phase: credit notes & gift cards).
            voucherService.applyRegistryVoucher(state, type, ctx.code);
        } else if (type.requiresManualAmount(ctx.code)) {
            state.payment.clearPendingVoucher();
            state.payment.voucherPanelOpen = true;
            state.payment.pendingVoucherTypeCode = type.code;
            state.payment.pendingVoucherLabel = type.label;
            state.payment.pendingVoucherNumber = ctx.code;
            state.payment.pendingVoucherNeedsAmount = true;
            state.touch();
        } else {
            voucherService.applyEncodedVoucher(state, type, ctx.code);
        }
        ctx.handled = true;
    }

    /**
     * Applies the controls the range administers and tells whether the code is
     * refused.
     *
     * <p>An informative level speaks and lets the voucher through; a blocking
     * level speaks and consumes the scan without registering anything.
     *
     * @param state the current POS state
     * @param type the range that recognised the code
     * @param ctx the scan context carrying the scanned code
     * @return true when the code must not be applied
     */
    private boolean refusedByControls(PosState state, CouponType type, ScanContext ctx) {
        List<CouponCheckService.Finding> findings =
                couponCheckService.check(type, ctx.code, state.ticket.totalAmount,
                        state.fidelity.active ? state.fidelity.label : null);
        AlertLevel level = couponCheckService.worst(findings);
        if (!level.speaks()) {
            return false;
        }
        state.ticket.setError(couponCheckService.message(findings));
        if (!level.blocks()) {
            return false;
        }
        ctx.handled = true;
        return true;
    }
}
