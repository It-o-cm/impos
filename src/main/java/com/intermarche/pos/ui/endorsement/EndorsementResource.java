package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.returnprocess.RefundService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.net.URI;

import java.math.BigDecimal;
import java.util.Map;

/**
 * JAX-RS resource driving the manager-endorsement modal: polling endpoint,
 * validation of the endorsed action and cancellation.
 * <p>
 * Phase 0: the pending price-modification value flows as {@link BigDecimal}.
 * <p>
 * The dispatch switch below is the REGISTRY of every guarded gesture of the
 * register: ticket and line cancellations, the three price modifications,
 * the refund methods (REFUND_&lt;METHOD&gt;_&lt;ticketId&gt;) and the
 * training toggle. Adding a guarded gesture is exactly two touches — one
 * request call parking the action string, one branch here executing it —
 * and the execution ALWAYS happens after the PIN validation, never before:
 * the parked string is inert until a manager credential passes.
 */
@Path("/")
public class EndorsementResource {

    @Inject EndorsementService endorsementService;
    @Inject TicketService ticketService;
    @Inject RefundService refundService;
    @Inject com.intermarche.pos.ui.home.HomeService homeService;

    /** Undoes the settlements an abandoned ticket had already taken (LC-04-04-07/09). */
    @Inject com.intermarche.pos.ui.payment.PaymentService paymentService;

    @Inject PosState state;

    /**
     * Returns the endorsement modal state for the UI polling.
     *
     * @return a JSON map with the modal state (active, action, scanned badge, error)
     */
    @GET
    @Path("/endorsement-data")
    @Produces("application/json")
    public Map<String, Object> getEndorsementData() {
        String badge = state.endorsement.scannedBadge;
        if (badge != null) state.endorsement.clearScannedBadge();
        return Map.of(
                "active", state.endorsement.active,
                "action", state.endorsement.requestedAction != null ? state.endorsement.requestedAction : "",
                "scannedBadge", badge != null ? badge : "",
                "error", state.endorsement.error != null ? state.endorsement.error : ""
        );
    }

    /**
     * Validates the pending endorsement with the presented credentials and,
     * on success, executes the endorsed action.
     *
     * @param login the badge id or login name presented for the endorsement
     * @param password the raw PIN presented for the endorsement
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST); any refusal message travels
     *         through the shared state
     */
    @POST
    @Path("/action/endorse-validate")
    @Consumes("application/x-www-form-urlencoded")
    public Response validateEndorsement(@FormParam("login") String login, @FormParam("password") String password) {
        String actionToExecute = state.endorsement.requestedAction;

        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            return redirectHome();
        }

        if (endorsementService.authorize(login, password, actionToExecute)) {
            executeApprovedAction(actionToExecute);
            endorsementService.clearRequest(state);
            state.touch();
            return redirectHome();
        } else {
            state.endorsement.error = "AUTORISATION REFUSÉE";
            state.touch();
            return redirectHome();
        }
    }

    /**
     * Executes an APPROVED endorsed action — the registry of every guarded
     * gesture. Called only after a manager credential passed, or through the
     * connected-supervisor shortcut below (LC-01-05-07): the approval always
     * precedes the execution, never the reverse.
     *
     * @param actionToExecute the parked action string
     */
    private void executeApprovedAction(String actionToExecute) {
            if (actionToExecute.equals("CANCEL_TICKET")) {
                // LC-04-04-07/09: a settlement already taken is undone with the
                // ticket — the lease released, the valuation reverted, the entries
                // dropped. The abandon screen named them before the operator
                // confirmed, which is what makes this an accepted proposal and not a
                // silent loss. With nothing settled it is a no-op.
                if (!state.payment.payments.isEmpty()) {
                    paymentService.cancelPayments(state);
                }
                ticketService.cancelTicket(state);
            } else if (actionToExecute.startsWith("CANCEL_LINE_")) {
                String uid = actionToExecute.substring("CANCEL_LINE_".length());
                ticketService.cancelItemById(state, uid);
            }
            else if (actionToExecute.equals("PRICE_MODIFICATION")) {
                String type = state.endorsement.pendingPriceType;
                String uid = state.endorsement.pendingTargetUid;
                BigDecimal val = state.endorsement.pendingValue;

                if (type != null && type.startsWith("GLOBAL_")) {
                    // Ticket-level gesture: no target line — the global
                    // discount applies to the whole sale (phase: global
                    // ticket discount) and recomputes internally; the flow
                    // then falls through to the common endorsement tail.
                    ticketService.applyGlobalDiscount(state, type, val);
                } else {
                    TicketState.TicketItem item = state.ticket.items.stream()
                            .filter(i -> i.uid.equals(uid))
                            .findFirst()
                            .orElse(null);

                    if (item != null) {
                        // Type dispatch
                        if ("REMISE".equals(type)) ticketService.applyRemise(item, val);
                        else if ("DISCOUNT".equals(type)) ticketService.applyDiscount(item, val);
                        else if ("FORCE_PRICE".equals(type)) ticketService.forcePrice(item, val);

                        ticketService.recalculateTotal(state);
                    }
                }
            }
            else if (actionToExecute.equals("TRAINING_TOGGLE")) {
                homeService.performTrainingToggle();
            }
            else if (actionToExecute.startsWith("REFUND_")) {
                // Format: REFUND_<METHOD>_<ticketId>
                String[] parts = actionToExecute.split("_");
                try {
                    Refund.RefundMethod method = Refund.RefundMethod.valueOf(parts[1]);
                    refundService.performRefund(state, method);
                } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                    state.endorsement.error = "ACTION DE REMBOURSEMENT INCONNUE";
                } catch (IllegalStateException e) {
                    // Guard refusal: the message is already on the refund screen
                }
            }
            else if (actionToExecute.startsWith("PRINT_BADGE_")) {
                // (Re)prints an operator's badge number (LC-01-06-01).
                homeService.printOperatorBadge(actionToExecute.substring("PRINT_BADGE_".length()));
            }
    }

    /**
     * Connected-supervisor shortcut (LC-01-05-07): when the LOGGED operator
     * already holds the MANAGER or ADMIN role, the pending endorsement is
     * executed directly — no second credential is asked. The role check is
     * done server-side here, never trusted from the page.
     *
     * @return a 303 redirect to the main page (PRG pattern, so a browser
     *         reload never replays the POST); any refusal message travels
     *         through the shared state
     */
    @POST
    @Path("/action/endorse-self")
    public Response selfEndorse() {
        String actionToExecute = state.endorsement.requestedAction;
        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            return redirectHome();
        }
        if (!endorsementService.operatorIsSupervisor(state)) {
            state.endorsement.error = "AUTORISATION REFUSÉE";
            state.touch();
            return redirectHome();
        }
        executeApprovedAction(actionToExecute);
        endorsementService.clearRequest(state);
        state.touch();
        return redirectHome();
    }

    /**
     * Cancels the pending endorsement request.
     *
     * @return the main page
     */
    @GET
    @Path("/action/endorse-cancel")
    public TemplateInstance cancelEndorsement() {
        endorsementService.clearRequest(state);
        state.touch();
        return mainView(state);
    }

    @Inject Template main;

    /**
     * Returns the main page.
     *
     * @param state the current POS state
     * @return the appropriate template instance
     */
    private TemplateInstance mainView(PosState state) {
        return main.data("state", state);
    }

    /**
     * Builds the 303 redirect to the main page used by every POST action
     * (PRG pattern, so a browser reload never replays the POST).
     *
     * @return a 303 See Other response targeting "/"
     */
    private Response redirectHome() {
        return Response.seeOther(URI.create("/")).build();
    }
}
