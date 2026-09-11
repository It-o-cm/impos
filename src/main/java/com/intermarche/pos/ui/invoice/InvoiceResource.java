package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * JAX-RS resource of the invoice screen, under its own {@code /invoice} prefix.
 *
 * <p>Three steps and one commitment: naming the ticket, naming the customer and
 * looking at the finished document are all free — they write nothing and draw no
 * number — while {@code /invoice/issue} is the point of no return. That is what makes
 * the abandon of {@code LC-08-04-14} real rather than nominal.
 *
 * <p>Everything before the issue is a GET or a form POST that lands back on the same
 * screen; the issue redirects to the issued document, so a browser refresh re-reads a
 * document instead of issuing a second one.
 */
@Path("/invoice")
@DrawerMustBeClosed
public class InvoiceResource {

    /** The register state, carrying the invoice sub-state the screen reads. */
    @Inject
    PosState state;

    /**
     * How many article rows one screen of the document shows.
     *
     * <p>A document is READ on this screen, not scrolled: nothing else in this
     * register asks an operator to drag a long page with a finger, and a till's screen
     * is not made for it. The rows are therefore paginated like the reprint list and
     * the ticket lines. THREE, not more: the parties, the metadata, the VAT table, the
     * settlement and the totals stay on every screen — they are what the rows are
     * checked against — and they take most of the 436 px the terminal leaves.
     */
    private static final int LINES_PER_PAGE = 3;

    /** The invoice flow. */
    @Inject
    InvoiceService invoiceService;

    /** The screen where the ticket and the customer are named. */
    @Inject
    @Location("invoice-request")
    Template requestPage;

    /** The document itself, laid out as it will be issued. */
    @Inject
    @Location("invoice")
    Template documentPage;

    /**
     * Opens the screen on its first step, with the closed-ticket shortlist and the
     * register's last ticket pre-filled.
     *
     * @return the request page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showInvoiceScreen() {
        invoiceService.open();
        return request();
    }

    /**
     * Renders the request page with the entry fields of the step it is on.
     *
     * <p>Every step of this screen is a mask typed on the on-screen keyboard, and the
     * page carries ONE list of fields whatever the step: the ticket mask
     * ({@code LC-08-04-02}), the customer lookup ({@code LC-08-04-06/07}) or the
     * administered customer-creation mask ({@code LC-08-04-10}). The template
     * therefore has one entry mechanism instead of three.
     *
     * @return the request page
     */
    private TemplateInstance request() {
        return requestPage
                .data("state", state)
                .data("fields", currentFields())
                .data("formAction", currentAction())
                .data("hasDocumentStep", invoiceService.eligibleDocumentTypes().size() > 1);
    }

    /**
     * Returns the fields the current step asks for.
     *
     * @return the fields, in entry order, never empty
     */
    private java.util.List<EntryField> currentFields() {
        if (state.invoice.isOnTicketStep()) {
            return EntryField.ticketFields(state.invoice.ticketNumber,
                    state.invoice.ticketDate, state.invoice.ticketTerminal);
        }
        if (state.invoice.creatingCustomer) {
            return invoiceService.customerFields();
        }
        return EntryField.customerLookupFields(state.invoice.customerSearch,
                state.invoice.customerNumber);
    }

    /**
     * Returns the form the current step posts to.
     *
     * @return the form action path
     */
    private String currentAction() {
        if (state.invoice.isOnTicketStep()) {
            return "/invoice/ticket";
        }
        return state.invoice.creatingCustomer
                ? "/invoice/customer/create" : "/invoice/customer/lookup";
    }

    /**
     * Names the ticket the document will state, from the number, the date and the
     * register the operator typed ({@code LC-08-04-02}).
     *
     * @param ticketNumber the number of the closed ticket to bill
     * @param ticketDate the day it was issued, blank when not narrowed
     * @param ticketTerminal the register that issued it, blank when not narrowed
     * @return the request page, on the customer step when the ticket was found
     */
    @POST
    @jakarta.ws.rs.Path("/ticket")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance chooseTicket(@FormParam("ticketNumber") String ticketNumber,
            @FormParam("ticketDate") String ticketDate,
            @FormParam("ticketTerminal") String ticketTerminal) {
        invoiceService.chooseTicket(ticketNumber, ticketDate, ticketTerminal);
        return request();
    }

