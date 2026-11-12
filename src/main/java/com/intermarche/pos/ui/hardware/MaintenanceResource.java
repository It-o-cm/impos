package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.CashSessionService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Peripheral diagnostics with an unmistakable non-selling screen (LC-13-02-01/-07).
 *
 * <p>EVERY GESTURE ANSWERS. Each operation of this screen leaves one outcome in the
 * register's transient zone — a confirmation naming what was done, a refusal naming
 * the peripheral that did not answer, or a warning while the terminal is still busy —
 * and the screen draws it at the top until the next operation replaces it.
 */
@Path("/")
public class MaintenanceResource {

    /** The peripherals this screen drives, in display order. */
    static final List<String> DEVICES = List.of("printer", "scanner", "handheld", "terminal");

    /** The operations each peripheral accepts from this screen. */
    static final Map<String, Set<String>> ACTIONS = Map.of(
            "printer", Set.of("reset", "test"),
            "scanner", Set.of("reset", "test", "stop"),
            "handheld", Set.of("reset", "test", "stop"),
            "terminal", Set.of("reset", "test"));

    /** The availability indicator each peripheral is probed under, when it has one. */
    static final Map<String, String> AVAILABILITY_KEYS = Map.of(
            "printer", "hardware.printer",
            "scanner", "hardware.scanner",
            "scale", "hardware.scale");

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
    /** The actual session state, which gates the workstation controls (LC-13-03-01). */
    @Inject CashSessionService sessions;

    /**
     * One peripheral as the screen lists it.
     *
     * @param id the bridge role (printer, scanner, handheld, terminal, scale)
     * @param label the localised peripheral name
     * @param status AVAILABLE, ABSENT, FAILED or UNKNOWN
     * @param statusLabel the localised status
     * @param scanTesting true while this peripheral's scan test is running
     */
    public record DeviceView(String id, String label, String status, String statusLabel, boolean scanTesting) {
    }

    /**
     * The payment terminal's last report, reduced to its diagnostic fields (LC-13-02-08).
     *
     * @param stateLabel the localised exchange state, empty when unknown
     * @param result the terminal's answer code, possibly empty
     * @param line1 the terminal's first display line, possibly empty
     * @param line2 the terminal's second display line, possibly empty
     * @param unavailable true when the terminal could not be read
     */
    public record TerminalView(String stateLabel, String result, String line1, String line2, boolean unavailable) {
    }

    /**
     * Shows diagnostics, refreshing the isolated test result when testing scans.
     * @return the maintenance page (LC-13-02-01/-07)
     */
    @GET
    @Path("/maintenance")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance show() {
        state.maintenanceScreenOpen = true;
        state.maintenanceSeenAt = System.currentTimeMillis();
        String code = state.maintenanceScanCode;
        String message = "";
        if (state.maintenanceScanMode) {
            try {
                String captured = field(hardware.maintenance("scanner", "status", ""), "code");
                if (!captured.isEmpty()) {
                    code = captured;
                }
            } catch (RuntimeException failure) {
                message = i18n.get("maintenance.failed");
            }
        }
        boolean sessionOpen = sessions.getOpenSession() != null;
        return maintenance.data("state", state).data("code", code).data("message", message)
                .data("terminal", terminalView())
                .data("devices", devices())
                .data("sessionOpen", sessionOpen)
                .data("reasons", workstation.reasons());
    }

    /**
     * Runs an explicit peripheral operation and returns to the diagnostics page with
     * its outcome: what was done, or which peripheral did not answer.
     * @param device printer, scanner, handheld or terminal
     * @param action reset, test or stop
     * @return the diagnostics page redirect (LC-13-02-01/-08)
     */
    @POST
    @Path("/action/maintenance/{device}/{action}")
    public Response perform(@PathParam("device") String device, @PathParam("action") String action) {
        state.maintenanceScreenOpen = true;
        state.maintenanceSeenAt = System.currentTimeMillis();
        Set<String> accepted = ACTIONS.get(device);
        if (accepted == null || !accepted.contains(action)) {
            state.ticket.setError(i18n.get("maintenance.invalidControl"));
            state.touch();
            return Response.seeOther(URI.create("/maintenance")).build();
        }
        boolean scanDevice = "scanner".equals(device) || "handheld".equals(device);
        if (scanDevice && "test".equals(action)) {
            state.maintenanceScanMode = true;
            state.maintenanceScanCode = "";
            state.maintenanceScanDevice = device;
        }
        if ("stop".equals(action)) {
            state.maintenanceScanMode = false;
            state.maintenanceScanCode = "";
            state.maintenanceScanDevice = "";
        }
        try {
            String report = hardware.maintenance(device, action, payload(device));
            if ("terminal".equals(device)) {
                terminalOutcome(action, report);
            } else {
                state.ticket.setNotice(i18n.code("maintenance.done." + device + ".", action));
            }
        } catch (RuntimeException failure) {
            if (scanDevice && "test".equals(action)) {
                state.maintenanceScanMode = false;
                state.maintenanceScanDevice = "";
            }
            state.ticket.setError(i18n.format("maintenance.deviceFailed", i18n.code("maintenance.device.", device)));
        }
        state.touch();
        return Response.seeOther(URI.create("/maintenance")).build();
    }

