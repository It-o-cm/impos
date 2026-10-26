package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Applies generic store approval rules before the selected operator function executes (LC-01-05-01). */
@Provider
@Priority(Priorities.USER + 20)
public class FunctionApprovalFilter implements ContainerRequestFilter {
    /** Register state and the shared modal. */
    @Inject PosState state;
    /** Generic rules and one-use continuation. */
    @Inject FunctionApprovalService approvals;
    /** Existing credentials, journal and supervisor-session behavior. */
    @Inject EndorsementService endorsements;
    @Inject FunctionApprovalContext functionContext = new FunctionApprovalContext();

    /**
     * Holds navigation or an urlencoded form until a supervisor approves its exact request.
     * @param context request before resource invocation
     * @throws IOException if the incoming form cannot be read
     */
    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String raw = context.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        // LC-01-05-01: the ceremony, authentication, static assets and machine polls cannot guard themselves.
        if (path.equals("/") || path.startsWith("/api/") || path.startsWith("/admin")
                || path.startsWith("/action/endorse") || path.equals("/endorsement-data")
                || path.equals("/approved-function") || path.equals("/ticket-fragment")
                || path.startsWith("/lock") || path.startsWith("/action/unlock")
                || path.startsWith("/action/hardware-override") || path.startsWith("/supervisor/")
                || path.startsWith("/pos/") || path.startsWith("/customer") || path.startsWith("/t/")) return;
        String method = context.getMethod();
        String policyMethod = method.equals("HEAD") ? "GET" : method;
        if (!policyMethod.equals("GET") && !policyMethod.equals("POST")) return;
        Boolean decision = approvals.decision(policyMethod, path);
        if (decision == null) return;
        if (!decision) { functionContext.exempt(); return; }
        // LC-01-05-01: JAX-RS can invoke a GET action for HEAD; never bypass its approval that way.
        if (method.equals("HEAD")) { context.abortWith(Response.status(405).header("Allow", "GET").build()); return; }
        synchronized (state) {
            String query = context.getUriInfo().getRequestUri().getRawQuery();
            String body = "";
            String token = context.getUriInfo().getQueryParameters().getFirst("_posApproval");
            if (method.equals("POST")) {
                if (context.getMediaType() == null || !context.getMediaType().isCompatible(jakarta.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED_TYPE)) {
                    context.abortWith(Response.status(415).build()); return;
                }
                byte[] bytes = context.getEntityStream().readNBytes(65537);
                if (bytes.length > 65536) { context.abortWith(Response.status(413).build()); return; }
                body = new String(bytes, StandardCharsets.UTF_8);
                var values = approvals.fields(body);
                var tokens = values.get("_posApproval");
                token = tokens == null ? null : tokens.getFirst();
                if (token != null) body = removeToken(body, token);
                context.setEntityStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            } else if (token != null) {
                query = removeToken(query, token);
            }
            String target = context.getUriInfo().getRequestUri().getRawPath()
                    + (query == null || query.isEmpty() ? "" : "?" + query);
            if (approvals.consume(method, target, body, token)) {
                functionContext.approve();
                return;
            }
            if (endorsements.selfAuthorized(state)) return;
            approvals.hold(method, target, body);
            endorsements.requestAuthorization(state, FunctionApprovalService.ACTION);
            // BO-04-02-01: a rule naming its validating profiles keeps the aval to them.
            state.endorsement.requiredProfiles = approvals.profiles(policyMethod, path);
            context.abortWith(Response.seeOther(URI.create("/")).build());
        }
    }

    /**
     * Removes only the server-appended last token; all original query/form bytes remain available.
     * @param content query or form containing the transport token
     * @param token parsed UUID
     * @return original content without the grant transport field
     */
    private String removeToken(String content, String token) {
        String suffix = "_posApproval=" + token;
        if (content.equals(suffix)) return "";
        String tail = "&" + suffix;
        return content.endsWith(tail) ? content.substring(0, content.length() - tail.length()) : content;
    }
}
