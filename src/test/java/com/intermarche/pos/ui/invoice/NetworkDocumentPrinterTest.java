package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

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

    /**
     * Builds the A4 printer with a real renderer wired.
     *
     * @return the printer
     */
    private NetworkDocumentPrinter withRenderer() {
        NetworkDocumentPrinter printer = new NetworkDocumentPrinter();
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
        return InvoiceDocument.of(invoice, ticket, false);
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
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.INVOICE_A4))
                    .thenReturn(List.of(layout(
                            "<h1>{document.title} {document.number}</h1>"
                                    + "<p>{seller.name}</p><p>{customer.name}</p>")));
            assertEquals("<h1>FACTURE C04-F000042</h1><p>MAGASIN</p><p>SARL DUPONT</p>",
                    printer.page(document));
        }
    }

    /**
     * A shop that administers no A4 layout keeps the page the class builds
     * itself, unchanged.
     */
    @Test
    void anUnadministeredA4KeepsTheHandBuiltPage() {
        NetworkDocumentPrinter printer = withRenderer();
        InvoiceDocument document = laidOut();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> DocumentTemplate.list(
                            "active = true and documentType = ?1 order by priority, code",
                            DocumentTemplate.DocumentType.INVOICE_A4))
                    .thenReturn(List.of());
            assertEquals(NetworkDocumentPrinter.html(document), printer.page(document));
        }
    }

    /**
     * A printer built with no renderer at all keeps the hand-built page — the
     * null leg of the layout guard.
     */
    @Test
    void aPrinterWithoutARendererKeepsTheHandBuiltPage() {
        NetworkDocumentPrinter printer = new NetworkDocumentPrinter();
        InvoiceDocument document = laidOut();
        String page = printer.page(document);
        assertEquals(NetworkDocumentPrinter.html(document), page);
        assertTrue(page.startsWith("<!DOCTYPE html>"));
    }

    /**
     * The A4 layout reads the SAME description as the roll layout: one document
     * restated twice, never described twice.
     */
    @Test
    void bothInvoiceLayoutsReadTheSameDescription() {
        NetworkDocumentPrinter printer = withRenderer();
        InvoiceDocument document = laidOut();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
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
    }
}
