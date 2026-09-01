package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of the electronic journal (BO-04-01, lot 4). It produces nothing:
 * it opens the consolidated data the transactional outbox already remitted —
 * tickets, lines, payments, refunds and {@link TechnicalEvent} — to a
 * multi-criteria search, a ticket detail, a functional-event search and a CSV
 * export that reproduces the list to the character (BO-04-01-50).
 * <p>
 * Runs over this node's own database, so it becomes the STORE journal when
 * opened on the consolidated node — the same node-local posture as the
 * supervision dashboard. Aggregation-free reads go through JPQL on the
 * {@link EntityManager} (the same choice the dashboard made): the query is
 * assembled from a {@link JournalCriteria} by pure builders — every criterion
 * is one AND-ed condition, absent criteria contribute nothing (BO-04-01-13,
 * the free combination) — and executed here one page at a time, each search
 * preceded by its own {@code count}, so a free search over a year of tickets
 * neither exhausts memory nor drops rows in silence: the screen always states
 * how many documents matched and which slice of them it shows.
 */
@ApplicationScoped
public class JournalService {

    /** The number of rows one page of a result list shows. */
    static final int PAGE_SIZE = 100;

    /** The number of rows one export query reads at a time. */
    static final int EXPORT_CHUNK = 1000;

    /** French date format for the list and export. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** French time format for the list and export. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    EntityManager entityManager;

    // --------------------------------------------------
    // Transactional journal
    // --------------------------------------------------

    /**
     * Builds the {@code where} clause of the transactional search from the
     * criteria. The document scope is fixed to finalized documents (CLOSED and
     * CANCELLED): drafts are never consolidated. Every other criterion is
     * optional and simply narrows the set.
     *
     * @param criteria the parsed search criteria
     * @return the assembled query fragment and its parameters
     */
    JournalQuery buildTicketQuery(JournalCriteria criteria) {
        JournalQuery query = new JournalQuery();
        query.and("t.status in :statuses");
        query.bind("statuses", List.of(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.CANCELLED));
        if (criteria.text != null) {
            query.and("(lower(t.ticketNumber) like :text or lower(t.fidelityCard) like :text"
                    + " or exists (select l from t.lines l where lower(l.productLabel) like :text))");
            query.bind("text", "%" + criteria.text.toLowerCase() + "%");
        }
        if (criteria.cashierMin != null) {
            query.and("t.cashier.badgeId >= :cashierMin");
            query.bind("cashierMin", criteria.cashierMin);
        }
        if (criteria.cashierMax != null) {
            query.and("t.cashier.badgeId <= :cashierMax");
            query.bind("cashierMax", criteria.cashierMax);
        }
        if (criteria.terminalMin != null) {
            query.and("t.terminalId >= :terminalMin");
            query.bind("terminalMin", criteria.terminalMin);
        }
        if (criteria.terminalMax != null) {
            query.and("t.terminalId <= :terminalMax");
            query.bind("terminalMax", criteria.terminalMax);
        }
        if (criteria.txMin != null) {
            query.and("t.ticketNumber >= :txMin");
            query.bind("txMin", criteria.txMin);
        }
        if (criteria.txMax != null) {
            query.and("t.ticketNumber <= :txMax");
            query.bind("txMax", criteria.txMax);
        }
        if (criteria.amountMin != null) {
            query.and("t.totalIncludingTax >= :amountMin");
            query.bind("amountMin", criteria.amountMin);
        }
        if (criteria.amountMax != null) {
            query.and("t.totalIncludingTax <= :amountMax");
            query.bind("amountMax", criteria.amountMax);
        }
        if (!criteria.methods.isEmpty()) {
            List<Class<?>> types = new ArrayList<>();
            for (String key : criteria.methods) {
                Class<?> type = PaymentTypes.forKey(key);
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                query.and("exists (select p from t.payments p where type(p) in :methodTypes)");
                query.bind("methodTypes", types);
            }
        }
        if (criteria.dateFrom != null) {
            query.and("t.creationDate >= :dateFrom");
            query.bind("dateFrom", criteria.dateFrom);
        }
        if (criteria.dateTo != null) {
            query.and("t.creationDate <= :dateTo");
            query.bind("dateTo", criteria.dateTo);
        }
        appendPluRange(query, criteria);
        if (criteria.vatRate != null) {
            query.and("exists (select l from t.lines l where l.vatRate = :vatRate)");
            query.bind("vatRate", criteria.vatRate);
        }
        appendReductionRange(query, criteria);
        appendAuthorizationRange(query, criteria);
        appendFlags(query, criteria);
        return query;
    }

