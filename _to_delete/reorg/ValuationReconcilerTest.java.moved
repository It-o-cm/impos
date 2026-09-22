package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link ValuationReconciler}.
 * <p>
 * The reconciler is a stateless CDI bean holding only a private final,
 * BigDecimal-safe {@link ObjectMapper}; it is exercised directly with the
 * real parser, the engine response being fed as JSON produced from
 * {@link ValuationPayloads} DTOs through a local mapper (round-trip). Its two
 * Panache collaborators are static finders that, under plain {@code mvn test},
 * resolve to {@link PanacheEntityBase} and are intercepted with
 * {@link org.mockito.Mockito#mockStatic}: {@code TicketLineValuation.delete}
 * (trace wipe) and {@code Ticket.findById} (draft attachment). The trace's
 * {@code new TicketLineValuation()} / {@code persist()} pair is neutralized
 * with {@link org.mockito.Mockito#mockConstruction}, letting the persisted
 * field values be asserted from the constructed mocks. No Quarkus context, no
 * H2 and no application boot.
 * <p>
 * Tests that pass a null draft id touch no Panache at all and therefore need
 * no static mock; only the draft-bearing paths install the interceptors.
 */
class ValuationReconcilerTest {

    /** Local mapper used to serialize the response DTOs into the engine JSON. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A stable draft database id used by the draft-bearing scenarios. */
    private static final Long DB_ID = 42L;

    /** The stateless subject under test. */
    private final ValuationReconciler reconciler = new ValuationReconciler();

    // --------------------------------------------------
    // Builders
    // --------------------------------------------------

    /**
     * Builds an amount carrying only the tax-included figure.
     *
     * @param incl the tax-included amount, or null
     * @return the amount DTO
     */
    private ValuationPayloads.AmountDto amount(String incl) {
        ValuationPayloads.AmountDto a = new ValuationPayloads.AmountDto();
        a.amountIncludingTax = incl == null ? null : new BigDecimal(incl);
        return a;
    }

    /**
     * Builds one valued portion of a line inside an offer.
     *
     * @param lineId the echoed line uid, or null
     * @param amount the portion amount, or null
     * @return the portion DTO
     */
    private ValuationPayloads.OfferItemDto portion(String lineId, ValuationPayloads.AmountDto amount) {
        ValuationPayloads.OfferItemDto p = new ValuationPayloads.OfferItemDto();
        p.lineId = lineId;
        p.amount = amount;
        return p;
    }

    /**
     * Builds an offer with an id, a type label and its portions.
     *
     * @param offerId the stable offer id, or null
     * @param type the offer type label
     * @param items the valued portions
     * @return the offer DTO
     */
    private ValuationPayloads.OfferDto offer(String offerId, String type, ValuationPayloads.OfferItemDto... items) {
        ValuationPayloads.OfferDto o = new ValuationPayloads.OfferDto();
        o.offerId = offerId;
        o.type = type;
        for (ValuationPayloads.OfferItemDto p : items) o.items.add(p);
        return o;
    }

    /**
     * Builds a discount advantage joined to an offer reference.
     *
     * @param offerRef the joined offer reference (offerId or type)
     * @param type the advantage label
     * @param amount the discount amount, or null (upsell/meal-voucher shape)
     * @return the advantage DTO
     */
    private ValuationPayloads.AdvantageDto discount(String offerRef, String type, ValuationPayloads.AmountDto amount) {
        ValuationPayloads.AdvantageDto a = new ValuationPayloads.AdvantageDto();
        a.offer = offerRef;
        a.type = type;
        a.discountAmount = amount;
        return a;
    }

    /**
     * Assembles and serializes an engine response of the given offers and advantages.
     *
     * @param offers the offers
     * @param advantages the advantages
     * @return the JSON response
     * @throws Exception if serialization fails
     */
    private String json(List<ValuationPayloads.OfferDto> offers,
                        List<ValuationPayloads.AdvantageDto> advantages) throws Exception {
        ValuationPayloads.ValuationResponseDto r = new ValuationPayloads.ValuationResponseDto();
        r.offers = offers;
        r.advantages = advantages;
        return MAPPER.writeValueAsString(r);
    }

    /**
     * Builds a cart line with a fixed uid, ean, unit price and quantity.
     *
     * @param uid the line uid (contractual lineId)
     * @param ean the ean, or null
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @return the ticket line
     */
    private TicketState.TicketItem item(String uid, String ean, String unitPrice, String quantity) {
        TicketState.TicketItem it = new TicketState.TicketItem(ean, null, "L-" + uid,
                new BigDecimal(unitPrice), new BigDecimal(quantity), new BigDecimal("0.20"));
        it.uid = uid;
        return it;
    }

    /**
     * Builds an in-memory ticket state holding the given lines.
     *
     * @param items the lines
     * @return the ticket state
     */
    private TicketState ticketWith(TicketState.TicketItem... items) {
        TicketState t = new TicketState();
        for (TicketState.TicketItem it : items) t.items.add(it);
        return t;
    }

    // --------------------------------------------------
    // apply — parsing guard
    // --------------------------------------------------

    /**
     * apply returns zero and keeps local totals when the JSON is unreadable
     * (readValue throws, catch arm).
     */
    @Test
    void applyReturnsZeroOnUnreadableJson() {
        TicketState ticket = ticketWith(item("L1", "1", "10", "1"));
        BigDecimal adjustment = reconciler.apply(ticket, null, "}{ not json");
        assertEquals(BigDecimal.ZERO, adjustment);
        assertNull(ticket.items.get(0).valuedTotal);
    }

    // --------------------------------------------------
    // apply — draft-less valuation
    // --------------------------------------------------

    /**
     * apply values a covered line without draft nor advantage: the offer id
     * feeds the label (offerId non-null arm), the portion passes the filter,
     * the default zero cut applies (getOrDefault default arm), the valued
     * total stays non-negative and no trace is built (draft null arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyValuesLineWithoutDraft() throws Exception {
        String response = json(
                List.of(offer("OFF", "T", portion("L1", amount("8.00")))),
                List.of());
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-2.00"), adjustment);
        assertEquals(new BigDecimal("8.00"), it.valuedTotal);
    }

    /**
     * apply clamps a valued total driven negative by an over-large advantage
     * cut to zero (valuedTotal.signum() &lt; 0 arm; getOrDefault present arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyClampsNegativeValuedTotalToZero() throws Exception {
        String response = json(
                List.of(offer("O", "T", portion("L1", amount("1.00")))),
                List.of(discount("O", "AD", amount("2.00"))));
        TicketState.TicketItem it = item("L1", "1", "5", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-5.00"), adjustment);
        assertEquals(BigDecimal.ZERO, it.valuedTotal);
    }

    /**
     * apply resolves the advantage's offer by type when no offerId is emitted
     * (offerLabel type arm; resolveOffer first loop misses, second loop hits).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyResolvesOfferByType() throws Exception {
        String response = json(
                List.of(offer(null, "PROMO", portion("L1", amount("4.00")))),
                List.of(discount("PROMO", "AD", amount("1.00"))));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-7.00"), adjustment);
        assertEquals(new BigDecimal("3.00"), it.valuedTotal);
    }

    /**
     * apply skips advantages whose offer cannot be resolved: a null reference
     * (resolveOffer reference-null arm) and a reference matching neither an
     * offerId nor a type (both loops miss, null returned, offer-null arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applySkipsAdvantagesWithUnresolvableOffer() throws Exception {
        String response = json(
                List.of(offer("A", "T", portion("L1", amount("5.00")))),
                List.of(discount(null, "NULLREF", amount("1.00")),
                        discount("ZZZ", "NOMATCH", amount("1.00"))));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-5.00"), adjustment);
        assertEquals(new BigDecimal("5.00"), it.valuedTotal);
    }

    /**
     * apply skips a resolvable but basket-level offer with no portions
     * (offer.items.isEmpty arm), applying nothing.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applySkipsAdvantageWithEmptyOfferItems() throws Exception {
        String response = json(
                List.of(offer("EMPTY", "BASKET")),
                List.of(discount("EMPTY", "AD", amount("1.00"))));
        BigDecimal adjustment = reconciler.apply(ticketWith(), null, response);
        assertEquals(BigDecimal.ZERO, adjustment);
    }

    /**
     * apply skips advantages carrying no discount figure: a wholly absent
     * discountAmount (first guard arm) and a present discountAmount with a
     * null tax-included figure (second guard arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applySkipsAdvantagesWithoutDiscountFigure() throws Exception {
        ValuationPayloads.AdvantageDto upsell = discount("A", "UPSELL", null);
        String response = json(
                List.of(offer("A", "T", portion("L1", amount("5.00")))),
                List.of(upsell, discount("A", "BLANK", amount(null))));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-5.00"), adjustment);
        assertEquals(new BigDecimal("5.00"), it.valuedTotal);
    }

    /**
     * apply returns early from allocation when every offer portion is unkeyed
     * (allocate shares.isEmpty arm) — also covering the offers-loop null-lineId
     * skip.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyReturnsEarlyWhenOfferPortionsUnkeyed() throws Exception {
        String response = json(
                List.of(offer("O", "T", portion(null, amount("5.00")))),
                List.of(discount("O", "AD", amount("1.00"))));
        BigDecimal adjustment = reconciler.apply(ticketWith(), null, response);
        assertEquals(BigDecimal.ZERO, adjustment);
    }

    /**
     * apply returns early from allocation when the total valued share is zero
     * even though a keyed share exists (allocate totalShare.signum()==0 arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyReturnsEarlyWhenTotalShareZero() throws Exception {
        String response = json(
                List.of(offer("O", "T", portion("L1", amount("0.00")))),
                List.of(discount("O", "AD", amount("1.00"))));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(new BigDecimal("-10.00"), adjustment);
        assertEquals(new BigDecimal("0.00"), it.valuedTotal);
    }

    /**
     * apply allocates a discount proportionally to each line's valued share,
     * with the rounding residual landing on the largest share: the largest
     * comparison is true on the first share and false thereafter, the two
     * merged portions of one line exercise the aggregate add, the malformed
     * portions (null amount, null tax-included figure) exercise both remaining
     * allocation skip arms, and a non-zero residual is redistributed.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyAllocatesProportionallyWithResidualOnLargest() throws Exception {
        String response = json(
                List.of(offer("O", "T",
                        portion("L1", amount("0.50")),
                        portion("L1", amount("0.50")),
                        portion("L2", amount("1.00")),
                        portion("L2", null),
                        portion("L3", amount("1.00")),
                        portion("L3", amount(null)))),
                List.of(discount("O", "AD", amount("0.10"))));
        TicketState.TicketItem l1 = item("L1", "1", "1", "1");
        TicketState.TicketItem l2 = item("L2", "2", "1", "1");
        TicketState.TicketItem l3 = item("L3", "3", "1", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(l1, l2, l3), null, response);
        assertEquals(new BigDecimal("-0.10"), adjustment);
        assertEquals(new BigDecimal("0.96"), l1.valuedTotal);
        assertEquals(new BigDecimal("0.97"), l2.valuedTotal);
        assertEquals(new BigDecimal("0.97"), l3.valuedTotal);
    }

    // --------------------------------------------------
    // apply — uncovered / unknown lines
    // --------------------------------------------------

    /**
     * apply warns and keeps local totals for an eligible cart line covered by
     * no offer (valued null with ean set, non-empty and positive total), and
     * warns for an engine line unknown to the cart (non-empty residue), with a
     * zero net adjustment.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyWarnsUncoveredCartLineAndUnknownEngineLine() throws Exception {
        String response = json(
                List.of(offer("O", "T", portion("GHOST", amount("5.00")))),
                List.of());
        TicketState.TicketItem it = item("X", "123", "10", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(it), null, response);
        assertEquals(BigDecimal.ZERO, adjustment);
        assertNull(it.valuedTotal);
    }

    /**
     * apply stays silent for uncovered lines that are not eligible: a null ean
     * (ean-null arm), an empty ean (isEmpty arm) and a non-positive total
     * (signum arm), leaving every valued total untouched.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyIgnoresUncoveredNonEligibleLines() throws Exception {
        String response = json(List.of(), List.of());
        TicketState.TicketItem nullEan = item("A1", null, "10", "1");
        TicketState.TicketItem emptyEan = item("A2", "", "10", "1");
        TicketState.TicketItem zeroTotal = item("A3", "9", "0", "1");
        BigDecimal adjustment = reconciler.apply(ticketWith(nullEan, emptyEan, zeroTotal), null, response);
        assertEquals(BigDecimal.ZERO, adjustment);
        assertNull(nullEan.valuedTotal);
        assertNull(emptyEan.valuedTotal);
        assertNull(zeroTotal.valuedTotal);
    }

    // --------------------------------------------------
    // apply — draft-bearing persistence
    // --------------------------------------------------

    /**
     * apply on a draft wipes the prior trace, aggregates portions and offer
     * labels (valued-by-line add arm; label merge with the contains arm true
     * then false), skips the three malformed-portion shapes, persists the line
     * trace with a null advantage figure and label (cut-zero arm; null label
     * truncation) and stamps the draft VALUATED (draft non-null arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyOnDraftAggregatesLabelsAndPersistsTrace() throws Exception {
        String response = json(
                List.of(offer("A", "3x2",
                                portion("L1", amount("2.00")),
                                portion(null, amount("9.00")),
                                portion("L1", null),
                                portion("L1", amount(null))),
                        offer("A", "3x2", portion("L1", amount("3.00"))),
                        offer("B", "-10%", portion("L1", amount("1.00")))),
                List.of());
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<TicketLineValuation> mc = mockConstruction(TicketLineValuation.class)) {
            Ticket draft = mock(Ticket.class);
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(draft);
            BigDecimal adjustment = reconciler.apply(ticketWith(it), DB_ID, response);
            assertEquals(new BigDecimal("-4.00"), adjustment);
            assertEquals(new BigDecimal("6.00"), it.valuedTotal);
            ms.verify(() -> TicketLineValuation.delete("ticket.id", DB_ID));
            assertEquals(Ticket.ValuationStatus.VALUATED, draft.valuationStatus);
            assertEquals(1, mc.constructed().size());
            TicketLineValuation trace = mc.constructed().get(0);
            assertSame(draft, trace.ticket);
            assertEquals("L1", trace.lineUid);
            assertEquals(new BigDecimal("10.00"), trace.localTotal);
            assertEquals(new BigDecimal("6.00"), trace.valuedTotal);
            assertEquals("A; B", trace.offerLabel);
            assertNull(trace.advantageLabel);
            assertNull(trace.advantageAmount);
        }
    }

    /**
     * apply on a draft resolves the offer by id, allocates a single-line
     * discount with a zero residual (residual.signum()==0 arm), persists a
     * positive advantage amount (cut &gt; 0 arm) and its non-null label.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyOnDraftPersistsAdvantageAmountAndLabel() throws Exception {
        String response = json(
                List.of(offer("OFF1", "3x2", portion("L1", amount("8.00")))),
                List.of(discount("OFF1", "DISC", amount("1.00"))));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<TicketLineValuation> mc = mockConstruction(TicketLineValuation.class)) {
            Ticket draft = mock(Ticket.class);
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(draft);
            BigDecimal adjustment = reconciler.apply(ticketWith(it), DB_ID, response);
            assertEquals(new BigDecimal("-3.00"), adjustment);
            assertEquals(new BigDecimal("7.00"), it.valuedTotal);
            TicketLineValuation trace = mc.constructed().get(0);
            assertEquals("OFF1", trace.offerLabel);
            assertEquals("DISC", trace.advantageLabel);
            assertEquals(new BigDecimal("1.00"), trace.advantageAmount);
        }
    }

    /**
     * apply on a draft merges advantage labels across several discounts on one
     * line (label merge contains arm true then false) and truncates an
     * over-long offer label built from the type when no offerId is emitted
     * (offerLabel type arm; truncate length &gt; max arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void applyOnDraftMergesAdvantageLabelsAndTruncatesLongLabel() throws Exception {
        String longType = "x".repeat(250);
        String response = json(
                List.of(offer(null, longType, portion("L1", amount("10.00")))),
                List.of(discount(longType, "X", amount("1.00")),
                        discount(longType, "X", amount("1.00")),
                        discount(longType, "Y", amount("1.00"))));
        TicketState.TicketItem it = item("L1", "1", "20", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class);
             MockedConstruction<TicketLineValuation> mc = mockConstruction(TicketLineValuation.class)) {
            Ticket draft = mock(Ticket.class);
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(draft);
            BigDecimal adjustment = reconciler.apply(ticketWith(it), DB_ID, response);
            assertEquals(new BigDecimal("-13.00"), adjustment);
            assertEquals(new BigDecimal("7.00"), it.valuedTotal);
            TicketLineValuation trace = mc.constructed().get(0);
            assertEquals("X; Y", trace.advantageLabel);
            assertEquals(new BigDecimal("3.00"), trace.advantageAmount);
            assertEquals(200, trace.offerLabel.length());
            assertEquals(longType.substring(0, 200), trace.offerLabel);
        }
    }

    // --------------------------------------------------
    // revert
    // --------------------------------------------------

    /**
     * revert on a draft clears every valued total, wipes the trace and stamps
     * the draft NOT_VALUATED (ticketDbId non-null arm; draft non-null arm).
     */
    @Test
    void revertOnDraftClearsAndStampsNotValuated() {
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        it.valuedTotal = new BigDecimal("7.00");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            Ticket draft = mock(Ticket.class);
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(draft);
            reconciler.revert(ticketWith(it), DB_ID);
            assertNull(it.valuedTotal);
            ms.verify(() -> TicketLineValuation.delete("ticket.id", DB_ID));
            assertEquals(Ticket.ValuationStatus.NOT_VALUATED, draft.valuationStatus);
        }
    }

    /**
     * revert with a null draft id only clears the in-memory valued totals and
     * touches no Panache (ticketDbId null arm).
     */
    @Test
    void revertWithoutDraftOnlyClearsItems() {
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        it.valuedTotal = new BigDecimal("7.00");
        reconciler.revert(ticketWith(it), null);
        assertNull(it.valuedTotal);
    }

    /**
     * revert on a missing draft still clears totals and wipes the trace but
     * stamps nothing (draft null arm).
     */
    @Test
    void revertWithMissingDraftClearsAndDeletesOnly() {
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        it.valuedTotal = new BigDecimal("7.00");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(null);
            reconciler.revert(ticketWith(it), DB_ID);
            assertNull(it.valuedTotal);
            ms.verify(() -> TicketLineValuation.delete("ticket.id", DB_ID));
        }
    }

    // --------------------------------------------------
    // markDegraded
    // --------------------------------------------------

    /**
     * markDegraded returns immediately for a null draft id, touching no
     * Panache (ticketDbId null arm).
     */
    @Test
    void markDegradedReturnsForNullDraftId() {
        reconciler.markDegraded(null);
    }

    /**
     * markDegraded stamps a resolved draft DEGRADED (draft non-null arm).
     */
    @Test
    void markDegradedStampsResolvedDraft() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            Ticket draft = mock(Ticket.class);
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(draft);
            reconciler.markDegraded(DB_ID);
            assertEquals(Ticket.ValuationStatus.DEGRADED, draft.valuationStatus);
        }
    }

    /**
     * markDegraded stamps nothing when the draft is missing (draft null arm).
     */
    @Test
    void markDegradedIgnoresMissingDraft() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            ms.when(() -> Ticket.findById(DB_ID)).thenReturn(null);
            reconciler.markDegraded(DB_ID);
            ms.verify(() -> Ticket.findById(DB_ID));
        }
    }
}
