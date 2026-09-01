package com.intermarche.pos.ui.journal;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JournalService}.
 * <p>
 * The pure query builders ({@code buildTicketQuery}, {@code buildEventQuery})
 * are exercised directly, with no {@link EntityManager} in sight: each optional
 * criterion is verified on both arms (present → clause added, absent → nothing)
 * and the PLU/reduction range helpers on their min-only, max-only and
 * both-present shapes. The execution methods ({@code search},
 * {@code searchEvents}, {@code buildDetail}, {@code exportCsv}) run against a
 * mocked entity manager whose typed queries return pre-baked rows: each search
 * stubs its count query as well, since the list is now paged and always states
 * its total. Absolute expected values throughout; no database, no Quarkus
 * context.
 */
class JournalServiceTest {

    /**
     * Builds a service whose entity manager is the given mock.
     *
     * @param entityManager the mocked entity manager
     * @return the wired service
     */
    private JournalService serviceWith(EntityManager entityManager) {
        JournalService service = new JournalService();
        service.entityManager = entityManager;
        return service;
    }

    /**
     * Creates a fresh {@link TypedQuery} mock whose {@code setParameter} and
     * {@code setMaxResults} return the query itself.
     *
     * @param <T> the query result type
     * @return the self-returning typed-query mock
     */
    @SuppressWarnings("unchecked")
    private <T> TypedQuery<T> selfQuery() {
        TypedQuery<T> query = mock(TypedQuery.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.setFirstResult(anyInt())).thenReturn(query);
        return query;
    }

    /**
     * Stubs the count query of a mocked entity manager.
     *
     * @param entityManager the mocked entity manager
     * @param total the total the count query must return
     */
    private void stubCount(EntityManager entityManager, long total) {
        TypedQuery<Long> query = selfQuery();
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(query);
        when(query.getSingleResult()).thenReturn(total);
    }

    // --------------------------------------------------
    // buildTicketQuery
    // --------------------------------------------------

