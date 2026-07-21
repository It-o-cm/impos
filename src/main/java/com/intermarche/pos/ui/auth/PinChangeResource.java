package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PinChangeResource {

    @Inject
    @Location("pin-change")
    Template pinChange;

    @Inject Template lock;
    @Inject AuthService authService;
    @Inject PosState state;

    /**
     * Displays the PIN change page for the logged-in operator.
     *
     * @return the PIN change page, or the lock page if no operator is logged in
     */
    @GET
    @Path("/pin-change")
    @DrawerMayBeOpen
    public TemplateInstance pinChangePage() {
        if (state.isLocked()) return lock.data("state", state);
        return pinChange.data("state", state);
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
     * @return the PIN change page with a success or error message
     */
    @POST
    @Path("/action/pin-change")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public TemplateInstance changePin(
            @FormParam("currentPin") String currentPin,
            @FormParam("newPin") String newPin,
            @FormParam("confirmPin") String confirmPin) {
        if (state.isLocked()) return lock.data("state", state);
        String error = authService.changePin(state, currentPin, newPin, confirmPin);
        if (error != null) {
            return pinChange.data("state", state).data("error", error);
        }
        return pinChange.data("state", state).data("success", "Code PIN modifié");
    }
}
