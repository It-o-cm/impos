package com.intermarche.pos.ui.hardware;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HardwareService}.
 * <p>
 * {@code HardwareService} is a degraded-mode facade over {@link HardwareClient}:
 * every peripheral call wraps the client in a {@code try/catch} that swallows
 * failures so a dead device never blocks a sale. Each method is therefore
 * exercised on both its success arm and its exception arm; {@code isDrawerOpen}
 * additionally covers the OPEN and non-OPEN readings of its status compare. The
 * package-private {@code hardwareClient} field is populated with a Mockito mock
 * from within the same package — no CDI, no application boot.
 */
class HardwareServiceTest {

    /**
     * Builds a service wired to the supplied mock client, bypassing CDI by
     * assigning the package-private field directly.
     *
     * @param client the mock hardware client to inject
     * @return a service under test bound to that client
     */
    private HardwareService serviceWith(HardwareClient client) {
        HardwareService service = new HardwareService();
        service.hardwareClient = client;
        service.state = new com.intermarche.pos.ui.PosState();
        return service;
    }

    /**
     * Builds the client exception a REST call raises for an error answer of
     * the hardware bridge, over a response whose body reads back as the
     * bridge wrote it.
     *
     * @param status the HTTP status of the error answer
     * @param body the machine-readable code carried by the answer
     * @return the exception to make the mock client throw
     */
    private WebApplicationException bridgeError(int status, String body) {
        Response response = errorResponse(status);
        when(response.readEntity(String.class)).thenReturn(body);
        return new WebApplicationException(response);
    }

