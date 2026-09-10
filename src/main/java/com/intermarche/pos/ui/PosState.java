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

    /** The pending age-check prompt (server-rendered modal on the sale screen). */
    public AgeCheckState ageCheck = new AgeCheckState();

    /**
     * State of the age-control prompt: a restricted product suspended the
     * add, and the sale waits for the cashier's CONFIRM (ID checked — the
     * parked add replays) or REFUSE (journalized, nothing added). The parked
     * gesture is replayed by KIND: SCAN through the recognition chain, PLU
     * through the weighing add, EAN_QTY through the quantity add.
     */
    public static class AgeCheckState {
        /** Whether the prompt is currently shown. */
        public boolean active = false;
        /** The restricted product's display label. */
        public String productLabel;
        /** The required minimum age. */
        public int threshold;
        /** The replay kind: SCAN, PLU or EAN_QTY. */
        public String kind;
        /** The parked code (scanned code, PLU or EAN). */
        public String code;
        /** The parked quantity (EAN_QTY kind only). */
        public java.math.BigDecimal quantity;

        /** Clears the prompt. */
        public void clear() {
            active = false; productLabel = null; threshold = 0;
            kind = null; code = null; quantity = null;
        }
    }

    /** The reprint screen state. */
    public ReprintState reprint = new ReprintState();

    /** The refund screen state. */
    public RefundState refund = new RefundState();

    /** The invoice screen state (ticket billed, addressee, step). */
    public com.intermarche.pos.ui.invoice.InvoiceState invoice =
            new com.intermarche.pos.ui.invoice.InvoiceState();

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

    /**
     * When the customer display last asked for its data, in epoch millis; 0 while
     * nothing has ever asked.
     * <p>
     * This till's customer display is a SECOND SCREEN showing {@code /customer}, not a
     * line display on the hardware bus. Such a screen has no device to probe: the only
     * evidence it exists is that something is polling its page, once a second. That is
     * what this timestamp records, and what lets the hardware gate report the display
     * as present on a till where no line display is bound. Volatile because the poll
     * and the gate run on different threads.
     */
    public volatile long customerDisplaySeenAt = 0L;

    /**
     * The last message sent to the customer display, empty when there is none.
     * <p>
     * A line display is a device the register WRITES to; the second screen showing
     * {@code /customer} is a page that READS the register. So a message that a VFD
     * would have shown has nowhere to go on this till unless it is kept here for the
     * page to pick up on its next poll. Everything else on that page is derived from
     * the sale; this one field is pushed, because "PAIEMENT REFUSE" or "CONTROLE
     * D'AGE EN COURS" is an event and not a state the cart can be read from.
     */
    public volatile String customerMessage = "";

    /** Database id of the last closed ticket, or null. */
    public Long lastClosedTicketId = null;

    /** The one answer the register gives when a function needs a closed ticket and there is none. */
    public static final String NO_LAST_TICKET = "AUCUN TICKET";

    /** The one answer the register gives when training mode forbids a real document. */
    public static final String TRAINING_FORBIDDEN = "FONCTION INDISPONIBLE EN FORMATION";

    /**
     * THE gate of every function that works on the last closed ticket —
     * reprint, identification barcode, card-receipt duplicate, ticket by
     * email. They all need the same two conditions and used to check them
     * each in its own way: one printed nothing at all and said nothing, two
     * refused with "AUCUN TICKET A IMPRIMER", the fourth opened a whole screen
     * to announce there was no ticket, and training mode was refused with two
     * different sentences. Four functions on the same menu, four behaviours.
     *
     * <p>The caller reads it as a question — "may I?" — and does nothing more
     * when the answer is no: the message is already on the sale screen and the
     * state already touched.
     *
     * @return true when there is a last closed ticket and the register is not
     *         in training mode; false after the refusal has been posted
     */
    public boolean requireLastClosedTicket() {
        if (trainingMode) {
            // Training touches no real document: no duplicata, no counter bump,
            // and no number that could name a sale that never happened.
            ticket.setError(TRAINING_FORBIDDEN);
            touch();
            return false;
        }
        if (lastClosedTicketId == null) {
            ticket.setError(NO_LAST_TICKET);
            touch();
            return false;
        }
        return true;
    }

    // --- Current ticket pagination ---

    /** Number of ticket lines per page. */
    private static final int PAGE_SIZE = 6;

    /** Current page of the ticket display. */
    public int ticketCurrentPage = 0;

    /** Global error message, or null. */
    public String globalError = null;

    /** The button menu currently shown; the sale gestures when the screen opens. */
    public PosMenu menu = PosMenu.VENTE;

    /**
     * Returns the five menus in tab order — what the bottom bar renders.
     *
     * @return the menus, never empty
     */
    public java.util.List<PosMenu> getMenus() {
        return PosMenu.ALL;
    }

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
        customerMessage = "";
        invoice.clear();
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
     * When the operator-forced monetics degraded mode expires, or null when it is
     * not forced ({@code LC-07-08-02} to {@code -05}).
     *
     * <p>AN EXPIRY AND NOT A BOOLEAN, deliberately. A degraded mode left on is a
     * till that stops asking the monetics for authorization, and the operator who
     * turned it on is not the one who will still be there in three hours. So the
     * shop administers how long a forcing lasts, the flag carries its own end, and
     * {@code LC-07-08-04}'s automatic deactivation costs no scheduler.
     */
    public java.time.LocalDateTime moneticsDegradedUntil = null;

    /**
     * Tells whether the operator has forced the monetics into degraded mode
     * ({@code LC-07-08-05}: this is what the permanent indicator reads).
     *
     * <p>Reading it EXPIRES it: the forcing ends by itself and every consumer —
     * the terminal gate, the screen indicator — sees the same instant of truth,
     * with no scheduled task to keep in step with them.
     *
     * @return true while the forcing is on and has not run out
     */
    public boolean isMoneticsDegradedForced() {
        if (moneticsDegradedUntil == null) {
            return false;
        }
        if (moneticsDegradedUntil.isBefore(java.time.LocalDateTime.now())) {
            moneticsDegradedUntil = null;
            return false;
        }
        return true;
    }

    /**
     * The legal cash-rounding step in cents, refreshed from the back office at
     * payment entry; zero or one means this shop does not round
     * ({@code LC-07-03-01}).
     *
     * <p>Held HERE and not read from the settings on every call because the screen,
     * the customer display and the printer all ask for the rounded amount several
     * times per second while the payment screen polls, and a parameter lookup per
     * question would be a database round trip per question.
     */
    public int cashRoundingStepCents = 0;

    /**
     * Returns what the customer settles in cash for the remaining due
     * ({@code LC-07-03-01}), which is the remaining due itself when the shop does
     * not round.
     *
     * @return the remaining due rounded to the administered step
     */
    public BigDecimal getCashRoundedRemaining() {
        return com.intermarche.pos.ui.payment.CashRounding.round(
                getRemaining(), cashRoundingStepCents);
    }

    /**
     * Tells whether the rounding actually changes what the customer hands over —
     * the only case worth showing a second figure for ({@code LC-07-03-02/03}).
     *
     * @return true when the rounded amount differs from the real one
     */
    public boolean isCashRoundingVisible() {
        return cashRoundingStepCents > 1
                && getCashRoundedRemaining().compareTo(getRemaining()) != 0;
    }

    /**
     * Returns the rounded remaining due formatted for display (French comma).
     *
     * @return the formatted rounded remaining due
     */
    public String getCashRoundedRemainingFormatted() {
        return String.format("%.2f", getCashRoundedRemaining()).replace(".", ",");
    }

    /**
     * Returns the rounded remaining due formatted for the numpad (dot separator).
     *
     * @return the rounded remaining due as a numpad-ready string
     */
    public String getCashRoundedRemainingNumpad() {
        return String.format("%.2f", getCashRoundedRemaining());
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
        // The clamp above is what keeps the window inside the list: with a
        // non-empty ticket, fromIndex = page * PAGE_SIZE <= maxPage *
        // PAGE_SIZE <= size - 1. No further bound check is reachable — one
        // used to sit here and could never fire.
        int fromIndex = ticketCurrentPage * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, ticket.items.size());
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
