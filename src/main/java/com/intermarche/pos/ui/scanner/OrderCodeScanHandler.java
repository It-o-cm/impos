package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.recall.BackOfficeDocumentClient;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Recognizes the BARCODE of a deposit slip printed by this register's store
 * ({@code LC-06-03-07}): a code shaped {@code <terminal>-CMD<digits>} names
 * an order created at a till, and the customer hands the slip back to reopen
 * the order — its articles loaded on the lane, the deposit already collected
 * credited as the "Reprise d'acompte" settlement, the balance to settle now.
 *
 * <p>THE SHAPE IS THE HANDLER'S, AND IT IS DISJOINT from every other family
 * of the chain: the {@code -CMD} marker never appears in a ticket number
 * ({@code -<8 digits>}), an EAN, a badge or a voucher. Only a TILL-CREATED
 * order claims it; a quote or a voucher the back office typed freely keeps
 * being recalled by hand on the /recall screen, which is where the back
 * office told the customer to key it.
 *
 * <p>Resolution is the node's, like every recall: the fetch asks the store
 * node for the document behind the code, and a code the node does not know
 * — or a lane with no store configured — falls through UNHANDLED, so the
 * unknown-code link answers exactly as before.
 */
@ApplicationScoped
@Priority(3)
public class OrderCodeScanHandler implements ScanContext.ScanHandler {

    /** The fetch to the store node's order store. */
    @Inject
    BackOfficeDocumentClient documentClient;

    /** The load of a recalled document into the current sale. */
    @Inject
    TicketService ticketService;

    /** The pattern of the external documents' codes (LC-06-02-01, -09). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The control of the selfscanning baskets (LC-06-05-03). */
    @Inject
    com.intermarche.pos.ui.recall.SelfScanService selfScanService;

    /**
     * Handles a scan by loading the order it names, when the store node
     * holds one behind that code.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.state.isLocked()) return;
        String code = ctx.code;
        if (code == null || !(code.matches("[A-Za-z0-9]+-CMD[0-9]+")
                || matches(code, posSettingsService.orderExternalCodePattern())
                || matches(code, posSettingsService.selfScanCodePattern())
                || matches(code, posSettingsService.qbustingCodePattern()))) return;
        if (!open(ctx.state, code)) {
            // Not this register's order to claim: the unknown-code link
            // answers below, with the slip the customer can still key.
            return;
        }
        ctx.handled = true;
    }

    /**
     * Loads the document the store node holds behind a code: a selfscanning
     * basket goes to its control, a quote or an order admitting a deposit
     * waits for the cashier's choice, anything else is loaded into the sale.
     * Shared with the administered order range (BO-03-06-58).
     *
     * @param state the register state
     * @param code the document's code
     * @return true when the node held a document behind the code
     */
    boolean open(com.intermarche.pos.ui.PosState state, String code) {
        RecalledDocument order = documentClient.fetch(code);
        if (order == null) {
            return false;
        }
        if (order.kind == RecalledDocument.Kind.SELF_SCAN) {
            // LC-06-05-03: a selfscanning basket is controlled before it is loaded,
            // on the screen the sale screen opens.
            selfScanService.start(state, order);
            state.selfScanRequested = true;
        } else if (order.offersDeposit()) {
            // LC-06-02-04 / -08: a quote or an order that admits a deposit waits
            // for the cashier's choice, on the screen the sale screen opens.
            state.pendingDocument = order;
            state.documentChoiceRequested = true;
        } else {
            ticketService.loadRecalledDocument(state, order);
        }
        state.touch();
        return true;
    }

    /**
     * Tells whether a code is shaped like the codes an administered pattern
     * describes: the quotes, orders and sale vouchers of the commercial
     * management tool (LC-06-02-01, -09), the baskets of the selfscanning
     * devices (LC-06-05-03) or those of the queue-busting handhelds
     * (BO-03-06-57).
     *
     * @param code the scanned code
     * @param pattern the administered regular expression, possibly blank
     * @return true when a pattern is administered and the code matches it
     */
    private static boolean matches(String code, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            return code.matches(pattern.trim());
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }
}
