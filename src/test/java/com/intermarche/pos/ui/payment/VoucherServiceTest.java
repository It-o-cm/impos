package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoucherService}, exercising every branch of voucher
 * resolution and of encoded / manual voucher registration. The external
 * collaborators — {@link PaymentService}, {@link PosState} and the static
 * {@code CouponType.listActivePaymentTypes()} finder — are mocked, while the
 * pure domain logic of a {@link CouponType} instance ({@code matches},
 * {@code extractAmount}) is used with real, field-configured instances.
 */
@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {

    /** The mocked payment service that ultimately records the voucher. */
    @Mock
    PaymentService paymentService;

    /** The mocked POS state exposing the remaining due. */
    @Mock
    PosState state;

    /** The mocked control engine recording the accepted codes (BO-03-06-39/49). */
    @Mock
    com.intermarche.pos.service.CouponCheckService couponCheckService;

    /** The service under test, wired with the mocked payment service. */
    VoucherService service;

    /**
     * Builds a fresh service and injects the mocked collaborators before each test.
     */
    @BeforeEach
    void setUp() {
        service = new VoucherService();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.paymentService = paymentService;
        service.couponCheckService = couponCheckService;
        service.scannedBarcodeService = mock(com.intermarche.pos.service.ScannedBarcodeService.class);
        service.storedValueLookupClient = mock(StoredValueLookupClient.class);
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        state.payment = new PaymentState();
    }

    /**
     * Builds a coupon type recognized by a match pattern, without amount encoding.
     *
     * @param code         the technical code
     * @param label        the human-readable label
     * @param matchPattern the regex a number must fully match
     * @return the configured coupon type
     */
    private CouponType matchOnlyType(String code, String label, String matchPattern) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = label;
        type.matchPattern = matchPattern;
        return type;
    }

    /**
     * Builds a coupon type whose amount is encoded in the number.
     *
     * @param code          the technical code
     * @param label         the human-readable label
     * @param amountPattern the extraction regex whose first group holds the cents
     * @return the configured encoded coupon type
     */
    private CouponType encodedType(String code, String label, String amountPattern) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = label;
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = amountPattern;
        return type;
    }

    /**
     * Builds a coupon type whose amount is entered manually by the cashier.
     *
     * @param code  the technical code
     * @param label the human-readable label
     * @return the configured manual coupon type
     */
    private CouponType manualType(String code, String label) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = label;
        type.amountSource = CouponType.AmountSource.MANUAL;
        return type;
    }

    /**
     * A null number resolves to no type without consulting the type list.
     */
    @Test
    void resolveType_nullNumber_returnsNull() {
        assertNull(service.resolveType(null));
    }

    /**
     * A blank number resolves to no type without consulting the type list.
     */
    @Test
    void resolveType_blankNumber_returnsNull() {
        assertNull(service.resolveType("   "));
    }

    /**
     * Resolution skips a non-matching type and returns the first matching one.
     */
    @Test
    void resolveType_firstNonMatchingSecondMatching_returnsSecond() {
        CouponType first = matchOnlyType("A", "Label A", "\\d{5}");
        CouponType second = matchOnlyType("B", "Label B", "\\d{7}");
        try (MockedStatic<CouponType> couponType = mockStatic(CouponType.class)) {
            couponType.when(CouponType::listActivePaymentTypes).thenReturn(List.of(first, second));
            assertSame(second, service.resolveType("1234567"));
        }
    }

    /**
     * When no active type matches the number, resolution returns null.
     */
    @Test
    void resolveType_noneMatch_returnsNull() {
        CouponType only = matchOnlyType("A", "Label A", "\\d{5}");
        try (MockedStatic<CouponType> couponType = mockStatic(CouponType.class)) {
            couponType.when(CouponType::listActivePaymentTypes).thenReturn(List.of(only));
            assertNull(service.resolveType("1234567"));
        }
    }

    /**
     * A null type cannot yield an encoded voucher and touches no payment.
     */
    @Test
    void applyEncodedVoucher_nullType_returnsFalse() {
        assertFalse(service.applyEncodedVoucher(state, null, "500"));
        verifyNoInteractions(paymentService);
    }

    /**
     * When the amount cannot be extracted from the number, no payment is registered.
     */
    @Test
    void applyEncodedVoucher_amountNotExtractable_returnsFalse() {
        CouponType type = encodedType("GIFT", "Chèque cadeau", null);
        assertFalse(service.applyEncodedVoucher(state, type, "500"));
        verifyNoInteractions(paymentService);
    }

    /**
     * A recognized encoded voucher registers its extracted amount and returns true.
     */
    @Test
    void applyEncodedVoucher_success_registersAndReturnsTrue() {
        CouponType type = encodedType("GIFT", "Chèque cadeau", "(\\d+)");
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        assertTrue(service.applyEncodedVoucher(state, type, "500"));
        verify(paymentService).processVoucher(state, "Chèque cadeau", "500", new BigDecimal("5.00"));
    }

    /**
     * A null manual amount registers nothing.
     */
    @Test
    void applyManualVoucher_nullAmount_noPayment() {
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", null);
        verifyNoInteractions(paymentService);
    }

    /**
     * A zero manual amount registers nothing.
     */
    @Test
    void applyManualVoucher_zeroAmount_noPayment() {
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", BigDecimal.ZERO);
        verifyNoInteractions(paymentService);
    }

    /**
     * A negative manual amount registers nothing.
     */
    @Test
    void applyManualVoucher_negativeAmount_noPayment() {
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", new BigDecimal("-5.00"));
        verifyNoInteractions(paymentService);
    }

    /**
     * A positive manual amount below the remaining due registers under the type label.
     */
    @Test
    void applyManualVoucher_positiveAmount_registersWithTypeLabel() {
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", new BigDecimal("7.50"));
        verify(paymentService).processVoucher(state, "Catalina", "C1", new BigDecimal("7.50"));
    }

    /**
     * A voucher exceeding the remaining due is capped at that remaining amount.
     */
    @Test
    void applyManualVoucher_overpaying_isCappedToRemaining() {
        when(state.getRemaining()).thenReturn(new BigDecimal("10.00"));
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", new BigDecimal("30.00"));
        verify(paymentService).processVoucher(state, "Catalina", "C1", new BigDecimal("10.00"));
    }

    /**
     * With nothing left to pay, the capped amount is zero and no payment is registered.
     */
    @Test
    void applyManualVoucher_nothingRemaining_noPayment() {
        when(state.getRemaining()).thenReturn(new BigDecimal("0.00"));
        service.applyManualVoucher(state, manualType("CATALINA", "Catalina"), "C1", new BigDecimal("5.00"));
        verifyNoInteractions(paymentService);
    }

    /**
     * A numberless voucher with a null type registers under the default label.
     */
    @Test
    void applyManualVoucher_nullType_usesDefaultLabel() {
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        service.applyManualVoucher(state, null, "N9", new BigDecimal("5.00"));
        verify(paymentService).processVoucher(state, "Bon d'achat", "N9", new BigDecimal("5.00"));
    }

    // --- applyRegistryVoucher ---

    /**
     * Wires the mocked state with a mocked ticket mailbox and a real payment
     * sub-state, the two collaborators the registry path touches: the ticket
     * receives the refusals, the payments carry the already-scanned numbers.
     *
     * @return the ticket mailbox mock, for verification
     */
    private TicketState wireStateForRegistry() {
        TicketState ticket = mock(TicketState.class);
        state.ticket = ticket;
        state.payment = new PaymentState();
        return ticket;
    }

    /**
     * Builds a registry instrument with the given status and balance.
     *
     * @param status the instrument status
     * @param balance the remaining balance
     * @return the wired instrument
     */
    private StoredValue instrument(StoredValue.Status status, String balance) {
        StoredValue sv = new StoredValue();
        sv.status = status;
        sv.balance = new BigDecimal(balance);
        return sv;
    }

    /**
     * A number ABSENT from the registry is refused: the register never trusts
     * a printed number on its own — the registry is the authority on what an
     * instrument is worth.
     */
    @Test
    void applyRegistryVoucher_unknownNumber_refused() {
        TicketState ticket = wireStateForRegistry();
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001")).thenReturn(null);
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(ticket).setError("BON INCONNU AU REGISTRE");
        verify(state).touch();
        verifyNoInteractions(paymentService);
        verify(service.storedValueLookupClient).adopt("297000000000001");
    }

    /**
     * A number neither this register nor the shop knows is journalled as not
     * found and, at the INFO level, only announced (BO-03-06-48/63): nothing is
     * paid, the cashier reads that the system does not know it.
     */
    @Test
    void applyRegistryVoucher_unknownEverywhere_atInfo_isAnnouncedAndJournalled() {
        TicketState ticket = wireStateForRegistry();
        state.payment.ticketDbId = 5L;
        CouponType type = matchOnlyType("AVOIR", "Avoir", "^297.*");
        when(service.posSettingsService.voucherUnknownAlert()).thenReturn("INFO");
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            service.applyRegistryVoucher(state, type, "297000000000001");
        }
        verify(ticket).setWarning("BON INCONNU DU SYSTÈME : AUCUN RÈGLEMENT");
        verify(ticket, never()).setError(any());
        verify(service.scannedBarcodeService).record("297000000000001", type, null, null, false, 5L, false);
        verifyNoInteractions(paymentService);
    }

    /**
     * An instrument another register printed is adopted from the store node and
     * redeemed here (BO-03-06-48): its balance pays.
     */
    @Test
    void applyRegistryVoucher_printedElsewhere_isAdoptedAndPays() {
        wireStateForRegistry();
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        when(service.storedValueLookupClient.adopt("9800100000314050924102610240124000072340"))
                .thenReturn(instrument(StoredValue.Status.ACTIVE, "8.00"));
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^98.*"),
                    "9800100000314050924102610240124000072340");
        }
        verify(paymentService).processVoucher(state, "Avoir", "9800100000314050924102610240124000072340",
                new BigDecimal("8.00"));
    }

    /**
     * An EXHAUSTED instrument is refused even if its balance still looks
     * positive (first leg of the status guard): the status is authoritative.
     */
    @Test
    void applyRegistryVoucher_exhaustedStatus_refused() {
        TicketState ticket = wireStateForRegistry();
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.EXHAUSTED, "5.00"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(ticket).setError("BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ");
        verifyNoInteractions(paymentService);
    }

    /**
     * A ZERO balance is refused (second leg, {@code signum() == 0}).
     */
    @Test
    void applyRegistryVoucher_zeroBalance_refused() {
        TicketState ticket = wireStateForRegistry();
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "0.00"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(ticket).setError("BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ");
        verifyNoInteractions(paymentService);
    }

    /**
     * A NEGATIVE balance is refused too (second leg, {@code signum() < 0}):
     * the guard is {@code <= 0}, so a corrupted row can never pay.
     */
    @Test
    void applyRegistryVoucher_negativeBalance_refused() {
        TicketState ticket = wireStateForRegistry();
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "-1.00"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(ticket).setError("BON DÉJÀ UTILISÉ - SOLDE ÉPUISÉ");
        verifyNoInteractions(paymentService);
    }

    /**
     * An instrument ALREADY SCANNED on this very sale is refused: a second
     * scan of the same paper would pay twice with one instrument.
     */
    @Test
    void applyRegistryVoucher_alreadyScannedOnThisSale_refused() {
        TicketState ticket = wireStateForRegistry();
        state.payment.payments.add(new PaymentState.PaymentEntry(
                "Avoir", new BigDecimal("10.00"), "297000000000001", true));
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "10.00"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(ticket).setError("BON DÉJÀ SCANNÉ SUR CETTE VENTE");
        verifyNoInteractions(paymentService);
    }

    /**
     * A DIFFERENT instrument already scanned does not block this one: the
     * guard matches on the NUMBER, not on the mere presence of a voucher.
     */
    @Test
    void applyRegistryVoucher_anotherVoucherScanned_stillAccepted() {
        wireStateForRegistry();
        state.payment.payments.add(new PaymentState.PaymentEntry(
                "Avoir", new BigDecimal("5.00"), "297000000000999", true));
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "10.00"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(paymentService).processVoucher(state, "Avoir", "297000000000001",
                new BigDecimal("10.00"));
    }

    /**
     * A valid instrument pays its FULL REGISTRY BALANCE — the amount comes
     * from the registry, never from the paper: an old printed figure can no
     * longer overpay.
     */
    @Test
    void applyRegistryVoucher_valid_paysTheRegistryBalance() {
        wireStateForRegistry();
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("297000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "12.34"));
            service.applyRegistryVoucher(state, matchOnlyType("AVOIR", "Avoir", "^297.*"),
                    "297000000000001");
        }
        verify(paymentService).processVoucher(state, "Avoir", "297000000000001",
                new BigDecimal("12.34"));
        verify(service.scannedBarcodeService).record(eq("297000000000001"), any(),
                eq(new BigDecimal("12.34")), isNull(), eq(true), isNull(), eq(false));
    }

    // --- tender binding and end of validity (BO-03-06-08/63) ---

    /**
     * A paper meal voucher range bound to the meal ticket settles as a meal
     * ticket, not as a voucher (BO-03-06-08), and the journal keeps what the
     * meal ticket actually settled, never less than nothing.
     */
    @Test
    void aRangeBoundToTheMealTicketSettlesAsAMealTicket() {
        CouponType type = manualType("TRP", "Titre restaurant papier");
        type.tenderCode = " TR ";
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"), new BigDecimal("14.00"));
        service.applyManualVoucher(state, type, "TR1", new BigDecimal("9.00"));
        verify(paymentService).processTicketResto(state, new BigDecimal("9.00"));
        verify(paymentService, never()).processVoucher(any(), any(), any(), any());
        verify(service.scannedBarcodeService).record("TR1", type, new BigDecimal("6.00"), null, true, null, false);
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"), new BigDecimal("21.00"));
        service.applyManualVoucher(state, type, "TR2", new BigDecimal("9.00"));
        verify(service.scannedBarcodeService).record("TR2", type, BigDecimal.ZERO, null, true, null, false);
    }

    /**
     * A range bound to an administered voucher tender settles under that
     * tender's label; a binding naming no tender keeps the range's label.
     */
    @Test
    void aRangeBoundToATenderSettlesUnderItsLabel() {
        CouponType bound = manualType("BA", "Bon partenaire");
        bound.tenderCode = "VOUCHER";
        CouponType orphan = manualType("BB", "Bon local");
        orphan.tenderCode = "GONE";
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.label = "Bon d'achat magasin";
        when(state.getRemaining()).thenReturn(new BigDecimal("20.00"));
        try (MockedStatic<com.intermarche.pos.domain.payment.TenderDefinition> tenders =
                     mockStatic(com.intermarche.pos.domain.payment.TenderDefinition.class)) {
            tenders.when(() -> com.intermarche.pos.domain.payment.TenderDefinition.findByCode("VOUCHER"))
                    .thenReturn(tender);
            service.applyManualVoucher(state, bound, "B1", new BigDecimal("3.00"));
            service.applyManualVoucher(state, orphan, "B2", new BigDecimal("4.00"));
        }
        verify(paymentService).processVoucher(state, "Bon d'achat magasin", "B1", new BigDecimal("3.00"));
        verify(paymentService).processVoucher(state, "Bon local", "B2", new BigDecimal("4.00"));
    }

    /**
     * The end of validity read on the code is journalled with the reading; a
     * range without end date field, a field the code is too short for, and a
     * numberless voucher journal none (BO-03-06-63).
     */
    @Test
    void theEndOfValidityReadOnTheCodeIsJournalled() {
        CouponType dated = encodedType("BD", "Bon daté", "^\\d{6}(\\d{4})$");
        com.intermarche.pos.domain.barcode.CouponField end = new com.intermarche.pos.domain.barcode.CouponField();
        end.role = com.intermarche.pos.domain.barcode.CouponField.Role.DATE_END;
        end.offsetPosition = 0;
        end.fieldLength = 6;
        end.dateFormat = com.intermarche.pos.domain.barcode.CouponField.DateFormat.DDMMYY;
        dated.fields = new java.util.ArrayList<>(List.of(end));
        CouponType undated = manualType("BU", "Bon");
        when(state.getRemaining()).thenReturn(new BigDecimal("50.00"));
        assertTrue(service.applyEncodedVoucher(state, dated, "3112270500"));
        service.applyManualVoucher(state, dated, "31", new BigDecimal("1.00"));
        service.applyManualVoucher(state, undated, "U1", new BigDecimal("2.00"));
        service.applyManualVoucher(state, undated, null, new BigDecimal("3.00"));
        verify(service.scannedBarcodeService).record("3112270500", dated, new BigDecimal("5.00"),
                java.time.LocalDateTime.of(2027, 12, 31, 0, 0), true, null, false);
        verify(service.scannedBarcodeService).record("31", dated, new BigDecimal("1.00"), null, true, null, false);
        verify(service.scannedBarcodeService).record("U1", undated, new BigDecimal("2.00"), null, true, null, false);
        verify(service.scannedBarcodeService).record(null, undated, new BigDecimal("3.00"), null, true, null, false);
    }

    /**
     * A balance above the remaining due is CAPPED at what is left to pay:
     * the residue stays on the instrument for a later sale.
     */
    @Test
    void applyRegistryVoucher_balanceAboveRemaining_isCapped() {
        wireStateForRegistry();
        when(state.getRemaining()).thenReturn(new BigDecimal("4.00"));
        try (MockedStatic<StoredValue> registry = mockStatic(StoredValue.class)) {
            registry.when(() -> StoredValue.findByNumber("296000000000001"))
                    .thenReturn(instrument(StoredValue.Status.ACTIVE, "25.00"));
            service.applyRegistryVoucher(state, matchOnlyType("CADEAU", "Carte cadeau", "^296.*"),
                    "296000000000001");
        }
        verify(paymentService).processVoucher(state, "Carte cadeau", "296000000000001",
                new BigDecimal("4.00"));
    }
}
