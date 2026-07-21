package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/")
public class PosHardwareResource {

    @Inject
    TicketService ticketService; // Changement ici

    // --- SCANNER ---
    @POST
    @Path("/api/pos/scan")
    @Consumes("text/plain")
    public Response handleScan(String code) {
        if (code == null || code.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ticketService.processScan(code);
        return Response.ok().build();
    }

    // --- BALANCE ---
    @POST
    @Path("/weight")
    @Consumes("text/plain")
    public Response handleWeight(String weightStr) {
        if (weightStr == null || weightStr.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ticketService.processWeight(weightStr);
        return Response.ok().build();
    }

}