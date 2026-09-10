package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketEmailResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState} (carrying a real
 * {@link FidelityState} whose {@code holderEmail} field the tests set directly),
 * a {@link TicketService}, a {@link TicketMailService}, a {@link PosSettingsService}
 * and the {@code ticket-email} Qute {@link Template}. Every collaborator is a
 * Mockito mock; the template echoes a recognizable {@link TemplateInstance} so the
 * returned view can be identified and its {@code data(...)} delegation verified. The
 * {@code Ticket.findById} static finder is intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. Tests assert
 * absolute expected values (response status, {@code Location}, returned view, mutated
 * fields) and cover both arms of the last-closed-ticket gate, the holder-address
 * ternary, the email trim ternary, the training and no-ticket guards, both arms of
 * the address-format guard, the {@code findById} null guard and every leg of the
 * {@code known}/{@code editable} compound booleans.
 */
class TicketEmailResourceTest {

    /**
     * Builds a {@link TicketEmailResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, with a real {@link FidelityState} on the mocked
     * {@link PosState} so the {@code holderEmail} field reads never NPE.
     *
     * @return a resource with fully mocked state, services and template
     */
    private TicketEmailResource newResource() {
        TicketEmailResource resource = new TicketEmailResource();
        resource.state = mock(PosState.class);
        resource.state.fidelity = new FidelityState();
        resource.ticketMailService = mock(TicketMailService.class);
        resource.ticketService = mock(TicketService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        resource.ticketEmailPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code ticket-email} template so the whole {@code data(...)} chain
     * returns one recognizable view for the given resource's state.
     *
     * @param resource the resource whose {@code ticketEmailPage} template is stubbed
     * @return the view the {@code data(...)} chain resolves to
     */
    private TemplateInstance stubPage(TicketEmailResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.ticketEmailPage.data("state", resource.state)).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    // --- showEmailScreen ---

    /**
     * {@code showEmailScreen()} redirects to the sale screen when the register has
     * closed no sale yet (gate {@code !requireLastClosedTicket()} true arm).
     */
    @Test
    void showEmailScreenRedirectsWhenNoLastTicket() {
        TicketEmailResource resource = newResource();
        when(resource.state.requireLastClosedTicket()).thenReturn(false);
        Object result = resource.showEmailScreen();
        Response response = (Response) result;
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).resolveLastClosedTicketId(resource.state);
    }

    /**
     * {@code showEmailScreen()} offers an empty address when the referential holds
     * none (gate false arm; holder ternary null arm; {@code known} first leg false so
     * {@code editable} short-circuits without querying the back office).
     */
    @Test
    void showEmailScreenOffersEmptyAddressWhenHolderUnknown() {
        TicketEmailResource resource = newResource();
        when(resource.state.requireLastClosedTicket()).thenReturn(true);
        resource.state.fidelity.holderEmail = null;
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.showEmailScreen());
        verify(view).data("address", "");
        verify(view).data("known", false);
        verify(view).data("editable", true);
        verify(resource.posSettingsService, never()).ticketEmailEditable();
    }

    /**
     * {@code showEmailScreen()} pre-fills the held address when the referential gave
     * one (holder ternary non-null arm; {@code known} both legs true; {@code editable}
     * first leg false so the back-office flag is consulted and true wins).
     */
    @Test
    void showEmailScreenPreFillsHeldEditableAddress() {
        TicketEmailResource resource = newResource();
        when(resource.state.requireLastClosedTicket()).thenReturn(true);
        resource.state.fidelity.holderEmail = "jean@example.fr";
        when(resource.posSettingsService.ticketEmailEditable()).thenReturn(true);
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.showEmailScreen());
        verify(view).data("address", "jean@example.fr");
        verify(view).data("known", true);
        verify(view).data("editable", true);
    }

    // --- sendEmail ---

    /**
     * {@code sendEmail()} refuses in training mode (email trim ternary non-null arm;
     * training guard true arm), rendering the training refusal without sending.
     */
    @Test
    void sendEmailRefusesInTrainingMode() {
        TicketEmailResource resource = newResource();
        resource.state.trainingMode = true;
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.sendEmail("  jean@example.fr  "));
        verify(view).data("address", "jean@example.fr");
        verify(view).data("error", PosState.TRAINING_FORBIDDEN);
        verify(view).data("sent", false);
        verify(resource.ticketMailService, never()).send(any(), anyString());
    }

    /**
     * {@code sendEmail()} refuses when no last ticket is known (email trim ternary null
     * arm defaulting to empty; training guard false arm; no-ticket guard true arm).
     */
    @Test
    void sendEmailRefusesWhenNoLastTicketId() {
        TicketEmailResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.lastClosedTicketId = null;
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.sendEmail(null));
        verify(view).data("address", "");
        verify(view).data("error", PosState.NO_LAST_TICKET);
        verify(view).data("sent", false);
        verify(resource.ticketMailService, never()).send(any(), anyString());
    }

    /**
     * {@code sendEmail()} rejects a malformed address (no-ticket guard false arm;
     * format guard true arm) and, with a held non-blank address the back office locks,
     * renders {@code known} true and {@code editable} false (editable second leg false).
     */
    @Test
    void sendEmailRejectsMalformedAddress() {
        TicketEmailResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.lastClosedTicketId = 5L;
        resource.state.fidelity.holderEmail = "marie@example.fr";
        when(resource.posSettingsService.ticketEmailEditable()).thenReturn(false);
        TemplateInstance view = stubPage(resource);
        assertSame(view, resource.sendEmail("  bad  "));
        verify(view).data("address", "bad");
        verify(view).data("error", "ADRESSE INVALIDE");
        verify(view).data("known", true);
        verify(view).data("editable", false);
        verify(resource.ticketMailService, never()).send(any(), anyString());
    }

    /**
     * {@code sendEmail()} refuses when the ticket is gone (format guard false arm;
     * {@code findById} null guard true arm), rendering the missing-ticket refusal.
     */
    @Test
    void sendEmailRefusesWhenTicketVanished() {
        TicketEmailResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.lastClosedTicketId = 5L;
        TemplateInstance view = stubPage(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            assertSame(view, resource.sendEmail("jane@example.fr"));
        }
        verify(view).data("address", "jane@example.fr");
        verify(view).data("error", "AUCUN TICKET À ENVOYER");
        verify(view).data("sent", false);
        verify(resource.ticketMailService, never()).send(any(), anyString());
    }

    /**
     * {@code sendEmail()} sends when the address is valid and the ticket present
     * ({@code findById} null guard false arm): it stamps the address on the ticket,
     * persists it, delegates the send and renders success. A held blank address keeps
     * {@code known} false (second leg of {@code known} false via {@code isBlank()}).
     */
    @Test
    void sendEmailSendsWhenAddressValidAndTicketPresent() {
        TicketEmailResource resource = newResource();
        resource.state.trainingMode = false;
        resource.state.lastClosedTicketId = 5L;
        resource.state.fidelity.holderEmail = "   ";
        Ticket ticket = mock(Ticket.class);
        TemplateInstance view = stubPage(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertSame(view, resource.sendEmail("john@example.fr"));
        }
        assertEquals("john@example.fr", ticket.customerEmail);
        verify(ticket).persist();
        verify(resource.ticketMailService).send(ticket, "john@example.fr");
        verify(view).data("address", "john@example.fr");
        verify(view).data("known", false);
        verify(view).data("editable", true);
        verify(view).data("sent", true);
        verify(resource.posSettingsService, never()).ticketEmailEditable();
    }
}
