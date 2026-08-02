package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RefundState}.
 * <p>
 * {@link RefundState} is a plain in-memory holder with no collaborators to mock:
 * {@link Ticket} and {@link TicketLine} are used purely as data carriers whose
 * public fields ({@code lines}, {@code id}, {@code unitPrice}) the tests set
 * directly. Every ternary and null guard is exercised on both arms, and the
 * paging arithmetic is checked at its boundaries.
 */
class RefundStateTest {

    /**
     * Builds a {@link TicketLine} with the given database id and unit price.
     *
     * @param id        the register-local line id
     * @param unitPrice the unit price including tax
     * @return a data-only ticket line
     */
    private TicketLine line(Long id, BigDecimal unitPrice) {
        TicketLine l = new TicketLine();
        l.id = id;
        l.unitPrice = unitPrice;
        return l;
    }

    /**
     * Builds a {@link Ticket} carrying the given lines.
     *
     * @param lines the ticket lines, or null
     * @return a data-only ticket
     */
    private Ticket ticket(List<TicketLine> lines) {
        Ticket t = new Ticket();
        t.lines = lines;
        return t;
    }

    /**
     * isTicketSelected returns false when no ticket is selected.
     */
    @Test
    void isTicketSelectedFalseWhenNull() {
        RefundState s = new RefundState();
        s.selectedTicket = null;
        assertFalse(s.isTicketSelected());
    }

