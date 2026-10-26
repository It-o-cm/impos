package com.intermarche.pos.ui.hardware;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * Shows, and pushes on demand, the driver configuration this register hands
 * to its hardware module (BO-03-09-11 to -29): what a technician reads to
 * check that what the back office posed has arrived.
 */
@Path("/api/pos/hardware-configuration")
public class HardwareConfigurationResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HardwareConfigurationResource.class);

    /** The configuration gatherer. */
    @Inject
    HardwareConfigurationService hardwareConfigurationService;

    /**
     * Returns the configuration block as the module receives it.
     *
     * @return one {@code key=value} per line
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String configuration() {
        LOGGER.info("Entering method configuration");
        LOGGER.info("Exiting method configuration");
        return hardwareConfigurationService.text();
    }

    /**
     * Pushes the configuration to the module now.
     *
     * @return PUSHED when a new block was acknowledged, UNCHANGED otherwise
     */
    @POST
    @Path("/push")
    @Produces(MediaType.TEXT_PLAIN)
    public String push() {
        LOGGER.info("Entering method push");
        LOGGER.info("Exiting method push");
        return hardwareConfigurationService.push() ? "PUSHED" : "UNCHANGED";
    }
}
