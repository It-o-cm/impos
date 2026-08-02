package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.TicketCounter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketNumberService}.
 * <p>
 * All Panache active-record static access ({@code TicketCounter.find}) is
 * intercepted with {@link org.mockito.Mockito#mockStatic}, and the lazy
 * creation path is intercepted with {@link org.mockito.Mockito#mockConstruction}
 * so no database or Quarkus context is needed. The single branch of the class
 * lives in {@link TicketNumberService#lockCounter(String)} (counter found vs.
 * counter created); both arms are covered.
 */
class TicketNumberServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the terminal id field populated, bypassing
     * the {@code @ConfigProperty} injection that a plain unit test does not run.
     *
     * @return a ready-to-use service bound to {@link #TERMINAL}
     */
    private TicketNumberService newService() {
        TicketNumberService service = new TicketNumberService();
        service.terminalId = TERMINAL;
        return service;
    }

    /**
     * Creates a mocked Panache query whose {@code withLock/firstResult} chain
     * resolves to the given counter (or {@code null} to simulate absence).
     *
     * @param result the counter the query must return, possibly {@code null}
     * @return the configured mocked query
     */
    private PanacheQuery<TicketCounter> queryReturning(TicketCounter result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<TicketCounter> query = mock(PanacheQuery.class);
        when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * Verifies that {@code nextTicketNumber} increments the ticket sequence of
     * the existing counter and formats it as {@code <terminal>-<8 digits>}.
     */
    @Test
    void nextTicketNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastNumber = 122L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextTicketNumber();
            assertEquals("C04-00000123", number);
            assertEquals(123L, counter.lastNumber);
        }
    }

    /**
     * Verifies that {@code nextSessionNumber} increments the session sequence of
     * the existing counter and formats it as {@code <terminal>-S<5 digits>}.
     */
    @Test
    void nextSessionNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastSessionNumber = 11L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextSessionNumber();
            assertEquals("C04-S00012", number);
            assertEquals(12L, counter.lastSessionNumber);
        }
    }

    /**
     * Verifies that {@code nextRefundNumber} increments the refund sequence of
     * the existing counter and formats it as {@code <terminal>-R<6 digits>}.
     */
    @Test
    void nextRefundNumberIncrementsAndFormats() {
        TicketCounter counter = new TicketCounter();
        counter.lastRefundNumber = 11L;
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            String number = newService().nextRefundNumber();
            assertEquals("C04-R000012", number);
            assertEquals(12L, counter.lastRefundNumber);
        }
    }

    /**
     * Covers the non-null arm of {@code lockCounter}: an existing counter is
     * locked and returned as-is, without any lazy creation/persist.
     */
    @Test
    void lockCounterReturnsExistingCounterWithoutPersisting() {
        TicketCounter counter = new TicketCounter();
        PanacheQuery<TicketCounter> query = queryReturning(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TicketCounter> created = mockConstruction(TicketCounter.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            TicketCounter result = newService().lockCounter(TERMINAL);
            assertSame(counter, result);
            verify(query).withLock(LockModeType.PESSIMISTIC_WRITE);
            assertEquals(0, created.constructed().size());
        }
    }

    /**
     * Covers the null arm of {@code lockCounter}: when no counter exists a new
     * one is created, seeded (terminal id and zero sequence) and persisted.
     */
    @Test
    void lockCounterCreatesAndPersistsCounterWhenAbsent() {
        PanacheQuery<TicketCounter> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<TicketCounter> created = mockConstruction(TicketCounter.class)) {
            mocked.when(() -> TicketCounter.find("terminalId", TERMINAL)).thenReturn(query);
            TicketCounter result = newService().lockCounter(TERMINAL);
            assertEquals(1, created.constructed().size());
            TicketCounter newCounter = created.constructed().get(0);
            assertSame(newCounter, result);
            assertEquals(TERMINAL, newCounter.terminalId);
            assertEquals(0L, newCounter.lastNumber);
            verify(newCounter, times(1)).persist();
        }
    }

    /**
     * Verifies that {@code getTerminalId} returns the configured terminal id.
     */
    @Test
    void getTerminalIdReturnsConfiguredValue() {
        TicketNumberService service = newService();
        assertNotNull(service.getTerminalId());
        assertEquals(TERMINAL, service.getTerminalId());
    }
}
