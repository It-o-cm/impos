package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * In-memory state of the refund screen: ticket search, selected ticket,
 * per-line refund quantities, manual amount and paging.
 * <p>
 * Phase 3 lot 4: carries the refusal message of the double-refund and
 * ticket-cap guards.
 * <p>
 * Everything here is PRE-DECISION staging: searched tickets, the selected
 * one, the per-line quantities the cashier dials and the manual amount are
 * all inert until a refund method is endorsed — the parked-gesture pattern
 * again, with the staging on this state instead of an action string. The
 * per-line quantities key on the ORIGINAL line ids (register-local), which
 * is fine here precisely because refunds run on the ticket's own register
 * (mono-register decision of phase 3).
 */
public class RefundState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Number of ticket lines per detail page. */
    private static final int PAGE_SIZE = 6;

    /** The typed search pattern. */
    public String searchPattern = "";

    /** The tickets found by the search. */
    public List<Ticket> foundTickets = new ArrayList<>();

    /** The ticket being refunded, or null. */
    public Ticket selectedTicket = null;

    /** The refund quantities keyed by original line id. */
    public Map<Long, BigDecimal> returnQuantities = new HashMap<>();

    /** The current detail page. */
    public int detailPage = 0;

    /** The line selected for direct quantity typing, or null. */
    public Long selectedLineId = null;

    /** True while the global amount is being typed. */
    public boolean isEditingAmount = false;

    /** The manually typed global amount, or null. */
    public BigDecimal manualTotalAmount = null;

    /** The refusal message of the refund guards, or null. */
    public String errorMessage = null;

    /**
     * Indicates whether a ticket is selected (detail mode).
     *
     * @return true when a ticket is selected
     */
    public boolean isTicketSelected() {
        return selectedTicket != null;
    }

    /**
     * Returns the ticket lines visible on the current detail page.
     *
     * @return the sublist of lines for the current page
     */
    public List<TicketLine> getVisibleLines() {
        if (selectedTicket == null || selectedTicket.lines == null) return Collections.emptyList();
        List<TicketLine> allLines = selectedTicket.lines;

        int maxPage = Math.max(0, (allLines.size() - 1) / PAGE_SIZE);
        if (detailPage > maxPage) detailPage = maxPage;

        int from = detailPage * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, allLines.size());

        if (from >= allLines.size()) return Collections.emptyList();
        return allLines.subList(from, to);
    }

    /**
     * Returns the refund quantity of a line.
     *
     * @param lineId the database id of the line
     * @return the refund quantity, or ZERO
     */
    public BigDecimal getReturnQty(Long lineId) {
        if (lineId == null || returnQuantities == null) return BigDecimal.ZERO;
        return returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
    }

    /**
     * Indicates whether a line has a positive refund quantity.
     *
     * @param lineId the database id of the line
     * @return true when the refund quantity is strictly positive
     */
    public boolean hasReturnQty(Long lineId) {
        return getReturnQty(lineId).compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Indicates whether the given line is selected for typing.
     *
     * @param lineId the database id of the line
     * @return true when the line is selected
     */
    public boolean isLineSelected(Long lineId) {
        return lineId != null && lineId.equals(selectedLineId);
    }

    /**
     * Returns the refund total: the manual amount when typed, otherwise the
     * sum of the selected line quantities at their original unit prices.
     *
     * @return the refund total including tax
     */
    public BigDecimal getTotalRefundAmount() {
        if (manualTotalAmount != null) return manualTotalAmount;

        if (selectedTicket == null) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (TicketLine line : selectedTicket.lines) {
            BigDecimal qty = returnQuantities.getOrDefault(line.id, BigDecimal.ZERO);
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(line.unitPrice.multiply(qty));
            }
        }
        return total;
    }

    /**
     * Returns the refund total formatted for display (2 decimals).
     *
     * @return the formatted refund total
     */
    public String getTotalRefundFormatted() {
        BigDecimal total = getTotalRefundAmount();
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        return rounded.toPlainString();
    }

    /**
     * Clears the whole refund state.
     */
    public void clear() {
        searchPattern = "";
        foundTickets.clear();
        selectedTicket = null;
        returnQuantities.clear();
        detailPage = 0;
        selectedLineId = null;
        isEditingAmount = false;
        manualTotalAmount = null;
        errorMessage = null;
    }

    /**
     * Indicates whether a previous detail page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasDetailPrev() {
        return detailPage > 0;
    }

    /**
     * Indicates whether a next detail page exists.
     *
     * @return true if more lines follow the current page
     */
    public boolean isHasDetailNext() {
        if (selectedTicket == null || selectedTicket.lines == null) return false;
        return (detailPage + 1) * PAGE_SIZE < selectedTicket.lines.size();
    }

    /**
     * Returns the 1-based current detail page number for display.
     *
     * @return the current page number
     */
    public int getDetailPageDisplay() {
        return detailPage + 1;
    }

    /**
     * Returns the total number of detail pages (at least one).
     *
     * @return the page count
     */
    public int getDetailTotalPages() {
        if (selectedTicket == null || selectedTicket.lines == null || selectedTicket.lines.isEmpty()) return 1;
        return (int) Math.ceil((double) selectedTicket.lines.size() / PAGE_SIZE);
    }
}
