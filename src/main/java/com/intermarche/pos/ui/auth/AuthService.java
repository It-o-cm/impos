package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;

/**
 * Authentication of operators at the register.
 * <p>
 * Phase 2: the register unlock and the manager endorsement both go through
 * the shared {@link #checkCredentials(String, String)} with PIN lockout —
 * after {@code pos.auth.max-attempts} consecutive failures the account is
 * locked for {@code pos.auth.lockout-minutes} minutes and the lockout is
 * journaled. The two flows therefore share ONE failure counter: a manager
 * burning attempts on an endorsement locks their register login too, which
 * is deliberate (one credential, one lockout). Exception: the PIN change
 * verifies the current PIN directly, outside the lockout.
 * <p>
 * Lockout mechanics worth knowing: locking RESETS the failure counter, so
 * the window after a lockout expires starts clean; a successful PIN resets
 * both counter and lock; and the pair (failedAttempts, lockedUntil) is
 * LOCAL operational state — the phase 6 referential pull never touches it,
 * a lock neither spreads to other registers nor gets reset by a sync.
 * <p>
 * {@code logout} clears the in-memory cart but NOT the persisted draft: a
 * lock in mid-sale abandons the sale (empty screen at next login), and the
 * stale OPEN draft is cancelled by the recovery of the next register
 * restart — the single-draft invariant absorbs it as a documented sequence
 * gap.
 */
@ApplicationScoped
public class AuthService {

    /** Number of consecutive PIN failures triggering a lockout. */
    @ConfigProperty(name = "pos.auth.max-attempts", defaultValue = "3")
    int maxAttempts;

    /** Lockout duration in minutes after repeated PIN failures. */
    @ConfigProperty(name = "pos.auth.lockout-minutes", defaultValue = "5")
    int lockoutMinutes;

    @Inject
    TechnicalEventService technicalEventService;

    /** Outcome of a login attempt. */
    public enum LoginResult {
        /** Credentials valid; the operator is logged in. */
        SUCCESS,
        /** Credentials invalid (unknown account or wrong PIN). */
        INVALID,
        /** Account locked after repeated PIN failures. */
        LOCKED
    }

    /**
     * Result of a credential check: the authenticated employee on success,
     * and a locked flag when the account is under a PIN lockout.
     */
    public static final class CredentialStatus {
        /** The authenticated employee, or null when the check failed. */
        public final Employee employee;
        /** True when the account is currently locked out. */
        public final boolean locked;

        /**
         * Creates a credential status.
         *
         * @param employee the authenticated employee, or null
         * @param locked true when the account is locked out
         */
        private CredentialStatus(Employee employee, boolean locked) {
            this.employee = employee;
            this.locked = locked;
        }

        /**
         * Indicates whether the check succeeded.
         *
         * @return true when an employee was authenticated
         */
        public boolean isSuccess() {
            return employee != null;
        }
    }

    /**
     * Verifies a login / PIN pair with lockout enforcement: a locked account
     * is refused without checking the PIN; a wrong PIN increments the failure
     * counter and locks the account after the configured threshold (journaled);
     * a correct PIN resets the counter.
     *
     * @param loginInfo the login identifier (badge id or login name)
     * @param rawPassword the raw PIN entered
     * @return the credential status (employee on success, locked flag)
     */
    @Transactional
    public CredentialStatus checkCredentials(String loginInfo, String rawPassword) {
        if (loginInfo == null || rawPassword == null) {
            return new CredentialStatus(null, false);
        }
        Employee employee = Employee.findActiveLogin(loginInfo.toLowerCase());
        if (employee == null) {
            return new CredentialStatus(null, false);
        }
        if (employee.isCurrentlyLocked()) {
            return new CredentialStatus(null, true);
        }
        if (employee.verifyPassword(rawPassword)) {
            employee.failedAttempts = 0;
            employee.lockedUntil = null;
            employee.persist();
            return new CredentialStatus(employee, false);
        }
        employee.failedAttempts++;
        boolean nowLocked = false;
        if (employee.failedAttempts >= maxAttempts) {
            employee.lockedUntil = LocalDateTime.now().plusMinutes(lockoutMinutes);
            employee.failedAttempts = 0;
            nowLocked = true;
            technicalEventService.log(TechnicalEvent.EventType.AUTH_LOCKED,
                    loginInfo + " (" + lockoutMinutes + " min)");
        }
        employee.persist();
        return new CredentialStatus(null, nowLocked);
    }

    /**
     * Authenticates an employee from a login and a raw PIN, updating the
     * session on success.
     *
     * @param state the current POS state
     * @param loginInfo the login identifier (badge id or login name)
     * @param rawPassword the raw PIN entered
     * @return the login outcome (success, invalid credentials, or locked account)
     */
    public LoginResult login(PosState state, String loginInfo, String rawPassword) {
        CredentialStatus status = checkCredentials(loginInfo, rawPassword);
        if (status.isSuccess()) {
            state.auth.login(status.employee.id, status.employee.getFullName());
            return LoginResult.SUCCESS;
        }
        return status.locked ? LoginResult.LOCKED : LoginResult.INVALID;
    }

    /**
     * Logs the current operator out and clears the ticket.
     *
     * @param state the current POS state
     */
    public void logout(PosState state) {
        state.auth.logout();
        state.clearTicket();
    }

    /**
     * Changes the PIN of the currently logged-in operator.
     * <p>
     * Validates the current PIN, the format of the new PIN (4 digits) and its
     * confirmation before persisting the new hashed PIN.
     *
     * @param state the current POS state holding the logged-in operator id
     * @param currentPin the operator's current PIN
     * @param newPin the desired new PIN
     * @param confirmPin the confirmation of the new PIN
     * @return null on success, or an error message describing why the change failed
     */
    @Transactional
    public String changePin(PosState state, String currentPin, String newPin, String confirmPin) {
        Long operatorId = state.auth.operatorId;
        if (operatorId == null) {
            return "Aucun opérateur connecté";
        }
        Employee employee = Employee.findById(operatorId);
        if (employee == null) {
            return "Opérateur introuvable";
        }
        if (currentPin == null || !employee.verifyPassword(currentPin)) {
            return "Code PIN actuel incorrect";
        }
        if (newPin == null || !newPin.matches("\\d{4}")) {
            return "Le nouveau code doit comporter 4 chiffres";
        }
        if (!newPin.equals(confirmPin)) {
            return "Les nouveaux codes ne correspondent pas";
        }
        employee.password = Employee.hashPassword(newPin);
        employee.persist();
        return null;
    }
}
