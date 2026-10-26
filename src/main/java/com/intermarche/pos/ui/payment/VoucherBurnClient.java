package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks the store node whether a voucher was already CONSUMED by a closed sale
 * of the shop (BO-11-01-18): the centralised burn of the scanned vouchers.
 *
 * <p>The node holds the closed sales of every register of the shop, so it is
 * the one place that knows a GS1 coupon was settled at another lane. A node
 * that cannot be asked — no synchronization, unreachable, an unexpected answer
 * — burns nothing: the coupon is then judged by the sale in progress alone, as
 * it was before the burn was centralised, rather than refused for a reason
 * nobody can check.
 */
@ApplicationScoped
public class VoucherBurnClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(VoucherBurnClient.class);

    /** Carries the store node's URL and whether the synchronization is wired at all. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** HTTP client toward the store node. */
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Tolerant reader: a newer store node may send properties this register ignores. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Where a voucher was burned, as the store node answers it. */
    public static class Burn {

        /** The voucher number. */
        public String number;

        /** The register that settled it. */
        public String terminalId;

        /** The ticket it settled. */
        public String ticketNumber;

        /** When that ticket was closed, ISO-8601, or null. */
        public String burnedAt;
    }

    /**
     * Asks the store node where a voucher was burned.
     *
     * @param number the voucher number
     * @return the burn, or null when the voucher is not burned or the node
     *     cannot tell
     */
    public Burn lookup(String number) {
        LOGGER.info("Entering method lookup");
        if (!syncOutboxService.isEnabled() || number == null || number.isBlank()) {
            LOGGER.info("Exiting method lookup (nothing to ask)");
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl() + "/api/sync/voucher-burn/"
                            + URLEncoder.encode(number.trim(), StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.info("Exiting method lookup (HTTP " + response.statusCode() + ")");
                return null;
            }
            Burn burn = objectMapper.readValue(response.body(), Burn.class);
            LOGGER.info("Exiting method lookup");
            return burn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Exiting method lookup (interrupted)");
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Nœud magasin injoignable pour le bon %s : %s", number, e.getMessage());
            return null;
        }
    }
}
