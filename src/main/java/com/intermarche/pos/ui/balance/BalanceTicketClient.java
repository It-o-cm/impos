package com.intermarche.pos.ui.balance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.service.sync.SyncPayloads;
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
 * Picks a counter ticket up from the store node, at the register (LC-06-01-02).
 *
 * <p>The scale never talks to a register: at weighing time nobody knows which lane
 * the customer will walk to. It pushes to the shop, the shop holds, and the scan at
 * the till is the RENDEZ-VOUS — this client is what turns that scan into the lines.
 *
 * <p>The pick-up is a GET that consumes on the shop side, so it must be called ONCE
 * per paper and its answer is the arbitration: a second pick-up of the same reference,
 * here or at another lane, comes back {@link Outcome#ALREADY_CONSUMED}. That is why the
 * three failures are told apart rather than folded into one empty answer — the cashier
 * facing "already picked up" and the cashier facing "shop unreachable" are not in the
 * same situation, and only the second one is allowed to key the paper in by hand.
 */
@ApplicationScoped
public class BalanceTicketClient {

    private static final Logger LOGGER = Logger.getLogger(BalanceTicketClient.class);

    /** Carries the store node's URL and whether the synchronization is wired at all. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** Shared HTTP client toward the store node (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Tolerant reader: a newer store node may send properties this register ignores. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * What the shop answered when this register presented a counter reference.
     */
    public enum Outcome {
        /** The shop served the ticket and marked it consumed; {@code ticket} is set. */
        SERVED,
        /** No store node is configured on this register: nothing to ask. */
        NO_STORE_NODE,
        /** A store node is configured but could not be reached or refused the call. */
        UNREACHABLE,
        /** The shop holds no such reference, or has already served it to someone. */
        ALREADY_CONSUMED
    }

    /**
     * The shop's answer: an outcome, plus the ticket when the outcome is SERVED.
     *
     * @param outcome what the shop answered
     * @param ticket the served counter ticket, or null on any non-SERVED outcome
     */
    public record Answer(Outcome outcome, SyncPayloads.BalanceTicketDto ticket) {

        /**
         * Compact constructor documenting that a ticket only rides with SERVED.
         *
         * @param outcome what the shop answered
         * @param ticket the served counter ticket, or null
         */
        public Answer {
            // Nothing to normalize: the factory methods below are the only builders.
        }

        /**
         * Builds the answer of a successful pick-up.
         *
         * @param ticket the served counter ticket
         * @return a SERVED answer carrying the ticket
         */
        public static Answer served(SyncPayloads.BalanceTicketDto ticket) {
            return new Answer(Outcome.SERVED, ticket);
        }

        /**
         * Builds a failure answer, which never carries a ticket.
         *
         * @param outcome the non-SERVED outcome
         * @return an answer carrying that outcome and no ticket
         */
        public static Answer failed(Outcome outcome) {
            return new Answer(outcome, null);
        }
    }

    /**
     * Whether a store node is configured at all; without one there is nowhere to ask.
     *
     * @return true when the register knows a store node
     */
    public boolean isAvailable() {
        return syncOutboxService.isEnabled();
    }

    /**
     * Presents a counter reference to the shop and, when the shop still holds it,
     * takes it — the shop marks it consumed in the same call.
     *
     * @param reference the reference read off the counter paper
     * @param terminalId the register picking it up, recorded by the shop
     * @return the shop's answer, never null
     */
    public Answer pickUp(String reference, String terminalId) {
        if (!isAvailable()) {
            return Answer.failed(Outcome.NO_STORE_NODE);
        }
        if (reference == null || reference.isBlank()) {
            return Answer.failed(Outcome.ALREADY_CONSUMED);
        }
        String encodedReference = URLEncoder.encode(reference.trim(), StandardCharsets.UTF_8);
        String encodedTerminal = URLEncoder.encode(
                terminalId == null ? "" : terminalId, StandardCharsets.UTF_8);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl()
                            + "/api/sync/balance-ticket/" + encodedReference
                            + "?terminal=" + encodedTerminal))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Answer.failed(Outcome.ALREADY_CONSUMED);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.infof("Ticket comptoir %s refusé par le nœud magasin (HTTP %d)",
                        reference, response.statusCode());
                return Answer.failed(Outcome.UNREACHABLE);
            }
            SyncPayloads.BalanceTicketDto served =
                    objectMapper.readValue(response.body(), SyncPayloads.BalanceTicketDto.class);
            // A body that parses to nothing usable is a broken shop, not an empty ticket:
            // saying UNREACHABLE keeps the paper keyable by hand instead of losing it.
            if (served == null || served.lines == null || served.lines.isEmpty()) {
                LOGGER.warnf("Ticket comptoir %s servi sans ligne exploitable", reference);
                return Answer.failed(Outcome.UNREACHABLE);
            }
            return Answer.served(served);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.failed(Outcome.UNREACHABLE);
        } catch (Exception e) {
            LOGGER.warnf("Nœud magasin injoignable pour le ticket comptoir %s : %s",
                    reference, e.getMessage());
            return Answer.failed(Outcome.UNREACHABLE);
        }
    }
}
