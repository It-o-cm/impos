package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
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

    /** Asks the store node for a ticket this register does not hold (LC-08-05-05). */
    @Inject
    StoreTicketClient storeTicketClient;

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

    /**
     * Opens the bon-pour-échange preparation on the viewed ticket (LC-08-05-10).
     */
    public void startExchange() {
        state.reprint.clearExchange();
        state.reprint.exchangeMode = true;
        state.touch();
    }

    /**
     * Leaves the bon-pour-échange preparation without printing anything.
     */
    public void cancelExchange() {
        state.reprint.clearExchange();
        state.touch();
    }

    /**
     * Names a line for the bon pour échange, or unnames it (LC-08-05-12).
     *
     * @param lineId the database id of the line touched
     */
    public void toggleExchangeLine(Long lineId) {
        state.reprint.toggleExchangeLine(lineId);
        state.touch();
    }

    /**
     * Prints the bon pour échange of a ticket (LC-08-05-13) and leaves the
     * preparation.
     *
     * <p>Refused in training for the same reason a duplicata is: the bon carries a
     * real ticket's references, and a number that leaves the register must name a sale
     * that happened. It bumps no print counter — it states no amount, so it is not a
     * copy of the ticket.
     *
     * @param ticketId the database id of the original ticket
     */
    public void printExchange(Long ticketId) {
        if (state.trainingMode) {
            state.ticket.setError("IMPRESSION INDISPONIBLE EN FORMATION");
            state.touch();
            return;
        }
        if (ticketId != null) {
            ticketPrinterService.printExchangeVoucher(ticketId,
                    new java.util.LinkedHashSet<>(state.reprint.exchangeSelection));
        }
        state.reprint.clearExchange();
        state.touch();
    }

    /**
     * Opens the mask naming a ticket held by another register (LC-08-05-05).
     */
    public void startForeign() {
        state.reprint.foreignTicketNumber = "";
        state.reprint.foreignError = "";
        state.touch();
    }

    /**
     * Prints the duplicata of a ticket made on ANOTHER register (LC-08-05-05).
     *
     * <p>The shop's sales all reach the store node, so that is where such a ticket is
     * asked for — already rendered, so the paper reads exactly like the one the
     * customer got at the other till. Nothing of it is stored here.
     *
     * @param ticketNumber the number of the ticket, as printed on the customer's paper
     */
    public void printForeign(String ticketNumber) {
        state.reprint.foreignTicketNumber = ticketNumber == null ? "" : ticketNumber.trim();
        state.reprint.foreignError = "";
        if (state.trainingMode) {
            state.reprint.foreignError = "RÉIMPRESSION INDISPONIBLE EN FORMATION";
            state.touch();
            return;
        }
        if (state.reprint.foreignTicketNumber.isEmpty()) {
            state.reprint.foreignError = "NUMÉRO DE TICKET VIDE";
            state.touch();
            return;
        }
        if (!storeTicketClient.isAvailable()) {
            state.reprint.foreignError = "AUCUN NŒUD MAGASIN CONFIGURÉ";
            state.touch();
            return;
        }
        java.util.Optional<String> rendered =
                storeTicketClient.fetchDuplicate(state.reprint.foreignTicketNumber);
        if (rendered.isEmpty()) {
            state.reprint.foreignError =
                    "TICKET INTROUVABLE AU MAGASIN : " + state.reprint.foreignTicketNumber;
            state.touch();
            return;
        }
        ticketPrinterService.printRenderedTicket(rendered.get());
        state.touch();
    }
}
