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
        service.cashSessionService = mock(com.intermarche.pos.service.CashSessionService.class);
        service.ticketPersistenceService = mock(com.intermarche.pos.service.TicketPersistenceService.class);
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
     * Builds a real {@link AuthService.CredentialStatus} through its private
     * constructor, so the lockout flag — a final field no mock can stub — is
     * the one the service will read.
     *
     * @param success true for an authenticated check
     * @param locked true for an account under PIN lockout
     * @return a credential status carrying those two answers
     */
    private AuthService.CredentialStatus status(boolean success, boolean locked) {
        try {
            java.lang.reflect.Constructor<AuthService.CredentialStatus> ctor =
                    AuthService.CredentialStatus.class.getDeclaredConstructor(
                            com.intermarche.pos.domain.people.Employee.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    success ? new com.intermarche.pos.domain.people.Employee() : null, locked);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- passwordOutcome ---

    /**
     * {@code passwordOutcome()} answers DENIED on a locked register, with no
     * operator to check a password against (first guard, badge-null arm).
     */
    @Test
    void passwordOutcomeDeniesOnALockedRegister() {
        SessionCloseService service = newService();
        assertEquals(AuthService.CheckOutcome.DENIED,
                service.passwordOutcome(state(null), "1234"));
        verify(service.authService, never()).checkCredentials(anyString(), anyString());
    }

    /**
     * {@code passwordOutcome()} answers DENIED on a null and on a blank
     * password, without reaching the credential check (the two other guards).
     */
    @Test
    void passwordOutcomeDeniesAnEmptyField() {
        SessionCloseService service = newService();
        assertEquals(AuthService.CheckOutcome.DENIED, service.passwordOutcome(state("007"), null));
        assertEquals(AuthService.CheckOutcome.DENIED, service.passwordOutcome(state("007"), "   "));
        verify(service.authService, never()).checkCredentials(anyString(), anyString());
    }

    /**
     * {@code passwordOutcome()} answers GRANTED on a right password
     * (isSuccess true arm).
     */
    @Test
    void passwordOutcomeGrantsARightCode() {
        SessionCloseService service = newService();
        when(service.authService.checkCredentials("007", "1234")).thenReturn(status(true, false));
        assertEquals(AuthService.CheckOutcome.GRANTED,
                service.passwordOutcome(state("007"), "1234"));
    }

    /**
     * {@code passwordOutcome()} answers DENIED on a wrong password that is not
     * a lockout (isSuccess false, locked false).
     */
    @Test
    void passwordOutcomeDeniesAWrongCode() {
        SessionCloseService service = newService();
        when(service.authService.checkCredentials("007", "9999")).thenReturn(status(false, false));
        assertEquals(AuthService.CheckOutcome.DENIED,
                service.passwordOutcome(state("007"), "9999"));
    }

    /**
     * {@code passwordOutcome()} answers LOCKED when the operator's own account
     * is under PIN lockout (isSuccess false, locked true). This is the case the
     * closing screen used to call "code opérateur incorrect", which sent the
     * operator retyping a code that was right.
     */
    @Test
    void passwordOutcomeNamesTheLockout() {
        SessionCloseService service = newService();
        when(service.authService.checkCredentials("007", "1234")).thenReturn(status(false, true));
        assertEquals(AuthService.CheckOutcome.LOCKED,
                service.passwordOutcome(state("007"), "1234"));
    }

    /**
     * The boolean {@code passwordMatches()} is GRANTED and nothing else: a
     * LOCKED outcome answers false, like a wrong code.
     */
    @Test
    void passwordMatchesIsFalseOnALockout() {
        SessionCloseService service = newService();
        when(service.authService.checkCredentials("007", "1234")).thenReturn(status(false, true));
        assertFalse(service.passwordMatches(state("007"), "1234"));
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

    /**
     * A null operator name prints a dash too — the {@code name == null} true
     * arm, which the default {@link AuthState#operatorName} empty string never
     * reaches (an empty name is non-null, so only an explicitly nulled name
     * exercises this leg).
     */
    @Test
    void receiptWithoutAnOperatorNameShowsDash() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printOpenReceipt()).thenReturn(true);
        PosState state = state("007");
        state.auth.operatorName = null;
        service.printOpening(state);
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("Nom       : —"));
    }

    /**
     * Builds a register state whose in-memory ticket is empty or carries one
     * (mock) line, to drive the in-transaction obstacle (EXP-11).
     *
     * @param hasItems whether a sale line is present
     * @return the state carrying a real, populated-or-empty ticket
     */
    private PosState stateWithTicket(boolean hasItems) {
        PosState state = state("007");
        state.ticket = new com.intermarche.pos.ui.ticket.TicketState();
        if (hasItems) {
            state.ticket.items.add(mock(com.intermarche.pos.ui.ticket.TicketState.TicketItem.class));
        }
        return state;
    }

    /**
     * A sale in progress is the first obstacle to a fermeture (EXP-11):
     * non-empty ticket, checked before the forceable obstacles.
     */
    @Test
    void saleInProgressBlocksTheClose() {
        SessionCloseService service = newService();
        assertEquals(SessionCloseService.Obstacle.IN_TRANSACTION,
                service.obstacle(stateWithTicket(true)));
    }

    /**
     * An empty ticket skips the in-transaction check and falls through to the
     * remaining questions (empty-items arm).
     */
    @Test
    void emptyTicketSkipsTheInTransactionCheck() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(false);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> ignored = parked(0L)) {
            assertEquals(SessionCloseService.Obstacle.NONE, service.obstacle(stateWithTicket(false)));
        }
    }

    /**
     * A null state skips the in-transaction check entirely (the historical
     * no-arg contract), reaching the password question.
     */
    @Test
    void nullStateSkipsTheInTransactionCheck() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(false);
        when(service.posSettingsService.passwordRequiredOnClose()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ignored = parked(0L)) {
            assertEquals(SessionCloseService.Obstacle.PASSWORD, service.obstacle(null));
        }
    }

    /**
     * The no-arg {@code obstacle()} delegates to the null-state overload, so it
     * never sees a ticket and behaves exactly as before.
     */
    @Test
    void noArgObstacleDelegatesToNullState() {
        SessionCloseService service = newService();
        when(service.posSettingsService.closeEndorsementOnPending()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ignored = parked(2L)) {
            assertEquals(SessionCloseService.Obstacle.PENDING_TICKETS, service.obstacle());
        }
    }

    /**
     * A forced close from a PAUSED register (EXP-96) has no signed-in operator:
     * the receipt and journal name the abandoned post from the locked-operator
     * identity (the operatorBadgeId-null fallback arm).
     */
    @Test
    void forcedCloseFromPausedRegisterNamesTheLockedOperator() {
        SessionCloseService service = newService();
        when(service.posSettingsService.printCloseReceipt()).thenReturn(true);
        PosState state = state("007");
        state.auth.pause();
        service.close(state, "poste abandonné — superviseur");
        verify(service.technicalEventService)
                .log(eq(TechnicalEvent.EventType.SUPERVISOR_CALLED), anyString(), eq("007"));
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(receipt.capture());
        assertTrue(receipt.getValue().contains("Opérateur : 007"));
        assertTrue(receipt.getValue().contains("Martin Durand"));
    }

    /** LC-01-02-07: active and paused identities close the real session before logout. */
    @Test
    void remoteCloseUsesActiveOrPausedIdentityBeforeLoggingOut() {
        for (boolean paused : java.util.List.of(false, true)) {
            var service = newService();
            var state = new PosState();
            state.auth.login(7L, "Cashier", "007");
            if (paused) state.auth.pause();
            state.payment.ticketDbId = 42L;
            try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
                assertTrue(service.closeRemotely(state, "back-office"));
            }
            var ordered = org.mockito.Mockito.inOrder(service.ticketPersistenceService,
                    service.cashSessionService, service.authService);
            ordered.verify(service.ticketPersistenceService).cancelDraft(42L);
            ordered.verify(service.cashSessionService).forceCloseSession(7L);
            ordered.verify(service.authService).logout(state);
        }
    }

    /** LC-01-02-07: in-flight or partially paid drafts stay pending; completed payments permit closure. */
    @Test
    void remoteClosePreservesUnfinishedPaymentsAndDoesNotCancelCompletedSales() {
        var service = newService();
        var state = new PosState();
        state.payment.paymentInProgress = true;
        try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
            remoteCloseKeepsUnfinishedPayments(service, state);
        }
    }

    /**
     * Plays the unfinished and completed payments against the remote close.
     *
     * @param service the service
     * @param state the register state, its payment in progress
     */
    private void remoteCloseKeepsUnfinishedPayments(SessionCloseService service, PosState state) {
        assertFalse(service.closeRemotely(state, "back-office"));
        state.payment.paymentInProgress = false;
        state.payment.paidAmount = java.math.BigDecimal.ONE;
        assertFalse(service.closeRemotely(state, "back-office"));
        org.mockito.Mockito.verifyNoInteractions(service.cashSessionService, service.authService);
        state.payment.transactionComplete = true;
        state.payment.ticketDbId = 42L;
        assertTrue(service.closeRemotely(state, "back-office"));
        verify(service.cashSessionService).forceCloseSession(null);
        org.mockito.Mockito.verifyNoInteractions(service.ticketPersistenceService);
        verify(service.authService).logout(state);
        state.payment.transactionComplete = false;
        state.payment.paidAmount = java.math.BigDecimal.ZERO;
        state.payment.ticketDbId = null;
        assertTrue(service.closeRemotely(state, "back-office"));
    }

    /** LC-01-02-07: a persistence failure leaves the operator signed in and the order uncompleted. */
    @Test
    void failedRemotePersistenceDoesNotLogout() {
        var service = newService();
        var state = new PosState();
        state.auth.login(7L, "Cashier", "007");
        org.mockito.Mockito.doThrow(new IllegalStateException("database"))
                .when(service.cashSessionService).forceCloseSession(7L);
        try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> service.closeRemotely(state, "back-office"));
        }
        org.mockito.Mockito.verifyNoInteractions(service.authService);
    }

    /**
     * BO-09-01-06, BO-09-02-03: a forced close prints its technical ticket —
     * register, session, operator, cause, declaration, tickets on hold — and
     * journals it, where the store administers it; nothing of the kind
     * otherwise.
     */
    @Test
    void aForcedClosePrintsItsTechnicalTicket() {
        var service = newService();
        when(service.posSettingsService.periodForcedCloseTicket()).thenReturn(true);
        com.intermarche.pos.domain.session.CashSession open = new com.intermarche.pos.domain.session.CashSession();
        open.sessionNumber = "POS07-S00012";
        when(service.cashSessionService.getOpenSession()).thenReturn(open);
        var state = new PosState();
        state.auth.login(7L, "Cashier", "007");
        try (MockedStatic<PanacheEntityBase> tickets = parked(2)) {
            assertTrue(service.closeRemotely(state, "fin de période"));
        }
        ArgumentCaptor<String> printed = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(printed.capture());
        String ticket = printed.getValue();
        assertTrue(ticket.startsWith("TICKET TECHNIQUE\nFERMETURE FORCÉE DE CAISSE\n"));
        assertTrue(ticket.contains("Caisse      : POS07"));
        assertTrue(ticket.contains("Session     : POS07-S00012"));
        assertTrue(ticket.contains("Opérateur   : 007"));
        assertTrue(ticket.contains("Nom         : Cashier"));
        assertTrue(ticket.contains("Cause       : fin de période"));
        assertTrue(ticket.contains("Déclaration : sans comptage"));
        assertTrue(ticket.contains("En attente  : 2 ticket(s) annulé(s)"));
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Ticket technique de fermeture forcée imprimé — fin de période", "007");
        var silent = newService();
        try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
            assertTrue(silent.closeRemotely(new PosState(), "back-office"));
        }
        org.mockito.Mockito.verifyNoInteractions(silent.ticketPrinterService);
    }

    /**
     * BO-09-03-01: the automatic declaration closes the session on its
     * theoretical amount and prints its Z; a session already gone prints no Z;
     * the technical ticket of a paused post names its operator and no session.
     */
    @Test
    void anAutomaticDeclarationClosesOnTheTheoretical() {
        var service = newService();
        when(service.posSettingsService.periodForcedCloseTicket()).thenReturn(true);
        com.intermarche.pos.service.CashSessionService.SessionReport report =
                new com.intermarche.pos.service.CashSessionService.SessionReport();
        when(service.cashSessionService.closeSession(7L, null, null, null, true)).thenReturn(report);
        var state = new PosState();
        state.auth.login(7L, "Cashier", "007");
        state.auth.pause();
        try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
            assertTrue(service.closeRemotely(state, "fin de période", true));
        }
        verify(service.ticketPrinterService).printSessionReport(report);
        verify(service.cashSessionService, never()).forceCloseSession(any());
        ArgumentCaptor<String> printed = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(printed.capture());
        assertTrue(printed.getValue().contains("Déclaration : automatique (théorique)"));
        assertTrue(printed.getValue().contains("Session     : —"));
        assertTrue(printed.getValue().contains("Opérateur   : 007"));
        assertTrue(printed.getValue().contains("Nom         : Cashier"));
        var gone = newService();
        try (MockedStatic<PanacheEntityBase> tickets = parked(0)) {
            assertTrue(gone.closeRemotely(new PosState(), "fin de période", true));
        }
        verify(gone.ticketPrinterService, never()).printSessionReport(any());
    }

    /**
     * BO-09-01-06, BO-09-02-03: a post left signed out is closed all the same,
     * its technical ticket and its journal line naming the host who opened
     * the session; nobody holding the post, it is signed out, not closed.
     */
    @Test
    void aForcedCloseOfASignedOutPostNamesTheSessionOpener() {
        var service = newService();
        when(service.posSettingsService.periodForcedCloseTicket()).thenReturn(true);
        com.intermarche.pos.domain.people.Employee opener = new com.intermarche.pos.domain.people.Employee();
        opener.badgeId = "12341234";
        opener.firstName = "Jean";
        opener.lastName = "Dupont";
        com.intermarche.pos.domain.session.CashSession open = new com.intermarche.pos.domain.session.CashSession();
        open.sessionNumber = "POS01-S00001";
        open.openingCashier = opener;
        when(service.cashSessionService.getOpenSession()).thenReturn(open);
        var state = new PosState();
        try (MockedStatic<PanacheEntityBase> tickets = parked(1)) {
            assertTrue(service.closeRemotely(state, "fin de période"));
        }
        ArgumentCaptor<String> printed = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(printed.capture());
        assertTrue(printed.getValue().contains("Opérateur   : 12341234"));
        assertTrue(printed.getValue().contains("Nom         : Jean Dupont"));
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Ticket technique de fermeture forcée imprimé — fin de période", "12341234");
        verify(service.authService).logout(state);
    }

    /**
     * The technical ticket of a post nobody held names no operator.
     */
    @Test
    void aTechnicalTicketWithoutOperator() {
        var service = newService();
        service.printTechnicalTicket(null, null, null, "back-office", false, 0);
        ArgumentCaptor<String> printed = ArgumentCaptor.forClass(String.class);
        verify(service.ticketPrinterService).printRenderedTicket(printed.capture());
        assertTrue(printed.getValue().contains("Opérateur   : —"));
        assertTrue(printed.getValue().contains("Nom         : —"));
    }

}
