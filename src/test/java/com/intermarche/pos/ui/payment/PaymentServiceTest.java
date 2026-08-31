package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.service.sync.FidEventOutboxService;
import com.intermarche.pos.ui.fidelity.FidelityService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.terminal.AutoAcceptTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.TerminalOutcome;
import com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentService}.
 * <p>
 * {@link PaymentService} is a pure orchestration class: it manipulates a
 * passed-in {@link PosState} (with its real {@link PaymentState} and
 * {@code TicketState} sub-states, kept real here since they are value/state
 * holders whose arithmetic — {@code getRemaining()}, {@code paidAmount}
 * accumulation, completion — the branches depend on) and drives four injected
 * service collaborators, all Mockito mocks: {@link HardwareService},
 * {@link TicketPersistenceService}, {@link ValuationService} and
 * {@link ValuationReconciler}. The class touches no Panache entity, so no
 * static mocking is needed. Every guard and ternary is exercised on both arms
 * with absolute expected values; the sole unreachable branch — the defensive
 * {@code tendered == null || signum <= 0} guard of the private
 * {@code handlePaymentWithChange}, which every public caller pre-guards — is
 * covered by reflection. Customer-display strings use the service's
 * locale-fixed French {@code DecimalFormat}, so the comma decimals asserted
 * here are independent of the JVM default locale.
 */
class PaymentServiceTest {

    /** The service under test with mocked collaborators wired in. */
    private PaymentService service;

    /** Mocked hardware boundary (customer display and cash drawer). */
    private HardwareService hardwareService;

    /** Mocked draft/ticket persistence collaborator. */
    private TicketPersistenceService ticketPersistenceService;

    /** Mocked valuation collaborator (engine revaluation). */
    private ValuationService valuationService;

    /** Mocked valuation reconciler collaborator (advantage revert). */
    private ValuationReconciler valuationReconciler;

    /** The printer, which issues the gift-card vouchers at the fiscal moment. */
    private com.intermarche.pos.ui.hardware.TicketPrinterService ticketPrinterService;

    /** The loyalty lease lifecycle: reserve, renew, release, confirm. */
    private FidelityService fidelityService;

    /** The loyalty fiscal-event outbox, fed at the fiscal moment. */
    private FidEventOutboxService fidEventOutboxService;

    /** The mocked payment-terminal port (decision legs driven by hand). */
    private PaymentTerminalClient terminal;

    /** The back-office parameters mock (drawer-open-on-payment rule). */
    private com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** A real POS state carrying real payment and ticket sub-states. */
    private PosState state;

    /**
     * Builds a fresh service with fresh mocks and a fresh real state before
     * each test; the terminal port is a mock whose callback legs the tests
     * fire by hand.
     */
    @BeforeEach
    void setUp() {
        service = new PaymentService();
        hardwareService = mock(HardwareService.class);
        ticketPersistenceService = mock(TicketPersistenceService.class);
        valuationService = mock(ValuationService.class);
        valuationReconciler = mock(ValuationReconciler.class);
        service.hardwareService = hardwareService;
        service.ticketPersistenceService = ticketPersistenceService;
        ticketPrinterService = mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        service.ticketPrinterService = ticketPrinterService;
        service.valuationService = valuationService;
        service.valuationReconciler = valuationReconciler;
        fidelityService = mock(FidelityService.class);
        fidEventOutboxService = mock(FidEventOutboxService.class);
        service.fidelityService = fidelityService;
        service.fidEventOutboxService = fidEventOutboxService;
        terminal = mock(PaymentTerminalClient.class);
        service.terminal = terminal;
        // The drawer-open-on-payment rule defaults to ON, the pre-existing
        // behavior every physical-tender case here relies on (BO-10-02-12).
        posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(true);
        service.posSettingsService = posSettingsService;
        state = new PosState();
    }

    // --------------------------------------------------
    // initPayment
    // --------------------------------------------------

