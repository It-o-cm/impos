package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.AccountCustomer;
import com.intermarche.pos.domain.Address;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.DocumentType;
import com.intermarche.pos.domain.ticket.Invoice;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link InvoiceDocument}.
 * <p>
 * The class is a pure layout function over entities that are built by hand here — no
 * CDI, no database, no Panache call — which is exactly why the whole document can be
 * asserted. Every guard is exercised on both arms and every leg of the compound ones:
 * the three legs of the legal footer, the three of an address, the two of a town
 * line, the three quantity shapes and the cancelled-line exclusion.
 */
class InvoiceDocumentTest {

    /**
     * Builds a ticket line.
     *
     * @param label    the article label
     * @param ean      the article code, null when it has none
     * @param quantity the quantity
     * @param unit     the tax-included unit price
     * @param total    the tax-included line total
     * @param rate     the VAT rate as a fraction, null for none
     * @param plu      the PLU, non-null for a weighed article
     * @param family   the family label, null when the line carries none
     * @return the line
     */
    private TicketLine line(String label, String ean, String quantity, String unit, String total,
            String rate, String plu, String family) {
        TicketLine line = new TicketLine();
        line.productLabel = label;
        line.ean = ean;
        line.plu = plu;
        line.familyLabel = family;
        line.quantity = new BigDecimal(quantity);
        line.unitPrice = new BigDecimal(unit);
        line.totalPrice = new BigDecimal(total);
        line.vatRate = rate == null ? null : new BigDecimal(rate);
        return line;
    }

