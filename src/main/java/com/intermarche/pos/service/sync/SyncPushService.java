package com.intermarche.pos.service.sync;

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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background half of the store synchronization: a single-thread JDK executor
 * (no scheduler dependency) drains the outbox every
 * {@code pos.sync.interval-seconds}, pushing each item to the store node
 * ({@code pos.sync.store-url}) as JSON over plain JDK HTTP. Acknowledged
 * items are deleted; failures are recorded and retried on the next cycle.
 * Disabled entirely when no store URL is configured (standalone register or
 * the store node itself).
 * <p>
 * Items are pushed ONE BY ONE on purpose: acknowledgement granularity is
 * per document, so a single 409 (missing reference on the store side, first
 * push of a fresh register) or one malformed item never blocks the rest of
 * the batch — it accumulates attempts and error text in its outbox row and
 * is retried every cycle until it passes. The batch is read in drain order
 * (entityType ordinal, then id), which is what delivers sessions before
 * tickets before refunds before events within and across cycles.
 */
@ApplicationScoped
public class SyncPushService {

    private static final Logger LOG = Logger.getLogger(SyncPushService.class);

    /** Maximum outbox rows processed per cycle. */
    private static final int BATCH_SIZE = 50;

    /** Seconds between two drain cycles. */
    @ConfigProperty(name = "pos.sync.interval-seconds", defaultValue = "10")
    long intervalSeconds;

    /** Shared ingestion token sent to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    java.util.Optional<String> token;

    @Inject
    SyncOutboxService syncOutboxService;

    /** The drain loop executor, or null when the synchronization is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client used toward the store node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the drain loop at register startup when a store URL is
     * configured.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!syncOutboxService.isEnabled()) {
            LOG.info("Synchronisation magasin désactivée (pos.sync.store-url absent)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "store-sync");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drainSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.infof("Synchronisation magasin active vers %s (toutes les %ds)",
                syncOutboxService.getStoreUrl(), intervalSeconds);
    }

    /**
     * Stops the drain loop at shutdown.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one drain cycle, never letting an exception kill the loop.
     */
    private void drainSafely() {
        try {
            drainOnce();
        } catch (Exception e) {
            LOG.errorf("Cycle de synchronisation en erreur: %s", e.getMessage());
        }
    }

    /**
     * Drains one batch of outbox items in order (sessions, tickets, refunds).
     */
    void drainOnce() {
        List<Long> ids = syncOutboxService.nextBatchIds(BATCH_SIZE);
        for (Long outboxId : ids) {
            SyncOutboxService.PreparedItem item = syncOutboxService.prepare(outboxId);
            if (item == null) {
                // Row or entity vanished: nothing left to push
                syncOutboxService.markGone(outboxId);
                continue;
            }
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(syncOutboxService.getStoreUrl() + "/api/sync/" + item.pathSuffix))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(item.json));
                String sharedToken = token.orElse("");
                if (!sharedToken.isBlank()) {
                    builder.header("X-Sync-Token", sharedToken);
                }
                HttpRequest request = builder.build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    syncOutboxService.markSuccess(outboxId);
                } else {
                    syncOutboxService.markFailure(outboxId,
                            "HTTP " + response.statusCode() + " " + shorten(response.body()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                syncOutboxService.markFailure(outboxId, e.getMessage());
            }
        }
    }

    /**
     * Shortens a response body for the outbox error column.
     *
     * @param body the response body, or null
     * @return the shortened body
     */
    private String shorten(String body) {
        if (body == null) return "";
        return body.length() > 120 ? body.substring(0, 120) : body;
    }
}
