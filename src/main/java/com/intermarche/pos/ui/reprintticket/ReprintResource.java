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

    @Inject Template lock;
    @Inject
    PosState state;
    @Inject ReprintService reprintService;

    @Inject @Location("reprint-ticket") Template reprintTicketPage;
    @Inject @Location("reprint-ticket-detail") Template reprintDetailPage;

    @GET
    @Produces(MediaType.TEXT_HTML)
    /**
     * Shows the reprint screen with a fresh closed-ticket history.
     *
     * @return the reprint page, or the lock page when locked
     */
    public TemplateInstance showReprintPage() {
        if (state.isLocked()) return lock.data("state", state);
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
        if (state.isLocked()) return lock.data("state", state);
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
        if (state.isLocked()) return lock.data("state", state);
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
        if (state.isLocked()) return lock.data("state", state);
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
        if (state.isLocked()) return lock.data("state", state);
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
        if (state.isLocked()) return lock.data("state", state);
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
}