package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketRecoveryService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;

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
 * <p>
 * Placement: ui.ticket — consumed only by ParkedTicketResource and the parked-ticket scan handler (consumer-exclusivity rule).
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

    /** Printer — the parked receipt carries the resume number (LC-04-01-02). */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** The back-office parameters (parked-receipt printing policy). */
    @Inject
    PosSettingsService posSettingsService;

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
        // LC-04-01-02: the parked receipt carries the ticket number — the
        // code the resume scan (or a manual entry) recognizes. Printing is
        // administered on the back office.
        if (posSettingsService.parkingPrintReceipt()) {
            ticketPrinterService.printParkedTicket(draft);
        }
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
    /**
     * Resumes a parked ticket from its SCANNED (or typed) ticket number
     * (LC-04-02-01) — the number printed on the parked receipt. Resolution
     * is register-local: the number must belong to this terminal and still
     * be PARKED; everything else answers the same message as an unknown id,
     * and the resume itself reuses the guarded {@link #resume(Long)}.
     *
     * @param ticketNumber the scanned or typed ticket number
     * @return null on success, or an error message shown to the cashier
     */
    public String resumeByNumber(String ticketNumber) {
        Ticket draft = Ticket.<Ticket>find(
                "ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                ticketNumber, ticketNumberService.getTerminalId(),
                Ticket.TicketStatus.PARKED).firstResult();
        if (draft == null) {
            return "TICKET EN ATTENTE INTROUVABLE";
        }
        return resume(draft.id);
    }
}