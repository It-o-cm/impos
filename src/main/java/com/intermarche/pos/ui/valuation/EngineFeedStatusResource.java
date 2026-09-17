package com.intermarche.pos.ui.valuation;

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
 * <p>The register no longer IMPORTS anything — the CSV front door left with
 * the store node — but it still DELIVERS the feeds it pulled to the engine
 * attached to it ({@code EngineFeedDeliveryService}), and that delivery is the
 * only thing whose progress cannot be seen from the node. Hence a read-only
 * endpoint here, in the package that already holds the register's whole
 * relationship with its engine.
 *
 * <p>The path is kept verbatim at {@code /feeds/import/status}: it is what
 * supervision and the demo stack poll, and renaming a URL to match a class
 * that moved would break callers for a matter of wording. The POST sibling
 * that used to share this path is gone; nothing else changed for a caller.
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
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (EngineFeedService.FeedState state : engineFeedService.feedStates()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"code\":\"").append(state.code())
                    .append("\",\"version\":\"").append(state.version())
                    .append("\",\"appliedVersion\":").append(quoted(state.appliedVersion()))
                    .append(",\"applied\":").append(state.applied())
                    .append(",\"lastError\":").append(quoted(escape(state.lastError())))
                    .append('}');
        }
        LOGGER.info("Exiting method status");
        return Response.ok(sb.append(']').toString()).build();
    }

    /**
     * Renders a nullable value as a JSON string or the literal {@code null}.
     *
     * @param value the value, possibly null
     * @return the JSON fragment
     */
    private String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /**
     * Neutralizes the characters a raw error message could use to break out of
     * its JSON string.
     *
     * @param value the message, possibly null
     * @return the safe message, or null when the input was null
     */
    private String escape(String value) {
        return value == null ? null : value.replace("\\", "\\\\").replace("\"", "'");
    }
}
