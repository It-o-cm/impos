package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.barcode.CouponType;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    /** The mocked control engine, silent unless a test administers otherwise. */
    @Mock
    com.intermarche.pos.service.CouponCheckService couponCheckService;

    /** The handler under test, wired with the mocked service. */
    VoucherScanHandler handler;

    /**
     * Builds a fresh handler and injects the mocked collaborators before each
     * test, the control engine finding nothing by default.
     */
    @BeforeEach
    void setUp() {
        handler = new VoucherScanHandler();
        handler.voucherService = voucherService;
        handler.couponCheckService = couponCheckService;
        org.mockito.Mockito.lenient().when(couponCheckService.worst(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.intermarche.pos.domain.barcode.AlertLevel.NONE);
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
     * Builds a REGISTRY-backed coupon type — a credit note or a gift card,
     * whose amount lives in the stored-value registry, never on the paper.
     *
     * @param code the technical code
     * @param label the human-readable label
     * @return the configured coupon type
     */
    private CouponType registryType(String code, String label) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = label;
        type.amountSource = CouponType.AmountSource.REGISTRY;
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
     * When no payment is in progress and the code matches no payment voucher
     * pattern, the scan falls through untouched so the chain can treat the
     * number as whatever else it may be.
     */
    @Test
    void handleReturnsWhenNoPaymentInProgress() {
        PosState state = nominalState();
        state.payment.paymentInProgress = false;
        ScanContext ctx = new ScanContext("VCH123", state);
        when(voucherService.resolveType("VCH123")).thenReturn(null);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verify(voucherService, never()).applyRegistryVoucher(any(), any(), any());
        verify(voucherService, never()).applyEncodedVoucher(any(), any(), any());
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

    /**
     * A REGISTRY-backed instrument goes straight to the registry path: the
     * panel is NEVER opened and no amount is asked, because the registry —
     * not the cashier, not the printed figure — is the authority on what the
     * instrument is still worth.
     */
    @Test
    void handleAppliesRegistryVoucherWithoutAskingAnAmount() {
        PosState state = nominalState();
        long versionBefore = state.version;
        ScanContext ctx = new ScanContext("297000000000001", state);
        CouponType type = registryType("AVOIR", "Avoir");
        when(voucherService.resolveType("297000000000001")).thenReturn(type);

        handler.handle(ctx);

        assertTrue(ctx.handled);
        verify(voucherService).applyRegistryVoucher(state, type, "297000000000001");
        assertFalse(state.payment.voucherPanelOpen);
        assertNull(state.payment.pendingVoucherTypeCode);
        assertNull(state.payment.pendingVoucherNumber);
        assertEquals(versionBefore, state.version);
    }

    /**
     * The three amount sources are MUTUALLY EXCLUSIVE: a registry scan must
     * never also reach the encoded path, which would pay twice with one
     * instrument.
     */
    @Test
    void handleRegistryPathExcludesTheOtherTwo() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("296000000000001", state);
        CouponType type = registryType("CADEAU", "Carte cadeau");
        when(voucherService.resolveType("296000000000001")).thenReturn(type);

        handler.handle(ctx);

        verify(voucherService, never()).applyEncodedVoucher(any(), any(), any());
        verify(voucherService).applyRegistryVoucher(state, type, "296000000000001");
    }

    /**
     * A gift card takes the same registry path as a credit note — the two
     * instruments differ by their prefix and their rules, not by the way the
     * register reads their balance.
     */
    @Test
    void handleGiftCardTakesTheRegistryPathToo() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("296000000000042", state);
        CouponType type = registryType("CADEAU", "Carte cadeau");
        when(voucherService.resolveType("296000000000042")).thenReturn(type);

        handler.handle(ctx);

        assertTrue(ctx.handled);
        verify(voucherService).applyRegistryVoucher(state, type, "296000000000042");
    }

    /**
     * The registry path CONSUMES the context even when the service refuses
     * the instrument (unknown, exhausted, already scanned): the refusal is
     * displayed by the service, and no later handler may re-interpret the
     * same code as something else.
     */
    @Test
    void handleRegistryScanIsConsumedEvenWhenTheServiceRefuses() {
        PosState state = nominalState();
        ScanContext ctx = new ScanContext("297000000000404", state);
        CouponType type = registryType("AVOIR", "Avoir");
        when(voucherService.resolveType("297000000000404")).thenReturn(type);
        doAnswer(invocation -> {
            state.ticket.setError("BON INCONNU AU REGISTRE");
            return null;
        }).when(voucherService).applyRegistryVoucher(state, type, "297000000000404");

        handler.handle(ctx);

        assertTrue(ctx.handled);
        assertEquals("BON INCONNU AU REGISTRE", state.ticket.transientError);
    }

    /**
     * OUTSIDE a payment, a code matching a payment voucher pattern is NOT
     * applied — no payment may be registered — but the scan is consumed with
     * the explicit wrong-moment message instead of falling through to the
     * misleading generic "CODE INCONNU".
     */
    @Test
    void handleRecognizedVoucherOutsideAPaymentTellsTheRightMoment() {
        PosState state = nominalState();
        state.payment.paymentInProgress = false;
        ScanContext ctx = new ScanContext("297000000000001", state);
        when(voucherService.resolveType("297000000000001"))
                .thenReturn(registryType("AVOIR", "Avoir"));

        handler.handle(ctx);

        assertTrue(ctx.handled);
        assertEquals("BON VALABLE EN PHASE PAIEMENT", state.ticket.transientError);
        verify(voucherService, never()).applyRegistryVoucher(any(), any(), any());
        verify(voucherService, never()).applyEncodedVoucher(any(), any(), any());
        assertFalse(state.payment.voucherPanelOpen);
    }
}
