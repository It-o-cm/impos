package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.recall.SelfScanService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Scan handler for the articles the cashier RE-SCANS while controlling a
 * selfscanning basket (LC-06-05-04, -05).
 *
 * <p>Like the activation code of a dematerialised article, the shape tells
 * nothing: what tells is that the register is WAITING for the re-scan, because
 * a basket is under control. While it is, every code is the answer — the article
 * goes to the control, never to the sale the basket has not been loaded into
 * yet. Priority -1 so the wait is honoured before any shape is tested; it shares
 * that rank with the activation-code wait, the two waits never being open
 * together (a control holds no sale line to activate).
 */
@ApplicationScoped
@Priority(-1)
public class SelfScanControlScanHandler implements ScanContext.ScanHandler {

    /** Counts the re-scanned articles. */
    @Inject
    SelfScanService selfScanService;

    /**
     * Claims a scan while a selfscanning basket is under control.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state == null || ctx.state.selfScan == null) return;
        if (ctx.state.isLocked()) return;
        if (ctx.code == null || ctx.code.isBlank()) return;
        selfScanService.rescan(ctx.state, ctx.code);
        ctx.handled = true;
    }
}
