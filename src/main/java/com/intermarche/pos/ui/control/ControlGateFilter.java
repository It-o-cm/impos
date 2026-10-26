package com.intermarche.pos.ui.control;

import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Puts every register FUNCTION through the control gate (BO-04-03-08/09/10/12):
 * the administered alerts on functions and payment methods, then the
 * justification a function asks for. Runs before the supervisor-endorsement
 * filter, because a justification comes before the aval — WHY before WHO
 * (BO-06-06-04) — and its replay passes the gate once, whatever endorsement
 * replays it after.
 */
@Provider
@Priority(Priorities.USER + 19)
public class ControlGateFilter implements ContainerRequestFilter {

    /** The register state the gate speaks through. */
    @Inject
    PosState state;

    /** The gate. */
    @Inject
    ControlGateService gate;

    /**
     * Stops, holds or lets through one request.
     *
     * @param context the request
     * @throws IOException when the form cannot be read
     */
    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        String raw = context.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        String method = context.getMethod();
        if (!gated(path) || (!"GET".equals(method) && !"POST".equals(method))) {
            return;
        }
        synchronized (state) {
            String query = context.getUriInfo().getRequestUri().getRawQuery();
            String body = "";
            String token = context.getUriInfo().getQueryParameters().getFirst("_posGate");
            boolean approvalReplay = context.getUriInfo().getQueryParameters().getFirst("_posApproval") != null;
            if ("POST".equals(method) && context.getMediaType() != null
                    && context.getMediaType().isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE)) {
                body = new String(context.getEntityStream().readAllBytes(), StandardCharsets.UTF_8);
                approvalReplay = approvalReplay || body.contains("_posApproval=");
                String carried = field(body, "_posGate");
                if (carried != null) {
                    token = carried;
                    body = removeField(body, "_posGate");
                }
                context.setEntityStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            }
            String target = context.getUriInfo().getRequestUri().getRawPath()
                    + (query == null || query.isEmpty() ? "" : "?" + query);
            if (gate.replay(method, ControlGateService.strip(target), token, approvalReplay)) {
                return;
            }
            String stop = gate.onFunction(method, path, ControlGateService.strip(target),
                    removeField(body, "_posApproval"));
            if (stop != null) {
                context.abortWith(Response.seeOther(URI.create(stop)).build());
            }
        }
    }

    /**
     * Whether a path is a register function the gate may watch: the gate's own
     * screens, the endorsement ceremony, authentication, the machine surfaces
     * and the polls cannot be stopped by it — the same roads the supervisor
     * endorsement leaves open (LC-01-05-01).
     *
     * @param path the request path
     * @return true when the gate looks at the request
     */
    static boolean gated(String path) {
        return !path.equals("/") && !path.startsWith("/api/") && !path.startsWith("/admin")
                && !path.startsWith("/control-gate") && !path.startsWith("/action/control-gate")
                && !path.startsWith("/action/endorse") && !path.equals("/endorsement-data")
                && !path.equals("/approved-function") && !path.equals("/ticket-fragment")
                && !path.startsWith("/lock") && !path.startsWith("/action/unlock") && !path.startsWith("/action/lock")
                && !path.startsWith("/action/hardware-override") && !path.startsWith("/supervisor/")
                && !path.startsWith("/pos/") && !path.startsWith("/customer") && !path.startsWith("/t/");
    }

    /**
     * Reads one field of a form, decoded.
     *
     * @param body the form
     * @param name the field
     * @return its first value, or null when absent
     */
    static String field(String body, String name) {
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    /**
     * Removes every occurrence of one field from a form.
     *
     * @param body the form
     * @param name the field
     * @return the form without it
     */
    static String removeField(String body, String name) {
        StringBuilder kept = new StringBuilder();
        for (String pair : body.split("&")) {
            if (pair.isEmpty() || pair.split("=", 2)[0].equals(name)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.toString();
    }
}
