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

    @Inject @Location("theme-select") Template themeSelect;
    @Inject @Location("lock") Template lock;

    @Inject
    PosState state;

    @Inject
    ThemeService themeService;

    /**
     * Shows the theme-selection screen.
     *
     * @return the selection page, or the lock page when locked
     */
    @GET
    @Path("/theme-select")
    public TemplateInstance themeSelectPage() {
        if (state.isLocked()) {
            return lock.data("state", state);
        }
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
        if (!state.isLocked()) {
            themeService.setThemeForOperator(theme);
        }
        return Response.seeOther(URI.create("/")).build();
    }
}
