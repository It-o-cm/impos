package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * JAX-RS resource of the reprint screen, under its own {@code /reprint}
 * prefix: history list, paged navigation, ticket detail and the duplicata
 * print action. Review is free (reading persisted tickets mutates
 * nothing); only the print action carries fiscal weight — it bumps the
 * duplicata counter and is blocked in training by the service.
 */
@Path("/reprint")
@DrawerMustBeClosed
public class ReprintResource {
    @Inject
    PosState state;
    @Inject ReprintService reprintService;

    @Inject @Location("reprint-ticket") Template reprintTicketPage;
    @Inject @Location("reprint-ticket-detail") Template reprintDetailPage;

    /** The mask naming a ticket held by another register (LC-08-05-05). */
    @Inject @Location("reprint-foreign") Template reprintForeignPage;

    @GET
    @Produces(MediaType.TEXT_HTML)
    /**
     * Shows the reprint screen with a fresh closed-ticket history.
     *
     * @return the reprint page
     */
    public TemplateInstance showReprintPage() {
        reprintService.loadHistory();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/prev")
    /**
     * Pages the history back.
     *
     * @return the reprint page
     */
    public TemplateInstance reprintPrevPage() {
        if (state.reprint.isHasListPrev()) state.reprint.listPage--;
        state.touch();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/next")
    /**
     * Pages the history forward.
     *
     * @return the reprint page
     */
    public TemplateInstance reprintNextPage() {
        if (state.reprint.isHasListNext()) state.reprint.listPage++;
        state.touch();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/view/{id}")
    /**
     * Opens a ticket in the detail view.
     *
     * @param id the database id of the ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance showReprintDetail(@PathParam("id") Long id) {
        Ticket t = Ticket.findById(id);
        if (t != null) {
            state.reprint.setViewedTicket(t);
        }
        state.touch();
        return reprintDetailPage.data("state", state);
    }

    @GET
    @Path("/view/{id}/prev")
    /**
     * Pages the detail back.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance detailPrevPage(@PathParam("id") Long id) {
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            return showReprintDetail(id);
        }
        if (state.reprint.isHasDetailPrev()) state.reprint.detailPage--;
        state.touch();
        return reprintDetailPage.data("state", state);
    }

    @GET
    @Path("/view/{id}/next")
    /**
     * Pages the detail forward.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    public TemplateInstance detailNextPage(@PathParam("id") Long id) {
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            return showReprintDetail(id);
        }
        if (state.reprint.isHasDetailNext()) state.reprint.detailPage++;
        state.touch();
        return reprintDetailPage.data("state", state);
    }

    @GET
    /**
     * Prints a numbered duplicata of a ticket (refused in training by the
     * service) and stays on the detail view.
     *
     * @param id the database id of the ticket to reprint
     * @return the reprint page
     */
    @Path("/print/{id}")
    public Response doReprint(@PathParam("id") Long id) {
        reprintService.print(id);
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }

    /**
     * Opens the bon-pour-échange preparation on the viewed ticket (LC-08-05-10).
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance startExchange(@PathParam("id") Long id) {
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            return showReprintDetail(id);
        }
        reprintService.startExchange();
        return reprintDetailPage.data("state", state);
    }

    /**
     * Leaves the bon-pour-échange preparation without printing.
     *
     * @param id the database id of the viewed ticket
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange/cancel")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cancelExchange(@PathParam("id") Long id) {
        reprintService.cancelExchange();
        return showReprintDetail(id);
    }

    /**
     * Names a line for the bon pour échange, or unnames it (LC-08-05-12).
     *
     * @param id the database id of the viewed ticket
     * @param lineId the database id of the line touched
     * @return the reprint page on its detail view
     */
    @GET
    @Path("/view/{id}/exchange/toggle/{lineId}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance toggleExchangeLine(@PathParam("id") Long id,
            @PathParam("lineId") Long lineId) {
        if (state.reprint.viewedTicket == null || !state.reprint.viewedTicket.id.equals(id)) {
            return showReprintDetail(id);
        }
        reprintService.toggleExchangeLine(lineId);
        return reprintDetailPage.data("state", state);
    }

    /**
     * Prints the bon pour échange of the viewed ticket (LC-08-05-13).
     *
     * @param id the database id of the ticket
     * @return a redirect to the detail view (PRG pattern, so a reload never prints a
     *         second bon)
     */
    @GET
    @Path("/view/{id}/exchange/print")
    public Response printExchange(@PathParam("id") Long id) {
        reprintService.printExchange(id);
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }

    /**
     * Opens the mask naming a ticket held by another register (LC-08-05-05).
     *
     * @return the foreign-duplicata page
     */
    @GET
    @Path("/foreign")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showForeign() {
        reprintService.startForeign();
        return reprintForeignPage.data("state", state);
    }

    /**
     * Prints the duplicata of a ticket made on another register (LC-08-05-05).
     *
     * @param ticketNumber the number typed
     * @return the foreign-duplicata page, carrying the failure when there was one
     */
    @jakarta.ws.rs.POST
    @Path("/foreign")
    @jakarta.ws.rs.Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance printForeign(
            @jakarta.ws.rs.FormParam("ticketNumber") String ticketNumber) {
        reprintService.printForeign(ticketNumber);
        return reprintForeignPage.data("state", state);
    }
}