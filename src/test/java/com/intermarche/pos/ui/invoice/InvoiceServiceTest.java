package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.DocumentType;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link InvoiceService}, the invoice flow.
 * <p>
 * No database and no CDI: every collaborator is a hand-written stand-in, which is what
 * the {@link InvoiceRepository} seam exists for — a service calling Panache statics
 * could not be unit-tested at all. The state is the real {@link PosState}, since the
 * flow is judged by what it leaves there.
 * <p>
 * Every guard is exercised on both arms and every leg of the compound ones: the two
 * legs of the blank-name refusal, the two of a missing ticket or customer, the three
 * shapes of a contact's name, the duplicate-versus-original fork of the issue, and
 * the training refusal.
 */
class InvoiceServiceTest {

    /** A repository that answers from lists set by each test, and records the writes. */
    private static class FakeRepository extends InvoiceRepository {

        /** The closed tickets it knows, by number. */
        private final List<Ticket> tickets = new ArrayList<>();

        /** The customers it knows. */
        private final List<AccountCustomer> customers = new ArrayList<>();

        /** The document already issued on a ticket, or null. */
        private Invoice existing;

        /** The customers it was asked to write. */
        private final List<AccountCustomer> savedCustomers = new ArrayList<>();

        /** The documents it was asked to write. */
        private final List<Invoice> savedInvoices = new ArrayList<>();

        /** The fragment of the last customer search. */
        private String lastSearch;

        /** How many times a ticket was re-read by its id. */
        private int ticketReads;

        /** How many times a customer was re-read by its id. */
        private int customerReads;

        /** The date the last narrowed lookup was given, null when it was not narrowed. */
        private java.time.LocalDate lastDate;

        /** The register the last narrowed lookup was given, null when not narrowed. */
        private String lastTerminal;

        /** The number the last lookup by account number was given. */
        private String lastAccountNumber;

        /** {@inheritDoc} */
        @Override
        public Ticket findClosedTicket(String ticketNumber) {
            return tickets.stream()
                    .filter(ticket -> ticket.ticketNumber.equals(ticketNumber))
                    .findFirst().orElse(null);
        }

        /** {@inheritDoc} */
        @Override
        public Ticket findClosedTicket(String ticketNumber, java.time.LocalDate date,
                String terminalId) {
            lastDate = date;
            lastTerminal = terminalId;
            return tickets.stream()
                    .filter(ticket -> ticket.ticketNumber.equals(ticketNumber))
                    .filter(ticket -> date == null
                            || (ticket.creationDate != null
                                && ticket.creationDate.toLocalDate().equals(date)))
                    .filter(ticket -> terminalId == null || terminalId.equals(ticket.terminalId))
                    .findFirst().orElse(null);
        }

        /** {@inheritDoc} */
        @Override
        public AccountCustomer findCustomerByNumber(String accountNumber) {
            lastAccountNumber = accountNumber;
            return customers.stream()
                    .filter(customer -> customer.accountNumber != null
                            && customer.accountNumber.equals(accountNumber))
                    .findFirst().orElse(null);
        }

        /** {@inheritDoc} */
        @Override
        public List<Ticket> recentClosedTickets(int limit) {
            return new ArrayList<>(tickets);
        }

        /** {@inheritDoc} */
        @Override
        public List<AccountCustomer> searchCustomers(String fragment, int limit) {
            lastSearch = fragment;
            return new ArrayList<>(customers);
        }

        /** {@inheritDoc} */
        @Override
        public AccountCustomer findCustomer(Long customerId) {
            customerReads++;
            return customers.stream()
                    .filter(customer -> customerId != null && customerId.equals(customer.id))
                    .findFirst().orElse(null);
        }

        /** {@inheritDoc} */
        @Override
        public Ticket findTicket(Long ticketId) {
            ticketReads++;
            return tickets.stream()
                    .filter(ticket -> ticketId != null && ticketId.equals(ticket.id))
                    .findFirst().orElse(null);
        }

        /** {@inheritDoc} */
        @Override
        public Invoice findInvoiceOfTicket(String ticketNumber) {
            return existing;
        }

        /** {@inheritDoc} */
        @Override
        public Invoice findInvoice(Long invoiceId) {
            return existing;
        }

        /** {@inheritDoc} */
        @Override
        public void save(AccountCustomer customer) {
            savedCustomers.add(customer);
        }

        /** {@inheritDoc} */
        @Override
        public void save(Invoice invoice) {
            savedInvoices.add(invoice);
        }
    }

    /** A number service that hands out predictable numbers and counts the draws. */
    private static class FakeNumbers extends TicketNumberService {

        /** How many document numbers were drawn. */
        private int documentDraws;

        /** How many customer numbers were drawn. */
        private int customerDraws;

        /** {@inheritDoc} */
        @Override
        public String nextDocumentNumber(DocumentType type) {
            documentDraws++;
            return "C04-" + type.getPrefix() + "000001";
        }

