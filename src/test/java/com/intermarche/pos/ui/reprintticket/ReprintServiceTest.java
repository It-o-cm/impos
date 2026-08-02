package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReprintService}.
 * <p>
 * The service drives the reprint screen over a mocked {@link PosState} (carrying
 * mocked {@link ReprintState} and {@link TicketState} holders) and a mocked
 * {@link TicketPrinterService}. {@code loadHistory()} pulls the closed-ticket
 * list through the {@code Ticket.find(...)} static finder, which resolves to
 * {@link PanacheEntityBase} under plain {@code mvn test} and is intercepted with
 * {@link org.mockito.Mockito#mockStatic}. {@code print(Long)} guards on the
 * training-mode flag and on a null id. Tests assert delegation and cover both
 * arms of the training-mode guard and both arms of the null-id guard
 * ({@code loadHistory} is straight-line, 4 branches in {@code print}).
 */
class ReprintServiceTest {

    /**
     * Builds a {@link ReprintService} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#reprint} and
     * {@code ticket} sub-state holders so no direct field access hits a null.
     *
     * @return a service with fully mocked state and printer
     */
    private ReprintService newService() {
        ReprintService service = new ReprintService();
        service.state = mock(PosState.class);
        service.state.reprint = mock(ReprintState.class);
        service.state.ticket = mock(TicketState.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        return service;
    }

    // --- loadHistory ---

    /**
     * {@code loadHistory()} loads the closed tickets, most recent first, into the
     * reprint state and touches the session.
     */
    @Test
    void loadHistoryLoadsClosedTicketsAndTouches() {
        ReprintService service = newService();
        List<Ticket> tickets = List.of(mock(Ticket.class), mock(Ticket.class));
        @SuppressWarnings("unchecked")
        PanacheQuery<Ticket> query = mock(PanacheQuery.class);
        when(query.list()).thenReturn(tickets);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.find("status = ?1 ORDER BY creationDate DESC", Ticket.TicketStatus.CLOSED))
                    .thenReturn(query);
            service.loadHistory();
        }
        verify(service.state.reprint).setTickets(tickets);
        verify(service.state).touch();
    }

    // --- print ---

    /**
     * {@code print()} refuses in training mode: it flags the error, touches the
     * session and never prints (training-mode guard true).
     */
    @Test
    void printRefusedInTrainingMode() {
        ReprintService service = newService();
        service.state.trainingMode = true;
        service.print(42L);
        verify(service.state.ticket).setError("RÉIMPRESSION INDISPONIBLE EN FORMATION");
        verify(service.state).touch();
        verifyNoInteractions(service.ticketPrinterService);
    }

    /**
     * {@code print()} delegates to the printer for a non-null id outside training
     * mode (training-mode guard false, null-id guard false).
     */
    @Test
    void printDelegatesForNonNullId() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.print(42L);
        verify(service.ticketPrinterService).printTicket(42L);
        verify(service.state.ticket, never()).setError(any());
        verify(service.state, never()).touch();
    }

    /**
     * {@code print()} prints nothing for a null id outside training mode
     * (training-mode guard false, null-id guard true).
     */
    @Test
    void printIgnoresNullId() {
        ReprintService service = newService();
        service.state.trainingMode = false;
        service.print(null);
        verifyNoInteractions(service.ticketPrinterService);
        verify(service.state.ticket, never()).setError(any());
        verify(service.state, never()).touch();
    }
}
