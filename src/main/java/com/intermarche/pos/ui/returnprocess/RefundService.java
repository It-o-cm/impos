package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.*;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
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
 * <p>
 * The guards run TWICE on purpose: once at staging (fast feedback on the
 * screen) and again INSIDE the creation transaction (the authoritative
 * check — a stale screen cannot oversell a cap). Failing the
 * in-transaction check rolls everything back with the message surfaced on
 * the refund screen. Phase 6: creation is blocked in training (a refund is
 * a real document); phase 7 will replace the loyalty journal note with a
 * real balance credit.
 */
@ApplicationScoped
public class RefundService {

    private static final int EXPIRATION_DAYS = 30;

    /**
     * The validity span of an avoir (credit note) from its issue, in days
     * (LC-05-06-07). A store-wide policy; a back-office parameter could later
     * override it.
     */
    private static final int VOUCHER_VALIDITY_DAYS = 365;
    private static final Logger LOGGER = Logger.getLogger(RefundService.class);

    /** Maximum amount encodable on a printed store voucher (4 cent digits). */
    private static final BigDecimal MAX_ENCODED_VOUCHER = new BigDecimal("99.99");

    @Inject
    EndorsementService endorsementService;

    @Inject
    TicketPrinterService ticketPrinterService;

    /** The conditional-printing rule — carries the forced credit slip (LC-08-03-10). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;

    /** Loyalty return-event outbox (imfid lot 3). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.sync.register.FidEventOutboxService fidEventOutboxService;

    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

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

    /** The shared ticket search engine (EXP-47), common to return and duplicata. */
    @Inject
    com.intermarche.pos.ui.ticket.TicketSearchService ticketSearchService;

    /**
     * Searches the closed tickets of the last 30 days matching the typed
     * number fragment (3 characters minimum).
     *
     * @param state the current POS state
     */
    /**
     * Whether a scanned code names a CLOSED sale ticket in this register's
     * database — the test for routing a scanned ticket number to the return
     * (EXP-37). A foreign-store number is not in this database, so it answers
     * false and the store boundary is honoured; another till and another day
     * (both local) answer true.
     *
     * @param ticketNumber the scanned ticket number
     * @return true when a CLOSED ticket carries that number
     */
    public boolean isReturnableTicket(String ticketNumber) {
        LOGGER.info("Entering method isReturnableTicket with ticketNumber: " + ticketNumber);
        if (ticketNumber == null) {
            LOGGER.info("Exiting method isReturnableTicket");
            return false;
        }
        Ticket found = Ticket.find("ticketNumber = ?1 and status = ?2",
                ticketNumber, Ticket.TicketStatus.CLOSED).firstResult();
        LOGGER.info("Exiting method isReturnableTicket");
        return found != null;
    }

    public void searchTickets(PosState state) {
        LOGGER.info("Entering method searchTickets with state: " + state);
        // EXP-47: the return search now runs through the ONE ticket search engine,
        // so its single field accepts a number, a date or an amount — the same
        // engine the duplicata search uses.
        List<Ticket> results = ticketSearchService.searchByPattern(state.refund.searchPattern);
        state.refund.foundTickets = results;
        state.touch();
        LOGGER.info("Exiting method searchTickets");
    }

    /**
     * Selects a ticket and resets the refund selection.
     *
     * @param state the current POS state
     * @param ticketId the database id of the ticket to refund
     */
    public void selectTicket(PosState state, Long ticketId) {
        LOGGER.info("Entering method selectTicket with state: " + state + ", ticketId: " + ticketId);
        Ticket t = Ticket.findById(ticketId);
        if (t != null) {
            state.refund.selectedTicket = t;
            // EXP-37: mark the lines already fully returned by past refunds so the
            // detail greys them and refuses their selection.
            state.refund.fullyReturnedLineIds.clear();
            if (t.lines != null) {
                for (TicketLine line : t.lines) {
                    if (!line.cancelled
                            && alreadyRefunded(line.id).compareTo(line.quantity) >= 0) {
                        state.refund.fullyReturnedLineIds.add(line.id);
                    }
                }
            }
            state.refund.returnQuantities.clear();
            state.refund.detailPage = 0;
            state.refund.selectedLineId = null;
            state.refund.isEditingAmount = false;
            state.refund.manualTotalAmount = null;
            state.refund.returnReason = null;
            state.refund.creditAccountNumber = null;
            state.refund.creditAccountName = null;
        }
        state.touch();
        LOGGER.info("Exiting method selectTicket");
    }