        /** {@inheritDoc} */
        @Override
        public String nextCustomerNumber() {
            customerDraws++;
            return "C04-CLI000001";
        }

        /** {@inheritDoc} */
        @Override
        public String getTerminalId() {
            return "C04";
        }
    }

    /** Settings that always print the article code, and carry an administered mask. */
    private static class FakeSettings extends PosSettingsService {

        /** The administered customer-creation mask; blank means the catalog default. */
        private String customerFields = "";

        /** {@inheritDoc} */
        @Override
        public boolean showEan() {
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public String invoiceCustomerFields() {
            return customerFields;
        }
    }

    /** An outbox that records the declarations instead of writing rows. */
    private static class FakeOutbox extends com.intermarche.pos.service.sync.SyncOutboxService {

        /** The entity types it was asked to declare, in order. */
        private final List<com.intermarche.pos.domain.SyncOutbox.EntityType> types =
                new ArrayList<>();

        /** The entity ids it was asked to declare, in order. */
        private final List<Long> ids = new ArrayList<>();

        /** {@inheritDoc} */
        @Override
        public void enqueue(com.intermarche.pos.domain.SyncOutbox.EntityType type, Long entityId) {
            types.add(type);
            ids.add(entityId);
        }
    }

    /** A printer that records what it was given instead of talking to a bridge. */
    private static class FakePrinter extends HardwareService {

        /** What it was asked to print. */
        private final List<String> printed = new ArrayList<>();

        /** How many times the paper was cut. */
        private int cuts;

        /** {@inheritDoc} */
        @Override
        public void printReceipt(String content) {
            printed.add(content);
        }

        /** {@inheritDoc} */
        @Override
        public void cutPaper() {
            cuts++;
        }
    }

    /** The service under test. */
    private InvoiceService service;

    /** The real register state the flow writes into. */
    private PosState state;

    /** The stand-in repository. */
    private FakeRepository repository;

    /** The stand-in number service. */
    private FakeNumbers numbers;

    /** The stand-in printer. */
    private FakePrinter printer;

    /** The stand-in back-office parameters. */
    private FakeSettings settings;

    /** The stand-in store-node outbox. */
    private FakeOutbox outbox;

    /**
     * Wires a fresh service to fresh stand-ins before each test.
     */
    @BeforeEach
    void setUp() {
        service = new InvoiceService();
        state = new PosState();
        repository = new FakeRepository();
        numbers = new FakeNumbers();
        printer = new FakePrinter();
        service.state = state;
        service.repository = repository;
        service.ticketNumberService = numbers;
        settings = new FakeSettings();
        outbox = new FakeOutbox();
        service.posSettingsService = settings;
        service.hardwareService = printer;
        service.syncOutboxService = outbox;
    }

    /**
     * Builds a closed ticket carrying one article and a store.
     *
     * @param id     its database id
     * @param number its ticket number
     * @return the ticket
     */
    private Ticket ticket(long id, String number) {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        store.address.city = "LILLE";
        Ticket ticket = new Ticket();
        ticket.id = id;
        ticket.ticketNumber = number;
        ticket.store = store;
        ticket.creationDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        TicketLine line = new TicketLine();
        line.productLabel = "ARTICLE";
        line.quantity = BigDecimal.ONE;
        line.unitPrice = new BigDecimal("12.00");
        line.totalPrice = new BigDecimal("12.00");
        line.vatRate = new BigDecimal("0.20");
        ticket.lines.add(line);
        return ticket;
    }

    /**
     * Builds an account customer.
     *
     * @param id   its database id
     * @param name its business name
     * @return the customer
     */
    private AccountCustomer customer(long id, String name) {
        AccountCustomer customer = new AccountCustomer();
        customer.id = id;
        customer.accountNumber = "C04-CLI000042";
        customer.companyName = name;
        customer.address = new Address();
        return customer;
    }

    /**
     * Puts the flow on its review step with a ticket and a customer.
     *
     * @return the ticket being billed
     */
    private Ticket onPreviewStep() {
        Ticket ticket = ticket(1L, "C04-00000417");
        AccountCustomer customer = customer(2L, "BOULANGERIE");
        repository.tickets.add(ticket);
        repository.customers.add(customer);
        service.chooseTicket("C04-00000417", "", "");
        service.chooseCustomer(2L);
        return ticket;
    }

    /**
     * Opening with closed tickets pre-fills the mask with the most recent one — the
     * true arm of the pre-fill guard.
     */
    @Test
    void openPreFillsTheMaskWithTheLastTicket() {
        repository.tickets.add(ticket(1L, "C04-00000417"));
        service.open();
        assertEquals("C04-00000417", state.invoice.ticketNumber);
        assertEquals(1, state.invoice.tickets.size());
        assertTrue(state.invoice.isOnTicketStep());
    }

