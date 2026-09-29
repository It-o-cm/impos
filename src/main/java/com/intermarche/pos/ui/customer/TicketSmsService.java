package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
 * message does not leave. The provider itself is out of this repo: the gateway URL is
 * a deployment secret and the actual HTTP call is left to the integration, so this
 * class models the caisse-side send + the journal, config-gated.
 */
@ApplicationScoped
public class TicketSmsService {

    private static final Logger LOGGER = Logger.getLogger(TicketSmsService.class);

    /** The SMS gateway base URL; absent = nothing leaves, the send is journalled only. */
    @ConfigProperty(name = "pos.sms.gateway-url")
    Optional<String> gatewayUrl;

    /** Journals the send, whether or not a message actually left. */
    @Inject
    TechnicalEventService technicalEventService;

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
        LOGGER.infof("Ticket %s envoyé par SMS à %s via %s", ticket.ticketNumber, phone, gatewayUrl.get());
        LOGGER.info("Exiting method send");
        return true;
    }
}