    /**
     * Selects the in-account customer a credit refund (LC-05-06-05) will lower
     * the balance of. The customer is looked up by account number; an unknown
     * or blank number clears the selection and surfaces a refusal.
     *
     * @param state the current POS state
     * @param accountNumber the account number the cashier scanned or typed
     */
    public void selectCreditAccount(PosState state, String accountNumber) {
        LOGGER.info("Entering method selectCreditAccount with state: " + state + ", accountNumber: " + accountNumber);
        String typed = accountNumber == null ? "" : accountNumber.trim();
        if (typed.isEmpty()) {
            state.refund.creditAccountNumber = null;
            state.refund.creditAccountName = null;
            state.refund.errorMessage = "CLIENT INTROUVABLE";
            state.touch();
            LOGGER.info("Exiting method selectCreditAccount");
            return;
        }
        com.intermarche.pos.domain.payment.AccountCustomer account =
                com.intermarche.pos.domain.payment.AccountCustomer
                        .<com.intermarche.pos.domain.payment.AccountCustomer>find("accountNumber", typed)
                        .firstResult();
        if (account == null) {
            state.refund.creditAccountNumber = null;
            state.refund.creditAccountName = null;
            state.refund.errorMessage = "CLIENT INTROUVABLE";
            state.touch();
            LOGGER.info("Exiting method selectCreditAccount");
            return;
        }
        state.refund.creditAccountNumber = account.accountNumber;
        state.refund.creditAccountName = account.getDisplayName();
        state.refund.errorMessage = null;
        state.touch();
        LOGGER.info("Exiting method selectCreditAccount");
    }

    /**
     * Records the return reason chosen by the cashier (LC-05-05-01). The reason
     * is accepted only when it is non-blank AND belongs to the administered
     * list of {@link com.intermarche.pos.service.PosSettingsService#returnReasons()};
     * anything else (blank, null or an off-list value) clears the reason. A
     * single reason applies to the whole return, all returned lines at once
     * (the group assignment of LC-05-05-02).
     *
     * @param state the current POS state
     * @param reason the reason the cashier tapped, possibly null or off-list
     */
    public void selectReason(PosState state, String reason) {
        LOGGER.info("Entering method selectReason with state: " + state + ", reason: " + reason);
        if (reason != null && !reason.isBlank() && posSettingsService.returnReasons().contains(reason)) {
            state.refund.returnReason = reason;
        } else {
            state.refund.returnReason = null;
        }
        state.touch();
        LOGGER.info("Exiting method selectReason");
    }

    /**
     * Toggles the selection of a ticket line for direct quantity typing.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void selectLine(PosState state, Long lineId) {
        LOGGER.info("Entering method selectLine with state: " + state + ", lineId: " + lineId);
        if (lineId != null && lineId.equals(state.refund.selectedLineId)) {
            state.refund.selectedLineId = null;
        } else {
            state.refund.selectedLineId = lineId;
        }
        state.refund.isEditingAmount = false;
        state.touch();
        LOGGER.info("Exiting method selectLine");
    }

    /**
     * Switches the input to global-amount edition.
     *
     * @param state the current POS state
     */
    public void startAmountEdit(PosState state) {
        LOGGER.info("Entering method startAmountEdit with state: " + state);
        state.refund.selectedLineId = null;
        state.refund.isEditingAmount = true;
        state.touch();
        LOGGER.info("Exiting method startAmountEdit");
    }

    /**
     * Applies a typed refund quantity on a line.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     * @param rawValue the typed quantity
     */
    public void submitLineQuantity(PosState state, Long lineId, String rawValue) {
        LOGGER.info("Entering method submitLineQuantity with state: " + state + ", lineId: " + lineId + ", rawValue: " + rawValue);
        try {
            BigDecimal qty = new BigDecimal(rawValue.replace(",", "."));
            setReturnQuantity(state, lineId, qty);
        } catch (Exception e) { }
        state.refund.selectedLineId = null;
        state.touch();
        LOGGER.info("Exiting method submitLineQuantity");
    }

