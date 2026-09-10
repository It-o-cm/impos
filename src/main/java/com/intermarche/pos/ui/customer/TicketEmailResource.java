package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Sends the last closed ticket by e-mail FROM THE REGISTER (LC-08-02-09/-10/-11).
 *
 * <p>The customer's own page already carries a capture — on a device with a real
 * keyboard. This screen exists for the other half of the gesture: when the customer
 * is still at the till and their address is already known, the operator should not
 * have to make them take out a phone. So the address the loyalty referential holds is
 * OFFERED here, pre-filled, and the operator confirms it (LC-08-02-09).
 *
 * <p>Whether that offered address can be corrected is administered
 * ({@code ticket.email-editable}, LC-08-02-10). Disabled, a known address is sent as
 * it stands and only a customer the referential knows nothing about gets a keyboard —
 * which is exactly the case LC-08-02-11 describes.
 */
@Path("/ticket-email")
@DrawerMustBeClosed
public class TicketEmailResource {

    /** The register state: the last closed ticket and the attached card's holder. */
    @Inject
    PosState state;

    /** Delivers the receipt and journals the request. */
    @Inject
    TicketMailService ticketMailService;

    /** Resolves the last sale of this register, recovering it after a restart. */
    @Inject
    com.intermarche.pos.ui.ticket.TicketService ticketService;

    /** Says whether a retrieved address may be corrected (LC-08-02-10). */
    @Inject
    PosSettingsService posSettingsService;

    /** The screen itself. */
    @Inject
    @Location("ticket-email")
    Template ticketEmailPage;

    /**
     * Opens the screen on the last closed ticket, with the holder's address offered
     * when the referential supplied one.
     *
     * @return the send page, or a redirect to the sale screen when the register has
     *         closed no sale yet
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Object showEmailScreen() {
        // NO TICKET, NO SCREEN — through the same gate as RÉIMPRIMER, CODE-BARRES
        // TICKET and DUPLICATA CB, so the four functions of the menu that need the
        // last closed sale refuse in the same words at the same moment. Opening a
        // keyboard over nothing would be the odd one out, and would make the operator
        // press a button that cannot work before being told.
        ticketService.resolveLastClosedTicketId(state);
        if (!state.requireLastClosedTicket()) {
            return Response.seeOther(URI.create("/")).build();
        }
        return page(state.fidelity.holderEmail == null ? "" : state.fidelity.holderEmail,
                "", false);
    }

    /**
     * Sends the last closed ticket to the address confirmed or typed by the operator.
     *
     * @param email the address to send to
     * @return the send page, carrying the outcome
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance sendEmail(@FormParam("email") String email) {
        String address = email == null ? "" : email.trim();
        // Same two conditions as the gate above, worded the same way; they are
        // re-checked here because the ticket can be gone by the time the form
        // comes back (a Z closing between the two requests, say).
        if (state.trainingMode) {
            return page(address, PosState.TRAINING_FORBIDDEN, false);
        }
        if (state.lastClosedTicketId == null) {
            return page(address, PosState.NO_LAST_TICKET, false);
        }
        if (!address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            return page(address, "ADRESSE INVALIDE", false);
        }
        Ticket ticket = Ticket.findById(state.lastClosedTicketId);
        if (ticket == null) {
            return page(address, "AUCUN TICKET À ENVOYER", false);
        }
        // The address the customer gave at the till belongs to the ticket, exactly as
        // the one typed on their own page does: a later send finds it again.
        ticket.customerEmail = address;
        ticket.persist();
        ticketMailService.send(ticket, address);
        return page(address, "", true);
    }

    /**
     * Renders the screen.
     *
     * <p>The keyboard is offered when the operator may type: either the referential
     * gave no address, or the back office allows correcting the one it gave. With a
     * known address and correction disabled, the screen shows it and offers the send
     * alone (LC-08-02-10).
     *
     * @param address the address shown
     * @param error the operator-facing refusal, empty when there is none
     * @param sent whether the send was just taken
     * @return the rendered page, showing no entry at all when the register has
     *         closed no sale yet
     */
    private TemplateInstance page(String address, String error, boolean sent) {
        boolean known = state.fidelity.holderEmail != null
                && !state.fidelity.holderEmail.isBlank();
        boolean editable = !known || posSettingsService.ticketEmailEditable();
        return ticketEmailPage
                .data("state", state)
                .data("address", address)
                .data("known", known)
                .data("editable", editable)
                .data("error", error)
                .data("sent", sent)
                .data("relayConfigured", ticketMailService.isConfigured());
    }
}
