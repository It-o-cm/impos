package com.intermarche.pos.ui.hardware;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST client toward the register's peripherals: scale,
 * customer line display, cash drawer and receipt printer.
 * <p>
 * THE HARDWARE BOUNDARY IS HTTP: this interface is the whole driver
 * contract, and the {@code hardware-api} config key decides who implements
 * it — in development the simulator's endpoints, on a real till a hardware
 * bridge exposing the same paths in front of the physical devices. Swapping
 * simulator for real peripherals is a base-URL change, no code.
 * Plain-text payloads on purpose: the contract stays curl-able and trivial
 * to reimplement.
 */
@Path("/api/hardware")
@RegisterRestClient(configKey = "hardware-api")
public interface HardwareClient {

    /**
     * Reads the current weight from the scale.
     *
     * @return the weight in kilograms, French or dot decimal
     */
    @GET
    @Path("/weight")
    @Produces(MediaType.TEXT_PLAIN)
    String getWeight();

    /**
     * Shows a text on the customer line display.
     *
     * @param text the text to display
     */
    @POST
    @Path("/display")
    @Consumes(MediaType.TEXT_PLAIN)
    void setDisplay(String text);

    /**
     * Fires the drawer-opening pulse.
     */
    @POST
    @Path("/drawer/open")
    void openDrawer();

    /**
     * Reads the physical drawer state.
     *
     * @return "OPEN" when the drawer is open, anything else otherwise
     */
    @GET
    @Path("/drawer/status")
    @Produces(MediaType.TEXT_PLAIN)
    String getDrawerStatus();

    /**
     * Sends a formatted receipt to the printer.
     *
     * @param content the 42-column formatted text
     */
    @POST
    @Path("/printer/print")
    @Consumes(MediaType.TEXT_PLAIN)
    void printTicket(String content);

    /**
     * Cuts the printer paper.
     */
    @POST
    @Path("/printer/cut")
    void cutPaper();

    /**
     * Starts a card payment on the terminal.
     * <p>
     * Returns as soon as the payment is under way, NOT when it ends: a card
     * payment waits for the cardholder and can take minutes, which no HTTP
     * request should be held open for. The outcome is read from
     * {@link #getPaymentStatus()}.
     *
     * @param amountCents the amount to debit, in cents
     */
    @POST
    @Path("/payment")
    @Consumes(MediaType.TEXT_PLAIN)
    void startPayment(String amountCents);

    /**
     * Reads where the card payment stands.
     * <p>
     * One {@code key=value} per line: {@code state} (IDLE, RUNNING or DONE),
     * {@code line1} and {@code line2} — what the terminal shows the cardholder
     * right now — then {@code result}, {@code approved}, {@code failure} and
     * {@code answer} once the payment is over.
     *
     * @return the status block
     */
    @GET
    @Path("/payment")
    @Produces(MediaType.TEXT_PLAIN)
    String getPaymentStatus();

    /**
     * Starts a cheque reading.
     * <p>
     * Returns as soon as the reader is waiting for the document, NOT when it has read
     * it: the reading waits for a person to offer a cheque and take it back. The
     * outcome is read from {@link #getChequeStatus()}.
     *
     * @param endorsement what the bridge prints on the cheque while the reader still
     *                    holds it, one line per newline; empty prints nothing
     */
    @POST
    @Path("/cheque")
    @Consumes(MediaType.TEXT_PLAIN)
    void startCheque(String endorsement);

    /**
     * Reads where the cheque reading stands.
     * <p>
     * One {@code key=value} per line: {@code state} (IDLE, RUNNING or DONE),
     * {@code failure}, then the magnetic line and its French zones —
     * {@code raw}, {@code bank}, {@code branch}, {@code account}, {@code serial}.
     *
     * @return the status block
     */
    @GET
    @Path("/cheque")
    @Produces(MediaType.TEXT_PLAIN)
    String getChequeStatus();
}
