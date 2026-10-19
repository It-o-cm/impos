package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link AuthService}, the
 * {@link HardwareService} drawer, the {@link CashSessionService} and two Qute
 * {@link Template}s, all driven off a {@link PosState}. Every collaborator is a
 * Mockito mock: the {@code lock} template echoes its fluent {@code data(...)}
 * chain so the returned view can be identified, {@code PosState} carries a
 * mocked {@link AuthState} mailbox and a plain {@code trainingMode} flag the
 * tests set directly. Tests assert absolute expected values and verify
 * delegation, covering both arms of the lock-page message selection, the badge
 * mailbox clear guard and its ternary, the unlock success/failure split, the
 * training-mode / open-session short-circuit and the locked/invalid redirect
 * ternary.
 */
class AuthResourceTest {

    /**
     * Builds an {@link AuthResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, including a mocked {@link AuthState} on
     * the {@link PosState} so the badge mailbox interactions can be verified.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private AuthResource newResource() {
        AuthResource resource = new AuthResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.state = mock(PosState.class);
        resource.state.auth = mock(AuthState.class);
        resource.authService = mock(AuthService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.lock = mock(Template.class);
        resource.hardwareUnavailable = mock(Template.class);
        // The drawer-open-on-login rule defaults to ON, the pre-existing
        // pulse behavior the success cases rely on (BO-10-02-25).
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        resource.sessionCloseService = mock(SessionCloseService.class);
        resource.endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        resource.close = mock(Template.class);
        // A checkout point IN SERVICE, or none administered at all: the state the
        // pre-existing cases run under, since an absent point never closes a lane
        // (BO-03-09-01). The deactivation case overrides it.
        resource.checkoutPointService =
                mock(com.intermarche.pos.service.CheckoutPointService.class);
        when(resource.checkoutPointService.isDeactivated()).thenReturn(false);
        when(resource.posSettingsService.drawerOpenOnLogin()).thenReturn(true);
        // BO-10-02-04: the drawer-outside-a-sale control answers as it does
        // with nothing administered — the drawer opens —, which is what every
        // case of this class was written against.
        when(resource.endorsementService.drawerOutOfSaleAllowed(any())).thenReturn(true);
        // The hardware gate defaults to ALL UP, the state every pre-existing
        // success case runs under; the gate tests override it device by device.
        when(resource.hardwareService.probeDevices()).thenReturn(java.util.List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("TIROIR-CAISSE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("AFFICHEUR CLIENT", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE)));
        return resource;
    }

    /**
     * Stubs the hardware page's fluent {@code data("devices", ...)} then
     * {@code data("allAvailable", ...)} chain to return a recognizable view.
     *
     * @param resource the resource whose hardware template is stubbed
     * @param devices the device list expected on the first call
     * @param allAvailable the flag expected on the second call
     * @return the view the chain returns
     */
    private TemplateInstance stubHardwarePage(AuthResource resource,
            java.util.List<HardwareService.DeviceStatus> devices, boolean allAvailable) {
        TemplateInstance withDevices = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.hardwareUnavailable.data("devices", devices)).thenReturn(withDevices);
        when(withDevices.data("allAvailable", allAvailable)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code lock} template's fluent {@code data("state", state)}
     * then {@code data("error", message)} chain to return a recognizable view.
     *
     * @param resource the resource whose {@code lock} template is stubbed
     * @param expectedMessage the error message expected on the second call
     * @return the final view the chain will return
     */
    private TemplateInstance stubLock(AuthResource resource, String expectedMessage) {
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withError = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(withState);
        when(withState.data("error", expectedMessage)).thenReturn(withError);
        // The lock view now carries more fluent data() links (welcomeMessage,
        // paused, lockedBadge, lockedName): the tail echoes itself so the final
        // link is still the recognizable view.
        when(withError.data(anyString(), any())).thenReturn(withError);
        return withError;
    }

    // --- lockPage ---

