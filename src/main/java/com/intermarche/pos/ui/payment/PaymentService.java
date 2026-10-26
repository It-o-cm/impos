package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Payment orchestration: draft ticket creation, registration of the various
 * payment methods, hardware display and transaction finalization.
 * <p>
 * All monetary amounts are {@link BigDecimal} (phase 0). Completion is reached
 * when the remaining due, rounded to 2 decimals, is zero or below — this
 * replaces the previous double epsilon comparison.
 * <p>
 * One money path for everything: every method funnels into
 * {@code handlePaymentWithChange} (cap at the remaining due where the
 * method demands it, change on overpayment for cash-like methods,
 * persistence of the entry on the draft, completion check) — a new payment
 * method is a factory subclass plus a thin wrapper here, never new money
 * math. Per-method drawer rules are deliberate: cash, cheque and meal
 * vouchers open it (something physical goes in), card and loyalty never do,
 * and training keeps it shut everywhere. {@code processCard} parks the
 * amount and hands the transaction to the {@code PaymentTerminalClient}
 * port: the CardPayment entity only exists after the terminal's accept
 * decision, whatever the implementation behind the port.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOGGER = Logger.getLogger(PaymentService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** Sends the training sales up to the back office's journal (LC-13-05-06). */
    @Inject
    TrainingTicketService trainingTicketService;

    /**
     * The payment terminal port (virtual simulator, auto-accept or Verifone
     * skeleton — selected by {@code pos.tpe.mode}); the card path only ever
     * talks to this interface.
     */
    @Inject
    com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient terminal;

    @Inject
    HardwareService hardwareService;

    /** The cheque reader, reached through the hardware bridge. */
    @Inject
    com.intermarche.pos.ui.hardware.ChequeReadingService chequeReadingService;

    /** The cash changer, reached through the hardware bridge (LC-07-15). */
    @Inject
    com.intermarche.pos.ui.hardware.CashChangerService cashChangerService;

    /** The Portuguese fiscal documents a closed sale is stated on (LC-08-01-05). */
    @Inject
    com.intermarche.pos.ui.invoice.PortugueseFiscalService portugueseFiscalService;

    /** The social purchase card and the attribute of the lines it may pay (LC-08-01-06). */
    private static final com.intermarche.pos.domain.catalog.attribute.RestrictedTender SOCIAL_CARD =
            new com.intermarche.pos.domain.catalog.attribute.RestrictedTender("CAS",
                    com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE, "");

    /** Records the automatic transfer of the cash changer's cash to the safe (LC-07-15-04). */
    @Inject
    com.intermarche.pos.service.CashMovementService cashMovementService;

    /** The open session the transfer to the safe belongs to (LC-07-15-04). */
    @Inject
    com.intermarche.pos.service.CashSessionService cashSessionService;

    /** The back-office parameters (drawer-open rules — BO-10-02-12). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * The register's own identity, for the cheque endorsement tokens
     * (BO-03-02-43): a cheque back saying WHICH till took it is what a bank
     * return is traced with.
     */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /**
     * The point-of-sale number, for the cheque endorsement tokens
     * ({@code BO-03-02-43}), absent on a register that has not been
     * commissioned into the organisation tree.
     *
     * <p>An {@link java.util.Optional} and NOT a {@code String} carrying an
     * empty default: SmallRye converts an empty default to null, and a
     * non-optional injection point then fails the startup validation of the
     * runtime configuration — the register would refuse to boot for the want
     * of a number it does not need. The store node reads the same property the
     * same way.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.pdv.number")
    java.util.Optional<String> pdvNumber;

    /** The administered tender rules — ceilings, change, drawer (BO-03-02). */
    @Inject
    TenderRulesService tenderRulesService;

    /** The supplier gift-card issuer registry seam (EXP-66). */
    @Inject
    GiftCardIssuerClient giftCardIssuerClient;

    /** The payment hub, spoken in Nexo Retailer (LC-07-11-01). */
    @Inject
    NexoHubClient nexoHubClient;

    /** The dematerialised articles of the sale and their activation tickets (LC-02-09-03). */
    @Inject
    com.intermarche.pos.ui.ticket.DematerialisedService dematerialisedService;

    /** The Resto Flash mobile meal-voucher seam (EXP-67). */
    @Inject
    RestoFlashClient restoFlashClient;

    /** The cheque-guarantee service asked before a cheque is taken (BO-03-02-24). */
    @Inject
    ChequeGuaranteeClient chequeGuaranteeClient;

    /** The SOGEC manufacturer-coupon compensation circuit (EXP-58). */
    @Inject
    SogecCompensationService sogecCompensationService;

    /** Checks the supervisor credential and journals the decision (BO-03-02-10). */
    @Inject
    com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The action code journalled when a supervisor passes an administered bound. */
    private static final String TENDER_OVERRIDE_ACTION = "DEPASSEMENT REGLE MODE DE REGLEMENT";

    /**
     * The settlement key whose change is handed over as a credit note
     * (BO-03-02-16) — the register's voucher tender, the one the stored-value
     * registry already numbers and redeems.
     */
    private static final String CREDIT_NOTE_TENDER = "VOUCHER";

    @Inject
    TicketPersistenceService ticketPersistenceService;

    /**
     * Derives and enqueues the campaign consumption of a finalized sale
     * (campaigns &amp; budgets spec §3).
     */
    @Inject
    com.intermarche.pos.service.sync.ConsumptionService consumptionService;

    /**
     * Prints the gift-card vouchers issued by a closed sale (phase: credit
     * notes & gift cards).
     */
    @Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /** The conditional-printing rule (LC-08-03). */
    @Inject
    com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;

    /**
     * The seam to the store node's order store: tells the node an order
     * recalled and settled at this till is closed (LC-06-03-07).
     */
    @Inject
    com.intermarche.pos.ui.recall.BackOfficeDocumentClient documentClient;

    /** Emits the document the settlement calls for, at the closing (LC-08-04-16). */
    @Inject
    com.intermarche.pos.ui.invoice.InvoiceService invoiceService;

    /** Prints the commercial management tool's document after its settlement (BO-10-01-26). */
    @Inject
    com.intermarche.pos.ui.recall.ChannelDocumentPrinter channelDocumentPrinter;

    /**
     * Technical EAN of the solidarity-rounding line (parameterized).
     * <p>
     * A register-generated line is still a line: every line carries an EAN the
     * valuation engine can resolve, because the engine prices the WHOLE ticket
     * and a line without an EAN does not exist.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.ean.donation")
    String donationEan;

    /** Loyalty lease lifecycle and event composition (imfid lots 2-3). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.fidelity.FidelityService fidelityService;

    /** Loyalty fiscal-event outbox (imfid lot 3). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.sync.register.FidEventOutboxService fidEventOutboxService;

    @Inject
    ValuationService valuationService;

    @Inject
    ValuationReconciler valuationReconciler;

    /** The survey trigger of the transaction's end (LC-03-07-02). */
    @Inject
    com.intermarche.pos.ui.supervision.SurveyTriggerService surveyTriggerService;

    /** French display format for amounts on the customer display. */
    private final DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

    /** How the endorsement dates a cheque. */
    private static final java.time.format.DateTimeFormatter ENDORSEMENT_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    // --------------------------------------------------
    // 1. INITIALIZATION
    // --------------------------------------------------

    /**
     * Initializes the payment screen: shows the total on the customer display
     * and runs a final draft synchronization. The draft normally exists since
     * the first article (lot 2); this sync picks up any late change (fidelity
     * card scanned after the last article) and recreates the draft defensively
     * if it is missing.
     *
     * @param state the current POS state
     */
    public void initPayment(PosState state) {
        LOGGER.info("Entering method initPayment with state: " + state);
        // Single-writer discipline: draft-writing gestures serialize on the
        // shared state — the pay screen reloads itself on every version bump
        // and re-enters initPayment (draft sync + revaluation) concurrently
        // with a payment registration or the terminal callback, which made
        // two transactions race on the same draft row (optimistic-lock).
        synchronized (state) {
            hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
            state.payment.paymentInProgress = true;
            // LC-07-03-01: the rounding step is read once, at payment entry, and
            // held on the state — the screen, the customer display and the printer
            // all ask for the rounded amount many times per second.
            state.cashRoundingStepCents = posSettingsService.cashRoundingStepCents();

            Long ticketId = ticketPersistenceService.syncDraft(state);
            if (ticketId == null && !state.trainingMode) {
                LOGGER.error("Impossible de créer/synchroniser le ticket (panier vide ou Store/Cashier manquant)");
            }
            // BO-06-06-02: the scanning ends and the payment begins here.
            ticketPersistenceService.markPaymentStarted(ticketId);

            // Phase 7 lot 4: final revaluation at payment entry — the cart was
            // already revalued after each mutation, this fixes the figure the
            // customer pays (fresh call, circuit permitting) and refreshes the
            // meal-voucher base and upsell hints.
            if (!state.trainingMode) {
                valuationService.revalueForPayment(state);
                hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
            } else {
                state.payment.valuationStatus = "LOCAL";
            }
        }
        LOGGER.info("Exiting method initPayment");
    }

    /**
     * Acknowledges to the NEXO Retailer server each prepaid-card payment it
     * validated, the sale being closed (BO-11-03-04): the validation took the
     * money, the acknowledgement makes it final. Only where the store
     * administers it and never in training; a server that does not answer is
     * logged — the sale is closed and its settlement was already validated.
     *
     * @param state the current POS state
     * @param ticketId the closed ticket's id
     */
    void acknowledgeHubPayments(PosState state, Long ticketId) {
        if (!posSettingsService.nexoPaymentAcknowledgement() || state.trainingMode) {
            return;
        }
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            if (entry.hubReference == null) {
                continue;
            }
            NexoHubClient.HubResult result = nexoHubClient.acknowledge(ticketId + "-ACK",
                    entry.hubReference, entry.amount);
            if (!result.success()) {
                LOGGER.warnf("Acquittement NEXO du paiement %s non abouti (%s)", entry.hubReference,
                        result.errorCondition());
            }
        }
    }

    /**
     * Toggles the solidarity round-up: adds a zero-VAT donation line raising
     * the ticket total to the administered step (LC-07-17-02), or removes it
     * when already present. A nonpositive suggested donation adds nothing.
     *
     * @param state the current POS state
     */
    public void toggleDonationRoundup(PosState state) {
        LOGGER.info("Entering method toggleDonationRoundup with state: " + state);
        if (state.payment.transactionComplete) { LOGGER.info("Exiting method toggleDonationRoundup"); return; }

        if (state.donationLineUid != null) {
            state.ticket.removeItemById(state.donationLineUid);
            state.donationLineUid = null;
        } else {
            BigDecimal total = state.ticket.totalAmount;
            // LC-07-17-02: the store administers both the step and its direction.
            BigDecimal step = BigDecimal.valueOf(posSettingsService.donationRoundingStepCents(), 2);
            BigDecimal roundedUp = total.divide(step, 0, posSettingsService.donationRoundingNearest()
                    ? RoundingMode.HALF_UP : RoundingMode.CEILING).multiply(step);
            BigDecimal difference = roundedUp.subtract(total);
            if (difference.signum() <= 0) { LOGGER.info("Exiting method toggleDonationRoundup"); return; }
            // Collected on behalf of the charity: out of VAT scope
            // Technical EAN, parameterized: a register-generated line is still
            // a line, and every line carries an EAN the valuation engine can
            // resolve (a line without one does not exist).
            state.ticket.addItem(donationEan, null, i18n.get("pay.donationLabel"),
                    difference, BigDecimal.ONE, BigDecimal.ZERO);
            state.donationLineUid = state.lastEnteredItemId;
        }
        ticketPersistenceService.syncDraft(state);
        hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
        state.touch();
        LOGGER.info("Exiting method toggleDonationRoundup");
    }

    /**
     * Cancels a SINGLE registered payment without purging the others (EXP-63):
     * the entry leaves the in-memory list and the draft is re-persisted with the
     * remaining payments, so a restart recovery cannot resurrect the cancelled
     * one. Unlike {@link #cancelPayments}, the payment stays in progress and the
     * fidelity lease and valuation are untouched — only one line is undone.
     *
     * @param state the current POS state
     * @param index the 0-based index of the payment to cancel
     * @return true when the payment was cancelled, false on an out-of-range index
     */
    public boolean cancelOnePayment(PosState state, int index) {
        LOGGER.info("Entering method cancelOnePayment with index: " + index);
        synchronized (state) {
            PaymentState.PaymentEntry cancelled = index >= 0
                    && index < state.payment.payments.size()
                    ? state.payment.payments.get(index) : null;
            // LC-07-11-01: a gift card paid on the hub is given back first; a
            // refusal keeps the settlement.
            if (cancelled != null && !reverseHubPayment(state, cancelled)) {
                state.touch();
                LOGGER.info("Exiting method cancelOnePayment (hub refused)");
                return false;
            }
            if (!state.payment.removePayment(index)) {
                LOGGER.info("Exiting method cancelOnePayment");
                return false;
            }
            Long ticketId = state.payment.ticketDbId;
            // BO-03-02-35: the slip of the one settlement undone, printed once
            // the removal succeeded — a refused index prints nothing.
            printCancellationSlip(ticketId, cancelled);
            if (ticketId != null) {
                // Re-persist the remaining payments through the same funnel used
                // to add them: clear the persisted set, replay what survives.
                ticketPersistenceService.removePaymentsFromTicket(ticketId, false);
                for (PaymentState.PaymentEntry entry : state.payment.payments) {
                    ticketPersistenceService.addPaymentToTicket(ticketId, entry);
                }
                // BO-04-01-19: the one settlement undone is journalled, not the survivors.
                ticketPersistenceService.journalCancelledPayment(ticketId, cancelled.method, cancelled.amount);
            }
            state.touch();
            LOGGER.info("Exiting method cancelOnePayment");
            return true;
        }
    }

    /**
     * Prints the slip of a settlement paid by gift card (BO-03-03-11).
     *
     * <p>The REMAINING balance is what the issuer answered minus what was just
     * settled: the register knows both and the customer is owed the
     * subtraction. A balance the issuer did not state leaves the line out
     * rather than printing a zero nobody verified.
     *
     * <p>A printer failure never fails the settlement: the money moved, the
     * paper is its witness.
     *
     * @param state the current POS state
     * @param cardNumber the gift-card number
     * @param amount the amount just settled
     * @param balanceBefore the balance the issuer answered, or null
     */
    private void printGiftCardPaymentSlip(PosState state, String cardNumber,
                                          BigDecimal amount, BigDecimal balanceBefore) {
        // LC-07-11-03: the slip is printed where the back office asks for it.
        if (ticketPrinterService == null || !posSettingsService.giftCardPaymentTicket()) {
            return;
        }
        try {
            BigDecimal remaining = balanceBefore == null
                    ? null : balanceBefore.subtract(amount).max(BigDecimal.ZERO);
            String ticketNumber = null;
            if (state.payment.ticketDbId != null) {
                com.intermarche.pos.domain.sale.Ticket ticket =
                        com.intermarche.pos.domain.sale.Ticket.findById(state.payment.ticketDbId);
                if (ticket != null) {
                    ticketNumber = ticket.ticketNumber;
                }
            }
            ticketPrinterService.printGiftCardPaymentReceipt(ticketNumber, cardNumber,
                    amount, remaining);
        } catch (Exception e) {
            LOGGER.warnf("Justificatif carte cadeau non imprime: %s", e.getMessage());
        }
    }

    /**
     * Prints the slip of one cancelled settlement (BO-03-02-35).
     *
     * <p>The tender's ADMINISTERED wording is used when the referential carries
     * one, and the settlement key otherwise: a slip naming "CHEQUE" to a
     * customer who was handed back a cheque is the point, and a store that has
     * not filled the referential in still gets a readable paper.
     *
     * <p>A printer failure never fails the cancellation: giving the cheque back
     * is the gesture, the paper is its witness. The register logs and goes on.
     *
     * @param ticketId the draft the settlement was on, or null
     * @param entry the cancelled settlement, or null when there was none
     */
    private void printCancellationSlip(Long ticketId, PaymentState.PaymentEntry entry) {
        if (entry == null || ticketPrinterService == null) {
            return;
        }
        try {
            String label = entry.method;
            com.intermarche.pos.domain.payment.TenderDefinition tender =
                    com.intermarche.pos.domain.payment.TenderDefinition.findByCode(entry.method);
            if (tender != null && tender.label != null && !tender.label.isBlank()) {
                label = tender.label;
            }
            String ticketNumber = null;
            if (ticketId != null) {
                com.intermarche.pos.domain.sale.Ticket ticket =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                if (ticket != null) {
                    ticketNumber = ticket.ticketNumber;
                }
            }
            ticketPrinterService.printPaymentCancellation(ticketNumber, label, entry.amount);
        } catch (Exception e) {
            LOGGER.warnf("Ticket d'annulation de reglement non imprime: %s", e.getMessage());
        }
    }

    /**
     * Cancels the registered payments coherently: clears the in-memory list
     * and removes the persisted payments from the draft, so a later restart
     * recovery cannot resurrect them. Each cancelled settlement leaves its own
     * slip (BO-03-02-35).
     *
     * @param state the current POS state
     */
    public void cancelPayments(PosState state) {
        LOGGER.info("Entering method cancelPayments with state: " + state);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            Long ticketId = state.payment.ticketDbId;
            // LC-07-11-01: every gift card paid on the hub is given back first.
            // One the hub refuses to give back stays, with the settlements still
            // owed to it, and only those the hub gave back leave the list.
            boolean refused = false;
            for (PaymentState.PaymentEntry entry : state.payment.payments) {
                if (!reverseHubPayment(state, entry)) {
                    refused = true;
                }
            }
            if (refused) {
                keepUnreversedHubPayments(state, ticketId);
                LOGGER.info("Exiting method cancelPayments (hub refused)");
                return;
            }
            // The fidelity lease dies with the payments (imfid spec §5.3);
            // failure-tolerant — the TTL is the safety net.
            fidelityService.releaseLease(state);
            state.payment.paymentInProgress = false;
            state.payment.pendingCardAmount = null;
            state.payment.pendingCardCallback = null;
            // Phase 7: leaving the payment reverts the valuation — the cart goes
            // back to local totals and will be revalued at the next entry
            if (state.payment.valuationAdjustment != null || "ENGINE".equals(state.payment.valuationStatus)) {
                valuationReconciler.revert(state.ticket, ticketId);
                state.ticket.recomputeTotal();
            }
            state.payment.valuationStatus = null;
            state.payment.valuationJson = null;
            state.payment.valuationEngineTotal = null;
            state.payment.valuationAdjustment = null;
            state.payment.valuationMealEligible = null;
            state.payment.valuationMealThreshold = null;
            state.payment.valuationUpsells = new java.util.ArrayList<>();
            // BO-03-02-35: a settlement undone is an event. One slip per
            // settlement the customer actually handed over, printed BEFORE the
            // list is cleared — afterwards there is nothing left to name.
            for (PaymentState.PaymentEntry entry : state.payment.payments) {
                printCancellationSlip(ticketId, entry);
            }
            state.clearPayments();
            // Back to the cart: revalue immediately so the screen shows engine
            // totals again without waiting for the next mutation
            valuationService.revalue(state);
            if (ticketId != null) {
                ticketPersistenceService.removePaymentsFromTicket(ticketId);
            }
        }
        LOGGER.info("Exiting method cancelPayments");
    }

    /**
     * Removes the gift-card settlements the hub gave back and keeps every other
     * one, after the hub refused to give one of them back (LC-07-11-01).
     *
     * @param state the current POS state
     * @param ticketId the draft the settlements are on, or null
     */
    private void keepUnreversedHubPayments(PosState state, Long ticketId) {
        java.util.List<PaymentState.PaymentEntry> given = new java.util.ArrayList<>();
        for (int i = state.payment.payments.size() - 1; i >= 0; i--) {
            PaymentState.PaymentEntry entry = state.payment.payments.get(i);
            if (entry.hubReversed) {
                state.payment.removePayment(i);
                printCancellationSlip(ticketId, entry);
                given.add(entry);
            }
        }
        if (ticketId != null) {
            ticketPersistenceService.removePaymentsFromTicket(ticketId, false);
            for (PaymentState.PaymentEntry entry : state.payment.payments) {
                ticketPersistenceService.addPaymentToTicket(ticketId, entry);
            }
            // BO-04-01-19: the settlements given back are journalled, not the ones kept.
            for (PaymentState.PaymentEntry entry : given) {
                ticketPersistenceService.journalCancelledPayment(ticketId, entry.method, entry.amount);
            }
        }
        state.touch();
    }

    // --------------------------------------------------
    // 2. COMMON PRIVATE METHOD
    // --------------------------------------------------

    /**
     * Registers a payment with change handling: caps the applied amount at the
     * remaining due, computes the change, updates the UI state, persists the
     * payment and drives the customer display.
     *
     * <p>THE ONE PLACE the administered tender rules are opposed to a settlement
     * (BO-03-02-10/11/12/13/14/16): every method funnels here, so a ceiling
     * checked here is checked for all of them, and a tender no row administers
     * passes through exactly as before.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (CASH, CARD, TR, CHEQUE...)
     * @param displayName the label shown on the customer display
     * @param tendered the amount handed over by the customer
     * @return true when the settlement was registered, false when an
     *         administered rule refused it and the sale was left untouched
     */
    private boolean handlePaymentWithChange(PosState state, String methodKey, String displayName, BigDecimal tendered) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (tendered == null || tendered.signum() <= 0) return false;
            // BO-03-02-01: the administered tender the rules are read under —
            // the one the store added and the cashier picked, or the nature.
            String ruleKey = ruleKey(state, methodKey);

            // BO-03-02-27/-29: what a SELF-SERVICE lane may settle with, and
            // what it calls the technical hostess for. Opposed before anything
            // is registered, like every other administered tender rule.
            if (!selfServiceTenderAllowed(state, methodKey, ruleKey, displayName, tendered)) {
                state.touch();
                return false;
            }

            BigDecimal remaining = state.getRemaining();
            BigDecimal amountToPay = tendered.min(remaining);

            // The administered bounds are opposed BEFORE anything is registered:
            // a blocking rule must leave the sale exactly as it was, not undo a
            // settlement the cashier can already see on the screen.
            TenderRulesService.Verdict verdict =
                    tenderRulesService.check(ruleKey, amountToPay, registeredCount(state, ruleKey));
            if (verdict.refuses() && !overrideCovers(state, methodKey, verdict)) {
                if (verdict.level.allowsOverride()) {
                    holdForSupervisor(state, methodKey, displayName, tendered, verdict);
                } else {
                    state.ticket.setError(verdict.displayMessage(i18n));
                }
                state.touch();
                return false;
            }
            // Consumed the moment it lets a settlement through: an authorization
            // outliving its settlement authorizes the next one.
            state.payment.clearTenderHold();

            state.payment.clearPendingVoucher();

            BigDecimal change = tendered.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);
            // A tender the back office forbids change on settles what it settles
            // and gives nothing back — the overpayment is simply not taken.
            if (change.signum() > 0 && !tenderRulesService.changeAllowed(ruleKey, true)) {
                change = BigDecimal.ZERO;
            }
            TenderRulesService.Verdict changeVerdict = tenderRulesService.checkChange(ruleKey, change);
            if (changeVerdict.refuses()) {
                state.ticket.setError(changeVerdict.displayMessage(i18n));
                state.touch();
                return false;
            }

            // BO-03-02-16: the change of a tender administered to give it back as
            // a credit note is not money out of the drawer. It is BOOKED here and
            // CREATED at the fiscal moment, like the gift cards sold on the sale —
            // an abandoned sale never creates value.
            boolean changeAsNote = change.signum() > 0
                    && CREDIT_NOTE_TENDER.equals(tenderRulesService.changeTender(ruleKey));
            if (changeAsNote) {
                state.payment.changeAsCreditNote = state.payment.changeAsCreditNote.add(change);
            }

            // UI state update
            state.payment.lastChangeAmount = (change.signum() > 0 && !changeAsNote)
                    ? change : BigDecimal.ZERO;

            // In-memory update
            if ("CASH".equals(methodKey)) {
                state.payment.addCashPayment(amountToPay, tendered);
            } else if ("CARD".equals(methodKey)) {
                // The card entry carries the terminal traces (authorization
                // number, degraded-mode indicator) parked by the accept leg.
                state.payment.addCardPayment(amountToPay,
                        state.payment.pendingCardAuthNumber, state.payment.pendingCardDegraded,
                        state.payment.pendingCardEntryMode);
            } else if ("CHEQUE".equals(methodKey)) {
                // The cheque entry carries the magnetic line parked by the reading.
                state.payment.addChequePayment(amountToPay, state.payment.pendingChequeLine);
                // BO-03-02-24: the account number the cashier keyed, if asked.
                lastEntry(state).chequeAccountNumber = state.payment.pendingChequeAccount;
                lastEntry(state).chequeGuaranteeAuthorization = state.payment.pendingChequeGuarantee;
                lastEntry(state).chequeGuaranteeProvider = state.payment.pendingChequeGuaranteeProvider;
            } else {
                state.payment.addPayment(methodKey, amountToPay);
            }
            state.touch();
            // BO-03-02-01: the settlement keeps the name of the tender it was
            // taken as, and the selection is consumed by it.
            consumeSelectedTender(state, methodKey, ruleKey);

            // LC-07-07-03: server belongs to the actual settlement, including TRD.
            state.payment.payments.get(state.payment.payments.size() - 1).moneticServer = state.payment.pendingMoneticServer;
            // Database persistence
            savePayment(state, methodKey);

            // Hardware display
            if (changeAsNote) {
                hardwareService.displayMessage(String.format(i18n.get("display.renduAvoir"), df.format(change)));
            } else if (change.signum() > 0) {
                hardwareService.displayMessage(String.format(i18n.get("display.donneRendu"), df.format(tendered), df.format(change)));
            } else {
                hardwareService.displayMessage(String.format("%-10s%s E", displayName, df.format(amountToPay)));
            }

            // A rule that informs or warns without blocking is shown AFTER the
            // settlement is registered: the cashier is told what happened, not
            // stopped from doing it.
            if (verdict.speaks()) {
                state.ticket.setError(verdict.displayMessage(i18n));
            } else if (changeVerdict.speaks()) {
                state.ticket.setError(changeVerdict.displayMessage(i18n));
            }

            checkCompletion(state);
            return true;
        }
    }

    /**
     * Issues and prints the credit note this sale owes as change
     * (BO-03-02-16).
     *
     * <p>The booked amount is NOT cleared here: {@code clearTicket()}, which ends
     * this same closing, resets the whole payment state — clearing it twice would
     * be a line that can never be observed to do anything.
     *
     * @param state the current POS state
     * @param ticketId the database id of the closed ticket
     */
    private void issueChangeCreditNote(PosState state, Long ticketId) {
        BigDecimal owed = state.payment.changeAsCreditNote;
        if (owed == null || owed.signum() <= 0) {
            return;
        }
        String number = ticketPersistenceService.issueChangeCreditNote(ticketId, owed);
        // BO-03-03-01: the note is ISSUED whatever the printing rule — it is a
        // registry fact and a balance the customer owns — but it is only
        // PRINTED when the shop named it among the forced documents, because
        // the end-of-transaction screen cannot ask for it.
        if (number != null
                && printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_CREDIT_NOTE)) {
            ticketPrinterService.printChangeVoucher(number, owed);
        }
    }

    /**
     * Tells whether a supervisor's authorization already covers the rule the
     * settlement just broke (BO-03-02-10/12/13/14).
     *
     * <p>An authorization covers ONE tender and only the levels that admit an
     * override: a supervisor can pass a "bloquant superviseur" rule, and nobody
     * passes a "bloquant" one — that is the whole difference between the two
     * levels the questionnaire asks for.
     *
     * @param state the current POS state
     * @param methodKey the settlement key
     * @param verdict the rule the settlement broke
     * @return true when the settlement goes through on that authorization
     */
    private boolean overrideCovers(PosState state, String methodKey,
            TenderRulesService.Verdict verdict) {
        return verdict.level.allowsOverride()
                && methodKey != null
                && methodKey.equals(state.payment.tenderOverride);
    }

    /**
     * Holds a settlement back until a supervisor allows the administered bound
     * to be passed (BO-03-02-10/12/13/14).
     *
     * @param state the current POS state
     * @param methodKey the settlement key
     * @param displayName the label the settlement shows on the customer display
     * @param tendered the amount handed over
     * @param verdict the rule the settlement broke
     */
    /**
     * Opposes the self-service rules of a tender (BO-03-02-27 and -29).
     *
     * <p>Nothing changes on a MANNED till: both rules are read off the
     * register's own kind, which the back office distributed on its checkout
     * point, and a till the referential does not describe is treated as manned
     * — a lane missing from the referential is a referential problem, not a
     * reason to refuse a customer at the one moment they are paying.
     *
     * <p>On a self-service lane, a tender the store forbade there is refused
     * and says so; a tender the store watches there raises the SAME supervisor
     * hold every administered tender rule raises, so the hostess sees the panel
     * she already knows. The hold is skipped when an authorization is already
     * in hand, or the hostess would be called for the settlement she just
     * allowed.
     *
     * @param state the current POS state
     * @param methodKey the settlement key being registered
     * @param displayName the tender's wording, for the supervisor panel
     * @param tendered the amount handed over
     * @return true when the settlement may go on
     */
    private boolean selfServiceTenderAllowed(PosState state, String methodKey, String ruleKey,
                                             String displayName, BigDecimal tendered) {
        com.intermarche.pos.domain.store.CheckoutPoint point = checkoutPoint();
        boolean selfService = point != null && point.isSelfService();
        String island = point == null ? null : point.islandCode;
        if (!selfService && (island == null || island.isBlank())) {
            return true;
        }
        com.intermarche.pos.domain.payment.TenderDefinition tender;
        try {
            tender = com.intermarche.pos.domain.payment.TenderDefinition.findByCode(ruleKey);
        } catch (Exception e) {
            // A referential this register cannot read opposes nothing.
            return true;
        }
        if (tender == null) {
            return true;
        }
        if (selfService && !tender.selfServiceAllowed) {
            state.ticket.setError(i18n.get("op.reglementIndisponibleLibreService"));
            return false;
        }
        // BO-03-02-29: watched on every self-service lane, or on the islands
        // the tender names — the "îlot spécifié" of the questionnaire.
        boolean laneWatched = selfService && tender.selfServiceSupervision;
        boolean watched = laneWatched || tender.supervisedOnIsland(island);
        TenderRulesService.Verdict call = new TenderRulesService.Verdict(
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                i18n.get(laneWatched ? "op.reglementSurveilleLibreService" : "op.reglementSurveilleIlot"));
        if (watched && !overrideCovers(state, methodKey, call)) {
            holdForSupervisor(state, methodKey, displayName, tendered, call);
            return false;
        }
        return true;
    }

    /**
     * The checkout point this register stands on (BO-03-02-27/-29).
     *
     * <p>Read off the checkout point the back office distributed, which is the
     * only description of a register that does not depend on the register
     * believing something about itself. A point nobody described, or a
     * referential that cannot be read, answers null — a manned till on no
     * island.
     *
     * @return the checkout point, or null
     */
    private com.intermarche.pos.domain.store.CheckoutPoint checkoutPoint() {
        try {
            String terminalId = ticketNumberService == null
                    ? null : ticketNumberService.getTerminalId();
            return com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(terminalId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the code the administered tender rules are read under for a
     * settlement of the given nature (BO-03-02-01).
     *
     * <p>The tender the store ADDED and the cashier picked when it is of that
     * nature, the nature itself otherwise: an "American Express" carries its
     * own ceilings, its own count and its own change rule, while a settlement
     * taken on the plain key reads the plain row.
     *
     * @param state the current POS state
     * @param methodKey the settlement nature
     * @return the selected tender's code, or the nature
     */
    private String ruleKey(PosState state, String methodKey) {
        String selected = state.payment.selectedTenderCode;
        if (selected == null) {
            return methodKey;
        }
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                tenderRulesService.tender(selected);
        return tender != null && tender.nature().equals(methodKey) ? selected : methodKey;
    }

    /**
     * Stamps the last registered settlement with the tender it was taken as
     * and consumes the selection (BO-03-02-01).
     *
     * @param state the current POS state
     * @param methodKey the settlement nature
     * @param ruleKey the code the rules were read under
     */
    private void consumeSelectedTender(PosState state, String methodKey, String ruleKey) {
        if (ruleKey.equals(methodKey)) {
            return;
        }
        lastEntry(state).tenderCode = ruleKey;
        state.payment.selectedTenderCode = null;
    }

    /**
     * Returns the settlement registered last.
     *
     * @param state the current POS state
     * @return the last payment entry
     */
    private PaymentState.PaymentEntry lastEntry(PosState state) {
        return state.payment.payments.get(state.payment.payments.size() - 1);
    }

    /**
     * Settles with a tender the STORE ADDED (BO-03-02-01): the tender is
     * selected, then its nature's own conversation runs — the card terminal,
     * the cheque reader, the drawer — and the settlement it registers keeps the
     * tender's name.
     *
     * <p>A selection the conversation did not consume and does not wait on —
     * a refusal, nothing due — is dropped at once, so it never names the next
     * plain settlement of the same nature.
     *
     * @param state the current POS state
     * @param code the code of the tender the cashier picked
     * @param amount the amount typed, or zero/null for the remaining due
     */
    public void processAdditionalTender(PosState state, String code, BigDecimal amount) {
        LOGGER.info("Entering method processAdditionalTender with code: " + code + ", amount: " + amount);
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                code == null || code.isBlank() ? null : tenderRulesService.tender(code.trim());
        if (tender == null || !tender.active || !tender.isAdditional()
                || !com.intermarche.pos.domain.payment.TenderDefinition.ADDITIONAL_NATURES
                        .contains(tender.nature())) {
            state.ticket.setError(i18n.get("op.moyenReglementIndisponible"));
            state.touch();
            LOGGER.info("Exiting method processAdditionalTender");
            return;
        }
        state.payment.selectedTenderCode = tender.code;
        switch (tender.nature()) {
            case "CASH" -> processCash(state, amount);
            case "CARD" -> processCard(state, amount);
            case "CHEQUE" -> processCheque(state, amount);
            case "CHEQUE_DEFERRED" -> processDeferredCheque(state, amount);
            case "TR" -> processTicketResto(state, amount);
            case "FIDELITY" -> processFidelity(state, amount);
            default -> processTrdCard(state, amount);
        }
        if (state.payment.pendingCardAmount == null && state.payment.pendingChequeAmount == null
                && state.payment.tenderHeldMethod == null) {
            state.payment.selectedTenderCode = null;
        }
        LOGGER.info("Exiting method processAdditionalTender");
    }

    private void holdForSupervisor(PosState state, String methodKey, String displayName,
            BigDecimal tendered, TenderRulesService.Verdict verdict) {
        state.payment.tenderHeldMethod = methodKey;
        state.payment.tenderHeldLabel = displayName;
        state.payment.tenderHeldAmount = tendered;
        // The BARE rule, not the display one: the panel is itself the call for a
        // supervisor, so repeating "appeler un superviseur" inside it says nothing.
        state.payment.tenderHeldMessage = verdict.message;
        state.payment.tenderOverride = null;
        state.payment.chequeHeldBeforeReading = false;
    }

    /**
     * Lets a supervisor allow the held settlement, then registers it
     * (BO-03-02-10/12/13/14).
     *
     * <p>What is registered is the settlement THAT WAS HELD, replayed from the
     * state: the supervisor authorizes an amount on a tender, not whatever the
     * pad shows by the time they have walked over.
     *
     * @param state the current POS state
     * @param login the supervisor's login, ignored when the logged operator is one
     * @param password the supervisor's password
     * @return true when the settlement was registered
     */
    public boolean authorizeHeldTender(PosState state, String login, String password) {
        LOGGER.info("Entering method authorizeHeldTender with state: " + state + ", login: " + login + ", password: ***");
        String methodKey = state.payment.tenderHeldMethod;
        BigDecimal tendered = state.payment.tenderHeldAmount;
        String displayName = state.payment.tenderHeldLabel;
        if (methodKey == null || tendered == null) {
            LOGGER.info("Exiting method authorizeHeldTender");
            return false;
        }
        boolean granted = endorsementService.operatorIsSupervisor(state)
                || endorsementService.authorize(login, password, TENDER_OVERRIDE_ACTION);
        if (!granted) {
            state.payment.tenderHeldMessage = i18n.get("op.autorisationRefusee");
            state.touch();
            LOGGER.info("Exiting method authorizeHeldTender");
            return false;
        }
        state.payment.tenderOverride = methodKey;
        boolean registered;
        if ("CARD".equals(methodKey)) {
            int previous = state.payment.payments.size();
            processCard(state, tendered, state.payment.tenderHeldCombined);
            registered = state.payment.pendingCardAmount != null || state.payment.payments.size() > previous;
        } else if ("CHEQUE".equals(methodKey) && state.payment.chequeHeldBeforeReading) {
            // BO-03-02-24: an authorized cheque is still read before it settles.
            int previous = state.payment.payments.size();
            takeCheque(state, tendered);
            registered = state.payment.pendingChequeAmount != null || state.payment.payments.size() > previous;
        } else {
            registered = handlePaymentWithChange(state, methodKey, displayName, tendered);
        }
        LOGGER.info("Exiting method authorizeHeldTender");
        return registered;
    }

    /**
     * Abandons the settlement held back for a supervisor's authorization.
     *
     * @param state the current POS state
     */
    public void cancelHeldTender(PosState state) {
        LOGGER.info("Entering method cancelHeldTender with state: " + state);
        state.payment.clearTenderHold();
        state.payment.selectedTenderCode = null;
        state.touch();
        LOGGER.info("Exiting method cancelHeldTender");
    }

    /**
     * Counts the settlements of one tender already registered on the sale
     * (BO-03-02-12).
     *
     * @param state the current POS state
     * @param methodKey the settlement key, or null
     * @return how many settlements of that tender the sale already carries
     */
    private int registeredCount(PosState state, String methodKey) {
        if (methodKey == null) {
            return 0;
        }
        int count = 0;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            // BO-03-02-01: a settlement taken as a tender the store added
            // counts for that tender, not for its nature.
            String counted = entry.tenderCode == null ? entry.method : entry.tenderCode;
            if (methodKey.equals(counted)) {
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------
    // 3. PUBLIC ACTIONS
    // --------------------------------------------------

    /**
     * Registers a cash payment; the drawer always opens (deposit and change).
     *
     * @param state the current POS state
     * @param tendered the cash amount handed over
     */
    public void processCash(PosState state, BigDecimal tendered) {
        LOGGER.info("Entering method processCash with state: " + state + ", tendered: " + tendered);
        if (tendered == null || tendered.signum() <= 0) { LOGGER.info("Exiting method processCash"); return; }

        // LC-07-03-05: where the shop rounds, the coins to make up anything off
        // the step no longer circulate, so an amount off the step cannot have been
        // handed over. Refused rather than silently rounded: the cashier is telling
        // the register what is in their hand, and the register does not know better.
        if (!CashRounding.isTenderable(tendered, state.cashRoundingStepCents)) {
            state.ticket.setError(i18n.format("op.montantNonMultipleE",
                    df.format(BigDecimal.valueOf(state.cashRoundingStepCents, 2))));
            state.touch();
            LOGGER.info("Exiting method processCash");
            return;
        }

        // LC-07-03-01/06: the rounding applies to what is SETTLED IN CASH, so it is
        // decided here and not on the ticket total — a sale part-paid by card rounds
        // the remainder, which is what the customer actually hands over. The
        // difference is booked FIRST, so the cash payment that follows sees a
        // remaining due already on the step and the change comes out right.
        registerCashRounding(state, tendered);

        if (!handlePaymentWithChange(state, "CASH", "ESPECES", tendered)) {
            LOGGER.info("Exiting method processCash");
            return;
        }

        // Rule: cash = systematic drawer opening (deposit + change), unless the
        // back office disabled the drawer-open-on-payment rule (BO-10-02-12) or
        // administered another moment for this tender (BO-03-02-19).
        LOGGER.info("Exiting method processCash");
    }

    /**
     * Starts a cash payment on the cash changer for the remaining due
     * (LC-07-15-01).
     *
     * <p>The machine is asked for what the customer settles in cash — the
     * remaining due, rounded where the shop rounds — and closes the deposit by
     * itself once that is reached, dispensing the change. Nothing is registered
     * before it has: coins in the machine are not yet a settlement, exactly as a
     * card is not before the bank accepts. The administered cash rules are
     * opposed BEFORE the machine takes anything, so a refusal never leaves the
     * customer's money inside it.
     *
     * <p>In training no machine is touched: the amount due is settled as if it
     * had been inserted exactly.
     *
     * @param state the current POS state
     */
    public void processCashChanger(PosState state) {
        LOGGER.info("Entering method processCashChanger");
        BigDecimal due;
        synchronized (state) {
            if (!posSettingsService.hardwareEnabled("cashchanger")) {
                state.ticket.setError(i18n.get("op.monnayeurIndisponible"));
                state.touch();
                LOGGER.info("Exiting method processCashChanger");
                return;
            }
            BigDecimal remaining = state.getRemaining();
            if (state.payment.cashChangerDue != null || remaining.signum() <= 0) {
                LOGGER.info("Exiting method processCashChanger");
                return;
            }
            due = CashRounding.round(remaining, state.cashRoundingStepCents);
            TenderRulesService.Verdict verdict =
                    tenderRulesService.check("CASH", due, registeredCount(state, "CASH"));
            if (verdict.refuses()) {
                state.ticket.setError(verdict.displayMessage(i18n));
                state.touch();
                LOGGER.info("Exiting method processCashChanger");
                return;
            }
            if (state.trainingMode) {
                registerCashChangerDeposit(state, new com.intermarche.pos.ui.hardware.CashChangerService.Deposit(
                        due, BigDecimal.ZERO));
                LOGGER.info("Exiting method processCashChanger");
                return;
            }
            state.payment.cashChangerDue = due;
            state.payment.cashChangerInserted = BigDecimal.ZERO.setScale(2);
            state.payment.cashChangerOutcome = null;
            state.payment.cashChangerFailure = null;
            state.touch();
        }
        hardwareService.displayMessage(String.format(i18n.get("display.monnayeur"), df.format(due)));
        // The callbacks fire on the follower thread and only DEPOSIT: the
        // registration happens on the next request, like a cheque reading.
        cashChangerService.deposit(due,
                inserted -> showCashChangerProgress(state, inserted),
                outcome -> {
                    state.payment.cashChangerOutcome = outcome;
                    state.touch();
                },
                message -> {
                    state.payment.cashChangerFailure = message;
                    state.touch();
                });
        LOGGER.info("Exiting method processCashChanger");
    }

    /**
     * Shows what the customer has fed into the cash changer so far, on the
     * register screen, the customer screen and the line display (LC-07-15-02).
     *
     * @param state the current POS state
     * @param inserted the amount inserted so far
     */
    private void showCashChangerProgress(PosState state, BigDecimal inserted) {
        if (state.payment.cashChangerDue == null) {
            return;
        }
        state.payment.cashChangerInserted = inserted;
        hardwareService.displayMessage(String.format(i18n.get("display.monnayeurInsere"), df.format(inserted)));
        state.touch();
    }

    /**
     * Closes the running deposit on what the customer has inserted, without
     * change: the rest is paid with another tender (LC-07-15-01).
     *
     * @param state the current POS state
     */
    public void finishCashChanger(PosState state) {
        LOGGER.info("Entering method finishCashChanger");
        if (state.payment.cashChangerDue != null) {
            cashChangerService.fix();
        }
        LOGGER.info("Exiting method finishCashChanger");
    }

    /**
     * Cancels the running deposit: the machine gives everything back and
     * nothing is registered.
     *
     * @param state the current POS state
     */
    public void cancelCashChanger(PosState state) {
        LOGGER.info("Entering method cancelCashChanger");
        if (state.payment.cashChangerDue != null) {
            cashChangerService.cancel();
        }
        LOGGER.info("Exiting method cancelCashChanger");
    }

    /**
     * Settles a deposit the follower thread reported, on the REQUEST thread
     * that calls this — the register writes its draft from one thread at a
     * time, and only the poll brings the machine's outcome back into a request.
     *
     * @param state the current POS state
     */
    public void settlePendingCashChanger(PosState state) {
        com.intermarche.pos.ui.hardware.CashChangerService.Deposit outcome;
        String failure;
        synchronized (state) {
            outcome = state.payment.cashChangerOutcome;
            failure = state.payment.cashChangerFailure;
            if (outcome == null && failure == null) {
                return;
            }
            state.payment.cashChangerOutcome = null;
            state.payment.cashChangerFailure = null;
            if (failure != null) {
                state.payment.cashChangerDue = null;
                state.payment.cashChangerInserted = null;
                state.ticket.setError(failure);
                state.touch();
                return;
            }
        }
        LOGGER.info("Entering method settlePendingCashChanger");
        registerCashChangerDeposit(state, outcome);
        LOGGER.info("Exiting method settlePendingCashChanger");
    }

    /**
     * Registers what a closed deposit settled, as CASH (LC-07-15-03).
     *
     * <p>The settled amount is what was inserted minus what the machine gave
     * back; a deposit given back whole settles nothing and is only reported.
     * The change was dispensed by the machine: it is shown, the drawer stays
     * shut, and the entry is flagged so the sale's closing transfers the cash to
     * the safe (LC-07-15-04).
     *
     * @param state the current POS state
     * @param outcome how the deposit ended
     */
    private void registerCashChangerDeposit(PosState state,
            com.intermarche.pos.ui.hardware.CashChangerService.Deposit outcome) {
        synchronized (state) {
            state.payment.cashChangerDue = null;
            state.payment.cashChangerInserted = null;
            BigDecimal settled = outcome.settled().setScale(2, RoundingMode.HALF_UP);
            if (settled.signum() <= 0) {
                state.ticket.setNotice(i18n.get("op.monnayeurRendu"));
                state.touch();
                return;
            }
            registerCashRounding(state, settled);
            BigDecimal applied = settled.min(state.getRemaining());
            state.payment.addCashPayment(applied, outcome.inserted());
            state.payment.payments.get(state.payment.payments.size() - 1).cashChanger = true;
            savePayment(state, "CASH");
            BigDecimal change = outcome.change().setScale(2, RoundingMode.HALF_UP);
            state.payment.lastChangeAmount = change;
            if (change.signum() > 0) {
                hardwareService.displayMessage(String.format(i18n.get("display.donneRendu"),
                        df.format(outcome.inserted()), df.format(change)));
            } else {
                hardwareService.displayMessage(String.format("%-10s%s E", "ESPECES", df.format(applied)));
            }
            checkCompletion(state);
            state.touch();
        }
    }

    /**
     * Transfers to the safe the cash the cash changer took for this sale
     * (LC-07-15-04): the notes are in the machine, not in the drawer, so the
     * drawer's theoretical cash must not count them.
     *
     * @param state the current POS state, sale not yet cleared
     */
    private void transferCashChangerToSafe(PosState state) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            if (entry.cashChanger) {
                total = total.add(entry.amount);
            }
        }
        if (total.signum() <= 0) {
            return;
        }
        com.intermarche.pos.domain.people.Employee cashier = state.auth.operatorId == null ? null
                : com.intermarche.pos.domain.people.Employee.findById(state.auth.operatorId);
        cashMovementService.recordCashChangerTransfer(cashSessionService.getOpenSession(), cashier, total);
    }

    /**
     * Registers a settlement taken on a backup-monetics terminal
     * ({@code LC-07-07-06} to {@code -09}).
     *
     * <p>Capped at the remaining due and NO CHANGE: a mobile terminal charges what
     * it was asked to charge, and an amount above what is left is a partial
     * authorization mismatch, not a customer handing over too much. Giving change on
     * it would take real money out of the drawer against a card settlement.
     *
     * <p>The drawer stays shut: nothing physical moves at this till.
     *
     * @param state the current POS state
     * @param amount the amount the mobile terminal accepted
     * @param methodLabel the scheme it reported
     * @param transactionNumber the sale the two codes were matched on
     * @param manual true when the outcome was keyed in rather than scanned
     */
    public void processBackupPayment(PosState state, BigDecimal amount, String methodLabel,
            String transactionNumber, boolean manual) {
        LOGGER.info("Entering method processBackupPayment with state: " + state + ", amount: " + amount + ", methodLabel: " + methodLabel + ", transactionNumber: " + transactionNumber + ", manual: " + manual);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processBackupPayment"); return; }
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) { LOGGER.info("Exiting method processBackupPayment"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(remaining);
            state.payment.addBackupPayment(amountToPay, methodLabel, transactionNumber, manual);
            state.touch();
            savePayment(state, "SECOURS");
            hardwareService.displayMessage(String.format(i18n.get("display.secours"), df.format(amountToPay)));

            // Rule: backup monetics = no drawer opening (nothing physical).

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processBackupPayment");
    }

    /**
     * Registers a settlement handed over in a foreign currency ({@code LC-07-14}).
     *
     * <p>Everything downstream works in EUROS: the euro value is what the sale is
     * credited with, what the change is computed on and what the accounts see. The
     * currency, the amount handed over and the rate ride on the entry so the receipt
     * can state all three ({@code LC-07-14-05}), and nowhere else.
     *
     * <p>The drawer opens: foreign notes go into it, like any physical tender.
     *
     * @param state the current POS state
     * @param currency the currency handed over
     * @param foreignAmount the amount handed over, in that currency
     * @param euroValue the euro value of that amount at the administered rate
     */
    public void processForeignCurrency(PosState state, com.intermarche.pos.domain.payment.Currency currency,
            BigDecimal foreignAmount, BigDecimal euroValue) {
        LOGGER.info("Entering method processForeignCurrency with state: " + state + ", currency: " + currency + ", foreignAmount: " + foreignAmount + ", euroValue: " + euroValue);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (currency == null || euroValue == null || euroValue.signum() <= 0) { LOGGER.info("Exiting method processForeignCurrency"); return; }
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) { LOGGER.info("Exiting method processForeignCurrency"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = euroValue.min(remaining);
            BigDecimal change = euroValue.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);
            state.payment.lastChangeAmount = change.signum() > 0 ? change : BigDecimal.ZERO;
            // BO-03-02-39: the "monnaie d'échange" of the currency. Given back in
            // that currency, the change never leaves the euro drawer: the drawer
            // of the currency keeps what was handed over LESS what went back.
            BigDecimal kept = foreignAmount;
            if (change.signum() > 0 && currency.changeInCurrency()) {
                BigDecimal foreignChange = currency.fromEuro(change);
                kept = foreignAmount.subtract(foreignChange);
                state.payment.lastChangeAmount = BigDecimal.ZERO;
                state.payment.lastChangeForeign = df.format(foreignChange) + " " + currency.displayUnit();
                change = BigDecimal.ZERO;
                hardwareService.displayMessage(String.format(i18n.get("display.renduDevise"),
                        state.payment.lastChangeForeign));
            }

            state.payment.addCurrencyPayment(amountToPay, currency.code, kept,
                    currency.euroPerUnit);
            state.touch();
            savePayment(state, "DEVISE");

            // LC-07-14-05: the three figures the cashier and the customer need to
            // agree on — what was handed over, what it is worth, and at what rate.
            hardwareService.displayMessage(String.format("%s %s = %s E",
                    df.format(foreignAmount), currency.displayUnit(), df.format(euroValue)));
            if (change.signum() > 0) {
                hardwareService.displayMessage(String.format(i18n.get("display.rendu"), df.format(change)));
            }


            checkCompletion(state);
        }
        LOGGER.info("Exiting method processForeignCurrency");
    }

    /**
     * Books the legal rounding difference when this cash payment settles the sale
     * ({@code LC-07-03-06}).
     *
     * <p>ONLY WHEN IT SETTLES. A cash payment smaller than the rounded remainder is
     * a part payment: the sale goes on, its remainder is rounded again later, and
     * rounding a part payment would round the same sale twice. So the difference is
     * booked only when what is tendered covers the rounded remainder.
     *
     * @param state the current POS state
     * @param tendered the cash amount handed over
     */
    private void registerCashRounding(PosState state, BigDecimal tendered) {
        int step = state.cashRoundingStepCents;
        if (step <= 1) {
            return;
        }
        BigDecimal remaining = state.getRemaining();
        BigDecimal difference = CashRounding.difference(remaining, step);
        if (difference.signum() == 0) {
            return;
        }
        if (tendered.compareTo(CashRounding.round(remaining, step)) < 0) {
            return;
        }
        state.payment.addPayment("ARRONDI", difference);
        state.touch();
        savePayment(state, "ARRONDI");
        hardwareService.displayMessage(String.format(i18n.get("display.arrondi"), df.format(difference.negate())));
    }

    /**
     * Applies the CARD PAYMENT control point (BO-10-02-10).
     *
     * <p>Answers whether the card may be used, and leaves its sentence on the
     * sale screen when it may not.
     *
     * @param state the current POS state
     * @return true when the settlement may go on
     */
    private boolean cardPaymentAllowed(PosState state) {
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT);
        // BO-03-10-14: an island may silence the non-blocking alerts tied to a
        // tender. The refusal below is NOT silenced: a card the store forbids
        // stays forbidden and says so.
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN) {
            if (!posSettingsService.islandBlockPaymentPopups()) {
                state.ticket.setWarning(endorsementService.controlMessage(
                        com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT, level));
            }
        } else if (!level.allows()) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT, level));
        }
        return level.allows();
    }

    /**
     * Registers a card payment; the amount defaults to the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCard(PosState state, BigDecimal amount) {
        processCard(state, amount, false);
    }

    /** Starts LC-07-06-02 combined processing with the same card controls and pending guard.
     * @param state current sale
     * @param amount requested total, zero for remaining due
     * @param combined whether eligible meal cents must accompany the request
     */
    public void processCard(PosState state, BigDecimal amount, boolean combined) {
        LOGGER.info("Entering method processCard with state: " + state + ", amount: " + amount);
        // Single-writer discipline (see initPayment): the in-flight check and
        // the parking of the pending card are one step, and the terminal's
        // decision, which may arrive at once from its own thread, waits for it.
        synchronized (state) {
            startCard(state, amount, combined);
        }
    }

    /**
     * Starts the card transaction under the register's lock: the controls, the
     * parking of the pending amount and the request to the terminal.
     *
     * @param state the current POS state
     * @param amount the requested total, zero or negative for the remaining due
     * @param combined whether eligible meal cents must accompany the request
     */
    private void startCard(PosState state, BigDecimal amount, boolean combined) {
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processCard"); return; }
        // BO-10-02-10: the shop may watch the use of the card at this register
        // — nothing, a warning, or a refusal. Said BEFORE the terminal is asked
        // anything, because a transaction started is a transaction to undo. The
        // point is not endorsable: see ControlPoint.isEndorsable().
        if (!cardPaymentAllowed(state)) { LOGGER.info("Exiting method processCard"); return; }

        // One transaction at a time: the pending amount is the UI-facing
        // in-flight marker, whatever the terminal implementation.
        if (state.payment.pendingCardAmount != null) { LOGGER.info("Exiting method processCard"); return; }
        BigDecimal remaining = state.getRemaining();
        BigDecimal requested = amount.min(remaining).setScale(2, RoundingMode.HALF_UP);
        if (requested.signum() <= 0) {
            return;
        }
        // LC-07-04-07: where the store offers cashback, an amount keyed ABOVE the
        // remaining due is debited whole, the difference going back in cash once
        // the bank accepts. Bounded by the administered ceiling, said before the
        // terminal is asked anything. The combined meal-voucher debit never
        // carries one: its amount is the remaining due by construction.
        if (!combined && amount.compareTo(remaining) > 0 && posSettingsService.cashbackEnabled()) {
            BigDecimal cashback = amount.subtract(remaining).setScale(2, RoundingMode.HALF_UP);
            BigDecimal ceiling = posSettingsService.cashbackMax();
            if (ceiling.signum() > 0 && cashback.compareTo(ceiling) > 0) {
                state.ticket.setError(i18n.format("op.cashbackPlafond", df.format(ceiling)));
                state.touch();
                LOGGER.info("Exiting method processCard");
                return;
            }
            requested = amount.setScale(2, RoundingMode.HALF_UP);
        }
        // LC-07: local controls must run BEFORE the bank accepts, never discard an accepted settlement.
        String cardKey = ruleKey(state, "CARD");
        if (!selfServiceTenderAllowed(state, "CARD", cardKey, i18n.get("pay.cardDisplayLabel"), requested)) {
            state.payment.tenderHeldCombined = combined;
            return;
        }
        TenderRulesService.Verdict beforeTerminal = tenderRulesService.check(cardKey, requested, registeredCount(state, cardKey));
        if (beforeTerminal.refuses() && !overrideCovers(state, "CARD", beforeTerminal)) {
            if (beforeTerminal.level.allowsOverride()) {
                holdForSupervisor(state, "CARD", i18n.get("pay.cardDisplayLabel"), requested, beforeTerminal);
                state.payment.tenderHeldCombined = combined;
            } else {
                state.ticket.setError(beforeTerminal.displayMessage(i18n));
            }
            state.touch();
            return;
        }
        state.payment.clearTenderHold();
        state.payment.pendingCardAmount = requested;
        state.payment.terminalLine1 = "";
        state.payment.terminalLine2 = "";
        hardwareService.displayMessage(String.format(i18n.get("display.carte"), df.format(requested)));
        state.touch();

        // The decision arrives asynchronously on the callback: the virtual
        // terminal fires it from the simulator's accept/refuse endpoint,
        // the auto mode fires it synchronously, Verifone from its exchange
        // thread, the hardware bridge from its follower thread. Every leg runs
        // under the register's lock, like the screen's own gestures, so the
        // decision and a concurrent reload of the payment screen never write
        // the draft at the same time; and only the transaction still pending
        // may settle, once.
        var callback = new com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback() {
            /** LC-07-08-01: relays WCL information throughout the exchange. */
            @Override
            public void onDisplay(String first, String second) {
                synchronized (state) {
                    if (state.payment.pendingCardCallback != this) {
                        return;
                    }
                    state.payment.terminalLine1 = first;
                    state.payment.terminalLine2 = second;
                    state.touch();
                }
            }

            /**
             * Registers the accepted card payment.
             *
             * @param outcome the terminal outcome
             */
            @Override
            public void onAccepted(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                synchronized (state) {
                    if (claimCardDecision(state, this)) {
                        registerAcceptedCard(state, outcome);
                    }
                }
            }

            /**
             * Drops the refused card payment and tells the cashier.
             *
             * @param outcome the refusal outcome
             */
            @Override
            public void onRefused(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                synchronized (state) {
                    if (!claimCardDecision(state, this)) {
                        return;
                    }
                    // LC-08-03-12: the not-completed transaction leaves a printed
                    // trace when the back office forces it — the terminal's own
                    // frame when it supplied one.
                    if (outcome != null && printPolicy.isTnaReceiptForced()) {
                        ticketPrinterService.printCardTnaReceipt(outcome.amount, outcome.tnaFrame);
                        for (String frame : outcome.additionalReceiptFrames) {
                            ticketPrinterService.printTerminalFrame(frame);
                        }
                    }
                    dropPendingCard(state, i18n.get("op.paiementRefuseTpe"));
                }
            }

            /**
             * Drops the card payment on a terminal failure with the
             * terminal's own message.
             *
             * @param message the operator-facing failure message
             */
            @Override
            public void onError(String message) {
                synchronized (state) {
                    if (claimCardDecision(state, this)) {
                        dropPendingCard(state, message);
                    }
                }
            }
        };
        state.payment.pendingCardCallback = callback;
        if (combined) {
            BigDecimal eligible = mealVoucherAllowance(state);
            terminal.requestCombinedDebit(requested, eligible == null ? BigDecimal.ZERO : eligible.min(requested), callback);
        } else {
            terminal.requestDebit(requested, callback);
        }

        // Rule: card = no drawer opening
        LOGGER.info("Exiting method processCard");
    }

    /**
     * Debits the card for the remaining due PLUS the cash the customer asked
     * for (LC-07-04-08): the cashier keys what the customer wants in cash, and
     * the register computes what the card is debited — no mental arithmetic at
     * the till.
     *
     * @param state the current POS state
     * @param cashback the cash the customer wants back
     */
    public void processCardCashback(PosState state, BigDecimal cashback) {
        LOGGER.info("Entering method processCardCashback with cashback: " + cashback);
        if (!posSettingsService.cashbackEnabled()) {
            state.ticket.setError(i18n.get("op.cashbackIndisponible"));
            state.touch();
            LOGGER.info("Exiting method processCardCashback");
            return;
        }
        BigDecimal remaining = state.getRemaining();
        if (cashback == null || cashback.signum() <= 0 || remaining.signum() <= 0) {
            LOGGER.info("Exiting method processCardCashback");
            return;
        }
        processCard(state, remaining.add(cashback));
        LOGGER.info("Exiting method processCardCashback");
    }

    /**
     * Registers the pending card payment — the accept leg of the terminal
     * callback. Parks the terminal traces (authorization number, degraded-mode
     * indicator) so the money path carries them onto the CardPayment entity
     * (BO-04-01-08/47/49), then clears them.
     *
     * @param state the current POS state
     * @param outcome the terminal outcome carrying the monetique traces
     */
    private void registerAcceptedCard(PosState state,
            com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
        BigDecimal amount = state.payment.pendingCardAmount;
        // LC-07-06-02: D10 is the actual settled amount, potentially a partial TRD debit.
        if (outcome.amount != null) {
            amount = amount.min(outcome.amount);
        }
        if (amount.signum() <= 0) {
            dropPendingCard(state, i18n.get("op.paiementRefuseTpe"));
            return;
        }
        // LC-07-04-07/08: what the bank accepted above the remaining due is the
        // cashback, measured BEFORE the card is registered.
        BigDecimal cashback = amount.subtract(state.getRemaining());
        state.payment.pendingCardAmount = null;
        state.payment.pendingCardAuthNumber = outcome.authorizationNumber;
        state.payment.pendingCardDegraded = outcome.degradedMode;
        // EXP-54: trace the real card entry mode (contactless / chip-and-PIN)
        // the terminal returned, parked here for the money path to carry it.
        state.payment.pendingCardEntryMode = outcome.entryMode;
        // BO-04-01-48: the server the terminal accepted on is parked with them.
        state.payment.pendingMoneticServer = outcome.server;
        // LC-08-03-11: a slip the customer must sign forces the card receipt
        // whatever the cashier chooses. One signed card in the transaction is
        // enough, so the flag only ever goes up.
        if (outcome.signatureRequired) {
            state.payment.cardSignatureRequired = true;
        }
        // BO-03-02-44: the tender whose administered monetics answers carry
        // the terminal's {D16}/{D46} is recorded first; the parameter mapping
        // of LC-07-07-04 remains for the answers no tender names.
        com.intermarche.pos.domain.payment.TenderDefinition mapped =
                tenderRulesService.moneticTender(outcome.meanCode, outcome.alternateMeanCode);
        String tenderCode = null;
        String method;
        if (mapped != null && com.intermarche.pos.domain.payment.PaymentTypes.forKey(mapped.nature()) != null) {
            method = mapped.nature();
            tenderCode = mapped.isAdditional() ? mapped.code : null;
        } else {
            method = VerifonePaymentMapping.resolve(posSettingsService.verifoneMethodMap(),
                    outcome.meanCode, outcome.alternateMeanCode);
        }
        // BO-03-02-01: a card tender the cashier picked names the settlement
        // when the terminal's answer did not name another one.
        String picked = ruleKey(state, "CARD");
        if (tenderCode == null && "CARD".equals(method) && !"CARD".equals(picked)) {
            tenderCode = picked;
        }
        state.payment.selectedTenderCode = null;
        // LC-07-07-04: the accepted bank decision is accounting truth, even if local tender rules changed meanwhile.
        if ("CARD".equals(method)) {
            state.payment.addCardPayment(amount, outcome.authorizationNumber, outcome.degradedMode, outcome.entryMode);
        } else {
            state.payment.addPayment(method, amount);
        }
        var entry = state.payment.payments.get(state.payment.payments.size() - 1);
        entry.tenderCode = tenderCode;
        entry.moneticServer = outcome.server;
        entry.customerReceiptFrame = outcome.customerReceiptFrame;
        entry.merchantReceiptFrame = outcome.merchantReceiptFrame;
        entry.additionalReceiptFrames = outcome.additionalReceiptFrames;
        state.payment.clearTenderHold();
        savePayment(state, method);
        String label = "CARD".equals(method) ? i18n.get("pay.cardDisplayLabel") : method;
        hardwareService.displayMessage(String.format("%-10s%s E", label, df.format(amount)));
        if (cashback.signum() > 0) {
            registerCashback(state, cashback);
        }
        checkCompletion(state);
        state.payment.pendingCardAuthNumber = null;
        state.payment.pendingCardDegraded = false;
        state.payment.pendingCardEntryMode = null;
        state.payment.pendingMoneticServer = null;
        state.touch();
    }

    /**
     * Gives back in cash what the card was debited above the remaining due
     * (LC-07-04-07/08).
     *
     * <p>A NEGATIVE CASH settlement and not a note on the card line: the card
     * line keeps the amount the bank actually debited, which is what the
     * terminal totals are reconciled against, and the notes that leave the
     * drawer are accounted for as cash, which is what the drawer count is
     * reconciled against. The two together still sum to the ticket total. The
     * amount is also the change the screens show, and the drawer opens on it.
     *
     * @param state the current POS state
     * @param cashback the cash to give back, positive
     */
    private void registerCashback(PosState state, BigDecimal cashback) {
        state.payment.addCashPayment(cashback.negate(), null);
        state.payment.payments.get(state.payment.payments.size() - 1).cashback = true;
        savePayment(state, "CASH");
        state.payment.lastChangeAmount = cashback;
        hardwareService.displayMessage(String.format(i18n.get("display.cashback"), df.format(cashback)));
    }

    /**
     * Drops the pending card payment with an operator-facing message — the
     * refuse and error legs of the terminal callback.
     *
     * @param state the current POS state
     * @param message the ticket error to show
     */
    private void dropPendingCard(PosState state, String message) {
        state.payment.pendingCardAmount = null;
        state.payment.selectedTenderCode = null;
        state.ticket.setError(message);
        hardwareService.displayMessage(i18n.both("display.paiementRefuse"));
        state.touch();
    }

    /**
     * Cancels the pending card payment from the register side and tells the
     * terminal to abandon its in-flight transaction.
     *
     * @param state the current POS state
     */
    public void cancelPendingCard(PosState state) {
        LOGGER.info("Entering method cancelPendingCard with state: " + state);
        // Single-writer discipline (see initPayment): a decision arriving while
        // the cashier cancels finds the transaction either still pending or gone.
        synchronized (state) {
            if (state.payment.pendingCardAmount == null) { LOGGER.info("Exiting method cancelPendingCard"); return; }
            state.payment.pendingCardAmount = null;
            state.payment.pendingCardCallback = null;
            state.payment.selectedTenderCode = null;
            terminal.abort();
            hardwareService.displayMessage(String.format(i18n.get("display.total"), df.format(state.ticket.totalAmount)));
            state.touch();
        }
        LOGGER.info("Exiting method cancelPendingCard");
    }

    /**
     * Claims the terminal's decision for the card transaction it belongs to,
     * the caller holding the register's lock: only the callback of the
     * transaction still pending may settle it, and only once — a decision of a
     * transaction the cashier cancelled or replaced, of one whose pending
     * amount was cleared meanwhile, or a second decision of the same one, is
     * ignored.
     *
     * @param state the current POS state
     * @param callback the callback the decision arrived on
     * @return true when the decision is the pending transaction's, now consumed
     */
    private boolean claimCardDecision(PosState state,
            com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback callback) {
        if (state.payment.pendingCardCallback != callback || state.payment.pendingCardAmount == null) {
            LOGGER.warn("Decision du terminal ignoree : transaction deja reglee ou abandonnee");
            return false;
        }
        state.payment.pendingCardCallback = null;
        return true;
    }

    /**
     * Registers a meal-ticket payment; the drawer opens to store the tickets.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processTicketResto(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processTicketResto with state: " + state + ", amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processTicketResto"); return; }

        // Phase 7 lot 3: the engine's MEAL_VOUCHER advantage caps meal tickets
        // at min(eligible base, threshold, requested). The cap applies to the
        // BASKET: the allowance left for this payment is the capped base minus
        // the meal tickets already registered on the ticket. It is derived
        // from the payment list at every call, never kept as a decremented
        // field: each revaluation (every poll of the payment screen) rewrites
        // the engine hints, so a decremented field would silently re-open the
        // allowance and let a second full payment through. No advantage
        // emitted = no cap (local behavior unchanged). Single-writer
        // discipline: the check and the registration must be one atomic step,
        // or two concurrent submissions could both pass the check.
        synchronized (state) {
            boolean engineAnswered = "ENGINE".equals(state.payment.valuationStatus)
                    && state.payment.valuationMealEligible != null;
            // BO-02-03-06: without an engine advantage the cap comes from the
            // ARTICLE ATTRIBUTES carried by the lines. A register in local mode
            // used to accept meal tickets on anything; it now settles at most
            // what the eligible lines are worth. The engine still wins when it
            // answered: it knows the offers, the attributes do not.
            BigDecimal localBase = state.ticket.mealVoucherEligibleTotal();
            // BO-02-03-06: where the shop administers it, a ticket without any
            // eligible article refuses the voucher instead of accepting it on
            // anything — the flag is what makes an article payable by it.
            if (engineAnswered || localBase.signum() > 0 || posSettingsService.mealVoucherEligibleOnly()) {
                BigDecimal allowed = engineAnswered
                        ? state.payment.valuationMealEligible : localBase;
                if (engineAnswered && state.payment.valuationMealThreshold != null) {
                    allowed = allowed.min(state.payment.valuationMealThreshold);
                }
                allowed = allowed.subtract(registeredMealTicketTotal(state)).max(BigDecimal.ZERO);
                if (allowed.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.trAucunArticleEligible"));
                    LOGGER.infof("Paiement TR refusé: assiette éligible épuisée");
                    LOGGER.info("Exiting method processTicketResto");
                    return;
                }
                if (amount.compareTo(allowed) > 0) {
                    amount = allowed;
                    hardwareService.displayMessage(String.format(i18n.get("display.trPlafonne"), df.format(allowed)));
                    LOGGER.infof("Paiement TR plafonné à %s (assiette moteur)", allowed);
                }
            }

            if (!handlePaymentWithChange(state, "TR", "TICKET", amount)) {
                LOGGER.info("Exiting method processTicketResto");
                return;
            }
        }

        // Rule: meal tickets = systematic drawer opening (to store the tickets),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12) or
        // another moment is administered for this tender (BO-03-02-19).
        LOGGER.info("Exiting method processTicketResto");
    }

    /**
     * Sums the meal-ticket payments already registered on the current ticket.
     * The engine's meal-voucher cap applies to the basket, so the allowance
     * left for a new meal ticket is the capped base minus this total. Reading
     * the payment list keeps the rule correct across revaluations (which
     * rewrite the engine hints verbatim) and across payment cancellations
     * (which empty the list and thereby restore the full allowance).
     *
     * @param state the current POS state
     * @return the total of registered TR payments, zero when there is none
     */
    private BigDecimal registeredMealTicketTotal(PosState state) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            // EXP-60/67: the paper voucher, the TRD card and Resto Flash all draw
            // on the SAME meal-voucher eligible base, so all three consume the
            // allowance — else a customer could settle the eligible base once per
            // tender and pay non-eligible goods with a meal voucher.
            if ("TR".equals(entry.method) || "TRD".equals(entry.method)
                    || "RESTOFLASH".equals(entry.method)) {
                total = total.add(entry.amount);
            }
        }
        return total;
    }

    /**
     * The meal-voucher allowance still open on the ticket (EXP-60/67): the
     * eligible base (the engine's when it answered and capped by its threshold,
     * else the base carried by the article attributes) minus the meal-voucher
     * payments already registered. Null means NO base constraint applies (no
     * engine answer and no eligible line) — the historical unconstrained
     * behaviour of a paper meal voucher in local mode.
     *
     * @param state the current POS state
     * @return the remaining allowance (never negative), or null when unconstrained
     */
    private BigDecimal mealVoucherAllowance(PosState state) {
        boolean engineAnswered = "ENGINE".equals(state.payment.valuationStatus)
                && state.payment.valuationMealEligible != null;
        BigDecimal localBase = state.ticket.mealVoucherEligibleTotal();
        if (!engineAnswered && localBase.signum() <= 0) {
            return null;
        }
        BigDecimal allowed = engineAnswered ? state.payment.valuationMealEligible : localBase;
        if (engineAnswered && state.payment.valuationMealThreshold != null) {
            allowed = allowed.min(state.payment.valuationMealThreshold);
        }
        return allowed.subtract(registeredMealTicketTotal(state)).max(BigDecimal.ZERO);
    }

    /**
     * Registers a TRD-card (dematerialized meal-voucher) payment (EXP-60): bound
     * to the meal-voucher eligible base like the paper voucher AND capped by the
     * administered per-transaction plafond ({@code payment.trd-card-cap}, 0 = no
     * cap). Refused when the eligible base is exhausted; plafonné to the smaller
     * of the base allowance and the cap.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processTrdCard(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processTrdCard with amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processTrdCard"); return; }
        synchronized (state) {
            BigDecimal allowance = mealVoucherAllowance(state);
            if (allowance != null) {
                if (allowance.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.trdAucunArticleEligible"));
                    LOGGER.info("Exiting method processTrdCard");
                    return;
                }
                if (amount.compareTo(allowance) > 0) {
                    amount = allowance;
                    hardwareService.displayMessage(String.format(i18n.get("display.trdPlafonne"), df.format(amount)));
                }
            }
            BigDecimal cap = posSettingsService.trdCardCap();
            if (cap != null && cap.signum() > 0 && amount.compareTo(cap) > 0) {
                amount = cap;
                hardwareService.displayMessage(String.format(i18n.get("display.trdPlafonne"), df.format(amount)));
            }
            if (!handlePaymentWithChange(state, "TRD", "CARTE TRD", amount)) {
                LOGGER.info("Exiting method processTrdCard");
                return;
            }
        }
        LOGGER.info("Exiting method processTrdCard");
    }

    /**
     * Registers a SOCIAL PURCHASE CARD payment (LC-08-01-06): bound to the lines
     * whose article is eligible to the card, less what the card already paid on
     * this sale, and to the remaining due — a card gives no change. Refused when
     * nothing eligible is left to pay.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to pay what the card may
     */
    public void processSocialCard(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processSocialCard with amount: " + amount);
        synchronized (state) {
            BigDecimal registered = BigDecimal.ZERO;
            for (PaymentState.PaymentEntry entry : state.payment.payments) {
                if ("CAS".equals(entry.method)) {
                    registered = registered.add(entry.amount);
                }
            }
            BigDecimal allowance = state.ticket.restrictedTenderEligibleTotal(SOCIAL_CARD)
                    .subtract(registered).min(state.getRemaining());
            if (allowance.signum() <= 0) {
                hardwareService.displayMessage(i18n.both("display.casAucunArticleEligible"));
                state.ticket.setError(i18n.get("display.casAucunArticleEligible"));
                LOGGER.info("Exiting method processSocialCard");
                return;
            }
            BigDecimal applied = amount == null || amount.signum() <= 0 ? allowance : amount;
            if (applied.compareTo(allowance) > 0) {
                applied = allowance;
                hardwareService.displayMessage(String.format(i18n.get("display.casPlafonne"), df.format(applied)));
            }
            handlePaymentWithChange(state, "CAS", "CARTE SOCIALE", applied);
        }
        LOGGER.info("Exiting method processSocialCard");
    }

    /**
     * Registers a Resto Flash mobile meal-voucher payment (EXP-67): confirmed
     * through {@link RestoFlashClient} and bound to the SAME meal-voucher
     * eligible base as the other meal vouchers. A token Resto Flash does not
     * confirm settles nothing.
     *
     * @param state the current POS state
     * @param token the mobile payment token presented by the customer
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processRestoFlash(PosState state, String token, BigDecimal amount) {
        LOGGER.info("Entering method processRestoFlash with amount: " + amount);
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processRestoFlash"); return; }
        synchronized (state) {
            BigDecimal allowance = mealVoucherAllowance(state);
            if (allowance != null) {
                if (allowance.signum() <= 0) {
                    hardwareService.displayMessage(i18n.both("display.restoFlashAucunArticle"));
                    LOGGER.info("Exiting method processRestoFlash");
                    return;
                }
                if (amount.compareTo(allowance) > 0) {
                    amount = allowance;
                    hardwareService.displayMessage(String.format(i18n.get("display.restoFlashPlafonne"), df.format(amount)));
                }
            }
            if (!restoFlashClient.confirm(token, amount)) {
                hardwareService.displayMessage(i18n.both("display.restoFlashRefuse"));
                LOGGER.info("Exiting method processRestoFlash");
                return;
            }
            if (!handlePaymentWithChange(state, "RESTOFLASH", "RESTO FLASH", amount)) {
                LOGGER.info("Exiting method processRestoFlash");
                return;
            }
        }
        LOGGER.info("Exiting method processRestoFlash");
    }

    /**
     * Registers a supplier gift-card payment (EXP-66): the issuer registry is
     * interrogated for validity and balance, and the payment is capped at that
     * balance (and at the remaining due). An invalid card, or one with no
     * balance, settles nothing.
     *
     * @param state the current POS state
     * @param cardNumber the scanned or keyed gift-card number
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processSupplierGiftCard(PosState state, String cardNumber, BigDecimal amount) {
        LOGGER.info("Entering method processSupplierGiftCard with amount: " + amount);
        // LC-07-11-01: with a payment hub, the card is first read on the hub and
        // its balance shown; the payment follows for the amount the operator chose.
        // BO-03-02-37: a card whose prepaid range is handled LOCALLY never goes
        // to the hub.
        // BO-03-02-37: the administered TYPE of prepaid card the number falls
        // in rules first — whether it may pay at all, and in which protocol;
        // without one, the protocol of its coupon range still answers.
        com.intermarche.pos.domain.payment.PrepaidCardType type = prepaidCardType(cardNumber);
        if (type != null && !type.paymentAllowed) {
            state.ticket.setError(i18n.format("op.carteNonAccepteePaiement", type.label));
            state.touch();
            LOGGER.info("Exiting method processSupplierGiftCard (type refuses payment)");
            return;
        }
        boolean throughHub = type != null
                ? type.isNexo() && giftCardHubActive(state)
                : giftCardHubActive(state)
                        && !com.intermarche.pos.domain.barcode.CouponType.PROTOCOL_LOCAL.equals(cardProtocol(cardNumber));
        if (throughHub) {
            inquireGiftCard(state, cardNumber);
            LOGGER.info("Exiting method processSupplierGiftCard (hub)");
            return;
        }
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { LOGGER.info("Exiting method processSupplierGiftCard"); return; }
        synchronized (state) {
            GiftCardIssuerClient.IssuerVerdict verdict = giftCardIssuerClient.query(cardNumber);
            if (!verdict.valid()) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauRefusee"));
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            BigDecimal balance = verdict.balance();
            if (balance != null && balance.signum() > 0 && amount.compareTo(balance) > 0) {
                amount = balance;
                hardwareService.displayMessage(String.format(i18n.get("display.carteCadeauMontant"), df.format(amount)));
            } else if (balance == null || balance.signum() <= 0) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauSoldeNul"));
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            if (!handlePaymentWithChange(state, "GIFTCARD", "CARTE CADEAU", amount)) {
                LOGGER.info("Exiting method processSupplierGiftCard");
                return;
            }
            // BO-03-03-11: the slip of a settlement PAID by gift card, which is
            // not the voucher a customer buying one walks away with. What it
            // adds is the balance the card still carries — the one question
            // asked at the counter — and that balance is the issuer's answer
            // minus what was just spent.
            printGiftCardPaymentSlip(state, cardNumber, amount, balance);
        }
        LOGGER.info("Exiting method processSupplierGiftCard");
    }

    /**
     * Whether gift cards are paid through the payment hub (LC-07-11-01).
     *
     * @param state the current POS state
     * @return true when a hub is administered and the register is not training
     */
    public boolean giftCardHubActive(PosState state) {
        return nexoHubClient.isConfigured() && !state.trainingMode;
    }

    /**
     * Reads the administered type of prepaid card a number belongs to
     * (BO-03-02-37), a referential that cannot be read naming none.
     *
     * @param cardNumber the card number
     * @return the type, or null
     */
    private com.intermarche.pos.domain.payment.PrepaidCardType prepaidCardType(String cardNumber) {
        try {
            return com.intermarche.pos.domain.payment.PrepaidCardType.findFor(cardNumber);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the protocol of the prepaid range a card falls in (BO-03-02-37), a
     * referential that cannot be read naming none.
     *
     * @param cardNumber the card number
     * @return the protocol, or null
     */
    private String cardProtocol(String cardNumber) {
        try {
            return com.intermarche.pos.domain.barcode.CouponType.protocolOf(cardNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads a gift card on the payment hub and shows its balance before any
     * payment is asked for (LC-07-11-01, Nexo {@code BalanceInquiry}).
     *
     * @param state the current POS state
     * @param cardNumber the scanned or keyed card code
     */
    public void inquireGiftCard(PosState state, String cardNumber) {
        LOGGER.info("Entering method inquireGiftCard");
        synchronized (state) {
            state.payment.pendingGiftCard = null;
            if (cardNumber == null || cardNumber.isBlank()) {
                state.ticket.setError(i18n.get("op.carteCadeauSansCode"));
                state.touch();
                LOGGER.info("Exiting method inquireGiftCard (no code)");
                return;
            }
            NexoHubClient.HubResult result = nexoHubClient.balance(cardNumber.trim());
            if (!result.success()) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauRefusee"));
                String refusal = hubRefusal("op.carteCadeauRefuseeHub", result, null);
                state.ticket.setError(refusal);
                state.touch();
                LOGGER.info("Exiting method inquireGiftCard (refused)");
                return;
            }
            BigDecimal balance = result.balance() == null ? BigDecimal.ZERO : result.balance();
            if (balance.signum() <= 0) {
                hardwareService.displayMessage(i18n.both("display.carteCadeauSoldeNul"));
                state.ticket.setError(i18n.get("op.carteCadeauSoldeEpuise"));
                state.touch();
                LOGGER.info("Exiting method inquireGiftCard (no balance)");
                return;
            }
            BigDecimal suggested = balance.min(state.getRemaining()).max(BigDecimal.ZERO);
            state.payment.pendingGiftCard = new PaymentState.PendingGiftCard(cardNumber.trim(),
                    balance, result.provider(), suggested);
            hardwareService.displayMessage(String.format(i18n.get("display.carteCadeauSolde"), df.format(balance)));
            state.touch();
        }
        LOGGER.info("Exiting method inquireGiftCard");
    }

    /**
     * Pays with the gift card read on the hub, for the amount the operator chose
     * (LC-07-11-01, Nexo {@code Payment}), and prints its slip where the back
     * office asks for it (LC-07-11-03).
     *
     * @param state the current POS state
     * @param amount the amount chosen, or zero/negative for the proposed one
     */
    public void payGiftCardOnHub(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method payGiftCardOnHub with amount: " + amount);
        synchronized (state) {
            PaymentState.PendingGiftCard card = state.payment.pendingGiftCard;
            if (card == null) {
                LOGGER.info("Exiting method payGiftCardOnHub (no card)");
                return;
            }
            BigDecimal requested = amount == null || amount.signum() <= 0 ? card.suggested : amount;
            requested = requested.min(state.getRemaining());
            if (requested.signum() <= 0) {
                state.payment.pendingGiftCard = null;
                state.touch();
                LOGGER.info("Exiting method payGiftCardOnHub (nothing due)");
                return;
            }
            if (requested.compareTo(card.balance) > 0) {
                state.ticket.setError(i18n.format("op.carteCadeauMontantSuperieurSolde", df.format(card.balance)));
                state.touch();
                LOGGER.info("Exiting method payGiftCardOnHub (above balance)");
                return;
            }
            String reference = (state.payment.ticketDbId == null ? "" : state.payment.ticketDbId + "-")
                    + "GC" + (state.payment.payments.size() + 1);
            NexoHubClient.HubResult result = nexoHubClient.pay(reference, card.cardNumber, requested);
            if (!result.success()) {
                state.payment.pendingGiftCard = null;
                hardwareService.displayMessage(i18n.both("display.carteCadeauRefusee"));
                String refusal = hubRefusal("op.carteCadeauRefuseeHub", result, card.provider);
                state.ticket.setError(refusal);
                state.touch();
                LOGGER.info("Exiting method payGiftCardOnHub (refused)");
                return;
            }
            BigDecimal authorized = result.amount() == null ? requested : result.amount();
            if (!handlePaymentWithChange(state, "GIFTCARD", "CARTE CADEAU", authorized)) {
                // The register refused what the hub accepted (an administered
                // bound): the money goes back to the card.
                nexoHubClient.reversePayment(reference, result.transactionId(), authorized);
                state.payment.pendingGiftCard = null;
                state.touch();
                LOGGER.info("Exiting method payGiftCardOnHub (register refused)");
                return;
            }
            PaymentState.PaymentEntry entry = state.payment.payments.get(state.payment.payments.size() - 1);
            entry.hubReference = result.transactionId();
            entry.hubCardNumber = card.cardNumber;
            entry.hubProvider = result.provider() == null ? card.provider : result.provider();
            entry.hubApproval = result.approvalCode();
            entry.hubBalanceBefore = card.balance;
            entry.hubBalanceAfter = card.balance.subtract(authorized).max(BigDecimal.ZERO);
            state.payment.pendingGiftCard = null;
            if (posSettingsService.giftCardPaymentTicket() && ticketPrinterService != null) {
                try {
                    // BO-03-08-07: the shop's ticket sentence for what the
                    // provider answered, its figures filled in by the printer.
                    ticketPrinterService.printGiftCardPaymentReceipt(draftNumber(state), card.cardNumber,
                            authorized, entry.hubBalanceBefore, entry.hubBalanceAfter, entry.hubProvider,
                            entry.hubApproval, entry.hubReference, result.ticketText(entry.hubProvider));
                } catch (Exception e) {
                    LOGGER.warnf("Justificatif carte cadeau non imprime: %s", e.getMessage());
                }
            }
            state.touch();
        }
        LOGGER.info("Exiting method payGiftCardOnHub");
    }

    /**
     * Puts aside the gift card read on the hub without paying with it.
     *
     * @param state the current POS state
     */
    public void cancelPendingGiftCard(PosState state) {
        LOGGER.info("Entering method cancelPendingGiftCard");
        state.payment.pendingGiftCard = null;
        state.touch();
        LOGGER.info("Exiting method cancelPendingGiftCard");
    }

    /**
     * Gives a gift-card payment back on the hub before it is cancelled
     * (LC-07-11-01, Nexo {@code Reversal}).
     *
     * @param state the current POS state
     * @param entry the settlement to cancel
     * @return true when the settlement owes nothing to the hub any more
     */
    private boolean reverseHubPayment(PosState state, PaymentState.PaymentEntry entry) {
        if (entry.hubReference == null) {
            return true;
        }
        NexoHubClient.HubResult result = nexoHubClient.reversePayment(
                entry.hubReference + "-R", entry.hubReference, entry.amount);
        if (!result.success()) {
            String refusal = hubRefusal("op.annulationCarteCadeauRefusee", result, entry.hubProvider);
            state.ticket.setError(refusal);
            return false;
        }
        entry.hubReference = null;
        entry.hubReversed = true;
        return true;
    }

    /**
     * The operator's message of a hub refusal.
     *
     * @param key the message pattern, taking the hub's reason
     * @param result the hub's answer
     * @param provider the provider the request went to, or null when unknown
     * @return the message
     */
    private String hubRefusal(String key, NexoHubClient.HubResult result, String provider) {
        if (result.unavailable()) {
            return i18n.get("op.hubIndisponible");
        }
        // BO-03-08-06: the shop's own sentence for this provider's code, when
        // it wrote one, replaces the provider's words.
        String administered = result.screenText(provider);
        if (administered != null) {
            return administered;
        }
        return i18n.format(key, result.message() == null ? result.errorCondition() : result.message());
    }

    /**
     * The number of the draft the sale is on, or null.
     *
     * @param state the current POS state
     * @return the draft's number, or null
     */
    private String draftNumber(PosState state) {
        if (state.payment.ticketDbId == null) {
            return null;
        }
        com.intermarche.pos.domain.sale.Ticket ticket =
                com.intermarche.pos.domain.sale.Ticket.findById(state.payment.ticketDbId);
        return ticket == null ? null : ticket.ticketNumber;
    }

    /**
     * Registers a SOGEC bon payment through the compensation circuit (EXP-58):
     * the bon settles the ticket and a compensation trace is recorded for the
     * supplier reimbursement — distinct from the generic GS1 coupon path.
     *
     * <p>THE BON SETTLES ITS OWN FACE VALUE, never the remaining due. It used to
     * settle the remainder whenever no amount reached it, and the screen sent none:
     * a 20 € bon closed a 56,50 € ticket and the receipt came out paid in full by
     * SOGEC. The value is the one the paper states — decoded from the bon's own
     * barcode when it carries it ({@code (390n)}), keyed by the cashier otherwise —
     * and a bon with no value settles NOTHING, because guessing it is exactly the
     * bug.
     *
     * <p>A bon worth more than what is left to pay is CAPPED at the remainder and
     * says so, like the meal-voucher allowance and the gift-card balance: the
     * register never hands change back against a manufacturer's coupon.
     *
     * @param state the current POS state
     * @param couponCode the SOGEC bon code
     * @param amount the face value keyed by the cashier, ignored when the bon's own
     *        barcode carries one
     */
    public void processSogec(PosState state, String couponCode, BigDecimal amount) {
        LOGGER.info("Entering method processSogec with amount: " + amount);
        BigDecimal faceValue = sogecCompensationService.faceValueOf(couponCode);
        if (faceValue == null) {
            faceValue = amount;
        }
        if (faceValue == null || faceValue.signum() <= 0) {
            hardwareService.displayMessage(i18n.both("display.bonSogecValeurRequise"));
            LOGGER.info("Exiting method processSogec: aucune valeur");
            return;
        }
        BigDecimal applied;
        synchronized (state) {
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) {
                LOGGER.info("Exiting method processSogec: rien a payer");
                return;
            }
            applied = faceValue.min(remaining);
            if (applied.compareTo(faceValue) < 0) {
                hardwareService.displayMessage(
                        String.format(i18n.get("display.bonSogecPlafonne"), df.format(applied)));
            }
            if (!handlePaymentWithChange(state, "SOGEC", "BON SOGEC", applied)) {
                LOGGER.info("Exiting method processSogec");
                return;
            }
        }
        sogecCompensationService.recordCompensation(couponCode, applied);
        LOGGER.info("Exiting method processSogec");
    }

    /**
     * Registers a manual deferred cheque under its own accounting tender
     * (LC-07-05-05), with the same administered bounds and change rules.
     * @param state the current register state
     * @param amount the cheque amount, null or nonpositive for the remainder
     */
    public void processDeferredCheque(PosState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            amount = state.getRemaining();
        }
        if (amount.signum() <= 0) {
            return;
        }
        if (!handlePaymentWithChange(state, "CHEQUE_DEFERRED", i18n.get("pay.deferredCheque"), amount)) {
            return;
        }
    }

    /**
     * Registers a cheque payment; the drawer opens to store the cheque.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCheque(PosState state, BigDecimal amount) {
        processCheque(state, amount, null);
    }

    /**
     * Registers a cheque payment carrying the account number the cashier keyed
     * (BO-03-02-24).
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     * @param accountNumber the keyed account number, or null/blank when none
     */
    public void processCheque(PosState state, BigDecimal amount, String accountNumber) {
        LOGGER.info("Entering method processCheque with state: " + state + ", amount: " + amount);
        state.payment.pendingChequeAccount = accountNumber == null || accountNumber.isBlank()
                ? null : accountNumber.trim();
        takeCheque(state, amount);
        LOGGER.info("Exiting method processCheque");
    }

    /**
     * Takes a cheque under the administered rules of its tender (BO-03-02-24):
     * the account number when it is asked for, the supervisor's authorization
     * when it is required, the MICR reading when it is administered, and the
     * automatic printing of its front.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    private void takeCheque(PosState state, BigDecimal amount) {
        if (state.payment.chequeFrontPrinting) {
            state.ticket.setError(i18n.get("pay.chequePrintingBusy"));
            return;
        }
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) { return; }
        if (state.trainingMode) {
            registerCheque(state, amount);
            return;
        }
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                tenderRulesService.tender(ruleKey(state, "CHEQUE"));
        if (tender != null && tender.accountNumberEntry && state.payment.pendingChequeAccount == null) {
            state.ticket.setError(i18n.get("op.numeroCompteChequeObligatoire"));
            state.touch();
            return;
        }
        TenderRulesService.Verdict authorization = new TenderRulesService.Verdict(
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                i18n.get("op.autorisationChequeRequise"));
        if (tender != null && tender.chequeAuthorization
                && !overrideCovers(state, "CHEQUE", authorization)) {
            // BO-03-02-24: the cheque-guarantee service is asked first when the
            // back office administers one; the supervisor is the fallback.
            ChequeGuaranteeClient.Verdict verdict = chequeGuaranteeClient.isConfigured()
                    ? chequeGuaranteeClient.authorize(java.util.Objects.toString(state.payment.ticketDbId, ""),
                            amount, state.payment.pendingChequeAccount)
                    : null;
            if (verdict != null && verdict.status() == ChequeGuaranteeClient.Status.REFUSED) {
                state.payment.pendingChequeAccount = null;
        state.payment.pendingChequeGuarantee = null;
        state.payment.pendingChequeGuaranteeProvider = null;
                state.ticket.setError(i18n.format("op.chequeRefuseGarantie",
                        verdict.message() == null ? "" : verdict.message()));
                state.touch();
                return;
            }
            if (verdict == null || verdict.status() == ChequeGuaranteeClient.Status.UNAVAILABLE) {
                holdForSupervisor(state, "CHEQUE", "CHEQUE", amount, verdict == null ? authorization
                        : new TenderRulesService.Verdict(
                                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                                i18n.get("op.garantieChequeIndisponible")));
                state.payment.chequeHeldBeforeReading = true;
                state.touch();
                return;
            }
            state.payment.pendingChequeGuarantee = verdict.authorization();
            state.payment.pendingChequeGuaranteeProvider = verdict.provider();
            state.ticket.setNotice(i18n.format("op.chequeGaranti", verdict.authorization()));
        }
        boolean read = tender == null || tender.micrReading;
        boolean autoPrint = tender != null && tender.chequeAutoPrint;
        if (!read && !autoPrint) {
            registerCheque(state, amount);
            return;
        }
        // The cheque is READ BEFORE it is registered. A payment settled first and read
        // afterwards would leave a paid line on the ticket for a cheque the reader
        // refused, and the cashier would have to undo a payment instead of simply
        // being told to present the cheque again.
        state.payment.pendingChequeAmount = amount;
        state.payment.pendingChequeLine = null;
        state.touch();
        // Built HERE, on the request thread: the reader prints the endorsement while it
        // still holds the cheque, so the text must leave with the start of the reading.
        String endorsement = endorsementText(state, amount);
        if (autoPrint) {
            // BO-03-02-24: the front passes the printer first; the reading — or,
            // with no reader administered, the registration — follows its success.
            String front = chequeText(state, amount, posSettingsService.value("cheque.front-lines"));
            state.payment.chequeFrontPrinting = true;
            chequeReadingService.printFront(front, ignored -> {
                state.payment.chequeFrontPrinting = false;
                if (read) {
                    readCheque(state, endorsement);
                } else {
                    depositChequeRead(state, "");
                }
            }, error -> {
                state.payment.chequeFrontPrinting = false;
                depositChequeFailure(state, error);
            });
            return;
        }
        readCheque(state, endorsement);
    }

    /**
     * Starts the MICR reading of the pending cheque.
     *
     * @param state the current POS state
     * @param endorsement the endorsement printed on the back during the reading
     */
    private void readCheque(PosState state, String endorsement) {
        // The two callbacks fire on the reader's follower thread and only DEPOSIT
        // their outcome: registering from there would make a second writer of the
        // draft row the request thread rewrites on every revaluation.
        chequeReadingService.read(
                endorsement,
                line -> depositChequeRead(state, line),
                message -> depositChequeFailure(state, message));
    }

    /** Prints LC-07-05-06 cheque front independently of payment validation.
     * @param state current sale
     * @param amount cheque amount, null or nonpositive for remaining due
     */
    public void printChequeFront(PosState state, BigDecimal amount) {
        if (state.payment.chequeFrontPrinting || state.payment.pendingChequeAmount != null) {
            state.ticket.setError(i18n.get("pay.chequePrintingBusy"));
            return;
        }
        BigDecimal requested = amount == null || amount.signum() <= 0 ? state.getRemaining() : amount;
        if (requested.signum() <= 0) {
            return;
        }
        if (state.trainingMode) {
            state.ticket.setNotice(i18n.get("pay.chequePrinted"));
            state.touch();
            return;
        }
        String front = chequeText(state, requested, posSettingsService.value("cheque.front-lines"));
        state.payment.chequeFrontPrinting = true;
        state.ticket.setNotice(i18n.get("pay.chequeInsertFront"));
        state.touch();
        chequeReadingService.printFront(front, ignored -> {
            state.payment.chequeFrontPrinting = false;
            state.ticket.setNotice(i18n.get("pay.chequePrinted"));
            state.touch();
        }, error -> {
            state.payment.chequeFrontPrinting = false;
            state.ticket.setError(error);
            state.touch();
        });
    }

    /** Resolves optional PDV cheque data, never printing a null value (LC-07-05-07).
     * @param key catalogue parameter
     * @return administered text or empty
     */
    private String chequeParameter(String key) {
        return posSettingsService == null ? "" : safeText(posSettingsService.value(key));
    }

    /**
     * Composes what is printed on the back of the cheque.
     * <p>
     * LC-07-05-03: the administered layout can carry the amount in words and
     * sale identities. The draft is read only when its ticket number is requested.
     *
     * @param amount the amount the cheque settles
     * @param state the current sale supplying its actual ticket identity (LC-07-05-03)
     * @return the endorsement, one line per newline
     */
    private String endorsementText(PosState state, BigDecimal amount) {
        String administered = posSettingsService == null
                ? null : posSettingsService.chequeEndorsementLines();
        return chequeText(state, amount, administered);
    }

    /** Renders the administered cheque front or back with the real sale identities.
     * @param state current sale
     * @param amount cheque amount
     * @param administered semicolon-separated template
     * @return printable document
     */
    private String chequeText(PosState state, BigDecimal amount, String administered) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (administered == null || administered.isBlank()) {
            // Nothing administered: the date and the amount, exactly as before
            // the key existed (BO-03-02-43).
            return now.format(ENDORSEMENT_DATE) + "\n" + df.format(amount) + " EUR";
        }
        String ticketNumber = "";
        if (administered.contains("{ticket}")) {
            if (state.payment.ticketDbId != null) {
                com.intermarche.pos.domain.sale.Ticket ticket =
                        com.intermarche.pos.domain.sale.Ticket.findById(state.payment.ticketDbId);
                if (ticket != null) {
                    ticketNumber = safeText(ticket.ticketNumber);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : administered.split(";")) {
            String rendered = fillEndorsementTokens(line.trim(), now, amount, ticketNumber);
            if (rendered.isEmpty()) {
                // An empty administered line prints nothing rather than a blank
                // band: paper on the back of a cheque is scarce.
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rendered);
        }
        return sb.toString();
    }

    /**
     * Substitutes the endorsement tokens of one administered line
     * (BO-03-02-43).
     *
     * <p>A token the register cannot answer renders EMPTY rather than leaving
     * the token visible: a cheque carrying {@code {pdv}} in ink is worse than
     * one carrying nothing there.
     *
     * @param line the administered line
     * @param now the moment the cheque is being taken
     * @param amount the amount the cheque settles
     * @param ticketNumber the actual draft ticket number (LC-07-05-03)
     * @return the line with its tokens replaced
     */
    private String fillEndorsementTokens(String line, java.time.LocalDateTime now,
                                         BigDecimal amount, String ticketNumber) {
        return line
                .replace("{date}", now.format(java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM/yyyy")))
                .replace("{heure}", now.format(java.time.format.DateTimeFormatter
                        .ofPattern("HH:mm")))
                .replace("{montant}", df.format(amount))
                .replace("{montant-lettres}", ChequeAmountWords.format(amount, i18n,
                        chequeParameter("cheque.unit-text"), chequeParameter("cheque.cent-text")))
                .replace("{ticket}", ticketNumber)
                .replace("{beneficiaire}", chequeParameter("cheque.beneficiary"))
                .replace("{lieu}", chequeParameter("cheque.place"))
                .replace("{banque}", chequeParameter("cheque.bank-reference"))
                .replace("{compte}", chequeParameter("cheque.account-number"))
                .replace("{caisse}", ticketNumberService == null
                        ? "" : safeText(ticketNumberService.getTerminalId()))
                .replace("{pdv}", safeText(pdvNumber == null ? null : pdvNumber.orElse(null)))
                .trim();
    }

    /**
     * Returns a text, never null.
     *
     * @param value the text, or null
     * @return the text, or the empty string
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Deposits a finished reading for the next request to register.
     *
     * <p>Called from the reader's follower thread. It writes nothing but the
     * deposit and bumps the state version, which is what makes the polling screen
     * reload within a second and carry the registration on its own thread.
     *
     * @param state the current POS state
     * @param line the magnetic line the reader returned
     */
    private void depositChequeRead(PosState state, String line) {
        state.payment.chequeReadLine = line;
        state.touch();
    }

    /**
     * Deposits a failed reading for the next request to report.
     *
     * @param state the current POS state
     * @param message the operator-facing message the reader reported
     */
    private void depositChequeFailure(PosState state, String message) {
        state.payment.chequeReadFailure = message;
        state.touch();
    }

    /**
     * Settles a cheque reading deposited by the follower thread, on the REQUEST
     * thread that calls this.
     *
     * <p>The register writes its draft from one thread at a time: the reading ends
     * far from the page — like a badge on the scan bus — and only the poll can
     * bring its outcome back into a request. A reading that lands after the
     * cashier cancelled changes nothing: the deposit is consumed and dropped.
     *
     * @param state the current POS state
     */
    public void settlePendingChequeRead(PosState state) {
        String line;
        String failure;
        synchronized (state) {
            line = state.payment.chequeReadLine;
            failure = state.payment.chequeReadFailure;
            if (line == null && failure == null) {
                return;
            }
            state.payment.chequeReadLine = null;
            state.payment.chequeReadFailure = null;
        }
        LOGGER.info("Entering method settlePendingChequeRead");
        if (failure != null) {
            dropPendingCheque(state, failure);
            LOGGER.info("Exiting method settlePendingChequeRead");
            return;
        }
        registerReadCheque(state, line);
        LOGGER.info("Exiting method settlePendingChequeRead");
    }

    /**
     * Registers the cheque the reader read, on the request thread.
     *
     * <p>The amount is the one the cashier asked for, still held in
     * {@code pendingChequeAmount}: its absence means the cashier cancelled while
     * the document was being read, and a payment appearing by itself on a ticket
     * they moved on from is worse than a cheque re-presented.
     *
     * @param state the current POS state
     * @param line the magnetic line, kept for the journal
     */
    private void registerReadCheque(PosState state, String line) {
        BigDecimal amount;
        synchronized (state) {
            amount = state.payment.pendingChequeAmount;
            if (amount == null) {
                LOGGER.info("Cheque lu apres annulation cote caisse : ignore");
                return;
            }
            state.payment.pendingChequeAmount = null;
            // An empty line is a cheque taken with no reader (BO-03-02-24).
            state.payment.pendingChequeLine = line.isEmpty() ? null : line;
        }
        LOGGER.infof("Cheque lu : %s", line);
        registerCheque(state, amount);
    }

    /**
     * Drops the pending cheque and tells the cashier why.
     *
     * @param state the current POS state
     * @param message the operator-facing message
     */
    private void dropPendingCheque(PosState state, String message) {
        if (state.payment.pendingChequeAmount == null) {
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = null;
        state.payment.pendingChequeAccount = null;
        state.payment.pendingChequeGuarantee = null;
        state.payment.pendingChequeGuaranteeProvider = null;
        state.payment.selectedTenderCode = null;
        state.ticket.setError(message);
        state.touch();
    }

    /**
     * Cancels the pending cheque from the register side.
     *
     * <p>The reader is not told: it has no abort, and the document it is holding is
     * given back by its own timeout. Only the register stops expecting a cheque.
     *
     * @param state the current POS state
     */
    public void cancelPendingCheque(PosState state) {
        LOGGER.info("Entering method cancelPendingCheque with state: " + state);
        if (state.payment.pendingChequeAmount == null) {
            LOGGER.info("Exiting method cancelPendingCheque");
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = null;
        state.payment.pendingChequeAccount = null;
        state.payment.pendingChequeGuarantee = null;
        state.payment.pendingChequeGuaranteeProvider = null;
        state.payment.selectedTenderCode = null;
        // A deposit already made by the follower must go with it: the cashier
        // moved on, and that reading must never settle later.
        state.payment.chequeReadLine = null;
        state.payment.chequeReadFailure = null;
        state.touch();
        LOGGER.info("Exiting method cancelPendingCheque");
    }

    /**
     * Settles a cheque payment, whatever led to it.
     *
     * @param state the current POS state
     * @param amount the amount applied to the ticket
     */
    private void registerCheque(PosState state, BigDecimal amount) {
        if (!handlePaymentWithChange(state, "CHEQUE", "CHEQUE", amount)) {
            return;
        }
        // Ephemeral, like the card traces: cleared once the entry carries it, so the
        // next cheque of the same ticket cannot inherit this one's line.
        state.payment.pendingChequeLine = null;
        state.payment.pendingChequeAccount = null;
        state.payment.pendingChequeGuarantee = null;
        state.payment.pendingChequeGuaranteeProvider = null;

        // Rule: cheque = systematic drawer opening (to store the cheque),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12) or
        // another moment is administered for this tender (BO-03-02-19).
    }

    /**
     * Applies the administered drawer rule of every tender used by the completed
     * sale (LC-07-16-01). Untouched physical tenders retain the global default.
     * A training sale opens the drawer like a real one only where the shop
     * administers the opening in training mode (BO-10-02-12).
     * @param state the current register state
     * @param receiptPrinted true immediately after printing the sale receipt
     */
    public void openSettlementDrawer(PosState state, boolean receiptPrinted) {
        if (state.trainingMode && !posSettingsService.drawerOpenInTraining()) {
            return;
        }
        if (!state.payment.transactionComplete) {
            return;
        }
        if (state.payment.settlementDrawerOpened) {
            return;
        }
        boolean changeDue = state.payment.lastChangeAmount != null
                && state.payment.lastChangeAmount.signum() > 0;
        for (PaymentState.PaymentEntry entry : state.payment.payments) {
            // LC-07-15-03: the cash changer holds its own cash; the drawer has
            // nothing to receive.
            if (entry.method == null || entry.cashChanger) {
                continue;
            }
            boolean physical = java.util.Set.of("CASH", "CHEQUE", "CHEQUE_DEFERRED", "TR", "DEVISE")
                    .contains(entry.method);
            boolean fallback = physical && !receiptPrinted && posSettingsService.drawerOpenOnPayment();
            if (tenderRulesService.opensDrawer(entry.method, changeDue, receiptPrinted, fallback)) {
                hardwareService.openDrawer();
                state.payment.settlementDrawerOpened = true;
                return;
            }
        }
    }

    /**
     * Registers a fidelity (virtual) payment capped at the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processFidelity(PosState state, BigDecimal amount) {
        LOGGER.info("Entering method processFidelity with state: " + state + ", amount: " + amount);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.getRemaining().signum() <= 0) { LOGGER.info("Exiting method processFidelity"); return; }

            // LC-07-10-05: repeated clicks cannot replace the amount on the slip.
            if (state.payment.pendingFidelitySignatureAmount != null) {
                return;
            }
            state.payment.clearPendingVoucher();

            // Reservation protocol (imfid spec §5): the lease is granted for
            // min(requested, remaining, burnableBase, availableBalance); no euro
            // moves before the fiscal confirmation. Refusals carry their exact
            // display message (closed nomenclature mapped by the service).
            com.intermarche.pos.ui.fidelity.FidelityService.BurnVerdict verdict =
                    fidelityService.reserveLease(state, amount, state.getRemaining());
            if (verdict.refusalMessage != null) {
                state.ticket.setError(verdict.refusalMessage);
                state.touch();
                LOGGER.info("Exiting method processFidelity");
                return;
            }
            state.payment.pendingFidelitySignatureAmount = verdict.grantedAmount;
            ticketPrinterService.printFidelitySignatureSlip(verdict.grantedAmount, state.fidelity.label);
            state.touch();
        }
        LOGGER.info("Exiting method processFidelity");
    }

    /**
     * Records the loyalty settlement only after the cashier confirms the paper
     * was signed, or releases the reservation on refusal (LC-07-10-05).
     * @param state the current register state
     * @param signed true when the customer signed the printed slip
     */
    public void confirmFidelitySignature(PosState state, boolean signed) {
        synchronized (state) {
            BigDecimal amount = state.payment.pendingFidelitySignatureAmount;
            if (amount == null) {
                return;
            }
            state.payment.pendingFidelitySignatureAmount = null;
            if (!signed) {
                fidelityService.releaseLease(state);
                state.touch();
                return;
            }
            // BO-10-03-06: the settlement is registered under the administered tender.
            String tender = com.intermarche.pos.ui.fidelity.FidelityService.tenderOrDefault(
                    posSettingsService.fidelityPaymentTender());
            state.payment.addPayment(tender, amount);
            savePayment(state, tender);
            hardwareService.displayMessage(String.format(i18n.get("display.fidelite"), df.format(amount)));
            checkCompletion(state);
            state.touch();
        }
    }

    /**
     * Registers a customer-credit settlement ({@code LC-07-09-01}).
     *
     * <p>Nothing is collected, so nothing is capped for change either: the caller —
     * {@link CreditClientService} — has already resolved the account, the ceiling and
     * the amount, and this method is what every other method's wrapper is, the thin
     * registration step. THE DRAWER STAYS SHUT: no money changes hands, and a drawer
     * that opened on a credit sale would be an invitation.
     *
     * @param state the current POS state
     * @param customer the account the debt is charged to
     * @param amount the amount to charge, already capped at the remaining due
     * @param overLimit true when a supervisor allowed the ceiling to be passed
     */
    public void processCredit(PosState state, com.intermarche.pos.domain.payment.AccountCustomer customer,
            BigDecimal amount, boolean overLimit) {
        LOGGER.info("Entering method processCredit with state: " + state + ", customer: " + customer + ", amount: " + amount + ", overLimit: " + overLimit);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (customer == null || amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processCredit"); return; }
            if (state.getRemaining().signum() <= 0) { LOGGER.info("Exiting method processCredit"); return; }

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(state.getRemaining());
            state.payment.addCreditPayment(amountToPay, customer.accountNumber,
                    customer.getDisplayName(), overLimit, creditTender());
            state.touch();
            savePayment(state, "CREDIT");
            hardwareService.displayMessage(
                    String.format(i18n.get("display.creditClient"), df.format(amountToPay)));

            // Rule: customer credit = no drawer opening (nothing physical).

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processCredit");
    }

    /**
     * Registers a voucher payment, displays it and persists it.
     * <p>
     * The amount is assumed already capped at the remaining due by the caller.
     *
     * @param state the current POS state
     * @param label the voucher type label shown to the cashier
     * @param number the voucher number, or null when there is none
     * @param amount the paid amount
     */
    public void processVoucher(PosState state, String label, String number, BigDecimal amount) {
        LOGGER.info("Entering method processVoucher with state: " + state + ", label: " + label + ", number: " + number + ", amount: " + amount);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method processVoucher"); return; }

            state.payment.addVoucherPayment(label, number, amount);
            state.touch();
            saveVoucherPayment(state);
            hardwareService.displayMessage(String.format("%-10s%s E", i18n.both("display.bon"), df.format(amount)));

            // Rule: voucher = no drawer opening (virtual)

            checkCompletion(state);
        }
        LOGGER.info("Exiting method processVoucher");
    }

    /**
     * Applies the cashier's end-of-transaction printing choice (LC-08-03-01 to
     * LC-08-03-06): the documents the {@link
     * com.intermarche.pos.ui.hardware.PrintPolicy} names for that choice are
     * sent to the printer at once.
     * <p>
     * Only the two documents that exist BEFORE the fiscal moment are printed
     * here — the sale ticket (printed from the still-open draft, so it is the
     * original and not a duplicata, exactly like the historical IMPRIMER
     * button) and the card receipt. The purchase vouchers are born at
     * validation, so {@link #finalizeTransaction} prints them under the very
     * same decision.
     * <p>
     * The choice is applied ONCE: a second call is ignored, so a reloaded
     * screen never prints a second original.
     *
     * @param state the current POS state
     * @param choice the cashier's choice, never null
     */
    public void applyPrintChoice(PosState state, com.intermarche.pos.ui.hardware.PrintChoice choice) {
        LOGGER.info("Entering method applyPrintChoice with state: " + state + ", choice: " + choice);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.payment.printApplied) { LOGGER.info("Exiting method applyPrintChoice"); return; }
            Long ticketId = state.payment.ticketDbId;
            if (ticketId == null) { LOGGER.info("Exiting method applyPrintChoice"); return; }
            // BO-03-03-26/-27/-35: the receipt chosen here is printed before the
            // closing; the loyalty zone it states is frozen first, or the paper
            // would leave out the messages and balances of the sale.
            freezeFidelityForPrint(state, ticketId);
            state.payment.printChoice = choice;
            com.intermarche.pos.domain.sale.Ticket ticket =
                    com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
            com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                    printPolicy.decide(choice, ticket, state.payment.cardSignatureRequired);
            if (decision.saleTicket()) {
                // BO-03-03-36: the exit ticket REPLACES the sale receipt where
                // the store administers it — substitutive, not one ticket more.
                // It answers zero when it is not administered, and the receipt
                // prints exactly as before.
                // LC-13-06-13: the exit ticket faces the customer too, in their language.
                if (i18n.forCustomer(() -> ticketPrinterService.printExitTicket(ticketId)) == 0) {
                    printSaleWithMoneticPolicy(state, ticketId, decision.cardReceipt());
                }
                // LC-05-07-02: the detail of the returns goes with the receipt the
                // customer chose to print.
                ticketPrinterService.printReturnDetail(ticketId);
                openSettlementDrawer(state, true);
            }
            if (decision.cardReceipt()) {
                printMoneticReceipts(state, ticketId);
            }
            // BO-03-02-26, -31, -32 and -33: the slip of each tender used,
            // AFTER the receipt and not instead of it. A tender administered
            // "forced printing" comes out even when the receipt did not.
            ticketPrinterService.printTenderSlips(ticket, decision.saleTicket());
            state.payment.printApplied = true;
            state.touch();
        }
        LOGGER.info("Exiting method applyPrintChoice");
    }

    /** Whether this sale holds any ephemeral WCL frames (LC-07-04-10).
     * @param state current sale
     * @return whether vendor frames are present
     */
    public boolean hasMoneticFrames(PosState state) {
        return state.payment.payments.stream().anyMatch(entry -> entry.customerReceiptFrame != null
                || entry.merchantReceiptFrame != null
                || entry.additionalReceiptFrames != null && !entry.additionalReceiptFrames.isEmpty());
    }

    /** Prints the legacy sale button with the administered attachment policy (LC-07-04-11).
     * @param state current sale
     * @param ticketId persisted sale
     */
    public void printTicketWithMoneticFrames(PosState state, Long ticketId) {
        printSaleWithMoneticPolicy(state, ticketId, true);
        printMoneticReceipts(state, ticketId);
        state.payment.printApplied = true;
    }

    /** Applies the administered attachment cut only when a customer frame will follow.
     * @param state current sale
     * @param ticketId persisted sale identifier
     * @param cardReceipt whether card printing was requested
     */
    private void printSaleWithMoneticPolicy(PosState state, Long ticketId, boolean cardReceipt) {
        boolean attach = cardReceipt && cardReceiptWanted(state) && posSettingsService.cardReceiptAttached()
                && state.payment.payments.stream().anyMatch(entry -> entry.customerReceiptFrame != null);
        // LC-13-06-13: the receipt of the sale under way, in the customer's language.
        i18n.runForCustomer(() -> {
            if (attach) {
                ticketPrinterService.printTicket(ticketId, false);
            } else {
                ticketPrinterService.printTicket(ticketId);
            }
        });
    }

    /**
     * Whether the card receipt is printed at all (BO-03-02-34): the store's
     * choice — attached to the receipt, separate, or not printed — except for a
     * slip the customer must sign, which is always printed (LC-08-03-11).
     *
     * @param state the current sale
     * @return true when the card receipt goes to the printer
     */
    private boolean cardReceiptWanted(PosState state) {
        return state.payment.cardSignatureRequired || posSettingsService.cardReceiptPrinted();
    }

    /** Prints WCL frames verbatim; virtual/legacy terminals retain the existing generated slip.
     * @param state current sale with ephemeral frames
     * @param ticketId persisted sale identifier
     */
    private void printMoneticReceipts(PosState state, Long ticketId) {
        if (!cardReceiptWanted(state)) {
            // BO-03-02-34: no card receipt in this store — the customer frame is
            // still ARCHIVED, so the duplicate remains available on demand.
            for (var entry : state.payment.payments) {
                if (entry.customerReceiptFrame != null) {
                    ticketPrinterService.archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, ticketId, entry.customerReceiptFrame);
                }
            }
            return;
        }
        boolean frames = false;
        for (var entry : state.payment.payments) {
            if (entry.customerReceiptFrame != null) {
                ticketPrinterService.archiveCustomerDocument(com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, ticketId, entry.customerReceiptFrame);
                ticketPrinterService.printTerminalFrame(entry.customerReceiptFrame, entry.cardEntryMode);
                frames = true;
            }
            if (entry.merchantReceiptFrame != null) {
                ticketPrinterService.printTerminalFrame(entry.merchantReceiptFrame, entry.cardEntryMode);
                frames = true;
            }
        }
        for (var entry : state.payment.payments) {
            if (entry.additionalReceiptFrames != null) {
                for (String frame : entry.additionalReceiptFrames) {
                    ticketPrinterService.printTerminalFrame(frame, entry.cardEntryMode);
                    frames = true;
                }
            }
        }
        if (!frames) {
            ticketPrinterService.printCardReceipt(ticketId, state.payment.cardSignatureRequired, null);
        }
    }

    // --------------------------------------------------
    // 4. FINALIZATION
    // --------------------------------------------------

    /**
     * Closes the transaction: validates the ticket in database, remembers it
     * as the last closed ticket and clears the in-memory state.
     *
     * <p>Only a SETTLED sale is closed ({@link #isSettled(PosState)}): the
     * closing is reachable by a plain GET, so the server — not the completion
     * modal that offers the button — decides. A sale with an amount still due,
     * or a net refund not yet paid out, is refused: the ticket stays open, the
     * draft is untouched and the operator reads what remains.
     *
     * @param state the current POS state
     * @return true when the sale was closed, false when it was refused as unsettled
     */
    public boolean finalizeTransaction(PosState state) {
        LOGGER.info("Entering method finalizeTransaction with state: " + state);
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (!isSettled(state)) {
                BigDecimal remaining = state.getRemaining();
                String key = remaining.signum() > 0 ? "op.venteNonSoldee" : "op.remboursementNonEffectue";
                state.ticket.setError(i18n.format(key, df.format(remaining.abs())));
                state.touch();
                LOGGER.warn("Closing refused: the sale is not settled, remaining " + remaining);
                LOGGER.info("Exiting method finalizeTransaction");
                return false;
            }
            Long ticketId = state.payment.ticketDbId;

            if (ticketId != null) {
                ticketPersistenceService.validateTicket(ticketId);
                state.lastClosedTicketId = ticketId;
                state.lastClosedTicketEmail = state.fidelity.holderEmail;
                LOGGER.info("Ticket validé et fermé en BDD ID: " + ticketId);
                // GB-01-07-05/06: freeze the engine offer messages on the ticket
                // so the receipt (and any reprint) states the very texts the
                // seller was shown. Empty in LOCAL/DEGRADED and when the engine
                // emitted none, in which case nothing is stored or printed.
                java.util.List<com.intermarche.pos.domain.sale.TicketMessage> ticketMessages =
                        new java.util.ArrayList<>();
                for (com.intermarche.pos.ui.valuation.ValuationService.EngineMessage engineMessage
                        : state.payment.valuationMessages) {
                    ticketMessages.add(new com.intermarche.pos.domain.sale.TicketMessage(
                            engineMessage.code, engineMessage.text, engineMessage.printMode));
                }
                ticketPersistenceService.storeEngineMessages(ticketId, ticketMessages);
                // Campaigns & budgets spec §3: a finalized sale valued by the
                // engine declares its consumption, derived from the retained
                // valuation (read here, before clearTicket, since the verbatim
                // offerCode lives only on the payment state). LOCAL/DEGRADED
                // applied no engine advantage, so nothing is declared. This only
                // enqueues a row — the delivery loop ships it, the sale never
                // waits on it.
                if ("ENGINE".equals(state.payment.valuationStatus)) {
                    com.intermarche.pos.domain.sale.Ticket valued =
                            com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                    if (valued != null) {
                        consumptionService.enqueueSale(valued.ticketNumber,
                                valued.store == null ? null : valued.store.code,
                                valued.closingDate, state.payment.valuationJson);
                    }
                }
                // Loyalty fiscal sequence (imfid spec §5.2 + §6): confirm the
                // lease (410 tolerated — the ingestion is authoritative), then
                // enqueue the ticket-closed event with the verbatim couple. The
                // outbox drains it; imfid recomputes and the recalcul fait foi.
                if (state.fidelity.active) {
                    java.time.LocalDate fiscalDate = java.time.LocalDate.now();
                    Long reservationId = fidelityService.confirmLease(state, fiscalDate);
                    com.intermarche.pos.domain.sale.Ticket closed =
                            com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                    if (closed != null) {
                        String ticketRef = fiscalDate.getYear() + "-" + closed.ticketNumber;
                        String payload = fidelityService.buildTicketClosedPayload(
                                state, ticketRef, state.fidelity.label, fiscalDate, reservationId);
                        // BO-11-04-05: the VT call is synchronous where the
                        // store asks for it; otherwise it leaves through the queue.
                        if (payload != null && posSettingsService.fidelityVtSync()) {
                            fidEventOutboxService.sendNow(
                                    com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED, payload);
                        } else if (payload != null) {
                            fidEventOutboxService.enqueue(
                                    com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED, payload);
                        }
                    }
                    // BO-11-04-07: the VT call before printing, so the paper
                    // states the balance with the transaction that just ended.
                    fidelityService.refreshBeforePrint(state);
                }
                acknowledgeHubPayments(state, ticketId);
                // BO-03-03-25/-29/-30/-31: freeze what the loyalty programme
                // said about this sale BEFORE the rendering below reads it. The
                // projection lives in the register's state and dies with the
                // sale; frozen on the row, a duplicata — here or on the store
                // node — states what the customer was shown on the day.
                freezeFidelity(state, ticketId);
                // LC-08-01-05: the sale is stated on its Portuguese documents
                // before it is frozen as printed, so the frozen paper states them.
                portugueseFiscalService.issueSaleDocuments(ticketId);
                // LC-08-02-02: freeze the ticket AS PRINTED, here and nowhere
                // else — this is the only moment the loyalty section of the
                // rendering still exists, and the only place that knows what
                // this register's own printer produces. The synchronization
                // carries it up to the store node from the ticket row.
                com.intermarche.pos.domain.sale.Ticket sold =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                if (sold != null) {
                    // LC-13-06-14: frozen in the customer's language, as the
                    // customer's paper reads it.
                    ticketPersistenceService.storeFormattedContent(ticketId,
                            i18n.forCustomer(() -> ticketPrinterService.renderTicket(sold, false, 0)),
                            i18n.customerLanguageTag());
                }
                // What this closing prints (LC-08-03). With conditional
                // printing off the decision names every document, which is the
                // historical behaviour: the vouchers come out and nothing else
                // is printed on its own.
                com.intermarche.pos.domain.sale.Ticket printed =
                        com.intermarche.pos.domain.sale.Ticket.findById(ticketId);
                com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                        printPolicy.decide(state.payment.printChoice, printed,
                                state.payment.cardSignatureRequired);
                // A cashier who closed without touching the choice buttons
                // still gets the forced documents (GLC ticket, signed card
                // slip) — the choice defaults to "tous les tickets".
                if (printPolicy.isConditionalEnabled() && !state.payment.printApplied) {
                    if (decision.saleTicket()) {
                        printSaleWithMoneticPolicy(state, ticketId, decision.cardReceipt());
                        openSettlementDrawer(state, true);
                    }
                    if (decision.cardReceipt()) {
                        printMoneticReceipts(state, ticketId);
                    }
                    state.payment.printApplied = true;
                }
                // LC-07-04-10: legacy closing still prints a vendor receipt when the sale button was skipped.
                if (!printPolicy.isConditionalEnabled() && !state.payment.printApplied && hasMoneticFrames(state)) {
                    printMoneticReceipts(state, ticketId);
                    state.payment.printApplied = true;
                }
                // LC-02-08-05: the articles the customer does not carry out get
                // their handover slip, additional to the receipt. After the
                // receipt, because it states a PAID sale — the desk hands goods
                // over against a sale that is over.
                if (printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_COLLECTION)) {
                    ticketPrinterService.printCollectionVoucher(ticketId);
                }
                // BO-03-03-10: a settlement taken on the loyalty balance gets
                // its own slip, additional to the receipt and after it — the
                // proof the customer comes back with on a disputed cagnotte
                // débit. A sale that did not touch the balance prints nothing.
                if (printPolicy.isAssociatedDocumentPrinted(
                        com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_LOYALTY)) {
                    ticketPrinterService.printLoyaltyReceipt(ticketId);
                }
                // LC-08-04-16: the settlement may call for a document of its own —
                // typically an invoice on a sale paid by customer credit. It is
                // emitted here, after the receipt, because it states a CLOSED sale
                // and because it is printed "en plus du ticket de caisse".
                invoiceService.autoPrint(ticketId);
                // BO-10-01-26: a sale that settles a document of the commercial
                // management tool prints the document the store administered.
                channelDocumentPrinter.printAfterSettlement(state, ticketId);
                // LC-02-09-03: every dematerialised article the hub activated gets
                // its activation ticket, in addition to the receipt and after it.
                dematerialisedService.printActivationTickets(state,
                        printed == null ? null : printed.ticketNumber);
                // LC-05-07-02: a ticket mixing sales and returns details its
                // returns on a ticket of its own, beside the receipt it goes with.
                if (decision.saleTicket()) {
                    ticketPrinterService.printReturnDetail(ticketId);
                }
                // Gift cards issued by this sale get their printed voucher —
                // the customer's proof, right after the fiscal moment (phase:
                // credit notes & gift cards).
                if (decision.voucher()) {
                    java.util.List<com.intermarche.pos.domain.payment.StoredValue> issued =
                            com.intermarche.pos.domain.payment.StoredValue
                                    .find("issuingTicketId", ticketId).list();
                    for (com.intermarche.pos.domain.payment.StoredValue card : issued) {
                        ticketPrinterService.printGiftCardVoucher(card.number, card.initialAmount);
                    }
                }
                // BO-03-02-16: the change a voucher-change tender owed becomes a
                // numbered credit note HERE — after the fiscal moment, like every
                // other instrument this sale creates. The gift-card sweep above
                // runs first and on its own rows, so the note is printed by the
                // branch that knows its amount rather than swept up with them.
                issueChangeCreditNote(state, ticketId);
                // LC-07-15-04: the cash the machine took leaves the drawer's
                // account for the safe, the moment the sale is closed.
                transferCashChangerToSafe(state);
            } else if (state.trainingMode) {
                // LC-13-05-06: a training sale reaches the back office's journal
                // in a table of its own, never in its revenue.
                trainingTicketService.record(state);
            }

            // LC-06-03-07: a sale that settles a recalled order closes that
            // order. Told AFTER the fiscal moment and tolerantly — the sale is
            // closed whatever the node answers, and the push exists so the
            // same code cannot be recalled and paid twice.
            // LC-06-02-12: every document the sale was loaded from — several sale
            // vouchers may be settled in one transaction — and the order a
            // recovered draft remembers.
            java.util.Set<String> settledCodes = new java.util.LinkedHashSet<>(state.recalledDocumentCodes);
            if (state.recalledOrderCode != null) {
                settledCodes.add(state.recalledOrderCode);
            }
            for (String settledCode : settledCodes) {
                documentClient.settleOrder(settledCode);
            }

            // LC-03-07-02: the transaction is ending — ask for the survey
            // administered for that moment BEFORE the broom clears the
            // per-transaction flags, so the banner reads on the sale screen
            // the cashier lands on.
            surveyTriggerService.fireOnTransactionEnd(state);
            hardwareService.displayMessage(i18n.both("display.merciBientot"));
            state.clearTicket();
            // LC-13-01-01: after the sale, what the cash changer flags out of its
            // thresholds is said on the screen the cashier lands on.
            String alert = cashChangerService.levelAlert();
            if (alert != null) {
                state.ticket.setWarning(alert);
            }
        }
        LOGGER.info("Exiting method finalizeTransaction");
        return true;
    }

    /**
     * Tells whether the amount due of the sale is settled, the one condition
     * under which it may be closed.
     *
     * <p>EXP-38: a mixed ticket carries a SIGNED total. A positive (or zero)
     * total is settled when the due is covered (remaining {@code <= 0}, the
     * overpay is change) — a zero ticket is settled from the start. A NEGATIVE
     * total is a net refund the register OWES the customer: it is settled only
     * once the refund has actually been paid OUT (remaining {@code == 0}).
     *
     * @param state the current POS state
     * @return true when nothing remains due, either way
     */
    public boolean isSettled(PosState state) {
        BigDecimal remaining = state.getRemaining();
        return state.ticket.totalAmount.signum() >= 0
                ? remaining.signum() <= 0
                : remaining.signum() == 0;
    }

    // --------------------------------------------------
    // Private helpers
    // --------------------------------------------------

    /**
     * Freezes the loyalty zone of a settled sale whose receipt is printed
     * before its closing ({@code BO-03-03-26}, {@code -27}, {@code -35}): the
     * pot balances are read with this sale first — the closing reads them
     * again, at the fiscal moment — so the receipt states them as well.
     *
     * @param state the current POS state
     * @param ticketId the database id of the settled ticket
     */
    void freezeFidelityForPrint(PosState state, Long ticketId) {
        fidelityService.refreshPotBalances(state);
        freezeFidelity(state, ticketId);
    }

    /**
     * Freezes on the closed ticket what the loyalty programme said about this
     * sale: the displayed earn, its advantage lines, the balance read when the
     * card was attached and whether the service answered at all.
     *
     * <p>Copied rather than referenced: {@code PosState} is cleared a few lines
     * later and the projection is not persisted anywhere else, so this is the
     * last moment the figures exist. A sale made without a card still writes —
     * it writes nothing but the availability flag, which is exactly what a
     * receipt printed for a non-holder needs to state (BO-03-03-30).
     *
     * @param state the current POS state
     * @param ticketId the database id of the closed ticket
     */
    private void freezeFidelity(PosState state, Long ticketId) {
        java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine> lines =
                new java.util.ArrayList<>();
        for (com.intermarche.pos.ui.fidelity.FidelityState.EarnLine line
                : state.fidelity.earnEntries) {
            lines.add(new com.intermarche.pos.domain.sale.TicketFidelityLine(
                    line.ruleCode, line.label, line.amount,
                    line.advantageType, line.advantageTypeLabel, line.advantageTypeOrder,
                    line.advantageCategory, line.advantageCategoryLabel));
        }
        ticketPersistenceService.storeFidelity(ticketId, state.fidelity.earnTotal,
                state.fidelity.availableBalance, fidelityService.isUnavailable(), lines,
                state.fidelity.projectedAfterEarn, state.fidelity.balanceAfter,
                state.fidelity.anonymousProjection);
        // BO-10-02-33: the pot balances read at the fiscal moment, frozen with
        // the rest of the zone so the paper and any duplicata state them.
        ticketPersistenceService.storePotBalances(ticketId,
                com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance.encode(state.fidelity.potBalances));
        // BO-03-03-26 / -27: the programme's messages, frozen so the receipt
        // and any duplicata print the very sentences of the sale.
        ticketPersistenceService.storeFidelityMessages(ticketId, state.fidelity.messages.encode());
    }

    /**
     * Persists the last registered payment entry on the draft ticket.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (for logging context)
     */
    private void savePayment(PosState state, String methodKey) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOGGER.error("Impossible de sauvegarder le paiement : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * The tender key customer credit is registered under ({@code BO-10-04-09}).
     *
     * <p>An administered key the register cannot BUILD is refused here rather
     * than at the database: the key picks the payment class the ticket line is
     * persisted as, so an unknown one would not produce a differently-labelled
     * settlement, it would lose the settlement altogether.
     *
     * @return the administered key when the register knows it, CREDIT otherwise
     */
    private String creditTender() {
        String administered = posSettingsService.customerCreditTender();
        if (com.intermarche.pos.domain.payment.PaymentTypes.forKey(administered) == null) {
            LOGGER.warnf("Moyen de paiement crédit inconnu (%s), CREDIT appliqué", administered);
            return "CREDIT";
        }
        return administered;
    }

    /**
     * Persists the last registered payment as a voucher payment.
     *
     * @param state the current POS state
     */
    private void saveVoucherPayment(PosState state) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOGGER.error("Impossible de sauvegarder le bon : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * Marks the transaction complete when the remaining due (rounded to
     * 2 decimals) is zero or below.
     *
     * @param state the current POS state
     */
    private void checkCompletion(PosState state) {
        // EXP-38: the historical "<= 0" would mark a net refund complete the
        // instant the pay screen opened, closing it without giving the money
        // back — the settlement rule is the closing's own (isSettled).
        if (isSettled(state)) {
            state.payment.transactionComplete = true;
            openSettlementDrawer(state, false);
            state.touch();
        }
    }

    /**
     * Settles the net refund of a mixed ticket whose signed total is NEGATIVE
     * (EXP-38): the returns outweigh the purchases, so the register pays the
     * difference back to the customer through the chosen tender. Records the
     * payout as a negative settlement on the SAME ticket — one document, signed
     * total — bringing the remaining to zero and completing the transaction.
     * Guarded to the refund direction: it does nothing when money is still due
     * (a positive remaining is collected through the ordinary tender methods).
     *
     * @param state the current POS state
     * @param methodKey the tender the refund is paid out in (CASH, CARD...)
     * @return true when the refund was settled, false when nothing was owed
     */
    public boolean processNetRefund(PosState state, String methodKey) {
        LOGGER.info("Entering method processNetRefund with methodKey: " + methodKey);
        synchronized (state) {
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() >= 0) {
                LOGGER.info("Exiting method processNetRefund");
                return false;
            }
            if ("CARD".equals(methodKey)) {
                if (state.payment.pendingCardAmount != null) {
                    return false;
                }
                state.payment.pendingCardAmount = remaining;
                state.touch();
                // Every leg runs under the register's lock and settles only the
                // refund still pending, once (see processCard).
                var callback = new com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback() {
                    /** LC-07: a refund is booked only after the terminal actually accepts c2. */
                    @Override
                    public void onAccepted(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                        synchronized (state) {
                            if (!claimCardDecision(state, this)) {
                                return;
                            }
                            state.payment.pendingCardAmount = null;
                            state.payment.addCardPayment(remaining, outcome.authorizationNumber,
                                    outcome.degradedMode, outcome.entryMode);
                            var entry = state.payment.payments.get(state.payment.payments.size() - 1);
                            entry.moneticServer = outcome.server;
                            entry.customerReceiptFrame = outcome.customerReceiptFrame;
                            entry.merchantReceiptFrame = outcome.merchantReceiptFrame;
                            entry.additionalReceiptFrames = outcome.additionalReceiptFrames;
                            savePayment(state, "CARD");
                            checkCompletion(state);
                            state.touch();
                        }
                    }
                    /** LC-07: refused credit never creates an outgoing payment. */
                    @Override
                    public void onRefused(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                        synchronized (state) {
                            if (claimCardDecision(state, this)) {
                                dropPendingCard(state, i18n.get("op.paiementRefuseTpe"));
                            }
                        }
                    }
                    /** LC-07: an unreachable terminal never settles the refund locally. */
                    @Override
                    public void onError(String message) {
                        synchronized (state) {
                            if (claimCardDecision(state, this)) {
                                dropPendingCard(state, message);
                            }
                        }
                    }
                };
                state.payment.pendingCardCallback = callback;
                terminal.requestCredit(remaining.abs(), callback);
                return true;
            }
            // A negative settlement: paying the customer back brings paidAmount
            // down to the (negative) total, i.e. remaining to zero.
            state.payment.addPayment(methodKey, remaining);
            savePayment(state, methodKey);
            hardwareService.displayMessage(String.format(i18n.get("display.remboursement"), df.format(remaining.abs())));
            checkCompletion(state);
            state.touch();
            LOGGER.info("Exiting method processNetRefund");
            return true;
        }
    }
}
