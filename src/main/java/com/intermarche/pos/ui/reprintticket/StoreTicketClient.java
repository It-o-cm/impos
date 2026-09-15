package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.service.sync.SyncOutboxService;
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
 * Asks the store node for a ticket this register does not hold (LC-08-05-05).
 *
 * <p>The sales of the shop all reach the store node — that is what the outbox is for —
 * so a ticket made on ANOTHER register exists there and nowhere else from here. This
 * client asks for it ALREADY RENDERED rather than as a payload: the store node runs
 * the register's own renderer, so the duplicata a customer is handed reads exactly
 * like the paper they got at the other till, and this register stays a printer.
 *
 * <p>Nothing of the foreign ticket is stored locally. A register's ticket table is its
 * own record of what IT sold; copying another register's sales into it to be able to
 * print them would corrupt that record for one slip of paper.
 *
 * <p>Every failure is an empty answer, never an exception: the store node may be down,
 * unreachable or simply not configured (a standalone register), and none of that is
 * an incident the cashier should meet as a stack trace.
 */
@ApplicationScoped
public class StoreTicketClient {

    private static final Logger LOGGER = Logger.getLogger(StoreTicketClient.class);

    /** Carries the store node's URL and whether the synchronization is wired at all. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The HTTP client used toward the store node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Whether a store node is configured at all; without one there is nowhere to ask.
     *
     * @return true when the register knows a store node
     */
    public boolean isAvailable() {
        return syncOutboxService.isEnabled();
    }

    /**
     * Fetches the duplicata of a ticket from the store node.
     *
     * @param ticketNumber the number of the ticket, as printed on the customer's paper
     * @return the rendered duplicata, or empty when there is no store node, no such
     *         ticket, or the node could not be reached
     */
    public Optional<String> fetchDuplicate(String ticketNumber) {
        if (!isAvailable() || ticketNumber == null || ticketNumber.isBlank()) {
            return Optional.empty();
        }
        String encoded = URLEncoder.encode(ticketNumber.trim(), StandardCharsets.UTF_8);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl()
                            + "/api/sync/ticket/" + encoded + "/duplicata"))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
            }
            LOGGER.infof("Duplicata %s refusé par le nœud magasin (HTTP %d)",
                    ticketNumber, response.statusCode());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warnf("Nœud magasin injoignable pour le duplicata %s : %s",
                    ticketNumber, e.getMessage());
            return Optional.empty();
        }
    }
}