    /**
     * Explicitly leaves diagnostics and restores the ordinary scan route; a
     * workstation confirmation link not used before leaving is not shown again.
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
        state.maintenanceScanDevice = "";
        state.workstationControlUrl = "";
        state.ticket.setError(null);
        state.touch();
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prepares independent control after supervisor and closed-session checks; the
     * service reports its own refusals, a granted request is confirmed here.
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
        if (workstation.prepare(state, action, reason, login, password)) {
            state.ticket.setNotice(i18n.get("maintenance.controlArmed"));
        }
        return Response.seeOther(URI.create("/maintenance")).build();
    }

    /**
     * Rechecks hardware after repair without attempting a sale operation, and says
     * which peripherals are still failing.
     * @return the maintenance page with refreshed indicators (LC-13-02-09/-14)
     */
    @POST
    @Path("/action/maintenance/probe")
    public Response probe() {
        try {
            List<String> failing = new ArrayList<>();
            for (HardwareService.DeviceStatus status : hardwareService.probeDevices()) {
                if (status.availability() == HardwareService.Availability.FAILED) {
                    failing.add(status.name());
                }
            }
            if (failing.isEmpty()) {
                state.ticket.setNotice(i18n.get("maintenance.probeOk"));
            } else {
                state.ticket.setError(i18n.format("maintenance.probeFailed", String.join(", ", failing)));
            }
        } catch (RuntimeException failure) {
            state.ticket.setError(i18n.get("maintenance.failed"));
        }
        state.touch();
        return Response.seeOther(URI.create("/maintenance")).build();
    }

    /**
     * Builds the peripheral list with each one's last observed availability.
     * @return the printer, scanner, handheld, terminal and scale rows (LC-13-02-09/-14)
     */
    List<DeviceView> devices() {
        List<DeviceView> views = new ArrayList<>();
        for (String id : DEVICES) {
            views.add(device(id));
        }
        views.add(device("scale"));
        return views;
    }

    /**
     * Builds one peripheral row.
     * @param id the bridge role
     * @return the row, UNKNOWN when the peripheral was never probed
     */
    private DeviceView device(String id) {
        String key = AVAILABILITY_KEYS.get(id);
        String status = key == null ? null : state.peripheralAvailability.get(key);
        if (status == null) {
            status = "UNKNOWN";
        }
        boolean scanTesting = state.maintenanceScanMode && id.equals(state.maintenanceScanDevice);
        return new DeviceView(id, i18n.code("maintenance.device.", id), status,
                i18n.code("maintenance.status.", status), scanTesting);
    }

    /**
     * Reads the payment terminal's report, keeping only its diagnostic fields: the
     * raw receipt and the encoded answer never reach the screen (LC-13-02-08).
     * @return the terminal view, flagged unavailable when the bridge failed
     */
    TerminalView terminalView() {
        try {
            String report = hardware.maintenance("terminal", "status", "1");
            String exchange = field(report, "state");
            String stateLabel = exchange.isEmpty() ? "" : i18n.code("maintenance.terminal.", exchange);
            return new TerminalView(stateLabel, field(report, "result"), field(report, "line1"),
                    field(report, "line2"), false);
        } catch (RuntimeException unavailable) {
            return new TerminalView("", "", "", "", true);
        }
    }

    /**
     * States the outcome of a terminal operation from the report it returned.
     * @param action reset or test
     * @param report the terminal's report, possibly null
     */
    private void terminalOutcome(String action, String report) {
        String exchange = field(report, "state");
        if ("RUNNING".equals(exchange)) {
            state.ticket.setWarning(i18n.get("maintenance.terminalRunning"));
            return;
        }
        String result = field(report, "result");
        boolean refused = "false".equals(field(report, "approved"))
                || (!result.isEmpty() && !"00".equals(result));
        if (refused) {
            state.ticket.setError(i18n.format("maintenance.terminalRefused", result, field(report, "failure")).trim());
            return;
        }
        state.ticket.setNotice(i18n.code("maintenance.done.terminal.", action));
    }

    /**
     * The body each peripheral expects: the localised sample for the printer test,
     * the server number for the payment terminal, nothing for the scanners.
     * @param device the bridge role
     * @return the request body
     */
    private String payload(String device) {
        if ("printer".equals(device)) {
            return i18n.get("maintenance.printerSample");
        }
        if ("terminal".equals(device)) {
            return "1";
        }
        return "";
    }

    /**
     * Reads one {@code name=value} line of a bridge report.
     * @param report the report, possibly null
     * @param name the field name
     * @return the value, empty when the report or the field is absent
     */
    static String field(String report, String name) {
        if (report == null) {
            return "";
        }
        Map<String, String> values = new HashMap<>();
        for (String line : report.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.putIfAbsent(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return values.getOrDefault(name, "");
    }

}
