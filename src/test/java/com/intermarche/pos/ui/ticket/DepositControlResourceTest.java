package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.domain.store.TpvType;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DepositControlResource} (BO-02-03-24, BO-02-03-25):
 * the lane kind read from the checkout point (absent, manned, self-service),
 * and the pure rule over the lines — a weighing expects its measured weight, a
 * unit line its reference weight times its quantity or nothing measurable, a
 * bulky line and a negative line are not controlled, and the switch off
 * controls nothing.
 */
class DepositControlResourceTest {

    /**
     * Builds a basket line.
     *
     * @param uid the line uid
     * @param plu the PLU, or null for a unit line
     * @param quantity the quantity (a weight for a weighing)
     * @param unitPrice the unit price
     * @return the line
     */
    private TicketState.TicketItem item(String uid, String plu, String quantity, String unitPrice) {
        TicketState.TicketItem item = new TicketState.TicketItem("3000000000017", plu, "L" + uid,
                new BigDecimal(unitPrice), new BigDecimal(quantity), new BigDecimal("0.20"));
        item.uid = uid;
        return item;
    }

    /**
     * Builds the resource over a basket and the given switch.
     *
     * @param enabled the administered deposit control
     * @return the resource
     */
    private DepositControlResource newResource(boolean enabled) {
        DepositControlResource resource = new DepositControlResource();
        resource.state = new PosState();
        resource.state.ticket.items.add(item("1", null, "2", "1.00"));
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.depositControlEnabled()).thenReturn(enabled);
        resource.ticketNumberService = mock(TicketNumberService.class);
        when(resource.ticketNumberService.getTerminalId()).thenReturn("C04");
        return resource;
    }

    /**
     * The lane kind travels in the answer: no checkout point and a manned till
     * read as not self-service, a self-service type as self-service.
     */
    @Test
    void expectedReadsTheLaneKind() {
        DepositControlResource resource = newResource(true);
        CheckoutPoint manned = new CheckoutPoint();
        CheckoutPoint lane = new CheckoutPoint();
        lane.tpvType = TpvType.SELF_CHECKOUT_NCR;
        try (MockedStatic<CheckoutPoint> points = mockStatic(CheckoutPoint.class)) {
            points.when(() -> CheckoutPoint.findByTerminal("C04")).thenReturn(null);
            assertEquals(Boolean.FALSE, resource.expected().get("selfService"));
            points.when(() -> CheckoutPoint.findByTerminal("C04")).thenReturn(manned);
            assertEquals(Boolean.FALSE, resource.expected().get("selfService"));
            points.when(() -> CheckoutPoint.findByTerminal("C04")).thenReturn(lane);
            Map<String, Object> answer = resource.expected();
            assertEquals(Boolean.TRUE, answer.get("selfService"));
            assertEquals(Boolean.TRUE, answer.get("enabled"));
            assertEquals(1, ((List<?>) answer.get("lines")).size());
        }
    }

    /**
     * The rule over every kind of line: a weighing, a unit line with and
     * without a reference weight, a line whose PLU is empty, a bulky line and a
     * return line.
     */
    @Test
    @SuppressWarnings("unchecked")
    void controlExpectsTheWeightOfEachControlledLine() {
        TicketState.TicketItem weighed = item("W", "4021", "0.750", "2.00");
        TicketState.TicketItem unit = item("U", null, "3", "1.00");
        unit.referenceWeightKg = new BigDecimal("0.3333");
        TicketState.TicketItem unknown = item("N", "", "1", "1.00");
        TicketState.TicketItem bulky = item("B", null, "1", "49.00");
        bulky.bulky = true;
        bulky.referenceWeightKg = new BigDecimal("8");
        TicketState.TicketItem returned = item("R", null, "-1", "1.00");
        returned.referenceWeightKg = new BigDecimal("1");
        Map<String, Object> answer = DepositControlResource.control(
                List.of(weighed, unit, unknown, bulky, returned), true, true);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) answer.get("lines");
        assertEquals(Boolean.TRUE, lines.get(0).get("weighed"));
        assertEquals(new BigDecimal("0.750"), lines.get(0).get("expectedKg"));
        assertEquals(Boolean.FALSE, lines.get(1).get("weighed"));
        assertEquals(new BigDecimal("1.000"), lines.get(1).get("expectedKg"));
        assertEquals(Boolean.FALSE, lines.get(2).get("weighed"));
        assertEquals(Boolean.TRUE, lines.get(2).get("controlled"));
        assertNull(lines.get(2).get("expectedKg"));
        assertEquals(Boolean.FALSE, lines.get(3).get("controlled"));
        assertEquals(Boolean.TRUE, lines.get(3).get("bulky"));
        assertNull(lines.get(3).get("expectedKg"));
        assertEquals(Boolean.FALSE, lines.get(4).get("controlled"));
        assertEquals("U", lines.get(1).get("uid"));
        assertEquals("3000000000017", lines.get(1).get("ean"));
        assertEquals("LU", lines.get(1).get("label"));
        assertEquals(new BigDecimal("3"), lines.get(1).get("quantity"));
        assertEquals(new BigDecimal("1.750"), answer.get("expectedTotalKg"));
    }

    /**
     * The switch off controls no line and expects nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void controlOffControlsNothing() {
        TicketState.TicketItem unit = item("U", null, "1", "1.00");
        unit.referenceWeightKg = new BigDecimal("0.5");
        Map<String, Object> answer = DepositControlResource.control(List.of(unit), false, false);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) answer.get("lines");
        assertFalse((Boolean) lines.get(0).get("controlled"));
        assertEquals(BigDecimal.ZERO, answer.get("expectedTotalKg"));
        assertFalse((Boolean) answer.get("enabled"));
        assertTrue(lines.get(0).containsKey("expectedKg"));
    }
}
