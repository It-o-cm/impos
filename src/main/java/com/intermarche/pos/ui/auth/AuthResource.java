package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;

/**
 * JAX-RS resource driving the lock screen: lock page, badge polling and
 * unlock action.
 * <p>
 * Phase 2: a locked-out account (repeated PIN failures) gets its own message
 * on the lock screen.
 * <p>
 * The badge flow is a relay: the scan handler deposits a badge scanned
 * while locked into the {@code AuthState} mailbox, the lock page polls
 * {@code /lock-data} (which clears the mailbox on read) and prefills the
 * login field — the cashier only types the PIN. Every route here is
 * {@code @DrawerMayBeOpen}: the lock screen must stay reachable while the
 * drawer guard blocks the rest of the register, otherwise a cashier who
 * locked with the drawer open could never come back. A successful unlock
 * opens the drawer once — the historical gesture for a cashier taking over
 * the till.
 */
@Path("/")
public class AuthResource {

    @Inject Template main;
    @Inject Template lock;
    @Inject AuthService authService;
    @Inject HardwareService hardwareService;

    @Inject
    PosState state;

    /**
     * Shows the lock page (logging the current operator out) with an optional
     * error message.
     *
     * @param error the error code from the redirect ("true" for invalid
     *        credentials, "locked" for a locked account), or null
     * @return the lock page
     */
    @GET
    @Path("/lock")
    @DrawerMayBeOpen
    public TemplateInstance lockPage(@QueryParam("error") String error) {
        authService.logout(state);
        String message = null;
        if ("locked".equals(error)) {
            message = "COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD";
        } else if (error != null) {
            message = "IDENTIFIANTS INCORRECTS";
        }
        return lock.data("state", state).data("error", message);
    }

    /**
     * Polling endpoint returning the last scanned badge on the lock screen.
     *
     * @return a JSON map with the scanned badge, or an empty string
     */
    @GET
    @Path("/lock-data")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> getLockData() {
        String badge = state.auth.scannedBadgeId;
        if (badge != null) state.auth.clearScannedBadge();
        return Map.of("scannedBadge", badge != null ? badge : "");
    }

    /**
     * Unlocks the register with the presented credentials.
     *
     * @param login the login identifier (badge id or login name)
     * @param password the raw PIN entered
     * @return a redirect to the home page on success, or back to the lock
     *         page with the appropriate error code
     */
    @POST
    @Path("/action/unlock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response unlock(
        @FormParam("login") String login,
        @FormParam("password") String password
    ) {
        AuthService.LoginResult result = authService.login(state, login, password);
        if (result == AuthService.LoginResult.SUCCESS) {
            hardwareService.openDrawer();
            return Response.seeOther(URI.create("/")).build();
        }
        String error = (result == AuthService.LoginResult.LOCKED) ? "locked" : "true";
        return Response.seeOther(URI.create("/lock?error=" + error)).build();
    }
}
