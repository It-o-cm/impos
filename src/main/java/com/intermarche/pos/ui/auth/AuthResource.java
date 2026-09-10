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
 * the till — and, when no cash session is open on this register, lands on
 * the SESSION SCREEN instead of the sale screen: the drawer is out
 * precisely to install the float, so the morning flow reads badge, PIN,
 * float, sale — the cashier never meets the "no session" refusal. A
 * mid-day relock (session already open) returns straight to the sale, and
 * training mode skips the detour (no session needed there).
 */
@Path("/")
public class AuthResource {

    /** The class logger. */
    private static final org.jboss.logging.Logger LOGGER =
            org.jboss.logging.Logger.getLogger(AuthResource.class);

    @Inject Template main;
    @Inject Template lock;

    /** The blocking hardware-status page shown when a peripheral is down. */
    @Inject
    @io.quarkus.qute.Location("hardware-unavailable.html")
    Template hardwareUnavailable;
    @Inject AuthService authService;
    @Inject HardwareService hardwareService;
    @Inject com.intermarche.pos.service.CashSessionService cashSessionService;

    /** The back-office parameters (drawer-open-on-login rule — BO-10-02-25). */
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;

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
     * @return the hardware status page on success — the lane opens from its
     *         CONTINUER button, never straight from here — or a redirect back
     *         to the lock page with the appropriate error code
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
            // Hardware gate: EVERY entry stops on the status page, whatever
            // the peripherals answered. The operator taking the lane sees
            // what this till is and is not carrying, and opens it knowingly
            // — the page tells them apart by colour and CONTINUER is the
            // only way through, green when nothing is wrong. Mid-sale
            // failures stay fail-soft (see HardwareService); the door is
            // where the register still asks. The operator is NOT logged back
            // out: only an authenticated entry may take the override.
            // Leaving the page by RETRY goes through /lock, which logs out.
            java.util.List<HardwareService.DeviceStatus> devices = hardwareService.probeDevices();
            boolean allAvailable = devices.stream().allMatch(HardwareService.DeviceStatus::available);
            state.touch();
            // TEXT_HTML explicitly: the method returns a Response, so no
            // @Produces drives the negotiation and the page would leave
            // typeless — which the browser downloads instead of showing.
            return Response.ok(hardwareUnavailable.data("devices", devices)
                    .data("allAvailable", allAvailable), MediaType.TEXT_HTML).build();
        }
        String error = (result == AuthService.LoginResult.LOCKED) ? "locked" : "true";
        return Response.seeOther(URI.create("/lock?error=" + error)).build();
    }

    /**
     * Opens the lane in spite of the hardware gate, from the button of the
     * blocking status page.
     * <p>
     * Deliberate escape hatch for commissioning, demonstration and a till
     * whose faulty peripheral must not stop the day: the failure is real and
     * stays reported, but the operator decides. It requires an unlocked
     * register — the credentials were checked by {@link #unlock} just before
     * and the gate does not log the operator out — so the page cannot be
     * used as a way in; a stale page posted after a lockout goes back to the
     * lock screen. The bypass is journalled at WARN, otherwise a till would
     * end up selling for weeks with a dead printer nobody remembers.
     *
     * @return the same routing a clean unlock takes, or a redirect to the
     *         lock page when no operator is logged in
     */
    @POST
    @Path("/action/hardware-override")
    @DrawerMayBeOpen
    public Response hardwareOverride() {
        if (state.auth.isLocked) {
            return Response.seeOther(URI.create("/lock")).build();
        }
        LOGGER.warnf("Hardware gate bypassed by '%s': the lane opens with a peripheral down.",
                state.auth.operatorName);
        return openLane();
    }

    /**
     * Finishes an accepted entry: drawer pulse, then the screen the cashier
     * lands on. Shared by the clean unlock and by the hardware override so
     * both take exactly the same route.
     *
     * @return the redirect to the session screen or to the sale screen
     */
    private Response openLane() {
        // The unlock pulse opens the drawer to install the float, unless
        // the back office disabled the drawer-open-on-login rule (BO-10-02-25).
        if (posSettingsService.drawerOpenOnLogin()) {
            hardwareService.openDrawer();
        }
        // Prise de poste: the drawer just opened to install the float —
        // when no session is open yet, land straight on the session
        // screen so the cashier opens it while the drawer is out
        // (training needs no session and goes straight to the sale).
        if (!state.trainingMode && cashSessionService.getOpenSession() == null) {
            return Response.seeOther(URI.create("/session")).build();
        }
        return Response.seeOther(URI.create("/")).build();
    }
}
