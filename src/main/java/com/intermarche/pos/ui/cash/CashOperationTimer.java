package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * TIMES the cash operations of the till (BO-06-06-03, « durée et part des
 * opérations de caisse (comptage, prélèvements,...) »): the time from the
 * moment the cashier opens a cash screen — a count, a withdrawal, a
 * contribution, a transfer, a cash movement — to the moment the operation is
 * recorded, journaled as a {@code CASH_OPERATION_TIMED} event carrying the kind
 * and the seconds, {@code kind;seconds}, which the node's cashier state adds up.
 *
 * <p>One filter over the routes rather than a clock in every screen: the
 * screens are many and the gesture is one — open a cash screen, record. The
 * clock starts at the FIRST cash screen opened, so a count reached through the
 * session screen is timed from the session screen; it stops at a recording
 * that succeeded — a redirect that carries no error — and is dropped when the
 * cashier goes back to the sale screen without recording anything, or locks
 * the till: an operation abandoned is not an operation.
 *
 * <p>An absurd duration — over an hour, a screen left open over lunch — is
 * dropped too rather than journaled as an hour of drawer work.
 *
 * <p>Placement: ui.cash, beside the cash screens it times.
 */
@Provider
public class CashOperationTimer implements ContainerRequestFilter, ContainerResponseFilter {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashOperationTimer.class);

    /** The longest operation journaled, in seconds. */
    static final long LONGEST = 3600;

    /** The cash screens whose opening starts the clock. */
    static final List<String> SCREENS = List.of("/cash-movement", "/withdrawal", "/apport", "/transfer",
            "/financial-movement", "/cash-count", "/session");

    /** The recordings that stop it, with the kind they journal. */
    static final List<String[]> RECORDINGS = List.of(
            new String[]{"/action/cash-movement/record", "MOUVEMENT"},
            new String[]{"/action/withdrawal/record", "PRELEVEMENT"},
            new String[]{"/action/safe-deposit/record", "PRELEVEMENT"},
            new String[]{"/action/apport/record", "APPORT"},
            new String[]{"/action/transfer/record", "TRANSFERT"},
            new String[]{"/action/financial-movement/record", "MOUVEMENT"},
            new String[]{"/action/session/close", "COMPTAGE"},
            new String[]{"/action/session/close-endorsed", "COMPTAGE"});

    /** The shared register state, holding the clock. */
    @Inject
    PosState state;

    /** The journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /** The clock, in epoch milliseconds. */
    LongSupplier now = System::currentTimeMillis;

    /**
     * Starts the clock on a cash screen, drops it on the sale or lock screen.
     *
     * @param request the incoming request
     */
    @Override
    public void filter(ContainerRequestContext request) {
        if (!"GET".equals(request.getMethod())) {
            return;
        }
        String path = path(request);
        if ("/".equals(path) || path.startsWith("/lock")) {
            state.cashOperationStartedAt = 0L;
            return;
        }
        for (String screen : SCREENS) {
            if (path.startsWith(screen) && state.cashOperationStartedAt == 0L) {
                state.cashOperationStartedAt = now.getAsLong();
                return;
            }
        }
    }

    /**
     * Stops the clock on a successful recording and journals the operation.
     *
     * @param request the request
     * @param response the response about to leave
     */
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!"POST".equals(request.getMethod()) || state.cashOperationStartedAt == 0L) {
            return;
        }
        String kind = kind(path(request));
        if (kind == null || !succeeded(response)) {
            return;
        }
        long seconds = (now.getAsLong() - state.cashOperationStartedAt) / 1000L;
        state.cashOperationStartedAt = 0L;
        if (seconds < 0 || seconds > LONGEST) {
            return;
        }
        technicalEventService.log(TechnicalEvent.EventType.CASH_OPERATION_TIMED, kind + ";" + seconds,
                state.auth.operatorBadgeId);
        LOGGER.infof("Opération de caisse %s chronométrée : %d s", kind, seconds);
    }

    /**
     * Names the kind of operation a recording route journals.
     *
     * @param path the route
     * @return the kind, or null when the route records no cash operation
     */
    static String kind(String path) {
        for (String[] recording : RECORDINGS) {
            if (recording[0].equals(path)) {
                return recording[1];
            }
        }
        return null;
    }

    /**
     * Whether a recording succeeded: a redirect, or a page, carrying no error.
     *
     * @param response the response
     * @return true when the status is below 400 and no redirect names an error
     */
    static boolean succeeded(ContainerResponseContext response) {
        if (response.getStatus() >= 400) {
            return false;
        }
        Object location = response.getHeaders().getFirst("Location");
        return location == null || !String.valueOf(location).contains("error=");
    }

    /**
     * Reads the request path with its leading slash.
     *
     * @param request the request
     * @return the path, starting with a slash
     */
    private static String path(ContainerRequestContext request) {
        String raw = request.getUriInfo().getPath();
        return raw.startsWith("/") ? raw : "/" + raw;
    }
}
