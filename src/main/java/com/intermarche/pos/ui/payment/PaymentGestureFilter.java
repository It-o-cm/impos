package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.PosState;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.Provider;

/**
 * Clears the stale message of the payment screen at the start of every
 * {@link PaymentGesture} route.
 *
 * <p>The payment screen's message zone shows the error, warning or notice the
 * last gesture left — a refused card, a cheque the guarantee turned down. It
 * used to stay there through every later gesture, a successful settlement
 * included, so the cashier read a refusal on a sale that had since been paid.
 * The sale screen never had the defect: every scan and article entry clears
 * the zone before it runs. This filter gives the payment screen the same
 * discipline, in one place rather than in each of its routes: the zone is
 * cleared, then the gesture runs and states its own outcome, if any.
 *
 * <p>The screen itself, {@code GET /pay}, is the target every gesture
 * redirects to, so it must not clear what the gesture just said: it clears
 * only on ENTRY into the payment phase (no payment under way, no completed
 * transaction), where whatever the zone holds was left by the sale screen.
 *
 * <p>Runs after the drawer guard: a gesture that guard diverts never ran, so
 * it clears nothing.
 */
@Provider
@PaymentGesture
@Priority(Priorities.USER + 100)
public class PaymentGestureFilter implements ContainerRequestFilter {

    /** The route of the payment screen itself. */
    static final String SCREEN_PATH = "/pay";

    /** The matched resource method. */
    @Inject
    ResourceInfo resourceInfo;

    /** The register state whose message zone is cleared. */
    @Inject
    PosState state;

    /**
     * Clears the message zone before a gesture, or on entry into the payment
     * phase for the screen itself.
     *
     * @param requestContext the JAX-RS request context
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        Path path = resourceInfo.getResourceMethod().getAnnotation(Path.class);
        boolean screen = path != null && SCREEN_PATH.equals(path.value());
        if (screen && (state.payment.paymentInProgress || state.payment.transactionComplete)) {
            return;
        }
        state.ticket.transientError = null;
    }
}
