package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentService;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Inbound endpoints of the simulated hardware: scanner, scale and, since
 * phase 6, the virtual payment terminal — the simulator polls the pending
 * card request and answers with an accept or refuse decision.
 * <p>
 * This is the INBOUND HALF of the peripheral bus, the mirror of
 * {@code HardwareClient}: the client drives devices (register → hardware),
 * this resource receives device events (hardware → register). The two
 * together are the whole hardware story, and both speak plain HTTP for the
 * same reason — simulator and real bridge are interchangeable behind the
 * contract. Security posture, explicit: no authentication here (LAN till
 * assumption, same posture as the dashboard) — any LAN client can inject a
 * scan or a TPE decision; fronting these endpoints with a filter is the
 * first step if a register ever leaves its closed network.
 */
@Path("/")
public class PosHardwareResource {

    @Inject
    TicketService ticketService;

    @Inject
    PaymentService paymentService;

    @Inject
    PosState state;

    /**
     * Handles a scanned code pushed by the scanner (or the simulator).
     *
     * @param code the scanned code
     * @return 200, or 400 on an empty code
     */
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

    /**
     * Handles a weight pushed by the scale (or the simulator).
     *
     * @param weightStr the weight in kilograms
     * @return 200, or 400 on an empty weight
     */
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

    /**
     * Returns the state of the virtual payment terminal, polled by the
     * simulator: whether a card request is pending and its amount.
     *
     * @return a JSON map with the pending flag and the formatted amount
     */
    @GET
    @Path("/api/hardware/tpe")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> tpeStatus() {
        Map<String, Object> result = new HashMap<>();
        boolean pending = state.payment.pendingCardAmount != null;
        result.put("pending", pending);
        result.put("amount", pending
                ? state.payment.pendingCardAmount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        return result;
    }

    /**
     * Applies the terminal's accept decision: the pending card payment is
     * registered.
     *
     * @return 200, or 409 when no request is pending
     */
    @POST
    @Path("/api/hardware/tpe/accept")
    public Response tpeAccept() {
        if (state.payment.pendingCardAmount == null) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        paymentService.confirmPendingCard(state);
        return Response.ok().build();
    }

    /**
     * Applies the terminal's refuse decision: the pending card payment is
     * dropped and the cashier is told.
     *
     * @return 200, or 409 when no request is pending
     */
    @POST
    @Path("/api/hardware/tpe/refuse")
    public Response tpeRefuse() {
        if (state.payment.pendingCardAmount == null) {
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        paymentService.refusePendingCard(state);
        return Response.ok().build();
    }
}
