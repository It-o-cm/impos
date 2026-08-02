package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncIngestService}.
 * <p>
 * The service is a Panache active-record consumer: every ingest method
 * resolves natural keys through inherited static finders ({@code X.find(...)},
 * {@code Store.findAll()}, {@code Product.findByEan/findByPlu}) and persists
 * the upserted graph. All static access is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase} (the
 * class the inherited {@code find}/{@code findAll} resolve to under plain
 * {@code mvn test}, and the one {@code Product.findByPlu}/{@code findByEan}
 * delegate to), and every {@code new X()} on the insert path is neutralized
 * with {@link org.mockito.Mockito#mockConstruction}: the constructed instance
 * is a mock whose {@code persist()} is inert and whose owned collections are
 * seeded to real lists so {@code clear()}/{@code add()} behave. Found entities
 * are plain Mockito mocks (fields read/written directly). Payment factories are
 * mocks registered through a mocked CDI {@link Instance}; no database and no
 * Quarkus context is booted.
 * <p>
 * Branch enumeration — 29 two-way decision points (58 branches), every arm
 * exercised: {@code init} loop; {@code ingestSession} (create/update,
 * closing-cashier ternary, created ternary); {@code ingestTicket}
 * (create/update, session ternary, lines loop, PLU/EAN product-resolution
 * arms, created ternary, payments loop, voucher-key ternary, unknown-factory
 * throw, {@code instanceof VoucherPayment}); {@code ingestRefund}
 * (missing-original throw, create/update, refund-method ternary, session
 * ternary, lines loop, original-line ternary both arms, missing-line throw,
 * created ternary); {@code ingestEvent} (create/update); the shared
 * {@code requireEmployee} (login ternary + not-found throw),
 * {@code requireStore} (code ternary, fallback arm, no-store throw) and
 * {@code parse} (null/non-null) — 100%.
 */
class SyncIngestServiceTest {

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * value.
     *
     * @param result the value the query must return
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Builds a service whose payment factories are indexed from the supplied
     * mocks by invoking {@link SyncIngestService#init()}.
     *
     * @param factories the payment factories to register
     * @return the initialized service
     */
    @SuppressWarnings("unchecked")
    private SyncIngestService serviceWith(TicketPayment.Factory... factories) {
        SyncIngestService service = new SyncIngestService();
        Instance<TicketPayment.Factory> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(factories).iterator());
        service.factoryInstances = instance;
        service.init();
        return service;
    }

    // --------------------------------------------------
    // ingestSession
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestSession} with a non-null closing
     * cashier (ternary true arm), a non-null opening date (parse true arm) and
     * a null closing date (parse false arm); the created row is stamped and
     * persisted and the "créée" log arm is taken.
     */
    @Test
    void ingestSessionCreatesWithClosingCashier() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S1";
        dto.terminalId = "T1";
        dto.status = "OPEN";
        dto.openingDate = "2026-01-01T08:00:00";
        dto.closingDate = null;
        dto.openingCashierLogin = "alice";
        dto.closingCashierLogin = "bob";
        dto.openingFloat = new BigDecimal("50.00");
        dto.countedAmount = new BigDecimal("120.00");
        dto.theoreticalAmount = new BigDecimal("119.00");
        dto.variance = new BigDecimal("1.00");
        dto.withdrawnAmount = new BigDecimal("70.00");
        dto.countDetail = "detail";
        Employee opening = mock(Employee.class);
        Employee closing = mock(Employee.class);
        PanacheQuery<CashSession> sessionQuery = queryReturning(null);
        PanacheQuery<Employee> openingQuery = queryReturning(opening);
        PanacheQuery<Employee> closingQuery = queryReturning(closing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(openingQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(closingQuery);
            service.ingestSession(dto);
            CashSession session = created.constructed().get(0);
            assertEquals("S1", session.sessionNumber);
            assertEquals("T1", session.terminalId);
            assertEquals(CashSession.SessionStatus.OPEN, session.status);
            assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0, 0), session.openingDate);
            assertNull(session.closingDate);
            assertSame(opening, session.openingCashier);
            assertSame(closing, session.closingCashier);
            assertEquals(new BigDecimal("50.00"), session.openingFloat);
            assertEquals("detail", session.countDetail);
            verify(session, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestSession} with a null closing
     * cashier (ternary false arm) and a non-null closing date (parse true arm);
     * the existing row is refreshed, persisted and the "mise à jour" log arm is
     * taken.
     */
    @Test
    void ingestSessionUpdatesWithoutClosingCashier() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S2";
        dto.terminalId = "T2";
        dto.status = "CLOSED";
        dto.openingDate = "2026-02-02T09:00:00";
        dto.closingDate = "2026-02-02T18:00:00";
        dto.openingCashierLogin = "alice";
        dto.closingCashierLogin = null;
        dto.openingFloat = new BigDecimal("40.00");
        Employee opening = mock(Employee.class);
        CashSession existing = mock(CashSession.class);
        PanacheQuery<CashSession> sessionQuery = queryReturning(existing);
        PanacheQuery<Employee> openingQuery = queryReturning(opening);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S2")).thenReturn(sessionQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(openingQuery);
            service.ingestSession(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(CashSession.SessionStatus.CLOSED, existing.status);
            assertEquals(LocalDateTime.of(2026, 2, 2, 18, 0, 0), existing.closingDate);
            assertSame(opening, existing.openingCashier);
            assertNull(existing.closingCashier);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the null-login arm of {@code requireEmployee} (ternary false arm)
     * and its unresolved-employee throw arm: a session whose opening cashier
     * login is null raises {@link IllegalStateException}.
     */
    @Test
    void ingestSessionThrowsOnUnknownEmployee() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.SessionDto dto = new SyncPayloads.SessionDto();
        dto.sessionNumber = "S3";
        dto.status = "OPEN";
        dto.openingDate = "2026-03-03T10:00:00";
        dto.openingCashierLogin = null;
        PanacheQuery<CashSession> sessionQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CashSession.find("sessionNumber", "S3")).thenReturn(sessionQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestSession(dto));
        }
    }

    // --------------------------------------------------
    // ingestTicket
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestTicket} end to end: a resolved
     * store (code ternary true arm, first store guard false), a resolved
     * cashier, a non-null session (session ternary true arm), the three
     * line product-resolution arms (PLU present, EAN-only, neither) and both
     * payment kinds (voucher — voucher-key ternary true arm and
     * {@code instanceof} true arm; card — both false arms). The "créé" log arm
     * is taken and the graph is persisted.
     */
    @Test
    void ingestTicketCreatesWithLinesPaymentsAndSession() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        when(cardFactory.getKey()).thenReturn("CB");
        when(voucherFactory.getKey()).thenReturn("VOUCHER");
        TicketPayment cardPayment = mock(TicketPayment.class);
        VoucherPayment voucherPayment = mock(VoucherPayment.class);
        when(cardFactory.create(any(), any())).thenReturn(cardPayment);
        when(voucherFactory.create(any(), any())).thenReturn(voucherPayment);
        SyncIngestService service = serviceWith(cardFactory, voucherFactory);
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K1";
        dto.terminalId = "T1";
        dto.status = "CLOSED";
        dto.creationDate = "2026-01-01T10:00:00";
        dto.closingDate = null;
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = "S1";
        dto.fidelityCard = "F1";
        dto.itemCount = 3;
        dto.totalExcludingTax = new BigDecimal("10.00");
        dto.totalIncludingTax = new BigDecimal("12.00");
        dto.totalVat = new BigDecimal("2.00");
        dto.grandTotal = new BigDecimal("100.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.LineDto byPlu = new SyncPayloads.LineDto();
        byPlu.lineNumber = 1;
        byPlu.plu = "100";
        byPlu.quantity = new BigDecimal("1");
        SyncPayloads.LineDto byEan = new SyncPayloads.LineDto();
        byEan.lineNumber = 2;
        byEan.plu = null;
        byEan.ean = "E1";
        SyncPayloads.LineDto noRef = new SyncPayloads.LineDto();
        noRef.lineNumber = 3;
        noRef.plu = null;
        noRef.ean = null;
        dto.lines.add(byPlu);
        dto.lines.add(byEan);
        dto.lines.add(noRef);
        SyncPayloads.PaymentDto voucherDto = new SyncPayloads.PaymentDto();
        voucherDto.paymentIndex = 1;
        voucherDto.methodKey = "CB";
        voucherDto.amount = new BigDecimal("5.00");
        voucherDto.voucherLabel = "Bon";
        voucherDto.voucherNumber = "V9";
        SyncPayloads.PaymentDto cardDto = new SyncPayloads.PaymentDto();
        cardDto.paymentIndex = 2;
        cardDto.methodKey = "CB";
        cardDto.amount = new BigDecimal("7.00");
        cardDto.tenderedAmount = new BigDecimal("7.00");
        cardDto.voucherLabel = null;
        dto.payments.add(voucherDto);
        dto.payments.add(cardDto);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product plaster = mock(Product.class);
        Product apple = mock(Product.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        PanacheQuery<Product> pluQuery = queryReturning(plaster);
        PanacheQuery<Product> eanQuery = queryReturning(apple);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); });
                MockedConstruction<TicketLine> createdLines = mockConstruction(TicketLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            mocked.when(() -> Product.find("plu", "100")).thenReturn(pluQuery);
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(eanQuery);
            service.ingestTicket(dto);
            Ticket ticket = createdTicket.constructed().get(0);
            assertEquals("K1", ticket.ticketNumber);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0, 0), ticket.creationDate);
            assertNull(ticket.closingDate);
            assertSame(store, ticket.store);
            assertSame(cashier, ticket.cashier);
            assertSame(session, ticket.session);
            assertEquals(Ticket.ValuationStatus.NOT_VALUATED, ticket.valuationStatus);
            assertEquals(3, createdLines.constructed().size());
            assertSame(plaster, createdLines.constructed().get(0).product);
            assertSame(apple, createdLines.constructed().get(1).product);
            assertNull(createdLines.constructed().get(2).product);
            verify(ticket, times(3)).addLine(any());
            assertEquals("Bon", voucherPayment.voucherLabel);
            assertEquals("V9", voucherPayment.voucherNumber);
            assertEquals(1, voucherPayment.paymentIndex);
            assertEquals(2, cardPayment.paymentIndex);
            verify(ticket).addPayment(voucherPayment);
            verify(ticket).addPayment(cardPayment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestTicket} with a null session
     * (session ternary false arm) and a null store code exercising the
     * fallback to the single local store (code ternary false arm, first store
     * guard true, second guard false). The "mis à jour" log arm is taken and no
     * lines or payments are replayed (loop exit arms).
     */
    @Test
    void ingestTicketUpdatesWithFallbackStoreAndNullSession() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K2";
        dto.terminalId = "T2";
        dto.status = "CANCELLED";
        dto.creationDate = "2026-02-02T11:00:00";
        dto.storeCode = null;
        dto.cashierLogin = "bob";
        dto.sessionNumber = null;
        dto.itemCount = 0;
        dto.totalExcludingTax = new BigDecimal("0.00");
        dto.totalIncludingTax = new BigDecimal("0.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "DEGRADED";
        Ticket existing = mock(Ticket.class);
        existing.lines = new ArrayList<>();
        existing.payments = new ArrayList<>();
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(existing);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K2")).thenReturn(ticketQuery);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(cashierQuery);
            service.ingestTicket(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(Ticket.TicketStatus.CANCELLED, existing.status);
            assertSame(store, existing.store);
            assertSame(cashier, existing.cashier);
            assertNull(existing.session);
            assertEquals(Ticket.ValuationStatus.DEGRADED, existing.valuationStatus);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the unknown-factory throw arm of {@code ingestTicket}: a non-voucher
     * payment whose method key has no registered factory (voucher-key ternary
     * false arm, {@code factory == null} true arm) raises
     * {@link IllegalStateException}.
     */
    @Test
    void ingestTicketThrowsOnUnknownPaymentMethod() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K3";
        dto.terminalId = "T3";
        dto.status = "CLOSED";
        dto.creationDate = "2026-03-03T12:00:00";
        dto.storeCode = "ST1";
        dto.cashierLogin = "alice";
        dto.sessionNumber = null;
        dto.totalExcludingTax = new BigDecimal("1.00");
        dto.totalIncludingTax = new BigDecimal("1.00");
        dto.totalVat = new BigDecimal("0.00");
        dto.valuationStatus = "NOT_VALUATED";
        SyncPayloads.PaymentDto payment = new SyncPayloads.PaymentDto();
        payment.methodKey = "UNKNOWN";
        payment.amount = new BigDecimal("1.00");
        payment.voucherLabel = null;
        dto.payments.add(payment);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Employee> cashierQuery = queryReturning(cashier);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K3")).thenReturn(ticketQuery);
            mocked.when(() -> Store.find("code", "ST1")).thenReturn(storeQuery);
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(cashierQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestTicket(dto));
        }
    }

    /**
     * Covers the no-store throw arm of {@code requireStore}: a null store code
     * with an empty local store table (code ternary false arm, first and second
     * store guards both true) raises {@link IllegalStateException}.
     */
    @Test
    void ingestTicketThrowsWhenNoStoreExists() {
        SyncIngestService service = serviceWith();
        SyncPayloads.TicketDto dto = new SyncPayloads.TicketDto();
        dto.ticketNumber = "K4";
        dto.status = "CLOSED";
        dto.creationDate = "2026-04-04T13:00:00";
        dto.storeCode = null;
        dto.cashierLogin = "alice";
        dto.valuationStatus = "NOT_VALUATED";
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        PanacheQuery<Store> storeQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> createdTicket = mockConstruction(Ticket.class,
                        (m, c) -> { m.lines = new ArrayList<>(); m.payments = new ArrayList<>(); })) {
            mocked.when(() -> Ticket.find("ticketNumber", "K4")).thenReturn(ticketQuery);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestTicket(dto));
        }
    }

    // --------------------------------------------------
    // ingestRefund
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestRefund} with a resolved original
     * ticket, a non-null refund method (ternary true arm), a non-null session
     * (ternary true arm) and one line whose original-line uid resolves against
     * the original ticket (original-line ternary true arm, missing-line guard
     * false arm). The "créé" log arm is taken and the graph is persisted.
     */
    @Test
    void ingestRefundCreatesWithResolvedLine() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R1";
        dto.terminalId = "T1";
        dto.status = "CLOSED";
        dto.refundMethod = "CASH";
        dto.originalTicketNumber = "K1";
        dto.sessionNumber = "S1";
        dto.creationDate = "2026-01-01T14:00:00";
        dto.totalAmount = new BigDecimal("5.00");
        dto.totalExcludingTax = new BigDecimal("4.00");
        dto.totalVat = new BigDecimal("1.00");
        SyncPayloads.RefundLineDto lineDto = new SyncPayloads.RefundLineDto();
        lineDto.originalLineUid = "U1";
        lineDto.productLabel = "Pomme";
        lineDto.quantity = new BigDecimal("1");
        lineDto.price = new BigDecimal("5.00");
        lineDto.vatRate = new BigDecimal("0.2000");
        dto.lines.add(lineDto);
        Ticket original = mock(Ticket.class);
        original.id = 7L;
        TicketLine originalLine = mock(TicketLine.class);
        originalLine.lineUid = "U1";
        originalLine.id = 42L;
        original.lines = new ArrayList<>(List.of(originalLine));
        CashSession session = mock(CashSession.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(null);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Refund> createdRefund = mockConstruction(Refund.class,
                        (m, c) -> m.lines = new ArrayList<>());
                MockedConstruction<RefundLine> createdLines = mockConstruction(RefundLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K1")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R1")).thenReturn(refundQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S1")).thenReturn(sessionQuery);
            service.ingestRefund(dto);
            Refund refund = createdRefund.constructed().get(0);
            assertEquals("R1", refund.refundNumber);
            assertEquals("T1", refund.terminalId);
            assertEquals(Refund.RefundStatus.CLOSED, refund.status);
            assertEquals(Refund.RefundMethod.CASH, refund.refundMethod);
            assertEquals(7L, refund.originalTicketId);
            assertSame(session, refund.session);
            assertEquals(LocalDateTime.of(2026, 1, 1, 14, 0, 0), refund.creationDate);
            assertEquals(1, refund.lines.size());
            RefundLine line = createdLines.constructed().get(0);
            assertSame(line, refund.lines.get(0));
            assertEquals(42L, line.originalLineId);
            assertEquals("Pomme", line.productLabel);
            assertEquals(new BigDecimal("5.00"), line.price);
            verify(refund, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestRefund}: an existing refund is
     * refreshed and persisted (created ternary false arm), with no lines
     * replayed (loop exit arm).
     */
    @Test
    void ingestRefundUpdatesExisting() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R2";
        dto.terminalId = "T2";
        dto.status = "CLOSED";
        dto.refundMethod = "CARD";
        dto.originalTicketNumber = "K2";
        dto.sessionNumber = "S2";
        dto.creationDate = "2026-02-02T15:00:00";
        dto.totalAmount = new BigDecimal("9.00");
        Ticket original = mock(Ticket.class);
        original.id = 8L;
        Refund existing = mock(Refund.class);
        existing.lines = new ArrayList<>();
        CashSession session = mock(CashSession.class);
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(existing);
        PanacheQuery<CashSession> sessionQuery = queryReturning(session);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K2")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R2")).thenReturn(refundQuery);
            mocked.when(() -> CashSession.find("sessionNumber", "S2")).thenReturn(sessionQuery);
            service.ingestRefund(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(Refund.RefundMethod.CARD, existing.refundMethod);
            assertEquals(8L, existing.originalTicketId);
            assertSame(session, existing.session);
            verify(existing, times(1)).persist();
        }
    }

    /**
     * Covers the missing-original throw arm of {@code ingestRefund}: an absent
     * original ticket raises {@link IllegalStateException}.
     */
    @Test
    void ingestRefundThrowsWhenOriginalTicketMissing() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R3";
        dto.originalTicketNumber = "K9";
        PanacheQuery<Ticket> ticketQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K9")).thenReturn(ticketQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestRefund(dto));
        }
    }

    /**
     * Covers the missing-line throw arm of {@code ingestRefund} through the
     * null-uid path: a null refund method (ternary false arm), a null session
     * (ternary false arm) and a refund line with a null original uid
     * (original-line ternary false arm, missing-line guard true arm) raise
     * {@link IllegalStateException}.
     */
    @Test
    void ingestRefundThrowsWhenOriginalLineMissing() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.RefundDto dto = new SyncPayloads.RefundDto();
        dto.refundNumber = "R4";
        dto.terminalId = "T4";
        dto.status = "CLOSED";
        dto.refundMethod = null;
        dto.originalTicketNumber = "K4";
        dto.sessionNumber = null;
        dto.creationDate = "2026-04-04T16:00:00";
        dto.totalAmount = new BigDecimal("3.00");
        SyncPayloads.RefundLineDto lineDto = new SyncPayloads.RefundLineDto();
        lineDto.originalLineUid = null;
        dto.lines.add(lineDto);
        Ticket original = mock(Ticket.class);
        original.id = 9L;
        original.lines = new ArrayList<>();
        PanacheQuery<Ticket> ticketQuery = queryReturning(original);
        PanacheQuery<Refund> refundQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Refund> createdRefund = mockConstruction(Refund.class,
                        (m, c) -> m.lines = new ArrayList<>());
                MockedConstruction<RefundLine> createdLines = mockConstruction(RefundLine.class)) {
            mocked.when(() -> Ticket.find("ticketNumber", "K4")).thenReturn(ticketQuery);
            mocked.when(() -> Refund.find("refundNumber", "R4")).thenReturn(refundQuery);
            assertThrows(IllegalStateException.class, () -> service.ingestRefund(dto));
        }
    }

    // --------------------------------------------------
    // ingestEvent
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code ingestEvent}: no event exists for the uid
     * so one is constructed, stamped and persisted.
     */
    @Test
    void ingestEventCreatesWhenAbsent() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = "EV1";
        dto.terminalId = "T1";
        dto.type = "TICKET_CLOSED";
        dto.detail = "detail";
        dto.eventDate = "2026-01-01T17:00:00";
        PanacheQuery<TechnicalEvent> eventQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TechnicalEvent> created = mockConstruction(TechnicalEvent.class)) {
            mocked.when(() -> TechnicalEvent.find("eventUid", "EV1")).thenReturn(eventQuery);
            service.ingestEvent(dto);
            TechnicalEvent event = created.constructed().get(0);
            assertEquals("EV1", event.eventUid);
            assertEquals("T1", event.terminalId);
            assertEquals(TechnicalEvent.EventType.TICKET_CLOSED, event.eventType);
            assertEquals("detail", event.detail);
            assertEquals(LocalDateTime.of(2026, 1, 1, 17, 0, 0), event.eventDate);
            verify(event, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code ingestEvent}: an event already exists for
     * the uid so it is refreshed and persisted.
     */
    @Test
    void ingestEventUpdatesWhenPresent() {
        SyncIngestService service = new SyncIngestService();
        SyncPayloads.EventDto dto = new SyncPayloads.EventDto();
        dto.eventUid = "EV2";
        dto.terminalId = "T2";
        dto.type = "TICKET_CANCELLED";
        dto.detail = null;
        dto.eventDate = "2026-02-02T18:00:00";
        TechnicalEvent existing = mock(TechnicalEvent.class);
        PanacheQuery<TechnicalEvent> eventQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TechnicalEvent.find("eventUid", "EV2")).thenReturn(eventQuery);
            service.ingestEvent(dto);
            assertEquals("T2", existing.terminalId);
            assertEquals(TechnicalEvent.EventType.TICKET_CANCELLED, existing.eventType);
            assertNull(existing.detail);
            assertEquals(LocalDateTime.of(2026, 2, 2, 18, 0, 0), existing.eventDate);
            verify(existing, times(1)).persist();
        }
    }
}
