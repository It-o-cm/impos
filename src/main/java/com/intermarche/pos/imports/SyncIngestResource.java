package com.intermarche.pos.imports;

import com.intermarche.pos.service.sync.SyncIngestService;
import com.intermarche.pos.service.sync.SyncPayloads;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.HeaderParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Store-node ingestion endpoints of the register synchronization (phase 5).
 * <p>
 * Active only when this node runs the {@code store} role ({@code pos.role});
 * a register node answers 403 so a misconfigured push cannot pollute a
 * register database. Resolution failures (missing referenced entity) answer
 * 409 so the register keeps the item in its outbox and retries.
 * <p>
 * The 409 contract is what makes the register-side drain order safe end to
 * end: sessions arrive before the tickets that reference them, tickets
 * before the refunds that cite their lines (by lineUid), events last — and
 * any out-of-order arrival (first push of a fresh register, store-node
 * downtime window) degrades into a retry instead of a data hole.
 */
@Path("/api/sync")
public class SyncIngestResource {

    private static final Logger LOG = Logger.getLogger(SyncIngestResource.class);

    /** The role of this node: "register" (default) or "store". */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared ingestion token; absent = no authentication required. */
    @ConfigProperty(name = "pos.sync.token")
    java.util.Optional<String> token;

    @Inject
    SyncIngestService syncIngestService;

    /**
     * Ingests a pushed cash session.
     *
     * @param dto the session payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestSession(@HeaderParam("X-Sync-Token") String presentedToken,
                                  SyncPayloads.SessionDto dto) {
        return handle(presentedToken, () -> syncIngestService.ingestSession(dto));
    }

    /**
     * Ingests a pushed ticket.
     *
     * @param dto the ticket payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/ticket")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestTicket(@HeaderParam("X-Sync-Token") String presentedToken,
                                 SyncPayloads.TicketDto dto) {
        return handle(presentedToken, () -> syncIngestService.ingestTicket(dto));
    }

    /**
     * Ingests a pushed refund.
     *
     * @param dto the refund payload
     * @return 200 on upsert, 403 off-role, 409 on a retryable resolution failure
     */
    @POST
    @Path("/refund")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestRefund(@HeaderParam("X-Sync-Token") String presentedToken,
                                 SyncPayloads.RefundDto dto) {
        return handle(presentedToken, () -> syncIngestService.ingestRefund(dto));
    }

    /**
     * Ingests a pushed technical journal event.
     *
     * @param presentedToken the shared token presented by the register
     * @param dto the event payload
     * @return 200 on upsert, 401 on a bad token, 403 off-role, 409 retryable
     */
    @POST
    @Path("/event")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response ingestEvent(@HeaderParam("X-Sync-Token") String presentedToken,
                                SyncPayloads.EventDto dto) {
        return handle(presentedToken, () -> syncIngestService.ingestEvent(dto));
    }

    /**
     * Runs an ingestion under the role gate, the shared-token gate and the
     * retryable-failure contract.
     *
     * @param presentedToken the token presented by the register, or null
     * @param ingestion the ingestion to run
     * @return the HTTP response
     */
    private Response handle(String presentedToken, Runnable ingestion) {
        if (!"store".equalsIgnoreCase(role)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("Ce nœud n'a pas le rôle store").build();
        }
        String expectedToken = token.orElse("");
        if (!expectedToken.isBlank() && !expectedToken.equals(presentedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Jeton de synchronisation invalide").build();
        }
        try {
            ingestion.run();
            return Response.ok("OK").build();
        } catch (IllegalStateException e) {
            // Missing referenced entity: the register retries later
            return Response.status(Response.Status.CONFLICT).entity(e.getMessage()).build();
        } catch (Exception e) {
            LOG.errorf("Ingestion en erreur: %s", e.getMessage());
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