    /**
     * Opening on a register with no closed ticket pre-fills nothing — the false arm.
     */
    @Test
    void openLeavesTheMaskEmptyWhenNothingIsClosed() {
        service.open();
        assertEquals("", state.invoice.ticketNumber);
        assertTrue(state.invoice.tickets.isEmpty());
    }

    /**
     * A ticket number that matches no closed ticket is refused, and the flow stays on
     * its first step.
     */
    @Test
    void chooseTicketRefusesAnUnknownNumber() {
        service.chooseTicket("C04-99999999", "", "");
        assertTrue(state.invoice.error.contains("TICKET INTROUVABLE"));
        assertNull(state.invoice.ticket);
        assertTrue(state.invoice.isOnTicketStep());
    }

    /**
     * A null number is read as an empty one rather than exploding — the null leg of
     * the trim guard.
     */
    @Test
    void chooseTicketReadsANullNumberAsEmpty() {
        service.chooseTicket(null, null, null);
        assertEquals("", state.invoice.ticketNumber);
        assertTrue(state.invoice.error.contains("TICKET INTROUVABLE"));
    }

    /**
     * A known closed ticket moves the flow to the customer step when no customer
     * is named yet — the forward path (customer null arm).
     */
    @Test
    void chooseTicketAcceptsAClosedTicket() {
        Ticket ticket = ticket(1L, "C04-00000417");
        repository.tickets.add(ticket);
        service.chooseTicket("  C04-00000417  ", "", "");
        assertEquals("", state.invoice.error);
        assertSame(ticket, state.invoice.ticket);
        assertTrue(state.invoice.isOnCustomerStep());
    }

    /**
     * Correcting the ticket from the review goes STRAIGHT BACK to the review: the
     * customer was kept, so walking the operator through that step again would
     * make correcting one choice cost the other — which is what going back was
     * meant to avoid (customer non-null arm).
     */
    @Test
    void chooseTicketReturnsToTheReviewWhenTheCustomerIsAlreadyNamed() {
        onPreviewStep();
        Ticket other = ticket(9L, "C04-00000999");
        repository.tickets.add(other);
        service.backToTicketStep();
        assertTrue(state.invoice.isOnTicketStep());
        service.chooseTicket("C04-00000999", "", "");
        assertSame(other, state.invoice.ticket);
        assertTrue(state.invoice.isOnPreviewStep());
        assertNotNull(state.invoice.customer);
        assertEquals("BOULANGERIE", state.invoice.customer.companyName);
    }

    /**
     * Correcting the customer from the review goes straight back to the review
     * too: the ticket was kept, and the customer step already ends there.
     */
    @Test
    void chooseCustomerReturnsToTheReviewWhenTheTicketIsAlreadyNamed() {
        onPreviewStep();
        AccountCustomer other = customer(9L, "EPICERIE");
        repository.customers.add(other);
        service.backToCustomerStep();
        assertTrue(state.invoice.isOnCustomerStep());
        service.chooseCustomer(9L);
        assertSame(other, state.invoice.customer);
        assertTrue(state.invoice.isOnPreviewStep());
        assertNotNull(state.invoice.ticket);
        assertEquals("C04-00000417", state.invoice.ticket.ticketNumber);
    }

    /**
     * A null search returns nothing without asking the repository — the null leg.
     */
    @Test
    void searchCustomersWithNullReturnsNothing() {
        service.searchCustomers(null);
        assertEquals("", state.invoice.customerSearch);
        assertTrue(state.invoice.customers.isEmpty());
        assertNull(repository.lastSearch);
    }

    /**
     * A blank search returns nothing without asking either — the blank leg.
     */
    @Test
    void searchCustomersWithBlankReturnsNothing() {
        service.searchCustomers("   ");
        assertTrue(state.invoice.customers.isEmpty());
        assertNull(repository.lastSearch);
    }

    /**
     * A real search is passed down lower-cased, and its matches are kept.
     */
    @Test
    void searchCustomersPassesTheFragmentDown() {
        repository.customers.add(customer(2L, "BOULANGERIE"));
        service.searchCustomers(" BouLan ");
        assertEquals("boulan", repository.lastSearch);
        assertEquals(1, state.invoice.customers.size());
    }

    /**
     * A search that finds nobody is REPORTED as such: the screen must be able to say
     * "aucun client" rather than come back identical, which reads as a dead button.
     */
    @Test
    void searchCustomersReportsAnEmptyResult() {
        service.searchCustomers("INCONNU");
        assertTrue(state.invoice.searched);
        assertTrue(state.invoice.isSearchWithoutMatch());
    }

    /**
     * A blank search is not a search: the screen goes back to its neutral state
     * instead of claiming nothing was found.
     */
    @Test
    void searchCustomersWithBlankIsNotASearch() {
        service.searchCustomers("BOU");
        service.searchCustomers("  ");
        assertFalse(state.invoice.searched);
        assertFalse(state.invoice.isSearchWithoutMatch());
    }

