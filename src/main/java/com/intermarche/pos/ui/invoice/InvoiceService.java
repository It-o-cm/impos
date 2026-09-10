package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.ticket.DocumentType;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Drives the invoice screen: finds the ticket to bill, finds or creates the customer,
 * lays the document out for review, and issues it.
 *
 * <p>THE ISSUE IS THE COMMITMENT, and everything before it is free. Naming a ticket,
 * looking a customer up, even looking at the finished document changes nothing that
 * has to be undone: no row is written and, above all, no number is drawn. A document
 * sequence that must run without a gap cannot afford one burnt by an operator who
 * looked and changed their mind, and that is exactly the window
 * {@code LC-08-04-14} asks to keep open.
 *
 * <p>A SECOND REQUEST ON THE SAME TICKET IS A DUPLICATE, never a second original
 * ({@code LC-08-04-17}). One sale, one document: issuing twice would put two numbers
 * on one taxable event, which is the one thing a numbering sequence exists to
 * prevent.
 */
@ApplicationScoped
public class InvoiceService {

    private static final Logger LOG = Logger.getLogger(InvoiceService.class);

    /** How many closed tickets the shortlist offers. */
    private static final int SHORTLIST = 12;

    /** How many customers a search returns at most. */
    private static final int CUSTOMER_MATCHES = 12;

    /** The register state, whose invoice sub-state this service drives. */
    @Inject
    PosState state;

    /** Issues the document numbers, under the counter row's lock. */
    @Inject
    TicketNumberService ticketNumberService;

    /** The back-office parameters (article code on the document — LC-02-04-02). */
    @Inject
    PosSettingsService posSettingsService;

    /** The printer, reached through the hardware bridge. */
    @Inject
    HardwareService hardwareService;

    /** Every database access of this flow, gathered so the flow can be tested. */
    @Inject
    InvoiceRepository repository;

    /**
     * The store-node outbox: a customer created at the register is declared to the
     * back office through it ({@code LC-08-04-09}), on the same road as tickets and
     * refunds, so the declaration survives a broken link instead of being lost.
     */
    @Inject
    com.intermarche.pos.service.sync.SyncOutboxService syncOutboxService;

    /** How a date is typed and shown on this screen. */
    private static final java.time.format.DateTimeFormatter DATE_MASK =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Opens the screen: loads the closed-ticket shortlist and pre-fills the mask with
     * the register's last ticket, which is the one an invoice is asked for nine times
     * out of ten ({@code LC-08-04-03}).
     */
    public void open() {
        state.invoice.clear();
        List<Ticket> recent = repository.recentClosedTickets(SHORTLIST);
        state.invoice.tickets = recent;
        state.invoice.ticketTerminal = ticketNumberService.getTerminalId();
        if (!recent.isEmpty()) {
            Ticket last = recent.get(0);
            state.invoice.ticketNumber = last.ticketNumber;
            state.invoice.ticketDate = last.creationDate == null
                    ? "" : last.creationDate.toLocalDate().format(DATE_MASK);
            state.invoice.ticketTerminal = last.terminalId == null
                    ? state.invoice.ticketTerminal : last.terminalId;
        }
        state.touch();
    }

    /**
     * Names the ticket the document will state and moves on — to the customer
     * step, or straight back to the review when a customer is already named.
     *
     * <p>The mask carries the three things printed on the paper the customer hands
     * back ({@code LC-08-04-02}): the number, the date and the register. The number
     * alone identifies the sale here, so an unreadable or blank date is treated as
     * "not narrowed" rather than as a refusal — a mask that rejects a badly typed
     * date would stop an invoice the register could perfectly well produce.
     *
     * @param ticketNumber the number of the closed ticket to bill
     * @param ticketDate the day it was issued, {@code dd/MM/yyyy}, blank when unknown
     * @param ticketTerminal the register that issued it, blank when unknown
     */
    public void chooseTicket(String ticketNumber, String ticketDate, String ticketTerminal) {
        state.invoice.error = "";
        state.invoice.ticketNumber = trimmed(ticketNumber);
        state.invoice.ticketDate = trimmed(ticketDate);
        state.invoice.ticketTerminal = trimmed(ticketTerminal);
        Ticket found = repository.findClosedTicket(state.invoice.ticketNumber,
                parseDate(state.invoice.ticketDate),
                blankToNull(state.invoice.ticketTerminal));
        if (found == null) {
            state.invoice.error = "TICKET INTROUVABLE : " + state.invoice.ticketNumber;
            state.touch();
            return;
        }
        state.invoice.ticket = found;
        // WHERE THIS LEADS depends on what is already known. Going forward, the
        // customer is still missing and comes next. Coming BACK from the review
        // to correct the ticket, the customer is already named and kept — sending
        // the operator through that step again would make correcting one choice
        // cost the other all the same, which is exactly what going back was for.
        state.invoice.step = state.invoice.customer == null
                ? InvoiceState.Step.CUSTOMER
                : InvoiceState.Step.PREVIEW;
        state.touch();
    }

