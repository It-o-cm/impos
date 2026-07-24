package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.TicketParkingService;
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
 * JAX-RS resource driving parked tickets: parking the current cart, listing
 * the parked tickets of this register and resuming one.
 */
@Path("/")
public class ParkedTicketResource {

    @Inject @Location("parked") Template parked;
    @Inject Template lock;
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
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        String error = ticketParkingService.parkCurrent();
        if (error != null) {
            state.ticket.setError(error);
        }
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Shows the parked tickets of this register.
     *
     * @return the parked-tickets page, or the lock page when no operator is
     *         logged in
     */
    @GET
    @Path("/parked")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance parkedPage() {
        if (state.isLocked()) return lock.data("state", state).data("error", null);
        return parked
                .data("state", state)
                .data("tickets", ticketParkingService.listParked());
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
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        String error = ticketParkingService.resume(id);
        if (error != null) {
            state.ticket.setError(error);
        }
        return Response.seeOther(URI.create("/")).build();
    }
}
