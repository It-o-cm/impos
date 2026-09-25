package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.home.HomeService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketAbandonResource}, the abandon screen resource.
 * <p>
 * Both handlers are thin: {@code showAbandonScreen} renders the page, and
 * {@code confirmAbandon} asks the shop's rules to prepare the abandon and
 * either hands the gesture to the manager endorsement or renders the page
 * carrying the refusal. All collaborators are mocked; the injected {@link
 * Template} is stubbed to self-chain so the private {@code page} helper builds
 * a {@link TemplateInstance} without a Qute engine. Tests cover both arms of
 * the {@code refusal != null} guard and both arms of the {@code print != null}
 * flag (four branches).
 */
class TicketAbandonResourceTest {

    /**
     * Builds a resource whose collaborators are fresh mocks, the abandon
     * template wired to self-chain onto a single {@link TemplateInstance}.
     *
     * @param view the template instance every {@code data} call returns
     * @return the wired resource
     */
    private TicketAbandonResource newResource(TemplateInstance view) {
        TicketAbandonResource resource = new TicketAbandonResource();
        resource.state = mock(PosState.class);
        resource.ticketAbandonService = mock(TicketAbandonService.class);
        resource.homeService = mock(HomeService.class);
        resource.abandonPage = mock(Template.class);
        when(resource.abandonPage.data(eq("state"), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return resource;
    }

    /**
     * The GET renders the abandon page bound to the register state, with no
     * error.
     */
    @Test
    void showAbandonScreenRendersThePage() {
        TemplateInstance view = mock(TemplateInstance.class);
        TicketAbandonResource resource = newResource(view);
        assertSame(view, resource.showAbandonScreen());
        verify(resource.abandonPage).data(eq("state"), any());
        verify(view).data("error", null);
    }

    /**
     * A refusal from the shop's rules keeps the operator on the abandon page
     * carrying that refusal — the true arm of {@code refusal != null} — and the
     * endorsement is never engaged. The blank print flag exercises the {@code
     * print != null} false arm.
     */
    @Test
    void confirmAbandonRendersTheRefusalAndDoesNotEndorse() {
        TemplateInstance view = mock(TemplateInstance.class);
        TicketAbandonResource resource = newResource(view);
        when(resource.ticketAbandonService.prepare("VOL", false)).thenReturn("REFUS");
        Object result = resource.confirmAbandon("VOL", null);
        assertSame(view, result);
        verify(view).data("error", "REFUS");
        verify(resource.homeService, never()).cancelTicket();
    }

    /**
     * No refusal hands the gesture to the manager endorsement and redirects to
     * the sale screen — the false arm of {@code refusal != null}. The {@code
     * on} print flag exercises the {@code print != null} true arm, prepared as
     * a printing request.
     */
    @Test
    void confirmAbandonEndorsesAndRedirectsWhenAccepted() {
        TemplateInstance view = mock(TemplateInstance.class);
        TicketAbandonResource resource = newResource(view);
        when(resource.ticketAbandonService.prepare("ERREUR", true)).thenReturn(null);
        Object result = resource.confirmAbandon("ERREUR", "on");
        verify(resource.homeService).cancelTicket();
        assertTrue(result instanceof Response);
        Response response = (Response) result;
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }
}
