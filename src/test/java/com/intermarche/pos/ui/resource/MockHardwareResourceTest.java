package com.intermarche.pos.ui.resource;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MockHardwareResource} — the embedded hardware
 * simulator (scale, customer display, drawer, printer, virtual payment
 * terminal). Each endpoint is exercised directly on a freshly instantiated
 * resource; the two Arc-resolved relays (TPE) drive the container through a
 * static mock so no CDI context is booted. Every leg of every guard and both
 * arms of every ternary are covered.
 */
class MockHardwareResourceTest {

    /**
     * A manual weight round-trips as a French-formatted three-decimal string,
     * covering the parse-success arm and the {@code manualWeight != null} arm
     * of getWeight.
     */
    @Test
    void setManualWeightThenGetWeightReturnsFormattedManualValue() {
        MockHardwareResource resource = new MockHardwareResource();
        Response set = resource.setManualWeight("1,5");
        assertEquals(200, set.getStatus());
        Response weight = resource.getWeight();
        assertEquals(200, weight.getStatus());
        assertEquals("1,500", weight.getEntity());
    }

    /**
     * An unparseable weight triggers the catch arm and answers 400 with the
     * French error entity.
     */
    @Test
    void setManualWeightRejectsInvalidFormat() {
        MockHardwareResource resource = new MockHardwareResource();
        Response set = resource.setManualWeight("not-a-number");
        assertEquals(400, set.getStatus());
        assertEquals("Format invalide", set.getEntity());
    }

    /**
     * A null body reaches the catch arm through the NPE of
     * {@code weightStr.replace} and answers 400.
     */
    @Test
    void setManualWeightRejectsNullBody() {
        MockHardwareResource resource = new MockHardwareResource();
        Response set = resource.setManualWeight(null);
        assertEquals(400, set.getStatus());
        assertEquals("Format invalide", set.getEntity());
    }

    /**
     * With no pending manual weight, getWeight takes the random arm and still
     * answers 200 with a three-decimal French string in the simulated range
     * [0,500 ; 5,000[.
     */
    @Test
    void getWeightWithoutManualValueReturnsRandomFormattedWeight() {
        MockHardwareResource resource = new MockHardwareResource();
        Response weight = resource.getWeight();
        assertEquals(200, weight.getStatus());
        String formatted = (String) weight.getEntity();
        double value = Double.parseDouble(formatted.replace(',', '.'));
        assertTrue(value >= 0.5d && value < 5.0d);
    }