    /**
     * isTicketSelected returns true when a ticket is selected.
     */
    @Test
    void isTicketSelectedTrueWhenSet() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(new ArrayList<>());
        assertTrue(s.isTicketSelected());
    }

    /**
     * getVisibleLines returns an empty list when no ticket is selected.
     */
    @Test
    void getVisibleLinesEmptyWhenNoTicket() {
        RefundState s = new RefundState();
        s.selectedTicket = null;
        assertTrue(s.getVisibleLines().isEmpty());
    }

    /**
     * getVisibleLines returns an empty list when the selected ticket has null lines.
     */
    @Test
    void getVisibleLinesEmptyWhenLinesNull() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(null);
        assertTrue(s.getVisibleLines().isEmpty());
    }

    /**
     * getVisibleLines returns an empty list when the lines list is empty
     * (from index reaches the size boundary).
     */
    @Test
    void getVisibleLinesEmptyWhenNoLines() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(new ArrayList<>());
        assertTrue(s.getVisibleLines().isEmpty());
    }

    /**
     * getVisibleLines returns the whole page when all lines fit and the page
     * index is within range (detailPage not beyond max).
     */
    @Test
    void getVisibleLinesFirstPage() {
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(line((long) i, BigDecimal.ONE));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(lines);
        s.detailPage = 0;
        List<TicketLine> visible = s.getVisibleLines();
        assertEquals(6, visible.size());
        assertSame(lines.get(0), visible.get(0));
        assertSame(lines.get(5), visible.get(5));
    }

    /**
     * getVisibleLines clamps an out-of-range detailPage back to the last page
     * and returns the remaining lines.
     */
    @Test
    void getVisibleLinesClampsOverflowPage() {
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(line((long) i, BigDecimal.ONE));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(lines);
        s.detailPage = 5;
        List<TicketLine> visible = s.getVisibleLines();
        assertEquals(1, s.detailPage);
        assertEquals(2, visible.size());
        assertSame(lines.get(6), visible.get(0));
        assertSame(lines.get(7), visible.get(1));
    }

    /**
     * getReturnQty returns ZERO when the line id is null.
     */
    @Test
    void getReturnQtyZeroWhenLineIdNull() {
        RefundState s = new RefundState();
        assertEquals(BigDecimal.ZERO, s.getReturnQty(null));
    }

    /**
     * getReturnQty returns ZERO when the quantities map is null.
     */
    @Test
    void getReturnQtyZeroWhenMapNull() {
        RefundState s = new RefundState();
        s.returnQuantities = null;
        assertEquals(BigDecimal.ZERO, s.getReturnQty(1L));
    }

    /**
     * getReturnQty returns ZERO when the line id is absent from the map.
     */
    @Test
    void getReturnQtyZeroWhenAbsent() {
        RefundState s = new RefundState();
        assertEquals(BigDecimal.ZERO, s.getReturnQty(1L));
    }

    /**
     * getReturnQty returns the stored quantity when present in the map.
     */
    @Test
    void getReturnQtyReturnsStored() {
        RefundState s = new RefundState();
        s.returnQuantities.put(1L, new BigDecimal("3"));
        assertEquals(new BigDecimal("3"), s.getReturnQty(1L));
    }

    /**
     * hasReturnQty returns false when the refund quantity is zero.
     */
    @Test
    void hasReturnQtyFalseWhenZero() {
        RefundState s = new RefundState();
        s.returnQuantities.put(1L, BigDecimal.ZERO);
        assertFalse(s.hasReturnQty(1L));
    }

    /**
     * hasReturnQty returns true when the refund quantity is strictly positive.
     */
    @Test
    void hasReturnQtyTrueWhenPositive() {
        RefundState s = new RefundState();
        s.returnQuantities.put(1L, new BigDecimal("2"));
        assertTrue(s.hasReturnQty(1L));
    }

    /**
     * isLineSelected returns false when the line id is null.
     */
    @Test
    void isLineSelectedFalseWhenLineIdNull() {
        RefundState s = new RefundState();
        s.selectedLineId = 5L;
        assertFalse(s.isLineSelected(null));
    }

    /**
     * isLineSelected returns false when the line id differs from the selection.
     */
    @Test
    void isLineSelectedFalseWhenDifferent() {
        RefundState s = new RefundState();
        s.selectedLineId = 5L;
        assertFalse(s.isLineSelected(6L));
    }

    /**
     * isLineSelected returns true when the line id equals the selection.
     */
    @Test
    void isLineSelectedTrueWhenEqual() {
        RefundState s = new RefundState();
        s.selectedLineId = 5L;
        assertTrue(s.isLineSelected(5L));
    }

    /**
     * getTotalRefundAmount returns the manual amount when one is typed.
     */
    @Test
    void getTotalRefundAmountReturnsManual() {
        RefundState s = new RefundState();
        s.manualTotalAmount = new BigDecimal("12.34");
        assertEquals(new BigDecimal("12.34"), s.getTotalRefundAmount());
    }

    /**
     * getTotalRefundAmount returns ZERO when no manual amount and no ticket.
     */
    @Test
    void getTotalRefundAmountZeroWhenNoTicket() {
        RefundState s = new RefundState();
        s.manualTotalAmount = null;
        s.selectedTicket = null;
        assertEquals(BigDecimal.ZERO, s.getTotalRefundAmount());
    }

    /**
     * getTotalRefundAmount sums only the lines with a strictly positive refund
     * quantity, skipping the zero-quantity ones.
     */
    @Test
    void getTotalRefundAmountSumsPositiveLines() {
        TicketLine a = line(1L, new BigDecimal("2.00"));
        TicketLine b = line(2L, new BigDecimal("5.00"));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(Arrays.asList(a, b));
        s.returnQuantities.put(1L, new BigDecimal("3"));
        s.returnQuantities.put(2L, BigDecimal.ZERO);
        assertEquals(new BigDecimal("6.00"), s.getTotalRefundAmount());
    }

    /**
     * getTotalRefundFormatted rounds the total to two decimals (HALF_UP).
     */
    @Test
    void getTotalRefundFormattedRoundsHalfUp() {
        RefundState s = new RefundState();
        s.manualTotalAmount = new BigDecimal("1.005");
        assertEquals("1.01", s.getTotalRefundFormatted());
    }

    /**
     * clear resets every field to its initial value.
     */
    @Test
    void clearResetsEverything() {
        RefundState s = new RefundState();
        s.searchPattern = "abc";
        s.foundTickets.add(ticket(new ArrayList<>()));
        s.selectedTicket = ticket(new ArrayList<>());
        s.returnQuantities.put(1L, BigDecimal.ONE);
        s.detailPage = 3;
        s.selectedLineId = 4L;
        s.isEditingAmount = true;
        s.manualTotalAmount = BigDecimal.TEN;
        s.errorMessage = "err";
        s.clear();
        assertEquals("", s.searchPattern);
        assertTrue(s.foundTickets.isEmpty());
        assertNull(s.selectedTicket);
        assertTrue(s.returnQuantities.isEmpty());
        assertEquals(0, s.detailPage);
        assertNull(s.selectedLineId);
        assertFalse(s.isEditingAmount);
        assertNull(s.manualTotalAmount);
        assertNull(s.errorMessage);
    }

    /**
     * isHasDetailPrev returns false on the first page.
     */
    @Test
    void isHasDetailPrevFalseOnFirstPage() {
        RefundState s = new RefundState();
        s.detailPage = 0;
        assertFalse(s.isHasDetailPrev());
    }

    /**
     * isHasDetailPrev returns true past the first page.
     */
    @Test
    void isHasDetailPrevTrueAfterFirstPage() {
        RefundState s = new RefundState();
        s.detailPage = 1;
        assertTrue(s.isHasDetailPrev());
    }

    /**
     * isHasDetailNext returns false when no ticket is selected.
     */
    @Test
    void isHasDetailNextFalseWhenNoTicket() {
        RefundState s = new RefundState();
        s.selectedTicket = null;
        assertFalse(s.isHasDetailNext());
    }

    /**
     * isHasDetailNext returns false when the selected ticket has null lines.
     */
    @Test
    void isHasDetailNextFalseWhenLinesNull() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(null);
        assertFalse(s.isHasDetailNext());
    }

    /**
     * isHasDetailNext returns true when more lines follow the current page.
     */
    @Test
    void isHasDetailNextTrueWhenMoreLines() {
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(line((long) i, BigDecimal.ONE));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(lines);
        s.detailPage = 0;
        assertTrue(s.isHasDetailNext());
    }

    /**
     * isHasDetailNext returns false when the current page is the last.
     */
    @Test
    void isHasDetailNextFalseWhenLastPage() {
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) lines.add(line((long) i, BigDecimal.ONE));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(lines);
        s.detailPage = 1;
        assertFalse(s.isHasDetailNext());
    }

    /**
     * getDetailPageDisplay returns the 1-based page number.
     */
    @Test
    void getDetailPageDisplayIsOneBased() {
        RefundState s = new RefundState();
        s.detailPage = 2;
        assertEquals(3, s.getDetailPageDisplay());
    }

    /**
     * getDetailTotalPages returns one when no ticket is selected.
     */
    @Test
    void getDetailTotalPagesOneWhenNoTicket() {
        RefundState s = new RefundState();
        s.selectedTicket = null;
        assertEquals(1, s.getDetailTotalPages());
    }

    /**
     * getDetailTotalPages returns one when the selected ticket has null lines.
     */
    @Test
    void getDetailTotalPagesOneWhenLinesNull() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(null);
        assertEquals(1, s.getDetailTotalPages());
    }

    /**
     * getDetailTotalPages returns one when the lines list is empty.
     */
    @Test
    void getDetailTotalPagesOneWhenEmpty() {
        RefundState s = new RefundState();
        s.selectedTicket = ticket(new ArrayList<>());
        assertEquals(1, s.getDetailTotalPages());
    }

    /**
     * getDetailTotalPages ceils the line count over the page size.
     */
    @Test
    void getDetailTotalPagesCeilsCount() {
        List<TicketLine> lines = new ArrayList<>();
        for (int i = 0; i < 7; i++) lines.add(line((long) i, BigDecimal.ONE));
        RefundState s = new RefundState();
        s.selectedTicket = ticket(lines);
        assertEquals(2, s.getDetailTotalPages());
    }
}
