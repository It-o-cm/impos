package com.intermarche.pos.service.sync.register;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.sync.RefPullService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeriodSyncCheckService} (BO-09-03-05): the register
 * compares its closed tickets of the day with the server's figures, pushes
 * them again when the count or the total differ, re-pulls its referential and
 * journals the outcome; unreadable figures are journalled and nothing is
 * checked.
 */
class PeriodSyncCheckServiceTest {

    /** The query listing the day's closed tickets. */
    private static final String DAY_TICKETS =
            "terminalId = ?1 and status = ?2 and closingDate >= ?3 and closingDate < ?4";

    /** The day checked. */
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    /**
     * A service over mocked collaborators, register C04.
     *
     * @return the service
     */
    private PeriodSyncCheckService service() {
        PeriodSyncCheckService service = new PeriodSyncCheckService();
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.refPullService = mock(RefPullService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.terminalId = "C04";
        return service;
    }

    /**
     * A closed ticket.
     *
     * @param id its id
     * @param total its total, or null
     * @return the ticket
     */
    private Ticket ticket(long id, String total) {
        Ticket ticket = new Ticket();
        ticket.id = id;
        ticket.totalIncludingTax = total == null ? null : new BigDecimal(total);
        return ticket;
    }

    /**
     * Checks against the given figures with the given local tickets.
     *
     * @param service the service
     * @param tickets the register's closed tickets of the day
     * @param figures the server's figures
     * @return what the check answered
     */
    private int check(PeriodSyncCheckService service, List<Ticket> tickets, String figures) {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.list(DAY_TICKETS, "C04", Ticket.TicketStatus.CLOSED,
                    DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay())).thenReturn(tickets);
            return service.check(figures);
        }
    }

    /**
     * Figures that match: nothing is pushed again, the referential is checked
     * and the journal says so.
     */
    @Test
    void matchingFiguresAreConforming() {
        PeriodSyncCheckService service = service();
        assertEquals(0, check(service, List.of(ticket(1L, "10.00"), ticket(2L, null)), "2026-10-24;2;10"));
        verifyNoInteractions(service.syncOutboxService);
        verify(service.refPullService).triggerPull();
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Contrôle caisse / serveur du 2026-10-24 : caisse 2 ticket(s) 10.00, serveur 2 ticket(s) 10.00"
                        + " — conforme, référentiel resynchronisé");
    }

    /**
     * A different count, or the same count with a different total, pushes
     * every sale of the day again; a referential in error is journalled.
     */
    @Test
    void differingFiguresPushTheSalesAgain() {
        PeriodSyncCheckService service = service();
        when(service.refPullService.triggerPull()).thenReturn("node down");
        assertEquals(2, check(service, List.of(ticket(1L, "10.00"), ticket(2L, "5.00")), "2026-10-24;1;10.00"));
        verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 1L);
        verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 2L);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Contrôle caisse / serveur du 2026-10-24 : caisse 2 ticket(s) 15.00, serveur 1 ticket(s) 10.00"
                        + " — écart, 2 vente(s) renvoyée(s), référentiel en erreur : node down");
        PeriodSyncCheckService total = service();
        assertEquals(1, check(total, List.of(ticket(3L, "4.00")), "2026-10-24;1;5.00"));
        verify(total.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 3L);
    }

    /**
     * Unreadable figures — none, a missing part, a bad number — are journalled
     * and nothing is checked.
     */
    @Test
    void unreadableFiguresCheckNothing() {
        PeriodSyncCheckService service = service();
        assertEquals(-1, service.check(null));
        assertEquals(-1, service.check("2026-10-24;2"));
        assertEquals(-1, service.check("hier;2;1"));
        verify(service.refPullService, never()).triggerPull();
        verify(service.syncOutboxService, never()).enqueue(any(), any());
        verify(service.technicalEventService, org.mockito.Mockito.times(3)).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Contrôle caisse / serveur impossible : ordre illisible");
    }
}
