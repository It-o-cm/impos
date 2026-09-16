package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PriceModType;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import org.jboss.logging.Logger;

/**
 * Manager endorsement service: credential check and endorsement request
 * lifecycle.
 * <p>
 * Phase 2: the check verifies the MANAGER or ADMIN role (any active employee
 * used to pass), goes through the shared lockout-aware credential check, and
 * every granted or refused endorsement is journaled with its action code.
 * <p>
 * Consequences of sharing the credential check with the register unlock:
 * ONE failure counter per account (a manager burning attempts on an
 * endorsement locks their own login too — deliberate, one credential = one
 * lockout), and the endorsing manager does NOT need to be the logged-in
 * operator: a cashier stays logged in while a manager validates over their
 * shoulder, which is exactly the four-eyes gesture the pattern models. The
 * journal entry carries the ACTION CODE, so the audit trail says what was
 * endorsed, by whom, granted or refused.
 */
@ApplicationScoped
public class EndorsementService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(EndorsementService.class);

    @Inject
    AuthService authService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Verifies that the presented credentials belong to an active employee
     * holding the MANAGER or ADMIN role, PIN lockout enforced.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @return true if the credentials are valid and the role is sufficient
     */
    public boolean isManager(String login, String password) {
        LOGGER.info("Entering method isManager with login: " + login + ", password: ***");
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
        LOGGER.info("Exiting method isManager");
        return status.isSuccess()
                && (status.employee.role == Employee.EmployeeRole.MANAGER
                    || status.employee.role == Employee.EmployeeRole.ADMIN);
    }

    /**
     * Verifies the credentials for an endorsement and journals the outcome
     * with the endorsed action code and the presented login.
     *
     * @param login the badge id or login name
     * @param password the raw PIN
     * @param actionCode the action code requiring authorization
     * @return true if the endorsement is granted
     */
    public boolean authorize(String login, String password, String actionCode) {
        LOGGER.info("Entering method authorize with login: " + login + ", password: ***" + ", actionCode: " + actionCode);
        boolean granted = isManager(login, password);
        // BO-04-01-39: name the endorsing operator in the first-class badge
        // column, not only inside the free-text detail — otherwise the journal's
        // "N° caissière" range filter (matched on operatorBadgeId) never returns
        // an endorsement event. A badge-based endorsement then filters exactly;
        // one keyed by login name carries that login as its identifier.
        technicalEventService.log(
                granted ? TechnicalEvent.EventType.ENDORSEMENT_GRANTED
                        : TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                actionCode + " par " + (login != null ? login : "?"),
                login);
        LOGGER.info("Exiting method authorize");
        return granted;
    }

    /**
     * Opens an endorsement request for the given action.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     */
    public void requestAuthorization(PosState state, String actionCode) {
        LOGGER.info("Entering method requestAuthorization with state: " + state + ", actionCode: " + actionCode);
        state.endorsement.request(actionCode);
        state.touch();
        LOGGER.info("Exiting method requestAuthorization");
    }

    /**
     * Returns true when the LOGGED operator already holds a supervising role
     * (MANAGER or ADMIN, the same roles that validate endorsements) — the
     * connected-supervisor shortcut of LC-01-05-07. Looked up live in the
     * referential: a role revoked by a pull is effective immediately.
     *
     * @param state the current POS state
     * @return true when the logged operator can approve without a credential
     */
    public boolean operatorIsSupervisor(PosState state) {
        LOGGER.info("Entering method operatorIsSupervisor with state: " + state);
        Long operatorId = state.auth.getOperatorId();
        if (operatorId == null) {
            LOGGER.info("Exiting method operatorIsSupervisor");
            return false;
        }
        com.intermarche.pos.domain.people.Employee operator =
                com.intermarche.pos.domain.people.Employee.findById(operatorId);
        LOGGER.info("Exiting method operatorIsSupervisor");
        return operator != null && operator.active
                && (operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.MANAGER
                    || operator.role == com.intermarche.pos.domain.people.Employee.EmployeeRole.ADMIN);
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param state the current POS state
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(PosState state, PriceModType type, String uid, BigDecimal value) {
        LOGGER.info("Entering method requestPriceModification with state: " + state + ", type: " + type + ", uid: " + uid + ", value: " + value);
        state.endorsement.requestPriceModification(type, uid, value);
        state.touch();
        LOGGER.info("Exiting method requestPriceModification");
    }

    /**
     * Clears the current endorsement request.
     *
     * @param state the current POS state
     */
    public void clearRequest(PosState state) {
        LOGGER.info("Entering method clearRequest with state: " + state);
        state.endorsement.clear();
        state.touch();
        LOGGER.info("Exiting method clearRequest");
    }
}
