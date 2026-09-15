package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.FidEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Loyalty fiscal-event outbox (imfid integration spec §6-7), the fidelity
 * sibling of the store {@code SyncOutboxService}: events are ENQUEUED
 * transactionally with their payload frozen, then DRAINED every 10 seconds
 * to imfid, which answers 202 and is idempotent by ticket reference — rows
 * are retried freely after any outage, in any order (spec: no sequencing
 * POS-side; imfid parks an early return itself).
 * <p>
 * Placement note: this is a background service that never serves the IHM —
 * it lives in {@code service.sync} and carries its OWN minimal HTTP posting
 * (same {@code pos.fid.*} configuration keys as the UI client) so that no
 * service→ui dependency exists.
 */
@ApplicationScoped
public class FidEventOutboxService {

    private static final Logger LOGGER = Logger.getLogger(FidEventOutboxService.class);

    /** Base URL of imfid; absent = loyalty disabled, the drain is a no-op. */
    @ConfigProperty(name = "pos.fid.url")
    Optional<String> url;

    /** Basic-auth user of the POS machine account. */
    @ConfigProperty(name = "pos.fid.user")
    Optional<String> user;

    /** Basic-auth password of the POS machine account. */
    @ConfigProperty(name = "pos.fid.password")
    Optional<String> password;

    /** Shared HTTP client of the drain. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();

    /** The drain loop, on the house scheduler pattern (see SyncPushService). */
    private java.util.concurrent.ScheduledExecutorService executor;

    /**
     * Starts the drain loop at boot — same pattern as the store push: a
     * single daemon thread, a fixed 10 s delay, exceptions never kill the
     * loop. Absent URL = loyalty disabled, no thread at all.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (url.isEmpty()) {
            return;
        }
        executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fid-events");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::drainSafely, 10, 10,
                java.util.concurrent.TimeUnit.SECONDS);
        LOGGER.infof("Outbox fidélité active vers %s (toutes les 10s)", url.get());
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
            drain();
        } catch (Exception e) {
            LOGGER.errorf("Cycle d'outbox fidélité en erreur: %s", e.getMessage());
        }
    }

    /**
     * Enqueues a loyalty event with its frozen payload. Runs in (or joins)
     * the caller's transaction: an event exists if and only if the business
     * fact it describes was committed.
     *
     * @param type the event kind
     * @param payloadJson the composed event body, posted as-is at drain
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public void enqueue(FidEvent.EventType type, String payloadJson) {
        LOGGER.info("Entering method enqueue with type: " + type + ", payloadJson: " + payloadJson);
        FidEvent event = new FidEvent();
        event.eventType = type;
        event.payloadJson = payloadJson;
        event.createdAt = LocalDateTime.now();
        event.persist();
        LOGGER.info("Exiting method enqueue");
    }

    /**
     * Drains the pending events to imfid. 202 marks the row sent; anything
     * else (transport failure included) leaves it pending for the next
     * cycle — the retry IS the degraded mode (spec §2.2: defer and replay).
     * <p>
     * Runs on the executor thread: the transaction is opened EXPLICITLY
     * ({@code QuarkusTransaction}) because a self-call from the loop would
     * bypass the {@code @Transactional} interceptor.
     */
    public void drain() {
        LOGGER.info("Entering method drain");
        if (url.isEmpty()) {
            LOGGER.info("Exiting method drain");
            return;
        }
        QuarkusTransaction.requiringNew().run(this::drainInTransaction);
        LOGGER.info("Exiting method drain");
    }

    /**
     * The transactional body of one drain cycle.
     */
    private void drainInTransaction() {
        List<FidEvent> pending = FidEvent.findPending();
        for (FidEvent event : pending) {
            String path = event.eventType == FidEvent.EventType.TICKET_CLOSED
                    ? "/api/events/ticket-closed" : "/api/events/ticket-return";
            event.attempts++;
            try {
                int status = post(path, event.payloadJson);
                if (status == 202) {
                    event.sentAt = LocalDateTime.now();
                    event.lastError = null;
                } else {
                    event.lastError = "HTTP " + status;
                    LOGGER.warnf("Événement fidélité %d refusé (%s): nouvel essai au prochain cycle",
                            event.id, event.lastError);
                }
            } catch (Exception e) {
                event.lastError = e.getMessage();
                LOGGER.debugf("imfid injoignable pour l'événement %d: rejoué au prochain cycle", event.id);
                return; // Down: no point hammering the rest of the queue now.
            }
        }
    }

    /**
     * Posts one event body to imfid.
     *
     * @param path the endpoint path
     * @param payloadJson the body
     * @return the HTTP status
     * @throws Exception on transport failure
     */
    private int post(String path, String payloadJson) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.orElseThrow() + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson));
        if (user.isPresent() && password.isPresent()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user.get() + ":" + password.get()).getBytes()));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
