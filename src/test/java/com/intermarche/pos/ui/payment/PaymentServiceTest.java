package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.service.valuation.ValuationReconciler;
import com.intermarche.pos.service.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    /** A real POS state carrying real payment and ticket sub-states. */
    private PosState state;

    /**
     * Builds a fresh service with fresh mocks and a fresh real state before
     * each test, defaulting the virtual terminal on (its production default).
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
        service.valuationService = valuationService;
        service.valuationReconciler = valuationReconciler;
        service.virtualTpe = true;
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

    // --------------------------------------------------
    // processCard
    // --------------------------------------------------

    /**
     * {@code processCard} with a null amount on the virtual terminal defaults to
     * the remaining due and parks it as a pending request ({@code amount ==
     * null} true, defaulted amount positive, {@code virtualTpe} true,
     * {@code pendingCardAmount == null} true).
     */
    @Test
    void processCardVirtualNullAmountParks() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        service.processCard(state, null);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.pendingCardAmount));
        verify(hardwareService).displayMessage("CARTE   15,00 E");
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
        assertNull(state.payment.pendingCardAmount);
    }

    /**
     * {@code processCard} on the virtual terminal ignores a second request while
     * one is pending ({@code amount == null} false, positive, {@code virtualTpe}
     * true, {@code pendingCardAmount != null} true).
     */
    @Test
    void processCardVirtualPendingIgnored() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("5.00");
        service.processCard(state, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.pendingCardAmount));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCard} with the virtual terminal off registers the payment
     * immediately without opening the drawer ({@code virtualTpe} false,
     * {@code "CASH".equals} false so a plain payment is added).
     */
    @Test
    void processCardPhysicalRegistersNoDrawer() {
        service.virtualTpe = false;
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     10,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
    }

    // --------------------------------------------------
    // confirmPendingCard / refusePendingCard / cancelPendingCard
    // --------------------------------------------------

    /**
     * {@code confirmPendingCard} does nothing when no card request is pending
     * ({@code amount == null} true arm).
     */
    @Test
    void confirmPendingCardNoneReturns() {
        state.payment.pendingCardAmount = null;
        service.confirmPendingCard(state);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code confirmPendingCard} registers the parked amount and clears it
     * ({@code amount == null} false arm).
     */
    @Test
    void confirmPendingCardRegisters() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.pendingCardAmount = new BigDecimal("15.00");
        service.confirmPendingCard(state);
        assertNull(state.payment.pendingCardAmount);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CARTE     15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
    }

    /**
     * {@code refusePendingCard} does nothing when no card request is pending
     * ({@code pendingCardAmount == null} true arm).
     */
    @Test
    void refusePendingCardNoneReturns() {
        state.payment.pendingCardAmount = null;
        service.refusePendingCard(state);
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code refusePendingCard} drops the pending amount, flags the ticket error
     * and displays the refusal ({@code pendingCardAmount == null} false arm).
     */
    @Test
    void refusePendingCardClearsAndFlags() {
        state.payment.pendingCardAmount = new BigDecimal("15.00");
        service.refusePendingCard(state);
        assertNull(state.payment.pendingCardAmount);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
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
    }

    /**
     * {@code cancelPendingCard} drops the pending amount and redisplays the
     * total ({@code pendingCardAmount == null} false arm).
     */
    @Test
    void cancelPendingCardClearsAndShowsTotal() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.pendingCardAmount = new BigDecimal("15.00");
        service.cancelPendingCard(state);
        assertNull(state.payment.pendingCardAmount);
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

    // --------------------------------------------------
    // processFidelity
    // --------------------------------------------------

    /**
     * {@code processFidelity} returns when the defaulted amount is non-positive
     * ({@code amount == null} true, second guard true).
     */
    @Test
    void processFidelityZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processFidelity(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
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
        service.finalizeTransaction(state);
        verify(ticketPersistenceService).validateTicket(9L);
        assertEquals(9L, state.lastClosedTicketId);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
        assertNull(state.payment.ticketDbId);
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