    /**
     * {@code lockPage("locked")} logs out and renders the lock page with the
     * account-locked message (first arm of the message selection true).
     */
    @Test
    void lockPageShowsLockedMessage() {
        AuthResource resource = newResource();
        TemplateInstance view = stubLock(resource, "COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD");
        assertSame(view, resource.lockPage("locked"));
        verify(resource.authService).logout(resource.state);
    }

    /**
     * {@code lockPage("true")} logs out and renders the lock page with the
     * invalid-credentials message (first arm false, second arm true).
     */
    @Test
    void lockPageShowsInvalidMessage() {
        AuthResource resource = newResource();
        TemplateInstance view = stubLock(resource, "IDENTIFIANTS INCORRECTS");
        assertSame(view, resource.lockPage("true"));
        verify(resource.authService).logout(resource.state);
    }

    /**
     * {@code lockPage(null)} logs out and renders the lock page with no message
     * (both arms of the message selection false).
     */
    @Test
    void lockPageShowsNoMessageWhenErrorNull() {
        AuthResource resource = newResource();
        TemplateInstance view = stubLock(resource, null);
        assertSame(view, resource.lockPage(null));
        verify(resource.authService).logout(resource.state);
    }

    // --- getLockData ---

    /**
     * {@code getLockData()} returns the scanned badge and clears the mailbox
     * when a badge is present (guard true, ternary true arm).
     */
    @Test
    void getLockDataReturnsAndClearsBadge() {
        AuthResource resource = newResource();
        resource.state.auth.scannedBadgeId = "B123";
        Map<String, Object> result = resource.getLockData();
        assertEquals("B123", result.get("scannedBadge"));
        verify(resource.state.auth).clearScannedBadge();
    }

    /**
     * {@code getLockData()} returns an empty string and does not clear the
     * mailbox when no badge is present (guard false, ternary false arm).
     */
    @Test
    void getLockDataReturnsEmptyWhenNoBadge() {
        AuthResource resource = newResource();
        resource.state.auth.scannedBadgeId = null;
        Map<String, Object> result = resource.getLockData();
        assertEquals("", result.get("scannedBadge"));
        verify(resource.state.auth, never()).clearScannedBadge();
    }

    // --- unlock ---

