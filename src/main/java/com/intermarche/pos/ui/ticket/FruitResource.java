package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.util.List;

@Path("/")
@DrawerMustBeClosed
public class FruitResource {

    @Inject Template fruits;
    @Inject Template lock;
    @Inject FruitService fruitService;

    @Inject
    PosState state;

    @GET
    @Path("/fruits")
    public TemplateInstance fruitsPage() {
        if (state.isLocked()) return lock.data("state", state);
        List<Product> products = fruitService.getPluProducts();
        return fruits.data("state", state).data("products", products);
    }
}