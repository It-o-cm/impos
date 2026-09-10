package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.ticket.TicketParkingService;
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
        return resource;
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
     * Wires the parked template so every {@code data(...)} call chains, and
     * returns the last link — what the resource hands back.
     *
     * @param resource the resource whose template is wired
     * @return the terminal template instance of the chain
     */
    private TemplateInstance wireParkedTemplate(ParkedTicketResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.parked.data(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(instance);
        when(instance.data(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(instance);
        return instance;
    }

    /**
     * {@code parkedPage(page)} renders the parked-tickets page seeded with the
     * state and the register's parked tickets when the terminal is unlocked
     * (guard false arm).
     */
    @Test
    void parkedPageRendersParkedWhenUnlocked() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        List<com.intermarche.pos.domain.ticket.Ticket> tickets = List.of();
        when(resource.ticketParkingService.listParked()).thenReturn(tickets);
        TemplateInstance instance = wireParkedTemplate(resource);
        assertSame(instance, resource.parkedPage(null));
        verify(resource.parked).data("state", resource.state);
        verify(instance).data("tickets", tickets);
        verify(instance).data("page", 1);
        verify(instance).data("pageCount", 1);
    }

    /**
     * An empty list still counts as one page, so the pager never announces
     * "page 1 / 0" (size zero arm).
     */
    @Test
    void parkedPageCountsOnePageWhenEmpty() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.listParked()).thenReturn(List.of());
        TemplateInstance instance = wireParkedTemplate(resource);
        resource.parkedPage(null);
        verify(instance).data("pageCount", 1);
        verify(instance).data("hasPrev", false);
        verify(instance).data("hasNext", false);
    }

    /**
     * Seven parked tickets make two pages of six; the first page carries the
     * first six and announces a next page (size above one page arm).
     */
    @Test
    void parkedPageCutsSevenTicketsInTwoPages() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.listParked()).thenReturn(parkedTickets(7));
        TemplateInstance instance = wireParkedTemplate(resource);
        resource.parkedPage(0);
        verify(instance).data("pageCount", 2);
        verify(instance).data("hasPrev", false);
        verify(instance).data("hasNext", true);
        verify(instance).data("nextPage", 1);
    }

    /**
     * The last page carries the remainder and announces no next page
     * (upper-bound arm).
     */
    @Test
    void parkedPageShowsRemainderOnLastPage() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.listParked()).thenReturn(parkedTickets(7));
        TemplateInstance instance = wireParkedTemplate(resource);
        resource.parkedPage(1);
        verify(instance).data("page", 2);
        verify(instance).data("hasPrev", true);
        verify(instance).data("hasNext", false);
        verify(instance).data("prevPage", 0);
    }

    /**
     * A page number below zero is clamped to the first page rather than
     * throwing (lower-clamp arm).
     */
    @Test
    void parkedPageClampsNegativePageToFirst() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.listParked()).thenReturn(parkedTickets(7));
        TemplateInstance instance = wireParkedTemplate(resource);
        resource.parkedPage(-5);
        verify(instance).data("page", 1);
    }

    /**
     * A page number past the end is clamped to the last page rather than
     * rendering an empty list (upper-clamp arm).
     */
    @Test
    void parkedPageClampsTooLargePageToLast() {
        ParkedTicketResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.ticketParkingService.listParked()).thenReturn(parkedTickets(7));
        TemplateInstance instance = wireParkedTemplate(resource);
        resource.parkedPage(99);
        verify(instance).data("page", 2);
    }

    /**
     * Builds a list of that many distinct parked tickets.
     *
     * @param count how many tickets to build
     * @return the list, never null
     */
    private List<com.intermarche.pos.domain.ticket.Ticket> parkedTickets(int count) {
        List<com.intermarche.pos.domain.ticket.Ticket> tickets = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            tickets.add(new com.intermarche.pos.domain.ticket.Ticket());
        }
        return tickets;
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
