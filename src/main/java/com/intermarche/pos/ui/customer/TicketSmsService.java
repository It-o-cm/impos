package com.intermarche.pos.ui.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Sends a customer's receipt by SMS (EXP-48 — « ticket dématérialisé par mail OU
 * téléphone »): the mirror of {@link TicketMailService} for the phone channel.
 *
 * <p>The SMS carries the retrieval link / short reference of the ticket rather than
 * the 42-column frame (a text message is not a document), but the GESTURE and the
 * JOURNAL are identical to the mail send: the customer asked for their receipt at
 * this register, recorded as {@code DIGITAL_TICKET_SENT} whether or not a message
 * actually left. WITHOUT A GATEWAY nothing is sent and nothing fails — a register on
 * a demo bench, or one whose store has no SMS provider, keeps working; only the
 * message does not leave.
 *
 * <p>With a gateway configured the register issues {@code POST <url>/api/sms/send}
 * carrying the shared {@code X-Sync-Token} and a {@code {phone, ticketNumber}} body,
 * and reads {@code {sent}}. The send never lets the sale path fall over: a gateway
 * down, unreachable, a non-2xx answer or an empty body all resolve to "journalled
 * only" (not sent) rather than an exception. The journal is written FIRST, so it
 * records the customer's request whatever happens to the message. The connected send
 * is [N] e2e residue (it needs the gateway running).
 */
@ApplicationScoped
public class TicketSmsService {

    private static final Logger LOGGER = Logger.getLogger(TicketSmsService.class);

    /** The SMS gateway base URL; absent = nothing leaves, the send is journalled only. */
    @ConfigProperty(name = "pos.sms.gateway-url")
    Optional<String> gatewayUrl;

    /** Shared ingestion token presented to the gateway; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> syncToken;

    /** Journals the send, whether or not a message actually left. */
    @Inject
    TechnicalEventService technicalEventService;

    /** The JSON reader/writer for the gateway exchange. */
    @Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the gateway (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** The gateway's answer body (tolerant subset). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class SendAnswer {
        /** Whether the gateway sent the message. */
        public boolean sent;
    }

    /**
     * Whether an SMS gateway is configured; without one the send is journalled only.
     *
     * @return true when a message can actually leave
     */
    public boolean isConfigured() {
        LOGGER.info("Entering method isConfigured");
        LOGGER.info("Exiting method isConfigured");
        return gatewayUrl.isPresent() && !gatewayUrl.get().isBlank();
    }

    /**
     * Sends a ticket to a customer's phone number and journals the send.
     *
     * <p>The journal entry is written whatever happens to the message — what it
     * records is that the customer asked for their receipt at this register, true
     * even when no gateway is configured. Answered with false when the message was
     * journalled only.
     *
     * @param ticket the closed ticket to send
     * @param phone the customer's phone number, already validated by the caller
     * @return true when a message actually left, false when it was journalled only
     */
    public boolean send(Ticket ticket, String phone) {
        LOGGER.info("Entering method send with ticket: " + ticket + ", phone: " + phone);
        technicalEventService.log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                ticket.ticketNumber + " -> SMS " + phone);
        if (!isConfigured()) {
            LOGGER.infof("Ticket %s envoyé par SMS à %s (aucune passerelle configurée : journalisé seulement)",
                    ticket.ticketNumber, phone);
            LOGGER.info("Exiting method send");
            return false;
        }
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("phone", phone, "ticketNumber", ticket.ticketNumber));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl.get().trim() + "/api/sms/send"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            syncToken.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.infof("Ticket %s : passerelle SMS a refusé l'envoi (journalisé seulement)",
                        ticket.ticketNumber);
                LOGGER.info("Exiting method send");
                return false;
            }
            SendAnswer answer = objectMapper.readValue(response.body(), SendAnswer.class);
            LOGGER.infof("Ticket %s envoyé par SMS à %s via %s (envoyé=%b)",
                    ticket.ticketNumber, phone, gatewayUrl.get(), answer.sent);
            LOGGER.info("Exiting method send");
            return answer.sent;
        } catch (Exception e) {
            // Tolerant: an unreachable gateway is "journalled only", never a thrown
            // error that could disturb the sale — the journal already recorded it.
            LOGGER.warnf("Passerelle SMS injoignable pour le ticket %s : %s",
                    ticket.ticketNumber, e.getMessage());
            LOGGER.info("Exiting method send");
            return false;
        }
    }
}
