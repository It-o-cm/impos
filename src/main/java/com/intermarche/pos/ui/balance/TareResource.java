package com.intermarche.pos.ui.balance;

import com.intermarche.pos.ui.PosState;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * JAX-RS resource of the container-tare gestures on the weighing screen
 * (LC-14-01-04/05). The cashier taps a container (or scans its barcode) to arm
 * a PUNCTUAL software tare that overrides the article and default tare for the
 * next weighing only; the register then sends that tare to the scale.
 * <p>
 * Both gestures redirect back to the FRUITS &amp; LÉGUMES grid (PRG pattern), where
 * the armed container is shown and the next PLU tap weighs with it. Clearing
 * the tare (an empty selection) disarms it.
 */
@Path("/tare")
public class TareResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TareResource.class);

    @Inject
    PosState state;

    @Inject
    TareService tareService;

    /**
     * Arms the punctual container tare of the given administered code
     * (LC-14-01-04) and returns to the weighing grid.
     *
     * @param code the administered container code the cashier tapped
     * @return a 303 redirect to the fruits screen
     */
    @GET
    @Path("/select/{code}")
    public Response selectContainer(@PathParam("code") String code) {
        LOGGER.info("Entering method selectContainer with code: " + code);
        tareService.selectContainer(state, code);
        LOGGER.info("Exiting method selectContainer");
        return redirectFruits();
    }

    /**
     * Arms the punctual container tare from a scanned container barcode
     * (LC-14-01-05) and returns to the weighing grid.
     *
     * @param barcode the scanned container barcode
     * @return a 303 redirect to the fruits screen
     */
    @GET
    @Path("/scan/{barcode}")
    public Response scanContainer(@PathParam("barcode") String barcode) {
        LOGGER.info("Entering method scanContainer with barcode: " + barcode);
        tareService.scanContainer(state, barcode);
        LOGGER.info("Exiting method scanContainer");
        return redirectFruits();
    }

    /**
     * Disarms any punctual container tare and returns to the weighing grid.
     *
     * @return a 303 redirect to the fruits screen
     */
    @GET
    @Path("/clear")
    public Response clearContainer() {
        LOGGER.info("Entering method clearContainer");
        state.tare.clear();
        LOGGER.info("Exiting method clearContainer");
        return redirectFruits();
    }

    /**
     * Builds the 303 redirect to the weighing grid (PRG pattern).
     *
     * @return a 303 See Other response targeting "/fruits"
     */
    private Response redirectFruits() {
        return Response.seeOther(URI.create("/fruits")).build();
    }
}