    /**
     * Looks customers up by name ({@code LC-08-04-07}: the address is returned with
     * them, because two businesses can carry the same name and the address is what
     * tells them apart).
     *
     * @param search what the operator typed, matched anywhere in the business name
     */
    public void searchCustomers(String search) {
        state.invoice.error = "";
        state.invoice.customerSearch = search == null ? "" : search.trim();
        if (state.invoice.customerSearch.isEmpty()) {
            state.invoice.customers = List.of();
            state.invoice.searched = false;
            state.touch();
            return;
        }
        state.invoice.customers = repository.searchCustomers(
                state.invoice.customerSearch.toLowerCase(), CUSTOMER_MATCHES);
        state.invoice.searched = true;
        state.touch();
    }

    /**
     * Reaches a customer straight by the account number the operator typed
     * ({@code LC-08-04-06}) and moves to the review step.
     *
     * <p>A number names exactly one customer, so there is nothing to pick from: found,
     * the flow goes on; not found, the operator is told and stays where they are with
     * the name search still available.
     *
     * @param accountNumber the account number typed
     */
    public void chooseCustomerByNumber(String accountNumber) {
        state.invoice.error = "";
        state.invoice.customerNumber = trimmed(accountNumber);
        if (state.invoice.customerNumber.isEmpty()) {
            state.invoice.error = "NUMERO CLIENT VIDE";
            state.touch();
            return;
        }
        AccountCustomer found = repository.findCustomerByNumber(state.invoice.customerNumber);
        if (found == null) {
            state.invoice.error = "CLIENT INTROUVABLE : " + state.invoice.customerNumber;
            state.touch();
            return;
        }
        state.invoice.customer = found;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
    }

    /**
     * Names the customer the document is addressed to and moves to the review step.
     *
     * @param customerId the database id of the customer
     */
    public void chooseCustomer(Long customerId) {
        state.invoice.error = "";
        AccountCustomer found = repository.findCustomer(customerId);
        if (found == null) {
            state.invoice.error = "CLIENT INTROUVABLE";
            state.touch();
            return;
        }
        state.invoice.customer = found;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
    }

    /**
     * Creates a customer at the register and addresses the document to it
     * ({@code LC-08-04-09}).
     *
     * <p>Its account number carries the register's own sequence: nothing arrives from
     * the commercial-management system yet, so nothing can collide with it. The
     * administered code ranges of {@code BO-03-06-53} will constrain that number the
     * day the two populations coexist.
     *
     * @param companyName the business name, the only mandatory field
     * @param contactName the contact's name, blank when the customer is a business alone
     * @param street      the street line, blank when unknown
     * @param postalCode  the postal code, blank when unknown
     * @param city        the town, blank when unknown
     * @param siret       the SIRET, blank when the customer gave none
     * @param vatNumber   the intra-community VAT number, blank when none
     * @param phone       the telephone number, blank when none
     * @param email       the electronic address, blank when none
     */
    @Transactional
    public void createCustomer(String companyName, String contactName, String street,
            String postalCode, String city, String siret, String vatNumber, String phone,
            String email) {
        state.invoice.error = "";
        java.util.Map<String, String> typed = new java.util.LinkedHashMap<>();
        typed.put("companyName", companyName);
        typed.put("contactName", contactName);
        typed.put("street", street);
        typed.put("postalCode", postalCode);
        typed.put("city", city);
        typed.put("siret", siret);
        typed.put("vatNumber", vatNumber);
        typed.put("phone", phone);
        typed.put("email", email);
        String missing = firstMissing(typed);
        if (missing != null) {
            state.invoice.error = missing.toUpperCase() + " OBLIGATOIRE";
            state.touch();
            return;
        }
        // The business name is what the document is addressed to: whatever the
        // administered mask says, a customer without one cannot be billed.
        if (companyName == null || companyName.isBlank()) {
            state.invoice.error = "RAISON SOCIALE OBLIGATOIRE";
            state.touch();
            return;
        }
        AccountCustomer created = new AccountCustomer();
        created.accountNumber = ticketNumberService.nextCustomerNumber();
        created.companyName = companyName.trim();
        created.firstName = firstNameOf(contactName);
        created.lastName = lastNameOf(contactName);
        created.address = new Address();
        created.address.streetLine1 = blankToNull(street);
        created.address.postalCode = blankToNull(postalCode);
        created.address.city = blankToNull(city);
        created.siret = blankToNull(siret);
        created.vatNumber = blankToNull(vatNumber);
        created.phone = blankToNull(phone);
        created.email = blankToNull(email);
        repository.save(created);
        // LC-08-04-09: the back office must know the customer the register just
        // created. It travels on the outbox road, so a store node that is down does
        // not lose the declaration — it receives it on the next drain.
        syncOutboxService.enqueue(
                com.intermarche.pos.domain.SyncOutbox.EntityType.CUSTOMER, created.id);
        LOG.infof("Client en compte cree en caisse : %s (%s)",
                created.companyName, created.accountNumber);
        state.invoice.customer = created;
        state.invoice.creatingCustomer = false;
        state.invoice.searched = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
    }

