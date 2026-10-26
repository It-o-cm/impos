package com.intermarche.pos.ui.scanner;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Priority(100) // Exécuté en tout dernier
public class UnknownScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * The printer, for the unknown-article slip the island may ask for
     * (BO-03-10-05).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /**
     * The back-office parameters: whether an unknown code chains to the direct
     * entry by family or code (BO-10-02-14).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The electronic journal the unknown code is kept in (BO-04-01-34). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** The screen the unknown code chains to: the direct entry (BO-10-02-14). */
    static final String MANUAL_ENTRY_PATH = "/manual?unknown=";

    /**
     * Stamps the "unknown code" error onto the ticket when no earlier link of
     * the chain recognized the scanned code, and prints the slip where the
     * island asks for one (BO-03-10-05).
     *
     * @param ctx the scan context carrying the code and the register state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // Si on arrive ici et que la caisse est vérouillée, on ne fait rien
        // (pas d'erreur "Code Inconnu" sur l'écran de lock)
        if (ctx.state.isLocked()) return;

        // Through setError, never by assigning transientError: only setError
        // calls onChange(), which bumps the polling version. Assigning the
        // field left the version untouched, /ticket-fragment answered
        // "changed: false" and the message never reached the screen.
        // BO-10-02-14: where the shop asks for it, the message names an unknown
        // ARTICLE and the register proposes the way out — the direct entry, where
        // the cashier sells it by its family or keys its code. The screen is
        // reached through the one-shot function path the sale poll follows, the
        // same road a scanned function barcode takes.
        if (posSettingsService != null && posSettingsService.unknownScanManualEntry()) {
            ctx.state.ticket.setError(i18n.format("op.articleInconnuSaisie", ctx.code));
            ctx.state.functionPath = MANUAL_ENTRY_PATH
                    + java.net.URLEncoder.encode(ctx.code, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            ctx.state.ticket.setError(i18n.format("op.codeInconnu", ctx.code));
        }
        // BO-03-10-05: the paper that leaves the till with the code on it. The
        // printer answers zero where the island asks for none, so this handler
        // does not have to know what the island decided — and the message on
        // the screen is stamped either way, before the paper and not instead
        // of it.
        if (ticketPrinterService != null) {
            ticketPrinterService.printUnknownArticleTicket(ctx.code);
        }
        journal(ctx);
        ctx.handled = true;
    }

    /**
     * Keeps the unknown code in the electronic journal (BO-04-01-34), so the
     * back office can list the tickets during which a code nothing answers was
     * scanned, and read that code. The ticket under way is named when it
     * exists; a code scanned before the first article is stamped with the
     * ticket's number once the ticket is created.
     *
     * @param ctx the scan context
     */
    private void journal(ScanContext ctx) {
        if (technicalEventService == null) {
            return;
        }
        String ticketNumber = null;
        if (ctx.state.payment.ticketDbId != null) {
            com.intermarche.pos.domain.sale.Ticket draft =
                    com.intermarche.pos.domain.sale.Ticket.findById(ctx.state.payment.ticketDbId);
            ticketNumber = draft == null ? null : draft.ticketNumber;
        }
        com.intermarche.pos.domain.session.TechnicalEvent event = technicalEventService.record(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.UNKNOWN_ARTICLE_SCANNED,
                "Code inconnu " + ctx.code, ctx.state.auth.operatorBadgeId, ticketNumber, null, ctx.code);
        ctx.state.ticket.awaitTicket(event);
    }
}
