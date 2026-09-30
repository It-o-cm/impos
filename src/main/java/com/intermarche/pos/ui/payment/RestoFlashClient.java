package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The seam to the Resto Flash mobile meal-voucher service (EXP-67): a titre
 * restaurant settled from the customer's phone. The register asks Resto Flash
 * to confirm a mobile payment token for an amount; only a confirmed token
 * settles the ticket.
 *
 * <p>The real service lives behind {@code pos.restoflash.url}: when a URL is
 * configured the register issues {@code POST <url>/api/restoflash/confirm}
 * carrying the shared {@code X-Sync-Token} and a {@code {token, amount}} body, and
 * reads {@code {accepted}}. With no URL the client answers a LOCAL stub verdict (a
 * valid token confirms) so a standalone lane runs deterministically. On the
 * CONNECTED lane robustness never lets the payment path fall over: a service down,
 * unreachable, a non-2xx answer or an empty body all resolve to a REFUSAL (voucher
 * not accepted) rather than an exception. The endpoint is guarded node-side by
 * {@code pos.role=store} + the token; the connected confirm is [N] e2e residue.
 */
@ApplicationScoped
public class RestoFlashClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RestoFlashClient.class);

    /** The Resto Flash base URL; absent = the local stub verdict applies. */
    @ConfigProperty(name = "pos.restoflash.url")
    Optional<String> serviceUrl;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> syncToken;

    /** The JSON reader/writer for the service exchange. */
    @jakarta.inject.Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the service (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** The service's answer body (tolerant subset). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class ConfirmAnswer {
        /** Whether Resto Flash accepts the payment. */
        public boolean accepted;
    }

    /**
     * Confirms a Resto Flash mobile meal-voucher payment (EXP-67).
     *
     * @param token the mobile payment token presented by the customer
     * @param amount the amount to confirm
     * @return true when Resto Flash confirms the payment
     */
    public boolean confirm(String token, BigDecimal amount) {
        LOGGER.info("Entering method confirm");
        if (token == null || token.isBlank() || amount == null || amount.signum() <= 0) {
            LOGGER.info("Exiting method confirm with refusal");
            return false;
        }
        if (serviceUrl.isEmpty() || serviceUrl.get().isBlank()) {
            LOGGER.info("Exiting method confirm with local stub confirmation");
            return true;
        }
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("token", token.trim(), "amount", amount));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl.get().trim() + "/api/restoflash/confirm"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            syncToken.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method confirm (service refused, voucher not accepted)");
                return false;
            }
            ConfirmAnswer answer = objectMapper.readValue(response.body(), ConfirmAnswer.class);
            LOGGER.info("Exiting method confirm (service answered)");
            return answer.accepted;
        } catch (Exception e) {
            // Robust: an unreachable service must NOT accept an unconfirmed voucher.
            LOGGER.warnf("Service Resto Flash injoignable : %s", e.getMessage());
            LOGGER.info("Exiting method confirm (service unreachable, voucher not accepted)");
            return false;
        }
    }
}