    /**
     * Appends the article PLU range as a single line-scoped {@code exists}, so
     * both bounds bear on the same line rather than on any two lines.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendPluRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.pluMin == null && criteria.pluMax == null) {
            return;
        }
        StringBuilder inner = new StringBuilder("exists (select l from t.lines l where ");
        boolean first = true;
        if (criteria.pluMin != null) {
            inner.append("l.plu >= :pluMin");
            query.bind("pluMin", criteria.pluMin);
            first = false;
        }
        if (criteria.pluMax != null) {
            if (!first) {
                inner.append(" and ");
            }
            inner.append("l.plu <= :pluMax");
            query.bind("pluMax", criteria.pluMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the manual-reduction criterion (BO-04-01-26): at least one line
     * carries a manual gesture, optionally within an applied-amount range.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendReductionRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.reductionMin == null && criteria.reductionMax == null) {
            return;
        }
        StringBuilder inner = new StringBuilder(
                "exists (select l from t.lines l where l.modifierType is not null");
        if (criteria.reductionMin != null) {
            inner.append(" and l.modifierValue >= :reductionMin");
            query.bind("reductionMin", criteria.reductionMin);
        }
        if (criteria.reductionMax != null) {
            inner.append(" and l.modifierValue <= :reductionMax");
            query.bind("reductionMax", criteria.reductionMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends the card authorization-number range (BO-04-01-08): at least one
     * card payment on the ticket carries an authorization number within the
     * requested bounds. The {@code treat} downcast restricts the correlated
     * sub-select to card payments, and the {@code is not null} guard excludes
     * degraded acceptances (which reach no monetique and hold no number), so a
     * bound never silently matches them.
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendAuthorizationRange(JournalQuery query, JournalCriteria criteria) {
        if (criteria.authMin == null && criteria.authMax == null) {
            return;
        }
        StringBuilder inner = new StringBuilder(
                "exists (select p from t.payments p where treat(p as CardPayment).authorizationNumber is not null");
        if (criteria.authMin != null) {
            inner.append(" and treat(p as CardPayment).authorizationNumber >= :authMin");
            query.bind("authMin", criteria.authMin);
        }
        if (criteria.authMax != null) {
            inner.append(" and treat(p as CardPayment).authorizationNumber <= :authMax");
            query.bind("authMax", criteria.authMax);
        }
        inner.append(")");
        query.and(inner.toString());
    }

    /**
     * Appends one condition per selected boolean flag
     * (BO-04-01-14/25/31/32/34/46/47/49).
     *
     * @param query the query under construction
     * @param criteria the parsed criteria
     */
    private void appendFlags(JournalQuery query, JournalCriteria criteria) {
        if (criteria.flags.contains(JournalCriteria.Flag.CANCELLED)) {
            query.and("t.status = :cancelled");
            query.bind("cancelled", Ticket.TicketStatus.CANCELLED);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DISCOUNT)) {
            query.and("t.globalDiscountApplied is not null and t.globalDiscountApplied > 0");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.RETURN)) {
            query.and("exists (select r from Refund r where r.originalTicketId = t.id)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.NEGATIVE)) {
            query.and("t.totalIncludingTax < 0");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.ZERO_PRICE)) {
            query.and("exists (select l from t.lines l where l.totalPrice = 0)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.UNKNOWN_ITEM)) {
            query.and("exists (select l from t.lines l where l.product is null and l.deposit = false)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.VOUCHER)) {
            query.and("exists (select p from t.payments p where type(p) = :voucherType)");
            query.bind("voucherType", VoucherPayment.class);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.CARD)) {
            query.and("exists (select p from t.payments p where type(p) = :cardType)");
            query.bind("cardType", CardPayment.class);
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DEGRADED)) {
            query.and("exists (select p from t.payments p where treat(p as CardPayment).degradedMode = true)");
        }
        if (criteria.flags.contains(JournalCriteria.Flag.DEGRADED_MANUAL)) {
            query.and("exists (select p from t.payments p where treat(p as CardPayment).degradedMode = true)");
        }
    }

    /**
     * Runs the transactional search and returns the requested page of rows,
     * with the total number of matching documents. Ordering is the requested
     * column (BO-04-01-51) with a stable id tiebreak. The journal is a control
     * surface: the count is always taken, so the list can state what it is not
     * showing rather than truncating in silence.
     *
     * @param criteria the parsed search criteria, carrying the requested page
     * @return the requested page of rows and the total row count
     */
    public JournalPage<JournalRow> search(JournalCriteria criteria) {
        JournalQuery query = buildTicketQuery(criteria);
        long total = count("select count(t.id) from Ticket t" + query.whereClause(), query);
        int number = clampPage(criteria.page, total, PAGE_SIZE);
        List<JournalRow> rows = ticketRows(criteria, query, (number - 1) * PAGE_SIZE, PAGE_SIZE);
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    /**
     * Reads one window of the transactional list and maps the projected rows to
     * {@link JournalRow}.
     *
     * @param criteria the parsed criteria, read for the sort column and direction
     * @param query the already-built where clause and its parameters
     * @param offset the 0-based index of the first row to read
     * @param limit the maximum number of rows to read
     * @return the mapped rows of that window
     */
    private List<JournalRow> ticketRows(JournalCriteria criteria, JournalQuery query,
                                        int offset, int limit) {
        JournalSort sort = JournalSort.fromKey(criteria.sort);
        String direction = criteria.descending ? "desc" : "asc";
        String jpql = "select t.id, t.terminalId, t.ticketNumber, t.cashier.badgeId,"
                + " t.creationDate, t.totalIncludingTax, t.itemCount from Ticket t"
                + query.whereClause()
                + " order by " + sort.path + " " + direction + ", t.id asc";
        TypedQuery<Object[]> typed = entityManager.createQuery(jpql, Object[].class);
        bind(typed, query);
        typed.setFirstResult(offset);
        typed.setMaxResults(limit);
        List<JournalRow> rows = new ArrayList<>();
        for (Object[] row : typed.getResultList()) {
            LocalDateTime creation = (LocalDateTime) row[4];
            rows.add(new JournalRow(
                    (Long) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    creation.format(DATE),
                    creation.format(TIME),
                    formatAmount((BigDecimal) row[5]),
                    (Integer) row[6],
                    "N",
                    "N"));
        }
        return rows;
    }

    /**
     * Runs a count query over an already-built where clause.
     *
     * @param jpql the complete count query
     * @param query the built query carrying the parameters to bind
     * @return the number of matching rows
     */
    private long count(String jpql, JournalQuery query) {
        TypedQuery<Long> typed = entityManager.createQuery(jpql, Long.class);
        bind(typed, query);
        return typed.getSingleResult();
    }

    /**
     * Binds every parameter of a built query onto a typed query.
     *
     * @param typed the query to bind the parameters on
     * @param query the built query carrying the parameters
     */
    private void bind(TypedQuery<?> typed, JournalQuery query) {
        for (Map.Entry<String, Object> entry : query.parameters().entrySet()) {
            typed.setParameter(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Clamps a requested page number into the pages the result set actually
     * has, so a stale link or a hand-typed page shows the last page rather than
     * an empty list.
     *
     * @param requested the requested 1-based page number
     * @param total the total number of matching rows
     * @param size the page size
     * @return the page number to read, between 1 and the page count
     */
    private int clampPage(int requested, long total, int size) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, JournalPage.pageCount(total, size));
    }

    /**
     * Loads a ticket and materializes it into a fully-formatted
     * {@link JournalTicketDetail}, or null when the id matches no row. The
     * lazy lines and payments are read here, inside the request session, so
     * the detail template never touches an unfetched association.
     *
     * @param id the ticket database id
     * @return the materialized detail, or null
     */
    public JournalTicketDetail buildDetail(Long id) {
        Ticket ticket = entityManager.find(Ticket.class, id);
        if (ticket == null) {
            return null;
        }
        List<JournalTicketDetail.Line> lines = new ArrayList<>();
        for (TicketLine line : ticket.lines) {
            lines.add(new JournalTicketDetail.Line(
                    line.lineNumber,
                    line.productLabel,
                    line.ean,
                    line.plu,
                    formatQuantity(line.quantity),
                    formatAmount(line.totalPrice),
                    line.modifierLabel));
        }
        List<JournalTicketDetail.Payment> payments = new ArrayList<>();
        for (TicketPayment payment : ticket.payments) {
            payments.add(new JournalTicketDetail.Payment(
                    payment.getMethodKey(),
                    formatAmount(payment.amount)));
        }
        return new JournalTicketDetail(
                ticket.ticketNumber,
                ticket.terminalId,
                ticket.status.name(),
                ticket.creationDate.format(DATE),
                ticket.creationDate.format(TIME),
                ticket.cashier != null ? ticket.cashier.getFullName() : "",
                ticket.cashier != null ? ticket.cashier.badgeId : "",
                ticket.store != null ? ticket.store.name : "",
                ticket.fidelityCard != null ? ticket.fidelityCard : "",
                formatAmount(ticket.totalIncludingTax),
                formatAmount(ticket.totalExcludingTax),
                formatAmount(ticket.totalVat),
                lines,
                payments);
    }

    // --------------------------------------------------
    // Functional journal
    // --------------------------------------------------

    /**
     * Builds the {@code where} clause of the functional-event search.
     * <p>
     * The cashier range (BO-04-01-27/28/29/30) reuses the same
     * {@code cashierMin}/{@code cashierMax} criteria as the transactional tab,
     * matched here against {@link TechnicalEvent#operatorBadgeId} — the
     * first-class badge column, badge compared to badge. The operator-security
     * events (register lock/unlock, password change and failure) name their
     * operator by that column, so the range narrows a selected action to a band
     * of cashiers exactly as the questionnaire asks. Comparing the range against
     * the free-text {@code detail} column instead would match any detail that
     * happens to sort between the bounds for the sixteen other event types — a
     * defect, not a shortcut. An event with no operator has a null badge, which
     * never matches a range, so it is excluded rather than wrongly returned.
     *
     * @param criteria the parsed criteria
     * @return the assembled query fragment and its parameters
     */
    JournalQuery buildEventQuery(JournalCriteria criteria) {
        JournalQuery query = new JournalQuery();
        if (!criteria.eventTypes.isEmpty()) {
            List<TechnicalEvent.EventType> types = new ArrayList<>();
            for (String name : criteria.eventTypes) {
                TechnicalEvent.EventType type = EventTypes.forName(name);
                if (type != null) {
                    types.add(type);
                }
            }
            if (!types.isEmpty()) {
                query.and("e.eventType in :eventTypes");
                query.bind("eventTypes", types);
            }
        }
        if (criteria.cashierMin != null) {
            query.and("e.operatorBadgeId >= :cashierMin");
            query.bind("cashierMin", criteria.cashierMin);
        }
        if (criteria.cashierMax != null) {
            query.and("e.operatorBadgeId <= :cashierMax");
            query.bind("cashierMax", criteria.cashierMax);
        }
        if (criteria.terminalMin != null) {
            query.and("e.terminalId >= :terminalMin");
            query.bind("terminalMin", criteria.terminalMin);
        }
        if (criteria.terminalMax != null) {
            query.and("e.terminalId <= :terminalMax");
            query.bind("terminalMax", criteria.terminalMax);
        }
        if (criteria.dateFrom != null) {
            query.and("e.eventDate >= :dateFrom");
            query.bind("dateFrom", criteria.dateFrom);
        }
        if (criteria.dateTo != null) {
            query.and("e.eventDate <= :dateTo");
            query.bind("dateTo", criteria.dateTo);
        }
        if (criteria.text != null) {
            query.and("lower(e.detail) like :text");
            query.bind("text", "%" + criteria.text.toLowerCase() + "%");
        }
        return query;
    }

    /**
     * Runs the functional-event search, most recent first, and returns the
     * requested page with the total number of matching events — the same
     * no-silent-truncation posture as the transactional list.
     *
     * @param criteria the parsed criteria, carrying the requested page
     * @return the requested page of event rows and the total row count
     */
    public JournalPage<JournalEventRow> searchEvents(JournalCriteria criteria) {
        JournalQuery query = buildEventQuery(criteria);
        long total = count("select count(e.id) from TechnicalEvent e" + query.whereClause(), query);
        int number = clampPage(criteria.page, total, PAGE_SIZE);
        String jpql = "select e from TechnicalEvent e" + query.whereClause()
                + " order by e.eventDate desc, e.id asc";
        TypedQuery<TechnicalEvent> typed = entityManager.createQuery(jpql, TechnicalEvent.class);
        bind(typed, query);
        typed.setFirstResult((number - 1) * PAGE_SIZE);
        typed.setMaxResults(PAGE_SIZE);
        List<JournalEventRow> rows = new ArrayList<>();
        for (TechnicalEvent event : typed.getResultList()) {
            rows.add(new JournalEventRow(
                    event.terminalId,
                    event.operatorBadgeId,
                    event.eventType.name(),
                    event.eventDate.format(DATE),
                    event.eventDate.format(TIME),
                    event.detail));
        }
        return new JournalPage<>(rows, number, PAGE_SIZE, total);
    }

    // --------------------------------------------------
    // Export
    // --------------------------------------------------

    /**
     * Renders the transactional list as a semicolon-separated CSV reproducing
     * the columns of {@link JournalRow} exactly (BO-04-01-50, export à
     * l'identique). Runs the same query as the screen so the two never diverge,
     * but over the WHOLE result set rather than the displayed page: an extract
     * an auditor works from is complete or it is worthless.
     *
     * @param criteria the parsed search criteria
     * @return the CSV document
     */
    public String exportCsv(JournalCriteria criteria) {
        return exportCsv(criteria, EXPORT_CHUNK);
    }

    /**
     * Renders the CSV export, reading the result set in windows of the given
     * size so a large extract never materializes a single unbounded query.
     *
     * @param criteria the parsed search criteria
     * @param chunkSize the number of rows read per query, strictly positive
     * @return the CSV document covering every matching row
     */
    String exportCsv(JournalCriteria criteria, int chunkSize) {
        JournalQuery query = buildTicketQuery(criteria);
        StringBuilder csv = new StringBuilder(
                "TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome\n");
        int offset = 0;
        List<JournalRow> chunk = ticketRows(criteria, query, offset, chunkSize);
        while (!chunk.isEmpty()) {
            for (JournalRow row : chunk) {
                csv.append(row.terminal).append(';')
                        .append(row.transaction).append(';')
                        .append(row.cashier).append(';')
                        .append(row.date).append(';')
                        .append(row.time).append(';')
                        .append(row.amount).append(';')
                        .append(row.itemCount).append(';')
                        .append(row.trainingMode).append(';')
                        .append(row.autonomous).append('\n');
            }
            if (chunk.size() < chunkSize) {
                break;
            }
            offset += chunkSize;
            chunk = ticketRows(criteria, query, offset, chunkSize);
        }
        return csv.toString();
    }

    /**
     * Formats an amount for the list and export (2 decimals, French comma).
     *
     * @param amount the amount, or null
     * @return the formatted amount, "0,00" for a null amount
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0,00";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }

    /**
     * Formats a quantity for the detail (French comma, trailing zeros
     * stripped).
     *
     * @param quantity the quantity, or null
     * @return the formatted quantity, an empty string for a null quantity
     */
    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return "";
        }
        return quantity.stripTrailingZeros().toPlainString().replace(".", ",");
    }
}
