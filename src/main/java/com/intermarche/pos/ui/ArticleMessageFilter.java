package com.intermarche.pos.ui;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;
import java.util.List;

/** LC-02-03-11: sale actions cannot bypass an unacknowledged article refusal. */
@Provider
@Priority(Priorities.AUTHENTICATION + 30)
public class ArticleMessageFilter implements ContainerRequestFilter {
    /** Machine status, customer display, locking and the acknowledgement remain available. */
    private static final List<String> ALLOWED = List.of("/article-message", "/action/article-message/ack",
            "/ticket-fragment", "/customer", "/customer-data", "/api/hardware", "/lock",
            "/action/unlock", "/action/force-unlock", "/action/force-close", "/admin", "/dashboard",
            "/feeds", "/q", "/health", "/css", "/js", "/img", "/fonts", "/webjars", "/theme.css");
    /** Current refusal, preserved across requests and lock/resume. */
    @Inject PosState state;

    /**
     * Rejects incoming scans and redirects operator actions to the acknowledgement page.
     * @param context request stopped before its resource can change the sale
     */
    @Override
    public void filter(ContainerRequestContext context) {
        if (state.blockingArticleMessage == null) return;
        String raw = context.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        if (ALLOWED.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) return;
        if (path.equals("/api/pos/scan") || path.equals("/weight")) {
            context.abortWith(Response.status(Response.Status.CONFLICT).build());
        } else {
            context.abortWith(Response.seeOther(URI.create("/article-message")).build());
        }
    }
}
