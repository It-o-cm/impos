package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceRepository}.
 * <p>
 * The repository is a pure Panache seam: every method is one static finder or one
 * {@code persist()} and nothing else. It is exercised without a database by
 * neutralizing the static finders on {@link PanacheEntityBase} (to which the
 * {@code Ticket}/{@code AccountCustomer}/{@code Invoice} static calls resolve when
 * the entities are not bytecode-enhanced) and by handing the {@code save} methods a
 * Mockito mock whose {@code persist()} is a no-op. Each finder is asserted on the
 * exact query string and parameters passed and on the returned entity being the very
 * object the query yielded. The only class carrying branches is the narrowed
 * {@code findClosedTicket(number, date, terminalId)}: its two guards (date and
 * register) are each covered on both legs — the four combinations null/null,
 * date-only, register-only and both — which is the whole 4-branch budget.
 */
class InvoiceRepositoryTest {

    /** The base query shared by both closed-ticket finders. */
    private static final String BASE = "ticketNumber = ?1 and status = ?2";

    /** The ticket number used throughout. */
    private static final String NUMBER = "C04-000123";

    /** The register id used to narrow the search. */
    private static final String TERMINAL = "C04";

    /** The system under test. */
    private final InvoiceRepository repository = new InvoiceRepository();

    /**
     * Builds a Panache query whose {@code firstResult()} yields the given entity.
     *
     * @param result the entity to return, possibly null
     * @param <T> the entity type
     * @return the wired query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a Panache query whose {@code page(0, limit).list()} yields the list.
     *
     * @param results the list to return
     * @param limit the page size the production code asks for
     * @param <T> the entity type
     * @return the wired query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryListing(List<T> results, int limit) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.page(0, limit)).thenReturn(query);
        when(query.list()).thenReturn(results);
        return query;
    }

    /**
     * findClosedTicket(number): passes the number and the CLOSED status to the base
     * query and returns its first result.
     */
    @Test
    void findClosedTicketByNumberReturnsFirstResult() {
        Ticket ticket = mock(Ticket.class);
        PanacheQuery<Ticket> query = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(BASE, NUMBER, Ticket.TicketStatus.CLOSED))
                    .thenReturn(query);
            assertSame(ticket, repository.findClosedTicket(NUMBER));
            panache.verify(() -> Ticket.find(BASE, NUMBER, Ticket.TicketStatus.CLOSED));
        }
    }

    /**
     * findClosedTicket(number, date, terminalId): date null AND terminalId null — the
     * first (date) guard false leg and the second (register) guard false leg. The
     * query stays the bare base query with only the two mandatory parameters.
     */
    @Test
    void findNarrowedTicketWithNoDateAndNoTerminalUsesBareQuery() {
        Ticket ticket = mock(Ticket.class);
        PanacheQuery<Ticket> query = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(BASE, NUMBER, Ticket.TicketStatus.CLOSED))
                    .thenReturn(query);
            assertSame(ticket, repository.findClosedTicket(NUMBER, null, null));
            panache.verify(() -> Ticket.find(BASE, NUMBER, Ticket.TicketStatus.CLOSED));
        }
    }

    /**
     * findClosedTicket(number, date, terminalId): date non-null AND terminalId null —
     * the date guard true leg with the register guard false leg. Two creationDate
     * bounds (?3, ?4) are appended and their day-start values passed.
     */
    @Test
    void findNarrowedTicketWithDateOnlyAppendsDateBounds() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime next = date.plusDays(1).atStartOfDay();
        String expected = BASE + " and creationDate >= ?3 and creationDate < ?4";
        Ticket ticket = mock(Ticket.class);
        PanacheQuery<Ticket> query = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, start, next))
                    .thenReturn(query);
            assertSame(ticket, repository.findClosedTicket(NUMBER, date, null));
            panache.verify(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, start, next));
        }
    }

    /**
     * findClosedTicket(number, date, terminalId): date null AND terminalId non-null —
     * the date guard false leg with the register guard true leg. Only a terminalId
     * criterion (?3) is appended, numbered straight after the two mandatory params.
     */
    @Test
    void findNarrowedTicketWithTerminalOnlyAppendsTerminalCriterion() {
        String expected = BASE + " and terminalId = ?3";
        Ticket ticket = mock(Ticket.class);
        PanacheQuery<Ticket> query = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, TERMINAL))
                    .thenReturn(query);
            assertSame(ticket, repository.findClosedTicket(NUMBER, null, TERMINAL));
            panache.verify(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, TERMINAL));
        }
    }

    /**
     * findClosedTicket(number, date, terminalId): date non-null AND terminalId
     * non-null — both guard true legs together. The date bounds take ?3/?4 and the
     * terminalId criterion is numbered ?5 after them.
     */
    @Test
    void findNarrowedTicketWithDateAndTerminalAppendsBothCriteria() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime next = date.plusDays(1).atStartOfDay();
        String expected = BASE + " and creationDate >= ?3 and creationDate < ?4 and terminalId = ?5";
        Ticket ticket = mock(Ticket.class);
        PanacheQuery<Ticket> query = queryReturning(ticket);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, start, next, TERMINAL))
                    .thenReturn(query);
            assertSame(ticket, repository.findClosedTicket(NUMBER, date, TERMINAL));
            panache.verify(() -> Ticket.find(expected, NUMBER, Ticket.TicketStatus.CLOSED, start, next, TERMINAL));
        }
    }

    /**
     * findCustomerByNumber(number): queries AccountCustomer on the accountNumber field
     * and returns the first result.
     */
    @Test
    void findCustomerByNumberReturnsFirstResult() {
        AccountCustomer customer = mock(AccountCustomer.class);
        PanacheQuery<AccountCustomer> query = queryReturning(customer);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("accountNumber", "C-0001")).thenReturn(query);
            assertSame(customer, repository.findCustomerByNumber("C-0001"));
            panache.verify(() -> AccountCustomer.find("accountNumber", "C-0001"));
        }
    }

    /**
     * recentClosedTickets(limit): orders CLOSED tickets by creationDate descending,
     * pages from zero with the requested size and returns the page's list.
     */
    @Test
    void recentClosedTicketsReturnsPagedList() {
        Ticket ticket = mock(Ticket.class);
        List<Ticket> tickets = List.of(ticket);
        PanacheQuery<Ticket> query = queryListing(tickets, 5);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.find("status = ?1 ORDER BY creationDate DESC",
                    Ticket.TicketStatus.CLOSED)).thenReturn(query);
            assertSame(tickets, repository.recentClosedTickets(5));
            verify(query).page(0, 5);
        }
    }

    /**
     * searchCustomers(fragment, limit): builds the case-insensitive like query with
     * the wrapped fragment, pages and returns the list.
     */
    @Test
    void searchCustomersReturnsPagedList() {
        AccountCustomer customer = mock(AccountCustomer.class);
        List<AccountCustomer> customers = List.of(customer);
        PanacheQuery<AccountCustomer> query = queryListing(customers, 10);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("lower(companyName) like ?1 ORDER BY companyName",
                    "%mairie%")).thenReturn(query);
            assertSame(customers, repository.searchCustomers("mairie", 10));
            verify(query).page(0, 10);
        }
    }

    /**
     * findCustomer(id): delegates to AccountCustomer.findById and returns whatever it
     * yields.
     */
    @Test
    void findCustomerByIdReturnsEntity() {
        AccountCustomer customer = mock(AccountCustomer.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertSame(customer, repository.findCustomer(7L));
        }
    }

    /**
     * findTicket(id): delegates to Ticket.findById and returns whatever it yields.
     */
    @Test
    void findTicketByIdReturnsEntity() {
        Ticket ticket = mock(Ticket.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(3L)).thenReturn(ticket);
            assertSame(ticket, repository.findTicket(3L));
        }
    }

    /**
     * findInvoiceOfTicket(number, kind): queries Invoice on BOTH the ticket number
     * and the document kind, and returns the first result (null when the ticket
     * carries no document of that kind).
     *
     * <p>The kind is part of the lookup because a sale carries one document of each
     * kind ({@code LC-08-04-17}): answering an invoice request with the delivery note
     * the same ticket already has would reprint the wrong paper.
     */
    @Test
    void findInvoiceOfTicketReturnsFirstResult() {
        PanacheQuery<Invoice> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.find("ticketNumber = ?1 and documentType = ?2",
                    NUMBER, com.intermarche.pos.domain.ticket.DocumentType.FACTURE))
                    .thenReturn(query);
            assertNull(repository.findInvoiceOfTicket(NUMBER,
                    com.intermarche.pos.domain.ticket.DocumentType.FACTURE));
            panache.verify(() -> Invoice.find("ticketNumber = ?1 and documentType = ?2",
                    NUMBER, com.intermarche.pos.domain.ticket.DocumentType.FACTURE));
        }
    }

    /**
     * findInvoice(id): delegates to Invoice.findById and returns whatever it yields.
     */
    @Test
    void findInvoiceByIdReturnsEntity() {
        Invoice invoice = mock(Invoice.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Invoice.findById(9L)).thenReturn(invoice);
            assertSame(invoice, repository.findInvoice(9L));
        }
    }

    /**
     * save(customer): persists the customer handed to it.
     */
    @Test
    void saveCustomerPersistsIt() {
        AccountCustomer customer = mock(AccountCustomer.class);
        repository.save(customer);
        verify(customer).persist();
    }

    /**
     * save(invoice): persists the document handed to it.
     */
    @Test
    void saveInvoicePersistsIt() {
        Invoice invoice = mock(Invoice.class);
        repository.save(invoice);
        verify(invoice).persist();
    }
}
