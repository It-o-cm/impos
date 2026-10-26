package com.intermarche.pos.ui.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SIMULATED CHEQUE-GUARANTEE PROVIDER (BO-03-02-24), answered by the
 * register itself so that the cheque authorization can be demonstrated on a
 * lane with no real provider — the same doctrine, and the same build-time
 * filter, as the simulated payment hub.
 *
 * <p>Its rules are fixed so a recette can play each outcome:
 * <ul>
 *   <li>an account number starting with {@value #OPPOSED_PREFIX} is refused,
 *       "CHEQUE EN OPPOSITION";</li>
 *   <li>an account number starting with {@value #DOWN_PREFIX} gets a 503, the
 *       provider being down — the register then calls the supervisor;</li>
 *   <li>an amount above {@value #CEILING} euros is refused, "MONTANT SUPERIEUR
 *       AU PLAFOND GARANTI";</li>
 *   <li>anything else is approved with an authorization number.</li>
 * </ul>
 */
@Path("/api/cheque-guarantee")
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test", "demo"})
public class MockChequeGuaranteeResource {

    /** The account prefix the simulated provider refuses as opposed. */
    static final String OPPOSED_PREFIX = "000";

    /** The account prefix for which the simulated provider is down. */
    static final String DOWN_PREFIX = "999";

    /** The largest amount the simulated provider guarantees, in euros. */
    static final String CEILING = "1500";

    /** The source of the authorization numbers. */
    private long counter;

    /**
     * Answers one guarantee request.
     *
     * @param request the {@code {provider, reference, amount, accountNumber}} body
     * @return 200 with {@code {approved, authorization, message}}, or 503 when "down"
     */
    @POST
    @Path("/authorize")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized Response authorize(JsonNode request) {
        String account = request.path("accountNumber").asText("");
        BigDecimal amount = request.path("amount").isNumber()
                ? request.path("amount").decimalValue() : BigDecimal.ZERO;
        if (account.startsWith(DOWN_PREFIX)) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        if (account.startsWith(OPPOSED_PREFIX)) {
            answer.put("approved", false);
            answer.put("message", "CHEQUE EN OPPOSITION");
        } else if (amount.compareTo(new BigDecimal(CEILING)) > 0) {
            answer.put("approved", false);
            answer.put("message", "MONTANT SUPERIEUR AU PLAFOND GARANTI");
        } else {
            counter++;
            answer.put("approved", true);
            answer.put("authorization", String.format("GAR%06d", counter));
            answer.put("message", "CHEQUE GARANTI");
        }
        return Response.ok(answer).build();
    }
}
