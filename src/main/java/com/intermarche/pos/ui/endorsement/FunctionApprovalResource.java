package com.intermarche.pos.ui.endorsement;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** Restores an approved form using the browser's ordinary POST/redirect flow (LC-01-05-01). */
@Path("/approved-function")
public class FunctionApprovalResource {
    /** The current one-use frozen form. */
    @Inject FunctionApprovalService approvals;
    /** Escapes every original field, including repeated values. */
    @Inject @Location("approved-function") Template page;

    /**
     * Presents only a valid continuation; a stale or arbitrary URL never recreates a gesture.
     * @param token approved continuation
     * @return an auto-submit form or the normal sale
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Object show(@QueryParam("token") String token) {
        var gesture = approvals.continuation(token);
        if (gesture == null) return Response.seeOther(URI.create("/")).build();
        return page.data("target", gesture.target()).data("fields", approvals.fields(gesture.body()))
                .data("token", gesture.token());
    }
}
