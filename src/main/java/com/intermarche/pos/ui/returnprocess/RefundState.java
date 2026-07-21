package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class RefundState implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int PAGE_SIZE = 6;

    public String searchPattern = "";
    public List<Ticket> foundTickets = new ArrayList<>();
    public Ticket selectedTicket = null;
    public Map<Long, BigDecimal> returnQuantities = new HashMap<>();
    public int detailPage = 0;

    public Long selectedLineId = null;
    public boolean isEditingAmount = false;
    public BigDecimal manualTotalAmount = null;

    public boolean isTicketSelected() {
        return selectedTicket != null;
    }

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

    public BigDecimal getReturnQty(Long lineId) {
        if (lineId == null || returnQuantities == null) return BigDecimal.ZERO;
        return returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
    }

    public boolean hasReturnQty(Long lineId) {
        return getReturnQty(lineId).compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isLineSelected(Long lineId) {
        return lineId != null && lineId.equals(selectedLineId);
    }

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

    public String getTotalRefundFormatted() {
        BigDecimal total = getTotalRefundAmount();
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        return rounded.toPlainString();
    }

    public void clear() {
        searchPattern = "";
        foundTickets.clear();
        selectedTicket = null;
        returnQuantities.clear();
        detailPage = 0;
        selectedLineId = null;
        isEditingAmount = false;
        manualTotalAmount = null;
    }

    public boolean isHasDetailPrev() {
        return detailPage > 0;
    }

    public boolean isHasDetailNext() {
        if (selectedTicket == null || selectedTicket.lines == null) return false;
        return (detailPage + 1) * PAGE_SIZE < selectedTicket.lines.size();
    }

    public int getDetailPageDisplay() {
        return detailPage + 1;
    }

    public int getDetailTotalPages() {
        if (selectedTicket == null || selectedTicket.lines == null || selectedTicket.lines.isEmpty()) return 1;
        return (int) Math.ceil((double) selectedTicket.lines.size() / PAGE_SIZE);
    }
}