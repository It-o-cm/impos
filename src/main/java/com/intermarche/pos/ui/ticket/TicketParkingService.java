package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.*;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
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
 * <p>
 * Placement: ui.ticket — consumed only by ParkedTicketResource and the parked-ticket scan handler (consumer-exclusivity rule).
 */
@ApplicationScoped
public class TicketParkingService {

    private static final Logger LOGGER = Logger.getLogger(TicketParkingService.class);

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


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

    /** The store-node seam for inter-till resume (EXP-46); offline = no node. */
    @Inject
    InterTillResumeClient interTillResumeClient;

    /** The signed trace of every ticket put on hold, resumed or purged (LC-04-01-03). */
    @Inject
    com.intermarche.pos.service.ParkedTicketArchiveService parkedTicketArchiveService;

    /** Queues the push of a training cart put on hold (BO-06-06-01). */
    @Inject
    com.intermarche.pos.service.sync.register.SyncOutboxService syncOutboxService;

    /**
     * Parks the current cart: synchronizes the draft one last time, flips it
     * to PARKED and clears the in-memory state.
     *
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    public String parkCurrent() {
        LOGGER.info("Entering method parkCurrent");
        if (state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.aucunTicketMettreAttente");
        }
        if (state.payment.paymentInProgress || state.payment.paidAmount.signum() > 0) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.paiementCoursMiseAttente");
        }
        if (state.trainingMode) {
            LOGGER.info("Exiting method parkCurrent (training)");
            return parkTraining();
        }
        // EXP-45: a single ticket may wait at a time. Several used to be allowed;
        // the register now refuses a second park until the first is resumed.
        if (!listParked().isEmpty()) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.ticketDejaAttenteReprenez");
        }
        Long ticketId = ticketPersistenceService.syncDraft(state);
        if (ticketId == null) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.synchronisationImpossible");
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null || draft.status != Ticket.TicketStatus.OPEN) {
            LOGGER.info("Exiting method parkCurrent");
            return i18n.get("op.ticketIntrouvable2");
        }
        draft.status = Ticket.TicketStatus.PARKED;
        // BO-06-06-01: when it was parked, kept whatever happens next.
        draft.parkedAt = java.time.LocalDateTime.now();
        draft.persist();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_PARKED, draft.ticketNumber);
        // LC-04-01-03: what the ticket on hold carried is frozen, signed and chained.
        parkedTicketArchiveService.record(draft, com.intermarche.pos.domain.sale.ParkedTicketRecord.Event.PARKED,
                state.auth.operatorBadgeId);
        // LC-04-01-02: the parked receipt carries the ticket number — the
        // code the resume scan (or a manual entry) recognizes. Printing is
        // administered on the back office.
        if (posSettingsService.parkingPrintReceipt() && !posSettingsService.parkingPrintOnRequest()) {
            ticketPrinterService.printParkedTicket(draft);
        }
        LOGGER.infof("Ticket mis en attente ID: %d (%s)", draft.id, draft.ticketNumber);

        state.clearTicket();
        state.touch();
        LOGGER.info("Exiting method parkCurrent");
        return null;
    }

    /** LC-04-01-02: expose the administered print activation to the parked list.
     * A training cart on hold is no ticket of the register and has no receipt
     * (BO-06-06-01): nothing is printed in training mode.
     *
     * @return true when the operator may print a waiting receipt
     */
    public boolean canPrintReceipt() {
        return !state.trainingMode && posSettingsService.parkingPrintReceipt();
    }

