package com.intermarche.pos.ui.admin;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.Principal;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAuthResource}, focused on the back-office
 * password change ({@code changePassword}) and its journaling of a
 * {@code PASSWORD_CHANGED} event (BO-04-01-29).
 * <p>
 * The resource verifies and rewrites the back-office credential inside a
 * {@code QuarkusTransaction.requiringNew().call(...)} block; under plain
 * {@code mvn test} there is no transaction manager, so the static
 * {@link io.quarkus.narayana.jta.QuarkusTransaction} is intercepted with
 * {@link org.mockito.Mockito#mockStatic} and its callable simply run, and the
 * {@link Employee} finder resolves to {@link PanacheEntityBase} and is
 * intercepted the same way. The injected {@link TechnicalEventService} and
 * {@link SecurityIdentity} are mocks. Every validation gate has its own case,
 * and the emission is asserted on the {@code log} call, not on a database.
 */
class AdminAuthResourceTest {

    /**
     * Builds a resource with a mocked emitter and a mocked identity resolving
     * to the given back-office login name.
     *
     * @param loginName the signed-in principal name
     * @return the wired resource
     */
    private AdminAuthResource newResource(String loginName) {
        AdminAuthResource resource = new AdminAuthResource();
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(loginName);
        when(resource.identity.getPrincipal()).thenReturn(principal);
        return resource;
    }

    /**
     * Runs {@code changePassword} with the {@link io.quarkus.narayana.jta.QuarkusTransaction}
     * static intercepted so the transactional callable is executed in place and
     * its result returned, and the {@link Employee} finder stubbed to the given
     * employee (or none).
     *
     * @param resource the resource under test
     * @param loginName the signed-in login name the finder keys on
     * @param employee the employee the finder returns, or null
     * @param current the current password submitted
     * @param renewed the new password submitted
     * @param confirmation the confirmation submitted
     * @return the resource response
     */
    private Response run(AdminAuthResource resource, String loginName, Employee employee,
                         String current, String renewed, String confirmation) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<io.quarkus.narayana.jta.QuarkusTransaction> tx =
                     mockStatic(io.quarkus.narayana.jta.QuarkusTransaction.class,
                             org.mockito.Answers.RETURNS_DEEP_STUBS)) {
            PanacheQuery<Employee> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(employee);
            panache.when(() -> Employee.find("loginName", loginName)).thenReturn(query);
            tx.when(() -> io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(any()))
                    .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
            return resource.changePassword(current, renewed, confirmation);
        }
    }

    /**
     * A valid change on a verifiable account rewrites the credential, clears the
     * forced-change flag, journals a {@code PASSWORD_CHANGED} event by badge and
     * redirects to the sign-in page (applied arm).
     */
    @Test
    void changePasswordAppliesRewritesAndJournalsByBadge() {
        AdminAuthResource resource = newResource("mcurie");
        Employee employee = mock(Employee.class);
        employee.badgeId = "11111111";
        when(employee.verifyBackOfficePassword("oldsecret")).thenReturn(true);
        Response response = run(resource, "mcurie", employee, "oldsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/login"));
        verify(employee).setBackOfficePassword("newsecret");
        assertEquals(false, employee.mustChangePassword);
        verify(resource.technicalEventService).log(TechnicalEvent.EventType.PASSWORD_CHANGED, null, "11111111");
    }

    /**
     * A new password shorter than the minimum is refused up front (short arm),
     * without touching the transaction or the journal.
     */
    @Test
    void changePasswordRefusesShortPassword() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("oldsecret", "short", "short");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A new password not matching its confirmation is refused (mismatch arm).
     */
    @Test
    void changePasswordRefusesConfirmationMismatch() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("oldsecret", "newsecret", "different");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A new password equal to the current one is refused (same-as-current arm).
     */
    @Test
    void changePasswordRefusesSameAsCurrent() {
        AdminAuthResource resource = newResource("mcurie");
        Response response = resource.changePassword("newsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * A wrong current password fails the verification inside the transaction, so
     * the change is not applied, nothing is journaled and the form refuses
     * (not-applied arm).
     */
    @Test
    void changePasswordRefusesWrongCurrent() {
        AdminAuthResource resource = newResource("mcurie");
        Employee employee = mock(Employee.class);
        when(employee.verifyBackOfficePassword("wrong")).thenReturn(false);
        Response response = run(resource, "mcurie", employee, "wrong", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(employee, never()).setBackOfficePassword(any());
        verify(resource.technicalEventService, never()).log(any(), any());
    }

    /**
     * An unknown account (finder returns null) is treated as a failed
     * verification: not applied, nothing journaled (employee-null arm).
     */
    @Test
    void changePasswordRefusesUnknownAccount() {
        AdminAuthResource resource = newResource("ghost");
        Response response = run(resource, "ghost", null, "oldsecret", "newsecret", "newsecret");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().startsWith("/admin/password"));
        verify(resource.technicalEventService, never()).log(any(), any());
    }
}
