package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Maintenance screen routing and diagnostic isolation (LC-13-02-01/-07). */
class MaintenanceResourceTest {

    /**
     * Creates a resource with the real message bundles and an isolated bridge.
     * @return the resource under test (LC-13-02-01/-07)
     */
    private MaintenanceResource resource() {
        MaintenanceResource resource = new MaintenanceResource();
        resource.state = new PosState();
        resource.i18n = TestMessages.french();
        resource.hardware = mock(HardwareClient.class);
        resource.workstation = mock(WorkstationMaintenanceService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.maintenance = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class, RETURNS_SELF);
        when(resource.maintenance.data(eq("state"), any())).thenReturn(view);
        return resource;
    }

    /** LC-13-02-01: entering maintenance suppresses sale scans without running a test. */
    @Test
    void enteringMaintenance() {
        MaintenanceResource resource = resource();
        resource.show();
        assertTrue(resource.state.maintenanceScreenOpen);
        verify(resource.hardware).maintenance("terminal", "status", "1");
        verifyNoMoreInteractions(resource.hardware);
    }

    /** LC-13-02-05/-07: a reported diagnostic label reaches the dedicated view. */
    @Test
    void displaysBridgeScan() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        when(resource.hardware.maintenance("scanner", "status", ""))
                .thenReturn("active=true\nsource=SCANNER\ncode=991");
        TemplateInstance view = resource.show();
        verify(view).data("code", "991");
    }

    /** LC-13-02-05/-07: an empty bridge label retains a simulator diagnostic label. */
    @Test
    void retainsLocalScanWithEmptyBridgeLabel() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        resource.state.maintenanceScanCode = "992";
        when(resource.hardware.maintenance("scanner", "status", "")).thenReturn("code=");
        verify(resource.show()).data("code", "992");
    }

    /** LC-13-02-05/-07: a null status also retains a local diagnostic label. */
    @Test
    void nullBridgeStatus() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        resource.state.maintenanceScanCode = "993";
        verify(resource.show()).data("code", "993");
    }

    /** LC-13-02-01: a failed status call is visibly reported and does not break the page. */
    @Test
    void statusFailure() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        when(resource.hardware.maintenance("scanner", "status", "")).thenThrow(new IllegalStateException());
        verify(resource.show()).data("message", resource.i18n.get("maintenance.failed"));
    }

    /** LC-13-02-05: the fixed-scanner test starts isolated capture. */
    @Test
    void scannerTest() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanCode = "old";
        assertEquals("/maintenance", resource.perform("scanner", "test").getLocation().toString());
        assertTrue(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanCode);
    }

    /** LC-13-02-07: the handheld leg also starts isolated capture. */
    @Test
    void handheldTest() {
        MaintenanceResource resource = resource();
        resource.perform("handheld", "test");
        assertTrue(resource.state.maintenanceScanMode);
    }

    /** LC-13-02-02/-03: a printer test does not start scan capture. */
    @Test
    void printerTest() {
        MaintenanceResource resource = resource();
        resource.perform("printer", "test");
        assertFalse(resource.state.maintenanceScanMode);
        verify(resource.hardware).maintenance("printer", "test", resource.i18n.get("maintenance.printerSample"));
    }

    /** LC-13-02-04/-06: reset does not start a scan-test window. */
    @Test
    void scannerReset() {
        MaintenanceResource resource = resource();
        resource.perform("scanner", "reset");
        assertFalse(resource.state.maintenanceScanMode);
    }

    /** LC-13-02-01: a failed explicit operation is displayed as a failure. */
    @Test
    void actionFailure() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());
        resource.perform("printer", "test");
        assertEquals(resource.i18n.get("maintenance.failed"), resource.state.ticket.transientError);
    }

    /** LC-13-02-05/-07: exit clears both selling isolation and the test result. */
    @Test
    void exitRestoresSaleRouting() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScreenOpen = true;
        resource.state.maintenanceScanMode = true;
        resource.state.maintenanceScanCode = "123";
        assertEquals("/", resource.exit().getLocation().toString());
        assertFalse(resource.state.maintenanceScreenOpen);
        assertFalse(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanCode);
    }

    /** LC-13-02-05/-07: an unreachable bridge does not trap the operator in maintenance. */
    @Test
    void exitWithBridgeFailure() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("scanner", "stop", "")).thenThrow(new IllegalStateException());
        resource.state.maintenanceScreenOpen = true;
        resource.exit();
        assertFalse(resource.state.maintenanceScreenOpen);
    }
    /** LC-13-03-01/-06: the control form delegates credentials only to the guarded service. */
    @Test
    void controlFormUsesGuardedService() {
        MaintenanceResource resource = resource();
        assertEquals("/maintenance", resource.prepareControl("APPLICATION", "reason", "manager", "secret")
                .getLocation().toString());
        verify(resource.workstation).prepare(resource.state, "APPLICATION", "reason", "manager", "secret");
    }

    /** LC-13-02-09/-14: maintenance offers an explicit recovery probe. */
    @Test
    void explicitlyReprobesHardware() {
        MaintenanceResource resource = resource();
        assertEquals("/maintenance", resource.probe().getLocation().toString());
        verify(resource.hardwareService).probeDevices();
    }

    /** LC-13-02-08: only state, display and result fields reach the screen; raw receipts remain hidden. */
    @Test
    void terminalReportShowsOnlyDiagnosticFields() {
        var resource = resource();
        when(resource.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\nline1=ONE\nline2=TWO\nresult=01\nanswer64=SECRET\noperation=CONTROL:test:1");
        var view = resource.show();
        verify(view).data("terminalStatus", "state=DONE\nline1=ONE\nline2=TWO\nresult=01");
    }

    /** LC-13-02-08: an unavailable terminal leaves the other maintenance actions reachable. */
    @Test
    void terminalReportFailureIsVisible() {
        var resource = resource();
        when(resource.hardware.maintenance("terminal", "status", "1")).thenThrow(new IllegalStateException());
        var view = resource.show();
        verify(view).data("terminalStatus", resource.i18n.get("maintenance.failed"));
    }

}
