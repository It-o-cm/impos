package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.EngineFeedService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Pass-through entry of the engine-specific feeds (OFFERS, STORE_GROUPS,
 * CATEGORY_STORAGES…): files the POS does NOT consume itself but must
 * carry down the chain to the valuation engines — the "sealed parcel" leg
 * of the single-import-line doctrine. The content is stored VERBATIM (a
 * light sanity check only: known code, non-empty body); parsing belongs to
 * imvaluation, which ingests the shared header-named format itself.
 * <p>
 * The shared feeds (PRODUCTS, PRICES, FAMILIES, STORES) do not need this
 * endpoint: their own import resources capture the verbatim file while
 * importing it into the POS referential.
 */
@Path("/feeds/import")
@ApplicationScoped
@RunOnVirtualThread
public class EngineFeedRelayResource {

    /** The feed keeper storing the sealed parcels. */
    @Inject
    EngineFeedService engineFeedService;

    /**
     * Stores the verbatim content of an engine-specific feed.
     *
     * @param code the feed code (case-insensitive, catalog-checked)
     * @param inputStream the raw CSV stream
     * @return 200 with the stored version, 400 on an unknown code or an
     *         empty body
     */
    @POST
    @Path("/{code}")
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response importFeed(@PathParam("code") String code, InputStream inputStream) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (EngineFeedService.catalogEntry(normalized).isEmpty()) {
            String known = EngineFeedService.CATALOG.stream()
                    .map(EngineFeedService.FeedDef::code)
                    .collect(Collectors.joining(", "));
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Unknown feed code '" + normalized
                            + "' - known codes: " + known + "\"}")
                    .build();
        }
        String content;
        try {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Response.serverError().entity("Error reading file: " + e.getMessage()).build();
        }
        if (content.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Empty feed body\"}")
                    .build();
        }
        String version = engineFeedService.store(normalized, content);
        return Response.ok("{\"code\":\"" + normalized + "\", \"version\":\"" + version + "\"}").build();
    }

    /**
     * Returns the engine sync state of every stored feed, in catalog
     * order: the stored version, the version the local engine last
     * acknowledged, whether the two match, and the last delivery error.
     * This is what supervision (and the demo stack) polls to know when the
     * engine is up to date.
     *
     * @return a JSON array of per-feed states
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("ADMIN")
    public Response status() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (EngineFeedService.FeedDef def : EngineFeedService.CATALOG) {
            com.intermarche.pos.domain.EngineFeed feed =
                    com.intermarche.pos.domain.EngineFeed.findByCode(def.code());
            if (feed == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"code\":\"").append(feed.code)
                    .append("\",\"version\":\"").append(feed.version)
                    .append("\",\"appliedVersion\":").append(
                            feed.appliedVersion == null ? "null" : "\"" + feed.appliedVersion + "\"")
                    .append(",\"applied\":").append(feed.version.equals(feed.appliedVersion))
                    .append(",\"lastError\":").append(
                            feed.lastError == null ? "null"
                                    : "\"" + feed.lastError.replace("\\", "\\\\").replace("\"", "'") + "\"")
                    .append('}');
        }
        return Response.ok(sb.append(']').toString()).build();
    }
}
