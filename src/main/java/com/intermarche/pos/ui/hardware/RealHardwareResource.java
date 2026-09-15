package com.intermarche.pos.ui.hardware;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * The REAL hardware boundary of a production till — the future home of the
 * physical device drivers, serving the exact {@link HardwareClient} contract
 * on {@code /api/hardware/**}.
 * <p>
 * Build-profile mirror of the simulator: {@code MockHardwareResource} exists
 * in dev and test only, THIS class exists in prod only — the two never share
 * a classpath, so the identical {@code @Path} never conflicts. A production
 * jar therefore answers its own hardware loopback (the {@code hardware-api}
 * client's prod default points at the register itself), the unlock gate's
 * probes pass, and the register opens.
 * <p>
 * EVERY ENDPOINT IS A STUB ANSWERING OK FOR NOW. Each one names the physical
 * device of the target till it will drive; the drivers get coded here (or in
 * classes of this package this resource delegates to), and nothing else in
 * the register has to change — the boundary is already HTTP.
 */
@Path("/api/hardware")
@ApplicationScoped
@IfBuildProfile("prod")
public class RealHardwareResource {

    /** The class logger — each stubbed gesture leaves a debug trace. */
    private static final Logger LOGGER = Logger.getLogger(RealHardwareResource.class);

    /**
     * Reads the current weight from the scale.
     * <p>
     * STUB: no scale is attached to the target till (none detected on USB);
     * answers 0,000 — the register's convention for "no weight on the
     * plate", which keeps the weighing screen honest (nothing weighed).
     *
     * @return the weight in kilograms, French decimal
     */
    @GET
    @Path("/weight")
    @Produces(MediaType.TEXT_PLAIN)
    public String getWeight() {
        LOGGER.info("Entering method getWeight");
        LOGGER.info("Exiting method getWeight");
        return "0,000";
    }

    /**
     * Shows a text on the customer line display.
     * <p>
     * STUB for the Diebold Nixdorf BA9x USB display: accepts and drops the
     * text. The real driver writes it to the two-line VFD.
     *
     * @param text the text to display
     */
    @POST
    @Path("/display")
    @Consumes(MediaType.TEXT_PLAIN)
    public void setDisplay(String text) {
        LOGGER.info("Entering method setDisplay with text: " + text);
        LOGGER.debugf("Afficheur (stub): %s", text);
        LOGGER.info("Exiting method setDisplay");
    }

    /**
     * Fires the drawer-opening pulse.
     * <p>
     * STUB for the cash drawer wired to the Epson UB-U05 printer port: the
     * real driver sends the ESC/POS kick pulse through the printer.
     */
    @POST
    @Path("/drawer/open")
    public void openDrawer() {
        LOGGER.info("Entering method openDrawer");
        LOGGER.debug("Ouverture tiroir (stub)");
        LOGGER.info("Exiting method openDrawer");
    }

    /**
     * Reads the physical drawer state.
     * <p>
     * STUB: answers CLOSED — the state that never traps the register behind
     * the drawer guard. The real driver reads the drawer sensor through the
     * printer status.
     *
     * @return "CLOSED", always, for now
     */
    @GET
    @Path("/drawer/status")
    @Produces(MediaType.TEXT_PLAIN)
    public String getDrawerStatus() {
        LOGGER.info("Entering method getDrawerStatus");
        LOGGER.info("Exiting method getDrawerStatus");
        return "CLOSED";
    }

    /**
     * Sends a formatted receipt to the printer.
     * <p>
     * STUB for the Epson UB-U05 ticket printer: accepts and drops the
     * 42-column text. The real driver renders it in ESC/POS.
     *
     * @param content the 42-column formatted text
     */
    @POST
    @Path("/printer/print")
    @Consumes(MediaType.TEXT_PLAIN)
    public void printTicket(String content) {
        LOGGER.info("Entering method printTicket with content: " + content);
        LOGGER.debugf("Impression (stub): %d caractere(s)", content == null ? 0 : content.length());
        LOGGER.info("Exiting method printTicket");
    }

    /**
     * Cuts the printer paper.
     * <p>
     * STUB: the real driver sends the ESC/POS cut command to the UB-U05.
     */
    @POST
    @Path("/printer/cut")
    public void cutPaper() {
        LOGGER.info("Entering method cutPaper");
        LOGGER.debug("Coupe papier (stub)");
        LOGGER.info("Exiting method cutPaper");
    }
}
