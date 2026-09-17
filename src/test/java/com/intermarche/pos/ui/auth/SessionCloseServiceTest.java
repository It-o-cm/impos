package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionCloseService}.
 * <p>
 * Branch enumeration: {@code obstacle} has the pending-and-required arm, the
 * required-but-none-pending arm, the pending-but-not-required arm, the
 * password arm and the clear arm. {@code passwordMatches} has its three guards
 * (no operator, null password, blank password) and both outcomes of the
 * credential check. {@code close} has both arms of {@code forcedBy != null}
 * crossed with the printing and silent arms, and the cause it was given is
 * asserted on both the journal and the receipt. {@code printOpening} has both
 * arms of its parameter.
 */
class SessionCloseServiceTest {

    /**
     * Builds a service with mocked collaborators and a register identifier.
     *
     * @return the wired service
     */
    private SessionCloseService newService() {
        SessionCloseService service = new SessionCloseService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.authService = mock(AuthService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        service.terminalId = "POS07";
        return service;
    }

    /**
     * Builds a register state carrying a signed-in operator.
     *
     * @param badge the operator badge, or null for a locked register
     * @return the state
     */
    private PosState state(String badge) {
        PosState state = mock(PosState.class);
        state.auth = new AuthState();
        if (badge != null) {
            state.auth.login(1L, "Martin Durand", badge);
        }
        return state;
    }

    /**
     * Opens a static mock answering the given number of parked tickets.
     *
     * @param count the number of waiting carts
     * @return the static mock
     */
    private MockedStatic<PanacheEntityBase> parked(long count) {
        MockedStatic<PanacheEntityBase> tickets = mockStatic(PanacheEntityBase.class);
        tickets.when(() -> Ticket.count(anyString(), any(), any())).thenReturn(count);
        return tickets;
    }

    /**
     * Parked tickets and the endorsement in force: that is the first obstacle,
     * before any password question.
     */
    @Test
    void pendingTicketsComeFirst() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(true);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ignored = parked(2L)) {
            assertEquals(SessionCloseService.Obstacle.PENDING_TICKETS, service.obstacle());
        }
    }

    /**
     * The endorsement in force but nothing parked: the password question is
     * reached (the false leg of the compound guard).
     */
    @Test
    void endorsementWithoutPendingTicketsFallsThrough() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(true);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ignored = parked(0L)) {
            assertEquals(SessionCloseService.Obstacle.PASSWORD, service.obstacle());
        }
    }

    /**
     * Tickets parked but the endorsement NOT required: they do not block (the
     * other leg of the same guard).
     */
    @Test
    void pendingTicketsWithoutTheEndorsementDoNotBlock() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(false);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> ignored = parked(3L)) {
            assertEquals(SessionCloseService.Obstacle.NONE, service.obstacle());
        }
    }

    /**
     * Neither question asked: the register closes on the key alone, which is
     * the historical behaviour and the default.
     */
    @Test
    void nothingAskedMeansNoObstacle() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(false);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> ignored = parked(0L)) {
            assertEquals(SessionCloseService.Obstacle.NONE, service.obstacle());
        }
    }

    /**
     * The waiting carts are counted on THIS register.
     */
    @Test
    void pendingCountReadsThisRegister() {
        SessionCloseService service = newService();
        try (MockedStatic<PanacheEntityBase> ignored = parked(5L)) {
            assertEquals(5L, service.pendingCount());
        }
    }

    /**
     * A locked register matches no password: there is no operator to check one
     * against.
     */
    @Test
    void lockedRegisterMatchesNoPassword() {
        SessionCloseService service = newService();
        assertFalse(service.passwordMatches(state(null), "1234"));
    }

    /**
     * A null or blank password is refused without reaching the credential
     * check.
     */
    @Test
    void nullAndBlankPasswordsAreRefused() {
        SessionCloseService service = newService();
        assertFalse(service.passwordMatches(state("007"), null));
        assertFalse(service.passwordMatches(state("007"), "   "));
        verify(service.authService, never()).checkCredentials(anyString(), anyString());
    }

    /**
     * A wrong password is refused, a right one accepted — both outcomes of the
     * credential check.
     */
    @Test
    void theCredentialCheckDecides() {
        SessionCloseService service = newService();
        AuthService.CredentialStatus bad = mock(AuthService.CredentialStatus.class);
        when(bad.isSuccess()).thenReturn(false);
        when(service.authService.checkCredentials("007", "9999")).thenReturn(bad);
        assertFalse(service.passwordMatches(state("007"), "9999"));
        AuthService.CredentialStatus good = mock(AuthService.CredentialStatus.class);
        when(good.isSuccess()).thenReturn(true);
        when(service.authService.checkCredentials("007", "1234")).thenReturn(good);
        assertTrue(service.passwordMatches(state("007"), "1234"));
    }

    /**
     * A plain close logs the operator out, journals no forcing and prints
     * nothing when the back office asked for no receipt.
     */
    @Test
    void plainCloseIsSilent() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printCloseReceipt()).thenReturn(false);
        PosState state = state("007");
        service.close(state, null);
        verify(service.authService).logout(state);
        verify(service.ticketPrinterService, never()).printRenderedTicket(anyString());
        verify(service.technicalEventService, never())
                .log(eq(TechnicalEvent.EventType.SUPERVISOR_CALLED), any(), any());
    }

    /**
     * A forced close journals the forcing, and the printed receipt says so.
     */
    @Test
    void forcedCloseIsJournalledAndPrinted() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printCloseReceipt()).thenReturn(true);
        PosState state = state("007");
        service.close(state, "superviseur en caisse");
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.SUPERVISOR_CALLED), anyString(), eq("007"));
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("FERMETURE DE CAISSE"));
        assertTrue(receipt.getValue().contains("POS07"));
        assertTrue(receipt.getValue().contains("Martin Durand"));
        assertTrue(receipt.getValue().contains("FERMETURE FORCÉE — superviseur en caisse"));
        verify(service.authService).logout(state);
    }

    /**
     * The receipt and the journal name the CAUSE they were given: an automatic
     * close at the end of the period must not tell the shop a supervisor stood
     * at the register (LC-01-02-08).
     */
    @Test
    void theCauseOfTheForcingIsTheOneReported() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printCloseReceipt()).thenReturn(true);
        service.close(state("007"), "fin de période");
        ArgumentCaptor<String> journal = ArgumentCaptor.forClass(String.class);
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.SUPERVISOR_CALLED), journal.capture(), eq("007"));
        assertTrue(journal.getValue().contains("fin de période"));
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("FERMETURE FORCÉE — fin de période"));
        assertFalse(receipt.getValue().contains("superviseur"));
    }

    /**
     * The opening receipt is printed only when the back office asked for one,
     * and it names the register and the operator.
     */
    @Test
    void openingReceiptObeysItsParameter() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printOpenReceipt()).thenReturn(false);
        service.printOpening(state("007"));
        verify(service.ticketPrinterService, never()).printRenderedTicket(anyString());
        when(service.posSettingsService.printOpenReceipt()).thenReturn(true);
        service.printOpening(state("007"));
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("OUVERTURE DE CAISSE"));
        assertTrue(receipt.getValue().contains("POS07"));
        assertFalse(receipt.getValue().contains("FORCÉE"));
    }

    /**
     * A receipt printed for a register with no operator shows dashes rather
     * than the word null — the null arms of both fields.
     */
    @Test
    void receiptWithoutAnOperatorShowsDashes() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printOpenReceipt()).thenReturn(true);
        service.printOpening(state(null));
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("Opérateur : —"));
    }
}