    /**
     * A single manual weight is consumed exactly once: the second read falls
     * back to the random arm (getAndSet returned null on the second call).
     */
    @Test
    void manualWeightIsConsumedOnFirstReadOnly() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("2,000");
        assertEquals("2,000", resource.getWeight().getEntity());
        String second = (String) resource.getWeight().getEntity();
        double value = Double.parseDouble(second.replace(',', '.'));
        assertTrue(value >= 0.5d && value < 5.0d);
    }

    /**
     * With the display alive, setDisplay stores the text and answers 200; a
     * subsequent getDisplay returns exactly what was set.
     */
    @Test
    void setDisplayWhileAliveStoresTextAndAnswersOk() {
        MockHardwareResource resource = new MockHardwareResource();
        Response set = resource.setDisplay("BONJOUR");
        assertEquals(200, set.getStatus());
        assertEquals("BONJOUR", resource.getDisplay().getEntity());
    }

    /**
     * Toggling the display off makes setDisplay take the {@code !displayAlive}
     * arm and answer 503 with the OFFLINE error entity.
     */
    @Test
    void setDisplayWhileOfflineAnswersServiceUnavailable() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.toggleDisplay();
        Response set = resource.setDisplay("BONJOUR");
        assertEquals(503, set.getStatus());
        assertEquals("DISPLAY_ERROR: OFFLINE", set.getEntity());
    }

    /**
     * The display starts BIENVENUE by default, covering the initial-state read
     * of getDisplay.
     */
    @Test
    void getDisplayReturnsInitialWelcomeText() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("BIENVENUE", resource.getDisplay().getEntity());
    }

    /**
     * toggleDisplay reports OFFLINE on the first flip (false arm of the
     * ternary) and ALIVE on the second (true arm), covering both arms.
     */
    @Test
    void toggleDisplayReportsBothStates() {
        MockHardwareResource resource = new MockHardwareResource();
        Response off = resource.toggleDisplay();
        assertEquals(200, off.getStatus());
        assertEquals("Display status: OFFLINE", off.getEntity());
        Response on = resource.toggleDisplay();
        assertEquals("Display status: ALIVE", on.getEntity());
    }

    /**
     * openDrawer flips the drawer to OPEN, covering the true arm of the
     * drawer-status ternary while the sensor is alive.
     */
    @Test
    void openDrawerThenStatusReportsOpen() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.openDrawer().getStatus());
        Response status = resource.getDrawerStatus();
        assertEquals(200, status.getStatus());
        assertEquals("OPEN", status.getEntity());
    }

    /**
     * closeDrawer flips the drawer to CLOSED, covering the false arm of the
     * drawer-status ternary while the sensor is alive.
     */
    @Test
    void closeDrawerThenStatusReportsClosed() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.openDrawer();
        assertEquals(200, resource.closeDrawer().getStatus());
        Response status = resource.getDrawerStatus();
        assertEquals(200, status.getStatus());
        assertEquals("CLOSED", status.getEntity());
    }

    /**
     * Killing the drawer sensor makes getDrawerStatus take the
     * {@code !drawerSensorAlive} arm and answer 503 with the OFFLINE entity.
     */
    @Test
    void getDrawerStatusWhileSensorDeadAnswersServiceUnavailable() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.toggleDrawerSensor();
        Response status = resource.getDrawerStatus();
        assertEquals(503, status.getStatus());
        assertEquals("DRAWER_SENSOR_ERROR: OFFLINE", status.getEntity());
    }

    /**
     * toggleDrawerSensor reports OFFLINE on the first flip (false arm) and
     * ALIVE on the second (true arm), covering both arms.
     */
    @Test
    void toggleDrawerSensorReportsBothStates() {
        MockHardwareResource resource = new MockHardwareResource();
        Response off = resource.toggleDrawerSensor();
        assertEquals(200, off.getStatus());
        assertEquals("Drawer sensor: OFFLINE", off.getEntity());
        Response on = resource.toggleDrawerSensor();
        assertEquals("Drawer sensor: ALIVE", on.getEntity());
    }

    /**
     * With paper present, printTicket appends the content plus a newline and
     * answers 200; the buffer read shows the accumulated ticket.
     */
    @Test
    void printTicketWhilePaperPresentAppendsToBuffer() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.printTicket("LINE1").getStatus());
        assertEquals(200, resource.printTicket("LINE2").getStatus());
        assertEquals("LINE1\nLINE2\n", resource.getPrintedContent().getEntity());
    }

    /**
     * Removing the paper makes printTicket take the {@code !paperPresent} arm
     * and answer 503 with the NO_PAPER entity.
     */
    @Test
    void printTicketWhileNoPaperAnswersServiceUnavailable() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.togglePaper();
        Response print = resource.printTicket("LINE1");
        assertEquals(503, print.getStatus());
        assertEquals("PRINTER_ERROR: NO_PAPER", print.getEntity());
    }

    /**
     * getPrintedContent returns the empty initial buffer, covering the neutral
     * read state.
     */
    @Test
    void getPrintedContentReturnsEmptyBufferInitially() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("", resource.getPrintedContent().getEntity());
    }

    /**
     * cutPaper appends the paper-cut marker to the buffer and answers 200.
     */
    @Test
    void cutPaperAppendsCutMarker() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.cutPaper().getStatus());
        String buffer = (String) resource.getPrintedContent().getEntity();
        assertTrue(buffer.contains("[ COUPE PAPIER ]"));
    }

    /**
     * getPrinterStatus reports OK while paper is present (true arm of the
     * ternary).
     */
    @Test
    void getPrinterStatusWhilePaperPresentReportsOk() {
        MockHardwareResource resource = new MockHardwareResource();
        Response status = resource.getPrinterStatus();
        assertEquals(200, status.getStatus());
        assertEquals("OK", status.getEntity());
    }

    /**
     * getPrinterStatus reports NO_PAPER once the paper is removed (false arm of
     * the ternary).
     */
    @Test
    void getPrinterStatusWhileNoPaperReportsNoPaper() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.togglePaper();
        assertEquals("NO_PAPER", resource.getPrinterStatus().getEntity());
    }

    /**
     * togglePaper reports ABSENT on the first flip (false arm) and PRESENT on
     * the second (true arm), covering both arms.
     */
    @Test
    void togglePaperReportsBothStates() {
        MockHardwareResource resource = new MockHardwareResource();
        Response absent = resource.togglePaper();
        assertEquals(200, absent.getStatus());
        assertEquals("Paper status: ABSENT", absent.getEntity());
        Response present = resource.togglePaper();
        assertEquals("Paper status: PRESENT", present.getEntity());
    }

    /**
     * clearPrinter empties the buffer and answers 200.
     */
    @Test
    void clearPrinterEmptiesBuffer() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.printTicket("LINE1");
        assertEquals(200, resource.clearPrinter().getStatus());
        assertEquals("", resource.getPrintedContent().getEntity());
    }

    /**
     * With a pending card amount in the register state, tpeStatus takes the
     * {@code pendingCardAmount != null} arm: it reports pending true and the
     * amount formatted with French decimals and two scale digits.
     */
    @Test
    void tpeStatusReportsPendingAmountWhenCardRequestPending() {
        MockHardwareResource resource = new MockHardwareResource();
        PosState state = new PosState();
        state.payment.pendingCardAmount = new BigDecimal("12.5");
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<PosState> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(PosState.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(state);
            Map<String, Object> result = resource.tpeStatus();
            assertEquals(Boolean.TRUE, result.get("pending"));
            assertEquals("12,50", result.get("amount"));
        }
    }

    /**
     * With no pending card amount, tpeStatus takes the null arm: pending false
     * and an empty amount string.
     */
    @Test
    void tpeStatusReportsNoPendingWhenNoCardRequest() {
        MockHardwareResource resource = new MockHardwareResource();
        PosState state = new PosState();
        state.payment.pendingCardAmount = null;
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<PosState> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(PosState.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(state);
            Map<String, Object> result = resource.tpeStatus();
            assertEquals(Boolean.FALSE, result.get("pending"));
            assertEquals("", result.get("amount"));
        }
    }

    /**
     * When the virtual terminal accepts, tpeAccept takes the true arm and
     * answers 200.
     */
    @Test
    void tpeAcceptAnswersOkWhenTerminalAccepts() {
        MockHardwareResource resource = new MockHardwareResource();
        VirtualTerminalClient terminal = Mockito.mock(VirtualTerminalClient.class);
        Mockito.when(terminal.accept()).thenReturn(true);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response accept = resource.tpeAccept();
            assertEquals(200, accept.getStatus());
        }
    }

    /**
     * When the virtual terminal has nothing pending, tpeAccept takes the
     * {@code !accept()} arm and answers 409 with the French entity.
     */
    @Test
    void tpeAcceptAnswersConflictWhenNothingPending() {
        MockHardwareResource resource = new MockHardwareResource();
        VirtualTerminalClient terminal = Mockito.mock(VirtualTerminalClient.class);
        Mockito.when(terminal.accept()).thenReturn(false);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response accept = resource.tpeAccept();
            assertEquals(409, accept.getStatus());
            assertEquals("Aucune demande en attente", accept.getEntity());
        }
    }

    /**
     * When the virtual terminal refuses, tpeRefuse takes the true arm and
     * answers 200.
     */
    @Test
    void tpeRefuseAnswersOkWhenTerminalRefuses() {
        MockHardwareResource resource = new MockHardwareResource();
        VirtualTerminalClient terminal = Mockito.mock(VirtualTerminalClient.class);
        Mockito.when(terminal.refuse()).thenReturn(true);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response refuse = resource.tpeRefuse();
            assertEquals(200, refuse.getStatus());
        }
    }

    /**
     * When the virtual terminal has nothing pending, tpeRefuse takes the
     * {@code !refuse()} arm and answers 409 with the French entity.
     */
    @Test
    void tpeRefuseAnswersConflictWhenNothingPending() {
        MockHardwareResource resource = new MockHardwareResource();
        VirtualTerminalClient terminal = Mockito.mock(VirtualTerminalClient.class);
        Mockito.when(terminal.refuse()).thenReturn(false);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response refuse = resource.tpeRefuse();
            assertEquals(409, refuse.getStatus());
            assertEquals("Aucune demande en attente", refuse.getEntity());
        }
    }
}
