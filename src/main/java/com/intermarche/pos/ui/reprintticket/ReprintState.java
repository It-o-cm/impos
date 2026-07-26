package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * In-memory state of the reprint screen: paged closed-ticket history and
 * paged detail of the ticket under review.
 * <p>
 * The tickets shown are PERSISTED entities loaded by the service — the
 * state pages over them, it never copies them, so what the cashier reviews
 * is exactly what the duplicata will print. Six rows per page on both
 * levels: the touch-screen constraint that shaped every list of the
 * register. The detail page self-clamps when a shorter ticket is opened
 * after a longer one (stale page index is corrected on read, not on set).
 */
public class ReprintState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Rows per page of the closed-ticket list (touch-screen sizing). */
    private static final int LIST_PAGE_SIZE = 6;

    /** Rows per page of the ticket detail (touch-screen sizing). */
    private static final int DETAIL_PAGE_SIZE = 6;

    // Données liste
    public List<Ticket> tickets = Collections.emptyList();
    public int listPage = 0;

    // Données détail
    public Ticket viewedTicket = null;
    public int detailPage = 0;

    // --- Logique Liste ---

    /**
     * Installs a fresh history and resets both pagings and the viewed ticket.
     *
     * @param tickets the closed tickets, most recent first
     */
    public void setTickets(List<Ticket> tickets) {
        this.tickets = tickets;
        this.listPage = 0;
        this.viewedTicket = null;
        this.detailPage = 0;
    }

    /**
     * Tells whether a previous list page exists.
     *
     * @return true when the list can page back
     */
    public boolean isHasListPrev() { return listPage > 0; }
    /**
     * Tells whether a next list page exists.
     *
     * @return true when the list can page forward
     */
    public boolean isHasListNext() { return (listPage + 1) * LIST_PAGE_SIZE < tickets.size(); }

    /**
     * Returns the tickets of the current list page.
     *
     * @return the visible slice of the history
     */
    public List<Ticket> getVisibleTickets() {
        if (tickets.isEmpty()) return Collections.emptyList();
        int from = listPage * LIST_PAGE_SIZE;
        int to = Math.min(from + LIST_PAGE_SIZE, tickets.size());
        if (from >= tickets.size()) return Collections.emptyList();
        return tickets.subList(from, to);
    }

    /**
     * Returns the 1-based list page number for display.
     *
     * @return the displayed page number
     */
    public int getListPageDisplay() { return listPage + 1; }

    // --- Logique Détail ---

    /**
     * Opens a ticket in the detail view, resetting its paging.
     *
     * @param ticket the persisted ticket to review
     */
    public void setViewedTicket(Ticket ticket) {
        this.viewedTicket = ticket;
        this.detailPage = 0;
    }

    /**
     * Tells whether a previous detail page exists.
     *
     * @return true when the detail can page back
     */
    public boolean isHasDetailPrev() { return detailPage > 0; }
    /**
     * Tells whether a next detail page exists.
     *
     * @return true when the detail can page forward
     */
    public boolean isHasDetailNext() {
        if (viewedTicket == null || viewedTicket.lines == null) return false;
        return (detailPage + 1) * DETAIL_PAGE_SIZE < viewedTicket.lines.size();
    }

    /**
     * Returns the lines of the current detail page, clamping a stale page
     * index left by a previously viewed longer ticket.
     *
     * @return the visible slice of the ticket's lines
     */
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

    /**
     * Returns the 1-based detail page number for display.
     *
     * @return the displayed page number
     */
    public int getDetailPageDisplay() { return detailPage + 1; }
    /**
     * Returns the total number of detail pages (at least one).
     *
     * @return the detail page count
     */
    public int getDetailTotalPages() {
        if (viewedTicket == null || viewedTicket.lines == null || viewedTicket.lines.isEmpty()) return 1;
        return (int) Math.ceil((double) viewedTicket.lines.size() / DETAIL_PAGE_SIZE);
    }
}