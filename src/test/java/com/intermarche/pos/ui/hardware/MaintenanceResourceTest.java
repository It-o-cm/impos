package com.intermarche.pos.ui.hardware;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Maintenance screen routing, outcomes and diagnostic isolation (LC-13-02-01/-14, LC-13-03-01/-06). */
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
        resource.sessions = mock(CashSessionService.class);
        resource.maintenance = mock(Template.class);
        TemplateInstance view = mock(TemplateInstance.class, RETURNS_SELF);
        when(resource.maintenance.data(eq("state"), any())).thenReturn(view);
        return resource;
    }

    /**
     * Reads the device row of one peripheral.
     * @param resource the resource under test
     * @param id the peripheral role
     * @return the row
     */
    private MaintenanceResource.DeviceView row(MaintenanceResource resource, String id) {
        return resource.devices().stream().filter(device -> device.id().equals(id)).findFirst().orElseThrow();
    }

    /** LC-13-02-01: entering maintenance suppresses sale scans without running a test. */
    @Test
    void enteringMaintenance() {
        MaintenanceResource resource = resource();
        TemplateInstance view = resource.show();
        assertTrue(resource.state.maintenanceScreenOpen);
        verify(resource.hardware).maintenance("terminal", "status", "1");
        verifyNoMoreInteractions(resource.hardware);
        verify(view).data("sessionOpen", false);
    }

    /** LC-13-03-01: an open session is told to the screen, which blocks the workstation commands. */
    @Test
    void openSessionReachesTheScreen() {
        MaintenanceResource resource = resource();
        when(resource.sessions.getOpenSession()).thenReturn(mock(CashSession.class));
        verify(resource.show()).data("sessionOpen", true);
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

    /** LC-13-02-05: the fixed-scanner test starts isolated capture and says so. */
    @Test
    void scannerTest() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanCode = "old";
        assertEquals("/maintenance", resource.perform("scanner", "test").getLocation().toString());
        assertTrue(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanCode);
        assertEquals("scanner", resource.state.maintenanceScanDevice);
        assertEquals(resource.i18n.get("maintenance.done.scanner.test"), resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientOk);
    }

    /** LC-13-02-07: the handheld leg also starts isolated capture. */
    @Test
    void handheldTest() {
        MaintenanceResource resource = resource();
        resource.perform("handheld", "test");
        assertTrue(resource.state.maintenanceScanMode);
        assertEquals("handheld", resource.state.maintenanceScanDevice);
    }

    /** LC-13-02-05: stopping a scan test leaves capture mode and confirms it. */
    @Test
    void stopScanTest() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        resource.state.maintenanceScanCode = "123";
        resource.state.maintenanceScanDevice = "scanner";
        resource.perform("scanner", "stop");
        assertFalse(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanDevice);
        assertEquals("", resource.state.maintenanceScanCode);
        verify(resource.hardware).maintenance("scanner", "stop", "");
        assertEquals(resource.i18n.get("maintenance.done.scanner.stop"), resource.state.ticket.transientError);
    }

    /** LC-13-02-02/-03: a printer test sends the sample, does not start scan capture and confirms the print. */
    @Test
    void printerTest() {
        MaintenanceResource resource = resource();
        resource.perform("printer", "test");
        assertFalse(resource.state.maintenanceScanMode);
        verify(resource.hardware).maintenance("printer", "test", resource.i18n.get("maintenance.printerSample"));
        assertEquals(resource.i18n.get("maintenance.done.printer.test"), resource.state.ticket.transientError);
    }

    /** LC-13-02-04/-06: reset does not start a scan-test window. */
    @Test
    void scannerReset() {
        MaintenanceResource resource = resource();
        resource.perform("scanner", "reset");
        assertFalse(resource.state.maintenanceScanMode);
        assertEquals(resource.i18n.get("maintenance.done.scanner.reset"), resource.state.ticket.transientError);
    }

    /** LC-13-02-01: an unknown peripheral is refused without reaching the bridge. */
    @Test
    void unknownDeviceRefused() {
        MaintenanceResource resource = resource();
        assertEquals("/maintenance", resource.perform("toaster", "test").getLocation().toString());
        verifyNoInteractions(resource.hardware);
        assertEquals(resource.i18n.get("maintenance.invalidControl"), resource.state.ticket.transientError);
        assertFalse(resource.state.ticket.transientOk);
    }

    /** LC-13-02-01: an operation a peripheral does not accept is refused without reaching the bridge. */
    @Test
    void unknownActionRefused() {
        MaintenanceResource resource = resource();
        resource.perform("terminal", "stop");
        verifyNoInteractions(resource.hardware);
        assertEquals(resource.i18n.get("maintenance.invalidControl"), resource.state.ticket.transientError);
    }

    /** LC-13-02-01: a failed operation names the peripheral that did not answer. */
    @Test
    void actionFailure() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());
        resource.perform("printer", "test");
        assertEquals(resource.i18n.format("maintenance.deviceFailed", resource.i18n.get("maintenance.device.printer")),
                resource.state.ticket.transientError);
        assertFalse(resource.state.ticket.transientOk);
    }

    /** LC-13-02-05: a scan test the bridge refused does not leave the screen in capture mode. */
    @Test
    void failedScanTestLeavesCaptureMode() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());
        resource.perform("handheld", "test");
        assertFalse(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanDevice);
    }

    /** LC-13-02-01: a failed reset of a scanner keeps whatever capture was running. */
    @Test
    void failedScannerResetKeepsCapture() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanMode = true;
        when(resource.hardware.maintenance(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());
        resource.perform("scanner", "reset");
        assertTrue(resource.state.maintenanceScanMode);
    }

    /** LC-13-02-08: an approved terminal test is confirmed, with the server number as its body. */
    @Test
    void terminalTestApproved() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "test", "1"))
                .thenReturn("state=DONE\nline1=\nline2=\nresult=00\napproved=true\nfailure=");
        resource.perform("terminal", "test");
        assertEquals(resource.i18n.get("maintenance.done.terminal.test"), resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientOk);
    }

    /** LC-13-02-08: a terminal reset without answer code is confirmed. */
    @Test
    void terminalResetWithoutResult() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "reset", "1")).thenReturn("state=DONE");
        resource.perform("terminal", "reset");
        assertEquals(resource.i18n.get("maintenance.done.terminal.reset"), resource.state.ticket.transientError);
    }

    /** LC-13-02-08: a terminal still busy is reported as a warning, not as a success. */
    @Test
    void terminalRunning() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "test", "1")).thenReturn("state=RUNNING");
        resource.perform("terminal", "test");
        assertEquals(resource.i18n.get("maintenance.terminalRunning"), resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientWarn);
    }

    /** LC-13-02-08: a refusal flag is reported with the terminal's code and reason. */
    @Test
    void terminalRefusedByFlag() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "test", "1"))
                .thenReturn("state=DONE\nresult=00\napproved=false\nfailure=NO LINE");
        resource.perform("terminal", "test");
        assertEquals(resource.i18n.format("maintenance.terminalRefused", "00", "NO LINE"),
                resource.state.ticket.transientError);
        assertFalse(resource.state.ticket.transientOk);
    }

    /** LC-13-02-08: a non-zero answer code is reported as a refusal even without the flag. */
    @Test
    void terminalRefusedByCode() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "reset", "1")).thenReturn("state=DONE\nresult=05");
        resource.perform("terminal", "reset");
        assertEquals(resource.i18n.format("maintenance.terminalRefused", "05", "").trim(),
                resource.state.ticket.transientError);
    }

    /** LC-13-02-05/-07: exit clears selling isolation, the test result and the last outcome. */
    @Test
    void exitRestoresSaleRouting() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScreenOpen = true;
        resource.state.maintenanceScanMode = true;
        resource.state.maintenanceScanCode = "123";
        resource.state.maintenanceScanDevice = "handheld";
        resource.state.workstationControlUrl = "http://bridge/control?token=t";
        resource.state.ticket.setNotice("done");
        assertEquals("/", resource.exit().getLocation().toString());
        assertFalse(resource.state.maintenanceScreenOpen);
        assertFalse(resource.state.maintenanceScanMode);
        assertEquals("", resource.state.maintenanceScanCode);
        assertEquals("", resource.state.maintenanceScanDevice);
        assertEquals("", resource.state.workstationControlUrl);
        assertNull(resource.state.ticket.transientError);
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
        assertNull(resource.state.ticket.transientError);
    }

    /** LC-13-03-01/-06: a granted request is confirmed on screen. */
    @Test
    void grantedControlIsConfirmed() {
        MaintenanceResource resource = resource();
        when(resource.workstation.prepare(resource.state, "REBOOT", "reason", "manager", "secret")).thenReturn(true);
        resource.prepareControl("REBOOT", "reason", "manager", "secret");
        assertEquals(resource.i18n.get("maintenance.controlArmed"), resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientOk);
    }

    /** LC-13-02-09/-14: a probe that finds every peripheral answering says so. */
    @Test
    void probeWithoutFailure() {
        MaintenanceResource resource = resource();
        when(resource.hardwareService.probeDevices()).thenReturn(List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.ABSENT),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.AVAILABLE)));
        assertEquals("/maintenance", resource.probe().getLocation().toString());
        assertEquals(resource.i18n.get("maintenance.probeOk"), resource.state.ticket.transientError);
        assertTrue(resource.state.ticket.transientOk);
    }

    /** LC-13-02-09/-14: a probe names the peripherals still failing. */
    @Test
    void probeNamesFailures() {
        MaintenanceResource resource = resource();
        when(resource.hardwareService.probeDevices()).thenReturn(List.of(
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.FAILED),
                new HardwareService.DeviceStatus("SCANNER", HardwareService.Availability.AVAILABLE),
                new HardwareService.DeviceStatus("IMPRIMANTE TICKETS", HardwareService.Availability.FAILED)));
        resource.probe();
        assertEquals(resource.i18n.format("maintenance.probeFailed", "BALANCE, IMPRIMANTE TICKETS"),
                resource.state.ticket.transientError);
        assertFalse(resource.state.ticket.transientOk);
    }

    /** LC-13-02-09/-14: a probe that cannot run is reported as a failure. */
    @Test
    void probeFailure() {
        MaintenanceResource resource = resource();
        when(resource.hardwareService.probeDevices()).thenThrow(new IllegalStateException());
        resource.probe();
        assertEquals(resource.i18n.get("maintenance.failed"), resource.state.ticket.transientError);
    }

    /** LC-13-02-09/-14: the rows carry the last probed state, UNKNOWN when never probed or not probeable. */
    @Test
    void deviceRowsCarryAvailability() {
        MaintenanceResource resource = resource();
        resource.state.peripheralAvailability.put("hardware.printer", "FAILED");
        List<MaintenanceResource.DeviceView> rows = resource.devices();
        assertEquals(List.of("printer", "scanner", "handheld", "terminal", "scale"),
                rows.stream().map(MaintenanceResource.DeviceView::id).toList());
        assertEquals("FAILED", row(resource, "printer").status());
        assertEquals(resource.i18n.get("maintenance.status.FAILED"), row(resource, "printer").statusLabel());
        assertEquals("UNKNOWN", row(resource, "scanner").status());
        assertEquals("UNKNOWN", row(resource, "handheld").status());
        assertEquals(resource.i18n.get("maintenance.device.scale"), row(resource, "scale").label());
    }

    /** LC-13-02-05/-07: only the peripheral whose scan test runs is flagged, and only while it runs. */
    @Test
    void scanTestingFlagsTheTestedPeripheralOnly() {
        MaintenanceResource resource = resource();
        resource.state.maintenanceScanDevice = "scanner";
        assertFalse(row(resource, "scanner").scanTesting());
        resource.state.maintenanceScanMode = true;
        assertTrue(row(resource, "scanner").scanTesting());
        assertFalse(row(resource, "handheld").scanTesting());
        assertFalse(row(resource, "printer").scanTesting());
    }

    /** LC-13-02-08: only state, display and result fields reach the screen; raw receipts remain hidden. */
    @Test
    void terminalReportShowsOnlyDiagnosticFields() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "status", "1"))
                .thenReturn("state=DONE\nline1=ONE\nline2=TWO\nresult=01\nanswer64=SECRET\noperation=CONTROL:test:1");
        MaintenanceResource.TerminalView view = resource.terminalView();
        assertEquals(new MaintenanceResource.TerminalView(resource.i18n.get("maintenance.terminal.DONE"),
                "01", "ONE", "TWO", false), view);
    }

    /** LC-13-02-08: a report without state leaves the state label empty. */
    @Test
    void terminalReportWithoutState() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "status", "1")).thenReturn("result=00");
        assertEquals(new MaintenanceResource.TerminalView("", "00", "", "", false), resource.terminalView());
    }

    /** LC-13-02-08: an unavailable terminal leaves the other maintenance actions reachable. */
    @Test
    void terminalReportFailureIsVisible() {
        MaintenanceResource resource = resource();
        when(resource.hardware.maintenance("terminal", "status", "1")).thenThrow(new IllegalStateException());
        assertTrue(resource.terminalView().unavailable());
        verify(resource.show()).data(eq("terminal"), any(MaintenanceResource.TerminalView.class));
    }

    /** LC-13-02-08: report parsing ignores lines without a name and keeps the first value of a field. */
    @Test
    void fieldParsing() {
        assertEquals("", MaintenanceResource.field(null, "state"));
        assertEquals("", MaintenanceResource.field("=orphan\nnoise", "state"));
        assertEquals("A", MaintenanceResource.field("state=A\nstate=B", "state"));
        assertEquals("x=y", MaintenanceResource.field("code=x=y", "code"));
    }

}
