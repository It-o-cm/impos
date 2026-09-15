package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PosHardwareResource.class);

    @Inject
    TicketService ticketService;

    /** Simulator decisions land on the virtual terminal implementation. */
    @Inject
    com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient virtualTerminalClient;

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
        LOGGER.info("Entering method handleScan with code: " + code);
        if (code == null || code.isEmpty()) {
            LOGGER.info("Exiting method handleScan");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ticketService.processScan(code);
        LOGGER.info("Exiting method handleScan");
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
        LOGGER.info("Entering method handleWeight with weightStr: " + weightStr);
        if (weightStr == null || weightStr.isEmpty()) {
            LOGGER.info("Exiting method handleWeight");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ticketService.processWeight(weightStr);
        LOGGER.info("Exiting method handleWeight");
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
        LOGGER.info("Entering method tpeStatus");
        Map<String, Object> result = new HashMap<>();
        boolean pending = state.payment.pendingCardAmount != null;
        result.put("pending", pending);
        result.put("amount", pending
                ? state.payment.pendingCardAmount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        LOGGER.info("Exiting method tpeStatus");
        return result;
    }

    /**
     * Applies the simulator's accept decision: the virtual terminal fires
     * the accept leg of the transaction callback, which registers the
     * pending card payment.
     *
     * @return 200, or 409 when no request is pending
     */
    @POST
    @Path("/api/hardware/tpe/accept")
    public Response tpeAccept() {
        LOGGER.info("Entering method tpeAccept");
        if (!virtualTerminalClient.accept()) {
            LOGGER.info("Exiting method tpeAccept");
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        LOGGER.info("Exiting method tpeAccept");
        return Response.ok().build();
    }

    /**
     * Applies the simulator's refuse decision: the virtual terminal fires
     * the refuse leg of the transaction callback, which drops the pending
     * card payment and tells the cashier.
     *
     * @return 200, or 409 when no request is pending
     */
    @POST
    @Path("/api/hardware/tpe/refuse")
    public Response tpeRefuse() {
        LOGGER.info("Entering method tpeRefuse");
        if (!virtualTerminalClient.refuse()) {
            LOGGER.info("Exiting method tpeRefuse");
            return Response.status(Response.Status.CONFLICT).entity("Aucune demande en attente").build();
        }
        LOGGER.info("Exiting method tpeRefuse");
        return Response.ok().build();
    }
}
