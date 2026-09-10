package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ManualResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, the
 * {@link ManualService} and the {@code manual} Qute {@link Template}. Every
 * collaborator is a Mockito mock: the template returns distinct
 * {@link TemplateInstance} mocks along the fluent {@code data(...)} chain so the
 * exact rendered view can be identified. Tests assert absolute expected values.
 */
class ManualResourceTest {

    /**
     * Builds a {@link ManualResource} whose collaborators are fresh mocks wired
     * onto its package-private fields.
     *
     * @return a resource with fully mocked state, service and template
     */
    private ManualResource newResource() {
        ManualResource resource = new ManualResource();
        resource.state = mock(PosState.class);
        resource.manualService = mock(ManualService.class);
        resource.manual = mock(Template.class);
        return resource;
    }

    /**
     * {@code manualPage(page)} renders the manual root grid seeded with the
     * state, the root tiles and the pager for the requested page.
     */
    @Test
    void manualPageRendersRootWithPager() {
        ManualResource resource = newResource();
        List<ManualService.ManualItem> tiles = List.of(
                new ManualService.ManualItem("Fruits", true, "/manual/cat/F", null, "size-normal", false));
        ManualService.ManualViewData viewData = new ManualService.ManualViewData(
                tiles, "Accueil", true, null, 2, 3, "/manual?page=1", "/manual?page=3");
        when(resource.manualService.getManualRootData(2)).thenReturn(viewData);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withBreadcrumb = mock(TemplateInstance.class);
        TemplateInstance withIsRoot = mock(TemplateInstance.class);
        TemplateInstance withParentUrl = mock(TemplateInstance.class);
        TemplateInstance withPage = mock(TemplateInstance.class);
        TemplateInstance withTotalPages = mock(TemplateInstance.class);
        TemplateInstance withPrevUrl = mock(TemplateInstance.class);
        TemplateInstance withNextUrl = mock(TemplateInstance.class);
        when(resource.manual.data("state", resource.state)).thenReturn(withState);
        when(withState.data("items", viewData.items)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        when(withIsRoot.data("parentUrl", null)).thenReturn(withParentUrl);
        when(withParentUrl.data("page", viewData.page)).thenReturn(withPage);
        when(withPage.data("totalPages", viewData.totalPages)).thenReturn(withTotalPages);
        when(withTotalPages.data("prevUrl", viewData.prevUrl)).thenReturn(withPrevUrl);
        when(withPrevUrl.data("nextUrl", viewData.nextUrl)).thenReturn(withNextUrl);
        assertSame(withNextUrl, resource.manualPage(2));
    }

    /**
     * {@code manualCategoryPage(code, page)} renders the category grid seeded with the
     * state, the category tiles, the way back up and the (single-page) pager.
     */
    @Test
    void manualCategoryPageRendersCategory() {
        ManualResource resource = newResource();
        List<ManualService.ManualItem> tiles = List.of(
                new ManualService.ManualItem("Banane", false, null, "3000", "size-normal", false));
        ManualService.ManualViewData viewData = new ManualService.ManualViewData(
                tiles, "Accueil > Fruits", false, "/manual", 1, 1, null, null);
        when(resource.manualService.getManualCategoryData("F", 1)).thenReturn(viewData);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withBreadcrumb = mock(TemplateInstance.class);
        TemplateInstance withIsRoot = mock(TemplateInstance.class);
        TemplateInstance withParentUrl = mock(TemplateInstance.class);
        TemplateInstance withPage = mock(TemplateInstance.class);
        TemplateInstance withTotalPages = mock(TemplateInstance.class);
        TemplateInstance withPrevUrl = mock(TemplateInstance.class);
        TemplateInstance withNextUrl = mock(TemplateInstance.class);
        when(resource.manual.data("state", resource.state)).thenReturn(withState);
        when(withState.data("items", viewData.items)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        when(withIsRoot.data("parentUrl", viewData.parentUrl)).thenReturn(withParentUrl);
        when(withParentUrl.data("page", viewData.page)).thenReturn(withPage);
        when(withPage.data("totalPages", viewData.totalPages)).thenReturn(withTotalPages);
        when(withTotalPages.data("prevUrl", viewData.prevUrl)).thenReturn(withPrevUrl);
        when(withPrevUrl.data("nextUrl", viewData.nextUrl)).thenReturn(withNextUrl);
        assertSame(withNextUrl, resource.manualCategoryPage("F", 1));
    }
}
