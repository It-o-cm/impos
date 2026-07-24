package com.intermarche.pos.service;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.service.sync.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cash session lifecycle of this register: opening with an initial float,
 * read-only X snapshot, Z closing with counted amount, variance and
 * withdrawal. Sessions live in the register's own database, so they survive
 * register restarts by construction.
 * <p>
 * The theoretical cash is the opening float plus the cash payments of the
 * closed tickets of the session, minus its cash refunds.
 * <p>
 * Reports are computed from the DATABASE, never from in-memory state: an X
 * or Z after a register restart is exact by construction. The Z closing has
 * two side effects beyond the session itself: the register's PARKED tickets
 * are cancelled (each pushed to the store node), and the session is pushed
 * a second time — it was already pushed at opening, the closing push
 * completes the same row by number upsert, so consolidated tickets can
 * reference their session from the first minute.
 */
@ApplicationScoped
public class CashSessionService {

    private static final Logger LOG = Logger.getLogger(CashSessionService.class);

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Read-only snapshot of a session (X report content), also used as the
     * body of the Z report once the session is closed.
     */
    public static class SessionReport {
        /** The reported session. */
        public CashSession session;
        /** The number of closed tickets of the session. */
        public int ticketCount;
        /** The tax-included revenue of the closed tickets. */
        public BigDecimal totalIncludingTax = BigDecimal.ZERO;
        /** The payment totals per method key, in first-seen order. */
        public Map<String, BigDecimal> totalsByMethod = new LinkedHashMap<>();
        /** The theoretical cash in the drawer (float + cash payments - cash refunds). */
        public BigDecimal theoreticalCash = BigDecimal.ZERO;
        /** The total refunded during the session, all methods. */
        public BigDecimal totalRefunds = BigDecimal.ZERO;
        /** True when this report closes the session (Z), false for an X snapshot. */
        public boolean closing;
    }

    /**
     * Returns the open session of this register, or null when none is open.
     *
     * @return the open session, or null
     */
    public CashSession getOpenSession() {
        return CashSession.findOpenByTerminal(ticketNumberService.getTerminalId());
    }

    /**
     * Opens a new session for this register with its initial float.
     *
     * @param cashierId the id of the cashier opening the session
     * @param openingFloat the initial cash float placed in the drawer
     * @return the opened session, or null when one is already open or the
     *         cashier cannot be resolved
     */
    @Transactional
    public CashSession openSession(Long cashierId, BigDecimal openingFloat) {
        if (getOpenSession() != null) {
            LOG.warn("Ouverture refusée : une session est déjà ouverte sur cette caisse");
            return null;
        }
        Employee cashier = (cashierId != null) ? Employee.findById(cashierId) : null;
        if (cashier == null) {
            LOG.error("Ouverture refusée : caissier introuvable");
            return null;
        }
        CashSession session = new CashSession();
        session.sessionNumber = ticketNumberService.nextSessionNumber();
        session.terminalId = ticketNumberService.getTerminalId();
        session.status = CashSession.SessionStatus.OPEN;
        session.openingDate = LocalDateTime.now();
        session.openingCashier = cashier;
        session.openingFloat = openingFloat != null ? openingFloat : BigDecimal.ZERO;
        session.persist();
        technicalEventService.log(TechnicalEvent.EventType.SESSION_OPENED,
                session.sessionNumber + " fond " + session.openingFloat.toPlainString());
        syncOutboxService.enqueue(SyncOutbox.EntityType.SESSION, session.id);
        return session;
    }

    /**
     * Builds the read-only snapshot of a session: closed-ticket count and
     * revenue, per-method payment totals and theoretical cash.
     *
     * @param session the session to report on
     * @return the report content
     */
    @Transactional
    public SessionReport buildReport(CashSession session) {
        SessionReport report = new SessionReport();
        report.session = session;

        List<Ticket> tickets = Ticket.list("session = ?1 and status = ?2",
                session, Ticket.TicketStatus.CLOSED);
        report.ticketCount = tickets.size();

        BigDecimal cashTotal = BigDecimal.ZERO;
        for (Ticket ticket : tickets) {
            report.totalIncludingTax = report.totalIncludingTax.add(ticket.totalIncludingTax);
            for (TicketPayment payment : ticket.payments) {
                String key = payment.getMethodKey();
                report.totalsByMethod.merge(key, payment.amount, BigDecimal::add);
                if ("CASH".equals(key)) {
                    cashTotal = cashTotal.add(payment.amount);
                }
            }
        }
        // Refunds of the session: all methods reported, cash ones lower the drawer
        BigDecimal cashRefunds = BigDecimal.ZERO;
        List<Refund> refunds = Refund.list("session", session);
        for (Refund refund : refunds) {
            report.totalRefunds = report.totalRefunds.add(refund.totalAmount);
            if (refund.refundMethod == Refund.RefundMethod.CASH) {
                cashRefunds = cashRefunds.add(refund.totalAmount);
            }
        }
        report.theoreticalCash = session.openingFloat.add(cashTotal).subtract(cashRefunds)
                .setScale(2, RoundingMode.HALF_UP);
        return report;
    }

    /**
     * Closes the open session of this register (Z report): stores the counted
     * amount, the denominations detail, the theoretical amount, the variance
     * and the withdrawal, then marks the session closed and journals it.
     *
     * @param cashierId the id of the cashier closing the session
     * @param countedAmount the cash amount counted in the drawer
     * @param withdrawnAmount the cash withdrawn from the drawer
     * @param countDetail the denominations detail as entered (JSON), or null
     * @return the closing report, or null when no session is open
     */
    @Transactional
    public SessionReport closeSession(Long cashierId, BigDecimal countedAmount,
                                      BigDecimal withdrawnAmount, String countDetail) {
        CashSession session = getOpenSession();
        if (session == null) {
            LOG.warn("Clôture refusée : aucune session ouverte sur cette caisse");
            return null;
        }

        SessionReport report = buildReport(session);
        report.closing = true;

        // Parked tickets never resumed die with the session
        List<Ticket> parked = Ticket.list("terminalId = ?1 and status = ?2",
                session.terminalId, Ticket.TicketStatus.PARKED);
        for (Ticket ticket : parked) {
            ticket.status = Ticket.TicketStatus.CANCELLED;
            ticket.persist();
            syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
        }
        if (!parked.isEmpty()) {
            technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED,
                    parked.size() + " ticket(s) en attente annulé(s) à la clôture");
        }

        session.closingDate = LocalDateTime.now();
        session.closingCashier = (cashierId != null) ? Employee.findById(cashierId) : null;
        session.countedAmount = countedAmount != null ? countedAmount : BigDecimal.ZERO;
        session.theoreticalAmount = report.theoreticalCash;
        session.variance = session.countedAmount.subtract(session.theoreticalAmount);
        session.withdrawnAmount = withdrawnAmount != null ? withdrawnAmount : BigDecimal.ZERO;
        session.countDetail = countDetail;
        session.status = CashSession.SessionStatus.CLOSED;
        session.persist();

        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                session.sessionNumber + " écart " + session.variance.toPlainString());
        syncOutboxService.enqueue(SyncOutbox.EntityType.SESSION, session.id);
        return report;
    }
}
