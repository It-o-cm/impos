package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.customer.QrCodeService;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackupPaymentService}.
 * <p>
 * The register and the handheld never talk; they meet on two signed QR squares, and
 * this service is the suspicion the register owes in return. Every leg is covered
 * separately: opening the panel with nothing left to pay against something to pay,
 * and — on the way to a real request — the three ways a transaction number is
 * resolved (a draft, a training placeholder, a draft whose number is missing) and
 * the three shapes of a point-of-sale (no shop, a shop, a shop without a code). A
 * scanned answer is refused when there is no request, when the payload does not
 * decode and when it decodes to another sale, and accepted only when it matches; the
 * card slip it carries is printed when readable, dropped when it is mojibake, skipped
 * when blank. The manual path is checked on its own guards — no request, an unusable
 * amount, and each leg of the endorsement rule (not required, an already-supervising
 * operator, a passing credential, a refused one). The scheme table is resolved on a
 * missing id, a blank id, a known id, an unknown id, a null table, a blank table and
 * a table with malformed pairs.
 */
class BackupPaymentServiceTest {

    /** The shared secret used to sign every payload in these tests. */
    private static final String SECRET = "secret-magasin";

    /** The sale the request and the responses are matched on. */
    private static final String TRANSACTION = "T-0042";

    /** The point-of-sale number both payloads carry. */
    private static final String PDV = "0001";

    /** The register both payloads carry. */
    private static final String TERMINAL = "CAISSE-01";

