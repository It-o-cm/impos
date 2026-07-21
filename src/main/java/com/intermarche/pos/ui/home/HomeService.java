package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class HomeService {

    @Inject
    PosState state;

    @Inject
    TicketService ticketService;

    @Inject
    EndorsementService endorsementService;

    // --- Navigation ---

    public void toggleSecondaryMenu(boolean show) {
        state.showSecondaryMenu = show;
        state.touch();
    }

    public void selectLine(int index) {
        if (state.selectedTicketIndex == index) {
            state.selectedTicketIndex = -1;
        } else {
            state.selectedTicketIndex = index;
        }
        state.touch();
    }

    // --- Actions Ticket ---

    public void cancelLine() {
        String targetUid = null;
        if (state.selectedTicketIndex >= 0 && state.selectedTicketIndex < state.ticket.items.size()) {
            targetUid = state.ticket.items.get(state.selectedTicketIndex).uid;
        } else if (!state.ticket.items.isEmpty()) {
            targetUid = state.ticket.items.get(state.ticket.items.size() - 1).uid;
        }

        if (targetUid == null) return;

        if (targetUid.equals(state.lastEnteredItemId)) {
            ticketService.cancelItemById(state, targetUid);
            state.lastEnteredItemId = null;
        } else {
            endorsementService.requestAuthorization(state, "CANCEL_LINE_" + targetUid);
        }

        state.selectedTicketIndex = -1;
        state.touch();
    }

    public void cancelTicket() {
        endorsementService.requestAuthorization(state, "CANCEL_TICKET");
    }

    public void printLastTicket() {
        if (state.lastClosedTicketId != null) {
            ticketService.reprintTicket(state.lastClosedTicketId);
        }
    }

    // --- Prix ---

    public void openPriceMod(String type) {
        TicketState.TicketItem target = state.getTargetItem();
        if (target == null) {
            state.ticket.setError("AUCUNE LIGNE SÉLECTIONNÉE");
        } else {
            state.priceModState.set(type.toUpperCase(), target.uid, target.label);
        }
        state.touch();
    }

    public void cancelPriceMod() {
        state.priceModState.clear();
        state.touch();
    }

    public void submitPriceMod(String type, String uid, double value) {
        endorsementService.requestPriceModification(state, type, uid, value);
        state.priceModState.clear();
        state.touch();
    }
}