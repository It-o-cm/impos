package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.sale.VatBreakdown;
import com.intermarche.pos.ui.customer.QrCodeService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import org.jboss.logging.Logger;

/**
 * Public digital receipt (phase 4): the customer opens the short link printed
 * on the paper ticket (and shown on the customer display) from their own
 * device — no register lock applies. Access requires the unguessable key
 * generated at draft creation; only closed tickets are shown.
 * <p>
 * The email capture lives on this page (the customer's device has a real
 * keyboard); the delivery itself is {@link TicketMailService}'s business — a
 * real send when an SMTP relay is configured, a journal entry either way. The
 * register-side capture, with the loyalty holder's address offered, is the
 * separate {@code /ticket-email} screen (LC-08-02-09).
 * <p>
 * Security model: the URL is a CAPABILITY — possession of the link is the
 * whole authorization (it is printed on the customer's own paper ticket),
 * there is no session and no account, which is exactly right for a receipt
 * handed to an anonymous customer. The 16-hex key is generated at draft
 * creation so the link exists before the closing signature does, and the
 * CLOSED-only rule is what keeps the capability harmless: a guessed or
 * leaked id/key pair can never expose a live cart or a cancelled draft.
 * The QR endpoint sits under the same key but NOT under the CLOSED gate:
 * the image carries no ticket data, only the very URL its caller already
 * presented, and the customer display has to show it while the ticket is
 * still OPEN (the payment is complete, the operator has not pressed
 * TERMINER yet — after which the display loses the id altogether). Under
 * the CLOSED gate the QR was therefore 404 at the only moment it is shown.
 * A CANCELLED draft still gets none: its link would never resolve.
 * {@code pos.digital.base-url} only widens the printed/encoded prefix
 * (absent = relative paths, LAN demo mode).
 */
@Path("/t")
public class DigitalTicketResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DigitalTicketResource.class);

    @Inject @Location("digital-ticket") Template digitalTicket;

    @Inject
    QrCodeService qrCodeService;

    /** Actually delivers the receipt (LC-08-02-04/07/08). */
    @Inject
    TicketMailService ticketMailService;

    /** Public base URL encoded in the QR (e.g. "http://caisse04:8080"); absent = path only. */
    @ConfigProperty(name = "pos.digital.base-url")
    java.util.Optional<String> baseUrl;

    /**
     * Shows the digital receipt of a closed ticket.
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @param sent true when the PRG redirect of the email action flags a
     *        just-sent confirmation to display
     * @return the digital receipt page, or its unavailable variant
     */
    @GET
    @Path("/{id}/{key}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance view(@PathParam("id") Long id, @PathParam("key") String key,
                                 @QueryParam("sent") boolean sent) {
        LOGGER.info("Entering method view with id: " + id + ", key: " + key + ", sent: " + sent);
        Ticket ticket = load(id, key);
        LOGGER.info("Exiting method view");
        return render(ticket, id, key, sent);
    }

    /**
     * Stores the typed email on the ticket and sends the digital receipt
     * (mocked delivery, journaled).
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @param email the email typed by the customer
     * @return a 303 redirect to the receipt page (PRG pattern, so a browser
     *         reload never re-sends the email), flagged with the sent
     *         confirmation when the delivery was journaled
     */
    @POST
    @Path("/{id}/{key}/email")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response sendByEmail(@PathParam("id") Long id, @PathParam("key") String key,
                                @FormParam("email") String email) {
        LOGGER.info("Entering method sendByEmail with id: " + id + ", key: " + key + ", email: " + email);
        Ticket ticket = load(id, key);
        boolean sent = false;
        if (ticket != null && email != null && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            ticket.customerEmail = email.trim();
            ticket.persist();
            // The mail service journals the request either way and answers whether a
            // letter actually left; the customer is told the request was taken, which
            // is what this page can honestly promise.
            ticketMailService.send(ticket, ticket.customerEmail);
            sent = true;
        }
        LOGGER.info("Exiting method sendByEmail");
        return Response.seeOther(URI.create("/t/" + id + "/" + key + (sent ? "?sent=true" : ""))).build();
    }

    /**
     * Renders the QR code of the digital receipt link, under the same access
     * key as the page (shown on the customer display for the customer to
     * scan).
     * <p>
     * The CLOSED gate of {@link #load} does NOT apply here: the display shows
     * this image while the ticket is still open, and the image discloses
     * nothing beyond the id and key its caller already holds.
     *
     * @param id the ticket database id
     * @param key the access key printed on the paper ticket
     * @return the SVG QR code, or 404 when the key does not match or the
     *         ticket was cancelled
     */
    @GET
    @Path("/{id}/{key}/qr.svg")
    @Produces("image/svg+xml")
    public jakarta.ws.rs.core.Response qr(@PathParam("id") Long id, @PathParam("key") String key) {
        LOGGER.info("Entering method qr with id: " + id + ", key: " + key);
        Ticket ticket = loadByKey(id, key);
        if (ticket == null || ticket.status == Ticket.TicketStatus.CANCELLED) {
            LOGGER.info("Exiting method qr");
            return jakarta.ws.rs.core.Response.status(jakarta.ws.rs.core.Response.Status.NOT_FOUND).build();
        }
        String target = baseUrl.orElse("") + "/t/" + id + "/" + key;
        LOGGER.info("Exiting method qr");
        return jakarta.ws.rs.core.Response.ok(qrCodeService.toSvg(target)).build();
    }

    /**
     * Loads a ticket when the key matches and the ticket is closed — the gate
     * of the receipt page itself, which must never expose a live cart.
     *
     * @param id the ticket database id
     * @param key the presented access key
     * @return the ticket, or null when unavailable
     */
    private Ticket load(Long id, String key) {
        Ticket ticket = loadByKey(id, key);
        if (ticket == null || ticket.status != Ticket.TicketStatus.CLOSED) {
            return null;
        }
        return ticket;
    }

    /**
     * Loads a ticket on the sole strength of its access key, whatever its
     * lifecycle status.
     *
     * @param id the ticket database id
     * @param key the presented access key
     * @return the ticket, or null when it does not exist or the key does not
     *         match
     */
    private Ticket loadByKey(Long id, String key) {
        Ticket ticket = Ticket.findById(id);
        if (ticket == null
                || ticket.digitalKey == null
                || !ticket.digitalKey.equals(key)) {
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
                // Cancelled articles (lot C4) are outside the sale: excluded
                // from the online receipt's VAT ventilation as from its lines.
                if (line.cancelled) continue;
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
