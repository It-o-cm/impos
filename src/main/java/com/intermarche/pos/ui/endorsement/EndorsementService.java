package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EndorsementService {

    public boolean isManager(String login, String password) {
        if (login == null || password == null) return false;
        Employee employee = Employee.findActiveLogin(login);
        return employee != null && employee.verifyPassword(password);
    }

    public void requestAuthorization(PosState state, String actionCode) {
        state.endorsement.request(actionCode);
        state.touch();
    }

    // NOUVEAU
    public void requestPriceModification(PosState state, String type, String uid, double value) {
        state.endorsement.requestPriceModification(type, uid, value);
        state.touch();
    }

    public void clearRequest(PosState state) {
        state.endorsement.clear();
        state.touch();
    }
}