    /**
     * Names the ticket from the shortlist, without typing its number.
     *
     * @param ticketNumber the number of the closed ticket to bill
     * @return the request page, on the customer step
     */
    @GET
    @jakarta.ws.rs.Path("/ticket/{ticketNumber}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pickTicket(@PathParam("ticketNumber") String ticketNumber) {
        // Picked from the list, the ticket is named by its number alone: the row the
        // operator touched IS the ticket, so narrowing it further could only miss it.
        invoiceService.chooseTicket(ticketNumber, "", "");
        return request();
    }

    /**
     * Names the kind of document to draw ({@code LC-08-04-04}).
     *
     * @param type the enum name of the kind the operator touched
     * @return the request page, on the customer or the review step
     */
    @GET
    @jakarta.ws.rs.Path("/type/{type}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance chooseDocumentType(@PathParam("type") String type) {
        invoiceService.chooseDocumentType(type);
        return request();
    }

    /**
     * Looks a customer up: straight by account number when the operator typed one
     * ({@code LC-08-04-06}), by name otherwise ({@code LC-08-04-07}).
     *
     * <p>The number WINS when both are filled: it names exactly one customer, where a
     * name may name several — answering a filled number with a list of near-matches
     * would be answering a question the operator did not ask.
     *
     * @param search the name fragment typed, possibly blank
     * @param customerNumber the account number typed, possibly blank
     * @return the request page, on the review step when a number was resolved
     */
    @POST
    @jakarta.ws.rs.Path("/customer/lookup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance lookupCustomer(@FormParam("search") String search,
            @FormParam("customerNumber") String customerNumber) {
        if (customerNumber != null && !customerNumber.isBlank()) {
            invoiceService.chooseCustomerByNumber(customerNumber);
        } else {
            invoiceService.searchCustomers(search);
        }
        return request();
    }

    /**
     * Names the customer the document is addressed to.
     *
     * @param id the database id of the customer
     * @return the request page, on the review step
     */
    @GET
    @jakarta.ws.rs.Path("/customer/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance chooseCustomer(@PathParam("id") Long id) {
        invoiceService.chooseCustomer(id);
        return request();
    }

    /**
     * Opens the form that creates a customer at the register.
     *
     * @return the request page, showing the creation form
     */
    @GET
    @jakarta.ws.rs.Path("/customer/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance newCustomer() {
        state.invoice.creatingCustomer = true;
        state.invoice.error = "";
        state.touch();
        return request();
    }

    /**
     * Creates the customer and addresses the document to it.
     *
     * @param companyName the business name
     * @param contactName the contact's name
     * @param street      the street line
     * @param postalCode  the postal code
     * @param city        the town
     * @param siret       the SIRET
     * @param vatNumber   the intra-community VAT number
     * @param phone       the telephone number
     * @param email       the electronic address
     * @return the request page, on the review step when the creation worked
     */
    @POST
    @jakarta.ws.rs.Path("/customer/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance createCustomer(@FormParam("companyName") String companyName,
            @FormParam("contactName") String contactName,
            @FormParam("street") String street,
            @FormParam("postalCode") String postalCode,
            @FormParam("city") String city,
            @FormParam("siret") String siret,
            @FormParam("vatNumber") String vatNumber,
            @FormParam("phone") String phone,
            @FormParam("email") String email) {
        invoiceService.createCustomer(companyName, contactName, street, postalCode, city,
                siret, vatNumber, phone, email);
        return request();
    }

    /**
     * Shows the document as it will be issued — no number yet, nothing written.
     *
     * @return the document page, or the request page when a step is still missing
     */
    @GET
    @jakarta.ws.rs.Path("/preview")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance preview(@QueryParam("page") Integer page) {
        InvoiceDocument document = invoiceService.preview();
        if (document == null) {
            return request();
        }
        return onePage(document, false, page, "/invoice/preview?page=");
    }

    /**
     * Issues the document: draws its number, writes it and prints it.
     *
     * @return a redirect to the issued document, or back to the screen on refusal
     */
    @POST
    @jakarta.ws.rs.Path("/issue")
    public Response issue() {
        Long issued = invoiceService.issue();
        if (issued == null) {
            return Response.seeOther(URI.create("/invoice/preview")).build();
        }
        // LC-08-04-12: a document that comes out of the slip station is not printed
        // yet. The operator is sent to the sheet-by-sheet screen, which tells them how
        // many sheets to have ready BEFORE the first one goes in.
        if (state.invoice.isOnInsertStep()) {
            return Response.seeOther(URI.create("/invoice/slip")).build();
        }
        return Response.seeOther(URI.create("/invoice/document/" + issued)).build();
    }

