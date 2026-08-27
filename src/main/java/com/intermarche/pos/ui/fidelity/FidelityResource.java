package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource of the manual fidelity-card entry page — the fallback
 * when the card does not scan (the scan handler is the primary path). Class
 * is {@code @DrawerMustBeClosed} like the other sale screens: attaching a
 * card is a sale gesture, not a drawer one.
 */
@Path("/")
@DrawerMustBeClosed
public class FidelityResource {

    @Inject Template fidelity;
    @Inject Template main;
    @Inject FidelityService fidelityService;
    @Inject
    PosState state;

    /**
     * Returns the home page.
     *
     * @return the main page
     */
    private TemplateInstance home() {
        return main.data("state", state);
    }

    // --- Vue ---

    /**
     * Shows the manual card-entry page.
     *
     * @return the fidelity page
     */
    @GET
    @Path("/fidelity") // Chemin complet
    public TemplateInstance fidelityPage() {
        // In-store consultation (imfid spec §4): balance and history of the
        // attached card, assembled server-side; degraded = a message.
        return fidelity.data("state", state)
                .data("lookup", null)
                .data("consultation", fidelityService.loadConsultation(state));
    }

    // --- Action ---

    /**
     * Attaches the typed card and returns to the home page.
     *
     * @param card the typed card number
     * @return the main page
     */
    @POST
    @Path("/action/fidelity") // Chemin complet
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance validateFidelity(@FormParam("card") String card) {
        fidelityService.validateCard(state, card);
        return home();
    }

    /**
     * Searches cards by holder identity (addendum §3) and re-renders the
     * fidelity page with the matches. ONE criterion per call, chosen by the
     * mode the operator picked: {@code tel}, {@code email} or {@code name}.
     * In name mode the single typed value is split on its FIRST space —
     * "DURAND JACQUES" searches name=DURAND, firstName=JACQUES; a compound
     * last name must therefore be searched without first name.
     *
     * @param mode the search mode (tel | email | name)
     * @param value the operator's input, as typed (imfid normalizes)
     * @return the fidelity page carrying the lookup outcome
     */
    @POST
    @Path("/action/fidelity-lookup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance lookupFidelity(@FormParam("mode") String mode,
                                           @FormParam("value") String value) {
        String phone = null, email = null, name = null, firstName = null;
        String input = value != null ? value.trim() : "";
        if ("tel".equals(mode)) {
            phone = input;
        } else if ("email".equals(mode)) {
            email = input;
        } else {
            int space = input.indexOf(' ');
            if (space > 0) {
                name = input.substring(0, space);
                firstName = input.substring(space + 1).trim();
            } else {
                name = input;
            }
        }
        FidelityService.LookupView lookup =
                fidelityService.lookupCards(phone, email, name, firstName);
        return fidelity.data("state", state)
                .data("lookup", lookup)
                .data("consultation", fidelityService.loadConsultation(state));
    }

    /**
     * Attaches a card selected in the lookup list (addendum §4) and returns
     * to the sale screen; a refused status (RESILIATED) re-renders the page
     * with the refusal. The card REPLACES any previously attached one, and
     * the found number then flows exactly like a scanned card.
     *
     * @param card the selected card number
     * @param lastName the holder's last name, echoed from the lookup
     * @param firstName the holder's first name, echoed from the lookup
     * @param status the account status, echoed from the lookup
     * @return the main page on success, or the fidelity page with the refusal
     */
    @POST
    @Path("/action/fidelity-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance selectFidelity(@FormParam("card") String card,
                                           @FormParam("lastName") String lastName,
                                           @FormParam("firstName") String firstName,
                                           @FormParam("status") String status) {
        String refusal = fidelityService.attachLookedUpCard(state, card, lastName, firstName, status);
        if (refusal != null) {
            FidelityService.LookupView lookup = new FidelityService.LookupView();
            lookup.message = refusal;
            return fidelity.data("state", state)
                    .data("lookup", lookup)
                    .data("consultation", fidelityService.loadConsultation(state));
        }
        return home();
    }
}