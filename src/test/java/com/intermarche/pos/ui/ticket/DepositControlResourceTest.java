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
     * LS-01-05-05 / LS-01-05-06: a line of an article sold by weight whose
     * quantity is NOT a weight expects the weight the sale states, never its
     * quantity as kilograms — a price-embedded sticker its deduced weight (or
     * nothing when the referential could not deduce it), a counter-ticket line
     * the weight the counter reported, a GS1 count line its reference weight
     * times its count.
     */
    @Test
    @SuppressWarnings("unchecked")
    void controlExpectsTheStatedWeightNeverAQuantityAsKilograms() {
        TicketState.TicketItem sticker = item("P", "230", "1", "8.99");
        sticker.priceEmbedded = true;
        sticker.embeddedWeight = new BigDecimal("0.897");
        sticker.embeddedUnitPrice = new BigDecimal("10.02");
        TicketState.TicketItem unconverted = item("Q", "230", "1", "8.99");
        unconverted.priceEmbedded = true;
        TicketState.TicketItem counter = item("C", "4021", "1", "3.10");
        counter.embeddedWeight = new BigDecimal("0.512");
        TicketState.TicketItem counted = item("G", null, "2", "1.50");
        counted.referenceWeightKg = new BigDecimal("0.250");
        TicketState.TicketItem weightOnly = item("E", null, "1", "2.00");
        weightOnly.embeddedWeight = new BigDecimal("0.300");
        Map<String, Object> answer = DepositControlResource.control(
                List.of(sticker, unconverted, counter, counted, weightOnly), true, true);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) answer.get("lines");
        assertEquals(Boolean.TRUE, lines.get(0).get("weighed"));
        assertEquals(new BigDecimal("0.897"), lines.get(0).get("expectedKg"));
        assertEquals(Boolean.TRUE, lines.get(1).get("weighed"));
        assertNull(lines.get(1).get("expectedKg"));
        assertEquals(new BigDecimal("0.512"), lines.get(2).get("expectedKg"));
        assertEquals(Boolean.FALSE, lines.get(3).get("weighed"));
        assertEquals(new BigDecimal("0.500"), lines.get(3).get("expectedKg"));
        assertEquals(Boolean.TRUE, lines.get(4).get("weighed"));
        assertEquals(new BigDecimal("0.300"), lines.get(4).get("expectedKg"));
        assertEquals(new BigDecimal("2.209"), answer.get("expectedTotalKg"));
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
