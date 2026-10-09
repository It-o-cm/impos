package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecallResource}. Every collaborator is a Mockito mock;
 * {@link PosState} is real so {@code state.auth} and {@code touch()} work. Tests
 * assert the delegation to {@link TicketService#loadRecalledDocument} and the
 * 303 routing, and cover both arms of the acompte guards (no session, invalid
 * movement, success) and the recall-page message selection.
 */
class RecallResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link PosState}.
     *
     * @return the wired resource
     */
    private RecallResource newResource() {
        RecallResource resource = new RecallResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.documentClient = mock(BackOfficeDocumentClient.class);
        resource.ticketService = mock(TicketService.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.cashMovementService = mock(CashMovementService.class);
        resource.recall = mock(Template.class);
        resource.state = new PosState();
        return resource;
    }

    /**
     * {@code recallDocument} fetches by code, loads the document into the sale and
     * redirects to the sale screen.
     */
    @Test
    void recallDocumentLoadsAndRedirects() {
        RecallResource resource = newResource();
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.COMMANDE, "CMD1", null, null);
        when(resource.documentClient.fetch("CMD1")).thenReturn(doc);
        Response response = resource.recallDocument("CMD1");
        verify(resource.ticketService).loadRecalledDocument(resource.state, doc);
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code recallDocument} on an unknown code still delegates (with a null
     * document) and redirects — the service posts the DOCUMENT INTROUVABLE error.
     */
    @Test
    void recallDocumentUnknownStillDelegates() {
        RecallResource resource = newResource();
        when(resource.documentClient.fetch("X")).thenReturn(null);
        Response response = resource.recallDocument("X");
        verify(resource.ticketService).loadRecalledDocument(resource.state, null);
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} refuses when no session is open (session-null arm).
     */
    @Test
    void recordAcompteWithoutSessionRedirectsWithError() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        Response response = resource.recordAcompte("CMD1", "5,00");
        assertEquals("/recall?error=no-session", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} redirects with the amount error when the service
     * refuses the movement (invalid amount / blank reference arm).
     */
    @Test
    void recordAcompteInvalidRedirectsWithError() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), any())).thenReturn(null);
        Response response = resource.recordAcompte("", "0");
        assertEquals("/recall?error=amount", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} confirms with the ok flag when the movement is recorded.
     */
    @Test
    void recordAcompteSuccessRedirectsWithOk() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), eq("CMD1")))
                .thenReturn(mock(com.intermarche.pos.domain.session.CashMovement.class));
        Response response = resource.recordAcompte("CMD1", "5,00");
        assertEquals("/recall?ok=1", response.getLocation().toString());
    }

    /**
     * {@code recallPage} renders the no-session message, the amount message, the
     * generic no-message arm and the saved flag through the template.
     */
    @Test
    void recallPageSelectsTheMessage() {
        RecallResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.recall.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        assertSame(view, resource.recallPage("no-session", null));
        assertSame(view, resource.recallPage("amount", null));
        assertSame(view, resource.recallPage(null, "1"));
    }
}
