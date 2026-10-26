package com.intermarche.pos.ui.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.deploy.NodeVersion;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.service.CheckoutPointService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.deploy.DeployPayloads;
import com.intermarche.pos.service.deploy.FunctionUsageService;
import com.intermarche.pos.service.deploy.NodeUpdateService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.SyncEndpoints;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The REPORT of this register to its store node, and what the store node
 * answers (BO-08-01-17, BO-08-02-02, BO-08-03-01/07/10, BO-08-04-01/14/15/16/17/20).
 *
 * <p>Every administered period the register tells its store node the
 * version it runs and where its update stands, the state of each of its
 * peripherals, its hardware and software inventory, the state of each
 * referential it pulled, the backlog of what it still has to push, its last
 * ticket, the functions it counted and the outcome of the maintenance orders
 * it carried out. The store node answers the version this register must run
 * and the orders waiting for it.
 *
 * <p>Lives beside the screen, not in the sync package: the report reads the
 * register's state — its peripherals, whether a sale is on the screen, which
 * defers an update and refuses a hardware reset. The FIRST report after a
 * start-up is the start-up update of BO-08-03-07: a seasonal register
 * switched on after its lot's window catches up when it boots.
 *
 * <p>Silent when the register has no store node, like the rest of the
 * synchronization.
 */
