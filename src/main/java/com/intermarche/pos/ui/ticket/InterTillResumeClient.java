package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 * <p>The node lives behind {@code pos.parking.node-url}, reached over HTTP like
 * the other node clients. When no URL is configured this client answers a LOCAL
 * stub verdict — it reaches no node, so every number resolves to {@code null}
 * (not found) and the standalone lane keeps its offline-only behaviour unchanged.
 * The connected fetch of a foreign till's parked ticket is therefore [N] e2e
 * residue (it needs imedge running); unit tests inject a mock of this client
 * returning a PARKED ticket to exercise the connected arm.
 */
@ApplicationScoped
public class InterTillResumeClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(InterTillResumeClient.class);

    /** The store-node base URL; absent = the local stub (no foreign ticket reachable). */
    @ConfigProperty(name = "pos.parking.node-url")
    Optional<String> nodeUrl;

    /**
     * Fetches, from the store node, a parked ticket named by its number when it
     * was parked on ANOTHER till of the store (EXP-46).
     *
     * @param ticketNumber the parked-ticket number the customer's receipt carries
     * @return the foreign parked ticket, or null when the number is blank, no node
     *         is configured (offline / standalone), or the node knows no such
     *         parked ticket
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
        // A configured node would be queried over HTTP here (imedge). Left to the
        // connected deployment; unit tests inject a mock returning a parked ticket.
        LOGGER.info("Exiting method fetchParkedTicket (configured node not reached in this build)");
        return null;
    }
}
