package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The SINGLE QUEUE of this register (BO-03-09-10): the hostess tells the
 * third-party queue system that her register is available for the next
 * customer, or not available.
 *
 * <p>Activated per register by {@code queue.single-enabled}: a register that
 * does not take part shows no key and publishes nothing. The state starts
 * UNAVAILABLE — a register just started has nobody serving at it — and each
 * gesture of the hostess flips it and publishes it to {@code queue.single-url}
 * as a small JSON body: the terminal, the state and the moment. A system that
 * does not answer leaves the state flipped on the register and the hostess
 * told that it was not published.
 */
@ApplicationScoped
public class SingleQueueService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SingleQueueService.class);

    /** The administered activation and address. */
    @Inject
    PosSettingsService posSettingsService;

    /** This register's identifier, the one the queue system knows it by. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** The client toward the queue system, replaced in a unit test. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** Whether the register is declared available for the next customer. */
    private volatile boolean available;

    /**
     * What the sale screen shows of the single queue.
     *
     * @param enabled whether the register takes part in the single queue
     * @param available whether it is declared available
     */
    public record View(boolean enabled, boolean available) {
    }

    /**
     * Returns what the sale screen shows.
     *
     * @return the view
     */
    public View view() {
        LOGGER.info("Entering method view");
        LOGGER.info("Exiting method view");
        return new View(posSettingsService.singleQueueEnabled(), available);
    }

    /**
     * Flips the availability and publishes it (BO-03-09-10).
     *
     * @return the published state — AVAILABLE or UNAVAILABLE — or null when the
     *         register does not take part, in which case nothing changes
     */
    public String toggle() {
        LOGGER.info("Entering method toggle");
        if (!posSettingsService.singleQueueEnabled()) {
            LOGGER.info("Exiting method toggle");
            return null;
        }
        available = !available;
        String state = available ? "AVAILABLE" : "UNAVAILABLE";
        LOGGER.info("Exiting method toggle");
        return state;
    }

    /**
     * Publishes the current availability to the queue system.
     *
     * @return true when the system acknowledged it, false when no address is
     *         administered or the system refused or did not answer
     */
    public boolean publish() {
        LOGGER.info("Entering method publish");
        String url = posSettingsService.singleQueueUrl();
        if (url.isEmpty()) {
            LOGGER.info("Exiting method publish");
            return false;
        }
        String body = JsonNodeFactory.instance.objectNode()
                .put("terminal", terminalId)
                .put("state", available ? "AVAILABLE" : "UNAVAILABLE")
                .put("at", LocalDateTime.now().toString())
                .toString();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            boolean acknowledged = response.statusCode() >= 200 && response.statusCode() < 300;
            LOGGER.info("Exiting method publish");
            return acknowledged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Exiting method publish");
            return false;
        } catch (Exception e) {
            LOGGER.warnf("Système de file unique injoignable : %s", e.getMessage());
            LOGGER.info("Exiting method publish");
            return false;
        }
    }
}
