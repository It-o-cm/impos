package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthService;
import com.intermarche.pos.ui.auth.AuthState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EndorsementService}.
 * <p>
 * The service delegates to a mocked {@link AuthService} and a mocked
 * {@link TechnicalEventService} for credential checks and journaling, and to a
 * mocked {@link PosState} carrying a mocked {@link EndorsementState} for request
 * lifecycle. Each {@code isManager} arm (failed check, MANAGER, ADMIN, other
 * role) and each {@code authorize} ternary arm (granted/denied event type,
 * present/absent login) is covered, plus the three thin lifecycle delegations.
 * Assertions use absolute expected values and every test is fully isolated.
 */
class EndorsementServiceTest {

    /**
     * Builds an {@link EndorsementService} whose {@link AuthService} and
     * {@link TechnicalEventService} collaborators are fresh Mockito mocks.
     *
     * @return a service with mocked collaborators
     */
    private EndorsementService newService() {
        EndorsementService service = new EndorsementService();
        service.authService = mock(AuthService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        return service;
    }

    /**
     * Builds a real {@link AuthService.CredentialStatus} through its private
     * constructor so {@code isSuccess()} reflects the carried employee.
     *
     * @param employee the authenticated employee, or null for a failed check
     * @return a credential status wrapping the given employee
     */
    private AuthService.CredentialStatus status(Employee employee) {
        try {
            Constructor<AuthService.CredentialStatus> ctor =
                    AuthService.CredentialStatus.class.getDeclaredConstructor(Employee.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(employee, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds an {@link Employee} carrying the given role.
     *
     * @param role the role assigned to the employee
     * @return an employee with that role
     */
    private Employee employee(Employee.EmployeeRole role) {
        Employee employee = new Employee();
        employee.role = role;
        return employee;
    }

    // --- isManager ---

    /**
     * {@code isManager()} returns false when the credential check fails
     * ({@code isSuccess()} false arm, role never evaluated).
     */
    @Test
    void isManagerReturnsFalseWhenCredentialsFail() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("m", "0000")).thenReturn(status(null));
        assertFalse(service.isManager("m", "0000"));
    }

    /**
     * {@code isManager()} returns true for an authenticated MANAGER
     * ({@code isSuccess()} true, MANAGER ternary true arm).
     */
    @Test
    void isManagerReturnsTrueForManager() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("m", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.MANAGER)));
        assertTrue(service.isManager("m", "1234"));
    }

