package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
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

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    /**
     * The payment terminal port (virtual simulator, auto-accept or Verifone
     * skeleton — selected by {@code pos.tpe.mode}); the card path only ever
     * talks to this interface.
     */
    @Inject
    com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient terminal;

    @Inject
    HardwareService hardwareService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    /**
     * Prints the gift-card vouchers issued by a closed sale (phase: credit
     * notes & gift cards).
     */
    @Inject
    com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

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
    com.intermarche.pos.service.sync.FidEventOutboxService fidEventOutboxService;

    @Inject
    ValuationService valuationService;

    @Inject
    ValuationReconciler valuationReconciler;

    /** French display format for amounts on the customer display. */
    private final DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRENCH));

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
        // Single-writer discipline: draft-writing gestures serialize on the
        // shared state — the pay screen reloads itself on every version bump
        // and re-enters initPayment (draft sync + revaluation) concurrently
        // with a payment registration or the terminal callback, which made
        // two transactions race on the same draft row (optimistic-lock).
        synchronized (state) {
            hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
            state.payment.paymentInProgress = true;

            Long ticketId = ticketPersistenceService.syncDraft(state);
            if (ticketId == null && !state.trainingMode) {
                LOG.error("Impossible de créer/synchroniser le ticket (panier vide ou Store/Cashier manquant)");
            }

            // Phase 7 lot 4: final revaluation at payment entry — the cart was
            // already revalued after each mutation, this fixes the figure the
            // customer pays (fresh call, circuit permitting) and refreshes the
            // meal-voucher base and upsell hints.
            if (!state.trainingMode) {
                valuationService.revalueForPayment(state);
                hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
            } else {
                state.payment.valuationStatus = "LOCAL";
            }
        }
    }

    /**
     * Toggles the solidarity round-up: adds a zero-VAT donation line raising
     * the ticket total to the next whole euro, or removes it when already
     * present. Ignored on a completed transaction or an already-whole total.
     *
     * @param state the current POS state
     */
    public void toggleDonationRoundup(PosState state) {
        if (state.payment.transactionComplete) return;

        if (state.donationLineUid != null) {
            state.ticket.removeItemById(state.donationLineUid);
            state.donationLineUid = null;
        } else {
            BigDecimal total = state.ticket.totalAmount;
            BigDecimal roundedUp = total.setScale(0, RoundingMode.CEILING);
            BigDecimal difference = roundedUp.subtract(total);
            if (difference.signum() <= 0) return;
            // Collected on behalf of the charity: out of VAT scope
            // Technical EAN, parameterized: a register-generated line is still
            // a line, and every line carries an EAN the valuation engine can
            // resolve (a line without one does not exist).
            state.ticket.addItem(donationEan, null, "ARRONDI SOLIDAIRE",
                    difference, BigDecimal.ONE, BigDecimal.ZERO);
            state.donationLineUid = state.lastEnteredItemId;
        }
        ticketPersistenceService.syncDraft(state);
        hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
        state.touch();
    }

    /**
     * Cancels the registered payments coherently: clears the in-memory list
     * and removes the persisted payments from the draft, so a later restart
     * recovery cannot resurrect them.
     *
     * @param state the current POS state
     */
    public void cancelPayments(PosState state) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            Long ticketId = state.payment.ticketDbId;
            // The fidelity lease dies with the payments (imfid spec §5.3);
            // failure-tolerant — the TTL is the safety net.
            fidelityService.releaseLease(state);
            state.payment.paymentInProgress = false;
            state.payment.pendingCardAmount = null;
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
            state.clearPayments();
            // Back to the cart: revalue immediately so the screen shows engine
            // totals again without waiting for the next mutation
            valuationService.revalue(state);
            if (ticketId != null) {
                ticketPersistenceService.removePaymentsFromTicket(ticketId);
            }
        }
    }

    // --------------------------------------------------
    // 2. COMMON PRIVATE METHOD
    // --------------------------------------------------

    /**
     * Registers a payment with change handling: caps the applied amount at the
     * remaining due, computes the change, updates the UI state, persists the
     * payment and drives the customer display.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (CASH, CARD, TR, CHEQUE...)
     * @param displayName the label shown on the customer display
     * @param tendered the amount handed over by the customer
     */
    private void handlePaymentWithChange(PosState state, String methodKey, String displayName, BigDecimal tendered) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (tendered == null || tendered.signum() <= 0) return;

            state.payment.clearPendingVoucher();

            BigDecimal remaining = state.getRemaining();
            BigDecimal amountToPay = tendered.min(remaining);
            BigDecimal change = tendered.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);

            // UI state update
            state.payment.lastChangeAmount = (change.signum() > 0) ? change : BigDecimal.ZERO;

            // In-memory update
            if ("CASH".equals(methodKey)) {
                state.payment.addCashPayment(amountToPay, tendered);
            } else {
                state.payment.addPayment(methodKey, amountToPay);
            }
            state.touch();

            // Database persistence
            savePayment(state, methodKey);

            // Hardware display
            if (change.signum() > 0) {
                hardwareService.displayMessage(String.format("DONNE %s RENDU %s", df.format(tendered), df.format(change)));
            } else {
                hardwareService.displayMessage(String.format("%-10s%s E", displayName, df.format(amountToPay)));
            }

            checkCompletion(state);
        }
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
        if (tendered == null || tendered.signum() <= 0) return;

        handlePaymentWithChange(state, "CASH", "ESPECES", tendered);

        // Rule: cash = systematic drawer opening (deposit + change)
        if (!state.trainingMode) hardwareService.openDrawer(); // drawer stays shut in training
    }

    /**
     * Registers a card payment; the amount defaults to the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCard(PosState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) return;

        // One transaction at a time: the pending amount is the UI-facing
        // in-flight marker, whatever the terminal implementation.
        if (state.payment.pendingCardAmount != null) return;
        BigDecimal requested = amount.setScale(2, RoundingMode.HALF_UP);
        state.payment.pendingCardAmount = requested;
        hardwareService.displayMessage(String.format("CARTE   %s E", df.format(requested)));
        state.touch();

        // The decision arrives asynchronously on the callback: the virtual
        // terminal fires it from the simulator's accept/refuse endpoint,
        // the auto mode fires it synchronously, Verifone from its exchange
        // thread. Exactly one method runs per transaction.
        terminal.requestDebit(requested,
                new com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback() {
            /**
             * Registers the accepted card payment.
             *
             * @param outcome the terminal outcome
             */
            @Override
            public void onAccepted(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                registerAcceptedCard(state);
            }

            /**
             * Drops the refused card payment and tells the cashier.
             *
             * @param outcome the refusal outcome
             */
            @Override
            public void onRefused(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                dropPendingCard(state, "PAIEMENT REFUSÉ PAR LE TPE");
            }

            /**
             * Drops the card payment on a terminal failure with the
             * terminal's own message.
             *
             * @param message the operator-facing failure message
             */
            @Override
            public void onError(String message) {
                dropPendingCard(state, message);
            }
        });

        // Rule: card = no drawer opening
    }

    /**
     * Registers the pending card payment — the accept leg of the terminal
     * callback.
     *
     * @param state the current POS state
     */
    private void registerAcceptedCard(PosState state) {
        BigDecimal amount = state.payment.pendingCardAmount;
        if (amount == null) return;
        state.payment.pendingCardAmount = null;
        handlePaymentWithChange(state, "CARD", "CARTE", amount);
        state.touch();
    }

    /**
     * Drops the pending card payment with an operator-facing message — the
     * refuse and error legs of the terminal callback.
     *
     * @param state the current POS state
     * @param message the ticket error to show
     */
    private void dropPendingCard(PosState state, String message) {
        if (state.payment.pendingCardAmount == null) return;
        state.payment.pendingCardAmount = null;
        state.ticket.setError(message);
        hardwareService.displayMessage("PAIEMENT REFUSE");
        state.touch();
    }

    /**
     * Cancels the pending card payment from the register side and tells the
     * terminal to abandon its in-flight transaction.
     *
     * @param state the current POS state
     */
    public void cancelPendingCard(PosState state) {
        if (state.payment.pendingCardAmount == null) return;
        state.payment.pendingCardAmount = null;
        terminal.abort();
        hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
        state.touch();
    }

    /**
     * Registers a meal-ticket payment; the drawer opens to store the tickets.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processTicketResto(PosState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) return;

        // Phase 7 lot 3: the engine's MEAL_VOUCHER advantage caps meal tickets
        // at min(eligible base, threshold, requested); the base shrinks with
        // each registered meal-ticket payment. No advantage emitted = no cap
        // (local behavior unchanged).
        if ("ENGINE".equals(state.payment.valuationStatus) && state.payment.valuationMealEligible != null) {
            BigDecimal allowed = state.payment.valuationMealEligible;
            if (state.payment.valuationMealThreshold != null) {
                allowed = allowed.min(state.payment.valuationMealThreshold);
            }
            if (allowed.signum() <= 0) {
                hardwareService.displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
                LOG.infof("Paiement TR refusé: assiette éligible épuisée");
                return;
            }
            if (amount.compareTo(allowed) > 0) {
                amount = allowed;
                hardwareService.displayMessage(String.format("TR PLAFONNE  %s E", df.format(allowed)));
                LOG.infof("Paiement TR plafonné à %s (assiette moteur)", allowed);
            }
            BigDecimal applied = amount.min(state.getRemaining());
            state.payment.valuationMealEligible =
                    state.payment.valuationMealEligible.subtract(applied).max(BigDecimal.ZERO);
        }

        handlePaymentWithChange(state, "TR", "TICKET", amount);

        // Rule: meal tickets = systematic drawer opening (to store the tickets)
        if (!state.trainingMode) hardwareService.openDrawer(); // drawer stays shut in training
    }

    /**
     * Registers a cheque payment; the drawer opens to store the cheque.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processCheque(PosState state, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) return;

        handlePaymentWithChange(state, "CHEQUE", "CHEQUE", amount);

        // Rule: cheque = systematic drawer opening (to store the cheque)
        if (!state.trainingMode) hardwareService.openDrawer(); // drawer stays shut in training
    }

    /**
     * Registers a fidelity (virtual) payment capped at the remaining due.
     *
     * @param state the current POS state
     * @param amount the amount to pay, or zero/negative to use the remaining due
     */
    public void processFidelity(PosState state, BigDecimal amount) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.getRemaining().signum() <= 0) return;

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
                return;
            }
            BigDecimal amountToPay = verdict.grantedAmount;
            state.payment.addPayment("FIDELITY", amountToPay);
            state.touch();
            savePayment(state, "FIDELITY");
            hardwareService.displayMessage(String.format("FIDELITE  %s E", df.format(amountToPay)));

            // Rule: fidelity = no drawer opening (virtual)

            checkCompletion(state);
        }
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
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) return;

            state.payment.addVoucherPayment(label, number, amount);
            state.touch();
            saveVoucherPayment(state);
            hardwareService.displayMessage(String.format("%-10s%s E", "BON", df.format(amount)));

            // Rule: voucher = no drawer opening (virtual)

            checkCompletion(state);
        }
    }

    // --------------------------------------------------
    // 4. FINALIZATION
    // --------------------------------------------------

    /**
     * Closes the transaction: validates the ticket in database, remembers it
     * as the last closed ticket and clears the in-memory state.
     *
     * @param state the current POS state
     */
    public void finalizeTransaction(PosState state) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            Long ticketId = state.payment.ticketDbId;

            if (ticketId != null) {
                ticketPersistenceService.validateTicket(ticketId);
                state.lastClosedTicketId = ticketId;
                LOG.info("Ticket validé et fermé en BDD ID: " + ticketId);
                // Loyalty fiscal sequence (imfid spec §5.2 + §6): confirm the
                // lease (410 tolerated — the ingestion is authoritative), then
                // enqueue the ticket-closed event with the verbatim couple. The
                // outbox drains it; imfid recomputes and the recalcul fait foi.
                if (state.fidelity.active) {
                    java.time.LocalDate fiscalDate = java.time.LocalDate.now();
                    Long reservationId = fidelityService.confirmLease(state, fiscalDate);
                    com.intermarche.pos.domain.ticket.Ticket closed =
                            com.intermarche.pos.domain.ticket.Ticket.findById(ticketId);
                    if (closed != null) {
                        String ticketRef = fiscalDate.getYear() + "-" + closed.ticketNumber;
                        String payload = fidelityService.buildTicketClosedPayload(
                                state, ticketRef, state.fidelity.label, fiscalDate, reservationId);
                        if (payload != null) {
                            fidEventOutboxService.enqueue(
                                    com.intermarche.pos.domain.FidEvent.EventType.TICKET_CLOSED, payload);
                        }
                    }
                }
                // Gift cards issued by this sale get their printed voucher —
                // the customer's proof, right after the fiscal moment (phase:
                // credit notes & gift cards).
                java.util.List<com.intermarche.pos.domain.StoredValue> issued =
                        com.intermarche.pos.domain.StoredValue
                                .find("issuingTicketId", ticketId).list();
                for (com.intermarche.pos.domain.StoredValue card : issued) {
                    ticketPrinterService.printGiftCardVoucher(card.number, card.initialAmount);
                }
            }

            hardwareService.displayMessage("MERCI A BIENTOT");
            state.clearTicket();
        }
    }

    // --------------------------------------------------
    // Private helpers
    // --------------------------------------------------

    /**
     * Persists the last registered payment entry on the draft ticket.
     *
     * @param state the current POS state
     * @param methodKey the payment method key (for logging context)
     */
    private void savePayment(PosState state, String methodKey) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOG.error("Impossible de sauvegarder le paiement : aucun Ticket ID");
            return;
        }
        PaymentState.PaymentEntry lastEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, lastEntry);
    }

    /**
     * Persists the last registered payment as a voucher payment.
     *
     * @param state the current POS state
     */
    private void saveVoucherPayment(PosState state) {
        if (state.trainingMode) return; // nothing persisted in training
        if (state.payment.ticketDbId == null) {
            LOG.error("Impossible de sauvegarder le bon : aucun Ticket ID");
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
        if (state.getRemaining().signum() <= 0) {
            state.payment.transactionComplete = true;
            state.touch();
        }
    }
}
