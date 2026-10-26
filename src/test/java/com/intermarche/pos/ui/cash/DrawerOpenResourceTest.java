package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosListService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DrawerOpenResource}, the forced drawer opening of
 * LC-12-01-01 with its administered reason (LC-12-01-02). Every collaborator is
 * mocked; the ticket is a real {@link TicketState} so the outside-transaction
 * guard reads real lists. Covered: the three legs of that guard on both entry
 * points, the chooser with and without administered reasons, an unknown code,
 * the three authorisation paths (replayed manager, supervisor at the till,
 * parked request) with and without a reason, and the replay of an empty, null
 * or set parked code.
 */
class DrawerOpenResourceTest {

    /** The reason the shop administers for the tests that need one. */
    private static final PosListService.ListChoice ERROR = new PosListService.ListChoice("ERR", "Erreur de rendu");

    /**
     * Builds a resource outside any transaction, with a cashier logged and every
     * collaborator mocked.
     *
     * @return the resource
     */
    private DrawerOpenResource newResource() {
        DrawerOpenResource resource = new DrawerOpenResource();
        resource.drawerOpen = mock(Template.class);
        resource.state = mock(PosState.class);
        resource.state.ticket = new TicketState();
        resource.state.payment = mock(PaymentState.class);
        resource.state.auth = new AuthState();
        resource.state.auth.operatorBadgeId = "12341234";
        resource.i18n = com.intermarche.pos.ui.TestMessages.french();
        resource.posListService = mock(PosListService.class);
        resource.endorsementService = mock(EndorsementService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.technicalEventService = mock(TechnicalEventService.class);
        return resource;
    }

    /**
     * Asserts a 303 redirect to the given location.
     *
     * @param location the expected location
     * @param response the response to check
     */
    private void assertRedirect(String location, Response response) {
        assertEquals(303, response.getStatus());
        assertEquals(location, response.getLocation().toString());
    }

    /**
     * Asserts the drawer stayed shut and nothing was asked or journalled.
     *
     * @param resource the resource under test
     */
    private void assertNothingHappened(DrawerOpenResource resource) {
        verify(resource.hardwareService, never()).openDrawer();
        verify(resource.endorsementService, never()).requestAuthorization(any(), anyString(), any(), any());
        verify(resource.technicalEventService, never()).log(any(), anyString(), any());
    }

    /** LC-12-01-01: a live line, a cancelled line or a payment under way each refuse the key on both entry points. */
    @Test
    void everyLegOfATransactionRefusesTheOpening() {
        DrawerOpenResource withLine = newResource();
        withLine.state.ticket.items.add(new TicketState.TicketItem("1", null, "Lait",
                new BigDecimal("2.76"), BigDecimal.ONE, BigDecimal.ZERO));
        assertRedirect("/", withLine.page());
        assertEquals("OUVERTURE TIROIR : HORS TRANSACTION UNIQUEMENT", withLine.state.ticket.transientError);
        assertRedirect("/", withLine.choose("ERR"));
        assertNothingHappened(withLine);
        DrawerOpenResource withCancelled = newResource();
        withCancelled.state.ticket.cancelledItems.add(new TicketState.TicketItem());
        assertRedirect("/", withCancelled.page());
        assertNothingHappened(withCancelled);
        DrawerOpenResource paying = newResource();
        paying.state.payment.paymentInProgress = true;
        assertRedirect("/", paying.choose("ERR"));
        assertNothingHappened(paying);
        verify(paying.state, org.mockito.Mockito.times(1)).touch();
    }

    /** LC-12-01-02: administered reasons open the chooser and nothing else happens yet. */
    @Test
    void administeredReasonsShowTheChooser() {
        DrawerOpenResource resource = newResource();
        List<PosListService.ListChoice> reasons = List.of(ERROR);
        when(resource.posListService.justificationsOf("DRAWER_OPEN")).thenReturn(reasons);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withReasons = mock(TemplateInstance.class);
        when(resource.drawerOpen.data("state", resource.state)).thenReturn(withState);
        when(withState.data("reasons", reasons)).thenReturn(withReasons);
        Response response = resource.page();
        assertEquals(200, response.getStatus());
        assertSame(withReasons, response.getEntity());
        assertEquals("text/html", response.getMediaType().toString());
        assertNothingHappened(resource);
    }

    /** LC-12-01-01: without reasons a cashier's key parks the opening on the supervisor modal, with an empty code. */
    @Test
    void noReasonAndACashierParksTheOpening() {
        DrawerOpenResource resource = newResource();
        when(resource.posListService.justificationsOf("DRAWER_OPEN")).thenReturn(List.of());
        assertRedirect("/", resource.page());
        verify(resource.endorsementService).requestAuthorization(resource.state, "DRAWER_FORCE_OPEN",
                Map.of("code", ""), "/");
        verify(resource.hardwareService, never()).openDrawer();
    }

    /** LC-12-01-02: an unknown code goes back to the chooser with a message and opens nothing. */
    @Test
    void anUnknownReasonIsRefused() {
        DrawerOpenResource resource = newResource();
        assertRedirect("/drawer-open", resource.choose("FORGED"));
        assertEquals("MOTIF D'OUVERTURE INCONNU", resource.state.ticket.transientError);
        verify(resource.state).touch();
        assertNothingHappened(resource);
    }

    /** LC-12-01-02: a chosen reason parks with its code when a cashier asked. */
    @Test
    void aChosenReasonParksWithItsCode() {
        DrawerOpenResource resource = newResource();
        when(resource.posListService.justificationOf("DRAWER_OPEN", "ERR")).thenReturn(ERROR);
        assertRedirect("/", resource.choose("ERR"));
        verify(resource.endorsementService).requestAuthorization(resource.state, "DRAWER_FORCE_OPEN",
                Map.of("code", "ERR"), "/");
        verify(resource.hardwareService, never()).openDrawer();
    }

    /** LC-12-01-01: a supervisor at the till opens at once, under their own badge, the journal naming the reason. */
    @Test
    void aSupervisorAtTheTillOpensAtOnce() {
        DrawerOpenResource resource = newResource();
        resource.state.auth.operatorBadgeId = "11111111";
        when(resource.endorsementService.operatorIsSupervisor(resource.state)).thenReturn(true);
        when(resource.posListService.justificationOf("DRAWER_OPEN", "ERR")).thenReturn(ERROR);
        assertRedirect("/", resource.choose("ERR"));
        verify(resource.hardwareService).openDrawer();
        verify(resource.technicalEventService).log(TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                "ERR - Erreur de rendu (aval 11111111)", "11111111");
        assertEquals("TIROIR OUVERT", resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientOk);
        verify(resource.endorsementService, never()).requestAuthorization(any(), anyString(), any(), any());
    }

    /** LC-12-01-01: the replay of a manager's endorsement opens under the manager's name, with or without a reason. */
    @Test
    void theReplayOpensUnderTheManagersName() {
        DrawerOpenResource resource = newResource();
        when(resource.posListService.justificationOf("DRAWER_OPEN", "ERR")).thenReturn(ERROR);
        assertRedirect("/", resource.performEndorsed(Map.of("code", "ERR"), "mcurie"));
        verify(resource.technicalEventService).log(TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                "ERR - Erreur de rendu (aval mcurie)", "12341234");
        DrawerOpenResource silent = newResource();
        when(silent.posListService.justificationsOf("DRAWER_OPEN")).thenReturn(List.of());
        assertRedirect("/", silent.performEndorsed(Map.of("code", ""), "mcurie"));
        verify(silent.technicalEventService).log(TechnicalEvent.EventType.DRAWER_FORCED_OPEN,
                "Sans motif (aval mcurie)", "12341234");
        verify(silent.hardwareService).openDrawer();
        verify(silent.endorsementService, never()).operatorIsSupervisor(any());
    }

    /** LC-12-01-01: a parked form without a code replays the page, and the manager's authority does not outlive the replay. */
    @Test
    void aReplayWithoutCodeGoesThroughThePageAndForgetsItsAuthority() {
        DrawerOpenResource resource = newResource();
        when(resource.posListService.justificationsOf("DRAWER_OPEN")).thenReturn(List.of());
        assertRedirect("/", resource.performEndorsed(new HashMap<>(), "mcurie"));
        verify(resource.hardwareService).openDrawer();
        assertRedirect("/", resource.page());
        verify(resource.endorsementService).requestAuthorization(eq(resource.state), eq("DRAWER_FORCE_OPEN"),
                eq(Map.of("code", "")), eq("/"));
        DrawerOpenResource late = newResource();
        late.state.ticket.items.add(new TicketState.TicketItem());
        assertRedirect("/", late.performEndorsed(Map.of("code", "ERR"), "mcurie"));
        verify(late.hardwareService, never()).openDrawer();
        assertEquals("OUVERTURE TIROIR : HORS TRANSACTION UNIQUEMENT", late.state.ticket.transientError);
    }
}
