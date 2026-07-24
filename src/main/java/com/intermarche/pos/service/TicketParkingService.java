package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Parks and resumes carts on this register (phase 3, single-register scope).
 * <p>
 * Parking relies on the continuously synchronized draft: the current draft is
 * flipped to {@link Ticket.TicketStatus#PARKED} and the in-memory state is
 * cleared; resuming flips it back to OPEN and restores it through the shared
 * restore machinery of {@link TicketRecoveryService}. Parked tickets that are
 * never resumed are cancelled by the Z closing of the session.
 * <p>
 * Parking is refused once a payment exists (in progress or already
 * registered): registered payments are persisted on the draft, and a parked
 * ticket holding money would be a liability in limbo — the cashier settles
 * or clears the payments first. In training mode the draft sync returns
 * null, so parking answers "SYNCHRONISATION IMPOSSIBLE": consistent with
 * training persisting nothing (a training cart cannot outlive its session).
 */
@ApplicationScoped
public class TicketParkingService {

    private static final Logger LOG = Logger.getLogger(TicketParkingService.class);

    @Inject
    PosState state;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    TicketRecoveryService ticketRecoveryService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Parks the current cart: synchronizes the draft one last time, flips it
     * to PARKED and clears the in-memory state.
     *
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    public String parkCurrent() {
        if (state.ticket.items.isEmpty()) {
            return "AUCUN TICKET À METTRE EN ATTENTE";
        }
        if (state.payment.paymentInProgress || state.payment.paidAmount.signum() > 0) {
            return "PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE";
        }
        Long ticketId = ticketPersistenceService.syncDraft(state);
        if (ticketId == null) {
            return "SYNCHRONISATION IMPOSSIBLE";
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null || draft.status != Ticket.TicketStatus.OPEN) {
            return "TICKET INTROUVABLE";
        }
        draft.status = Ticket.TicketStatus.PARKED;
        draft.persist();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_PARKED, draft.ticketNumber);
        LOG.infof("Ticket mis en attente ID: %d (%s)", draft.id, draft.ticketNumber);

        state.clearTicket();
        state.touch();
        return null;
    }

    /**
     * Lists the parked tickets of this register, oldest first.
     *
     * @return the parked tickets
     */
    public List<Ticket> listParked() {
        return Ticket.list("terminalId = ?1 and status = ?2 order by id",
                ticketNumberService.getTerminalId(), Ticket.TicketStatus.PARKED);
    }

    /**
     * Resumes a parked ticket of this register: flips it back to OPEN and
     * restores it into the in-memory state. Refused while a cart is already
     * in progress.
     *
     * @param ticketId the database id of the parked ticket
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    public String resume(Long ticketId) {
        if (!state.ticket.items.isEmpty()) {
            return "TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD";
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null
                || draft.status != Ticket.TicketStatus.PARKED
                || !ticketNumberService.getTerminalId().equals(draft.terminalId)) {
            return "TICKET EN ATTENTE INTROUVABLE";
        }
        draft.status = Ticket.TicketStatus.OPEN;
        draft.persist();
        ticketRecoveryService.restoreDraft(draft);
        technicalEventService.log(TechnicalEvent.EventType.TICKET_RESUMED, draft.ticketNumber);
        LOG.infof("Ticket repris ID: %d (%s)", draft.id, draft.ticketNumber);
        return null;
    }
}