    /**
     * Applies a typed global refund amount (manager free amount).
     *
     * @param state the current POS state
     * @param rawValue the typed amount
     */
    public void submitManualAmount(PosState state, String rawValue) {
        LOGGER.info("Entering method submitManualAmount with state: " + state + ", rawValue: " + rawValue);
        try {
            BigDecimal amount = new BigDecimal(rawValue.replace(",", "."));
            state.refund.manualTotalAmount = amount;
        } catch (Exception e) {
            state.refund.manualTotalAmount = BigDecimal.ZERO;
        }
        state.refund.isEditingAmount = false;
        state.touch();
        LOGGER.info("Exiting method submitManualAmount");
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
        LOGGER.info("Entering method setReturnQuantity with state: " + state + ", lineId: " + lineId + ", quantity: " + quantity);
        if (state.refund.selectedTicket == null) { LOGGER.info("Exiting method setReturnQuantity"); return; }

        TicketLine line = state.refund.selectedTicket.lines.stream()
                .filter(l -> l.id.equals(lineId) && !l.cancelled).findFirst().orElse(null);

        if (line != null) {
            // Gift cards are non-returnable: refunding a sold card while the
            // registry instrument stays ACTIVE would double the value
            // (phase: credit notes & gift cards).
            if (line.ean != null) {
                com.intermarche.pos.domain.catalog.Product product =
                        com.intermarche.pos.domain.catalog.Product.find("ean", line.ean).firstResult();
                if (product != null && product.giftCardAmount != null) {
                    state.refund.errorMessage = "RETOUR INTERDIT SUR CARTE CADEAU";
                    state.touch();
                    LOGGER.info("Exiting method setReturnQuantity");
                    return;
                }
            }
            BigDecimal refundable = line.quantity.subtract(alreadyRefunded(lineId));
            if (refundable.signum() < 0) refundable = BigDecimal.ZERO;
            if (quantity.compareTo(BigDecimal.ZERO) < 0) quantity = BigDecimal.ZERO;
            if (quantity.compareTo(refundable) > 0) quantity = refundable;

            state.refund.returnQuantities.put(lineId, quantity);
            state.refund.manualTotalAmount = null;
        }
        state.touch();
        LOGGER.info("Exiting method setReturnQuantity");
    }

