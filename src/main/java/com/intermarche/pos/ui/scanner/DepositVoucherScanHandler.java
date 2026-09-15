package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.AlertLevel;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.service.CouponCheckService;
import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.List;

/**
 * Scan handler recognizing deposit-return vouchers (bons de déconsigne
 * printed by the reverse-vending machine) and adding them to the ticket as a
 * negative line.
 * <p>
 * Recognition and amount extraction reuse the {@link CouponType} machinery:
 * only active types flagged {@code depositLine} are considered, and the
 * amount must be encoded in the number. Runs before the payment-voucher and
 * EAN handlers; ignored during an active payment (a deposit is a sale line,
 * not a payment).
 * <p>
 * Mirror of {@code VoucherScanHandler}: the {@code depositLine} flag on
 * {@code CouponType} splits the voucher world in two, this handler owning
 * the CART phase (negative line, VAT 0, never merged) and the other the
 * PAYMENT phase — one voucher family can never cross into the other's
 * territory whatever the moment of the scan.
 * <p>
 * The controls the range administers apply here as they do on the payment side
 * (BO-03-06-27/28/29/39/46/47/49/67): a deposit voucher can be expired, meant
 * for another shop, or already handed in.
 */
@ApplicationScoped
@Priority(1)
public class DepositVoucherScanHandler implements ScanContext.ScanHandler {

    /** The administered controls of the barcode ranges (BO-03-06). */
    @Inject
    CouponCheckService couponCheckService;

    /**
     * Handles a scanned deposit-return voucher by adding its negative line.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        PosState state = ctx.state;
        if (state.isLocked()) return;
        if (state.payment.paymentInProgress) return;

        for (CouponType type : CouponType.<CouponType>listActiveDepositTypes()) {
            if (!type.matches(ctx.code)) continue;

            if (refusedByControls(state, type, ctx)) return;

            BigDecimal amount = type.extractAmount(ctx.code);
            if (amount == null || amount.signum() <= 0) {
                state.ticket.setError("BON DE CONSIGNE ILLISIBLE");
                ctx.handled = true;
                return;
            }
            // Deposit refunds are out of VAT scope: zero rate. The line
            // carries the SCANNED CODE as its identity — it is printed on the
            // voucher and is what the valuation engine receives; a line
            // without an EAN does not exist.
            state.ticket.addItem(ctx.code, null, type.label.toUpperCase(),
                    amount.negate(), BigDecimal.ONE, BigDecimal.ZERO);
            couponCheckService.record(type, ctx.code);
            ctx.handled = true;
            return;
        }
    }

    /**
     * Applies the controls the range administers and tells whether the voucher
     * is refused.
     *
     * <p>An informative level speaks and lets the line through; a blocking
     * level speaks and consumes the scan without adding anything.
     *
     * @param state the current POS state
     * @param type the range that recognised the code
     * @param ctx the scan context carrying the scanned code
     * @return true when the voucher must not be added
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
