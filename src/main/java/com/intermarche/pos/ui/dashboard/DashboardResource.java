package com.intermarche.pos.ui.dashboard;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manager-facing supervision IHM (phase 5 lot 2), separate from the register
 * screens: the dashboard page, its polled data (aggregations plus pending
 * supervisor calls), the call acknowledgement, and the inbound call endpoint
 * the registers POST to — the latter gated like the sync ingestion (store
 * role, optional shared token).
 */
@Path("/")
public class DashboardResource {

    @Inject @Location("dashboard") Template dashboard;
    @Inject DashboardService dashboardService;
    @Inject SupervisorCallRegistry supervisorCallRegistry;

    /** The role of this node: "register" (default) or "store". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token of the inbound call endpoint; absent = open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * An incoming supervisor call pushed by a register.
     */
    public static class CallDto {
        /** The calling register identifier. */
        public String terminalId;
        /** The calling operator display name. */
        public String operator;
        /** The call reason. */
        public String reason;
    }

    /**
     * Shows the supervision dashboard.
     *
     * @return the dashboard page
     */
    @GET
    @Path("/dashboard")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboardPage() {
        return dashboard.instance();
    }

    /**
     * Returns the polled dashboard data: today's aggregations and the
     * pending supervisor calls.
     *
     * @return the JSON dashboard data
     */
    @GET
    @Path("/dashboard-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> dashboardData() {
        Map<String, Object> data = dashboardService.buildData();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (SupervisorCallRegistry.Call call : supervisorCallRegistry.getPending()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", call.id);
            entry.put("terminal", call.terminalId);
            entry.put("operator", call.operator);
            entry.put("reason", call.reason);
            entry.put("time", call.time);
            calls.add(entry);
        }
        data.put("calls", calls);
        return data;
    }

    /**
     * Acknowledges a pending supervisor call.
     *
     * @param id the registry id of the call
     * @return 204
     */
    @GET
    @Path("/dashboard/ack/{id}")
    public Response acknowledge(@PathParam("id") long id) {
        supervisorCallRegistry.acknowledge(id);
        return Response.noContent().build();
    }

    /**
     * Receives a supervisor call pushed by a register; gated like the sync
     * ingestion.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the call payload
     * @return 200 on registration, 401 on a bad token, 403 off-role
     */
    @POST
    @Path("/api/supervisor/call")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response receiveCall(@HeaderParam("X-Sync-Token") String presentedToken, CallDto dto) {
        if (!"store".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton invalide").build();
        }
        supervisorCallRegistry.add(dto.terminalId, dto.operator, dto.reason);
        return Response.ok("OK").build();
    }
}