    /**
     * Increments the refund quantity of a line by one unit.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void incrementQty(PosState state, Long lineId) {
        LOGGER.info("Entering method incrementQty with state: " + state + ", lineId: " + lineId);
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.add(BigDecimal.ONE));
        state.touch();
        LOGGER.info("Exiting method incrementQty");
    }

    /**
     * Decrements the refund quantity of a line by one unit.
     *
     * @param state the current POS state
     * @param lineId the database id of the line
     */
    public void decrementQty(PosState state, Long lineId) {
        LOGGER.info("Entering method decrementQty with state: " + state + ", lineId: " + lineId);
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.subtract(BigDecimal.ONE));
        state.touch();
        LOGGER.info("Exiting method decrementQty");
    }

    /**
     * Requests a manager endorsement for a refund with the chosen method; the
     * refund itself is performed by the endorsement dispatch on grant.
     *
     * @param state the current POS state
     * @param method the refund method chosen by the cashier
     */
    public void requestRefund(PosState state, Refund.RefundMethod method) {
        LOGGER.info("Entering method requestRefund with state: " + state + ", method: " + method);
        if (state.trainingMode) {
            state.refund.errorMessage = "RETOURS INDISPONIBLES EN FORMATION";
            state.touch();
            LOGGER.info("Exiting method requestRefund");
            return;
        }
        if (state.refund.selectedTicket == null) { LOGGER.info("Exiting method requestRefund"); return; }
        if (state.refund.getTotalRefundAmount().signum() <= 0) {
            state.refund.errorMessage = "RIEN À REMBOURSER";
            state.touch();
            LOGGER.info("Exiting method requestRefund");
            return;
        }
        // BO-03-02-15: a tender the back office does not allow for refunds is
        // refused here, before the endorsement is even asked for — a manager
        // must not be made to authorize something the store forbids outright.
        if (!isRefundAllowed(method)) {
            state.refund.errorMessage = "MOYEN NON AUTORISE AU REMBOURSEMENT";
            state.touch();
            LOGGER.info("Exiting method requestRefund");
            return;
        }
        // LC-05-06-05: a credit refund needs an in-account customer to credit;
        // without one, refuse before any endorsement is asked.
        if (method == Refund.RefundMethod.CREDIT && state.refund.creditAccountNumber == null) {
            state.refund.errorMessage = "AUCUN CLIENT EN COMPTE SÉLECTIONNÉ";
            state.touch();
            LOGGER.info("Exiting method requestRefund");
            return;
        }
        state.refund.errorMessage = null;
        if (endorsementService.gestureAuthorized(state, "refund")) {
            // EXP-32: administered without endorsement, connected supervisor, or an
            // open supervisor session — perform the refund directly, ceremony
            // skipped. A guard refusal leaves its message on the refund screen.
            try {
                performRefund(state, method);
            } catch (IllegalStateException e) {
                // message already set on the refund screen by the guard
            }
        } else {
            endorsementService.requestAuthorization(state,
                    "REFUND_" + method.name() + "_" + state.refund.selectedTicket.id);
        }
        state.touch();
        LOGGER.info("Exiting method requestRefund");
    }

    /**
     * Tells whether the back office allows a refund on the tender behind a
     * refund method (BO-03-02-15).
     *
     * <p>A method whose tender no row administers stays allowed: the register's
     * four refund methods are what a store gets before it administers anything.
     *
     * @param method the refund method the cashier chose
     * @return true unless an administered row forbids that tender for refunds
     */
    public boolean isRefundAllowed(Refund.RefundMethod method) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                com.intermarche.pos.domain.payment.TenderDefinition
                        .findByCode(refundTenderKey(method));
        return tender == null || tender.refundAllowed;
    }

    /**
     * Returns the settlement key a refund method hands the money back on
     * (BO-03-02-15).
     *
     * @param method the refund method, or null
     * @return the settlement key, or null when the method is unknown
     */
    private String refundTenderKey(Refund.RefundMethod method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case CASH -> "CASH";
            case CARD -> "CARD";
            case VOUCHER -> "VOUCHER";
            case LOYALTY -> "FIDELITY";
            case CREDIT -> "CREDIT";
        };
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
        LOGGER.info("Entering method performRefund with state: " + state + ", method: " + method);
        Ticket original = state.refund.selectedTicket;
        if (original == null) { LOGGER.info("Exiting method performRefund"); return; }

        // Loyalty refund guard (imfid spec §28): crediting a loyalty balance
        // requires the ORIGIN ticket's card. Checked HERE, before the refund
        // is built — a refusal must leave NOTHING behind (no persisted
        // refund, no outbox row): the cashier simply picks another method.
        if (method == Refund.RefundMethod.LOYALTY && original.fidelityCard == null) {
            state.refund.errorMessage = "AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE";
            state.touch();
            LOGGER.info("Exiting method performRefund");
            return;
        }

        // LC-05-06-05 credit refund guard: crediting an in-account customer
        // requires that customer to be selected. Checked HERE, before the
        // refund is built — a refusal leaves NOTHING behind.
        if (method == Refund.RefundMethod.CREDIT && state.refund.creditAccountNumber == null) {
            state.refund.errorMessage = "AUCUN CLIENT EN COMPTE SÉLECTIONNÉ";
            state.touch();
            LOGGER.info("Exiting method performRefund");
            return;
        }

        Refund refund = new Refund();
        refund.refundNumber = ticketNumberService.nextRefundNumber();
        refund.originalTicketId = original.id;
        refund.creationDate = LocalDateTime.now();
        refund.refundMethod = method;
        refund.terminalId = ticketNumberService.getTerminalId();
        refund.session = cashSessionService.getOpenSession();
        refund.status = Refund.RefundStatus.CLOSED;
        // LC-05-05-01/02: the chosen reason (already validated against the
        // administered list) is carried on the refund so it reaches the store
        // reports; it applies to the whole return.
        refund.returnReason = state.refund.returnReason;
        // LC-05-06-05: name the credited account on the refund (only for a
        // credit refund) so the refund record is exploitable in the store's
        // account statements.
        if (method == Refund.RefundMethod.CREDIT) {
            refund.creditAccountNumber = state.refund.creditAccountNumber;
        }

        VatBreakdown breakdown = new VatBreakdown();
        for (var entry : state.refund.returnQuantities.entrySet()) {
            Long lineId = entry.getKey();
            BigDecimal qty = entry.getValue();
            if (qty.signum() <= 0) continue;

            // A cancelled article (lot C4) was never sold: it can never back a
            // refund line, even against a hand-posted line id.
            TicketLine orig = original.lines.stream()
                    .filter(l -> l.id.equals(lineId) && !l.cancelled).findFirst().orElse(null);
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

        // LC-05-05-01: journal the reason with the refund so it is exploitable
        // in the back-office reports; appended only when a reason was chosen.
        String reasonSuffix = (refund.returnReason != null && !refund.returnReason.isBlank())
                ? " MOTIF:" + refund.returnReason : "";
        technicalEventService.log(TechnicalEvent.EventType.REFUND_CREATED,
                original.ticketNumber + " " + method.name() + " "
                        + refund.totalAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + reasonSuffix);
        syncOutboxService.enqueue(SyncOutbox.EntityType.REFUND, refund.id);

        // Loyalty fiscal event (imfid spec §7): a return on a card-bearing
        // origin ticket feeds the RETURN_DEBIT recomputation — enqueued in
        // THIS transaction (the event exists iff the refund committed), with
        // the ORIGIN couple's line ids (the lineUid echoed by /valuation).
        Ticket originForFid = original;
        if (originForFid.fidelityCard != null) {
            StringBuilder fidLines = new StringBuilder("[");
            boolean firstFidLine = true;
            for (RefundLine refundLine : refund.lines) {
                com.intermarche.pos.domain.sale.TicketLine originLine =
                        com.intermarche.pos.domain.sale.TicketLine.findById(refundLine.originalLineId);
                if (originLine == null || originLine.lineUid == null) continue;
                if (!firstFidLine) fidLines.append(',');
                firstFidLine = false;
                fidLines.append("{\"lineId\":\"").append(originLine.lineUid)
                        .append("\",\"quantity\":").append(refundLine.quantity.toPlainString())
                        .append('}');
            }
            fidLines.append(']');
            java.time.LocalDate fidFiscalDate = java.time.LocalDate.now();
            String originRef = fidFiscalDate.getYear() + "-" + originForFid.ticketNumber;
            // The voluntary loyalty refund travels with the return event
            // (spec §28): the ingestion creates the REFUND_CREDIT, capped
            // POS-side to the refund amount by construction.
            String refundToCard = refund.refundMethod == Refund.RefundMethod.LOYALTY
                    ? ",\"refundToCard\":{\"card\":\"" + originForFid.fidelityCard
                            + "\",\"amount\":" + refund.totalAmount.toPlainString() + "}"
                    : "";
            String fidPayload = "{\"returnTicketRef\":\"" + originRef + "-R" + refund.id + "\","
                    + "\"originTicketRef\":\"" + originRef + "\","
                    + "\"fiscalDate\":\"" + fidFiscalDate + "\","
                    + "\"lines\":" + fidLines
                    + refundToCard + "}";
            fidEventOutboxService.enqueue(
                    com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN, fidPayload);
        }

        applyMethodSideEffects(refund);
        ticketPrinterService.printRefund(refund.id);

        // LC-05-06-01: a PARTIAL refund (a hand-keyed amount smaller than what
        // the ticket still has to give back) keeps the ticket selected and
        // announces the remaining, so the cashier hands the rest back on
        // another mean — the split of one return across several payment means,
        // leg by leg. A full or line-based refund clears the screen as before.
        BigDecimal remaining = original.totalIncludingTax
                .subtract(alreadyRefundedTotal).subtract(refund.totalAmount);
        if (state.refund.manualTotalAmount != null && remaining.signum() > 0) {
            state.refund.returnQuantities.clear();
            state.refund.manualTotalAmount = null;
            state.refund.selectedLineId = null;
            state.refund.isEditingAmount = false;
            state.refund.creditAccountNumber = null;
            state.refund.creditAccountName = null;
            state.refund.errorMessage = "REMBOURSEMENT PARTIEL - RESTE "
                    + remaining.setScale(2, RoundingMode.HALF_UP).toPlainString() + " €";
        } else {
            state.refund.clear();
        }
        state.touch();
        LOGGER.info("Exiting method performRefund");
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
            case VOUCHER -> {
                // The credit note is a REGISTRY instrument: persisted first,
                // numbered from its own row id (pure identifier — the
                // registry stays authoritative for the balance), then
                // printed with its scannable number (phase: credit notes &
                // gift cards). Any amount is now supported: the historical
                // 99,99 € encoded-number cap no longer applies.
                com.intermarche.pos.domain.payment.StoredValue note =
                        new com.intermarche.pos.domain.payment.StoredValue();
                note.kind = com.intermarche.pos.domain.payment.StoredValue.Kind.CREDIT_NOTE;
                note.initialAmount = refund.totalAmount;
                note.balance = refund.totalAmount;
                note.issuedAt = java.time.LocalDateTime.now();
                // LC-05-06-07: the avoir carries an end of validity, stored on
                // the instrument and printed on the AVOIR ticket.
                note.validUntil = note.issuedAt.plusDays(VOUCHER_VALIDITY_DAYS);
                note.issuingRefundId = refund.id;
                // The number is derived from the generated id, but the column
                // is NOT NULL and only 20 characters wide: the INSERT carries
                // a SHORT unique placeholder (a base-36 random, 14 chars at
                // most), and the definitive number replaces it once the id
                // exists.
                note.number = "T" + Long.toUnsignedString(
                        java.util.UUID.randomUUID().getMostSignificantBits(), 36);
                note.persistAndFlush();
                note.number = com.intermarche.pos.domain.payment.StoredValue.CREDIT_NOTE_PREFIX
                        + String.format("%012d", note.id);
                ticketPrinterService.printRefundVoucher(refund, note.number, note.validUntil);
            }
            case CREDIT -> {
                // LC-05-06-05: the refund lowers what the in-account customer
                // owes the store. The account is credited by the refund amount
                // (its outstanding balance goes down, possibly into an advance
                // in the customer's favour). The account balance is otherwise
                // owned by the nightly referential pull, so this POS-side credit
                // is corrected at the next pull — a known, accepted limit.
                com.intermarche.pos.domain.payment.AccountCustomer account =
                        com.intermarche.pos.domain.payment.AccountCustomer
                                .<com.intermarche.pos.domain.payment.AccountCustomer>find(
                                        "accountNumber", refund.creditAccountNumber)
                                .firstResult();
                if (account != null) {
                    BigDecimal balance = account.creditBalance == null
                            ? BigDecimal.ZERO : account.creditBalance;
                    account.creditBalance = balance.subtract(refund.totalAmount);
                    account.persist();
                }
            }
            case LOYALTY -> {
                // The credit itself is born at imfid's ingestion of the
                // ticket-return event (REFUND_CREDIT); the till hands the
                // customer a printed proof and announces it.
                hardwareService.displayMessage(String.format("CREDIT FIDELITE %s E",
                        refund.totalAmount.toPlainString().replace('.', ',')));
                ticketPrinterService.printLoyaltyCredit(refund.totalAmount);
            }
            case CARD -> {
                LOGGER.info("Remboursement carte à traiter sur le TPE (monétique hors périmètre)");
                // LC-08-03-10: a card refund is a "credit" card transaction —
                // its slip is printed on its own when the back office forces it.
                if (printPolicy.isCreditCardReceiptForced()) {
                    ticketPrinterService.printCardCreditReceipt(refund);
                }
            }
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
