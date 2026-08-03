package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * JAX-RS resource driving the product search screen: full-text search over
 * the active catalog and one-tap addition of a result to the ticket.
 */
@Path("/")
public class ProductSearchResource {

    /** Maximum number of results shown on the search page. */
    private static final int MAX_RESULTS = 24;

    @Inject @Location("search") Template search;
    @Inject Template lock;
    @Inject TicketService ticketService;
    @Inject PosState state;

    /**
     * A search result displayed on the page.
     */
    public static class SearchHit {
        /** The product display label. */
        public String label;
        /**
         * The scannable code used for the addition: the EAN when the product
         * has one, its PLU otherwise — a tap routes it through
         * {@code processScan}, so a PLU-only product (weighed catalog) is
         * addable from the search exactly like a typed PLU.
         */
        public String code;
        /** The current price formatted for display, or a dash when absent. */
        public String priceFormatted;

        /**
         * Creates a search result.
         *
         * @param label the product display label
         * @param code the scannable code (EAN, or PLU when EAN-less)
         * @param priceFormatted the formatted current price
         */
        public SearchHit(String label, String code, String priceFormatted) {
            this.label = label;
            this.code = code;
            this.priceFormatted = priceFormatted;
        }
    }

    /**
     * Shows the search page, with the results of the typed query when one is
     * present (2 characters minimum): name fragment, EAN prefix or exact
     * article code (PLU).
     *
     * @param query the typed query, or null
     * @return the search page, or the lock page when no operator is logged in
     */
    @GET
    @Path("/search")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance searchPage(@QueryParam("q") String query) {
        if (state.isLocked()) return lock.data("state", state).data("error", null);
        String q = (query != null) ? query.trim() : "";
        List<SearchHit> hits = new ArrayList<>();
        if (q.length() >= 2) {
            List<Product> found = Product
                    .<Product>find("active = true and forbiddenToSale = false"
                                    + " and (lower(name) like ?1 or ean like ?2 or plu = ?3) order by name",
                            "%" + q.toLowerCase() + "%", q + "%", q)
                    .page(0, MAX_RESULTS)
                    .list();
            for (Product product : found) {
                if (product.ean == null || product.ean.isEmpty()) continue;
                Price price = Price.findCurrentPrice(product.id);
                String priceFormatted = (price != null)
                        ? String.format("%.2f €", price.priceIncludingTax.setScale(2, RoundingMode.HALF_UP)).replace(".", ",")
                        : "—";
                String code = (product.ean != null && !product.ean.isEmpty()) ? product.ean : product.plu;
                hits.add(new SearchHit(product.name.toUpperCase(), code, priceFormatted));
            }
        }
        return search
                .data("state", state)
                .data("q", q)
                .data("hits", hits);
    }

    /**
     * Adds a search result to the ticket by its EAN and returns to the home
     * page.
     *
     * @param ean the EAN of the chosen product
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/search/add/{ean}")
    public Response addFromSearch(@PathParam("ean") String ean) {
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        ticketService.addItemByEan(state, ean, BigDecimal.ONE);
        return Response.seeOther(URI.create("/")).build();
    }
}
