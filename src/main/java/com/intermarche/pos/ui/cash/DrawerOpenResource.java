package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosListService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The forced opening of the drawer outside any transaction ({@code LC-12-01-01}),
 * with the reason the back office administers for it ({@code LC-12-01-02}).
 *
 * <p>WHY BEFORE WHO, as for a line cancellation: when the shop administers
 * justifications for the {@code DRAWER_OPEN} function, the cashier picks one
 * before the supervisor is asked, so the reason survives the parking the
 * authorisation imposes. No list administered, no question.
 *
 * <p>THE DRAWER OPENS ONLY ONCE AUTHORISED. A cashier's gesture parks on the
 * shared endorsement modal and is replayed once a manager validates it; a
 * supervisor at the till opens it under their own badge. Every opening is
 * journalled with its reason and its authoriser, which is what the back-office
 * journal filters and the states group by.
 *
 * <p>OUTSIDE A TRANSACTION ONLY: a drawer opened with a cart on the screen
 * would be money handled with no sale to account for it. The key is not offered
 * then, and the server refuses it the same way when it is reached anyway.
 */
@Path("/")
@DrawerMustBeClosed
public class DrawerOpenResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(DrawerOpenResource.class);

    /** The action code parked on the endorsement modal for this gesture. */
    public static final String ENDORSEMENT_ACTION = "DRAWER_FORCE_OPEN";

    /** The reason chooser. */
    @Inject @Location("drawer-open") Template drawerOpen;

    /** The screen state: the cart, the payment and the operator. */
    @Inject PosState state;

    /** The operator messages. */
    @Inject com.intermarche.pos.ui.Messages i18n;

    /** The administered justifications. */
    @Inject PosListService posListService;

    /** The supervisor authorisation. */
    @Inject EndorsementService endorsementService;

    /** The drawer itself. */
    @Inject HardwareService hardwareService;

    /** The journal the back office reads. */
    @Inject TechnicalEventService technicalEventService;

    /**
     * The manager the shared modal just validated, while a parked opening is
     * being replayed; null the rest of the time. Cleared in a {@code finally}
     * so a replay cannot lend its authority to the next request.
     */
    private String endorsedByOverride = null;

    /**
     * Starts a forced opening: the reason chooser when the shop administers
     * reasons, the authorisation otherwise.
     *
     * @return the reason chooser, or a redirect to the sale screen
     */
    @GET
    @Path("/drawer-open")
    public Response page() {
        LOGGER.info("Entering method page");
        if (!outsideTransaction()) {
            LOGGER.info("Exiting method page");
            return refusedDuringTransaction();
        }
        List<PosListService.ListChoice> reasons =
                posListService.justificationsOf(PosListService.FUNCTION_DRAWER_OPEN);
        if (reasons.isEmpty()) {
            LOGGER.info("Exiting method page");
            return proceed(null);
        }
        LOGGER.info("Exiting method page");
        return Response.ok(drawerOpen.data("state", state).data("reasons", reasons))
                .type(MediaType.TEXT_HTML).build();
    }

    /**
     * Records the reason the cashier tapped and goes on to the authorisation.
     *
     * <p>A code the administered list does not carry is refused rather than
     * recorded as itself: a state grouping by reason must not grow a column of
     * codes nobody administered.
     *
     * @param code the code of the reason tapped
     * @return a redirect to the sale screen, or back to the chooser on an unknown code
     */
    @GET
    @Path("/action/drawer-open")
    public Response choose(@QueryParam("code") String code) {
        LOGGER.info("Entering method choose with code: " + code);
        if (!outsideTransaction()) {
            LOGGER.info("Exiting method choose");
            return refusedDuringTransaction();
        }
        PosListService.ListChoice reason =
                posListService.justificationOf(PosListService.FUNCTION_DRAWER_OPEN, code);
        if (reason == null) {
            state.ticket.setError(i18n.get("op.motifOuvertureInconnu"));
            state.touch();
            LOGGER.info("Exiting method choose");
            return redirect("/drawer-open");
        }
        LOGGER.info("Exiting method choose");
        return proceed(reason);
    }

    /**
     * Replays a parked opening once the shared modal validated a manager.
     *
     * <p>The replay goes back through every guard: a cart rung while the
     * request waited cancels the opening.
     *
     * @param form the parked fields: the reason's code, empty when none was asked
     * @param endorsedBy the endorsing manager's badge or login
     * @return a redirect to the sale screen
     */
    public Response performEndorsed(Map<String, String> form, String endorsedBy) {
        LOGGER.info("Entering method performEndorsed with endorsedBy: " + endorsedBy);
        endorsedByOverride = endorsedBy;
        try {
            String code = form.get("code");
            LOGGER.info("Exiting method performEndorsed");
            return code == null || code.isEmpty() ? page() : choose(code);
        } finally {
            endorsedByOverride = null;
        }
    }

    /**
     * Opens the drawer when someone authorised it, or parks the gesture on the
     * endorsement modal.
     *
     * @param reason the reason chosen, or null when none is administered
     * @return a redirect to the sale screen
     */
    private Response proceed(PosListService.ListChoice reason) {
        String endorser = endorsedByOverride;
        if (endorser == null && endorsementService.operatorIsSupervisor(state)) {
            endorser = state.auth.operatorBadgeId;
        }
        if (endorser == null) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("code", reason == null ? "" : reason.code());
            endorsementService.requestAuthorization(state, ENDORSEMENT_ACTION, form, "/");
            return redirect("/");
        }
        hardwareService.openDrawer();
        String why = reason == null ? i18n.get("drawer.sansMotif") : reason.code() + " - " + reason.label();
        technicalEventService.log(TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                why + " (aval " + endorser + ")", state.auth.operatorBadgeId);
        state.ticket.setNotice(i18n.get("op.tiroirOuvertForce"));
        state.touch();
        return redirect("/");
    }

    /**
     * Tells whether the register is outside any transaction: no line on the
     * ticket, cancelled ones included, and no payment under way.
     *
     * @return true when the drawer may be opened
     */
    private boolean outsideTransaction() {
        return state.ticket.items.isEmpty() && state.ticket.cancelledItems.isEmpty()
                && !state.payment.paymentInProgress;
    }

    /**
     * Refuses the opening because a transaction is under way.
     *
     * @return a redirect to the sale screen carrying the message
     */
    private Response refusedDuringTransaction() {
        state.ticket.setError(i18n.get("op.ouvertureTiroirHorsTransaction"));
        state.touch();
        return redirect("/");
    }

    /**
     * Builds a 303 redirect.
     *
     * @param location the target location
     * @return a 303 See Other response
     */
    private Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}
