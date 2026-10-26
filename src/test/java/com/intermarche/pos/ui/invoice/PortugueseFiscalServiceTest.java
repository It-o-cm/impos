package com.intermarche.pos.ui.invoice;

import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.domain.payment.CardPayment;
import com.intermarche.pos.domain.payment.CashPayment;
import com.intermarche.pos.domain.payment.SocialCardPayment;
import com.intermarche.pos.domain.sale.FiscalDocument;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.setting.DocumentTemplate;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PortugueseFiscalService} (LC-08-01-05, LC-08-01-06, LC-08-04-18
 * to -23, LC-05-07-03).
 * <p>
 * The documents are built for real — {@link FiscalDocument} is constructed with its
 * real methods and only its {@code persist} neutralized — and signed with a key the
 * test generates, so every signature is verified against its public half and every
 * QR payload asserted in full.
 */
class PortugueseFiscalServiceTest {

    /** The sale every test closes. */
    private static final Long TICKET_ID = 7L;

    /** The query of the active documents of a sale. */
    private static final String ACTIVE =
            "ticketId = ?1 and cancelled = false and documentType in ?2 order by part";

    /** The query of the last document of a series. */
    private static final String LAST = "documentType = ?1 and series = ?2 order by rank desc";

    /** The query of a document named by its day, register and rank. */
    private static final String ISSUED =
            "documentType = ?1 and terminalId = ?2 and rank = ?3 and issuedAt >= ?4 and issuedAt < ?5";

    /** The key pair the documents are signed with. */
    private final KeyPair keys = keyPair();

    /**
     * Generates the signing key pair of the test.
     *
     * @return a 1024-bit RSA key pair
     */
    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds the service, enabled or not, closing ticket 7 at register C04.
     *
     * @param enabled whether the store issues the Portuguese documents
     * @return the service
     */
    private PortugueseFiscalService service(boolean enabled) {
        PortugueseFiscalService service = new PortugueseFiscalService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.ptDocuments()).thenReturn(enabled);
        when(service.posSettingsService.atcudCode(anyString())).thenAnswer(i -> "CODE" + i.getArgument(0));
        when(service.posSettingsService.fiscalCertificateNumber()).thenReturn("2468/AT");
        service.ticketNumberService = mock(TicketNumberService.class);
        service.syncOutboxService = mock(com.intermarche.pos.service.sync.register.SyncOutboxService.class);
        AtomicInteger rank = new AtomicInteger();
        when(service.ticketNumberService.nextDocumentNumber(any(DocumentTemplate.DocumentType.class),
                anyString(), anyInt())).thenAnswer(i -> i.getArgument(0).toString() + " C04/"
                + i.getArgument(2) + "/0000000" + rank.incrementAndGet());
        service.state = new PosState();
        service.state.payment.ticketDbId = TICKET_ID;
        service.terminalId = "C04";
        service.pdvNumber = Optional.of("PDV1");
        service.privateKey = Optional.of(Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        return service;
    }

