package com.intermarche.pos.service;

import com.intermarche.pos.domain.sale.ParkedTicketRecord;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ParkedTicketPurgeService} (BO-09-01-08): every ticket
 * on hold of the register is cancelled, pushed and its purge traced; the
 * journal says how many went and on whose order.
 */
class ParkedTicketPurgeServiceTest {

    /**
     * A service over mocked collaborators, register C04.
     *
     * @return the service
     */
    private ParkedTicketPurgeService service() {
        ParkedTicketPurgeService service = new ParkedTicketPurgeService();
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.parkedTicketArchiveService = mock(ParkedTicketArchiveService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.terminalId = "C04";
        return service;
    }

    /**
     * Each ticket on hold is cancelled, pushed and traced as purged by the
     * back office; the journal names who ordered it.
     */
    @Test
    void theTicketsOnHoldAreReset() {
        ParkedTicketPurgeService service = service();
        Ticket ticket = mock(Ticket.class);
        ticket.id = 5L;
        ticket.status = Ticket.TicketStatus.PARKED;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.list("terminalId = ?1 and status = ?2", "C04", Ticket.TicketStatus.PARKED))
                    .thenReturn(List.of(ticket));
            assertEquals(1, service.purge(" Remise à zéro "));
        }
        assertEquals(Ticket.TicketStatus.CANCELLED, ticket.status);
        verify(ticket).persist();
        verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        verify(service.parkedTicketArchiveService).record(ticket, ParkedTicketRecord.Event.PURGED, "RAZ BACK-OFFICE");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TICKET_CANCELLED,
                "1 ticket(s) en attente remis à zéro sur ordre du back-office (Remise à zéro)");
    }

    /**
     * Nothing on hold resets nothing; an order without reason names nobody.
     */
    @Test
    void nothingOnHoldResetsNothing() {
        ParkedTicketPurgeService service = service();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.list("terminalId = ?1 and status = ?2", "C04", Ticket.TicketStatus.PARKED))
                    .thenReturn(List.of());
            assertEquals(0, service.purge(null));
            assertEquals(0, service.purge(" "));
        }
        verify(service.technicalEventService, org.mockito.Mockito.times(2)).log(TechnicalEvent.EventType.TICKET_CANCELLED,
                "0 ticket(s) en attente remis à zéro sur ordre du back-office");
    }
}
