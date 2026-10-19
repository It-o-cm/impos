package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the A4 page of {@link NetworkDocumentPrinter}.
 * <p>
 * What they are about is the ONE decision this class now carries: which layout
 * the office printer's page comes from. The engine is a REAL Qute engine, not a
 * stand-in, because a mocked one would assert nothing about a layout; the
 * referential is reached through the Panache statics of {@link DocumentTemplate}
 * and intercepted with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration: {@code page} covers the administered arm, the
 * unadministered arm and the no-renderer arm.
 */
class NetworkDocumentPrinterTest {
    /** The static mock silencing the catalog for every test, closed after each. */
    private MockedStatic<PanacheEntityBase> panache;

    /**
     * The document funnels of this class read the VAT referential (the legal
     * mentions, {@code VatRate.findByRate}) and, in places, the administered
     * document names — plain unit tests have no persistence context to
     * answer either with, so the catalog is silent for every test: no rate
     * resolves to a regime, so no mention prints, and every layout falls
     * back to the register's own wording.
     */
    @BeforeEach
    void silenceTheCatalog() {
        panache = mockStatic(PanacheEntityBase.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Object> nothing = mock(PanacheQuery.class);
        when(nothing.firstResult()).thenReturn(null);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class)))
                .thenReturn(nothing);
        panache.when(() -> PanacheEntityBase.list(anyString(), any(Object[].class)))
                .thenReturn(java.util.List.of());
    }

    /**
     * Closes the static mock the {@code @BeforeEach} opened, on the same
     * thread Mockito requires.
     */
    @AfterEach
    void closeTheCatalog() {
        panache.close();
    }


    /**
     * Builds the A4 printer with a real renderer wired.
     *
     * @return the printer
     */
    private NetworkDocumentPrinter withRenderer() {
        NetworkDocumentPrinter printer = new NetworkDocumentPrinter();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        printer.i18n = TestMessages.french();
        printer.documentTemplateService = new com.intermarche.pos.service.DocumentTemplateService(
                Engine.builder().addDefaults().build());
        return printer;
    }

    /**
     * Builds a laid-out invoice to render.
     *
     * @return the document
     */
    private InvoiceDocument laidOut() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        store.address.postalCode = "92420";
        store.address.city = "VAUCRESSON";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-000123";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C04-F000042";
        invoice.issueDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        invoice.customerName = "SARL DUPONT";
        invoice.customerDueDate = java.time.LocalDate.of(2026, 10, 31);
        return InvoiceDocument.of(invoice, ticket, false, TestMessages.french());
    }

    /**
     * Builds the same document WITHOUT administered terms, for the silent arm
     * of the due-date line.
     *
     * @return the document
     */
    private InvoiceDocument withoutDueDate() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-000123";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C04-F000042";
        invoice.issueDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        invoice.customerName = "SARL DUPONT";
        return InvoiceDocument.of(invoice, ticket, false, TestMessages.french());
    }

    /**
     * Builds an administered A4 layout.
     *
     * @param source the Qute source
     * @return the administered template
     */
    private DocumentTemplate layout(String source) {
        DocumentTemplate template = new DocumentTemplate();
        template.code = "FACTURE_A4";
        template.label = "Facture A4";
        template.documentType = DocumentTemplate.DocumentType.INVOICE_A4;
        template.active = true;
        template.width = 80;
        template.source = source;
        return template;
    }

    /**
     * An administered layout REPLACES the page built in Java — which is the
     * whole point of putting the A4 under the referential, since that page was
     * hand-assembled on a register that already runs Qute.
     */
    @Test
    void anAdministeredLayoutReplacesTheHandBuiltPage() {
        NetworkDocumentPrinter printer = withRenderer();
        InvoiceDocument document = laidOut();
        panache.when(() -> DocumentTemplate.list(
                        "active = true and documentType = ?1 order by priority, code",
                        DocumentTemplate.DocumentType.INVOICE_A4))
                .thenReturn(List.of(layout(
                        "<h1>{document.title} {document.number}</h1>"
                                + "<p>{seller.name}</p><p>{customer.name}</p>")));
        assertEquals("<h1>FACTURE C04-F000042</h1><p>MAGASIN</p><p>SARL DUPONT</p>",
                printer.page(document));
    }

    /**
     * A shop that administers no A4 layout keeps the page the class builds
     * itself, unchanged.
     */
    @Test
    void anUnadministeredA4KeepsTheHandBuiltPage() {
        NetworkDocumentPrinter printer = withRenderer();
        InvoiceDocument document = laidOut();
        panache.when(() -> DocumentTemplate.list(
                        "active = true and documentType = ?1 order by priority, code",
                        DocumentTemplate.DocumentType.INVOICE_A4))
                .thenReturn(List.of());
        assertEquals(NetworkDocumentPrinter.html(document, TestMessages.french()), printer.page(document));
    }

    /**
     * A printer built with no renderer at all keeps the hand-built page: the
     * null leg of the layout guard, which hands back the page the class
     * draws itself.
     */
    @Test
    void aPrinterWithoutARendererKeepsTheHandBuiltPage() {
        NetworkDocumentPrinter printer = new NetworkDocumentPrinter();
        printer.i18n = TestMessages.french();
        InvoiceDocument document = laidOut();
        String page = printer.page(document);
        assertEquals(NetworkDocumentPrinter.html(document, TestMessages.french()), page);
        assertTrue(page.startsWith("<!DOCTYPE html>"));
    }

    /**
     * The hand-built page carries the ÉCHÉANCE when the customer has terms, and
     * carries no such mention when they have none — the two arms of
     * {@code BO-02-04-08} on the A4 output.
     */
    @Test
    void theHandBuiltPageCarriesTheDueDateOnlyWhenThereIsOne() {
        String withTerms = NetworkDocumentPrinter.html(laidOut(), TestMessages.french());
        assertTrue(withTerms.contains("Échéance : 31/10/2026"),
                () -> "no due date on the page: " + withTerms);
        String without = NetworkDocumentPrinter.html(withoutDueDate(), TestMessages.french());
        assertFalse(without.contains("Échéance"),
                () -> "a due date was printed for a customer without terms: " + without);
    }

    /**
     * The A4 layout reads the SAME description as the roll layout: one document
     * restated twice, never described twice.
     */
    @Test
    void bothInvoiceLayoutsReadTheSameDescription() {
        NetworkDocumentPrinter printer = withRenderer();
        InvoiceDocument document = laidOut();
        panache.when(() -> DocumentTemplate.list(
                        "active = true and documentType = ?1 order by priority, code",
                        DocumentTemplate.DocumentType.INVOICE_A4))
                .thenReturn(List.of(layout("{totals.includingTax}")));
        assertEquals(document.asDocumentData().get("totals") instanceof java.util.Map
                        ? ((java.util.Map<?, ?>) document.asDocumentData().get("totals"))
                                .get("includingTax")
                        : null,
                printer.page(document));
    }
    // --------------------------------------------------
    // Coverage campaign — the hand-built page body and the network send
    // --------------------------------------------------

    /**
     * Builds a fully-populated document so the hand-built page exercises every
     * optional section: a duplicate mention, a seller block carrying a legal
     * name, a phone, a SIRET and a VAT number, a customer block carrying an
     * account number, a contact, a SIRET and a VAT number, an article row with
     * an attributes line and an EAN, a VAT table row and a settlement row.
     *
     * @return the rich document
     */
    private InvoiceDocument fullyPopulated() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.legalName = "INTERMARCHE SAS";
        store.phone = "0102030405";
        store.siret = "12345678900011";
        store.vatNumber = "FR12345678900011";
        store.rcs = "NANTERRE 123";
        store.shareCapital = new BigDecimal("50000.00");
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        store.address.postalCode = "92420";
        store.address.city = "VAUCRESSON";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-000123";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        TicketLine line = new TicketLine();
        line.productLabel = "POMMES";
        line.familyLabel = "FRUITS";
        line.ean = "3560070139682";
        line.quantity = new BigDecimal("2");
        line.unitPrice = new BigDecimal("1.50");
        line.totalPrice = new BigDecimal("3.00");
        line.vatRate = new BigDecimal("0.0550");
        ticket.lines = new ArrayList<>(List.of(line));
        ticket.payments = new ArrayList<>(List.of(
                new CashPayment(new BigDecimal("3.00"), new BigDecimal("3.00"))));
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C04-F000042";
        invoice.issueDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        invoice.printCount = 1;
        invoice.customerName = "SARL DUPONT";
        invoice.customerContact = "M. Durand";
        invoice.customerAccountNumber = "CPT-9";
        invoice.customerSiret = "98765432100011";
        invoice.customerVatNumber = "FR98765432100011";
        invoice.totalExcludingTax = new BigDecimal("2.84");
        invoice.totalVat = new BigDecimal("0.16");
        invoice.totalIncludingTax = new BigDecimal("3.00");
        return InvoiceDocument.of(invoice, ticket, true, TestMessages.french());
    }

    /**
     * The hand-built page draws every optional section when the document
     * carries one: the DUPLICATA mention (the true arm of {@code isDuplicate}),
     * the party fields (the true arms of the account-number, legal-name,
     * contact, phone, SIRET and VAT-number guards), the article row with its
     * attributes and EAN, the VAT table row and the settlement row (the loop
     * bodies and the non-empty tender arm).
     */
    @Test
    void theHandBuiltPageDrawsEveryOptionalSection() {
        String html = NetworkDocumentPrinter.html(fullyPopulated(), TestMessages.french());
        assertTrue(html.contains("DUPLICATA N°"));
        assertTrue(html.contains("N° compte : CPT-9"));
        assertTrue(html.contains("INTERMARCHE SAS"));
        assertTrue(html.contains("M. Durand"));
        assertTrue(html.contains("Tél. 0102030405"));
        assertTrue(html.contains("SIRET 12345678900011"));
        assertTrue(html.contains("TVA FR12345678900011"));
        assertTrue(html.contains("SIRET 98765432100011"));
        assertTrue(html.contains("Famille : FRUITS"));
        assertTrue(html.contains("3560070139682"));
        assertTrue(html.contains("Règlement"));
        assertTrue(html.contains("Espèces"));
    }

    /**
     * Builds the network printer with a configured URL and a mock HTTP client
     * injected in place of the real one (the field is private and final, so the
     * substitution goes through reflection, the pom opening java.base for it).
     *
     * @param url    the print-service URL, or null for a register with none
     * @param client the mock HTTP client
     * @return the wired printer
     * @throws Exception when the reflection fails
     */
    private NetworkDocumentPrinter networkPrinter(String url, HttpClient client) throws Exception {
        NetworkDocumentPrinter printer = new NetworkDocumentPrinter();
        printer.i18n = TestMessages.french();
        printer.printUrl = url == null ? Optional.empty() : Optional.of(url);
        Field field = NetworkDocumentPrinter.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(printer, client);
        return printer;
    }

    /**
     * Builds a mock HTTP response carrying the given status code.
     *
     * @param status the HTTP status code
     * @return the mock response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    /**
     * With no print service configured, {@code print} reports failure at once —
     * the not-available leg of the guard — and never touches the HTTP client.
     */
    @Test
    void printReportsFailureWithoutAConfiguredService() throws Exception {
        HttpClient client = mock(HttpClient.class);
        NetworkDocumentPrinter printer = networkPrinter(null, client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
        verifyNoInteractions(client);
    }

    /**
     * With a service configured but a null document, {@code print} reports
     * failure — the null-document leg of the guard — and never posts.
     */
    @Test
    void printReportsFailureForANullDocument() throws Exception {
        HttpClient client = mock(HttpClient.class);
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertFalse(printer.print(null, List.of("ligne")));
        verifyNoInteractions(client);
    }

    /**
     * A 2xx response is a successful print: {@code print} posts the page and
     * returns true, the document number and the fallback size travelling as
     * headers (the non-null arms of both header ternaries).
     */
    @Test
    void printPostsThePageAndReturnsTrueOnSuccess() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertTrue(printer.print(fullyPopulated(), List.of("ligne 1", "ligne 2")));
        verify(client).send(any(HttpRequest.class), any());
    }

    /**
     * A response at or above 300 is a refusal: {@code print} logs it and returns
     * false (the {@code statusCode >= 300} leg of the compound status guard).
     */
    @Test
    void printReportsFailureOnAServerError() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(500)).when(client).send(any(HttpRequest.class), any());
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
    }

    /**
     * A response below 200 is likewise a refusal: {@code print} returns false
     * (the {@code statusCode < 200} leg of the compound status guard).
     */
    @Test
    void printReportsFailureOnAnInformationalResponse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(199)).when(client).send(any(HttpRequest.class), any());
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
    }

    /**
     * An unreachable print service (an I/O failure) is caught: {@code print}
     * logs it and returns false — the general exception arm.
     */
    @Test
    void printReportsFailureWhenTheServiceIsUnreachable() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any()))
                .thenThrow(new java.io.IOException("connection refused"));
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
    }

    /**
     * An interrupted send is caught apart: {@code print} returns false AND
     * restores the thread's interrupt flag — the interrupted-exception arm. The
     * flag is cleared afterwards so the test leaves no state behind.
     */
    @Test
    void printReportsFailureAndRestoresTheInterruptFlagWhenInterrupted() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any())).thenThrow(new InterruptedException());
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
        assertTrue(Thread.interrupted());
    }

    /**
     * A document with no number and a null fallback list still posts: the number
     * header falls back to the empty string and the length header to zero (the
     * null arms of both header ternaries), and a 2xx response returns true.
     */
    @Test
    void printHandlesANullNumberAndNullFallbackLines() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        NetworkDocumentPrinter printer = networkPrinter("http://print.local/a4", client);
        Invoice invoice = new Invoice();
        invoice.documentNumber = null;
        invoice.issueDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        invoice.customerName = "SARL DUPONT";
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-000123";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        InvoiceDocument document = InvoiceDocument.of(invoice, ticket, false, TestMessages.french());
        assertTrue(printer.print(document, null));
    }

    /**
     * A configured but blank URL is no service: {@code isAvailable} takes the
     * present-yet-blank leg of its guard, so {@code print} reports failure and
     * never posts.
     */
    @Test
    void printReportsFailureWhenTheUrlIsBlank() throws Exception {
        HttpClient client = mock(HttpClient.class);
        NetworkDocumentPrinter printer = networkPrinter("   ", client);
        assertFalse(printer.print(fullyPopulated(), List.of("ligne")));
        verifyNoInteractions(client);
    }

    /**
     * An article row carrying neither attributes nor an EAN draws neither line:
     * with a line present but its family unset and the EAN suppressed, the
     * article loop runs and takes the empty-attributes and empty-EAN false arms
     * that the row-less documents never reach.
     */
    @Test
    void theHandBuiltPageOmitsAttributesAndEanWhenTheLineHasNone() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-000123";
        ticket.terminalId = "C04";
        ticket.creationDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        TicketLine line = new TicketLine();
        line.productLabel = "POMMES";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("1.50");
        line.totalPrice = new BigDecimal("1.50");
        line.vatRate = new BigDecimal("0.0550");
        ticket.lines = new ArrayList<>(List.of(line));
        Invoice invoice = new Invoice();
        invoice.documentNumber = "C04-F000042";
        invoice.issueDate = LocalDateTime.of(2026, 9, 15, 11, 24);
        invoice.customerName = "SARL DUPONT";
        String html = NetworkDocumentPrinter.html(InvoiceDocument.of(invoice, ticket, false, TestMessages.french()), TestMessages.french());
        assertTrue(html.contains("POMMES"));
        assertFalse(html.contains("class=\"attr\""));
    }
}
