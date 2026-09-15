package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
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
import org.jboss.logging.Logger;

/**
 * JAX-RS resource driving the product search screen: full-text search over
 * the active catalog and one-tap addition of a result to the ticket.
 */
@Path("/")
public class ProductSearchResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ProductSearchResource.class);

    /** Maximum number of results the search brings back. */
    private static final int MAX_RESULTS = 24;

    /**
     * Hits per screen.
     *
     * <p>EIGHT, in four columns of two rows: what the results area holds between the
     * title bar and the two keyboards WITHOUT a scrollbar. This register paginates,
     * it does not scroll — dragging a list with a finger while the other hand holds
     * an article is not a gesture a till can ask for, and nothing else here does it.
     */
    private static final int PAGE_SIZE = 8;

    @Inject @Location("search") Template search;
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
     * @param page the page of results asked for, null for the first
     * @return the search page
     */
    @GET
    @Path("/search")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance searchPage(@QueryParam("q") String query,
                                       @QueryParam("page") Integer page) {
        LOGGER.info("Entering method searchPage with query: " + query + ", page: " + page);
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
                // The EAN is guaranteed non-empty by the filter above (a
                // PLU-only product never reaches here — its home is the
                // weighing grid, not the search), so no fallback is
                // reachable: the guarded version could never take its
                // false arm.
                String code = product.ean;
                hits.add(new SearchHit(product.name.toUpperCase(), code, priceFormatted));
            }
        }
        // A page outside the results is brought back inside rather than answered with
        // an error: a stale link is not an incident.
        int pageCount = Math.max(1, (hits.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = page == null ? 1 : Math.min(Math.max(page, 1), pageCount);
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, hits.size());
        LOGGER.info("Exiting method searchPage");
        return search
                .data("state", state)
                .data("q", q)
                .data("hits", hits)
                .data("pageHits", hits.subList(from, to))
                .data("page", current)
                .data("pageCount", pageCount)
                .data("hasPrev", current > 1)
                .data("hasNext", current < pageCount)
                .data("prevPage", current - 1)
                .data("nextPage", current + 1)
                // The pager's link is built HERE, encoded once: a query carrying a
                // space or an accent must not produce a link the browser mangles.
                .data("pageUrl", "/search?q="
                        + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
                        + "&page=");
    }

    /**
     * Hands a code the operator TYPED to the scan chain ({@code LC-06-01-01}).
     *
     * <p>The search above only ever offers CATALOG articles, which is the right
     * answer for a product and the wrong one for everything else: a counter-ticket
     * reference, a voucher, a loyalty card are all codes that exist and that the
     * catalog has never heard of, so they can never appear in a result list. When a
     * scanner cannot read a damaged barcode, the operator reads it out — and what
     * they type must reach exactly what the scanner would have reached.
     *
     * <p>So this route does NOT look anything up: it walks the code through
     * {@code processScan}, the same chain, in the same order, under the same guards.
     * Typing is scanning.
     *
     * @param code the code typed by the operator
     * @return a redirect to the home page
     */
    @POST
    @Path("/action/search/scan")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response scanTypedCode(@FormParam("q") String code) {
        LOGGER.info("Entering method scanTypedCode with code: " + code);
        String typed = code == null ? "" : code.trim();
        if (!typed.isEmpty()) {
            ticketService.processScan(typed);
        }
        LOGGER.info("Exiting method scanTypedCode");
        return Response.seeOther(URI.create("/")).build();
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
        LOGGER.info("Entering method addFromSearch with ean: " + ean);
        ticketService.addItemByEan(state, ean, BigDecimal.ONE);
        LOGGER.info("Exiting method addFromSearch");
        return Response.seeOther(URI.create("/")).build();
    }
}
