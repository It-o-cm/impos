package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the manual fidelity-card entry page — the fallback
 * when the card does not scan (the scan handler is the primary path). Class
 * is {@code @DrawerMustBeClosed} like the other sale screens: attaching a
 * card is a sale gesture, not a drawer one.
 */
@Path("/")
@DrawerMustBeClosed
public class FidelityResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FidelityResource.class);

    @Inject Template fidelity;
    @Inject FidelityService fidelityService;
    @Inject
    PosState state;

    // --- Vue ---

    /**
     * Shows the manual card-entry page.
     *
     * @return the fidelity page
     */
    @GET
    @Path("/fidelity") // Chemin complet
    public TemplateInstance fidelityPage() {
        LOGGER.info("Entering method fidelityPage");
        // In-store consultation (imfid spec §4): balance and history of the
        // attached card, assembled server-side; degraded = a message.
        // The lookup outcome is read from the state (stored by the POST
        // before its 303 hop here), so refreshing the page re-renders the
        // same result list instead of replaying a POST.
        LOGGER.info("Exiting method fidelityPage");
        return fidelity.data("state", state)
                .data("lookup", state.fidelity.lastLookup)
                .data("searchMode", state.fidelity.lastLookupMode)
                .data("searchValue", state.fidelity.lastLookupValue)
                .data("consultation", fidelityService.loadConsultation(state));
    }

    // --- Action ---

    /**
     * Attaches the typed card and returns to the home page.
     *
     * @param card the typed card number
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/fidelity") // Chemin complet
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateFidelity(@FormParam("card") String card) {
        LOGGER.info("Entering method validateFidelity with card: " + card);
        fidelityService.validateCard(state, card);
        LOGGER.info("Exiting method validateFidelity");
        return Response.seeOther(URI.create("/")).build();
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
     * @return a 303 redirect to the fidelity page (PRG pattern, so a browser
     *         refresh never replays the POST); the outcome plus the echoed
     *         search mode and value travel through the fidelity state and the
     *         page re-renders in the SAME mode with the criterion displayed
     */
    @POST
    @Path("/action/fidelity-lookup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response lookupFidelity(@FormParam("mode") String mode,
                                           @FormParam("value") String value) {
        LOGGER.info("Entering method lookupFidelity with mode: " + mode + ", value: " + value);
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
        state.fidelity.lastLookup = lookup;
        state.fidelity.lastLookupMode = mode != null ? mode : "name";
        state.fidelity.lastLookupValue = input;
        state.fidelity.lastLookupPage = 0;
        LOGGER.info("Exiting method lookupFidelity");
        return Response.seeOther(URI.create("/fidelity")).build();
    }

    /**
     * Changes the lookup result page (same pattern as the refund detail
     * pagination) and re-renders the fidelity page; the index is clamped by
     * the state when the list is read.
     *
     * @param page the target 0-based page index
     * @return the fidelity page on the requested result page
     */
    @GET
    @Path("/fidelity/page/{p}")
    public TemplateInstance changeLookupPage(@PathParam("p") int page) {
        LOGGER.info("Entering method changeLookupPage with page: " + page);
        state.fidelity.lastLookupPage = Math.max(0, page);
        LOGGER.info("Exiting method changeLookupPage");
        return fidelityPage();
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
     * @param email the holder's e-mail, echoed from the lookup (LC-08-02-09)
     * @return a 303 redirect (PRG pattern, so a browser reload never replays
     *         the POST): to the main page on success, or back to the fidelity
     *         page with the refusal stored in the fidelity state
     */
    @POST
    @Path("/action/fidelity-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectFidelity(@FormParam("card") String card,
                                           @FormParam("lastName") String lastName,
                                           @FormParam("firstName") String firstName,
                                           @FormParam("status") String status,
                                           @FormParam("email") String email) {
        LOGGER.info("Entering method selectFidelity with card: " + card + ", lastName: " + lastName + ", firstName: " + firstName + ", status: " + status + ", email: " + email);
        String refusal = fidelityService.attachLookedUpCard(state, card, lastName, firstName,
                status, email);
        if (refusal != null) {
            // The refusal replaces the stored result list; the search mode
            // and criterion are kept so the operator stays in context.
            FidelityService.LookupView lookup = new FidelityService.LookupView();
            lookup.message = refusal;
            state.fidelity.lastLookup = lookup;
            LOGGER.info("Exiting method selectFidelity");
            return Response.seeOther(URI.create("/fidelity")).build();
        }
        LOGGER.info("Exiting method selectFidelity");
        return Response.seeOther(URI.create("/")).build();
    }
}