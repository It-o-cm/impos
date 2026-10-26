package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.valuation.ValuationClient;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReturnValuationService}.
 * <p>
 * The only collaborator is the engine transport, which is a Mockito mock: the
 * basket the service builds is captured from it, so the private request builder
 * is asserted through the one public entry point. Tickets and lines are plain
 * data objects — no Panache static is reached — and each test asserts absolute
 * expected values. Every leg of every compound guard has its own case, the
 * nullities included, and both failure families of the call (a checked
 * interruption and everything else) are driven separately.
 */
class ReturnValuationServiceTest {

    /**
     * Builds the service with a mocked engine transport, enabled by default.
     *
     * @return a ready-to-drive service
     */
    private ReturnValuationService newService() {
        ReturnValuationService service = new ReturnValuationService();
        service.valuationClient = mock(ValuationClient.class);
        when(service.valuationClient.isEnabled()).thenReturn(true);
        service.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return service;
    }

    /**
     * Builds a persisted-looking ticket line.
     *
     * @param id the line database id, or null
     * @param ean the article code
     * @param qty the sold quantity
     * @param unitPrice the catalog unit price including tax
     * @param totalPrice the line total including tax, or null
     * @return the line
     */
    private TicketLine line(Long id, String ean, String qty, String unitPrice, String totalPrice) {
        TicketLine line = new TicketLine();
        line.id = id;
        line.lineUid = "uid-" + id;
        line.ean = ean;
        line.quantity = new BigDecimal(qty);
        line.unitPrice = new BigDecimal(unitPrice);
        line.vatRate = new BigDecimal("0.2000");
        line.totalPrice = totalPrice == null ? null : new BigDecimal(totalPrice);
        return line;
    }

    /**
     * Builds a ticket carrying the given lines, a store code and a card.
     *
     * @param storeCode the store code, or null for no store at all
     * @param card the fidelity card, or null
     * @param lines the ticket lines
     * @return the ticket
     */
    private Ticket ticket(String storeCode, String card, TicketLine... lines) {
        Ticket ticket = new Ticket();
        // Seconds on purpose: ISO_LOCAL_DATE_TIME renders the second-of-minute
        // only when it is present, so a date on the round minute would make the
        // expected string depend on that rule rather than on the contract.
        ticket.creationDate = LocalDateTime.of(2026, 10, 6, 11, 30, 45);
        ticket.fidelityCard = card;
        if (storeCode != null) {
            Store store = new Store();
            store.code = storeCode;
            ticket.store = store;
        }
        ticket.lines = new ArrayList<>(List.of(lines));
        return ticket;
    }

    /**
     * Builds an engine answer carrying a tax-included total and advantage types.
     *
     * @param totalIncludingTax the engine total, or null for no total block
     * @param advantageTypes the advantage type labels, possibly empty
     * @return the engine result
     */
    private ValuationClient.ValuationResult answer(String totalIncludingTax,
                                                   String... advantageTypes) {
        ValuationPayloads.ValuationResponseDto response =
                new ValuationPayloads.ValuationResponseDto();
        if (totalIncludingTax != null) {
            ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
            amount.amountIncludingTax = new BigDecimal(totalIncludingTax);
            response.totalPrice = amount;
        }
        for (String type : advantageTypes) {
            ValuationPayloads.AdvantageDto advantage = new ValuationPayloads.AdvantageDto();
            advantage.type = type;
            response.advantages.add(advantage);
        }
        return new ValuationClient.ValuationResult(response, "{}");
    }

