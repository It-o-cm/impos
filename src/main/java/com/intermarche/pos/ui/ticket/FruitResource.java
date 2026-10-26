package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * JAX-RS resource of the FRUITS &amp; LÉGUMES weighing screen. Display
 * only: the tap on a fruit goes through the ticket actions, this resource
 * just shows the grid.
 */
@Path("/")
@DrawerMustBeClosed
public class FruitResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FruitResource.class);

    @Inject Template fruits;
    @Inject FruitService fruitService;

    @Inject
    PosState state;

    /** Serves the administered tare containers to the weighing grid (LC-14-01-04). */
    @Inject
    com.intermarche.pos.ui.balance.TareService tareService;

    /**
     * Shows the weighing grid of active PLU products, with the administered
     * tare containers (LC-14-01-04) the cashier can arm before a weighing.
     *
     * @return the fruits page
     */
    @GET
    @Path("/fruits")
    public TemplateInstance fruitsPage() {
        LOGGER.info("Entering method fruitsPage");
        List<Product> products = fruitService.getPluProducts();
        LOGGER.info("Exiting method fruitsPage");
        return fruits.data("state", state).data("products", products)
                .data("tareContainers", tareService.containers())
                .data("tareImages", tareService.containerImages());
    }
}