    /**
     * Lays the document out for review, WITHOUT writing anything.
     *
     * <p>THE TICKET AND THE CUSTOMER ARE RE-READ, they are not taken from the state.
     * The screen carries them from one step to the next, so the instances it holds
     * were loaded by an EARLIER request and their Hibernate session is long closed:
     * touching the ticket's lines on such an instance fails with a lazy-initialisation
     * error. The reprint screen gets away with holding entities because it loads and
     * renders them within one request; a flow spread over several steps does not.
     *
     * @return the document as it will be issued, its number still empty, or null when
     *         the screen has no ticket or no customer yet
     */
    public InvoiceDocument preview() {
        if (state.invoice.ticket == null || state.invoice.customer == null) {
            return null;
        }
        Ticket ticket = repository.findTicket(state.invoice.ticket.id);
        AccountCustomer customer = repository.findCustomer(state.invoice.customer.id);
        if (ticket == null || customer == null) {
            return null;
        }
        Invoice draft = build(ticket, customer, "");
        return InvoiceDocument.of(draft, ticket, posSettingsService.showEan());
    }

    /**
     * Issues the document: draws its number, writes it and prints it.
     *
     * <p>Asked twice for the same ticket, this does NOT create a second original: it
     * counts one more print on the document already issued and prints a duplicate
     * ({@code LC-08-04-17}).
     *
     * @return the database id of the issued document, or null when it could not be issued
     */
    @Transactional
    public Long issue() {
        state.invoice.error = "";
        if (state.invoice.ticket == null || state.invoice.customer == null) {
            state.invoice.error = "TICKET OU CLIENT MANQUANT";
            state.touch();
            return null;
        }
        if (state.trainingMode) {
            state.invoice.error = "FACTURE INDISPONIBLE EN FORMATION";
            state.touch();
            return null;
        }
        Ticket ticket = repository.findTicket(state.invoice.ticket.id);
        Invoice existing = repository.findInvoiceOfTicket(ticket.ticketNumber);
        Invoice document = existing != null
                ? existing
                : build(ticket, repository.findCustomer(state.invoice.customer.id),
                        ticketNumberService.nextDocumentNumber(DocumentType.FACTURE));
        print(document, ticket);
        document.printCount++;
        repository.save(document);
        state.invoice.issuedInvoiceId = document.id;
        state.touch();
        return document.id;
    }

    /**
     * Lays out an already-issued document, for a screen that shows it again.
     *
     * @param invoiceId the database id of the document
     * @return the document, or null when there is none under that id
     */
    public InvoiceDocument issued(Long invoiceId) {
        Invoice document = repository.findInvoice(invoiceId);
        if (document == null) {
            return null;
        }
        Ticket ticket = repository.findClosedTicket(document.ticketNumber);
        if (ticket == null) {
            return null;
        }
        return InvoiceDocument.of(document, ticket, posSettingsService.showEan());
    }

    /**
     * Goes back to naming the TICKET, keeping the customer already chosen.
     *
     * <p>The wizard only ever went forward. An operator who picked the wrong
     * ticket at the first step discovered it on the review step, where the only
     * two ways out were to abandon — retyping everything — or to issue a wrong
     * invoice, which then costs a credit note and a number out of the fiscal
     * sequence. Going back is what makes the review step a review.
     *
     * <p>The ticket itself is dropped: the point of coming back is to name
     * another one, and a stale entity here is what {@code preview()} reloads
     * around anyway. The customer stays — correcting one choice must not cost
     * the other.
     */
    public void backToTicketStep() {
        state.invoice.error = "";
        state.invoice.ticket = null;
        state.invoice.step = InvoiceState.Step.TICKET;
        state.touch();
    }

