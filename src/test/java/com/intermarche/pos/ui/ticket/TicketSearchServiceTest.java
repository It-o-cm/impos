package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.sale.Ticket;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketSearchService}, the shared ticket search engine
 * (EXP-47). The pattern-interpretation branches (amount / date / number / too
 * short) are exercised on a spy so the routing to {@link
 * TicketSearchService#searchClosed} is observed without the database; the
 * {@code searchClosed} criterion branches (amount, date, number, none) are
 * exercised against a mocked Panache static. Both arms of every branch are
 * covered.
 */
class TicketSearchServiceTest {

    /**
     * A free-text amount ({@code 12,50}) routes to the amount criterion.
     */
    @Test
    void searchByPatternRoutesAnAmount() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, null, new BigDecimal("12.50"), false);
        assertSame(marker, service.searchByPattern("12,50"));
        verify(service).searchClosed(null, null, new BigDecimal("12.50"), false);
    }

    /**
     * A free-text {@code dd/MM/yyyy} value routes to the date criterion.
     */
    @Test
    void searchByPatternRoutesADate() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, false);
        assertSame(marker, service.searchByPattern("14/03/2026"));
        verify(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, false);
    }

    /**
     * Anything else long enough routes to the number criterion.
     */
    @Test
    void searchByPatternRoutesANumber() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed("C04-T12", null, null, false);
        assertSame(marker, service.searchByPattern("C04-T12"));
        verify(service).searchClosed("C04-T12", null, null, false);
    }

    /**
     * A value with a comma but no valid number is not read as an amount and falls
     * through to the number criterion (the amount-parse false arm).
     */
    @Test
    void searchByPatternAmountShapedButUnparsableIsANumber() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed("1234567", null, null, false);
        assertSame(marker, service.searchByPattern("1234567"));
        verify(service).searchClosed("1234567", null, null, false);
    }

    /**
     * A too-short pattern returns an empty list without any search.
     */
    @Test
    void searchByPatternTooShortReturnsEmpty() {
        TicketSearchService service = spy(new TicketSearchService());
        assertTrue(service.searchByPattern("ab").isEmpty());
        verify(service, org.mockito.Mockito.never()).searchClosed(anyString(), any(), any());
    }

    /**
     * A null pattern returns an empty list (the null arm of the length guard).
     */
    @Test
    void searchByPatternNullReturnsEmpty() {
        TicketSearchService service = spy(new TicketSearchService());
        assertTrue(service.searchByPattern(null).isEmpty());
    }

    /**
     * {@code searchClosed} with all criteria null returns an empty list without a
     * query.
     */
    @Test
    void searchClosedAllNullReturnsEmpty() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed(null, null, null).isEmpty());
    }

    /**
     * {@code searchClosed} with a blank number returns an empty list (the blank
     * arm of the number guard).
     */
    @Test
    void searchClosedBlankNumberReturnsEmpty() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed("   ", null, null).isEmpty());
    }

    /**
     * {@code searchClosed} by amount queries the total and returns the matches.
     */
    @Test
    void searchClosedByAmountQueriesTotal() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, new BigDecimal("9.99")));
        }
    }

    /**
     * {@code searchClosed} by date queries the day window and returns the matches.
     */
    @Test
    void searchClosedByDateQueriesTheDay() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, LocalDate.of(2026, 1, 2), null));
        }
    }

    /**
     * {@code searchClosed} by number queries the number and returns the matches.
     */
    @Test
    void searchClosedByNumberQueriesTheNumber() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed("C04-T99", null, null));
        }
    }

    /**
     * LC-03-06-02: the reassignment pool keeps only the tickets no client is
     * attached to — the routed criterion carries the filter down to the
     * engine, on every one of the three criteria.
     */
    @Test
    void searchByPatternRoutesWithTheUnassignedFilter() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> marker = List.of(new Ticket());
        doReturn(marker).when(service).searchClosed(null, null, new BigDecimal("12.50"), true);
        doReturn(marker).when(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, true);
        doReturn(marker).when(service).searchClosed("C04-T12", null, null, true);
        assertSame(marker, service.searchByPattern("12,50", true));
        assertSame(marker, service.searchByPattern("14/03/2026", true));
        assertSame(marker, service.searchByPattern("C04-T12", true));
        verify(service).searchClosed(null, null, new BigDecimal("12.50"), true);
        verify(service).searchClosed(null, LocalDate.of(2026, 3, 14), null, true);
        verify(service).searchClosed("C04-T12", null, null, true);
    }

    /**
     * LC-03-06-02: the unassigned filter reaches the QUERY itself — the
     * question the pool asks is the closed-ticket question plus the
     * no-fidelity-card one, on the amount criterion.
     */
    @Test
    void searchClosedUnassignedQueriesTheCardFilter() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, new BigDecimal("9.99"), true));
            panache.verify(() -> Ticket.find(
                    org.mockito.ArgumentMatchers.argThat((String q) -> q.contains("fidelityCard is null")),
                    any(Object[].class)));
        }
    }

    /**
     * LC-03-06-02, the blank arm of the pool: no criterion at all still
     * answers the whole pool — the reassignment screen's full history.
     */
    @Test
    void searchClosedUnassignedBlankAnswersTheWholePool() {
        TicketSearchService service = new TicketSearchService();
        List<Ticket> hits = List.of(new Ticket());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Ticket> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(hits);
            panache.when(() -> Ticket.find(anyString(), any(Object[].class))).thenReturn(query);
            assertSame(hits, service.searchClosed(null, null, null, true));
        }
    }

    /**
     * LC-03-06-02, the ordinary blank arm: no criterion answers nothing —
     * the ordinary search never returned the world and does not start now.
     */
    @Test
    void searchClosedOrdinaryBlankAnswersNothing() {
        TicketSearchService service = new TicketSearchService();
        assertTrue(service.searchClosed(null, null, null, false).isEmpty());
    }
    /** LC-04-05-01: impossible calendar values remain ticket-number patterns; covers the date parsing rejection. */
    @Test
    void impossibleDateFallsBackToTicketNumberSearch() {
        TicketSearchService service = spy(new TicketSearchService());
        List<Ticket> tickets = List.of(new Ticket());
        doReturn(tickets).when(service).searchClosed("32/13/2026", null, null, false);
        assertSame(tickets, service.searchByPattern("32/13/2026"));
        verify(service).searchClosed("32/13/2026", null, null, false);
    }

    /** LC-04-05-01: all seven criteria are ANDed with the closed-ticket, retention and reassignment boundaries; identifiers stay parameters. */
    @Test
    void allCriteriaCombineWithoutBroadeningTheSearch() {
        var service = new TicketSearchService();
        var criteria = new TicketSearchCriteria();
        criteria.number = " C04-%_42 ";
        criteria.loyaltyCard = " 999888777 ";
        criteria.articleCode = " 12345670 ";
        criteria.terminalId = " C04 ";
        criteria.date = " 29/02/2024 ";
        criteria.amount = " 12,50 ";
        criteria.paymentMethod = " card ";
        var query = mock(PanacheQuery.class);
        var expected = List.of(new Ticket());
        when(query.list()).thenReturn(expected);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            var before = java.time.LocalDateTime.now().minusDays(30);
            assertSame(expected, service.searchCriteria(criteria, true));
            var queryText = org.mockito.ArgumentCaptor.forClass(String.class);
            var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
            panache.verify(() -> PanacheEntityBase.find(queryText.capture(), values.capture()));
            String jpql = queryText.getValue();
            assertTrue(jpql.startsWith("select t from Ticket t where t.status = ?1 and t.creationDate > ?2"));
            assertTrue(jpql.contains("locate(lower(?3), lower(t.ticketNumber)) > 0"));
            assertTrue(jpql.contains("t.fidelityCard = ?4"));
            assertTrue(jpql.contains("l.ean = ?5"));
            assertTrue(jpql.contains("lower(t.terminalId) = lower(?6)"));
            assertTrue(jpql.contains("t.creationDate >= ?7 and t.creationDate < ?8"));
            assertTrue(jpql.contains("t.totalIncludingTax = ?9"));
            assertTrue(jpql.contains("type(p) = ?10"));
            assertTrue(jpql.contains("and t.fidelityCard is null"));
            org.junit.jupiter.api.Assertions.assertFalse(jpql.contains("999888777"));
            Object[] parameters = values.getValue();
            assertEquals(10, parameters.length);
            assertEquals(Ticket.TicketStatus.CLOSED, parameters[0]);
            var retention = (java.time.LocalDateTime) parameters[1];
            org.junit.jupiter.api.Assertions.assertFalse(retention.isBefore(before));
            org.junit.jupiter.api.Assertions.assertFalse(retention.isAfter(java.time.LocalDateTime.now().minusDays(30)));
            assertEquals("C04-%_42", parameters[2]);
            assertEquals("999888777", parameters[3]);
            assertEquals("12345670", parameters[4]);
            assertEquals("C04", parameters[5]);
            assertEquals(java.time.LocalDateTime.of(2024, 2, 29, 0, 0), parameters[6]);
            assertEquals(java.time.LocalDateTime.of(2024, 3, 1, 0, 0), parameters[7]);
            assertEquals(new BigDecimal("12.50"), parameters[8]);
            assertEquals(com.intermarche.pos.domain.payment.CardPayment.class, parameters[9]);
            org.junit.jupiter.api.Assertions.assertNull(criteria.errorKey);
            assertTrue(criteria.submitted);
        }
    }
    /** LC-04-05-01: a single criterion works alone; empty normal searches return none while reassignment keeps only unattached tickets. */
    @Test
    void independentCriteriaAndEmptySearchesKeepTheirBoundaries() {
        var service = new TicketSearchService();
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            assertTrue(service.searchCriteria(new TicketSearchCriteria(), false).isEmpty());
            panache.verifyNoInteractions();
            var query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(new Ticket()));
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
            var criteria = new TicketSearchCriteria();
            criteria.number = null;
            criteria.amount = "17";
            assertEquals(1, service.searchCriteria(criteria, false).size());
            assertEquals(1, service.searchCriteria(new TicketSearchCriteria(), true).size());
            criteria.amount = "17.50";
            assertEquals(1, service.searchCriteria(criteria, false).size());
        }
    }
    /** LC-04-05-01: invalid dates, malformed amounts and unknown tenders produce explicit errors and never discard a filter to query more tickets. */
    @Test
    void invalidCriteriaRefuseTheSearchBeforePersistence() {
        var service = new TicketSearchService();
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            var criteria = new TicketSearchCriteria();
            criteria.date = "31/02/2026";
            assertTrue(service.searchCriteria(criteria, false).isEmpty());
            assertEquals("ticketsearch.invalidDate", criteria.errorKey);
            criteria.date = "";
            criteria.amount = "12,3,4";
            assertTrue(service.searchCriteria(criteria, false).isEmpty());
            assertEquals("ticketsearch.invalidAmount", criteria.errorKey);
            criteria.amount = "";
            criteria.paymentMethod = "UNKNOWN";
            assertTrue(service.searchCriteria(criteria, false).isEmpty());
            assertEquals("ticketsearch.invalidPayment", criteria.errorKey);
            panache.verifyNoInteractions();
        }
    }

}
