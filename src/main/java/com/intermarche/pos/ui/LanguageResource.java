package com.intermarche.pos.ui;

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
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * JAX-RS resource of the language-selection screen (LC-13-06-03/04): lists the
 * languages the back office offers, plus the "store default" choice that hands
 * the decision back to the chain, and records the operator's choice for this
 * register.
 *
 * <p>WHEN THE CHOICE IS ALLOWED. Before the register is opened, always: that is
 * LC-13-06-03, and it is the whole point — an operator who does not read the
 * screen cannot sign in to change its language. Once signed in, only when
 * {@code language.operator-change} is administered (LC-13-06-04); refused, the
 * screen says so rather than hiding the key, so the cashier learns the rule
 * instead of doubting the machine.
 *
 * <p>The choice lives in the register state and dies at sign-out. It is NOT
 * written on the employee row: the language administered for an operator is
 * referential data the back office owns, and a till must not overwrite it
 * because someone pressed a flag once. That is the one place this screen differs
 * from the theme one, which does persist its choice.
 *
 * <p>The redirect after the choice reloads the screen the operator came from, by
 * which the new language becomes visible immediately — every label is resolved
 * at render time.
 *
 * <p>Package placement: cross-screen ui concern, ui root like
 * {@link ThemeResource} and {@link Messages}.
 */
@Path("/")
@DrawerMustBeClosed
public class LanguageResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(LanguageResource.class);

    /** The selection screen. */
    @Inject
    @Location("language-select")
    Template languageSelect;

    /** The register state: carries the chosen language and the lock state. */
    @Inject
    PosState state;

    /** The label resolver: offers the selectable languages and the current one. */
    @Inject
    Messages messages;

    /** Administered settings: whether the change is allowed during the session. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Shows the language-selection screen.
     *
     * @return the selection page
     */
    @GET
    @Path("/language-select")
    public TemplateInstance languageSelectPage() {
        LOGGER.info("Entering method languageSelectPage");
        LOGGER.info("Exiting method languageSelectPage");
        return languageSelect.data("state", state)
                .data("languages", messages.getAvailableLanguages())
                .data("current", messages.getLang())
                .data("allowed", changeAllowed());
    }

    /**
     * Records the operator's language choice (blank = follow the store) and
     * returns to the screen it was asked from, where the new language renders
     * immediately.
     *
     * @param language the chosen language tag, or blank for the store default
     * @return a redirect to the lock screen when the register is locked, to the
     *         sale screen otherwise
     */
    @POST
    @Path("/action/language")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response chooseLanguage(@FormParam("language") String language) {
        LOGGER.info("Entering method chooseLanguage with language: " + language);
        if (!changeAllowed()) {
            // The back office forbids changing the language mid-session: the
            // choice is simply not taken, and the screen says why.
            LOGGER.info("Exiting method chooseLanguage");
            return redirectBack();
        }
        String chosen = language == null ? "" : language.trim();
        state.language = chosen.isEmpty() ? null : chosen;
        state.touch();
        LOGGER.info("Exiting method chooseLanguage");
        return redirectBack();
    }

    /**
     * Whether the language may be changed right now (LC-13-06-03/04): always on a
     * locked register, and during a session only when the back office allows it.
     *
     * @return true when the choice is accepted
     */
    private boolean changeAllowed() {
        return state.isLocked() || posSettingsService.languageChangeAllowed();
    }

    /**
     * Builds the redirect back to the screen the choice was made from.
     *
     * @return a 303 to the lock screen on a locked register, to the sale screen
     *         otherwise
     */
    private Response redirectBack() {
        return Response.seeOther(URI.create(state.isLocked() ? "/lock" : "/")).build();
    }
}
