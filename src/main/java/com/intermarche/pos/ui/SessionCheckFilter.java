package com.intermarche.pos.ui;

import com.intermarche.pos.service.CashSessionService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * JAX-RS filter enforcing the cash-session guard globally: a logged-in cashier
 * with no open session on this register is sent to the session screen instead
 * of the sale screen.
 *
 * <p>The routing existed already — {@code AuthResource.openLane()} lands on
 * {@code /session} when no session is open — but it was a SUGGESTION: the
 * session screen has a RETOUR key like every other, and the drawer diversion
 * could swallow the redirect on the way. Whichever way the sale screen was
 * reached, the first scanned article then answered "AUCUNE SESSION OUVERTE"
 * and the operator had to work out what to do. A guard, like the lock and the
 * drawer, is what makes a rule hold from every direction rather than from one.
 *
 * <p>Runs AFTER the lock guard ({@link LockCheckFilter},
 * {@link Priorities#AUTHENTICATION}) and before the drawer one: there is no
 * session to demand from a register nobody is logged into, and the drawer
 * check belongs to the screen the operator finally reaches.
 *
 * <p>Training mode sells without a session by design, so it is not held here.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
public class SessionCheckFilter implements ContainerRequestFilter {

    /**
     * Path prefixes that keep answering with no session open — the session
     * flow itself, everything the lock guard already lets through while
     * locked, and the gestures that legitimately precede a session:
     * <ul>
     *   <li>{@code /session} AND {@code /action/session}: the screen that opens
     *       one and the action that does it. Two prefixes because they are two
     *       different paths — allowlisting only the screen left the OPEN button
     *       diverted back to the screen it was posted from, so the session could
     *       never be opened at all;</li>
     *   <li>{@code /lock}, {@code /action/unlock},
     *       {@code /action/hardware-override}: the entry flow, which ENDS on
     *       the session screen;</li>
     *   <li>{@code /drawer-error}, {@code /api/}, {@code /action/resume-after-drawer}:
     *       the drawer diversion and the machine surfaces — the login pulse
     *       opens the drawer, so the very next request is one of these;</li>
     *   <li>{@code /action/training}: entering training mode is how one sells
     *       without a session, and it must not be locked behind having one;</li>
     *   <li>{@code /weight}: the scale PUSHES here — a machine surface, like the
     *       scan bus under {@code /api/}, that a redirect would simply break;</li>
     *   <li>{@code /pin-change}, {@code /badge-print}, {@code /theme-select},
     *       {@code /supervisor} and their actions: what an operator does at their
     *       post rather than at the till. None of them sells anything, and the
     *       supervisor call is wanted PRECISELY when someone is stuck;</li>
     *   <li>{@code /ticket-fragment}, {@code /endorsement-data}: the polls of
     *       an open screen, which must answer rather than be handed a
     *       redirect their JSON parser cannot read;</li>
     *   <li>{@code /cash-count}: the Z counting screen, reached from the session
     *       screen and still needed while the session is being closed;</li>
     *   <li>{@code /admin}, {@code /dashboard}, {@code /journal}, {@code /t/},
     *       {@code /customer}: back office, store node and customer-facing
     *       pages, which have no cash session at all.</li>
     * </ul>
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/session",
            "/action/session",
            "/cash-count",
            "/lock",
            "/action/unlock",
            "/action/hardware-override",
            "/action/resume-after-drawer",
            "/action/training",
            "/weight",
            "/pin-change",
            "/action/pin-change",
            "/badge-print",
            "/action/badge-print",
            "/theme-select",
            "/action/theme",
            "/supervisor",
            "/action/supervisor",
            "/drawer-error",
            "/api/",
            "/t/",
            "/customer",
            "/dashboard",
            "/journal",
            "/admin",
            "/ticket-fragment",
            "/endorsement-data",
            "/products/import",
            "/prices/import",
            "/product-families/import",
            "/stores/import",
            "/employees/import",
            "/feeds/import");

    @Inject
    PosState state;

    @Inject
    CashSessionService cashSessionService;

    /**
     * Aborts the request toward the session screen when a logged-in cashier has
     * no open session on this register, unless the requested path is one of the
     * allowlisted surfaces.
     *
     * @param requestContext the JAX-RS request context
     * @throws IOException never in practice (filter contract)
     */
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (state.isLocked() || state.trainingMode) {
            return;
        }
        String raw = requestContext.getUriInfo().getPath();
        String path = raw.startsWith("/") ? raw : "/" + raw;
        if (isAllowedWithoutSession(path)) {
            return;
        }
        if (cashSessionService.getOpenSession() != null) {
            return;
        }
        requestContext.abortWith(
                Response.seeOther(URI.create("/session?error=no-session")).build()
        );
    }

    /**
     * Returns true when the path may answer with no session open.
     *
     * @param path the normalized request path
     * @return true for allowlisted surfaces
     */
    private boolean isAllowedWithoutSession(String path) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
