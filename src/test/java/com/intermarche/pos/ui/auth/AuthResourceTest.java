package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        resource.state = mock(PosState.class);
        resource.state.auth = mock(AuthState.class);
        resource.authService = mock(AuthService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.main = mock(Template.class);
        resource.lock = mock(Template.class);
        resource.hardwareUnavailable = mock(Template.class);
        // The drawer-open-on-login rule defaults to ON, the pre-existing
        // pulse behavior the success cases rely on (BO-10-02-25).
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettingsService.drawerOpenOnLogin()).thenReturn(true);
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
}
