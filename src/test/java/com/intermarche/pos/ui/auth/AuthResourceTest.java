package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
        return resource;
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
     * {@code unlock(...)} opens the drawer and redirects to the session screen
     * on success when not in training and no session is open (both conditions
     * of the short-circuit true).
     */
    @Test
    void unlockSuccessLandsOnSessionWhenNoOpenSession() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        Response response = resource.unlock("alice", "1234");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/session", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
    }

    /**
     * {@code unlock(...)} opens the drawer and redirects home on success when a
     * session is already open (first condition true, second condition false).
     */
    @Test
    void unlockSuccessLandsHomeWhenSessionOpen() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        resource.state.trainingMode = false;
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        Response response = resource.unlock("alice", "1234");
        assertEquals("/", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
    }

    /**
     * {@code unlock(...)} opens the drawer and redirects home on success in
     * training mode, never consulting the session service (first condition of
     * the short-circuit false).
     */
    @Test
    void unlockSuccessLandsHomeInTrainingMode() {
        AuthResource resource = newResource();
        when(resource.authService.login(resource.state, "alice", "1234"))
                .thenReturn(AuthService.LoginResult.SUCCESS);
        resource.state.trainingMode = true;
        Response response = resource.unlock("alice", "1234");
        assertEquals("/", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
        verify(resource.cashSessionService, never()).getOpenSession();
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
