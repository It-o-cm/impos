package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Supervisor and closed-session checks before independent bridge control (LC-13-03-01/-06). */
@ApplicationScoped
public class WorkstationMaintenanceService {

    /** Actual session state, never inferred from the displayed page. */
    @Inject CashSessionService sessions;
    /** Supervisor credentials and their existing audit. */
    @Inject EndorsementService endorsement;
    /** Administered reasons and explicit host commands. */
    @Inject PosSettingsService settings;
    /** Authorised-request outbox to the store node. */
    @Inject TechnicalEventService events;
    /** Localised operator messages. */
    @Inject Messages i18n;
    /** Independent daemon, replaced by the demo simulator by default. */
    @Inject @RestClient HardwareClient hardware;
    /** Existing bridge URL; the default preserves the embedded demo. */
    @ConfigProperty(name = "quarkus.rest-client.hardware-api.url", defaultValue = "http://localhost:8080")
    String hardwareUrl;

    /**
     * Returns the administered reasons, or translated demo defaults.
     * @return selectable reasons (LC-13-03-04)
     */
    public List<String> reasons() {
        String configured = settings.value("maintenance.restart-reasons");
        if (configured == null || configured.isBlank()) {
            return List.of(i18n.get("maintenance.reasonFrozen"), i18n.get("maintenance.reasonScheduled"));
        }
        return java.util.Arrays.stream(configured.split(";")).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    /**
     * Arms one operation; the bridge still requires a separate final confirmation.
     * @param state the current register
     * @param action APPLICATION, REBOOT or SHUTDOWN
     * @param reason an administered reason
     * @param login the supervisor identifier
     * @param password the supervisor secret, never logged or sent to the bridge
     * @return true when a one-shot confirmation page was prepared (LC-13-03-01/-06)
     */
    public boolean prepare(PosState state, String action, String reason, String login, String password) {
        synchronized (state) {
            state.workstationControlUrl = "";
            if (sessions.getOpenSession() != null) {
                state.ticket.setError(i18n.get("maintenance.sessionMustBeClosed"));
                return false;
            }
            if (action == null) {
                state.ticket.setError(i18n.get("maintenance.invalidControl"));
                return false;
            }
            if (!java.util.Set.of("APPLICATION", "REBOOT", "SHUTDOWN").contains(action)) {
                state.ticket.setError(i18n.get("maintenance.invalidControl"));
                return false;
            }
            if (reason == null) {
                state.ticket.setError(i18n.get("maintenance.invalidReason"));
                return false;
            }
            if (!reasons().contains(reason)) {
                state.ticket.setError(i18n.get("maintenance.invalidReason"));
                return false;
            }
            if (!endorsement.authorize(login, password, "WORKSTATION_" + action)) {
                state.ticket.setError(i18n.get("maintenance.supervisorRequired"));
                return false;
            }
            String command = settings.value("maintenance.command." + action.toLowerCase(Locale.ROOT));
            if (command == null) {
                command = "";
            }
            Map<String, String> values = new java.util.HashMap<>(Map.of("closed", "true", "supervisor", "true",
                    "action", action, "reason", reason, "command", command,
                    "title", i18n.get(Map.of("APPLICATION", "maintenance.restartApplication",
                            "REBOOT", "maintenance.reboot", "SHUTDOWN", "maintenance.shutdown").get(action)), 
                    "confirm", i18n.get("common.validate"), "cancel", i18n.get("common.cancel")));
            values.put("SIMULATED", i18n.get("maintenance.controlSimulated"));
            values.put("STARTED", i18n.get("maintenance.controlStarted"));
            values.put("CANCELLED", i18n.get("maintenance.controlCancelled"));
            values.put("FAILURE", i18n.get("maintenance.failed"));
            String form = values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            try {
                String token = hardware.armWorkstation(form);
                if (token == null || token.isBlank()) {
                    state.ticket.setError(i18n.get("maintenance.failed"));
                    return false;
                }
                events.log(TechnicalEvent.EventType.WORKSTATION_REQUEST, action + ": " + reason);
                state.workstationControlUrl = hardwareUrl.replaceAll("/+$", "")
                        + "/api/hardware/control?token=" + encode(token);
                state.touch();
                return true;
            } catch (RuntimeException failure) {
                state.ticket.setError(i18n.get("maintenance.failed"));
                return false;
            }
        }
    }

    /**
     * Encodes one form field without exposing credentials to the bridge.
     * @param text the field
     * @return the encoded value (LC-13-03-01/-06)
     */
    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
