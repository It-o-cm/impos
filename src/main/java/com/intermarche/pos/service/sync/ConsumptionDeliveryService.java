package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Delivery loop of the campaign-consumption declarations toward the valuation
 * engine — the ascending counterpart of {@link EngineFeedDeliveryService}: the
 * feeds descend the definition, this ships the consumption up.
 *
 * <p>Every {@code pos.valuation.feed-delivery-seconds} it drains the pending
 * declarations and POSTs each as JSON to {@code {pos.valuation.url}/consumptions}
 * (HTTP Basic, the same credentials as the valuation calls). A 2xx answer is the
 * acknowledgement; a failure is recorded on the row and retried next cycle.
 * Because the engine is idempotent on {@code (storeCode, ticketId, direction)},
 * a replay by any link is safe, and — unlike the feeds — the declarations carry
 * no ordering, so a failed one does NOT stop the others.
 *
 * <p>Disabled when {@code pos.valuation.url} is absent, like every engine
 * interaction on this register. The sale never waits on it: the payment only
 * enqueues a row.
 */
@ApplicationScoped
public class ConsumptionDeliveryService {

    /** Logger for this service. */
    private static final Logger LOGGER = Logger.getLogger(ConsumptionDeliveryService.class);

    /** JSON writer for the declaration body. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base URL of the local engine; absent = delivery disabled. */
    @ConfigProperty(name = "pos.valuation.url")
    Optional<String> url;

    /** Basic-auth user of the engine; absent = no authentication header. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** Basic-auth password of the engine. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Seconds between two delivery cycles (shared with the feed delivery cadence). */
    @ConfigProperty(name = "pos.valuation.feed-delivery-seconds", defaultValue = "30")
    long deliverySeconds;

    /** Delivery call timeout in milliseconds. */
    @ConfigProperty(name = "pos.valuation.timeout-millis", defaultValue = "2500")
    long timeoutMillis;

    /** The consumption queue keeper (transactional reads and acknowledgements). */
    @Inject
    ConsumptionService consumptionService;

    /** The delivery loop executor, or null when delivery is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client toward the local engine. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the delivery loop at startup when an engine URL is configured.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (url.isEmpty() || url.get().isBlank()) {
            LOGGER.info("Livraison des consommations désactivée (pos.valuation.url absent)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "consumption-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::deliverSafely,
                Math.min(20, deliverySeconds), deliverySeconds, TimeUnit.SECONDS);
        LOGGER.infof("Livraison des consommations active vers %s (toutes les %ds)",
                url.get(), deliverySeconds);
    }

    /**
     * Stops the delivery loop when the bean is destroyed (application shutdown),
     * so its thread does not outlive the CDI container across test JVMs.
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
            LOGGER.warnf("Cycle de livraison des consommations en échec: %s", e.getMessage());
        }
    }

    /**
     * Delivers every pending declaration; a failure on one does not stop the
     * others, since declarations carry no ordering.
     */
    void deliverPending() {
        for (ConsumptionService.PendingConsumption pending : consumptionService.pending()) {
            deliver(pending);
        }
    }

    /**
     * Runs one delivery cycle NOW, on the caller's thread, for a supervision
     * trigger; the outcome is returned rather than swallowed.
     *
     * @return null when the cycle ran, or the failure message otherwise
     */
    public String triggerDelivery() {
        LOGGER.info("Entering method triggerDelivery");
        try {
            deliverPending();
            LOGGER.info("Exiting method triggerDelivery");
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Livraison manuelle des consommations en échec: %s", e.getMessage());
            LOGGER.info("Exiting method triggerDelivery");
            return e.getMessage();
        }
    }

    /**
     * Delivers one declaration to the engine and records the outcome.
     *
     * @param pending the detached snapshot to deliver
     * @return true when the engine acknowledged (2xx), false otherwise
     */
    private boolean deliver(ConsumptionService.PendingConsumption pending) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.orElseThrow() + "/consumptions"))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body(pending)));
            if (user.isPresent() && !user.get().isBlank()) {
                String token = Base64.getEncoder().encodeToString(
                        (user.get() + ":" + password.orElse("")).getBytes());
                builder.header("Authorization", "Basic " + token);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                consumptionService.markApplied(pending.id());
                LOGGER.infof("Consommation %s/%s livrée", pending.ticketNumber(), pending.direction());
                return true;
            }
            consumptionService.recordFailure(pending.id(), "HTTP " + response.statusCode()
                    + ": " + Objects.toString(response.body(), ""));
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            consumptionService.recordFailure(pending.id(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Composes the JSON body of a declaration, embedding the advantages array.
     *
     * @param pending the declaration to serialize
     * @return the JSON body
     * @throws Exception when the advantages JSON is unreadable
     */
    String body(ConsumptionService.PendingConsumption pending) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ticketId", pending.ticketNumber());
        node.put("storeCode", pending.storeCode());
        node.put("finalizedAt", pending.finalizedAt() == null ? null : pending.finalizedAt().toString());
        node.put("direction", pending.direction().name());
        node.set("advantages", pending.advantagesJson() == null
                ? MAPPER.createArrayNode() : MAPPER.readTree(pending.advantagesJson()));
        return MAPPER.writeValueAsString(node);
    }
}
