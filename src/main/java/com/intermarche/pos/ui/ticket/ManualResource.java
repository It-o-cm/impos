package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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

    @GET
    @Path("/manual")
    /**
     * Shows the root level of the drill-down, on the requested group-grid page.
     *
     * @param page the 1-based group-grid page, defaulting to the first
     * @return the manual page
     */
    public TemplateInstance manualPage(@jakarta.ws.rs.QueryParam("page")
                                       @jakarta.ws.rs.DefaultValue("1") int page) {
        LOGGER.info("Entering method manualPage with page: " + page);
        ManualService.ManualViewData viewData = manualService.getManualRootData(page);
        LOGGER.info("Exiting method manualPage");
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot)
                .data("parentUrl", null)
                .data("page", viewData.page)
                .data("totalPages", viewData.totalPages)
                .data("prevUrl", viewData.prevUrl)
                .data("nextUrl", viewData.nextUrl);
    }

    @GET
    @Path("/manual/cat/{code}")
    /**
     * Shows one category level of the drill-down.
     *
     * @param code the family code
     * @param page the 1-based page of the grid, defaulting to the first
     * @return the manual page
     */
    public TemplateInstance manualCategoryPage(@PathParam("code") String code,
                                               @jakarta.ws.rs.QueryParam("page")
                                               @jakarta.ws.rs.DefaultValue("1") int page) {
        LOGGER.info("Entering method manualCategoryPage with code: " + code + ", page: " + page);
        ManualService.ManualViewData viewData = manualService.getManualCategoryData(code, page);
        LOGGER.info("Exiting method manualCategoryPage");
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot)
                .data("parentUrl", viewData.parentUrl)
                .data("page", viewData.page)
                .data("totalPages", viewData.totalPages)
                .data("prevUrl", viewData.prevUrl)
                .data("nextUrl", viewData.nextUrl);
    }
}