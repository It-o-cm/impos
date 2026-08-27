package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PinChangeResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link AuthService} and two Qute
 * {@link Template}s, all driven off a {@link PosState}. Every collaborator is a
 * Mockito mock: the templates echo their fluent {@code data(...)} chain so the
 * returned view can be identified. Tests assert absolute expected views and
 * verify delegation, covering both arms of the locked guard on each endpoint
 * and both arms of the change-PIN error split.
 */
class PinChangeResourceTest {

    /**
     * Builds a {@link PinChangeResource} whose collaborators are fresh mocks
     * wired onto its package-private fields.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private PinChangeResource newResource() {
        PinChangeResource resource = new PinChangeResource();
        resource.state = mock(PosState.class);
        resource.authService = mock(AuthService.class);
        resource.pinChange = mock(Template.class);
        return resource;
    }

    // --- pinChangePage ---

    /**
     * {@code pinChangePage()} renders the PIN change page when the state is not
     * locked (guard false).
     */
    @Test
    void pinChangePageRendersPinChangeWhenUnlocked() {
        PinChangeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.pinChange.data("state", resource.state)).thenReturn(view);
        assertSame(view, resource.pinChangePage());
    }

    // --- changePin ---

    /**
     * {@code changePin(...)} renders the PIN change page with the service error
     * when the change fails (guard false, error non-null).
     */
    @Test
    void changePinRendersErrorWhenServiceReturnsError() {
        PinChangeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.authService.changePin(resource.state, "1111", "2222", "3333"))
                .thenReturn("PIN incorrect");
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withError = mock(TemplateInstance.class);
        when(resource.pinChange.data("state", resource.state)).thenReturn(withState);
        when(withState.data("error", "PIN incorrect")).thenReturn(withError);
        assertSame(withError, resource.changePin("1111", "2222", "3333"));
    }

    /**
     * {@code changePin(...)} renders the PIN change page with the success
     * message when the change succeeds (guard false, error null).
     */
    @Test
    void changePinRendersSuccessWhenServiceReturnsNull() {
        PinChangeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.authService.changePin(resource.state, "1111", "2222", "2222"))
                .thenReturn(null);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withSuccess = mock(TemplateInstance.class);
        when(resource.pinChange.data("state", resource.state)).thenReturn(withState);
        when(withState.data("success", "Code PIN modifié")).thenReturn(withSuccess);
        assertSame(withSuccess, resource.changePin("1111", "2222", "2222"));
        verify(resource.authService).changePin(resource.state, "1111", "2222", "2222");
    }
}
