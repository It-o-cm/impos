package com.intermarche.pos.ui.customer;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Locale;

/**
 * Edits the sale NIF without entering customer creation or invoicing
 * (LC-08-06-02/-03).
 *
 * <p>Where the store requires a NIF (LC-08-06-01), the payment screen sends the
 * cashier here first, with {@code next=pay}, and an empty NIF is refused; where
 * it checks Portuguese NIFs (LC-08-06-04), a malformed one is refused with the
 * rule it breaks. A NIF scanned while the screen is open (LC-08-06-05) appears
 * in the field through {@code /sale-tax-id/scanned}.
 */
@Path("/sale-tax-id")
@DrawerMustBeClosed
public class SaleTaxIdResource {

    /** Current cart and payment boundary. */
    @Inject PosState state;
    /** Existing inherited anonymous fiscal identity. */
    @Inject PosSettingsService settings;
    /** Same draft funnel as ordinary cart mutations; training writes nothing. */
    @Inject TicketPersistenceService persistence;
    /** Translated entry and refusal labels. */
    @Inject Messages i18n;
    /** Existing tactile keyboard pattern, dedicated to this sale-only field. */
    @Inject @Location("sale-tax-id") Template page;

    /**
     * Proposes the administered default only until the operator has made a choice.
     * @return writable sale identity, including an explicitly cleared empty choice
     */
    String proposed() {
        if (state.ticket.customerTaxId != null) return state.ticket.customerTaxId;
        String value = settings.defaultTaxId();
        return value == null ? "" : value.trim();
    }

    /**
     * Renders the mask without changing the ticket or reserving a document number.
     * @param next "pay" when the payment screen sent the cashier here (LC-08-06-01)
     * @return the sale NIF entry screen (LC-08-06-02)
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show(@QueryParam("next") String next) {
        return render(proposed(), "", next);
    }

    /**
     * Tells the open entry screen which NIF a scan recorded, and how many scans
     * did so (LC-08-06-05).
     * @return the scan counter and the sale NIF, empty when none is recorded
     */
    @GET
    @Path("/scanned")
    @Produces(MediaType.APPLICATION_JSON)
    public java.util.Map<String, Object> scanned() {
        String value = state.ticket.customerTaxId;
        return java.util.Map.of("scans", state.ticket.customerTaxIdScans, "value", value == null ? "" : value);
    }

    /**
     * Renders the entry screen with its value, its message and where it goes next.
     * @param value the NIF shown in the field
     * @param error the refusal to show, empty when none
     * @param next "pay" to continue to the payment screen, anything else for the sale
     * @return the sale NIF entry screen
     */
    private TemplateInstance render(String value, String error, String next) {
        return page.data("value", value).data("error", error).data("next", toPayment(next) ? "pay" : "")
                .data("scans", state.ticket.customerTaxIdScans)
                .data("scanHint", !settings.taxIdScanPattern().isEmpty());
    }

    /**
     * Tells whether the screen was opened on the way to the payment screen.
     * @param next the posted or requested continuation
     * @return true when the payment screen comes next
     */
    private static boolean toPayment(String next) {
        return "pay".equals(next);
    }

    /**
     * Saves an identifier or clears it before payment; never edits a closed sale.
     * @param taxId tactile entry, optional and limited to the existing 30-character fiscal field
     * @param next "pay" to continue to the payment screen once saved (LC-08-06-01)
     * @return the ordinary sale or the payment screen, or the unchanged entry screen on refusal (LC-08-06-03)
     */
    @POST
    @Produces(MediaType.TEXT_HTML)
    public Object save(@FormParam("taxId") String taxId, @FormParam("next") String next) {
        synchronized (state) {
            if (state.payment.paymentInProgress || state.payment.transactionComplete) {
                return render(proposed(), i18n.get("op.terminezAnnulezTicketAbord"), next);
            }
            String typed = taxId == null ? "" : taxId.trim().toUpperCase(Locale.ROOT);
            // LC-08-06-03: keep foreign identifiers, but never accept printer directives or control characters.
            if (!typed.matches("[A-Z0-9 ./-]{0,30}")) {
                return render(typed, i18n.get("saletaxid.invalid"), next);
            }
            // LC-08-06-01: where the store requires a NIF, an empty one settles nothing.
            if (typed.isEmpty() && settings.taxIdRequired()) {
                return render(typed, i18n.get("saletaxid.required"), next);
            }
            // LC-08-06-04: a Portuguese NIF must be legally constructed.
            if (!TaxIdRules.acceptable(typed, settings.taxIdPortugueseCheck())) {
                return render(typed, i18n.get("saletaxid.invalidPt"), next);
            }
            state.ticket.customerTaxId = typed;
            persistence.syncDraft(state);
            state.touch();
            return Response.seeOther(URI.create(toPayment(next) ? "/pay" : "/")).build();
        }
    }
}
