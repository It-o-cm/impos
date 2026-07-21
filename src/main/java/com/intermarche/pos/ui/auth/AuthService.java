package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class AuthService {

    /**
     * Authenticates an employee from a login and a raw PIN, updating the session on success.
     *
     * @param state the current POS state
     * @param loginInfo the login identifier (badge id or login name)
     * @param rawPassword the raw PIN entered
     * @return true if the credentials are valid
     */
    public boolean login(PosState state, String loginInfo, String rawPassword) {
        if (loginInfo == null || rawPassword == null) return false;
        Employee employee = Employee.findActiveLogin(loginInfo.toLowerCase());
        if (employee != null && employee.verifyPassword(rawPassword)) {
            state.auth.login(employee.id, employee.getFullName());
            return true;
        }
        return false;
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
