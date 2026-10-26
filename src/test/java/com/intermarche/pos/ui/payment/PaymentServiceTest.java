package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.valuation.ValuationReconciler;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.service.sync.register.FidEventOutboxService;
import com.intermarche.pos.ui.fidelity.FidelityService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.terminal.AutoAcceptTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient;
import com.intermarche.pos.ui.hardware.terminal.TerminalOutcome;
import com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;

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

    /** The cheque reader mock, whose two callbacks the tests fire by hand. */
    private com.intermarche.pos.ui.hardware.ChequeReadingService chequeReadingService;

    /** A real POS state carrying real payment and ticket sub-states. */
    private PosState state;

    /** The conditional-printing rule (LC-08-03), mocked. */
    private com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;

    /**
     * Builds a fresh service with fresh mocks and a fresh real state before
     * each test; the terminal port is a mock whose callback legs the tests
     * fire by hand.
     */
    /** The invoice service asked for an automatic document at closing time. */
    private com.intermarche.pos.ui.invoice.InvoiceService invoiceService;

    /** The administered tender rules opposed to every settlement (BO-03-02). */
    private TenderRulesService tenderRulesService;

    /** The supervisor credential asked for when an administered bound is passed. */
    private com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    @BeforeEach
    void setUp() {
        service = new PaymentService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        hardwareService = mock(HardwareService.class);
        ticketPersistenceService = mock(TicketPersistenceService.class);
        valuationService = mock(ValuationService.class);
        valuationReconciler = mock(ValuationReconciler.class);
        service.hardwareService = hardwareService;
        service.ticketPersistenceService = ticketPersistenceService;
        service.consumptionService = mock(com.intermarche.pos.service.sync.ConsumptionService.class);
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
        // LC-08-04-16: closing a sale asks the invoice service whether the settlement
        // calls for an automatic document. The stand-in answers nothing, which is the
        // shop that administered no automatic emission — the default of every case
        // below.
        invoiceService = mock(com.intermarche.pos.ui.invoice.InvoiceService.class);
        service.invoiceService = invoiceService;
        // BO-10-01-26: the commercial management tool's document after its
        // settlement; the stand-in prints nothing, as no such document is loaded.
        service.channelDocumentPrinter = mock(com.intermarche.pos.ui.recall.ChannelDocumentPrinter.class);
        // The drawer-open-on-payment rule defaults to ON, the pre-existing
        // behavior every physical-tender case here relies on (BO-10-02-12).
        posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(true);
        when(posSettingsService.donationRoundingStepCents()).thenReturn(100);
        // BO-03-02-34: the card receipt is printed, as it always was.
        when(posSettingsService.cardReceiptPrinted()).thenReturn(true);
        service.posSettingsService = posSettingsService;
        // The administered tender rules (BO-03-02): the stand-in answers as a
        // shop that administered NO tender — no bound is opposed, change is
        // given, and the drawer follows the register's own rule. That is the
        // behaviour every case below was written against.
        tenderRulesService = mock(TenderRulesService.class);
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(TenderRulesService.Verdict.silent());
        when(tenderRulesService.checkChange(any(), any()))
                .thenReturn(TenderRulesService.Verdict.silent());
        when(tenderRulesService.changeAllowed(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> call.getArgument(1));
        when(tenderRulesService.opensDrawer(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> call.getArgument(3));
        service.tenderRulesService = tenderRulesService;
        // The supervisor credential (BO-03-02-10): the stand-in refuses by
        // default, so a case that wants an authorization grants it explicitly.
        endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        // The control points of BO-10-02 answer as they do with nothing
        // administered — no control — which is the behaviour every case of this
        // class was written against. A case exercising a control states its own.
        when(endorsementService.check(any(), any()))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.NONE);
        service.endorsementService = endorsementService;
        chequeReadingService =
                mock(com.intermarche.pos.ui.hardware.ChequeReadingService.class);
        service.chequeReadingService = chequeReadingService;
        // The register's own identity (BO-03-02-43 tokens, BO-03-02-27/-29
        // rules): the stand-in names no terminal, so a case needing one states
        // it, and every pre-existing case keeps a register the referential
        // cannot place — which opposes nothing.
        service.ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
        // The conditional-printing rule (LC-08-03) answers as it does with the
        // option OFF — every document named, nothing forced —, which is the
        // behaviour every case here was written against.
        printPolicy = mock(com.intermarche.pos.ui.hardware.PrintPolicy.class);
        when(printPolicy.decide(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.intermarche.pos.ui.hardware.PrintPolicy.Decision(true, true, true));
        service.printPolicy = printPolicy;
        // Step-6 tender seams (EXP-66/67/58): a supplier gift-card issuer, the
        // Resto Flash mobile service and the SOGEC compensation circuit. The
        // stand-ins let each new tender's arms be driven explicitly per test.
        restoFlashClient = mock(RestoFlashClient.class);
        service.restoFlashClient = restoFlashClient;
        chequeGuaranteeClient = mock(ChequeGuaranteeClient.class);
        service.chequeGuaranteeClient = chequeGuaranteeClient;
        giftCardIssuerClient = mock(GiftCardIssuerClient.class);
        service.giftCardIssuerClient = giftCardIssuerClient;
        sogecCompensationService = mock(SogecCompensationService.class);
        service.sogecCompensationService = sogecCompensationService;
        // LC-03-07-01: the transaction-end survey trigger, asked at the sale's
        // close; a mock keeps it silent, which is the shop that administers no
        // survey — the default of every case below.
        service.surveyTriggerService =
                mock(com.intermarche.pos.ui.supervision.SurveyTriggerService.class);
        // LC-07-15 / LC-13-01-01: the cash changer and the safe transfer. The
        // stand-ins answer as a till without a cash changer — no alert, nothing
        // to transfer — which is what every pre-existing case was written for.
        cashChangerService = mock(com.intermarche.pos.ui.hardware.CashChangerService.class);
        service.cashChangerService = cashChangerService;
        service.cashMovementService = mock(com.intermarche.pos.service.CashMovementService.class);
        service.cashSessionService = mock(com.intermarche.pos.service.CashSessionService.class);
        // LC-08-01-05: the Portuguese documents; a mock states nothing, which is
        // every register outside Portugal.
        service.portugueseFiscalService = mock(com.intermarche.pos.ui.invoice.PortugueseFiscalService.class);
        // LC-07-11-01 / LC-02-09-03: the payment hub is not administered and no
        // article was activated — every pre-existing case; the gift-card slip is
        // printed, as the back office has it by default (LC-07-11-03).
        nexoHubClient = mock(NexoHubClient.class);
        service.nexoHubClient = nexoHubClient;
        service.dematerialisedService = mock(com.intermarche.pos.ui.ticket.DematerialisedService.class);
        when(posSettingsService.giftCardPaymentTicket()).thenReturn(true);
        state = new PosState();
    }

    /** The payment hub (LC-07-11-01). */
    private NexoHubClient nexoHubClient;

    /** The cash changer seam (LC-07-15). */
    private com.intermarche.pos.ui.hardware.CashChangerService cashChangerService;

    /** The Resto Flash mobile-voucher seam (EXP-67). */
    private RestoFlashClient restoFlashClient;

    /** The mocked cheque-guarantee service (BO-03-02-24). */
    private ChequeGuaranteeClient chequeGuaranteeClient;

    /** The supplier gift-card issuer registry seam (EXP-66). */
    private GiftCardIssuerClient giftCardIssuerClient;

    /** The SOGEC compensation circuit (EXP-58). */
    private SogecCompensationService sogecCompensationService;

    /**
     * Makes the cheque reader answer with a magnetic line as soon as a reading is
     * started, on the calling thread — the real service answers from its own follower
     * thread, which nothing here depends on.
     *
     * @param line the magnetic line the reader reports
     */
    private void chequeReaderAnswers(String line) {
        // The callback only DEPOSITS the reading, as the follower thread does in
        // production; the scenarios settle it with settlePendingChequeRead, which
        // is what the next request carries.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onRead =
                    (java.util.function.Consumer<String>) invocation.getArgument(1);
            onRead.accept(line);
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
    }

    /**
     * Makes the cheque reader refuse as soon as a reading is started.
     *
     * @param message the operator-facing message the reader reports
     */
    private void chequeReaderRefuses(String message) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onError =
                    (java.util.function.Consumer<String>) invocation.getArgument(2);
            onError.accept(message);
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
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

    /**
     * Opens a {@code PanacheEntityBase} static mock that answers every
     * catalog lookup with nothing. The cancellation slip resolves the
     * tender's administered label — a lookup a plain unit test has no
     * persistence context to answer, and whose failure the slip funnel
     * swallows into a silence; the mock keeps the slip printing.
     *
     * @return the opened static mock, to close through try-with-resources
     */
    private MockedStatic<PanacheEntityBase> quietCatalog() {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> nothing = mock(PanacheQuery.class);
        when(nothing.firstResult()).thenReturn(null);
        panache.when(() -> PanacheEntityBase.find(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenReturn(nothing);
        panache.when(() -> PanacheEntityBase.list(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenReturn(java.util.List.of());
        return panache;
    }

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

    /** LC-07-16-01: a part payment cannot open the settlement drawer. */
    @Test
    void drawerWaitsForCompletedSale() {
        state.payment.addPayment("CASH", BigDecimal.ONE);
        service.openSettlementDrawer(state, false);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-16-01, BO-10-02-12: training sends no opening order when the shop keeps the drawer shut in training. */
    @Test
    void settlementDrawerTraining() {
        state.trainingMode = true;
        state.payment.transactionComplete = true;
        state.payment.addPayment("CASH", BigDecimal.ONE);
        when(posSettingsService.drawerOpenInTraining()).thenReturn(false);
        service.openSettlementDrawer(state, false);
        verify(hardwareService, never()).openDrawer();
    }

    /** BO-10-02-12: a training cash sale opens the drawer like a real one where the shop administers it. */
    @Test
    void settlementDrawerTrainingOpensWhenAdministered() {
        state.trainingMode = true;
        state.payment.transactionComplete = true;
        state.payment.addPayment("CASH", BigDecimal.ONE);
        when(posSettingsService.drawerOpenInTraining()).thenReturn(true);
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(true);
        when(tenderRulesService.opensDrawer("CASH", false, false, true)).thenReturn(true);
        service.openSettlementDrawer(state, false);
        verify(hardwareService).openDrawer();
    }

    /** LC-07-16-01: a sale sends at most one opening order across both moments. */
    @Test
    void settlementDrawerOpensOnlyOnce() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CASH", BigDecimal.ONE);
        service.openSettlementDrawer(state, false);
        service.openSettlementDrawer(state, true);
        verify(hardwareService, times(1)).openDrawer();
    }

    /** LC-07-16-01: an administered card tender can request drawer opening. */
    @Test
    void administeredCardCanOpenDrawer() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        when(tenderRulesService.opensDrawer("CARD", false, false, false)).thenReturn(true);
        service.openSettlementDrawer(state, false);
        verify(hardwareService).openDrawer();
    }

    /** LC-07-16-01: a virtual tender without administration retains a closed drawer. */
    @Test
    void defaultCardKeepsDrawerClosed() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        service.openSettlementDrawer(state, false);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-16-01: the receipt moment is passed to the administered rule. */
    @Test
    void administeredReceiptMoment() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CHEQUE", BigDecimal.ONE);
        when(tenderRulesService.opensDrawer("CHEQUE", false, true, false)).thenReturn(true);
        service.openSettlementDrawer(state, true);
        verify(hardwareService).openDrawer();
    }

    /** LC-07-16-01: a null change amount means no change is due. */
    @Test
    void drawerWithNullChange() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.lastChangeAmount = null;
        service.openSettlementDrawer(state, false);
        verify(tenderRulesService).opensDrawer("CARD", false, false, false);
    }

    /** LC-07-16-01: zero change takes the second false leg of the change rule. */
    @Test
    void drawerWithZeroChange() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.lastChangeAmount = BigDecimal.ZERO;
        service.openSettlementDrawer(state, false);
        verify(tenderRulesService).opensDrawer("CARD", false, false, false);
    }

    /** LC-07-16-01: positive change is forwarded to the tender rule. */
    @Test
    void drawerWithPositiveChange() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.lastChangeAmount = BigDecimal.ONE;
        service.openSettlementDrawer(state, false);
        verify(tenderRulesService).opensDrawer("CARD", true, false, false);
    }

    /** LC-07-16-01: the global default can keep unadministered physical tenders shut. */
    @Test
    void physicalDrawerGlobalDefaultOff() {
        state.payment.transactionComplete = true;
        state.payment.addPayment("CASH", BigDecimal.ONE);
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        service.openSettlementDrawer(state, false);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-16-01: an incomplete recovery entry cannot prevent other rules. */
    @Test
    void drawerSkipsUnknownEntryAndChecksOtherTenders() {
        state.payment.transactionComplete = true;
        state.payment.addPayment(null, BigDecimal.ONE);
        state.payment.addPayment("CARD", BigDecimal.ONE);
        service.openSettlementDrawer(state, false);
        verify(tenderRulesService).opensDrawer("CARD", false, false, false);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-05-05: a deferred manual cheque keeps a distinct accounting key. */
    @Test
    void deferredChequeRegistersOnce() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 42L;
        service.processDeferredCheque(state, BigDecimal.TEN);
        assertEquals(1, state.payment.payments.size());
        assertEquals("CHEQUE_DEFERRED", state.payment.payments.get(0).method);
        assertEquals(BigDecimal.TEN, state.payment.payments.get(0).amount);
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(eq(42L), any());
        verifyNoInteractions(terminal, chequeReadingService);
    }

    /** LC-07-05-05: a null amount uses the unpaid remainder (first guard leg). */
    @Test
    void deferredChequeNullAmountUsesRemainder() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processDeferredCheque(state, null);
        assertEquals(new BigDecimal("10.00"), state.payment.payments.get(0).amount);
    }

    /** LC-07-05-05: zero uses the remainder (second guard leg). */
    @Test
    void deferredChequeZeroAmountUsesRemainder() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processDeferredCheque(state, BigDecimal.ZERO);
        assertEquals(new BigDecimal("10.00"), state.payment.payments.get(0).amount);
    }

    /** LC-07-05-05: a negative input also requests the remainder. */
    @Test
    void deferredChequeNegativeAmountUsesRemainder() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processDeferredCheque(state, BigDecimal.ONE.negate());
        assertEquals(new BigDecimal("10.00"), state.payment.payments.get(0).amount);
    }

    /** LC-07-05-05: nothing is taken when no unpaid amount remains. */
    @Test
    void deferredChequeWithNoRemainderDoesNothing() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processDeferredCheque(state, null);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(hardwareService, terminal, chequeReadingService);
    }

    /** LC-07-05-05: a refused tender bound leaves the sale unpaid. */
    @Test
    void deferredChequeHonoursTenderRefusal() {
        state.ticket.totalAmount = BigDecimal.TEN;
        TenderRulesService.Verdict refusal = new TenderRulesService.Verdict(
                com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING, "limit");
        when(tenderRulesService.check(eq("CHEQUE_DEFERRED"), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(refusal);
        service.processDeferredCheque(state, BigDecimal.ONE);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(hardwareService);
    }

    /** LC-07-05-05: training takes the cheque without opening the physical drawer. */
    @Test
    void deferredChequeTrainingKeepsDrawerClosed() {
        state.trainingMode = true;
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processDeferredCheque(state, BigDecimal.ONE);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-05-05: the administered deferred-tender drawer rule is obeyed. */
    @Test
    void deferredChequeCanKeepDrawerClosed() {
        state.ticket.totalAmount = BigDecimal.TEN;
        when(tenderRulesService.opensDrawer(eq("CHEQUE_DEFERRED"), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        service.processDeferredCheque(state, BigDecimal.ONE);
        verify(hardwareService, never()).openDrawer();
    }

    /** LC-07-05-05: an ordinary physical cheque opens the drawer when requested. */
    @Test
    void deferredChequeOpensDrawer() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processDeferredCheque(state, BigDecimal.TEN);
        assertTrue(state.payment.transactionComplete);
        verify(hardwareService).openDrawer();
    }

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

    /** LC-07-17-02: an upper tenth-step produces a five-cent donation. */
    @Test
    void donationUsesAdministeredUpperStep() {
        when(posSettingsService.donationRoundingStepCents()).thenReturn(10);
        state.ticket.totalAmount = new BigDecimal("4.35");
        service.toggleDonationRoundup(state);
        assertEquals(0, new BigDecimal("0.05").compareTo(state.ticket.items.get(0).unitPrice));
    }

    /** LC-07-17-02: nearest five-cent rounding can suggest a positive donation. */
    @Test
    void donationUsesNearestStepUp() {
        when(posSettingsService.donationRoundingStepCents()).thenReturn(5);
        when(posSettingsService.donationRoundingNearest()).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("4.33");
        service.toggleDonationRoundup(state);
        assertEquals(0, new BigDecimal("0.02").compareTo(state.ticket.items.get(0).unitPrice));
    }

    /** LC-07-17-02: nearest-step rounding down never books a negative donation. */
    @Test
    void donationNearestStepDownAddsNothing() {
        when(posSettingsService.donationRoundingStepCents()).thenReturn(5);
        when(posSettingsService.donationRoundingNearest()).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("4.32");
        service.toggleDonationRoundup(state);
        assertNull(state.donationLineUid);
        verify(ticketPersistenceService, never()).syncDraft(any());
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

    /**
     * {@code cancelOnePayment} at a valid index (EXP-63) drops only that entry and,
     * with a draft id, re-persists the survivors (removeAll + replay), leaving the
     * payment in progress — unlike the global cancel.
     */
    @Test
    void cancelOnePaymentValidRepersistsSurvivors() {
        state.payment.ticketDbId = 5L;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        state.payment.addPayment("CARD", new BigDecimal("5.00"));
        assertTrue(service.cancelOnePayment(state, 0));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CARD", state.payment.payments.get(0).method);
        verify(ticketPersistenceService).removePaymentsFromTicket(5L, false);
        verify(ticketPersistenceService).addPaymentToTicket(eq(5L), any());
        verify(ticketPersistenceService).journalCancelledPayment(5L, "CASH", new BigDecimal("10.00"));
    }

    /**
     * BO-03-02-35: cancelling a SINGLE settlement leaves its slip, naming the
     * settlement key when the referential administers no label for it. A
     * refused index prints nothing — the slip follows the removal, it does not
     * announce it.
     */
    @Test
    void cancelOnePaymentPrintsTheCancellationSlip() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            state.payment.ticketDbId = null;
            state.payment.addPayment("CHEQUE", new BigDecimal("42.50"));
            assertTrue(service.cancelOnePayment(state, 0));
            verify(ticketPrinterService).printPaymentCancellation(
                    org.mockito.ArgumentMatchers.isNull(), eq("CHEQUE"),
                    eq(new BigDecimal("42.50")));

            state.payment.addPayment("CASH", new BigDecimal("5.00"));
            assertFalse(service.cancelOnePayment(state, 7));
            verify(ticketPrinterService, never()).printPaymentCancellation(
                    any(), eq("CASH"), any());
        }
    }

    /**
     * BO-03-02-35: cancelling the whole payment leaves ONE slip per settlement
     * the customer actually handed over, and none at all on a payment where
     * nothing was registered.
     */
    @Test
    void cancelPaymentsPrintsOneSlipPerSettlement() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            state.payment.ticketDbId = null;
            state.payment.addPayment("CHEQUE", new BigDecimal("42.50"));
            state.payment.addPayment("CARD", new BigDecimal("7.50"));
            service.cancelPayments(state);
            verify(ticketPrinterService).printPaymentCancellation(
                    org.mockito.ArgumentMatchers.isNull(), eq("CHEQUE"),
                    eq(new BigDecimal("42.50")));
            verify(ticketPrinterService).printPaymentCancellation(
                    org.mockito.ArgumentMatchers.isNull(), eq("CARD"),
                    eq(new BigDecimal("7.50")));

            service.cancelPayments(state);
            verify(ticketPrinterService, org.mockito.Mockito.times(2))
                    .printPaymentCancellation(any(), any(), any());
        }
    }

    /**
     * BO-03-02-27: a tender the store forbade on a SELF-SERVICE lane is refused
     * there and says so, and nothing is registered. On a manned till the same
     * tender settles as before — the rule is read off the register's own kind.
     */
    @Test
    void aTenderForbiddenOnSelfServiceIsRefusedThere() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = false;
        com.intermarche.pos.domain.store.CheckoutPoint lane =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.terminalId = "C04";
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_TGCS;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .findByCode("CHEQUE")).thenReturn(tender);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals("REGLEMENT INDISPONIBLE EN LIBRE-SERVICE", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * BO-03-02-29: a tender the store WATCHES on a self-service lane raises the
     * supervisor hold every administered tender rule raises, and registers
     * nothing until the hostess allows it.
     */
    @Test
    void aWatchedTenderOnSelfServiceCallsTheHostess() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = true;
        tender.selfServiceSupervision = true;
        com.intermarche.pos.domain.store.CheckoutPoint lane =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.terminalId = "C04";
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_DN;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                    .findByCode("CHEQUE")).thenReturn(tender);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals("CHEQUE", state.payment.tenderHeldMethod);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * On a MANNED till neither rule is opposed, and a self-service lane the
     * referential does not describe is treated as manned: a lane missing from
     * the referential is a referential problem, not a reason to refuse a
     * customer at the one moment they are paying (both null arms).
     */
    @Test
    void aMannedTillAndAnUndescribedLaneOpposeNothing() {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = "CHEQUE";
        tender.selfServiceAllowed = false;
        com.intermarche.pos.domain.store.CheckoutPoint manned =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        manned.terminalId = "C04";
        manned.tpvType = com.intermarche.pos.domain.store.TpvType.CASH_REGISTER;
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(manned);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals(1, state.payment.payments.size());

        // The lane the referential does not describe at all.
        state.clearPayments();
        state.ticket.transientError = null;
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint
                    .findByTerminal("C04")).thenReturn(null);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
        }
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * BO-03-03-11: a settlement paid by gift card leaves its slip, carrying the
     * balance the card STILL holds — the issuer's answer minus what was just
     * spent. A card whose balance covers more than the due is settled at the
     * due, and the remainder is what the customer walks away with.
     */
    @Test
    void aGiftCardPaymentPrintsItsSlipWithTheRemainingBalance() {
        when(giftCardIssuerClient.query("9999000011114321")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                        true, new BigDecimal("50.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "9999000011114321", new BigDecimal("30.00"));
        verify(ticketPrinterService).printGiftCardPaymentReceipt(
                org.mockito.ArgumentMatchers.isNull(), eq("9999000011114321"),
                eq(new BigDecimal("30.00")), eq(new BigDecimal("20.00")));
    }

    /**
     * A card whose balance is SMALLER than the amount asked settles the balance
     * and leaves nothing on the card: the remaining balance is zero and never
     * negative (BO-03-03-11, the capping arm).
     */
    @Test
    void aCappedGiftCardPaymentLeavesNoBalance() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            when(giftCardIssuerClient.query("4321")).thenReturn(
                    new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                            true, new BigDecimal("12.00")));
            state.ticket.totalAmount = new BigDecimal("30.00");
            service.processSupplierGiftCard(state, "4321", new BigDecimal("30.00"));
            verify(ticketPrinterService).printGiftCardPaymentReceipt(
                    org.mockito.ArgumentMatchers.isNull(), eq("4321"),
                    // The capped balance is what the subtraction leaves,
                    // 12,00 - 12,00, still at the currency's two decimals.
                    eq(new BigDecimal("12.00")), eq(new BigDecimal("0.00")));
        }
    }

    /**
     * A card the issuer REFUSES settles nothing and prints nothing: there is no
     * settlement to witness (BO-03-03-11).
     */
    @Test
    void aRefusedGiftCardPrintsNoSlip() {
        when(giftCardIssuerClient.query("0000")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(
                        false, new BigDecimal("50.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "0000", new BigDecimal("30.00"));
        verify(ticketPrinterService, never()).printGiftCardPaymentReceipt(
                any(), any(), any(), any());
    }

    /** LC-07-05-03: the administered cheque carries words and the actual ticket number. */
    @Test
    void chequePrintsWordsAndTicketIdentity() {
        when(posSettingsService.chequeEndorsementLines()).thenReturn("{montant-lettres};{ticket}");
        state.payment.ticketDbId = 42L;
        state.ticket.totalAmount = new BigDecimal("20.00");
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-000042";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(42L)).thenReturn(ticket);
            service.processCheque(state, new BigDecimal("20.00"));
        }
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chequeReadingService).read(text.capture(), any(), any());
        assertEquals("vingt euros et zéro centimes\nC04-000042", text.getValue());
    }

    /** LC-07-05-03: a missing draft id renders no invented ticket identity. */
    @Test
    void chequeTicketTokenWithoutDraft() {
        when(posSettingsService.chequeEndorsementLines()).thenReturn("TICKET {ticket}");
        state.payment.ticketDbId = null;
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processCheque(state, BigDecimal.TEN);
        verify(chequeReadingService).read(eq("TICKET"), any(), any());
    }

    /** LC-07-05-03: a missing database row also renders no invented identity. */
    @Test
    void chequeTicketTokenWithMissingDraft() {
        when(posSettingsService.chequeEndorsementLines()).thenReturn("TICKET {ticket}");
        state.payment.ticketDbId = 42L;
        state.ticket.totalAmount = BigDecimal.TEN;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(42L)).thenReturn(null);
            service.processCheque(state, BigDecimal.TEN);
        }
        verify(chequeReadingService).read(eq("TICKET"), any(), any());
    }

    /** LC-07-05-03: an unnumbered draft leaves the ticket token empty. */
    @Test
    void chequeTicketTokenWithUnnumberedDraft() {
        when(posSettingsService.chequeEndorsementLines()).thenReturn("TICKET {ticket}");
        state.payment.ticketDbId = 42L;
        state.ticket.totalAmount = BigDecimal.TEN;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(42L)).thenReturn(new Ticket());
            service.processCheque(state, BigDecimal.TEN);
        }
        verify(chequeReadingService).read(eq("TICKET"), any(), any());
    }

    /**
     * BO-03-02-43: with nothing administered, the cheque back carries the date
     * and the amount, exactly as before the key existed.
     */
    @Test
    void theChequeEndorsementFallsBackOnTheDateAndTheAmount() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("20.00"));
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chequeReadingService).read(captor.capture(), any(), any());
        assertTrue(captor.getValue().contains("20,00 EUR"), captor.getValue());
        assertEquals(2, captor.getValue().split("\n").length, captor.getValue());
    }

    /**
     * BO-03-02-43: an ADMINISTERED endorsement is printed line by line with its
     * tokens filled; an empty administered line prints nothing rather than a
     * blank band, and a token the register cannot answer renders empty rather
     * than leaving the token in ink.
     */
    @Test
    void theAdministeredChequeEndorsementIsPrintedLineByLine() {
        when(posSettingsService.chequeEndorsementLines())
                .thenReturn("{montant} EUR;;LE {date} A {heure};CAISSE {caisse} PDV {pdv}");
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, new BigDecimal("20.00"));
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chequeReadingService).read(captor.capture(), any(), any());
        String[] lines = captor.getValue().split("\n");
        // The empty administered line is dropped: three lines, not four.
        assertEquals(3, lines.length, captor.getValue());
        assertEquals("20,00 EUR", lines[0]);
        assertTrue(lines[1].startsWith("LE "), lines[1]);
        assertTrue(lines[1].contains(" A "), lines[1]);
        // No till service and no PDV number on this fixture: both render empty,
        // and the token never reaches the paper.
        assertFalse(captor.getValue().contains("{"), captor.getValue());
    }

    /**
     * {@code cancelOnePayment} on an out-of-range index (EXP-63) removes nothing
     * and touches no draft.
     */
    @Test
    void cancelOnePaymentOutOfRangeIsNoOp() {
        state.payment.ticketDbId = 5L;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        assertFalse(service.cancelOnePayment(state, 3));
        assertEquals(1, state.payment.payments.size());
        verify(ticketPersistenceService, never()).removePaymentsFromTicket(any());
    }

    /**
     * {@code cancelOnePayment} with no draft id (EXP-63) still drops the entry but
     * re-persists nothing (the ticketId-null arm).
     */
    @Test
    void cancelOnePaymentWithoutDraftDropsEntryOnly() {
        state.payment.ticketDbId = null;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        assertTrue(service.cancelOnePayment(state, 0));
        assertEquals(0, state.payment.payments.size());
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

    // --------------------------------------------------
    // processCash — legal cash rounding (LC-07-03)
    // --------------------------------------------------

    /**
     * With rounding OFF (step zero, the French default), an amount off any step is
     * accepted exactly as before and nothing is booked: the rule must not leak into
     * a shop that has not enabled it.
     */
    @Test
    void processCashWithoutRoundingAcceptsAnyAmount() {
        state.cashRoundingStepCents = 0;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * A step of one cent is not a rounding either and takes the same leg.
     */
    @Test
    void processCashWithOneCentStepAcceptsAnyAmount() {
        state.cashRoundingStepCents = 1;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * Where the shop rounds, an amount off the step is REFUSED and nothing is
     * registered: the coins to make it up no longer circulate
     * ({@code LC-07-03-05}).
     */
    @Test
    void processCashRefusesAnAmountOffTheStep() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.42");
        service.processCash(state, new BigDecimal("23.42"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("MONTANT NON MULTIPLE DE 0,05 E", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * Rounding DOWN: the sale owes 24,62, the customer hands over 24,60, and the
     * two cents are booked on ARRONDI so the settlements still sum to the total
     * ({@code LC-07-03-01/06}). The rounding is registered BEFORE the cash, so the
     * cash line settles a remainder already on the step.
     */
    @Test
    void processCashRoundsDownAndBooksThePositiveDifference() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("24.60"));
        assertEquals(2, state.payment.payments.size());
        assertEquals("ARRONDI", state.payment.payments.get(0).method);
        assertEquals(0, new BigDecimal("0.02").compareTo(state.payment.payments.get(0).amount));
        assertEquals("CASH", state.payment.payments.get(1).method);
        assertEquals(0, new BigDecimal("24.60").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * Rounding UP: the sale owes 23,43, the customer hands over 23,45, and the
     * difference booked is NEGATIVE — the other side of the same rule.
     */
    @Test
    void processCashRoundsUpAndBooksTheNegativeDifference() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.43");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("23.45"));
        assertEquals(2, state.payment.payments.size());
        assertEquals(0, new BigDecimal("-0.02").compareTo(state.payment.payments.get(0).amount));
        assertEquals(0, new BigDecimal("23.45").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("23.43").compareTo(state.payment.paidAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * A remainder already on the step books no rounding at all — the difference is
     * zero and the guard returns before registering anything.
     */
    @Test
    void processCashOnTheStepBooksNoRounding() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("23.45");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("23.45"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
    }

    /**
     * A PART payment in cash books no rounding: the sale goes on, its remainder is
     * rounded when it is finally settled, and rounding a part payment would round
     * the same sale twice.
     */
    @Test
    void processCashPartPaymentBooksNoRounding() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("10.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("CASH", state.payment.payments.get(0).method);
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * The rounding applies to what is SETTLED IN CASH and not to the ticket total:
     * a sale part-paid by card rounds the REMAINDER the customer hands over.
     */
    @Test
    void processCashRoundsTheRemainderNotTheTotal() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        state.payment.addPayment("CARD", new BigDecimal("10.00"));
        service.processCash(state, new BigDecimal("14.60"));
        assertEquals(3, state.payment.payments.size());
        assertEquals("ARRONDI", state.payment.payments.get(1).method);
        assertEquals(0, new BigDecimal("0.02").compareTo(state.payment.payments.get(1).amount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * Overpaying with a note still rounds the sale and gives change on the ROUNDED
     * remainder: handing over 50 on a 24,62 sale returns 25,40, not 25,38.
     */
    @Test
    void processCashOverpaymentGivesChangeOnTheRoundedRemainder() {
        state.cashRoundingStepCents = 5;
        state.ticket.totalAmount = new BigDecimal("24.62");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("25.40").compareTo(state.payment.lastChangeAmount));
        assertEquals(0, new BigDecimal("24.62").compareTo(state.payment.paidAmount));
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
        // LC-07-16-01: a partial settlement waits for the end of the sale.
        verify(hardwareService, never()).openDrawer();
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
    // cash changer (LC-07-15-01 to -04, LC-13-01-01)
    // --------------------------------------------------

    /**
     * Turns the cash changer on for a sale of the given total, draft 3.
     *
     * @param total the sale total
     */
    private void cashChangerSale(String total) {
        when(posSettingsService.hardwareEnabled("cashchanger")).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal(total);
        state.payment.ticketDbId = 3L;
    }

    /**
     * Starts a deposit and captures the three callbacks handed to the cash
     * changer.
     *
     * @return the progress, outcome and failure callbacks, in that order
     */
    @SuppressWarnings("unchecked")
    private Object[] startDeposit() {
        service.processCashChanger(state);
        ArgumentCaptor<java.util.function.Consumer<BigDecimal>> progress =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        ArgumentCaptor<java.util.function.Consumer<com.intermarche.pos.ui.hardware.CashChangerService.Deposit>> done =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        ArgumentCaptor<java.util.function.Consumer<String>> error =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(cashChangerService).deposit(any(), progress.capture(), done.capture(), error.capture());
        return new Object[] {progress.getValue(), done.getValue(), error.getValue()};
    }

    /**
     * LC-07-15-01: a till without a cash changer refuses the key and starts
     * nothing.
     */
    @Test
    void processCashChangerRefusedWithoutACashChanger() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processCashChanger(state);
        assertEquals("MONNAYEUR NON ACTIVÉ SUR CETTE CAISSE", state.ticket.transientError);
        verifyNoInteractions(cashChangerService);
    }

    /**
     * LC-07-15-01: a deposit already running, or nothing left to pay, starts
     * nothing.
     */
    @Test
    void processCashChangerIgnoredWhileRunningOrWithNothingDue() {
        cashChangerSale("10.00");
        state.payment.cashChangerDue = BigDecimal.ONE;
        service.processCashChanger(state);
        state.payment.cashChangerDue = null;
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCashChanger(state);
        verifyNoInteractions(cashChangerService);
    }

    /**
     * LC-07-15-01: an administered cash rule that refuses is said BEFORE the
     * machine takes any money.
     */
    @Test
    void processCashChangerOpposesTheCashRulesFirst() {
        cashChangerSale("40.00");
        when(tenderRulesService.check(eq("CASH"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "MONTANT SUPERIEUR AU PLAFOND (25,00 E)"));
        service.processCashChanger(state);
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
        assertNull(state.payment.cashChangerDue);
        verifyNoInteractions(cashChangerService);
    }

    /**
     * LC-07-15-01/02: the machine is asked for the remaining due, rounded where
     * the shop rounds; each insertion is shown on the screens and the line
     * display; the outcome and the failure are only deposited for the next
     * request; an insertion reported after the deposit ended shows nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void processCashChangerStartsTheDepositAndShowsTheInsertions() {
        cashChangerSale("24.62");
        state.cashRoundingStepCents = 5;
        Object[] callbacks = startDeposit();
        verify(cashChangerService).deposit(eq(new BigDecimal("24.60")), any(), any(), any());
        assertEquals(new BigDecimal("24.60"), state.payment.cashChangerDue);
        assertEquals("24,60", state.payment.getCashChangerDueFormatted());
        assertEquals("0,00", state.payment.getCashChangerInsertedFormatted());
        verify(hardwareService).displayMessage("MONNAYEUR 24,60 E");
        ((java.util.function.Consumer<BigDecimal>) callbacks[0]).accept(new BigDecimal("20.00"));
        assertEquals("20,00", state.payment.getCashChangerInsertedFormatted());
        verify(hardwareService).displayMessage("INSERE    20,00 E");
        com.intermarche.pos.ui.hardware.CashChangerService.Deposit outcome =
                new com.intermarche.pos.ui.hardware.CashChangerService.Deposit(
                        new BigDecimal("25.00"), new BigDecimal("0.40"));
        ((java.util.function.Consumer<com.intermarche.pos.ui.hardware.CashChangerService.Deposit>) callbacks[1])
                .accept(outcome);
        assertEquals(outcome, state.payment.cashChangerOutcome);
        ((java.util.function.Consumer<String>) callbacks[2]).accept("JAM");
        assertEquals("JAM", state.payment.cashChangerFailure);
        assertTrue(state.payment.payments.isEmpty());
        state.payment.cashChangerDue = null;
        ((java.util.function.Consumer<BigDecimal>) callbacks[0]).accept(new BigDecimal("24.00"));
        assertEquals("20,00", state.payment.getCashChangerInsertedFormatted());
    }

    /**
     * LC-07-15-03: the deposit the machine closed is registered as CASH on the
     * next request, rounding first; the change it gave back is shown, the
     * drawer stays shut and the sale completes.
     */
    @Test
    void settlePendingCashChangerRegistersTheCashAndTheChange() {
        cashChangerSale("24.62");
        state.cashRoundingStepCents = 5;
        startDeposit();
        state.payment.cashChangerOutcome = new com.intermarche.pos.ui.hardware.CashChangerService.Deposit(
                new BigDecimal("25.00"), new BigDecimal("0.40"));
        service.settlePendingCashChanger(state);
        assertNull(state.payment.cashChangerDue);
        assertNull(state.payment.cashChangerOutcome);
        assertEquals("", state.payment.getCashChangerInsertedFormatted());
        PaymentState.PaymentEntry cash = state.payment.payments.get(1);
        assertEquals("ARRONDI", state.payment.payments.get(0).method);
        assertEquals("CASH", cash.method);
        assertEquals(new BigDecimal("24.60"), cash.amount);
        assertEquals(new BigDecimal("25.00"), cash.tenderedAmount);
        assertTrue(cash.cashChanger);
        assertEquals(new BigDecimal("0.40"), state.payment.lastChangeAmount);
        assertTrue(state.payment.transactionComplete);
        verify(ticketPersistenceService).addPaymentToTicket(3L, cash);
        verify(hardwareService).displayMessage("DONNE 25,00 RENDU 0,40");
        service.openSettlementDrawer(state, false);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * LC-07-15-01: a deposit closed on what was inserted settles that part as
     * cash, without change, and leaves the rest due.
     */
    @Test
    void settlePendingCashChangerRegistersAPartialDeposit() {
        cashChangerSale("10.00");
        startDeposit();
        state.payment.cashChangerOutcome = new com.intermarche.pos.ui.hardware.CashChangerService.Deposit(
                new BigDecimal("5.00"), BigDecimal.ZERO);
        service.settlePendingCashChanger(state);
        assertEquals(new BigDecimal("5.00"), state.payment.payments.get(0).amount);
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        assertFalse(state.payment.transactionComplete);
        verify(hardwareService).displayMessage("ESPECES   5,00 E");
    }

    /**
     * A deposit given back whole settles nothing and says so.
     */
    @Test
    void settlePendingCashChangerReportsAMoneyGivenBack() {
        cashChangerSale("10.00");
        startDeposit();
        state.payment.cashChangerOutcome = new com.intermarche.pos.ui.hardware.CashChangerService.Deposit(
                new BigDecimal("5.00"), new BigDecimal("5.00"));
        service.settlePendingCashChanger(state);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.cashChangerDue);
        assertEquals("MONNAYEUR : MONTANT RENDU AU CLIENT", state.ticket.transientError);
    }

    /**
     * A failed deposit ends the wait and says why; with nothing deposited, the
     * poll changes nothing.
     */
    @Test
    void settlePendingCashChangerReportsTheFailure() {
        cashChangerSale("10.00");
        service.settlePendingCashChanger(state);
        assertNull(state.ticket.transientError);
        startDeposit();
        state.payment.cashChangerFailure = "MONNAYEUR INJOIGNABLE";
        service.settlePendingCashChanger(state);
        assertNull(state.payment.cashChangerDue);
        assertNull(state.payment.cashChangerFailure);
        assertEquals("MONNAYEUR INJOIGNABLE", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * In training no machine is touched: the amount due is settled as if it
     * had been inserted exactly, and nothing is persisted.
     */
    @Test
    void processCashChangerInTrainingSettlesWithoutTheMachine() {
        cashChangerSale("10.00");
        state.trainingMode = true;
        service.processCashChanger(state);
        assertEquals(new BigDecimal("10.00"), state.payment.payments.get(0).amount);
        assertTrue(state.payment.payments.get(0).cashChanger);
        assertTrue(state.payment.transactionComplete);
        verifyNoInteractions(cashChangerService, ticketPersistenceService);
    }

    /**
     * Closing and cancelling reach the machine only while a deposit runs.
     */
    @Test
    void finishAndCancelReachTheMachineOnlyWhileADepositRuns() {
        service.finishCashChanger(state);
        service.cancelCashChanger(state);
        verifyNoInteractions(cashChangerService);
        state.payment.cashChangerDue = BigDecimal.ONE;
        service.finishCashChanger(state);
        service.cancelCashChanger(state);
        verify(cashChangerService).fix();
        verify(cashChangerService).cancel();
    }

    /**
     * LC-07-15-04 and LC-13-01-01: closing a sale transfers the cash the
     * machine took to the safe, then says what the machine flags.
     */
    @Test
    void finalizeTransactionTransfersTheCashChangerCashAndSaysTheLevels() {
        state.payment.ticketDbId = 9L;
        state.auth.operatorId = 4L;
        state.payment.addCashPayment(new BigDecimal("12.00"), new BigDecimal("12.00"));
        state.payment.payments.get(0).cashChanger = true;
        state.payment.addCashPayment(new BigDecimal("3.00"), new BigDecimal("3.00"));
        com.intermarche.pos.domain.session.CashSession session = mock(com.intermarche.pos.domain.session.CashSession.class);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        com.intermarche.pos.domain.people.Employee cashier = mock(com.intermarche.pos.domain.people.Employee.class);
        when(cashChangerService.levelAlert()).thenReturn("MONNAYEUR : PRESQUE VIDE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.people.Employee.findById(4L)).thenReturn(cashier);
            service.finalizeTransaction(state);
        }
        verify(service.cashMovementService).recordCashChangerTransfer(session, cashier, new BigDecimal("12.00"));
        assertEquals("MONNAYEUR : PRESQUE VIDE", state.ticket.transientError);
        assertTrue(state.ticket.transientWarn);
    }

    /**
     * LC-07-15-04: a sale without the machine transfers nothing; a sale on the
     * machine without an operator transfers with no cashier.
     */
    @Test
    void finalizeTransactionTransfersOnlyTheCashChangerCash() {
        state.payment.ticketDbId = 9L;
        state.payment.addCashPayment(new BigDecimal("3.00"), new BigDecimal("3.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.cashMovementService, never()).recordCashChangerTransfer(any(), any(), any());
        state.payment.ticketDbId = 10L;
        state.payment.addCashPayment(new BigDecimal("5.00"), new BigDecimal("5.00"));
        state.payment.payments.get(0).cashChanger = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 10L);
            service.finalizeTransaction(state);
        }
        verify(service.cashMovementService).recordCashChangerTransfer(null, null, new BigDecimal("5.00"));
    }

    /**
     * LC-08-01-05: the closing sale is stated on its Portuguese documents before
     * it is frozen as printed.
     */
    @Test
    void finalizeTransactionStatesTheSaleOnItsPortugueseDocuments() {
        state.payment.ticketDbId = 9L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.portugueseFiscalService).issueSaleDocuments(9L);
    }

    // --------------------------------------------------
    // processSocialCard (LC-08-01-06)
    // --------------------------------------------------

    /**
     * Rings a 30 euro sale whose first 8 euros are eligible to the social card.
     */
    private void socialCardSale() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        com.intermarche.pos.ui.ticket.TicketState.TicketItem eligible =
                new com.intermarche.pos.ui.ticket.TicketState.TicketItem("1", null, "A",
                        new BigDecimal("8.00"), BigDecimal.ONE, new BigDecimal("0.06"));
        eligible.restrictedTenders = com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE;
        state.ticket.items.add(eligible);
        state.ticket.items.add(new com.intermarche.pos.ui.ticket.TicketState.TicketItem("2", null, "B",
                new BigDecimal("22.00"), BigDecimal.ONE, new BigDecimal("0.23")));
    }

    /**
     * LC-08-01-06: a sale with nothing eligible left is refused the social card,
     * on the display and on the screen.
     */
    @Test
    void processSocialCardRefusesWithoutEligibleLines() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.ticket.items.add(new com.intermarche.pos.ui.ticket.TicketState.TicketItem("2", null, "B",
                new BigDecimal("10.00"), BigDecimal.ONE, new BigDecimal("0.23")));
        service.processSocialCard(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("CAS: AUCUN ARTICLE ELIGIBLE");
        assertEquals("CAS: AUCUN ARTICLE ELIGIBLE", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * LC-08-01-06: no amount typed pays what the card may — the eligible lines —
     * and a second card finds the eligible base already paid.
     */
    @Test
    void processSocialCardPaysTheEligibleBaseOnce() {
        socialCardSale();
        state.payment.addCashPayment(new BigDecimal("1.00"), new BigDecimal("1.00"));
        service.processSocialCard(state, null);
        assertEquals("CAS", state.payment.payments.get(1).method);
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.payments.get(1).amount));
        service.processSocialCard(state, BigDecimal.ZERO);
        verify(hardwareService).displayMessage("CAS: AUCUN ARTICLE ELIGIBLE");
        assertEquals(2, state.payment.payments.size());
    }

    /**
     * LC-08-01-06: an amount beyond the eligible base is capped to it; one within
     * it is paid as typed.
     */
    @Test
    void processSocialCardCapsToTheEligibleBase() {
        socialCardSale();
        service.processSocialCard(state, new BigDecimal("3.00"));
        assertEquals(0, new BigDecimal("3.00").compareTo(state.payment.payments.get(0).amount));
        service.processSocialCard(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("CAS PLAFONNE  5,00 E");
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.payments.get(1).amount));
    }

    /**
     * LC-08-01-06: a negative amount reads as no amount: the card pays what it may.
     */
    @Test
    void processSocialCardReadsANegativeAmountAsNone() {
        socialCardSale();
        service.processSocialCard(state, new BigDecimal("-1"));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.payments.get(0).amount));
    }

    /**
     * LC-08-01-06: the card never pays beyond what remains due.
     */
    @Test
    void processSocialCardNeverPaysBeyondTheRemainingDue() {
        socialCardSale();
        state.payment.addCashPayment(new BigDecimal("25.00"), new BigDecimal("25.00"));
        service.processSocialCard(state, null);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.payments.get(1).amount));
    }

    // --------------------------------------------------
    // card cashback (LC-07-04-07/08)
    // --------------------------------------------------

    /**
     * Turns the cashback on with the given ceiling, for a 50 euro sale.
     *
     * @param ceiling the administered ceiling, "0" for none
     */
    private void cashbackStore(String ceiling) {
        when(posSettingsService.cashbackEnabled()).thenReturn(true);
        when(posSettingsService.cashbackMax()).thenReturn(new BigDecimal(ceiling));
        state.ticket.totalAmount = new BigDecimal("50.00");
        state.payment.ticketDbId = 3L;
    }

    /**
     * LC-07-04-07: with cashback offered, a card amount above the remaining
     * due is debited whole, within the ceiling.
     */
    @Test
    void processCardAboveRemainingDebitsTheWholeAmountWhenCashbackIsOffered() {
        cashbackStore("60.00");
        service.processCard(state, new BigDecimal("70"));
        assertEquals(new BigDecimal("70.00"), state.payment.pendingCardAmount);
        verify(terminal).requestDebit(eq(new BigDecimal("70.00")), any());
        assertNull(state.ticket.transientError);
    }

    /**
     * LC-07-04-07: a cashback above the ceiling is refused before the
     * terminal is asked anything.
     */
    @Test
    void processCardRefusesACashbackAboveTheCeiling() {
        cashbackStore("10.00");
        service.processCard(state, new BigDecimal("70"));
        assertEquals("CASHBACK REFUSÉ : PLAFOND 10,00 €", state.ticket.transientError);
        assertNull(state.payment.pendingCardAmount);
        verifyNoInteractions(terminal);
    }

    /**
     * LC-07-04-07: a zero ceiling bounds nothing.
     */
    @Test
    void processCardZeroCeilingBoundsNoCashback() {
        cashbackStore("0");
        service.processCard(state, new BigDecimal("250"));
        verify(terminal).requestDebit(eq(new BigDecimal("250.00")), any());
    }

    /**
     * LC-07-04-07: a store that does not offer cashback keeps capping the
     * card at the remaining due.
     */
    @Test
    void processCardAboveRemainingIsCappedWithoutCashback() {
        state.ticket.totalAmount = new BigDecimal("50.00");
        service.processCard(state, new BigDecimal("70"));
        verify(terminal).requestDebit(eq(new BigDecimal("50.00")), any());
        verify(posSettingsService, never()).cashbackMax();
    }

    /**
     * LC-07-04-07: the combined meal-voucher debit never carries a cashback,
     * even where the store offers one.
     */
    @Test
    void processCardCombinedNeverCarriesACashback() {
        cashbackStore("60.00");
        service.processCard(state, new BigDecimal("70"), true);
        verify(posSettingsService, never()).cashbackMax();
    }

    /**
     * LC-07-04-07/08: once the bank accepts, the card keeps the whole debit
     * and the difference is given back as a negative cash settlement, shown
     * as change; the sale is complete.
     */
    @Test
    void acceptedCashbackGivesTheDifferenceBackInCash() {
        cashbackStore("60.00");
        service.processCard(state, new BigDecimal("70"));
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), captor.capture());
        captor.getValue().onAccepted(TerminalOutcome.ofAmount(new BigDecimal("70.00")));
        assertEquals(2, state.payment.payments.size());
        PaymentState.PaymentEntry card = state.payment.payments.get(0);
        PaymentState.PaymentEntry cash = state.payment.payments.get(1);
        assertEquals("CARD", card.method);
        assertEquals(new BigDecimal("70.00"), card.amount);
        assertFalse(card.cashback);
        assertEquals("CASH", cash.method);
        assertEquals(new BigDecimal("-20.00"), cash.amount);
        assertNull(cash.tenderedAmount);
        assertTrue(cash.cashback);
        assertEquals(new BigDecimal("20.00"), state.payment.lastChangeAmount);
        assertEquals(0, state.getRemaining().signum());
        assertTrue(state.payment.transactionComplete);
        verify(hardwareService).displayMessage("CASHBACK 20,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, card);
        verify(ticketPersistenceService).addPaymentToTicket(3L, cash);
    }

    /**
     * LC-07-04-08: the cashier keys the cash wanted, the register debits the
     * card for the remaining due plus that amount.
     */
    @Test
    void processCardCashbackDebitsRemainingPlusTheCashWanted() {
        cashbackStore("60.00");
        service.processCardCashback(state, new BigDecimal("20.00"));
        verify(terminal).requestDebit(eq(new BigDecimal("70.00")), any());
    }

    /**
     * LC-07-04-08: the CASHBACK key of a store that does not offer it says so
     * and debits nothing.
     */
    @Test
    void processCardCashbackRefusedWhereNotOffered() {
        state.ticket.totalAmount = new BigDecimal("50.00");
        service.processCardCashback(state, new BigDecimal("20.00"));
        assertEquals("CASHBACK NON PROPOSÉ DANS CE MAGASIN", state.ticket.transientError);
        verifyNoInteractions(terminal);
    }

    /**
     * LC-07-04-08: no amount, a zero amount, or nothing left to pay debits
     * nothing.
     */
    @Test
    void processCardCashbackIgnoresEmptyAmountsAndSettledSales() {
        cashbackStore("60.00");
        service.processCardCashback(state, null);
        service.processCardCashback(state, BigDecimal.ZERO);
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCardCashback(state, new BigDecimal("20.00"));
        verifyNoInteractions(terminal);
        assertNull(state.ticket.transientError);
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
     * The accept leg carries the terminal traces (authorization number and
     * degraded-mode indicator) from the outcome onto the registered card entry,
     * which is then persisted (BO-04-01-08/47/49).
     */
    @Test
    void acceptLegCarriesTerminalTraces() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.authorizationNumber = "654321";
        outcome.degradedMode = true;
        cb.onAccepted(outcome);
        PaymentState.PaymentEntry entry = state.payment.payments.get(0);
        assertEquals("654321", entry.authorizationNumber);
        org.junit.jupiter.api.Assertions.assertTrue(entry.degradedMode);
        assertNull(state.payment.pendingCardAuthNumber);
        assertFalse(state.payment.pendingCardDegraded);
        verify(ticketPersistenceService).addPaymentToTicket(3L, entry);
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
     * BO-02-03-06: where the shop admits the meal voucher on eligible articles
     * only, a ticket carrying none refuses it in local mode.
     */
    @Test
    void processTicketRestoRefusedWithoutEligibleArticleWhenTheShopRequiresOne() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "LOCAL";
        when(posSettingsService.mealVoucherEligibleOnly()).thenReturn(true);
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTicketResto} under an engine cap with a threshold allows
     * up to {@code min(base, threshold)} and registers a fitting request as-is,
     * leaving the engine base untouched — it is a basket hint, not a counter
     * ({@code ENGINE} true, {@code mealEligible != null} true,
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
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.valuationMealEligible));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        // LC-07-16-01: a partial settlement waits for the end of the sale.
        verify(hardwareService, never()).openDrawer();
        service.processCash(state, new BigDecimal("15.00"));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * The meal-voucher cap applies to the BASKET: a second meal ticket is
     * capped at the base minus the one already registered, and a third is
     * refused once the allowance is spent — even though the engine base field
     * itself is rewritten by every revaluation and never decremented. This is
     * the regression test for the double 2,67 € payment observed on demo.
     */
    @Test
    void processTicketRestoBasketCapSpansPayments() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        service.processTicketResto(state, new BigDecimal("5"));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR PLAFONNE  3,00 E");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TR: AUCUN ARTICLE ELIGIBLE");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
        assertEquals(2, state.payment.payments.size());
    }

    /**
     * {@code processTicketResto} caps a request above the allowed base and
     * announces the ceiling, leaving the engine base field untouched
     * ({@code mealThreshold != null} false so the base itself is the ceiling,
     * {@code amount > allowed} true).
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
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.valuationMealEligible));
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

    // --------------------------------------------------
    // processTrdCard (EXP-60)
    // --------------------------------------------------

    /**
     * {@code processTrdCard} returns on a non-positive defaulted amount
     * (amount null → remaining, second guard true).
     */
    @Test
    void processTrdCardZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processTrdCard(state, null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTrdCard} refuses when the meal-voucher base is exhausted
     * (allowance signum &lt;= 0 arm).
     */
    @Test
    void processTrdCardBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processTrdCard(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TRD: AUCUN ARTICLE ELIGIBLE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processTrdCard} caps a request above the eligible base and settles
     * the ceiling (amount &gt; allowance arm; no administered cap).
     */
    @Test
    void processTrdCardCappedByBase() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(BigDecimal.ZERO);
        service.processTrdCard(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TRD PLAFONNE  8,00 E");
        assertEquals(0, new BigDecimal("8").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processTrdCard} caps at the administered plafond when it bites below
     * the eligible base (cap &gt; 0 and amount &gt; cap arm).
     */
    @Test
    void processTrdCardCappedByCap() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(new BigDecimal("10"));
        service.processTrdCard(state, new BigDecimal("20"));
        verify(hardwareService).displayMessage("TRD PLAFONNE  10,00 E");
        assertEquals(0, new BigDecimal("10").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processTrdCard} settles a fitting amount within the base with no
     * plafond (the handlePaymentWithChange settle path).
     */
    @Test
    void processTrdCardCompletesWithinBaseAndCap() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(posSettingsService.trdCardCap()).thenReturn(BigDecimal.ZERO);
        service.processTrdCard(state, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processRestoFlash (EXP-67)
    // --------------------------------------------------

    /**
     * {@code processRestoFlash} returns on a non-positive defaulted amount.
     */
    @Test
    void processRestoFlashZeroReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processRestoFlash(state, "TOK", null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} refuses when the meal-voucher base is exhausted.
     */
    @Test
    void processRestoFlashBaseExhausted() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.ZERO;
        state.payment.valuationMealThreshold = null;
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        verify(hardwareService).displayMessage("RESTO FLASH: AUCUN ARTICLE ELIGIBLE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} settles nothing when the service refuses the
     * token (confirm false arm).
     */
    @Test
    void processRestoFlashRefusedByService() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(restoFlashClient.confirm(eq("TOK"), any())).thenReturn(false);
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        verify(hardwareService).displayMessage("RESTO FLASH REFUSE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processRestoFlash} settles when the service confirms the token
     * (confirm true arm, the settle path).
     */
    @Test
    void processRestoFlashConfirmedSettles() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("1000");
        state.payment.valuationMealThreshold = null;
        when(restoFlashClient.confirm(eq("TOK"), any())).thenReturn(true);
        service.processRestoFlash(state, "TOK", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processSupplierGiftCard (EXP-66)
    // --------------------------------------------------

    /**
     * {@code processSupplierGiftCard} refuses an invalid card (verdict.valid
     * false arm).
     */
    @Test
    void processSupplierGiftCardRefusedWhenInvalid() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(false, BigDecimal.ZERO));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        verify(hardwareService).displayMessage("CARTE CADEAU REFUSEE");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSupplierGiftCard} refuses a valid card with a nil balance
     * (balance null/&lt;=0 arm).
     */
    @Test
    void processSupplierGiftCardRefusedWhenBalanceNil() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, BigDecimal.ZERO));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        verify(hardwareService).displayMessage("CARTE CADEAU: SOLDE NUL");
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSupplierGiftCard} caps the payment at the issuer balance
     * (amount &gt; balance arm) and settles it.
     */
    @Test
    void processSupplierGiftCardCappedAtBalance() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("5")));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("20"));
        verify(hardwareService).displayMessage("CARTE CADEAU  5,00 E");
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    /**
     * {@code processSupplierGiftCard} settles a fitting amount below the balance
     * (amount &lt;= balance arm, the settle path).
     */
    @Test
    void processSupplierGiftCardSettlesWithinBalance() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(giftCardIssuerClient.query("GC1"))
                .thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("100")));
        service.processSupplierGiftCard(state, "GC1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // processSogec (EXP-58)
    // --------------------------------------------------

    /**
     * {@code processSogec} with NO value at all — nothing decoded from the bon
     * (null arm of the face-value fallback) and nothing keyed (null arm of the
     * refusal guard) — settles nothing and says so. This is the regression the
     * recette found: the register used to read "no amount" as "the whole ticket".
     */
    @Test
    void processSogecWithoutAnyValueSettlesNothing() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", null);
        verify(hardwareService).displayMessage("BON SOGEC: VALEUR REQUISE");
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSogec} with a keyed value of zero (non-positive arm of the
     * refusal guard, the null arm being covered above) also settles nothing: a bon
     * is worth what is printed on it, and zero is not a value.
     */
    @Test
    void processSogecWithAZeroValueSettlesNothing() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", BigDecimal.ZERO);
        verify(hardwareService).displayMessage("BON SOGEC: VALEUR REQUISE");
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processSogec} settles the KEYED value when the bon's barcode carries
     * none (null arm of the decoded value) and records the compensation trace for
     * the supplier reimbursement.
     */
    @Test
    void processSogecSettlesAndRecordsCompensation() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(state.payment.paidAmount));
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("5"));
    }

    /**
     * A bon whose own barcode carries its face value (non-null arm of the decoded
     * value) settles THAT value, whatever the cashier keyed: the paper wins.
     */
    @Test
    void processSogecPrefersTheValueEncodedOnTheBon() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        when(sogecCompensationService.faceValueOf("SG1")).thenReturn(new BigDecimal("10.00"));
        service.processSogec(state, "SG1", new BigDecimal("5"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("10.00"));
    }

    /**
     * A 20 € bon on a 56,50 € ticket settles TWENTY EUROS and leaves 36,50 € to
     * pay — the exact case the recette reported coming out paid in full by SOGEC.
     * The cap arm is not taken, so nothing is announced.
     */
    @Test
    void processSogecSettlesOnlyItsFaceValueOnALargerTicket() {
        state.ticket.totalAmount = new BigDecimal("56.50");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
        assertEquals(0, new BigDecimal("36.50").compareTo(state.getRemaining()));
        verify(hardwareService, never()).displayMessage(startsWith("BON SOGEC PLAFONNE"));
    }

    /**
     * A bon worth MORE than what is left to pay is capped at the remainder (cap arm
     * true), the cap is announced, and the compensation trace carries the capped
     * amount: the register hands no change back against a manufacturer's coupon.
     */
    @Test
    void processSogecCapsABonLargerThanTheRemainder() {
        state.ticket.totalAmount = new BigDecimal("8.00");
        state.payment.ticketDbId = 3L;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("BON SOGEC PLAFONNE  8,00 E");
        verify(sogecCompensationService).recordCompensation("SG1", new BigDecimal("8.00"));
    }

    /**
     * A bon presented on an already settled ticket (non-positive remainder) settles
     * nothing and records nothing — and says nothing either, the cashier having made
     * no mistake worth a message.
     */
    @Test
    void processSogecOnASettledTicketDoesNothing() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processSogec(state, "SG1", new BigDecimal("20.00"));
        verifyNoInteractions(hardwareService);
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
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
        // LC-07-16-01: a partial settlement waits for the end of the sale.
        verify(hardwareService, never()).openDrawer();
        service.processCash(state, new BigDecimal("15.00"));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * The basket total behind the engine cap counts ONLY meal tickets: a
     * non-meal payment already on the ticket (here a card part-payment) is
     * skipped, so the full eligible base is still available to this meal ticket
     * ({@code "TR".equals(entry.method)} false arm of the summation loop).
     */
    @Test
    void processTicketRestoEngineBasketTotalSkipsNonMealPayments() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8");
        state.payment.valuationMealThreshold = null;
        state.payment.addPayment("CARD", new BigDecimal("10.00"));
        service.processTicketResto(state, new BigDecimal("5"));
        verify(hardwareService).displayMessage("TICKET    5,00 E");
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
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
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
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
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        verify(hardwareService).displayMessage("CHEQUE    20,00 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * The endorsement leaves WITH the reading: the reader prints on the cheque while
     * it still holds it, so the text cannot be sent afterwards. Its two lines are the
     * date and the amount the register knows and the paper does not.
     */
    @Test
    void processChequeSendsTheEndorsementWithTheReading() {
        chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        ArgumentCaptor<String> endorsement = ArgumentCaptor.forClass(String.class);
        service.processCheque(state, BigDecimal.ZERO);
        verify(chequeReadingService).read(endorsement.capture(), any(), any());
        String[] lines = endorsement.getValue().split("\n");
        assertEquals(2, lines.length);
        assertEquals("20,00 EUR", lines[1]);
    }

    /**
     * A cheque the reader refuses settles NOTHING: the pending amount is dropped, the
     * cashier is told why and the ticket keeps its due.
     */
    @Test
    void processChequeRefusedSettlesNothing() {
        chequeReaderRefuses("AUCUNE DONNEE MAGNETIQUE");
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.pendingChequeAmount);
        assertFalse(state.payment.transactionComplete);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A reading that lands AFTER the cashier cancelled is ignored ({@code
     * pendingChequeAmount == null} true): the cheque was given up on, and a payment
     * appearing by itself on a ticket the cashier moved on from is worse than a
     * cheque re-presented.
     */
    @Test
    void processChequeReadAfterCancellationIsIgnored() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onRead =
                    (java.util.function.Consumer<String>) invocation.getArgument(1);
            service.cancelPendingCheque(state);
            onRead.accept("a0007639 a800000000909r 000000000000i");
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * A refusal that lands after the cashier cancelled changes nothing either
     * ({@code pendingChequeAmount == null} true in the drop path).
     */
    @Test
    void processChequeRefusedAfterCancellationIsIgnored() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onError =
                    (java.util.function.Consumer<String>) invocation.getArgument(2);
            service.cancelPendingCheque(state);
            onError.accept("BOURRAGE");
            return null;
        }).when(chequeReadingService).read(any(), any(), any());
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCheque(state, BigDecimal.ZERO);
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.pendingChequeAmount);
    }

    /**
     * {@code cancelPendingCheque} on a register expecting no cheque is a no-op
     * ({@code pendingChequeAmount == null} true, the guard's other arm).
     */
    @Test
    void cancelPendingChequeWithoutAPendingChequeDoesNothing() {
        long version = state.version;
        service.cancelPendingCheque(state);
        assertEquals(version, state.version);
    }

    // --------------------------------------------------
    // processFidelity
    // --------------------------------------------------

    /** LC-07-10-05: a granted reservation prints a slip and does not yet pay. */
    @Test
    void loyaltyWaitsForHandwrittenSignature() {
        state.ticket.totalAmount = BigDecimal.TEN;
        FidelityService.BurnVerdict granted = grant("4");
        when(fidelityService.reserveLease(eq(state), any(), any())).thenReturn(granted);
        service.processFidelity(state, new BigDecimal("4"));
        assertEquals(new BigDecimal("4"), state.payment.pendingFidelitySignatureAmount);
        assertTrue(state.payment.payments.isEmpty());
        verify(ticketPrinterService).printFidelitySignatureSlip(new BigDecimal("4"), state.fidelity.label);
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
    }

    /** LC-07-10-05: a duplicate request cannot replace the unsigned amount. */
    @Test
    void loyaltyDoesNotReplaceUnsignedRequest() {
        state.ticket.totalAmount = BigDecimal.TEN;
        state.payment.pendingFidelitySignatureAmount = BigDecimal.ONE;
        service.processFidelity(state, BigDecimal.TEN);
        assertEquals(BigDecimal.ONE, state.payment.pendingFidelitySignatureAmount);
        verifyNoInteractions(fidelityService, ticketPrinterService);
    }

    /** LC-07-10-05: refusing to sign releases the lease without a settlement. */
    @Test
    void loyaltySignatureRefusalReleasesReservation() {
        state.payment.pendingFidelitySignatureAmount = BigDecimal.ONE;
        service.confirmFidelitySignature(state, false);
        assertNull(state.payment.pendingFidelitySignatureAmount);
        assertTrue(state.payment.payments.isEmpty());
        verify(fidelityService).releaseLease(state);
        verifyNoInteractions(hardwareService);
    }

    /** LC-07-10-05: an unsolicited or repeated confirmation cannot create money. */
    @Test
    void loyaltySignatureWithoutPendingAmountDoesNothing() {
        service.confirmFidelitySignature(state, true);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(fidelityService, hardwareService);
    }

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
        assertTrue(state.payment.payments.isEmpty());
        service.confirmFidelitySignature(state, true);
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
        assertTrue(state.payment.payments.isEmpty());
        service.confirmFidelitySignature(state, true);
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
    // applyPrintChoice (LC-08-03)
    // --------------------------------------------------

    /**
     * Makes the conditional-printing rule answer with the given decision.
     *
     * @param saleTicket whether the sale ticket is printed
     * @param cardReceipt whether the card receipt is printed
     * @param voucher whether the vouchers are printed
     */
    private void stubDecision(boolean saleTicket, boolean cardReceipt, boolean voucher) {
        when(printPolicy.decide(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.intermarche.pos.ui.hardware.PrintPolicy.Decision(
                        saleTicket, cardReceipt, voucher));
    }

    /**
     * Applies a printing choice with the Panache statics neutralized — the rule
     * reads the ticket back to look for a GLC article.
     *
     * @param choice the cashier's choice
     */
    private void applyChoice(com.intermarche.pos.ui.hardware.PrintChoice choice) {
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            service.applyPrintChoice(state, choice);
        }
    }

    /**
     * A decision naming the sale ticket prints it — and only it
     * (LC-08-03-03) — with the detail of its returns where it has one
     * (LC-05-07-02).
     */
    @Test
    void applyPrintChoicePrintsTheSaleTicket() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService).printReturnDetail(9L);
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        // BO-03-03-26/-27/-35: the loyalty zone is frozen before the receipt
        // is printed, ahead of the closing.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fidelityService, ticketPersistenceService,
                ticketPrinterService);
        order.verify(fidelityService).refreshPotBalances(state);
        order.verify(ticketPersistenceService).storeFidelityMessages(eq(9L), any());
        order.verify(ticketPrinterService).printTicket(9L);
        assertTrue(state.payment.printApplied);
    }

    /**
     * BO-03-03-36: where the store administers the exit ticket, it REPLACES the
     * sale receipt — the printer answers one, and the receipt is not printed.
     */
    @Test
    void applyPrintChoicePrintsTheExitTicketInsteadOfTheReceipt() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        org.mockito.Mockito.when(ticketPrinterService.printExitTicket(9L)).thenReturn(1);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printExitTicket(9L);
        verify(ticketPrinterService, never()).printTicket(any());
        assertTrue(state.payment.printApplied);
    }

    /**
     * And where it is not administered the printer answers zero, so the receipt
     * prints exactly as before — the other leg of the substitution.
     */
    @Test
    void applyPrintChoiceFallsBackToTheReceiptWithoutAnExitTicket() {
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        org.mockito.Mockito.when(ticketPrinterService.printExitTicket(9L)).thenReturn(0);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        verify(ticketPrinterService).printExitTicket(9L);
        verify(ticketPrinterService).printTicket(9L);
    }

    /**
     * LC-13-06-13: the exit ticket and the sale receipt are both laid out in the
     * language the customer chose on the customer display; the cashier's labels
     * stay French around them.
     */
    @Test
    void applyPrintChoicePrintsTheCustomersPaperInTheirLanguage() {
        service.i18n = TestMessages.french(state);
        state.customerLanguage = "nl";
        state.payment.ticketDbId = 9L;
        stubDecision(true, false, false);
        java.util.List<String> languages = new java.util.ArrayList<>();
        org.mockito.Mockito.when(ticketPrinterService.printExitTicket(9L)).thenAnswer(call -> {
            languages.add(service.i18n.getLang());
            return 0;
        });
        org.mockito.Mockito.doAnswer(call -> languages.add(service.i18n.getLang()))
                .when(ticketPrinterService).printTicket(9L);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.SALE_TICKET);
        assertEquals(java.util.List.of("nl", "nl"), languages);
        assertEquals("fr", service.i18n.getLang());
    }

    /**
     * LC-13-06-13: the sale receipt kept attached to the card frame is laid out
     * in the customer's language too — the other arm of the attachment policy.
     */
    @Test
    void theAttachedReceiptIsPrintedInTheCustomersLanguage() {
        service.i18n = TestMessages.french(state);
        state.customerLanguage = "pt";
        when(posSettingsService.cardReceiptAttached()).thenReturn(true);
        PaymentState.PaymentEntry card = new PaymentState.PaymentEntry("CARD", new BigDecimal("5.00"));
        card.customerReceiptFrame = "FRAME";
        state.payment.payments.add(card);
        java.util.List<String> languages = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> languages.add(service.i18n.getLang()))
                .when(ticketPrinterService).printTicket(9L, false);
        service.printTicketWithMoneticFrames(state, 9L);
        assertEquals(java.util.List.of("pt"), languages);
    }

    /**
     * A decision naming the card receipt prints it — and only it
     * (LC-08-03-04).
     */
    @Test
    void applyPrintChoicePrintsTheCardReceipt() {
        state.payment.ticketDbId = 9L;
        state.payment.cardSignatureRequired = true;
        stubDecision(false, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.CARD_RECEIPT);
        verify(ticketPrinterService).printCardReceipt(9L, true, null);
        verify(ticketPrinterService, never()).printTicket(any());
    }

    /**
     * A decision naming nothing prints nothing, and still records the choice as
     * applied so the closing step adds none (LC-08-03-06).
     */
    @Test
    void applyPrintChoicePrintsNothingButRecordsTheChoice() {
        state.payment.ticketDbId = 9L;
        stubDecision(false, false, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.NONE);
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printReturnDetail(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        assertTrue(state.payment.printApplied);
        assertEquals(com.intermarche.pos.ui.hardware.PrintChoice.NONE, state.payment.printChoice);
    }

    /**
     * A second application prints nothing: a reloaded modal never produces a
     * second original ({@code printApplied} true arm).
     */
    @Test
    void applyPrintChoiceIgnoresASecondCall() {
        state.payment.ticketDbId = 9L;
        state.payment.printApplied = true;
        stubDecision(true, true, true);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService, never()).printTicket(any());
    }

    /**
     * Without a draft there is nothing to print ({@code ticketDbId == null}
     * arm).
     */
    @Test
    void applyPrintChoiceWithoutDraftPrintsNothing() {
        state.payment.ticketDbId = null;
        stubDecision(true, true, true);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService, never()).printTicket(any());
        assertFalse(state.payment.printApplied);
    }

    /**
     * The refuse leg prints the not-completed-transaction slip when the back
     * office forces it, with the terminal's own frame (LC-08-03-12).
     */
    @Test
    void refuseLegPrintsTheTnaReceiptWhenForced() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(true);
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.tnaFrame = "TRAME TNA";
        cb.onRefused(outcome);
        verify(ticketPrinterService).printCardTnaReceipt(new BigDecimal("15.00"), "TRAME TNA");
    }

    /**
     * The refuse leg prints no slip when the rule is off — the historical
     * behaviour.
     */
    @Test
    void refuseLegPrintsNoTnaReceiptWhenNotForced() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(false);
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        verify(ticketPrinterService, never()).printCardTnaReceipt(any(), any());
    }

    /**
     * The refuse leg prints no not-completed slip when the terminal supplied NO
     * outcome, even with the rule forced: there is no frame to print
     * ({@code outcome != null} false arm short-circuits the {@code &&}). The
     * pending amount is still dropped with the standard refusal message.
     */
    @Test
    void refuseLegWithNullOutcomePrintsNoTnaReceipt() {
        when(printPolicy.isTnaReceiptForced()).thenReturn(true);
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onRefused(null);
        verify(ticketPrinterService, never()).printCardTnaReceipt(any(), any());
        assertNull(state.payment.pendingCardAmount);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
    }

    /**
     * The accept leg raises the signature flag the printing rule reads
     * (LC-08-03-11).
     */
    @Test
    void acceptLegRecordsTheSignatureRequirement() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.signatureRequired = true;
        cb.onAccepted(outcome);
        assertTrue(state.payment.cardSignatureRequired);
    }

    /**
     * An accepted transaction asking for no signature leaves the flag down.
     */
    @Test
    void acceptLegLeavesTheSignatureFlagDown() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertFalse(state.payment.cardSignatureRequired);
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
        state.fidelity.holderEmail = "client@example.test";
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
        assertEquals("client@example.test", state.lastClosedTicketEmail);
        assertNull(state.fidelity.holderEmail);
        verify(hardwareService).displayMessage("MERCI A BIENTOT");
        assertNull(state.payment.ticketDbId);
    }

    /**
     * LC-06-03-07: the closing of a sale that settles a recalled order TELLS
     * the store node, after the fiscal moment and tolerantly — the sale is
     * closed whatever the node answers, and the order cannot be recalled and
     * paid twice. A sale with no recalled order tells nothing (both arms of
     * the settle guard).
     */
    @Test
    void finalizeTransactionSettlesTheRecalledOrder() {
        com.intermarche.pos.ui.recall.BackOfficeDocumentClient documentClient =
                mock(com.intermarche.pos.ui.recall.BackOfficeDocumentClient.class);
        service.documentClient = documentClient;
        state.payment.ticketDbId = 9L;
        state.recalledOrderCode = "C04-CMD000001";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(documentClient).settleOrder("C04-CMD000001");
        assertNull(state.recalledOrderCode);
    }

    /**
     * LC-06-02-12: the closing of a sale loaded from several documents settles
     * each of them once, the remembered order included, and clears the list.
     */
    @Test
    void finalizeTransactionSettlesEveryDocumentOfTheSale() {
        com.intermarche.pos.ui.recall.BackOfficeDocumentClient documentClient =
                mock(com.intermarche.pos.ui.recall.BackOfficeDocumentClient.class);
        service.documentClient = documentClient;
        state.payment.ticketDbId = 9L;
        state.recalledDocumentCodes.add("BV-1");
        state.recalledDocumentCodes.add("BV-2");
        state.recalledOrderCode = "BV-2";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(documentClient).settleOrder("BV-1");
        verify(documentClient).settleOrder("BV-2");
        assertTrue(state.recalledDocumentCodes.isEmpty());
    }

    /**
     * LC-13-05-06: a training sale ending reaches the back office's journal in
     * its own table; a sale without draft outside training mode keeps nothing.
     */
    @Test
    void finalizeTransactionKeepsTheTrainingSale() {
        service.trainingTicketService = mock(TrainingTicketService.class);
        service.documentClient = mock(com.intermarche.pos.ui.recall.BackOfficeDocumentClient.class);
        state.payment.ticketDbId = null;
        state.trainingMode = true;
        service.finalizeTransaction(state);
        verify(service.trainingTicketService).record(state);
        state.trainingMode = false;
        state.payment.ticketDbId = null;
        service.finalizeTransaction(state);
        verify(service.trainingTicketService, org.mockito.Mockito.times(1)).record(any());
    }

    /**
     * LC-06-03-07: a sale that settles no order tells the node nothing (the
     * null arm of the settle guard).
     */
    @Test
    void finalizeTransactionWithoutRecalledOrderTellsNothing() {
        com.intermarche.pos.ui.recall.BackOfficeDocumentClient documentClient =
                mock(com.intermarche.pos.ui.recall.BackOfficeDocumentClient.class);
        service.documentClient = documentClient;
        state.payment.ticketDbId = 9L;
        state.recalledOrderCode = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(documentClient, org.mockito.Mockito.never()).settleOrder(org.mockito.ArgumentMatchers.any());
    }

    /**
     * An engine-valued sale declares its campaign consumption at the fiscal
     * moment, derived from the retained valuation, with the ticket's number,
     * store code (null here) and closing date ({@code "ENGINE".equals} true,
     * {@code valued != null} true).
     */
    @Test
    void finalizeTransactionEnqueuesConsumptionForEngineValuation() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationJson = "{\"advantages\":[]}";
        com.intermarche.pos.domain.sale.Ticket valued =
                org.mockito.Mockito.mock(com.intermarche.pos.domain.sale.Ticket.class);
        valued.ticketNumber = "C04-000003";
        valued.closingDate = java.time.LocalDateTime.of(2026, 8, 2, 10, 30);
        valued.store = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L)).thenReturn(valued);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService).enqueueSale("C04-000003", null,
                java.time.LocalDateTime.of(2026, 8, 2, 10, 30), "{\"advantages\":[]}");
    }

    /**
     * The retained engine offer messages are frozen on the ticket at the fiscal
     * moment (spec §3): each {@code EngineMessage} on the payment state is mapped
     * to a {@code TicketMessage} and handed to the persistence service.
     */
    @Test
    void finalizeTransactionFreezesEngineMessagesOnTheTicket() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationMessages.add(
                new com.intermarche.pos.ui.valuation.ValuationService.EngineMessage(
                        "BELOW_TRIGGER", "Plus que 5 €", "FOOTER"));
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeEngineMessages(
                org.mockito.ArgumentMatchers.eq(9L), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("Plus que 5 €", captor.getValue().get(0).text);
        assertEquals("BELOW_TRIGGER", captor.getValue().get(0).code);
        assertEquals("FOOTER", captor.getValue().get(0).printMode);
    }

    /**
     * An engine-valued sale whose ticket cannot be reloaded declares nothing
     * ({@code "ENGINE".equals} true, {@code valued != null} false).
     */
    @Test
    void finalizeTransactionDeclaresNoConsumptionWhenTicketVanished() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "ENGINE";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService, never()).enqueueSale(any(), any(), any(), any());
    }

    /**
     * A locally-valued sale declares no engine consumption ({@code
     * "ENGINE".equals} false).
     */
    @Test
    void finalizeTransactionDeclaresNoConsumptionForLocalValuation() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "LOCAL";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService, never()).enqueueSale(any(), any(), any(), any());
    }

    /**
     * WITHOUT a card, the fiscal moment declares nothing loyalty-side: no
     * lease to confirm, no event to enqueue.
     *
     * <p>It does still ASK whether the programme was reachable, and freezes the
     * answer: a receipt printed for a non-holder while the service was silent
     * carries its own message (BO-03-03-30), so the question is asked on every
     * sale and not only on the ones that presented a card.
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
        verify(fidelityService).isUnavailable();
        verify(fidelityService, never()).confirmLease(any(), any());
        verify(fidelityService, never()).buildTicketClosedPayload(
                any(), any(), any(), any(), any());
        verifyNoInteractions(fidEventOutboxService);
    }

    /**
     * BO-03-03-25/-29/-31: the closing FREEZES the loyalty zone on the sale —
     * the displayed earn, its advantage lines, the balance read at attachment
     * and whether the service answered. Frozen here and nowhere else: the
     * projection is cleared with the state a few lines later.
     */
    @Test
    void finalizeTransactionFreezesTheLoyaltyZoneOnTheSale() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        state.fidelity.earnTotal = new java.math.BigDecimal("1.03");
        state.fidelity.availableBalance = new java.math.BigDecimal("42.30");
        state.fidelity.earnEntries.add(
                new com.intermarche.pos.ui.fidelity.FidelityState.EarnLine(
                        "SOCLE", "Cagnotte socle", new java.math.BigDecimal("0.28")));
        com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance pot =
                new com.intermarche.pos.ui.fidelity.ImfidClient.PotBalance();
        pot.label = "Cagnotte";
        pot.balance = new java.math.BigDecimal("5.00");
        state.fidelity.potBalances.add(pot);
        when(fidelityService.isUnavailable()).thenReturn(true);
        com.intermarche.pos.domain.sale.Ticket closed =
                org.mockito.Mockito.mock(com.intermarche.pos.domain.sale.Ticket.class);
        closed.ticketNumber = "C04-000003";
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>>
                frozen = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeFidelity(eq(9L),
                eq(new java.math.BigDecimal("1.03")), eq(new java.math.BigDecimal("42.30")),
                eq(true), frozen.capture(), eq(null), eq(null), eq(false));
        assertEquals(1, frozen.getValue().size());
        assertEquals("SOCLE", frozen.getValue().get(0).ruleCode);
        assertEquals("Cagnotte socle", frozen.getValue().get(0).label);
        assertEquals(new java.math.BigDecimal("0.28"), frozen.getValue().get(0).amount);
        // BO-10-02-33: the pot balances of the fiscal moment are frozen too.
        verify(ticketPersistenceService).storePotBalances(9L, "Cagnotte\t5.00");
    }

    /**
     * A sale that displayed NO advantage still writes: an empty list and three
     * nulls, which is what the zone of a non-holder's receipt states.
     */
    @Test
    void finalizeTransactionFreezesAnEmptyLoyaltyZoneOnAbareSale() {
        state.payment.ticketDbId = 9L;
        when(fidelityService.isUnavailable()).thenReturn(false);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        org.mockito.ArgumentCaptor<java.util.List<com.intermarche.pos.domain.sale.TicketFidelityLine>>
                frozen = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(ticketPersistenceService).storeFidelity(eq(9L), eq(null), eq(null),
                eq(false), frozen.capture(), eq(null), eq(null), eq(false));
        assertTrue(frozen.getValue().isEmpty());
        // BO-10-02-33: no pot read, nothing to freeze.
        verify(ticketPersistenceService).storePotBalances(9L, null);
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
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000001";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(77L);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"ticketRef\":\"2026-C04-000001\"}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fidelityService, fidEventOutboxService);
        order.verify(fidelityService).confirmLease(eq(state), any());
        order.verify(fidelityService).buildTicketClosedPayload(
                eq(state), eq(java.time.LocalDate.now().getYear() + "-C04-000001"),
                eq("2990000000019"), any(), eq(77L));
        order.verify(fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED),
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
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000002";
        when(fidelityService.confirmLease(eq(state), any())).thenReturn(null);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"earn\":1}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
                    .thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidelityService).buildTicketClosedPayload(
                eq(state), any(), eq("2990000000019"), any(), org.mockito.ArgumentMatchers.isNull());
        verify(fidEventOutboxService).enqueue(
                com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED, "{\"earn\":1}");
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
        com.intermarche.pos.domain.sale.Ticket closed =
                new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000003";
        when(fidelityService.buildTicketClosedPayload(any(), any(), any(), any(), any()))
                .thenReturn(null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
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
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L))
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
    private com.intermarche.pos.domain.payment.StoredValue issuedCard(String number, String amount) {
        com.intermarche.pos.domain.payment.StoredValue card = new com.intermarche.pos.domain.payment.StoredValue();
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
            long ticketId, java.util.List<com.intermarche.pos.domain.payment.StoredValue> issued) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.payment.StoredValue> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(issued);
        panache.when(() -> com.intermarche.pos.domain.payment.StoredValue
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
        io.quarkus.hibernate.orm.panache.PanacheQuery<com.intermarche.pos.domain.payment.StoredValue> empty =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(empty.list()).thenReturn(java.util.List.of());
        panache.when(() -> com.intermarche.pos.domain.payment.StoredValue
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
    // processBackupPayment
    // --------------------------------------------------

    /**
     * {@code processBackupPayment} rejects a null amount ({@code amount == null}
     * true arm of the first guard).
     */
    @Test
    void processBackupPaymentNullReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processBackupPayment(state, null, "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} rejects a non-positive amount ({@code amount
     * == null} false, {@code amount.signum() <= 0} true).
     */
    @Test
    void processBackupPaymentZeroReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processBackupPayment(state, BigDecimal.ZERO, "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} rejects a payment on a settled ticket: the
     * amount is positive but nothing is due ({@code amount == null} false,
     * {@code amount.signum() <= 0} false, {@code remaining.signum() <= 0} true).
     */
    @Test
    void processBackupPaymentNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processBackupPayment(state, new BigDecimal("5"), "CB SECOURS", "TX1", false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processBackupPayment} caps the applied amount at the remaining due,
     * gives no change, persists the entry, completes the transaction and never
     * opens the drawer ({@code remaining.signum() <= 0} false, {@code manual}
     * carried, completion reached).
     */
    @Test
    void processBackupPaymentCapsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processBackupPayment(state, new BigDecimal("20.00"), "CB SECOURS", "TX1", true);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("SECOURS  15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processBackupPayment} for a partial amount registers it and leaves
     * the transaction incomplete (completion not reached, the false arm of the
     * check).
     */
    @Test
    void processBackupPaymentPartialStaysIncomplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processBackupPayment(state, new BigDecimal("5.00"), "CB SECOURS", "TX2", false);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("SECOURS  5,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processForeignCurrency
    // --------------------------------------------------

    /**
     * Builds a foreign currency with the given ISO code, display symbol (null to
     * fall back on the code) and administered euro-per-unit rate.
     *
     * @param code the ISO code
     * @param symbol the display symbol, or null to use the code
     * @param euroPerUnit how many euros one unit is worth
     * @return the currency
     */
    private com.intermarche.pos.domain.payment.Currency currency(String code, String symbol, String euroPerUnit) {
        com.intermarche.pos.domain.payment.Currency c = new com.intermarche.pos.domain.payment.Currency();
        c.code = code;
        c.symbol = symbol;
        c.euroPerUnit = new BigDecimal(euroPerUnit);
        return c;
    }

    /**
     * {@code processForeignCurrency} rejects a null currency ({@code currency ==
     * null} true arm of the first guard).
     */
    @Test
    void processForeignCurrencyNullCurrencyReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, null, new BigDecimal("50"), new BigDecimal("47.50"));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a null euro value ({@code currency
     * == null} false, {@code euroValue == null} true).
     */
    @Test
    void processForeignCurrencyNullEuroValueReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), null);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a non-positive euro value
     * ({@code currency == null} false, {@code euroValue == null} false,
     * {@code euroValue.signum() <= 0} true).
     */
    @Test
    void processForeignCurrencyZeroEuroValueReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("0"), BigDecimal.ZERO);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} rejects a settlement on a ticket with
     * nothing due ({@code euroValue} positive so the first guard is fully false,
     * {@code remaining.signum() <= 0} true).
     */
    @Test
    void processForeignCurrencyNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processForeignCurrency} for an exact euro value gives no change,
     * persists the entry, shows the three figures without a RENDU line, opens
     * the drawer and completes ({@code change.signum() > 0} false arm of both the
     * ternary and the display guard, drawer rule on).
     */
    @Test
    void processForeignCurrencyExactOpensDrawerCompletes() {
        state.ticket.totalAmount = new BigDecimal("47.50");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        assertEquals(0, new BigDecimal("47.50").compareTo(state.payment.paidAmount));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService, never()).displayMessage("RENDU 0,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processForeignCurrency} for foreign notes worth more than the due
     * caps at the remaining, gives euro change, shows the RENDU line and opens
     * the drawer ({@code change.signum() > 0} true arm of both the ternary and
     * the display guard).
     */
    @Test
    void processForeignCurrencyOverpaymentShowsChangeOpensDrawer() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.paidAmount));
        assertEquals(0, new BigDecimal("7.50").compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService).displayMessage("RENDU 7,50 E");
        verify(hardwareService).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processForeignCurrency} in training registers the entry, shows the
     * figures using the ISO code when no symbol was given, but never opens the
     * drawer ({@code !trainingMode} false arm short-circuits the drawer pulse).
     */
    @Test
    void processForeignCurrencyTrainingNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processForeignCurrency(state, currency("USD", null, "0.90"),
                new BigDecimal("10"), new BigDecimal("9.00"));
        verify(hardwareService).displayMessage("10,00 USD = 9,00 E");
        verify(hardwareService, never()).openDrawer();
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * BO-10-02-12: with the drawer-open-on-payment rule DISABLED, a foreign
     * currency settlement registers but the drawer stays shut ({@code
     * !trainingMode} true but {@code drawerOpenOnPayment()} false arm).
     */
    @Test
    void processForeignCurrencyDrawerRuleDisabledKeepsDrawerShut() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("47.50");
        state.payment.ticketDbId = 3L;
        service.processForeignCurrency(state, currency("CHF", "CHF", "1.05"),
                new BigDecimal("50"), new BigDecimal("47.50"));
        verify(hardwareService).displayMessage("50,00 CHF = 47,50 E");
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processCredit
    // --------------------------------------------------

    /**
     * Builds an account customer with the given account number and business
     * name (no contact, so {@code getDisplayName()} is the business name alone).
     *
     * @param number the account number
     * @param company the business name
     * @return the account customer
     */
    private com.intermarche.pos.domain.payment.AccountCustomer customer(String number, String company) {
        com.intermarche.pos.domain.payment.AccountCustomer c = new com.intermarche.pos.domain.payment.AccountCustomer();
        c.accountNumber = number;
        c.companyName = company;
        return c;
    }

    /**
     * {@code processCredit} rejects a null customer ({@code customer == null}
     * true arm of the first guard).
     */
    @Test
    void processCreditNullCustomerReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, null, new BigDecimal("10"), false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a null amount ({@code customer == null}
     * false, {@code amount == null} true).
     */
    @Test
    void processCreditNullAmountReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, customer("C001", "ACME"), null, false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a non-positive amount ({@code customer ==
     * null} false, {@code amount == null} false, {@code amount.signum() <= 0}
     * true).
     */
    @Test
    void processCreditZeroAmountReturns() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCredit(state, customer("C001", "ACME"), BigDecimal.ZERO, false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} rejects a charge on a settled ticket: the amount is
     * positive but nothing is due (first guard fully false, {@code
     * remaining.signum() <= 0} true).
     */
    @Test
    void processCreditNothingDueReturns() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCredit(state, customer("C001", "ACME"), new BigDecimal("5"), false);
        verifyNoInteractions(hardwareService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * {@code processCredit} caps the charge at the remaining due, persists the
     * entry, completes and never opens the drawer ({@code remaining.signum() <=
     * 0} false, {@code overLimit} carried, completion reached).
     */
    @Test
    void processCreditCapsCompletesNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C001", "ACME"), new BigDecimal("20.00"), true);
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CREDIT CLIENT  15,00 E");
        verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        verify(hardwareService, never()).openDrawer();
        assertTrue(state.payment.transactionComplete);
    }

    /**
     * {@code processCredit} for a partial charge registers it and leaves the
     * transaction incomplete (completion not reached, the false arm of the
     * check).
     */
    @Test
    void processCreditPartialStaysIncomplete() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C002", "BETA"), new BigDecimal("5.00"), false);
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("CREDIT CLIENT  5,00 E");
        assertFalse(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // finalizeTransaction — conditional printing (LC-08-03)
    // --------------------------------------------------

    /**
     * With conditional printing ENABLED and the choice not yet applied, the
     * closing prints the documents the decision names — here the sale ticket and
     * the card receipt — and records the choice as applied ({@code
     * isConditionalEnabled()} true AND {@code !printApplied} true, both {@code
     * decision.saleTicket()} and {@code decision.cardReceipt()} true). The
     * closing broom {@code payment.reset()} wipes {@code printApplied}, so the
     * documents printed — not the flag — are the durable proof the block ran.
     */
    @Test
    void finalizeTransactionConditionalEnabledPrintsNamedDocuments() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).validateTicket(9L);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService).printCardReceipt(9L, false, null);
    }

    /**
     * With conditional printing ENABLED but the choice ALREADY applied, the
     * closing prints nothing on its own — a reloaded screen never produces a
     * second original ({@code isConditionalEnabled()} true AND {@code
     * !printApplied} false arm).
     */
    @Test
    void finalizeTransactionConditionalEnabledButAlreadyApplied() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        state.payment.ticketDbId = 9L;
        state.payment.printApplied = true;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    /**
     * With conditional printing ENABLED and a decision naming NEITHER document,
     * the closing block is entered but prints nothing ({@code
     * decision.saleTicket()} false arm and {@code decision.cardReceipt()} false
     * arm, {@code decision.voucher()} false so the gift-card loop is skipped);
     * the ticket is still validated and the customer thanked.
     */
    @Test
    void finalizeTransactionConditionalEnabledDecisionNamesNothing() {
        when(printPolicy.isConditionalEnabled()).thenReturn(true);
        stubDecision(false, false, false);
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printTicket(any());
        verify(ticketPrinterService, never()).printCardReceipt(any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(ticketPersistenceService).validateTicket(9L);
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

    // --- Assiette TR locale, portée par les attributs article (BO-02-03-06) ---

    /**
     * Adds a line to the cart with the given total and eligibility.
     *
     * @param total the line total
     * @param eligible whether the article is meal-voucher eligible
     */
    private void cartLine(String total, boolean eligible) {
        com.intermarche.pos.ui.ticket.TicketState.TicketItem item =
                new com.intermarche.pos.ui.ticket.TicketState.TicketItem(
                        "EAN" + state.ticket.items.size(), null, "ART",
                        new java.math.BigDecimal(total), java.math.BigDecimal.ONE,
                        new java.math.BigDecimal("0.055"));
        item.mealVoucherEligible = eligible;
        state.ticket.items.add(item);
    }

    /**
     * WITHOUT an engine answer, the meal-ticket settlement is capped by what the
     * ELIGIBLE lines are worth: a 20 € ticket carrying 8 € of eligible articles
     * settles 8 €, not 20 €.
     */
    @Test
    void processTicketRestoIsCappedByTheEligibleLines() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("8.00", true);
        cartLine("12.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.payment.paidAmount));
        verify(hardwareService).displayMessage("TR PLAFONNE  8,00 E");
    }

    /**
     * A SECOND eligible base caps at another figure, which is what proves the
     * cap is read from the lines and not hard-coded.
     */
    @Test
    void aSecondEligibleBaseCapsElsewhere() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("5.50", true);
        cartLine("14.50", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("5.50").compareTo(state.payment.paidAmount));
    }

    /**
     * A request that FITS the eligible base is registered as it stands, without
     * a capping message (the {@code amount > allowed} false arm).
     */
    @Test
    void aRequestFittingTheEligibleBaseIsRegisteredAsIs() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("8.00", true);
        cartLine("12.00", false);
        service.processTicketResto(state, new BigDecimal("3.00"));
        assertEquals(0, new BigDecimal("3.00").compareTo(state.payment.paidAmount));
        verify(hardwareService, never()).displayMessage("TR PLAFONNE  8,00 E");
    }

    /**
     * A cart carrying NO eligible line keeps the former behaviour — no cap at
     * all — because an attribute nobody declared cannot state a base. The
     * register is not made stricter by an empty referential.
     */
    @Test
    void aCartWithoutAnEligibleLineIsNotCapped() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        cartLine("20.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.paidAmount));
    }

    /**
     * The ENGINE still wins when it answered: it knows the offers, the
     * attributes do not. A 10 € engine base caps above the 4 € the lines
     * declare.
     */
    @Test
    void theEngineBaseWinsOverTheLines() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("10.00");
        cartLine("4.00", true);
        cartLine("16.00", false);
        service.processTicketResto(state, new BigDecimal("20.00"));
        assertEquals(0, new BigDecimal("10.00").compareTo(state.payment.paidAmount));
    }

    // --------------------------------------------------
    // Administered tender rules (BO-03-02-10 to -23)
    // --------------------------------------------------

    /**
     * A blocking rule leaves the sale EXACTLY as it was: nothing registered,
     * nothing persisted, nothing displayed, and the refusal on the screen
     * (BO-03-02-10/12/13/14).
     */
    @Test
    void aBlockingTenderRuleRegistersNothing() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CASH"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "MONTANT SUPERIEUR AU PLAFOND (25,00 E)"));
        service.processCash(state, new BigDecimal("40.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.paidAmount));
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(Long.class), any());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A rule that only informs lets the settlement through and tells the cashier
     * afterwards — the other arm of the same guard.
     */
    @Test
    void anInformativeTenderRuleRegistersAndSpeaks() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CASH"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                        "MONTANT SUPERIEUR AU PLAFOND (25,00 E)"));
        service.processCash(state, new BigDecimal("40.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
    }

    /**
     * The count opposed to a settlement is the number of settlements of THAT
     * tender the sale already carries, not the number of settlements it carries
     * (BO-03-02-12).
     */
    @Test
    void theCountOpposedIsTheCountOfThatTender() {
        state.ticket.totalAmount = new BigDecimal("60.00");
        state.payment.ticketDbId = 3L;
        state.payment.addCashPayment(new BigDecimal("10.00"), new BigDecimal("10.00"));
        state.payment.addPayment("TR", new BigDecimal("10.00"));
        service.processCash(state, new BigDecimal("10.00"));
        verify(tenderRulesService).check(eq("CASH"), any(), eq(1));
    }

    /**
     * A tender the back office forbids change on settles what it settles and
     * gives nothing back (BO-03-02-16).
     */
    @Test
    void aTenderForbiddenChangeGivesNoneBack() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeAllowed(eq("CASH"), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("ESPECES   20,00 E");
    }

    /**
     * A change ceiling that blocks refuses the settlement rather than capping
     * the change, the drawer staying shut (BO-03-02-11).
     */
    @Test
    void aBlockingChangeCeilingRefusesTheSettlement() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "RENDU SUPERIEUR AU PLAFOND (8,00 E)"));
        service.processCash(state, new BigDecimal("50.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("RENDU SUPERIEUR AU PLAFOND (8,00 E)", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * The drawer follows the ADMINISTERED moment: a tender administered to open
     * only on change keeps it shut on an exact settlement and opens it when
     * change is owed (BO-03-02-19).
     */
    @Test
    void theDrawerFollowsTheAdministeredMoment() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.opensDrawer(eq("CASH"), eq(false), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);
        service.processCash(state, new BigDecimal("20.00"));
        verify(hardwareService, never()).openDrawer();

        state.payment.reset();
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.opensDrawer(eq("CASH"), eq(true), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        service.processCash(state, new BigDecimal("50.00"));
        verify(hardwareService).openDrawer();
    }

    /**
     * The moment is asked with the change the settlement actually produced, so a
     * tender administered on the change can tell the two cases apart.
     */
    @Test
    void theDrawerMomentIsAskedWithTheChangeThatWasGiven() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCash(state, new BigDecimal("50.00"));
        verify(tenderRulesService).opensDrawer(eq("CASH"), eq(true), eq(false),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // --------------------------------------------------
    // Supervisor override of an administered bound (BO-03-02-10/12/13/14)
    // --------------------------------------------------

    /**
     * Stubs the bound check of one tender to the given verdict.
     *
     * @param methodKey the settlement key
     * @param level the control level the rule carries
     * @param message what the cashier is told
     */
    private void ruleOn(String methodKey,
            com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel level,
            String message) {
        when(tenderRulesService.check(eq(methodKey), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(level, message));
    }

    /**
     * A rule at the supervisor level HOLDS the settlement instead of losing it:
     * nothing is registered, and the sale carries what the supervisor must
     * decide on — the tender, its label, the amount and the rule broken.
     */
    @Test
    void aSupervisorRuleHoldsTheSettlement() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertTrue(state.payment.isTenderAuthorizationPending());
        assertEquals("CASH", state.payment.tenderHeldMethod);
        assertEquals("ESPECES", state.payment.tenderHeldLabel);
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.tenderHeldAmount));
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.payment.tenderHeldMessage);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A rule at the blocking level is never held: nobody passes it, so no
     * authorization is asked for — the other arm of the same guard.
     */
    @Test
    void aBlockingRuleIsNeverHeld() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertEquals("MONTANT SUPERIEUR AU PLAFOND (25,00 E)", state.ticket.transientError);
    }

    /**
     * A supervisor authorizing the hold registers the settlement THAT WAS HELD,
     * opens the drawer for it and clears the hold.
     */
    @Test
    void anAuthorizedHoldRegistersTheHeldSettlement() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "MONTANT SUPERIEUR AU PLAFOND (25,00 E)");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(eq("chef"), eq("secret"), any())).thenReturn(true);

        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, new BigDecimal("40.00").compareTo(state.payment.paidAmount));
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertNull(state.payment.tenderOverride);
        verify(hardwareService).openDrawer();
    }

    /**
     * A logged operator who IS a supervisor authorizes without typing anything —
     * the other leg of the credential disjunction.
     */
    @Test
    void aSupervisorAtTheTillAuthorizesWithoutCredentials() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);

        assertTrue(service.authorizeHeldTender(state, null, null));
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * A refused credential registers nothing, keeps the hold and says so.
     */
    @Test
    void aRefusedCredentialKeepsTheHold() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));

        assertFalse(service.authorizeHeldTender(state, "quidam", "faux"));
        assertTrue(state.payment.payments.isEmpty());
        assertTrue(state.payment.isTenderAuthorizationPending());
        assertEquals("AUTORISATION REFUSEE", state.payment.tenderHeldMessage);
    }

    /**
     * Authorizing when nothing is held does nothing at all, and never asks for a
     * credential — the two legs of the guard.
     */
    @Test
    void authorizingNothingDoesNothing() {
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        state.payment.tenderHeldMethod = "CASH";
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        verifyNoInteractions(endorsementService);
    }

    /**
     * An authorization covers ONE tender: a hold released on cash does not let a
     * meal voucher past its own supervisor rule.
     */
    @Test
    void anAuthorizationCoversOnlyItsOwnTender() {
        state.ticket.totalAmount = new BigDecimal("60.00");
        state.payment.ticketDbId = 3L;
        state.payment.valuationStatus = "LOCAL";
        state.payment.tenderOverride = "CASH";
        ruleOn("TR", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND TR");
        service.processTicketResto(state, new BigDecimal("30.00"));
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("TR", state.payment.tenderHeldMethod);
    }

    /**
     * Abandoning the hold forgets the settlement and the authorization alike.
     */
    @Test
    void abandoningTheHoldForgetsEverything() {
        state.payment.tenderHeldMethod = "CASH";
        state.payment.tenderHeldAmount = new BigDecimal("40.00");
        state.payment.tenderOverride = "CASH";
        service.cancelHeldTender(state);
        assertFalse(state.payment.isTenderAuthorizationPending());
        assertNull(state.payment.tenderOverride);
        assertNull(state.payment.tenderHeldAmount);
    }

    // --------------------------------------------------
    // Change given as a credit note (BO-03-02-16)
    // --------------------------------------------------

    /**
     * A tender administered to give its change back in vouchers books the change
     * instead of handing cash over, and says so on the customer display.
     */
    @Test
    void changeAdministeredAsAVoucherIsBookedNotHandedOver() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("VOUCHER");
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("30.00").compareTo(state.payment.changeAsCreditNote));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        verify(hardwareService).displayMessage("RENDU EN AVOIR 30,00 E");
    }

    /**
     * A tender whose change stays in its own tender books nothing — the other
     * arm of the same guard.
     */
    @Test
    void changeInTheTenderItselfBooksNoNote() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("CASH");
        service.processCash(state, new BigDecimal("50.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.changeAsCreditNote));
        assertEquals(0, new BigDecimal("30.00").compareTo(state.payment.lastChangeAmount));
    }

    /**
     * An exact settlement books no note whatever the administered change tender
     * — the amount leg of the same guard.
     */
    @Test
    void anExactSettlementBooksNoNote() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.changeTender("CASH")).thenReturn("VOUCHER");
        service.processCash(state, new BigDecimal("20.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.changeAsCreditNote));
    }

    /**
     * The booked change becomes a NUMBERED credit note at the fiscal moment, is
     * printed with its number, and stops being owed.
     */
    @Test
    void theBookedChangeBecomesACreditNoteAtTheFiscalMoment() {
        state.payment.ticketDbId = 9L;
        state.payment.changeAsCreditNote = new BigDecimal("30.00");
        when(ticketPersistenceService.issueChangeCreditNote(eq(9L), any()))
                .thenReturn("297000000000042");
        when(printPolicy.isAssociatedDocumentPrinted(
                com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_CREDIT_NOTE))
                .thenReturn(true);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService).issueChangeCreditNote(9L, new BigDecimal("30.00"));
        verify(ticketPrinterService).printChangeVoucher("297000000000042", new BigDecimal("30.00"));
    }

    /**
     * A sale owing no change as a note issues none, and a registry that answers
     * no number prints nothing — the two legs of the issuance guard.
     */
    @Test
    void aSaleOwingNoNoteIssuesNone() {
        state.payment.ticketDbId = 9L;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService, never()).issueChangeCreditNote(any(), any());

        state.payment.ticketDbId = 11L;
        state.payment.changeAsCreditNote = new BigDecimal("5.00");
        when(ticketPersistenceService.issueChangeCreditNote(eq(11L), any())).thenReturn(null);
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 11L);
            service.finalizeTransaction(state);
        }
        verify(ticketPrinterService, never()).printChangeVoucher(any(), any());
    }

    // --------------------------------------------------
    // Enrichment: remaining branches
    // --------------------------------------------------

    /**
     * A change verdict that only INFORMS is shown AFTER the settlement is
     * registered when the tender verdict itself stayed silent
     * ({@code verdict.speaks()} false arm, {@code changeVerdict.speaks()} true
     * arm of the closing message guard).
     */
    @Test
    void anInformativeChangeVerdictSpeaksAfterSettlement() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.INFO,
                        "RENDU INHABITUEL"));
        service.processCash(state, new BigDecimal("20.00"));
        assertEquals(1, state.payment.payments.size());
        assertEquals("RENDU INHABITUEL", state.ticket.transientError);
    }

    /**
     * A closing whose {@code changeAsCreditNote} was cleared to null issues no
     * credit note: the {@code owed == null} true arm of the issuance guard,
     * unreachable through the money path (the field is initialized to zero and
     * only ever added to) but defended here.
     */
    @Test
    void finalizeTransactionWithNullChangeAsCreditNoteIssuesNone() {
        state.payment.ticketDbId = 9L;
        state.payment.changeAsCreditNote = null;
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(
                             io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            service.finalizeTransaction(state);
        }
        verify(ticketPersistenceService, never()).issueChangeCreditNote(any(), any());
    }

    /**
     * Invokes the private {@code handlePaymentWithChange} with a null method key:
     * {@code registeredCount} short-circuits on the null key ({@code methodKey ==
     * null} true arm) and, when the rule holds for a supervisor,
     * {@code overrideCovers} short-circuits on the null key ({@code methodKey !=
     * null} false arm). Both are unreachable through the public API, where every
     * caller passes a literal key.
     *
     * @throws Exception if reflective invocation fails
     */
    @Test
    void handlePaymentWithChangeNullMethodKeyCountsZeroAndHolds() throws Exception {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(org.mockito.ArgumentMatchers.isNull(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                        "PLAFOND"));
        Method m = PaymentService.class.getDeclaredMethod(
                "handlePaymentWithChange", PosState.class, String.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        Object result = m.invoke(service, state, null, "X", new BigDecimal("20.00"));
        assertEquals(Boolean.FALSE, result);
        assertTrue(state.payment.payments.isEmpty());
        assertEquals(0, new BigDecimal("20.00").compareTo(state.payment.tenderHeldAmount));
    }

    /**
     * An authorized hold whose replay is REFUSED by a blocking change ceiling
     * registers nothing and opens no drawer ({@code registered} false arm of the
     * post-authorization drawer guard).
     */
    @Test
    void anAuthorizedHoldRefusedOnReplayOpensNoDrawer() {
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        when(tenderRulesService.checkChange(eq("CASH"), any()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "RENDU SUPERIEUR AU PLAFOND"));
        assertFalse(service.authorizeHeldTender(state, "chef", "secret"));
        assertTrue(state.payment.payments.isEmpty());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * An authorized hold in TRAINING registers the held settlement but opens no
     * drawer ({@code !state.trainingMode} false arm of the post-authorization
     * drawer guard).
     */
    @Test
    void anAuthorizedHoldInTrainingOpensNoDrawer() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * An authorized hold registered while the drawer-open-on-payment rule is
     * DISABLED opens no drawer ({@code opensDrawerFor} false arm of the
     * post-authorization drawer guard).
     */
    @Test
    void anAuthorizedHoldWithDrawerRuleDisabledOpensNoDrawer() {
        when(posSettingsService.drawerOpenOnPayment()).thenReturn(false);
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        ruleOn("CASH", com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR,
                "PLAFOND");
        service.processCash(state, new BigDecimal("40.00"));
        when(endorsementService.authorize(any(), any(), any())).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, "chef", "secret"));
        assertEquals(1, state.payment.payments.size());
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * A cheque settlement REFUSED by a blocking tender rule registers nothing and
     * opens no drawer: {@code registerCheque} returns on the false arm of its
     * {@code handlePaymentWithChange} guard. Exercised in training, where the
     * cheque is registered directly without a reading.
     */
    @Test
    void processChequeBlockedByRuleRegistersNothing() {
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.check(eq("CHEQUE"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING,
                        "PLAFOND CHEQUE"));
        service.processCheque(state, new BigDecimal("20.00"));
        service.settlePendingChequeRead(state);
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("PLAFOND CHEQUE", state.ticket.transientError);
        verify(hardwareService, never()).openDrawer();
    }

    /**
     * LC-07-16-01: a completed cash sale with null change consults the physical
     * fallback and sends the opening order through the public completion flow.
     */
    @Test
    void opensDrawerForWithNullLastChangeAmount() {
        state.payment.lastChangeAmount = null;
        state.payment.transactionComplete = true;
        state.payment.addPayment("CASH", BigDecimal.ONE);
        service.openSettlementDrawer(state, false);
        verify(hardwareService).openDrawer();
        verify(tenderRulesService).opensDrawer("CASH", false, false, true);
    }

    /**
     * {@code creditTender} keeps an administered key the register KNOWS: a
     * customer-credit settlement is then registered under that key
     * ({@code forKey(administered) == null} false arm, versus the true arm the
     * default tests exercise where the unstubbed key resolves to null).
     */
    @Test
    void processCreditUsesTheKnownAdministeredTenderKey() {
        when(posSettingsService.customerCreditTender()).thenReturn("CHEQUE");
        state.ticket.totalAmount = new BigDecimal("15.00");
        state.payment.ticketDbId = 3L;
        service.processCredit(state, customer("C003", "GAMMA"), new BigDecimal("15.00"), false);
        assertEquals(1, state.payment.payments.size());
        assertEquals("CHEQUE", state.payment.payments.get(0).method);
        assertTrue(state.payment.transactionComplete);
    }

    // --------------------------------------------------
    // processNetRefund (EXP-38 signed settlement direction)
    // --------------------------------------------------

    /**
     * {@code processNetRefund} does nothing when money is still due (a positive
     * remaining): the ordinary tender methods collect it, so the refund direction
     * refuses and leaves the sale untouched (remaining.signum() &gt;= 0 arm).
     */
    @Test
    void processNetRefundReturnsFalseWhenNothingOwed() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        boolean settled = service.processNetRefund(state, "CASH");
        assertFalse(settled);
        assertTrue(state.payment.payments.isEmpty());
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * {@code processNetRefund} on a mixed ticket whose signed total is negative
     * pays the difference back: it records the negative settlement, drives the
     * display, brings the remaining to zero and completes the transaction
     * (remaining.signum() &lt; 0 arm; negative-total completion == 0 arm).
     */
    @Test
    void processNetRefundPaysBackNegativeTotal() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 3L;
        boolean settled = service.processNetRefund(state, "CASH");
        assertTrue(settled);
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, state.payment.paidAmount.compareTo(new BigDecimal("-5.00")));
        assertEquals(0, state.getRemaining().compareTo(BigDecimal.ZERO));
        assertTrue(state.payment.transactionComplete);
        verify(hardwareService).displayMessage("REMBOURSEMENT 5,00 E");
    }
    /** LC-07-04-10/-11: terminal frames replace generated slips and stay attached when administered. */
    @Test
    void applyPrintChoiceUsesExactTerminalFramesWithAttachment() {
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        var entry = state.payment.payments.get(0);
        entry.customerReceiptFrame = " CLIENT\n";
        entry.merchantReceiptFrame = " MERCHANT\n";
        when(posSettingsService.cardReceiptAttached()).thenReturn(true);
        stubDecision(true, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService).printTicket(9L, false);
        verify(ticketPrinterService).printTerminalFrame(" CLIENT\n", null);
        verify(ticketPrinterService).printTerminalFrame(" MERCHANT\n", null);
        verify(ticketPrinterService).archiveCustomerDocument(
                com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, 9L, " CLIENT\n");
        verify(ticketPrinterService, never()).archiveCustomerDocument(any(), any(), eq(" MERCHANT\n"));
        verify(ticketPrinterService, never()).printCardReceipt(any(), anyBoolean(), any());
    }

    /** LC-07-04-11: separate policy retains the normal sale cut and supports a merchant-only frame. */
    @Test
    void applyPrintChoiceKeepsSeparateTerminalFrames() {
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.payments.get(0).merchantReceiptFrame = "MERCHANT";
        stubDecision(true, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService).printTerminalFrame("MERCHANT", null);
        verify(ticketPrinterService, never()).printTicket(9L, false);
        verify(ticketPrinterService, never()).printCardReceipt(any(), anyBoolean(), any());
    }

    /** LC-07: a card refund remains unpaid until the terminal approves, and duplicate callbacks do not pay twice. */
    @Test
    void cardRefundRequiresTerminalAcceptance() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        assertTrue(service.processNetRefund(state, "CARD"));
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        assertFalse(state.payment.transactionComplete);
        assertFalse(service.processNetRefund(state, "CARD"));
        var callback = org.mockito.ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
        verify(terminal).requestCredit(eq(new BigDecimal("5.00")), callback.capture());
        var outcome = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("5.00"));
        outcome.server = "2";
        callback.getValue().onAccepted(outcome);
        callback.getValue().onAccepted(outcome);
        assertEquals(new BigDecimal("-5.00"), state.payment.paidAmount);
        assertEquals(1, state.payment.payments.size());
        assertEquals("2", state.payment.payments.get(0).moneticServer);
        assertTrue(state.payment.transactionComplete);
    }

    /** LC-07: a refused credit and a transport failure both leave the refund due. */
    @Test
    void cardRefundRefusalAndFailureRemainUnpaid() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        service.processNetRefund(state, "CARD");
        var callback = org.mockito.ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
        verify(terminal).requestCredit(eq(new BigDecimal("5.00")), callback.capture());
        callback.getValue().onRefused(null);
        assertNull(state.payment.pendingCardAmount);
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        service.processNetRefund(state, "CARD");
        verify(terminal, times(2)).requestCredit(eq(new BigDecimal("5.00")), callback.capture());
        callback.getValue().onError("UNREACHABLE");
        assertNull(state.payment.pendingCardAmount);
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        assertFalse(state.payment.transactionComplete);
    }

    /** LC-07-06-02: one combined key sends the engine allowance without recording an assumed payment. */
    @Test
    void combinedCardUsesEligibleAllowance() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("8.00");
        service.processCard(state, BigDecimal.ZERO, true);
        verify(terminal).requestCombinedDebit(eq(new BigDecimal("20.00")), eq(new BigDecimal("8.00")), any());
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
    }

    /** LC-07-06-02/-07-04: the actual partial D10 and mapped tender are recorded, with the selected server. */
    @Test
    void actualTerminalAmountAndMappedTender() {
        var callback = captureCallback("15.00");
        when(posSettingsService.verifoneMethodMap()).thenReturn("D16:3F=TRD");
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("5.00"));
        outcome.meanCode = "3F";
        outcome.server = "2";
        callback.onAccepted(outcome);
        assertEquals(new BigDecimal("5.00"), state.payment.paidAmount);
        assertEquals("TRD", state.payment.payments.get(0).method);
        assertEquals("2", state.payment.payments.get(0).moneticServer);
        assertFalse(state.payment.transactionComplete);
        assertNull(state.payment.pendingMoneticServer);
    }

    /** LC-07: a zero settled amount never creates a false accepted card payment. */
    @Test
    void zeroTerminalAmountIsNotBooked() {
        var callback = captureCallback("15.00");
        callback.onAccepted(TerminalOutcome.ofAmount(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        assertNull(state.payment.pendingCardAmount);
    }

    /** LC-07-05-06/-07: the front uses actual amount words and optional PDV fields, without booking a cheque. */
    @Test
    void chequeFrontUsesAdministeredDataAndCallbacks() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(posSettingsService.value("cheque.front-lines"))
                .thenReturn("{montant-lettres};{montant};{beneficiaire};{lieu};{banque};{compte}");
        when(posSettingsService.value("cheque.beneficiary")).thenReturn("SHOP");
        when(posSettingsService.value("cheque.place")).thenReturn("PARIS");
        when(posSettingsService.value("cheque.bank-reference")).thenReturn("BANK");
        when(posSettingsService.value("cheque.account-number")).thenReturn("ACCOUNT");
        service.printChequeFront(state, null);
        assertTrue(state.payment.chequeFrontPrinting);
        var text = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<String>> success = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Consumer<String>> error = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(chequeReadingService).printFront(text.capture(), success.capture(), error.capture());
        assertTrue(text.getValue().contains("dix"));
        assertTrue(text.getValue().contains("10,00"));
        assertTrue(text.getValue().contains("SHOP\nPARIS\nBANK\nACCOUNT"));
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        success.getValue().accept("");
        assertFalse(state.payment.chequeFrontPrinting);
        error.getValue().accept("PRINT FAILED");
        assertFalse(state.payment.chequeFrontPrinting);
        assertEquals("PRINT FAILED", state.ticket.transientError);
    }

    /** LC-07-05-06: concurrent print/read guards and nonpositive amounts never request paper. */
    @Test
    void chequeFrontGuards() {
        state.payment.chequeFrontPrinting = true;
        service.printChequeFront(state, BigDecimal.ONE);
        service.processCheque(state, BigDecimal.ONE);
        state.payment.chequeFrontPrinting = false;
        state.payment.pendingChequeAmount = BigDecimal.ONE;
        service.printChequeFront(state, BigDecimal.ONE);
        state.payment.pendingChequeAmount = null;
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.printChequeFront(state, BigDecimal.ZERO);
        verifyNoInteractions(chequeReadingService);
        assertTrue(state.payment.payments.isEmpty());
    }

    /** LC-07-05-06: training simulates printing and never reaches the physical station. */
    @Test
    void trainingFrontPrintIsSimulated() {
        state.trainingMode = true;
        state.ticket.totalAmount = BigDecimal.ONE;
        service.printChequeFront(state, BigDecimal.ONE);
        verifyNoInteractions(chequeReadingService);
        assertFalse(state.payment.chequeFrontPrinting);
        assertTrue(state.payment.payments.isEmpty());
    }

    /** LC-07-17-05: a terminal-controlled donation receipt is printed, without adding a second POS donation. */
    @Test
    void additionalMoneticFramesArePrinted() {
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.payments.get(0).additionalReceiptFrames = java.util.List.of("DONATION\n");
        assertTrue(service.hasMoneticFrames(state));
        stubDecision(false, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.CARD_RECEIPT);
        verify(ticketPrinterService).printTerminalFrame("DONATION\n", null);
        verify(ticketPrinterService, never()).printCardReceipt(any(), anyBoolean(), any());
        assertNull(state.donationLineUid);
    }

    /** LC-07-17-05: missing or empty additional frames never fabricate a monetic receipt. */
    @Test
    void absentAdditionalFramesStayAbsent() {
        state.payment.addPayment("CARD", BigDecimal.ONE);
        assertFalse(service.hasMoneticFrames(state));
        state.payment.payments.get(0).additionalReceiptFrames = java.util.List.of();
        assertFalse(service.hasMoneticFrames(state));
    }

    /** LC-07-04-10: either primary frame makes the vendor print path available independently. */
    @Test
    void primaryMoneticFramesAreDetectedIndependently() {
        state.payment.addPayment("CARD", BigDecimal.ONE);
        var entry = state.payment.payments.get(0);
        entry.customerReceiptFrame = "CLIENT";
        assertTrue(service.hasMoneticFrames(state));
        entry.customerReceiptFrame = null;
        entry.merchantReceiptFrame = "MERCHANT";
        assertTrue(service.hasMoneticFrames(state));
    }

    /** LC-07-06-02: absent meal eligibility sends zero eligible cents rather than assuming the full basket. */
    @Test
    void combinedCardWithoutEligibilitySendsZero() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        service.processCard(state, BigDecimal.ZERO, true);
        verify(terminal).requestCombinedDebit(eq(new BigDecimal("20.00")), eq(BigDecimal.ZERO), any());
    }

    /** LC-07: a blocked card control never starts a monetary exchange. */
    @Test
    void blockedCardControlNeverReachesTerminal() {
        when(endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        service.processCard(state, BigDecimal.ONE);
        verifyNoInteractions(terminal);
        assertNull(state.payment.pendingCardAmount);
    }

    /** LC-07-04-12: supplementary TNA frames are printed only after an actual refused result. */
    @Test
    void refusedTerminalAdditionalFramesArePrinted() {
        var callback = captureCallback("15.00");
        when(printPolicy.isTnaReceiptForced()).thenReturn(true);
        var outcome = TerminalOutcome.ofAmount(new BigDecimal("15.00"));
        outcome.tnaFrame = "TNA";
        outcome.additionalReceiptFrames.add("TNA EXTRA");
        callback.onRefused(outcome);
        verify(ticketPrinterService).printCardTnaReceipt(new BigDecimal("15.00"), "TNA");
        verify(ticketPrinterService).printTerminalFrame("TNA EXTRA");
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
    }

    /** LC-07: legacy terminal outcomes without a returned amount retain the requested amount. */
    @Test
    void acceptedOutcomeWithoutAmountUsesRequested() {
        var callback = captureCallback("15.00");
        callback.onAccepted(TerminalOutcome.ofAmount(null));
        assertEquals(new BigDecimal("15.00"), state.payment.paidAmount);
    }

    /** LC-07-07-04: an already accepted bank decision cannot be discarded by a later local rule change. */
    @Test
    void acceptedBankDecisionSurvivesLaterRuleChange() {
        var callback = captureCallback("15.00");
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING, "BLOCKED"));
        callback.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertEquals(1, state.payment.payments.size());
        assertEquals(new BigDecimal("15.00"), state.payment.paidAmount);
        verify(ticketPersistenceService).addPaymentToTicket(eq(3L), any());
    }

    /** LC-07-04-11: merchant-only frames cannot suppress the sale cut even when attachment is enabled. */
    @Test
    void merchantOnlyFrameCannotAttachToSale() {
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.payments.get(0).merchantReceiptFrame = "MERCHANT";
        when(posSettingsService.cardReceiptAttached()).thenReturn(true);
        stubDecision(true, true, false);
        applyChoice(com.intermarche.pos.ui.hardware.PrintChoice.ALL);
        verify(ticketPrinterService).printTicket(9L);
        verify(ticketPrinterService).printTerminalFrame("MERCHANT", null);
    }

    /** LC-07-04-11: the legacy print button uses exact frames and marks them already printed for closing. */
    @Test
    void legacyPrintButtonUsesAttachedFrames() {
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.payments.get(0).customerReceiptFrame = "CLIENT";
        when(posSettingsService.cardReceiptAttached()).thenReturn(true);
        service.printTicketWithMoneticFrames(state, 9L);
        verify(ticketPrinterService).printTicket(9L, false);
        verify(ticketPrinterService).printTerminalFrame("CLIENT", null);
        assertTrue(state.payment.printApplied);
    }

    /** LC-07: tender ceilings are opposed before the bank is asked and cashback is never requested. */
    @Test
    void cardPreflightBlocksBeforeBankAndCapsAmount() {
        state.ticket.totalAmount = new BigDecimal("5.00");
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING, "BLOCKED"));
        service.processCard(state, new BigDecimal("10.00"));
        verifyNoInteractions(terminal);
        assertNull(state.payment.pendingCardAmount);
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(TenderRulesService.Verdict.silent());
        service.processCard(state, new BigDecimal("10.00"));
        verify(terminal).requestDebit(eq(new BigDecimal("5.00")), any());
    }

    /** LC-07: a positive explicit debit on an already settled basket never reaches the terminal. */
    @Test
    void cardOnSettledBasketDoesNotStart() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.processCard(state, BigDecimal.ONE);
        verifyNoInteractions(terminal);
    }

    /** LC-07-06-02: a held combined key is replayed to the terminal after approval, without local booking. */
    @Test
    void supervisorReplaysCombinedCardToTerminal() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR, "SUPERVISOR"));
        service.processCard(state, BigDecimal.ZERO, true);
        verifyNoInteractions(terminal);
        assertEquals("CARD", state.payment.tenderHeldMethod);
        assertTrue(state.payment.tenderHeldCombined);
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, "", ""));
        verify(terminal).requestCombinedDebit(eq(new BigDecimal("10.00")), eq(BigDecimal.ZERO), any());
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        assertNull(state.payment.tenderHeldMethod);
    }

    /** LC-07: synchronous simulator acceptance counts as a completed authorized card request. */
    @Test
    void supervisorCardSynchronousAcceptance() {
        state.ticket.totalAmount = BigDecimal.ONE;
        state.payment.tenderHeldMethod = "CARD";
        state.payment.tenderHeldAmount = BigDecimal.ONE;
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        service.terminal = new AutoAcceptTerminalClient();
        assertTrue(service.authorizeHeldTender(state, "", ""));
        assertEquals(new BigDecimal("1.00"), state.payment.paidAmount);
        assertNull(state.payment.pendingCardAmount);
    }

    /** LC-07: synchronous refusal after endorsement cannot be reported as a registered payment. */
    @Test
    void supervisorCardSynchronousRefusal() {
        state.ticket.totalAmount = BigDecimal.ONE;
        state.payment.tenderHeldMethod = "CARD";
        state.payment.tenderHeldAmount = BigDecimal.ONE;
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(1, TerminalTransactionCallback.class).onRefused(null);
            return null;
        }).when(terminal).requestDebit(any(), any());
        assertFalse(service.authorizeHeldTender(state, "", ""));
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
    }

    /** LC-07-12-04: a positive out-of-range index preserves the existing settlement. */
    @Test
    void cancellationAboveLastIndexPreservesPayment() {
        state.payment.addPayment("CASH", BigDecimal.ONE);
        assertFalse(service.cancelOnePayment(state, 1));
        assertEquals(1, state.payment.payments.size());
        verifyNoInteractions(ticketPrinterService);
    }

    /** LC-07-12-04: null entries and absent printers produce no cancellation slip. */
    @Test
    void cancellationSlipMissingEntryOrPrinter() throws Exception {
        invokePaymentHelper("printCancellationSlip", new Class<?>[]{Long.class, PaymentState.PaymentEntry.class}, null, null);
        service.ticketPrinterService = null;
        invokePaymentHelper("printCancellationSlip", new Class<?>[]{Long.class, PaymentState.PaymentEntry.class}, null,
                new PaymentState.PaymentEntry("CHEQUE", BigDecimal.ONE));
        verifyNoInteractions(ticketPrinterService);
    }

    /** LC-07-12-04: every administered-label null/blank leg falls back to the settlement key. */
    @Test
    void cancellationSlipUsesAdministeredLabelOnlyWhenPresent() throws Exception {
        var entry = new PaymentState.PaymentEntry("CHEQUE", BigDecimal.ONE);
        try (var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class);
             var tickets = mockStatic(PanacheEntityBase.class)) {
            var tender = new com.intermarche.pos.domain.payment.TenderDefinition();
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CHEQUE"))
                    .thenReturn(tender);
            for (String label : new String[]{null, " ", "STORE CHEQUE"}) {
                tender.label = label;
                invokePaymentHelper("printCancellationSlip", new Class<?>[]{Long.class, PaymentState.PaymentEntry.class}, null, entry);
            }
            verify(ticketPrinterService, times(2)).printPaymentCancellation(null, "CHEQUE", BigDecimal.ONE);
            verify(ticketPrinterService).printPaymentCancellation(null, "STORE CHEQUE", BigDecimal.ONE);
            Ticket ticket = new Ticket();
            ticket.ticketNumber = "C04-T00001";
            tickets.when(() -> PanacheEntityBase.findById(8L)).thenReturn(ticket);
            invokePaymentHelper("printCancellationSlip", new Class<?>[]{Long.class, PaymentState.PaymentEntry.class}, 8L, entry);
            verify(ticketPrinterService).printPaymentCancellation("C04-T00001", "STORE CHEQUE", BigDecimal.ONE);
            invokePaymentHelper("printCancellationSlip", new Class<?>[]{Long.class, PaymentState.PaymentEntry.class}, 9L, entry);
            verify(ticketPrinterService, times(2)).printPaymentCancellation(null, "STORE CHEQUE", BigDecimal.ONE);
        }
    }

    /** BO-03-03-11: absent balances and vanished drafts are printed as unknown, not invented zeros. */
    @Test
    void giftSlipUnknownBalanceAndDraftLegs() throws Exception {
        Class<?>[] types = {PosState.class, String.class, BigDecimal.class, BigDecimal.class};
        invokePaymentHelper("printGiftCardPaymentSlip", types, state, "CARD", BigDecimal.ONE, null);
        verify(ticketPrinterService).printGiftCardPaymentReceipt(null, "CARD", BigDecimal.ONE, null);
        state.payment.ticketDbId = 8L;
        try (var tickets = mockStatic(PanacheEntityBase.class)) {
            Ticket ticket = new Ticket();
            ticket.ticketNumber = "C04-T00001";
            tickets.when(() -> PanacheEntityBase.findById(8L)).thenReturn(ticket);
            invokePaymentHelper("printGiftCardPaymentSlip", types, state, "CARD", BigDecimal.ONE, BigDecimal.TEN);
            verify(ticketPrinterService).printGiftCardPaymentReceipt("C04-T00001", "CARD", BigDecimal.ONE, new BigDecimal("9"));
            state.payment.ticketDbId = 9L;
            invokePaymentHelper("printGiftCardPaymentSlip", types, state, "CARD", BigDecimal.ONE, BigDecimal.TEN);
            verify(ticketPrinterService).printGiftCardPaymentReceipt(null, "CARD", BigDecimal.ONE, new BigDecimal("9"));
        }
        service.ticketPrinterService = null;
        invokePaymentHelper("printGiftCardPaymentSlip", types, state, "OTHER", BigDecimal.ONE, null);
        verify(ticketPrinterService, never()).printGiftCardPaymentReceipt(any(), eq("OTHER"), any(), any());
    }

    /** LC-07-05-03: absent settings retain the legacy date/amount endorsement. */
    @Test
    void endorsementWithoutSettingsUsesFallback() throws Exception {
        service.posSettingsService = null;
        String text = (String) invokePaymentHelper("endorsementText", new Class<?>[]{PosState.class, BigDecimal.class}, state, BigDecimal.ONE);
        assertTrue(text.matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}\\n1,00 EUR"));
        assertEquals("", invokePaymentHelper("chequeParameter", new Class<?>[]{String.class}, "cheque.place"));
    }

    /** LC-07-05-03: blank templates and absent terminal/store identities never leak placeholders. */
    @Test
    void chequeBlankTemplateAndMissingIdentities() throws Exception {
        Class<?>[] types = {PosState.class, BigDecimal.class, String.class};
        String fallback = (String) invokePaymentHelper("chequeText", types, state, BigDecimal.ONE, " ");
        assertTrue(fallback.endsWith("\n1,00 EUR"));
        service.ticketNumberService = null;
        service.pdvNumber = null;
        assertEquals("X", invokePaymentHelper("chequeText", types, state, BigDecimal.ONE, "{caisse};{pdv};X"));
    }

    /** LC-07-06-02: engine nulls, local eligibility, thresholds and all three meal tenders reduce allowance. */
    @Test
    void combinedAllowanceCoversEverySourceAndRegisteredTender() throws Exception {
        Class<?>[] types = {PosState.class};
        state.payment.valuationStatus = "ENGINE";
        assertNull(invokePaymentHelper("mealVoucherAllowance", types, state));
        cartLine("20.00", true);
        assertEquals(new BigDecimal("20.00"), invokePaymentHelper("mealVoucherAllowance", types, state));
        state.payment.valuationMealEligible = new BigDecimal("15.00");
        state.payment.valuationMealThreshold = new BigDecimal("10.00");
        state.payment.addPayment("TR", BigDecimal.ONE);
        state.payment.addPayment("TRD", BigDecimal.ONE);
        state.payment.addPayment("RESTOFLASH", BigDecimal.ONE);
        state.payment.addPayment("CASH", BigDecimal.ONE);
        assertEquals(new BigDecimal("7.00"), invokePaymentHelper("mealVoucherAllowance", types, state));
        state.payment.valuationStatus = "LOCAL";
        assertEquals(new BigDecimal("17.00"), invokePaymentHelper("mealVoucherAllowance", types, state));
    }

    /** LC-07: warnings preserve the debit, and the popup suppression flag covers its independent leg. */
    @Test
    void cardWarningCanBeDisplayedOrSuppressed() {
        state.ticket.totalAmount = BigDecimal.TEN;
        when(endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.WARN);
        when(endorsementService.controlMessage(com.intermarche.pos.ui.endorsement.ControlPoint.CARD_PAYMENT,
                com.intermarche.pos.ui.endorsement.ControlLevel.WARN)).thenReturn("CARD WARNING");
        service.processCard(state, BigDecimal.ONE);
        assertEquals("CARD WARNING", state.ticket.transientError);
        state.payment.pendingCardAmount = null;
        state.ticket.transientError = null;
        when(posSettingsService.islandBlockPaymentPopups()).thenReturn(true);
        service.processCard(state, BigDecimal.ONE);
        assertNull(state.ticket.transientError);
        verify(terminal, times(2)).requestDebit(eq(new BigDecimal("1.00")), any());
    }

    /** BO-03-02-27/-29: a forbidden card on self-service never starts a combined bank exchange. */
    @Test
    void selfServiceCardGuardRunsBeforeBank() {
        state.ticket.totalAmount = BigDecimal.TEN;
        var lane = new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_DN;
        var tender = new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.selfServiceAllowed = false;
        service.ticketNumberService = null;
        try (var points = mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(null)).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CARD")).thenReturn(tender);
            service.processCard(state, BigDecimal.ONE, true);
            assertTrue(state.payment.tenderHeldCombined);
            verifyNoInteractions(terminal);
        }
    }

    /** BO-03-02-27/-29: missing tender, unwatched tender and an existing supervisor override each allow the card. */
    @Test
    void selfServiceCardAllowedLegs() {
        state.ticket.totalAmount = BigDecimal.TEN;
        var lane = new com.intermarche.pos.domain.store.CheckoutPoint();
        lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_DN;
        var tender = new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.selfServiceAllowed = true;
        try (var points = mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(null)).thenReturn(lane);
            service.processCard(state, BigDecimal.ONE);
            state.payment.pendingCardAmount = null;
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CARD")).thenReturn(tender);
            service.processCard(state, BigDecimal.ONE);
            state.payment.pendingCardAmount = null;
            tender.selfServiceSupervision = true;
            state.payment.tenderOverride = "CARD";
            service.processCard(state, BigDecimal.ONE);
            verify(terminal, times(3)).requestDebit(eq(new BigDecimal("1.00")), any());
        }
    }

    /** LC-07: a negative basket is incomplete when a deliberately inconsistent payment over-refunds it. */
    @Test
    void negativeBasketOverRefundIsNotComplete() throws Exception {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.paidAmount = new BigDecimal("-6.00");
        invokePaymentHelper("checkCompletion", new Class<?>[]{PosState.class}, state);
        assertFalse(state.payment.transactionComplete);
    }

    /** Invokes defensive private branches which public validation otherwise makes unreachable.
     * @param name helper name
     * @param types declared argument types
     * @param arguments supplied arguments
     * @return helper result
     * @throws Exception if the helper cannot be invoked
     */
    private Object invokePaymentHelper(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = PaymentService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, arguments);
    }

    /** LC-07-12-04: a negative index short-circuits before reading any settlement. */
    @Test
    void cancellationNegativeIndexPreservesPayment() {
        state.payment.addPayment("CASH", BigDecimal.ONE);
        assertFalse(service.cancelOnePayment(state, -1));
        assertEquals(1, state.payment.payments.size());
    }

    /** LC-07: the legacy card accounting helper retains the terminal traces without initiating a new debit. */
    @Test
    void legacyCardAccountingBranch() throws Exception {
        state.ticket.totalAmount = BigDecimal.TEN;
        state.payment.pendingCardAuthNumber = "AUTH";
        assertEquals(true, invokePaymentHelper("handlePaymentWithChange",
                new Class<?>[]{PosState.class, String.class, String.class, BigDecimal.class}, state, "CARD", "CARD", BigDecimal.ONE));
        assertEquals("CARD", state.payment.payments.get(0).method);
        verifyNoInteractions(terminal);
    }

    /** BO-03-02-16: an issued credit note remains owed to the customer even when its paper is disabled. */
    @Test
    void changeNoteWithoutForcedPaper() throws Exception {
        state.payment.changeAsCreditNote = BigDecimal.ONE;
        when(ticketPersistenceService.issueChangeCreditNote(9L, BigDecimal.ONE)).thenReturn("NOTE-1");
        invokePaymentHelper("issueChangeCreditNote", new Class<?>[]{PosState.class, Long.class}, state, 9L);
        verify(ticketPersistenceService).issueChangeCreditNote(9L, BigDecimal.ONE);
        verify(ticketPrinterService, never()).printChangeVoucher(any(), any());
    }

    /** LC-07-06: TRD null/zero requests default to the due; cap equal/below/zero/null legs preserve the allowed amount. */
    @Test
    void trdDefaultAmountAndCapLegs() {
        state.ticket.totalAmount = new BigDecimal("100.00");
        when(posSettingsService.trdCardCap()).thenReturn(null);
        service.processTrdCard(state, BigDecimal.ZERO);
        assertEquals(new BigDecimal("100.00"), state.payment.payments.get(0).amount);
        state.clearPayments();
        state.ticket.totalAmount = new BigDecimal("100.00");
        when(posSettingsService.trdCardCap()).thenReturn(new BigDecimal("10.00"));
        service.processTrdCard(state, BigDecimal.ONE);
        assertEquals(BigDecimal.ONE, state.payment.payments.get(0).amount);
        when(posSettingsService.trdCardCap()).thenReturn(BigDecimal.ZERO);
        service.processTrdCard(state, BigDecimal.ONE);
        assertEquals(2, state.payment.payments.size());
    }

    /** LC-07-06: a mobile-voucher zero request defaults to the due with no local eligible-base constraint. */
    @Test
    void mobileVoucherWithoutEligibleBaseUsesDue() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(restoFlashClient.confirm("TOKEN", new BigDecimal("10.00"))).thenReturn(true);
        service.processRestoFlash(state, "TOKEN", BigDecimal.ZERO);
        assertEquals(new BigDecimal("10.00"), state.payment.paidAmount);
    }

    /** LC-07-06: a mobile voucher below the known allowance retains the requested amount. */
    @Test
    void mobileVoucherBelowAllowanceIsNotCapped() {
        state.ticket.totalAmount = BigDecimal.TEN;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = BigDecimal.TEN;
        when(restoFlashClient.confirm("TOKEN", BigDecimal.ONE)).thenReturn(true);
        service.processRestoFlash(state, "TOKEN", BigDecimal.ONE);
        assertEquals(BigDecimal.ONE, state.payment.paidAmount);
    }

    /** BO-03-02: TRD, mobile vouchers, issuer gift cards and SOGEC each respect a locally blocking tender rule. */
    @Test
    void specializedTendersCannotBypassBlockingRules() {
        state.ticket.totalAmount = BigDecimal.TEN;
        when(tenderRulesService.check(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.BLOCKING, "BLOCKED"));
        when(restoFlashClient.confirm("TOKEN", BigDecimal.ONE)).thenReturn(true);
        when(giftCardIssuerClient.query("CARD")).thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, BigDecimal.TEN));
        service.processTrdCard(state, BigDecimal.ONE);
        service.processRestoFlash(state, "TOKEN", BigDecimal.ONE);
        service.processSupplierGiftCard(state, "CARD", BigDecimal.ONE);
        service.processSogec(state, "SG1", BigDecimal.ONE);
        assertTrue(state.payment.payments.isEmpty());
        verify(sogecCompensationService, never()).recordCompensation(any(), any());
    }

    /** BO-03-03-11: gift-card null/zero amounts default to the remainder, and a settled basket never queries the issuer. */
    @Test
    void giftCardDefaultAndSettledAmountLegs() {
        service.processSupplierGiftCard(state, "NONE", BigDecimal.ZERO);
        verifyNoInteractions(giftCardIssuerClient);
        state.ticket.totalAmount = new BigDecimal("10.00");
        when(giftCardIssuerClient.query("CARD")).thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("10.00")));
        service.processSupplierGiftCard(state, "CARD", null);
        assertEquals(new BigDecimal("10.00"), state.payment.paidAmount);
        state.clearPayments();
        state.ticket.totalAmount = new BigDecimal("10.00");
        service.processSupplierGiftCard(state, "CARD", BigDecimal.ZERO);
        assertEquals(new BigDecimal("10.00"), state.payment.paidAmount);
    }

    /** BO-03-03-11: unknown balances and known zero balances both refuse instead of fabricating funds. */
    @Test
    void giftCardNullAndZeroBalanceLegs() {
        state.ticket.totalAmount = BigDecimal.TEN;
        when(giftCardIssuerClient.query("UNKNOWN")).thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, null));
        when(giftCardIssuerClient.query("EMPTY")).thenReturn(new GiftCardIssuerClient.IssuerVerdict(true, BigDecimal.ZERO));
        service.processSupplierGiftCard(state, "UNKNOWN", BigDecimal.ONE);
        service.processSupplierGiftCard(state, "EMPTY", BigDecimal.ONE);
        assertTrue(state.payment.payments.isEmpty());
        verifyNoInteractions(ticketPrinterService);
    }

    /** LC-07-05-03: a present but empty store identity renders no literal Optional or placeholder. */
    @Test
    void chequeEmptyOptionalStore() throws Exception {
        service.pdvNumber = java.util.Optional.empty();
        assertEquals("X", invokePaymentHelper("chequeText", new Class<?>[]{PosState.class, BigDecimal.class, String.class},
                state, BigDecimal.ONE, "{pdv};X"));
    }

    /** LC-07-04-10: closing prints unprinted vendor frames once and can force the associated collection/loyalty documents. */
    @Test
    void closingLegacyReceiptLegsAndAssociatedDocuments() {
        when(printPolicy.isAssociatedDocumentPrinted(com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_COLLECTION)).thenReturn(true);
        when(printPolicy.isAssociatedDocumentPrinted(com.intermarche.pos.ui.hardware.PrintPolicy.DOCUMENT_LOYALTY)).thenReturn(true);
        for (int index = 0; index < 3; index++) {
            long id = 9L + index;
            state.payment.ticketDbId = id;
            state.payment.addPayment("CARD", BigDecimal.ONE);
            state.payment.payments.get(0).customerReceiptFrame = "CLIENT";
            state.payment.printApplied = index == 1;
            if (index == 2) state.payment.payments.get(0).customerReceiptFrame = null;
            try (var panache = mockStatic(PanacheEntityBase.class)) {
                stubNoGiftCards(panache, id);
                service.finalizeTransaction(state);
            }
            verify(ticketPrinterService).printCollectionVoucher(id);
            verify(ticketPrinterService).printLoyaltyReceipt(id);
        }
        verify(ticketPrinterService).printTerminalFrame("CLIENT", null);
    }

    /** LC-07: engine consumption carries the known store identity instead of dropping it as null. */
    @Test
    void closingEngineConsumptionWithStoreIdentity() {
        state.payment.ticketDbId = 9L;
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationJson = "{}";
        Ticket valued = mock(Ticket.class);
        valued.ticketNumber = "C04-T00001";
        valued.store = new com.intermarche.pos.domain.store.Store();
        valued.store.code = "PDV";
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(valued);
            service.finalizeTransaction(state);
        }
        verify(service.consumptionService).enqueueSale("C04-T00001", "PDV", null, "{}");
    }

    /** LC-07-06: a mobile voucher exceeding the engine allowance confirms only the eligible amount. */
    @Test
    void mobileVoucherIsCappedBeforeConfirmation() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.valuationStatus = "ENGINE";
        state.payment.valuationMealEligible = new BigDecimal("2.00");
        when(restoFlashClient.confirm("TOKEN", new BigDecimal("2.00"))).thenReturn(true);
        service.processRestoFlash(state, "TOKEN", new BigDecimal("5.00"));
        assertEquals(new BigDecimal("2.00"), state.payment.paidAmount);
        verify(restoFlashClient).confirm("TOKEN", new BigDecimal("2.00"));
    }
    /** BO-03-02-27/-29: temporary lane or tender referential failures retain the documented manned-lane fallback. */
    @Test
    void unreadableSelfServiceReferentialsDoNotBlockTheCard() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.ticketNumberService = null;
        try (var points = mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             var tenders = mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(null)).thenThrow(new IllegalStateException("lane unavailable"));
            service.processCard(state, BigDecimal.ONE);
            state.payment.pendingCardAmount = null;
            var lane = new com.intermarche.pos.domain.store.CheckoutPoint();
            lane.tpvType = com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_DN;
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal(null)).thenReturn(lane);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CARD")).thenThrow(new IllegalStateException("tender unavailable"));
            service.processCard(state, BigDecimal.ONE);
            verify(terminal, times(2)).requestDebit(eq(BigDecimal.ONE.setScale(2)), any());
        }
    }
    /** LC-07-08-01: terminal display frames update both operator lines without assuming a payment decision. */
    @Test
    void terminalDisplayFramesDoNotSettleThePendingCard() {
        state.ticket.totalAmount = BigDecimal.TEN;
        service.processCard(state, BigDecimal.ONE);
        var callback = ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), callback.capture());
        callback.getValue().onDisplay("INSERT CARD", "PLEASE WAIT");
        assertEquals("INSERT CARD", state.payment.terminalLine1);
        assertEquals("PLEASE WAIT", state.payment.terminalLine2);
        assertEquals(BigDecimal.ZERO, state.payment.paidAmount);
        assertFalse(state.payment.transactionComplete);
    }


    // --------------------------------------------------
    // LC-07-11-01 / -03: the gift card on the payment hub
    // --------------------------------------------------

    /**
     * An accepted hub answer.
     *
     * @param amount the authorized amount, or null
     * @param balance the balance, or null
     * @param provider the provider, or null
     * @return the answer
     */
    private NexoHubClient.HubResult hubOk(String amount, String balance, String provider) {
        return new NexoHubClient.HubResult(true, null, null, "HUB7",
                amount == null ? null : new BigDecimal(amount),
                balance == null ? null : new BigDecimal(balance), provider, "P7");
    }

    /**
     * Reads a card worth 30,00 on the hub for a sale of 12,40.
     */
    private void readCard() {
        when(nexoHubClient.isConfigured()).thenReturn(true);
        when(nexoHubClient.balance("6299")).thenReturn(hubOk(null, "30.00", "ILLICADO"));
        state.ticket.totalAmount = new BigDecimal("12.40");
        service.processSupplierGiftCard(state, " 6299 ", null);
    }

    /**
     * Gift cards go through the hub only when it is administered and the
     * register is not training.
     */
    @Test
    void giftCardsGoThroughTheHubOutsideTrainingOnly() {
        assertFalse(service.giftCardHubActive(state));
        when(nexoHubClient.isConfigured()).thenReturn(true);
        assertTrue(service.giftCardHubActive(state));
        state.trainingMode = true;
        assertFalse(service.giftCardHubActive(state));
    }

    /**
     * LC-07-11-01: the card is first read on the hub — its balance shown on
     * the register and the customer display, the amount proposed being the due
     * or the balance when smaller — and the issuer registry is not asked.
     */
    @Test
    void theCardIsFirstReadOnTheHub() {
        readCard();
        PaymentState.PendingGiftCard card = state.payment.pendingGiftCard;
        assertEquals("6299", card.cardNumber);
        assertEquals(0, new BigDecimal("30.00").compareTo(card.balance));
        assertEquals("ILLICADO", card.provider);
        assertEquals(0, new BigDecimal("12.40").compareTo(card.suggested));
        verify(hardwareService).displayMessage("SOLDE CARTE  30,00 E");
        verify(giftCardIssuerClient, never()).query(any());
        when(nexoHubClient.balance("6299")).thenReturn(hubOk(null, "5.00", null));
        service.inquireGiftCard(state, "6299");
        assertEquals(0, new BigDecimal("5.00").compareTo(state.payment.pendingGiftCard.suggested));
        assertTrue(state.version > 0);
    }

    /**
     * A card without code, refused by the hub (its words, its condition, its
     * absence) or carrying nothing is not kept, and the operator is told.
     */
    @Test
    void anUnreadableCardIsNotKept() {
        service.inquireGiftCard(state, null);
        assertEquals("SCANNEZ OU SAISISSEZ LE CODE DE LA CARTE CADEAU", state.ticket.transientError);
        service.inquireGiftCard(state, " ");
        assertEquals("SCANNEZ OU SAISISSEZ LE CODE DE LA CARTE CADEAU", state.ticket.transientError);
        when(nexoHubClient.balance("1")).thenReturn(NexoHubClient.HubResult.failure("InvalidCard", "CARTE INCONNUE"));
        service.inquireGiftCard(state, "1");
        assertEquals("CARTE CADEAU REFUSÉE (CARTE INCONNUE)", state.ticket.transientError);
        when(nexoHubClient.balance("2")).thenReturn(NexoHubClient.HubResult.failure("InvalidCard", null));
        service.inquireGiftCard(state, "2");
        assertEquals("CARTE CADEAU REFUSÉE (InvalidCard)", state.ticket.transientError);
        when(nexoHubClient.balance("3")).thenReturn(NexoHubClient.HubResult.failure(NexoHubClient.UNAVAILABLE, null));
        service.inquireGiftCard(state, "3");
        assertEquals("HUB DE PAIEMENT INJOIGNABLE", state.ticket.transientError);
        when(nexoHubClient.balance("4")).thenReturn(hubOk(null, null, null));
        service.inquireGiftCard(state, "4");
        assertEquals("CARTE CADEAU SANS SOLDE", state.ticket.transientError);
        when(nexoHubClient.balance("5")).thenReturn(hubOk(null, "0.00", null));
        service.inquireGiftCard(state, "5");
        assertEquals("CARTE CADEAU SANS SOLDE", state.ticket.transientError);
        assertNull(state.payment.pendingGiftCard);
    }

    /**
     * LC-07-11-01 / -03: paying the proposed amount debits the card on the hub,
     * registers the settlement with what the hub answered, forgets the card read
     * and prints the slip with the balances, the provider and its code.
     */
    @Test
    void theProposedAmountIsPaidOnTheHub() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            readCard();
            when(nexoHubClient.pay("GC1", "6299", new BigDecimal("12.40"))).thenReturn(hubOk("12.40", null, null));
            service.payGiftCardOnHub(state, BigDecimal.ZERO);
            assertNull(state.payment.pendingGiftCard);
            PaymentState.PaymentEntry entry = state.payment.payments.get(0);
            assertEquals("GIFTCARD", entry.method);
            assertEquals(0, new BigDecimal("12.40").compareTo(entry.amount));
            assertEquals("HUB7", entry.hubReference);
            assertEquals("6299", entry.hubCardNumber);
            assertEquals("ILLICADO", entry.hubProvider);
            assertEquals("P7", entry.hubApproval);
            assertEquals(0, new BigDecimal("30.00").compareTo(entry.hubBalanceBefore));
            assertEquals(0, new BigDecimal("17.60").compareTo(entry.hubBalanceAfter));
            verify(ticketPrinterService).printGiftCardPaymentReceipt(null, "6299", new BigDecimal("12.40"),
                    new BigDecimal("30.00"), new BigDecimal("17.60"), "ILLICADO", "P7", "HUB7", null);
        }
    }

    /**
     * A chosen amount is paid as chosen, the provider the hub names wins, the
     * slip is not printed where the back office does not want it, and the draft
     * names the slip's ticket.
     */
    @Test
    void aChosenAmountIsPaidAndTheSlipFollowsTheBackOffice() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            readCard();
            when(posSettingsService.giftCardPaymentTicket()).thenReturn(false);
            when(nexoHubClient.pay("GC1", "6299", new BigDecimal("5.00"))).thenReturn(hubOk(null, null, "HUBX"));
            service.payGiftCardOnHub(state, new BigDecimal("5.00"));
            PaymentState.PaymentEntry entry = state.payment.payments.get(0);
            assertEquals(0, new BigDecimal("5.00").compareTo(entry.amount));
            assertEquals("HUBX", entry.hubProvider);
            verify(ticketPrinterService, never()).printGiftCardPaymentReceipt(any(), any(), any(), any(),
                    any(), any(), any(), any(), any());
        }
    }

    /**
     * The slip names the draft's ticket when there is one, survives a printer
     * failure, and is skipped without a printer.
     */
    @Test
    void theSlipNamesTheDraftAndSurvivesThePrinter() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            Ticket draft = new Ticket();
            draft.ticketNumber = "POS01-00000009";
            panache.when(() -> PanacheEntityBase.findById(8L)).thenReturn(draft);
            readCard();
            state.payment.ticketDbId = 8L;
            when(nexoHubClient.pay("8-GC1", "6299", new BigDecimal("2.00"))).thenReturn(hubOk("2.00", null, null));
            when(ticketPrinterService.printGiftCardPaymentReceipt(any(), any(), any(), any(), any(), any(), any(),
                    any(), any())).thenThrow(new IllegalStateException("paper"));
            service.payGiftCardOnHub(state, new BigDecimal("2.00"));
            verify(ticketPrinterService).printGiftCardPaymentReceipt(eq("POS01-00000009"), eq("6299"),
                    any(), any(), any(), any(), any(), any(), any());
            assertEquals(1, state.payment.payments.size());
            state.payment.ticketDbId = 9L;
            service.inquireGiftCard(state, "6299");
            when(nexoHubClient.pay("9-GC2", "6299", new BigDecimal("1.00"))).thenReturn(hubOk("1.00", null, null));
            service.payGiftCardOnHub(state, new BigDecimal("1.00"));
            verify(ticketPrinterService).printGiftCardPaymentReceipt(org.mockito.ArgumentMatchers.isNull(),
                    eq("6299"), eq(new BigDecimal("1.00")), any(), any(), any(), any(), any(), any());
            service.inquireGiftCard(state, "6299");
            service.ticketPrinterService = null;
            when(nexoHubClient.pay("9-GC3", "6299", new BigDecimal("1.00"))).thenReturn(hubOk("1.00", null, null));
            service.payGiftCardOnHub(state, new BigDecimal("1.00"));
            assertEquals(3, state.payment.payments.size());
        }
    }

    /**
     * Without a card read nothing is paid; with nothing left to pay the card is
     * forgotten; an amount above the balance is refused and the card kept.
     */
    @Test
    void theHubPaymentGuards() {
        service.payGiftCardOnHub(state, BigDecimal.ONE);
        verify(nexoHubClient, never()).pay(any(), any(), any());
        when(nexoHubClient.isConfigured()).thenReturn(true);
        when(nexoHubClient.balance("6299")).thenReturn(hubOk(null, "5.00", null));
        state.ticket.totalAmount = new BigDecimal("12.40");
        service.inquireGiftCard(state, "6299");
        service.payGiftCardOnHub(state, new BigDecimal("8.00"));
        assertEquals("MONTANT SUPÉRIEUR AU SOLDE DE LA CARTE (5,00 €)", state.ticket.transientError);
        assertNotNull(state.payment.pendingGiftCard);
        state.ticket.totalAmount = BigDecimal.ZERO;
        service.payGiftCardOnHub(state, new BigDecimal("1.00"));
        assertNull(state.payment.pendingGiftCard);
        verify(nexoHubClient, never()).pay(any(), any(), any());
    }

    /**
     * A payment the hub refuses forgets the card and says why; one the register
     * then refuses is given back to the card on the hub.
     */
    @Test
    void refusedHubPayments() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            readCard();
            when(nexoHubClient.pay(any(), any(), any())).thenReturn(NexoHubClient.HubResult.failure("Refusal", "SOLDE"));
            service.payGiftCardOnHub(state, null);
            assertNull(state.payment.pendingGiftCard);
            assertEquals("CARTE CADEAU REFUSÉE (SOLDE)", state.ticket.transientError);
            service.inquireGiftCard(state, "6299");
            when(nexoHubClient.pay(any(), any(), any())).thenReturn(hubOk("0.00", null, null));
            service.payGiftCardOnHub(state, null);
            verify(nexoHubClient).reversePayment("GC1", "HUB7", new BigDecimal("0.00"));
            assertTrue(state.payment.payments.isEmpty());
            assertNull(state.payment.pendingGiftCard);
        }
    }

    /**
     * BO-03-08-06: where the shop wrote its own sentence for what the provider
     * answered, the cashier reads it in place of the provider's words.
     */
    @Test
    void aRefusedHubPaymentShowsTheShopsSentence() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            readCard();
            com.intermarche.pos.domain.setting.ProviderMessage message =
                    new com.intermarche.pos.domain.setting.ProviderMessage();
            message.screenText = "Solde de la carte insuffisant";
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(found.firstResult()).thenReturn(message);
            panache.when(() -> PanacheEntityBase.find(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(Object[].class)))
                    .thenReturn(found);
            when(nexoHubClient.pay(any(), any(), any())).thenReturn(NexoHubClient.HubResult.failure("Refusal", "SOLDE"));
            service.payGiftCardOnHub(state, null);
            assertEquals("Solde de la carte insuffisant", state.ticket.transientError);
        }
    }

    /**
     * Putting the card aside forgets it.
     */
    @Test
    void theCardReadCanBePutAside() {
        readCard();
        service.cancelPendingGiftCard(state);
        assertNull(state.payment.pendingGiftCard);
    }

    /**
     * Cancelling one settlement paid on the hub gives it back first; refused,
     * the settlement stays and the operator is told; accepted, it leaves.
     */
    @Test
    void cancellingOneHubSettlement() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            state.payment.ticketDbId = null;
            state.payment.addPayment("GIFTCARD", new BigDecimal("12.40"));
            PaymentState.PaymentEntry entry = state.payment.payments.get(0);
            entry.hubReference = "HUB7";
            when(nexoHubClient.reversePayment("HUB7-R", "HUB7", new BigDecimal("12.40")))
                    .thenReturn(NexoHubClient.HubResult.failure(NexoHubClient.UNAVAILABLE, null));
            assertFalse(service.cancelOnePayment(state, 0));
            assertEquals(1, state.payment.payments.size());
            assertEquals("HUB DE PAIEMENT INJOIGNABLE", state.ticket.transientError);
            when(nexoHubClient.reversePayment("HUB7-R", "HUB7", new BigDecimal("12.40")))
                    .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", "DEJA"));
            assertFalse(service.cancelOnePayment(state, 0));
            assertEquals("RESTITUTION DE LA CARTE CADEAU REFUSÉE (DEJA) : RÈGLEMENT CONSERVÉ",
                    state.ticket.transientError);
            when(nexoHubClient.reversePayment("HUB7-R", "HUB7", new BigDecimal("12.40")))
                    .thenReturn(hubOk("12.40", null, null));
            assertTrue(service.cancelOnePayment(state, 0));
            assertTrue(state.payment.payments.isEmpty());
            assertTrue(entry.hubReversed);
            assertNull(entry.hubReference);
        }
    }

    /**
     * Cancelling every settlement gives each hub one back first; when one is
     * refused, only those given back leave the list, with their slip and the
     * draft re-persisted, and nothing else is undone.
     */
    @Test
    void cancellingAllSettlementsWithAHubRefusal() {
        try (MockedStatic<PanacheEntityBase> panache = quietCatalog()) {
            state.payment.ticketDbId = 5L;
            state.payment.paymentInProgress = true;
            state.payment.addPayment("GIFTCARD", new BigDecimal("3.00"));
            state.payment.addPayment("GIFTCARD", new BigDecimal("4.00"));
            state.payment.addPayment("CASH", new BigDecimal("5.00"));
            state.payment.payments.get(0).hubReference = "HUBA";
            state.payment.payments.get(1).hubReference = "HUBB";
            when(nexoHubClient.reversePayment("HUBA-R", "HUBA", new BigDecimal("3.00"))).thenReturn(hubOk("3.00", null, null));
            when(nexoHubClient.reversePayment("HUBB-R", "HUBB", new BigDecimal("4.00")))
                    .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", null));
            service.cancelPayments(state);
            assertEquals(2, state.payment.payments.size());
            assertEquals("HUBB", state.payment.payments.get(0).hubReference);
            assertEquals("CASH", state.payment.payments.get(1).method);
            assertTrue(state.payment.paymentInProgress);
            verify(ticketPrinterService).printPaymentCancellation(null, "GIFTCARD", new BigDecimal("3.00"));
            verify(ticketPersistenceService).removePaymentsFromTicket(5L, false);
            verify(ticketPersistenceService, org.mockito.Mockito.times(2)).addPaymentToTicket(eq(5L), any());
            verify(ticketPersistenceService).journalCancelledPayment(5L, "GIFTCARD", new BigDecimal("3.00"));
            state.payment.ticketDbId = null;
            service.cancelPayments(state);
            assertEquals(2, state.payment.payments.size());
            when(nexoHubClient.reversePayment("HUBB-R", "HUBB", new BigDecimal("4.00"))).thenReturn(hubOk("4.00", null, null));
            service.cancelPayments(state);
            assertTrue(state.payment.payments.isEmpty());
        }
    }

    /**
     * BO-03-03-11 / LC-07-11-03: the issuer registry's slip, too, is printed only
     * where the back office asks for it.
     */
    @Test
    void theRegistrySlipFollowsTheBackOffice() {
        when(posSettingsService.giftCardPaymentTicket()).thenReturn(false);
        when(giftCardIssuerClient.query("9999000011114321")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(true, new BigDecimal("50.00")));
        state.ticket.totalAmount = new BigDecimal("30.00");
        service.processSupplierGiftCard(state, "9999000011114321", new BigDecimal("30.00"));
        verify(ticketPrinterService, never()).printGiftCardPaymentReceipt(any(), any(), any(), any());
    }

    // --------------------------------------------------
    // lot 19 — group L (BO-03-02)
    // --------------------------------------------------

    /**
     * Builds an administered tender.
     *
     * @param code the tender code
     * @param nature the nature of a tender the store added, or null
     * @return an active tender
     */
    private com.intermarche.pos.domain.payment.TenderDefinition lotTender(String code, String nature) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = code;
        tender.nature = nature;
        tender.active = true;
        return tender;
    }

    /**
     * BO-03-02-01: a tender key the register cannot honour — no code, an
     * unknown code, a sleeping tender, a native key, a nature with its own
     * panel — is refused and named, and nothing is selected.
     */
    @Test
    void anAdditionalTenderTheRegisterCannotHonourIsRefused() {
        com.intermarche.pos.domain.payment.TenderDefinition sleeping = lotTender("PASS", "CARD");
        sleeping.active = false;
        when(tenderRulesService.tender("PASS")).thenReturn(sleeping);
        when(tenderRulesService.tender("CARD")).thenReturn(lotTender("CARD", null));
        when(tenderRulesService.tender("CHF")).thenReturn(lotTender("CHF", "DEVISE"));
        for (String code : new String[]{null, "  ", "NOPE", "PASS", "CARD", "CHF"}) {
            state.ticket.transientError = null;
            service.processAdditionalTender(state, code, BigDecimal.TEN);
            assertEquals("MOYEN DE REGLEMENT INDISPONIBLE", state.ticket.transientError);
            assertNull(state.payment.selectedTenderCode);
        }
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * BO-03-02-01: a CASH tender the store added settles as cash under its OWN
     * rules and keeps its name on the settlement; its count is its own, not
     * the plain cash's.
     */
    @Test
    void anAddedCashTenderSettlesUnderItsOwnName() {
        when(tenderRulesService.tender("EURO2")).thenReturn(lotTender("EURO2", "CASH"));
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.addCashPayment(new BigDecimal("5.00"), new BigDecimal("5.00"));
        service.processAdditionalTender(state, " EURO2 ", new BigDecimal("10"));
        verify(tenderRulesService).check("EURO2", new BigDecimal("10"), 0);
        assertEquals("EURO2", state.payment.payments.get(1).tenderCode);
        assertNull(state.payment.payments.get(0).tenderCode);
        assertNull(state.payment.selectedTenderCode);
        service.processCash(state, new BigDecimal("1"));
        verify(tenderRulesService).check("CASH", new BigDecimal("1"), 1);
        assertNull(state.payment.payments.get(2).tenderCode);
    }

    /**
     * BO-03-02-01: a selection is honoured by its nature only — a plain
     * settlement of another nature neither takes the name nor consumes it —
     * and a selection whose tender vanished reads the plain rules.
     */
    @Test
    void aSelectionNamesOnlyASettlementOfItsNature() {
        when(tenderRulesService.tender("AMEX")).thenReturn(lotTender("AMEX", "CARD"));
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        state.payment.selectedTenderCode = "AMEX";
        service.processCash(state, new BigDecimal("10"));
        assertNull(state.payment.payments.get(0).tenderCode);
        assertEquals("AMEX", state.payment.selectedTenderCode);
        state.payment.selectedTenderCode = "GONE";
        service.processCash(state, new BigDecimal("5"));
        assertNull(state.payment.payments.get(1).tenderCode);
        verify(tenderRulesService).check("CASH", new BigDecimal("5"), 1);
    }

    /**
     * BO-03-02-01: a CARD tender the store added keeps its selection while the
     * terminal is asked, and names the accepted settlement.
     */
    @Test
    void anAddedCardTenderNamesTheAcceptedSettlement() {
        when(tenderRulesService.tender("AMEX")).thenReturn(lotTender("AMEX", "CARD"));
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processAdditionalTender(state, "AMEX", BigDecimal.ZERO);
        assertEquals("AMEX", state.payment.selectedTenderCode);
        verify(tenderRulesService).check("AMEX", new BigDecimal("20.00"), 0);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), captor.capture());
        captor.getValue().onAccepted(TerminalOutcome.ofAmount(new BigDecimal("20.00")));
        assertEquals("CARD", state.payment.payments.get(0).method);
        assertEquals("AMEX", state.payment.payments.get(0).tenderCode);
        assertNull(state.payment.selectedTenderCode);
    }

    /**
     * BO-03-02-01: each amount nature runs its own conversation; one that does
     * not wait on anything drops the selection at once.
     */
    @Test
    void everyAmountNatureIsDispatchedAndAnUnusedSelectionDropped() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        for (String nature : new String[]{"CHEQUE_DEFERRED", "TR", "FIDELITY", "TRD"}) {
            when(tenderRulesService.tender("X" + nature)).thenReturn(lotTender("X" + nature, nature));
            service.processAdditionalTender(state, "X" + nature, BigDecimal.ZERO);
            assertNull(state.payment.selectedTenderCode);
        }
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * BO-03-02-01/-24: a CHEQUE tender the store added waits for its reading
     * with its selection kept; the reading then names the settlement.
     */
    @Test
    void anAddedChequeTenderWaitsForItsReading() {
        when(tenderRulesService.tender("CHQKDO")).thenReturn(lotTender("CHQKDO", "CHEQUE"));
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processAdditionalTender(state, "CHQKDO", new BigDecimal("20"));
        assertEquals("CHQKDO", state.payment.selectedTenderCode);
        verify(chequeReadingService).read(any(), any(), any());
        state.payment.chequeReadLine = "CMC7";
        service.settlePendingChequeRead(state);
        assertEquals("CHQKDO", state.payment.payments.get(0).tenderCode);
        assertEquals("CMC7", state.payment.payments.get(0).magneticLine);
    }

    /**
     * BO-03-02-44: the terminal's {D16} answer administered on a tender the
     * store added records that tender; on a native row it records the native
     * nature; a mapped nature the register does not know falls back on the
     * parameter mapping.
     */
    @Test
    void theMoneticsAnswerRecordsTheAdministeredTender() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.moneticTender("43", null)).thenReturn(lotTender("AMEX", "CARD"));
        when(tenderRulesService.moneticTender("3F", null)).thenReturn(lotTender("TRD", null));
        when(tenderRulesService.moneticTender("77", null)).thenReturn(lotTender("ODD", "XX"));
        for (String mean : new String[]{"43", "3F", "77"}) {
            service.processCard(state, new BigDecimal("10"));
            ArgumentCaptor<TerminalTransactionCallback> captor =
                    ArgumentCaptor.forClass(TerminalTransactionCallback.class);
            verify(terminal, org.mockito.Mockito.atLeastOnce()).requestDebit(any(), captor.capture());
            TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("10.00"));
            outcome.meanCode = mean;
            captor.getValue().onAccepted(outcome);
        }
        assertEquals("CARD", state.payment.payments.get(0).method);
        assertEquals("AMEX", state.payment.payments.get(0).tenderCode);
        assertEquals("TRD", state.payment.payments.get(1).method);
        assertNull(state.payment.payments.get(1).tenderCode);
        assertEquals("CARD", state.payment.payments.get(2).method);
        assertNull(state.payment.payments.get(2).tenderCode);
    }

    /**
     * BO-03-02-01/-44: a card tender picked by the cashier does not rename a
     * settlement the terminal recorded as another nature.
     */
    @Test
    void aPickedCardTenderDoesNotRenameAnotherNature() {
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        when(tenderRulesService.tender("AMEX")).thenReturn(lotTender("AMEX", "CARD"));
        when(tenderRulesService.moneticTender("3F", null)).thenReturn(lotTender("TRD", null));
        state.payment.selectedTenderCode = "AMEX";
        service.processCard(state, new BigDecimal("10"));
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestDebit(any(), captor.capture());
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("10.00"));
        outcome.meanCode = "3F";
        captor.getValue().onAccepted(outcome);
        assertEquals("TRD", state.payment.payments.get(0).method);
        assertNull(state.payment.payments.get(0).tenderCode);
        assertNull(state.payment.selectedTenderCode);
    }

    /**
     * BO-03-02-29: on a MANNED till standing on an island the tender names,
     * the settlement calls a supervisor, the self-service prohibition not
     * applying there; a tender that does not name the island passes, and so
     * does any tender on a point with a blank island.
     */
    @Test
    void anIslandTheTenderNamesCallsASupervisor() {
        com.intermarche.pos.domain.payment.TenderDefinition watched = lotTender("CHEQUE", null);
        watched.selfServiceAllowed = false;
        watched.supervisionIslands = "CLS";
        com.intermarche.pos.domain.store.CheckoutPoint point =
                new com.intermarche.pos.domain.store.CheckoutPoint();
        point.terminalId = "C04";
        point.tpvType = com.intermarche.pos.domain.store.TpvType.CASH_REGISTER;
        point.islandCode = "CLS";
        when(service.ticketNumberService.getTerminalId()).thenReturn("C04");
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.store.CheckoutPoint> points =
                     mockStatic(com.intermarche.pos.domain.store.CheckoutPoint.class);
             MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            points.when(() -> com.intermarche.pos.domain.store.CheckoutPoint.findByTerminal("C04"))
                    .thenReturn(point);
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("CHEQUE"))
                    .thenReturn(watched);
            chequeReaderAnswers("a0007639 a800000000909r 000000000000i");
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
            assertEquals("CHEQUE", state.payment.tenderHeldMethod);
            assertEquals("REGLEMENT SOUMIS A CONTROLE SUR CET ILOT", state.payment.tenderHeldMessage);
            assertTrue(state.payment.payments.isEmpty());
            state.payment.clearTenderHold();
            watched.supervisionIslands = "DRIVE";
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
            assertEquals(1, state.payment.payments.size());
            state.clearPayments();
            watched.supervisionIslands = "CLS";
            point.islandCode = " ";
            service.processCheque(state, new BigDecimal("20.00"));
            service.settlePendingChequeRead(state);
            assertEquals(1, state.payment.payments.size());
        }
    }

    /**
     * BO-03-02-24: a cheque tender asking for the account number refuses a
     * cheque without one, and records it when keyed; a blank one is none.
     */
    @Test
    void theAccountNumberOfAChequeIsAskedAndRecorded() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.accountNumberEntry = true;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, new BigDecimal("20"), "  ");
        assertEquals("NUMERO DE COMPTE DU CHEQUE OBLIGATOIRE", state.ticket.transientError);
        verify(chequeReadingService, never()).read(any(), any(), any());
        chequeReaderAnswers("CMC7");
        service.processCheque(state, new BigDecimal("20"), " 12345 ");
        service.settlePendingChequeRead(state);
        assertEquals("12345", state.payment.payments.get(0).chequeAccountNumber);
        assertNull(state.payment.pendingChequeAccount);
    }

    /**
     * BO-03-02-24: a cheque tender requiring an authorization holds the cheque
     * BEFORE it is read; the supervisor's answer starts the reading.
     */
    @Test
    void anAuthorizedChequeIsHeldThenRead() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.chequeAuthorization = true;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        when(endorsementService.authorize(eq("chef"), eq("1111"), any())).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, new BigDecimal("20"));
        assertEquals("CHEQUE", state.payment.tenderHeldMethod);
        assertTrue(state.payment.chequeHeldBeforeReading);
        assertEquals("AUTORISATION CHEQUE REQUISE", state.payment.tenderHeldMessage);
        verify(chequeReadingService, never()).read(any(), any(), any());
        chequeReaderAnswers("CMC7");
        assertTrue(service.authorizeHeldTender(state, "chef", "1111"));
        service.settlePendingChequeRead(state);
        assertEquals(1, state.payment.payments.size());
        assertFalse(state.payment.chequeHeldBeforeReading);
    }

    /**
     * A cheque held by an administered rule AFTER its reading is replayed as
     * read, not read twice.
     */
    @Test
    void aChequeHeldAfterItsReadingIsNotReadAgain() {
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.payment.tenderHeldMethod = "CHEQUE";
        state.payment.tenderHeldAmount = new BigDecimal("20");
        state.payment.tenderHeldLabel = "CHEQUE";
        assertTrue(service.authorizeHeldTender(state, null, null));
        verify(chequeReadingService, never()).read(any(), any(), any());
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * BO-03-02-24: a cheque tender with no MICR reading settles at once.
     */
    @Test
    void aChequeWithoutReadingSettlesAtOnce() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.micrReading = false;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, new BigDecimal("20"));
        verifyNoInteractions(chequeReadingService);
        assertEquals(1, state.payment.payments.size());
        assertNull(state.payment.payments.get(0).magneticLine);
    }

    /**
     * BO-03-02-24: with the automatic front print, the front passes the
     * printer first; its success starts the reading, or — with no reader —
     * registers the cheque unread; its failure drops the cheque.
     */
    @Test
    void theChequeFrontIsPrintedAutomaticallyBeforeTheReading() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.chequeAutoPrint = true;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        state.ticket.totalAmount = new BigDecimal("60.00");
        state.payment.ticketDbId = 3L;
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> done =
                    (java.util.function.Consumer<String>) invocation.getArgument(1);
            done.accept("ok");
            return null;
        }).when(chequeReadingService).printFront(any(), any(), any());
        chequeReaderAnswers("CMC7");
        service.processCheque(state, new BigDecimal("20"));
        assertFalse(state.payment.chequeFrontPrinting);
        service.settlePendingChequeRead(state);
        assertEquals("CMC7", state.payment.payments.get(0).magneticLine);
        cheque.micrReading = false;
        service.processCheque(state, new BigDecimal("20"));
        service.settlePendingChequeRead(state);
        assertEquals(2, state.payment.payments.size());
        assertNull(state.payment.payments.get(1).magneticLine);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> failed =
                    (java.util.function.Consumer<String>) invocation.getArgument(2);
            failed.accept("BOURRAGE");
            return null;
        }).when(chequeReadingService).printFront(any(), any(), any());
        service.processCheque(state, new BigDecimal("20"));
        assertFalse(state.payment.chequeFrontPrinting);
        service.settlePendingChequeRead(state);
        assertEquals("BOURRAGE", state.ticket.transientError);
        assertEquals(2, state.payment.payments.size());
    }

    /**
     * BO-03-02-34: a store printing no card receipt still ARCHIVES the
     * terminal's customer frame and prints nothing; a slip the customer must
     * sign is printed all the same.
     */
    @Test
    void noCardReceiptIsPrintedWhereTheStoreSaysSo() {
        when(posSettingsService.cardReceiptPrinted()).thenReturn(false);
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CARD", BigDecimal.ONE);
        state.payment.payments.get(0).customerReceiptFrame = "CLIENT";
        state.payment.addPayment("CASH", BigDecimal.ONE);
        service.printTicketWithMoneticFrames(state, 9L);
        verify(ticketPrinterService).archiveCustomerDocument(
                com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, 9L, "CLIENT");
        verify(ticketPrinterService, never()).printTerminalFrame(any(), any());
        verify(ticketPrinterService, never()).printCardReceipt(any(), anyBoolean(), any());
        verify(ticketPrinterService).printTicket(9L);
        state.payment.cardSignatureRequired = true;
        service.printTicketWithMoneticFrames(state, 9L);
        verify(ticketPrinterService).printTerminalFrame("CLIENT", null);
    }

    /**
     * BO-03-02-39: a currency whose change tender is not the cash gives its
     * change IN that currency — the drawer of the currency keeps what was
     * handed over less what went back, and no euro leaves the till.
     */
    @Test
    void theChangeOfACurrencyIsGivenInItsExchangeCurrency() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        com.intermarche.pos.domain.payment.Currency franc = currency("CHF", null, "1.05");
        franc.changeTenderCode = "DEVISE";
        service.processForeignCurrency(state, franc, new BigDecimal("25"), new BigDecimal("26.25"));
        assertEquals(0, BigDecimal.ZERO.compareTo(state.payment.lastChangeAmount));
        assertEquals("5,95 CHF", state.payment.lastChangeForeign);
        assertEquals(new BigDecimal("19.05"), state.payment.payments.get(0).currencyAmount);
        verify(hardwareService).displayMessage("RENDU 5,95 CHF");
    }

    /**
     * BO-03-02-37: a card of a prepaid range handled LOCALLY never goes to the
     * hub, even where one is administered; a NEXO range goes to it.
     */
    @Test
    void thePrepaidRangeSaysWhetherTheHubIsAsked() {
        when(nexoHubClient.isConfigured()).thenReturn(true);
        when(giftCardIssuerClient.query("2971")).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(false, null));
        state.ticket.totalAmount = new BigDecimal("20.00");
        try (MockedStatic<com.intermarche.pos.domain.barcode.CouponType> types =
                     mockStatic(com.intermarche.pos.domain.barcode.CouponType.class)) {
            types.when(() -> com.intermarche.pos.domain.barcode.CouponType.protocolOf("2971")).thenReturn("LOCAL");
            types.when(() -> com.intermarche.pos.domain.barcode.CouponType.protocolOf("2961")).thenReturn("NEXO");
            service.processSupplierGiftCard(state, "2971", BigDecimal.TEN);
            verify(giftCardIssuerClient).query("2971");
            verify(nexoHubClient, never()).balance(any());
            when(nexoHubClient.balance("2961")).thenReturn(NexoHubClient.HubResult.failure("Refused", "NON"));
            service.processSupplierGiftCard(state, "2961", BigDecimal.TEN);
            verify(nexoHubClient).balance("2961");
        }
    }

    /**
     * BO-03-02-01: an added tender held for a supervisor keeps its selection,
     * the replay naming the settlement once the supervisor allows it.
     */
    @Test
    void aHeldAddedTenderKeepsItsSelection() {
        when(tenderRulesService.tender("EURO2")).thenReturn(lotTender("EURO2", "CASH"));
        when(tenderRulesService.check(eq("EURO2"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new TenderRulesService.Verdict(
                        com.intermarche.pos.domain.payment.TenderDefinition.ControlLevel.SUPERVISOR, "PLAFOND"));
        state.ticket.totalAmount = new BigDecimal("30.00");
        state.payment.ticketDbId = 3L;
        service.processAdditionalTender(state, "EURO2", new BigDecimal("10"));
        assertEquals("CASH", state.payment.tenderHeldMethod);
        assertEquals("EURO2", state.payment.selectedTenderCode);
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        assertTrue(service.authorizeHeldTender(state, null, null));
        assertEquals("EURO2", state.payment.payments.get(0).tenderCode);
    }

    /**
     * BO-03-02-24: a cheque authorized before its reading, once the tender no
     * longer reads cheques, settles at once; one the account rule now refuses
     * is not registered.
     */
    @Test
    void anAuthorizedChequeSettlesOrIsRefusedWithoutReading() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.chequeAuthorization = true;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        when(endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("40.00");
        state.payment.ticketDbId = 3L;
        service.processCheque(state, new BigDecimal("20"));
        cheque.micrReading = false;
        assertTrue(service.authorizeHeldTender(state, null, null));
        assertEquals(1, state.payment.payments.size());
        cheque.micrReading = true;
        service.processCheque(state, new BigDecimal("20"));
        cheque.accountNumberEntry = true;
        assertFalse(service.authorizeHeldTender(state, null, null));
        assertEquals(1, state.payment.payments.size());
    }

    /**
     * Builds a type of prepaid card.
     *
     * @param protocol its protocol
     * @param payment whether it may settle a sale
     * @return the type
     */
    private com.intermarche.pos.domain.payment.PrepaidCardType prepaid(String protocol, boolean payment) {
        com.intermarche.pos.domain.payment.PrepaidCardType type =
                new com.intermarche.pos.domain.payment.PrepaidCardType();
        type.code = "GIFT";
        type.label = "Cadeau";
        type.protocol = protocol;
        type.paymentAllowed = payment;
        return type;
    }

    /**
     * BO-03-02-37: the administered type of prepaid card rules the payment —
     * a type refusing payment refuses the card; a NEXO type goes to the hub
     * only where one is active; a LOCAL type never does.
     */
    @Test
    void thePrepaidCardTypeRulesThePayment() {
        state.ticket.totalAmount = new BigDecimal("20.00");
        when(giftCardIssuerClient.query(any())).thenReturn(
                new com.intermarche.pos.ui.payment.GiftCardIssuerClient.IssuerVerdict(false, null));
        when(nexoHubClient.balance("6299")).thenReturn(NexoHubClient.HubResult.failure("Refused", "NON"));
        try (MockedStatic<com.intermarche.pos.domain.payment.PrepaidCardType> types =
                     mockStatic(com.intermarche.pos.domain.payment.PrepaidCardType.class)) {
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("1111"))
                    .thenReturn(prepaid("NEXO", false));
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("6299"))
                    .thenReturn(prepaid("NEXO", true));
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("2971"))
                    .thenReturn(prepaid("LOCAL", true));
            service.processSupplierGiftCard(state, "1111", BigDecimal.TEN);
            assertEquals("CARTE Cadeau NON ACCEPTEE EN PAIEMENT", state.ticket.transientError);
            verify(giftCardIssuerClient, never()).query("1111");
            service.processSupplierGiftCard(state, "6299", BigDecimal.TEN);
            verify(giftCardIssuerClient).query("6299");
            verify(nexoHubClient, never()).balance(any());
            when(nexoHubClient.isConfigured()).thenReturn(true);
            service.processSupplierGiftCard(state, "6299", BigDecimal.TEN);
            verify(nexoHubClient).balance("6299");
            service.processSupplierGiftCard(state, "2971", BigDecimal.TEN);
            verify(giftCardIssuerClient).query("2971");
            verify(nexoHubClient, never()).balance("2971");
        }
    }

    /**
     * Arms an authorized cheque tender read by no reader, so a guaranteed
     * cheque settles at once.
     *
     * @return the tender
     */
    private com.intermarche.pos.domain.payment.TenderDefinition guaranteedCheque() {
        com.intermarche.pos.domain.payment.TenderDefinition cheque = lotTender("CHEQUE", null);
        cheque.chequeAuthorization = true;
        cheque.micrReading = false;
        when(tenderRulesService.tender("CHEQUE")).thenReturn(cheque);
        when(chequeGuaranteeClient.isConfigured()).thenReturn(true);
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        return cheque;
    }

    /**
     * BO-03-02-24: a cheque the guarantee service approves is taken without a
     * supervisor, its authorization and provider recorded on the settlement.
     */
    @Test
    void aGuaranteedChequeIsTakenWithItsAuthorization() {
        guaranteedCheque();
        when(chequeGuaranteeClient.authorize("3", new BigDecimal("20"), "123")).thenReturn(
                new ChequeGuaranteeClient.Verdict(ChequeGuaranteeClient.Status.APPROVED, "GAR000001", "OK", "SIM"));
        service.processCheque(state, new BigDecimal("20"), "123");
        assertNull(state.payment.tenderHeldMethod);
        assertEquals(1, state.payment.payments.size());
        assertEquals("GAR000001", state.payment.payments.get(0).chequeGuaranteeAuthorization);
        assertEquals("SIM", state.payment.payments.get(0).chequeGuaranteeProvider);
        assertNull(state.payment.pendingChequeGuarantee);
        assertNull(state.payment.pendingChequeGuaranteeProvider);
    }

    /**
     * BO-03-02-24: a cheque the guarantee service refuses is refused, with the
     * provider's wording or without any.
     */
    @Test
    void aChequeRefusedByTheGuaranteeIsRefused() {
        guaranteedCheque();
        when(chequeGuaranteeClient.authorize(any(), any(), any())).thenReturn(
                new ChequeGuaranteeClient.Verdict(ChequeGuaranteeClient.Status.REFUSED, null, "CHEQUE EN OPPOSITION", "SIM"),
                new ChequeGuaranteeClient.Verdict(ChequeGuaranteeClient.Status.REFUSED, null, null, "SIM"));
        service.processCheque(state, new BigDecimal("20"), "000");
        assertEquals("CHEQUE REFUSE PAR LA GARANTIE : CHEQUE EN OPPOSITION", state.ticket.transientError);
        service.processCheque(state, new BigDecimal("20"), null);
        assertEquals("CHEQUE REFUSE PAR LA GARANTIE : ", state.ticket.transientError);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.payment.tenderHeldMethod);
    }

    /**
     * BO-03-02-24: a guarantee service that cannot answer hands the cheque to
     * the supervisor, the hold saying why.
     */
    @Test
    void anUnavailableGuaranteeFallsBackOnTheSupervisor() {
        guaranteedCheque();
        when(chequeGuaranteeClient.authorize(any(), any(), any())).thenReturn(
                new ChequeGuaranteeClient.Verdict(ChequeGuaranteeClient.Status.UNAVAILABLE, null, null, "SIM"));
        service.processCheque(state, new BigDecimal("20"));
        assertEquals("CHEQUE", state.payment.tenderHeldMethod);
        assertTrue(state.payment.chequeHeldBeforeReading);
        assertEquals("GARANTIE CHEQUE INDISPONIBLE - AUTORISATION SUPERVISEUR REQUISE",
                state.payment.tenderHeldMessage);
    }

    // --------------------------------------------------
    // Settlement guard of the closing (lot 19 recette RL, defect 1)
    // --------------------------------------------------

    /**
     * A direct close of a sale with an amount still due is refused: nothing is
     * validated, the ticket stays open in memory and the operator reads what
     * remains to be paid.
     */
    @Test
    void finalizeTransactionRefusesASaleWithAnAmountStillDue() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 9L;
        state.payment.paymentInProgress = true;
        state.payment.addPayment("CASH", new BigDecimal("4.00"));
        assertFalse(service.finalizeTransaction(state));
        verify(ticketPersistenceService, never()).validateTicket(any());
        assertEquals("VENTE NON SOLDEE - RESTE A PAYER 6,00 €", state.ticket.transientError);
        assertEquals(Long.valueOf(9L), state.payment.ticketDbId);
        assertTrue(state.payment.paymentInProgress);
        assertNull(state.lastClosedTicketId);
    }

    /**
     * A net refund not yet paid out is refused too, the message saying how much
     * remains to give back.
     */
    @Test
    void finalizeTransactionRefusesANetRefundNotPaidOut() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 9L;
        assertFalse(service.finalizeTransaction(state));
        verify(ticketPersistenceService, never()).validateTicket(any());
        assertEquals("REMBOURSEMENT NON EFFECTUE - RESTE A RENDRE 5,00 €", state.ticket.transientError);
    }

    /**
     * An unsettled training sale is refused as well: no training record is
     * written for a sale that was never paid.
     */
    @Test
    void finalizeTransactionRefusesAnUnsettledTrainingSale() {
        service.trainingTicketService = mock(TrainingTicketService.class);
        state.trainingMode = true;
        state.ticket.totalAmount = new BigDecimal("3.00");
        assertFalse(service.finalizeTransaction(state));
        verify(service.trainingTicketService, never()).record(any());
    }

    /**
     * A fully paid sale closes and the closing reports it.
     */
    @Test
    void finalizeTransactionClosesAFullyPaidSale() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 9L;
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            assertTrue(service.finalizeTransaction(state));
        }
        verify(ticketPersistenceService).validateTicket(9L);
        assertEquals(Long.valueOf(9L), state.lastClosedTicketId);
    }

    /**
     * A zero ticket is legitimately settled from the start and closes.
     */
    @Test
    void finalizeTransactionClosesAZeroTicket() {
        state.ticket.totalAmount = BigDecimal.ZERO;
        state.payment.ticketDbId = 9L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            assertTrue(service.finalizeTransaction(state));
        }
        verify(ticketPersistenceService).validateTicket(9L);
    }

    /**
     * Every arm of the settlement rule: a positive total covered or overpaid is
     * settled, one still due is not; a net refund is settled only once paid
     * out exactly.
     */
    @Test
    void isSettledFollowsTheSignOfTheTotal() {
        state.ticket.totalAmount = new BigDecimal("10.00");
        assertFalse(service.isSettled(state));
        state.payment.addPayment("CASH", new BigDecimal("10.00"));
        assertTrue(service.isSettled(state));
        state.payment.addPayment("CASH", new BigDecimal("2.00"));
        assertTrue(service.isSettled(state));
        PosState refund = new PosState();
        refund.ticket.totalAmount = new BigDecimal("-5.00");
        assertFalse(service.isSettled(refund));
        refund.payment.addPayment("CASH", new BigDecimal("-5.00"));
        assertTrue(service.isSettled(refund));
        refund.payment.addPayment("CASH", new BigDecimal("-1.00"));
        assertFalse(service.isSettled(refund));
    }

    /**
     * BO-11-04-05: where the store asks for a synchronous VT call, the closed
     * event goes out NOW through the synchronous call rather than the queue;
     * BO-11-04-07: the balance is refreshed before the paper is printed.
     */
    @Test
    void finalizeTransactionMakesTheSynchronousVtCall() {
        state.payment.ticketDbId = 9L;
        state.fidelity.assignCard("2990000000019");
        com.intermarche.pos.domain.sale.Ticket closed = new com.intermarche.pos.domain.sale.Ticket();
        closed.ticketNumber = "C04-000003";
        when(posSettingsService.fidelityVtSync()).thenReturn(true);
        when(fidelityService.buildTicketClosedPayload(eq(state), any(), any(), any(), any()))
                .thenReturn("{\"vt\":1}");
        try (org.mockito.MockedStatic<io.quarkus.hibernate.orm.panache.PanacheEntityBase> panache =
                     org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            stubNoGiftCards(panache, 9L);
            panache.when(() -> com.intermarche.pos.domain.sale.Ticket.findById(9L)).thenReturn(closed);
            service.finalizeTransaction(state);
        }
        verify(fidEventOutboxService).sendNow(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_CLOSED,
                "{\"vt\":1}");
        verify(fidEventOutboxService, never()).enqueue(any(), any());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fidEventOutboxService, fidelityService);
        order.verify(fidEventOutboxService).sendNow(any(), any());
        order.verify(fidelityService).refreshBeforePrint(state);
    }

    /**
     * BO-11-03-04: each prepaid-card payment the NEXO server validated is
     * acknowledged once the sale is closed — a payment the hub never saw is
     * not, a refused acknowledgement is only logged; nothing is acknowledged
     * when the store does not ask for it or in training.
     */
    @Test
    void theValidatedPrepaidPaymentsAreAcknowledged() {
        NexoHubClient hub = mock(NexoHubClient.class);
        service.nexoHubClient = hub;
        PaymentState.PaymentEntry gift = new PaymentState.PaymentEntry("GIFTCARD", new BigDecimal("15.00"));
        gift.hubReference = "H1";
        PaymentState.PaymentEntry refusedGift = new PaymentState.PaymentEntry("GIFTCARD", new BigDecimal("5.00"));
        refusedGift.hubReference = "H2";
        state.payment.payments.add(gift);
        state.payment.payments.add(refusedGift);
        state.payment.payments.add(new PaymentState.PaymentEntry("CASH", new BigDecimal("1.00")));
        service.acknowledgeHubPayments(state, 9L);
        verify(hub, never()).acknowledge(any(), any(), any());
        when(posSettingsService.nexoPaymentAcknowledgement()).thenReturn(true);
        state.trainingMode = true;
        service.acknowledgeHubPayments(state, 9L);
        verify(hub, never()).acknowledge(any(), any(), any());
        state.trainingMode = false;
        when(hub.acknowledge("9-ACK", "H1", new BigDecimal("15.00")))
                .thenReturn(new NexoHubClient.HubResult(true, null, null, "T1", null, null, null, null));
        when(hub.acknowledge("9-ACK", "H2", new BigDecimal("5.00")))
                .thenReturn(NexoHubClient.HubResult.failure("Refusal", null));
        service.acknowledgeHubPayments(state, 9L);
        verify(hub).acknowledge("9-ACK", "H1", new BigDecimal("15.00"));
        verify(hub).acknowledge("9-ACK", "H2", new BigDecimal("5.00"));
    }

    // --------------------------------------------------
    // lot 21: the terminal decision serialized with the payment screen
    // --------------------------------------------------

    /** The bridge status of a payment the terminal accepted at once. */
    private static final String INSTANT_DONE = "state=DONE\napproved=true\nresult=00\nanswer={RS}00";

    /**
     * Waits, bounded, until a thread is blocked on a monitor.
     *
     * @param thread the thread expected to block
     */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    /**
     * Waits, bounded, until a condition holds.
     *
     * @param condition the condition awaited
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * Runs a gesture on another thread while this thread holds the register's
     * lock, and proves it waits for the lock: blocked and without effect while
     * the lock is held, applied once it is released.
     *
     * @param gesture the gesture run on the other thread
     * @param applied tells whether the gesture's effect is visible
     * @throws InterruptedException when the joining thread is interrupted
     */
    private void assertWaitsForTheRegisterLock(Runnable gesture, java.util.function.BooleanSupplier applied)
            throws InterruptedException {
        Thread other = new Thread(gesture, "terminal-decision");
        synchronized (state) {
            other.start();
            awaitBlocked(other);
            assertEquals(Thread.State.BLOCKED, other.getState());
            assertFalse(applied.getAsBoolean());
        }
        other.join(5000);
        assertFalse(other.isAlive());
        assertTrue(applied.getAsBoolean());
    }

    /**
     * Lot 21: the accept leg waits for the register's lock before registering
     * and persisting the card, then does it once.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void acceptLegWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        assertWaitsForTheRegisterLock(() -> cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00"))),
                () -> state.payment.payments.size() == 1);
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(3L, state.payment.payments.get(0));
        assertNull(state.payment.pendingCardCallback);
    }

    /**
     * Lot 21: the refuse leg waits for the register's lock before dropping the
     * pending card.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void refuseLegWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        assertWaitsForTheRegisterLock(() -> cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00"))),
                () -> state.payment.pendingCardAmount == null);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
    }

    /**
     * Lot 21: the error leg waits for the register's lock before dropping the
     * pending card with the terminal's message.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void errorLegWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        assertWaitsForTheRegisterLock(() -> cb.onError("TERMINAL INJOIGNABLE"),
                () -> state.payment.pendingCardAmount == null);
        assertEquals("TERMINAL INJOIGNABLE", state.ticket.transientError);
    }

    /**
     * Lot 21: the display leg waits for the register's lock before relaying
     * the terminal lines.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void displayLegWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        assertWaitsForTheRegisterLock(() -> cb.onDisplay("INSERER CARTE", "PIN"),
                () -> "INSERER CARTE".equals(state.payment.terminalLine1));
        assertEquals("PIN", state.payment.terminalLine2);
    }

    /**
     * Lot 21: starting a card waits for the register's lock — the in-flight
     * check and the parking of the pending card are one step.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void processCardWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("20.00");
        assertWaitsForTheRegisterLock(() -> service.processCard(state, new BigDecimal("15.00")),
                () -> state.payment.pendingCardAmount != null);
        verify(terminal).requestDebit(eq(new BigDecimal("15.00")), any());
        assertNotNull(state.payment.pendingCardCallback);
    }

    /**
     * Lot 21: cancelling the pending card waits for the register's lock and
     * forgets the transaction, so its late decision is ignored.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void cancelPendingCardWaitsForTheLockAndForgetsTheTransaction() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        assertWaitsForTheRegisterLock(() -> service.cancelPendingCard(state),
                () -> state.payment.pendingCardAmount == null);
        assertNull(state.payment.pendingCardCallback);
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertTrue(state.payment.payments.isEmpty());
        verify(ticketPersistenceService, never()).addPaymentToTicket(any(), any());
    }

    /**
     * Lot 21: a transaction is settled exactly once — a second accept, a late
     * refusal and a late error of the same callback are all ignored.
     */
    @Test
    void aSettledCardIgnoresEveryLaterDecision() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        cb.onRefused(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        cb.onError("LATE");
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, new BigDecimal("15.00").compareTo(state.payment.paidAmount));
        assertNull(state.ticket.transientError);
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(any(), any());
        verify(hardwareService, never()).displayMessage("PAIEMENT REFUSE");
    }

    /**
     * Lot 21: the decision of a cancelled transaction never settles the card
     * started after it — neither its accept nor its display lines nor its
     * error — while the newer transaction's own decision does, once
     * ({@code pendingCardCallback != callback} true leg of the claim).
     */
    @Test
    void aStaleDecisionNeverSettlesTheNewerCard() {
        TerminalTransactionCallback older = captureCallback("15.00");
        service.cancelPendingCard(state);
        service.processCard(state, new BigDecimal("12.00"));
        ArgumentCaptor<TerminalTransactionCallback> captor = ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal, times(2)).requestDebit(any(), captor.capture());
        TerminalTransactionCallback newer = captor.getAllValues().get(1);
        older.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        older.onDisplay("STALE", "STALE");
        older.onError("STALE");
        assertTrue(state.payment.payments.isEmpty());
        assertEquals("", state.payment.terminalLine1);
        assertNull(state.ticket.transientError);
        assertEquals(new BigDecimal("12.00"), state.payment.pendingCardAmount);
        newer.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("12.00")));
        assertEquals(1, state.payment.payments.size());
        assertEquals(0, new BigDecimal("12.00").compareTo(state.payment.paidAmount));
    }

    /**
     * Lot 21: cancelling the payments forgets the pending card transaction.
     */
    @Test
    void cancelPaymentsForgetsThePendingCardTransaction() {
        TerminalTransactionCallback cb = captureCallback("15.00");
        service.cancelPayments(state);
        assertNull(state.payment.pendingCardCallback);
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00")));
        assertTrue(state.payment.payments.isEmpty());
    }

    /**
     * Lot 21, the race found in dev: the terminal answers while the payment
     * screen reloads. The decision arrives on another thread in the middle of
     * the draft synchronisation of {@code initPayment}; it waits for the
     * reload to finish and only then persists its payment, so the two never
     * write the draft at the same time.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void theDecisionWaitsForThePayScreenReload() throws InterruptedException {
        TerminalTransactionCallback cb = captureCallback("15.00");
        Thread[] decision = new Thread[1];
        when(ticketPersistenceService.syncDraft(state)).thenAnswer(invocation -> {
            decision[0] = new Thread(() -> cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("15.00"))));
            decision[0].start();
            awaitBlocked(decision[0]);
            assertEquals(Thread.State.BLOCKED, decision[0].getState());
            assertTrue(state.payment.payments.isEmpty());
            return 3L;
        });
        service.initPayment(state);
        decision[0].join(5000);
        assertEquals(1, state.payment.payments.size());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(ticketPersistenceService);
        order.verify(ticketPersistenceService).syncDraft(state);
        order.verify(ticketPersistenceService).markPaymentStarted(3L);
        order.verify(ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
    }

    /**
     * Lot 21: the combined card and meal-voucher debit's decision waits for
     * the register's lock like the plain debit's.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void combinedDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        service.processCard(state, BigDecimal.ZERO, true);
        ArgumentCaptor<TerminalTransactionCallback> captor = ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestCombinedDebit(eq(new BigDecimal("20.00")), any(), captor.capture());
        assertWaitsForTheRegisterLock(() -> captor.getValue().onAccepted(TerminalOutcome.ofAmount(new BigDecimal("20.00"))),
                () -> state.payment.transactionComplete);
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(any(), any());
    }

    /**
     * Captures the callback of a CARD net refund of five euros.
     *
     * @return the captured callback
     */
    private TerminalTransactionCallback captureNetRefundCallback() {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 3L;
        assertTrue(service.processNetRefund(state, "CARD"));
        ArgumentCaptor<TerminalTransactionCallback> captor = ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        verify(terminal).requestCredit(eq(new BigDecimal("5.00")), captor.capture());
        return captor.getValue();
    }

    /**
     * Lot 21: the net refund's accept leg waits for the register's lock, then
     * books the refund once; a later error of the same callback is ignored.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void netRefundAcceptWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureNetRefundCallback();
        assertWaitsForTheRegisterLock(() -> cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("5.00"))),
                () -> state.payment.payments.size() == 1);
        cb.onError("LATE");
        assertNull(state.ticket.transientError);
        assertTrue(state.payment.transactionComplete);
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(any(), any());
    }

    /**
     * Lot 21: the net refund's refuse leg waits for the register's lock.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void netRefundRefusalWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureNetRefundCallback();
        assertWaitsForTheRegisterLock(() -> cb.onRefused(null), () -> state.payment.pendingCardAmount == null);
        assertEquals("PAIEMENT REFUSÉ PAR LE TPE", state.ticket.transientError);
    }

    /**
     * Lot 21: the net refund's error leg waits for the register's lock.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void netRefundErrorWaitsForTheRegisterLock() throws InterruptedException {
        TerminalTransactionCallback cb = captureNetRefundCallback();
        assertWaitsForTheRegisterLock(() -> cb.onError("INJOIGNABLE"), () -> state.payment.pendingCardAmount == null);
        assertEquals("INJOIGNABLE", state.ticket.transientError);
    }

    /**
     * Lot 21: a net refund the cashier cancelled is not booked by its late
     * decision, nor told refused by its late refusal.
     */
    @Test
    void aCancelledNetRefundIgnoresItsLateDecision() {
        TerminalTransactionCallback cb = captureNetRefundCallback();
        service.cancelPendingCard(state);
        cb.onAccepted(TerminalOutcome.ofAmount(new BigDecimal("5.00")));
        cb.onRefused(null);
        assertTrue(state.payment.payments.isEmpty());
        assertNull(state.ticket.transientError);
        assertFalse(state.payment.transactionComplete);
    }

    /**
     * Builds the register's real terminal chain — the degraded-mode gate in
     * front of the hardware bridge — over a bridge whose terminal decides at
     * the first reading, and records the follower thread that reads it.
     *
     * @param hardware the mocked bridge
     * @param follower where the follower thread is recorded
     */
    private void bridgeAnsweringAtOnce(com.intermarche.pos.ui.hardware.HardwareClient hardware, Thread[] follower) {
        when(hardware.getPaymentStatus()).thenAnswer(invocation -> {
            follower[0] = Thread.currentThread();
            return INSTANT_DONE;
        });
        com.intermarche.pos.ui.hardware.terminal.HardwareBridgeTerminalClient bridge =
                new com.intermarche.pos.ui.hardware.terminal.HardwareBridgeTerminalClient(hardware, 1, 10000,
                        TestMessages.french());
        service.terminal = new com.intermarche.pos.ui.hardware.terminal.DegradedModePaymentTerminalClient(
                bridge, mock(PaymentTerminalClient.class), posSettingsService, state);
    }

    /**
     * Starts a bridge payment while holding the register's lock, as the
     * request thread does, and proves the decision the bridge's follower
     * thread reads at once waits for the lock, then is applied exactly once.
     *
     * @param start the gesture starting the payment
     * @param applied tells whether the decision was applied
     * @return the mocked bridge, for the request it was sent
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private com.intermarche.pos.ui.hardware.HardwareClient assertBridgeDecisionWaits(Runnable start,
            java.util.function.BooleanSupplier applied) throws InterruptedException {
        com.intermarche.pos.ui.hardware.HardwareClient hardware = mock(com.intermarche.pos.ui.hardware.HardwareClient.class);
        Thread[] follower = new Thread[1];
        bridgeAnsweringAtOnce(hardware, follower);
        synchronized (state) {
            start.run();
            verify(hardware, org.mockito.Mockito.timeout(5000).atLeastOnce()).getPaymentStatus();
            awaitTrue(() -> follower[0] != null);
            awaitBlocked(follower[0]);
            assertEquals(Thread.State.BLOCKED, follower[0].getState());
            assertFalse(applied.getAsBoolean());
        }
        awaitTrue(applied);
        assertTrue(applied.getAsBoolean());
        verify(ticketPersistenceService, times(1)).addPaymentToTicket(any(), any());
        assertEquals(1, state.payment.payments.size());
        assertNull(state.payment.pendingCardCallback);
        return hardware;
    }

    /**
     * Lot 21, debit through the bridge: the decision read at once by the
     * follower thread waits for the payment screen's lock.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeDebitDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        verify(assertBridgeDecisionWaits(() -> service.processCard(state, BigDecimal.ZERO),
                () -> state.payment.transactionComplete)).startPayment("2000");
    }

    /**
     * Lot 21, combined debit through the bridge: same serialization.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeCombinedDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        verify(assertBridgeDecisionWaits(() -> service.processCard(state, BigDecimal.ZERO, true),
                () -> state.payment.transactionComplete)).startCombinedPayment("2000;0");
    }

    /**
     * Lot 21, forced degraded debit through the bridge (LC-07-08-02): same
     * serialization.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeForcedDegradedDebitDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("20.00");
        state.payment.ticketDbId = 3L;
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().plusMinutes(10);
        verify(assertBridgeDecisionWaits(() -> service.processCard(state, BigDecimal.ZERO),
                () -> state.payment.transactionComplete)).startDegradedPayment("2000");
    }

    /**
     * Lot 21, net refund credited through the bridge: same serialization.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeNetRefundDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 3L;
        verify(assertBridgeDecisionWaits(() -> service.processNetRefund(state, "CARD"),
                () -> state.payment.transactionComplete)).startCredit("500");
    }

    /**
     * Lot 21, forced degraded credit through the bridge (LC-07-08-02), the net
     * refund of a forced register: same serialization.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeForcedDegradedCreditDecisionWaitsForTheRegisterLock() throws InterruptedException {
        state.ticket.totalAmount = new BigDecimal("-5.00");
        state.payment.ticketDbId = 3L;
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().plusMinutes(10);
        verify(assertBridgeDecisionWaits(() -> service.processNetRefund(state, "CARD"),
                () -> state.payment.transactionComplete)).startDegradedCredit("500");
    }
}
