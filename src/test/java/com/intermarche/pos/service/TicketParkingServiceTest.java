package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketParkingService}.
 * <p>
 * The service owns no persistence of its own: it reads and writes the draft
 * through Panache active-record static access ({@code Ticket.findById},
 * {@code Ticket.list}), all intercepted with {@link org.mockito.Mockito#mockStatic}
 * on {@link PanacheEntityBase}; the drafts themselves are Mockito mocks whose
 * {@code persist()} is a no-op and whose public fields are read and written
 * directly. No database and no Quarkus context are booted. The injected
 * {@link PosState} is a real instance so the in-memory ticket, payment and
 * version behave exactly as in production, while the four collaborators
 * ({@link TicketPersistenceService}, {@link TicketRecoveryService},
 * {@link TicketNumberService}, {@link TechnicalEventService}) are Mockito mocks
 * assigned to the package-private injection fields. Every branch of the three
 * public methods is covered: parking (empty cart, payment in progress, paid
 * amount present, sync failure, draft missing, draft not OPEN, success), the
 * parked listing, and resuming (cart in progress, draft missing, draft not
 * PARKED, foreign terminal, success).
 */
class TicketParkingServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with a real {@link PosState} and the four
     * collaborators mocked, with the terminal id resolved through the ticket
     * number service.
     *
     * @return a ready-to-use service with a real state and mocked collaborators
     */
    private TicketParkingService newService() {
        TicketParkingService service = new TicketParkingService();
        service.state = new PosState();
        service.ticketPersistenceService = mock(TicketPersistenceService.class);
        service.ticketRecoveryService = mock(TicketRecoveryService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Appends one empty line to the given state's ticket so that the cart is
     * no longer considered empty.
     *
     * @param service the service whose in-memory ticket must be filled
     */
    private void addOneItem(TicketParkingService service) {
        service.state.ticket.items.add(new TicketState.TicketItem());
    }

    /**
     * Creates a mocked draft with the given status, ticket number and terminal.
     *
     * @param status the lifecycle status of the draft
     * @param ticketNumber the portable ticket number
     * @param terminalId the owning register id
     * @return the configured mocked draft
     */
    private Ticket draft(Ticket.TicketStatus status, String ticketNumber, String terminalId) {
        Ticket draft = mock(Ticket.class);
        draft.id = 42L;
        draft.status = status;
        draft.ticketNumber = ticketNumber;
        draft.terminalId = terminalId;
        return draft;
    }

    /**
     * Covers the empty-cart guard of {@code parkCurrent}: the parking is refused
     * and no draft is synchronized, journaled or cleared.
     */
    @Test
    void parkCurrentRefusedWhenCartEmpty() {
        TicketParkingService service = newService();
        assertEquals("AUCUN TICKET À METTRE EN ATTENTE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the first arm of the payment guard of {@code parkCurrent}: a
     * payment is in progress, so parking is refused before any sync.
     */
    @Test
    void parkCurrentRefusedWhenPaymentInProgress() {
        TicketParkingService service = newService();
        addOneItem(service);
        service.state.payment.paymentInProgress = true;
        assertEquals("PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
    }

    /**
     * Covers the second arm of the payment guard of {@code parkCurrent}: no
     * payment is in progress but a positive amount is already paid, so parking
     * is refused before any sync.
     */
    @Test
    void parkCurrentRefusedWhenAmountAlreadyPaid() {
        TicketParkingService service = newService();
        addOneItem(service);
        service.state.payment.paymentInProgress = false;
        service.state.payment.paidAmount = new BigDecimal("10.00");
        assertEquals("PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE", service.parkCurrent());
        verifyNoInteractions(service.ticketPersistenceService);
    }

    /**
     * Covers the sync-failure arm of {@code parkCurrent}: the draft sync returns
     * null (training mode), so parking answers the synchronization error.
     */
    @Test
    void parkCurrentRefusedWhenSyncReturnsNull() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(null);
        assertEquals("SYNCHRONISATION IMPOSSIBLE", service.parkCurrent());
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the missing-draft arm of {@code parkCurrent}: the sync yields an id
     * but the finder resolves to null, so parking answers the not-found error.
     */
    @Test
    void parkCurrentRefusedWhenDraftNotFound() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertEquals("TICKET INTROUVABLE", service.parkCurrent());
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the wrong-status arm of {@code parkCurrent}: the draft is found but
     * is not OPEN, so parking answers the not-found error and nothing is flipped
     * or journaled.
     */
    @Test
    void parkCurrentRefusedWhenDraftNotOpen() {
        TicketParkingService service = newService();
        addOneItem(service);
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET INTROUVABLE", service.parkCurrent());
            verify(draft, never()).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the nominal parking of {@code parkCurrent}: the OPEN draft is
     * flipped to PARKED, persisted, journaled, and the in-memory state is
     * cleared and touched.
     */
    @Test
    void parkCurrentSucceeds() {
        TicketParkingService service = newService();
        addOneItem(service);
        long versionBefore = service.state.version;
        when(service.ticketPersistenceService.syncDraft(service.state)).thenReturn(7L);
        Ticket draft = draft(Ticket.TicketStatus.OPEN, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.parkCurrent());
            assertEquals(Ticket.TicketStatus.PARKED, draft.status);
            verify(draft, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_PARKED, "C04-000001");
            assertTrue(service.state.ticket.items.isEmpty());
            assertEquals(versionBefore + 2, service.state.version);
        }
    }

    /**
     * Verifies that {@code listParked} returns the terminal-scoped, oldest-first
     * parked listing produced by the finder.
     */
    @Test
    void listParkedReturnsFinderResult() {
        TicketParkingService service = newService();
        Ticket parked = mock(Ticket.class);
        List<Ticket> expected = List.of(parked);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list("terminalId = ?1 and status = ?2 order by id",
                    TERMINAL, Ticket.TicketStatus.PARKED)).thenReturn(expected);
            assertSame(expected, service.listParked());
        }
    }

    /**
     * Covers the in-progress-cart guard of {@code resume}: a cart is already
     * open, so the resume is refused and no draft is looked up.
     */
    @Test
    void resumeRefusedWhenCartInProgress() {
        TicketParkingService service = newService();
        addOneItem(service);
        assertEquals("TICKET EN COURS - METTEZ-LE EN ATTENTE D'ABORD", service.resume(7L));
        verifyNoInteractions(service.ticketRecoveryService);
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * Covers the missing-draft arm of {@code resume}: the finder resolves to
     * null, so the resume answers the not-found error.
     */
    @Test
    void resumeRefusedWhenDraftNotFound() {
        TicketParkingService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the wrong-status arm of {@code resume}: the draft is found but is
     * not PARKED, so the resume answers the not-found error and nothing is
     * restored.
     */
    @Test
    void resumeRefusedWhenDraftNotParked() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.OPEN, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verify(draft, never()).persist();
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the foreign-terminal arm of {@code resume}: the draft is PARKED but
     * belongs to another register, so the resume answers the not-found error.
     */
    @Test
    void resumeRefusedWhenDraftOnOtherTerminal() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", "C09");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertEquals("TICKET EN ATTENTE INTROUVABLE", service.resume(7L));
            verifyNoInteractions(service.ticketRecoveryService);
        }
    }

    /**
     * Covers the nominal resume of {@code resume}: the PARKED draft of this
     * register is flipped back to OPEN, persisted, restored into the state and
     * journaled.
     */
    @Test
    void resumeSucceeds() {
        TicketParkingService service = newService();
        Ticket draft = draft(Ticket.TicketStatus.PARKED, "C04-000001", TERMINAL);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(draft);
            assertNull(service.resume(7L));
            assertEquals(Ticket.TicketStatus.OPEN, draft.status);
            verify(draft, times(1)).persist();
            verify(service.ticketRecoveryService).restoreDraft(draft);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_RESUMED, "C04-000001");
        }
    }
}
