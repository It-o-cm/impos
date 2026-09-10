package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Every database access of the invoice flow, and nothing else.
 *
 * <p>A SEAM, for the same reason the direct-entry screen has one: the register's unit
 * tests cannot lean on a database, and a service that calls Panache statics cannot be
 * unit-tested at all — a static call is not a collaborator you can replace. Gathering
 * the accesses here turns {@link InvoiceService} into a class whose whole behaviour is
 * decided by objects handed to it, which is what lets every guard of the flow be
 * exercised without a schema.
 *
 * <p>It holds no logic on purpose. Deciding what to do with what it returns belongs to
 * the service; a repository that starts deciding is a second service and the seam
 * stops meaning anything.
 */
@ApplicationScoped
public class InvoiceRepository {

    /**
     * Finds a closed ticket by its number.
     *
     * @param ticketNumber the ticket number
     * @return the ticket, or null when no CLOSED ticket carries that number
     */
    public Ticket findClosedTicket(String ticketNumber) {
        return Ticket.find("ticketNumber = ?1 and status = ?2",
                ticketNumber, Ticket.TicketStatus.CLOSED).firstResult();
    }

    /**
     * Finds a closed ticket by its number, narrowed by the date and the register the
     * operator named ({@code LC-08-04-02}).
     *
     * <p>Both extra criteria are OPTIONAL: a null date or a null register widens the
     * search instead of emptying it, because the number alone already identifies the
     * sale in this register's database. They become discriminating the day tickets of
     * other registers are searchable here.
     *
     * @param ticketNumber the ticket number
     * @param date the day the ticket was issued, or null when not narrowed
     * @param terminalId the register that issued it, or null when not narrowed
     * @return the ticket, or null when no CLOSED ticket matches every named criterion
     */
    public Ticket findClosedTicket(String ticketNumber, java.time.LocalDate date,
            String terminalId) {
        StringBuilder query = new StringBuilder("ticketNumber = ?1 and status = ?2");
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(ticketNumber);
        parameters.add(Ticket.TicketStatus.CLOSED);
        if (date != null) {
            query.append(" and creationDate >= ?").append(parameters.size() + 1);
            parameters.add(date.atStartOfDay());
            query.append(" and creationDate < ?").append(parameters.size() + 1);
            parameters.add(date.plusDays(1).atStartOfDay());
        }
        if (terminalId != null) {
            query.append(" and terminalId = ?").append(parameters.size() + 1);
            parameters.add(terminalId);
        }
        return Ticket.find(query.toString(), parameters.toArray()).firstResult();
    }

    /**
     * Finds an account customer by the number the operator typed
     * ({@code LC-08-04-06}).
     *
     * @param accountNumber the account number, already trimmed
     * @return the customer, or null when no customer carries that number
     */
    public AccountCustomer findCustomerByNumber(String accountNumber) {
        return AccountCustomer.find("accountNumber", accountNumber).firstResult();
    }

    /**
     * Lists the most recently closed tickets.
     *
     * @param limit how many at most
     * @return the tickets, most recent first
     */
    public List<Ticket> recentClosedTickets(int limit) {
        return Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED)
                .page(0, limit)
                .list();
    }

    /**
     * Finds the account customers whose business name contains the given fragment.
     *
     * @param fragment what the operator typed, already trimmed and lower-cased
     * @param limit    how many at most
     * @return the matching customers, by name
     */
    public List<AccountCustomer> searchCustomers(String fragment, int limit) {
        return AccountCustomer.find("lower(companyName) like ?1 ORDER BY companyName",
                        "%" + fragment + "%")
                .page(0, limit)
                .list();
    }

    /**
     * Finds an account customer by its database id.
     *
     * @param customerId the database id
     * @return the customer, or null
     */
    public AccountCustomer findCustomer(Long customerId) {
        return AccountCustomer.findById(customerId);
    }

    /**
     * Finds a ticket by its database id.
     *
     * @param ticketId the database id
     * @return the ticket, or null
     */
    public Ticket findTicket(Long ticketId) {
        return Ticket.findById(ticketId);
    }

    /**
     * Finds the document already issued on a ticket, if there is one.
     *
     * @param ticketNumber the ticket number
     * @return the document, or null when the ticket has never been billed
     */
    public Invoice findInvoiceOfTicket(String ticketNumber) {
        return Invoice.find("ticketNumber", ticketNumber).firstResult();
    }

    /**
     * Finds a document by its database id.
     *
     * @param invoiceId the database id
     * @return the document, or null
     */
    public Invoice findInvoice(Long invoiceId) {
        return Invoice.findById(invoiceId);
    }

    /**
     * Writes a customer.
     *
     * @param customer the customer to write
     */
    public void save(AccountCustomer customer) {
        customer.persist();
    }

    /**
     * Writes a document.
     *
     * @param invoice the document to write
     */
    public void save(Invoice invoice) {
        invoice.persist();
    }
}
