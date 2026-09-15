package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sale.DocumentType;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its {@link InvoiceState}
 * sub-state, an {@link InvoiceService} and two Qute {@link Template}s
 * ({@code invoice-request}, {@code invoice}). Every collaborator is a Mockito mock:
 * the templates echo a recognizable {@link TemplateInstance} so the returned view can
 * be identified, and the {@code state.invoice} holder's decisions
 * ({@code isOnTicketStep()}) and public fields ({@code creatingCustomer}) are driven
 * directly. The paginated document paths take a REAL {@link InvoiceDocument} built by
 * {@link InvoiceDocument#of} — its {@code lines} field is a public final field a mock
 * cannot echo, so a genuine document with a known number of article rows is required.
 * <p>
 * The tests cover both arms of every guard and every leg of the compound ones: the
 * three step configurations of {@code currentFields}/{@code currentAction} (ticket
 * step, customer lookup, customer creation), the three legs of the lookup
 * number-versus-name guard (filled, blank, null), the document null-versus-non-null
 * forks of {@code preview}/{@code showDocument}, the issued null-versus-non-null fork
 * of {@code issue}, and the pagination guards of {@code onePage} (asked null versus
 * non-null, below one, in range, above the page count; a single-page and a many-page
 * document).
 */
class InvoiceResourceTest {

    /**
     * Builds an {@link InvoiceResource} whose collaborators are fresh mocks wired onto
     * its package-private fields, including the {@link PosState#invoice} sub-state
     * holder so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private InvoiceResource newResource() {
        InvoiceResource resource = new InvoiceResource();
        resource.state = mock(PosState.class);
        resource.state.invoice = mock(InvoiceState.class);
        resource.invoiceService = mock(InvoiceService.class);
        resource.requestPage = mock(Template.class);
        resource.documentPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code invoice-request} template so the whole {@code .data(...)} chain
     * of {@code request()} returns one recognizable view.
     *
     * @param resource the resource whose {@code requestPage} template is stubbed
     * @return the view the request chain resolves to
     */
    private TemplateInstance stubRequest(InvoiceResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.requestPage.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code invoice} template so the whole {@code .data(...)} chain of
     * {@code onePage()} returns one recognizable view.
     *
     * @param resource the resource whose {@code documentPage} template is stubbed
     * @return the view the document chain resolves to
     */
    private TemplateInstance stubDocument(InvoiceResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.documentPage.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    /**
     * Builds a real laid-out document carrying the given number of article rows, so
     * {@code onePage()} can page a known number of lines.
     *
     * @param lineCount how many article rows the document states
     * @return the laid-out document
     */
    private InvoiceDocument documentOf(int lineCount) {
        Store store = new Store();
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "T-1";
        ticket.store = store;
        for (int i = 0; i < lineCount; i++) {
            TicketLine line = new TicketLine();
            line.productLabel = "ARTICLE " + i;
            line.quantity = BigDecimal.ONE;
            line.unitPrice = new BigDecimal("1.00");
            line.totalPrice = new BigDecimal("1.00");
            line.vatRate = new BigDecimal("0.20");
            ticket.lines.add(line);
        }
        Invoice invoice = new Invoice();
        invoice.documentType = DocumentType.FACTURE;
        invoice.documentNumber = "C04-F000001";
        invoice.terminalId = "C04";
        invoice.ticketNumber = "T-1";
        invoice.issueDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        return InvoiceDocument.of(invoice, ticket, false);
    }

    // --- showInvoiceScreen ---

    /**
     * {@code showInvoiceScreen()} opens the flow and renders the request page; the
     * ticket-step configuration drives the {@code isOnTicketStep()} true arm of both
     * {@code currentFields} and {@code currentAction}.
     */
    @Test
    void showInvoiceScreenOpensAndRendersRequest() {
        InvoiceResource resource = newResource();
        when(resource.state.invoice.isOnTicketStep()).thenReturn(true);
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.showInvoiceScreen());
        verify(resource.invoiceService).open();
    }

    // --- chooseTicket / pickTicket ---

    /**
     * {@code chooseTicket()} delegates the typed mask to the service and renders the
     * request page (ticket-step configuration).
     */
    @Test
    void chooseTicketDelegatesAndRendersRequest() {
        InvoiceResource resource = newResource();
        when(resource.state.invoice.isOnTicketStep()).thenReturn(true);
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.chooseTicket("C04-00000417", "09/09/2026", "C04"));
        verify(resource.invoiceService).chooseTicket("C04-00000417", "09/09/2026", "C04");
    }

    /**
     * {@code pickTicket()} names the ticket by its number alone, without narrowing, and
     * renders the request page.
     */
    @Test
    void pickTicketNamesByNumberAloneAndRendersRequest() {
        InvoiceResource resource = newResource();
        when(resource.state.invoice.isOnTicketStep()).thenReturn(true);
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.pickTicket("C04-00000417"));
        verify(resource.invoiceService).chooseTicket("C04-00000417", "", "");
    }

    // --- lookupCustomer: the three legs of the number-versus-name guard ---

    /**
     * {@code lookupCustomer()} with a filled account number reaches the customer by
     * number — the true leg of {@code customerNumber != null && !isBlank()}. The
     * customer-lookup step (not creating) drives the lookup arm of
     * {@code currentFields}/{@code currentAction}.
     */
    @Test
    void lookupCustomerWithFilledNumberChoosesByNumber() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.lookupCustomer("BOU", "C04-CLI000007"));
        verify(resource.invoiceService).chooseCustomerByNumber("C04-CLI000007");
    }

    /**
     * {@code lookupCustomer()} with a blank number falls back to the name search — the
     * blank leg (first condition true, second false) short-circuits to the else branch.
     */
    @Test
    void lookupCustomerWithBlankNumberSearchesByName() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.lookupCustomer("BOULANGERIE", "   "));
        verify(resource.invoiceService).searchCustomers("BOULANGERIE");
    }

    /**
     * {@code lookupCustomer()} with a null number falls back to the name search — the
     * null leg (first condition false) short-circuits to the else branch.
     */
    @Test
    void lookupCustomerWithNullNumberSearchesByName() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.lookupCustomer("BOULANGERIE", null));
        verify(resource.invoiceService).searchCustomers("BOULANGERIE");
    }

    // --- chooseCustomer / newCustomer / createCustomer ---

    /**
     * {@code chooseCustomer()} names the customer by id and renders the request page.
     */
    @Test
    void chooseCustomerDelegatesAndRendersRequest() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.chooseCustomer(2L));
        verify(resource.invoiceService).chooseCustomer(2L);
    }

    /**
     * {@code newCustomer()} opens the creation form, clears the error, touches the
     * state and renders the request page; {@code creatingCustomer} true drives the
     * creation arm of {@code currentFields}/{@code currentAction}.
     */
    @Test
    void newCustomerOpensCreationFormAndRendersRequest() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.newCustomer());
        assertEquals(true, resource.state.invoice.creatingCustomer);
        assertEquals("", resource.state.invoice.error);
        verify(resource.state).touch();
    }

    /**
     * {@code createCustomer()} passes every posted field to the service and renders the
     * request page.
     */
    @Test
    void createCustomerDelegatesAllFieldsAndRendersRequest() {
        InvoiceResource resource = newResource();
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.createCustomer("BOULANGERIE", "Marc VIDAL", "3 place",
                "92420", "VAUCRESSON", "SIRET", "FR1", "0102", "a@b.c"));
        verify(resource.invoiceService).createCustomer("BOULANGERIE", "Marc VIDAL", "3 place",
                "92420", "VAUCRESSON", "SIRET", "FR1", "0102", "a@b.c");
    }

    // --- preview: document null versus non-null, first-page pagination ---

    /**
     * {@code preview()} with no document yet falls back to the request page — the
     * {@code document == null} true arm.
     */
    @Test
    void previewWithoutDocumentRendersRequest() {
        InvoiceResource resource = newResource();
        when(resource.invoiceService.preview()).thenReturn(null);
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.preview(null));
    }

    /**
     * {@code preview()} with a single-page document and no page asked renders the
     * document page on page one — the {@code document != null} arm plus the
     * {@code asked == null} true arm, {@code page > 1} false and {@code page < pageCount}
     * false (one page, no previous, no next), issued flag false.
     */
    @Test
    void previewRendersFirstPageOfASinglePageDocument() {
        InvoiceResource resource = newResource();
        InvoiceDocument document = documentOf(1);
        when(resource.invoiceService.preview()).thenReturn(document);
        TemplateInstance view = stubDocument(resource);
        assertSame(view, resource.preview(null));
        verify(resource.documentPage).data("document", document);
        verify(view).data("issued", false);
        verify(view).data("page", 1);
        verify(view).data("pageCount", 1);
        verify(view).data("hasPrev", false);
        verify(view).data("hasNext", false);
    }

    /**
     * {@code preview()} asked for a middle page of a many-page document renders that
     * page — the {@code asked == null} false arm, {@code page > 1} true and
     * {@code page < pageCount} true (a page with both a previous and a next).
     */
    @Test
    void previewRendersAMiddlePageOfAManyPageDocument() {
        InvoiceResource resource = newResource();
        InvoiceDocument document = documentOf(7);
        when(resource.invoiceService.preview()).thenReturn(document);
        TemplateInstance view = stubDocument(resource);
        assertSame(view, resource.preview(2));
        verify(view).data("page", 2);
        verify(view).data("pageCount", 3);
        verify(view).data("hasPrev", true);
        verify(view).data("hasNext", true);
    }

    // --- issue: issued null versus non-null ---

    /**
     * {@code issue()} refused (no id) redirects back to the preview — the
     * {@code issued == null} true arm.
     */
    @Test
    void issueRefusedRedirectsToPreview() {
        InvoiceResource resource = newResource();
        when(resource.invoiceService.issue()).thenReturn(null);
        Response response = resource.issue();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/invoice/preview", response.getLocation().toString());
    }

    /**
     * {@code issue()} that drew a number redirects to the issued document — the
     * {@code issued != null} arm.
     */
    @Test
    void issueDoneRedirectsToTheIssuedDocument() {
        InvoiceResource resource = newResource();
        when(resource.invoiceService.issue()).thenReturn(7L);
        Response response = resource.issue();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/invoice/document/7", response.getLocation().toString());
    }

    // --- showDocument: document null versus non-null, pagination boundaries ---

    /**
     * {@code showDocument()} for an unknown id falls back to the request page — the
     * {@code document == null} true arm.
     */
    @Test
    void showDocumentForUnknownIdRendersRequest() {
        InvoiceResource resource = newResource();
        when(resource.invoiceService.issued(7L)).thenReturn(null);
        TemplateInstance view = stubRequest(resource);
        assertSame(view, resource.showDocument(7L, null));
    }

    /**
     * {@code showDocument()} for a known single-page document renders it on page one
     * with the issued flag true — the {@code document != null} arm and the issued path.
     */
    @Test
    void showDocumentRendersAnIssuedSinglePageDocument() {
        InvoiceResource resource = newResource();
        InvoiceDocument document = documentOf(1);
        when(resource.invoiceService.issued(7L)).thenReturn(document);
        TemplateInstance view = stubDocument(resource);
        assertSame(view, resource.showDocument(7L, null));
        verify(resource.documentPage).data("document", document);
        verify(view).data("issued", true);
        verify(view).data("page", 1);
    }

    /**
     * {@code showDocument()} asked for a page below one is brought back to page one —
     * the {@code asked != null} arm with the lower clamp (a stale low link is corrected,
     * not refused).
     */
    @Test
    void showDocumentClampsAPageBelowOneToTheFirst() {
        InvoiceResource resource = newResource();
        InvoiceDocument document = documentOf(7);
        when(resource.invoiceService.issued(7L)).thenReturn(document);
        TemplateInstance view = stubDocument(resource);
        assertSame(view, resource.showDocument(7L, 0));
        verify(view).data("page", 1);
        verify(view).data("hasPrev", false);
    }

    /**
     * {@code showDocument()} asked for a page above the count is brought back to the
     * last page — the {@code asked != null} arm with the upper clamp, {@code page > 1}
     * true and {@code page < pageCount} false (a last page has a previous but no next).
     */
    @Test
    void showDocumentClampsAPageAboveTheCountToTheLast() {
        InvoiceResource resource = newResource();
        InvoiceDocument document = documentOf(7);
        when(resource.invoiceService.issued(7L)).thenReturn(document);
        TemplateInstance view = stubDocument(resource);
        assertSame(view, resource.showDocument(7L, 99));
        verify(view).data("page", 3);
        verify(view).data("hasPrev", true);
        verify(view).data("hasNext", false);
    }

    // --- navigation redirects ---

    /**
     * {@code backToTicket()} rewinds to the ticket step and redirects to the screen.
     */
    @Test
    void backToTicketRewindsAndRedirects() {
        InvoiceResource resource = newResource();
        Response response = resource.backToTicket();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/invoice", response.getLocation().toString());
        verify(resource.invoiceService).backToTicketStep();
    }

    /**
     * {@code backToCustomer()} rewinds to the customer step and redirects to the screen.
     */
    @Test
    void backToCustomerRewindsAndRedirects() {
        InvoiceResource resource = newResource();
        Response response = resource.backToCustomer();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/invoice", response.getLocation().toString());
        verify(resource.invoiceService).backToCustomerStep();
    }

    /**
     * {@code abandon()} gives the document up and redirects to the sale screen.
     */
    @Test
    void abandonGivesUpAndRedirectsToSale() {
        InvoiceResource resource = newResource();
        Response response = resource.abandon();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.invoiceService).abandon();
    }
}