    /**
     * A customer id that matches nothing is refused and the flow stays put.
     */
    @Test
    void chooseCustomerRefusesAnUnknownId() {
        service.chooseCustomer(99L);
        assertEquals("CLIENT INTROUVABLE", state.invoice.error);
        assertNull(state.invoice.customer);
        assertFalse(state.invoice.isOnPreviewStep());
    }

    /**
     * A known customer moves the flow to the review step.
     */
    @Test
    void chooseCustomerAcceptsAKnownId() {
        AccountCustomer customer = customer(2L, "BOULANGERIE");
        repository.customers.add(customer);
        service.chooseCustomer(2L);
        assertSame(customer, state.invoice.customer);
        assertTrue(state.invoice.isOnPreviewStep());
        assertFalse(state.invoice.creatingCustomer);
    }

    /**
     * A creation with no business name at all is refused — the null leg of the guard.
     */
    @Test
    void createCustomerRefusesANullName() {
        service.createCustomer(null, "", "", "", "", "", "", "", "");
        assertEquals("RAISON SOCIALE OBLIGATOIRE", state.invoice.error);
        assertTrue(repository.savedCustomers.isEmpty());
    }

    /**
     * A creation with a blank business name is refused too — the blank leg.
     */
    @Test
    void createCustomerRefusesABlankName() {
        service.createCustomer("   ", "", "", "", "", "", "", "", "");
        assertEquals("RAISON SOCIALE OBLIGATOIRE", state.invoice.error);
        assertTrue(repository.savedCustomers.isEmpty());
    }

    /**
     * A full creation writes the customer, draws its account number and moves the flow
     * on; a two-word contact splits into a first and a last name.
     */
    @Test
    void createCustomerWritesAFullCustomer() {
        service.createCustomer("BOULANGERIE", "Marc VIDAL", "3 place", "92420", "VAUCRESSON",
                "SIRET", "FR1", "0102", "a@b.c");
        assertEquals(1, repository.savedCustomers.size());
        AccountCustomer created = repository.savedCustomers.get(0);
        assertEquals("C04-CLI000001", created.accountNumber);
        assertEquals(1, numbers.customerDraws);
        assertEquals(0, numbers.documentDraws);
        assertEquals("BOULANGERIE", created.companyName);
        assertEquals("Marc", created.firstName);
        assertEquals("VIDAL", created.lastName);
        assertEquals("3 place", created.address.streetLine1);
        assertEquals("92420", created.address.postalCode);
        assertEquals("VAUCRESSON", created.address.city);
        assertEquals("SIRET", created.siret);
        assertEquals("FR1", created.vatNumber);
        assertTrue(state.invoice.isOnPreviewStep());
    }

    /**
     * A one-word contact becomes the last name alone, and every blank optional field
     * is stored as missing rather than as an empty string.
     */
    @Test
    void createCustomerHandlesAOneWordContactAndBlankOptionals() {
        service.createCustomer("BOULANGERIE", "VIDAL", "", "  ", "", null, "", "", "");
        AccountCustomer created = repository.savedCustomers.get(0);
        assertEquals("", created.firstName);
        assertEquals("VIDAL", created.lastName);
        assertNull(created.address.streetLine1);
        assertNull(created.address.postalCode);
        assertNull(created.siret);
        assertNull(created.vatNumber);
        assertNull(created.phone);
        assertNull(created.email);
    }

    /**
     * A creation with no contact at all leaves both name parts empty.
     */
    @Test
    void createCustomerHandlesAMissingContact() {
        service.createCustomer("BOULANGERIE", null, "", "", "", "", "", "", "");
        AccountCustomer created = repository.savedCustomers.get(0);
        assertEquals("", created.firstName);
        assertEquals("", created.lastName);
    }

    /**
     * There is nothing to preview before a ticket is named.
     */
    @Test
    void previewIsEmptyWithoutATicket() {
        assertNull(service.preview());
    }

    /**
     * There is nothing to preview before a customer is named either — the second leg
     * of that guard.
     */
    @Test
    void previewIsEmptyWithoutACustomer() {
        repository.tickets.add(ticket(1L, "C04-00000417"));
        service.chooseTicket("C04-00000417", "", "");
        assertNull(service.preview());
    }

    /**
     * The preview lays the document out WITHOUT drawing a number or writing anything —
     * which is what makes the abandon free.
     */
    @Test
    void previewDrawsNoNumberAndWritesNothing() {
        onPreviewStep();
        InvoiceDocument document = service.preview();
        assertEquals("", document.number);
        assertEquals("FACTURE", document.title);
        assertEquals("C04-00000417", document.ticketNumber);
        assertEquals(0, numbers.documentDraws);
        assertTrue(repository.savedInvoices.isEmpty());
        assertTrue(printer.printed.isEmpty());
    }

