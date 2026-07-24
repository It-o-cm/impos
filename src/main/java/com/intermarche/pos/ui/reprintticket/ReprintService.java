package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Drives the ticket-reprint screen: loads the closed-ticket history and
 * prints numbered duplicatas.
 * <p>
 * Phase 6: reprinting is blocked in training mode — a duplicata is a real
 * fiscal document and its print counter is part of the record, so a
 * training action must neither produce one nor bump one.
 */
@ApplicationScoped
public class ReprintService {

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    /**
     * Loads the closed tickets, most recent first, into the reprint state.
     */
    public void loadHistory() {
        state.reprint.setTickets(
                Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED).list()
        );
        state.touch();
    }

    /**
     * Prints a numbered duplicata of a closed ticket; refused in training
     * mode (real documents are untouchable there).
     *
     * @param ticketId the database id of the ticket to reprint
     */
    public void print(Long ticketId) {
        if (state.trainingMode) {
            state.ticket.setError("RÉIMPRESSION INDISPONIBLE EN FORMATION");
            state.touch();
            return;
        }
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
    }
}
