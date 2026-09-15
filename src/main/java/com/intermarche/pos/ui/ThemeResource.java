package com.intermarche.pos.ui;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the theme-selection screen: lists the available themes
 * plus the "store default" choice, and persists the logged cashier's
 * preference ({@code Employee.theme}).
 * <p>
 * The choice STICKS: the referential pull only seeds employees who never
 * chose (see RefApplyService), so a preference set here survives the next
 * pull. The redirect after the choice reloads the sale screen, which is how
 * the new theme becomes visible immediately ({@code data-theme} is rendered
 * at page load). Package placement: cross-screen ui concern, ui root like
 * ThemeService.
 */
@Path("/")
@DrawerMustBeClosed
public class ThemeResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ThemeResource.class);

    @Inject @Location("theme-select") Template themeSelect;

    @Inject
    PosState state;

    @Inject
    ThemeService themeService;

    /**
     * Shows the theme-selection screen.
     *
     * @return the selection page
     */
    @GET
    @Path("/theme-select")
    public TemplateInstance themeSelectPage() {
        LOGGER.info("Entering method themeSelectPage");
        LOGGER.info("Exiting method themeSelectPage");
        return themeSelect.data("state", state)
                .data("themes", ThemeService.AVAILABLE_THEMES)
                .data("current", themeService.currentTheme());
    }

    /**
     * Persists the cashier's theme choice (blank = follow the store) and
     * returns to the sale screen, where the new theme renders immediately.
     *
     * @param theme the chosen theme name, or blank for the store default
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/theme")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response chooseTheme(@FormParam("theme") String theme) {
        LOGGER.info("Entering method chooseTheme with theme: " + theme);
        themeService.setThemeForOperator(theme);
        LOGGER.info("Exiting method chooseTheme");
        return Response.seeOther(URI.create("/")).build();
    }
}
