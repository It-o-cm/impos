package com.intermarche.pos.ui;

import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.reprintticket.ReprintState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.returnprocess.RefundState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.inject.Singleton;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Singleton
public class PosState implements Serializable {
    private static final long serialVersionUID = 1L;

    public TicketState ticket = new TicketState();
    public PaymentState payment = new PaymentState();
    public FidelityState fidelity = new FidelityState();
    public AuthState auth = new AuthState();
    public EndorsementState endorsement = new EndorsementState();
    public PriceModState priceModState = new PriceModState();
    public ReprintState reprint = new ReprintState();
    public RefundState refund = new RefundState();

    public int selectedTicketIndex = -1;
    public String lastEnteredItemId = null;
    public long version = 0;

    public String returnUrl = null;
    public Long lastClosedTicketId = null;

    // --- Pagination Ticket Courant ---
    private static final int PAGE_SIZE = 6;
    public int ticketCurrentPage = 0;
    public String globalError = null;

    public boolean showSecondaryMenu = false;

    public PosState() {
        this.ticket.setParent(this);
    }

    public void touch() {
        this.version++;
    }

    public void clearTicket() {
        ticket.clear();
        fidelity.clear();
        payment.reset();
        selectedTicketIndex = -1;
        lastEnteredItemId = null;
        priceModState.clear();
        ticketCurrentPage = 0;
    }

    public void clearPayments() {
        payment.clearPayments();
        touch();
    }

    // --- Calculs ---

    public double getRemaining() {
        return ticket.totalAmount - payment.paidAmount;
    }

    public String getRemainingFormatted() {
        return String.format("%.2f", getRemaining()).replace(".", ",");
    }

    public String getRemainingNumpad() {
        return String.format("%.2f", getRemaining());
    }

    public boolean isLocked() {
        return auth.isLocked;
    }
    public String getOperatorName() { return auth.operatorName; }

    public TicketState.TicketItem getTargetItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        if (!ticket.items.isEmpty()) {
            return ticket.items.get(ticket.items.size() - 1);
        }
        return null;
    }

    public TicketState.TicketItem getSelectedItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        return null;
    }

    public List<TicketState.TicketItem> getVisibleItems() {
        if (ticket.items.isEmpty()) return Collections.emptyList();
        int maxPage = Math.max(0, (ticket.items.size() - 1) / PAGE_SIZE);
        if (ticketCurrentPage > maxPage) ticketCurrentPage = maxPage;
        int fromIndex = ticketCurrentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, ticket.items.size());
        if (fromIndex >= ticket.items.size()) return Collections.emptyList();
        return ticket.items.subList(fromIndex, toIndex);
    }

    public boolean isHasPreviousPage() { return ticketCurrentPage > 0; }
    public boolean isHasNextPage() { return (ticketCurrentPage + 1) * PAGE_SIZE < ticket.items.size(); }
    public int getTicketCurrentPageDisplay() { return ticketCurrentPage + 1; }
    public int getTicketTotalPages() {
        if (ticket.items.isEmpty()) return 1;
        return (int) Math.ceil((double) ticket.items.size() / PAGE_SIZE);
    }

    public void nextPage() { if (isHasNextPage()) { ticketCurrentPage++; touch(); } }
    public void prevPage() { if (isHasPreviousPage()) { ticketCurrentPage--; touch(); } }
}