package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.payment.PaymentState.PaymentEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaymentState} and its nested {@link PaymentEntry}.
 * <p>
 * {@link PaymentState} is a pure in-memory state/value holder with no
 * collaborators and no Panache entity access, so no mocking is required.
 * Every guard, ternary and short-circuit is exercised on both arms with
 * absolute expected values, and each mutator/reset is exercised for line
 * coverage. The formatting helpers of {@link PaymentEntry} force a French
 * comma through an explicit {@code replace(".", ",")}, so the asserted
 * strings are independent of the JVM default locale. The sole uncovered
 * branch is the defensive {@code fromIndex >= payments.size()} true arm of
 * {@link PaymentState#getVisiblePayments()}: after both page clamps the
 * from-index is provably always below the list size, making that arm
 * unreachable through the public API.
 */
class PaymentStateTest {

    /**
     * Fills a fresh state with the given number of plain payments of 1,00 each.
     *
     * @param count the number of payments to register
     * @return the populated state
     */
    private PaymentState stateWith(int count) {
        PaymentState state = new PaymentState();
        for (int i = 0; i < count; i++) {
            state.addPayment("CARD", new BigDecimal("1.00"));
        }
        return state;
    }

    /**
     * addPayment appends a plain entry, accumulates the paid amount, jumps to
     * the last page and clears the numpad inputs.
     */
    @Test
    void addPaymentRegistersEntryAndAccumulates() {
        PaymentState state = new PaymentState();
        state.inputMode = "AMOUNT";
        state.temporaryInput = "12,00";
        state.addPayment("CARD", new BigDecimal("12.00"));
        assertEquals(1, state.payments.size());
        assertEquals("CARD", state.payments.get(0).method);
        assertNull(state.payments.get(0).tenderedAmount);
        assertFalse(state.payments.get(0).voucher);
        assertEquals(new BigDecimal("12.00"), state.paidAmount);
        assertEquals(0, state.currentPage);
        assertNull(state.inputMode);
        assertEquals("0,00", state.temporaryInput);
    }

    /**
     * addCashPayment records the tendered amount alongside the applied amount.
     */
    @Test
    void addCashPaymentKeepsTenderedAmount() {
        PaymentState state = new PaymentState();
        state.addCashPayment(new BigDecimal("10.00"), new BigDecimal("20.00"));
        PaymentEntry entry = state.payments.get(0);
        assertEquals("CASH", entry.method);
        assertEquals(new BigDecimal("10.00"), entry.amount);
        assertEquals(new BigDecimal("20.00"), entry.tenderedAmount);
        assertFalse(entry.voucher);
        assertEquals(new BigDecimal("10.00"), state.paidAmount);
    }

    /**
     * addVoucherPayment marks the entry as a voucher and stores its number.
     */
    @Test
    void addVoucherPaymentMarksVoucher() {
        PaymentState state = new PaymentState();
        state.addVoucherPayment("Ticket Resto", "V123", new BigDecimal("7.50"));
        PaymentEntry entry = state.payments.get(0);
        assertEquals("Ticket Resto", entry.method);
        assertEquals(new BigDecimal("7.50"), entry.amount);
        assertNull(entry.tenderedAmount);
        assertEquals("V123", entry.voucherNumber);
        assertTrue(entry.voucher);
        assertEquals(new BigDecimal("7.50"), state.paidAmount);
    }

    /**
     * clearPayments empties the list, zeroes the paid amount, resets the page
     * and the numpad inputs.
     */
    @Test
    void clearPaymentsResetsListAndPage() {
        PaymentState state = stateWith(3);
        state.inputMode = "AMOUNT";
        state.temporaryInput = "3,00";
        state.clearPayments();
        assertTrue(state.payments.isEmpty());
        assertEquals(BigDecimal.ZERO, state.paidAmount);
        assertEquals(0, state.currentPage);
        assertNull(state.inputMode);
        assertEquals("0,00", state.temporaryInput);
    }

    /**
     * reset clears every payment and transaction field back to its default.
     */
    @Test
    void resetRestoresDefaults() {
        PaymentState state = stateWith(2);
        state.transactionComplete = true;
        state.ticketDbId = 42L;
        state.paymentInProgress = true;
        state.pendingCardAmount = new BigDecimal("5.00");
        state.valuationStatus = "ENGINE";
        state.valuationJson = "{}";
        state.valuationEngineTotal = new BigDecimal("9.99");
        state.valuationAdjustment = new BigDecimal("-1.00");
        state.valuationMealEligible = new BigDecimal("19.00");
        state.valuationMealThreshold = new BigDecimal("25.00");
        state.valuationUpsells.add("Café");
        state.lastChangeAmount = new BigDecimal("2.00");
        state.voucherPanelOpen = true;
        state.pendingVoucherTypeCode = "TR";
        state.pendingVoucherLabel = "Ticket Resto";
        state.pendingVoucherNumber = "V1";
        state.pendingVoucherNeedsAmount = true;
        state.voucherError = "bad";
        state.reset();
        assertTrue(state.payments.isEmpty());
        assertEquals(BigDecimal.ZERO, state.paidAmount);
        assertFalse(state.transactionComplete);
        assertNull(state.ticketDbId);
        assertFalse(state.paymentInProgress);
        assertNull(state.pendingCardAmount);
        assertNull(state.valuationStatus);
        assertNull(state.valuationJson);
        assertNull(state.valuationEngineTotal);
        assertNull(state.valuationAdjustment);
        assertNull(state.valuationMealEligible);
        assertNull(state.valuationMealThreshold);
        assertTrue(state.valuationUpsells.isEmpty());
        assertNull(state.lastChangeAmount);
        assertFalse(state.voucherPanelOpen);
        assertNull(state.pendingVoucherTypeCode);
        assertNull(state.pendingVoucherLabel);
        assertNull(state.pendingVoucherNumber);
        assertFalse(state.pendingVoucherNeedsAmount);
        assertNull(state.voucherError);
    }

    /**
     * clearPendingVoucher wipes only the in-progress voucher entry fields.
     */
    @Test
    void clearPendingVoucherWipesVoucherFields() {
        PaymentState state = new PaymentState();
        state.voucherPanelOpen = true;
        state.pendingVoucherTypeCode = "TR";
        state.pendingVoucherLabel = "Ticket Resto";
        state.pendingVoucherNumber = "V1";
        state.pendingVoucherNeedsAmount = true;
        state.voucherError = "bad";
        state.clearPendingVoucher();
        assertFalse(state.voucherPanelOpen);
        assertNull(state.pendingVoucherTypeCode);
        assertNull(state.pendingVoucherLabel);
        assertNull(state.pendingVoucherNumber);
        assertFalse(state.pendingVoucherNeedsAmount);
        assertNull(state.voucherError);
    }

    /**
     * getTotalPages returns one page when no payment is registered (empty arm).
     */
    @Test
    void getTotalPagesEmptyReturnsOne() {
        assertEquals(1, new PaymentState().getTotalPages());
    }

    /**
     * getTotalPages rounds the page count up for a non-empty list (non-empty arm).
     */
    @Test
    void getTotalPagesRoundsUp() {
        assertEquals(1, stateWith(5).getTotalPages());
        assertEquals(2, stateWith(6).getTotalPages());
        assertEquals(3, stateWith(11).getTotalPages());
    }

    /**
     * goToLastPage positions the view on the final page.
     */
    @Test
    void goToLastPageSelectsFinalPage() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        state.goToLastPage();
        assertEquals(2, state.currentPage);
    }

    /**
     * getVisiblePayments returns an empty list when there is no payment (empty arm).
     */
    @Test
    void getVisiblePaymentsEmptyList() {
        assertTrue(new PaymentState().getVisiblePayments().isEmpty());
    }

    /**
     * getVisiblePayments clamps a page beyond the last (currentPage > maxPage
     * true, currentPage < 0 false) and returns that last page's slice.
     */
    @Test
    void getVisiblePaymentsClampsPageTooHigh() {
        PaymentState state = stateWith(3);
        state.currentPage = 5;
        List<PaymentEntry> visible = state.getVisiblePayments();
        assertEquals(0, state.currentPage);
        assertEquals(3, visible.size());
    }

    /**
     * getVisiblePayments clamps a negative page (currentPage > maxPage false,
     * currentPage < 0 true) back to the first page.
     */
    @Test
    void getVisiblePaymentsClampsNegativePage() {
        PaymentState state = stateWith(3);
        state.currentPage = -3;
        List<PaymentEntry> visible = state.getVisiblePayments();
        assertEquals(0, state.currentPage);
        assertEquals(3, visible.size());
    }

    /**
     * getVisiblePayments returns the second-page slice for an in-range page
     * (both clamps false, fromIndex below size), covering only its five entries.
     */
    @Test
    void getVisiblePaymentsSecondPageSlice() {
        PaymentState state = stateWith(11);
        state.currentPage = 1;
        List<PaymentEntry> visible = state.getVisiblePayments();
        assertEquals(5, visible.size());
        assertSame(state.payments.get(5), visible.get(0));
        assertSame(state.payments.get(9), visible.get(4));
    }

    /**
     * getVisiblePayments returns the short final-page slice (toIndex capped at
     * the list size) with the trailing entry only.
     */
    @Test
    void getVisiblePaymentsLastPagePartialSlice() {
        PaymentState state = stateWith(11);
        state.currentPage = 2;
        List<PaymentEntry> visible = state.getVisiblePayments();
        assertEquals(1, visible.size());
        assertSame(state.payments.get(10), visible.get(0));
    }

    /**
     * isHasPreviousPage is true past the first page and false on it (both arms).
     */
    @Test
    void isHasPreviousPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        assertFalse(state.isHasPreviousPage());
        state.currentPage = 1;
        assertTrue(state.isHasPreviousPage());
    }

    /**
     * isHasNextPage is true while payments remain beyond the page and false on
     * the last page (both arms).
     */
    @Test
    void isHasNextPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        assertTrue(state.isHasNextPage());
        state.currentPage = 2;
        assertFalse(state.isHasNextPage());
    }

    /**
     * isPaginated is true above one full page and false at or below it (both arms).
     */
    @Test
    void isPaginatedBothArms() {
        assertFalse(stateWith(5).isPaginated());
        assertTrue(stateWith(6).isPaginated());
    }

    /**
     * getCurrentPageDisplay returns the 1-based page number.
     */
    @Test
    void getCurrentPageDisplayIsOneBased() {
        PaymentState state = stateWith(11);
        state.currentPage = 2;
        assertEquals(3, state.getCurrentPageDisplay());
    }

    /**
     * isOnCurrentPage covers the three short-circuit outcomes of its && guard:
     * below the window (first condition false), at or after its end (second
     * condition false), and inside it (both true).
     */
    @Test
    void isOnCurrentPageAllShortCircuitArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 1;
        assertFalse(state.isOnCurrentPage(4));
        assertFalse(state.isOnCurrentPage(10));
        assertTrue(state.isOnCurrentPage(7));
    }

    /**
     * isAmongLastTwo is true for the final two indices and false before them
     * (both arms).
     */
    @Test
    void isAmongLastTwoBothArms() {
        PaymentState state = stateWith(5);
        assertTrue(state.isAmongLastTwo(3));
        assertTrue(state.isAmongLastTwo(4));
        assertFalse(state.isAmongLastTwo(2));
    }

    /**
     * nextPage advances when a next page exists and stays put otherwise (both arms).
     */
    @Test
    void nextPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 0;
        state.nextPage();
        assertEquals(1, state.currentPage);
        state.currentPage = 2;
        state.nextPage();
        assertEquals(2, state.currentPage);
    }

    /**
     * prevPage retreats when a previous page exists and stays put otherwise
     * (both arms).
     */
    @Test
    void prevPageBothArms() {
        PaymentState state = stateWith(11);
        state.currentPage = 2;
        state.prevPage();
        assertEquals(1, state.currentPage);
        state.currentPage = 0;
        state.prevPage();
        assertEquals(0, state.currentPage);
    }

    /**
     * The two-argument plain-entry constructor leaves the tendered amount null
     * and the voucher flag false.
     */
    @Test
    void plainEntryConstructorDefaults() {
        PaymentEntry entry = new PaymentEntry("CARD", new BigDecimal("3.00"));
        assertEquals("CARD", entry.method);
        assertEquals(new BigDecimal("3.00"), entry.amount);
        assertNull(entry.tenderedAmount);
        assertFalse(entry.isVoucher());
    }

    /**
     * getFormattedAmount renders two decimals with a French comma and HALF_UP
     * rounding, independent of the JVM locale.
     */
    @Test
    void getFormattedAmountUsesCommaAndHalfUp() {
        assertEquals("1,50", new PaymentEntry("CARD", new BigDecimal("1.5")).getFormattedAmount());
        assertEquals("1,01", new PaymentEntry("CARD", new BigDecimal("1.005")).getFormattedAmount());
    }

    /**
     * getFormattedTendered renders the amount when present (non-null arm).
     */
    @Test
    void getFormattedTenderedPresent() {
        PaymentEntry entry = new PaymentEntry("CASH", new BigDecimal("10.00"), new BigDecimal("20.5"));
        assertEquals("20,50", entry.getFormattedTendered());
    }

    /**
     * getFormattedTendered returns a dash when the tendered amount is absent
     * (null arm).
     */
    @Test
    void getFormattedTenderedAbsent() {
        assertEquals("-", new PaymentEntry("CARD", new BigDecimal("10.00")).getFormattedTendered());
    }

    /**
     * getVoucherNumber returns the stored number when present (non-null arm).
     */
    @Test
    void getVoucherNumberPresent() {
        PaymentEntry entry = new PaymentEntry("Ticket Resto", new BigDecimal("7.50"), "V123", true);
        assertEquals("V123", entry.getVoucherNumber());
    }

    /**
     * getVoucherNumber returns an empty string when the number is null (null arm).
     */
    @Test
    void getVoucherNumberAbsent() {
        PaymentEntry entry = new PaymentEntry("Ticket Resto", new BigDecimal("7.50"), null, true);
        assertEquals("", entry.getVoucherNumber());
    }
}
