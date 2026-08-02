package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    /** The service under test, wired with the mocked payment service. */
    VoucherService service;

    /**
     * Builds a fresh service and injects the mocked payment service before each test.
     */
    @BeforeEach
    void setUp() {
        service = new VoucherService();
        service.paymentService = paymentService;
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
}
