package com.intermarche.pos.ui;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThemeResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, {@link ThemeService}
 * and two Qute {@link Template}s. Every collaborator is a Mockito mock: the
 * templates only need to echo a {@link TemplateInstance} so the returned view can
 * be identified, {@code PosState} only exposes its {@code isLocked()} decision, and
 * {@code ThemeService} only supplies the current theme and receives the persisted
 * choice. Tests assert absolute expected values and verify delegation, covering both
 * arms of each lock guard.
 */
class ThemeResourceTest {

    /**
     * Builds a {@link ThemeResource} whose four collaborators are fresh mocks wired
     * onto its package-private fields so tests can both drive and verify them.
     *
     * @return a resource with mocked state, service and templates
     */
    private ThemeResource newResource() {
        ThemeResource resource = new ThemeResource();
        resource.state = mock(PosState.class);
        resource.themeService = mock(ThemeService.class);
        resource.themeSelect = mock(Template.class);
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * {@code themeSelectPage()} renders the lock page (and nothing else) when the
     * terminal is locked.
     */
    @Test
    void themeSelectPageReturnsLockWhenLocked() {
        ThemeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(lockView);
        TemplateInstance result = resource.themeSelectPage();
        assertSame(lockView, result);
        verifyNoInteractions(resource.themeSelect);
        verifyNoInteractions(resource.themeService);
    }

    /**
     * {@code themeSelectPage()} renders the selection page seeded with the theme
     * list and the current theme when the terminal is unlocked.
     */
    @Test
    void themeSelectPageReturnsSelectionWhenUnlocked() {
        ThemeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.themeService.currentTheme()).thenReturn("clair");
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.themeSelect.data(eq("state"), any())).thenReturn(view);
        when(view.data(eq("themes"), any())).thenReturn(view);
        when(view.data(eq("current"), any())).thenReturn(view);
        TemplateInstance result = resource.themeSelectPage();
        assertSame(view, result);
        verify(resource.themeSelect).data("state", resource.state);
        verify(view).data("themes", ThemeService.AVAILABLE_THEMES);
        verify(view).data("current", "clair");
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code chooseTheme()} persists the operator's choice and redirects to the sale
     * screen when the terminal is unlocked.
     */
    @Test
    void chooseThemePersistsAndRedirectsWhenUnlocked() {
        ThemeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.chooseTheme("clair");
        verify(resource.themeService).setThemeForOperator("clair");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code chooseTheme()} skips persistence but still redirects to the sale screen
     * when the terminal is locked.
     */
    @Test
    void chooseThemeSkipsPersistWhenLocked() {
        ThemeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.chooseTheme("clair");
        verifyNoInteractions(resource.themeService);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }
}
