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

import java.math.BigDecimal;
import java.util.Map;

/**
 * JAX-RS resource driving the manager-endorsement modal: polling endpoint,
 * validation of the endorsed action and cancellation.
 * <p>
 * Phase 0: the pending price-modification value flows as {@link BigDecimal}.
 */
@Path("/")
public class EndorsementResource {

    @Inject EndorsementService endorsementService;
    @Inject TicketService ticketService;
    @Inject RefundService refundService;
    @Inject com.intermarche.pos.ui.home.HomeService homeService;

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
     * @return the main (or lock) page
     */
    @POST
    @Path("/action/endorse-validate")
    @Consumes("application/x-www-form-urlencoded")
    public TemplateInstance validateEndorsement(@FormParam("login") String login, @FormParam("password") String password) {
        String actionToExecute = state.endorsement.requestedAction;

        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            return mainView(state);
        }

        if (endorsementService.authorize(login, password, actionToExecute)) {
            if (actionToExecute.equals("CANCEL_TICKET")) {
                ticketService.cancelTicket(state);
            } else if (actionToExecute.startsWith("CANCEL_LINE_")) {
                String uid = actionToExecute.substring("CANCEL_LINE_".length());
                ticketService.cancelItemById(state, uid);
            }
            else if (actionToExecute.equals("PRICE_MODIFICATION")) {
                String type = state.endorsement.pendingPriceType;
                String uid = state.endorsement.pendingTargetUid;
                BigDecimal val = state.endorsement.pendingValue;

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

            endorsementService.clearRequest(state);
            state.touch();
            return mainView(state);
        } else {
            state.endorsement.error = "AUTORISATION REFUSÉE";
            state.touch();
            return mainView(state);
        }
    }

    /**
     * Cancels the pending endorsement request.
     *
     * @return the main (or lock) page
     */
    @GET
    @Path("/action/endorse-cancel")
    public TemplateInstance cancelEndorsement() {
        endorsementService.clearRequest(state);
        state.touch();
        return mainView(state);
    }

    @Inject Template main;
    @Inject Template lock;

    /**
     * Returns the main page, or the lock page when no operator is logged in.
     *
     * @param state the current POS state
     * @return the appropriate template instance
     */
    private TemplateInstance mainView(PosState state) {
        return state.isLocked() ? lock.data("state", state) : main.data("state", state);
    }
}