    /**
     * Builds a kept-quantity map from id/quantity pairs.
     *
     * @param pairs alternating line id and kept quantity
     * @return the map
     */
    private Map<Long, BigDecimal> kept(Object... pairs) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Long) pairs[i], new BigDecimal((String) pairs[i + 1]));
        }
        return map;
    }

    // --- the guards that skip the call ---

    /**
     * A null ticket yields NOT_APPLICABLE without touching the engine (first leg
     * of the entry guard).
     */
    @Test
    void recomputeSkipsANullTicket() {
        ReturnValuationService service = newService();
        ReturnValuationService.Recalculation result = service.recompute(null, kept());
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE, result.status);
        assertEquals(BigDecimal.ZERO, result.withdrawnGenerosity);
        assertTrue(result.keptAdvantages.isEmpty());
    }

    /**
     * A ticket without its lines yields NOT_APPLICABLE (second leg of the entry
     * guard).
     */
    @Test
    void recomputeSkipsATicketWithoutLines() {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null);
        origin.lines = null;
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(origin, kept()).status);
    }

    /**
     * A null kept-quantity map yields NOT_APPLICABLE (third leg of the entry
     * guard).
     */
    @Test
    void recomputeSkipsANullQuantityMap() {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(origin, null).status);
    }

    /**
     * With no engine administered the sale was priced on catalog prices alone, so
     * a return takes no advantage back and nothing is called.
     */
    @Test
    void recomputeSkipsWhenTheEngineIsNotAdministered() throws Exception {
        ReturnValuationService service = newService();
        when(service.valuationClient.isEnabled()).thenReturn(false);
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(origin, kept(1L, "1")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    /**
     * When the WHOLE ticket comes back there is no kept basket to re-price.
     */
    @Test
    void recomputeSkipsWhenNothingIsKept() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(origin, kept(1L, "0")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    /**
     * A line the sale CANCELLED is not part of the kept basket, so a ticket whose
     * only line was cancelled is re-priced on nothing (first leg of the line
     * exclusion).
     */
    @Test
    void recomputeExcludesACancelledLine() throws Exception {
        ReturnValuationService service = newService();
        TicketLine cancelled = line(1L, "111", "2", "5.00", "10.00");
        cancelled.cancelled = true;
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(ticket("0001", null, cancelled), kept(1L, "2")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    /**
     * A RETURN line of a mixed ticket earns no offer and is not part of the kept
     * basket (second leg of the line exclusion).
     */
    @Test
    void recomputeExcludesAReturnLine() throws Exception {
        ReturnValuationService service = newService();
        TicketLine returned = line(1L, "111", "2", "5.00", "10.00");
        returned.returnLine = true;
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(ticket("0001", null, returned), kept(1L, "2")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    /**
     * A line with no database id cannot be named in the kept map (third leg of
     * the line exclusion).
     */
    @Test
    void recomputeExcludesALineWithoutAnId() throws Exception {
        ReturnValuationService service = newService();
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(ticket("0001", null, line(null, "111", "2", "5.00", "10.00")),
                        kept(1L, "2")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    /**
     * A line the kept map does not name is fully returned (fourth leg of the
     * line exclusion: no entry at all).
     */
    @Test
    void recomputeExcludesALineAbsentFromTheMap() throws Exception {
        ReturnValuationService service = newService();
        assertEquals(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE,
                service.recompute(ticket("0001", null, line(1L, "111", "2", "5.00", "10.00")),
                        kept(9L, "2")).status);
        verify(service.valuationClient, never()).valuate(any());
    }

    // --- the answer the engine gives ---

    /**
     * The nominal case: the kept basket is worth more than it was paid, and the
     * difference is the advantage the return takes back (LC-05-02-13). The
     * surviving advantages are restated from the engine's labels.
     */
    @Test
    void recomputeWithdrawsWhatTheKeptBasketNoLongerDeserves() throws Exception {
        ReturnValuationService service = newService();
        // Two articles at 5,00 EUR paid 8,00 EUR in all (a 2,00 EUR advantage):
        // one comes back, so the kept one was paid 4,00 EUR and is now worth 5,00.
        Ticket origin = ticket("0001", "CARD-1", line(1L, "111", "2", "5.00", "8.00"));
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00", "REMISE 2 ACHETES"));
        ReturnValuationService.Recalculation result = service.recompute(origin, kept(1L, "1"));
        assertEquals(ReturnValuationService.RecalculationStatus.RECOMPUTED, result.status);
        assertEquals(new BigDecimal("1.00"), result.withdrawnGenerosity);
        assertEquals(List.of("REMISE 2 ACHETES"), result.keptAdvantages);
        // LC-05-07-02: the priced couple rides along for the loyalty programme.
        assertTrue(result.requestJson.contains("\"produceEan\":\"111\""));
        assertEquals("{}", result.responseJson);
    }

    /**
     * A recalculation that did not happen carries no priced couple
     * (LC-05-07-02).
     */
    @Test
    void aRecalculationThatDidNotHappenCarriesNoCouple() {
        ReturnValuationService.Recalculation none = ReturnValuationService.Recalculation
                .none(ReturnValuationService.RecalculationStatus.UNAVAILABLE);
        assertNull(none.requestJson);
        assertNull(none.responseJson);
    }

    /**
     * A kept basket worth LESS than it was paid takes nothing back: a return
     * never grants a new advantage on the way out.
     */
    @Test
    void recomputeNeverWithdrawsANegativeAmount() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any())).thenReturn(answer("3.00"));
        ReturnValuationService.Recalculation result = service.recompute(origin, kept(1L, "1"));
        assertEquals(ReturnValuationService.RecalculationStatus.RECOMPUTED, result.status);
        assertEquals(0, BigDecimal.ZERO.compareTo(result.withdrawnGenerosity));
        assertTrue(result.keptAdvantages.isEmpty());
    }

    /**
     * An engine answer with no total block cannot be compared to anything
     * (second leg of the unusable-answer guard).
     */
    @Test
    void recomputeReportsUnavailableWithoutATotal() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any())).thenReturn(answer(null));
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE,
                service.recompute(origin, kept(1L, "1")).status);
    }

    /**
     * A total block without a tax-included amount is just as unusable (third leg
     * of the same guard).
     */
    @Test
    void recomputeReportsUnavailableWithoutATaxIncludedAmount() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        ValuationPayloads.ValuationResponseDto response =
                new ValuationPayloads.ValuationResponseDto();
        response.totalPrice = new ValuationPayloads.AmountDto();
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(response, "{}"));
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE,
                service.recompute(origin, kept(1L, "1")).status);
    }

    /**
     * A result carrying no parsed response at all (first leg of the same guard).
     */
    @Test
    void recomputeReportsUnavailableWithoutAResponse() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(null, "{}"));
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE,
                service.recompute(origin, kept(1L, "1")).status);
    }

    /**
     * An engine in error leaves the refund alone: UNAVAILABLE, no exception out
     * (the customer is at the till).
     */
    @Test
    void recomputeReportsUnavailableWhenTheEngineFails() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any()))
                .thenThrow(new IllegalStateException("HTTP 500"));
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE,
                service.recompute(origin, kept(1L, "1")).status);
    }

    /**
     * An interrupted call reports UNAVAILABLE and leaves the thread's interrupt
     * flag raised, as every other interruptible call of the register does.
     */
    @Test
    void recomputeRestoresTheInterruptFlag() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any())).thenThrow(new InterruptedException("stop"));
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE,
                service.recompute(origin, kept(1L, "1")).status);
        assertTrue(Thread.interrupted());
    }

    // --- the basket the engine receives ---

    /**
     * The request carries the kept quantity, the line uid, the article, the
     * store code, the sale date and the card (LC-05-02-13): the engine must
     * price the kept basket exactly as it priced the sale.
     */
    @Test
    void recomputeSendsTheKeptBasketAsTheSaleWasSent() throws Exception {
        ReturnValuationService service = newService();
        TicketLine dated = line(1L, "111", "3", "5.00", "15.00");
        dated.gs1ExpiryDate = LocalDate.of(2026, 12, 24);
        Ticket origin = ticket("0042", "CARD-9", dated,
                line(2L, "222", "1", "4.00", "4.00"));
        when(service.valuationClient.valuate(any())).thenReturn(answer("10.00"));
        service.recompute(origin, kept(1L, "2", 2L, "0"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        ValuationPayloads.BasketDto basket = captor.getValue();
        assertEquals("0042", basket.storeCode);
        assertEquals("CARD-9", basket.customerCode);
        assertEquals("2026-10-06T11:30:45", basket.createdAt);
        assertEquals(1, basket.items.size());
        assertEquals("uid-1", basket.items.get(0).lineId);
        assertEquals("111", basket.items.get(0).produceEan);
        assertEquals(new BigDecimal("2"), basket.items.get(0).quantity);
        assertEquals("2026-10-06T11:30:45", basket.items.get(0).priceDate);
        assertEquals("2026-12-24", basket.items.get(0).bestBeforeDate);
        assertNull(basket.items.get(0).pricePerUnitInclTax);
    }

    /**
     * A ticket naming no store sends the fallback store code (first leg of the
     * store guard).
     */
    @Test
    void recomputeSendsTheFallbackStoreCodeWithoutAStore() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket(null, null, line(1L, "111", "2", "5.00", "10.00"));
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(origin, kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertEquals("0000", captor.getValue().storeCode);
    }

    /**
     * A store without a code sends the fallback too (second leg of the store
     * guard).
     */
    @Test
    void recomputeSendsTheFallbackStoreCodeWithoutAStoreCode() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        origin.store.code = null;
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(origin, kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertEquals("0000", captor.getValue().storeCode);
    }

    /**
     * A ticket with no creation date still carries a date: the engine refuses a
     * basket without one, and a return must not fail over a legacy row.
     */
    @Test
    void recomputeSendsADateEvenWithoutACreationDate() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        origin.creationDate = null;
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(origin, kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertFalse(captor.getValue().createdAt.isBlank());
    }

    /**
     * A PRICE-EMBEDDED sticker line carries the all-or-nothing surcharge trio,
     * or the engine would re-price the EAN per kilogram and clobber the sticker.
     */
    @Test
    void recomputeSendsTheSurchargeTrioOfAPriceEmbeddedLine() throws Exception {
        ReturnValuationService service = newService();
        TicketLine sticker = line(1L, "2100001", "1", "6.00", "6.00");
        sticker.priceEmbedded = true;
        when(service.valuationClient.valuate(any())).thenReturn(answer("6.00"));
        service.recompute(ticket("0001", null, sticker), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        ValuationPayloads.ItemDto item = captor.getValue().items.get(0);
        assertEquals(new BigDecimal("6.00"), item.pricePerUnitInclTax);
        assertEquals(new BigDecimal("0.2000"), item.vatRate);
        assertEquals(new BigDecimal("5.00"), item.pricePerUnitExclTax);
    }

    /**
     * A REMISE gesture travels as the euros removed from the KEPT part of the
     * line, not from the line as it was sold (first arm of the gesture switch):
     * a unit remise of 0,50 EUR on two kept units is 1,00 EUR, never the 1,50 EUR
     * the three sold units carried.
     */
    @Test
    void recomputeSendsARemiseScaledOnTheKeptQuantity() throws Exception {
        ReturnValuationService service = newService();
        TicketLine discounted = line(1L, "111", "3", "5.00", "13.50");
        discounted.modifierType = PriceModType.REMISE;
        discounted.modifierValue = new BigDecimal("0.50");
        when(service.valuationClient.valuate(any())).thenReturn(answer("10.00"));
        service.recompute(ticket("0001", null, discounted), kept(1L, "2"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertEquals(new BigDecimal("1.00"), captor.getValue().items.get(0).manualDiscountAmount);
    }

    /**
     * A DISCOUNT gesture travels as its percentage (second arm).
     */
    @Test
    void recomputeSendsADiscountAsAPercentage() throws Exception {
        ReturnValuationService service = newService();
        TicketLine discounted = line(1L, "111", "2", "5.00", "9.00");
        discounted.modifierType = PriceModType.DISCOUNT;
        discounted.modifierValue = new BigDecimal("10");
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(ticket("0001", null, discounted), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertEquals(new BigDecimal("10"), captor.getValue().items.get(0).manualDiscountPercent);
    }

    /**
     * A FORCE_PRICE gesture travels as the new unit price (third arm).
     */
    @Test
    void recomputeSendsAForcedPriceAsTheUnitPrice() throws Exception {
        ReturnValuationService service = newService();
        TicketLine forced = line(1L, "111", "2", "5.00", "8.00");
        forced.modifierType = PriceModType.FORCE_PRICE;
        forced.modifierValue = new BigDecimal("4.00");
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(ticket("0001", null, forced), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertEquals(new BigDecimal("4.00"), captor.getValue().items.get(0).manualForcedPrice);
    }

    /**
     * A TICKET-level gesture has no line field in the contract: the request
     * carries none of the three (default arm of the gesture switch).
     */
    @Test
    void recomputeSendsNoLineGestureForATicketLevelOne() throws Exception {
        ReturnValuationService service = newService();
        TicketLine global = line(1L, "111", "2", "5.00", "9.00");
        global.modifierType = PriceModType.GLOBAL_REMISE;
        global.modifierValue = new BigDecimal("1.00");
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(ticket("0001", null, global), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        ValuationPayloads.ItemDto item = captor.getValue().items.get(0);
        assertNull(item.manualDiscountAmount);
        assertNull(item.manualDiscountPercent);
        assertNull(item.manualForcedPrice);
    }

    /**
     * A line with a gesture TYPE but no value carries nothing (first leg of the
     * gesture guard).
     */
    @Test
    void recomputeSendsNoGestureWithoutAValue() throws Exception {
        ReturnValuationService service = newService();
        TicketLine partial = line(1L, "111", "2", "5.00", "10.00");
        partial.modifierType = PriceModType.REMISE;
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(ticket("0001", null, partial), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertNull(captor.getValue().items.get(0).manualDiscountAmount);
    }

    /**
     * A line with a gesture VALUE but no type carries nothing either (second leg
     * of the gesture guard).
     */
    @Test
    void recomputeSendsNoGestureWithoutAType() throws Exception {
        ReturnValuationService service = newService();
        TicketLine partial = line(1L, "111", "2", "5.00", "10.00");
        partial.modifierValue = new BigDecimal("0.50");
        when(service.valuationClient.valuate(any())).thenReturn(answer("5.00"));
        service.recompute(ticket("0001", null, partial), kept(1L, "1"));
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        verify(service.valuationClient).valuate(captor.capture());
        assertNull(captor.getValue().items.get(0).manualDiscountAmount);
    }

    // --- the labels of the surviving advantages ---

    /**
     * An advantage with no type falls back to its offer, blanks are dropped and
     * a repeated label is listed once, in the engine's order.
     */
    @Test
    void recomputeListsTheSurvivingAdvantagesOnceEach() throws Exception {
        ReturnValuationService service = newService();
        ValuationPayloads.ValuationResponseDto response =
                new ValuationPayloads.ValuationResponseDto();
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        amount.amountIncludingTax = new BigDecimal("5.00");
        response.totalPrice = amount;
        ValuationPayloads.AdvantageDto typed = new ValuationPayloads.AdvantageDto();
        typed.type = " REMISE PANIER ";
        ValuationPayloads.AdvantageDto repeated = new ValuationPayloads.AdvantageDto();
        repeated.type = "REMISE PANIER";
        ValuationPayloads.AdvantageDto offerOnly = new ValuationPayloads.AdvantageDto();
        offerOnly.offer = "OFFRE-7";
        ValuationPayloads.AdvantageDto blank = new ValuationPayloads.AdvantageDto();
        blank.type = "   ";
        ValuationPayloads.AdvantageDto empty = new ValuationPayloads.AdvantageDto();
        response.advantages.addAll(List.of(typed, repeated, offerOnly, blank, empty));
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(response, "{}"));
        ReturnValuationService.Recalculation result = service.recompute(
                ticket("0001", null, line(1L, "111", "2", "5.00", "10.00")), kept(1L, "1"));
        assertEquals(List.of("REMISE PANIER", "OFFRE-7"), result.keptAdvantages);
    }

    /**
     * A response whose advantage list is absent altogether lists nothing
     * (nullity leg of the label extraction).
     */
    @Test
    void recomputeListsNothingWithoutAnAdvantageList() throws Exception {
        ReturnValuationService service = newService();
        ValuationPayloads.ValuationResponseDto response =
                new ValuationPayloads.ValuationResponseDto();
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        amount.amountIncludingTax = new BigDecimal("5.00");
        response.totalPrice = amount;
        response.advantages = null;
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(response, "{}"));
        ReturnValuationService.Recalculation result = service.recompute(
                ticket("0001", null, line(1L, "111", "2", "5.00", "10.00")), kept(1L, "1"));
        assertEquals(ReturnValuationService.RecalculationStatus.RECOMPUTED, result.status);
        assertTrue(result.keptAdvantages.isEmpty());
    }

    /**
     * A line with no stored total is valued at its unit price, the fallback of
     * {@code getEffectiveUnitPrice}: the kept basket is then compared against
     * the catalog price the customer paid.
     */
    @Test
    void recomputeValuesALineWithoutATotalAtItsUnitPrice() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", null));
        when(service.valuationClient.valuate(any())).thenReturn(answer("7.00"));
        ReturnValuationService.Recalculation result = service.recompute(origin, kept(1L, "1"));
        assertEquals(new BigDecimal("2.00"), result.withdrawnGenerosity);
    }

    /**
     * {@code none} builds an outcome with no amount and no advantage, whatever
     * status it carries.
     */
    @Test
    void noneCarriesNoAmountAndNoAdvantage() {
        ReturnValuationService.Recalculation result = ReturnValuationService.Recalculation
                .none(ReturnValuationService.RecalculationStatus.UNAVAILABLE);
        assertEquals(ReturnValuationService.RecalculationStatus.UNAVAILABLE, result.status);
        assertEquals(BigDecimal.ZERO, result.withdrawnGenerosity);
        assertTrue(result.keptAdvantages.isEmpty());
    }

    // --------------------------------------------------
    // Campaign 2 — retours added branches
    // --------------------------------------------------

    /**
     * A null element in the origin's line list is counted as kept-ZERO (the
     * {@code line == null} true leg of the kept-of guard): {@code keptOf} is
     * reached first for each line, so a null slips through both passes without a
     * dereference and the recomputation still completes.
     *
     * @throws Exception if the engine stub cannot be wired
     */
    @Test
    void recomputeTreatsANullLineAsKeptZero() throws Exception {
        ReturnValuationService service = newService();
        Ticket origin = ticket("0001", null, line(1L, "111", "2", "5.00", "10.00"));
        origin.lines.add(null);
        when(service.valuationClient.valuate(any())).thenReturn(answer("3.00"));
        ReturnValuationService.Recalculation result = service.recompute(origin, kept(1L, "1"));
        assertEquals(ReturnValuationService.RecalculationStatus.RECOMPUTED, result.status);
        assertTrue(result.keptAdvantages.isEmpty());
    }

    /**
     * An advantage whose only label is a blank offer is dropped (the
     * {@code !label.isBlank()} false leg of the label extraction): the label is
     * non-null yet blank, so it never joins the surviving advantages.
     *
     * @throws Exception if the engine stub cannot be wired
     */
    @Test
    void recomputeDropsAnAdvantageWhoseOfferIsBlank() throws Exception {
        ReturnValuationService service = newService();
        ValuationPayloads.ValuationResponseDto response =
                new ValuationPayloads.ValuationResponseDto();
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        amount.amountIncludingTax = new BigDecimal("5.00");
        response.totalPrice = amount;
        ValuationPayloads.AdvantageDto real = new ValuationPayloads.AdvantageDto();
        real.type = "REMISE PANIER";
        ValuationPayloads.AdvantageDto blankOffer = new ValuationPayloads.AdvantageDto();
        blankOffer.offer = "   ";
        response.advantages.addAll(List.of(real, blankOffer));
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(response, "{}"));
        ReturnValuationService.Recalculation result = service.recompute(
                ticket("0001", null, line(1L, "111", "2", "5.00", "10.00")), kept(1L, "1"));
        assertEquals(List.of("REMISE PANIER"), result.keptAdvantages);
    }
}
