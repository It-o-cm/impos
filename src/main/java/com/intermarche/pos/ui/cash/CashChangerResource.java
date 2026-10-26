package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.CashChangerService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * The CASH LEVEL screen of the cash changer (BO-11-03-03): what the machine
 * holds, denomination by denomination, and what it flags out of its
 * thresholds — the "information niveau d'espèces" the cashier and the
 * supervisor read before a loan or a withdrawal.
 */
@Path("/")
public class CashChangerResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashChangerResource.class);

    /** The cash level screen. */
    @Inject
    @Location("cash-changer")
    Template cashChanger;

    /** The machine. */
    @Inject
    CashChangerService cashChangerService;

    /** The operator. */
    @Inject
    PosState state;

    /**
     * Shows the cash level of the machine.
     *
     * @return the cash level page
     */
    @GET
    @Path("/cash-changer")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance page() {
        LOGGER.info("Entering method page");
        LOGGER.info("Exiting method page");
        return cashChanger.data("state", state)
                .data("inventory", cashChangerService.inventory())
                .data("alert", cashChangerService.levelAlert())
                .data("viaMachine", cashChangerService.movementsViaMachine());
    }
}
