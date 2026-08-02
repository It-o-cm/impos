package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncOutboxService}.
 * <p>
 * The service is a Panache active-record producer/reader: it enqueues rows,
 * drains them by static finder, serializes an entity graph to JSON through an
 * injected {@link ObjectMapper} and deletes/annotates rows by id. All static
 * access ({@code X.findById}, {@code SyncOutbox.find}, {@code deleteById})
 * resolves to {@link PanacheEntityBase} under plain {@code mvn test} and is
 * intercepted with {@link org.mockito.Mockito#mockStatic}; the single
 * {@code new SyncOutbox()} on the enqueue path is neutralized with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is
 * inert. Loaded entities are real POJO instances (never persisted here, only
 * read), and a real {@link ObjectMapper} performs and reverses the
 * serialization so the private {@code toDto}/{@code iso} mappings are asserted
 * on absolute round-tripped values; the failure branch swaps in a mapper mock
 * that throws. Since {@code findById} of every entity resolves to the same
 * {@code PanacheEntityBase.findById}, ids are kept distinct across types within
 * a test so stubs never collide. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration — every decision point, both arms: {@code isEnabled}
 * (present/absent, blank/non-blank); {@code getStoreUrl} (disabled arm,
 * trailing-slash ternary both arms); {@code enqueue} (disabled short-circuit,
 * null-id arm, persist arm); {@code prepare} (row-null arm, the four switch
 * cases each with entity-present/entity-gone arms, and the serialization
 * catch); {@code markFailure} (row-null arm, error-null and error-non-null
 * arms of the {@code &&}, and the length &gt; 255 ternary both arms);
 * {@code markSuccess}/{@code markGone}/{@code nextBatchIds} (no decision);
 * and the private mappers reached through {@code prepare} — {@code toDto}
 * cashier/store/session/refund-method/original-ticket/original-line ternaries
 * both arms, line and payment loops empty and non-empty, the
 * {@code instanceof CashPayment}/{@code VoucherPayment} arms, and {@code iso}
 * null and non-null.
 */
class SyncOutboxServiceTest {

    /**
     * Builds a service enabled with the given store URL and a real
     * {@link ObjectMapper} for serialization round-trips.
     *
     * @param url the configured store URL
     * @return the enabled service
     */
    private SyncOutboxService enabledService(String url) {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of(url);
        service.objectMapper = new ObjectMapper();
        return service;
    }

    // --------------------------------------------------
    // isEnabled / getStoreUrl
    // --------------------------------------------------

    /**
     * Covers the absent-URL arm of {@code isEnabled}: no configured URL yields
     * disabled.
     */
    @Test
    void isEnabledReturnsFalseWhenUrlAbsent() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.empty();
        assertFalse(service.isEnabled());
    }

    /**
     * Covers the blank-URL arm of {@code isEnabled}: a present but blank URL
     * yields disabled (isBlank true arm).
     */
    @Test
    void isEnabledReturnsFalseWhenUrlBlank() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of("   ");
        assertFalse(service.isEnabled());
    }

    /**
     * Covers the enabled arm of {@code isEnabled}: a present non-blank URL
     * yields enabled (isPresent and isBlank-false arms).
     */
    @Test
    void isEnabledReturnsTrueWhenUrlPresent() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of("http://store");
        assertTrue(service.isEnabled());
    }

    /**
     * Covers the disabled arm of {@code getStoreUrl}: it returns an empty
     * string when synchronization is off.
     */
    @Test
    void getStoreUrlReturnsEmptyWhenDisabled() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.empty();
        assertEquals("", service.getStoreUrl());
    }

    /**
     * Covers the trailing-slash true arm of {@code getStoreUrl}: the slash is
     * stripped after trimming.
     */
    @Test
    void getStoreUrlStripsTrailingSlash() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of(" http://store/ ");
        assertEquals("http://store", service.getStoreUrl());
    }

    /**
     * Covers the trailing-slash false arm of {@code getStoreUrl}: a URL without
     * a trailing slash is returned as-is after trimming.
     */
    @Test
    void getStoreUrlKeepsUrlWithoutTrailingSlash() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of(" http://store ");
        assertEquals("http://store", service.getStoreUrl());
    }

    // --------------------------------------------------
    // enqueue
    // --------------------------------------------------

    /**
     * Covers the disabled short-circuit arm of {@code enqueue}: nothing is
     * constructed when synchronization is off.
     */
    @Test
    void enqueueDoesNothingWhenDisabled() {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.empty();
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.TICKET, 5L);
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Covers the null-id arm of {@code enqueue}: an enabled service enqueues
     * nothing when the entity id is null.
     */
    @Test
    void enqueueDoesNothingWhenEntityIdNull() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.TICKET, null);
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * Covers the persist arm of {@code enqueue}: an enabled service with a
     * non-null id constructs, stamps and persists a row.
     */
    @Test
    void enqueuePersistsRow() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedConstruction<SyncOutbox> created = mockConstruction(SyncOutbox.class)) {
            service.enqueue(SyncOutbox.EntityType.SESSION, 42L);
            assertEquals(1, created.constructed().size());
            SyncOutbox row = created.constructed().get(0);
            assertEquals(SyncOutbox.EntityType.SESSION, row.entityType);
            assertEquals(42L, row.entityId);
            assertTrue(row.createdAt != null);
            verify(row, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // nextBatchIds
    // --------------------------------------------------

    /**
     * Covers {@code nextBatchIds}: the paged query result is mapped to its row
     * ids in order.
     */
    @Test
    @SuppressWarnings("unchecked")
    void nextBatchIdsMapsRowsToIds() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox first = new SyncOutbox();
        first.id = 3L;
        SyncOutbox second = new SyncOutbox();
        second.id = 7L;
        PanacheQuery<SyncOutbox> query = mock(PanacheQuery.class);
        when(query.page(0, 10)).thenReturn(query);
        when(query.list()).thenReturn(List.of(first, second));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.find("order by entityType, id")).thenReturn(query);
            List<Long> ids = service.nextBatchIds(10);
            assertEquals(List.of(3L, 7L), ids);
        }
    }

    // --------------------------------------------------
    // prepare
    // --------------------------------------------------

    /**
     * Covers the row-null arm of {@code prepare}: a vanished outbox row yields
     * null.
     */
    @Test
    void prepareReturnsNullWhenRowMissing() {
        SyncOutboxService service = enabledService("http://store");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the SESSION case of {@code prepare} with a fully populated session:
     * both cashier ternary true arms and the {@code iso} non-null arm for both
     * dates; the payload is serialized and round-trips to the expected values.
     */
    @Test
    void prepareSessionSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        session.terminalId = "T1";
        session.status = CashSession.SessionStatus.CLOSED;
        session.openingDate = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        session.closingDate = LocalDateTime.of(2026, 1, 1, 18, 0, 0);
        Employee opening = new Employee();
        opening.loginName = "alice";
        Employee closing = new Employee();
        closing.loginName = "bob";
        session.openingCashier = opening;
        session.closingCashier = closing;
        session.openingFloat = new BigDecimal("50.00");
        session.countDetail = "detail";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("session", item.pathSuffix);
            SyncPayloads.SessionDto out = new ObjectMapper().readValue(item.json, SyncPayloads.SessionDto.class);
            assertEquals("S1", out.sessionNumber);
            assertEquals("CLOSED", out.status);
            assertEquals("2026-01-01T08:00:00", out.openingDate);
            assertEquals("2026-01-01T18:00:00", out.closingDate);
            assertEquals("alice", out.openingCashierLogin);
            assertEquals("bob", out.closingCashierLogin);
        }
    }

    /**
     * Covers the SESSION case of {@code prepare} with null cashiers and null
     * dates: both cashier ternary false arms and the {@code iso} null arm.
     */
    @Test
    void prepareSessionSerializesWithNulls() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S2";
        session.terminalId = "T2";
        session.status = CashSession.SessionStatus.OPEN;
        session.openingDate = null;
        session.closingDate = null;
        session.openingCashier = null;
        session.closingCashier = null;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.SessionDto out = new ObjectMapper().readValue(item.json, SyncPayloads.SessionDto.class);
            assertEquals("S2", out.sessionNumber);
            assertNull(out.openingDate);
            assertNull(out.closingDate);
            assertNull(out.openingCashierLogin);
            assertNull(out.closingCashierLogin);
        }
    }

    /**
     * Covers the entity-gone arm of the SESSION case: the outbox row exists but
     * its session vanished, so {@code prepare} yields null.
     */
    @Test
    void prepareSessionReturnsNullWhenSessionGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the TICKET case of {@code prepare} with a full graph: store,
     * cashier and session ternary true arms, one line, a cash payment
     * ({@code instanceof CashPayment} true, {@code instanceof VoucherPayment}
     * false) and a voucher payment (the mirrored arms), and both {@code iso}
     * date arms; the payload round-trips to the expected values.
     */
    @Test
    void prepareTicketSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K1";
        ticket.terminalId = "T1";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.valuationStatus = Ticket.ValuationStatus.VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        ticket.closingDate = LocalDateTime.of(2026, 1, 1, 10, 5, 0);
        Store store = new Store();
        store.code = "ST1";
        ticket.store = store;
        Employee cashier = new Employee();
        cashier.loginName = "alice";
        ticket.cashier = cashier;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        ticket.session = session;
        ticket.itemCount = 2;
        ticket.totalExcludingTax = new BigDecimal("10.00");
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.lineUid = "U1";
        line.ean = "E1";
        line.productLabel = "Pomme";
        line.quantity = new BigDecimal("1");
        line.unitPrice = new BigDecimal("12.00");
        line.totalPrice = new BigDecimal("12.00");
        ticket.lines.add(line);
        CashPayment cash = mock(CashPayment.class);
        when(cash.getMethodKey()).thenReturn("CASH");
        cash.paymentIndex = 1;
        cash.amount = new BigDecimal("7.00");
        cash.tenderedAmount = new BigDecimal("10.00");
        VoucherPayment voucher = mock(VoucherPayment.class);
        when(voucher.getMethodKey()).thenReturn("VOUCHER");
        voucher.paymentIndex = 2;
        voucher.amount = new BigDecimal("5.00");
        voucher.voucherLabel = "Bon";
        voucher.voucherNumber = "V9";
        ticket.payments.add(cash);
        ticket.payments.add(voucher);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("ticket", item.pathSuffix);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals("K1", out.ticketNumber);
            assertEquals("VALUATED", out.valuationStatus);
            assertEquals("2026-01-01T10:00:00", out.creationDate);
            assertEquals("2026-01-01T10:05:00", out.closingDate);
            assertEquals("ST1", out.storeCode);
            assertEquals("alice", out.cashierLogin);
            assertEquals("S1", out.sessionNumber);
            assertEquals(1, out.lines.size());
            assertEquals("U1", out.lines.get(0).lineUid);
            assertEquals(2, out.payments.size());
            assertEquals("CASH", out.payments.get(0).methodKey);
            assertEquals(new BigDecimal("10.00"), out.payments.get(0).tenderedAmount);
            assertNull(out.payments.get(0).voucherLabel);
            assertEquals("VOUCHER", out.payments.get(1).methodKey);
            assertEquals("Bon", out.payments.get(1).voucherLabel);
            assertEquals("V9", out.payments.get(1).voucherNumber);
            assertNull(out.payments.get(1).tenderedAmount);
        }
    }

    /**
     * Covers the TICKET case of {@code prepare} with a lean graph: store,
     * cashier and session ternary false arms, empty line and payment loops, and
     * the {@code iso} null arm for the closing date.
     */
    @Test
    void prepareTicketSerializesLeanGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "K2";
        ticket.terminalId = "T2";
        ticket.status = Ticket.TicketStatus.CANCELLED;
        ticket.valuationStatus = Ticket.ValuationStatus.NOT_VALUATED;
        ticket.creationDate = LocalDateTime.of(2026, 2, 2, 11, 0, 0);
        ticket.closingDate = null;
        ticket.store = null;
        ticket.cashier = null;
        ticket.session = null;
        ticket.itemCount = 0;
        ticket.totalExcludingTax = new BigDecimal("0.00");
        ticket.totalIncludingTax = new BigDecimal("0.00");
        ticket.totalVat = new BigDecimal("0.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(ticket);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.TicketDto out = new ObjectMapper().readValue(item.json, SyncPayloads.TicketDto.class);
            assertEquals("K2", out.ticketNumber);
            assertNull(out.storeCode);
            assertNull(out.cashierLogin);
            assertNull(out.sessionNumber);
            assertNull(out.closingDate);
            assertTrue(out.lines.isEmpty());
            assertTrue(out.payments.isEmpty());
        }
    }

    /**
     * Covers the entity-gone arm of the TICKET case: a vanished ticket yields
     * null.
     */
    @Test
    void prepareTicketReturnsNullWhenTicketGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.TICKET;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Ticket.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the REFUND case of {@code prepare} with a full graph: refund-method
     * ternary true arm, resolved original ticket (original ternary true arm),
     * session ternary true arm, and one line whose original line resolves
     * (original-line ternary true arm); the payload round-trips as expected.
     */
    @Test
    void prepareRefundSerializesFullGraph() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        Refund refund = new Refund();
        refund.refundNumber = "R1";
        refund.terminalId = "T1";
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = Refund.RefundMethod.CASH;
        refund.originalTicketId = 201L;
        refund.creationDate = LocalDateTime.of(2026, 1, 1, 14, 0, 0);
        refund.totalAmount = new BigDecimal("5.00");
        refund.totalExcludingTax = new BigDecimal("4.00");
        refund.totalVat = new BigDecimal("1.00");
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        refund.session = session;
        RefundLine refundLine = new RefundLine();
        refundLine.originalLineId = 202L;
        refundLine.productLabel = "Pomme";
        refundLine.quantity = new BigDecimal("1");
        refundLine.price = new BigDecimal("5.00");
        refundLine.vatRate = new BigDecimal("0.2000");
        refund.lines.add(refundLine);
        Ticket original = new Ticket();
        original.ticketNumber = "K1";
        TicketLine originalLine = new TicketLine();
        originalLine.lineUid = "U1";
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(201L)).thenReturn(original);
            mocked.when(() -> TicketLine.findById(202L)).thenReturn(originalLine);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("refund", item.pathSuffix);
            SyncPayloads.RefundDto out = new ObjectMapper().readValue(item.json, SyncPayloads.RefundDto.class);
            assertEquals("R1", out.refundNumber);
            assertEquals("CASH", out.refundMethod);
            assertEquals("K1", out.originalTicketNumber);
            assertEquals("S1", out.sessionNumber);
            assertEquals("2026-01-01T14:00:00", out.creationDate);
            assertEquals(1, out.lines.size());
            assertEquals("U1", out.lines.get(0).originalLineUid);
        }
    }

    /**
     * Covers the REFUND case of {@code prepare} with nulls: refund-method
     * ternary false arm, vanished original ticket (original ternary false arm),
     * null session (session ternary false arm) and a line whose original line
     * vanished (original-line ternary false arm).
     */
    @Test
    void prepareRefundSerializesWithNulls() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        Refund refund = new Refund();
        refund.refundNumber = "R2";
        refund.terminalId = "T2";
        refund.status = Refund.RefundStatus.CLOSED;
        refund.refundMethod = null;
        refund.originalTicketId = 201L;
        refund.creationDate = LocalDateTime.of(2026, 2, 2, 15, 0, 0);
        refund.totalAmount = new BigDecimal("3.00");
        refund.session = null;
        RefundLine refundLine = new RefundLine();
        refundLine.originalLineId = 202L;
        refundLine.productLabel = "Poire";
        refundLine.quantity = new BigDecimal("1");
        refundLine.price = new BigDecimal("3.00");
        refund.lines.add(refundLine);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(refund);
            mocked.when(() -> Ticket.findById(201L)).thenReturn(null);
            mocked.when(() -> TicketLine.findById(202L)).thenReturn(null);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            SyncPayloads.RefundDto out = new ObjectMapper().readValue(item.json, SyncPayloads.RefundDto.class);
            assertEquals("R2", out.refundNumber);
            assertNull(out.refundMethod);
            assertNull(out.originalTicketNumber);
            assertNull(out.sessionNumber);
            assertEquals(1, out.lines.size());
            assertNull(out.lines.get(0).originalLineUid);
        }
    }

    /**
     * Covers the entity-gone arm of the REFUND case: a vanished refund yields
     * null.
     */
    @Test
    void prepareRefundReturnsNullWhenRefundGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.REFUND;
        row.entityId = 200L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> Refund.findById(200L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the EVENT case of {@code prepare}: a present event with a non-null
     * date is serialized ({@code iso} non-null arm) and round-trips.
     */
    @Test
    void prepareEventSerializes() throws Exception {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.EVENT;
        row.entityId = 100L;
        TechnicalEvent event = new TechnicalEvent();
        event.eventUid = "EV1";
        event.terminalId = "T1";
        event.eventType = TechnicalEvent.EventType.TICKET_CLOSED;
        event.detail = "detail";
        event.eventDate = LocalDateTime.of(2026, 1, 1, 17, 0, 0);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> TechnicalEvent.findById(100L)).thenReturn(event);
            SyncOutboxService.PreparedItem item = service.prepare(1L);
            assertEquals("event", item.pathSuffix);
            SyncPayloads.EventDto out = new ObjectMapper().readValue(item.json, SyncPayloads.EventDto.class);
            assertEquals("EV1", out.eventUid);
            assertEquals("TICKET_CLOSED", out.type);
            assertEquals("detail", out.detail);
            assertEquals("2026-01-01T17:00:00", out.eventDate);
        }
    }

    /**
     * Covers the entity-gone arm of the EVENT case: a vanished event yields
     * null.
     */
    @Test
    void prepareEventReturnsNullWhenEventGone() {
        SyncOutboxService service = enabledService("http://store");
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.EVENT;
        row.entityId = 100L;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> TechnicalEvent.findById(100L)).thenReturn(null);
            assertNull(service.prepare(1L));
        }
    }

    /**
     * Covers the serialization catch arm of {@code prepare}: a mapper that
     * throws makes preparation return null rather than propagate.
     */
    @Test
    void prepareReturnsNullWhenSerializationFails() throws Exception {
        SyncOutboxService service = new SyncOutboxService();
        service.storeUrl = Optional.of("http://store");
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        service.objectMapper = mapper;
        SyncOutbox row = new SyncOutbox();
        row.entityType = SyncOutbox.EntityType.SESSION;
        row.entityId = 100L;
        CashSession session = new CashSession();
        session.sessionNumber = "S1";
        session.status = CashSession.SessionStatus.OPEN;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(1L)).thenReturn(row);
            mocked.when(() -> CashSession.findById(100L)).thenReturn(session);
            assertNull(service.prepare(1L));
        }
    }

    // --------------------------------------------------
    // markSuccess / markGone
    // --------------------------------------------------

    /**
     * Covers {@code markSuccess}: the row is deleted by id.
     */
    @Test
    void markSuccessDeletesRow() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.deleteById(5L)).thenReturn(true);
            service.markSuccess(5L);
            mocked.verify(() -> SyncOutbox.deleteById(5L));
        }
    }

    /**
     * Covers {@code markGone}: the row of a vanished entity is deleted by id.
     */
    @Test
    void markGoneDeletesRow() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.deleteById(6L)).thenReturn(true);
            service.markGone(6L);
            mocked.verify(() -> SyncOutbox.deleteById(6L));
        }
    }

    // --------------------------------------------------
    // markFailure
    // --------------------------------------------------

    /**
     * Covers the row-null arm of {@code markFailure}: a vanished row is a no-op.
     */
    @Test
    void markFailureDoesNothingWhenRowMissing() {
        SyncOutboxService service = new SyncOutboxService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(9L)).thenReturn(null);
            service.markFailure(9L, "error");
            mocked.verify(() -> SyncOutbox.findById(9L));
        }
    }

    /**
     * Covers the short-error arm of {@code markFailure}: a non-null error under
     * 256 chars is stored verbatim (error-non-null true arm, length false arm)
     * and the attempt counter is bumped.
     */
    @Test
    void markFailureRecordsShortError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(10L)).thenReturn(row);
            service.markFailure(10L, "boom");
            assertEquals(1, row.attempts);
            assertEquals("boom", row.lastError);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers the null-error arm of {@code markFailure}: a null error short-
     * circuits the {@code &&} (error-non-null false arm) and is stored as null.
     */
    @Test
    void markFailureAcceptsNullError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(11L)).thenReturn(row);
            service.markFailure(11L, null);
            assertEquals(1, row.attempts);
            assertNull(row.lastError);
            verify(row, times(1)).persist();
        }
    }

    /**
     * Covers the long-error arm of {@code markFailure}: an error over 255 chars
     * is truncated to 255 (length true arm).
     */
    @Test
    void markFailureTruncatesLongError() {
        SyncOutboxService service = new SyncOutboxService();
        SyncOutbox row = mock(SyncOutbox.class);
        String longError = "x".repeat(300);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> SyncOutbox.findById(12L)).thenReturn(row);
            service.markFailure(12L, longError);
            assertEquals(1, row.attempts);
            assertEquals(255, row.lastError.length());
            assertEquals(longError.substring(0, 255), row.lastError);
            verify(row, times(1)).persist();
        }
    }
}
