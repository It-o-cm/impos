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
 * A scan is only treated as a voucher when a payment is in progress (the payment
 * screen is the current screen). If the voucher amount is encoded in the number,
 * the payment is registered automatically; otherwise (Catalina) the amount must be
 * entered, so the UI is switched to amount entry.
 */
@ApplicationScoped
@Priority(2)
public class VoucherScanHandler implements ScanContext.ScanHandler {

    @Inject
    VoucherService voucherService;

    /**
     * Handles a scan by attempting to recognize and apply a voucher.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        PosState state = ctx.state;
        if (state.isLocked()) return;

        // Un scan n'est traité comme un bon que si un paiement est en cours.
        if (state.payment.ticketDbId == null) return;

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
