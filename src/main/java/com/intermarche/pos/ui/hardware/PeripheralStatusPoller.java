package com.intermarche.pos.ui.hardware;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/** LC-13-02-12: detects a scanner disconnected during a sale without waiting for another login. */
@ApplicationScoped
public class PeripheralStatusPoller {
    /** Shared state updater. */
    @Inject HardwareService hardware;
    /** Background probe worker. */
    private ScheduledExecutorService executor;

    /** Starts the scanner-only probe without resetting or consuming scans.
     * @param event application startup
     */
    void start(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "scanner-status");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::poll, 15, 15, TimeUnit.SECONDS);
    }

    /** Contains communication failures so the monitor never stops the sale. */
    void poll() {
        try {
            QuarkusTransaction.requiringNew().run(hardware::probeScanner);
        } catch (RuntimeException failure) {
            Logger.getLogger(PeripheralStatusPoller.class).warn("LC-13-02-12 scanner probe failed", failure);
        }
    }

    /** Releases the background worker at shutdown. */
    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
