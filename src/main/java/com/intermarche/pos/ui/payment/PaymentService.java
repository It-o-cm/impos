package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
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
 * and training keeps it shut everywhere. The virtual-terminal branch of
 * {@code processCard} (phase 6) parks the amount instead of registering it:
 * the CardPayment entity only exists after the terminal's accept.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);

    /** True when card payments go through the virtual terminal of the simulator. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "pos.tpe.virtual", defaultValue = "true")
    boolean virtualTpe;

    @Inject
    HardwareService hardwareService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

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
        hardwareService.displayMessage(String.format("TOTAL   %s E", df.format(state.ticket.totalAmount)));
        state.payment.paymentInProgress = true;

        Long ticketId = ticketPersistenceService.syncDraft(state);
        if (ticketId == null) {
            LOG.error("Impossible de créer/synchroniser le ticket (panier vide ou Store/Cashier manquant)");
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
            state.ticket.addItem(null, null, "ARRONDI SOLIDAIRE", difference, BigDecimal.ONE, BigDecimal.ZERO);
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
        Long ticketId = state.payment.ticketDbId;
        state.payment.paymentInProgress = false;
        state.payment.pendingCardAmount = null;
        state.clearPayments();
        if (ticketId != null) {
            ticketPersistenceService.removePaymentsFromTicket(ticketId);
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

        if (virtualTpe) {
            // Phase 6: the amount goes to the virtual terminal; the payment is
            // registered only on its accept decision (simulator buttons).
            if (state.payment.pendingCardAmount != null) return; // one request at a time
            state.payment.pendingCardAmount = amount.setScale(2, RoundingMode.HALF_UP);
            hardwareService.displayMessage(String.format("CARTE   %s E", df.format(state.payment.pendingCardAmount)));
            state.touch();
            return;
        }

        handlePaymentWithChange(state, "CARD", "CARTE", amount);

        // Rule: card = no drawer opening
    }

    /**
     * Registers the pending card payment on the terminal's accept decision.
     *
     * @param state the current POS state
     */
    public void confirmPendingCard(PosState state) {
        BigDecimal amount = state.payment.pendingCardAmount;
        if (amount == null) return;
        state.payment.pendingCardAmount = null;
        handlePaymentWithChange(state, "CARD", "CARTE", amount);
        state.touch();
    }

    /**
     * Drops the pending card payment on the terminal's refuse decision.
     *
     * @param state the current POS state
     */
    public void refusePendingCard(PosState state) {
        if (state.payment.pendingCardAmount == null) return;
        state.payment.pendingCardAmount = null;
        state.ticket.setError("PAIEMENT REFUSÉ PAR LE TPE");
        hardwareService.displayMessage("PAIEMENT REFUSE");
        state.touch();
    }

    /**
     * Cancels the pending card payment from the register side.
     *
     * @param state the current POS state
     */
    public void cancelPendingCard(PosState state) {
        if (state.payment.pendingCardAmount == null) return;
        state.payment.pendingCardAmount = null;
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
        if (amount == null || amount.signum() <= 0) amount = state.getRemaining();
        if (amount.signum() <= 0) return;

        state.payment.clearPendingVoucher();

        BigDecimal amountToPay = amount.min(state.getRemaining());
        state.payment.addPayment("FIDELITY", amountToPay);
        state.touch();
        savePayment(state, "FIDELITY");
        hardwareService.displayMessage(String.format("FIDELITE  %s E", df.format(amountToPay)));

        // Rule: fidelity = no drawer opening (virtual)

        checkCompletion(state);
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
        if (amount == null || amount.signum() <= 0) return;

        state.payment.addVoucherPayment(label, number, amount);
        state.touch();
        saveVoucherPayment(state);
        hardwareService.displayMessage(String.format("%-10s%s E", "BON", df.format(amount)));

        // Rule: voucher = no drawer opening (virtual)

        checkCompletion(state);
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
        Long ticketId = state.payment.ticketDbId;

        if (ticketId != null) {
            ticketPersistenceService.validateTicket(ticketId);
            state.lastClosedTicketId = ticketId;
            LOG.info("Ticket validé et fermé en BDD ID: " + ticketId);
        }

        hardwareService.displayMessage("MERCI A BIENTOT");
        state.clearTicket();
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
