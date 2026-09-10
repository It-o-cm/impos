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

    /** The cheque reader, reached through the hardware bridge. */
    @Inject
    com.intermarche.pos.ui.hardware.ChequeReadingService chequeReadingService;

    /** The back-office parameters (drawer-open rules — BO-10-02-12). */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

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
        // Single-writer discipline: draft-writing gestures serialize on the
        // shared state — the pay screen reloads itself on every version bump
        // and re-enters initPayment (draft sync + revaluation) concurrently
        // with a payment registration or the terminal callback, which made
        // two transactions race on the same draft row (optimistic-lock).
        synchronized (state) {
            hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
            state.payment.paymentInProgress = true;
            // LC-07-03-01: the rounding step is read once, at payment entry, and
            // held on the state — the screen, the customer display and the printer
            // all ask for the rounded amount many times per second.
            state.cashRoundingStepCents = posSettingsService.cashRoundingStepCents();

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
            } else if ("CARD".equals(methodKey)) {
                // The card entry carries the terminal traces (authorization
                // number, degraded-mode indicator) parked by the accept leg.
                state.payment.addCardPayment(amountToPay,
                        state.payment.pendingCardAuthNumber, state.payment.pendingCardDegraded);
            } else if ("CHEQUE".equals(methodKey)) {
                // The cheque entry carries the magnetic line parked by the reading.
                state.payment.addChequePayment(amountToPay, state.payment.pendingChequeLine);
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

        // LC-07-03-05: where the shop rounds, the coins to make up anything off
        // the step no longer circulate, so an amount off the step cannot have been
        // handed over. Refused rather than silently rounded: the cashier is telling
        // the register what is in their hand, and the register does not know better.
        if (!CashRounding.isTenderable(tendered, state.cashRoundingStepCents)) {
            state.ticket.setError("MONTANT NON MULTIPLE DE "
                    + df.format(BigDecimal.valueOf(state.cashRoundingStepCents, 2)) + " E");
            state.touch();
            return;
        }

        // LC-07-03-01/06: the rounding applies to what is SETTLED IN CASH, so it is
        // decided here and not on the ticket total — a sale part-paid by card rounds
        // the remainder, which is what the customer actually hands over. The
        // difference is booked FIRST, so the cash payment that follows sees a
        // remaining due already on the step and the change comes out right.
        registerCashRounding(state, tendered);

        handlePaymentWithChange(state, "CASH", "ESPECES", tendered);

        // Rule: cash = systematic drawer opening (deposit + change), unless the
        // back office disabled the drawer-open-on-payment rule (BO-10-02-12).
        if (!state.trainingMode && posSettingsService.drawerOpenOnPayment()) hardwareService.openDrawer(); // drawer stays shut in training
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
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (amount == null || amount.signum() <= 0) return;
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) return;

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(remaining);
            state.payment.addBackupPayment(amountToPay, methodLabel, transactionNumber, manual);
            state.touch();
            savePayment(state, "SECOURS");
            hardwareService.displayMessage(String.format("SECOURS  %s E", df.format(amountToPay)));

            // Rule: backup monetics = no drawer opening (nothing physical).

            checkCompletion(state);
        }
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
    public void processForeignCurrency(PosState state, com.intermarche.pos.domain.Currency currency,
            BigDecimal foreignAmount, BigDecimal euroValue) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (currency == null || euroValue == null || euroValue.signum() <= 0) return;
            BigDecimal remaining = state.getRemaining();
            if (remaining.signum() <= 0) return;

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = euroValue.min(remaining);
            BigDecimal change = euroValue.subtract(amountToPay).setScale(2, RoundingMode.HALF_UP);
            state.payment.lastChangeAmount = change.signum() > 0 ? change : BigDecimal.ZERO;

            state.payment.addCurrencyPayment(amountToPay, currency.code, foreignAmount,
                    currency.euroPerUnit);
            state.touch();
            savePayment(state, "DEVISE");

            // LC-07-14-05: the three figures the cashier and the customer need to
            // agree on — what was handed over, what it is worth, and at what rate.
            hardwareService.displayMessage(String.format("%s %s = %s E",
                    df.format(foreignAmount), currency.displayUnit(), df.format(euroValue)));
            if (change.signum() > 0) {
                hardwareService.displayMessage(String.format("RENDU %s E", df.format(change)));
            }

            if (!state.trainingMode && posSettingsService.drawerOpenOnPayment()) {
                hardwareService.openDrawer();
            }

            checkCompletion(state);
        }
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
        hardwareService.displayMessage(String.format("ARRONDI  %s E", df.format(difference.negate())));
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
                registerAcceptedCard(state, outcome);
            }

            /**
             * Drops the refused card payment and tells the cashier.
             *
             * @param outcome the refusal outcome
             */
            @Override
            public void onRefused(com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome) {
                // LC-08-03-12: the not-completed transaction leaves a printed
                // trace when the back office forces it — the terminal's own
                // frame when it supplied one.
                if (outcome != null && printPolicy.isTnaReceiptForced()) {
                    ticketPrinterService.printCardTnaReceipt(outcome.amount, outcome.tnaFrame);
                }
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
        if (amount == null) return;
        state.payment.pendingCardAmount = null;
        state.payment.pendingCardAuthNumber = outcome.authorizationNumber;
        state.payment.pendingCardDegraded = outcome.degradedMode;
        // LC-08-03-11: a slip the customer must sign forces the card receipt
        // whatever the cashier chooses. One signed card in the transaction is
        // enough, so the flag only ever goes up.
        if (outcome.signatureRequired) {
            state.payment.cardSignatureRequired = true;
        }
        handlePaymentWithChange(state, "CARD", "CARTE", amount);
        state.payment.pendingCardAuthNumber = null;
        state.payment.pendingCardDegraded = false;
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
            if ("ENGINE".equals(state.payment.valuationStatus) && state.payment.valuationMealEligible != null) {
                BigDecimal allowed = state.payment.valuationMealEligible;
                if (state.payment.valuationMealThreshold != null) {
                    allowed = allowed.min(state.payment.valuationMealThreshold);
                }
                allowed = allowed.subtract(registeredMealTicketTotal(state)).max(BigDecimal.ZERO);
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
            }

            handlePaymentWithChange(state, "TR", "TICKET", amount);
        }

        // Rule: meal tickets = systematic drawer opening (to store the tickets),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12).
        if (!state.trainingMode && posSettingsService.drawerOpenOnPayment()) hardwareService.openDrawer(); // drawer stays shut in training
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
            if ("TR".equals(entry.method)) {
                total = total.add(entry.amount);
            }
        }
        return total;
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
        if (state.trainingMode) {
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
        BigDecimal requested = amount;
        // Built HERE, on the request thread: the reader prints the endorsement while it
        // still holds the cheque, so the text must leave with the start of the reading.
        String endorsement = endorsementText(amount);
        chequeReadingService.read(
                endorsement,
                line -> registerReadCheque(state, requested, line),
                message -> dropPendingCheque(state, message));
    }

    /**
     * Composes what is printed on the back of the cheque.
     * <p>
     * What the register knows and the paper does not: WHEN the cheque was taken and
     * FOR HOW MUCH. Nothing is read from the database here — this service orchestrates
     * a payment, it does not query, and an endorsement is not worth a round trip in the
     * middle of a sale.
     *
     * @param amount the amount the cheque settles
     * @return the endorsement, one line per newline
     */
    private String endorsementText(BigDecimal amount) {
        return java.time.LocalDateTime.now().format(ENDORSEMENT_DATE)
                + "\n" + df.format(amount) + " EUR";
    }

    /**
     * Registers the cheque once the reader has read it.
     *
     * @param state the current POS state
     * @param amount the amount the cashier asked for
     * @param line the magnetic line, kept for the journal
     */
    private void registerReadCheque(PosState state, BigDecimal amount, String line) {
        if (state.payment.pendingChequeAmount == null) {
            LOG.info("Cheque lu apres annulation cote caisse : ignore");
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = line;
        LOG.infof("Cheque lu : %s", line);
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
        if (state.payment.pendingChequeAmount == null) {
            return;
        }
        state.payment.pendingChequeAmount = null;
        state.payment.pendingChequeLine = null;
        state.touch();
    }

    /**
     * Settles a cheque payment, whatever led to it.
     *
     * @param state the current POS state
     * @param amount the amount applied to the ticket
     */
    private void registerCheque(PosState state, BigDecimal amount) {
        handlePaymentWithChange(state, "CHEQUE", "CHEQUE", amount);
        // Ephemeral, like the card traces: cleared once the entry carries it, so the
        // next cheque of the same ticket cannot inherit this one's line.
        state.payment.pendingChequeLine = null;

        // Rule: cheque = systematic drawer opening (to store the cheque),
        // unless the drawer-open-on-payment rule is disabled (BO-10-02-12).
        if (!state.trainingMode && posSettingsService.drawerOpenOnPayment()) hardwareService.openDrawer(); // drawer stays shut in training
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
    public void processCredit(PosState state, com.intermarche.pos.domain.AccountCustomer customer,
            BigDecimal amount, boolean overLimit) {
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (customer == null || amount == null || amount.signum() <= 0) return;
            if (state.getRemaining().signum() <= 0) return;

            state.payment.clearPendingVoucher();

            BigDecimal amountToPay = amount.min(state.getRemaining());
            state.payment.addCreditPayment(amountToPay, customer.accountNumber,
                    customer.getDisplayName(), overLimit);
            state.touch();
            savePayment(state, "CREDIT");
            hardwareService.displayMessage(
                    String.format("CREDIT CLIENT  %s E", df.format(amountToPay)));

            // Rule: customer credit = no drawer opening (nothing physical).

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
        // Single-writer discipline (see initPayment).
        synchronized (state) {
            if (state.payment.printApplied) return;
            Long ticketId = state.payment.ticketDbId;
            if (ticketId == null) return;
            state.payment.printChoice = choice;
            com.intermarche.pos.domain.ticket.Ticket ticket =
                    com.intermarche.pos.domain.ticket.Ticket.findById(ticketId);
            com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                    printPolicy.decide(choice, ticket, state.payment.cardSignatureRequired);
            if (decision.saleTicket()) {
                ticketPrinterService.printTicket(ticketId);
            }
            if (decision.cardReceipt()) {
                ticketPrinterService.printCardReceipt(ticketId,
                        state.payment.cardSignatureRequired, null);
            }
            state.payment.printApplied = true;
            state.touch();
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
                // LC-08-02-02: freeze the ticket AS PRINTED, here and nowhere
                // else — this is the only moment the loyalty section of the
                // rendering still exists, and the only place that knows what
                // this register's own printer produces. The synchronization
                // carries it up to the store node from the ticket row.
                com.intermarche.pos.domain.ticket.Ticket sold =
                        com.intermarche.pos.domain.ticket.Ticket.findById(ticketId);
                if (sold != null) {
                    ticketPersistenceService.storeFormattedContent(ticketId,
                            ticketPrinterService.renderTicket(sold, false, 0));
                }
                // What this closing prints (LC-08-03). With conditional
                // printing off the decision names every document, which is the
                // historical behaviour: the vouchers come out and nothing else
                // is printed on its own.
                com.intermarche.pos.domain.ticket.Ticket printed =
                        com.intermarche.pos.domain.ticket.Ticket.findById(ticketId);
                com.intermarche.pos.ui.hardware.PrintPolicy.Decision decision =
                        printPolicy.decide(state.payment.printChoice, printed,
                                state.payment.cardSignatureRequired);
                // A cashier who closed without touching the choice buttons
                // still gets the forced documents (GLC ticket, signed card
                // slip) — the choice defaults to "tous les tickets".
                if (printPolicy.isConditionalEnabled() && !state.payment.printApplied) {
                    if (decision.saleTicket()) {
                        ticketPrinterService.printTicket(ticketId);
                    }
                    if (decision.cardReceipt()) {
                        ticketPrinterService.printCardReceipt(ticketId,
                                state.payment.cardSignatureRequired, null);
                    }
                    state.payment.printApplied = true;
                }
                // Gift cards issued by this sale get their printed voucher —
                // the customer's proof, right after the fiscal moment (phase:
                // credit notes & gift cards).
                if (decision.voucher()) {
                    java.util.List<com.intermarche.pos.domain.StoredValue> issued =
                            com.intermarche.pos.domain.StoredValue
                                    .find("issuingTicketId", ticketId).list();
                    for (com.intermarche.pos.domain.StoredValue card : issued) {
                        ticketPrinterService.printGiftCardVoucher(card.number, card.initialAmount);
                    }
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
