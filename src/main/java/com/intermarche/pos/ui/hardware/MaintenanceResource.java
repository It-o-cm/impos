package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.Messages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.net.URI;

/** Peripheral diagnostics with an unmistakable non-selling screen (LC-13-02-01/-07). */
@Path("/")
public class MaintenanceResource {

    /** The shared register state. */
    @Inject PosState state;
    /** Localised operator messages. */
    @Inject Messages i18n;
    /** The dedicated diagnostics page. */
    @Inject Template maintenance;
    /** The same bridge used for sales, or its demo simulator. */
    @Inject @RestClient HardwareClient hardware;
    /** Supervisor-gated workstation controls (LC-13-03-01/-06). */
    @Inject WorkstationMaintenanceService workstation;
    /** Explicit recovery probes for disabled or failed peripherals (LC-13-02-09/-14). */
    @Inject HardwareService hardwareService;

    /**
     * Shows diagnostics, refreshing the isolated test result when testing scans.
     * @return the maintenance page (LC-13-02-01/-07)
     */
    @GET
    @Path("/maintenance")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show() {
        state.maintenanceScreenOpen = true;
        String code = state.maintenanceScanCode;
        String message = "";
        if (state.maintenanceScanMode) {
            try {
                String block = hardware.maintenance("scanner", "status", "");
                if (block != null) {
                    for (String line : block.split("\n")) {
                        if (line.startsWith("code=")) {
                            String captured = line.substring(5);
                            if (!captured.isEmpty()) {
                                code = captured;
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) {
                message = i18n.get("maintenance.failed");
            }
        }
        String terminalStatus = "";
        try {
            String status = hardware.maintenance("terminal", "status", "1");
            if (status != null) {
                terminalStatus = status.lines().filter(line -> line.startsWith("state=")
                        || line.startsWith("line1=") || line.startsWith("line2=")
                        || line.startsWith("result=")).collect(java.util.stream.Collectors.joining("\n"));
            }
        } catch (RuntimeException unavailable) {
            terminalStatus = i18n.get("maintenance.failed");
        }
        return maintenance.data("state", state).data("code", code).data("message", message)
                .data("terminalStatus", terminalStatus)
                .data("reasons", workstation.reasons());
    }

    /**
     * Runs an explicit peripheral operation and returns to the diagnostics page.
     * @param device printer, scanner or handheld
     * @param action reset, test or stop
     * @return the diagnostics page redirect (LC-13-02-01/-07)
     */
    @POST
    @Path("/action/maintenance/{device}/{action}")
    public Response perform(@PathParam("device") String device, @PathParam("action") String action) {
        state.maintenanceScreenOpen = true;
        boolean scanDevice = "scanner".equals(device) || "handheld".equals(device);
        if (scanDevice && "test".equals(action)) {
            state.maintenanceScanMode = true;
            state.maintenanceScanCode = "";
        }
        try {
            hardware.maintenance(device, action, i18n.get("maintenance.printerSample"));
            state.ticket.setNotice(i18n.get("maintenance.completed"));
        } catch (RuntimeException failure) {
            state.ticket.setError(i18n.get("maintenance.failed"));
        }
        state.touch();
        return Response.seeOther(URI.create("/maintenance")).build();
    }

    /**
     * Explicitly leaves diagnostics and restores the ordinary scan route.
     * @return the selling page redirect (LC-13-02-05/-07)
     */
    @POST
    @Path("/action/maintenance/exit")
    public Response exit() {
        try {
            hardware.maintenance("scanner", "stop", "");
        } catch (RuntimeException ignored) {
            // LC-13-02-05/-07: bridge capture expires independently after sixty seconds.
        }
        state.maintenanceScanMode = false;
        state.maintenanceScreenOpen = false;
        state.maintenanceScanCode = "";
        state.touch();
        return Response.seeOther(URI.create("/")).build();
    }
    /**
     * Prepares independent control after supervisor and closed-session checks.
     * @param action the requested operation
     * @param reason the administered reason
     * @param login the supervisor identifier
     * @param password the supervisor secret
     * @return the maintenance page containing the separate confirmation link (LC-13-03-01/-06)
     */
    @POST
    @Path("/action/maintenance/control")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response prepareControl(@FormParam("action") String action, @FormParam("reason") String reason,
            @FormParam("supervisor") String login, @FormParam("supervisorPassword") String password) {
        workstation.prepare(state, action, reason, login, password);
        return Response.seeOther(URI.create("/maintenance")).build();
    }

    /**
     * Rechecks hardware after repair without attempting a sale operation.
     * @return the maintenance page with refreshed indicators (LC-13-02-09/-14)
     */
    @POST
    @Path("/action/maintenance/probe")
    public Response probe() {
        hardwareService.probeDevices();
        return Response.seeOther(URI.create("/maintenance")).build();
    }

}
