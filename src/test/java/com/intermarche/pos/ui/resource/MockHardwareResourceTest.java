package com.intermarche.pos.ui.resource;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.terminal.VirtualTerminalClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
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
     * {@code getWeightWithTare} deducts a strictly positive tare from the preset
     * gross and answers the NET weight (LC-14-01-03/06): a preset of 0,300 with
     * a tare of 0,010 nets 0,290.
     */
    @Test
    void getWeightWithTareDeductsTareFromPresetGross() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("0,300");
        Response weight = resource.getWeightWithTare("0.010");
        assertEquals(200, weight.getStatus());
        assertEquals("0,290", weight.getEntity());
    }

    /**
     * {@code getWeightWithTare} floors the net at zero when the tare exceeds the
     * gross, never answering a negative weight (the {@code Math.max} arm).
     */
    @Test
    void getWeightWithTareFloorsNetAtZero() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("0,005");
        assertEquals("0,000", resource.getWeightWithTare("0.010").getEntity());
    }

    /**
     * {@code getWeightWithTare} with a null tare deducts nothing, answering the
     * gross unchanged (the null-tare arm of the parse).
     */
    @Test
    void getWeightWithTareNullTareReturnsGross() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("1,500");
        assertEquals("1,500", resource.getWeightWithTare(null).getEntity());
    }

    /**
     * {@code getWeightWithTare} with a blank tare deducts nothing (the blank
     * arm of the parse).
     */
    @Test
    void getWeightWithTareBlankTareReturnsGross() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("1,500");
        assertEquals("1,500", resource.getWeightWithTare("   ").getEntity());
    }

    /**
     * {@code getWeightWithTare} with an unreadable tare deducts nothing (the
     * catch arm of the parse), never raising.
     */
    @Test
    void getWeightWithTareUnreadableTareReturnsGross() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.setManualWeight("1,500");
        assertEquals("1,500", resource.getWeightWithTare("abc").getEntity());
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
     * BO-03-04-14: the simulated slip station keeps its own sheets, apart from
     * the roll's buffer.
     */
    @Test
    void theSlipStationKeepsItsOwnSheets() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.printSlip("SHEET 1").getStatus());
        assertEquals(200, resource.printSlip("SHEET 2").getStatus());
        assertEquals("SHEET 1\nSHEET 2\n", resource.getSlipContent().getEntity());
        assertEquals("", resource.getPrintedContent().getEntity());
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
        Mockito.when(terminal.accept("")).thenReturn(true);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response accept = resource.tpeAccept("");
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
        Mockito.when(terminal.accept("")).thenReturn(false);
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            @SuppressWarnings("unchecked")
            InstanceHandle<VirtualTerminalClient> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(VirtualTerminalClient.class)).thenReturn(handle);
            Mockito.when(handle.get()).thenReturn(terminal);
            Response accept = resource.tpeAccept("");
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

    /**
     * Builds a Product with the four fields the catalogue serialises, leaving
     * every other attribute at its default.
     *
     * @param ean the EAN, possibly null
     * @param name the label, possibly null
     * @param plu the PLU, possibly null or blank
     * @param forbidden the forbidden-to-sale flag
     * @return the populated Product
     */
    private Product product(String ean, String name, String plu, boolean forbidden) {
        Product product = new Product();
        product.ean = ean;
        product.name = name;
        product.plu = plu;
        product.forbiddenToSale = forbidden;
        return product;
    }

    /**
     * Stubs the catalogue query so that {@code Product.find(...).page(0,
     * expectedBound).list()} yields the given products, then invokes catalog
     * with the given limit and returns the JSON body.
     *
     * @param limit the limit query parameter passed to catalog
     * @param expectedBound the page size the bounded computation must resolve to
     * @param products the products the paged query returns
     * @return the JSON body produced by catalog
     */
    @SuppressWarnings("unchecked")
    private String runCatalog(Integer limit, int expectedBound, List<Product> products) {
        MockHardwareResource resource = new MockHardwareResource();
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        PanacheQuery<Product> paged = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(0, expectedBound)).thenReturn(paged);
        Mockito.when(paged.list()).thenReturn(products);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Product.find("active = true order by ean")).thenReturn(query);
            Response response = resource.catalog(limit);
            assertEquals(200, response.getStatus());
            return (String) response.getEntity();
        }
    }

    /**
     * A null limit takes the {@code limit == null} arm of the bounded ternary
     * (defaulting to page size 200) and, over an empty result set, the loop
     * body is never entered: the body is the empty JSON array.
     */
    @Test
    void catalogWithNullLimitDefaultsToBoundAndEmptyList() {
        String body = runCatalog(null, 200, List.of());
        assertEquals("[]", body);
    }

    /**
     * A negative limit is non-null but takes the {@code limit <= 0} arm of the
     * bounded ternary, again defaulting to page size 200.
     */
    @Test
    void catalogWithNegativeLimitDefaultsToBound() {
        String body = runCatalog(-5, 200, List.of());
        assertEquals("[]", body);
    }

    /**
     * A limit above the ceiling is non-null and positive but takes the
     * {@code limit > 500} arm of the bounded ternary, defaulting to page size
     * 200.
     */
    @Test
    void catalogWithLimitAboveCeilingDefaultsToBound() {
        String body = runCatalog(600, 200, List.of());
        assertEquals("[]", body);
    }

    /**
     * A valid in-range limit takes the false arm of the bounded ternary and is
     * used verbatim as the page size; the single product exercises the
     * {@code first} true arm, a non-blank PLU (quoted) and non-null ean/name
     * passing untouched through escape.
     */
    @Test
    void catalogWithValidLimitSerialisesSingleProduct() {
        String body = runCatalog(10, 10, List.of(product("111", "MILK", "0100", false)));
        assertEquals("[{\"ean\":\"111\",\"label\":\"MILK\",\"plu\":\"0100\",\"forbidden\":false}]", body);
    }

    /**
     * Several products cover the remaining catalogue branches in one pass: the
     * second product takes the {@code !first} arm (comma separator); a null PLU
     * and a blank PLU both take the {@code null} arm of the PLU ternary; a null
     * name takes the {@code value == null} arm of escape (rendered as an empty
     * label); and the forbidden flag rides through as true.
     */
    @Test
    void catalogSerialisesSeparatorPluVariantsAndNullEscape() {
        List<Product> products = List.of(
                product("111", "A", null, true),
                product("222", null, "   ", false));
        String body = runCatalog(5, 5, products);
        assertEquals("[{\"ean\":\"111\",\"label\":\"A\",\"plu\":null,\"forbidden\":true},"
                + "{\"ean\":\"222\",\"label\":\"\",\"plu\":null,\"forbidden\":false}]", body);
    }

    /**
     * A label carrying a backslash, a double quote and a newline is escaped
     * through every replacement in escape, proving the non-null arm and the
     * full replace chain.
     */
    @Test
    void catalogEscapesSpecialCharactersInLabel() {
        String body = runCatalog(1, 1, List.of(product("999", "a\\b\"c\nd", "12", false)));
        assertEquals("[{\"ean\":\"999\",\"label\":\"a\\\\b\\\"c d\",\"plu\":\"12\",\"forbidden\":false}]", body);
    }
    /** LC-13-02-01/-07: demo reset/test/status/stop actions preserve the bridge contract. */
    @Test
    void maintenanceSimulation() {
        MockHardwareResource resource = new MockHardwareResource();
        assertTrue(resource.maintenance("scanner", "reset", "").contains("code="));
        assertTrue(resource.maintenance("handheld", "test", "").contains("code="));
        assertTrue(resource.maintenance("scanner", "status", "").contains("code="));
        assertTrue(resource.maintenance("scanner", "stop", "").contains("code="));
        assertTrue(resource.maintenance("printer", "reset", "").contains("code="));
        resource.maintenance("printer", "test", "MAINTENANCE TEST");
        assertTrue(resource.getPrintedContent().getEntity().toString().contains("MAINTENANCE TEST"));
    }

    /** LC-13-02-01: the demo also refuses unknown roles and actions. */
    @Test
    void maintenanceRejectsUnknownRequests() {
        MockHardwareResource resource = new MockHardwareResource();
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.maintenance("unknown", "test", ""));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.maintenance("printer", "unknown", ""));
    }

    /** LC-13-02-03: a simulated out-of-paper state cannot report a successful test. */
    @Test
    void maintenancePrinterTestWithoutPaper() {
        MockHardwareResource resource = new MockHardwareResource();
        resource.togglePaper();
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,
                () -> resource.maintenance("printer", "test", "MAINTENANCE TEST"));
    }

    /**
     * Builds a complete demo control form with already-validated supervisor evidence.
     * @return a simulated application request (LC-13-03-01/-06)
     */
    private jakarta.ws.rs.core.MultivaluedMap<String, String> controlForm() {
        jakarta.ws.rs.core.MultivaluedMap<String, String> form = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        form.putSingle("closed", "true");
        form.putSingle("supervisor", "true");
        form.putSingle("action", "APPLICATION");
        form.putSingle("reason", "reason");
        form.putSingle("title", "<application>");
        form.putSingle("confirm", "confirm");
        form.putSingle("cancel", "cancel");
        form.putSingle("SIMULATED", "simulation");
        form.putSingle("CANCELLED", "cancelled");
        return form;
    }

    /** LC-13-03-01/-06: the demo rejects session-open and unapproved control forms. */
    @Test
    void workstationControlMandatoryGuards() {
        MockHardwareResource resource = new MockHardwareResource();
        jakarta.ws.rs.core.MultivaluedMap<String, String> form = controlForm();
        form.putSingle("closed", "false");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> resource.armWorkstation(form));
        form.putSingle("closed", "true"); form.remove("supervisor");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class,
                () -> resource.armWorkstation(form));
    }

    /** LC-13-03-04: null, unknown and blank control fields are refused in the simulator too. */
    @Test
    void workstationControlValidatesFields() {
        MockHardwareResource resource = new MockHardwareResource();
        jakarta.ws.rs.core.MultivaluedMap<String, String> form = controlForm();
        form.remove("action");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.armWorkstation(form));
        form.putSingle("action", "OTHER");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.armWorkstation(form));
        form.putSingle("action", "REBOOT"); form.remove("reason");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.armWorkstation(form));
        form.putSingle("reason", " ");
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.armWorkstation(form));
    }

    /** LC-13-03-01/-06: a valid demo permit displays confirmation and simulates exactly once. */
    @Test
    void workstationControlSimulationIsOneShot() {
        MockHardwareResource resource = new MockHardwareResource();
        String token = resource.armWorkstation(controlForm());
        assertTrue(resource.controlPage(token).contains("&lt;application&gt;"));
        assertTrue(resource.executeWorkstation(token, true).contains("simulation"));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class,
                () -> resource.executeWorkstation(token, true));
    }

    /** LC-13-03-01/-06: wrong tokens, cancellation and revocation cannot execute anything. */
    @Test
    void workstationControlCancellationAndRevocation() {
        MockHardwareResource resource = new MockHardwareResource();
        String token = resource.armWorkstation(controlForm());
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class,
                () -> resource.controlPage("wrong"));
        assertTrue(resource.executeWorkstation(token, false).contains("cancelled"));
        String second = resource.armWorkstation(controlForm());
        resource.revokeWorkstation();
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class,
                () -> resource.controlPage(second));
    }

    /** LC-13-03-01/-06: an expired demo permit cannot be confirmed. */
    @Test
    void workstationControlExpiry() throws Exception {
        MockHardwareResource resource = new MockHardwareResource();
        String token = resource.armWorkstation(controlForm());
        java.lang.reflect.Field expiry = MockHardwareResource.class.getDeclaredField("controlExpiry");
        expiry.setAccessible(true); expiry.setLong(resource, 0);
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class,
                () -> resource.controlPage(token));
    }


    /** LC-13-02/LC-14-01: simulated terminal maintenance accepts its commands and an empty scale stays empty across reads. */
    @Test
    void terminalMaintenanceAndAnEmptyScaleAreSimulated() {
        var resource = new MockHardwareResource();
        assertTrue(resource.maintenance("terminal", "status", "").contains("state=DONE"));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.BadRequestException.class,
                () -> resource.maintenance("terminal", "unknown", ""));
        resource.setManualWeight("0");
        assertEquals("0,000", resource.getWeight().getEntity());
        assertEquals("0,000", resource.getWeight().getEntity());
    }

    /** LC-07-05-06: the cheque simulator acknowledges reading, supplies CMC7 and prints its front on the ordinary printer buffer. */
    @Test
    void chequeSimulationUsesTheVisiblePrinterAndCompletesReading() {
        var resource = new MockHardwareResource();
        resource.clearPrinter();
        assertEquals(200, resource.printChequeFront("CHEQUE FRONT").getStatus());
        assertTrue(resource.getPrintedContent().getEntity().toString().contains("CHEQUE FRONT"));
        assertEquals(200, resource.startCheque("ENDORSEMENT").getStatus());
        assertEquals("state=DONE\nraw=CMC7 0000000 0000000000 000000000000", resource.getChequeStatus().getEntity());
    }

    /**
     * LC-07-15-01/02: the simulated deposit runs from its start, counts each
     * insertion and closes by itself once the amount due is reached, giving
     * back the difference; an insertion outside a deposit is refused.
     */
    @Test
    void cashChangerDepositClosesItselfOnceTheAmountDueIsReached() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(409, resource.insertIntoCashChanger("100").getStatus());
        assertEquals(400, resource.startCashChangerDeposit("abc").getStatus());
        assertEquals(200, resource.startCashChangerDeposit(" 2372 ").getStatus());
        assertEquals(400, resource.insertIntoCashChanger("x").getStatus());
        assertEquals(200, resource.insertIntoCashChanger("2000").getStatus());
        assertEquals("state=RUNNING\ndue=2372\ninserted=2000\nchange=0\ncancelled=false\nfailure=",
                resource.getCashChangerDeposit().getEntity());
        assertEquals(200, resource.insertIntoCashChanger("500").getStatus());
        assertEquals("state=DONE\ndue=2372\ninserted=2500\nchange=128\ncancelled=false\nfailure=",
                resource.getCashChangerDeposit().getEntity());
        assertEquals(409, resource.insertIntoCashChanger("100").getStatus());
    }

    /**
     * Fixing closes the running deposit on what was inserted, without change;
     * outside a deposit it changes nothing.
     */
    @Test
    void cashChangerFixClosesOnWhatWasInserted() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.fixCashChangerDeposit().getStatus());
        assertEquals("state=IDLE\ndue=0\ninserted=0\nchange=0\ncancelled=false\nfailure=",
                resource.getCashChangerDeposit().getEntity());
        resource.startCashChangerDeposit("1000");
        resource.insertIntoCashChanger("500");
        assertEquals(200, resource.fixCashChangerDeposit().getStatus());
        assertEquals("state=DONE\ndue=1000\ninserted=500\nchange=0\ncancelled=false\nfailure=",
                resource.getCashChangerDeposit().getEntity());
    }

    /**
     * Cancelling gives back everything inserted; outside a deposit it changes
     * nothing.
     */
    @Test
    void cashChangerCancelGivesEverythingBack() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.cancelCashChangerDeposit().getStatus());
        resource.startCashChangerDeposit("1000");
        resource.insertIntoCashChanger("500");
        assertEquals(200, resource.cancelCashChangerDeposit().getStatus());
        assertEquals("state=DONE\ndue=1000\ninserted=500\nchange=500\ncancelled=true\nfailure=",
                resource.getCashChangerDeposit().getEntity());
    }

    /**
     * LC-13-01-01: the simulated levels read back as set; nothing set is OK,
     * and a null body clears them.
     */
    @Test
    void cashChangerLevelsReadBackAsSet() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("status=OK\nfull=OK\nlow=\nhigh=", resource.getCashChangerLevels().getEntity());
        assertEquals(200, resource.setCashChangerLevels("low=100\nhigh=50").getStatus());
        assertEquals("low=100\nhigh=50", resource.getCashChangerLevels().getEntity());
        resource.setCashChangerLevels(null);
        assertEquals("", resource.getCashChangerLevels().getEntity());
    }

    /**
     * BO-11-03-03: the simulated machine dispenses a withdrawal and takes a
     * loan in, highest denomination first, reports what it actually moved, and
     * its cash level follows; an unreadable amount is refused.
     */
    @Test
    void cashChangerDispensesAndTakesInWhileItsLevelFollows() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("total=88200\n10=40\n20=40\n50=40\n100=50\n200=50\n500=20\n1000=20\n2000=10\n5000=4\n",
                resource.getCashChangerInventory().getEntity());
        assertEquals(400, resource.dispenseFromCashChanger("x").getStatus());
        assertEquals(400, resource.replenishCashChanger(null).getStatus());
        assertEquals("dispensed=12340", resource.dispenseFromCashChanger(" 12345 ").getEntity());
        assertEquals("accepted=1230", resource.replenishCashChanger("1234").getEntity());
        assertEquals("total=77090\n10=41\n20=39\n50=40\n100=49\n200=50\n500=20\n1000=21\n2000=9\n5000=2\n",
                resource.getCashChangerInventory().getEntity());
    }

    /** The simulator keeps the driver configuration last handed over, a null block reading empty. */
    @Test
    void theSimulatorKeepsTheDriverConfiguration() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("", resource.configuration().getEntity());
        assertEquals(200, resource.configure("drawer.enabled=false\n").getStatus());
        assertEquals("drawer.enabled=false\n", resource.configuration().getEntity());
        resource.configure(null);
        assertEquals("", resource.configuration().getEntity());
    }

    /** The simulated second printer accumulates the pages, each under its queue. */
    @Test
    void theSimulatedSecondPrinterAccumulatesThePages() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.printOnSecondPrinter("usb0", "PAGE 1").getStatus());
        resource.printOnSecondPrinter("usb1", "PAGE 2");
        assertEquals("[usb0]\nPAGE 1\n[usb1]\nPAGE 2\n", resource.secondPrinterContent().getEntity());
    }

    /**
     * Follows the simulated bridge payment as {@code HardwareBridgeTerminalClient}
     * does — under way at the first reading, over at the next — and decodes its
     * WCL answer into the outcome the register reads.
     *
     * @param resource the simulator
     * @return the outcome read from the final status block
     */
    private com.intermarche.pos.ui.hardware.terminal.TerminalOutcome bridgeOutcome(MockHardwareResource resource) {
        assertEquals("state=RUNNING\nline1=INSERER CARTE\nline2=\n", resource.paymentStatus());
        String status = resource.paymentStatus();
        assertTrue(status.startsWith("state=DONE\n"));
        assertTrue(status.contains("approved=true"));
        String encoded = status.substring(status.indexOf("answer64=") + "answer64=".length());
        com.intermarche.pos.ui.hardware.terminal.TerminalOutcome outcome =
                new com.intermarche.pos.ui.hardware.terminal.TerminalOutcome();
        outcome.readVerifone(new String(java.util.Base64.getDecoder().decode(encoded.trim()),
                java.nio.charset.StandardCharsets.UTF_8));
        return outcome;
    }

    /**
     * BO-04-01-48: the simulated bridge terminal answers like the real one —
     * the primary server for an ordinary debit or credit, the secondary server
     * 4 it was asked to force for the degraded debit and credit — with the
     * settled amount and an authorization number; nothing ran yet reads IDLE.
     */
    @Test
    void theBridgePaymentAnswersTheServerItWasAskedFor() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals("state=IDLE\n", resource.paymentStatus());
        assertEquals(200, resource.startPayment(" 1250 ").getStatus());
        var debit = bridgeOutcome(resource);
        assertEquals("1", debit.server);
        assertEquals(new BigDecimal("12.50"), debit.amount);
        assertTrue(debit.authorizationNumber.matches("\\d{6}"));
        assertEquals(200, resource.startCredit("300").getStatus());
        assertEquals("1", bridgeOutcome(resource).server);
        assertEquals(200, resource.startDegradedPayment("400").getStatus());
        assertEquals("4", bridgeOutcome(resource).server);
        assertEquals(200, resource.startDegradedCredit("500").getStatus());
        var credit = bridgeOutcome(resource);
        assertEquals("4", credit.server);
        assertEquals(new BigDecimal("5.00"), credit.amount);
    }

    /**
     * BO-04-01-48: the combined debit answers on the server the degraded mode
     * forced after the two amounts, on the primary server without forcing; an
     * unreadable or absent amount is refused with 400 and changes nothing.
     */
    @Test
    void theCombinedBridgePaymentAnswersItsServerAndBadAmountsAreRefused() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(200, resource.startCombinedPayment("2000;800;4").getStatus());
        var forced = bridgeOutcome(resource);
        assertEquals("4", forced.server);
        assertEquals(new BigDecimal("20.00"), forced.amount);
        assertEquals(200, resource.startCombinedPayment("2000;800").getStatus());
        assertEquals("1", bridgeOutcome(resource).server);
        assertEquals(400, resource.startCombinedPayment(null).getStatus());
        assertEquals(400, resource.startPayment(null).getStatus());
        Response refused = resource.startDegradedPayment("12,50");
        assertEquals(400, refused.getStatus());
        assertEquals("Montant illisible", refused.getEntity());
        assertTrue(resource.paymentStatus().contains("approved=true"));
    }

    /**
     * Lot 21: the simulated bridge terminal answers under way once by default,
     * the realistic cardholder; switched to zero readings it decides at the
     * very first reading, the instant decision of a real terminal, which
     * replays the race between the payment follower and the payment screen.
     */
    @Test
    void theBridgeTerminalCanDecideAtTheFirstReading() {
        MockHardwareResource resource = new MockHardwareResource();
        assertEquals(1, resource.paymentRunningReadings);
        resource.paymentRunningReadings = 0;
        assertEquals(200, resource.startPayment("1250").getStatus());
        String status = resource.paymentStatus();
        assertTrue(status.startsWith("state=DONE\n"));
        assertTrue(status.contains("approved=true"));
    }
}
