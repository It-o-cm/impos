package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * JAX-RS resource of the PIN change page for the logged-in operator: same
 * page on success (confirmation) and on failure (typed error, retry). The
 * current-PIN verification is direct — it does not increment the shared
 * lockout counter (see {@code AuthService}).
 */
@Path("/")
public class PinChangeResource {

    @Inject
    @Location("pin-change")
    Template pinChange;
    @Inject AuthService authService;
    @Inject
    PosState state;

    /**
     * Displays the PIN change page for the logged-in operator, optionally
     * with the outcome of a change carried by the PRG redirect.
     *
     * @param error the one-shot error message, or null
     * @param success the one-shot confirmation message, or null
     * @return the PIN change page
     */
    @GET
    @Path("/pin-change")
    @DrawerMayBeOpen
    public TemplateInstance pinChangePage(@QueryParam("error") String error,
                                          @QueryParam("success") String success) {
        return pinChange.data("state", state)
                .data("error", error)
                .data("success", success);
    }

    /**
     * Applies a PIN change for the logged-in operator.
     * <p>
     * On success, the page is shown again with a confirmation; on failure, it is shown
     * again with the corresponding error so the operator can retry.
     *
     * @param currentPin the operator's current PIN
     * @param newPin the desired new PIN
     * @param confirmPin the confirmation of the new PIN
     * @return a 303 redirect to the PIN change page (PRG pattern, so a
     *         browser reload never replays the POST) carrying the success or
     *         error message as a query parameter
     */
    @POST
    @Path("/action/pin-change")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response changePin(
            @FormParam("currentPin") String currentPin,
            @FormParam("newPin") String newPin,
            @FormParam("confirmPin") String confirmPin) {
        String error = authService.changePin(state, currentPin, newPin, confirmPin);
        if (error != null) {
            return Response.seeOther(URI.create("/pin-change?error=" + encode(error))).build();
        }
        return Response.seeOther(URI.create("/pin-change?success=" + encode("Code PIN modifié"))).build();
    }

    /**
     * URL-encodes a message for the PRG redirect query string.
     *
     * @param value the raw message
     * @return the UTF-8 URL-encoded value
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
