package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;

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
 */
@ApplicationScoped
@Priority(1)
public class DepositVoucherScanHandler implements ScanContext.ScanHandler {

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

            BigDecimal amount = type.extractAmount(ctx.code);
            if (amount == null || amount.signum() <= 0) {
                state.ticket.setError("BON DE CONSIGNE ILLISIBLE");
                ctx.handled = true;
                return;
            }
            // Deposit refunds are out of VAT scope: zero rate.
            state.ticket.addItem(null, null, type.label.toUpperCase(),
                    amount.negate(), BigDecimal.ONE, BigDecimal.ZERO);
            ctx.handled = true;
            return;
        }
    }
}
