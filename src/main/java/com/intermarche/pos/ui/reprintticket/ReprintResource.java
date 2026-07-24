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
    public TemplateInstance showReprintPage() {
        if (state.isLocked()) return lock.data("state", state);
        reprintService.loadHistory();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/prev")
    public TemplateInstance reprintPrevPage() {
        if (state.isLocked()) return lock.data("state", state);
        if (state.reprint.isHasListPrev()) state.reprint.listPage--;
        state.touch();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/next")
    public TemplateInstance reprintNextPage() {
        if (state.isLocked()) return lock.data("state", state);
        if (state.reprint.isHasListNext()) state.reprint.listPage++;
        state.touch();
        return reprintTicketPage.data("state", state);
    }

    @GET
    @Path("/view/{id}")
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
    @Path("/print/{id}")
    public Response doReprint(@PathParam("id") Long id) {
        reprintService.print(id);
        return Response.seeOther(URI.create("/reprint/view/" + id)).build();
    }
}