@ApplicationScoped
public class RegisterReportService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RegisterReportService.class);

    /** Where the store node lives. */
    @Inject
    SyncEndpoints syncEndpoints;

    /** The register's state. */
    @Inject
    PosState state;

    /** The report cadence. */
    @Inject
    PosSettingsService posSettingsService;

    /** The update state machine. */
    @Inject
    NodeUpdateService nodeUpdateService;

    /** The function counters. */
    @Inject
    FunctionUsageService functionUsageService;

    /** The referential pull's errors. */
    @Inject
    RefPullService refPullService;

    /** The push backlog. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** This register as an administered checkout point. */
    @Inject
    CheckoutPointService checkoutPointService;

    /** The orders carried out on this register. */
    @Inject
    RemoteMaintenanceService remoteMaintenanceService;

    /** The JSON mapper of the wire format. */
    @Inject
    ObjectMapper objectMapper;

    /** This register's identifier. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** This register's point of sale, when commissioned; the store node fills it otherwise. */
    @ConfigProperty(name = "pos.pdv.number")
    Optional<String> pdvNumber;

    /** Shared synchronization token; absent = the node is open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The HTTP client toward the store node. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Whether a report already went since the register started. */
    boolean started;

    /** The outcomes of the orders carried out, sent with the next report. */
    final List<DeployPayloads.ActionResult> results = new ArrayList<>();

    /** The report loop, null when this register has no store node. */
    private ScheduledExecutorService executor;

    /**
     * Starts the report loop, unless this register stands alone.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!syncEndpoints.hasStoreUrl()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "register-report");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::cycleSafely, 10, posSettingsService.supervisionReportSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * Stops the report loop.
     */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one report, swallowing whatever it fails on: a store node that is
     * down must not stop the next report.
     */
    void cycleSafely() {
        try {
            cycle();
        } catch (Exception failure) {
            LOGGER.warnf("Rapport de caisse impossible (%s)", failure.getMessage());
        }
    }

    /**
     * Sends one report and carries out the answer.
     *
     * @throws Exception when the store node cannot be reached or answers badly
     */
    void cycle() throws Exception {
        boolean startup = !started;
        DeployPayloads.NodeReport report = QuarkusTransaction.requiringNew().call(() -> build(startup));
        DeployPayloads.ReportAnswer answer = post(report);
        started = true;
        results.clear();
        DeployPayloads.Target own = null;
        for (DeployPayloads.Target target : answer.targets) {
            if (DeployPayloads.REGISTER.equals(target.kind) && terminalId.equals(target.nodeId)) {
                own = target;
            }
        }
        List<DeployPayloads.Action> checks = new ArrayList<>();
        for (DeployPayloads.Action order : answer.actions) {
            if ("UPDATE_CHECK".equals(order.type)) {
                checks.add(order);
            } else {
                results.add(remoteMaintenanceService.execute(order));
            }
        }
        NodeUpdateService.Decision decision = nodeUpdateService.consider(own, startup || !checks.isEmpty(),
                state.ticket.items.isEmpty(), this::download);
        for (DeployPayloads.Action check : checks) {
            results.add(RemoteMaintenanceService.outcome(check, true, "Contrôle de mise à jour : " + decision));
        }
    }

    /**
     * Builds this register's report.
     *
     * @param startup whether this is the first report since the register started
     * @return the report
     */
    DeployPayloads.NodeReport build(boolean startup) {
        DeployPayloads.NodeReport report = new DeployPayloads.NodeReport();
        report.kind = DeployPayloads.REGISTER;
        report.nodeId = terminalId;
        report.pdvNumber = pdvNumber.filter(value -> !value.isBlank()).map(String::trim).orElse(null);
        NodeVersion version = nodeUpdateService.snapshot();
        report.version = version.installedVersion;
        report.previousVersion = version.previousVersion;
        report.updateState = version.state.name();
        report.updateTarget = version.targetVersion;
        report.updateMessage = version.lastError;
        report.appOk = version.state != NodeVersion.State.FAILED;
        report.appDetail = report.appOk ? null : version.lastError;
        report.startup = startup;
        report.reportedAt = LocalDateTime.now().toString();
        Ticket last = Ticket.findLastClosedByTerminal(terminalId);
        report.lastTicketAt = last == null || last.closingDate == null ? null : last.closingDate.toString();
        for (SyncOutboxService.BacklogRow row : syncOutboxService.backlog()) {
            report.outboxPending += row.count();
            if (report.outboxError == null && row.lastError() != null) {
                report.outboxError = row.lastError();
            }
        }
        for (Map.Entry<String, String> peripheral : state.peripheralAvailability.entrySet()) {
            DeployPayloads.Peripheral item = new DeployPayloads.Peripheral();
            item.role = peripheralName(peripheral.getKey());
            item.state = peripheral.getValue();
            report.peripherals.add(item);
            report.inventory.add(item("PERIPHERAL", item.role, item.state));
        }
        report.inventory.addAll(inventory(report.version, checkoutPointService.own()));
        report.integrations = integrations();
        report.usages = functionUsageService.recent(1);
        report.actionResults = new ArrayList<>(results);
        return report;
    }

    /**
     * The state of each referential this register pulled, a pull error
     * marking its domain failed.
     *
     * @return the integrations
     */
    List<DeployPayloads.Integration> integrations() {
        List<DeployPayloads.Integration> integrations = new ArrayList<>();
        Map<String, String> errors = refPullService.getLastErrors();
        Set<String> seen = new HashSet<>();
        for (RefState ref : RefState.<RefState>listAll()) {
            seen.add(ref.domain);
            String error = errors.get(ref.domain);
            integrations.add(integration(ref.domain, error, ref.appliedAt));
        }
        for (Map.Entry<String, String> error : errors.entrySet()) {
            if (!seen.contains(error.getKey())) {
                integrations.add(integration(error.getKey(), error.getValue(), null));
            }
        }
        return integrations;
    }

    /**
     * Builds one integration line.
     *
     * @param domain the domain
     * @param error its pull error, null when it applied
     * @param at when it was applied, possibly null
     * @return the line
     */
    static DeployPayloads.Integration integration(String domain, String error, LocalDateTime at) {
        DeployPayloads.Integration integration = new DeployPayloads.Integration();
        integration.domain = domain;
        integration.outcome = error == null ? "APPLIED" : "FAILED";
        integration.at = at == null ? null : at.toString();
        integration.detail = error;
        return integration;
    }

    /**
     * The software and hardware of this register: its application, its
     * runtime, its system, its processors and memory, and the equipment its
     * checkout point declares.
     *
     * @param version the application version
     * @param point this register's checkout point, null when not administered
     * @return the inventory lines
     */
    static List<DeployPayloads.InventoryItem> inventory(String version, CheckoutPoint point) {
        List<DeployPayloads.InventoryItem> items = new ArrayList<>();
        items.add(item("SOFTWARE", "Application impos", version));
        items.add(item("SOFTWARE", "Java", System.getProperty("java.version")));
        items.add(item("SOFTWARE", "Système", System.getProperty("os.name") + " " + System.getProperty("os.version")));
        items.add(item("HARDWARE", "Processeurs", String.valueOf(Runtime.getRuntime().availableProcessors())));
        items.add(item("HARDWARE", "Mémoire maximale (Mo)", String.valueOf(Runtime.getRuntime().maxMemory() / 1048576)));
        if (point != null) {
            items.add(item("HARDWARE", "Type de point de caisse", String.valueOf(point.tpvType)));
            items.add(item("HARDWARE", "Afficheur caissier", point.cashierDisplayType + " " + text(point.cashierDisplayResolution)));
            items.add(item("HARDWARE", "Afficheur client", point.customerDisplayType + " " + text(point.customerDisplayResolution)));
            items.add(item("HARDWARE", "Balance", point.scaleProtocol + " " + text(point.scaleAddress)));
            items.add(item("HARDWARE", "Monnayeur", point.coinMachineProtocol + " " + text(point.coinMachineAddress)));
        }
        return items;
    }

    /**
     * Builds one inventory line.
     *
     * @param category HARDWARE, PERIPHERAL or SOFTWARE
     * @param name what it describes
     * @param detail its value
     * @return the line
     */
    static DeployPayloads.InventoryItem item(String category, String name, String detail) {
        DeployPayloads.InventoryItem item = new DeployPayloads.InventoryItem();
        item.category = category;
        item.name = name;
        item.detail = detail;
        return item;
    }

    /**
     * Names a peripheral role for the screens of the back office.
     *
     * @param role the register's role key
     * @return its French name, the key itself for an unknown role
     */
    static String peripheralName(String role) {
        return switch (role) {
            case "hardware.printer" -> "Imprimante";
            case "hardware.scale" -> "Balance";
            case "hardware.scanner" -> "Scanner";
            default -> role;
        };
    }

    /**
     * An optional text.
     *
     * @param value the value, possibly null
     * @return the value, empty when null
     */
    static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * Fetches the package of this register's target from the store node.
     *
     * @param target the target
     * @return the package content
     */
    String download(DeployPayloads.Target target) {
        try {
            HttpResponse<String> response = httpClient.send(request("/api/deployment/package/" + target.product
                    + "/" + target.version).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    /**
     * Sends the report to the store node.
     *
     * @param report the report
     * @return the store node's answer
     * @throws Exception when the store node cannot be reached or answers badly
     */
    DeployPayloads.ReportAnswer post(DeployPayloads.NodeReport report) throws Exception {
        HttpResponse<String> response = httpClient.send(request("/api/supervision/report")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(report))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " du nœud magasin");
        }
        return objectMapper.readValue(response.body(), DeployPayloads.ReportAnswer.class);
    }

    /**
     * Builds a request to the store node, carrying the shared token.
     *
     * @param path the path
     * @return the prepared builder
     */
    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(syncEndpoints.storeUrl() + path))
                .timeout(Duration.ofSeconds(15));
        String shared = token.orElse("");
        if (!shared.isBlank()) {
            builder.header("X-Sync-Token", shared);
        }
        return builder;
    }
}
