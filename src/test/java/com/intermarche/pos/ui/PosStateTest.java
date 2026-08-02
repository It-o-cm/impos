package com.intermarche.pos.ui;

import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link PosState}.
 * <p>
 * {@code PosState} is the in-memory composition root of the terminal. Its own
 * logic is limited to a version heartbeat, an end-of-sale reset that delegates
 * to the sub-states, a handful of BigDecimal money computations and the ticket
 * pagination arithmetic. Every collaborator ({@link TicketState},
 * {@link PaymentState}, {@link FidelityState}, {@link AuthState} and
 * {@link PriceModState}) is a pure in-memory state holder, so each is replaced
 * by a Mockito mock: {@code PosState} only ever reads their public fields or
 * invokes their void mutators, both of which a mock serves without booting any
 * Quarkus context. Tests assert absolute expected values and verify delegation.
 */
class PosStateTest {

    /**
     * Builds a {@link PosState} whose collaborators are all fresh mocks, wired
     * onto the public fields so tests can both drive and verify them.
     *
     * @return a POS state with mocked sub-states
     */
    private PosState newState() {
        PosState state = new PosState();
        state.ticket = mock(TicketState.class);
        state.payment = mock(PaymentState.class);
        state.fidelity = mock(FidelityState.class);
        state.auth = mock(AuthState.class);
        state.priceModState = mock(PriceModState.class);
        return state;
    }

