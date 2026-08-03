package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.TicketParkingService;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParkedTicketResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, the
 * {@link TicketParkingService} and two Qute {@link Template}s
 * ({@code parked} and {@code lock}). Every collaborator is a Mockito mock:
 * {@code PosState} exposes its {@code isLocked()} decision and a mocked
 * {@link TicketState} through its public {@code ticket} field, the templates
 * return distinct {@link TemplateInstance} mocks along the fluent
 * {@code data(...)} chain so the exact rendered view can be identified, and the
 * redirect {@link Response}s are asserted on their absolute status and
 * location. Both arms of every lock guard and every error-null guard are
 * covered.
 */
class ParkedTicketResourceTest {

    /**
     * Builds a {@link ParkedTicketResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, including a mocked
     * {@link TicketState} reachable through {@code state.ticket}.
     *
     * @return a resource with fully mocked state, ticket, service and templates
     */
    private ParkedTicketResource newResource() {
        ParkedTicketResource resource = new ParkedTicketResource();
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.ticketParkingService = mock(TicketParkingService.class);
        resource.parked = mock(Template.class);
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * {@code parkCurrent()} redirects to the lock page and never touches the
     * parking service when the terminal is locked (guard true arm).
     */
    @Test
    void parkCurrentRedirectsToLockWhenLocked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.parkCurrent();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/lock", response.getLocation().toString());
        verifyNoInteractions(resource.ticketParkingService);
        verify(resource.state.ticket, never()).setError(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code parkCurrent()} parks the cart, records the returned error on the
     * ticket and redirects home when unlocked and the service refuses (guard
     * false arm, error non-null arm).
     */
    @Test
    void parkCurrentRecordsErrorWhenServiceRefuses() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.parkCurrent()).thenReturn("nothing to park");
        Response response = resource.parkCurrent();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket).setError("nothing to park");
    }

    /**
     * {@code parkCurrent()} parks the cart and redirects home without recording
     * any error when unlocked and the service accepts (guard false arm, error
     * null arm).
     */
    @Test
    void parkCurrentRedirectsHomeWhenParked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.parkCurrent()).thenReturn(null);
        Response response = resource.parkCurrent();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket, never()).setError(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code parkedPage()} renders the lock page seeded with the state and a
     * null error, and never lists parked tickets, when the terminal is locked
     * (guard true arm).
     */
    @Test
    void parkedPageRendersLockWhenLocked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withError = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(withState);
        when(withState.data("error", null)).thenReturn(withError);
        assertSame(withError, resource.parkedPage());
        verifyNoInteractions(resource.ticketParkingService);
        verifyNoInteractions(resource.parked);
    }

    /**
     * {@code parkedPage()} renders the parked-tickets page seeded with the state
     * and the register's parked tickets when the terminal is unlocked (guard
     * false arm).
     */
    @Test
    void parkedPageRendersParkedWhenUnlocked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<com.intermarche.pos.domain.ticket.Ticket> tickets = List.of();
        when(resource.ticketParkingService.listParked()).thenReturn(tickets);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withTickets = mock(TemplateInstance.class);
        when(resource.parked.data("state", resource.state)).thenReturn(withState);
        when(withState.data("tickets", tickets)).thenReturn(withTickets);
        assertSame(withTickets, resource.parkedPage());
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code resume(id)} redirects to the lock page and never touches the
     * parking service when the terminal is locked (guard true arm).
     */
    @Test
    void resumeRedirectsToLockWhenLocked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.resume(7L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/lock", response.getLocation().toString());
        verifyNoInteractions(resource.ticketParkingService);
        verify(resource.state.ticket, never()).setError(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * {@code resume(id)} resumes the ticket, records the returned error on the
     * ticket and redirects home when unlocked and the service refuses (guard
     * false arm, error non-null arm).
     */
    @Test
    void resumeRecordsErrorWhenServiceRefuses() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.resume(7L)).thenReturn("already resumed");
        Response response = resource.resume(7L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket).setError("already resumed");
    }

    /**
     * {@code resume(id)} resumes the ticket and redirects home without recording
     * any error when unlocked and the service accepts (guard false arm, error
     * null arm).
     */
    @Test
    void resumeRedirectsHomeWhenResumed() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.resume(7L)).thenReturn(null);
        Response response = resource.resume(7L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket, never()).setError(org.mockito.ArgumentMatchers.anyString());
    }
}