    /**
     * Prints a still-parked local ticket at the operator's request.
     * @param id local draft identifier
     * @return null on success, otherwise a localized refusal
     */
    @Transactional
    public String printParked(Long id) {
        if (!canPrintReceipt()) return i18n.get("parked.printDisabled");
        Ticket draft = Ticket.findById(id);
        if (draft == null || draft.status != Ticket.TicketStatus.PARKED
                || !ticketNumberService.getTerminalId().equals(draft.terminalId)) {
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        ticketPrinterService.printParkedTicket(draft);
        return null;
    }

    /**
     * Lists the parked tickets of this register, oldest first.
     *
     * @return the parked tickets
     */
    public List<Ticket> listParked() {
        LOGGER.info("Entering method listParked");
        if (state.trainingMode) {
            LOGGER.info("Exiting method listParked (training)");
            return trainingParked();
        }
        LOGGER.info("Exiting method listParked");
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
        LOGGER.info("Entering method resume with ticketId: " + ticketId);
        if (state.trainingMode) {
            LOGGER.info("Exiting method resume (training)");
            return resumeTraining(ticketId);
        }
        if (!state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method resume");
            return i18n.get("op.ticketCoursMettezAttente");
        }
        Ticket draft = Ticket.findById(ticketId);
        if (draft == null
                || draft.status != Ticket.TicketStatus.PARKED
                || !ticketNumberService.getTerminalId().equals(draft.terminalId)) {
            LOGGER.info("Exiting method resume");
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        draft.status = Ticket.TicketStatus.OPEN;
        // BO-06-06-01: the recall is dated and named, so the state of pending
        // tickets can tell a cart that came back from one still waiting.
        draft.resumedAt = java.time.LocalDateTime.now();
        draft.resumedTerminalId = ticketNumberService.getTerminalId();
        draft.persist();
        ticketRecoveryService.restoreDraft(draft);
        technicalEventService.log(TechnicalEvent.EventType.TICKET_RESUMED, draft.ticketNumber);
        // LC-04-01-03: the resumption is traced too, with what was resumed.
        parkedTicketArchiveService.record(draft, com.intermarche.pos.domain.sale.ParkedTicketRecord.Event.RESUMED,
                state.auth.operatorBadgeId);
        // BO-03-03-07: the resume ticket, where the store administers it. A
        // local resume keeps its number, so the document's two numbers are the
        // same one — it states both anyway, because it is the SAME document
        // that serves the inter-till resume.
        ticketPrinterService.printResumeTicket(draft, draft.ticketNumber);
        LOGGER.info("Exiting method resume");
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
        LOGGER.info("Entering method resumeByNumber with ticketNumber: " + ticketNumber);
        Ticket draft = Ticket.<Ticket>find(
                "ticketNumber = ?1 and terminalId = ?2 and status = ?3",
                ticketNumber, ticketNumberService.getTerminalId(),
                Ticket.TicketStatus.PARKED).firstResult();
        if (draft != null) {
            LOGGER.info("Exiting method resumeByNumber (local)");
            return resume(draft.id);
        }
        // EXP-46: not parked on THIS till. Offline, that is the end — the
        // degraded rule resumes only its own tickets. Connected, the store node
        // may hand over a ticket parked on ANOTHER till; the client reaches no
        // node offline (returns null) so the standalone lane is unchanged.
        Ticket foreign = interTillResumeClient.fetchParkedTicket(ticketNumber);
        if (foreign == null || foreign.status != Ticket.TicketStatus.PARKED) {
            LOGGER.info("Exiting method resumeByNumber");
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        LOGGER.info("Exiting method resumeByNumber (inter-caisses)");
        return resumeForeign(foreign);
    }

    /**
     * Resumes a ticket the store node handed over from another till (EXP-46,
     * connected mode): unlike {@link #resume(Long)} it does not check the
     * originating terminal — the node authorized the hand-over — and it re-homes
     * the ticket to this register. Refused while a cart is already in progress,
     * like every resume.
     *
     * @param foreign the parked ticket the node returned
     * @return null on success, or an error message shown to the cashier
     */
    @Transactional
    String resumeForeign(Ticket foreign) {
        LOGGER.info("Entering method resumeForeign");
        if (!state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method resumeForeign");
            return i18n.get("op.ticketCoursMettezAttente");
        }
        // BO-03-03-07: the number the parked paper carried, read BEFORE the
        // resume re-homes the ticket onto this register.
        String parkedNumber = foreign.ticketNumber;
        foreign.status = Ticket.TicketStatus.OPEN;
        // BO-06-06-01: the recalling lane is written BEFORE the re-homing
        // overwrites the parking one — after it, the two are the same.
        foreign.resumedAt = java.time.LocalDateTime.now();
        foreign.resumedTerminalId = ticketNumberService.getTerminalId();
        foreign.terminalId = ticketNumberService.getTerminalId();
        foreign.persist();
        ticketRecoveryService.restoreDraft(foreign);
        technicalEventService.log(TechnicalEvent.EventType.TICKET_RESUMED, foreign.ticketNumber);
        // LC-04-01-03: traced in THIS register's chain, which now holds the ticket.
        parkedTicketArchiveService.record(foreign, com.intermarche.pos.domain.sale.ParkedTicketRecord.Event.RESUMED,
                state.auth.operatorBadgeId);
        ticketPrinterService.printResumeTicket(foreign, parkedNumber);
        LOGGER.infof("Ticket repris inter-caisses ID: %d (%s)", foreign.id, foreign.ticketNumber);
        LOGGER.info("Exiting method resumeForeign");
        return null;
    }

    /**
     * Puts a TRAINING cart on hold (BO-06-06-01): school mode writes nothing
     * fiscal, so the cart waits in memory and a training row records the
     * parking — pushed to the store node, whose state of pending tickets lists
     * it with its school mode. One cart may wait at a time, as in a real sale.
     *
     * @return null on success, or an error message shown to the cashier
     */
    String parkTraining() {
        if (state.trainingParkedId != null) {
            return i18n.get("op.ticketDejaAttenteReprenez");
        }
        com.intermarche.pos.domain.people.Employee operator = state.auth.operatorId == null ? null
                : com.intermarche.pos.domain.people.Employee.findById(state.auth.operatorId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        com.intermarche.pos.domain.sale.TrainingTicket parked = new com.intermarche.pos.domain.sale.TrainingTicket();
        parked.terminalId = ticketNumberService.getTerminalId();
        parked.cashierLogin = operator == null ? null : operator.loginName;
        parked.issuedAt = now;
        parked.parkedAt = now;
        parked.parkedBy = state.auth.operatorBadgeId;
        parked.itemCount = state.ticket.items.size();
        parked.totalIncludingTax = state.ticket.getTotalAmount();
        parked.persist();
        syncOutboxService.enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TRAINING, parked.id);
        state.trainingParkedItems = new java.util.ArrayList<>(state.ticket.items);
        state.trainingParkedId = parked.id;
        state.trainingParkedAt = now;
        LOGGER.infof("Ticket de formation mis en attente (%d)", parked.id);
        state.clearTicket();
        state.touch();
        return null;
    }

    /**
     * Lists the training cart on hold, as the parked list shows it.
     *
     * @return the cart, as an unsaved ticket, or nothing when none waits
     */
    List<Ticket> trainingParked() {
        if (state.trainingParkedId == null) {
            return List.of();
        }
        Ticket shown = new Ticket();
        shown.id = state.trainingParkedId;
        shown.ticketNumber = i18n.get("parked.r21v-formation");
        shown.creationDate = state.trainingParkedAt;
        shown.itemCount = state.trainingParkedItems.size();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (com.intermarche.pos.ui.ticket.TicketState.TicketItem item : state.trainingParkedItems) {
            total = total.add(item.getTotalPrice());
        }
        shown.totalIncludingTax = total;
        return List.of(shown);
    }

    /**
     * Recalls the training cart on hold (BO-06-06-01): the cart comes back in
     * memory and the training row records the recall, pushed again.
     *
     * @param ticketId the training row's id
     * @return null on success, or an error message shown to the cashier
     */
    String resumeTraining(Long ticketId) {
        if (!state.ticket.items.isEmpty()) {
            return i18n.get("op.ticketCoursMettezAttente");
        }
        if (state.trainingParkedId == null || !state.trainingParkedId.equals(ticketId)) {
            return i18n.get("op.ticketAttenteIntrouvable");
        }
        com.intermarche.pos.domain.sale.TrainingTicket parked =
                com.intermarche.pos.domain.sale.TrainingTicket.findById(ticketId);
        if (parked != null) {
            parked.resumedAt = java.time.LocalDateTime.now();
            parked.resumedTerminalId = ticketNumberService.getTerminalId();
            parked.resumedBy = state.auth.operatorBadgeId;
            parked.persist();
            syncOutboxService.enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TRAINING, parked.id);
        }
        state.ticket.items.addAll(state.trainingParkedItems);
        state.ticket.recomputeTotal();
        state.trainingParkedItems = new java.util.ArrayList<>();
        state.trainingParkedId = null;
        state.trainingParkedAt = null;
        state.touch();
        return null;
    }
}