    /**
     * Builds a list holding the requested number of distinct ticket lines, used
     * to populate the mocked ticket's public {@code items} field.
     *
     * @param count the number of lines to create
     * @return a mutable list of that many ticket items
     */
    private List<TicketState.TicketItem> items(int count) {
        List<TicketState.TicketItem> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new TicketState.TicketItem());
        }
        return list;
    }

    /**
     * {@code touch()} bumps the polling version counter by exactly one.
     */
    @Test
    void touchIncrementsVersion() {
        PosState state = newState();
        state.version = 41;
        state.touch();
        assertEquals(42, state.version);
    }

    /**
     * {@code clearTicket()} delegates to every transactional sub-state and
     * resets the cross-cutting per-sale fields to their pristine values.
     */
    @Test
    void clearTicketResetsAllTransactionalState() {
        PosState state = newState();
        state.selectedTicketIndex = 3;
        state.lastEnteredItemId = "abc";
        state.donationLineUid = "don";
        state.ticketCurrentPage = 5;
        state.clearTicket();
        verify(state.ticket).clear();
        verify(state.fidelity).clear();
        verify(state.payment).reset();
        verify(state.priceModState).clear();
        assertEquals(-1, state.selectedTicketIndex);
        assertNull(state.lastEnteredItemId);
        assertNull(state.donationLineUid);
        assertEquals(0, state.ticketCurrentPage);
    }

    /**
     * {@code clearPayments()} clears only the payments and bumps the version.
     */
    @Test
    void clearPaymentsDelegatesAndTouches() {
        PosState state = newState();
        state.version = 7;
        state.clearPayments();
        verify(state.payment).clearPayments();
        assertEquals(8, state.version);
    }

    /**
     * {@code getRemaining()} subtracts the paid amount from the ticket total and
     * rounds HALF_UP to two decimals.
     */
    @Test
    void getRemainingSubtractsAndRoundsHalfUp() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("10.005");
        state.payment.paidAmount = new BigDecimal("3.00");
        assertEquals(new BigDecimal("7.01"), state.getRemaining());
    }

    /**
     * {@code getRemainingFormatted()} renders the remaining due with a French
     * comma decimal separator.
     */
    @Test
    void getRemainingFormattedUsesComma() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.01");
        state.payment.paidAmount = BigDecimal.ZERO;
        assertEquals("7,01", state.getRemainingFormatted());
    }

    /**
     * {@code getRemainingNumpad()} formats the remaining due through
     * {@code String.format("%.2f", ...)} with no explicit locale, so the decimal
     * separator follows the JVM default locale (a comma under fr-FR here).
     */
    @Test
    void getRemainingNumpadFormatsWithDefaultLocale() {
        PosState state = newState();
        state.ticket.totalAmount = new BigDecimal("7.01");
        state.payment.paidAmount = BigDecimal.ZERO;
        assertEquals(String.format("%.2f", new BigDecimal("7.01")), state.getRemainingNumpad());
    }

    /**
     * {@code isLocked()} reflects a locked authentication state.
     */
    @Test
    void isLockedTrueWhenAuthLocked() {
        PosState state = newState();
        state.auth.isLocked = true;
        assertTrue(state.isLocked());
    }

    /**
     * {@code isLocked()} reflects an unlocked authentication state.
     */
    @Test
    void isLockedFalseWhenAuthUnlocked() {
        PosState state = newState();
        state.auth.isLocked = false;
        assertFalse(state.isLocked());
    }

    /**
     * {@code getOperatorName()} returns the authenticated operator's name.
     */
    @Test
    void getOperatorNameReturnsAuthName() {
        PosState state = newState();
        state.auth.operatorName = "Alice";
        assertEquals("Alice", state.getOperatorName());
    }

    /**
     * {@code getTargetItem()} returns the explicitly selected line when the
     * selection index is within bounds (both {@code >=0} and {@code <size} true).
     */
    @Test
    void getTargetItemReturnsSelectedWhenInBounds() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = 0;
        assertSame(list.get(0), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} falls back to the last line when nothing is
     * selected (index {@code >=0} false) but the ticket is not empty.
     */
    @Test
    void getTargetItemReturnsLastWhenNoSelection() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = -1;
        assertSame(list.get(1), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} falls back to the last line when the selection
     * index is out of range (index {@code >=0} true but {@code <size} false).
     */
    @Test
    void getTargetItemReturnsLastWhenSelectionOutOfRange() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(2);
        state.ticket.items = list;
        state.selectedTicketIndex = 5;
        assertSame(list.get(1), state.getTargetItem());
    }

    /**
     * {@code getTargetItem()} returns null when the ticket has no lines.
     */
    @Test
    void getTargetItemReturnsNullWhenEmpty() {
        PosState state = newState();
        state.ticket.items = items(0);
        state.selectedTicketIndex = -1;
        assertNull(state.getTargetItem());
    }

    /**
     * {@code getSelectedItem()} returns the selected line when the index is in
     * bounds (both conditions true).
     */
    @Test
    void getSelectedItemReturnsSelectedWhenInBounds() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(1);
        state.ticket.items = list;
        state.selectedTicketIndex = 0;
        assertSame(list.get(0), state.getSelectedItem());
    }

    /**
     * {@code getSelectedItem()} returns null when nothing is selected (index
     * {@code >=0} false).
     */
    @Test
    void getSelectedItemReturnsNullWhenNoSelection() {
        PosState state = newState();
        state.ticket.items = items(1);
        state.selectedTicketIndex = -1;
        assertNull(state.getSelectedItem());
    }

    /**
     * {@code getSelectedItem()} returns null when the selection index is out of
     * range (index {@code >=0} true but {@code <size} false).
     */
    @Test
    void getSelectedItemReturnsNullWhenOutOfRange() {
        PosState state = newState();
        state.ticket.items = items(1);
        state.selectedTicketIndex = 5;
        assertNull(state.getSelectedItem());
    }

    /**
     * {@code getVisibleItems()} returns an empty list when the ticket is empty.
     */
    @Test
    void getVisibleItemsEmptyWhenNoLines() {
        PosState state = newState();
        state.ticket.items = items(0);
        assertTrue(state.getVisibleItems().isEmpty());
    }

    /**
     * {@code getVisibleItems()} returns the current page slice when the page
     * index is within range (no clamping needed).
     */
    @Test
    void getVisibleItemsReturnsPageSlice() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(3);
        state.ticket.items = list;
        state.ticketCurrentPage = 0;
        List<TicketState.TicketItem> visible = state.getVisibleItems();
        assertEquals(3, visible.size());
        assertSame(list.get(0), visible.get(0));
    }

    /**
     * {@code getVisibleItems()} clamps an over-large page index down to the last
     * page before slicing.
     */
    @Test
    void getVisibleItemsClampsPageBeyondMax() {
        PosState state = newState();
        List<TicketState.TicketItem> list = items(3);
        state.ticket.items = list;
        state.ticketCurrentPage = 5;
        List<TicketState.TicketItem> visible = state.getVisibleItems();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(3, visible.size());
    }

    /**
     * {@code isHasPreviousPage()} is false on the first page.
     */
    @Test
    void hasPreviousPageFalseOnFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 0;
        assertFalse(state.isHasPreviousPage());
    }

    /**
     * {@code isHasPreviousPage()} is true past the first page.
     */
    @Test
    void hasPreviousPageTrueBeyondFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 1;
        assertTrue(state.isHasPreviousPage());
    }

    /**
     * {@code isHasNextPage()} is false when the current page shows the last line.
     */
    @Test
    void hasNextPageFalseWhenLastPage() {
        PosState state = newState();
        state.ticket.items = items(3);
        state.ticketCurrentPage = 0;
        assertFalse(state.isHasNextPage());
    }

    /**
     * {@code isHasNextPage()} is true when further lines follow the current page.
     */
    @Test
    void hasNextPageTrueWhenMoreLines() {
        PosState state = newState();
        state.ticket.items = items(10);
        state.ticketCurrentPage = 0;
        assertTrue(state.isHasNextPage());
    }

    /**
     * {@code getTicketCurrentPageDisplay()} returns the 1-based page number.
     */
    @Test
    void currentPageDisplayIsOneBased() {
        PosState state = newState();
        state.ticketCurrentPage = 2;
        assertEquals(3, state.getTicketCurrentPageDisplay());
    }

    /**
     * {@code getTicketTotalPages()} returns one page for an empty ticket.
     */
    @Test
    void totalPagesIsOneWhenEmpty() {
        PosState state = newState();
        state.ticket.items = items(0);
        assertEquals(1, state.getTicketTotalPages());
    }

    /**
     * {@code getTicketTotalPages()} rounds the line count up to a whole number
     * of pages.
     */
    @Test
    void totalPagesCeilsLineCount() {
        PosState state = newState();
        state.ticket.items = items(7);
        assertEquals(2, state.getTicketTotalPages());
    }

    /**
     * {@code nextPage()} advances the page and bumps the version when a next
     * page exists.
     */
    @Test
    void nextPageAdvancesWhenAvailable() {
        PosState state = newState();
        state.ticket.items = items(10);
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.nextPage();
        assertEquals(1, state.ticketCurrentPage);
        assertEquals(1, state.version);
    }

    /**
     * {@code nextPage()} is a no-op with no version bump when no next page
     * exists.
     */
    @Test
    void nextPageNoopWhenLastPage() {
        PosState state = newState();
        state.ticket.items = items(3);
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.nextPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(0, state.version);
    }

    /**
     * {@code prevPage()} steps back and bumps the version when a previous page
     * exists.
     */
    @Test
    void prevPageStepsBackWhenAvailable() {
        PosState state = newState();
        state.ticketCurrentPage = 1;
        state.version = 0;
        state.prevPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(1, state.version);
    }

    /**
     * {@code prevPage()} is a no-op with no version bump on the first page.
     */
    @Test
    void prevPageNoopOnFirstPage() {
        PosState state = newState();
        state.ticketCurrentPage = 0;
        state.version = 0;
        state.prevPage();
        assertEquals(0, state.ticketCurrentPage);
        assertEquals(0, state.version);
    }

    /**
     * The freshly constructed state exposes its pristine defaults and touches no
     * collaborator before any action is taken.
     */
    @Test
    void constructorLeavesPristineDefaults() {
        PosState state = newState();
        assertEquals(-1, state.selectedTicketIndex);
        assertEquals(0, state.version);
        assertFalse(state.trainingMode);
        verifyNoInteractions(state.payment);
    }
}