    /**
     * An empty criteria produces only the base finalized-documents clause
     * (every optional criterion on its absent arm).
     */
    @Test
    void emptyCriteriaProducesOnlyBaseClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertEquals(" where t.status in :statuses", query.whereClause());
        assertEquals(List.of(Ticket.TicketStatus.CLOSED, Ticket.TicketStatus.CANCELLED),
                query.parameters().get("statuses"));
        assertEquals(1, query.parameters().size());
    }

    /**
     * A criteria with every field set adds every clause and binds every
     * parameter (every optional criterion on its present arm, all eight flags).
     */
    @Test
    void fullCriteriaAddsEveryClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.text = "lait";
        criteria.cashierMin = "100";
        criteria.cashierMax = "200";
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.txMin = "A";
        criteria.txMax = "Z";
        criteria.amountMin = new BigDecimal("1.00");
        criteria.amountMax = new BigDecimal("9.00");
        criteria.methods.add("CARD");
        criteria.dateFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 8, 31, 23, 59);
        criteria.pluMin = "40";
        criteria.pluMax = "50";
        criteria.vatRate = new BigDecimal("0.2000");
        criteria.reductionMin = new BigDecimal("1.00");
        criteria.reductionMax = new BigDecimal("5.00");
        criteria.authMin = "100000";
        criteria.authMax = "999999";
        criteria.flags.add(JournalCriteria.Flag.CANCELLED);
        criteria.flags.add(JournalCriteria.Flag.DISCOUNT);
        criteria.flags.add(JournalCriteria.Flag.RETURN);
        criteria.flags.add(JournalCriteria.Flag.NEGATIVE);
        criteria.flags.add(JournalCriteria.Flag.ZERO_PRICE);
        criteria.flags.add(JournalCriteria.Flag.UNKNOWN_ITEM);
        criteria.flags.add(JournalCriteria.Flag.VOUCHER);
        criteria.flags.add(JournalCriteria.Flag.CARD);
        criteria.flags.add(JournalCriteria.Flag.DEGRADED);
        criteria.flags.add(JournalCriteria.Flag.DEGRADED_MANUAL);
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        Map<String, Object> params = query.parameters();
        assertTrue(where.contains("lower(t.ticketNumber) like :text"));
        assertEquals("%lait%", params.get("text"));
        assertTrue(where.contains("t.cashier.badgeId >= :cashierMin"));
        assertTrue(where.contains("t.cashier.badgeId <= :cashierMax"));
        assertTrue(where.contains("t.terminalId >= :terminalMin"));
        assertTrue(where.contains("t.terminalId <= :terminalMax"));
        assertTrue(where.contains("t.ticketNumber >= :txMin"));
        assertTrue(where.contains("t.ticketNumber <= :txMax"));
        assertTrue(where.contains("t.totalIncludingTax >= :amountMin"));
        assertTrue(where.contains("t.totalIncludingTax <= :amountMax"));
        assertTrue(where.contains("type(p) in :methodTypes"));
        assertEquals(List.of(CardPayment.class), params.get("methodTypes"));
        assertTrue(where.contains("t.creationDate >= :dateFrom"));
        assertTrue(where.contains("t.creationDate <= :dateTo"));
        assertTrue(where.contains("l.plu >= :pluMin and l.plu <= :pluMax"));
        assertTrue(where.contains("l.vatRate = :vatRate"));
        assertTrue(where.contains("l.modifierValue >= :reductionMin and l.modifierValue <= :reductionMax"));
        assertTrue(where.contains("t.status = :cancelled"));
        assertTrue(where.contains("t.globalDiscountApplied is not null"));
        assertTrue(where.contains("from Refund r where r.originalTicketId = t.id"));
        assertTrue(where.contains("t.totalIncludingTax < 0"));
        assertTrue(where.contains("l.totalPrice = 0"));
        assertTrue(where.contains("l.product is null and l.deposit = false"));
        assertEquals(CardPayment.class, params.get("cardType"));
        assertTrue(params.containsKey("voucherType"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber is not null"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber >= :authMin"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber <= :authMax"));
        assertEquals("100000", params.get("authMin"));
        assertEquals("999999", params.get("authMax"));
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
    }

    /**
     * A card authorization lower bound alone appends the {@code >=} half only,
     * guarded by {@code is not null}, without the {@code <=} half
     * (BO-04-01-08, first-bound arm).
     */
    @Test
    void authMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.authMin = "100000";
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber is not null"));
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber >= :authMin)"));
        assertFalse(where.contains("authMax"));
        assertEquals("100000", query.parameters().get("authMin"));
    }

    /**
     * A card authorization upper bound alone appends the {@code <=} half only
     * (BO-04-01-08, second-bound arm).
     */
    @Test
    void authMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.authMax = "999999";
        JournalQuery query = service.buildTicketQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("treat(p as CardPayment).authorizationNumber <= :authMax)"));
        assertFalse(where.contains("authMin"));
        assertEquals("999999", query.parameters().get("authMax"));
    }

    /**
     * No authorization bound adds no authorization clause (both-null early
     * return arm of the builder).
     */
    @Test
    void noAuthorizationBoundAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildTicketQuery(new JournalCriteria());
        assertFalse(query.whereClause().contains("authorizationNumber"));
        assertFalse(query.parameters().containsKey("authMin"));
        assertFalse(query.parameters().containsKey("authMax"));
    }

    /**
     * The DEGRADED flag alone adds the degraded-mode existence clause
     * (BO-04-01-47), and the DEGRADED_MANUAL flag is absent.
     */
    @Test
    void degradedFlagAddsDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.DEGRADED);
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
    }

    /**
     * The DEGRADED_MANUAL flag alone adds the same degraded-mode clause
     * (BO-04-01-49): the register's only degraded mode is manual, so both
     * criteria match the same rows.
     */
    @Test
    void degradedManualFlagAddsDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.flags.add(JournalCriteria.Flag.DEGRADED_MANUAL);
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("treat(p as CardPayment).degradedMode = true"));
    }

    /**
     * Neither degraded flag adds no degraded-mode clause (both absent arms).
     */
    @Test
    void noDegradedFlagAddsNoDegradedClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        String where = service.buildTicketQuery(new JournalCriteria()).whereClause();
        assertFalse(where.contains("degradedMode"));
    }

    /**
     * A PLU lower bound alone appends the {@code >=} half only (first-bound
     * arm, no {@code and}).
     */
    @Test
    void pluMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.pluMin = "40";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.plu >= :pluMin)"));
        assertFalse(where.contains("pluMax"));
    }

    /**
     * A PLU upper bound alone appends the {@code <=} half only (the {@code and}
     * separator is skipped: {@code !first} false arm).
     */
    @Test
    void pluMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.pluMax = "50";
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("where l.plu <= :pluMax)"));
        assertFalse(where.contains("pluMin"));
    }

    /**
     * A reduction lower bound alone appends only the {@code >=} half.
     */
    @Test
    void reductionMinOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.reductionMin = new BigDecimal("1.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.modifierType is not null and l.modifierValue >= :reductionMin)"));
        assertFalse(where.contains("reductionMax"));
    }

    /**
     * A reduction upper bound alone appends only the {@code <=} half.
     */
    @Test
    void reductionMaxOnly() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.reductionMax = new BigDecimal("5.00");
        String where = service.buildTicketQuery(criteria).whereClause();
        assertTrue(where.contains("l.modifierType is not null and l.modifierValue <= :reductionMax)"));
        assertFalse(where.contains("reductionMin"));
    }

    /**
     * A method set that resolves to no known type adds no payment clause
     * (unknown key skipped, resulting type list empty).
     */
    @Test
    void unknownMethodAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.methods.add("BITCOIN");
        JournalQuery query = service.buildTicketQuery(criteria);
        assertFalse(query.whereClause().contains("methodTypes"));
        assertFalse(query.parameters().containsKey("methodTypes"));
    }

    // --------------------------------------------------
    // buildEventQuery
    // --------------------------------------------------

    /**
     * An empty criteria produces no functional where clause (all absent arms).
     */
    @Test
    void emptyEventCriteriaProducesNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalQuery query = service.buildEventQuery(new JournalCriteria());
        assertEquals("", query.whereClause());
        assertTrue(query.parameters().isEmpty());
    }

    /**
     * A full functional criteria adds every clause (all present arms).
     */
    @Test
    void fullEventCriteriaAddsEveryClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("SESSION_CLOSED");
        criteria.cashierMin = "100";
        criteria.cashierMax = "200";
        criteria.terminalMin = "C01";
        criteria.terminalMax = "C09";
        criteria.dateFrom = LocalDateTime.of(2026, 8, 1, 0, 0);
        criteria.dateTo = LocalDateTime.of(2026, 8, 31, 0, 0);
        criteria.text = "Z";
        JournalQuery query = service.buildEventQuery(criteria);
        String where = query.whereClause();
        assertTrue(where.contains("e.eventType in :eventTypes"));
        assertEquals(List.of(TechnicalEvent.EventType.SESSION_CLOSED),
                query.parameters().get("eventTypes"));
        assertTrue(where.contains("e.operatorBadgeId >= :cashierMin"));
        assertTrue(where.contains("e.operatorBadgeId <= :cashierMax"));
        assertEquals("100", query.parameters().get("cashierMin"));
        assertEquals("200", query.parameters().get("cashierMax"));
        assertTrue(where.contains("e.terminalId >= :terminalMin"));
        assertTrue(where.contains("e.terminalId <= :terminalMax"));
        assertTrue(where.contains("e.eventDate >= :dateFrom"));
        assertTrue(where.contains("e.eventDate <= :dateTo"));
        assertTrue(where.contains("lower(e.detail) like :text"));
        assertEquals("%z%", query.parameters().get("text"));
    }

    /**
     * An event-type set that resolves to no known type adds no type clause
     * (unknown name skipped, resulting list empty).
     */
    @Test
    void unknownEventTypeAddsNoClause() {
        JournalService service = serviceWith(mock(EntityManager.class));
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("NOPE");
        JournalQuery query = service.buildEventQuery(criteria);
        assertEquals("", query.whereClause());
        assertFalse(query.parameters().containsKey("eventTypes"));
    }

    // --------------------------------------------------
    // search / searchEvents / export
    // --------------------------------------------------

    /**
     * {@code search} maps projected rows, formats the datetime and amount, and
     * turns a null amount into "0,00" (formatAmount null arm); the ascending
     * direction is applied (descending false arm), the first page is read from
     * offset 0 and the page carries the counted total.
     */
    @Test
    void searchMapsRowsAscending() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] normal = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3};
        Object[] nullAmount = {6L, "C04", "C04-00000124", "12341234",
                LocalDateTime.of(2026, 8, 31, 15, 0), null, 0};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(normal, nullAmount));
        stubCount(em, 2L);
        JournalService service = serviceWith(em);
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalRow> page = service.search(new JournalCriteria());
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("order by t.creationDate asc, t.id asc"));
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.PAGE_SIZE);
        assertEquals(2, page.rows.size());
        assertEquals(2L, page.total);
        assertEquals(1, page.number);
        assertEquals(5L, page.rows.get(0).id);
        assertEquals("31/08/2026", page.rows.get(0).date);
        assertEquals("14:30", page.rows.get(0).time);
        assertEquals("12,50", page.rows.get(0).amount);
        assertEquals("N", page.rows.get(0).trainingMode);
        assertEquals("0,00", page.rows.get(1).amount);
    }

    /**
     * {@code search} counts the matching documents with a count query built on
     * the same where clause as the list.
     */
    @Test
    void searchCountsOverTheSameWhereClause() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 0L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.text = "lait";
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        service.search(criteria);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertTrue(jpql.getValue().startsWith("select count(t.id) from Ticket t where "));
        assertTrue(jpql.getValue().contains("like :text"));
    }

    /**
     * {@code search} applies the requested column and descending direction
     * (descending true arm, non-default sort).
     */
    @Test
    void searchDescendingUsesRequestedSort() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 0L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.sort = "amount";
        criteria.descending = true;
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        assertTrue(service.search(criteria).rows.isEmpty());
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Object[].class));
        assertTrue(jpql.getValue().contains("order by t.totalIncludingTax desc, t.id asc"));
    }

    /**
     * {@code search} reads the requested page from its offset when that page
     * exists (clampPage min arm, requested below the page count).
     */
    @Test
    void searchReadsTheRequestedPage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 250L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 2;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(JournalService.PAGE_SIZE);
        assertEquals(2, page.number);
        assertEquals(250L, page.total);
        assertEquals(3, page.getPageCount());
    }

    /**
     * {@code search} clamps a page beyond the last one onto the last page
     * (clampPage min arm, requested above the page count).
     */
    @Test
    void searchClampsPageBeyondTheLastOne() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 150L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 9;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(JournalService.PAGE_SIZE);
        assertEquals(2, page.number);
    }

    /**
     * {@code search} clamps a non-positive page onto the first one (clampPage
     * requested-below-one arm).
     */
    @Test
    void searchClampsNonPositivePage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 150L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 0;
        JournalPage<JournalRow> page = service.search(criteria);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        assertEquals(1, page.number);
    }

    /**
     * {@code searchEvents} maps the technical events to formatted rows.
     */
    @Test
    void searchEventsMapsRows() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> query = selfQuery();
        TechnicalEvent event = new TechnicalEvent();
        event.terminalId = "C04";
        event.eventType = TechnicalEvent.EventType.SESSION_CLOSED;
        event.eventDate = LocalDateTime.of(2026, 8, 31, 18, 0);
        event.detail = "Z report";
        event.operatorBadgeId = "12341234";
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(event));
        stubCount(em, 1L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.eventTypes.add("SESSION_CLOSED");
        JournalPage<JournalEventRow> page = service.searchEvents(criteria);
        assertEquals(1, page.rows.size());
        assertEquals(1L, page.total);
        assertEquals(1, page.number);
        assertEquals("C04", page.rows.get(0).terminal);
        assertEquals("12341234", page.rows.get(0).cashier);
        assertEquals("SESSION_CLOSED", page.rows.get(0).type);
        assertEquals("31/08/2026", page.rows.get(0).date);
        assertEquals("18:00", page.rows.get(0).time);
        assertEquals("Z report", page.rows.get(0).detail);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.PAGE_SIZE);
    }

    /**
     * {@code searchEvents} reads the requested page of events from its offset
     * and counts the whole result set.
     */
    @Test
    void searchEventsReadsTheRequestedPage() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<TechnicalEvent> query = selfQuery();
        when(em.createQuery(anyString(), eq(TechnicalEvent.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        stubCount(em, 320L);
        JournalService service = serviceWith(em);
        JournalCriteria criteria = new JournalCriteria();
        criteria.page = 3;
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        JournalPage<JournalEventRow> page = service.searchEvents(criteria);
        org.mockito.Mockito.verify(em).createQuery(jpql.capture(), eq(Long.class));
        assertEquals("select count(e.id) from TechnicalEvent e", jpql.getValue());
        org.mockito.Mockito.verify(query).setFirstResult(2 * JournalService.PAGE_SIZE);
        assertEquals(3, page.number);
        assertEquals(320L, page.total);
    }

    /**
     * {@code exportCsv} renders the header and one line per row, reproducing
     * the list columns. A partial first chunk ends the read (short-chunk arm),
     * and the export never goes through a count: it covers the whole set.
     */
    @Test
    void exportCsvReproducesTheList() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] row = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(row));
        JournalService service = serviceWith(em);
        String csv = service.exportCsv(new JournalCriteria());
        assertEquals("TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome\n"
                + "C04;C04-00000123;12341234;31/08/2026;14:30;12,50;3;N;N\n", csv);
        org.mockito.Mockito.verify(query).setMaxResults(JournalService.EXPORT_CHUNK);
        org.mockito.Mockito.verify(query).setFirstResult(0);
    }

    /**
     * {@code exportCsv} keeps reading while the chunks come back full, so an
     * extract larger than one window is complete rather than truncated
     * (full-chunk arm, then the exhausted arm).
     */
    @Test
    void exportCsvReadsEveryChunk() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        Object[] first = {5L, "C04", "C04-00000123", "12341234",
                LocalDateTime.of(2026, 8, 31, 14, 30), new BigDecimal("12.50"), 3};
        Object[] second = {6L, "C05", "C05-00000456", "12341234",
                LocalDateTime.of(2026, 8, 31, 15, 45), new BigDecimal("7.00"), 1};
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(
                List.<Object[]>of(first), List.<Object[]>of(second), List.<Object[]>of());
        JournalService service = serviceWith(em);
        String csv = service.exportCsv(new JournalCriteria(), 1);
        assertEquals("TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome\n"
                + "C04;C04-00000123;12341234;31/08/2026;14:30;12,50;3;N;N\n"
                + "C05;C05-00000456;12341234;31/08/2026;15:45;7,00;1;N;N\n", csv);
        org.mockito.Mockito.verify(query).setFirstResult(0);
        org.mockito.Mockito.verify(query).setFirstResult(1);
        org.mockito.Mockito.verify(query).setFirstResult(2);
    }

    /**
     * {@code exportCsv} on a result set matching nothing renders the header
     * alone (empty first chunk arm).
     */
    @Test
    void exportCsvOnEmptyResultRendersHeaderOnly() {
        EntityManager em = mock(EntityManager.class);
        TypedQuery<Object[]> query = selfQuery();
        when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        JournalService service = serviceWith(em);
        assertEquals("TPV;Transaction;Caissiere;Date;Heure;Montant;Articles;Ecole;Autonome\n",
                service.exportCsv(new JournalCriteria()));
    }

    // --------------------------------------------------
    // buildDetail
    // --------------------------------------------------

    /**
     * {@code buildDetail} returns null when the id matches no row (find-null
     * arm).
     */
    @Test
    void buildDetailReturnsNullWhenAbsent() {
        EntityManager em = mock(EntityManager.class);
        when(em.find(Ticket.class, 99L)).thenReturn(null);
        JournalService service = serviceWith(em);
        assertNull(service.buildDetail(99L));
    }

    /**
     * {@code buildDetail} materializes a ticket with a cashier, store and
     * fidelity card (non-null arms), one formatted line and one payment.
     */
    @Test
    void buildDetailMaterializesFullTicket() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000001";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.creationDate = LocalDateTime.of(2026, 8, 31, 10, 0);
        Employee cashier = new Employee();
        cashier.firstName = "Jean";
        cashier.lastName = "Dupont";
        cashier.badgeId = "12341234";
        ticket.cashier = cashier;
        Store store = new Store();
        store.name = "IM Lyon";
        ticket.store = store;
        ticket.fidelityCard = "CARTE1";
        ticket.totalIncludingTax = new BigDecimal("3.00");
        ticket.totalExcludingTax = new BigDecimal("2.85");
        ticket.totalVat = new BigDecimal("0.15");
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.productLabel = "Lait";
        line.ean = "3001";
        line.plu = "42";
        line.quantity = new BigDecimal("2.000");
        line.totalPrice = new BigDecimal("3.00");
        line.modifierLabel = "REMISE";
        ticket.lines.add(line);
        ticket.payments.add(new CardPayment(new BigDecimal("3.00")));
        when(em.find(Ticket.class, 1L)).thenReturn(ticket);
        JournalService service = serviceWith(em);
        JournalTicketDetail detail = service.buildDetail(1L);
        assertEquals("C04-00000001", detail.number);
        assertEquals("CLOSED", detail.status);
        assertEquals("31/08/2026", detail.date);
        assertEquals("10:00", detail.time);
        assertEquals("Jean Dupont", detail.cashier);
        assertEquals("12341234", detail.cashierBadge);
        assertEquals("IM Lyon", detail.store);
        assertEquals("CARTE1", detail.fidelityCard);
        assertEquals("3,00", detail.totalIncludingTax);
        assertEquals("2,85", detail.totalExcludingTax);
        assertEquals("0,15", detail.totalVat);
        assertEquals(1, detail.lines.size());
        assertEquals("2", detail.lines.get(0).quantity);
        assertEquals("REMISE", detail.lines.get(0).modifier);
        assertEquals(1, detail.payments.size());
        assertEquals("CARD", detail.payments.get(0).method);
        assertEquals("3,00", detail.payments.get(0).amount);
    }

    /**
     * {@code buildDetail} tolerates a null cashier, store and fidelity card
     * (null arms), and a line with a null quantity (formatQuantity null arm).
     */
    @Test
    void buildDetailToleratesNullAssociations() {
        EntityManager em = mock(EntityManager.class);
        Ticket ticket = new Ticket();
        ticket.ticketNumber = "C04-00000002";
        ticket.terminalId = "C04";
        ticket.status = Ticket.TicketStatus.CANCELLED;
        ticket.creationDate = LocalDateTime.of(2026, 8, 31, 11, 0);
        ticket.cashier = null;
        ticket.store = null;
        ticket.fidelityCard = null;
        ticket.totalIncludingTax = null;
        ticket.totalExcludingTax = null;
        ticket.totalVat = null;
        TicketLine line = new TicketLine();
        line.lineNumber = 1;
        line.productLabel = "Inconnu";
        line.ean = null;
        line.plu = null;
        line.quantity = null;
        line.totalPrice = new BigDecimal("0.00");
        line.modifierLabel = null;
        ticket.lines.add(line);
        when(em.find(Ticket.class, 2L)).thenReturn(ticket);
        JournalService service = serviceWith(em);
        JournalTicketDetail detail = service.buildDetail(2L);
        assertEquals("", detail.cashier);
        assertEquals("", detail.cashierBadge);
        assertEquals("", detail.store);
        assertEquals("", detail.fidelityCard);
        assertEquals("0,00", detail.totalIncludingTax);
        assertEquals("", detail.lines.get(0).quantity);
        assertEquals("", detail.lines.get(0).ean);
        assertTrue(detail.payments.isEmpty());
    }
}
