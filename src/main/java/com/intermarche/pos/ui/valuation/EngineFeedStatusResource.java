package com.intermarche.pos.ui.valuation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.pos.service.sync.EngineFeedService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Reports how far the LOCAL valuation engine is behind the feeds this
 * register holds: per feed, the stored version, the version the engine last
 * acknowledged, whether the two match, and the last delivery error.
 *
 * <p>The register DELIVERS the feeds it holds to the engine attached to it
 * ({@code EngineFeedDeliveryService}), and that delivery is the only thing
 * whose progress cannot be seen from the node. Hence a read-only endpoint
 * here, in the package that already holds the register's whole relationship
 * with its engine.
 *
 * <p>The path is {@code /feeds/import/status}, what supervision and the demo
 * stack poll. Its POST sibling, {@code /feeds/import/{code\}}, is served by
 * {@link com.intermarche.pos.imports.FeedImportResource}: it left with the
 * store node and came back for the till that runs WITHOUT one. Two classes on
 * one root path, one reading and one writing — the literal {@code /status}
 * wins over the template, and the methods differ anyway.
 *
 * <p>The answer is built by the JSON library, never by concatenation: a
 * delivery error quotes the engine's message verbatim, and a quote, a
 * backslash or a line break in it must not break the document.
 */
@Path("/feeds/import")
public class EngineFeedStatusResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EngineFeedStatusResource.class);

    /** The feed store, which owns the catalog order and the state projection. */
    @Inject EngineFeedService engineFeedService;

    /**
     * Returns the engine sync state of every stored feed, in catalog order.
     *
     * @return a JSON array of per-feed states
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        LOGGER.info("Entering method status");
        ArrayNode feeds = JsonNodeFactory.instance.arrayNode();
        for (EngineFeedService.FeedState state : engineFeedService.feedStates()) {
            feeds.addObject()
                    .put("code", state.code())
                    .put("version", state.version())
                    .put("appliedVersion", state.appliedVersion())
                    .put("applied", state.applied())
                    .put("lastError", state.lastError());
        }
        LOGGER.info("Exiting method status");
        return Response.ok(feeds.toString()).build();
    }
}
