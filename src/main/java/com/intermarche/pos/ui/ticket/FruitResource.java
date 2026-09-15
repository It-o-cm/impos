package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.util.List;
import org.jboss.logging.Logger;

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

    /**
     * Shows the weighing grid of active PLU products.
     *
     * @return the fruits page
     */
    @GET
    @Path("/fruits")
    public TemplateInstance fruitsPage() {
        LOGGER.info("Entering method fruitsPage");
        List<Product> products = fruitService.getPluProducts();
        LOGGER.info("Exiting method fruitsPage");
        return fruits.data("state", state).data("products", products);
    }
}