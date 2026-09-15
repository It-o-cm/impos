package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import org.jboss.logging.Logger;

/**
 * Operator badge reprint (LC-01-06-01): the typed operator number is parked
 * as the endorsed action {@code PRINT_BADGE_<n>} — the print itself runs in
 * the endorsement dispatch, strictly AFTER a manager approval (or the
 * connected-supervisor shortcut), like every guarded gesture.
 */
@Path("/")
@DrawerMustBeClosed
public class BadgePrintResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BadgePrintResource.class);

    /** The badge-reprint entry page. */
    @Inject
    @Location("badge-print")
    Template badgePrint;

    /** The endorsement machinery parking the guarded action. */
    @Inject
    EndorsementService endorsementService;

    /** The register's composition root. */
    @Inject
    PosState state;

    /**
     * Shows the badge-reprint entry page.
     *
     * @return the entry page
     */
    @GET
    @Path("/badge-print")
    public TemplateInstance badgePrintPage() {
        LOGGER.info("Entering method badgePrintPage");
        LOGGER.info("Exiting method badgePrintPage");
        return badgePrint.data("state", state);
    }

    /**
     * Parks the badge print as an endorsed action and returns to the sale
     * screen, where the endorsement modal takes over.
     *
     * @param operator the typed operator badge id or login name
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/badge-print")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response requestBadgePrint(@FormParam("operator") String operator) {
        LOGGER.info("Entering method requestBadgePrint with operator: " + operator);
        if (operator != null && !operator.isBlank()) {
            endorsementService.requestAuthorization(state, "PRINT_BADGE_" + operator.trim());
        }
        LOGGER.info("Exiting method requestBadgePrint");
        return Response.seeOther(URI.create("/")).build();
    }
}
