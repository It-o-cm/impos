package com.intermarche.pos.service;

import com.intermarche.pos.domain.CashMovement;
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
 * The theoretical cash is the opening float, plus the cash payments of the
 * closed tickets of the session, minus its cash refunds, plus the net of the
 * session's cash MOVEMENTS: deposits and customer down-payments add cash to
 * the drawer, withdrawals and expenses take it out, and a cash-count
 * declaration moves nothing (it is a witness, not a transfer). A session with
 * no movement is therefore strictly unchanged.
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
        /** The theoretical cash in the drawer (float + cash payments - cash refunds + net movements). */
        public BigDecimal theoreticalCash = BigDecimal.ZERO;
        /** The net cash impact of the session's movements (deposits/acomptes minus withdrawals/expenses). */
        public BigDecimal netCashMovements = BigDecimal.ZERO;
        /** The total refunded during the session, all methods. */
        public BigDecimal totalRefunds = BigDecimal.ZERO;
        /** True when this report closes the session (Z), false for an X snapshot. */
        public boolean closing;

        /**
         * One settlement line of the report: a payment method and its total.
         *
         * <p>The map above is what the report COMPUTES; this is what a screen and a
         * roll of paper both need — a label and an already-formatted amount. Deriving
         * it here rather than in each renderer is what keeps the printed X and the
         * displayed X from ever disagreeing.
         *
         * @param method the payment method key
         * @param amountFormatted the total, French format
         */
        public record MethodRow(String method, String amountFormatted) {
        }

        /**
         * Returns the settlement lines, in the order the methods were first seen.
         *
         * @return the settlement rows, possibly empty
         */
        public List<MethodRow> getMethodRows() {
            List<MethodRow> rows = new java.util.ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : totalsByMethod.entrySet()) {
                rows.add(new MethodRow(entry.getKey(), money(entry.getValue())));
            }
            return rows;
        }

        /**
         * Returns the tax-included revenue, French format.
         *
         * @return the formatted revenue
         */
        public String getTotalIncludingTaxFormatted() {
            return money(totalIncludingTax);
        }

        /**
         * Returns the theoretical cash in the drawer, French format.
         *
         * @return the formatted theoretical cash
         */
        public String getTheoreticalCashFormatted() {
            return money(theoreticalCash);
        }

        /**
         * Returns the total refunded during the session, French format.
         *
         * @return the formatted refund total
         */
        public String getTotalRefundsFormatted() {
            return money(totalRefunds);
        }

        /**
         * Returns the net cash impact of the session's movements, French format.
         *
         * @return the formatted net movement
         */
        public String getNetCashMovementsFormatted() {
            return money(netCashMovements);
        }

        /**
         * Formats an amount the way every screen and every ticket of the register
         * does, tolerating a missing value.
         *
         * @param amount the amount, possibly null
         * @return the amount with two decimals and a French comma
         */
        private static String money(BigDecimal amount) {
            BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
            return String.format("%.2f",
                    value.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
        }
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
        // Cash movements of the session: net impact on the drawer (a session
        // without any movement leaves the theoretical strictly unchanged)
        BigDecimal netMovements = BigDecimal.ZERO;
        List<CashMovement> movements = CashMovement.list("session = ?1", session);
        for (CashMovement movement : movements) {
            netMovements = netMovements.add(cashImpact(movement));
        }
        // LC-12-03 and LC-12-10: the theoretical of EACH tender follows its movements
        // too, not only the cash. A withdrawal of cheques lowers the cheques, and a
        // transfer moves an amount from one tender to another — without this the
        // per-method breakdown would keep stating what the tickets said and ignore
        // everything the drawer did afterwards.
        for (CashMovement movement : movements) {
            applyMethodImpact(report.totalsByMethod, movement);
        }
        report.netCashMovements = netMovements;
        report.theoreticalCash = session.openingFloat.add(cashTotal).subtract(cashRefunds)
                .add(netMovements).setScale(2, RoundingMode.HALF_UP);
        return report;
    }

    /**
     * Returns the signed impact of a cash movement on the drawer: a deposit or
     * a customer down-payment adds cash, a withdrawal or an expense removes it,
     * a cash-count declaration moves nothing. A movement with a null amount has
     * no impact.
     *
     * @param movement the movement to weigh
     * @return the signed amount added to the drawer (negative when it removes cash)
     */
    private BigDecimal cashImpact(CashMovement movement) {
        if (movement.amount == null) {
            return BigDecimal.ZERO;
        }
        return switch (movement.type) {
            case DEPOSIT, CUSTOMER_DEPOSIT -> movement.amount;
            // LC-12-03-02: a withdrawal now names its tender. Only a withdrawal of
            // CASH lowers the cash in the drawer — taking the cheques out leaves the
            // notes where they are. A movement recorded before the tender existed
            // carries none, and those were all cash.
            case WITHDRAWAL -> isCash(movement.paymentMethod)
                    ? movement.amount.negate() : BigDecimal.ZERO;
            case EXPENSE -> movement.amount.negate();
            // LC-12-10-04: a transfer moves an amount from one tender to another. It
            // touches the cash only when cash is one of the two ends, and not at all
            // when it is both.
            case TRANSFER -> transferCashImpact(movement);
            case DECLARATION -> BigDecimal.ZERO;
        };
    }

    /**
     * Applies a movement to the per-tender theoretical of the drawer
     * ({@code LC-12-03-06}, {@code LC-12-10-04}).
     *
     * <p>Only the two movements that NAME a tender act here. A deposit, an expense or
     * a customer down-payment are cash gestures already carried by the cash
     * theoretical, and a declaration counts without moving anything.
     *
     * @param totals   the per-tender totals being built
     * @param movement the movement to apply
     */
    private void applyMethodImpact(Map<String, BigDecimal> totals, CashMovement movement) {
        if (movement.amount == null) {
            return;
        }
        String from = isCash(movement.paymentMethod) ? CashMovement.CASH : movement.paymentMethod;
        if (movement.type == CashMovement.MovementType.WITHDRAWAL) {
            totals.merge(from, movement.amount.negate(), BigDecimal::add);
            return;
        }
        if (movement.type == CashMovement.MovementType.TRANSFER && movement.transferTo != null) {
            totals.merge(from, movement.amount.negate(), BigDecimal::add);
            totals.merge(movement.transferTo, movement.amount, BigDecimal::add);
        }
    }

    /**
     * Returns the signed impact of a transfer on the CASH in the drawer.
     *
     * @param movement the transfer
     * @return the signed amount added to the cash, zero when neither end is cash
     */
    private BigDecimal transferCashImpact(CashMovement movement) {
        boolean fromCash = isCash(movement.paymentMethod);
        boolean toCash = isCash(movement.transferTo);
        if (fromCash == toCash) {
            return BigDecimal.ZERO;
        }
        return fromCash ? movement.amount.negate() : movement.amount;
    }

    /**
     * Tells whether a tender key names the cash, a missing key included.
     *
     * @param method the tender key, possibly null
     * @return true when the tender is the drawer's cash
     */
    private boolean isCash(String method) {
        return method == null || method.isBlank() || CashMovement.CASH.equals(method);
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