    /**
     * Goes back to naming the CUSTOMER, keeping the ticket already chosen.
     *
     * <p>The customer being replaced is dropped, and so is the half-finished
     * creation form: coming back to choose someone else must not reopen the
     * screen on a form the operator had already left.
     */
    public void backToCustomerStep() {
        state.invoice.error = "";
        state.invoice.customer = null;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.CUSTOMER;
        state.touch();
    }

    /**
     * Gives up the document being prepared. Nothing to undo: nothing was written.
     */
    public void abandon() {
        state.invoice.clear();
        state.touch();
    }

    /**
     * Builds a document over a ticket and a customer, without writing it.
     *
     * <p>The totals come from the per-rate ventilation and not from the ticket's own
     * stored totals, so that what the document states and what its VAT table states
     * cannot disagree — {@code BO-03-03-04} requires the second to match the receipt,
     * and deriving both from the same ventilation is the only way to guarantee it.
     *
     * @param ticket   the closed ticket the document states
     * @param customer the customer it is addressed to
     * @param number   its number, empty on a document being previewed
     * @return the document, transient
     */
    private Invoice build(Ticket ticket, AccountCustomer customer, String number) {
        Invoice document = new Invoice();
        document.documentNumber = number;
        document.documentType = DocumentType.FACTURE;
        document.terminalId = ticketNumberService.getTerminalId();
        document.issueDate = LocalDateTime.now();
        document.ticket = ticket;
        document.ticketNumber = ticket.ticketNumber;
        document.addressTo(customer);
        VatBreakdown breakdown = new VatBreakdown();
        for (com.intermarche.pos.domain.ticket.TicketLine line : ticket.lines) {
            if (line.cancelled) {
                continue;
            }
            breakdown.add(line.vatRate, line.totalPrice);
        }
        document.totalExcludingTax = breakdown.getTotalExcludingTax();
        document.totalIncludingTax = breakdown.getTotalIncludingTax();
        document.totalVat = breakdown.getTotalVat();
        return document;
    }

    /**
     * Sends the document to the receipt roll.
     *
     * <p>The roll, and not the document station: the slip path is the next lot, and a
     * document that comes out somewhere is worth more than one that waits for the
     * right paper path.
     *
     * @param document the document to print
     * @param ticket   the ticket it states
     */
    private void print(Invoice document, Ticket ticket) {
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, posSettingsService.showEan());
        hardwareService.printReceipt(String.join("\n", InvoiceRenderer.render(laid)) + "\n");
        hardwareService.cutPaper();
    }

    /**
     * Returns the administered customer-creation mask ({@code LC-08-04-10}), typed.
     *
     * @return the fields to ask for, in administered order, never empty
     */
    public List<EntryField> customerFields() {
        return EntryField.customerFields(posSettingsService.invoiceCustomerFields());
    }

    /**
     * Names the first mandatory field of the administered mask the operator left
     * blank.
     *
     * @param typed what was posted, keyed by field name
     * @return the label of the first missing mandatory field, or null when none is
     */
    private String firstMissing(java.util.Map<String, String> typed) {
        for (EntryField field : customerFields()) {
            if (!field.required()) {
                continue;
            }
            String value = typed.get(field.name());
            if (value == null || value.isBlank()) {
                return field.label();
            }
        }
        return null;
    }

    /**
     * Reads a date typed on the ticket mask.
     *
     * @param text the date as typed, {@code dd/MM/yyyy}, possibly blank
     * @return the date, or null when nothing readable was typed
     */
    private static java.time.LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.trim(), DATE_MASK);
        } catch (java.time.format.DateTimeParseException e) {
            // An unreadable date widens the search instead of refusing it: the number
            // identifies the sale on its own.
            return null;
        }
    }

    /**
     * Trims a posted field, tolerating a missing one.
     *
     * @param value what was posted, possibly null
     * @return the trimmed value, empty when nothing was posted
     */
    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Reads the first name out of a contact's full name.
     *
     * @param contactName the full name, possibly blank
     * @return the first name, empty when the name has only one part or is blank
     */
    private static String firstNameOf(String contactName) {
        String name = contactName == null ? "" : contactName.trim();
        int space = name.indexOf(' ');
        return space < 0 ? "" : name.substring(0, space);
    }

    /**
     * Reads the last name out of a contact's full name.
     *
     * @param contactName the full name, possibly blank
     * @return the last name, the whole name when it has only one part
     */
    private static String lastNameOf(String contactName) {
        String name = contactName == null ? "" : contactName.trim();
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(space + 1).trim();
    }

    /**
     * Turns a blank field of a form into a missing value.
     *
     * @param value what the operator typed, possibly blank
     * @return the trimmed value, or null when nothing was typed
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
