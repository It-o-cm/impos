package com.intermarche.pos.ui.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The seam to the store node for INTER-TILL parked-ticket resume (EXP-46).
 *
 * <p>The offline rule is fixed and correct: a parked ticket resumes only on its
 * own till ({@link TicketParkingService#resume(Long)} filters on terminalId).
 * The CONNECTED rule adds a fallback: a ticket parked on ANOTHER till of the
 * store can be resumed here, the store node (imedge, a separate node out of this
 * repo) being the one that holds the consolidated parked tickets and authorizes
 * the hand-over.
 *
 * <p>The node lives behind {@code pos.parking.node-url}: when a URL is configured
 * the register issues {@code GET <url>/api/parked/{ticketNumber}} carrying the
 * shared {@code X-Sync-Token}, and maps the node's summary answer
 * ({@code terminalId}, {@code status}, {@code grandTotal}) onto a {@link Ticket}.
 * When no URL is configured this client answers a LOCAL stub verdict — it reaches
 * no node, so every number resolves to {@code null} (not found) and the standalone
 * lane keeps its offline-only behaviour unchanged. The call is TOLERANT: a node
 * down, unreachable, a non-2xx answer or an empty body all resolve to {@code null}
 * rather than an exception, so a network hiccup never disturbs a sale. The endpoint
 * is guarded node-side by {@code pos.role=store} + the token; the connected fetch is
 * [N] e2e residue (it needs imedge running).
 */
@ApplicationScoped
public class InterTillResumeClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(InterTillResumeClient.class);

    /** The store-node base URL; absent = the local stub (no foreign ticket reachable). */
    @ConfigProperty(name = "pos.parking.node-url")
    Optional<String> nodeUrl;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The JSON reader for the node's answer. */
    @jakarta.inject.Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the node (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** The node's summary of a parked ticket (tolerant subset). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class NodeParkedTicket {
        /** The till the ticket was parked on. */
        public String terminalId;
        /** The ticket status, e.g. PARKED. */
        public String status;
        /** The ticket grand total. */
        public BigDecimal grandTotal;
    }

    /**
     * Fetches, from the store node, a parked ticket named by its number when it
     * was parked on ANOTHER till of the store (EXP-46).
     *
     * @param ticketNumber the parked-ticket number the customer's receipt carries
     * @return the foreign parked ticket, or null when the number is blank, no node
     *         is configured (offline / standalone), the node knows no such parked
     *         ticket, or the node could not be reached
     */
    public Ticket fetchParkedTicket(String ticketNumber) {
        LOGGER.info("Entering method fetchParkedTicket");
        if (ticketNumber == null || ticketNumber.isBlank()) {
            LOGGER.info("Exiting method fetchParkedTicket with blank number");
            return null;
        }
        if (nodeUrl.isEmpty() || nodeUrl.get().isBlank()) {
            // No node configured: the standalone lane resumes only its own
            // parked tickets, so nothing foreign resolves (the connected fetch
            // needs imedge).
            LOGGER.info("Exiting method fetchParkedTicket with local stub (offline)");
            return null;
        }
        try {
            String encoded = URLEncoder.encode(ticketNumber.trim(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(nodeUrl.get().trim() + "/api/parked/" + encoded))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method fetchParkedTicket (node knows no such ticket)");
                return null;
            }
            NodeParkedTicket summary =
                    objectMapper.readValue(response.body(), NodeParkedTicket.class);
            Ticket ticket = new Ticket();
            ticket.ticketNumber = ticketNumber.trim();
            ticket.terminalId = summary.terminalId;
            ticket.grandTotal = summary.grandTotal;
            Ticket.TicketStatus parsed = parseStatus(summary.status);
            if (parsed != null) {
                ticket.status = parsed;
            }
            LOGGER.info("Exiting method fetchParkedTicket (ticket handed over by node)");
            return ticket;
        } catch (Exception e) {
            // Tolerant: a node down or a malformed answer is "no ticket", never
            // a thrown error that could disturb the lane.
            LOGGER.warnf("Nœud magasin injoignable pour le ticket en attente %s : %s",
                    ticketNumber, e.getMessage());
            LOGGER.info("Exiting method fetchParkedTicket (node unreachable, no ticket)");
            return null;
        }
    }

    /**
     * Maps the node's status string onto a {@link Ticket.TicketStatus}, or null
     * when it is blank or names no known status (the caller then rejects the
     * hand-over, since only a PARKED ticket may be resumed).
     *
     * @param raw the status string the node returned, possibly null
     * @return the matching status, or null when blank or unknown
     */
    private Ticket.TicketStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Ticket.TicketStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
