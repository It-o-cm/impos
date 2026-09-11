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

        /** Naming the kind of document to draw ({@code LC-08-04-04}). */
        DOCUMENT,

        /** Naming the customer the document is addressed to. */
        CUSTOMER,

        /** Looking at the document, before issuing or giving up. */
        PREVIEW,

        /** Feeding the slip station one sheet at a time ({@code LC-08-04-12}). */
        INSERT
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

    /**
     * The kind of document being drawn ({@code LC-08-04-04}), null until one is named.
     *
     * <p>It is resolved right after the ticket, because it decides everything that
     * follows: the title on the paper, the sequence the number is drawn from, and — the
     * day the slip path lands — the printer the document comes out of.
     */
    public com.intermarche.pos.domain.ticket.DocumentType documentType;

    /**
     * The kinds offered on the document step, empty until a ticket is named.
     * <p>
     * Held on the state rather than recomputed by the screen so that what the operator
     * touches is exactly what the flow resolved: a list rebuilt at render time could
     * drift from the one the eligibility ran on.
     */
    public List<com.intermarche.pos.domain.ticket.DocumentType> documentTypes =
            Collections.emptyList();

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

    /**
     * The sheets left to feed the slip station ({@code LC-08-04-12}), empty whenever
     * the document does not come out of one.
     *
     * <p>They are rendered ONCE, at the issue, and kept: re-rendering between two
     * sheets would let a document change shape halfway through its own printing.
     */
    public List<List<String>> slipPages = Collections.emptyList();

    /** How many sheets of {@link #slipPages} have already come out. */
    public int slipPrinted = 0;

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
        documentType = null;
        documentTypes = Collections.emptyList();
        customerSearch = "";
        customerNumber = "";
        customers = new ArrayList<>();
        searched = false;
        customer = null;
        creatingCustomer = false;
        issuedInvoiceId = null;
        slipPages = Collections.emptyList();
        slipPrinted = 0;
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
     * Tells whether the operator is naming the kind of document
     * ({@code LC-08-04-04}).
     *
     * @return true on the second step
     */
    public boolean isOnDocumentStep() {
        return step == Step.DOCUMENT;
    }

    /**
     * Tells whether the operator is naming the customer.
     *
     * @return true on the third step
     */
    public boolean isOnCustomerStep() {
        return step == Step.CUSTOMER;
    }

    /**
     * Tells whether the operator is looking at the document.
     *
     * @return true on the last step
     */
    public boolean isOnPreviewStep() {
        return step == Step.PREVIEW;
    }

    /**
     * Tells whether the operator is feeding the slip station ({@code LC-08-04-12}).
     *
     * @return true while sheets are still to be inserted
     */
    public boolean isOnInsertStep() {
        return step == Step.INSERT;
    }

    /**
     * Returns the title of the kind of document being drawn, for a screen that names
     * it back to the operator.
     *
     * @return the title, or an empty string while no kind is named
     */
    public String getDocumentTitle() {
        return documentType == null ? "" : documentType.getTitle();
    }

    /**
     * Returns how many sheets the document takes ({@code LC-08-04-13}), which is what
     * the operator is told before the first one is printed.
     *
     * @return the sheet count, zero when the document does not use the slip station
     */
    public int getSlipPageCount() {
        return slipPages.size();
    }

    /**
     * Returns the number of the sheet being asked for, counting from one.
     *
     * @return the sheet number, capped at the last sheet
     */
    public int getSlipPageNumber() {
        return Math.min(slipPrinted + 1, Math.max(slipPages.size(), 1));
    }
}
