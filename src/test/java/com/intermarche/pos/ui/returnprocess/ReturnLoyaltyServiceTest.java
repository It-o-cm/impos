package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundFidelityLine;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketFidelityLine;
import com.intermarche.pos.ui.fidelity.ImfidClient;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnLoyaltyService} (LC-05-07-02), targeting 100%
 * branch coverage.
 *
 * <p>The loyalty programme is a mock and the earlier refunds are read through a
 * mocked static finder. Branch enumeration: no card, no earn frozen and an empty
 * earn; the whole ticket handed back; a kept basket priced and projected, not
 * priced (other status, no couple), no programme, a programme failing; earlier
 * returns that took part back; rules matched by code and by label; amounts
 * absent on the sale, on the projection and on an earlier refund; a rule earned
 * on two lines.
 */
class ReturnLoyaltyServiceTest {

    /**
     * A service over a configured programme.
     *
     * @return the service
     */
    private ReturnLoyaltyService service() {
        ReturnLoyaltyService service = new ReturnLoyaltyService();
        service.imfidClient = mock(ImfidClient.class);
        when(service.imfidClient.isConfigured()).thenReturn(true);
        return service;
    }

    /**
     * A ticket with a card and the earn its sale froze.
     *
     * @param lines the earn lines
     * @return the ticket
     */
    private Ticket ticket(TicketFidelityLine... lines) {
        Ticket ticket = new Ticket();
        ticket.id = 9L;
        ticket.fidelityCard = "2990000000019";
        ticket.fidelityLines = new ArrayList<>(List.of(lines));
        return ticket;
    }

    /**
     * An earn line.
     *
     * @param rule the rule code, or null
     * @param label the label
     * @param amount the amount, or null
     * @param type the advantage type label, or null
     * @return the line
     */
    private TicketFidelityLine earned(String rule, String label, String amount, String type) {
        TicketFidelityLine line = new TicketFidelityLine(rule, label, amount == null ? null : new BigDecimal(amount));
        line.advantageTypeLabel = type;
        return line;
    }

    /**
     * A kept-quantity map.
     *
     * @param quantity the quantity kept by line 1, or null
     * @return the map
     */
    private Map<Long, BigDecimal> kept(String quantity) {
        Map<Long, BigDecimal> kept = new HashMap<>();
        kept.put(1L, quantity == null ? null : new BigDecimal(quantity));
        return kept;
    }

    /**
     * A recalculation of the kept basket.
     *
     * @param status the status
     * @param request the priced request, or null
     * @return the recalculation
     */
    private ReturnValuationService.Recalculation recalculation(ReturnValuationService.RecalculationStatus status,
                                                              String request) {
        return new ReturnValuationService.Recalculation(status, BigDecimal.ZERO, List.of(), request, "{}");
    }

    /**
     * An earn projection entry.
     *
     * @param rule the rule code, or null
     * @param label the label
     * @param amount the amount, or null
     * @return the entry
     */
    private ImfidClient.EarnEntry entry(String rule, String label, String amount) {
        ImfidClient.EarnEntry entry = new ImfidClient.EarnEntry();
        entry.ruleCode = rule;
        entry.label = label;
        entry.amount = amount == null ? null : new BigDecimal(amount);
        return entry;
    }

    /**
     * Without a card, without earn frozen or with an empty earn, nothing is
     * taken back and nothing is asked.
     */
    @Test
    void nothingEarnedNothingTaken() {
        ReturnLoyaltyService service = service();
        ReturnValuationService.Recalculation none = ReturnValuationService.Recalculation
                .none(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE);
        Ticket noCard = ticket(earned("R1", "CAGNOTTE", "1.00", null));
        noCard.fidelityCard = null;
        assertTrue(service.withdrawal(noCard, kept("0"), none).isEmpty());
        Ticket noEarn = ticket();
        noEarn.fidelityLines = null;
        assertTrue(service.withdrawal(noEarn, kept("0"), none).isEmpty());
        assertTrue(service.withdrawal(ticket(), kept("0"), none).isEmpty());
        verify(service.imfidClient, never()).isConfigured();
    }

