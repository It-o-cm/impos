package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentDepositService}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5 + Mockito: the collaborators are mocks, the pricing of the
 * document answers its own lines, and the cashier lookup goes through a static
 * Panache mock. Branch enumeration: the total (lines priced to something, to
 * nothing with and without a stated total), the remaining amount (never
 * negative), every refusal of {@code collect} (no document, null and blank
 * code, no session, training, no deposit admitted, null, zero and excessive
 * amount), the collected arm with and without an operator, with and without
 * the deposit invoice, and a second deposit adding to the first.
 */
class DocumentDepositServiceTest {

    /**
     * The service under test over fresh mocks, an open session and the
     * document's own prices.
     *
     * @return the wired service
     */
    private DocumentDepositService service() {
        DocumentDepositService service = new DocumentDepositService();
        service.cashMovementService = mock(CashMovementService.class);
        service.cashSessionService = mock(CashSessionService.class);
        service.ticketService = mock(TicketService.class);
        service.documentClient = mock(BackOfficeDocumentClient.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.channelRules = mock(ChannelRules.class);
        service.channelGate = mock(ChannelGate.class);
        when(service.cashSessionService.getOpenSession()).thenReturn(new CashSession());
        when(service.ticketService.pricedLines(any())).thenAnswer(inv ->
                ((RecalledDocument) inv.getArgument(0)).lines);
        return service;
    }

    /**
     * An optional-deposit quote of 60.00 at 20% and 40.00 at 5.5%.
     *
     * @return the document
     */
    private RecalledDocument quote() {
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.DEVIS, "DV-1", List.of(
                new RecalledDocument.Line("301", "LAIT", BigDecimal.ONE, new BigDecimal("60.00"), new BigDecimal("0.2")),
                new RecalledDocument.Line("302", "PAIN", BigDecimal.ONE, new BigDecimal("40.00"), new BigDecimal("0.055"))),
                null);
        doc.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        return doc;
    }

    /**
     * A state waiting for the choice on the given document.
     *
     * @param doc the waiting document
     * @return the state
     */
    private PosState waiting(RecalledDocument doc) {
        PosState state = new PosState();
        state.pendingDocument = doc;
        return state;
    }

    /**
     * The total is the sum of the priced lines when they price to something.
     */
    @Test
    void totalSumsThePricedLines() {
        assertEquals(new BigDecimal("100.00"), service().total(quote()));
    }

