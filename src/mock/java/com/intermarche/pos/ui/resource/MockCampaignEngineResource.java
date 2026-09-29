package com.intermarche.pos.ui.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Embedded CONSUMPTION-INGEST SIMULATOR for the autonomous e2e/demo runs: the
 * consumption sibling of {@link MockValuationResource}. It answers the one call
 * the STAYING ticket&rarr;consumption delivery makes to the engine so the register
 * under test never depends on an externally running imvaluation:
 * <ul>
 *   <li>{@code POST {url}/consumptions} — accepts a declaration (idempotent by
 *       contract) and answers 200, so the delivery loop marks it applied.</li>
 * </ul>
 * The campaign back office (and its live {@code groupBudgetStatus} read) moved to
 * the store node (imedge); only the register's consumption declaration remains
 * here, so this simulator no longer answers GraphQL.
 * It lives in {@code src/mock} and never enters the production jar.
 * <p>
 * WHY THE FULL PATH IS ON THE CLASS: RESTEasy classic selects the resource CLASS
 * by the most specific LITERAL path first, so a broad {@code @Path("/api")} here
 * would beat {@code PosHardwareResource} at {@code @Path("/")} and swallow every
 * uncovered {@code /api/**} URI — {@code /api/pos/scan} among them — answering
 * 404 and killing the scan bus of both the e2e tests and {@code quarkus:dev}.
 * The class therefore carries the exact endpoint {@code /api/consumptions} and
 * claims nothing more, the same discipline documented in
 * {@link com.intermarche.pos.ui.resource.MockHardwareResource}.
 */
@Path("/api/consumptions")
public class MockCampaignEngineResource {

    /**
     * Accepts a consumption declaration and acknowledges it. The effective URL
     * is {@code {pos.valuation.url}/consumptions}, unchanged by carrying the
     * path on the class instead of this method.
     *
     * @param body the declaration JSON, ignored by the simulator
     * @return a 200 acknowledgement
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ingestConsumption(String body) {
        return Response.ok("{\"accepted\":true}").build();
    }
}
