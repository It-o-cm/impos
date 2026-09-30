package com.intermarche.pos.ui.supervision;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The caisse-side SEAM that PULLS a pending back-office message from the store
 * node (EXP-20).
 *
 * <p>The topology is pull, not push: the caisse interrogates the node, the node
 * never has to know a caisse address. This client asks the node
 * {@code pos.message.node-url} — naming this terminal — for the message currently
 * addressed to it, if any. It is TOLERANT by construction: no URL configured (a
 * standalone lane), an unreachable node, or a non-2xx answer all resolve to
 * "no message" rather than an exception, so a network hiccup never disturbs a
 * sale. The node producer lives in imedge; the real fetch is connected-mode
 * ({@code [N]}) territory.
 */
@ApplicationScoped
public class BackOfficeMessageClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BackOfficeMessageClient.class);

    /** The store node's pending-message endpoint; absent = standalone lane. */
    @ConfigProperty(name = "pos.message.node-url")
    Optional<String> nodeUrl;

    /** Shared token sent to the node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** This register's identifier, named on the request so the node targets it. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** The JSON reader for the node's answer. */
    @jakarta.inject.Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** The HTTP client used toward the node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * A pending back-office message pulled from the node: a plain notice, or a
     * survey when {@code question} is present.
     *
     * @param text the message text, or null when there is only a survey
     * @param question the survey question, or null for a plain notice
     * @param options the survey options as a semicolon list, or null
     */
    public record PendingMessage(String text, String question, String options) {
    }

    /**
     * Pulls the message the node currently addresses to this terminal, or null
     * when there is none, no node is configured, or the node could not be reached.
     *
     * @return the pending message, or null
     */
    public PendingMessage fetchPending() {
        LOGGER.info("Entering method fetchPending");
        if (nodeUrl.isEmpty() || nodeUrl.get().isBlank()) {
            LOGGER.info("Exiting method fetchPending");
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(nodeUrl.get() + "?terminal=" + terminalId))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetchPending");
                return null;
            }
            PendingMessage message = objectMapper.readValue(response.body(), PendingMessage.class);
            LOGGER.info("Exiting method fetchPending");
            return message;
        } catch (Exception e) {
            // Tolerant: a node down or a malformed answer is "no message", never a
            // thrown error that could disturb the lane.
            LOGGER.warnf("Message back-office indisponible (%s)", e.getMessage());
            LOGGER.info("Exiting method fetchPending");
            return null;
        }
    }
}
