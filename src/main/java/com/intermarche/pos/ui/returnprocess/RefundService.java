package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Customer refund flow: ticket search, per-line quantity selection, manager
 * endorsement and refund creation.
 * <p>
 * Phase 3 lot 4: the refund method is chosen by the cashier and persisted;
 * refunded quantities are capped per original line across every past refund
 * (double-refund guard, plus a ticket-level cap covering manual amounts); the
 * VAT of the refunded lines is restituted; the refund is attached to the
 * current cash session (cash refunds lower the theoretical drawer amount);
 * every method choice goes through a journaled manager endorsement.
 */
@ApplicationScoped
public class RefundService {

    private static final int EXPIRATION_DAYS = 30;
    private static final Logger LOG = Logger.getLogger(RefundService.class);

    /** Maximum amount encodable on a printed store voucher (4 cent digits). */
    private static final BigDecimal MAX_ENCODED_VOUCHER = new BigDecimal("99.99");

    @Inject
    EndorsementService endorsementService;

    @Inject
    TicketPrinterService ticketPrinterService;

    @Inject
    CashSessionService cashSessionService;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    HardwareService hardwareService;

    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Searches the closed tickets of the last 30 days matching the typed
     * number fragment (3 characters minimum).
     *
     * @param state the current POS state
     */
    public void searchTickets(PosState state) {
        String pattern = state.refund.searchPattern;

        if (pattern == null || pattern.trim().length() < 3) {
            state.refund.foundTickets.clear();
            state.touch();
            return;
        }

        LocalDateTime limit = LocalDateTime.now().minusDays(EXPIRATION_DAYS);

        List<Ticket> results = Ticket.find(
                "status = ?1 and lower(ticketNumber) like lower(?2) and creationDate > ?3",
                Ticket.TicketStatus.CLOSED,
                "%" + pattern + "%",
                limit
        ).list();

        LOG.info("Recherche Retour: Pattern=" + pattern + ", Résultats=" + results.size());

        state.refund.foundTickets = results;
        state.touch();
    }

    /**
     * Selects a ticket and resets the refund selection.
     *
     * @param state the current POS state
     * @param ticketId the database id of the ticket to refund
     */
    public void selectTicket(PosState state, Long ticketId) {
        Ticket t = Ticket.findById(ticketId);
        if (t != null) {
            state.refund.selectedTicket = t;
            state.refund.returnQuantities.clear();
            state.refund.detailPage = 0;
            state.refund.selectedLineId = null;
            state.refund.isEditingAmount = false;
            state.refund.manualTotalAmount = null;
        }
        state.touch();
    }

    /**
     * Toggles the selection of a ticket line for direct quantity typing.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void selectLine(PosState state, Long lineId) {
        if (lineId != null && lineId.equals(state.refund.selectedLineId)) {
            state.refund.selectedLineId = null;
        } else {
            state.refund.selectedLineId = lineId;
        }
        state.refund.isEditingAmount = false;
        state.touch();
    }

    /**
     * Switches the input to global-amount edition.
     *
     * @param state the current POS state
     */
    public void startAmountEdit(PosState state) {
        state.refund.selectedLineId = null;
        state.refund.isEditingAmount = true;
        state.touch();
    }

    /**
     * Applies a typed refund quantity on a line.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     * @param rawValue the typed quantity
     */
    public void submitLineQuantity(PosState state, Long lineId, String rawValue) {
        try {
            BigDecimal qty = new BigDecimal(rawValue.replace(",", "."));
            setReturnQuantity(state, lineId, qty);
        } catch (Exception e) { }
        state.refund.selectedLineId = null;
        state.touch();
    }

    /**
     * Applies a typed global refund amount (manager free amount).
     *
     * @param state the current POS state
     * @param rawValue the typed amount
     */
    public void submitManualAmount(PosState state, String rawValue) {
        try {
            BigDecimal amount = new BigDecimal(rawValue.replace(",", "."));
            state.refund.manualTotalAmount = amount;
        } catch (Exception e) {
            state.refund.manualTotalAmount = BigDecimal.ZERO;
        }
        state.refund.isEditingAmount = false;
        state.touch();
    }

    /**
     * Sets the refund quantity of a line, capped at the quantity still
     * refundable: sold quantity minus what previous refunds already returned
     * on this line (double-refund guard).
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     * @param quantity the requested refund quantity
     */
    public void setReturnQuantity(PosState state, Long lineId, BigDecimal quantity) {
        if (state.refund.selectedTicket == null) return;

        TicketLine line = state.refund.selectedTicket.lines.stream()
                .filter(l -> l.id.equals(lineId)).findFirst().orElse(null);

        if (line != null) {
            BigDecimal refundable = line.quantity.subtract(alreadyRefunded(lineId));
            if (refundable.signum() < 0) refundable = BigDecimal.ZERO;
            if (quantity.compareTo(BigDecimal.ZERO) < 0) quantity = BigDecimal.ZERO;
            if (quantity.compareTo(refundable) > 0) quantity = refundable;

            state.refund.returnQuantities.put(lineId, quantity);
            state.refund.manualTotalAmount = null;
        }
        state.touch();
    }