    /**
     * Builds the error response a REST client exception carries. Both the
     * code and the status info are stubbed: the exception itself reads the
     * status info to build its message.
     *
     * @param status the HTTP status of the error answer
     * @return the response to hang on the exception
     */
    private Response errorResponse(int status) {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getStatusInfo()).thenReturn(Response.Status.fromStatusCode(status));
        return response;
    }

    /**
     * Builds the client exception as the REST client really hands it over on
     * a real bridge: the status and the headers survive, the entity does not.
     *
     * @param status the HTTP status of the error answer
     * @param header the value of the hardware status header
     * @return the exception to make the mock client throw
     */
    private WebApplicationException bridgeErrorWithHeader(int status, String header) {
        Response response = errorResponse(status);
        when(response.getHeaderString("X-Hardware-Status")).thenReturn(header);
        when(response.readEntity(String.class)).thenThrow(new IllegalStateException("entity dropped"));
        return new WebApplicationException(response);
    }

    /**
     * The real shape of an unbound role on a live bridge: the entity is gone,
     * the header names the cause, and the role reads ABSENT.
     */
    @Test
    void probeDevicesUnboundRoleIsAbsentFromTheHeaderAlone() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException unbound = bridgeErrorWithHeader(503, "NO_DEVICE_BOUND");
        when(client.getWeight()).thenThrow(unbound);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).absent());
    }

    /**
     * A header naming a device failure keeps the role FAILED, proving the
     * header is read as a code and not taken as an absence marker.
     */
    @Test
    void probeDevicesDeviceErrorHeaderIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException broken = bridgeErrorWithHeader(503, "SCALE_ERROR: no reply");
        when(client.getWeight()).thenThrow(broken);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * A blank header falls back to the body, which is what a bridge written
     * without the header — or a curl-driven stub — leaves behind.
     */
    @Test
    void probeDevicesBlankHeaderFallsBackToTheBody() {
        HardwareClient client = mock(HardwareClient.class);
        Response response = errorResponse(503);
        when(response.getHeaderString("X-Hardware-Status")).thenReturn("  ");
        when(response.readEntity(String.class)).thenReturn("NO_DEVICE_BOUND");
        WebApplicationException blankHeader = new WebApplicationException(response);
        when(client.getWeight()).thenThrow(blankHeader);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).absent());
    }

    /**
     * {@code probeDevices()} with every peripheral answering reports the four
     * devices available — the printer inheriting the answering bridge.
     */
    @Test
    void probeDevicesAllUp() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("0,000");
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertEquals(4, devices.size());
        assertTrue(devices.stream().allMatch(HardwareService.DeviceStatus::available));
    }

    /**
     * {@code probeDevices()} with no bridge at all (every probe throwing a
     * transport failure) reports the four devices FAILED, printer included —
     * the three legs of {@code bridgeAnswered} false together.
     */
    @Test
    void probeDevicesAllFailedWithoutBridge() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("connection refused"));
        when(client.getDrawerStatus()).thenThrow(new RuntimeException("connection refused"));
        doThrow(new RuntimeException("connection refused")).when(client).setDisplay("BONJOUR");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.stream().allMatch(HardwareService.DeviceStatus::failed));
    }

    /**
     * {@code probeDevices()} with one dead peripheral on a live bridge
     * reports exactly that device FAILED and keeps the printer presumed
     * available (the bridge answered through the surviving probes).
     */
    @Test
    void probeDevicesSingleDeviceFailedKeepsPrinterPresumedUp() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("scale dead"));
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
        assertTrue(devices.get(1).available());
        assertTrue(devices.get(2).available());
        assertTrue(devices.get(3).available());
        assertEquals("BALANCE", devices.get(0).name());
        assertEquals("IMPRIMANTE TICKETS", devices.get(3).name());
    }

    /**
     * A 503 {@code NO_DEVICE_BOUND} answer reports the role ABSENT, not
     * failed: a till with no scale must still open its lane.
     */
    @Test
    void probeDevicesUnboundRoleIsAbsent() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException unbound = bridgeError(503, "NO_DEVICE_BOUND");
        when(client.getWeight()).thenThrow(unbound);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).absent());
        assertFalse(devices.get(0).failed());
        assertTrue(devices.get(3).available());
    }

    /**
     * A 501 answer reports the role ABSENT too — the endpoint exists but the
     * bridge drives no such device (the customer display of this till).
     */
    @Test
    void probeDevicesNotImplementedRoleIsAbsent() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("0,000");
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        WebApplicationException noDisplay = bridgeError(501, "DISPLAY_NOT_IMPLEMENTED");
        doThrow(noDisplay).when(client).setDisplay("BONJOUR");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(2).absent());
    }

    /**
     * A 503 carrying a device error code — a bound device that broke — is
     * FAILED: the status is right, the code is not the absence sentinel.
     */
    @Test
    void probeDevicesBoundDeviceErrorIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException broken = bridgeError(503, "SCALE_ERROR: no reply");
        when(client.getWeight()).thenThrow(broken);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * Any other HTTP error — here a 404 of a drifted contract — is FAILED:
     * the status is neither 501 nor 503, so the body is never consulted.
     */
    @Test
    void probeDevicesUnknownStatusIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException drifted = bridgeError(404, "NO_DEVICE_BOUND");
        when(client.getWeight()).thenThrow(drifted);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * A 503 whose body cannot be read is FAILED: without the sentinel there
     * is nothing proving the role is merely unbound.
     */
    @Test
    void probeDevicesUnreadableBodyIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        Response response = errorResponse(503);
        when(response.readEntity(String.class)).thenThrow(new IllegalStateException("closed"));
        WebApplicationException unreadable = new WebApplicationException(response);
        when(client.getWeight()).thenThrow(unreadable);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * A 503 with no body at all is FAILED, the null entity being read as an
     * empty code rather than raising inside the probe.
     */
    @Test
    void probeDevicesEmptyBodyIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException bodyless = bridgeError(503, null);
        when(client.getWeight()).thenThrow(bodyless);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * A client exception carrying no response at all is FAILED — the
     * defensive null arm of the absence reading.
     */
    @Test
    void probeDevicesResponselessErrorIsFailed() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException failure = mock(WebApplicationException.class);
        when(failure.getResponse()).thenReturn(null);
        when(client.getWeight()).thenThrow(failure);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).failed());
    }

    /**
     * The printer is presumed available when the scale alone answered — the
     * first leg of {@code bridgeAnswered} carrying the disjunction.
     */
    @Test
    void printerPresumedUpWhenOnlyScaleAnswered() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("0,000");
        when(client.getDrawerStatus()).thenThrow(new RuntimeException("drawer dead"));
        doThrow(new RuntimeException("display dead")).when(client).setDisplay("BONJOUR");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(3).available());
    }

    /**
     * The printer is presumed available when the drawer alone answered — the
     * second leg of {@code bridgeAnswered}, the first being false.
     */
    @Test
    void printerPresumedUpWhenOnlyDrawerAnswered() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("scale dead"));
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        doThrow(new RuntimeException("display dead")).when(client).setDisplay("BONJOUR");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(3).available());
    }

    /**
     * The printer is presumed available when the display alone answered — the
     * third leg of {@code bridgeAnswered}, the first two being false.
     */
    @Test
    void printerPresumedUpWhenOnlyDisplayAnswered() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("scale dead"));
        when(client.getDrawerStatus()).thenThrow(new RuntimeException("drawer dead"));
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(3).available());
    }

    /**
     * An ABSENT role still proves the bridge answered: a till bound to
     * nothing but declaring it keeps its printer presumed available.
     */
    @Test
    void printerPresumedUpWhenEveryRoleIsAbsent() {
        HardwareClient client = mock(HardwareClient.class);
        WebApplicationException noScale = bridgeError(503, "NO_DEVICE_BOUND");
        WebApplicationException noDrawer = bridgeError(503, "NO_DEVICE_BOUND");
        WebApplicationException noDisplay = bridgeError(501, "DISPLAY_NOT_IMPLEMENTED");
        when(client.getWeight()).thenThrow(noScale);
        when(client.getDrawerStatus()).thenThrow(noDrawer);
        doThrow(noDisplay).when(client).setDisplay("BONJOUR");
        java.util.List<HardwareService.DeviceStatus> devices = serviceWith(client).probeDevices();
        assertTrue(devices.get(0).absent());
        assertTrue(devices.get(1).absent());
        assertTrue(devices.get(2).absent());
        assertTrue(devices.get(3).available());
    }

    /**
     * The three readings of a status are exclusive, each answering true for
     * its own state and false for the two others.
     */
    @Test
    void deviceStatusReadingsAreExclusive() {
        HardwareService.DeviceStatus up =
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.AVAILABLE);
        HardwareService.DeviceStatus absent =
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.ABSENT);
        HardwareService.DeviceStatus failed =
                new HardwareService.DeviceStatus("BALANCE", HardwareService.Availability.FAILED);
        assertTrue(up.available());
        assertFalse(up.absent());
        assertFalse(up.failed());
        assertFalse(absent.available());
        assertTrue(absent.absent());
        assertFalse(absent.failed());
        assertFalse(failed.available());
        assertFalse(failed.absent());
        assertTrue(failed.failed());
    }

    /**
     * {@code requestWeighing()} parses a well-formed French-decimal payload,
     * translating the comma to a dot before parsing.
     */
    @Test
    void requestWeighingParsesFrenchDecimal() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("1,234");
        assertEquals(1.234, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} parses a dot-decimal payload unchanged, the
     * comma replacement being a no-op on such input.
     */
    @Test
    void requestWeighingParsesDotDecimal() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("2.5");
        assertEquals(2.5, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} answers 0.0 when the client throws, taking the
     * degraded-mode catch arm so a dead scale never blocks the sale.
     */
    @Test
    void requestWeighingReturnsZeroOnClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("scale down"));
        assertEquals(0.0, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} answers 0.0 when the payload is unparseable, the
     * {@link NumberFormatException} being caught like any other failure.
     */
    @Test
    void requestWeighingReturnsZeroOnUnparseablePayload() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("not-a-number");
        assertEquals(0.0, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code displayMessage()} forwards the text to the customer line display on
     * the success arm.
     */
    @Test
    void displayMessageForwardsText() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).displayMessage("BONJOUR");
        verify(client).setDisplay("BONJOUR");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code displayMessage()} swallows a client failure, returning normally so a
     * dead display never blocks the sale.
     */
    @Test
    void displayMessageSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("display down")).when(client).setDisplay("KO");
        serviceWith(client).displayMessage("KO");
        verify(client).setDisplay("KO");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code displayMessage()} records the text on the register state, from where the
     * customer PAGE reads it — this till's customer display being a screen and not a
     * device on the bus.
     */
    @Test
    void displayMessageRecordsTheTextForTheCustomerPage() {
        HardwareClient client = mock(HardwareClient.class);
        HardwareService service = serviceWith(client);
        service.displayMessage("TOTAL   12,34 E");
        assertEquals("TOTAL   12,34 E", service.state.customerMessage);
    }

    /**
     * {@code displayMessage()} records an empty text for a null message (null arm of
     * the recording ternary), so the page clears instead of showing "null".
     */
    @Test
    void displayMessageRecordsAnEmptyTextForNull() {
        HardwareClient client = mock(HardwareClient.class);
        HardwareService service = serviceWith(client);
        service.displayMessage(null);
        assertEquals("", service.state.customerMessage);
    }

    /**
     * A bridge declaring the display unbound is NOT an incident: the message is still
     * recorded for the page and nothing is logged as an error ({@code declaredAbsent}
     * true, so the negated guard is false).
     */
    @Test
    void displayMessageIgnoresADisplayTheBridgeDeclaresUnbound() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(bridgeError(501, "")).when(client).setDisplay("MERCI A BIENTOT");
        HardwareService service = serviceWith(client);
        service.displayMessage("MERCI A BIENTOT");
        assertEquals("MERCI A BIENTOT", service.state.customerMessage);
        verify(client).setDisplay("MERCI A BIENTOT");
        verifyNoMoreInteractions(client);
    }

    /**
     * A bridge answer naming a device failure IS an incident and takes the logging
     * arm ({@code declaredAbsent} false, negated guard true), while still leaving the
     * sale alone.
     */
    @Test
    void displayMessageSwallowsABridgeFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(bridgeError(503, "DISPLAY_ERROR")).when(client).setDisplay("KO");
        HardwareService service = serviceWith(client);
        service.displayMessage("KO");
        assertEquals("KO", service.state.customerMessage);
        verify(client).setDisplay("KO");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code openDrawer()} fires the drawer-opening pulse on the success arm.
     */
    @Test
    void openDrawerFiresPulse() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).openDrawer();
        verify(client).openDrawer();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code openDrawer()} swallows a client failure, returning normally.
     */
    @Test
    void openDrawerSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("drawer stuck")).when(client).openDrawer();
        serviceWith(client).openDrawer();
        verify(client).openDrawer();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers true when the sensor reports the OPEN
     * sentinel exactly.
     */
    @Test
    void isDrawerOpenReturnsTrueOnOpen() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("OPEN");
        assertTrue(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers true for a case-insensitive OPEN reading,
     * proving the compare ignores case.
     */
    @Test
    void isDrawerOpenReturnsTrueOnLowercaseOpen() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("open");
        assertTrue(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers false for any non-OPEN reading, covering the
     * closed-drawer arm of the status compare.
     */
    @Test
    void isDrawerOpenReturnsFalseOnClosed() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        assertFalse(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers false when the sensor throws, so a dead
     * drawer sensor never traps the register behind the drawer guard.
     */
    @Test
    void isDrawerOpenReturnsFalseOnClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenThrow(new RuntimeException("sensor dead"));
        assertFalse(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code printReceipt()} forwards the formatted content to the printer on the
     * success arm.
     */
    @Test
    void printReceiptForwardsContent() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).printReceipt("LINE 1\nLINE 2");
        verify(client).printTicket("LINE 1\nLINE 2");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code printReceipt()} swallows a client failure, returning normally so a
     * dead printer never blocks the sale.
     */
    @Test
    void printReceiptSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("printer down")).when(client).printTicket("KO");
        serviceWith(client).printReceipt("KO");
        verify(client).printTicket("KO");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code cutPaper()} issues the paper-cut command on the success arm.
     */
    @Test
    void cutPaperCutsOnce() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).cutPaper();
        verify(client).cutPaper();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code cutPaper()} swallows a client failure, returning normally.
     */
    @Test
    void cutPaperSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("cutter jammed")).when(client).cutPaper();
        serviceWith(client).cutPaper();
        verify(client).cutPaper();
        verifyNoMoreInteractions(client);
    }
}
