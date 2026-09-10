package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.ticket.TicketParkingService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

/**
 * JAX-RS resource driving parked tickets: parking the current cart, listing
 * the parked tickets of this register and resuming one.
 */
@Path("/")
public class ParkedTicketResource {

    /**
     * Rows per page. The list area is 460 px tall and a row is 72 px with its
     * gap, so six rows and the pager fill it exactly — the register pages its
     * lists, it never scrolls them.
     */
    private static final int PAGE_SIZE = 6;

    @Inject @Location("parked") Template parked;
    @Inject TicketParkingService ticketParkingService;
    @Inject
    PosState state;

    /**
     * Parks the current cart and returns to the home page; on refusal the
     * error is shown on the ticket area.
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/parked/park")
    public Response parkCurrent() {
        String error = ticketParkingService.parkCurrent();
        if (error != null) {
            state.ticket.setError(error);
        }
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Shows one page of the parked tickets of this register.
     *
     * @param page the zero-based page to show; null or out of range is clamped
     *             to the nearest existing page
     * @return the parked-tickets page
     */
    @GET
    @Path("/parked")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance parkedPage(@QueryParam("page") Integer page) {
        List<?> all = ticketParkingService.listParked();
        int pageCount = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = page == null ? 0 : page;
        if (current < 0) {
            current = 0;
        }
        if (current > pageCount - 1) {
            current = pageCount - 1;
        }
        int from = current * PAGE_SIZE;
        int to = Math.min(all.size(), from + PAGE_SIZE);
        return parked
                .data("state", state)
                .data("tickets", all.subList(from, to))
                .data("page", current + 1)
                .data("pageCount", pageCount)
                .data("hasPrev", current > 0)
                .data("hasNext", current < pageCount - 1)
                .data("prevPage", current - 1)
                .data("nextPage", current + 1);
    }

    /**
     * Resumes a parked ticket and returns to the home page; on refusal the
     * error is shown on the ticket area.
     *
     * @param id the database id of the parked ticket
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/parked/resume/{id}")
    public Response resume(@PathParam("id") Long id) {
        String error = ticketParkingService.resume(id);
        if (error != null) {
            state.ticket.setError(error);
        }
        return Response.seeOther(URI.create("/")).build();
    }
}
