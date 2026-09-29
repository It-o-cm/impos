package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthService}.
 * <p>
 * The service authenticates operators against {@link Employee} Panache
 * entities and enforces a shared PIN lockout. Under plain {@code mvn test} the
 * entity is not bytecode-enhanced, so its static finders are intercepted with
 * {@link org.mockito.Mockito#mockStatic}: {@code Employee.findActiveLogin} and
 * {@code Employee.hashPassword} resolve to {@link Employee} while the inherited
 * {@code Employee.findById} resolves to {@link PanacheEntityBase}. The resolved
 * employee is itself a Mockito mock so {@code verifyPassword},
 * {@code isCurrentlyLocked} and {@code getFullName} can be driven directly and
 * {@code persist()} is a no-op; its lockout fields are real fields on the mock
 * and are read back to assert the counter/timestamp mutations. The injected
 * {@link TechnicalEventService} and the {@link PosState}/{@link AuthState} pair
 * are mocks used to verify delegation. The config-driven {@code maxAttempts}
 * and {@code lockoutMinutes} fields are set directly since no container injects
 * them here. Every test is isolated and asserts absolute expected values,
 * covering both arms of every null guard, the lockout threshold, the
 * success/invalid/locked login split and each PIN-change validation gate.
 */
class AuthServiceTest {

    /**
     * Builds an {@link AuthService} with a mocked {@link TechnicalEventService}
     * and the two lockout config fields set to their canonical test values
     * (three attempts, five minutes).
     *
     * @return a ready-to-drive service instance
     */
    private AuthService newService() {
        AuthService service = new AuthService();
        service.technicalEventService = mock(TechnicalEventService.class);
        service.maxAttempts = 3;
        service.lockoutMinutes = 5;
        return service;
    }

    /**
     * Builds a mocked {@link PosState} carrying a mocked {@link AuthState} so
     * the auth mailbox and session mutations can be verified.
     *
     * @return a state whose {@code auth} field is a fresh mock
     */
    private PosState newState() {
        PosState state = mock(PosState.class);
        state.auth = mock(AuthState.class);
        return state;
    }

    // --- checkCredentials ---

    /**
     * A null {@code loginInfo} short-circuits the guard and returns a failed,
     * unlocked status without touching the entity (first operand true).
     */
    @Test
    void checkCredentialsRejectsNullLogin() {
        AuthService service = newService();
        AuthService.CredentialStatus status = service.checkCredentials(null, "1234");
        assertFalse(status.isSuccess());
        assertNull(status.employee);
        assertFalse(status.locked);
    }

    /**
     * A non-null login with a null PIN fails the second operand of the guard
     * and returns a failed, unlocked status.
     */
    @Test
    void checkCredentialsRejectsNullPassword() {
        AuthService service = newService();
        AuthService.CredentialStatus status = service.checkCredentials("alice", null);
        assertFalse(status.isSuccess());
        assertNull(status.employee);
        assertFalse(status.locked);
    }

    /**
     * When no active account matches the (lower-cased) login, the finder
     * returns null and the check reports failure without a lock.
     */
    @Test
    void checkCredentialsReturnsFailureWhenAccountUnknown() {
        AuthService service = newService();
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(null);
            AuthService.CredentialStatus status = service.checkCredentials("ALICE", "1234");
            assertFalse(status.isSuccess());
            assertNull(status.employee);
            assertFalse(status.locked);
        }
    }

    /**
     * A currently-locked account is refused with the locked flag set and its
     * PIN is never verified.
     */
    @Test
    void checkCredentialsReturnsLockedWhenAccountLocked() {
        AuthService service = newService();
        Employee employee = mock(Employee.class);
        when(employee.isCurrentlyLocked()).thenReturn(true);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.CredentialStatus status = service.checkCredentials("alice", "1234");
            assertFalse(status.isSuccess());
            assertNull(status.employee);
            assertTrue(status.locked);
            verify(employee, never()).verifyPassword(any());
        }
    }

    /**
     * A correct PIN authenticates the employee, resets the failure counter and
     * clears any residual lock timestamp before persisting.
     */
    @Test
    void checkCredentialsSucceedsAndResetsCounters() {
        AuthService service = newService();
        Employee employee = mock(Employee.class);
        employee.failedAttempts = 2;
        employee.lockedUntil = LocalDateTime.now();
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("1234")).thenReturn(true);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.CredentialStatus status = service.checkCredentials("alice", "1234");
            assertTrue(status.isSuccess());
            assertSame(employee, status.employee);
            assertFalse(status.locked);
            assertEquals(0, employee.failedAttempts);
            assertNull(employee.lockedUntil);
            verify(employee).persist();
        }
    }

    /**
     * A wrong PIN below the threshold increments the counter, leaves the
     * account unlocked, journals the failed attempt by badge (BO-04-01-30) but
     * NOT a lockout (threshold condition false).
     */
    @Test
    void checkCredentialsWrongPinBelowThresholdIncrements() {
        AuthService service = newService();
        Employee employee = mock(Employee.class);
        employee.failedAttempts = 0;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("0000")).thenReturn(false);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.CredentialStatus status = service.checkCredentials("alice", "0000");
            assertFalse(status.isSuccess());
            assertNull(status.employee);
            assertFalse(status.locked);
            assertEquals(1, employee.failedAttempts);
            assertNull(employee.lockedUntil);
            verify(employee).persist();
            verify(service.technicalEventService).log(TechnicalEvent.EventType.PASSWORD_FAILED, null, "12341234");
            verify(service.technicalEventService, never()).log(TechnicalEvent.EventType.AUTH_LOCKED, "alice (5 min)", "12341234");
        }
    }

    /**
     * A wrong PIN reaching the threshold locks the account: the lock timestamp
     * is set, the counter is reset and the lockout is journaled (threshold
     * condition true).
     */
    @Test
    void checkCredentialsWrongPinAtThresholdLocks() {
        AuthService service = newService();
        Employee employee = mock(Employee.class);
        employee.failedAttempts = 2;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("0000")).thenReturn(false);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.CredentialStatus status = service.checkCredentials("alice", "0000");
            assertFalse(status.isSuccess());
            assertNull(status.employee);
            assertTrue(status.locked);
            assertEquals(0, employee.failedAttempts);
            assertTrue(employee.lockedUntil.isAfter(LocalDateTime.now()));
            verify(employee).persist();
            verify(service.technicalEventService).log(TechnicalEvent.EventType.PASSWORD_FAILED, null, "12341234");
            verify(service.technicalEventService).log(TechnicalEvent.EventType.AUTH_LOCKED, "alice (5 min)", "12341234");
        }
    }

    // --- login ---

    /**
     * A successful credential check logs the operator into the session and
     * returns {@code SUCCESS} (success arm of the outcome selection).
     */
    @Test
    void loginSuccessUpdatesSession() {
        AuthService service = newService();
        PosState state = newState();
        Employee employee = mock(Employee.class);
        employee.id = 42L;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("1234")).thenReturn(true);
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "alice", "1234");
            assertEquals(AuthService.LoginResult.SUCCESS, result);
            verify(state.auth).login(42L, "Alice Martin", "12341234");
            verify(service.technicalEventService).log(TechnicalEvent.EventType.REGISTER_UNLOCKED, null, "12341234");
        }
    }

    /**
     * A locked account produces a failed check with the locked flag, so login
     * returns {@code LOCKED} (locked arm of the outcome ternary) and never
     * touches the session.
     */
    @Test
    void loginLockedReturnsLocked() {
        AuthService service = newService();
        PosState state = newState();
        Employee employee = mock(Employee.class);
        when(employee.isCurrentlyLocked()).thenReturn(true);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "alice", "1234");
            assertEquals(AuthService.LoginResult.LOCKED, result);
            verify(state.auth, never()).login(any(), any(), any());
        }
    }

    /**
     * A failed, unlocked check makes login return {@code INVALID} (invalid arm
     * of the outcome ternary).
     */
    @Test
    void loginInvalidReturnsInvalid() {
        AuthService service = newService();
        PosState state = newState();
        Employee employee = mock(Employee.class);
        employee.failedAttempts = 0;
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("0000")).thenReturn(false);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "alice", "0000");
            assertEquals(AuthService.LoginResult.INVALID, result);
            verify(state.auth, never()).login(any(), any(), any());
        }
    }

    // --- logout ---

    /**
     * Logout clears both the session auth and the in-memory ticket, and
     * journals nothing when no operator was signed in (the null-badge arm:
     * /lock is also the landing page of an already-locked register).
     */
    @Test
    void logoutClearsSessionAndTicket() {
        AuthService service = newService();
        PosState state = newState();
        service.logout(state);
        verify(state.auth).logout();
        verify(state).clearTicket();
        verify(service.technicalEventService, never()).log(any(), any(), any());
    }

    /**
     * A deliberate lock journals REGISTER_LOCKED with the operator badge, like
     * the idle timeout does (BO-04-01-27, signed-in arm).
     */
    @Test
    void logoutJournalsTheDeliberateLock() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorBadgeId = "12341234";
        service.logout(state);
        verify(service.technicalEventService)
                .log(TechnicalEvent.EventType.REGISTER_LOCKED, null, "12341234");
        verify(state.auth).logout();
    }

    // --- changePin ---

    /**
     * With no operator logged in, the change is refused up front (operator id
     * null arm).
     */
    @Test
    void changePinFailsWhenNoOperator() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = null;
        assertEquals("Aucun opérateur connecté", service.changePin(state, "1111", "1234", "1234"));
    }

    /**
     * When the logged-in id resolves to no employee, the change is refused
     * (employee null arm).
     */
    @Test
    void changePinFailsWhenEmployeeMissing() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(null);
            assertEquals("Opérateur introuvable", service.changePin(state, "1111", "1234", "1234"));
        }
    }

    /**
     * A null current PIN fails the first operand of the verification guard
     * without calling {@code verifyPassword}.
     */
    @Test
    void changePinFailsWhenCurrentPinNull() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertEquals("Code PIN actuel incorrect", service.changePin(state, null, "1234", "1234"));
            verify(employee, never()).verifyPassword(any());
        }
    }

    /**
     * A non-null but incorrect current PIN fails the second operand of the
     * verification guard.
     */
    @Test
    void changePinFailsWhenCurrentPinWrong() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        when(employee.verifyPassword("9999")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertEquals("Code PIN actuel incorrect", service.changePin(state, "9999", "1234", "1234"));
        }
    }

    /**
     * A null new PIN fails the first operand of the format guard.
     */
    @Test
    void changePinFailsWhenNewPinNull() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertEquals("Le nouveau code doit comporter 4 chiffres", service.changePin(state, "1111", null, "1234"));
        }
    }

    /**
     * A new PIN that is not exactly four digits fails the second operand of the
     * format guard.
     */
    @Test
    void changePinFailsWhenNewPinNotFourDigits() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertEquals("Le nouveau code doit comporter 4 chiffres", service.changePin(state, "1111", "12a4", "12a4"));
        }
    }

    /**
     * A well-formed new PIN that differs from its confirmation is refused
     * (confirmation mismatch arm).
     */
    @Test
    void changePinFailsWhenConfirmationDiffers() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertEquals("Les nouveaux codes ne correspondent pas", service.changePin(state, "1111", "1234", "5678"));
        }
    }

    /**
     * A valid, confirmed new PIN is hashed, persisted and reported as success
     * (null return, all guards passed).
     */
    @Test
    void changePinSucceeds() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        employee.badgeId = "12341234";
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            emp.when(() -> Employee.hashPassword("1234")).thenReturn("HASH");
            assertNull(service.changePin(state, "1111", "1234", "1234"));
            assertEquals("HASH", employee.password);
            verify(employee).persist();
            verify(service.technicalEventService).log(TechnicalEvent.EventType.PASSWORD_CHANGED, null, "12341234");
        }
    }

    // --- login (paused resume branch) & resume ---

    /**
     * On a paused register, login delegates to the resume path: the locker's
     * matching badge and correct PIN restore the operator and journal the
     * reprise (isPaused branch true, resume success arm).
     */
    @Test
    void loginResumesPausedRegisterOnMatchingLockerCredentials() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorBadgeId = "12341234";
        Employee employee = mock(Employee.class);
        employee.id = 42L;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("1234")).thenReturn(true);
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("12341234")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "12341234", "1234");
            assertEquals(AuthService.LoginResult.SUCCESS, result);
            verify(state.auth).login(42L, "Alice Martin", "12341234");
            verify(service.technicalEventService).log(TechnicalEvent.EventType.REGISTER_UNLOCKED, null, "12341234");
        }
    }

    /**
     * A paused resume with a null login fails the first operand of the identity
     * guard and returns INVALID without touching the session.
     */
    @Test
    void resumeRejectsNullLogin() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorBadgeId = "12341234";
        AuthService.LoginResult result = service.login(state, null, "1234");
        assertEquals(AuthService.LoginResult.INVALID, result);
        verify(state.auth, never()).login(any(), any(), any());
    }

    /**
     * A paused resume by an operator other than the locker fails the second
     * operand of the identity guard (badge mismatch) and returns INVALID.
     */
    @Test
    void resumeRejectsAnotherOperator() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorBadgeId = "12341234";
        AuthService.LoginResult result = service.login(state, "99999999", "1234");
        assertEquals(AuthService.LoginResult.INVALID, result);
        verify(state.auth, never()).login(any(), any(), any());
    }

    /**
     * A paused resume by the locker whose account is locked out returns LOCKED
     * (resume's locked arm).
     */
    @Test
    void resumeReturnsLockedWhenAccountLocked() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorBadgeId = "12341234";
        Employee employee = mock(Employee.class);
        when(employee.isCurrentlyLocked()).thenReturn(true);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("12341234")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "12341234", "1234");
            assertEquals(AuthService.LoginResult.LOCKED, result);
            verify(state.auth, never()).login(any(), any(), any());
        }
    }

    /**
     * A paused resume by the locker with a wrong PIN returns INVALID (resume's
     * invalid arm, account not locked).
     */
    @Test
    void resumeReturnsInvalidOnWrongPin() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorBadgeId = "12341234";
        Employee employee = mock(Employee.class);
        employee.failedAttempts = 0;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("0000")).thenReturn(false);
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("12341234")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "12341234", "0000");
            assertEquals(AuthService.LoginResult.INVALID, result);
            verify(state.auth, never()).login(any(), any(), any());
        }
    }

    // --- pause ---

    /**
     * Pausing a manned register journals REGISTER_LOCKED with the operator badge
     * and pauses the auth state (signed-in arm).
     */
    @Test
    void pauseJournalsWhenOperatorSignedIn() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorBadgeId = "12341234";
        service.pause(state);
        verify(service.technicalEventService)
                .log(TechnicalEvent.EventType.REGISTER_LOCKED, null, "12341234");
        verify(state.auth).pause();
    }

    /**
     * Pausing a register with no operator signed in pauses the state but
     * journals nothing (null-badge arm).
     */
    @Test
    void pauseSkipsJournalWhenNoOperator() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorBadgeId = null;
        service.pause(state);
        verify(service.technicalEventService, never()).log(any(), any(), any());
        verify(state.auth).pause();
    }

    // --- forceResume ---

    /**
     * A forced resume on a register that is not paused does nothing and reports
     * false (not-paused arm).
     */
    @Test
    void forceResumeFalseWhenNotPaused() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(false);
        assertFalse(service.forceResume(state));
        verify(state.auth, never()).login(any(), any(), any());
    }

    /**
     * A forced resume whose locked-operator id resolves to no employee reports
     * false without logging anyone in (employee-null arm).
     */
    @Test
    void forceResumeFalseWhenLockedOperatorMissing() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorId = 7L;
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(null);
            assertFalse(service.forceResume(state));
            verify(state.auth, never()).login(any(), any(), any());
        }
    }

    /**
     * A forced resume of a paused register logs the locked operator back in, sets
     * the forced-password-change flag and journals the forcing (success arm).
     */
    @Test
    void forceResumeLogsLockedOperatorBackInAndForcesPinChange() {
        AuthService service = newService();
        PosState state = newState();
        when(state.auth.isPaused()).thenReturn(true);
        state.auth.lockedOperatorId = 7L;
        Employee employee = mock(Employee.class);
        employee.id = 7L;
        employee.badgeId = "12341234";
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            assertTrue(service.forceResume(state));
            verify(state.auth).login(7L, "Alice Martin", "12341234");
            assertTrue(state.auth.passwordChangeRequired);
            verify(service.technicalEventService)
                    .log(TechnicalEvent.EventType.REGISTER_UNLOCKED, "forçage superviseur", "12341234");
        }
    }

    /**
     * A successful PIN change clears the forced-password-change flag imposed by a
     * forced resume (EXP-14).
     */
    @Test
    void changePinClearsForcedPasswordFlagOnSuccess() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorId = 7L;
        state.auth.passwordChangeRequired = true;
        Employee employee = mock(Employee.class);
        employee.badgeId = "12341234";
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            emp.when(() -> Employee.hashPassword("1234")).thenReturn("HASH");
            assertNull(service.changePin(state, "1111", "1234", "1234"));
            assertFalse(state.auth.passwordChangeRequired);
        }
    }

    // --- badgeOpensAlone ---

    /**
     * A keyed non-blank password disqualifies the badge-alone opening at the
     * first guard (both operands true: password non-null and non-blank), so the
     * settings service is never consulted.
     */
    @Test
    void badgeOpensAloneRejectsWhenPasswordKeyed() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        PosState state = newState();
        assertFalse(service.badgeOpensAlone(state, "alice", "1234"));
        verify(service.posSettingsService, never()).passwordRequiredOnOpen();
    }

    /**
     * A null password makes the first guard's first operand false; with the
     * opening password turned off and the presented badge matching the keyed
     * identifier, the badge opens the register alone (ternary both operands
     * true).
     */
    @Test
    void badgeOpensAloneAllowsWhenNullPasswordAndBadgeMatches() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(false);
        PosState state = newState();
        when(state.auth.takePresentedBadge()).thenReturn("alice");
        assertTrue(service.badgeOpensAlone(state, "alice", null));
    }

    /**
     * A blank password fails the first guard's second operand (isBlank true);
     * with the opening password off but no badge physically presented, the
     * ternary's first operand is false and the opening is refused.
     */
    @Test
    void badgeOpensAloneRejectsWhenBlankPasswordAndNoPresentedBadge() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(false);
        PosState state = newState();
        when(state.auth.takePresentedBadge()).thenReturn(null);
        assertFalse(service.badgeOpensAlone(state, "alice", "   "));
    }

    /**
     * The back office requiring a password on open disqualifies the badge-alone
     * opening at the second guard (passwordRequiredOnOpen true), so the presented
     * badge is never read.
     */
    @Test
    void badgeOpensAloneRejectsWhenPasswordRequiredOnOpen() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(true);
        PosState state = newState();
        assertFalse(service.badgeOpensAlone(state, "alice", null));
        verify(state.auth, never()).takePresentedBadge();
    }

    /**
     * A badge physically presented that does not match the keyed identifier
     * fails the ternary's second operand (equals false) and refuses the opening.
     */
    @Test
    void badgeOpensAloneRejectsWhenPresentedBadgeDiffers() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(false);
        PosState state = newState();
        when(state.auth.takePresentedBadge()).thenReturn("99999999");
        assertFalse(service.badgeOpensAlone(state, "alice", null));
    }

    // --- login (badge-alone path) ---

    /**
     * A badge presented on a password-free register opens it alone: the operator
     * is logged into the session and REGISTER_UNLOCKED is journaled (badge-alone
     * branch true, employee found arm).
     */
    @Test
    void loginBadgeAloneSuccess() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(false);
        PosState state = newState();
        when(state.auth.takePresentedBadge()).thenReturn("12341234");
        Employee employee = mock(Employee.class);
        employee.id = 42L;
        employee.badgeId = "12341234";
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("12341234")).thenReturn(employee);
            AuthService.LoginResult result = service.login(state, "12341234", null);
            assertEquals(AuthService.LoginResult.SUCCESS, result);
            verify(state.auth).login(42L, "Alice Martin", "12341234");
            verify(service.technicalEventService).log(TechnicalEvent.EventType.REGISTER_UNLOCKED, null, "12341234");
        }
    }

    /**
     * A badge that opens the register alone but resolves to no active account
     * returns INVALID without touching the session (badge-alone branch true,
     * employee-null arm).
     */
    @Test
    void loginBadgeAloneUnknownAccountReturnsInvalid() {
        AuthService service = newService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.passwordRequiredOnOpen()).thenReturn(false);
        PosState state = newState();
        when(state.auth.takePresentedBadge()).thenReturn("12341234");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("12341234")).thenReturn(null);
            AuthService.LoginResult result = service.login(state, "12341234", null);
            assertEquals(AuthService.LoginResult.INVALID, result);
            verify(state.auth, never()).login(any(), any(), any());
        }
    }
}
