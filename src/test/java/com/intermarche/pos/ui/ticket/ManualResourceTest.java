package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ManualResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, the
 * {@link ManualService} and two Qute {@link Template}s ({@code manual} and
 * {@code lock}). Every collaborator is a Mockito mock: the templates return
 * distinct {@link TemplateInstance} mocks along the fluent {@code data(...)}
 * chain so the exact rendered view can be identified, and {@code PosState}
 * exposes its {@code isLocked()} decision. Tests assert absolute expected
 * values and cover both arms of each lock guard.
 */
class ManualResourceTest {

    /**
     * Builds a {@link ManualResource} whose collaborators are fresh mocks
     * wired onto its package-private fields.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private ManualResource newResource() {
        ManualResource resource = new ManualResource();
        resource.state = mock(PosState.class);
        resource.manualService = mock(ManualService.class);
        resource.manual = mock(Template.class);
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * {@code manualPage()} renders the lock page seeded with the state and
     * never queries the drill-down service when the terminal is locked
     * (guard true arm).
     */
    @Test
    void manualPageRendersLockWhenLocked() {
        ManualResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(lockView);
        assertSame(lockView, resource.manualPage());
        verifyNoInteractions(resource.manualService);
        verifyNoInteractions(resource.manual);
    }

    /**
     * {@code manualPage()} renders the manual root grid seeded with the state
     * and the root view data when the terminal is unlocked (guard false arm).
     */
    @Test
    void manualPageRendersManualWhenUnlocked() {
        ManualResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<ManualService.ManualItem> tiles = List.of(new ManualService.ManualItem("Fruits", true, "/manual/cat/F", null));
        ManualService.ManualViewData viewData = new ManualService.ManualViewData(tiles, "Accueil", true, null);
        when(resource.manualService.getManualRootData()).thenReturn(viewData);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withBreadcrumb = mock(TemplateInstance.class);
        TemplateInstance withIsRoot = mock(TemplateInstance.class);
        when(resource.manual.data("state", resource.state)).thenReturn(withState);
        when(withState.data("items", viewData.items)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        assertSame(withIsRoot, resource.manualPage());
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code manualCategoryPage(code)} renders the lock page seeded with the
     * state and never queries the drill-down service when the terminal is
     * locked (guard true arm).
     */
    @Test
    void manualCategoryPageRendersLockWhenLocked() {
        ManualResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(lockView);
        assertSame(lockView, resource.manualCategoryPage("F"));
        verifyNoInteractions(resource.manualService);
        verifyNoInteractions(resource.manual);
    }

    /**
     * {@code manualCategoryPage(code)} renders the category grid seeded with
     * the state and the category view data, including the way back up, when
     * the terminal is unlocked (guard false arm).
     */
    @Test
    void manualCategoryPageRendersManualWhenUnlocked() {
        ManualResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<ManualService.ManualItem> tiles = List.of(new ManualService.ManualItem("Banane", false, null, "3000"));
        ManualService.ManualViewData viewData = new ManualService.ManualViewData(tiles, "Accueil > Fruits", false, "/manual");
        when(resource.manualService.getManualCategoryData("F")).thenReturn(viewData);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withBreadcrumb = mock(TemplateInstance.class);
        TemplateInstance withIsRoot = mock(TemplateInstance.class);
        TemplateInstance withParentUrl = mock(TemplateInstance.class);
        when(resource.manual.data("state", resource.state)).thenReturn(withState);
        when(withState.data("items", viewData.items)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        when(withIsRoot.data("parentUrl", viewData.parentUrl)).thenReturn(withParentUrl);
        assertSame(withParentUrl, resource.manualCategoryPage("F"));
        verifyNoInteractions(resource.lock);
    }
}