    /**
     * Shows the sheet the slip station is waiting for ({@code LC-08-04-12/13}).
     *
     * <p>A plain render, and NOT the screen's entry point: {@code /invoice} opens the
     * flow from nothing, which on an issued document would throw away the sheets still
     * to be printed.
     *
     * @return the request page, on its insertion step
     */
    @GET
    @jakarta.ws.rs.Path("/slip")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showSlip() {
        return request();
    }

    /**
     * Prints the sheet the operator has just fed the station, and asks for the next.
     *
     * @return the insertion screen while sheets remain, the issued document once the
     *         last has come out
     */
    @POST
    @jakarta.ws.rs.Path("/slip")
    public Response printSlip() {
        if (invoiceService.printNextSlip()) {
            return Response.seeOther(URI.create("/invoice/slip")).build();
        }
        Long issued = state.invoice.issuedInvoiceId;
        if (issued == null) {
            return Response.seeOther(URI.create("/invoice")).build();
        }
        return Response.seeOther(URI.create("/invoice/document/" + issued)).build();
    }

    /**
     * Shows an issued document again.
     *
     * @param id the database id of the document
     * @return the document page, or the request page when there is no such document
     */
    @GET
    @jakarta.ws.rs.Path("/document/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance showDocument(@PathParam("id") Long id,
            @QueryParam("page") Integer page) {
        InvoiceDocument document = invoiceService.issued(id);
        if (document == null) {
            return request();
        }
        return onePage(document, true, page, "/invoice/document/" + id + "?page=");
    }

    /**
     * Shows one screen of a document.
     *
     * <p>Only the article rows are paged; the parties, the metadata, the VAT table and
     * the totals stay on every screen, because they are what the operator checks the
     * rows against. A page number outside the document is brought back inside rather
     * than answered with an error — a stale link is not an incident.
     *
     * @param document the laid-out document
     * @param issued   whether it has been issued, which decides the actions offered
     * @param asked    the page asked for, {@code null} on the first screen
     * @param pageUrl  the link the pager appends a page number to
     * @return the document page
     */
    private TemplateInstance onePage(InvoiceDocument document, boolean issued, Integer asked,
            String pageUrl) {
        int rows = document.lines.size();
        int pageCount = Math.max(1, (rows + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
        int page = asked == null ? 1 : Math.min(Math.max(asked, 1), pageCount);
        int from = (page - 1) * LINES_PER_PAGE;
        int to = Math.min(from + LINES_PER_PAGE, rows);
        return documentPage
                .data("document", document)
                .data("issued", issued)
                .data("pageLines", document.lines.subList(from, to))
                .data("page", page)
                .data("pageCount", pageCount)
                .data("hasPrev", page > 1)
                .data("hasNext", page < pageCount)
                .data("prevPage", page - 1)
                .data("nextPage", page + 1)
                .data("pageUrl", pageUrl);
    }

    /**
     * Goes back to the ticket step, keeping the customer already chosen.
     *
     * @return a redirect to the request screen
     */
    @GET
    @jakarta.ws.rs.Path("/back/ticket")
    public Response backToTicket() {
        invoiceService.backToTicketStep();
        return Response.seeOther(URI.create("/invoice")).build();
    }

    /**
     * Goes back to the document-kind step, keeping the ticket and the customer.
     *
     * @return a redirect to the request screen
     */
    @GET
    @jakarta.ws.rs.Path("/back/type")
    public Response backToDocumentType() {
        invoiceService.backToDocumentStep();
        return Response.seeOther(URI.create("/invoice")).build();
    }

    /**
     * Goes back to the customer step, keeping the ticket already chosen.
     *
     * @return a redirect to the request screen
     */
    @GET
    @jakarta.ws.rs.Path("/back/customer")
    public Response backToCustomer() {
        invoiceService.backToCustomerStep();
        return Response.seeOther(URI.create("/invoice")).build();
    }

    /**
     * Gives up the document being prepared and leaves the screen.
     *
     * @return a redirect to the sale screen
     */
    @GET
    @jakarta.ws.rs.Path("/abandon")
    public Response abandon() {
        invoiceService.abandon();
        return Response.seeOther(URI.create("/")).build();
    }
}
