package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the SAISIE DIRECTE drill-down: root level and category
 * levels. Adding the chosen product goes through the ticket actions.
 */
@Path("/")
@DrawerMustBeClosed
public class ManualResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ManualResource.class);

    @Inject Template manual;
    @Inject ManualService manualService;

    @Inject
    PosState state;

    /** LC-02-07-02: the administered touch layout. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettings;


    @GET
    @Path("/manual")
    /**
     * Shows the root level of the drill-down, on the requested group-grid page.
     *
     * @param cancelMode true for the one-unit cancellation consultation
     * @param page the 1-based group-grid page, defaulting to the first
     * @param unknownCode the unknown code the scan chained here with, or null
     *        (BO-10-02-14)
     * @return the manual page
     */
    public TemplateInstance manualPage(@jakarta.ws.rs.QueryParam("page")
                                       @jakarta.ws.rs.DefaultValue("1") int page,
                                       @jakarta.ws.rs.QueryParam("cancel") @jakarta.ws.rs.DefaultValue("false") boolean cancelMode,
                                       @jakarta.ws.rs.QueryParam("unknown") String unknownCode) {
        LOGGER.info("Entering method manualPage with page: " + page);
        ManualService.ManualViewData viewData = manualService.getManualRootData(page);
        LOGGER.info("Exiting method manualPage");
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("gridColumns", posSettings.touchGridColumns())
                .data("gridRows", gridRows(viewData.items, posSettings.touchGridColumns()))
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot)
                .data("parentUrl", null)
                .data("page", viewData.page)
                .data("totalPages", viewData.totalPages)
                .data("prevUrl", viewData.prevUrl)
                .data("nextUrl", viewData.nextUrl)
                .data("articleSort", "DEFAULT")
                .data("showArticleEan", posSettings.touchShowEan())
                .data("cancelMode", cancelMode)
                .data("unknownCode", unknownCode == null ? "" : unknownCode);
    }

    @GET
    @Path("/manual/cat/{code}")
    /**
     * Shows one category level of the drill-down.
     *
     * @param code the family code
     * @param cancelMode true for cancellation instead of sale admission
     * @param requestedSort temporary article order, absent when entering the group normally
     * @param page the 1-based page of the grid, defaulting to the first
     * @return the manual page
     */
    public TemplateInstance manualCategoryPage(@PathParam("code") String code,
                                               @jakarta.ws.rs.QueryParam("page")
                                               @jakarta.ws.rs.DefaultValue("1") int page,
                                               @jakarta.ws.rs.QueryParam("sort") String requestedSort,
                                               @jakarta.ws.rs.QueryParam("cancel") @jakarta.ws.rs.DefaultValue("false") boolean cancelMode) {
        LOGGER.info("Entering method manualCategoryPage with code: " + code + ", page: " + page);
        ManualService.ArticleSort sort = ManualService.ArticleSort.parse(requestedSort);
        ManualService.ManualViewData viewData = manualService.getManualCategoryData(code, page, sort);
        LOGGER.info("Exiting method manualCategoryPage");
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("gridColumns", posSettings.touchGridColumns())
                .data("gridRows", gridRows(viewData.items, posSettings.touchGridColumns()))
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot)
                .data("parentUrl", viewData.parentUrl)
                .data("page", viewData.page)
                .data("totalPages", viewData.totalPages)
                .data("prevUrl", viewData.prevUrl)
                .data("nextUrl", viewData.nextUrl)
                .data("articleSort", sort.name())
                .data("showArticleEan", posSettings.touchShowEan())
                .data("cancelMode", cancelMode);
    }

    /** LC-02-07-02: reserves enough visible rows, including wide pinned groups and navigation.
     * @param items ordered content tiles
     * @param columns administered grid width
     * @return rows required by CSS grid's sequential placement
     */
    static int gridRows(java.util.List<ManualService.ManualItem> items, int columns) {
        int cells = 1;
        for (var item : items) {
            int span = "size-large".equals(item.sizeClass) ? 2 : 1;
            if (span == 2 && cells % columns == columns - 1) {
                cells++;
            }
            cells += span;
        }
        return (cells + columns - 1) / columns;
    }
}