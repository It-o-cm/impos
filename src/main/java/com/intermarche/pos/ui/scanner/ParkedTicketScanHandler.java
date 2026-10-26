package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.returnprocess.RefundService;
import com.intermarche.pos.ui.ticket.TicketParkingService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Recognizes a TICKET NUMBER pushed on the scan bus, shaped {@code
 * <terminal>-<8 digits>} — a domain disjoint from every EAN, voucher and
 * fidelity pattern (no other scanned code carries a dash). ONE owner of that
 * pattern dispatches by the resolved ticket's status: a PARKED number resumes
 * the cart (LC-04-02-01), a CLOSED number routes to the return (EXP-37) — the
 * number printed at the bottom of the sale ticket, scanned to start a return.
 * A separate handler on the same pattern would tie at this priority, which the
 * chain contract forbids, so the two live here. Resolution is register-local
 * and status-guarded: an unknown or foreign number answers the same message as
 * a stale one. Reaching a CLOSED ticket also honours the store boundary — a
 * ticket from another store is not in this register's database, so it never
 * resolves (another till and another day, both local, do).
 */
@ApplicationScoped
@Priority(1)
public class ParkedTicketScanHandler implements ScanContext.ScanHandler {

    /** The parking machinery — shared with the list-based resume. */
    @Inject
    TicketParkingService ticketParkingService;

    /** The return search, shared with the /return screen (EXP-37). */
    @Inject
    RefundService refundService;

    /** The prefix the printed ticket barcode carries (BO-03-06-55, BO-03-13-04). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Handles a scan by resuming the parked ticket it names, or routing a closed
     * sale ticket to the return.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state.isLocked()) return;
        String code = withoutTicketPrefix(ctx.code);
        if (code == null || !code.matches("[A-Za-z0-9]+-\\d{8}")) return;

        // EXP-37: a CLOSED sale ticket number scanned on the sale screen starts a
        // return — populate the return search and let the poll carry the screen to
        // /return, the same one-shot relay the badge-close uses.
        if (refundService.isReturnableTicket(code)) {
            ctx.state.refund.searchPattern = code;
            refundService.searchTickets(ctx.state);
            // BO-03-13-04: the scanned original ticket IS the one returned —
            // it is selected, and the return screen opens on its detail.
            if (ctx.state.refund.foundTickets.size() == 1) {
                refundService.selectTicket(ctx.state, ctx.state.refund.foundTickets.get(0).id);
            }
            ctx.state.returnByScanRequested = true;
            ctx.state.touch();
            ctx.handled = true;
            return;
        }

        String error = ticketParkingService.resumeByNumber(code);
        if (error != null) {
            ctx.state.ticket.setError(error);
        }
        ctx.state.touch();
        ctx.handled = true;
    }

    /**
     * Strips the administered prefix the printed ticket barcode carries
     * (BO-03-06-55), so the code read off a receipt is its ticket number
     * (BO-03-13-04).
     *
     * @param code the scanned code, possibly null
     * @return the code without the prefix, or the code itself when it does not carry it
     */
    private String withoutTicketPrefix(String code) {
        String prefix = posSettingsService == null ? null : posSettingsService.ticketBarcodePrefix();
        if (code == null || prefix == null || prefix.isBlank() || !code.startsWith(prefix.trim())) {
            return code;
        }
        return code.substring(prefix.trim().length());
    }
}
