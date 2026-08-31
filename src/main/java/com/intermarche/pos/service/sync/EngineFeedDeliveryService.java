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
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivery loop of the verbatim feeds toward the valuation engine attached
 * to THIS node — the last hop of the single-import-line chain (central →
 * store POS → register POS → local imvaluation). Runs on every role: the
 * store node feeds its own engine exactly like a register feeds its own.
 * <p>
 * Every {@code pos.valuation.feed-delivery-seconds}, walks the catalog IN
 * ORDER and, for each feed whose stored version differs from the version
 * the engine last acknowledged, POSTs the raw content to the engine's
 * import endpoint (HTTP Basic, same credentials as the valuation calls).
 * A 2xx answer is the ACK: the applied version is recorded. On the first
 * failure the walk STOPS — shipping OFFERS while PRODUCTS failed would
 * break the shared-before-specific application order — the error is
 * recorded on the feed and the next tick retries.
 * <p>
 * Disabled when {@code pos.valuation.url} is absent, like every engine
 * interaction on this register.
 */
@ApplicationScoped
public class EngineFeedDeliveryService {

    private static final Logger LOG = Logger.getLogger(EngineFeedDeliveryService.class);

    /** Base URL of the local engine; absent = delivery disabled. */
    @ConfigProperty(name = "pos.valuation.url")
    Optional<String> url;

    /** Basic-auth user of the engine; absent = no authentication header. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** Basic-auth password of the engine. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Seconds between two delivery cycles. */
    @ConfigProperty(name = "pos.valuation.feed-delivery-seconds", defaultValue = "30")
    long deliverySeconds;

    /** Import call timeout in milliseconds (bulk ingestion can be slow). */
    @ConfigProperty(name = "pos.valuation.feed-timeout-millis", defaultValue = "60000")
    long timeoutMillis;

    /** The feed keeper (catalog, contents, acknowledgements). */
    @Inject
    EngineFeedService engineFeedService;

    /** The delivery loop executor, or null when delivery is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client toward the local engine. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the delivery loop at startup when an engine URL is configured;
     * the first cycle runs shortly after boot so a fresh node fills its
     * engine quickly.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (url.isEmpty() || url.get().isBlank()) {
            LOG.info("Livraison des flux moteur désactivée (pos.valuation.url absent)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "engine-feed-delivery");
            thread.setDaemon(true);
            return thread;
        });
        // First run after min(20s, period): a demo stack with a short
        // period gets its first delivery quickly, production keeps the
        // boot-quiet 20 seconds.
        executor.scheduleWithFixedDelay(this::deliverSafely,
                Math.min(20, deliverySeconds), deliverySeconds, TimeUnit.SECONDS);
        LOG.infof("Livraison des flux moteur active vers %s (toutes les %ds)", url.get(), deliverySeconds);
    }

    /**
     * Stops the delivery loop when the bean is destroyed (application
     * shutdown) — the same lifecycle pattern as the other scheduled
     * services: without this the thread outlives its CDI container,
     * harmless in production where the JVM dies with the application, but
     * a zombie in a test JVM where Quarkus instances succeed one another,
     * each dead instance's loop then failing every cycle against a closed
     * container.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Runs one delivery cycle, never letting an exception kill the loop.
     */
    void deliverSafely() {
        try {
            deliverPending();
        } catch (Exception e) {
            LOG.warnf("Cycle de livraison moteur en échec: %s", e.getMessage());
        }
    }

    /**
     * Delivers every pending feed, in catalog order, from the detached
     * snapshot handed by the transactional keeper (this thread has no
     * persistence context of its own); stops at the first failure to
     * preserve the application order.
     */
    void deliverPending() {
        for (EngineFeedService.PendingFeed feed : engineFeedService.pendingFeeds()) {
            if (!deliver(feed)) return;
        }
    }

    /**
     * Delivers one feed to the engine and records the outcome.
     *
     * @param feed the detached snapshot to deliver
     * @return true when the engine acknowledged (2xx), false otherwise
     */
    private boolean deliver(EngineFeedService.PendingFeed feed) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.orElseThrow() + feed.enginePath()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(feed.content()));
            if (user.isPresent() && !user.get().isBlank()) {
                String token = Base64.getEncoder().encodeToString(
                        (user.get() + ":" + password.orElse("")).getBytes());
                builder.header("Authorization", "Basic " + token);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                engineFeedService.markApplied(feed.code(), feed.version());
                LOG.infof("Flux moteur %s livré (version %s)", feed.code(), feed.version().substring(0, 12));
                return true;
            }
            recordFailure(feed, "HTTP " + response.statusCode() + " sur " + feed.enginePath()
                    + ": " + Objects.toString(response.body(), ""));
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            recordFailure(feed, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Records a delivery failure, logging it only when the message changed
     * since the previous attempt — an engine down for an hour is one line,
     * not one per tick.
     *
     * @param feed the snapshot whose delivery failed
     * @param error the failure message
     */
    private void recordFailure(EngineFeedService.PendingFeed feed, String error) {
        if (!Objects.equals(feed.lastError(), error)) {
            LOG.warnf("Livraison du flux moteur %s en échec: %s", feed.code(), error);
        }
        engineFeedService.markError(feed.code(), error);
    }
}