    /**
     * {@code isManager()} returns true for an authenticated ADMIN
     * (MANAGER ternary false arm, ADMIN ternary true arm).
     */
    @Test
    void isManagerReturnsTrueForAdmin() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("a", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.ADMIN)));
        assertTrue(service.isManager("a", "1234"));
    }

    /**
     * {@code isManager()} returns false for an authenticated non-manager
     * (both role ternaries false arm).
     */
    @Test
    void isManagerReturnsFalseForOtherRole() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("c", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.PICKER)));
        assertFalse(service.isManager("c", "1234"));
    }

    // --- authorize ---

    /**
     * {@code authorize()} journals a GRANTED event with the present login and
     * returns true when the endorsement is granted (granted ternary true arm,
     * login non-null arm).
     */
    @Test
    void authorizeGrantedJournalsGrantedWithLogin() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("mgr", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.MANAGER)));
        assertTrue(service.authorize("mgr", "1234", "CANCEL_TICKET"));
        // BO-04-01-39: the endorsing operator is named in the first-class badge
        // column (3rd arg) so the journal's N° caissière filter can reach it.
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "CANCEL_TICKET par mgr", "mgr");
    }

    /**
     * {@code authorize()} journals a DENIED event with the {@code "?"}
     * placeholder and returns false when the endorsement is refused and the
     * login is null (granted ternary false arm, login null arm).
     */
    @Test
    void authorizeDeniedJournalsDeniedWithPlaceholderWhenLoginNull() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials(null, "0000")).thenReturn(status(null));
        assertFalse(service.authorize(null, "0000", "CANCEL_TICKET"));
        // BO-04-01-39: a null login carries a null badge — the "?" placeholder
        // stays in the detail, but the badge column is null (never matches a range).
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.ENDORSEMENT_DENIED, "CANCEL_TICKET par ?", null);
    }

    // --- requestAuthorization / requestPriceModification / clearRequest ---

    /**
     * {@code requestAuthorization()} opens the request on the endorsement state
     * and touches the POS state.
     */
    @Test
    void requestAuthorizationDelegatesAndTouches() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = mock(EndorsementState.class);
        service.requestAuthorization(state, "CANCEL_TICKET");
        verify(state.endorsement).request("CANCEL_TICKET");
        verify(state).touch();
    }

    /**
     * {@code requestPriceModification()} opens the price-modification request on
     * the endorsement state and touches the POS state.
     */
    @Test
    void requestPriceModificationDelegatesAndTouches() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = mock(EndorsementState.class);
        BigDecimal value = new BigDecimal("0.50");
        service.requestPriceModification(state, PriceModType.REMISE, "L1", value);
        verify(state.endorsement).requestPriceModification(PriceModType.REMISE, "L1", value);
        verify(state).touch();
    }

    /**
     * {@code clearRequest()} clears the endorsement state and touches the POS
     * state.
     */
    @Test
    void clearRequestDelegatesAndTouches() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = mock(EndorsementState.class);
        service.clearRequest(state);
        verify(state.endorsement).clear();
        verify(state).touch();
    }

    // --- operatorIsSupervisor ---

    /**
     * Builds a state whose logged-in operator id is the given value.
     *
     * @param operatorId the operator id, or null for none
     * @return the wired state
     */
    private PosState stateWithOperator(Long operatorId) {
        PosState state = mock(PosState.class);
        state.auth = new AuthState();
        state.auth.operatorId = operatorId;
        return state;
    }

    /**
     * Builds an employee with the given activity and role.
     *
     * @param active whether the employee is active
     * @param role the employee role
     * @return the employee mock
     */
    private Employee employee(boolean active, Employee.EmployeeRole role) {
        Employee employee = mock(Employee.class);
        employee.active = active;
        employee.role = role;
        return employee;
    }

    /**
     * {@code operatorIsSupervisor} is false when no operator is logged in
     * (operator-id null arm), without a lookup.
     */
    @Test
    void supervisorFalseWhenNoOperator() {
        EndorsementService service = new EndorsementService();
        assertFalse(service.operatorIsSupervisor(stateWithOperator(null)));
    }

    /**
     * {@code operatorIsSupervisor} is false when the operator row is unknown
     * (operator-null arm).
     */
    @Test
    void supervisorFalseWhenOperatorUnknown() {
        EndorsementService service = new EndorsementService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(null);
            assertFalse(service.operatorIsSupervisor(stateWithOperator(7L)));
        }
    }

    /**
     * {@code operatorIsSupervisor} is false for a deactivated manager
     * (active-false arm).
     */
    @Test
    void supervisorFalseWhenInactive() {
        EndorsementService service = new EndorsementService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(false, Employee.EmployeeRole.MANAGER));
            assertFalse(service.operatorIsSupervisor(stateWithOperator(7L)));
        }
    }

    /**
     * {@code operatorIsSupervisor} is false for an active cashier (role
     * neither MANAGER nor ADMIN arm).
     */
    @Test
    void supervisorFalseForCashier() {
        EndorsementService service = new EndorsementService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(true, Employee.EmployeeRole.CASHIER));
            assertFalse(service.operatorIsSupervisor(stateWithOperator(7L)));
        }
    }

    /**
     * {@code operatorIsSupervisor} is true for an active manager (MANAGER arm).
     */
    @Test
    void supervisorTrueForManager() {
        EndorsementService service = new EndorsementService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(true, Employee.EmployeeRole.MANAGER));
            assertTrue(service.operatorIsSupervisor(stateWithOperator(7L)));
        }
    }

    /**
     * {@code operatorIsSupervisor} is true for an active admin (ADMIN arm).
     */
    @Test
    void supervisorTrueForAdmin() {
        EndorsementService service = new EndorsementService();
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(true, Employee.EmployeeRole.ADMIN));
            assertTrue(service.operatorIsSupervisor(stateWithOperator(7L)));
        }
    }
}
