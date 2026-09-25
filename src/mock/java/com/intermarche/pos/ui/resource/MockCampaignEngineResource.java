package com.intermarche.pos.ui.resource;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Embedded CAMPAIGN-ENGINE SIMULATOR for the autonomous e2e/demo runs: the
 * campaign sibling of {@link MockValuationResource}. It answers the two calls
 * the campaigns &amp; budgets feature makes to the engine so the register under
 * test never depends on an externally running imvaluation:
 * <ul>
 *   <li>{@code POST {url}/consumptions} — accepts a declaration (idempotent by
 *       contract) and answers 200, so the delivery loop marks it applied;</li>
 *   <li>{@code POST {url}/graphql} — answers a neutral {@code groupBudgetStatus}
 *       (nothing consumed yet) so the back-office screens can render a live
 *       status against the simulator.</li>
 * </ul>
 * It lives in {@code src/mock} and never enters the production jar.
 */
@Path("/api")
public class MockCampaignEngineResource {

    /**
     * Accepts a consumption declaration and acknowledges it.
     *
     * @param body the declaration JSON, ignored by the simulator
     * @return a 200 acknowledgement
     */
    @POST
    @Path("/consumptions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ingestConsumption(String body) {
        return Response.ok("{\"accepted\":true}").build();
    }

    /**
     * Answers a neutral group budget status for any GraphQL query.
     *
     * @param query the GraphQL request, ignored by the simulator
     * @return a canned {@code groupBudgetStatus} with nothing consumed
     */
    @POST
    @Path("/graphql")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String graphql(String query) {
        return "{\"data\":{\"groupBudgetStatus\":{\"consumedAmount\":0.00,\"maxAmount\":null,"
                + "\"remainingAmount\":null,\"consumedCount\":0,\"maxApplications\":null,"
                + "\"remainingCount\":null,\"byStore\":[]}}}";
    }
}
