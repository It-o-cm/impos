package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class ReprintState implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int LIST_PAGE_SIZE = 6;
    private static final int DETAIL_PAGE_SIZE = 6;

    // Données liste
    public List<Ticket> tickets = Collections.emptyList();
    public int listPage = 0;

    // Données détail
    public Ticket viewedTicket = null;
    public int detailPage = 0;

    // --- Logique Liste ---

    public void setTickets(List<Ticket> tickets) {
        this.tickets = tickets;
        this.listPage = 0;
        this.viewedTicket = null;
        this.detailPage = 0;
    }

    public boolean isHasListPrev() { return listPage > 0; }
    public boolean isHasListNext() { return (listPage + 1) * LIST_PAGE_SIZE < tickets.size(); }

    public List<Ticket> getVisibleTickets() {
        if (tickets.isEmpty()) return Collections.emptyList();
        int from = listPage * LIST_PAGE_SIZE;
        int to = Math.min(from + LIST_PAGE_SIZE, tickets.size());
        if (from >= tickets.size()) return Collections.emptyList();
        return tickets.subList(from, to);
    }

    public int getListPageDisplay() { return listPage + 1; }

    // --- Logique Détail ---

    public void setViewedTicket(Ticket ticket) {
        this.viewedTicket = ticket;
        this.detailPage = 0;
    }

    public boolean isHasDetailPrev() { return detailPage > 0; }
    public boolean isHasDetailNext() {
        if (viewedTicket == null || viewedTicket.lines == null) return false;
        return (detailPage + 1) * DETAIL_PAGE_SIZE < viewedTicket.lines.size();
    }

    public List<TicketLine> getVisibleLines() {
        if (viewedTicket == null || viewedTicket.lines == null) return Collections.emptyList();
        List<TicketLine> allLines = viewedTicket.lines;

        int maxPage = Math.max(0, (allLines.size() - 1) / DETAIL_PAGE_SIZE);
        if (detailPage > maxPage) detailPage = maxPage;

        int from = detailPage * DETAIL_PAGE_SIZE;
        int to = Math.min(from + DETAIL_PAGE_SIZE, allLines.size());
        if (from >= allLines.size()) return Collections.emptyList();
        return allLines.subList(from, to);
    }

    public int getDetailPageDisplay() { return detailPage + 1; }
    public int getDetailTotalPages() {
        if (viewedTicket == null || viewedTicket.lines == null || viewedTicket.lines.isEmpty()) return 1;
        return (int) Math.ceil((double) viewedTicket.lines.size() / DETAIL_PAGE_SIZE);
    }
}