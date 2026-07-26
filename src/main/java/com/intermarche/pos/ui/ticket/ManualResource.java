package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

/**
 * JAX-RS resource of the SAISIE DIRECTE drill-down: root level and category
 * levels. Adding the chosen product goes through the ticket actions.
 */
@Path("/")
@DrawerMustBeClosed
public class ManualResource {

    @Inject Template manual;
    @Inject Template lock;
    @Inject ManualService manualService;

    @Inject
    PosState state;

    @GET
    @Path("/manual")
    /**
     * Shows the root level of the drill-down.
     *
     * @return the manual page, or the lock page when locked
     */
    public TemplateInstance manualPage() {
        if (state.isLocked()) return lock.data("state", state);
        ManualService.ManualViewData viewData = manualService.getManualRootData();
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot);
    }

    @GET
    @Path("/manual/cat/{code}")
    /**
     * Shows one category level of the drill-down.
     *
     * @param code the family code
     * @return the manual page, or the lock page when locked
     */
    public TemplateInstance manualCategoryPage(@PathParam("code") String code) {
        if (state.isLocked()) return lock.data("state", state);
        ManualService.ManualViewData viewData = manualService.getManualCategoryData(code);
        return manual.data("state", state)
                .data("items", viewData.items)
                .data("breadcrumb", viewData.breadcrumb)
                .data("isRoot", viewData.isRoot)
                .data("parentUrl", viewData.parentUrl);
    }
}