package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.util.List;

/**
 * JAX-RS resource of the FRUITS &amp; LÉGUMES weighing screen. Display
 * only: the tap on a fruit goes through the ticket actions, this resource
 * just shows the grid.
 */
@Path("/")
@DrawerMustBeClosed
public class FruitResource {

    @Inject Template fruits;
    @Inject Template lock;
    @Inject FruitService fruitService;

    @Inject
    PosState state;

    /**
     * Shows the weighing grid of active PLU products.
     *
     * @return the fruits page, or the lock page when locked
     */
    @GET
    @Path("/fruits")
    public TemplateInstance fruitsPage() {
        if (state.isLocked()) return lock.data("state", state);
        List<Product> products = fruitService.getPluProducts();
        return fruits.data("state", state).data("products", products);
    }
}