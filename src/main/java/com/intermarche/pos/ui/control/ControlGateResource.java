package com.intermarche.pos.ui.control;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * The CONTROL screen of the register (BO-04-03-09/10/12): the yes/no question
 * an alert asks, or the justifications a function asks for, and the one-use
 * continuation that replays the held gesture once it is answered.
 */
@Path("/")
public class ControlGateResource {

    /** The gate holding the gesture. */
    @Inject
    ControlGateService gate;

    /** Reads the held form back into its fields. */
    @Inject
    com.intermarche.pos.ui.endorsement.FunctionApprovalService functionApprovals;

    /** The question or justification screen. */
    @Inject
    @Location("control-gate")
    Template page;

    /** The auto-submitted form of an answered POST. */
    @Inject
    @Location("control-gate-continue")
    Template continuePage;

    /**
     * Shows what the held gesture waits for; with nothing held, the sale.
     *
     * @return the control screen, or a redirect to the sale
     */
    @GET
    @Path("/control-gate")
    @Produces(MediaType.TEXT_HTML)
    public Object show() {
        ControlGateService.Held held = gate.pending();
        if (held == null) {
            return Response.seeOther(URI.create("/")).build();
        }
        return page.data("question", held.question())
                .data("justification", held.function() != null)
                .data("reasons", gate.justifications());
    }

    /**
     * Records the answer and goes where the gate says: the replay of the
     * gesture, or the sale.
     *
     * @param choice YES or NO, or the code of a justification
     * @return the redirect
     */
    @POST
    @Path("/action/control-gate/answer")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response answer(@FormParam("choice") String choice) {
        return Response.seeOther(URI.create(gate.answer(choice))).build();
    }

    /**
     * Replays an answered POST through the browser's ordinary form flow.
     *
     * @param token the continuation's token
     * @return the auto-submitted form, or the sale when the token is stale
     */
    @GET
    @Path("/control-gate/continue")
    @Produces(MediaType.TEXT_HTML)
    public Object resume(@QueryParam("token") String token) {
        ControlGateService.Held held = gate.continuation(token);
        if (held == null) {
            return Response.seeOther(URI.create("/")).build();
        }
        return continuePage.data("target", held.target())
                .data("fields", functionApprovals.fields(held.body()))
                .data("token", held.token());
    }
}
