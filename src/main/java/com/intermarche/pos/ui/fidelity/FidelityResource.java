package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/")
@DrawerMustBeClosed
public class FidelityResource {

    @Inject Template fidelity;
    @Inject Template main;
    @Inject Template lock;
    @Inject FidelityService fidelityService;
    @Inject PosState state;

    private TemplateInstance home() {
        return state.isLocked() ? lock.data("state", state) : main.data("state", state);
    }

    // --- Vue ---
    @GET
    @Path("/fidelity") // Chemin complet
    public TemplateInstance fidelityPage() {
        if (state.isLocked()) return lock.data("state", state);
        return fidelity.data("state", state);
    }

    // --- Action ---
    @POST
    @Path("/action/fidelity") // Chemin complet
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance validateFidelity(@FormParam("card") String card) {
        if (state.isLocked()) return lock.data("state", state);
        fidelityService.validateCard(state, card);
        return home();
    }
}