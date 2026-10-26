package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** LC-13-04-03/-06: schedules documented c2g requests without interrupting a payment. */
@ApplicationScoped
public class MoneticMaintenanceService {
    /** Diagnostic destination; receipt contents are never logged. */
    private static final Logger LOG = Logger.getLogger(MoneticMaintenanceService.class);
    /** Applicable hierarchy settings. */
    @Inject PosSettingsService settings;
    /** Open sessions defer maintenance. */
    @Inject CashSessionService sessions;
    /** Register identifier for sequential staggering. */
    @Inject TicketNumberService numbers;
    /** Bridge that owns the native WCL session. */
    @Inject @RestClient HardwareClient hardware;
    /** Last successes remain available to local support after application restarts. */
    Path statusFile = Path.of("verifone-maintenance.txt");
    /** Background pulse, absent before startup. */
    private ScheduledExecutorService executor;
    /** Selected local instant, regenerated after period close and restart. */
    LocalDateTime planned;
    /** Day already processed. */
    LocalDate completed;
    /** Next server in the sequential pair. */
    int nextServer = 1;
    /** Server whose response is awaited. */
    int pendingServer;

    /** Starts a daemon pulse; the hierarchy switch defaults to disabled.
     * @param event Quarkus startup event
     */
    void start(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verifone-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pulse, 60, 60, TimeUnit.SECONDS);
    }

    /** Stops the daemon when the application stops. */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Contains failures so one missed server never disables the next night's maintenance. */
    void pulse() {
        try {
            QuarkusTransaction.requiringNew().run(() -> tick(LocalDateTime.now()));
        } catch (RuntimeException failure) {
            LOG.warn("LC-13-04 maintenance deferred", failure);
        }
    }

    /** Renews the random choice after a period close (LC-13-04-05). */
    public synchronized void resetPlan() {
        planned = null;
    }

    /** Advances one maintenance exchange, never starting both servers at once.
     * @param now local current instant
     */
    synchronized void tick(LocalDateTime now) {
        if (!Boolean.parseBoolean(settings.value("maintenance.verifone.enabled"))) {
            return;
        }
        if (pendingServer != 0) {
            String status = hardware.maintenance("terminal", "status", "1");
            if (status == null || !status.contains("state=DONE\n")) {
                return;
            }
            if (status.contains("operation=CONTROL:test:" + pendingServer + "\n")
                    && status.contains("approved=true\n")) {
                try {
                    rememberSuccess(pendingServer, now);
                } catch (IOException failure) {
                    // LC-13-04-06: a failed trace is visible and never claims a saved success.
                    LOG.warn("LC-13-04 could not save last maintenance success", failure);
                }
            }
            pendingServer = 0;
            nextServer++;
            if (nextServer > 2) {
                completed = now.toLocalDate();
                nextServer = 1;
                return;
            }
        }
        if (now.toLocalDate().equals(completed)) {
            return;
        }
        if (planned == null || !planned.toLocalDate().equals(now.toLocalDate())) {
            LocalTime start = LocalTime.parse(settings.value("maintenance.verifone.start"));
            LocalTime end = LocalTime.parse(settings.value("maintenance.verifone.end"));
            long seconds = java.time.Duration.between(start, end).getSeconds();
            if (seconds <= 0) {
                return;
            }
            boolean random = Boolean.parseBoolean(settings.value("maintenance.verifone.random"));
            long offset = random ? ThreadLocalRandom.current().nextLong(seconds)
                    : sequentialOffset(numbers.getTerminalId(), settings.value("maintenance.verifone.stagger-seconds"), seconds);
            planned = now.toLocalDate().atTime(start).plusSeconds(offset);
        }
        if (!now.toLocalTime().isBefore(LocalTime.parse(settings.value("maintenance.verifone.end")))) {
            completed = now.toLocalDate();
            nextServer = 1;
            return;
        }
        if (now.isBefore(planned) || sessions.getOpenSession() != null) {
            return;
        }
        String status = hardware.maintenance("terminal", "status", "1");
        if (status != null && status.contains("state=RUNNING")) {
            return;
        }
        hardware.maintenance("terminal", "test", Integer.toString(nextServer));
        pendingServer = nextServer;
    }

    /** Calculates deterministic register staggering within the configured window.
     * @param register register identity
     * @param stagger configured seconds per register
     * @param window positive window size
     * @return bounded offset
     */
    static long sequentialOffset(String register, String stagger, long window) {
        String digits = register == null ? "" : register.replaceAll("[^0-9]", "");
        long number = digits.isEmpty() ? 0 : Long.parseLong(digits);
        long step = Math.max(0, Long.parseLong(stagger));
        return Math.min(window - 1, Math.multiplyExact(number, step));
    }

    /** Stores only timestamps in the local text file, preserving the other server's success.
     * @param server server number
     * @param now successful completion instant
     * @throws IOException when support trace cannot be saved
     */
    void rememberSuccess(int server, LocalDateTime now) throws IOException {
        Properties values = new Properties();
        if (Files.exists(statusFile)) {
            try (var input = Files.newInputStream(statusFile)) {
                values.load(input);
            }
        }
        values.setProperty("server." + server, now.toString());
        try (var output = Files.newOutputStream(statusFile)) {
            values.store(output, "LC-13-04-06 last successful Verifone maintenance");
        }
    }
}
