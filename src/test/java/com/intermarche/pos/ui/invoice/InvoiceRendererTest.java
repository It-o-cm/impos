package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.DocumentType;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link InvoiceRenderer}, the degraded rendering of a document onto a
 * 42-column roll.
 * <p>
 * Two documents carry the whole branch set: one declaring everything a seller and an
 * addressee can declare, one declaring the strict minimum. Every optional block is
 * therefore exercised present and absent, and the width invariant — no line ever
 * wider than the paper — is asserted on both.
 */
class InvoiceRendererTest {

    /**
     * Builds a ticket line.
     *
     * @param label    the article label
     * @param ean      the article code, null when it has none
     * @param quantity the quantity
     * @param unit     the tax-included unit price
     * @param total    the tax-included line total
     * @param rate     the VAT rate as a fraction
     * @param family   the family label, null when the line carries none
     * @return the line
     */
    private TicketLine line(String label, String ean, String quantity, String unit, String total,
            String rate, String family) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.ean = ean;
        line.familyLabel = family;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = new BigDecimal(unit);
        line.totalPrice = new BigDecimal(total);
        line.vatRate = new BigDecimal(rate);
        return line;
    }

    /**
     * Builds a document over a ticket and a customer.
     *
     * @param ticket     the ticket
     * @param customer   the addressee
     * @param printCount how many times it has been printed already
     * @return the transient invoice
     */
    private Invoice invoice(Ticket ticket, AccountCustomer customer, int printCount) {
        Invoice document = new Invoice();
        document.documentNumber = "C04-F000123";
        document.documentType = DocumentType.FACTURE;
        document.terminalId = "C04";
        document.issueDate = LocalDateTime.of(2026, 9, 9, 16, 42);
        document.ticket = ticket;
        document.ticketNumber = ticket.ticketNumber;
        document.addressTo(customer);
        document.printCount = printCount;
        document.totalExcludingTax = new BigDecimal("50.79");
        document.totalVat = new BigDecimal("5.81");
        document.totalIncludingTax = new BigDecimal("56.60");
        return document;
    }

    /**
     * A ticket whose seller, addressee and lines declare everything.
     *
     * @param printCount how many times the document has been printed already
     * @return the laid-out document
     */
    private InvoiceDocument full(int printCount) {
        Store store = new Store();
        store.name = "MAGASIN";
        store.legalName = "SA JANSELIN";
        store.siret = "SIRET1";
        store.vatNumber = "FR1";
        store.phone = "0102030405";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        store.address.postalCode = "92420";
        store.address.city = "VAUCRESSON";

        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "C04-CLI000042";
        customer.companyName = "BOULANGERIE DU PARC SARL";
        customer.firstName = "Marc";
        customer.lastName = "VIDAL";
        customer.siret = "SIRET2";
        customer.vatNumber = "FR2";
        customer.address = new Address();
        customer.address.streetLine1 = "3 place";
        customer.address.postalCode = "92420";
        customer.address.city = "VAUCRESSON";

        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "C04-00000417";
        ticket.creationDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        ticket.lines.add(line("CAFE", "3178530403022", "2", "12.90", "25.80", "0.055", "EPICERIE"));
        ticket.payments.add(new CardPayment(new BigDecimal("56.60")));
        return InvoiceDocument.of(invoice(ticket, customer, printCount), ticket, true);
    }

    /**
     * A ticket whose seller and addressee declare the strict minimum, with no tender.
     *
     * @return the laid-out document
     */
    private InvoiceDocument bare() {
        Store store = new Store();
        store.name = "MAGASIN";

        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "";
        customer.companyName = "CLIENT";

        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        ticket.lines.add(line("ARTICLE", null, "1", "1.00", "1.00", "0.20", null));
        return InvoiceDocument.of(invoice(ticket, customer, 0), ticket, false);
    }

    /**
     * Tells whether any line of the document carries the given text.
     *
     * @param lines the rendered lines
     * @param text  what to look for
     * @return true when one line contains it
     */
    private boolean carries(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    /**
     * The full document states every optional block, and no line is wider than the
     * paper.
     */
    @Test
    void statesEveryBlockOfAFullDocument() {
        List<String> lines = InvoiceRenderer.render(full(0));
        assertTrue(carries(lines, "MAGASIN"));
        assertTrue(carries(lines, "SA JANSELIN"));
        assertTrue(carries(lines, "Tel. 0102030405"));
        assertTrue(carries(lines, "SIRET SIRET1"));
        assertTrue(carries(lines, "TVA FR1"));
        assertTrue(carries(lines, "FACTURE"));
        assertTrue(carries(lines, "C04-F000123"));
        assertTrue(carries(lines, "C04-00000417"));
        assertTrue(carries(lines, "N° compte : C04-CLI000042"));
        assertTrue(carries(lines, "BOULANGERIE DU PARC SARL"));
        assertTrue(carries(lines, "Marc VIDAL"));
        assertTrue(carries(lines, "SIRET SIRET2"));
        assertTrue(carries(lines, "TVA FR2"));
        assertTrue(carries(lines, "Famille : EPICERIE"));
        assertTrue(carries(lines, "3178530403022"));
        assertTrue(carries(lines, "2 x 12,90 (HT 12,23)"));
        assertTrue(carries(lines, "TOTAL HT"));
        assertTrue(carries(lines, "TOTAL TVA"));
        assertTrue(carries(lines, "TOTAL TTC"));
        assertTrue(carries(lines, "REGLEMENT"));
        assertTrue(carries(lines, "Carte bancaire"));
        assertFalse(carries(lines, "DUPLICATA"));
        for (String rendered : lines) {
            assertTrue(rendered.length() <= InvoiceRenderer.WIDTH,
                    "ligne trop large : " + rendered);
        }
    }

    /**
     * The bare document states none of the optional blocks — every guard on its false
     * arm — and stays within the paper too.
     */
    @Test
    void omitsEveryOptionalBlockOfABareDocument() {
        List<String> lines = InvoiceRenderer.render(bare());
        assertFalse(carries(lines, "SA JANSELIN"));
        assertFalse(carries(lines, "Tel."));
        assertFalse(carries(lines, "SIRET"));
        assertFalse(carries(lines, "N° compte"));
        assertFalse(carries(lines, "Famille"));
        assertFalse(carries(lines, "3178530403022"));
        assertFalse(carries(lines, "REGLEMENT"));
        assertTrue(carries(lines, "CLIENT"));
        for (String rendered : lines) {
            assertTrue(rendered.length() <= InvoiceRenderer.WIDTH,
                    "ligne trop large : " + rendered);
        }
    }

    /**
     * A reprinted document carries its duplicate rank under the title.
     */
    @Test
    void marksADuplicateUnderTheTitle() {
        List<String> lines = InvoiceRenderer.render(full(2));
        assertTrue(carries(lines, "*** DUPLICATA N°2 ***"));
    }

    /**
     * A label longer than the paper is cut rather than wrapped, and a value that
     * leaves no room still gets one separating space — the two clamped legs.
     */
    @Test
    void cutsWhatIsTooWideAndAlwaysSeparates() {
        Store store = new Store();
        store.name = "MAGASIN DONT LE NOM DEPASSE LARGEMENT LA LARGEUR DU PAPIER DE CAISSE";
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "";
        customer.companyName = "CLIENT DONT LA RAISON SOCIALE DEPASSE AUSSI LA LARGEUR DU ROULEAU";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        ticket.lines.add(line("ARTICLE AU LIBELLE INTERMINABLE QUI NE TIENDRA JAMAIS SUR LA LIGNE",
                null, "1", "1.00", "1.00", "0.20", null));
        List<String> lines = InvoiceRenderer.render(
                InvoiceDocument.of(invoice(ticket, customer, 0), ticket, false));
        for (String rendered : lines) {
            assertTrue(rendered.length() <= InvoiceRenderer.WIDTH,
                    "ligne trop large : " + rendered);
        }
        assertTrue(carries(lines, "ARTICLE AU LIBELLE INTERMINABLE"));
    }

    /**
     * The renderer formats nothing of its own: every figure it prints is the string
     * the document handed it.
     */
    @Test
    void printsTheDocumentsOwnFigures() {
        InvoiceDocument document = full(0);
        List<String> lines = InvoiceRenderer.render(document);
        assertTrue(carries(lines, document.totalExcludingTax + " E"));
        assertTrue(carries(lines, document.totalVat + " E"));
        assertTrue(carries(lines, document.totalIncludingTax + " E"));
        assertTrue(carries(lines, document.vatRows.get(0).rate()));
        assertTrue(carries(lines, document.tenders.get(0).amount() + " E"));
    }

    // --------------------------------------------------
    // Cutting the document into sheets (LC-08-04-12/13)
    // --------------------------------------------------

    /**
     * A document shorter than one sheet is one sheet, and its lines are untouched.
     */
    @Test
    void aShortDocumentIsOneSheet() {
        List<List<String>> pages = InvoiceRenderer.paginate(List.of("a", "b", "c"), 10);
        assertEquals(1, pages.size());
        assertEquals(List.of("a", "b", "c"), pages.get(0));
    }

    /**
     * A document exactly as long as one sheet is still ONE sheet: the boundary belongs
     * to the sheet before it, and an operator asked for a second, empty sheet would be
     * right to think the register had lost count.
     */
    @Test
    void aDocumentThatExactlyFillsASheetIsOneSheet() {
        List<List<String>> pages = InvoiceRenderer.paginate(List.of("a", "b", "c"), 3);
        assertEquals(1, pages.size());
    }

    /**
     * A longer document is cut in order, the last sheet carrying the remainder.
     */
    @Test
    void aLongDocumentIsCutInOrder() {
        List<List<String>> pages =
                InvoiceRenderer.paginate(List.of("a", "b", "c", "d", "e"), 2);
        assertEquals(3, pages.size());
        assertEquals(List.of("a", "b"), pages.get(0));
        assertEquals(List.of("c", "d"), pages.get(1));
        assertEquals(List.of("e"), pages.get(2));
    }

    /**
     * A capacity nobody administered prints the document whole — the zero leg of the
     * guard, and the shape an unconfigured slip station has anyway.
     */
    @Test
    void anUnadministeredCapacityPrintsTheDocumentWhole() {
        List<List<String>> pages = InvoiceRenderer.paginate(List.of("a", "b", "c"), 0);
        assertEquals(1, pages.size());
        assertEquals(3, pages.get(0).size());
    }

    /**
     * A negative capacity does the same — the other side of that guard, which an
     * integer parameter mistyped with a minus produces.
     */
    @Test
    void aNegativeCapacityPrintsTheDocumentWhole() {
        assertEquals(1, InvoiceRenderer.paginate(List.of("a", "b", "c"), -5).size());
    }

    /**
     * Nothing at all is still ONE sheet: a caller telling the operator "0 feuille(s)"
     * would be asking for a gesture that cannot be made.
     */
    @Test
    void anEmptyDocumentIsStillOneSheet() {
        assertEquals(1, InvoiceRenderer.paginate(List.of(), 10).size());
        assertEquals(1, InvoiceRenderer.paginate(null, 10).size());
    }

    /**
     * The sheets are copies: printing one must not be able to change the document it
     * came from, nor the next sheet.
     */
    @Test
    void theSheetsAreCopies() {
        List<String> source = new java.util.ArrayList<>(List.of("a", "b", "c", "d"));
        List<List<String>> pages = InvoiceRenderer.paginate(source, 2);
        pages.get(0).set(0, "CHANGED");
        assertEquals("a", source.get(0));
    }
}