    /**
     * {@code initPayment} on a normal sale (draft synced, not training): no
     * error, engine revaluation, total displayed at entry and after revaluation
     * ({@code ticketId != null} short-circuits the guard, {@code !trainingMode}
     * true in the revaluation branch).
     */
    @Test
    void initPaymentSyncedNotTraining() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(5L);
        service.initPayment(state);
        assertTrue(state.payment.paymentInProgress);
        verify(hardwareService, times(2)).displayMessage("TOTAL   20,00 E");
        verify(valuationService).revalueForPayment(state);
        assertNull(state.payment.valuationStatus);
    }

    /**
     * {@code initPayment} still revalues but logs the failure when the draft
     * cannot be synced outside training ({@code ticketId == null} true,
     * {@code !trainingMode} true — both arms of the {@code &&} taken).
     */
    @Test
    void initPaymentSyncFailsNotTraining() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(null);
        service.initPayment(state);
        verify(valuationService).revalueForPayment(state);
        assertNull(state.payment.valuationStatus);
    }

    /**
     * {@code initPayment} in training mode does not log the sync failure and
     * keeps the valuation local ({@code ticketId == null} true but
     * {@code !trainingMode} false, and the revaluation branch false).
     */
    @Test
    void initPaymentTrainingKeepsLocal() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(ticketPersistenceService.syncDraft(state)).thenReturn(null);
        service.initPayment(state);
        assertEquals("LOCAL", state.payment.valuationStatus);
        verify(hardwareService, times(1)).displayMessage("TOTAL   20,00 E");
        verify(valuationService, never()).revalueForPayment(any());
    }

    // --------------------------------------------------
    // toggleDonationRoundup
    // --------------------------------------------------

    /**
     * {@code toggleDonationRoundup} does nothing on a completed transaction
     * ({@code transactionComplete} true arm).
     */
    @Test
    void toggleDonationOnCompletedTransactionReturns() {
        state.payment.transactionComplete = true;
        service.toggleDonationRoundup(state);
        verify(ticketPersistenceService, never()).syncDraft(any());
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code toggleDonationRoundup} removes the existing donation line and
     * clears its uid ({@code donationLineUid != null} true arm).
     */
    @Test
    void toggleDonationRemovesExistingLine() {
        state.payment.transactionComplete = false;
        state.donationLineUid = "donation-uid";
        service.toggleDonationRoundup(state);
        assertNull(state.donationLineUid);
        verify(ticketPersistenceService).syncDraft(state);
        verify(hardwareService).displayMessage("TOTAL   0,00 E");
    }

    /**
     * {@code toggleDonationRoundup} adds no line when the total is already whole
     * ({@code donationLineUid == null}, {@code difference.signum() <= 0} true).
     */
    @Test
    void toggleDonationWholeTotalAddsNothing() {
        state.payment.transactionComplete = false;
        state.donationLineUid = null;
        state.ticket.totalAmount = new BigDecimal("5.00");
        service.toggleDonationRoundup(state);
        assertNull(state.donationLineUid);
        verify(ticketPersistenceService, never()).syncDraft(any());
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code toggleDonationRoundup} adds a round-up donation line when the total
     * is not whole ({@code donationLineUid == null}, {@code difference.signum()
     * <= 0} false), tagging its uid.
     */
    @Test
    void toggleDonationAddsRoundupLine() {
        state.payment.transactionComplete = false;
        state.donationLineUid = null;
        state.ticket.totalAmount = new BigDecimal("4.30");
        service.toggleDonationRoundup(state);
        assertEquals(1, state.ticket.items.size());
        assertEquals(state.lastEnteredItemId, state.donationLineUid);
        assertEquals(0, new BigDecimal("0.70").compareTo(state.ticket.items.get(0).unitPrice));
        verify(ticketPersistenceService).syncDraft(state);
    }

    // --------------------------------------------------
    // cancelPayments
    // --------------------------------------------------

    /**
     * {@code cancelPayments} reverts the valuation when an adjustment exists and
     * removes the persisted payments when a draft id exists
     * ({@code valuationAdjustment != null} short-circuits the {@code ||},
     * {@code ticketId != null} true).
     */
    @Test
    void cancelPaymentsWithAdjustmentAndDraft() {
        state.payment.ticketDbId = 5L;
        state.payment.valuationAdjustment = new BigDecimal("-1.00");
        state.payment.paymentInProgress = true;
        service.cancelPayments(state);
        verify(valuationReconciler).revert(state.ticket, 5L);
        verify(valuationService).revalue(state);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L);
        assertFalse(state.payment.paymentInProgress);
        assertNull(state.payment.valuationStatus);
        assertNull(state.payment.valuationAdjustment);
    }

    /**
     * {@code cancelPayments} reverts the valuation through the ENGINE status
     * when no adjustment is set ({@code valuationAdjustment != null} false,
     * {@code "ENGINE".equals(valuationStatus)} true; {@code ticketId != null}
     * true).
     */
    @Test
    void cancelPaymentsEngineStatusReverts() {
        state.payment.ticketDbId = 5L;
        state.payment.valuationAdjustment = null;
        state.payment.valuationStatus = "ENGINE";
        service.cancelPayments(state);
        verify(valuationReconciler).revert(state.ticket, 5L);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L);
    }

    /**
     * {@code cancelPayments} skips the revert and the payment removal when there
     * is neither adjustment nor ENGINE status nor draft id
     * ({@code valuationAdjustment != null} false,
     * {@code "ENGINE".equals(valuationStatus)} false, {@code ticketId != null}
     * false).
     */
    @Test
    void cancelPaymentsNoValuationNoDraft() {
        state.payment.ticketDbId = null;
        state.payment.valuationAdjustment = null;
        state.payment.valuationStatus = "LOCAL";
        service.cancelPayments(state);
        verifyNoInteractions(valuationReconciler);
        verify(valuationService).revalue(state);
        verify(ticketPersistenceService, never()).removePaymentsFromTicket(any());
    }

    // --------------------------------------------------
    // processCash
    // --------------------------------------------------

    /**
     * {@code processCash} rejects a null amount ({@code tendered == null} true
     * arm of the guard).
     */
    @Test
    void processCashNullReturns() {
        service.processCash(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCash} rejects a non-positive amount ({@code tendered ==
     * null} false, {@code tendered.signum() <= 0} true).
     */
    @Test
    void processCashZeroReturns() {
        service.processCash(state, BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCash} for an exact payment persists it, shows the method
     * line (no change), opens the drawer and completes the transaction
     * ({@code "CASH".equals} true, change ternary/if false arm, savePayment
     * persists, drawer opens, completion reached).
     */
    @Test
    void processCashExactCompletesAndOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("20"));
        assertEquals(0, new BigDecimal("20").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCash} with an overpayment records the change and shows the
     * change line, but in training neither opens the drawer nor persists
     * (change ternary/if true arm, {@code !trainingMode} false, savePayment
     * training return).
     */
    @Test
    void processCashOverpaymentTrainingShowsChangeNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("10.00");
        service.processCash(state, new BigDecimal("20"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("DONNE 20,00 RENDU 10,00");
        verify(hardwareService, never()).openDrawer();
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCash} outside training logs but does not persist when there
     * is no draft id, and leaves the transaction incomplete (savePayment
     * {@code ticketDbId == null} branch, completion not reached).
     */
    @Test
    void processCashNoDraftDoesNotPersistNorComplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = null;
        service.processCash(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("ESPECES   5,00 E");
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
        verify(hardwareService).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, an exact
     * cash payment completes and persists but leaves the drawer shut (the
     * {@code drawerOpenOnPayment()} false arm at the cash pulse).
     */
    @Test
    void processCashDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processCard
    // --------------------------------------------------

    /**
     * {@code processCard} with a null amount defaults to the remaining due,
     * parks it as a pending request and hands the debit to the terminal port
     * ({@code amount == null} true, defaulted amount positive,
     * {@code pendingCardAmount == null} true).
     */
    @Test
    void processCardNullAmountParksAndRequestsDebit() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        service.processCard(state, null);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.pendingCardAmount));
        verify(hardwareService).displayMessage("CARTE   15,00 E");
        verify(terminal).requestDebit(eq(new BigDecimal("15.00")), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCard} returns when the defaulted amount is non-positive
     * ({@code amount == null} false, {@code amount.signum() <= 0} true in the
     * default guard, then the second guard true).
     */
    @Test
    void processCardZeroRemainingReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCard(state, BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
        assertNull(state.payment.pendingCardAmount);
    }

    /**
     * {@code processCard} ignores a second request while one is pending
     * ({@code amount == null} false, positive,
     * {@code pendingCardAmount != null} true).
     */
    @Test
    void processCardPendingIgnored() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("5.00");
        service.processCard(state, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.pendingCardAmount));
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCard} behind the auto-accept terminal registers the
     * payment synchronously without opening the drawer (accept leg fired
     * before returning, {@code "CASH".equals} false so a plain payment is
     * added).
     */
    @Test
    void processCardAutoAcceptRegistersNoDrawer() {
        service.terminal = new AutoAcceptTerminalClient();
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, new BigDecimal("10"));
        assertNull(state.payment.pendingCardAmount);
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     10,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
    }

    // --------------------------------------------------
    // terminal callback legs / cancelPendingCard
    // --------------------------------------------------

    /**
     * Captures the callback handed to the terminal port by a processCard
     * call parking the given amount.
     *
     * @param amount the amount to request
     * @return the captured transaction callback
     */
    private TerminalTransactionCallback captureCallback(String amount) {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, new BigDecimal(amount));
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), captor.capture());
        return captor.getValue();
    }

    /**
     * The accept leg registers the parked amount and clears it
     * ({@code amount == null} false arm of the registration).
     */
    @Test
    void acceptLegRegistersPendingCard() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertNull(state.payment.pendingCardAmount);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
    }

    /**
     * The accept leg does nothing when the pending amount was cleared
     * meanwhile ({@code amount == null} true arm — ticket cancelled between
     * the request and the terminal decision).
     */
    @Test
    void acceptLegNoneReturns() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        state.payment.pendingCardAmount = null;
        org.mockito.Mockito.reset(hardwareService);
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * The refuse leg drops the pending amount, flags the ticket error and
     * displays the refusal ({@code pendingCardAmount == null} false arm).
     */
    @Test
    void refuseLegClearsAndFlags() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertNull(state.payment.pendingCardAmount);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
        verify(hardwareService).displayMessage("PAIEMENT REFUSE");
    }

    /**
     * The refuse leg does nothing when the pending amount was cleared
     * meanwhile ({@code pendingCardAmount == null} true arm).
     */
    @Test
    void refuseLegNoneReturns() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        state.payment.pendingCardAmount = null;
        org.mockito.Mockito.reset(hardwareService);
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verifyNoInteractions(hardwareService);
        assertNull(state.ticket.transientError);
    }

    /**
     * The error leg drops the pending amount with the terminal's own
     * message (terminal unreachable, protocol failure).
     */
    @Test
    void errorLegClearsWithTerminalMessage() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onError("CLIENT MONETIQUE INJOIGNABLE");
        assertNull(state.payment.pendingCardAmount);
        assertEquals("CLIENT MONETIQUE INJOIGNABLE", state.ticket.transientError);
        verify(hardwareService).displayMessage("PAIEMENT REFUSE");
    }

    /**
     * {@code cancelPendingCard} does nothing when no card request is pending
     * ({@code pendingCardAmount == null} true arm).
     */
    @Test
    void cancelPendingCardNoneReturns() {
        state.payment.pendingCardAmount = null;
        service.cancelPendingCard(state);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(terminal);
    }

    /**
     * {@code cancelPendingCard} drops the pending amount, tells the terminal
     * to abandon and redisplays the total ({@code pendingCardAmount == null}
     * false arm).
     */
    @Test
    void cancelPendingCardClearsAbortsAndShowsTotal() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("15.00");
        service.cancelPendingCard(state);
        assertNull(state.payment.pendingCardAmount);
        verify(terminal).abort();
        verify(hardwareService).displayMessage("TOTAL   20,00 E");
    }

    // --------------------------------------------------
    // processTicketResto
    // --------------------------------------------------

    /**
     * {@code processTicketResto} returns when the defaulted amount is
     * non-positive ({@code amount == null} true, second guard true).
     */
    @Test
    void processTicketRestoZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processTicketResto(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTicketResto} with no engine cap defaults a blank amount to
     * the remaining due, completes and opens the drawer ({@code amount == null}
     * false with {@code signum() <= 0} true, {@code "ENGINE".equals} false,
     * {@code !trainingMode} true, completion reached).
     */
    @Test
    void processTicketRestoLocalDefaultsCompletesOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        service.processTicketResto(state, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TICKET    20,00 E");
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processTicketResto} under an engine cap with a threshold caps the
     * eligible base at {@code min(base, threshold)} and decrements it when the
     * request fits ({@code ENGINE} true, {@code mealEligible != null} true,
     * {@code mealThreshold != null} true, {@code allowed.signum() <= 0} false,
     * {@code amount > allowed} false).
     */
    @Test
    void processTicketRestoEngineWithinThreshold() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("10");
        state.payment.valuationMealThreshold = new BigDecimal("8");
        service.processTicketResto(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.valuationMealEligible));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService).openDrawer();
    }

    /**
     * {@code processTicketResto} caps a request above the allowed base and
     * announces the ceiling ({@code mealThreshold != null} false so the base
     * itself is the ceiling, {@code amount > allowed} true).
     */
    @Test
    void processTicketRestoEngineCapsAboveBase() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TR PLAFONNE  8,00 E");
        verify(hardwareService).displayMessage("TICKET    8,00 E");
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.valuationMealEligible));
    }

    /**
     * {@code processTicketResto} refuses the payment when the eligible base is
     * exhausted ({@code allowed.signum() <= 0} true), displaying the rejection.
     */
    @Test
    void processTicketRestoEngineBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTicketResto} applies no cap when the engine emitted no
     * eligible base, taking a positive explicit amount as-is ({@code ENGINE}
     * true but {@code mealEligible != null} false; default guard fully false).
     */
    @Test
    void processTicketRestoEngineNoEligibleBase() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = null;
        service.processTicketResto(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService).openDrawer();
    }

    /**
     * {@code processTicketResto} in training does not open the drawer
     * ({@code !trainingMode} false arm).
     */
    @Test
    void processTicketRestoTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a meal
     * voucher registers but the drawer stays shut (false arm at the TR pulse).
     */
    @Test
    void processTicketRestoDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        service.processTicketResto(state, BigDecimal.ZERO);
        verify(hardwareService).displayMessage("TICKET    20,00 E");
        verify(hardwareService, never()).openDrawer();
    }

    // --------------------------------------------------
    // processCheque
    // --------------------------------------------------

    /**
     * {@code processCheque} returns when the defaulted amount is non-positive
     * ({@code amount == null} true, second guard true).
     */
    @Test
    void processChequeZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCheque(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCheque} defaults a non-positive amount to the remaining due,
     * completes, persists and opens the drawer ({@code amount == null} false
     * with {@code signum() <= 0} true, {@code !trainingMode} true).
     */
    @Test
    void processChequeDefaultsCompletesOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CHEQUE    20,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCheque} for a partial explicit amount in training neither
     * opens the drawer nor completes ({@code amount == null} false with
     * {@code signum() <= 0} false, {@code !trainingMode} false).
     */
    @Test
    void processChequePartialTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("CHEQUE    5,00 E");
        verify(hardwareService, never()).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a cheque
     * registers but the drawer stays shut (false arm at the cheque pulse).
     */
    @Test
    void processChequeDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        verify(hardwareService).displayMessage("CHEQUE    20,00 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processFidelity
    // --------------------------------------------------

    /**
     * {@code processFidelity} returns on a settled ticket: nothing is due, so
     * no lease is even requested (first guard true).
     */
    @Test
    void processFidelityZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processFidelity(state, null);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(fidelityService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * A REFUSED lease registers no payment and shows the refusal message
     * verbatim — imfid's closed nomenclature reaches the cashier untouched
     * (refusal arm of the reservation protocol, spec §5.1).
     */
    @Test
    void processFidelityRefusedShowsMessageAndPaysNothing() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(refusal("SOLDE CAGNOTTE INSUFFISANT"));
        service.processFidelity(state, new BigDecimal("4"));
        assertEquals("SOLDE CAGNOTTE INSUFFISANT", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(hardwareService);
    }

    /**
     * Builds a refusal verdict carrying the given display message.
     *
     * @param message the exact message the cashier must see
     * @return the refusal verdict
     */
    private FidelityService.BurnVerdict refusal(String message) {
        FidelityService.BurnVerdict verdict = new FidelityService.BurnVerdict();
        verdict.refusalMessage = message;
        return verdict;
    }

    /**
     * Builds a granted verdict for the given amount.
     *
     * @param amount the amount the lease grants
     * @return the granted verdict
     */
    private FidelityService.BurnVerdict grant(String amount) {
        FidelityService.BurnVerdict verdict = new FidelityService.BurnVerdict();
        verdict.grantedAmount = new BigDecimal(amount);
        return verdict;
    }

    /**
     * {@code processFidelity} defaults a non-positive amount to the remaining
     * due, persists it and completes without a drawer opening ({@code amount ==
     * null} false with {@code signum() <= 0} true, second guard false).
     */
    @Test
    void processFidelityDefaultsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        // The GRANTED amount is imfid's, not the caller's: the lease caps it
        // to min(asked, due, burnable base, available balance).
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(grant("10.00"));
        service.processFidelity(state, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("FIDELITE  10,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processFidelity} caps a positive explicit amount at the remaining
     * due and stays incomplete ({@code amount == null} false with
     * {@code signum() <= 0} false — the default guard fully false).
     */
    @Test
    void processFidelityPositivePartial() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        when(fidelityService.reserveLease(eq(state), any(), any()))
                .thenReturn(grant("4"));
        service.processFidelity(state, new BigDecimal("4"));
        assertEquals(0, new BigDecimal("4").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("FIDELITE  4,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processVoucher
    // --------------------------------------------------

    /**
     * {@code processVoucher} rejects a null amount ({@code amount == null} true
     * arm).
     */
    @Test
    void processVoucherNullReturns() {
        service.processVoucher(state, "BON", "N1", null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processVoucher} rejects a non-positive amount ({@code amount ==
     * null} false, {@code amount.signum() <= 0} true).
     */
    @Test
    void processVoucherZeroReturns() {
        service.processVoucher(state, "BON", "N1", BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processVoucher} registers the voucher, persists it and completes
     * ({@code amount == null} false, positive; saveVoucherPayment persists with
     * a draft id, completion reached).
     */
    @Test
    void processVoucherRegistersAndCompletes() {
        state.ticket.totalAmount = new BigDecimal("12.00");
        state.payment.ticketDbId = 3L;
        service.processVoucher(state, "BON", "N1", new BigDecimal("12.00"));
        assertEquals(0, new BigDecimal("12.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("BON       12,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processVoucher} in training registers in memory but persists
     * nothing (saveVoucherPayment {@code trainingMode} true arm).
     */
    @Test
    void processVoucherTrainingNoPersist() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processVoucher(state, "BON", "N1", new BigDecimal("5.00"));
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verifyNoInteractions(ticketPersistenceService);
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * {@code processVoucher} outside training does not persist when there is no
     * draft id (saveVoucherPayment {@code ticketDbId == null} arm).
     */
    @Test
    void processVoucherNoDraftNoPersist() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = null;
        service.processVoucher(state, "BON", "N1", new BigDecimal("5.00"));
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
        verify(hardwareService).displayMessage("BON       5,00 E");
    }

    // --------------------------------------------------
    // finalizeTransaction
    // --------------------------------------------------

    /**
     * {@code finalizeTransaction} validates the draft, remembers it as the last
     * closed ticket and clears the state ({@code ticketId != null} true arm).
     */
    @Test
    void finalizeTransactionValidatesDraft() {
        state.payment.ticketDbId = 9L;
        // The fiscal moment looks up the gift cards ISSUED by this sale to
        // print their vouchers: a Panache call, neutralized here (plain
        // mvn test leaves entities un-enhanced). No card issued = no voucher.
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).validateTicket(9L);
        assertEquals(9L, state.lastClosedTicketId);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
        assertNull(state.payment.ticketDbId);
    }

    /**
     * WITHOUT a card, the fiscal moment touches nothing loyalty-side: no
     * lease to confirm, no event to declare.
     */
    @Test
    void finalizeTransactionWithoutCardEnqueuesNoLoyaltyEvent() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verifyNoInteractions(fidelityService);
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * The FISCAL SEQUENCE of a card-bearing sale, in order: the lease is
     * confirmed FIRST (its id then travels), the closed ticket supplies the
     * reference, and the composed payload is enqueued. The outbox — not a
     * direct call — is what makes the declaration survive a dead imfid.
     */
    @Test
    void finalizeTransactionConfirmsTheLeaseThenEnqueuesTheClosedEvent() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.ticket.Ticket closed =
                new com.intermarche.pos.domain.ticket.Ticket();
        closed.ticketNumber = "C04-000001";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(77L);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"ticketRef\":\"2026-C04-000001\"}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.ticket.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fidelityService, fidEventOutboxService);
        order.verify(fidelityService).confirmLease(eq(state), any());
        order.verify(fidelityService).buildTicketClosedPayload(
                eq(state), eq(java.time.LocalDate.now().getYear() + "-C04-000001"),
                eq("2990000000019"), any(), eq(77L));
        order.verify(fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.FidEvent.EventType.TICKET_CLOSED),
                eq("{\"ticketRef\":\"2026-C04-000001\"}"));
    }

    /**
     * A sale paid WITHOUT the cagnotte still declares its earn: the lease id
     * is simply null and no burn is claimed.
     */
    @Test
    void finalizeTransactionEnqueuesTheEventWithoutALease() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.ticket.Ticket closed =
                new com.intermarche.pos.domain.ticket.Ticket();
        closed.ticketNumber = "C04-000002";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(null);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"earn\":1}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.ticket.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).buildTicketClosedPayload(
                eq(state), any(), eq("2990000000019"), any(), org.mockito.ArgumentMatchers.isNull());
        verify(fidEventOutboxService).enqueue(
                com.intermarche.pos.domain.FidEvent.EventType.TICKET_CLOSED, "{\"earn\":1}");
    }

    /**
     * NO COUPLE, no event: without the verbatim valuation couple the payload
     * is null, and imfid would have nothing to recompute — the composer says
     * so and nothing is enqueued. The lease is still confirmed, because a
     * reserved balance must never stay locked.
     */
    @Test
    void finalizeTransactionEnqueuesNothingWhenThePayloadIsNull() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.ticket.Ticket closed =
                new com.intermarche.pos.domain.ticket.Ticket();
        closed.ticketNumber = "C04-000003";
        when(fidelityService.buildTicketClosedPayload(any(), any(), any(), any(), any()))
                .thenReturn(null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.ticket.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).confirmLease(eq(state), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * A draft that VANISHED between the validation and the lookup enqueues
     * nothing rather than composing a reference from a null ticket: the
     * event would be unmatchable at ingestion.
     */
    @Test
    void finalizeTransactionEnqueuesNothingWhenTheClosedTicketIsGone() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.ticket.Ticket.findById(9L))
                    .thenReturn(null);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).confirmLease(eq(state), any());
        verify(fidelityService, never()).buildTicketClosedPayload(any(), any(), any(), any(), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * Builds a registry instrument as issued by a sale.
     *
     * @param number the instrument number
     * @param amount the loaded face value
     * @return the instrument
     */
    private com.intermarche.pos.domain.StoredValue issuedCard(String number, String amount) {
        com.intermarche.pos.domain.StoredValue card = new com.intermarche.pos.domain.StoredValue();
        card.number = number;
        card.initialAmount = new BigDecimal(amount);
        return card;
    }

    /**
     * Stubs the gift cards issued by the closed sale.
     *
     * @param panache the open Panache static mock
     * @param ticketId the closed draft id
     * @param issued the instruments to return
     */
    @SuppressWarnings("unchecked")
    private void stubIssuedGiftCards(
            org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
            long ticketId, java.util.List<com.intermarche.pos.domain.StoredValue> issued) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.StoredValue> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(issued);
        panache.when(() -> com.intermarche.pos.domain.StoredValue
                .find("issuingTicketId", ticketId)).thenReturn(query);
    }

    /**
     * A sale that issued a gift card prints ITS voucher at the fiscal moment:
     * the customer leaves with the paper carrying the number and the loaded
     * value — the only thing that lets them use the instrument later, since
     * the register never writes the balance on the card itself.
     */
    @Test
    void finalizeTransactionPrintsTheIssuedGiftCardVoucher() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubIssuedGiftCards(panache, 9L,
                    java.util.List.of(issuedCard("296000000000001", "25.00")));
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService).printGiftCardVoucher("296000000000001",
                new BigDecimal("25.00"));
    }

    /**
     * SEVERAL cards issued by the same sale each get their own voucher — a
     * customer buying two gift cards must leave with two papers, not one.
     */
    @Test
    void finalizeTransactionPrintsOneVoucherPerIssuedCard() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubIssuedGiftCards(panache, 9L, java.util.List.of(
                    issuedCard("296000000000001", "25.00"),
                    issuedCard("296000000000002", "50.00")));
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService).printGiftCardVoucher("296000000000001",
                new BigDecimal("25.00"));
        verify(ticketPrinterService).printGiftCardVoucher("296000000000002",
                new BigDecimal("50.00"));
    }

    /**
     * A sale that issued NOTHING prints no voucher: the loop body is skipped,
     * and an ordinary sale must not produce a stray slip.
     */
    @Test
    void finalizeTransactionPrintsNoVoucherWhenNothingWasIssued() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printGiftCardVoucher(any(), any());
    }

    /**
     * Neutralizes the gift-card lookup of the fiscal moment (no card issued).
     *
     * @param panache the open Panache static mock
     * @param ticketId the closed draft id
     */
    @SuppressWarnings("unchecked")
    private void stubNoGiftCards(
            org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache,
            long ticketId) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.StoredValue> empty =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(empty.list()).thenReturn(java.util.List.of());
        panache.when(() -> com.intermarche.pos.domain.StoredValue
                .find("issuingTicketId", ticketId)).thenReturn(empty);
    }

    /**
     * {@code finalizeTransaction} validates nothing when there is no draft but
     * still thanks the customer and clears the state ({@code ticketId != null}
     * false arm).
     */
    @Test
    void finalizeTransactionNoDraft() {
        state.payment.ticketDbId = null;
        service.finalizeTransaction(state);
        verify(ticketPersistenceService, never()).validateTicket(any());
        assertNull(state.lastClosedTicketId);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
    }

    // --------------------------------------------------
    // handlePaymentWithChange — defensive guard (reflection)
    // --------------------------------------------------

    /**
     * Invokes the private {@code handlePaymentWithChange} with a null tendered
     * amount to cover its defensive guard's {@code tendered == null} true arm,
     * unreachable through the public API (every caller pre-guards).
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeNullTenderedReturns() throws Exception {
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(service, state, "CASH", "ESPECES", null);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * Invokes the private {@code handlePaymentWithChange} with a zero tendered
     * amount to cover its defensive guard's {@code tendered.signum() <= 0} true
     * arm (with {@code tendered == null} false), unreachable through the public
     * API.
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeZeroTenderedReturns() throws Exception {
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        m.invoke(service, state, "CASH", "ESPECES", BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        verifyNoInteractions(ticketPersistenceService);
        assertTrue(state.payment.payments.isEmpty());
    }
}