    /** A fixed emission instant, so the request is reproducible. */
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 9, 10, 14, 30, 0);

    /**
     * Builds a service whose six collaborators are all mocks and whose configured
     * secret is present.
     *
     * @return the wired service
     */
    private BackupPaymentService newService() {
        BackupPaymentService service = new BackupPaymentService();
        service.paymentService = mock(PaymentService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.endorsementService = mock(EndorsementService.class);
        service.qrCodeService = mock(QrCodeService.class);
        service.hardwareService = mock(HardwareService.class);
        service.secret = Optional.of(SECRET);
        return service;
    }

    /**
     * Builds a POS state whose ticket total leaves the given amount to pay.
     *
     * @param totalToPay the ticket total, which with no payment is the remaining due
     * @return the state under test
     */
    private PosState stateWithRemaining(String totalToPay) {
        PosState state = new PosState();
        state.ticket.totalAmount = new BigDecimal(totalToPay);
        return state;
    }

    /**
     * Builds the request this till emitted, matching the responses built below.
     *
     * @return the pending request
     */
    private BackupPaymentTicket.Request request() {
        return new BackupPaymentTicket.Request("D", 2462L, 1200L, TRANSACTION, PDV, TERMINAL,
                STAMP);
    }

    /**
     * Builds a signed response payload with the given fields, signed with {@link #SECRET}.
     *
     * @param accepted the accepted amount in cents, as text
     * @param methodId the scheme identifier
     * @param transaction the transaction number the answer claims
     * @param receipt the base64 card slip, possibly empty
     * @return the full payload the register reads back
     */
    private String response(String accepted, String methodId, String transaction, String receipt) {
        String body = String.join("|", "A1", accepted, methodId, transaction, PDV, TERMINAL,
                receipt);
        return body + "|" + BackupPaymentTicket.sign(body, SECRET);
    }

    // --------------------------------------------------
    // openPanel
    // --------------------------------------------------

    /**
     * A panel opened with nothing left to pay refuses at the boundary: the remaining
     * signum is zero, the guard's true arm posts "RIEN A REGLER" and emits no request.
     */
    @Test
    void openPanelWithNothingDueRefuses() {
        BackupPaymentService service = newService();
        PosState state = stateWithRemaining("0.00");
        service.openPanel(state);
        assertEquals("RIEN A REGLER", state.payment.backupError);
        assertNull(state.payment.backupRequest);
        assertTrue(state.payment.backupPanelOpen);
    }

    /**
     * A panel opened with money due and no draft ticket takes the false arm of the
     * remaining guard, the training-placeholder arm of the transaction number (no
     * draft id), the zero arm of the meal-eligible base (none reported) and the
     * empty-shop arm of the point of sale (no shop), emitting a rendered request.
     */
    @Test
    void openPanelEmitsTrainingRequestWithoutDraftOrShop() {
        BackupPaymentService service = newService();
        when(service.ticketNumberService.getTerminalId()).thenReturn("CAISSE-01");
        when(service.qrCodeService.toSvg(anyString())).thenReturn("<svg/>");
        PosState state = stateWithRemaining("24.62");
        state.payment.ticketDbId = null;
        state.payment.valuationMealEligible = null;
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Store::findAll).thenReturn(query);
            service.openPanel(state);
        }
        assertNull(state.payment.backupError);
        assertNotNull(state.payment.backupRequest);
        assertEquals("FORMATION-CAISSE-01", state.payment.backupRequest.transactionNumber());
        assertEquals(0L, state.payment.backupRequest.mealEligibleCents());
        assertEquals("", state.payment.backupRequest.pdv());
        assertEquals("<svg/>", state.payment.backupRequestSvg);
    }

    /**
     * A draft with a number, a reported meal-eligible base and a coded shop take the
     * non-null arm of every helper: the request carries the draft number, the meal
     * cents and the shop code.
     */
    @Test
    void openPanelEmitsRequestFromDraftMealAndShop() {
        BackupPaymentService service = newService();
        when(service.ticketNumberService.getTerminalId()).thenReturn("CAISSE-01");
        when(service.qrCodeService.toSvg(anyString())).thenReturn("<svg/>");
        PosState state = stateWithRemaining("24.62");
        state.payment.ticketDbId = 5L;
        state.payment.valuationMealEligible = new BigDecimal("12.00");
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "T-1";
        Store store = new Store();
        store.code = PDV;
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            panache.when(Store::findAll).thenReturn(query);
            service.openPanel(state);
        }
        assertNull(state.payment.backupError);
        assertEquals("T-1", state.payment.backupRequest.transactionNumber());
        assertEquals(1200L, state.payment.backupRequest.mealEligibleCents());
        assertEquals(PDV, state.payment.backupRequest.pdv());
    }

    /**
     * A draft id whose ticket cannot be found takes the null-ticket leg of the
     * transaction-number guard and falls back to the training placeholder.
     */
    @Test
    void openPanelFallsBackWhenDraftTicketMissing() {
        BackupPaymentService service = newService();
        when(service.ticketNumberService.getTerminalId()).thenReturn("CAISSE-01");
        when(service.qrCodeService.toSvg(anyString())).thenReturn("<svg/>");
        PosState state = stateWithRemaining("24.62");
        state.payment.ticketDbId = 9L;
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(9L)).thenReturn(null);
            panache.when(Store::findAll).thenReturn(query);
            service.openPanel(state);
        }
        assertEquals("FORMATION-CAISSE-01", state.payment.backupRequest.transactionNumber());
    }

    /**
     * A draft found but carrying no number takes the second leg of the compound
     * guard (ticket non-null, number null) and also falls back to the placeholder.
     */
    @Test
    void openPanelFallsBackWhenDraftNumberNull() {
        BackupPaymentService service = newService();
        when(service.ticketNumberService.getTerminalId()).thenReturn("CAISSE-01");
        when(service.qrCodeService.toSvg(anyString())).thenReturn("<svg/>");
        PosState state = stateWithRemaining("24.62");
        state.payment.ticketDbId = 7L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = null;
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            panache.when(Store::findAll).thenReturn(query);
            service.openPanel(state);
        }
        assertEquals("FORMATION-CAISSE-01", state.payment.backupRequest.transactionNumber());
    }

    /**
     * A shop present but with no code takes the second leg of the point-of-sale
     * guard (store non-null, code null) and yields an empty pdv.
     */
    @Test
    void openPanelYieldsEmptyPdvWhenShopHasNoCode() {
        BackupPaymentService service = newService();
        when(service.ticketNumberService.getTerminalId()).thenReturn("CAISSE-01");
        when(service.qrCodeService.toSvg(anyString())).thenReturn("<svg/>");
        PosState state = stateWithRemaining("24.62");
        state.payment.ticketDbId = null;
        Store store = new Store();
        store.code = null;
        PanacheQuery<Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(store);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(Store::findAll).thenReturn(query);
            service.openPanel(state);
        }
        assertEquals("", state.payment.backupRequest.pdv());
    }

    // --------------------------------------------------
    // closePanel
    // --------------------------------------------------

    /**
     * Closing the panel forgets the pending request and bumps the version so the
     * screen refreshes.
     */
    @Test
    void closePanelForgetsTheRequest() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupPanelOpen = true;
        state.payment.backupRequest = request();
        long before = state.version;
        service.closePanel(state);
        assertFalse(state.payment.backupPanelOpen);
        assertNull(state.payment.backupRequest);
        assertEquals(before + 1, state.version);
    }

    // --------------------------------------------------
    // validateScanned
    // --------------------------------------------------

    /**
     * A scan with no pending request is refused with the no-request message (null arm
     * of the request guard) and registers nothing.
     */
    @Test
    void validateScannedWithoutRequestIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = null;
        assertFalse(service.validateScanned(state, response("2462", "20", TRANSACTION, "")));
        assertEquals(BackupPaymentService.NO_REQUEST, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * A payload that does not decode is refused with the single refusal message (the
     * null-response leg of the compound guard).
     */
    @Test
    void validateScannedUndecodableIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertFalse(service.validateScanned(state, "not-a-valid-payload"));
        assertEquals(BackupPaymentService.REFUSED, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * A well-signed answer for another sale decodes but does not match the request
     * (the non-null-response, non-matching leg) and is refused all the same.
     */
    @Test
    void validateScannedForAnotherSaleIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertFalse(service.validateScanned(state, response("2462", "20", "T-9999", "")));
        assertEquals(BackupPaymentService.REFUSED, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * The matching answer (the false arm of the guard) registers the settlement in
     * euros, resolves the scheme, prints the readable card slip and cuts the paper.
     */
    @Test
    void validateScannedMatchingRegistersAndPrints() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("20=Carte");
        PosState state = new PosState();
        state.payment.backupRequest = request();
        String slip = Base64.getEncoder()
                .encodeToString("TICKET CB".getBytes(StandardCharsets.UTF_8));
        assertTrue(service.validateScanned(state, response("2462", "20", TRANSACTION, slip)));
        verify(service.paymentService).processBackupPayment(eq(state),
                eq(new BigDecimal("24.62")), eq("Carte"), eq(TRANSACTION), eq(false));
        verify(service.hardwareService).printReceipt("TICKET CB");
        verify(service.hardwareService).cutPaper();
    }

    /**
     * A matching answer whose slip is blank registers the settlement but prints
     * nothing (the blank arm of the print guard drops the slip).
     */
    @Test
    void validateScannedWithBlankSlipRegistersWithoutPrinting() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("20=Carte");
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertTrue(service.validateScanned(state, response("2462", "20", TRANSACTION, "")));
        verify(service.paymentService).processBackupPayment(eq(state),
                eq(new BigDecimal("24.62")), eq("Carte"), eq(TRANSACTION), eq(false));
        verify(service.hardwareService, never()).printReceipt(anyString());
        verify(service.hardwareService, never()).cutPaper();
    }

    /**
     * A matching answer whose slip is present but not decodable base64 registers the
     * settlement and drops the slip rather than printing mojibake (the catch arm of
     * the print path); the settlement stays registered.
     */
    @Test
    void validateScannedWithUnreadableSlipRegistersWithoutPrinting() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("20=Carte");
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertTrue(service.validateScanned(state, response("2462", "20", TRANSACTION, "@@@")));
        verify(service.paymentService).processBackupPayment(eq(state),
                eq(new BigDecimal("24.62")), eq("Carte"), eq(TRANSACTION), eq(false));
        verify(service.hardwareService, never()).printReceipt(anyString());
        verify(service.hardwareService, never()).cutPaper();
    }

    // --------------------------------------------------
    // validateManually
    // --------------------------------------------------

    /**
     * A manual validation with no pending request is refused (null arm of the request
     * guard) and registers nothing.
     */
    @Test
    void validateManuallyWithoutRequestIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = null;
        assertFalse(service.validateManually(state, new BigDecimal("10.00"), "chef", "mdp"));
        assertEquals(BackupPaymentService.NO_REQUEST, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * A null keyed amount is refused with the bad-amount message (the null leg of the
     * amount guard).
     */
    @Test
    void validateManuallyWithNullAmountIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertFalse(service.validateManually(state, null, "chef", "mdp"));
        assertEquals(BackupPaymentService.BAD_AMOUNT, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * A keyed amount of zero is refused at the boundary (the non-null, signum-zero
     * leg of the amount guard).
     */
    @Test
    void validateManuallyWithZeroAmountIsRefused() {
        BackupPaymentService service = newService();
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertFalse(service.validateManually(state, BigDecimal.ZERO, "chef", "mdp"));
        assertEquals(BackupPaymentService.BAD_AMOUNT, state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    /**
     * A shop requiring no endorsement lets the operator settle manually alone: the
     * first leg of the endorsement rule is false, no credential is checked, and the
     * settlement is registered with the generic label and the manual flag set.
     */
    @Test
    void validateManuallyWithoutEndorsementRegisters() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupManualEndorsement()).thenReturn(false);
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertTrue(service.validateManually(state, new BigDecimal("10.00"), "chef", "mdp"));
        verify(service.paymentService).processBackupPayment(eq(state), eq(new BigDecimal("10.00")),
                eq(BackupPaymentService.GENERIC_LABEL), eq(TRANSACTION), eq(true));
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A logged operator who already supervises settles manually without a second
     * credential (the middle leg short-circuits the credential check).
     */
    @Test
    void validateManuallyWithConnectedSupervisorRegisters() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupManualEndorsement()).thenReturn(true);
        PosState state = new PosState();
        state.payment.backupRequest = request();
        when(service.endorsementService.operatorIsSupervisor(state)).thenReturn(true);
        assertTrue(service.validateManually(state, new BigDecimal("10.00"), "chef", "mdp"));
        verify(service.paymentService).processBackupPayment(eq(state), eq(new BigDecimal("10.00")),
                eq(BackupPaymentService.GENERIC_LABEL), eq(TRANSACTION), eq(true));
        verify(service.endorsementService, never()).authorize(any(), any(), any());
    }

    /**
     * A passing supervisor credential settles manually (the third leg: endorsement
     * required, operator not supervising, credential accepted).
     */
    @Test
    void validateManuallyWithPassingCredentialRegisters() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupManualEndorsement()).thenReturn(true);
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(false);
        when(service.endorsementService.authorize(eq("chef"), eq("mdp"),
                eq(BackupPaymentService.MANUAL_ACTION))).thenReturn(true);
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertTrue(service.validateManually(state, new BigDecimal("10.00"), "chef", "mdp"));
        verify(service.paymentService).processBackupPayment(eq(state), eq(new BigDecimal("10.00")),
                eq(BackupPaymentService.GENERIC_LABEL), eq(TRANSACTION), eq(true));
    }

    /**
     * A refused supervisor credential blocks the manual settlement (all three legs of
     * the endorsement rule true) with the authorization-refused message.
     */
    @Test
    void validateManuallyWithRefusedCredentialIsRefused() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupManualEndorsement()).thenReturn(true);
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(false);
        when(service.endorsementService.authorize(anyString(), anyString(), anyString()))
                .thenReturn(false);
        PosState state = new PosState();
        state.payment.backupRequest = request();
        assertFalse(service.validateManually(state, new BigDecimal("10.00"), "stagiaire", "mdp"));
        assertEquals("AUTORISATION REFUSEE", state.payment.backupError);
        verify(service.paymentService, never()).processBackupPayment(any(), any(), any(), any(),
                anyBoolean());
    }

    // --------------------------------------------------
    // methodLabel
    // --------------------------------------------------

    /**
     * A null scheme id resolves to the generic label (the null leg of the id guard),
     * without ever consulting the administered table.
     */
    @Test
    void methodLabelOfNullIsGeneric() {
        BackupPaymentService service = newService();
        assertEquals(BackupPaymentService.GENERIC_LABEL, service.methodLabel(null));
        verify(service.posSettingsService, never()).backupMethodLabels();
    }

    /**
     * A blank scheme id resolves to the generic label (the blank leg of the id guard).
     */
    @Test
    void methodLabelOfBlankIsGeneric() {
        BackupPaymentService service = newService();
        assertEquals(BackupPaymentService.GENERIC_LABEL, service.methodLabel("   "));
    }

    /**
     * A known scheme id resolves against the administered table case- and
     * whitespace-insensitively (non-blank id, non-blank table, a well-formed pair).
     */
    @Test
    void methodLabelResolvesAdministeredScheme() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("CB=Carte Bleue,VISA=Visa");
        assertEquals("Carte Bleue", service.methodLabel(" cb "));
    }

    /**
     * A scheme id the table does not carry falls back to the generic label rather
     * than being lost.
     */
    @Test
    void methodLabelOfUnknownSchemeIsGeneric() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("CB=Carte Bleue");
        assertEquals(BackupPaymentService.GENERIC_LABEL, service.methodLabel("XX"));
    }

    /**
     * A null administered table yields the generic label (the null leg of the table
     * guard).
     */
    @Test
    void methodLabelWithNullTableIsGeneric() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn(null);
        assertEquals(BackupPaymentService.GENERIC_LABEL, service.methodLabel("CB"));
    }

    /**
     * A blank administered table yields the generic label (the blank leg of the table
     * guard).
     */
    @Test
    void methodLabelWithBlankTableIsGeneric() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels()).thenReturn("   ");
        assertEquals(BackupPaymentService.GENERIC_LABEL, service.methodLabel("CB"));
    }

    /**
     * A table with malformed pairs (one with no equals, one starting with an equals)
     * skips them and still resolves the well-formed one — covering both the skip leg
     * ({@code equals <= 0}) and the keep leg ({@code equals > 0}) of the parser.
     */
    @Test
    void methodLabelSkipsMalformedPairs() {
        BackupPaymentService service = newService();
        when(service.posSettingsService.backupMethodLabels())
                .thenReturn("NOEQUALS,=NOKEY,CB=Carte Bleue");
        assertEquals("Carte Bleue", service.methodLabel("CB"));
    }
}