    /**
     * The preview RE-READS the ticket and the customer instead of using the instances
     * the screen has been carrying since the earlier steps.
     * <p>
     * Those instances were loaded by a previous request and their Hibernate session is
     * closed, so reading the ticket's lines off them fails. This test does not prove
     * the detachment — a stand-in repository hands back the same objects — it proves
     * the RE-READ, which is the thing that must not be removed by a later
     * simplification.
     */
    @Test
    void previewRereadsTheTicketAndTheCustomer() {
        onPreviewStep();
        int ticketReadsBefore = repository.ticketReads;
        int customerReadsBefore = repository.customerReads;
        service.preview();
        assertEquals(ticketReadsBefore + 1, repository.ticketReads);
        assertEquals(customerReadsBefore + 1, repository.customerReads);
    }

    /**
     * A preview whose ticket has vanished between two steps lays out nothing rather
     * than failing — the ticket leg of the re-read guard.
     */
    @Test
    void previewIsEmptyWhenTheTicketVanished() {
        onPreviewStep();
        repository.tickets.clear();
        assertNull(service.preview());
    }

    /**
     * A preview whose customer has vanished lays out nothing either — the customer leg.
     */
    @Test
    void previewIsEmptyWhenTheCustomerVanished() {
        onPreviewStep();
        repository.customers.clear();
        assertNull(service.preview());
    }

    /**
     * Issuing without a ticket is refused and draws no number.
     */
    @Test
    void issueRefusesWithoutATicket() {
        assertNull(service.issue());
        assertEquals("TICKET OU CLIENT MANQUANT", state.invoice.error);
        assertEquals(0, numbers.documentDraws);
    }

    /**
     * Issuing without a customer is refused too — the second leg of that guard.
     */
    @Test
    void issueRefusesWithoutACustomer() {
        repository.tickets.add(ticket(1L, "C04-00000417"));
        service.chooseTicket("C04-00000417", "", "");
        assertNull(service.issue());
        assertEquals("TICKET OU CLIENT MANQUANT", state.invoice.error);
    }

    /**
     * Training mode issues nothing: a document is a real fiscal object and a training
     * gesture must neither produce one nor consume a number.
     */
    @Test
    void issueIsRefusedInTraining() {
        onPreviewStep();
        state.trainingMode = true;
        assertNull(service.issue());
        assertEquals("FACTURE INDISPONIBLE EN FORMATION", state.invoice.error);
        assertEquals(0, numbers.documentDraws);
        assertTrue(repository.savedInvoices.isEmpty());
    }

    /**
     * The first issue draws a number, writes the document, prints it as an original
     * and cuts the paper.
     */
    @Test
    void issueWritesAndPrintsTheOriginal() {
        onPreviewStep();
        service.issue();
        assertEquals(1, numbers.documentDraws);
        assertEquals(1, repository.savedInvoices.size());
        Invoice document = repository.savedInvoices.get(0);
        assertEquals("C04-F000001", document.documentNumber);
        assertEquals("C04", document.terminalId);
        assertEquals("C04-00000417", document.ticketNumber);
        assertEquals("BOULANGERIE", document.customerName);
        assertEquals(1, document.printCount);
        assertEquals(0, new BigDecimal("12.00").compareTo(document.totalIncludingTax));
        assertEquals(0, new BigDecimal("10.00").compareTo(document.totalExcludingTax));
        assertEquals(1, printer.printed.size());
        assertEquals(1, printer.cuts);
        assertFalse(printer.printed.get(0).contains("DUPLICATA"));
        assertEquals(document.id, state.invoice.issuedInvoiceId);
    }

    /**
     * A second issue on the same ticket produces a DUPLICATE, never a second original:
     * no new number is drawn and the print carries the duplicate mark.
     */
    @Test
    void issueOnAnAlreadyBilledTicketPrintsADuplicate() {
        Ticket ticket = onPreviewStep();
        Invoice already = new Invoice();
        already.documentNumber = "C04-F000001";
        already.documentType = DocumentType.FACTURE;
        already.terminalId = "C04";
        already.issueDate = LocalDateTime.of(2026, 9, 9, 16, 42);
        already.ticketNumber = ticket.ticketNumber;
        already.addressTo(customer(2L, "BOULANGERIE"));
        already.totalExcludingTax = new BigDecimal("10.00");
        already.totalVat = new BigDecimal("2.00");
        already.totalIncludingTax = new BigDecimal("12.00");
        already.printCount = 1;
        repository.existing = already;

        service.issue();

        assertEquals(0, numbers.documentDraws);
        assertEquals(2, already.printCount);
        assertSame(already, repository.savedInvoices.get(0));
        assertTrue(printer.printed.get(0).contains("DUPLICATA N°1"));
    }