    /**
     * A successful unlock ALWAYS lands on the hardware status page, even with
     * every peripheral available: the operator takes the lane knowingly. The
     * drawer is not pulsed and no session is looked up here — that belongs to
     * the override the page's button posts.
     */
    @Test
    void unlockShowsThePageWhenEverythingIsAvailable() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        java.util.List<HardwareService.DeviceStatus> devices = java.util.List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("TIROIR-CAISSE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("AFFICHEUR CLIENT", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE));
        when(resource.hardwareService.probeDevices()).thenReturn(devices);
        TemplateInstance view = stubHardwarePage(resource, devices, true);
        Response response = resource.unlock("alice", "1234");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(MediaType.TEXT_HTML_TYPE, response.getMediaType());
        assertSame(view, response.getEntity());
        verify(resource.state).touch();
        verify(resource.state.auth, never()).logout();
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.cashSessionService, never()).getOpenSession();
    }

    /**
     * The hardware gate: correct credentials but one FAILED peripheral stop
     * the entry — the drawer stays shut and the status page is rendered with
     * the device list (the {@code allAvailable} false arm). The operator
     * stays authenticated behind the page, which is what allows the override
     * button to open the lane without presenting credentials again.
     */
    @Test
    void unlockBlocksOnFailedHardware() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        java.util.List<HardwareService.DeviceStatus> devices = java.util.List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("TIROIR-CAISSE", HardwareService.Availability.FAILED),
                new HardwareService.DeviceStatus("AFFICHEUR CLIENT", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE));
        when(resource.hardwareService.probeDevices()).thenReturn(devices);
        TemplateInstance view = stubHardwarePage(resource, devices, false);
        Response response = resource.unlock("alice", "1234");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(MediaType.TEXT_HTML_TYPE, response.getMediaType());
        assertSame(view, response.getEntity());
        verify(resource.state.auth, never()).logout();
        verify(resource.state).touch();
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.cashSessionService, never()).getOpenSession();
    }

    /**
     * The override button opens the lane in spite of a failed peripheral,
     * taking the same route as a clean unlock: drawer pulse then the session
     * screen when none is open (guard false arm, register unlocked).
     */
    @Test
    void hardwareOverrideOpensTheLaneOnSessionScreen() {
        AuthResource resource = newResource();
        resource.state.auth.isLocked = false;
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        Response response = resource.hardwareOverride();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/session", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
    }

    /**
     * The override lands on the sale screen when a cash session is already
     * open, exactly like a clean unlock.
     */
    @Test
    void hardwareOverrideLandsHomeWhenSessionOpen() {
        AuthResource resource = newResource();
        resource.state.auth.isLocked = false;
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        Response response = resource.hardwareOverride();
        assertEquals("/", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
    }

    /**
     * The override honours the drawer-open-on-login rule: with the rule
     * disabled the lane opens without the pulse (BO-10-02-25).
     */
    @Test
    void hardwareOverrideLeavesDrawerShutWhenRuleDisabled() {
        AuthResource resource = newResource();
        resource.state.auth.isLocked = false;
        resource.state.trainingMode = true;
        when(resource.posSettingsService.drawerOpenOnLogin()).thenReturn(false);
        Response response = resource.hardwareOverride();
        assertEquals("/", response.getLocation().toString());
        verify(resource.hardwareService, never()).openDrawer();
    }

    /**
     * A locked register refuses the override and returns to the lock screen
     * (guard true arm): a stale status page cannot be replayed as a way in.
     */
    @Test
    void hardwareOverrideOnLockedRegisterGoesBackToLockScreen() {
        AuthResource resource = newResource();
        resource.state.auth.isLocked = true;
        Response response = resource.hardwareOverride();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.cashSessionService, never()).getOpenSession();
    }

    /**
     * An ABSENT peripheral shows the page too: the operator taking the lane
     * must see what this till is not carrying, and decides with CONTINUER.
     * Nothing is broken here, and the entry stops all the same.
     */
    @Test
    void unlockShowsThePageOnAbsentPeripherals() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        java.util.List<HardwareService.DeviceStatus> devices = java.util.List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.ABSENT),
                new HardwareService.DeviceStatus("TIROIR-CAISSE", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("AFFICHEUR CLIENT", HardwareService.Availability.ABSENT),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE));
        when(resource.hardwareService.probeDevices()).thenReturn(devices);
        TemplateInstance view = stubHardwarePage(resource, devices, false);
        Response response = resource.unlock("alice", "1234");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.state.auth, never()).logout();
    }

    /**
     * {@code unlock(...)} redirects to the lock page with the locked error and
     * does not open the drawer when the account is locked (success false,
     * locked ternary true arm).
     */
    @Test
    void unlockLockedRedirectsToLockedError() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.LOCKED);
        Response response = resource.unlock("alice", "1234");
        assertEquals("/lock?error=locked", response.getLocation().toString());
        verify(resource.hardwareService, never()).openDrawer();
    }

    /**
     * {@code unlock(...)} redirects to the lock page with the generic error and
     * does not open the drawer on invalid credentials (success false, locked
     * ternary false arm).
     */
    @Test
    void unlockInvalidRedirectsToGenericError() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.INVALID);
        Response response = resource.unlock("alice", "1234");
        assertEquals("/lock?error=true", response.getLocation().toString());
        verify(resource.hardwareService, never()).openDrawer();
    }

    // --- closePage ---

    /**
     * Stubs the {@code close} template's fluent chain so the closing page is a
     * recognizable view.
     *
     * @param resource the resource whose {@code close} template is stubbed
     * @return the final view the chain will return
     */
    private TemplateInstance stubClose(AuthResource resource) {
        TemplateInstance step = mock(TemplateInstance.class);
        when(resource.close.data(eq("state"), any())).thenReturn(step);
        when(step.data(anyString(), any())).thenReturn(step);
        return step;
    }

    /**
     * {@code closePage()} on a register holding no operator goes back to the
     * lock screen without closing anything.
     */
    @Test
    void closePageOnALockedRegisterGoesToTheLockScreen() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = null;
        Response response = resource.closePage();
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closePage()} with nothing in the way closes the register — the
     * historical behaviour of the DÉCONNEXION key, and still the default.
     */
    @Test
    void closePageWithoutObstacleClosesTheRegister() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.NONE);
        Response response = resource.closePage();
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService).close(resource.state, null);
    }

    /**
     * {@code closePage()} with a password demanded and NO badge presented
     * renders the closing page instead of closing.
     */
    @Test
    void closePageAsksForThePasswordWhenNoBadgeWasScanned() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.state.auth.takeCloseRequest()).thenReturn(false);
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closePage();
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closePage()} reached by a BADGE SCAN closes without asking for the
     * password: the badge is the credential (LC-01-02-03).
     */
    @Test
    void closePageByBadgeAnswersThePassword() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.state.auth.takeCloseRequest()).thenReturn(true);
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        Response response = resource.closePage();
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService).close(resource.state, null);
    }

    /**
     * A badge scan does NOT sweep away the tickets left in attente: that
     * obstacle is not about proving who is standing at the register, so the
     * page still asks for the supervisor's endorsement (LC-01-02-10).
     */
    @Test
    void closePageByBadgeStillStopsOnPendingTickets() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.state.auth.takeCloseRequest()).thenReturn(true);
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closePage();
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    // --- closeRegister ---

    /**
     * {@code closeRegister(...)} on a register holding no operator
     * ({@code operatorBadgeId} null) goes back to the lock screen without
     * closing anything (operator-badge guard true arm).
     */
    @Test
    void closeRegisterOnALockedRegisterGoesToTheLockScreen() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = null;
        Response response = resource.closeRegister("pw", "sup", "supPw");
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closeRegister(...)} with a supervisor whose endorsement is
     * accepted forces the close and records it as a forcing (badge guard
     * false, supervisor both legs true, authorize true arm).
     */
    @Test
    void closeRegisterForcesTheCloseOnSupervisorEndorsement() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.GRANTED);
        Response response = resource.closeRegister(null, "mcurie", "1111");
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService).close(resource.state, "superviseur en caisse");
    }

    /**
     * {@code closeRegister(...)} with a supervisor whose endorsement is
     * refused renders the closing page with the refusal message and does not
     * close (authorize false arm).
     */
    @Test
    void closeRegisterRefusesTheForcedCloseOnBadSupervisor() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        when(resource.endorsementService.authorizeOutcome("mcurie", "bad",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closeRegister(null, "mcurie", "bad");
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closeRegister(...)} with no supervisor (supervisor null, first
     * leg false) and the correct operator password closes the register
     * (obstacle PASSWORD true, passwordMatches true arm).
     */
    @Test
    void closeRegisterClosesOnCorrectOperatorPassword() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        when(resource.sessionCloseService.passwordOutcome(resource.state, "1234"))
                .thenReturn(AuthService.CheckOutcome.GRANTED);
        Response response = resource.closeRegister("1234", null, null);
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService).close(resource.state, null);
    }

    /**
     * {@code closeRegister(...)} with a BLANK supervisor (supervisor
     * non-null, {@code !isBlank()} false) and a wrong operator password
     * renders the page with the operator-code refusal (passwordMatches false
     * arm, message PASSWORD arm).
     */
    @Test
    void closeRegisterRefusesOnWrongOperatorPassword() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        when(resource.sessionCloseService.passwordOutcome(resource.state, "wrong"))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closeRegister("wrong", "   ", null);
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closeRegister(...)} with no supervisor and a non-PASSWORD
     * obstacle skips the password check and renders the page with the
     * supervisor-required refusal (obstacle PASSWORD false, message else arm).
     */
    @Test
    void closeRegisterShowsSupervisorRequiredOnPendingTickets() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closeRegister(null, null, null);
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(resource.sessionCloseService, never()).close(any(), any());
        verify(resource.sessionCloseService, never()).passwordOutcome(any(), any());
    }

    /**
     * {@code closeRegister(...)} names the LOCKOUT when the supervisor's own
     * account is blocked, instead of answering "autorisation refusée" — the
     * refusal a supervisor cannot act on by retyping (outcome LOCKED arm).
     */
    @Test
    void closeRegisterNamesALockedSupervisorAccount() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.LOCKED);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closeRegister(null, "mcurie", "1111");
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(view).data("error", "COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD");
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code closeRegister(...)} names the LOCKOUT when the OPERATOR's own
     * account is blocked. This is the definitive block: the shift is over, the
     * code is right, and the register used to answer "code opérateur incorrect"
     * for the whole lockout window (outcome LOCKED arm of the password branch).
     */
    @Test
    void closeRegisterNamesALockedOperatorAccount() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        when(resource.sessionCloseService.passwordOutcome(resource.state, "1234"))
                .thenReturn(AuthService.CheckOutcome.LOCKED);
        TemplateInstance view = stubClose(resource);
        Response response = resource.closeRegister("1234", null, null);
        assertEquals(200, response.getStatus());
        assertSame(view, response.getEntity());
        verify(view).data("error", "COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD");
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * A DENIED operator code still shows the ordinary refusal: the lockout
     * message is for the lockout only (outcome DENIED arm of the same branch).
     */
    @Test
    void closeRegisterStillBlamesTheCodeOnAPlainRefusal() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PASSWORD);
        when(resource.sessionCloseService.passwordOutcome(resource.state, "9999"))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        TemplateInstance view = stubClose(resource);
        resource.closeRegister("9999", null, null);
        verify(view).data("error", "Code opérateur incorrect.");
    }

    /**
     * A DENIED supervisor still shows the ordinary authorization refusal.
     */
    @Test
    void closeRegisterStillRefusesAPlainSupervisorRefusal() {
        AuthResource resource = newResource();
        resource.state.auth.operatorBadgeId = "007";
        when(resource.sessionCloseService.obstacle(any()))
                .thenReturn(SessionCloseService.Obstacle.PENDING_TICKETS);
        when(resource.endorsementService.authorizeOutcome("mcurie", "bad",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        TemplateInstance view = stubClose(resource);
        resource.closeRegister(null, "mcurie", "bad");
        verify(view).data("error", "Autorisation superviseur refusée.");
    }

    // --- lockRegister (verrouillage) ---

    /**
     * {@code lockRegister()} pauses the register through the service and redirects
     * to the lock screen (EXP-12).
     */
    @Test
    void lockRegisterPausesAndRedirectsToLock() {
        AuthResource resource = newResource();
        Response response = resource.lockRegister();
        verify(resource.authService).pause(resource.state);
        verify(resource.state).touch();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/lock", response.getLocation().toString());
    }

    // --- unlock (resume of a paused register) ---

    /**
     * A resume of a paused register drops straight onto the sale — no hardware
     * gate, no drawer pulse — when no forced PIN change is pending
     * (wasPaused true, passwordChangeRequired false arm).
     */
    @Test
    void unlockResumesPausedRegisterToSale() {
        AuthResource resource = newResource();
        when(resource.state.auth.isPaused()).thenReturn(true);
        resource.state.auth.passwordChangeRequired = false;
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        Response response = resource.unlock("alice", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state).touch();
        verify(resource.hardwareService, never()).openDrawer();
    }

    /**
     * A FORCED resume (EXP-14) lands on the mandatory PIN-change screen
     * (wasPaused true, passwordChangeRequired true arm).
     */
    @Test
    void unlockForcedResumeLandsOnPinChange() {
        AuthResource resource = newResource();
        when(resource.state.auth.isPaused()).thenReturn(true);
        resource.state.auth.passwordChangeRequired = true;
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        Response response = resource.unlock("alice", "1234");
        assertEquals("/pin-change", response.getLocation().toString());
        verify(resource.hardwareService, never()).openDrawer();
    }

    // --- forceUnlock (EXP-14) ---

    /**
     * A supervisor whose endorsement is refused cannot force the unlock: the
     * resume is never attempted and the lock screen shows the refusal
     * (authorize false arm, short-circuit).
     */
    @Test
    void forceUnlockRefusedWhenSupervisorDenied() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "bad",
                AuthService.FORCE_UNLOCK_ACTION))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        Response response = resource.forceUnlock("mcurie", "bad");
        assertEquals("/lock?error=supervisor", response.getLocation().toString());
        verify(resource.authService, never()).forceResume(any());
    }

    /**
     * An endorsed supervisor whose forced resume finds no paused register still
     * ends on the lock screen (authorize true, forceResume false arm).
     */
    @Test
    void forceUnlockRefusedWhenNotPaused() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                AuthService.FORCE_UNLOCK_ACTION))
                .thenReturn(AuthService.CheckOutcome.GRANTED);
        when(resource.authService.forceResume(resource.state)).thenReturn(false);
        Response response = resource.forceUnlock("mcurie", "1111");
        assertEquals("/lock?error=supervisor", response.getLocation().toString());
    }

    /**
     * An endorsed supervisor forcing the resume of a paused register lands the
     * cashier on the mandatory PIN-change screen (both true arm).
     */
    @Test
    void forceUnlockSucceedsRedirectsToPinChange() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                AuthService.FORCE_UNLOCK_ACTION))
                .thenReturn(AuthService.CheckOutcome.GRANTED);
        when(resource.authService.forceResume(resource.state)).thenReturn(true);
        Response response = resource.forceUnlock("mcurie", "1111");
        assertEquals("/pin-change", response.getLocation().toString());
        verify(resource.state).touch();
    }

    /**
     * A supervisor under PIN lockout is sent to the lock screen with the
     * LOCKOUT code, not the refusal code: the screen then names the block
     * instead of suggesting the credentials were wrong (outcome LOCKED arm).
     */
    @Test
    void forceUnlockNamesALockedSupervisorAccount() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                AuthService.FORCE_UNLOCK_ACTION))
                .thenReturn(AuthService.CheckOutcome.LOCKED);
        Response response = resource.forceUnlock("mcurie", "1111");
        assertEquals("/lock?error=locked", response.getLocation().toString());
        verify(resource.authService, never()).forceResume(any());
    }

    // --- forceCloseAbandoned (EXP-96) ---

    /**
     * An endorsed supervisor forces the fin de journée of an abandoned post: the
     * register is closed and returns to the lock screen (authorize true arm).
     */
    @Test
    void forceCloseAbandonedClosesOnEndorsement() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.GRANTED);
        Response response = resource.forceCloseAbandoned("mcurie", "1111");
        assertEquals("/lock", response.getLocation().toString());
        verify(resource.sessionCloseService).close(resource.state, "poste abandonné — superviseur");
        verify(resource.state).touch();
    }

    /**
     * A refused supervisor cannot force the fin de journée: nothing is closed and
     * the lock screen shows the refusal (authorize false arm).
     */
    @Test
    void forceCloseAbandonedRefusedOnBadSupervisor() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "bad",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.DENIED);
        Response response = resource.forceCloseAbandoned("mcurie", "bad");
        assertEquals("/lock?error=supervisor", response.getLocation().toString());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    // --- lockPage (paused keeps the pause; supervisor error message) ---

    /**
     * {@code lockPage()} on a PAUSED register does NOT log the operator out — the
     * pause keeps the identity and the ticket (EXP-12, isPaused true arm).
     */
    @Test
    void lockPageKeepsThePauseWhenPaused() {
        AuthResource resource = newResource();
        when(resource.state.auth.isPaused()).thenReturn(true);
        TemplateInstance view = stubLock(resource, null);
        assertSame(view, resource.lockPage(null));
        verify(resource.authService, never()).logout(any());
    }

    /**
     * {@code lockPage("supervisor")} renders the supervisor-refusal message (the
     * new error arm).
     */
    @Test
    void lockPageShowsSupervisorRefusalMessage() {
        AuthResource resource = newResource();
        TemplateInstance view = stubLock(resource, "AUTORISATION SUPERVISEUR REFUSÉE");
        assertSame(view, resource.lockPage("supervisor"));
        verify(resource.authService).logout(resource.state);
    }

    /**
     * BO-03-09-01: a checkout point DEACTIVATED in the back office refuses the
     * prise de poste whoever presents themselves, and refuses it BEFORE the
     * credentials are even looked at — telling an operator their code is wrong
     * when the register is out of service sends them looking in the wrong place.
     * No drawer pulse, no opening receipt, no login attempt.
     */
    @Test
    void unlockIsRefusedWhenTheCheckoutPointIsDeactivated() {
        AuthResource resource = newResource();
        when(resource.checkoutPointService.isDeactivated()).thenReturn(true);
        Response response = resource.unlock("alice", "1234");
        assertEquals("/lock?error=deactivated", response.getLocation().toString());
        verify(resource.authService, never()).login(any(), anyString(), anyString());
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.sessionCloseService, never()).printOpening(any());
    }

    /**
     * The lock page names the cause of that refusal rather than showing the
     * generic credential error: a lane that only answers "identifiants
     * incorrects" reads as a broken lane, and someone spends the morning on the
     * credentials.
     */
    @Test
    void theLockPageNamesADeactivatedCheckoutPoint() {
        AuthResource resource = newResource();
        TemplateInstance view = stubLock(resource,
                "CAISSE HORS SERVICE - POINT DE CONTACT DÉSACTIVÉ AU BACK OFFICE");
        assertSame(view, resource.lockPage("deactivated"));
    }

    /**
     * A supervisor under PIN lockout forcing the fin de journée of an abandoned
     * post is sent back with the LOCKOUT code, and nothing is closed
     * (outcome LOCKED arm).
     */
    @Test
    void forceCloseAbandonedNamesALockedSupervisorAccount() {
        AuthResource resource = newResource();
        when(resource.endorsementService.authorizeOutcome("mcurie", "1111",
                SessionCloseService.FORCE_CLOSE_ACTION))
                .thenReturn(AuthService.CheckOutcome.LOCKED);
        Response response = resource.forceCloseAbandoned("mcurie", "1111");
        assertEquals("/lock?error=locked", response.getLocation().toString());
        verify(resource.sessionCloseService, never()).close(any(), any());
    }

    /**
     * {@code /supervisor-badge-data} ARMS the supervisor mailbox and returns
     * nothing when no badge was presented: the arming is what the poll is for,
     * the collection is a bonus.
     */
    @Test
    void supervisorBadgeDataArmsTheMailbox() {
        AuthResource resource = newResource();
        // A REAL auth state here: the arming and the one-shot mailbox are the
        // behaviour under test, and a mock of AuthState has neither.
        resource.state.auth = new AuthState();
        java.util.Map<String, Object> data = resource.getSupervisorBadgeData();
        assertEquals("", data.get("scannedBadge"));
        assertTrue(resource.state.auth.supervisorBadgeArmed());
    }

    /**
     * {@code /supervisor-badge-data} hands over a presented badge and empties
     * the mailbox, so the next poll does not replay it — while leaving the
     * arming standing for a second attempt.
     */
    @Test
    void supervisorBadgeDataHandsOverThePresentedBadgeOnce() {
        AuthResource resource = newResource();
        resource.state.auth = new AuthState();
        resource.state.auth.setSupervisorBadge("BADGE-9");
        assertEquals("BADGE-9", resource.getSupervisorBadgeData().get("scannedBadge"));
        assertEquals("", resource.getSupervisorBadgeData().get("scannedBadge"));
        assertTrue(resource.state.auth.supervisorBadgeArmed());
    }
}
