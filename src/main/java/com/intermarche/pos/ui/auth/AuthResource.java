package com.intermarche.pos.ui.auth;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;

@Path("/")
public class AuthResource {

    @Inject Template main;
    @Inject Template lock;
    @Inject AuthService authService;
    @Inject HardwareService hardwareService;

    @Inject PosState state;

    @GET
    @Path("/lock")
    @DrawerMayBeOpen
    public TemplateInstance lockPage() {
        authService.logout(state);
        return lock.data("state", state);
    }

    @GET
    @Path("/lock-data")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> getLockData() {
        String badge = state.auth.scannedBadgeId;
        if (badge != null) state.auth.clearScannedBadge();
        return Map.of("scannedBadge", badge != null ? badge : "");
    }

    @POST
    @Path("/action/unlock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response unlock(
        @FormParam("login") String login,
        @FormParam("password") String password
    ) {
        if (authService.login(state, login, password)) {
            hardwareService.openDrawer();
            return Response.seeOther(URI.create("/")).build();
        }
        return Response.seeOther(URI.create("/lock?error=true")).build();
    }
}