    /**
     * A cancelled line is skipped when the document is built: its price counts neither
     * excluding nor including tax — the true arm of the {@code line.cancelled} guard,
     * exercised alongside the live line that provides the false arm.
     */
    @Test
    void issueExcludesCancelledLinesFromTheTotals() {
        Ticket ticket = ticket(1L, "C04-00000417");
        TicketLine cancelled = new TicketLine();
        cancelled.productLabel = "ANNULE";
        cancelled.quantity = BigDecimal.ONE;
        cancelled.unitPrice = new BigDecimal("5.00");
        cancelled.totalPrice = new BigDecimal("5.00");
        cancelled.vatRate = new BigDecimal("0.20");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        AccountCustomer customer = customer(2L, "BOULANGERIE");
        repository.tickets.add(ticket);
        repository.customers.add(customer);
        service.chooseTicket("C04-00000417", "", "");
        service.chooseCustomer(2L);
        service.issue();
        Invoice document = repository.savedInvoices.get(0);
        assertEquals(0, new BigDecimal("12.00").compareTo(document.totalIncludingTax));
        assertEquals(0, new BigDecimal("10.00").compareTo(document.totalExcludingTax));
    }

    /**
     * An issued document can be laid out again from its id.
     */
    @Test
    void issuedLaysOutAnAlreadyIssuedDocument() {
        Ticket ticket = ticket(1L, "C04-00000417");
        repository.tickets.add(ticket);
        Invoice already = new Invoice();
        already.documentNumber = "C04-F000001";
        already.documentType = DocumentType.FACTURE;
        already.terminalId = "C04";
        already.issueDate = LocalDateTime.of(2026, 9, 9, 16, 42);
        already.ticketNumber = ticket.ticketNumber;
        already.addressTo(customer(2L, "BOULANGERIE"));
        already.totalExcludingTax = new BigDecimal("10.00");
        already.totalVat = new BigDecimal("2.00");
        already.totalIncludingTax = new BigDecimal("12.00");
        repository.existing = already;

        InvoiceDocument document = service.issued(7L);
        assertEquals("C04-F000001", document.number);
        assertEquals("BOULANGERIE", document.customer.name());
    }

    /**
     * An id that matches no document lays out nothing.
     */
    @Test
    void issuedIsEmptyForAnUnknownDocument() {
        assertNull(service.issued(7L));
    }

    /**
     * A document whose ticket has vanished lays out nothing either — the second leg
     * of that guard.
     */
    @Test
    void issuedIsEmptyWhenTheTicketIsGone() {
        Invoice already = new Invoice();
        already.ticketNumber = "C04-00000417";
        repository.existing = already;
        assertNull(service.issued(7L));
    }

    // --- Going back: correcting one choice must not cost the other ---

    /**
     * Going back to the ticket step drops the ticket and KEEPS the customer: the
     * operator came back to name another sale, not to retype the client.
     */
    @Test
    void backToTicketStepDropsTheTicketAndKeepsTheCustomer() {
        onPreviewStep();
        long before = state.version;
        service.backToTicketStep();
        assertTrue(state.invoice.isOnTicketStep());
        assertNull(state.invoice.ticket);
        assertNotNull(state.invoice.customer);
        assertEquals("BOULANGERIE", state.invoice.customer.companyName);
        assertEquals("", state.invoice.error);
        assertEquals(before + 1, state.version);
    }

    /**
     * Going back to the customer step drops the customer and KEEPS the ticket.
     */
    @Test
    void backToCustomerStepDropsTheCustomerAndKeepsTheTicket() {
        onPreviewStep();
        long before = state.version;
        service.backToCustomerStep();
        assertTrue(state.invoice.isOnCustomerStep());
        assertNull(state.invoice.customer);
        assertNotNull(state.invoice.ticket);
        assertEquals("C04-00000417", state.invoice.ticket.ticketNumber);
        assertEquals("", state.invoice.error);
        assertEquals(before + 1, state.version);
    }

    /**
     * Going back to the customer step closes a half-finished creation form: the
     * screen must reopen on the choice, not on the form the operator had left.
     */
    @Test
    void backToCustomerStepClosesTheCreationForm() {
        onPreviewStep();
        state.invoice.creatingCustomer = true;
        service.backToCustomerStep();
        assertFalse(state.invoice.creatingCustomer);
    }

    /**
     * Going back clears the error left by the step being redone, so a stale
     * refusal does not greet the operator on a screen they just corrected.
     */
    @Test
    void goingBackClearsTheStandingError() {
        onPreviewStep();
        state.invoice.error = "CLIENT INTROUVABLE";
        service.backToTicketStep();
        assertEquals("", state.invoice.error);
        state.invoice.error = "TICKET INTROUVABLE";
        service.backToCustomerStep();
        assertEquals("", state.invoice.error);
    }

    /**
     * Going back writes NOTHING and draws no number — the whole point of the
     * review step is that everything before issuing is free.
     */
    @Test
    void goingBackWritesNothingAndDrawsNoNumber() {
        onPreviewStep();
        service.backToTicketStep();
        service.backToCustomerStep();
        assertTrue(repository.savedInvoices.isEmpty());
        assertEquals(0, numbers.documentDraws);
    }

