package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PosHardwareResource.class);

    @Inject
    TicketService ticketService;

    /** Simulator decisions land on the virtual terminal implementation. */
    @Inject
    com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient virtualTerminalClient;

    @Inject
    PosState state;

    /** EXP-67: the Resto Flash confirmation a token read on the payment screen goes to. */
    @Inject
    com.intermarche.pos.ui.payment.PaymentService paymentService;

    /**
     * Handles a scanned code pushed by the scanner (or the simulator), or a
     * code the cashier KEYED on the sale screen's pad, which the pad marks
     * with the {@code X-Entry: KEYED} header (BO-06-04-05): both walk the same
     * chain, and the keyed one rings its articles as keyed.
     *
     * @param code the scanned code
     * @param entry the entry marker, {@code KEYED} for a typed code, else null
     * @return 200, or 400 on an empty code
     */
    @POST
    @Path("/api/pos/scan")
    @Consumes("text/plain")
    public Response handleScan(String code, @jakarta.ws.rs.HeaderParam("X-Entry") String entry) {
        LOGGER.info("Entering method handleScan with code: " + code);
        if (code == null || code.isEmpty()) {
            LOGGER.info("Exiting method handleScan");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        // LC-13-02-05/-07: diagnostic scans cannot register sale articles.
        if (state.maintenanceScreenOpen) {
            state.maintenanceScanCode = code;
            state.touch();
            return Response.ok().build();
        }
        if (state.scannerDisabled) {
            return Response.noContent().build();
        }
        // EXP-67: the Resto Flash zone of the payment screen is open — what the
        // scanner reads is the customer's token, and it is the Resto Flash
        // confirmation that answers it, not the sale's chain ("CODE INCONNU").
        if (state.payment.restoFlashScanArmed && state.payment.paymentInProgress) {
            state.payment.restoFlashScanArmed = false;
            paymentService.processRestoFlash(state,
                    com.intermarche.pos.ui.scanner.Symbology.read(code).payload().trim(), java.math.BigDecimal.ZERO);
            state.touch();
            LOGGER.info("Exiting method handleScan");
            return Response.ok().build();
        }
        if ("KEYED".equalsIgnoreCase(entry)) {
            ticketService.processKeyed(code);
        } else {
            ticketService.processScan(code);
        }
        LOGGER.info("Exiting method handleScan");
        return Response.ok().build();
    }

    /**
     * Handles a Dallas key (iButton) touched to the register's reader
     * (BO-01-02-07): while the register is locked, the key is deposited for
     * the lock page, which signs its holder in without a PIN; at any other
     * moment it is ignored.
     *
     * @param key the key identifier the reader delivered
     * @return 200 when deposited, 204 when ignored, 400 when it is not a key
     */
    @POST
    @Path("/api/pos/dallas")
    @Consumes("text/plain")
    public Response handleDallasKey(String key) {
        LOGGER.info("Entering method handleDallasKey");
        String normalized = com.intermarche.pos.domain.people.Employee.normalizeDallasKey(key);
        if (!com.intermarche.pos.domain.people.Employee.isDallasKey(normalized)) {
            LOGGER.info("Exiting method handleDallasKey");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        // BO-10-02-31: while the supervisor panel is open, the key answers IT —
        // the more immediate gesture, exactly as a badge read over the
        // cashier's shoulder endorses before it unlocks anything.
        if (state.endorsement.active) {
            state.endorsement.setPresentedDallasKey(normalized);
            state.touch();
            LOGGER.info("Exiting method handleDallasKey");
            return Response.ok().build();
        }
        if (!state.auth.isLocked) {
            LOGGER.info("Exiting method handleDallasKey");
            return Response.noContent().build();
        }
        state.auth.setScannedDallasKey(normalized);
        state.touch();
        LOGGER.info("Exiting method handleDallasKey");
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
     * pending card payment. The body, when present, is the receipt the
     * terminal returns with its decision (LC-08-05-07).
     *
     * @param receipt the terminal receipt, customer then merchant copy after
     *        {@code --/--}; empty for none
     * @return 200, or 409 when no request is pending
     */
    @POST
    @Path("/api/hardware/tpe/accept")
    public Response tpeAccept(String receipt) {
        LOGGER.info("Entering method tpeAccept");
        if (!virtualTerminalClient.accept(receipt)) {
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
