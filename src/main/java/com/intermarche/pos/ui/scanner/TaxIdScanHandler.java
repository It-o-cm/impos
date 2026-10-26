package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.customer.TaxIdRules;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Records the customer NIF a barcode carries — the social purchase card, for
 * one (LC-08-06-05) — exactly as if it had been keyed on the NIF CLIENT screen.
 *
 * <p>The barcode is recognised by the pattern the back office administers
 * ({@code customer.tax-id-scan-pattern}), whose first group names the NIF; no
 * pattern, no recognition, and a pattern the shop got wrong is ignored rather
 * than breaking the scan. The NIF goes through the same rules as a keyed one:
 * refused once the payment has started, refused when the store checks
 * Portuguese NIFs and this one is malformed (LC-08-06-04).
 *
 * <p>Inert while the register is locked, like every sale gesture.
 */
@ApplicationScoped
@Priority(1)
public class TaxIdScanHandler implements ScanContext.ScanHandler {

    /** The localised operator messages (LC-13-06). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The administered pattern and the Portuguese check. */
    @Inject
    PosSettingsService posSettingsService;

    /** Same draft funnel as the NIF CLIENT screen; training writes nothing. */
    @Inject
    TicketPersistenceService persistence;

    /**
     * Records the NIF of a recognised barcode on the running sale.
     *
     * @param ctx the token walking the chain
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled || ctx.state.isLocked()) {
            return;
        }
        String nif = recognise(ctx.code);
        if (nif == null) {
            return;
        }
        ctx.handled = true;
        synchronized (ctx.state) {
            if (ctx.state.payment.paymentInProgress || ctx.state.payment.transactionComplete) {
                ctx.state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            } else if (!nif.matches("[A-Z0-9 ./-]{1,30}")) {
                // The same characters as a keyed NIF: never a printer directive.
                ctx.state.ticket.setError(i18n.get("saletaxid.invalid"));
            } else if (!TaxIdRules.acceptable(nif, posSettingsService.taxIdPortugueseCheck())) {
                ctx.state.ticket.setError(i18n.get("saletaxid.invalidPt"));
            } else {
                ctx.state.ticket.customerTaxId = nif;
                ctx.state.ticket.customerTaxIdScans++;
                persistence.syncDraft(ctx.state);
                ctx.state.ticket.setNotice(i18n.format("op.nifScanne", nif));
            }
            ctx.state.touch();
        }
    }

    /**
     * Extracts the NIF a scanned code carries, as the administered pattern
     * reads it.
     *
     * @param code the scanned code
     * @return the NIF, upper-cased, or null when the code carries none
     */
    private String recognise(String code) {
        String pattern = posSettingsService.taxIdScanPattern();
        if (pattern.isEmpty() || code == null) {
            return null;
        }
        try {
            Matcher matcher = Pattern.compile(pattern).matcher(code.trim());
            if (!matcher.matches()) {
                return null;
            }
            String nif = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
            return nif == null || nif.isBlank() ? null : nif.trim().toUpperCase(Locale.ROOT);
        } catch (PatternSyntaxException e) {
            // A pattern the shop mistyped is an administration defect, not a lane
            // error: the code goes on to the other handlers.
            return null;
        }
    }
}
