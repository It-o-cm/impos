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
}
