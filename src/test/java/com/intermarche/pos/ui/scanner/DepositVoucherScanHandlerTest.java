package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DepositVoucherScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link of the scan chain owning the
 * CART phase of the deposit-return voucher world: it walks the active
 * {@code depositLine} {@link CouponType}s, and for the first one recognizing
 * the code it either rejects an unreadable amount ({@code setError}) or adds
 * a negative, VAT-free line to the ticket. It bails out early when the
 * context is already handled, the register is locked, or a payment is in
 * progress. Every collaborator is a Mockito mock: the {@link PosState} whose
 * public {@code ticket}/{@code payment} sub-states are themselves mocks, and
 * the {@link CouponType} instances whose {@code matches}/{@code extractAmount}
 * are stubbed and whose {@code label} field is set directly. The static
 * finder {@code CouponType.listActiveDepositTypes()} is neutralized with
 * {@link Mockito#mockStatic}. Seven decision points, fourteen branches, are
 * exercised by eight isolated cases.
 */
class DepositVoucherScanHandlerTest {

    /** A deposit-return voucher code fed to the handler. */
    private static final String CODE = "298000250";

    /**
     * Builds a mock {@link PosState} carrying mock ticket and payment
     * sub-states, unlocked and with no payment in progress by default.
     *
     * @param ticket the ticket mailbox mock
     * @param payment the payment sub-state mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket, PaymentState payment) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        state.payment = payment;
        return state;
    }

    /**
     * Builds a mock {@link CouponType} whose {@code matches} verdict is
     * preset and whose visible label is fixed.
     *
     * @param matches the value {@code matches(CODE)} must return
     * @return the wired coupon-type mock
     */
    private CouponType newType(boolean matches) {
        CouponType type = mock(CouponType.class);
        type.label = "consigne";
        when(type.matches(CODE)).thenReturn(matches);
        return type;
    }

    /**
     * An already-handled context short-circuits before any state is read:
     * the handler returns leaving the flag set and touching nothing.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        ScanContext ctx = new ScanContext(CODE, state);
        ctx.handled = true;
        new DepositVoucherScanHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(ticket);
        verifyNoInteractions(payment);
    }

    /**
     * A locked register short-circuits: the handler returns before consulting
     * the deposit types or the ticket, leaving the context unhandled.
     */
    @Test
    void lockedRegisterShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        when(state.isLocked()).thenReturn(true);
        ScanContext ctx = new ScanContext(CODE, state);
        new DepositVoucherScanHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * A payment in progress short-circuits: a deposit is a sale line, not a
     * payment, so the handler stands down and leaves the context unhandled.
     */
    @Test
    void paymentInProgressShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        payment.paymentInProgress = true;
        PosState state = newState(ticket, payment);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(CODE, state);
        new DepositVoucherScanHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * With no active deposit type the loop body never runs: the context stays
     * unhandled and the ticket is untouched.
     */
    @Test
    void emptyDepositTypeListLeavesContextUnhandled() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<CouponType> couponTypes = Mockito.mockStatic(CouponType.class)) {
            couponTypes.when(CouponType::listActiveDepositTypes).thenReturn(List.of());
            new DepositVoucherScanHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * A type that does not recognize the code is skipped: the loop exhausts,
     * the context stays unhandled and the ticket is untouched.
     */
    @Test
    void nonMatchingTypeIsSkipped() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        CouponType type = newType(false);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<CouponType> couponTypes = Mockito.mockStatic(CouponType.class)) {
            couponTypes.when(CouponType::listActiveDepositTypes).thenReturn(List.of(type));
            new DepositVoucherScanHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(type, never()).extractAmount(CODE);
        verifyNoInteractions(ticket);
    }

    /**
     * A matching type whose amount cannot be read (null) rejects the voucher:
     * the ticket carries the illisible error and the context is consumed.
     */
    @Test
    void unreadableNullAmountSetsError() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        CouponType type = newType(true);
        when(type.extractAmount(CODE)).thenReturn(null);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<CouponType> couponTypes = Mockito.mockStatic(CouponType.class)) {
            couponTypes.when(CouponType::listActiveDepositTypes).thenReturn(List.of(type));
            new DepositVoucherScanHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("BON DE CONSIGNE ILLISIBLE");
        verify(ticket, never()).addItem(isNull(), isNull(), eq("CONSIGNE"),
                eq(BigDecimal.ZERO.negate()), eq(BigDecimal.ONE), eq(BigDecimal.ZERO));
    }

    /**
     * A matching type whose amount is non-positive (zero signum) is rejected:
     * the ticket carries the illisible error and the context is consumed.
     */
    @Test
    void nonPositiveAmountSetsError() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        CouponType type = newType(true);
        when(type.extractAmount(CODE)).thenReturn(BigDecimal.ZERO);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<CouponType> couponTypes = Mockito.mockStatic(CouponType.class)) {
            couponTypes.when(CouponType::listActiveDepositTypes).thenReturn(List.of(type));
            new DepositVoucherScanHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("BON DE CONSIGNE ILLISIBLE");
        verify(ticket, never()).addItem(isNull(), isNull(), eq("CONSIGNE"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(BigDecimal.ZERO));
    }

    /**
     * A matching type with a readable positive amount adds a negative,
     * VAT-free line labelled in upper case and consumes the context.
     */
    @Test
    void validDepositAddsNegativeVatFreeLine() {
        TicketState ticket = mock(TicketState.class);
        PaymentState payment = mock(PaymentState.class);
        PosState state = newState(ticket, payment);
        CouponType type = newType(true);
        BigDecimal amount = new BigDecimal("2.50");
        when(type.extractAmount(CODE)).thenReturn(amount);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<CouponType> couponTypes = Mockito.mockStatic(CouponType.class)) {
            couponTypes.when(CouponType::listActiveDepositTypes).thenReturn(List.of(type));
            new DepositVoucherScanHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(isNull(), isNull(), eq("CONSIGNE"),
                eq(new BigDecimal("-2.50")), eq(BigDecimal.ONE), eq(BigDecimal.ZERO));
        verify(ticket, never()).setError("BON DE CONSIGNE ILLISIBLE");
    }
}
