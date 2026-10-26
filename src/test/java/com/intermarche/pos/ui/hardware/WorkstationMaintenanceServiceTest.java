package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Supervisor, session, reason and independent-confirmation guards (LC-13-03-01/-06). */
class WorkstationMaintenanceServiceTest {

    /**
     * Creates a closed-session fixture with a real message resolver.
     * @return the service with controlled boundaries (LC-13-03-01/-06)
     */
    private WorkstationMaintenanceService service() {
        WorkstationMaintenanceService service = new WorkstationMaintenanceService();
        service.sessions = mock(CashSessionService.class);
        service.endorsement = mock(EndorsementService.class);
        service.settings = mock(PosSettingsService.class);
        service.events = mock(TechnicalEventService.class);
        service.hardware = mock(HardwareClient.class);
        service.i18n = TestMessages.french();
        service.hardwareUrl = "http://127.0.0.1:8081/";
        return service;
    }

    /**
     * Creates a service whose supervisor explicitly grants the operation.
     * @return the approved fixture (LC-13-03-01/-06)
     */
    private WorkstationMaintenanceService approved() {
        WorkstationMaintenanceService service = service();
        when(service.endorsement.authorize(eq("manager"), eq("secret"), anyString())).thenReturn(true);
        when(service.hardware.armWorkstation(anyString())).thenReturn("permit-token");
        return service;
    }

    /** LC-13-03-04: an absent setting uses translated default reasons. */
    @Test
    void defaultReasons() {
        assertEquals(List.of("Application figée", "Maintenance prévue"), service().reasons());
    }

    /** LC-13-03-04: a blank configured list takes the second default leg. */
    @Test
    void blankReasons() {
        WorkstationMaintenanceService service = service();
        when(service.settings.value("maintenance.restart-reasons")).thenReturn("  ");
        assertEquals(2, service.reasons().size());
    }

    /** LC-13-03-04: administered reasons retain their labels, remove blanks and deduplicate. */
    @Test
    void configuredReasons() {
        WorkstationMaintenanceService service = service();
        when(service.settings.value("maintenance.restart-reasons")).thenReturn("One;; Two ;One");
        assertEquals(List.of("One", "Two"), service.reasons());
    }

    /** LC-13-03-01/-06: any open cash session blocks arming before credentials or bridge calls. */
    @Test
    void openSessionBlocksControl() {
        WorkstationMaintenanceService service = service();
        when(service.sessions.getOpenSession()).thenReturn(new CashSession());
        assertFalse(service.prepare(new PosState(), "APPLICATION", "Application figée", "manager", "secret"));
        verifyNoInteractions(service.endorsement, service.hardware);
    }

    /** LC-13-03-01/-06: a null operation is refused explicitly. */
    @Test
    void nullAction() {
        WorkstationMaintenanceService service = service();
        assertFalse(service.prepare(new PosState(), null, "Application figée", "manager", "secret"));
        verifyNoInteractions(service.endorsement, service.hardware);
    }

    /** LC-13-03-01/-06: an unknown operation is never forwarded. */
    @Test
    void unknownAction() {
        WorkstationMaintenanceService service = service();
        assertFalse(service.prepare(new PosState(), "OTHER", "Application figée", "manager", "secret"));
        verifyNoInteractions(service.endorsement, service.hardware);
    }

    /** LC-13-03-04: a null reason is refused before consulting an immutable list. */
    @Test
    void nullReason() {
        WorkstationMaintenanceService service = service();
        assertFalse(service.prepare(new PosState(), "APPLICATION", null, "manager", "secret"));
        verifyNoInteractions(service.endorsement, service.hardware);
    }

    /** LC-13-03-04: a reason outside the administered list is refused. */
    @Test
    void unknownReason() {
        WorkstationMaintenanceService service = service();
        assertFalse(service.prepare(new PosState(), "APPLICATION", "Other", "manager", "secret"));
        verifyNoInteractions(service.endorsement, service.hardware);
    }

    /** LC-13-03-01/-06: refused supervisor credentials never arm the daemon. */
    @Test
    void supervisorRefusal() {
        WorkstationMaintenanceService service = service();
        assertFalse(service.prepare(new PosState(), "APPLICATION", "Application figée", "manager", "secret"));
        verifyNoInteractions(service.hardware, service.events);
    }

    /** LC-13-03-01/-06: a valid request creates a separate link and journals only action/reason. */
    @Test
    void preparesSeparateConfirmationAndAudit() {
        WorkstationMaintenanceService service = approved();
        PosState state = new PosState();
        assertTrue(service.prepare(state, "APPLICATION", "Application figée", "manager", "secret"));
        assertEquals("http://127.0.0.1:8081/api/hardware/control?token=permit-token", state.workstationControlUrl);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(service.hardware).armWorkstation(body.capture());
        assertFalse(body.getValue().contains("secret"));
        assertTrue(body.getValue().contains("closed=true"));
        assertTrue(body.getValue().contains("supervisor=true"));
        verify(service.events).log(TechnicalEvent.EventType.WORKSTATION_REQUEST, "APPLICATION: Application figée");
    }

    /** LC-13-03-05/-06: explicit reboot and shutdown commands use their own settings. */
    @Test
    void configuredSystemActions() {
        for (String action : List.of("REBOOT", "SHUTDOWN")) {
            WorkstationMaintenanceService service = approved();
            String key = "maintenance.command." + action.toLowerCase(java.util.Locale.ROOT);
            when(service.settings.value(key)).thenReturn("fake;argument");
            assertTrue(service.prepare(new PosState(), action, "Application figée", "manager", "secret"));
            verify(service.settings).value(key);
        }
    }

    /** LC-13-03-01/-06: a null bridge permit is not treated as authorisation. */
    @Test
    void missingPermit() {
        WorkstationMaintenanceService service = approved();
        when(service.hardware.armWorkstation(anyString())).thenReturn(null);
        assertFalse(service.prepare(new PosState(), "APPLICATION", "Application figée", "manager", "secret"));
        verifyNoInteractions(service.events);
    }

    /** LC-13-03-01/-06: a blank permit takes the second invalid-permit leg. */
    @Test
    void blankPermit() {
        WorkstationMaintenanceService service = approved();
        when(service.hardware.armWorkstation(anyString())).thenReturn(" ");
        assertFalse(service.prepare(new PosState(), "APPLICATION", "Application figée", "manager", "secret"));
    }

    /** LC-13-03-01/-06: bridge errors leave no usable confirmation URL. */
    @Test
    void bridgeFailure() {
        WorkstationMaintenanceService service = approved();
        when(service.hardware.armWorkstation(anyString())).thenThrow(new IllegalStateException());
        PosState state = new PosState(); state.workstationControlUrl = "stale";
        assertFalse(service.prepare(state, "APPLICATION", "Application figée", "manager", "secret"));
        assertEquals("", state.workstationControlUrl);
    }
}
