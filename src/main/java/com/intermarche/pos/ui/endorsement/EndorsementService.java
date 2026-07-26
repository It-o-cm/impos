package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;

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
        AuthService.CredentialStatus status = authService.checkCredentials(login, password);
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
        boolean granted = isManager(login, password);
        technicalEventService.log(
                granted ? TechnicalEvent.EventType.ENDORSEMENT_GRANTED
                        : TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                actionCode + " par " + (login != null ? login : "?"));
        return granted;
    }

    /**
     * Opens an endorsement request for the given action.
     *
     * @param state the current POS state
     * @param actionCode the action code requiring authorization
     */
    public void requestAuthorization(PosState state, String actionCode) {
        state.endorsement.request(actionCode);
        state.touch();
    }

    /**
     * Opens an endorsement request for a price modification.
     *
     * @param state the current POS state
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param value the modification value (euros or percent depending on the type)
     */
    public void requestPriceModification(PosState state, String type, String uid, BigDecimal value) {
        state.endorsement.requestPriceModification(type, uid, value);
        state.touch();
    }

    /**
     * Clears the current endorsement request.
     *
     * @param state the current POS state
     */
    public void clearRequest(PosState state) {
        state.endorsement.clear();
        state.touch();
    }
}
