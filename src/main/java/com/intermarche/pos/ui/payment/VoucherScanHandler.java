package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.scanner.ScanContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 * Note the split with the DEPOSIT side: this handler only ever sees
 * non-deposit coupon types (a deposit voucher becomes a negative ticket
 * line in the cart phase, through its own handler) — the {@code depositLine}
 * flag on {@code CouponType} is what keeps the two families apart, and the
 * refund store vouchers close the loop by coming back through HERE as
 * STORE_VOUCHER payments.
 */
@ApplicationScoped
@Priority(2)
public class VoucherScanHandler implements ScanContext.ScanHandler {

    @Inject
    VoucherService voucherService;

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
        if (!state.payment.paymentInProgress) return;

        CouponType type = voucherService.resolveType(ctx.code);
        if (type == null) return;

        if (type.requiresManualAmount()) {
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
}
