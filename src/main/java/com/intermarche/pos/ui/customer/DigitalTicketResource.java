package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import com.intermarche.pos.service.QrCodeService;
import com.intermarche.pos.service.TechnicalEventService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Public digital receipt (phase 4): the customer opens the short link printed
 * on the paper ticket (and shown on the customer display) from their own
 * device — no register lock applies. Access requires the unguessable key
 * generated at draft creation; only closed tickets are shown.
 * <p>
 * The email capture lives on this page (the customer's device has a real
 * keyboard); the actual SMTP delivery is mocked in the log until a mailer is
 * configured, and journaled either way.
 */
@Path("/t")
public class DigitalTicketResource {

    private static final Logger LOG = Logger.getLogger(DigitalTicketResource.class);

    @Inject @Location("digital-ticket") Template digitalTicket;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    QrCodeService qrCodeService;

    /** Public base URL encoded in the QR (e.g. "http://caisse04:8080"); absent = path only. */
    @ConfigProperty(name = "pos.digital.base-url")
    java.util.Optional<String> baseUrl;

    /**
     * Shows the digital receipt of a closed ticket.
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @return the digital receipt page, or its unavailable variant
     */
    @GET
    @Path("/{id}/{key}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance view(@PathParam("id") Long id, @PathParam("key") String key) {
        Ticket ticket = load(id, key);
        return render(ticket, id, key, false);
    }

    /**
     * Stores the typed email on the ticket and sends the digital receipt
     * (mocked delivery, journaled).
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @param email the email typed by the customer
     * @return the digital receipt page with the sent confirmation
     */
    @POST
    @Path("/{id}/{key}/email")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance sendByEmail(@PathParam("id") Long id, @PathParam("key") String key,
                                        @FormParam("email") String email) {
        Ticket ticket = load(id, key);
        boolean sent = false;
        if (ticket != null && email != null && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            ticket.customerEmail = email.trim();
            ticket.persist();
            // Mocked delivery until a mailer is configured
            LOG.infof("Ticket dématérialisé %s envoyé à %s (mock)", ticket.ticketNumber, ticket.customerEmail);
            technicalEventService.log(TechnicalEvent.EventType.DIGITAL_TICKET_SENT,
                    ticket.ticketNumber + " -> " + ticket.customerEmail);
            sent = true;
        }
        return render(ticket, id, key, sent);
    }

    /**
     * Renders the QR code of the digital receipt link, under the same access
     * key as the page (shown on the customer display for the customer to
     * scan).
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @return the SVG QR code, or 404 when the ticket is unavailable
     */
    @GET
    @Path("/{id}/{key}/qr.svg")
    @Produces("image/svg+xml")
    public jakarta.ws.rs.core.Response qr(@PathParam("id") Long id, @PathParam("key") String key) {
        if (load(id, key) == null) {
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.NOT_FOUND).build();
        }
        String target = baseUrl.orElse("") + "/t/" + id + "/" + key;
        return jakarta.ws.rs.core.Response.ok(qrCodeService.toSvg(target)).build();
    }

    /**
     * Loads a ticket when the key matches and the ticket is closed.
     *
     * @param id the ticket database id
     * @param key the presented access key
     * @return the ticket, or null when unavailable
     */
    private Ticket load(Long id, String key) {
        Ticket ticket = Ticket.findById(id);
        if (ticket == null
                || ticket.digitalKey == null
                || !ticket.digitalKey.equals(key)
                || ticket.status != Ticket.TicketStatus.CLOSED) {
            return null;
        }
        return ticket;
    }

    /**
     * Renders the digital receipt page with its VAT ventilation.
     *
     * @param ticket the loaded ticket, or null for the unavailable variant
     * @param id the requested ticket id (for the email form action)
     * @param key the presented key (for the email form action)
     * @param sent true when an email was just sent
     * @return the rendered page
     */
    private TemplateInstance render(Ticket ticket, Long id, String key, boolean sent) {
        VatBreakdown breakdown = new VatBreakdown();
        if (ticket != null) {
            for (TicketLine line : ticket.lines) {
                breakdown.add(line.vatRate, line.totalPrice);
            }
        }
        return digitalTicket
                .data("ticket", ticket)
                .data("buckets", ticket != null ? breakdown.getBuckets() : java.util.List.of())
                .data("path", "/t/" + id + "/" + key)
                .data("sent", sent);
    }
}
