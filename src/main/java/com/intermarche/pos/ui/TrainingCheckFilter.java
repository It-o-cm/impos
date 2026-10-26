package com.intermarche.pos.ui;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;

/** Rejects configured training actions before their resource can mutate state (LC-13-05-04). */
@Provider
@Priority(Priorities.AUTHENTICATION + 20)
public class TrainingCheckFilter implements ContainerRequestFilter {

    /** Current operator mode. */
    @Inject PosState state;
    /** Same policy as the visible key guard. */
    @Inject TrainingPolicyService policy;
    /** Existing translated operator refusal. */
    @Inject Messages i18n;

    /**
     * Keeps normal selling unchanged and refuses configured actions only in training.
     * @param context incoming request, aborted before reaching a blocked resource
     */
    @Override
    public void filter(ContainerRequestContext context) {
        if (!state.trainingMode) return;
        String raw = context.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        if (!policy.blocksPath(path)) return;
        state.ticket.setError(i18n.get("op.fonctionIndisponibleFormation"));
        state.touch();
        context.abortWith(Response.seeOther(URI.create("/")).build());
    }
}
