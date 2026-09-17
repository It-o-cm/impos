package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import org.jboss.logging.Logger;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(AuthService.class);

    /** Number of consecutive PIN failures triggering a lockout. */
    @ConfigProperty(name = "pos.auth.max-attempts", defaultValue = "3")
    int maxAttempts;

    /** Lockout duration in minutes after repeated PIN failures. */
    @ConfigProperty(name = "pos.auth.lockout-minutes", defaultValue = "5")
    int lockoutMinutes;

    @Inject
    TechnicalEventService technicalEventService;

    /** The administered parameters governing how the register opens. */
    @Inject
    PosSettingsService posSettingsService;

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
        LOGGER.info("Entering method checkCredentials with loginInfo: " + loginInfo + ", rawPassword: ***");
        if (loginInfo == null || rawPassword == null) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, false);
        }
        Employee employee = Employee.findActiveLogin(loginInfo.toLowerCase());
        if (employee == null) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, false);
        }
        if (employee.isCurrentlyLocked()) {
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(null, true);
        }
        if (employee.verifyPassword(rawPassword)) {
            employee.failedAttempts = 0;
            employee.lockedUntil = null;
            employee.persist();
            LOGGER.info("Exiting method checkCredentials");
            return new CredentialStatus(employee, false);
        }
        employee.failedAttempts++;
        technicalEventService.log(TechnicalEvent.EventType.PASSWORD_FAILED, null, employee.badgeId);
        boolean nowLocked = false;
        if (employee.failedAttempts >= maxAttempts) {
            employee.lockedUntil = LocalDateTime.now().plusMinutes(lockoutMinutes);
            employee.failedAttempts = 0;
            nowLocked = true;
            technicalEventService.log(TechnicalEvent.EventType.AUTH_LOCKED,
                    loginInfo + " (" + lockoutMinutes + " min)", employee.badgeId);
        }
        employee.persist();
        LOGGER.info("Exiting method checkCredentials");
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
        LOGGER.info("Entering method login with state: " + state + ", loginInfo: " + loginInfo + ", rawPassword: ***");
        if (badgeOpensAlone(state, loginInfo, rawPassword)) {
            Employee employee = Employee.findActiveLogin(loginInfo);
            if (employee != null) {
                state.auth.login(employee.id, employee.getFullName(), employee.badgeId);
                technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                        null, employee.badgeId);
                LOGGER.info("Exiting method login");
                return LoginResult.SUCCESS;
            }
            LOGGER.info("Exiting method login");
            return LoginResult.INVALID;
        }
        CredentialStatus status = checkCredentials(loginInfo, rawPassword);
        if (status.isSuccess()) {
            state.auth.login(status.employee.id, status.employee.getFullName(),
                    status.employee.badgeId);
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_UNLOCKED,
                    null, status.employee.badgeId);
            LOGGER.info("Exiting method login");
            return LoginResult.SUCCESS;
        }
        LOGGER.info("Exiting method login");
        return status.locked ? LoginResult.LOCKED : LoginResult.INVALID;
    }


    /**
     * Whether this attempt is a badge opening the register on its own
     * (LC-01-01-03).
     *
     * <p>THREE conditions, all of them: the back office must have turned the
     * opening password off, no password may have been keyed, and the presented
     * identifier must be the badge this register PHYSICALLY READ — the marker
     * {@code AuthState} kept from the scan. Without that third condition the
     * parameter would turn every badge number into a password, and a badge
     * number is printed on the badge.
     *
     * <p>The marker is consumed whatever the outcome: a presented badge opens
     * one attempt, not a window.
     *
     * @param state the current register state
     * @param loginInfo the presented identifier
     * @param rawPassword the keyed password, expected to be empty here
     * @return true when the badge alone may open the register
     */
    boolean badgeOpensAlone(PosState state, String loginInfo, String rawPassword) {
        if (rawPassword != null && !rawPassword.isBlank()) {
            return false;
        }
        if (posSettingsService.passwordRequiredOnOpen()) {
            return false;
        }
        String presented = state.auth.takePresentedBadge();
        return presented != null && presented.equals(loginInfo);
    }
    /**
     * Logs the current operator out and clears the ticket.
     *
     * @param state the current POS state
     */
    public void logout(PosState state) {
        LOGGER.info("Entering method logout with state: " + state);
        // A DELIBERATE lock is journaled exactly like the idle one
        // (BO-04-01-27): the requirement asks for the registers that went on
        // pause, and a cashier locking the till on purpose is one of them —
        // only the automatic timeout was journaled, so the list was silently
        // incomplete. Nothing is written when no operator was signed in:
        // /lock is also the landing page of an already-locked register.
        if (state.auth.operatorBadgeId != null) {
            technicalEventService.log(TechnicalEvent.EventType.REGISTER_LOCKED,
                    null, state.auth.operatorBadgeId);
        }
        state.auth.logout();
        state.clearTicket();
        LOGGER.info("Exiting method logout");
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
        LOGGER.info("Entering method changePin with state: " + state + ", currentPin: ***" + ", newPin: ***" + ", confirmPin: ***");
        Long operatorId = state.auth.operatorId;
        if (operatorId == null) {
            LOGGER.info("Exiting method changePin");
            return "Aucun opérateur connecté";
        }
        Employee employee = Employee.findById(operatorId);
        if (employee == null) {
            LOGGER.info("Exiting method changePin");
            return "Opérateur introuvable";
        }
        if (currentPin == null || !employee.verifyPassword(currentPin)) {
            LOGGER.info("Exiting method changePin");
            return "Code PIN actuel incorrect";
        }
        if (newPin == null || !newPin.matches("\\d{4}")) {
            LOGGER.info("Exiting method changePin");
            return "Le nouveau code doit comporter 4 chiffres";
        }
        if (!newPin.equals(confirmPin)) {
            LOGGER.info("Exiting method changePin");
            return "Les nouveaux codes ne correspondent pas";
        }
        employee.password = Employee.hashPassword(newPin);
        employee.persist();
        technicalEventService.log(TechnicalEvent.EventType.PASSWORD_CHANGED, null, employee.badgeId);
        LOGGER.info("Exiting method changePin");
        return null;
    }
}
