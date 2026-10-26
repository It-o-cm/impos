package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * The VIGNETTES screen of the register (BO-11-01-20): the cashier reads, for
 * her open shift, how many sales she closed and how many vignettes the
 * administered formula made due, and declares how many she actually handed
 * out. The declaration is journalled and travels up to the store node, whose
 * vignettes flow states the two counts side by side.
 *
 * <p>A menu destination outside any sale, reachable drawer open: declaring the
 * stickers handed out has nothing to do with the till.
 */
@Path("/")
public class VignetteResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VignetteResource.class);

    /** The vignettes screen. */
    @Inject
    @Location("vignettes")
    Template vignettes;

    /** The shift summary and the declaration. */
    @Inject
    VignetteService vignetteService;

    /** The operator declaring. */
    @Inject
    PosState state;

    /**
     * Shows the vignettes of the open shift.
     *
     * @param ok a non-null flag when a declaration was just recorded
     * @param error an optional error code from a redirect
     * @return the vignettes page
     */
    @GET
    @Path("/vignettes")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance page(@QueryParam("ok") String ok, @QueryParam("error") String error) {
        LOGGER.info("Entering method page");
        LOGGER.info("Exiting method page");
        return vignettes.data("state", state)
                .data("enabled", vignetteService.enabled())
                .data("summary", vignetteService.summary())
                .data("saved", ok != null)
                .data("error", error);
    }

    /**
     * Records the cashier's declaration of the vignettes handed out.
     *
     * @param countText the count typed on the screen
     * @return a redirect back to the screen carrying the outcome
     */
    @POST
    @Path("/action/vignettes/declare")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response declare(@FormParam("count") String countText) {
        LOGGER.info("Entering method declare with count: " + countText);
        long count;
        try {
            count = Long.parseLong(countText == null ? "" : countText.trim());
        } catch (NumberFormatException unreadable) {
            LOGGER.info("Exiting method declare: unreadable count");
            return redirect("/vignettes?error=count");
        }
        if (!vignetteService.enabled()) {
            LOGGER.info("Exiting method declare: disabled");
            return redirect("/vignettes?error=disabled");
        }
        boolean recorded = vignetteService.declare(count, state.auth.operatorBadgeId);
        LOGGER.info("Exiting method declare");
        return redirect(recorded ? "/vignettes?ok=1" : "/vignettes?error=count");
    }

    /**
     * Builds a see-other redirect.
     *
     * @param target the target path and query
     * @return the redirect
     */
    private Response redirect(String target) {
        return Response.seeOther(URI.create(target)).build();
    }
}