    /**
     * Giving up leaves nothing of the document behind, and nothing to undo: nothing
     * was ever written.
     */
    @Test
    void abandonClearsEverything() {
        onPreviewStep();
        service.abandon();
        assertTrue(state.invoice.isOnTicketStep());
        assertNull(state.invoice.ticket);
        assertNull(state.invoice.customer);
        assertTrue(repository.savedInvoices.isEmpty());
        assertEquals(0, numbers.documentDraws);
    }

    // ------------------------------------------------------------------
    // The ticket mask: number, date and register (LC-08-04-02)
    // ------------------------------------------------------------------

    /**
     * Opening the screen pre-fills the three fields of the mask from the register's
     * last ticket — the one an invoice is asked for nine times out of ten.
     */
    @Test
    void openPrefillsTheWholeTicketMask() {
        Ticket last = ticket(1L, "C04-00000417");
        last.terminalId = "C04";
        repository.tickets.add(last);
        service.open();
        assertEquals("C04-00000417", state.invoice.ticketNumber);
        assertEquals("09/09/2026", state.invoice.ticketDate);
        assertEquals("C04", state.invoice.ticketTerminal);
    }

    /**
     * With no closed ticket to copy, the mask still names THIS register: it is the
     * one truth the screen holds on its own.
     */
    @Test
    void openWithoutTicketStillNamesThisRegister() {
        service.open();
        assertEquals("", state.invoice.ticketNumber);
        assertEquals("", state.invoice.ticketDate);
        assertEquals("C04", state.invoice.ticketTerminal);
    }

    /**
     * A last ticket carrying no date leaves the date field blank rather than failing.
     */
    @Test
    void openTolerdatesATicketWithoutDate() {
        Ticket last = ticket(1L, "C04-00000417");
        last.creationDate = null;
        last.terminalId = "C04";
        repository.tickets.add(last);
        service.open();
        assertEquals("", state.invoice.ticketDate);
    }

    /**
     * A last ticket carrying no register leaves this register's own name in the mask.
     */
    @Test
    void openTolerantOfATicketWithoutRegister() {
        Ticket last = ticket(1L, "C04-00000417");
        last.terminalId = null;
        repository.tickets.add(last);
        service.open();
        assertEquals("C04", state.invoice.ticketTerminal);
    }

    /**
     * The date and the register typed narrow the lookup.
     */
    @Test
    void chooseTicketNarrowsByDateAndRegister() {
        Ticket wanted = ticket(1L, "C04-00000417");
        wanted.terminalId = "C04";
        repository.tickets.add(wanted);
        service.chooseTicket("C04-00000417", "09/09/2026", "C04");
        assertEquals(java.time.LocalDate.of(2026, 9, 9), repository.lastDate);
        assertEquals("C04", repository.lastTerminal);
        assertTrue(state.invoice.isOnCustomerStep());
    }

    /**
     * A date that names another day finds nothing: the criterion really discriminates.
     */
    @Test
    void chooseTicketRefusesAnotherDay() {
        Ticket wanted = ticket(1L, "C04-00000417");
        repository.tickets.add(wanted);
        service.chooseTicket("C04-00000417", "08/09/2026", "");
        assertTrue(state.invoice.isOnTicketStep());
        assertTrue(state.invoice.error.startsWith("TICKET INTROUVABLE"));
    }

    /**
     * A register that issued nothing under that number finds nothing.
     */
    @Test
    void chooseTicketRefusesAnotherRegister() {
        Ticket wanted = ticket(1L, "C04-00000417");
        wanted.terminalId = "C04";
        repository.tickets.add(wanted);
        service.chooseTicket("C04-00000417", "", "C09");
        assertTrue(state.invoice.isOnTicketStep());
    }

    /**
     * A blank date and a blank register widen the lookup instead of emptying it.
     */
    @Test
    void chooseTicketWithBlankCriteriaDoesNotNarrow() {
        repository.tickets.add(ticket(1L, "C04-00000417"));
        service.chooseTicket("C04-00000417", "  ", "  ");
        assertNull(repository.lastDate);
        assertNull(repository.lastTerminal);
        assertTrue(state.invoice.isOnCustomerStep());
    }

    /**
     * An UNREADABLE date widens the lookup too: a badly typed date must never stop an
     * invoice the register could perfectly well produce.
     */
    @Test
    void chooseTicketWithAnUnreadableDateDoesNotNarrow() {
        repository.tickets.add(ticket(1L, "C04-00000417"));
        service.chooseTicket("C04-00000417", "32/13/2026", "");
        assertNull(repository.lastDate);
        assertTrue(state.invoice.isOnCustomerStep());
    }

    // ------------------------------------------------------------------
    // Reaching a customer by number (LC-08-04-06)
    // ------------------------------------------------------------------

    /**
     * A known account number names the customer straight and goes to the review step.
     */
    @Test
    void chooseCustomerByNumberReachesTheCustomer() {
        AccountCustomer known = customer(2L, "BOULANGERIE");
        known.accountNumber = "C04-CLI000007";
        repository.customers.add(known);
        service.chooseCustomerByNumber("  C04-CLI000007 ");
        assertEquals("C04-CLI000007", repository.lastAccountNumber);
        assertEquals(known, state.invoice.customer);
        assertTrue(state.invoice.isOnPreviewStep());
    }

