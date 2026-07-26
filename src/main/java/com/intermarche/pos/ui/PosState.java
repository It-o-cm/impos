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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * Global state of this POS terminal.
 * <p>
 * One executable serves exactly one register (one process = one terminal),
 * therefore a {@link Singleton} scope is legitimate here.
 * All monetary computations are {@link BigDecimal} (phase 0).
 * <p>
 * This singleton is the COMPOSITION ROOT of every in-memory state: ticket,
 * payment, fidelity, auth, endorsement, price-mod modal, reprint and refund
 * screens all hang off it, and cross-cutting flags (training mode, drawer
 * return URL, selection, donation line) live directly on it. Two contracts
 * every contributor must know: {@code version}/{@code touch()} is the
 * reactive heartbeat — a mutation without a touch is invisible to every
 * polling screen; and {@code clearTicket()} is the END-OF-SALE broom — it
 * resets the whole transactional sub-state (ticket, fidelity, payment,
 * selection, modal) and any new per-sale field MUST be added to it, or it
 * will leak into the next customer's sale. Memory only: the durable truth
 * is the draft, this object is rebuilt from it at recovery.
 */
@Singleton
public class PosState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The ticket being built. */
    public TicketState ticket = new TicketState();

    /** The payment in progress. */
    public PaymentState payment = new PaymentState();

    /** The fidelity card state. */
    public FidelityState fidelity = new FidelityState();

    /** The authentication state. */
    public AuthState auth = new AuthState();

    /** The manager endorsement state. */
    public EndorsementState endorsement = new EndorsementState();

    /** The price-modification modal state. */
    public PriceModState priceModState = new PriceModState();

    /** The reprint screen state. */
    public ReprintState reprint = new ReprintState();

    /** The refund screen state. */
    public RefundState refund = new RefundState();

    /** Index of the selected ticket line, or -1. */
    public int selectedTicketIndex = -1;

    /** Uid of the last entered line (cancellable without endorsement), or null. */
    public String lastEnteredItemId = null;


    /** Uid of the solidarity round-up line of the current ticket, or null. */
    public String donationLineUid = null;

    /**
     * True while the register runs in training mode: nothing is persisted
     * (no draft, no ticket number, no fiscal chain, no sync), the drawer
     * stays shut, refunds and session actions are blocked, and every screen
     * shows the training banner. Toggled under manager endorsement with an
     * empty cart.
     */
    public boolean trainingMode = false;

    /** Version counter used by the UI polling. */
    public long version = 0;

    /** Return URL stored while the drawer-open screen is shown. */
    public String returnUrl = null;

    /** Database id of the last closed ticket, or null. */
    public Long lastClosedTicketId = null;

    // --- Current ticket pagination ---

    /** Number of ticket lines per page. */
    private static final int PAGE_SIZE = 6;

    /** Current page of the ticket display. */
    public int ticketCurrentPage = 0;

    /** Global error message, or null. */
    public String globalError = null;

    /** True when the secondary menu is shown. */
    public boolean showSecondaryMenu = false;

    /**
     * Creates the POS state and wires the ticket back-reference.
     */
    public PosState() {
        this.ticket.setParent(this);
    }

    /**
     * Bumps the version counter so the UI polling refreshes.
     */
    public void touch() {
        this.version++;
    }

    /**
     * Clears the current ticket and every transaction-related sub-state.
     */
    public void clearTicket() {
        ticket.clear();
        fidelity.clear();
        payment.reset();
        selectedTicketIndex = -1;
        lastEnteredItemId = null;
        donationLineUid = null;
        priceModState.clear();
        ticketCurrentPage = 0;
    }

    /**
     * Clears only the registered payments.
     */
    public void clearPayments() {
        payment.clearPayments();
        touch();
    }

    // --- Computations ---

    /**
     * Returns the remaining amount due, rounded to 2 decimals.
     *
     * @return the remaining due (ticket total minus paid amount)
     */
    public BigDecimal getRemaining() {
        return ticket.totalAmount.subtract(payment.paidAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the remaining due formatted for display (French comma).
     *
     * @return the formatted remaining due
     */
    public String getRemainingFormatted() {
        return String.format("%.2f", getRemaining()).replace(".", ",");
    }

    /**
     * Returns the remaining due formatted for the numpad (dot separator).
     *
     * @return the remaining due as a numpad-ready string
     */
    public String getRemainingNumpad() {
        return String.format("%.2f", getRemaining());
    }

    /**
     * Indicates whether the register is locked (no operator logged in).
     *
     * @return true if locked
     */
    public boolean isLocked() {
        return auth.isLocked;
    }

    /**
     * Returns the display name of the logged-in operator.
     *
     * @return the operator name, or an empty string
     */
    public String getOperatorName() { return auth.operatorName; }

    /**
     * Returns the line targeted by contextual actions: the selected line,
     * or the last line when nothing is selected.
     *
     * @return the target line, or null when the ticket is empty
     */
    public TicketState.TicketItem getTargetItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        if (!ticket.items.isEmpty()) {
            return ticket.items.get(ticket.items.size() - 1);
        }
        return null;
    }

    /**
     * Returns the explicitly selected line.
     *
     * @return the selected line, or null when nothing is selected
     */
    public TicketState.TicketItem getSelectedItem() {
        if (selectedTicketIndex >= 0 && selectedTicketIndex < ticket.items.size()) {
            return ticket.items.get(selectedTicketIndex);
        }
        return null;
    }

    /**
     * Returns the ticket lines visible on the current page.
     *
     * @return the sublist of lines for the current page
     */
    public List<TicketState.TicketItem> getVisibleItems() {
        if (ticket.items.isEmpty()) return Collections.emptyList();
        int maxPage = Math.max(0, (ticket.items.size() - 1) / PAGE_SIZE);
        if (ticketCurrentPage > maxPage) ticketCurrentPage = maxPage;
        int fromIndex = ticketCurrentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, ticket.items.size());
        if (fromIndex >= ticket.items.size()) return Collections.emptyList();
        return ticket.items.subList(fromIndex, toIndex);
    }

    /**
     * Indicates whether a previous ticket page exists.
     *
     * @return true if not on the first page
     */
    public boolean isHasPreviousPage() { return ticketCurrentPage > 0; }

    /**
     * Indicates whether a next ticket page exists.
     *
     * @return true if more lines follow the current page
     */
    public boolean isHasNextPage() { return (ticketCurrentPage + 1) * PAGE_SIZE < ticket.items.size(); }

    /**
     * Returns the 1-based current ticket page number for display.
     *
     * @return the current page number
     */
    public int getTicketCurrentPageDisplay() { return ticketCurrentPage + 1; }

    /**
     * Returns the total number of ticket pages (at least one).
     *
     * @return the page count
     */
    public int getTicketTotalPages() {
        if (ticket.items.isEmpty()) return 1;
        return (int) Math.ceil((double) ticket.items.size() / PAGE_SIZE);
    }

    /**
     * Moves to the next ticket page if one exists.
     */
    public void nextPage() { if (isHasNextPage()) { ticketCurrentPage++; touch(); } }

    /**
     * Moves to the previous ticket page if one exists.
     */
    public void prevPage() { if (isHasPreviousPage()) { ticketCurrentPage--; touch(); } }
}
