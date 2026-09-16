package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.sale.DocumentOutput;
import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.VatBreakdown;
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

    private static final Logger LOGGER = Logger.getLogger(InvoiceService.class);

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

    /** The A4 printer of the store network ({@code LC-08-04-11}). */
    @Inject
    NetworkDocumentPrinter networkPrinter;

    /** The administered layouts (BO-03-03). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.DocumentTemplateService documentTemplateService;

    /**
     * Lays the document out in forty-two columns, through the ADMINISTERED
     * layout when the shop has one (BO-03-03).
     *
     * <p>The gabarit answers ONE string; the roll and the slip station both work
     * on lines, so it is cut on its own newlines — a layout decides what is
     * printed, the paper path decides how it is fed.
     *
     * @param laid the document, already written
     * @return the document's lines
     */
    List<String> renderLines(InvoiceDocument laid) {
        String administered = documentTemplateService == null ? null
                : documentTemplateService.render(
                        com.intermarche.pos.domain.setting.DocumentTemplate
                                .DocumentType.INVOICE, laid.asDocumentData());
        if (administered != null) {
            return List.of(administered.split("\\n", -1));
        }
        return InvoiceRenderer.render(laid);
    }

    /**
     * The store-node outbox: a customer created at the register is declared to the
     * back office through it ({@code LC-08-04-09}), on the same road as tickets and
     * refunds, so the declaration survives a broken link instead of being lost.
     */
    @Inject
    com.intermarche.pos.service.sync.register.SyncOutboxService syncOutboxService;

    /** How a date is typed and shown on this screen. */
    private static final java.time.format.DateTimeFormatter DATE_MASK =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Opens the screen: loads the closed-ticket shortlist and pre-fills the mask with
     * the register's last ticket, which is the one an invoice is asked for nine times
     * out of ten ({@code LC-08-04-03}).
     */
    public void open() {
        LOGGER.info("Entering method open");
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
        LOGGER.info("Exiting method open");
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
        LOGGER.info("Entering method chooseTicket with ticketNumber: " + ticketNumber + ", ticketDate: " + ticketDate + ", ticketTerminal: " + ticketTerminal);
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
            LOGGER.info("Exiting method chooseTicket");
            return;
        }
        state.invoice.ticket = found;
        // LC-08-04-04/05: the kind of document is resolved here, because the ticket is
        // what it is drawn over. One eligible kind and there is nothing to ask — the
        // step is skipped rather than shown with a single button, which is exactly what
        // -05 requires.
        state.invoice.documentTypes = eligibleDocumentTypes();
        if (state.invoice.documentTypes.size() == 1) {
            state.invoice.documentType = state.invoice.documentTypes.get(0);
        } else if (!state.invoice.documentTypes.contains(state.invoice.documentType)) {
            // Either nothing was chosen yet, or the kind chosen before is no longer
            // offered. Both mean the operator must choose, and neither may leave a kind
            // behind that the back office has since deactivated.
            state.invoice.documentType = null;
            state.invoice.step = InvoiceState.Step.DOCUMENT;
            state.touch();
            LOGGER.info("Exiting method chooseTicket");
            return;
        }
        // WHERE THIS LEADS depends on what is already known. Going forward, the
        // customer is still missing and comes next. Coming BACK from the review
        // to correct the ticket, the customer is already named and kept — sending
        // the operator through that step again would make correcting one choice
        // cost the other all the same, which is exactly what going back was for.
        state.invoice.step = state.invoice.customer == null
                ? InvoiceState.Step.CUSTOMER
                : InvoiceState.Step.PREVIEW;
        state.touch();
        LOGGER.info("Exiting method chooseTicket");
    }

    /**
     * Names the kind of document to draw and moves on ({@code LC-08-04-04}).
     *
     * <p>The kind is checked against the offered list rather than merely parsed: the
     * page carrying the buttons may have been rendered before the back office changed
     * the activated kinds, and a document must never be drawn on a kind the store has
     * turned off.
     *
     * @param typeName the enum name of the kind the operator touched
     */
    public void chooseDocumentType(String typeName) {
        LOGGER.info("Entering method chooseDocumentType with typeName: " + typeName);
        state.invoice.error = "";
        DocumentType chosen = DocumentType.byName(typeName);
        if (chosen == null || !state.invoice.documentTypes.contains(chosen)) {
            state.invoice.error = "TYPE DE DOCUMENT INDISPONIBLE";
            state.touch();
            LOGGER.info("Exiting method chooseDocumentType");
            return;
        }
        state.invoice.documentType = chosen;
        state.invoice.step = state.invoice.customer == null
                ? InvoiceState.Step.CUSTOMER
                : InvoiceState.Step.PREVIEW;
        state.touch();
        LOGGER.info("Exiting method chooseDocumentType");
    }

    /**
     * Returns the kinds of document this ticket may be drawn as ({@code LC-08-04-04}).
     *
     * <p>Today the back-office list is the whole of the eligibility: every activated
     * kind can be drawn over any closed ticket. The day a sale carries a nature of its
     * own — a deposit, which only a deposit invoice may state — this is where that
     * narrowing belongs, and {@code LC-08-04-05} already skips the step whenever the
     * narrowing leaves one kind standing.
     *
     * @return the kinds to offer, in administered order, never empty
     */
    public List<DocumentType> eligibleDocumentTypes() {
        LOGGER.info("Entering method eligibleDocumentTypes");
        LOGGER.info("Exiting method eligibleDocumentTypes");
        return DocumentType.activated(posSettingsService.invoiceDocumentTypes());
    }

    /**
     * Looks customers up by name ({@code LC-08-04-07}: the address is returned with
     * them, because two businesses can carry the same name and the address is what
     * tells them apart).
     *
     * @param search what the operator typed, matched anywhere in the business name
     */
    public void searchCustomers(String search) {
        LOGGER.info("Entering method searchCustomers with search: " + search);
        state.invoice.error = "";
        state.invoice.customerSearch = search == null ? "" : search.trim();
        if (state.invoice.customerSearch.isEmpty()) {
            state.invoice.customers = List.of();
            state.invoice.searched = false;
            state.touch();
            LOGGER.info("Exiting method searchCustomers");
            return;
        }
        state.invoice.customers = repository.searchCustomers(
                state.invoice.customerSearch.toLowerCase(), CUSTOMER_MATCHES);
        state.invoice.searched = true;
        state.touch();
        LOGGER.info("Exiting method searchCustomers");
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
        LOGGER.info("Entering method chooseCustomerByNumber with accountNumber: " + accountNumber);
        state.invoice.error = "";
        state.invoice.customerNumber = trimmed(accountNumber);
        if (state.invoice.customerNumber.isEmpty()) {
            state.invoice.error = "NUMERO CLIENT VIDE";
            state.touch();
            LOGGER.info("Exiting method chooseCustomerByNumber");
            return;
        }
        AccountCustomer found = repository.findCustomerByNumber(state.invoice.customerNumber);
        if (found == null) {
            state.invoice.error = "CLIENT INTROUVABLE : " + state.invoice.customerNumber;
            state.touch();
            LOGGER.info("Exiting method chooseCustomerByNumber");
            return;
        }
        state.invoice.customer = found;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
        LOGGER.info("Exiting method chooseCustomerByNumber");
    }

    /**
     * Names the customer the document is addressed to and moves to the review step.
     *
     * @param customerId the database id of the customer
     */
    public void chooseCustomer(Long customerId) {
        LOGGER.info("Entering method chooseCustomer with customerId: " + customerId);
        state.invoice.error = "";
        AccountCustomer found = repository.findCustomer(customerId);
        if (found == null) {
            state.invoice.error = "CLIENT INTROUVABLE";
            state.touch();
            LOGGER.info("Exiting method chooseCustomer");
            return;
        }
        state.invoice.customer = found;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
        LOGGER.info("Exiting method chooseCustomer");
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
        LOGGER.info("Entering method createCustomer with companyName: " + companyName + ", contactName: " + contactName + ", street: " + street + ", postalCode: " + postalCode + ", city: " + city + ", siret: " + siret + ", vatNumber: " + vatNumber + ", phone: " + phone + ", email: " + email);
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
            LOGGER.info("Exiting method createCustomer");
            return;
        }
        // The business name is what the document is addressed to: whatever the
        // administered mask says, a customer without one cannot be billed.
        if (companyName == null || companyName.isBlank()) {
            state.invoice.error = "RAISON SOCIALE OBLIGATOIRE";
            state.touch();
            LOGGER.info("Exiting method createCustomer");
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
                com.intermarche.pos.domain.sync.SyncOutbox.EntityType.CUSTOMER, created.id);
        LOGGER.infof("Client en compte cree en caisse : %s (%s)",
                created.companyName, created.accountNumber);
        state.invoice.customer = created;
        state.invoice.creatingCustomer = false;
        state.invoice.searched = false;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
        LOGGER.info("Exiting method createCustomer");
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
        LOGGER.info("Entering method preview");
        if (state.invoice.ticket == null || state.invoice.customer == null
                || state.invoice.documentType == null) {
            LOGGER.info("Exiting method preview");
            return null;
        }
        Ticket ticket = repository.findTicket(state.invoice.ticket.id);
        AccountCustomer customer = repository.findCustomer(state.invoice.customer.id);
        if (ticket == null || customer == null) {
            LOGGER.info("Exiting method preview");
            return null;
        }
        Invoice draft = build(ticket, customer, "");
        LOGGER.info("Exiting method preview");
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
        LOGGER.info("Entering method issue");
        state.invoice.error = "";
        if (state.invoice.ticket == null || state.invoice.customer == null
                || state.invoice.documentType == null) {
            state.invoice.error = "TICKET OU CLIENT MANQUANT";
            state.touch();
            LOGGER.info("Exiting method issue");
            return null;
        }
        if (state.trainingMode) {
            state.invoice.error = "FACTURE INDISPONIBLE EN FORMATION";
            state.touch();
            LOGGER.info("Exiting method issue");
            return null;
        }
        DocumentType type = state.invoice.documentType;
        Ticket ticket = repository.findTicket(state.invoice.ticket.id);
        // The duplicate rule is PER KIND ({@code LC-08-04-17}). One sale carries one
        // document of each kind: asking for the invoice of a ticket that already has a
        // delivery note is a first original, not a duplicate, and a lookup blind to the
        // kind would reprint the wrong paper.
        Invoice existing = repository.findInvoiceOfTicket(ticket.ticketNumber, type);
        Invoice document = existing != null
                ? existing
                : build(ticket, repository.findCustomer(state.invoice.customer.id),
                        ticketNumberService.nextDocumentNumber(type));
        print(document, ticket);
        document.printCount++;
        repository.save(document);
        state.invoice.issuedInvoiceId = document.id;
        state.touch();
        LOGGER.info("Exiting method issue");
        return document.id;
    }

    /**
     * Lays out an already-issued document, for a screen that shows it again.
     *
     * @param invoiceId the database id of the document
     * @return the document, or null when there is none under that id
     */
    public InvoiceDocument issued(Long invoiceId) {
        LOGGER.info("Entering method issued with invoiceId: " + invoiceId);
        Invoice document = repository.findInvoice(invoiceId);
        if (document == null) {
            LOGGER.info("Exiting method issued");
            return null;
        }
        Ticket ticket = repository.findClosedTicket(document.ticketNumber);
        if (ticket == null) {
            LOGGER.info("Exiting method issued");
            return null;
        }
        LOGGER.info("Exiting method issued");
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
        LOGGER.info("Entering method backToTicketStep");
        state.invoice.error = "";
        state.invoice.ticket = null;
        state.invoice.step = InvoiceState.Step.TICKET;
        state.touch();
        LOGGER.info("Exiting method backToTicketStep");
    }

    /**
     * Goes back to naming the KIND OF DOCUMENT, keeping the ticket and the customer.
     *
     * <p>The kind being replaced is dropped, so the step the operator lands on shows a
     * choice rather than an answer. The offered list is left as the ticket step
     * resolved it: coming back to change one's mind is not a reason to re-run an
     * eligibility the ticket has not moved.
     */
    public void backToDocumentStep() {
        LOGGER.info("Entering method backToDocumentStep");
        state.invoice.error = "";
        state.invoice.documentType = null;
        state.invoice.step = InvoiceState.Step.DOCUMENT;
        state.touch();
        LOGGER.info("Exiting method backToDocumentStep");
    }

    /**
     * Goes back to naming the CUSTOMER, keeping the ticket already chosen.
     *
     * <p>The customer being replaced is dropped, and so is the half-finished
     * creation form: coming back to choose someone else must not reopen the
     * screen on a form the operator had already left.
     */
    public void backToCustomerStep() {
        LOGGER.info("Entering method backToCustomerStep");
        state.invoice.error = "";
        state.invoice.customer = null;
        state.invoice.creatingCustomer = false;
        state.invoice.step = InvoiceState.Step.CUSTOMER;
        state.touch();
        LOGGER.info("Exiting method backToCustomerStep");
    }

    /**
     * Gives up the document being prepared. Nothing to undo: nothing was written.
     */
    public void abandon() {
        LOGGER.info("Entering method abandon");
        state.invoice.clear();
        state.touch();
        LOGGER.info("Exiting method abandon");
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
        return build(ticket, customer, number, state.invoice.documentType);
    }

    /**
     * Builds a document of a named kind over a ticket and a customer, without writing
     * it.
     *
     * <p>The totals come from the per-rate ventilation and not from the ticket's own
     * stored totals, so that what the document states and what its VAT table states
     * cannot disagree — {@code BO-03-03-04} requires the second to match the receipt,
     * and deriving both from the same ventilation is the only way to guarantee it.
     *
     * @param ticket   the closed ticket the document states
     * @param customer the customer it is addressed to
     * @param number   its number, empty on a document being previewed
     * @param type     the kind of document to draw
     * @return the document, transient
     */
    private Invoice build(Ticket ticket, AccountCustomer customer, String number,
            DocumentType type) {
        Invoice document = new Invoice();
        document.documentNumber = number;
        document.documentType = type;
        document.terminalId = ticketNumberService.getTerminalId();
        document.issueDate = LocalDateTime.now();
        document.ticket = ticket;
        document.ticketNumber = ticket.ticketNumber;
        document.addressTo(customer);
        VatBreakdown breakdown = new VatBreakdown();
        for (com.intermarche.pos.domain.sale.TicketLine line : ticket.lines) {
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
     * Emits, at the end of a sale, the document the settlement calls for
     * ({@code LC-08-04-16}).
     *
     * <p>THE SETTLEMENT NAMES THE DOCUMENT, AND THE SETTLEMENT NAMES THE CUSTOMER. A
     * document is addressed to somebody; a sale paid in cash names nobody, so there is
     * nothing to emit however the parameter reads. Customer credit is the one method
     * that carries an account, which is why the questionnaire's own example is exactly
     * that one.
     *
     * <p>It NEVER stops the closing. The sale is over, the customer is leaving, and the
     * screen has already gone back to the next one: a document that cannot be built is
     * skipped rather than turned into a refusal, and one whose printer needs a hand is
     * printed whole on the roll instead of holding the lane between two sheets — the
     * sheet-by-sheet gesture of {@code LC-08-04-12} belongs to the operator who ASKED
     * for a document, not to a closing that produced one on its own.
     *
     * @param ticketId the database id of the ticket just closed
     * @return the kind emitted, or null when the settlement called for none
     */
    @Transactional
    public DocumentType autoPrint(Long ticketId) {
        LOGGER.info("Entering method autoPrint with ticketId: " + ticketId);
        java.util.Map<String, DocumentType> automatic =
                DocumentType.automatic(posSettingsService.invoiceAutoPrint());
        if (automatic.isEmpty() || ticketId == null) {
            LOGGER.info("Exiting method autoPrint");
            return null;
        }
        Ticket ticket = repository.findTicket(ticketId);
        if (ticket == null) {
            LOGGER.info("Exiting method autoPrint");
            return null;
        }
        for (com.intermarche.pos.domain.payment.TicketPayment payment : ticket.payments) {
            DocumentType kind = automatic.get(payment.getMethodKey());
            if (kind == null) {
                continue;
            }
            AccountCustomer customer = customerOf(payment);
            if (customer == null) {
                continue;
            }
            // One sale, one document of each kind: a ticket that already carries this
            // kind — reissued by hand a moment earlier — is not doubled.
            if (repository.findInvoiceOfTicket(ticket.ticketNumber, kind) != null) {
                LOGGER.info("Exiting method autoPrint");
                return null;
            }
            Invoice document = build(ticket, customer,
                    ticketNumberService.nextDocumentNumber(kind), kind);
            InvoiceDocument laid =
                    InvoiceDocument.of(document, ticket, posSettingsService.showEan());
            List<String> lines = renderLines(laid);
            if (outputFor(kind) != DocumentOutput.A4 || !networkPrinter.print(laid, lines)) {
                hardwareService.printReceipt(String.join("\n", lines) + "\n");
                hardwareService.cutPaper();
            }
            document.printCount++;
            repository.save(document);
            LOGGER.infof("Document %s émis automatiquement sur le règlement %s du ticket %s",
                    document.documentNumber, payment.getMethodKey(), ticket.ticketNumber);
            LOGGER.info("Exiting method autoPrint");
            return kind;
        }
        LOGGER.info("Exiting method autoPrint");
        return null;
    }

    /**
     * Names the account customer a settlement designates, when it designates one.
     *
     * @param payment the settlement of the closed sale
     * @return the customer in account, or null when the method names none
     */
    private AccountCustomer customerOf(com.intermarche.pos.domain.payment.TicketPayment payment) {
        if (payment instanceof com.intermarche.pos.domain.payment.CreditPayment credit) {
            return repository.findCustomerByNumber(credit.accountNumber);
        }
        return null;
    }

    /**
     * Returns the printer a kind of document comes out of ({@code LC-08-04-11}).
     *
     * <p>Unadministered, it is the roll: that is the one printer a register always has,
     * and a document that comes out somewhere is worth more than one that waits for the
     * right paper path.
     *
     * @param type the kind of document
     * @return its printer, never null
     */
    public DocumentOutput outputFor(DocumentType type) {
        LOGGER.info("Entering method outputFor with type: " + type);
        LOGGER.info("Exiting method outputFor");
        return DocumentOutput.administered(posSettingsService.invoiceDocumentOutput())
                .getOrDefault(type, DocumentOutput.TICKET);
    }

    /**
     * Sends the document to the printer its kind is administered to
     * ({@code LC-08-04-11}).
     *
     * <p>The roll prints it whole and unattended. The SLIP STATION does not: it takes
     * one sheet at a time from a hand, so nothing is printed here — the document is cut
     * into sheets, the operator is told how many there are and asked for the first
     * ({@code LC-08-04-12/13}). The network printer prints an A4 page somewhere else in
     * the store, which is again nothing the operator has to do.
     *
     * @param document the document to print
     * @param ticket   the ticket it states
     */
    private void print(Invoice document, Ticket ticket) {
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, posSettingsService.showEan());
        List<String> lines = renderLines(laid);
        DocumentOutput target = outputFor(document.documentType);
        if (target.needsInsertion()) {
            state.invoice.slipPages =
                    InvoiceRenderer.paginate(lines, posSettingsService.invoiceSlipLines());
            state.invoice.slipPrinted = 0;
            state.invoice.step = InvoiceState.Step.INSERT;
            return;
        }
        // A network printer that is not configured, is down or refuses the page leaves
        // the operator with nothing in their hand. The roll then prints the degraded
        // rendering and the operator is told where the document actually came out —
        // silently falling back would send them looking at an office printer for a page
        // that is on the till beside them.
        if (target == DocumentOutput.A4 && networkPrinter.print(laid, lines)) {
            return;
        }
        if (target == DocumentOutput.A4) {
            state.invoice.error = "IMPRIMANTE RESEAU INJOIGNABLE - DOCUMENT IMPRIME EN CAISSE";
        }
        hardwareService.printReceipt(String.join("\n", lines) + "\n");
        hardwareService.cutPaper();
    }

    /**
     * Prints the sheet the operator has just fed the slip station
     * ({@code LC-08-04-12}).
     *
     * <p>Called once per sheet, it advances by exactly one and stops on its own when
     * the last has come out — a station that is asked for a sheet it does not have
     * would leave the screen waiting on a gesture that can no longer be made.
     *
     * @return true when sheets are still to be fed, false when the document is complete
     */
    public boolean printNextSlip() {
        LOGGER.info("Entering method printNextSlip");
        state.invoice.error = "";
        if (state.invoice.slipPrinted >= state.invoice.slipPages.size()) {
            finishSlips();
            LOGGER.info("Exiting method printNextSlip");
            return false;
        }
        List<String> sheet = state.invoice.slipPages.get(state.invoice.slipPrinted);
        hardwareService.printReceipt(String.join("\n", sheet) + "\n");
        hardwareService.cutPaper();
        state.invoice.slipPrinted++;
        if (state.invoice.slipPrinted >= state.invoice.slipPages.size()) {
            finishSlips();
            LOGGER.info("Exiting method printNextSlip");
            return false;
        }
        state.touch();
        LOGGER.info("Exiting method printNextSlip");
        return true;
    }

    /**
     * Closes the slip sequence: the document is out, the screen goes back to looking
     * at it.
     */
    private void finishSlips() {
        state.invoice.slipPages = List.of();
        state.invoice.slipPrinted = 0;
        state.invoice.step = InvoiceState.Step.PREVIEW;
        state.touch();
    }

    /**
     * Returns the administered customer-creation mask ({@code LC-08-04-10}), typed.
     *
     * @return the fields to ask for, in administered order, never empty
     */
    public List<EntryField> customerFields() {
        LOGGER.info("Entering method customerFields");
        LOGGER.info("Exiting method customerFields");
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
