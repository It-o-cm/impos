package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.Map;

@Path("/")
public class EndorsementResource {
    @Inject EndorsementService endorsementService;
    @Inject TicketService ticketService;

    @Inject PosState state;

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

    @POST
    @Path("/action/endorse-validate")
    @Consumes("application/x-www-form-urlencoded")
    public TemplateInstance validateEndorsement(@FormParam("login") String login, @FormParam("password") String password) {
        String actionToExecute = state.endorsement.requestedAction;

        if (actionToExecute == null) {
            endorsementService.clearRequest(state);
            return mainView(state);
        }

        if (endorsementService.isManager(login, password)) {
            if (actionToExecute.equals("CANCEL_TICKET")) {
                ticketService.cancelTicket(state);
            } else if (actionToExecute.startsWith("CANCEL_LINE_")) {
                String uid = actionToExecute.substring("CANCEL_LINE_".length());
                ticketService.cancelItemById(state, uid);
            }
            else if (actionToExecute.equals("PRICE_MODIFICATION")) {
                String type = state.endorsement.pendingPriceType;
                String uid = state.endorsement.pendingTargetUid;
                double val = state.endorsement.pendingValue;

                TicketState.TicketItem item = state.ticket.items.stream()
                        .filter(i -> i.uid.equals(uid))
                        .findFirst()
                        .orElse(null);

                if (item != null) {
                    // Correspondance des types
                    if ("REMISE".equals(type)) ticketService.applyRemise(item, val);
                    else if ("DISCOUNT".equals(type)) ticketService.applyDiscount(item, val);
                    else if ("FORCE_PRICE".equals(type)) ticketService.forcePrice(item, val);

                    ticketService.recalculateTotal(state);
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

    @GET
    @Path("/action/endorse-cancel")
    public TemplateInstance cancelEndorsement() {
        endorsementService.clearRequest(state);
        state.touch();
        return mainView(state);
    }

    @Inject Template main;
    @Inject Template lock;
    private TemplateInstance mainView(PosState state) {
        return state.isLocked() ? lock.data("state", state) : main.data("state", state);
    }
}