    /**
     * Increments the refund quantity of a line by one unit.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void incrementQty(PosState state, Long lineId) {
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.add(BigDecimal.ONE));
        state.touch();
    }

    /**
     * Decrements the refund quantity of a line by one unit.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void decrementQty(PosState state, Long lineId) {
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.subtract(BigDecimal.ONE));
        state.touch();
    }

    /**
     * Requests a manager endorsement for a refund with the chosen method; the
     * refund itself is performed by the endorsement dispatch on grant.
     *
     * @param state the current POS state
     * @param method the refund method chosen by the cashier
     */
    public void requestRefund(PosState state, Refund.RefundMethod method) {
        if (state.trainingMode) {
            state.refund.errorMessage = "RETOURS INDISPONIBLES EN FORMATION";
            state.touch();
            return;
        }
        if (state.refund.selectedTicket == null) return;
        if (state.refund.getTotalRefundAmount().signum() <= 0) {
            state.refund.errorMessage = "RIEN À REMBOURSER";
            state.touch();
            return;
        }
        state.refund.errorMessage = null;
        endorsementService.requestAuthorization(state,
                "REFUND_" + method.name() + "_" + state.refund.selectedTicket.id);
        state.touch();
    }

    /**
     * Performs the endorsed refund: re-validates the per-line caps and the
     * ticket-level cap inside the transaction, persists the refund (method,
     * VAT restitution, session attachment), applies the method side effects
     * (drawer for cash, printed store voucher, loyalty journal) and prints
     * the refund ticket.
     *
     * @param state the current POS state
     * @param method the endorsed refund method
     */
    @Transactional
    public void performRefund(PosState state, Refund.RefundMethod method) {
        Ticket original = state.refund.selectedTicket;
        if (original == null) return;

        Refund refund = new Refund();
        refund.refundNumber = ticketNumberService.nextRefundNumber();
        refund.originalTicketId = original.id;
        refund.creationDate = LocalDateTime.now();
        refund.refundMethod = method;
        refund.terminalId = ticketNumberService.getTerminalId();
        refund.session = cashSessionService.getOpenSession();
        refund.status = Refund.RefundStatus.CLOSED;

        VatBreakdown breakdown = new VatBreakdown();
        for (var entry : state.refund.returnQuantities.entrySet()) {
            Long lineId = entry.getKey();
            BigDecimal qty = entry.getValue();
            if (qty.signum() <= 0) continue;

            TicketLine orig = original.lines.stream()
                    .filter(l -> l.id.equals(lineId)).findFirst().orElse(null);
            if (orig == null) continue;

            // Transactional re-check of the double-refund cap
            BigDecimal refundable = orig.quantity.subtract(alreadyRefunded(lineId));
            if (qty.compareTo(refundable) > 0) {
                state.refund.errorMessage = "QUANTITÉ DÉJÀ REMBOURSÉE (" + orig.productLabel + ")";
                state.touch();
                throw new IllegalStateException("Double remboursement refusé sur la ligne " + lineId);
            }

            RefundLine rl = new RefundLine();
            rl.originalLineId = lineId;
            rl.productLabel = orig.productLabel;
            rl.quantity = qty;
            rl.price = orig.unitPrice;
            rl.vatRate = orig.vatRate;
            refund.lines.add(rl);
            breakdown.add(orig.vatRate, orig.unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP));
        }

        refund.totalAmount = state.refund.getTotalRefundAmount();
        if (!refund.lines.isEmpty() && state.refund.manualTotalAmount == null) {
            refund.totalExcludingTax = breakdown.getTotalExcludingTax();
            refund.totalVat = breakdown.getTotalVat();
        }

        // Ticket-level cap: past refunds plus this one never exceed the ticket
        BigDecimal alreadyRefundedTotal = totalRefundedFor(original.id);
        if (alreadyRefundedTotal.add(refund.totalAmount).compareTo(original.totalIncludingTax) > 0) {
            state.refund.errorMessage = "PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : "
                    + alreadyRefundedTotal.setScale(2, RoundingMode.HALF_UP).toPlainString() + " €)";
            state.touch();
            throw new IllegalStateException("Plafond de remboursement du ticket dépassé");
        }

        refund.persist();

        technicalEventService.log(TechnicalEvent.EventType.REFUND_CREATED,
                original.ticketNumber + " " + method.name() + " "
                        + refund.totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        syncOutboxService.enqueue(SyncOutbox.EntityType.REFUND, refund.id);

        applyMethodSideEffects(refund);
        ticketPrinterService.printRefund(refund.id);

        state.refund.clear();
        state.touch();
    }

    /**
     * Applies the side effects of the chosen refund method: drawer opening
     * for cash, printed store voucher (scannable as a payment voucher when
     * its amount is encodable), loyalty journal note until the real balance
     * arrives with the valuation engine.
     *
     * @param refund the persisted refund
     */
    private void applyMethodSideEffects(Refund refund) {
        switch (refund.refundMethod) {
            case CASH -> hardwareService.openDrawer();
            case VOUCHER -> ticketPrinterService.printRefundVoucher(refund,
                    refund.totalAmount.compareTo(MAX_ENCODED_VOUCHER) <= 0);
            case LOYALTY -> LOG.infof(
                    "Cagnotte créditée de %s (réelle avec le valorisateur, phase 7)",
                    refund.totalAmount.toPlainString());
            case CARD -> LOG.info("Remboursement carte à traiter sur le TPE (monétique hors périmètre)");
        }
    }

    /**
     * Sums the quantities already refunded on an original ticket line, across
     * every past refund.
     *
     * @param originalLineId the database id of the original line
     * @return the already refunded quantity (ZERO when none)
     */
    private BigDecimal alreadyRefunded(Long originalLineId) {
        return RefundLine.<RefundLine>list("originalLineId", originalLineId).stream()
                .map(l -> l.quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Sums the tax-included totals already refunded on a ticket.
     *
     * @param ticketId the database id of the original ticket
     * @return the already refunded total (ZERO when none)
     */
    private BigDecimal totalRefundedFor(Long ticketId) {
        return Refund.<Refund>list("originalTicketId", ticketId).stream()
                .map(r -> r.totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