    /**
     * Opens the construction mock that keeps the documents real but never persists them.
     *
     * @return the construction mock, to close through try-with-resources
     */
    private static MockedConstruction<FiscalDocument> documents() {
        return Mockito.mockConstruction(FiscalDocument.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS),
                (document, context) -> Mockito.doNothing().when(document).persist());
    }

    /**
     * Opens the static mock answering the store, PT500100200, and nothing else.
     *
     * @return the static mock, to close through try-with-resources
     */
    private static MockedStatic<PanacheEntityBase> panache() {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        Store store = new Store();
        store.vatNumber = "PT500100200";
        panache.when(PanacheEntityBase::listAll).thenReturn(List.of(store));
        return panache;
    }

    /**
     * Builds a sale line.
     *
     * @param uid its uid
     * @param total its total
     * @param rate its VAT rate
     * @param eligible whether the social card may pay it
     * @return the line
     */
    private static TicketLine line(String uid, String total, String rate, boolean eligible) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.productLabel = uid;
        line.totalPrice = new BigDecimal(total);
        line.vatRate = new BigDecimal(rate);
        line.restrictedTenders = eligible ? ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE : null;
        return line;
    }

    /**
     * Builds sale 7, its lines and payments.
     *
     * @param total the total
     * @param vat the VAT
     * @param lines the lines
     * @param payments the payments
     * @return the sale
     */
    private static Ticket ticket(String total, String vat, List<TicketLine> lines,
            List<com.intermarche.pos.domain.payment.TicketPayment> payments) {
        Ticket ticket = new Ticket();
        ticket.id = TICKET_ID;
        ticket.totalIncludingTax = new BigDecimal(total);
        ticket.totalVat = new BigDecimal(vat);
        ticket.lines = new ArrayList<>(lines);
        ticket.payments = new ArrayList<>(payments);
        return ticket;
    }

    /**
     * Asserts a document's signature is the signature of its chained data under
     * the test key.
     *
     * @param document the sealed document
     */
    private void assertSigned(FiscalDocument document) {
        try {
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(keys.getPublic());
            verifier.update(String.join(";", document.issuedAt.toLocalDate().toString(),
                    document.issuedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
                    document.number, document.grossTotal.setScale(2).toPlainString(),
                    document.previousHash).getBytes(StandardCharsets.UTF_8));
            assertTrue(verifier.verify(Base64.getDecoder().decode(document.hash)));
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * The QR payload a document must carry, its variable parts read off it.
     *
     * @param document the sealed document
     * @param customer the customer NIF the payload states
     * @param taxes the rate-class fields, from I1
     * @return the expected payload
     */
    private static String qr(FiscalDocument document, String customer, String taxes) {
        return "A:500100200*B:" + customer + "*C:PT*D:" + document.documentType + "*E:N*F:"
                + document.issuedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "*G:" + document.number + "*H:" + document.atcud + "*" + taxes
                + "*N:" + document.taxTotal.setScale(2).toPlainString()
                + "*O:" + document.grossTotal.setScale(2).toPlainString()
                + "*Q:" + document.hashCharacters() + "*R:2468";
    }

    /**
     * A register that issues no Portuguese document states nothing, and reads nothing.
     */
    @Test
    void issueSaleDocumentsDoesNothingWhenDisabled() {
        PortugueseFiscalService service = service(false);
        assertEquals(List.of(), service.issueSaleDocuments(TICKET_ID));
        verifyNoInteractions(service.ticketNumberService);
        assertFalse(service.enabled());
    }

    /**
     * Training, a missing sale and a sale other than the one being closed are
     * never stated.
     */
    @Test
    void issueSaleDocumentsStatesOnlyTheSaleBeingClosed() {
        PortugueseFiscalService service = service(true);
        assertEquals(List.of(), service.issueSaleDocuments(null));
        assertEquals(List.of(), service.issueSaleDocuments(8L));
        service.state.trainingMode = true;
        assertEquals(List.of(), service.issueSaleDocuments(TICKET_ID));
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * A sale already stated answers its documents, without issuing again.
     */
    @Test
    void issueSaleDocumentsIsIdempotent() {
        PortugueseFiscalService service = service(true);
        FiscalDocument issued = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            panache.when(() -> PanacheEntityBase.list(ACTIVE, TICKET_ID,
                    List.of(FiscalDocument.Type.FS, FiscalDocument.Type.FT))).thenReturn(List.of(issued));
            assertEquals(List.of(issued), service.issueSaleDocuments(TICKET_ID));
        }
        verifyNoInteractions(service.ticketNumberService, service.syncOutboxService);
    }

    /**
     * A sale that cannot be read, a negative sale and a sale not yet settled are
     * not stated.
     */
    @Test
    void issueSaleDocumentsWaitsForASettledSale() {
        PortugueseFiscalService service = service(true);
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            assertEquals(List.of(), service.issueSaleDocuments(TICKET_ID));
            Ticket negative = ticket("-5.00", "0.00", List.of(), List.of());
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(negative);
            assertEquals(List.of(), service.issueSaleDocuments(TICKET_ID));
            Ticket unpaid = ticket("10.00", "1.87", List.of(),
                    List.of(new CashPayment(new BigDecimal("9.99"), new BigDecimal("9.99"))));
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(unpaid);
            assertEquals(List.of(), service.issueSaleDocuments(TICKET_ID));
        }
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * BO-10-02-18: the per-rate figures of the simplified invoice are cut by the
     * administered rounding rule, like the ticket it states — 8,19 at 23 % is 6,65
     * excluding tax rounded down where half up gives 6,66.
     */
    @Test
    void issueSaleDocumentsCutsTheRatesWithTheAdministeredRounding() {
        PortugueseFiscalService service = service(true);
        Mockito.when(service.posSettingsService.calcRoundingMode()).thenReturn(java.math.RoundingMode.DOWN);
        Ticket ticket = ticket("8.19", "1.54", List.of(line("L1", "8.19", "0.23", false)),
                List.of(new CashPayment(new BigDecimal("8.19"), new BigDecimal("10.00"))));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            panache.when(() -> PanacheEntityBase.list(LAST, FiscalDocument.Type.FS, "C04/"
                    + LocalDate.now().getYear())).thenReturn(List.of());
            FiscalDocument document = service.issueSaleDocuments(TICKET_ID).get(0);
            assertTrue(document.qrPayload.contains("*I7:6.65*I8:1.54*"), document.qrPayload);
        }
    }

    /**
     * LC-08-01-05 / LC-08-04-23: a settled sale is stated on ONE simplified
     * invoice carrying the sale's NIF, numbered in its series, with its ATCUD,
     * chained to the previous document of the series, signed, and its QR payload
     * stating every rate class; a cancelled line is left out, and the ticket is
     * closed sale is queued again, once, so the store node receives its
     * documents (BO-05-01-05, BO-11-04-02).
     */
    @Test
    void issueSaleDocumentsStatesTheSaleOnASimplifiedInvoice() {
        PortugueseFiscalService service = service(true);
        TicketLine cancelled = line("L9", "99.00", "0.23", false);
        cancelled.cancelled = true;
        Ticket ticket = ticket("40.00", "4.17", List.of(line("L1", "10.00", "0", false),
                line("L2", "10.60", "0.06", false), line("L3", "11.30", "0.13", false),
                line("L4", "8.10", "0.23", false), cancelled),
                List.of(new CashPayment(new BigDecimal("40.00"), new BigDecimal("50.00"))));
        ticket.customerTaxId = "PT123456789";
        ticket.status = Ticket.TicketStatus.CLOSED;
        FiscalDocument previous = new FiscalDocument();
        previous.hash = "PREVIOUS";
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            panache.when(() -> PanacheEntityBase.list(LAST, FiscalDocument.Type.FS, "C04/"
                    + LocalDate.now().getYear())).thenReturn(List.of(previous));
            List<FiscalDocument> documents = service.issueSaleDocuments(TICKET_ID);
            assertEquals(1, documents.size());
            FiscalDocument document = documents.get(0);
            assertSame(built.constructed().get(0), document);
            assertEquals(FiscalDocument.Type.FS, document.documentType);
            assertEquals("FS C04/" + LocalDate.now().getYear() + "/00000001", document.number);
            assertEquals("C04/" + LocalDate.now().getYear(), document.series);
            assertEquals(1L, document.rank);
            assertEquals("CODEFS-1", document.atcud);
            assertEquals("C04", document.terminalId);
            assertEquals(TICKET_ID, document.ticketId);
            assertEquals(0, document.part);
            assertNull(document.lineUids);
            assertNull(document.paymentShares);
            assertEquals("PT123456789", document.customerTaxId);
            assertNull(document.customerName);
            assertEquals(new BigDecimal("40.00"), document.grossTotal);
            assertEquals(new BigDecimal("4.17"), document.taxTotal);
            assertEquals("PREVIOUS", document.previousHash);
            assertSigned(document);
            assertEquals(qr(document, "123456789",
                    "I1:PT*I2:10.00*I3:10.00*I4:0.60*I5:10.00*I6:1.30*I7:6.59*I8:1.51"), document.qrPayload);
            Mockito.verify(document).persist();
            Mockito.verify(service.ticketNumberService).nextDocumentNumber(
                    DocumentTemplate.DocumentType.FS, "PDV1", LocalDate.now().getYear());
            Mockito.verify(service.syncOutboxService).enqueue(
                    com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET, TICKET_ID);
        }
    }

    /**
     * A sale without a NIF — none or a blank one — is stated for the final
     * consumer; a store without a NIF states an empty issuer, and a series without
     * a previous document chains to nothing; a sale stated before its closing is
     * not queued again, its closing queues it with its documents.
     */
    @Test
    void issueSaleDocumentsStatesTheFinalConsumer() {
        PortugueseFiscalService service = service(true);
        Ticket ticket = ticket("8.10", "1.51", List.of(line("L4", "8.10", "0.23", false)),
                List.of(new CardPayment(new BigDecimal("8.10"))));
        ticket.customerTaxId = " ";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            FiscalDocument blank = service.issueSaleDocuments(TICKET_ID).get(0);
            assertNull(blank.customerTaxId);
            assertEquals("", blank.previousHash);
            assertTrue(blank.qrPayload.startsWith("A:*B:999999990*C:PT*D:FS"));
            assertTrue(blank.qrPayload.contains("*I1:PT*I7:6.59*I8:1.51*N:"));
            ticket.customerTaxId = null;
            FiscalDocument none = service.issueSaleDocuments(TICKET_ID).get(0);
            assertNull(none.customerTaxId);
            assertEquals(2, built.constructed().size());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * LC-08-04-18 / -19: a sale whose customer asked for an invoice is stated on
     * a FATURA in the customer's name, and on nothing else.
     */
    @Test
    void issueSaleDocumentsStatesTheRequestedInvoice() {
        PortugueseFiscalService service = service(true);
        service.state.ticket.invoiceRequest = new TicketState.InvoiceRequest("123456789", "ACME LDA", "RUA A 1");
        Ticket ticket = ticket("8.10", "1.51", List.of(line("L4", "8.10", "0.23", false)),
                List.of(new CardPayment(new BigDecimal("8.10"))));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            List<FiscalDocument> documents = service.issueSaleDocuments(TICKET_ID);
            assertEquals(1, documents.size());
            FiscalDocument invoice = documents.get(0);
            assertEquals(FiscalDocument.Type.FT, invoice.documentType);
            assertEquals("CODEFT-1", invoice.atcud);
            assertEquals("123456789", invoice.customerTaxId);
            assertEquals("ACME LDA", invoice.customerName);
            assertEquals("RUA A 1", invoice.customerAddress);
            assertEquals(qr(invoice, "123456789", "I1:PT*I7:6.59*I8:1.51"), invoice.qrPayload);
            assertEquals(1, built.constructed().size());
        }
    }

    /**
     * LC-08-01-06: a sale the social card paid in part is stated on two simplified
     * invoices of the same transaction — the eligible lines first, carrying the
     * card and what the other payments added to it, the rest second carrying what
     * remains of each payment.
     */
    @Test
    void issueSaleDocumentsSplitsASocialCardSale() {
        PortugueseFiscalService service = service(true);
        Ticket ticket = ticket("20.00", "2.70", List.of(line("A", "8.00", "0.06", true),
                line("B", "12.00", "0.23", false)),
                List.of(new CashPayment(new BigDecimal("10.00"), new BigDecimal("10.00")),
                        new SocialCardPayment(new BigDecimal("5.00")),
                        new CardPayment(new BigDecimal("5.01")),
                        new CashPayment(new BigDecimal("-0.01"), null)));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            List<FiscalDocument> documents = service.issueSaleDocuments(TICKET_ID);
            assertEquals(2, documents.size());
            FiscalDocument first = documents.get(0);
            FiscalDocument second = documents.get(1);
            assertEquals(1, first.part);
            assertEquals("A", first.lineUids);
            assertEquals("0:3.00;1:5.00", first.paymentShares);
            assertEquals(new BigDecimal("8.00"), first.grossTotal);
            assertEquals(new BigDecimal("0.45"), first.taxTotal);
            assertEquals("FS C04/" + LocalDate.now().getYear() + "/00000001", first.number);
            assertEquals(2, second.part);
            assertEquals("B", second.lineUids);
            assertEquals("0:7.00;2:5.01;3:-0.01", second.paymentShares);
            assertEquals(new BigDecimal("12.00"), second.grossTotal);
            assertEquals(new BigDecimal("2.25"), second.taxTotal);
            assertEquals("FS C04/" + LocalDate.now().getYear() + "/00000002", second.number);
            assertSigned(first);
            assertSigned(second);
            assertEquals(qr(first, "999999990", "I1:PT*I3:7.55*I4:0.45"), first.qrPayload);
            assertEquals(qr(second, "999999990", "I1:PT*I7:9.76*I8:2.24"), second.qrPayload);
            assertEquals(2, built.constructed().size());
        }
    }

    /**
     * A social card paying a sale whose lines are all eligible, or none of them,
     * or no social card at all, leaves the sale on one document.
     */
    @Test
    void issueSaleDocumentsKeepsOneDocumentWithoutAMixedSocialCardSale() {
        PortugueseFiscalService service = service(true);
        Ticket allEligible = ticket("8.00", "0.45", List.of(line("A", "8.00", "0.06", true)),
                List.of(new SocialCardPayment(new BigDecimal("8.00"))));
        Ticket noneEligible = ticket("8.00", "1.50", List.of(line("B", "8.00", "0.23", false)),
                List.of(new SocialCardPayment(new BigDecimal("8.00"))));
        Ticket noCard = ticket("16.00", "1.95", List.of(line("A", "8.00", "0.06", true),
                line("B", "8.00", "0.23", false)), List.of(new CardPayment(new BigDecimal("16.00"))));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            for (Ticket ticket : List.of(allEligible, noneEligible, noCard)) {
                panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
                List<FiscalDocument> documents = service.issueSaleDocuments(TICKET_ID);
                assertEquals(1, documents.size());
                assertEquals(0, documents.get(0).part);
            }
            assertEquals(3, built.constructed().size());
        }
    }

    /**
     * An administered key that cannot be read fails the issue loudly rather than
     * printing an unsigned document.
     */
    @Test
    void issueSaleDocumentsRefusesAnUnreadableKey() {
        PortugueseFiscalService service = service(true);
        service.privateKey = Optional.of("AAAA");
        Ticket ticket = ticket("8.10", "1.51", List.of(line("L4", "8.10", "0.23", false)),
                List.of(new CardPayment(new BigDecimal("8.10"))));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.issueSaleDocuments(TICKET_ID));
            assertEquals("Signature du document fiscal impossible", failure.getMessage());
        }
    }

    /**
     * Without an administered key a development key is generated once and kept.
     */
    @Test
    void issueSaleDocumentsGeneratesADevelopmentKeyOnce() {
        PortugueseFiscalService service = service(true);
        service.privateKey = Optional.empty();
        Ticket ticket = ticket("8.10", "1.51", List.of(line("L4", "8.10", "0.23", false)),
                List.of(new CardPayment(new BigDecimal("8.10"))));
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            String first = service.issueSaleDocuments(TICKET_ID).get(0).hash;
            String second = service.issueSaleDocuments(TICKET_ID).get(0).hash;
            assertEquals(172, first.length());
            assertEquals(172, second.length());
        }
    }

    /**
     * LC-05-07-03: no credit note where the register issues no Portuguese document.
     */
    @Test
    void issueCreditNoteForRefundDoesNothingWhenDisabled() {
        PortugueseFiscalService service = service(false);
        assertNull(service.issueCreditNoteForRefund(3L));
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * A return already stated answers its credit note; a return that cannot be
     * read is not stated.
     */
    @Test
    void issueCreditNoteForRefundIsIdempotentAndNeedsTheReturn() {
        PortugueseFiscalService service = service(true);
        FiscalDocument existing = new FiscalDocument();
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            panache.when(() -> PanacheEntityBase.list("refundId", 3L)).thenReturn(List.of(existing));
            assertSame(existing, service.issueCreditNoteForRefund(3L));
            assertNull(service.issueCreditNoteForRefund(4L));
        }
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * LC-05-07-03: a return of a stated sale is stated on a credit note naming the
     * sale's documents and their customer, its totals those of the return; the
     * refund is not queued a second time, its own queueing carries the note
     * (BO-05-01-05).
     */
    @Test
    void issueCreditNoteForRefundNamesTheCorrectedDocuments() {
        PortugueseFiscalService service = service(true);
        Refund refund = refund(TICKET_ID, "-10.60", "-0.60");
        FiscalDocument first = new FiscalDocument();
        first.number = "FS C04/2026/00000001";
        first.customerTaxId = "123456789";
        first.customerName = "ACME";
        first.customerAddress = "RUA A";
        FiscalDocument second = new FiscalDocument();
        second.number = "FS C04/2026/00000002";
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(refund);
            panache.when(() -> PanacheEntityBase.list(ACTIVE, TICKET_ID,
                    List.of(FiscalDocument.Type.FS, FiscalDocument.Type.FT))).thenReturn(List.of(first, second));
            FiscalDocument note = service.issueCreditNoteForRefund(3L);
            assertEquals(FiscalDocument.Type.NC, note.documentType);
            assertEquals(3L, note.refundId);
            assertEquals(TICKET_ID, note.ticketId);
            assertEquals("FS C04/2026/00000001, FS C04/2026/00000002", note.sourceNumber);
            assertEquals("123456789", note.customerTaxId);
            assertEquals("ACME", note.customerName);
            assertEquals("RUA A", note.customerAddress);
            assertEquals(new BigDecimal("10.60"), note.grossTotal);
            assertEquals(new BigDecimal("0.60"), note.taxTotal);
            assertEquals("CODENC-1", note.atcud);
            assertEquals(qr(note, "123456789", "I1:PT*I3:10.00*I4:0.60"), note.qrPayload);
            assertSigned(note);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * LC-05-07-03 / LC-08-01-06: a return of one line of a sale stated on two
     * documents names only the document stating that line.
     */
    @Test
    void issueCreditNoteForRefundNamesOnlyTheDocumentOfTheReturnedLine() {
        PortugueseFiscalService service = service(true);
        TicketLine untraced = line("X", "1.00", "0.23", false);
        TicketLine eligible = line("A", "8.00", "0.06", true);
        eligible.id = 11L;
        TicketLine other = line("B", "12.00", "0.23", false);
        other.id = 12L;
        Ticket sale = ticket("21.00", "2.95", List.of(untraced, eligible, other), List.of());
        FiscalDocument first = new FiscalDocument();
        first.number = "FS C04/2026/00000001";
        first.lineUids = "A";
        FiscalDocument second = new FiscalDocument();
        second.number = "FS C04/2026/00000002";
        second.lineUids = "B";
        Refund refund = refund(TICKET_ID, "5.30", "0.30");
        refund.lines.get(0).originalLineId = 12L;
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(refund);
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(sale);
            panache.when(() -> PanacheEntityBase.list(ACTIVE, TICKET_ID,
                    List.of(FiscalDocument.Type.FS, FiscalDocument.Type.FT))).thenReturn(List.of(first, second));
            assertEquals("FS C04/2026/00000002", service.issueCreditNoteForRefund(3L).sourceNumber);
            refund.lines.get(0).originalLineId = 99L;
            assertEquals("FS C04/2026/00000001, FS C04/2026/00000002",
                    service.issueCreditNoteForRefund(3L).sourceNumber);
        }
    }

    /**
     * A return without its sale — or of a sale stated on nothing — names no
     * document, and a return without its VAT total states the ventilation's.
     */
    @Test
    void issueCreditNoteForRefundWithoutStatedSale() {
        PortugueseFiscalService service = service(true);
        Refund orphan = refund(null, "10.60", null);
        Refund unstated = refund(TICKET_ID, "10.60", "0.60");
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(orphan);
            FiscalDocument note = service.issueCreditNoteForRefund(3L);
            assertNull(note.sourceNumber);
            assertNull(note.customerTaxId);
            assertEquals(new BigDecimal("0.60"), note.taxTotal);
            panache.when(() -> PanacheEntityBase.findById(3L)).thenReturn(unstated);
            FiscalDocument second = service.issueCreditNoteForRefund(3L);
            assertNull(second.sourceNumber);
        }
    }

    /**
     * Builds return 3 of one article, 2 x 5.30 at 6 %.
     *
     * @param originalTicketId the returned sale, possibly null
     * @param total the return total
     * @param vat the return VAT, possibly null
     * @return the return
     */
    private static Refund refund(Long originalTicketId, String total, String vat) {
        Refund refund = new Refund();
        refund.id = 3L;
        refund.originalTicketId = originalTicketId;
        refund.totalAmount = new BigDecimal(total);
        refund.totalVat = vat == null ? null : new BigDecimal(vat);
        RefundLine line = new RefundLine();
        line.price = new BigDecimal("5.30");
        line.quantity = new BigDecimal("2");
        line.vatRate = new BigDecimal("0.06");
        refund.lines = new ArrayList<>(List.of(line));
        return refund;
    }

    /**
     * Builds simplified invoice 5 of the 23 October 2026 at C04, stating line A of
     * sale 7.
     *
     * @param type the document type
     * @return the document
     */
    private static FiscalDocument issued(FiscalDocument.Type type) {
        FiscalDocument document = new FiscalDocument();
        document.documentType = type;
        document.number = type + " C04/2026/00000005";
        document.ticketId = TICKET_ID;
        document.part = 1;
        document.lineUids = "A";
        document.paymentShares = "1:8.00";
        document.customerTaxId = "123456789";
        document.customerName = type == FiscalDocument.Type.FT ? "ACME" : null;
        document.customerAddress = type == FiscalDocument.Type.FT ? "RUA A" : null;
        document.grossTotal = new BigDecimal("8.00");
        document.taxTotal = new BigDecimal("0.45");
        return document;
    }

    /**
     * Stubs the lookup of a document named by the operator.
     *
     * @param panache the static mock
     * @param type the document type
     * @param rank the rank named
     * @param found the document answered, possibly null
     */
    private static void named(MockedStatic<PanacheEntityBase> panache, FiscalDocument.Type type, long rank,
            FiscalDocument found) {
        LocalDate day = LocalDate.of(2026, 10, 23);
        panache.when(() -> PanacheEntityBase.list(ISSUED, type, "C04", rank, day.atStartOfDay(),
                day.plusDays(1).atStartOfDay())).thenReturn(found == null ? List.of() : List.of(found));
    }

    /**
     * LC-08-04-21: a document that is not found, or already cancelled, is not
     * converted.
     */
    @Test
    void convertToInvoiceRefusesAMissingOrCancelledDocument() {
        PortugueseFiscalService service = service(true);
        TicketState.InvoiceRequest customer = new TicketState.InvoiceRequest("123456789", "ACME", "RUA A");
        FiscalDocument cancelled = issued(FiscalDocument.Type.FS);
        cancelled.cancelled = true;
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            named(panache, FiscalDocument.Type.FS, 6L, cancelled);
            PortugueseFiscalService.Outcome missing =
                    service.convertToInvoice(LocalDate.of(2026, 10, 23), "C04", 5L, customer);
            assertEquals("fiscalpt.introuvable", missing.error());
            assertEquals(List.of(), missing.documents());
            assertEquals("fiscalpt.dejaAnnule",
                    service.convertToInvoice(LocalDate.of(2026, 10, 23), "C04", 6L, customer).error());
        }
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * LC-08-04-21: a simplified invoice turned into an invoice is cancelled by a
     * credit note referring to it, then the invoice states the same lines and
     * payments for the customer.
     */
    @Test
    void convertToInvoiceCancelsThenReplaces() {
        PortugueseFiscalService service = service(true);
        FiscalDocument simplified = issued(FiscalDocument.Type.FS);
        Ticket ticket = ticket("20.00", "2.70", List.of(line("A", "8.00", "0.06", true),
                line("B", "12.00", "0.23", false)), List.of());
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            named(panache, FiscalDocument.Type.FS, 5L, simplified);
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            PortugueseFiscalService.Outcome outcome = service.convertToInvoice(LocalDate.of(2026, 10, 23), "C04",
                    5L, new TicketState.InvoiceRequest("987654321", "ACME", "RUA A"));
            assertNull(outcome.error());
            assertTrue(simplified.cancelled);
            FiscalDocument note = outcome.documents().get(0);
            FiscalDocument invoice = outcome.documents().get(1);
            assertEquals(FiscalDocument.Type.NC, note.documentType);
            assertEquals("FS C04/2026/00000005", note.sourceNumber);
            assertEquals("123456789", note.customerTaxId);
            assertNull(note.refundId);
            assertEquals(1, note.part);
            assertEquals("A", note.lineUids);
            assertEquals("1:8.00", note.paymentShares);
            assertEquals(new BigDecimal("8.00"), note.grossTotal);
            assertEquals(new BigDecimal("0.45"), note.taxTotal);
            assertEquals(qr(note, "123456789", "I1:PT*I3:7.55*I4:0.45"), note.qrPayload);
            assertEquals(FiscalDocument.Type.FT, invoice.documentType);
            assertEquals("FS C04/2026/00000005", invoice.sourceNumber);
            assertEquals("987654321", invoice.customerTaxId);
            assertEquals("ACME", invoice.customerName);
            assertEquals("RUA A", invoice.customerAddress);
            assertEquals(new BigDecimal("0.45"), invoice.taxTotal);
            assertEquals(qr(invoice, "987654321", "I1:PT*I3:7.55*I4:0.45"), invoice.qrPayload);
            assertSigned(note);
            assertSigned(invoice);
        }
        // BO-02-04-19: the sale goes up again, carrying its new documents.
        verify(service.syncOutboxService).enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET,
                simplified.ticketId);
    }

    /**
     * LC-08-04-22: an invoice that is not found, cancelled or already received is
     * refused its receipt.
     */
    @Test
    void issueReceiptRefusesWhatCannotBeReceived() {
        PortugueseFiscalService service = service(true);
        FiscalDocument cancelled = issued(FiscalDocument.Type.FT);
        cancelled.cancelled = true;
        FiscalDocument received = issued(FiscalDocument.Type.FT);
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            named(panache, FiscalDocument.Type.FT, 6L, cancelled);
            named(panache, FiscalDocument.Type.FT, 7L, received);
            panache.when(() -> PanacheEntityBase.list("documentType = ?1 and sourceNumber = ?2",
                    FiscalDocument.Type.RC, received.number)).thenReturn(List.of(new FiscalDocument()));
            LocalDate day = LocalDate.of(2026, 10, 23);
            assertEquals("fiscalpt.introuvable", service.issueReceipt(day, "C04", 5L).error());
            assertEquals("fiscalpt.dejaAnnule", service.issueReceipt(day, "C04", 6L).error());
            assertEquals("fiscalpt.reciboDejaEmis", service.issueReceipt(day, "C04", 7L).error());
        }
        verifyNoInteractions(service.ticketNumberService);
    }

    /**
     * LC-08-04-22: the receipt of an invoice states its customer and its amount,
     * refers to it, and its QR payload states no tax.
     */
    @Test
    void issueReceiptStatesThePaymentOfTheInvoice() {
        PortugueseFiscalService service = service(true);
        FiscalDocument invoice = issued(FiscalDocument.Type.FT);
        try (MockedStatic<PanacheEntityBase> panache = panache();
             MockedConstruction<FiscalDocument> built = documents()) {
            named(panache, FiscalDocument.Type.FT, 5L, invoice);
            PortugueseFiscalService.Outcome outcome = service.issueReceipt(LocalDate.of(2026, 10, 23), "C04", 5L);
            assertNull(outcome.error());
            FiscalDocument receipt = outcome.documents().get(0);
            assertEquals(FiscalDocument.Type.RC, receipt.documentType);
            assertEquals("FT C04/2026/00000005", receipt.sourceNumber);
            assertEquals("ACME", receipt.customerName);
            assertEquals("RUA A", receipt.customerAddress);
            assertEquals(new BigDecimal("8.00"), receipt.grossTotal);
            assertEquals(BigDecimal.ZERO, receipt.taxTotal);
            assertEquals(qr(receipt, "123456789", "I1:0"), receipt.qrPayload);
            assertSigned(receipt);
        }
        verify(service.syncOutboxService).enqueue(com.intermarche.pos.domain.sync.SyncOutbox.EntityType.TICKET,
                invoice.ticketId);
    }

    /**
     * The lines of a document are those of its sale it covers; a document without
     * a sale, or whose sale cannot be read, states none.
     */
    @Test
    void linesOfReadsTheCoveredLines() {
        PortugueseFiscalService service = service(true);
        FiscalDocument document = issued(FiscalDocument.Type.FS);
        TicketLine a = line("A", "8.00", "0.06", true);
        Ticket ticket = ticket("20.00", "2.70", List.of(a, line("B", "12.00", "0.23", false)), List.of());
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            assertEquals(List.of(), service.linesOf(document));
            panache.when(() -> PanacheEntityBase.findById(TICKET_ID)).thenReturn(ticket);
            assertEquals(List.of(a), service.linesOf(document));
            document.ticketId = null;
            assertEquals(List.of(), service.linesOf(document));
        }
    }

    /**
     * The certificate number is stated without the /AT the mention adds, and as
     * 0 when none is administered.
     */
    @Test
    void certificateStripsTheAdministrationSuffix() {
        assertEquals("2468", PortugueseFiscalService.certificate("2468/AT"));
        assertEquals("2468", PortugueseFiscalService.certificate(" 2468 /at "));
        assertEquals("2468", PortugueseFiscalService.certificate("2468"));
        assertEquals("0", PortugueseFiscalService.certificate("/AT"));
        assertEquals("0", PortugueseFiscalService.certificate(""));
        assertEquals("0", PortugueseFiscalService.certificate(null));
    }

    /**
     * The QR payload of a document issued at a known moment, asserted in full.
     */
    @Test
    void qrPayloadStatesTheDocumentInFull() {
        PortugueseFiscalService service = service(true);
        FiscalDocument document = issued(FiscalDocument.Type.FS);
        document.issuedAt = LocalDateTime.of(2026, 10, 23, 10, 15, 0);
        document.atcud = "CODEFS-5";
        document.hash = "A123456789B123456789C123456789D123456789";
        com.intermarche.pos.domain.sale.VatBreakdown twoReduced = new com.intermarche.pos.domain.sale.VatBreakdown();
        twoReduced.add(new BigDecimal("0.05"), new BigDecimal("10.50"));
        twoReduced.add(new BigDecimal("0.06"), new BigDecimal("10.60"));
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            assertEquals("A:500100200*B:123456789*C:PT*D:FS*E:N*F:20261023*G:FS C04/2026/00000005"
                    + "*H:CODEFS-5*I1:PT*N:0.45*O:8.00*Q:ABCD*R:2468",
                    service.qrPayload(document, new com.intermarche.pos.domain.sale.VatBreakdown()));
            assertEquals("A:500100200*B:123456789*C:PT*D:FS*E:N*F:20261023*G:FS C04/2026/00000005"
                    + "*H:CODEFS-5*I1:PT*I3:20.00*I4:1.10*N:0.45*O:8.00*Q:ABCD*R:2468",
                    service.qrPayload(document, twoReduced));
        }
    }

    /**
     * LC-08-06-11: the QR states the final consumer's 999999990 in Portugal for a
     * stated 999999990, prefixed or not, and the country and number of a foreign
     * customer; a store whose NIF is not administered states an empty issuer.
     */
    @Test
    void qrPayloadStatesTheCustomersCountry() {
        PortugueseFiscalService service = service(true);
        FiscalDocument document = issued(FiscalDocument.Type.FT);
        document.issuedAt = LocalDateTime.of(2026, 10, 23, 10, 15, 0);
        document.atcud = "CODEFT-1";
        document.hash = "A123456789B123456789C123456789D123456789";
        try (MockedStatic<PanacheEntityBase> panache = panache()) {
            document.customerTaxId = "PT999999990";
            assertTrue(service.qrPayload(document, new com.intermarche.pos.domain.sale.VatBreakdown())
                    .startsWith("A:500100200*B:999999990*C:PT*D:FT"));
            document.customerTaxId = "es b12345678";
            assertTrue(service.qrPayload(document, new com.intermarche.pos.domain.sale.VatBreakdown())
                    .startsWith("A:500100200*B:b12345678*C:ES*D:FT"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(PanacheEntityBase::listAll).thenReturn(List.of(new Store()));
            assertTrue(service.qrPayload(document, new com.intermarche.pos.domain.sale.VatBreakdown())
                    .startsWith("A:*B:b12345678*C:ES"));
        }
    }
}
