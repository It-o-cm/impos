package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.ticket.Ticket;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * State of the invoice screen: which ticket is being billed, to whom, and where the
 * operator stands in the sequence.
 *
 * <p>THREE STEPS, in the order the questionnaire describes them
 * ({@code LC-08-04-02} to {@code -14}): name the original ticket, name the customer,
 * look at the document and either issue it or give up. The last step is what makes
 * the abandon real — as long as nothing has been issued there is nothing to undo,
 * and the operator sees exactly what they are about to commit to.
 *
 * <p>NOTHING IS PERSISTED BEFORE THE ISSUE. Not the document, and above all not its
 * number: a sequence that must be "unique, séquentiel et sans rupture par type de
 * document" cannot afford a number burnt by an operator who looked at a preview and
 * changed their mind. The preview therefore shows a document without a number, and
 * the number is drawn at the moment of issue.
 */
public class InvoiceState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Rows of the closed-ticket shortlist (touch-screen sizing). */
    public static final int TICKET_ROWS = 6;

    /** Rows of the customer shortlist (touch-screen sizing). */
    public static final int CUSTOMER_ROWS = 6;

    /** Where the operator stands. */
    public enum Step {

        /** Naming the ticket the document states. */
        TICKET,

        /** Naming the customer the document is addressed to. */
        CUSTOMER,

        /** Looking at the document, before issuing or giving up. */
        PREVIEW
    }

    /** Where the operator stands; TICKET when the screen opens. */
    public Step step = Step.TICKET;

    /** The most recent closed tickets, offered as a shortlist. */
    public List<Ticket> tickets = Collections.emptyList();

    /** The ticket number typed or picked, pre-filled with the register's last one. */
    public String ticketNumber = "";

    /**
     * The date of the original ticket, {@code dd/MM/yyyy}, pre-filled with the
     * register's last one ({@code LC-08-04-02}). Blank means the operator does not
     * narrow the search by date.
     */
    public String ticketDate = "";

    /**
     * The register that issued the original ticket, pre-filled with this one
     * ({@code LC-08-04-02}). Blank means the operator does not narrow the search by
     * register.
     */
    public String ticketTerminal = "";

    /** The ticket the document will state, null until one is named. */
    public Ticket ticket;

    /** What the operator typed to look a customer up. */
    public String customerSearch = "";

    /**
     * The account number typed to reach a customer straight ({@code LC-08-04-06}).
     * Filled, it wins over the name search: a number names exactly one customer, a
     * name may name several.
     */
    public String customerNumber = "";

    /** The customers matching that search. */
    public List<AccountCustomer> customers = new ArrayList<>();

    /**
     * True once a search has actually been run.
     * <p>
     * Without it an empty list means two different things — nobody has searched yet,
     * and the search found nothing — and the screen would answer a search by showing
     * exactly what it showed before, which reads as a button that does nothing.
     */
    public boolean searched = false;

    /** The customer the document is addressed to, null until one is named. */
    public AccountCustomer customer;

    /** True while the operator is filling in a new customer rather than picking one. */
    public boolean creatingCustomer = false;

    /** The database id of the issued document, null until it is issued. */
    public Long issuedInvoiceId;

    /** The operator-facing error of the last action, empty when it worked. */
    public String error = "";

    /**
     * Clears everything, so the next document starts from nothing.
     */
    public void clear() {
        step = Step.TICKET;
        tickets = Collections.emptyList();
        ticketNumber = "";
        ticketDate = "";
        ticketTerminal = "";
        ticket = null;
        customerSearch = "";
        customerNumber = "";
        customers = new ArrayList<>();
        searched = false;
        customer = null;
        creatingCustomer = false;
        issuedInvoiceId = null;
        error = "";
    }

    /**
     * Returns the closed tickets offered on the shortlist, capped to what the screen
     * shows.
     *
     * @return at most {@link #TICKET_ROWS} tickets
     */
    public List<Ticket> getVisibleTickets() {
        return tickets.size() <= TICKET_ROWS ? tickets : tickets.subList(0, TICKET_ROWS);
    }

    /**
     * Returns the customers offered on the shortlist, capped to what the screen shows.
     *
     * @return at most {@link #CUSTOMER_ROWS} customers
     */
    public List<AccountCustomer> getVisibleCustomers() {
        return customers.size() <= CUSTOMER_ROWS ? customers : customers.subList(0, CUSTOMER_ROWS);
    }

    /**
     * Tells whether a search was run and came back with nothing.
     *
     * @return true when the operator searched and no customer matched
     */
    public boolean isSearchWithoutMatch() {
        return searched && customers.isEmpty();
    }

    /**
     * Tells whether the operator is naming the ticket.
     *
     * @return true on the first step
     */
    public boolean isOnTicketStep() {
        return step == Step.TICKET;
    }

    /**
     * Tells whether the operator is naming the customer.
     *
     * @return true on the second step
     */
    public boolean isOnCustomerStep() {
        return step == Step.CUSTOMER;
    }

    /**
     * Tells whether the operator is looking at the document.
     *
     * @return true on the third step
     */
    public boolean isOnPreviewStep() {
        return step == Step.PREVIEW;
    }
}
