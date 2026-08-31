package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.ticket.TicketParkingService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Recognizes a PARKED-TICKET NUMBER pushed on the scan bus (LC-04-02-01):
 * the code printed on the parked receipt, shaped {@code <terminal>-<8
 * digits>} — a domain disjoint from every EAN, voucher and fidelity pattern
 * (no other scanned code carries a dash). Resolution is register-local and
 * status-guarded, so a closed or foreign number answers the same message as
 * an unknown one; the resume itself reuses the guarded parking service
 * (refused over a non-empty cart, with its own message).
 */
@ApplicationScoped
@Priority(1)
public class ParkedTicketScanHandler implements ScanContext.ScanHandler {

    /** The parking machinery — shared with the list-based resume. */
    @Inject
    TicketParkingService ticketParkingService;

    /**
     * Handles a scan by attempting to resume the parked ticket it names.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state.isLocked()) return;
        String code = ctx.code;
        if (code == null || !code.matches("[A-Za-z0-9]+-\\d{8}")) return;

        String error = ticketParkingService.resumeByNumber(code);
        if (error != null) {
            ctx.state.ticket.setError(error);
        }
        ctx.state.touch();
        ctx.handled = true;
    }
}