    /**
     * Builds a store carrying everything a document can print of a seller.
     *
     * @return the store
     */
    private Store fullStore() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.legalName = "SA JANSELIN";
        store.rcs = "342 490 562";
        store.shareCapital = new BigDecimal("40000");
        store.siret = "SIRET1";
        store.vatNumber = "FR1";
        store.phone = "0102030405";
        store.fax = "0102030406";
        store.address = new Address();
        store.address.streetLine1 = "12 rue A";
        store.address.streetLine2 = "Bat B";
        store.address.postalCode = "92420";
        store.address.city = "VAUCRESSON";
        return store;
    }

    /**
     * Builds a customer carrying everything a document can print of an addressee.
     *
     * @return the customer
     */
    private AccountCustomer fullCustomer() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "C04-CLI000042";
        customer.companyName = "BOULANGERIE";
        customer.firstName = "Marc";
        customer.lastName = "VIDAL";
        customer.siret = "SIRET2";
        customer.vatNumber = "FR2";
        customer.address = new Address();
        customer.address.streetLine1 = "3 place";
        customer.address.postalCode = "92420";
        customer.address.city = "VAUCRESSON";
        return customer;
    }

    /**
     * Builds a document over a ticket and a customer.
     *
     * @param ticket   the ticket
     * @param customer the addressee
     * @param number   the document number
     * @return the transient invoice
     */
    private Invoice invoice(Ticket ticket, AccountCustomer customer, String number) {
        Invoice document = new Invoice();
        document.documentNumber = number;
        document.documentType = DocumentType.FACTURE;
        document.terminalId = "C04";
        document.issueDate = LocalDateTime.of(2026, 9, 9, 16, 42);
        document.ticket = ticket;
        document.ticketNumber = ticket.ticketNumber;
        document.addressTo(customer);
        document.totalExcludingTax = new BigDecimal("50.79");
        document.totalVat = new BigDecimal("5.81");
        document.totalIncludingTax = new BigDecimal("56.60");
        return document;
    }

    /**
     * A ticket with one line of each shape, one cancelled line and two tenders lays
     * out completely: the cancelled line is gone, the quantities take their three
     * shapes and the tenders are grouped in the order they first appear.
     */
    @Test
    void laysOutEveryShapeAndDropsTheCancelledLine() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "C04-00000417";
        ticket.creationDate = LocalDateTime.of(2026, 9, 9, 16, 12);
        ticket.lines.add(line("A", "3178530403022", "2", "12.90", "25.80", "0.055", null, "EPICERIE"));
        ticket.lines.add(line("B", null, "1.50", "2.00", "3.00", "0.20", null, null));
        ticket.lines.add(line("C", null, "1.480", "3.95", "5.85", "0.055", "0100", "FL"));
        TicketLine cancelled = line("ANNULE", null, "1", "9.99", "9.99", "0.20", null, null);
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.payments.add(new CardPayment(new BigDecimal("40.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("16.60"), new BigDecimal("20.00")));

        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "C04-F000123"),
                ticket, true);

        assertEquals(3, document.lines.size());
        assertEquals("A", document.lines.get(0).label());
        assertEquals("2", document.lines.get(0).quantity());
        assertEquals("12,23", document.lines.get(0).unitExcludingTax());
        assertEquals("12,90", document.lines.get(0).unitIncludingTax());
        assertEquals("25,80", document.lines.get(0).totalIncludingTax());
        assertEquals("5,50 %", document.lines.get(0).vatRate());
        assertEquals("Famille : EPICERIE", document.lines.get(0).attributes());
        assertEquals("3178530403022", document.lines.get(0).ean());
        assertEquals("1,50", document.lines.get(1).quantity());
        assertEquals("", document.lines.get(1).attributes());
        assertEquals("", document.lines.get(1).ean());
        assertEquals("1,480 kg", document.lines.get(2).quantity());
        assertEquals(2, document.vatRows.size());
        assertEquals("5,50 %", document.vatRows.get(0).rate());
        assertEquals(2, document.tenders.size());
        assertEquals("Carte bancaire", document.tenders.get(0).label());
        assertEquals("40,00", document.tenders.get(0).amount());
        assertEquals("Espèces", document.tenders.get(1).label());
        assertEquals("Carte bancaire, Espèces", document.paymentMethods);
        assertEquals("SA JANSELIN — RCS 342 490 562 — Capital : 40 000,00 €", document.legalFooter);
        assertEquals("09/09/2026", document.saleDate);
        assertEquals("09/09/2026 16:42", document.issueDate);
        assertEquals("BOULANGERIE", document.customer.name());
        assertEquals("Marc VIDAL", document.customer.contact());
        assertFalse(document.isDuplicate());
    }

    /**
     * The article code is withheld when the back office says not to print it, the
     * false arm of the {@code showEan} guard.
     */
    @Test
    void withholdsTheArticleCodeWhenItIsNotPrinted() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.lines.add(line("A", "3178530403022", "1", "1.00", "1.00", "0.20", null, null));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, false);
        assertEquals("", document.lines.get(0).ean());
    }

    /**
     * The same tender used twice is summed on one row rather than repeated.
     */
    @Test
    void sumsATenderUsedTwice() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.payments.add(new CashPayment(new BigDecimal("10.00"), new BigDecimal("10.00")));
        ticket.payments.add(new CashPayment(new BigDecimal("5.50"), new BigDecimal("5.50")));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true);
        assertEquals(1, document.tenders.size());
        assertEquals("15,50", document.tenders.get(0).amount());
    }

    /**
     * A line with no VAT rate at all is treated as zero-rated rather than refused,
     * the null leg of the rate guard.
     */
    @Test
    void treatsAMissingRateAsZero() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.lines.add(line("A", null, "1", "10.00", "10.00", null, null, null));
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true);
        assertEquals("0,00 %", document.lines.get(0).vatRate());
        assertEquals("10,00", document.lines.get(0).unitExcludingTax());
    }

    /**
     * A store with no legal name, no register entry and no capital prints an empty
     * footer — all three legs false — and a bare address prints one line.
     */
    @Test
    void printsAnEmptyFooterAndABareAddressWhenTheStoreDeclaresNothing() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.address = new Address();
        store.address.city = "VAUCRESSON";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true);
        assertEquals("", document.legalFooter);
        assertEquals(1, document.seller.addressLines().size());
        assertEquals("VAUCRESSON", document.seller.addressLines().get(0));
        assertEquals("", document.seller.phone());
        assertEquals("", document.seller.legalName());
        assertTrue(document.tenders.isEmpty());
        assertEquals("", document.paymentMethods);
    }

    /**
     * Each leg of the legal footer stands alone: a store declaring only its capital
     * prints only that.
     */
    @Test
    void printsTheCapitalAloneWhenItIsTheOnlyLegalFieldDeclared() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.shareCapital = new BigDecimal("1");
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true);
        assertEquals("Capital : 1,00 €", document.legalFooter);
        assertTrue(document.seller.addressLines().isEmpty());
    }

    /**
     * A store declaring only its register entry prints only that, the middle leg.
     */
    @Test
    void printsTheRegisterEntryAloneWhenItIsTheOnlyLegalFieldDeclared() {
        Store store = new Store();
        store.name = "MAGASIN";
        store.rcs = "123";
        Ticket ticket = new Ticket();
        ticket.store = store;
        ticket.ticketNumber = "T";
        InvoiceDocument document = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"),
                ticket, true);
        assertEquals("RCS 123", document.legalFooter);
    }

    /**
     * A town without a postal code prints the town alone, and a postal code without a
     * town prints the code alone — the two legs of the town line.
     */
    @Test
    void printsEitherHalfOfTheTownLineAlone() {
        Store townOnly = new Store();
        townOnly.name = "M";
        townOnly.address = new Address();
        townOnly.address.city = "VAUCRESSON";
        Ticket first = new Ticket();
        first.store = townOnly;
        first.ticketNumber = "T";
        assertEquals("VAUCRESSON", InvoiceDocument.of(invoice(first, fullCustomer(), "N"), first, true)
                .seller.addressLines().get(0));

        Store codeOnly = new Store();
        codeOnly.name = "M";
        codeOnly.address = new Address();
        codeOnly.address.postalCode = "92420";
        Ticket second = new Ticket();
        second.store = codeOnly;
        second.ticketNumber = "T";
        assertEquals("92420", InvoiceDocument.of(invoice(second, fullCustomer(), "N"), second, true)
                .seller.addressLines().get(0));
    }

    /**
     * A customer with no address at all yields no address lines, the null leg of the
     * address guard — a document is still issued.
     */
    @Test
    void survivesACustomerWithNoAddress() {
        AccountCustomer customer = new AccountCustomer();
        customer.accountNumber = "AC";
        customer.companyName = "CO";
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        Invoice document = invoice(ticket, customer, "N");
        document.customerAddress = null;
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, true);
        assertTrue(laid.customer.addressLines().isEmpty());
        assertEquals("", laid.customer.contact());
        assertEquals("", laid.customer.siret());
    }

    /**
     * A printed document reports itself as a duplicate and carries its rank.
     */
    @Test
    void reportsItselfAsADuplicateOnceItHasBeenPrinted() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        Invoice document = invoice(ticket, fullCustomer(), "N");
        document.printCount = 2;
        InvoiceDocument laid = InvoiceDocument.of(document, ticket, true);
        assertTrue(laid.isDuplicate());
        assertEquals(2, laid.duplicateNumber);
    }

    /**
     * A ticket with no creation date states no sale date rather than failing, the
     * null leg of that guard.
     */
    @Test
    void statesNoSaleDateWhenTheTicketHasNone() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        ticket.creationDate = null;
        assertEquals("", InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"), ticket, true)
                .saleDate);
    }

    /**
     * The mentions a professional invoice must carry are on the document, and they
     * name the two articles they come from.
     */
    @Test
    void carriesTheLatePaymentMentions() {
        Ticket ticket = new Ticket();
        ticket.store = fullStore();
        ticket.ticketNumber = "T";
        String mentions = InvoiceDocument.of(invoice(ticket, fullCustomer(), "N"), ticket, true)
                .getLegalMentions();
        assertTrue(mentions.contains("L441-10"));
        assertTrue(mentions.contains("40 €"));
    }
}
