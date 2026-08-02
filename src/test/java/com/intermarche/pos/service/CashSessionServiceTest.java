package com.intermarche.pos.service;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.service.sync.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashSessionService}.
 * <p>
 * The service reads and writes exclusively through Panache active-record static
 * access ({@code CashSession.find}, {@code Employee.findById}, {@code Ticket.list},
 * {@code Refund.list}); all of it is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}, and the
 * {@code new CashSession()} of the opening path is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} so its {@code persist()} is a
 * no-op. No database and no Quarkus context are booted. The three collaborators
 * ({@link TicketNumberService}, {@link TechnicalEventService},
 * {@link SyncOutboxService}) are Mockito mocks assigned to the package-private
 * injection fields. Every branch of the four public methods is covered:
 * opening (already-open, null cashier id, unknown cashier, float present, float
 * defaulted), report building (empty vs. populated, cash vs. non-cash payment,
 * cash vs. non-cash refund) and closing (no session, parked present with
 * cashier and amounts, parked absent with null cashier and null amounts).
 */
class CashSessionServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the three collaborators mocked and its
     * terminal id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private CashSessionService newService() {
        CashSessionService service = new CashSessionService();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Creates a mocked Panache query whose {@code firstResult} resolves to the
     * given session (or {@code null} to simulate no open session).
     *
     * @param result the session the query must return, possibly {@code null}
     * @return the configured mocked query
     */
    private PanacheQuery<CashSession> queryReturning(CashSession result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<CashSession> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Stubs {@code CashSession.find(...)} on the given static mock so that the
     * open-session lookup of this register resolves to the given session.
     *
     * @param mocked the active Panache static mock
     * @param result the open session to return, possibly {@code null}
     */
    private void stubOpenSession(MockedStatic<PanacheEntityBase> mocked, CashSession result) {
        PanacheQuery<CashSession> query = queryReturning(result);
        mocked.when(() -> CashSession.find("terminalId = ?1 and status = ?2",
                TERMINAL, CashSession.SessionStatus.OPEN)).thenReturn(query);
    }

    /**
     * Creates a mocked payment with the given method key and amount.
     *
     * @param key the method key returned by {@code getMethodKey}
     * @param amount the applied amount
     * @return the configured mocked payment
     */
    private TicketPayment payment(String key, String amount) {
        TicketPayment payment = mock(TicketPayment.class);
        when(payment.getMethodKey()).thenReturn(key);
        payment.amount = new BigDecimal(amount);
        return payment;
    }

    /**
     * Verifies that {@code getOpenSession} returns the session resolved by the
     * terminal-scoped finder.
     */
    @Test
    void getOpenSessionReturnsFinderResult() {
        CashSession session = mock(CashSession.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            assertSame(session, newService().getOpenSession());
        }
    }

    /**
     * Covers the guard arm of {@code openSession}: a session is already open, so
     * the opening is refused and nothing is created, logged or enqueued.
     */
    @Test
    void openSessionRefusedWhenAlreadyOpen() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, mock(CashSession.class));
            assertNull(service.openSession(7L, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the null arm of the cashier-id ternary of {@code openSession}: a
     * null id resolves to a null cashier and the opening is refused.
     */
    @Test
    void openSessionRefusedWhenCashierIdNull() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            assertNull(service.openSession(null, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the non-null cashier-id arm with an unknown cashier: the finder
     * returns null and the opening is refused.
     */
    @Test
    void openSessionRefusedWhenCashierNotFound() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(null);
            assertNull(service.openSession(7L, new BigDecimal("50.00")));
            assertEquals(0, created.constructed().size());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the nominal opening with a provided float (non-null float arm):
     * the session is created, seeded, persisted, journaled and enqueued.
     */
    @Test
    void openSessionSucceedsWithProvidedFloat() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        when(service.ticketNumberService.nextSessionNumber()).thenReturn("C04-S00012");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(cashier);
            CashSession result = service.openSession(7L, new BigDecimal("50.00"));
            assertEquals(1, created.constructed().size());
            assertSame(created.constructed().get(0), result);
            assertEquals("C04-S00012", result.sessionNumber);
            assertEquals(TERMINAL, result.terminalId);
            assertEquals(CashSession.SessionStatus.OPEN, result.status);
            assertSame(cashier, result.openingCashier);
            assertEquals(0, new BigDecimal("50.00").compareTo(result.openingFloat));
            verify(result, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.SESSION_OPENED, "C04-S00012 fond 50.00");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.SESSION, result.id);
        }
    }

    /**
     * Covers the null arm of the float ternary of {@code openSession}: a null
     * float is stored as zero.
     */
    @Test
    void openSessionDefaultsFloatToZeroWhenNull() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        when(service.ticketNumberService.nextSessionNumber()).thenReturn("C04-S00012");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CashSession> created = mockConstruction(CashSession.class)) {
            stubOpenSession(mocked, null);
            mocked.when(() -> Employee.findById(7L)).thenReturn(cashier);
            CashSession result = service.openSession(7L, null);
            assertEquals(0, BigDecimal.ZERO.compareTo(result.openingFloat));
        }
    }

    /**
     * Covers the empty-session report: no closed tickets and no refunds, so the
     * theoretical cash equals the opening float and the totals stay empty.
     */
    @Test
    void buildReportEmptySession() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("50.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            CashSessionService.SessionReport report = service.buildReport(session);
            assertSame(session, report.session);
            assertEquals(0, report.ticketCount);
            assertEquals(0, BigDecimal.ZERO.compareTo(report.totalIncludingTax));
            assertTrue(report.totalsByMethod.isEmpty());
            assertEquals(0, BigDecimal.ZERO.compareTo(report.totalRefunds));
            assertEquals(0, new BigDecimal("50.00").compareTo(report.theoreticalCash));
            assertFalse(report.closing);
        }
    }

    /**
     * Covers the populated report: both arms of the {@code "CASH".equals(key)}
     * payment test and both arms of the cash-vs-non-cash refund test, so the
     * theoretical cash reflects only the cash payment minus the cash refund.
     */
    @Test
    void buildReportAggregatesPaymentsAndRefunds() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.openingFloat = new BigDecimal("100.00");
        Ticket ticket = new Ticket();
        ticket.totalIncludingTax = new BigDecimal("30.00");
        ticket.payments = List.of(payment("CASH", "20.00"), payment("CARD", "10.00"));
        Refund cashRefund = new Refund();
        cashRefund.totalAmount = new BigDecimal("5.00");
        cashRefund.refundMethod = Refund.RefundMethod.CASH;
        Refund cardRefund = new Refund();
        cardRefund.totalAmount = new BigDecimal("3.00");
        cardRefund.refundMethod = Refund.RefundMethod.CARD;
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of(ticket));
            mocked.when(() -> Refund.list("session", session))
                    .thenReturn(List.of(cashRefund, cardRefund));
            CashSessionService.SessionReport report = service.buildReport(session);
            assertEquals(1, report.ticketCount);
            assertEquals(0, new BigDecimal("30.00").compareTo(report.totalIncludingTax));
            assertEquals(0, new BigDecimal("20.00").compareTo(report.totalsByMethod.get("CASH")));
            assertEquals(0, new BigDecimal("10.00").compareTo(report.totalsByMethod.get("CARD")));
            assertEquals(0, new BigDecimal("8.00").compareTo(report.totalRefunds));
            assertEquals(0, new BigDecimal("115.00").compareTo(report.theoreticalCash));
        }
    }

    /**
     * Covers the guard arm of {@code closeSession}: no session is open, so the
     * closing is refused and nothing is journaled or enqueued.
     */
    @Test
    void closeSessionRefusedWhenNoOpenSession() {
        CashSessionService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, null);
            assertNull(service.closeSession(9L, new BigDecimal("120.00"),
                    new BigDecimal("40.00"), "{}"));
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the full closing with parked tickets present and a resolvable
     * closing cashier and non-null amounts: the parked tickets are cancelled
     * and enqueued, the cancellation is journaled, and the session records the
     * counted, theoretical, variance and withdrawal values before being closed
     * and pushed again.
     */
    @Test
    void closeSessionCancelsParkedAndRecordsCounts() {
        CashSessionService service = newService();
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("100.00");
        Ticket parked = mock(Ticket.class);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of(parked));
            mocked.when(() -> Employee.findById(9L)).thenReturn(cashier);
            CashSessionService.SessionReport report = service.closeSession(9L,
                    new BigDecimal("120.00"), new BigDecimal("40.00"), "{}");
            assertTrue(report.closing);
            assertEquals(Ticket.TicketStatus.CANCELLED, parked.status);
            verify(parked, times(1)).persist();
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, parked.id);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED,
                    "1 ticket(s) en attente annulé(s) à la clôture");
            assertSame(cashier, session.closingCashier);
            assertEquals(0, new BigDecimal("120.00").compareTo(session.countedAmount));
            assertEquals(0, new BigDecimal("100.00").compareTo(session.theoreticalAmount));
            assertEquals(0, new BigDecimal("20.00").compareTo(session.variance));
            assertEquals(0, new BigDecimal("40.00").compareTo(session.withdrawnAmount));
            assertEquals("{}", session.countDetail);
            assertEquals(CashSession.SessionStatus.CLOSED, session.status);
            verify(session, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.SESSION_CLOSED, "C04-S00012 écart 20.00");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.SESSION, session.id);
        }
    }

    /**
     * Covers the closing with no parked ticket, a null cashier id and null
     * amounts: the no-parked branch skips the cancellation journal, the closing
     * cashier stays null and the counted and withdrawal amounts default to zero.
     */
    @Test
    void closeSessionWithoutParkedAndNullInputs() {
        CashSessionService service = newService();
        CashSession session = mock(CashSession.class);
        session.sessionNumber = "C04-S00012";
        session.terminalId = TERMINAL;
        session.openingFloat = new BigDecimal("0.00");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            stubOpenSession(mocked, session);
            mocked.when(() -> Ticket.list("session = ?1 and status = ?2",
                    session, Ticket.TicketStatus.CLOSED)).thenReturn(List.of());
            mocked.when(() -> Refund.list("session", session)).thenReturn(List.of());
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(List.of());
            CashSessionService.SessionReport report = service.closeSession(null, null, null, null);
            assertTrue(report.closing);
            assertNull(session.closingCashier);
            assertEquals(0, BigDecimal.ZERO.compareTo(session.countedAmount));
            assertEquals(0, BigDecimal.ZERO.compareTo(session.withdrawnAmount));
            assertNull(session.countDetail);
            assertEquals(CashSession.SessionStatus.CLOSED, session.status);
            verify(service.technicalEventService, never()).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.TICKET_CANCELLED),
                    org.mockito.ArgumentMatchers.anyString());
            verify(service.technicalEventService).log(
                    org.mockito.ArgumentMatchers.eq(TechnicalEvent.EventType.SESSION_CLOSED),
                    org.mockito.ArgumentMatchers.anyString());
        }
    }
}