    /**
     * Lines pricing to nothing take the total the document states, or zero
     * when it states none (both arms of the fallback).
     */
    @Test
    void totalFallsBackOnTheStatedTotal() {
        DocumentDepositService service = service();
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.COMMANDE, "C-1", List.of(), null);
        assertEquals(BigDecimal.ZERO, service.total(doc));
        doc.totalAmount = new BigDecimal("75.00");
        assertEquals(new BigDecimal("75.00"), service.total(doc));
    }

    /**
     * The remaining amount is the total less the deposits, never negative.
     */
    @Test
    void remainingIsNeverNegative() {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        doc.acompteAlreadyPaid = new BigDecimal("30.00");
        assertEquals(new BigDecimal("70.00"), service.remaining(doc));
        doc.acompteAlreadyPaid = new BigDecimal("130.00");
        assertEquals(BigDecimal.ZERO, service.remaining(doc));
    }

    /**
     * Without a waiting document, or with one carrying a null or blank code,
     * nothing is collected.
     */
    @Test
    void noDocumentIsRefused() {
        DocumentDepositService service = service();
        assertEquals(DocumentDepositService.Outcome.NO_DOCUMENT, service.collect(new PosState(), BigDecimal.TEN));
        RecalledDocument doc = quote();
        doc.code = null;
        assertEquals(DocumentDepositService.Outcome.NO_DOCUMENT, service.collect(waiting(doc), BigDecimal.TEN));
        doc.code = "  ";
        assertEquals(DocumentDepositService.Outcome.NO_DOCUMENT, service.collect(waiting(doc), BigDecimal.TEN));
        verifyNoInteractions(service.cashMovementService, service.documentClient, service.ticketPrinterService);
    }

    /**
     * Without an open session the deposit is refused.
     */
    @Test
    void noSessionIsRefused() {
        DocumentDepositService service = service();
        when(service.cashSessionService.getOpenSession()).thenReturn(null);
        assertEquals(DocumentDepositService.Outcome.NO_SESSION, service.collect(waiting(quote()), BigDecimal.TEN));
        verifyNoInteractions(service.cashMovementService);
    }

    /**
     * Training mode touches no real document.
     */
    @Test
    void trainingIsRefused() {
        DocumentDepositService service = service();
        PosState state = waiting(quote());
        state.trainingMode = true;
        assertEquals(DocumentDepositService.Outcome.TRAINING, service.collect(state, BigDecimal.TEN));
        verifyNoInteractions(service.cashMovementService);
    }

    /**
     * A document admitting no deposit is refused.
     */
    @Test
    void aDocumentWithoutDepositIsRefused() {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        doc.depositRule = RecalledDocument.DEPOSIT_NONE;
        assertEquals(DocumentDepositService.Outcome.NOT_ALLOWED, service.collect(waiting(doc), BigDecimal.TEN));
        verifyNoInteractions(service.cashMovementService);
    }

    /**
     * A null, zero or negative amount is refused.
     */
    @Test
    void aBadAmountIsRefused() {
        DocumentDepositService service = service();
        assertEquals(DocumentDepositService.Outcome.BAD_AMOUNT, service.collect(waiting(quote()), null));
        assertEquals(DocumentDepositService.Outcome.BAD_AMOUNT, service.collect(waiting(quote()), BigDecimal.ZERO));
        assertEquals(DocumentDepositService.Outcome.BAD_AMOUNT,
                service.collect(waiting(quote()), new BigDecimal("-1")));
        verifyNoInteractions(service.cashMovementService);
    }

    /**
     * An amount over what remains due is refused, the exact remainder accepted.
     */
    @Test
    void anAmountOverTheRemainderIsRefused() {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        doc.acompteAlreadyPaid = new BigDecimal("90.00");
        assertEquals(DocumentDepositService.Outcome.OVER_REMAINING,
                service.collect(waiting(doc), new BigDecimal("10.01")));
        verifyNoInteractions(service.cashMovementService);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals(DocumentDepositService.Outcome.COLLECTED,
                    service.collect(waiting(doc), new BigDecimal("10.00")));
        }
    }

    /**
     * A deposit collected without an operator records the movement without a
     * cashier, tells the node its VAT ventilation, prints the slip and no
     * invoice, and leaves the document carrying it (LC-06-02-04, -07).
     */
    @Test
    void aDepositIsCollectedWithoutInvoiceByDefault() {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        PosState state = waiting(doc);
        long before = state.version;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals(DocumentDepositService.Outcome.COLLECTED, service.collect(state, new BigDecimal("10.00")));
        }
        verify(service.cashMovementService).recordAcompte(any(CashSession.class), isNull(),
                eq(new BigDecimal("10.00")), eq("DV-1"), isNull());
        verify(service.documentClient).recordDeposit("DV-1", new BigDecimal("10.00"), "0.055:0.21;0.2:1");
        verify(service.ticketPrinterService).printOrderDepositTicket(eq(RecalledDocument.Kind.DEVIS), eq("DV-1"), any(),
                eq(new BigDecimal("10.00")), eq("0.055:0.21;0.2:1"), eq(doc.customerNumber));
        verify(service.ticketPrinterService, never()).printDepositInvoice(any(), any(), any(), any(), any(), any(), any());
        assertEquals(new BigDecimal("10.00"), doc.acompteAlreadyPaid);
        assertEquals("0.055:0.21;0.2:1", doc.depositVatDetail);
        assertTrue(state.version > before);
    }

    /**
     * A second deposit by a known cashier is recorded under the cashier, prints
     * the numbered deposit invoice with what was paid before, and adds its VAT
     * to the first (LC-06-02-07, LC-08-04-15).
     */
    @Test
    void aSecondDepositPrintsTheInvoiceAndAddsUp() {
        DocumentDepositService service = service();
        when(service.posSettingsService.orderDepositInvoice()).thenReturn(true);
        when(service.ticketNumberService.nextDepositInvoiceNumber()).thenReturn("C04-FA2026-000002");
        RecalledDocument doc = quote();
        doc.acompteAlreadyPaid = new BigDecimal("10.00");
        doc.depositVatDetail = "0.055:0.21;0.2:1";
        PosState state = waiting(doc);
        state.auth.operatorId = 7L;
        Employee cashier = new Employee();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(cashier);
            assertEquals(DocumentDepositService.Outcome.COLLECTED, service.collect(state, new BigDecimal("20.00")));
        }
        verify(service.cashMovementService).recordAcompte(any(CashSession.class), eq(cashier),
                eq(new BigDecimal("20.00")), eq("DV-1"), isNull());
        verify(service.ticketPrinterService).printDepositInvoice(eq(RecalledDocument.Kind.DEVIS), eq("C04-FA2026-000002"), eq("DV-1"), any(),
                eq(new BigDecimal("100.00")), eq(new BigDecimal("20.00")), eq(new BigDecimal("10.00")));
        assertEquals(new BigDecimal("30.00"), doc.acompteAlreadyPaid);
        assertEquals("0.055:0.63;0.2:3", doc.depositVatDetail);
    }

    /**
     * BO-10-01: a document its channel refuses takes no deposit, and nothing is
     * recorded.
     */
    @Test
    void aDocumentItsChannelRefusesTakesNoDeposit() {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        PosState state = waiting(doc);
        when(service.channelGate.refusal(state, doc)).thenReturn("REFUS");
        assertEquals(DocumentDepositService.Outcome.REFUSED, service.collect(state, new BigDecimal("10.00")));
        verifyNoInteractions(service.cashMovementService);
    }

    /**
     * BO-10-01-27: the deposit is recorded in the tender the store administers.
     */
    @Test
    void theDepositIsTakenInTheAdministeredTender() {
        DocumentDepositService service = service();
        when(service.channelRules.commercialDepositTender()).thenReturn("CARD");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals(DocumentDepositService.Outcome.COLLECTED,
                    service.collect(waiting(quote()), new BigDecimal("10.00")));
        }
        verify(service.cashMovementService).recordAcompte(any(CashSession.class), isNull(),
                eq(new BigDecimal("10.00")), eq("DV-1"), eq("CARD"));
    }

    /**
     * Collects a deposit on a quote issued for the given customer number, the
     * customer referential answering with the given customer, and returns the
     * name the slip was printed for (BO-03-03-09).
     *
     * @param number the customer number of the document
     * @param known the customer the referential answers, or null
     * @return the customer name handed to the printer
     */
    private String slipCustomer(String number, com.intermarche.pos.domain.payment.AccountCustomer known) {
        DocumentDepositService service = service();
        RecalledDocument doc = quote();
        doc.customerNumber = number;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<Object> query =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(query.firstResult()).thenReturn(known);
            panache.when(() -> PanacheEntityBase.find(eq("accountNumber"), any(Object[].class))).thenReturn(query);
            assertEquals(DocumentDepositService.Outcome.COLLECTED,
                    service.collect(waiting(doc), new BigDecimal("10.00")));
        }
        org.mockito.ArgumentCaptor<String> name = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printOrderDepositTicket(any(), any(), any(), any(), any(),
                name.capture());
        return name.getValue();
    }

    /**
     * BO-03-03-09: the slip names the customer by the business name the
     * register knows for the document's customer number, keeps the number of
     * a customer it does not know, and names nobody for a blank number.
     */
    @Test
    void theDepositSlipNamesTheCustomer() {
        com.intermarche.pos.domain.payment.AccountCustomer acme =
                new com.intermarche.pos.domain.payment.AccountCustomer();
        acme.companyName = "ACME SARL";
        assertEquals("ACME SARL", slipCustomer(" C100 ", acme));
        assertEquals("C999", slipCustomer("C999", null));
        assertEquals(" ", slipCustomer(" ", null));
    }
}
