package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.scanner.ScanContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VoucherScanHandler}, exercising every branch of its
 * scan-chain contract with all collaborators but {@link VoucherService}
 * built as real plain objects.
 */
@ExtendWith(MockitoExtension.class)
class VoucherScanHandlerTest {

    /** The mocked voucher service resolving and applying vouchers. */
    @Mock
    VoucherService voucherService;

    /** The handler under test, wired with the mocked service. */
    VoucherScanHandler handler;

    /**
     * Builds a fresh handler and injects the mocked service before each test.
     */
    @BeforeEach
    void setUp() {
        handler = new VoucherScanHandler();
        handler.voucherService = voucherService;
    }

    /**
     * Builds a POS state that is unlocked and has a payment in progress, i.e.
     * the nominal context in which a voucher scan is processed.
     *
     * @return a ready-to-scan POS state
     */
    private PosState nominalState() {
        PosState state = new PosState();
        state.auth.isLocked = false;
        state.payment.paymentInProgress = true;
        return state;
    }

    /**
     * Builds a coupon type with the given code, label and manual-amount flag.
     *
     * @param code   the technical code
     * @param label  the human-readable label
     * @param manual true for a MANUAL amount source, false for ENCODED
     * @return the configured coupon type
     */
    private CouponType couponType(String code, String label, boolean manual) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = label;
        type.amountSource = manual ? CouponType.AmountSource.MANUAL : CouponType.AmountSource.ENCODED;
        return type;
    }

    /**
     * When the context is already handled, the handler returns immediately
     * without touching the service or the state.
     */
    @Test
    void handleReturnsWhenAlreadyHandled() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("VCH123", state);
        ctx.handled = true;
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(voucherService);
        assertFalse(state.payment.voucherPanelOpen);
    }

    /**
     * When the register is locked, the handler returns without resolving the
     * voucher and leaves the context unhandled.
     */
    @Test
    void handleReturnsWhenLocked() {
        PosState state = nominalState();
        state.auth.isLocked = true;
        ScanContext ctx = new ScanContext("VCH123", state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(voucherService);
        assertFalse(state.payment.voucherPanelOpen);
    }

    /**
     * When no payment is in progress, the scan is not treated as a voucher and
     * the handler returns without resolving anything.
     */
    @Test
    void handleReturnsWhenNoPaymentInProgress() {
        PosState state = nominalState();
        state.payment.paymentInProgress = false;
        ScanContext ctx = new ScanContext("VCH123", state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(voucherService);
        assertFalse(state.payment.voucherPanelOpen);
    }

    /**
     * When the code resolves to no known type, the handler returns without
     * applying a voucher or opening the panel, leaving the context unhandled.
     */
    @Test
    void handleReturnsWhenTypeUnresolved() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("UNKNOWN", state);
        when(voucherService.resolveType("UNKNOWN")).thenReturn(null);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verify(voucherService, never()).applyEncodedVoucher(state, null, "UNKNOWN");
        assertFalse(state.payment.voucherPanelOpen);
    }

    /**
     * When the resolved type requires a manual amount, the handler opens the
     * voucher panel, records the pending voucher details, bumps the version
     * and marks the context handled without applying an encoded voucher.
     */
    @Test
    void handleOpensPanelForManualAmountType() {
        PosState state = nominalState();
        long versionBefore = state.version;
        ScanContext ctx = new ScanContext("CAT999", state);
        CouponType type = couponType("CATALINA", "Catalina", true);
        when(voucherService.resolveType("CAT999")).thenReturn(type);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        assertTrue(state.payment.voucherPanelOpen);
        assertEquals("CATALINA", state.payment.pendingVoucherTypeCode);
        assertEquals("Catalina", state.payment.pendingVoucherLabel);
        assertEquals("CAT999", state.payment.pendingVoucherNumber);
        assertTrue(state.payment.pendingVoucherNeedsAmount);
        assertEquals(versionBefore + 1, state.version);
        verify(voucherService, never()).applyEncodedVoucher(state, type, "CAT999");
    }

    /**
     * When the resolved type carries an encoded amount, the handler delegates
     * to the service, marks the context handled and does not open the panel or
     * bump the version.
     */
    @Test
    void handleAppliesEncodedVoucherForEncodedType() {
        PosState state = nominalState();
        long versionBefore = state.version;
        ScanContext ctx = new ScanContext("GIFT500", state);
        CouponType type = couponType("GIFT_VOUCHER", "Chèque cadeau", false);
        when(voucherService.resolveType("GIFT500")).thenReturn(type);
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verify(voucherService).applyEncodedVoucher(state, type, "GIFT500");
        assertFalse(state.payment.voucherPanelOpen);
        assertNull(state.payment.pendingVoucherTypeCode);
        assertEquals(versionBefore, state.version);
    }

    /**
     * Sanity check that the nominal helper state carries the scanned code
     * straight from the context into the panel, preserving code identity.
     */
    @Test
    void handleUsesContextCodeAsVoucherNumber() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("CAT-ABC", state);
        CouponType type = couponType("CATALINA", "Catalina", true);
        when(voucherService.resolveType("CAT-ABC")).thenReturn(type);
        handler.handle(ctx);
        assertSame(ctx.code, state.payment.pendingVoucherNumber);
    }
}
