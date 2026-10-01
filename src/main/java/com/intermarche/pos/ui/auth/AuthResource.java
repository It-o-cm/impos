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

    @Inject Template lock;

    /** The blocking hardware-status page shown when a peripheral is down. */
    @Inject
    @io.quarkus.qute.Location("hardware-unavailable.html")
    Template hardwareUnavailable;
    @Inject AuthService authService;

    /** The supervisor endorsement, used by a forced close (LC-01-02-06). */
    @Inject com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The closing rules: what has to be true before the register closes. */
    @Inject SessionCloseService sessionCloseService;

    /** The closing page, shown only when something stands in the way. */
    @Inject @io.quarkus.qute.Location("close") Template close;
    @Inject HardwareService hardwareService;

    /** Says whether the back office has taken this checkout point out of service. */
    @Inject com.intermarche.pos.service.CheckoutPointService checkoutPointService;
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
        LOGGER.info("Entering method lockPage with error: " + error);
        // A PAUSED register (verrouillage) keeps its operator and its ticket: the
        // lock landing must NOT log them out, or the pause would abandon the sale
        // it exists to protect (EXP-12). A free register (fermeture / never
        // logged in) is logged out as before — the whole login recipe relies on
        // /lock being the reset.
        if (!state.auth.isPaused()) {
            authService.logout(state);
        }
        String message = null;
        if ("locked".equals(error)) {
            message = "COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD";
        } else if ("supervisor".equals(error)) {
            message = "AUTORISATION SUPERVISEUR REFUSÉE";
        } else if ("deactivated".equals(error)) {
            // BO-03-09-01: the back office took this checkout point out of
            // service. Naming the cause is the whole point — a lane that just
            // refuses every code reads as a broken lane, and someone spends the
            // morning on the credentials.
            message = "CAISSE HORS SERVICE - POINT DE CONTACT DÉSACTIVÉ AU BACK OFFICE";
        } else if (error != null) {
            message = "IDENTIFIANTS INCORRECTS";
        }
        LOGGER.info("Exiting method lockPage");
        return lock.data("state", state).data("error", message)
                .data("welcomeMessage", posSettingsService.welcomeMessage())
                .data("paused", state.auth.isPaused())
                .data("lockedBadge", state.auth.lockedOperatorBadgeId)
                .data("lockedName", state.auth.lockedOperatorName);
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
        LOGGER.info("Entering method getLockData");
        String badge = state.auth.scannedBadgeId;
        if (badge != null) state.auth.clearScannedBadge();
        LOGGER.info("Exiting method getLockData");
        return Map.of("scannedBadge", badge != null ? badge : "",
                "passwordRequired", posSettingsService.passwordRequiredOnOpen());
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
        LOGGER.info("Entering method unlock with login: " + login + ", password: ***");
        // BO-03-09-01: a checkout point DEACTIVATED in the back office does not
        // open, whoever presents themselves. Checked before the credentials on
        // purpose — the refusal has nothing to do with who is at the lane, and
        // telling an operator their code is wrong when the register is out of
        // service sends them looking in the wrong place. A register with no
        // administered point is NOT deactivated: it opens as it always did.
        if (checkoutPointService.isDeactivated()) {
            LOGGER.warn("Prise de poste refusée: le point de contact de cette caisse"
                    + " est désactivé au back office.");
            LOGGER.info("Exiting method unlock");
            return Response.seeOther(URI.create("/lock?error=deactivated")).build();
        }
        // Captured BEFORE the login, which clears the paused state on success: a
        // RESUME (déverrouillage) is not a prise de poste — no opening receipt, no
        // hardware gate, no drawer pulse, no session detour — the cashier drops
        // straight back onto the sale, their ticket intact (EXP-12).
        boolean wasPaused = state.auth.isPaused();
        AuthService.LoginResult result = authService.login(state, login, password);
        if (result == AuthService.LoginResult.SUCCESS && wasPaused) {
            state.touch();
            LOGGER.info("Exiting method unlock");
            // A forced reprise (EXP-14) lands on the PIN-change screen first.
            String target = state.auth.passwordChangeRequired ? "/pin-change" : "/";
            return Response.seeOther(URI.create(target)).build();
        }
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
            sessionCloseService.printOpening(state);
            java.util.List<HardwareService.DeviceStatus> devices = hardwareService.probeDevices();
            boolean allAvailable = devices.stream().allMatch(HardwareService.DeviceStatus::available);
            state.touch();
            // TEXT_HTML explicitly: the method returns a Response, so no
            // @Produces drives the negotiation and the page would leave
            // typeless — which the browser downloads instead of showing.
            LOGGER.info("Exiting method unlock");
            return Response.ok(hardwareUnavailable.data("devices", devices)
                    .data("allAvailable", allAvailable), MediaType.TEXT_HTML).build();
        }
        String error = (result == AuthService.LoginResult.LOCKED) ? "locked" : "true";
        LOGGER.info("Exiting method unlock");
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
        LOGGER.info("Entering method hardwareOverride");
        if (state.auth.isLocked) {
            LOGGER.info("Exiting method hardwareOverride");
            return Response.seeOther(URI.create("/lock")).build();
        }
        LOGGER.warnf("Hardware gate bypassed by '%s': the lane opens with a peripheral down.",
                state.auth.operatorName);
        LOGGER.info("Exiting method hardwareOverride");
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

    /**
     * Closes the register, or shows what stands in the way (LC-01-02-01).
     *
     * <p>A GET, because it is reached from a key on the sale screen; it either
     * closes straight away — the historical behaviour, and still the default —
     * or renders the page that asks for what the back office demanded.
     *
     * <p>Reached the other way too: the operator scans their own badge and the
     * sale screen sends them here. The badge IS the credential, so it answers
     * the password the back office may demand (LC-01-02-03) — and only that.
     * Tickets left in attente still stop the close and still ask for the
     * supervisor's endorsement, because that question is not about proving who
     * is standing at the register.
     *
     * @return a redirect to the lock screen, or the closing page
     */
    @GET
    @Path("/close")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public Response closePage() {
        LOGGER.info("Entering method closePage");
        if (state.auth.operatorBadgeId == null) {
            LOGGER.info("Exiting method closePage");
            return Response.seeOther(URI.create("/lock")).build();
        }
        boolean byBadge = state.auth.takeCloseRequest();
        SessionCloseService.Obstacle obstacle = sessionCloseService.obstacle(state);
        if (byBadge && obstacle == SessionCloseService.Obstacle.PASSWORD) {
            obstacle = SessionCloseService.Obstacle.NONE;
        }
        if (obstacle == SessionCloseService.Obstacle.NONE) {
            sessionCloseService.close(state, null);
            LOGGER.info("Exiting method closePage");
            return Response.seeOther(URI.create("/lock")).build();
        }
        LOGGER.info("Exiting method closePage");
        return Response.ok(closeView(obstacle, null), MediaType.TEXT_HTML).build();
    }

    /**
     * Carries out a close the closing page asked the operator to justify
     * (LC-01-02-02/06/10).
     *
     * <p>Two ways through, and they are not the same event. The OPERATOR keys
     * their own password; a SUPERVISOR endorses a forced close, which is what
     * the requirement asks for when the password is forgotten, and which the
     * journal records as a forcing.
     *
     * @param password the operator's password, or null on a forced close
     * @param supervisor the supervisor's identifier on a forced close
     * @param supervisorPassword the supervisor's password on a forced close
     * @return a redirect to the lock screen, or the page again with its refusal
     */
    @POST
    @Path("/action/close")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public Response closeRegister(@FormParam("password") String password,
                                  @FormParam("supervisor") String supervisor,
                                  @FormParam("supervisorPassword") String supervisorPassword) {
        LOGGER.info("Entering method closeRegister");
        if (state.auth.operatorBadgeId == null) {
            LOGGER.info("Exiting method closeRegister");
            return Response.seeOther(URI.create("/lock")).build();
        }
        SessionCloseService.Obstacle obstacle = sessionCloseService.obstacle(state);
        if (obstacle == SessionCloseService.Obstacle.IN_TRANSACTION) {
            // EXP-11: a sale in progress cannot be forced past — not by the
            // operator's password, not by a supervisor.
            LOGGER.info("Exiting method closeRegister");
            return Response.ok(closeView(obstacle, message(obstacle)), MediaType.TEXT_HTML).build();
        }
        if (supervisor != null && !supervisor.isBlank()) {
            if (endorsementService.authorize(supervisor, supervisorPassword,
                    SessionCloseService.FORCE_CLOSE_ACTION)) {
                sessionCloseService.close(state, "superviseur en caisse");
                LOGGER.info("Exiting method closeRegister");
                return Response.seeOther(URI.create("/lock")).build();
            }
            LOGGER.info("Exiting method closeRegister");
            return Response.ok(closeView(obstacle, "Autorisation superviseur refusée."),
                    MediaType.TEXT_HTML).build();
        }
        if (obstacle == SessionCloseService.Obstacle.PASSWORD
                && sessionCloseService.passwordMatches(state, password)) {
            sessionCloseService.close(state, null);
            LOGGER.info("Exiting method closeRegister");
            return Response.seeOther(URI.create("/lock")).build();
        }
        LOGGER.info("Exiting method closeRegister");
        return Response.ok(closeView(obstacle, message(obstacle)), MediaType.TEXT_HTML).build();
    }

    /**
     * Builds the closing page.
     *
     * @param obstacle what stands in the way
     * @param error the refusal to show, or null
     * @return the page instance
     */
    private io.quarkus.qute.TemplateInstance closeView(SessionCloseService.Obstacle obstacle, String error) {
        return close.data("state", state)
                .data("obstacle", obstacle.name())
                .data("pending", sessionCloseService.pendingCount())
                .data("error", error);
    }

    /**
     * Names the refusal that matches an obstacle.
     *
     * @param obstacle what stood in the way
     * @return the message shown to the operator
     */
    private String message(SessionCloseService.Obstacle obstacle) {
        if (obstacle == SessionCloseService.Obstacle.IN_TRANSACTION) {
            return "Une vente est en cours : terminez ou abandonnez le ticket avant de fermer.";
        }
        return obstacle == SessionCloseService.Obstacle.PASSWORD
                ? "Code opérateur incorrect."
                : "Des tickets en attente exigent une autorisation superviseur.";
    }

    /**
     * VERROUILLE (pauses) the register from the sale screen (EXP-12): the
     * operator identity and the in-memory ticket are kept aside, the drawer is
     * left untouched, and the register lands on its lock screen in the
     * "CAISSE VERROUILLÉE" mode. Reached from the VERROUILLER key.
     *
     * @return a redirect to the lock screen
     */
    @GET
    @Path("/action/lock")
    @DrawerMayBeOpen
    public Response lockRegister() {
        LOGGER.info("Entering method lockRegister");
        authService.pause(state);
        state.touch();
        LOGGER.info("Exiting method lockRegister");
        return Response.seeOther(URI.create("/lock")).build();
    }

    /**
     * Forces the reprise of a paused register whose cashier forgot their password
     * (EXP-14): a supervisor endorses the unlock, the cashier keeps their session
     * and drawer, and a PIN change is imposed at the reprise. Reached from the
     * "mot de passe oublié" form of the paused lock screen.
     *
     * @param supervisor the supervisor's identifier
     * @param supervisorPassword the supervisor's password
     * @return a redirect to the mandatory PIN-change screen on success, or back to
     *         the lock screen with the refusal
     */
    @POST
    @Path("/action/force-unlock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response forceUnlock(@FormParam("supervisor") String supervisor,
                                @FormParam("supervisorPassword") String supervisorPassword) {
        LOGGER.info("Entering method forceUnlock");
        if (endorsementService.authorize(supervisor, supervisorPassword,
                AuthService.FORCE_UNLOCK_ACTION) && authService.forceResume(state)) {
            state.touch();
            LOGGER.info("Exiting method forceUnlock");
            return Response.seeOther(URI.create("/pin-change")).build();
        }
        LOGGER.info("Exiting method forceUnlock");
        return Response.seeOther(URI.create("/lock?error=supervisor")).build();
    }

    /**
     * Forces the fin de journée of a paused, abandoned register (EXP-96): the
     * cashier did not come back, and a supervisor endorses the fermeture from the
     * lock screen. Reuses the forced-close mechanism, resolving the abandoned
     * post's identity from the paused state.
     *
     * @param supervisor the supervisor's identifier
     * @param supervisorPassword the supervisor's password
     * @return a redirect to the lock screen (closed on success, refusal otherwise)
     */
    @POST
    @Path("/action/force-close")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response forceCloseAbandoned(@FormParam("supervisor") String supervisor,
                                        @FormParam("supervisorPassword") String supervisorPassword) {
        LOGGER.info("Entering method forceCloseAbandoned");
        if (endorsementService.authorize(supervisor, supervisorPassword,
                SessionCloseService.FORCE_CLOSE_ACTION)) {
            sessionCloseService.close(state, "poste abandonné — superviseur");
            state.touch();
            LOGGER.info("Exiting method forceCloseAbandoned");
            return Response.seeOther(URI.create("/lock")).build();
        }
        LOGGER.info("Exiting method forceCloseAbandoned");
        return Response.seeOther(URI.create("/lock?error=supervisor")).build();
    }
}