    /**
     * An unknown number tells the operator and leaves them where they are, with the
     * name search still available.
     */
    @Test
    void chooseCustomerByNumberRefusesAnUnknownNumber() {
        service.chooseCustomerByNumber("C04-CLI999999");
        assertNull(state.invoice.customer);
        assertTrue(state.invoice.error.startsWith("CLIENT INTROUVABLE"));
    }

    /**
     * A blank number is a refusal, not a lookup: nothing is asked of the database.
     */
    @Test
    void chooseCustomerByNumberRefusesABlankNumber() {
        service.chooseCustomerByNumber("   ");
        assertNull(repository.lastAccountNumber);
        assertEquals("NUMERO CLIENT VIDE", state.invoice.error);
    }

    /**
     * A missing number is a refusal too (null arm of the same guard).
     */
    @Test
    void chooseCustomerByNumberRefusesAMissingNumber() {
        service.chooseCustomerByNumber(null);
        assertNull(repository.lastAccountNumber);
        assertEquals("NUMERO CLIENT VIDE", state.invoice.error);
    }

    // ------------------------------------------------------------------
    // The administered customer mask (LC-08-04-10)
    // ------------------------------------------------------------------

    /**
     * With no administered mask the register asks for the whole catalog.
     */
    @Test
    void customerFieldsFallBackToTheCatalog() {
        settings.customerFields = "";
        assertEquals(9, service.customerFields().size());
    }

    /**
     * An administered mask decides which fields are asked for, and in which order.
     */
    @Test
    void customerFieldsFollowTheAdministeredMask() {
        settings.customerFields = "companyName*;city;siret";
        List<EntryField> fields = service.customerFields();
        assertEquals(3, fields.size());
        assertEquals("companyName", fields.get(0).name());
        assertEquals("city", fields.get(1).name());
        assertEquals("siret", fields.get(2).name());
    }

    /**
     * A field the administered mask marks mandatory and the operator left blank stops
     * the creation, naming that field.
     */
    @Test
    void createCustomerRefusesAMissingAdministeredField() {
        settings.customerFields = "companyName*;city*";
        service.createCustomer("BOULANGERIE", "", "", "", "", "", "", "", "");
        assertEquals("VILLE OBLIGATOIRE", state.invoice.error);
        assertTrue(repository.savedCustomers.isEmpty());
    }

    /**
     * The same field left blank while the mask does NOT require it lets the creation
     * through — the administered flag is what decides, not the field itself.
     */
    @Test
    void createCustomerAcceptsABlankOptionalField() {
        settings.customerFields = "companyName*;city";
        service.createCustomer("BOULANGERIE", "", "", "", "", "", "", "", "");
        assertEquals("", state.invoice.error);
        assertEquals(1, repository.savedCustomers.size());
    }

    /**
     * A mask that does not require the business name at all still refuses a customer
     * without one: it is what the document is addressed to.
     */
    @Test
    void createCustomerAlwaysRefusesAMissingBusinessName() {
        settings.customerFields = "city";
        service.createCustomer("", "", "", "", "VAUCRESSON", "", "", "", "");
        assertEquals("RAISON SOCIALE OBLIGATOIRE", state.invoice.error);
        assertTrue(repository.savedCustomers.isEmpty());
    }

    // ------------------------------------------------------------------
    // Declaring the customer to the back office (LC-08-04-09)
    // ------------------------------------------------------------------

    /**
     * A customer created at the register is declared to the store node, on the outbox
     * road, so a broken link postpones the declaration instead of losing it.
     */
    @Test
    void createCustomerDeclaresItToTheBackOffice() {
        service.createCustomer("BOULANGERIE", "", "", "", "", "", "", "", "");
        assertEquals(1, outbox.types.size());
        assertEquals(com.intermarche.pos.domain.SyncOutbox.EntityType.CUSTOMER,
                outbox.types.get(0));
    }

    /**
     * A mask that does not require the business name reaches the dedicated business-name
     * guard, where a null name is still refused: the true arm of {@code companyName ==
     * null} on that guard, which the administered-mask refusal cannot reach because it
     * only sees a blank name.
     */
    @Test
    void createCustomerRefusesANullNameEvenWhenTheMaskDoesNotRequireIt() {
        settings.customerFields = "city";
        service.createCustomer(null, "", "", "", "VAUCRESSON", "", "", "", "");
        assertEquals("RAISON SOCIALE OBLIGATOIRE", state.invoice.error);
        assertTrue(repository.savedCustomers.isEmpty());
    }

    /**
     * A refused creation declares nothing: there is no customer to declare.
     */
    @Test
    void refusedCreationDeclaresNothing() {
        service.createCustomer("   ", "", "", "", "", "", "", "", "");
        assertTrue(outbox.types.isEmpty());
    }
}
