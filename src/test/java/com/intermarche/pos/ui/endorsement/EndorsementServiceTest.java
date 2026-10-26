package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.auth.AuthService;
import com.intermarche.pos.ui.auth.AuthState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

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
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        // The control points speak to the operator, so the service resolves
        // their wording like every screen does: from the French bundle.
        service.i18n = com.intermarche.pos.ui.TestMessages.french();
        return service;
    }

    /**
     * Builds a POS state whose operator is nobody and whose ticket is a real
     * one, so a control point's message lands somewhere a test can read.
     *
     * @return the state
     */
    private PosState controlState() {
        PosState state = mock(PosState.class);
        state.auth = new AuthState();
        state.ticket = new com.intermarche.pos.ui.ticket.TicketState();
        state.supervisorSessionBadge = null;
        return state;
    }

    /**
     * Builds a real {@link AuthService.CredentialStatus} through its private
     * constructor so {@code isSuccess()} reflects the carried employee.
     *
     * @param employee the authenticated employee, or null for a failed check
     * @return a credential status wrapping the given employee
     */
    private AuthService.CredentialStatus status(Employee employee) {
        return status(employee, false);
    }

    /**
     * Builds a real {@link AuthService.CredentialStatus} through its private
     * constructor, carrying the lockout flag too.
     *
     * @param employee the authenticated employee, or null for a failed check
     * @param locked true for an account under PIN lockout
     * @return a credential status wrapping the given employee and flag
     */
    private AuthService.CredentialStatus status(Employee employee, boolean locked) {
        try {
            Constructor<AuthService.CredentialStatus> ctor =
                    AuthService.CredentialStatus.class.getDeclaredConstructor(Employee.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(employee, locked);
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

    // --- authorizeOutcome ---

    /**
     * {@code authorizeOutcome()} answers GRANTED for an authenticated MANAGER
     * and journals the grant, exactly as {@code authorize()} does — the boolean
     * form delegates here, so one check and one journal entry.
     */
    @Test
    void authorizeOutcomeGrantsAManager() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("mgr", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.MANAGER)));
        assertEquals(AuthService.CheckOutcome.GRANTED,
                service.authorizeOutcome("mgr", "1234", "SESSION_FORCE_CLOSE"));
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.ENDORSEMENT_GRANTED, "SESSION_FORCE_CLOSE par mgr", "mgr");
    }

    /**
     * {@code authorizeOutcome()} answers GRANTED for an ADMIN too (the second
     * arm of the role test).
     */
    @Test
    void authorizeOutcomeGrantsAnAdmin() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("adm", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.ADMIN)));
        assertEquals(AuthService.CheckOutcome.GRANTED,
                service.authorizeOutcome("adm", "1234", "SESSION_FORCE_CLOSE"));
    }

    /**
     * {@code authorizeOutcome()} answers DENIED on a failed credential check
     * that is NOT a lockout (isSuccess false, locked false).
     */
    @Test
    void authorizeOutcomeDeniesAWrongCode() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("mgr", "0000")).thenReturn(status(null, false));
        assertEquals(AuthService.CheckOutcome.DENIED,
                service.authorizeOutcome("mgr", "0000", "SESSION_FORCE_CLOSE"));
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.ENDORSEMENT_DENIED, "SESSION_FORCE_CLOSE par mgr", "mgr");
    }

    /**
     * {@code authorizeOutcome()} answers DENIED for an authenticated employee
     * whose role is NEITHER manager nor admin (isSuccess true, both role arms
     * false) — a valid code that is not enough is a refusal, not a lockout.
     */
    @Test
    void authorizeOutcomeDeniesAnInsufficientRole() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("cashier", "1234"))
                .thenReturn(status(employee(Employee.EmployeeRole.CASHIER)));
        assertEquals(AuthService.CheckOutcome.DENIED,
                service.authorizeOutcome("cashier", "1234", "SESSION_FORCE_CLOSE"));
    }

    /**
     * {@code authorizeOutcome()} answers GRANTED for the holder of a supervisor
     * card, whatever their role (BO-03-06-51).
     */
    @Test
    void authorizeOutcomeGrantsASupervisorCardHolder() {
        EndorsementService service = newService();
        Employee holder = employee(Employee.EmployeeRole.CASHIER);
        holder.supervisorCard = true;
        when(service.authService.checkCredentials("sup", "1234")).thenReturn(status(holder));
        assertEquals(AuthService.CheckOutcome.GRANTED,
                service.authorizeOutcome("sup", "1234", "GS1_EXPIRY_FORCE"));
    }

    /**
     * {@code authorizeOutcome()} answers LOCKED when the account is under PIN
     * lockout (isSuccess false, locked true) — the refusal retyping cannot fix,
     * and the one the screens must name.
     */
    @Test
    void authorizeOutcomeNamesTheLockout() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("mgr", "1234")).thenReturn(status(null, true));
        assertEquals(AuthService.CheckOutcome.LOCKED,
                service.authorizeOutcome("mgr", "1234", "SESSION_FORCE_CLOSE"));
        verify(service.technicalEventService).log(
                TechnicalEvent.EventType.ENDORSEMENT_DENIED, "SESSION_FORCE_CLOSE par mgr", "mgr");
    }

    /**
     * The boolean {@code authorize()} is GRANTED and nothing else: a LOCKED
     * outcome answers false, like a plain refusal, so the ten call sites that
     * only need a yes or no are unchanged by the outcome form.
     */
    @Test
    void authorizeIsFalseOnALockout() {
        EndorsementService service = newService();
        when(service.authService.checkCredentials("mgr", "1234")).thenReturn(status(null, true));
        assertFalse(service.authorize("mgr", "1234", "SESSION_FORCE_CLOSE"));
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
     * The FOUR-ARGUMENT {@code requestAuthorization()} parks the gesture's whole
     * form and the path to land on, then touches the POS state.
     *
     * <p>A different method from the two-argument one, and tested on its own: it
     * is what lets the drawer screens use the shared modal instead of their own
     * badge and PIN boxes, and the form it parks is what the dispatch replays
     * once a manager credential passes. A test on the short overload proves
     * nothing about the fields this one carries.
     */
    @Test
    void requestAuthorizationWithAFormParksTheGestureAndTouches() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = mock(EndorsementState.class);
        java.util.Map<String, String> form = new java.util.HashMap<>();
        form.put("amount", "25.00");
        form.put("reason", "FOND DE CAISSE");
        service.requestAuthorization(state, "CASH_WITHDRAWAL", form, "/withdrawal");
        verify(state.endorsement).request("CASH_WITHDRAWAL", form, "/withdrawal");
        verify(state).touch();
    }

    /**
     * An EMPTY form and a null return path are handed on as they stand: it is the
     * endorsement state, not this service, that decides what an empty parked
     * gesture replays to.
     */
    @Test
    void requestAuthorizationWithAnEmptyFormPassesItThrough() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = mock(EndorsementState.class);
        java.util.Map<String, String> empty = new java.util.HashMap<>();
        service.requestAuthorization(state, "DRAWER_OPEN", empty, null);
        verify(state.endorsement).request("DRAWER_OPEN", empty, null);
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
        state.ticket = new com.intermarche.pos.ui.ticket.TicketState();
        state.ticket.pendingCancelUid = "A";
        state.ticket.pendingCancelReasonCode = "ERROR";
        state.ticket.pendingCancelReasonLabel = "Erreur";
        service.clearRequest(state);
        assertNull(state.ticket.pendingCancelUid);
        assertNull(state.ticket.pendingCancelReasonCode);
        assertNull(state.ticket.pendingCancelReasonLabel);
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
     * P6: {@code selfAuthorized} is true when an open supervisor session stands,
     * with no operator lookup (session arm).
     */
    @Test
    void selfAuthorizedTrueWhenSupervisorSessionOpen() {
        EndorsementService service = new EndorsementService();
        PosState state = stateWithOperator(null);
        state.supervisorSessionBadge = "11111111";
        assertTrue(service.selfAuthorized(state));
    }

    /**
     * P6: {@code selfAuthorized} is false when the operator is not a supervisor and
     * no session is open (both-arms-false).
     */
    @Test
    void selfAuthorizedFalseWhenNeither() {
        EndorsementService service = new EndorsementService();
        PosState state = stateWithOperator(null);
        state.supervisorSessionBadge = null;
        assertFalse(service.selfAuthorized(state));
    }

    /**
     * P6: {@code selfAuthorized} is true when the logged operator is themselves an
     * active supervisor (operatorIsSupervisor arm).
     */
    @Test
    void selfAuthorizedTrueWhenOperatorIsSupervisor() {
        EndorsementService service = new EndorsementService();
        PosState state = stateWithOperator(7L);
        state.supervisorSessionBadge = null;
        Employee manager = employee(Employee.EmployeeRole.MANAGER);
        manager.active = true;
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(manager);
            assertTrue(service.selfAuthorized(state));
        }
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

    /**
     * {@code gestureAuthorized} is true when the gesture's flag is off (EXP-32):
     * the gesture proceeds without a modal, no operator lookup needed.
     */
    @Test
    void gestureAuthorizedWhenFlagOff() {
        EndorsementService service = newService();
        when(service.posSettingsService.gestureEndorsementRequired("cancel-line")).thenReturn(false);
        assertTrue(service.gestureAuthorized(stateWithOperator(null), "cancel-line"));
    }

    /**
     * {@code gestureAuthorized} is true when a supervisor session is open on the
     * ticket (EXP-33), even with the flag on and no supervising operator.
     */
    @Test
    void gestureAuthorizedWhenSupervisorSessionOpen() {
        EndorsementService service = newService();
        when(service.posSettingsService.gestureEndorsementRequired("refund")).thenReturn(true);
        PosState state = stateWithOperator(null);
        state.supervisorSessionBadge = "M1";
        assertTrue(service.gestureAuthorized(state, "refund"));
    }

    /**
     * {@code gestureAuthorized} is true when the logged operator is a supervisor
     * (LC-01-05-07), with the flag on and no open session.
     */
    @Test
    void gestureAuthorizedWhenOperatorIsSupervisor() {
        EndorsementService service = newService();
        when(service.posSettingsService.gestureEndorsementRequired("cancel-line")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(true, Employee.EmployeeRole.MANAGER));
            assertTrue(service.gestureAuthorized(stateWithOperator(7L), "cancel-line"));
        }
    }

    /**
     * {@code gestureAuthorized} is false when the flag is on, no supervisor is
     * logged and no session is open — the modal is required (all arms false).
     */
    @Test
    void gestureNotAuthorizedWhenNoneApply() {
        EndorsementService service = newService();
        when(service.posSettingsService.gestureEndorsementRequired("abandon")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Employee.findById(7L)).thenReturn(employee(true, Employee.EmployeeRole.PICKER));
            PosState state = stateWithOperator(7L);
            state.supervisorSessionBadge = null;
            assertFalse(service.gestureAuthorized(state, "abandon"));
        }
    }

    /**
     * {@code openSupervisorSessionIfPerTicket} attaches the endorser's badge in
     * PER_TICKET scope (EXP-33).
     */
    @Test
    void openSupervisorSessionPerTicketSetsBadge() {
        EndorsementService service = newService();
        when(service.posSettingsService.endorsementScope()).thenReturn("PER_TICKET");
        PosState state = stateWithOperator(null);
        service.openSupervisorSessionIfPerTicket(state, "M1");
        assertEquals("M1", state.supervisorSessionBadge);
    }

    /**
     * {@code openSupervisorSessionIfPerTicket} leaves no session in PER_OP scope.
     */
    @Test
    void openSupervisorSessionPerOpLeavesNull() {
        EndorsementService service = newService();
        when(service.posSettingsService.endorsementScope()).thenReturn("PER_OP");
        PosState state = stateWithOperator(null);
        state.supervisorSessionBadge = null;
        service.openSupervisorSessionIfPerTicket(state, "M1");
        assertNull(state.supervisorSessionBadge);
    }

    /**
     * {@code endSupervisorSession} clears an open supervisor session (EXP-33).
     */
    @Test
    void endSupervisorSessionClearsBadge() {
        EndorsementService service = newService();
        PosState state = stateWithOperator(null);
        state.supervisorSessionBadge = "M1";
        service.endSupervisorSession(state);
        assertNull(state.supervisorSessionBadge);
    }
    // --------------------------------------------------
    // Control points (BO-10-02-01 to -10)
    // --------------------------------------------------

    /**
     * {@code level} reads the administered value of a point, and falls back on
     * that point's own default when the back office says nothing it can read —
     * both arms, on two points whose defaults differ so the fallback is visible.
     */
    @Test
    void levelReadsTheAdministeredValueOrThePointsOwnDefault() {
        EndorsementService service = newService();
        when(service.posSettingsService.controlLevel("control.card-payment")).thenReturn("BLOCK");
        assertEquals(ControlLevel.BLOCK, service.level(ControlPoint.CARD_PAYMENT));

        when(service.posSettingsService.controlLevel("control.card-payment")).thenReturn("n'importe quoi");
        assertEquals(ControlLevel.NONE, service.level(ControlPoint.CARD_PAYMENT));

        when(service.posSettingsService.controlLevel("control.cancel-line")).thenReturn(null);
        assertEquals(ControlLevel.ENDORSE, service.level(ControlPoint.CANCEL_LINE));
    }

    /**
     * The three levels that are not an aval cross {@code check} untouched: the
     * resolution it performs concerns ENDORSE and nothing else.
     */
    @Test
    void checkPassesTheOtherThreeLevelsThrough() {
        EndorsementService service = newService();
        PosState state = controlState();
        for (ControlLevel level : new ControlLevel[] {
                ControlLevel.NONE, ControlLevel.WARN, ControlLevel.BLOCK }) {
            when(service.posSettingsService.controlLevel("control.cancel-payment"))
                    .thenReturn(level.name());
            assertEquals(level, service.check(state, ControlPoint.CANCEL_PAYMENT),
                    "niveau " + level);
        }
    }

    /**
     * An aval asked of a SUPERVISOR is no aval: the first leg of the shortcut,
     * the logged operator holding the role.
     */
    @Test
    void checkAsksNoAvalOfAConnectedSupervisor() {
        EndorsementService service = newService();
        PosState state = controlState();
        state.auth.operatorId = 7L;
        when(service.posSettingsService.controlLevel("control.cancel-line")).thenReturn("ENDORSE");
        Employee manager = new Employee();
        manager.active = true;
        manager.role = Employee.EmployeeRole.MANAGER;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Employee.findById(7L)).thenReturn(manager);
            assertEquals(ControlLevel.NONE, service.check(state, ControlPoint.CANCEL_LINE));
        }
    }

    /**
     * Second leg of the same shortcut: a supervisor session open on the ticket,
     * the operator being nobody in particular.
     */
    @Test
    void checkAsksNoAvalUnderAnOpenSupervisorSession() {
        EndorsementService service = newService();
        PosState state = controlState();
        state.supervisorSessionBadge = "9001";
        when(service.posSettingsService.controlLevel("control.cancel-line")).thenReturn("ENDORSE");
        assertEquals(ControlLevel.NONE, service.check(state, ControlPoint.CANCEL_LINE));
    }

    /**
     * Neither leg: the aval stands, which is the historical behaviour of the
     * gestures that already carried one.
     */
    @Test
    void checkKeepsTheAvalWhenNoSupervisorIsThere() {
        EndorsementService service = newService();
        PosState state = controlState();
        when(service.posSettingsService.controlLevel("control.cancel-line")).thenReturn("ENDORSE");
        assertEquals(ControlLevel.ENDORSE, service.check(state, ControlPoint.CANCEL_LINE));
    }

    /**
     * An aval administered on a point that fires where NO ceremony exists is
     * applied as a refusal: a shop gets a refusal rather than a button nobody
     * can press.
     */
    @Test
    void checkRefusesWhereNoAvalCeremonyExists() {
        EndorsementService service = newService();
        PosState state = controlState();
        when(service.posSettingsService.controlLevel("control.ticket-total")).thenReturn("ENDORSE");
        assertEquals(ControlLevel.BLOCK, service.check(state, ControlPoint.TICKET_TOTAL));
        when(service.posSettingsService.controlLevel("control.parked-at-close")).thenReturn("ENDORSE");
        assertEquals(ControlLevel.BLOCK, service.check(state, ControlPoint.PARKED_AT_CLOSE));
    }

    /**
     * A supervisor standing at the register still clears the aval of a
     * non-endorsable point — the shortcut is read BEFORE the downgrade, so a
     * manager is never refused a gesture the shop would have let them endorse.
     */
    @Test
    void theSupervisorShortcutIsReadBeforeTheDowngrade() {
        EndorsementService service = newService();
        PosState state = controlState();
        state.supervisorSessionBadge = "9001";
        when(service.posSettingsService.controlLevel("control.ticket-total")).thenReturn("ENDORSE");
        assertEquals(ControlLevel.NONE, service.check(state, ControlPoint.TICKET_TOTAL));
    }

    /**
     * {@code controlMessage} says one of two sentences — the refusal and
     * everything else — and names the gesture inside it.
     */
    @Test
    void controlMessageSaysWhichGestureAndWhatHappened() {
        EndorsementService service = newService();
        String refused = service.controlMessage(ControlPoint.CARD_PAYMENT, ControlLevel.BLOCK);
        String warned = service.controlMessage(ControlPoint.CARD_PAYMENT, ControlLevel.WARN);
        assertTrue(refused.contains("RÈGLEMENT PAR CARTE"), refused);
        assertTrue(refused.contains("INTERDIT"), refused);
        assertTrue(warned.contains("RÈGLEMENT PAR CARTE"), warned);
        assertTrue(warned.contains("SIGNALÉ"), warned);
        assertFalse(warned.contains("INTERDIT"), warned);
    }

    /**
     * The drawer outside a sale: the three outcomes of the shared answer — it
     * opens in silence, it opens with a notice, it stays shut with a refusal.
     */
    @Test
    void theDrawerOutOfSaleAnswersAndSaysWhy() {
        EndorsementService silent = newService();
        PosState quiet = controlState();
        when(silent.posSettingsService.controlLevel("control.drawer-out-of-sale")).thenReturn("NONE");
        assertTrue(silent.drawerOutOfSaleAllowed(quiet));
        assertNull(quiet.ticket.transientError);

        EndorsementService warning = newService();
        PosState warned = controlState();
        when(warning.posSettingsService.controlLevel("control.drawer-out-of-sale")).thenReturn("WARN");
        assertTrue(warning.drawerOutOfSaleAllowed(warned));
        assertNotNull(warned.ticket.transientError);
        assertTrue(warned.ticket.transientError.contains("TIROIR"));
        // Drawn as a caution and not as a refusal: the gesture went through.
        assertTrue(warned.ticket.transientWarn);
        assertFalse(warned.ticket.transientOk);

        EndorsementService refusing = newService();
        PosState refused = controlState();
        when(refusing.posSettingsService.controlLevel("control.drawer-out-of-sale")).thenReturn("BLOCK");
        assertFalse(refusing.drawerOutOfSaleAllowed(refused));
        assertNotNull(refused.ticket.transientError);
        assertTrue(refused.ticket.transientError.contains("INTERDIT"));
        assertFalse(refused.ticket.transientWarn);
    }

    /**
     * BO-04-02-01: the roles that endorse are administered. A list naming a
     * role lets it endorse whatever its case; a list naming another one
     * refuses it; and a null role never endorses.
     */
    @Test
    void theEndorsingRolesAreAdministered() {
        EndorsementService service = newService();
        when(service.posSettingsService.endorsementRoles()).thenReturn(" cashier ; manager ");
        assertTrue(service.endorses(Employee.EmployeeRole.CASHIER));
        assertTrue(service.endorses(Employee.EmployeeRole.MANAGER));
        assertFalse(service.endorses(Employee.EmployeeRole.ADMIN));
        assertFalse(service.endorses(null));
    }

    /**
     * BO-04-02-01: nothing administered, a blank list and a list naming
     * nothing readable all keep the two roles that endorsed before the list
     * existed — a typo must not lock a shop out of its own endorsements.
     */
    @Test
    void anUnreadableListKeepsTheHistoricalRoles() {
        EndorsementService service = newService();
        when(service.posSettingsService.endorsementRoles()).thenReturn(null);
        assertTrue(service.endorses(Employee.EmployeeRole.MANAGER));
        assertFalse(service.endorses(Employee.EmployeeRole.CASHIER));
        when(service.posSettingsService.endorsementRoles()).thenReturn("   ");
        assertTrue(service.endorses(Employee.EmployeeRole.ADMIN));
        when(service.posSettingsService.endorsementRoles()).thenReturn(" ; ; ");
        assertTrue(service.endorses(Employee.EmployeeRole.MANAGER));
        assertFalse(service.endorses(Employee.EmployeeRole.CASHIER));
    }

    /**
     * A service built with no settings at all keeps the historical roles
     * rather than refusing every endorsement.
     */
    @Test
    void aServiceWithoutSettingsKeepsTheHistoricalRoles() {
        EndorsementService service = new EndorsementService();
        assertTrue(service.endorses(Employee.EmployeeRole.MANAGER));
        assertFalse(service.endorses(Employee.EmployeeRole.CASHIER));
    }

    /**
     * BO-04-02-02: an authorisation granted at the back office is given
     * back trimmed and journalled; a state carrying none answers nothing and
     * journals nothing.
     */
    @Test
    void aRemoteAuthorisationIsConsumedAndJournalled() {
        EndorsementService service = newService();
        PosState state = controlState();
        state.endorsement = new EndorsementState();
        state.endorsement.remoteGrant = "  Marie Curie  ";
        assertEquals("Marie Curie", service.remotelyGranted(state));
        verify(service.technicalEventService).log(
                eq(TechnicalEvent.EventType.ENDORSEMENT_GRANTED),
                contains("Marie Curie"));
        state.endorsement.remoteGrant = null;
        assertNull(service.remotelyGranted(state));
        state.endorsement.remoteGrant = "   ";
        assertNull(service.remotelyGranted(state));
        assertNull(service.remotelyGranted(null));
    }
    /** LC-01-05-08: consuming the back office's authorisation settles the lane's other open requests. */
    @Test
    void aConsumedRemoteAuthorisationSettlesTheLane() {
        EndorsementService service = newService();
        service.supervisorSettleService = mock(com.intermarche.pos.service.SupervisorSettleService.class);
        PosState state = controlState();
        state.endorsement = new EndorsementState();
        state.endorsement.remoteGrant = "Le Manager";
        assertEquals("Le Manager", service.remotelyGranted(state));
        verify(service.supervisorSettleService).settledAtTill();
    }

    /** LC-01-05: an empty administered role list falls back for every role and missing approval mailboxes grant nothing. */
    @Test
    void blankRoleListAndMissingRemoteMailboxKeepTheirFallbacks() {
        EndorsementService service = newService();
        when(service.posSettingsService.endorsementRoles()).thenReturn("; ;");
        for (var role : Employee.EmployeeRole.values()) assertEquals(role == Employee.EmployeeRole.MANAGER || role == Employee.EmployeeRole.ADMIN, service.endorses(role));
        assertNull(service.remotelyGranted(null));
        PosState state = mock(PosState.class);
        state.endorsement = null;
        assertNull(service.remotelyGranted(state));
    }

    /** LC-01-05-08: every way of opening a request sends it to the back office and records whether it went. */
    @Test
    void openedRequestsAreSentToTheBackOffice() {
        EndorsementService service = newService();
        service.supervisorSettleService = mock(com.intermarche.pos.service.SupervisorSettleService.class);
        PosState state = mock(PosState.class);
        state.endorsement = new EndorsementState();
        when(state.getOperatorName()).thenReturn("Jean Dupont");
        when(service.supervisorSettleService.authorizationRequested("CANCEL_LINE", "Jean Dupont")).thenReturn(true);
        service.requestAuthorization(state, "CANCEL_LINE_123e4567-e89b-12d3-a456-426614174000");
        assertTrue(state.endorsement.remoteRequested);
        when(service.supervisorSettleService.authorizationRequested("DRAWER_FORCE_OPEN", "Jean Dupont")).thenReturn(false);
        service.requestAuthorization(state, "DRAWER_FORCE_OPEN", java.util.Map.of("code", "ERR"), "/");
        assertFalse(state.endorsement.remoteRequested);
        when(service.supervisorSettleService.authorizationRequested("DISCOUNT 10", "Jean Dupont")).thenReturn(true);
        service.requestPriceModification(state, PriceModType.DISCOUNT, "L1", new BigDecimal("10.00"));
        assertTrue(state.endorsement.remoteRequested);
        verify(state, times(3)).touch();
    }

    /** LC-01-05-08: a register without the notice service opens its request locally and marks nothing as sent. */
    @Test
    void requestWithoutNoticeServiceStaysLocal() {
        EndorsementService service = newService();
        PosState state = mock(PosState.class);
        state.endorsement = new EndorsementState();
        service.requestAuthorization(state, "CANCEL_TICKET");
        assertTrue(state.endorsement.active);
        assertFalse(state.endorsement.remoteRequested);
    }

    /** LC-01-05-08: a gesture is named by its action code, a line cancellation without its uid, a price change by type and value. */
    @Test
    void describeNamesTheGestureForTheBoard() {
        EndorsementState endorsement = new EndorsementState();
        endorsement.request("PRINT_BADGE_12341234");
        assertEquals("PRINT_BADGE_12341234", EndorsementService.describe(endorsement));
        endorsement.request("CANCEL_LINE_123e4567-e89b-12d3-a456-426614174000");
        assertEquals("CANCEL_LINE", EndorsementService.describe(endorsement));
        endorsement.requestPriceModification(PriceModType.FORCE_PRICE, "L1", new BigDecimal("2.50"));
        assertEquals("FORCE_PRICE 2.5", EndorsementService.describe(endorsement));
    }

    /** LC-01-05-08: abandoning withdraws a sent request, keeps the board when a service request is pending, and never posts an unsent one. */
    @Test
    void abandonRequestWithdrawsOnlyARequestThatWentUp() {
        EndorsementService service = newService();
        service.supervisorSettleService = mock(com.intermarche.pos.service.SupervisorSettleService.class);
        PosState state = mock(PosState.class);
        state.ticket = new com.intermarche.pos.ui.ticket.TicketState();
        state.endorsement = new EndorsementState();
        state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        state.endorsement.request("CANCEL_TICKET");
        service.abandonRequest(state);
        verify(service.supervisorSettleService, never()).authorizationWithdrawn();
        state.endorsement.request("CANCEL_TICKET");
        state.endorsement.remoteRequested = true;
        state.serviceRequest = PosState.ServiceRequestStatus.PENDING;
        service.abandonRequest(state);
        verify(service.supervisorSettleService, never()).authorizationWithdrawn();
        assertFalse(state.endorsement.active);
        state.endorsement.request("CANCEL_TICKET");
        state.endorsement.remoteRequested = true;
        state.serviceRequest = PosState.ServiceRequestStatus.NONE;
        service.abandonRequest(state);
        verify(service.supervisorSettleService).authorizationWithdrawn();
        assertFalse(state.endorsement.active);
        assertFalse(state.endorsement.remoteRequested);
    }

    /** BO-04-02-03: an approved local supervisor gesture clears the outstanding store-node request. */
    @Test
    void localApprovalNotifiesTheStoreNode() {
        EndorsementService service = newService();
        service.supervisorSettleService = mock(com.intermarche.pos.service.SupervisorSettleService.class);
        Employee manager = new Employee();
        manager.role = Employee.EmployeeRole.MANAGER;
        var granted = status(manager);
        when(service.authService.checkCredentials("M", "PIN")).thenReturn(granted);
        assertEquals(AuthService.CheckOutcome.GRANTED, service.authorizeOutcome("M", "PIN", "ACTION"));
        verify(service.supervisorSettleService).settledAtTill();
    }

    /** LC-01-05-01: a function decision applies only to this request and never invents a manager role. */
    @Test
    void exactFunctionDecisionAndRealApproval() {
        EndorsementService service = newService();
        PosState state = new PosState();
        service.functionApproval.exempt();
        assertTrue(service.selfAuthorized(state));
        assertTrue(service.gestureAuthorized(state, "refund"));
        assertFalse(service.functionApproved());
        service.functionApproval.approve();
        assertTrue(service.functionApproved());
        assertFalse(service.operatorIsSupervisor(state));
    }

    // --- BO-10-02-31: the supervisor validation modes ---

    /**
     * Builds a register state whose endorsement request carries the given
     * physical proofs.
     *
     * @param badge the barcode the scanner read, or null
     * @param dallas the Dallas key the reader delivered, or null
     * @return the state
     */
    private PosState panelState(String badge, String dallas) {
        PosState state = mock(PosState.class);
        state.endorsement = new EndorsementState();
        state.endorsement.presentedBadge = badge;
        state.endorsement.presentedDallasKey = dallas;
        return state;
    }

    /**
     * Builds an active employee with an id, a role and a lock state.
     *
     * @param id the employee id
     * @param role the role
     * @param locked whether the account is under lockout
     * @return the employee mock
     */
    private Employee holder(long id, Employee.EmployeeRole role, boolean locked) {
        Employee employee = mock(Employee.class);
        employee.id = id;
        employee.role = role;
        when(employee.isCurrentlyLocked()).thenReturn(locked);
        return employee;
    }

    /**
     * The administered mode is read tolerantly; a service without its
     * parameters asks code and password.
     */
    @Test
    void validationModeReadsTheAdministeredMode() {
        EndorsementService service = newService();
        when(service.posSettingsService.supervisorValidationMode()).thenReturn(" dallas ");
        assertEquals(SupervisorValidationMode.DALLAS, service.validationMode());
        service.posSettingsService = null;
        assertEquals(SupervisorValidationMode.CODE_PASSWORD, service.validationMode());
    }

    /**
     * DALLAS: the key the reader delivered endorses alone for a supervisor; no
     * key, or a key nobody holds, endorses nothing.
     */
    @Test
    void theDallasKeyEndorsesAlone() {
        EndorsementService service = newService();
        service.supervisorSettleService = mock(com.intermarche.pos.service.SupervisorSettleService.class);
        Employee manager = holder(1L, Employee.EmployeeRole.MANAGER, false);
        try (MockedStatic<Employee> employees = mockStatic(Employee.class)) {
            employees.when(() -> Employee.findActiveDallasKey("KEY")).thenReturn(manager);
            assertFalse(service.authorizePanel(panelState(null, null), SupervisorValidationMode.DALLAS,
                    null, null, "CANCEL_TICKET"));
            assertTrue(service.authorizePanel(panelState(null, "KEY"), SupervisorValidationMode.DALLAS,
                    null, null, "CANCEL_TICKET"));
            assertFalse(service.authorizePanel(panelState(null, "OTHER"), SupervisorValidationMode.DALLAS,
                    null, null, "CANCEL_TICKET"));
        }
        verify(service.technicalEventService).log(TechnicalEvent.EventType.ENDORSEMENT_GRANTED,
                "CANCEL_TICKET par KEY", "KEY");
        verify(service.supervisorSettleService).settledAtTill();
    }

    /**
     * BARCODE: no barcode read refuses; a read barcode endorses alone for a
     * holder who may endorse — by role or by supervisor card — and is not
     * locked out.
     */
    @Test
    void theBarcodeEndorsesAloneWhenRead() {
        EndorsementService service = newService();
        Employee carded = holder(2L, Employee.EmployeeRole.CASHIER, false);
        carded.supervisorCard = true;
        Employee cashier = holder(3L, Employee.EmployeeRole.CASHIER, false);
        Employee locked = holder(4L, Employee.EmployeeRole.MANAGER, true);
        try (MockedStatic<Employee> employees = mockStatic(Employee.class)) {
            employees.when(() -> Employee.findActiveLogin("CARD")).thenReturn(carded);
            employees.when(() -> Employee.findActiveLogin("CASH")).thenReturn(cashier);
            employees.when(() -> Employee.findActiveLogin("LOCK")).thenReturn(locked);
            assertFalse(service.authorizePanel(panelState(null, null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
            assertTrue(service.authorizePanel(panelState("CARD", null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
            assertFalse(service.authorizePanel(panelState("CASH", null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
            assertFalse(service.authorizePanel(panelState("LOCK", null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
            assertFalse(service.authorizePanel(panelState("NOBODY", null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
        }
        verify(service.technicalEventService).log(TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                "CANCEL_TICKET par ? (code-barres superviseur non lu)", null);
    }

    /**
     * BARCODE_PASSWORD: the read barcode and the typed password go through
     * the credential check, the posted login playing no part.
     */
    @Test
    void theBarcodeAndPasswordGoThroughTheCredentialCheck() {
        EndorsementService service = newService();
        Employee manager = holder(1L, Employee.EmployeeRole.MANAGER, false);
        when(service.authService.checkCredentials("11111111", "1111")).thenReturn(status(manager));
        PosState state = panelState("11111111", "KEY");
        assertTrue(service.authorizePanel(state,
                SupervisorValidationMode.BARCODE_PASSWORD, null, "1111", "CANCEL_TICKET"));
        // A proof serves one validation: both are consumed.
        assertNull(state.endorsement.presentedBadge);
        assertNull(state.endorsement.presentedDallasKey);
    }

    /**
     * BARCODE_CODE_PASSWORD: the typed code must designate the holder of the
     * read barcode — absent, blank, unknown or someone else's code refuses —
     * and the password then goes through the credential check.
     */
    @Test
    void theBarcodeCodeAndPasswordMustNameOneHolder() {
        EndorsementService service = newService();
        Employee manager = holder(1L, Employee.EmployeeRole.MANAGER, false);
        Employee other = holder(9L, Employee.EmployeeRole.MANAGER, false);
        when(service.authService.checkCredentials("11111111", "1111")).thenReturn(status(manager));
        SupervisorValidationMode mode = SupervisorValidationMode.BARCODE_CODE_PASSWORD;
        try (MockedStatic<Employee> employees = mockStatic(Employee.class)) {
            employees.when(() -> Employee.findActiveLogin("11111111")).thenReturn(manager);
            employees.when(() -> Employee.findActiveLogin("mcurie")).thenReturn(manager);
            employees.when(() -> Employee.findActiveLogin("other")).thenReturn(other);
            assertFalse(service.authorizePanel(panelState("11111111", null), mode, null, "1111", "A"));
            assertFalse(service.authorizePanel(panelState("11111111", null), mode, " ", "1111", "A"));
            assertFalse(service.authorizePanel(panelState("11111111", null), mode, "nobody", "1111", "A"));
            assertFalse(service.authorizePanel(panelState("11111111", null), mode, "other", "1111", "A"));
            assertFalse(service.authorizePanel(panelState("22222222", null), mode, "mcurie", "1111", "A"));
            assertTrue(service.authorizePanel(panelState("11111111", null), mode, " MCURIE ", "1111", "A"));
        }
        verify(service.technicalEventService, times(4)).log(TechnicalEvent.EventType.ENDORSEMENT_DENIED,
                "A par 11111111 (code superviseur différent)", "11111111");
    }

    /**
     * Builds the checkout point service of a register.
     *
     * @param type the type of the point, null for no point at all
     * @param hostessBadge the technical hostess attached to it
     * @return the mocked service
     */
    private com.intermarche.pos.service.CheckoutPointService lane(com.intermarche.pos.domain.store.TpvType type,
            String hostessBadge) {
        com.intermarche.pos.service.CheckoutPointService points =
                mock(com.intermarche.pos.service.CheckoutPointService.class);
        if (type != null) {
            com.intermarche.pos.domain.store.CheckoutPoint point = new com.intermarche.pos.domain.store.CheckoutPoint();
            point.tpvType = type;
            point.technicalHostessBadge = hostessBadge;
            when(points.own()).thenReturn(point);
        }
        return points;
    }

    /**
     * BO-03-09-08: only the technical hostess attached to this self-service
     * lane is its hostess — every other leg of the check refuses.
     */
    @Test
    void theLaneHostessIsTheOneAttachedToThisSelfServicePoint() {
        EndorsementService service = newService();
        Employee hostess = employee(Employee.EmployeeRole.TECHNICAL_HOSTESS);
        hostess.badgeId = "H1";
        assertFalse(service.isLaneHostess(hostess));
        service.checkoutPointService = lane(com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_NCR, "H1");
        assertTrue(service.isLaneHostess(hostess));
        Employee noBadge = employee(Employee.EmployeeRole.TECHNICAL_HOSTESS);
        assertFalse(service.isLaneHostess(noBadge));
        Employee cashier = employee(Employee.EmployeeRole.CASHIER);
        cashier.badgeId = "H1";
        assertFalse(service.isLaneHostess(cashier));
        Employee other = employee(Employee.EmployeeRole.TECHNICAL_HOSTESS);
        other.badgeId = "H2";
        assertFalse(service.isLaneHostess(other));
        service.checkoutPointService = lane(com.intermarche.pos.domain.store.TpvType.CASH_REGISTER, "H1");
        assertFalse(service.isLaneHostess(hostess));
        service.checkoutPointService = lane(null, null);
        assertFalse(service.isLaneHostess(hostess));
    }

    /**
     * BO-03-09-08: the hostess of the lane endorses by her code and password
     * as well as by her badge alone.
     */
    @Test
    void theLaneHostessEndorsesOnHerLane() {
        EndorsementService service = newService();
        service.checkoutPointService = lane(com.intermarche.pos.domain.store.TpvType.SELF_CHECKOUT_NCR, "H1");
        Employee hostess = holder(9L, Employee.EmployeeRole.TECHNICAL_HOSTESS, false);
        hostess.badgeId = "H1";
        when(service.authService.checkCredentials("H1", "1234")).thenReturn(status(hostess));
        assertEquals(AuthService.CheckOutcome.GRANTED, service.authorizeOutcome("H1", "1234", "CANCEL_TICKET"));
        try (MockedStatic<Employee> employees = mockStatic(Employee.class)) {
            employees.when(() -> Employee.findActiveLogin("H1")).thenReturn(hostess);
            assertTrue(service.authorizePanel(panelState("H1", null), SupervisorValidationMode.BARCODE,
                    null, null, "CANCEL_TICKET"));
        }
    }

    /**
     * BO-04-02-01: a holder of an administered endorsing profile endorses
     * whatever the role; a gesture whose rule names its profiles is endorsed
     * by their holders alone, the endorsing roles included.
     */
    @Test
    void profilesEndorse() {
        EndorsementService service = newService();
        Employee cashier = new Employee();
        cashier.role = Employee.EmployeeRole.CASHIER;
        cashier.profileNames = "Chef de caisse; Gérant";
        Employee manager = new Employee();
        manager.role = Employee.EmployeeRole.MANAGER;
        assertFalse(service.mayEndorse(cashier));
        when(service.posSettingsService.endorsementProfiles()).thenReturn("gérant, ");
        assertTrue(service.mayEndorse(cashier));
        assertTrue(service.mayEndorse(manager));
        service.posState = new PosState();
        assertTrue(service.mayEndorse(manager));
        service.posState.endorsement.requiredProfiles = java.util.List.of("Chef de caisse");
        assertTrue(service.mayEndorse(cashier));
        assertFalse(service.mayEndorse(manager));
        service.posState.endorsement = null;
        assertTrue(service.mayEndorse(manager));
        service.posSettingsService = null;
        assertTrue(service.administeredProfiles().isEmpty());
        assertTrue(EndorsementService.names(null).isEmpty());
    }
}
