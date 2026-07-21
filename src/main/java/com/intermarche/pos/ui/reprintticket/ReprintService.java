package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReprintService {

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    public void loadHistory() {
        state.reprint.setTickets(
                Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED).list()
        );
        state.touch();
    }

    public void print(Long ticketId) {
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
    }
}