    /**
     * The whole ticket handed back takes back what was earned and not taken by
     * an earlier return, rule by rule, without asking the programme; amounts
     * absent count as nothing.
     */
    @Test
    void aWholeReturnTakesBackWhatIsLeft() {
        ReturnLoyaltyService service = service();
        Ticket ticket = ticket(earned("R1", "CAGNOTTE 5%", "2.50", "Cagnotte"),
                earned(null, "COUPON CAFE", "1.00", "Coupon"), earned("R3", "VIDE", null, null));
        Refund earlier = new Refund();
        earlier.fidelityLines = new ArrayList<>(List.of(new RefundFidelityLine("R1", "CAGNOTTE 5%", "Cagnotte",
                new BigDecimal("0.50")), new RefundFidelityLine("R9", "AUTRE", null, null)));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("originalTicketId", 9L)).thenReturn(List.of(earlier));
            Map<Long, BigDecimal> nothing = kept("0");
            nothing.put(2L, null);
            List<RefundFidelityLine> lines = service.withdrawal(ticket, nothing,
                    ReturnValuationService.Recalculation.none(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE));
            assertEquals(2, lines.size());
            assertEquals("R1", lines.get(0).ruleCode);
            assertEquals("Cagnotte", lines.get(0).advantageTypeLabel);
            assertEquals(new BigDecimal("2.00"), lines.get(0).amount);
            assertNull(lines.get(1).ruleCode);
            assertEquals("COUPON CAFE", lines.get(1).label);
            assertEquals(new BigDecimal("1.00"), lines.get(1).amount);
        }
        verify(service.imfidClient, never()).isConfigured();
    }

    /**
     * A partial return asks the programme what the kept basket earns and takes
     * back the difference, a rule earned on two lines being counted once.
     *
     * @throws Exception never, the mock's signature
     */
    @Test
    void aPartialReturnTakesBackTheDifference() throws Exception {
        ReturnLoyaltyService service = service();
        Ticket ticket = ticket(earned("R1", "CAGNOTTE 5%", "2.50", "Cagnotte"),
                earned("R1", "CAGNOTTE 5%", "1.00", "Cagnotte"), earned("R2", "BON 5 EUR", "5.00", "Bon d'achat"));
        ImfidClient.EarnProjection projection = new ImfidClient.EarnProjection();
        projection.entries = new ArrayList<>(List.of(entry("R1", "CAGNOTTE 5%", "2.00"),
                entry("R2", "BON 5 EUR", "5.00"), entry(null, "SANS", null)));
        when(service.imfidClient.earn("{\"items\":[]}", "{}")).thenReturn(projection);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("originalTicketId", 9L)).thenReturn(List.of());
            List<RefundFidelityLine> lines = service.withdrawal(ticket, kept("1"),
                    recalculation(ReturnValuationService.RecalculationStatus.RECOMPUTED, "{\"items\":[]}"));
            assertEquals(2, lines.size());
            assertEquals(new BigDecimal("0.50"), lines.get(0).amount);
            assertEquals(new BigDecimal("1.00"), lines.get(1).amount);
        }
    }

    /**
     * A kept basket not priced, priced without a couple, without a programme or
     * with a programme failing takes nothing back.
     *
     * @throws Exception never, the mock's signature
     */
    @Test
    void anUnpricedOrUnprojectedKeptBasketTakesNothing() throws Exception {
        ReturnLoyaltyService service = service();
        Ticket ticket = ticket(earned("R1", "CAGNOTTE", "2.50", null));
        assertTrue(service.withdrawal(ticket, kept("1"),
                recalculation(ReturnValuationService.RecalculationStatus.UNAVAILABLE, "{}")).isEmpty());
        assertTrue(service.withdrawal(ticket, kept("1"),
                recalculation(ReturnValuationService.RecalculationStatus.RECOMPUTED, null)).isEmpty());
        when(service.imfidClient.isConfigured()).thenReturn(false);
        assertTrue(service.withdrawal(ticket, kept("1"),
                recalculation(ReturnValuationService.RecalculationStatus.RECOMPUTED, "{}")).isEmpty());
        when(service.imfidClient.isConfigured()).thenReturn(true);
        when(service.imfidClient.earn(any(), any())).thenThrow(new IllegalStateException("down"));
        assertTrue(service.withdrawal(ticket, kept("1"),
                recalculation(ReturnValuationService.RecalculationStatus.RECOMPUTED, "{}")).isEmpty());
    }

    /**
     * The JPA constructor leaves a line empty.
     */
    @Test
    void anEmptyLine() {
        RefundFidelityLine line = new RefundFidelityLine();
        assertNull(line.ruleCode);
        assertNull(line.amount);
    }
}
