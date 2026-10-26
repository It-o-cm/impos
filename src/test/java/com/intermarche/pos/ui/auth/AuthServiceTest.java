package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
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
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        service.i18n = TestMessages.french();
        service.technicalEventService = mock(TechnicalEventService.class);
        // The back-office parameters at their catalog defaults: a sign-in reads
        // the dedicated-return-register flag (LC-05-01-03), so the collaborator
        // can no longer be left null. Cases that drive a parameter replace this
        // mock with their own.
        service.posSettingsService = mock(PosSettingsService.class);
        // BO-10-02-23: the PIN length at its catalog default.
        when(service.posSettingsService.pinLength()).thenReturn(4);
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
        // A mock never runs PosState's field initialiser, and the logout
        // clears the endorsement mailbox: give it like the real state does.
        state.endorsement = new com.intermarche.pos.ui.endorsement.EndorsementState();
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
     * On a register the back office dedicated to returns (LC-05-01-03), a
     * successful sign-in finds the till already in return mode — the guard arm
     * where the parameter is on.
     */
    @Test
    void loginForcesReturnModeOnADedicatedRegister() {
        AuthService service = newService();
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(true);
        PosState state = newState();
        Employee employee = mock(Employee.class);
        employee.id = 42L;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("1234")).thenReturn(true);
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            assertEquals(AuthService.LoginResult.SUCCESS, service.login(state, "alice", "1234"));
            assertTrue(state.returnMode);
        }
    }

    /**
     * On an ordinary register the sign-in leaves the return mode alone
     * (LC-05-01-03, parameter off).
     */
    @Test
    void loginLeavesReturnModeAloneOnAnOrdinaryRegister() {
        AuthService service = newService();
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(false);
        PosState state = newState();
        Employee employee = mock(Employee.class);
        employee.id = 42L;
        employee.badgeId = "12341234";
        when(employee.isCurrentlyLocked()).thenReturn(false);
        when(employee.verifyPassword("1234")).thenReturn(true);
        when(employee.getFullName()).thenReturn("Alice Martin");
        try (MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            emp.when(() -> Employee.findActiveLogin("alice")).thenReturn(employee);
            assertEquals(AuthService.LoginResult.SUCCESS, service.login(state, "alice", "1234"));
            assertFalse(state.returnMode);
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
     * A logout with NOBODY signed in keeps the chosen language (LC-13-06-03).
     *
     * <p>{@code GET /lock} logs out at every render to reset a free register, so a
     * language dropped unconditionally here was erased a millisecond after the
     * operator chose it — on the one screen where an operator who cannot read the
     * till's language is able to choose one.
     */
    @Test
    void logoutKeepsTheChosenLanguageWhenNobodyWasSignedIn() {
        AuthService service = newService();
        PosState state = newState();
        state.language = "nl";
        service.logout(state);
        assertEquals("nl", state.language);
    }

    /**
     * A logout of a SIGNED-IN operator drops the chosen language: it belonged to
     * that operator, not to the register (LC-13-06-02).
     */
    @Test
    void logoutDropsTheChosenLanguageOfTheOperator() {
        AuthService service = newService();
        PosState state = newState();
        state.auth.operatorBadgeId = "12341234";
        state.language = "nl";
        service.logout(state);
        assertNull(state.language);
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
                .log(TechnicalEvent.EventType.REGISTER_LOCKED, TechnicalEvent.LOCK_MANUAL, "12341234");
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
     * BO-10-02-23: a shop administering six digits refuses a four-digit PIN
     * with the administered count, and accepts six digits.
     */
    @Test
    void changePinFollowsTheAdministeredLength() {
        AuthService service = newService();
        when(service.posSettingsService.pinLength()).thenReturn(6);
        PosState state = newState();
        state.auth.operatorId = 7L;
        Employee employee = mock(Employee.class);
        employee.badgeId = "12341234";
        when(employee.verifyPassword("1111")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> pe = mockStatic(PanacheEntityBase.class);
             MockedStatic<Employee> emp = mockStatic(Employee.class)) {
            pe.when(() -> Employee.findById(7L)).thenReturn(employee);
            emp.when(() -> Employee.hashPassword("123456")).thenReturn("HASH6");
            assertEquals("Le nouveau code doit comporter 6 chiffres", service.changePin(state, "1111", "1234", "1234"));
            assertNull(service.changePin(state, "1111", "123456", "123456"));
            assertEquals("HASH6", employee.password);
        }
    }

    /**
     * BO-10-02-23: a service built without its parameters keeps four digits.
     */
    @Test
    void pinLengthDefaultsToFourWithoutSettings() {
        AuthService service = newService();
        assertEquals(4, service.pinLength());
        service.posSettingsService = null;
        assertEquals(4, service.pinLength());
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
                .log(TechnicalEvent.EventType.REGISTER_LOCKED, TechnicalEvent.LOCK_MANUAL, "12341234");
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
    /** BO-01-02-12: password expiry is evaluated only with both collaborators and forces renewal when expired. */
    @Test
    void passwordExpiryCoversMissingCollaboratorsAndExpiredSecret() throws Exception {
        AuthService service = newService();
        PosState state = newState();
        var employee = mock(com.intermarche.pos.domain.people.Employee.class);
        var method = AuthService.class.getDeclaredMethod("applyPasswordExpiry", PosState.class, com.intermarche.pos.domain.people.Employee.class);
        method.setAccessible(true);
        var settings = service.posSettingsService;
        service.posSettingsService = null;
        method.invoke(service, state, employee);
        service.posSettingsService = settings;
        method.invoke(service, state, null);
        when(settings.passwordExpiryDays()).thenReturn(30);
        when(employee.secretExpired(30)).thenReturn(true);
        method.invoke(service, state, employee);
        assertTrue(state.auth.passwordChangeRequired);
    }

    // --- dallasLogin (BO-01-02-07) ---

    /**
     * Builds an active holder of the Dallas key {@code 01A2B3C4D5E6F708}.
     *
     * @return the holder
     */
    private Employee dallasHolder() {
        Employee holder = new Employee();
        holder.id = 9L;
        holder.firstName = "Ines";
        holder.lastName = "Sco";
        holder.badgeId = "77777777";
        holder.dallasKey = "01A2B3C4D5E6F708";
        holder.active = true;
        return holder;
    }

    /**
     * Runs a Dallas sign-in with the given holder found for the key.
     *
     * @param service the service
     * @param state the register state
     * @param holder the account holding the key, possibly null
     * @param loginInfo the presented identifier
     * @param rawPassword the keyed password
     * @return the outcome
     */
    @SuppressWarnings("unchecked")
    private AuthService.LoginResult dallas(AuthService service, PosState state, Employee holder,
                                           String loginInfo, String rawPassword) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<Employee> query = mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.firstResult()).thenReturn(holder);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> PanacheEntityBase.find("dallasKey = ?1 and active = true", "01A2B3C4D5E6F708")).thenReturn(query);
            return service.login(state, loginInfo, rawPassword);
        }
    }

    /**
     * The key read by this register opens it alone when the back office allows
     * it: the holder is signed in and the opening journalled.
     */
    @Test
    void aReadDallasKeyOpensTheRegisterAlone() {
        AuthService service = newService();
        when(service.posSettingsService.dallasKeyOpens()).thenReturn(true);
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(true);
        PosState state = newState();
        when(state.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.SUCCESS, dallas(service, state, dallasHolder(), "DALLAS:01a2b3c4d5e6f708", null));
        verify(state.auth).login(9L, "Ines Sco", "77777777");
        assertTrue(state.returnMode);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.REGISTER_UNLOCKED, null, "77777777");
    }

    /**
     * A blank keyed password is no password: the key still opens.
     */
    @Test
    void aBlankPasswordDoesNotStopTheDallasKey() {
        AuthService service = newService();
        when(service.posSettingsService.dallasKeyOpens()).thenReturn(true);
        PosState state = newState();
        when(state.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.SUCCESS, dallas(service, state, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", "  "));
    }

    /**
     * Every missing condition refuses the key: the parameter off, a PIN keyed,
     * no key read, another key read, nobody holding the key.
     */
    @Test
    void aDallasKeyIsRefusedWhenAConditionIsMissing() {
        AuthService off = newService();
        PosState state = newState();
        when(state.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.INVALID, dallas(off, state, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", null));
        AuthService on = newService();
        when(on.posSettingsService.dallasKeyOpens()).thenReturn(true);
        assertEquals(AuthService.LoginResult.INVALID, dallas(on, state, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", "1234"));
        PosState unread = newState();
        assertEquals(AuthService.LoginResult.INVALID, dallas(on, unread, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", null));
        PosState other = newState();
        when(other.auth.takePresentedDallasKey()).thenReturn("FFFFFFFFFFFFFFFF");
        assertEquals(AuthService.LoginResult.INVALID, dallas(on, other, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", null));
        PosState orphan = newState();
        when(orphan.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.INVALID, dallas(on, orphan, null, "DALLAS:01A2B3C4D5E6F708", null));
        verify(orphan.auth, never()).login(any(), any(), any());
    }

    /**
     * An account locked out after PIN failures stays locked for its key too.
     */
    @Test
    void aLockedAccountIsNotOpenedByItsDallasKey() {
        AuthService service = newService();
        when(service.posSettingsService.dallasKeyOpens()).thenReturn(true);
        PosState state = newState();
        when(state.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        Employee holder = dallasHolder();
        holder.lockedUntil = LocalDateTime.now().plusMinutes(5);
        assertEquals(AuthService.LoginResult.LOCKED, dallas(service, state, holder, "DALLAS:01A2B3C4D5E6F708", null));
    }

    /**
     * A paused register reopens for the key of the operator who paused it
     * only, without the dedicated-return switch of a new sign-in.
     */
    @Test
    void aPausedRegisterReopensOnlyForItsOperatorsKey() {
        AuthService service = newService();
        when(service.posSettingsService.dallasKeyOpens()).thenReturn(true);
        when(service.posSettingsService.returnDedicatedRegister()).thenReturn(true);
        PosState mine = newState();
        when(mine.auth.isPaused()).thenReturn(true);
        mine.auth.lockedOperatorBadgeId = "77777777";
        when(mine.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.SUCCESS, dallas(service, mine, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", null));
        assertFalse(mine.returnMode);
        PosState theirs = newState();
        when(theirs.auth.isPaused()).thenReturn(true);
        theirs.auth.lockedOperatorBadgeId = "12341234";
        when(theirs.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        assertEquals(AuthService.LoginResult.INVALID, dallas(service, theirs, dallasHolder(), "DALLAS:01A2B3C4D5E6F708", null));
        PosState badgeless = newState();
        when(badgeless.auth.isPaused()).thenReturn(true);
        badgeless.auth.lockedOperatorBadgeId = "77777777";
        when(badgeless.auth.takePresentedDallasKey()).thenReturn("01A2B3C4D5E6F708");
        Employee noBadge = dallasHolder();
        noBadge.badgeId = null;
        assertEquals(AuthService.LoginResult.INVALID, dallas(service, badgeless, noBadge, "DALLAS:01A2B3C4D5E6F708", null));
    }
}
