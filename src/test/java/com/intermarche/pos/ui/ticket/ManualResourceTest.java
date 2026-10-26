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
        resource.posSettings = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettings.touchGridColumns()).thenReturn(4);
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
        when(withItems.data("gridColumns", 4)).thenReturn(withItems);
        when(withItems.data("gridRows", 1)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        when(withIsRoot.data("parentUrl", null)).thenReturn(withParentUrl);
        when(withParentUrl.data("page", viewData.page)).thenReturn(withPage);
        when(withPage.data("totalPages", viewData.totalPages)).thenReturn(withTotalPages);
        when(withTotalPages.data("prevUrl", viewData.prevUrl)).thenReturn(withPrevUrl);
        when(withPrevUrl.data("nextUrl", viewData.nextUrl)).thenReturn(withNextUrl);
        when(withNextUrl.data("articleSort", "DEFAULT")).thenReturn(withNextUrl);
        when(withNextUrl.data("showArticleEan", false)).thenReturn(withNextUrl);
        when(withNextUrl.data("cancelMode", false)).thenReturn(withNextUrl);
        when(withNextUrl.data("unknownCode", "")).thenReturn(withNextUrl);
        assertSame(withNextUrl, resource.manualPage(2, false, null));
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
        when(resource.manualService.getManualCategoryData("F", 1, ManualService.ArticleSort.DEFAULT)).thenReturn(viewData);
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
        when(withItems.data("gridColumns", 4)).thenReturn(withItems);
        when(withItems.data("gridRows", 1)).thenReturn(withItems);
        when(withItems.data("breadcrumb", viewData.breadcrumb)).thenReturn(withBreadcrumb);
        when(withBreadcrumb.data("isRoot", viewData.isRoot)).thenReturn(withIsRoot);
        when(withIsRoot.data("parentUrl", viewData.parentUrl)).thenReturn(withParentUrl);
        when(withParentUrl.data("page", viewData.page)).thenReturn(withPage);
        when(withPage.data("totalPages", viewData.totalPages)).thenReturn(withTotalPages);
        when(withTotalPages.data("prevUrl", viewData.prevUrl)).thenReturn(withPrevUrl);
        when(withPrevUrl.data("nextUrl", viewData.nextUrl)).thenReturn(withNextUrl);
        when(withNextUrl.data("articleSort", "DEFAULT")).thenReturn(withNextUrl);
        when(withNextUrl.data("showArticleEan", false)).thenReturn(withNextUrl);
        when(withNextUrl.data("cancelMode", false)).thenReturn(withNextUrl);
        assertSame(withNextUrl, resource.manualCategoryPage("F", 1, null, false));
    }

    /** LC-02-07-14/15: real group tiles render an inline image only when present, with the article name and selection code intact. */
    @Test
    void groupTileTemplateRendersOptionalInlineImages() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/manual.html"));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver()).build();
        engine.parse(source);
        int start = source.indexOf("{#for item in items}");
        int end = source.indexOf("{/for}", start) + "{/for}".length();
        var illustrated = new ManualService.ManualItem("ILLUSTRATED", false, null, "301", "size-normal", false);
        illustrated.imageData = "data:image/png;base64,THUMBNAIL";
        illustrated.priceFormatted = "2,35 EUR";
        var plain = new ManualService.ManualItem("PLAIN", false, null, "302", "size-normal", false);
        var category = new ManualService.ManualItem("GROUP", true, "/manual/cat/G", null, "size-normal", false);
        String html = engine.parse(source.substring(start, end)).data("items", java.util.List.of(illustrated, plain, category)).data("articleSort", "CODE_ASC").data("showArticleEan", true).data("cancelMode", false).render();
        org.junit.jupiter.api.Assertions.assertEquals(1, html.split("<img ", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("src=\"data:image/png;base64,THUMBNAIL\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("ILLUSTRATED"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("2,35 EUR"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("PLAIN"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("'301'"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("href=\"/manual/cat/G\""));
    }


    /** LC-02-07-02: wide tiles cannot spill into clipped rows, and page sizes above eleven remain visible. */
    @Test
    void rowsIncludeNavigationWideTilesAndConfiguredPageSize() {
        var normal = new ManualService.ManualItem("N", true, "/", null, "size-normal", false);
        var wide = new ManualService.ManualItem("W", true, "/", null, "size-large", true);
        org.junit.jupiter.api.Assertions.assertEquals(1, ManualResource.gridRows(List.of(), 3));
        org.junit.jupiter.api.Assertions.assertEquals(2, ManualResource.gridRows(List.of(normal, wide), 3));
        org.junit.jupiter.api.Assertions.assertEquals(1, ManualResource.gridRows(List.of(wide), 3));
        org.junit.jupiter.api.Assertions.assertEquals(2, ManualResource.gridRows(List.of(wide), 2));
        org.junit.jupiter.api.Assertions.assertEquals(7, ManualResource.gridRows(java.util.Collections.nCopies(24, normal), 4));
    }

    /** LC-02-07-18: explicit ordering reaches the service and the selected option reaches the template. */
    @Test
    void categorySortIsWhitelistedAndRenderedWithoutSessionState() {
        ManualResource resource = newResource();
        TemplateInstance template = mock(TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(resource.manual.data("state", resource.state)).thenReturn(template);
        var data = new ManualService.ManualViewData(List.of(), "Group", false, "/manual", 2, 3, "prev", "next");
        when(resource.manualService.getManualCategoryData("F", 2, ManualService.ArticleSort.CODE_DESC)).thenReturn(data);
        assertSame(template, resource.manualCategoryPage("F", 2, "CODE_DESC", false));
        org.mockito.Mockito.verify(template).data("articleSort", "CODE_DESC");
        org.mockito.Mockito.verify(resource.manualService).getManualCategoryData("F", 2, ManualService.ArticleSort.CODE_DESC);
    }

    /** LC-02-07-18/-08: sort selection is independent of the BackOffice EAN visibility flag. */
    @Test
    void sortTemplateHasSelectedModeAndNoRootControl() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/manual.html"));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver()).build();
        int start = source.indexOf("{#if !isRoot}");
        int end = source.indexOf("</form>", start) + "</form>".length();
        String controls = source.substring(start, end) + "{/if}";
        // Operator keys are rendered elsewhere; their resolver is deliberately replaced only in this fragment.
        controls = controls.replaceAll("\\{inject:i18n.get\\('[^']+'\\)\\}", "LABEL");
        for (var sort : ManualService.ArticleSort.values()) {
            String html = engine.parse(controls).data("isRoot", false).data("articleSort", sort.name()).data("cancelMode", false).render();
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("value=\"" + sort.name() + "\" selected"));
            org.junit.jupiter.api.Assertions.assertEquals(1, html.split(" selected", -1).length - 1);
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("method=\"get\""));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("name=\"page\""));
        }
        org.junit.jupiter.api.Assertions.assertTrue(engine.parse(controls).data("isRoot", true).render().isBlank());
        start = source.indexOf("{#for item in items}");
        end = source.indexOf("{/for}", start) + "{/for}".length();
        var item = new ManualService.ManualItem("A", false, null, "300", "size-normal", false);
        for (var sort : ManualService.ArticleSort.values()) {
            String html = engine.parse(source.substring(start, end)).data("items", List.of(item))
                    .data("articleSort", sort.name()).data("showArticleEan", true).data("cancelMode", false).render();
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"article-code\""));
            String hidden = engine.parse(source.substring(start, end)).data("items", List.of(item))
                    .data("articleSort", sort.name()).data("showArticleEan", false).data("cancelMode", false).render();
            org.junit.jupiter.api.Assertions.assertFalse(hidden.contains("class=\"article-code\""));
        }
    }


    /** LC-02-07-08: every category order receives the same administered visibility flag. */
    @Test
    void backOfficeEanVisibilityAppliesToEverySort() {
        ManualResource resource = newResource();
        TemplateInstance template = mock(TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(resource.manual.data("state", resource.state)).thenReturn(template);
        when(resource.posSettings.touchShowEan()).thenReturn(true);
        var data = new ManualService.ManualViewData(List.of(), "Group", false, "/manual", 1, 1, null, null);
        for (var sort : ManualService.ArticleSort.values()) {
            when(resource.manualService.getManualCategoryData("F", 1, sort)).thenReturn(data);
            assertSame(template, resource.manualCategoryPage("F", 1, sort.name(), false));
        }
        org.mockito.Mockito.verify(template, org.mockito.Mockito.times(5)).data("showArticleEan", true);
    }

    /** LC-02-07-08: visible EANs preserve leading zeros, while absent/empty identifiers create no empty badge. */
    @Test
    void articleCodeRenderingHonorsTheFlagAndPreservesTheExactEan() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/manual.html"));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver()).build();
        int start = source.indexOf("{#for item in items}");
        int end = source.indexOf("{/for}", start) + "{/for}".length();
        var known = new ManualService.ManualItem("Known", false, null, "0012345678905", "size-normal", false);
        var absent = new ManualService.ManualItem("Absent", false, null, null, "size-normal", false);
        var empty = new ManualService.ManualItem("Empty", false, null, "", "size-normal", false);
        String html = engine.parse(source.substring(start, end)).data("items", List.of(known, absent, empty))
                .data("articleSort", "DEFAULT").data("showArticleEan", true).data("cancelMode", false).render();
        org.junit.jupiter.api.Assertions.assertEquals(1, html.split("class=\"article-code\"", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains(">0012345678905</span>"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Absent"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Empty"));
    }


    /** LC-02-12-05: cancellation context reaches root and category pages without persistent register state. */
    @Test
    void cancellationContextIsPassedToBothViews() {
        ManualResource resource = newResource();
        TemplateInstance template = mock(TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(resource.manual.data("state", resource.state)).thenReturn(template);
        var root = new ManualService.ManualViewData(List.of(), "Root", true, null, 1, 1, null, null);
        when(resource.manualService.getManualRootData(1)).thenReturn(root);
        when(resource.manualService.getManualCategoryData("G", 1, ManualService.ArticleSort.DEFAULT)).thenReturn(root);
        assertSame(template, resource.manualPage(1, true, "ABC999"));
        org.mockito.Mockito.verify(template).data("unknownCode", "ABC999");
        assertSame(template, resource.manualCategoryPage("G", 1, null, true));
        org.mockito.Mockito.verify(template, org.mockito.Mockito.times(2)).data("cancelMode", true);
    }

    /** LC-02-12-05: all navigation keeps cancellation context, and selection posts to cancellation rather than sale admission. */
    @Test
    void cancellationTemplateHasItsOwnActionAndNavigation() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/manual.html"));
        source = source.replace("{#include theme /}", "").replace("{#include keyboard-lib /}", "")
                .replaceAll("\\{inject:i18n\\.(?:get|format)\\('[^']+'[^}]*\\)\\}", "LABEL");
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver()).build();
        var group = new ManualService.ManualItem("Group", true, "/manual/cat/G", null, "size-normal", false);
        var item = new ManualService.ManualItem("Milk", false, null, "300", "size-normal", false);
        for (boolean root : List.of(true, false)) {
            String html = engine.parse(source).data("posTheme", "light").data("cancelMode", true)
                    .data("items", List.of(group, item)).data("isRoot", root).data("breadcrumb", "Group")
                    .data("gridColumns", 3).data("gridRows", 2).data("articleSort", "CODE_ASC")
                    .data("showArticleEan", true).data("parentUrl", "/manual")
                    .data("page", 2).data("totalPages", 3).data("prevUrl", "/manual?page=1")
                    .data("nextUrl", "/manual?page=3").render();
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("href=\"/manual/cat/G?cancel=true\""));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("href=\"/manual?page=3&amp;cancel=true\""));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("action=\"/action/cancel-group-article\""));
            org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"price-section\" style=\"display:none;\""));
            org.junit.jupiter.api.Assertions.assertFalse(html.contains("selectProduct(this, 'LABEL'"));
            if (!root) org.junit.jupiter.api.Assertions.assertTrue(html.contains("name=\"cancel\" value=\"true\""));
        }
    }

}
