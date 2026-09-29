package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConsumptionService}.
 * <p>
 * The Panache statics ({@code ConsumptionOutbox.find}, {@code findById}) are
 * intercepted with {@code mockStatic(PanacheEntityBase.class)} and the {@code
 * persist()} of enqueued rows is neutralised with {@code
 * mockConstruction(ConsumptionOutbox.class)}. Every branch of {@code
 * parseAdvantages}, {@code enqueueSale}, {@code enqueueCancellation}, {@code
 * pending}, {@code markApplied} and {@code recordFailure} is exercised.
 */
class ConsumptionServiceTest {

    /** The service under test. */
    private final ConsumptionService service = new ConsumptionService();

    /** A valid one-advantage engine valuation response. */
    private static final String ONE_ADVANTAGE =
            "{\"advantages\":[{\"offerCode\":\"OFF1\","
            + "\"discountAmount\":{\"amountIncludingTax\":-5.00}}]}";

    /**
     * Builds a Panache query whose first result is the given row.
     *
     * @param row the row to answer, or null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<ConsumptionOutbox> firstResult(ConsumptionOutbox row) {
        PanacheQuery<ConsumptionOutbox> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(row);
        return query;
    }

    /**
     * Stubs {@code findByKey} for a given key to answer the given row.
     *
     * @param panache the open Panache static mock
     * @param storeCode the store code of the key
     * @param ticketNumber the ticket number of the key
     * @param direction the direction of the key
     * @param row the row to answer, or null
     */
    private void stubKey(MockedStatic<PanacheEntityBase> panache, String storeCode,
            String ticketNumber, ConsumptionOutbox.Direction direction, ConsumptionOutbox row) {
        PanacheQuery<ConsumptionOutbox> query = firstResult(row);
        panache.when(() -> ConsumptionOutbox.find(
                        "storeCode = ?1 and ticketNumber = ?2 and direction = ?3",
                        storeCode, ticketNumber, direction))
                .thenReturn(query);
    }

    /**
     * A null valuation JSON yields no advantage (the null-json arm).
     */
    @Test
    void parseAdvantagesEmptyForNullJson() {
        assertTrue(service.parseAdvantages(null).isEmpty());
    }

    /**
     * A blank valuation JSON yields no advantage (the blank-json arm).
     */
    @Test
    void parseAdvantagesEmptyForBlankJson() {
        assertTrue(service.parseAdvantages("   ").isEmpty());
    }

    /**
     * Unreadable JSON is tolerated and yields no advantage (the catch arm).
     */
    @Test
    void parseAdvantagesEmptyForUnreadableJson() {
        assertTrue(service.parseAdvantages("}{ not json").isEmpty());
    }

    /**
     * A response carrying no advantages array yields none (the null-advantages
     * arm).
     */
    @Test
    void parseAdvantagesEmptyWhenNoAdvantagesArray() {
        assertTrue(service.parseAdvantages("{}").isEmpty());
    }

    /**
     * A response whose advantages field is explicitly null yields none (the
     * null-advantages true arm).
     */
    @Test
    void parseAdvantagesEmptyWhenAdvantagesExplicitlyNull() {
        assertTrue(service.parseAdvantages("{\"advantages\":null}").isEmpty());
    }

    /**
     * An advantage without an offer code is skipped (the offerCode-null leg).
     */
    @Test
    void parseAdvantagesSkipsAdvantageWithoutOfferCode() {
        String json = "{\"advantages\":[{\"discountAmount\":{\"amountIncludingTax\":5.00}}]}";
        assertTrue(service.parseAdvantages(json).isEmpty());
    }

    /**
     * An advantage without a discount amount is skipped (the discountAmount-null
     * leg).
     */
    @Test
    void parseAdvantagesSkipsAdvantageWithoutDiscountAmount() {
        String json = "{\"advantages\":[{\"offerCode\":\"OFF1\"}]}";
        assertTrue(service.parseAdvantages(json).isEmpty());
    }

    /**
     * An advantage whose discount amount carries no tax-included figure is
     * skipped (the amountIncludingTax-null leg).
     */
    @Test
    void parseAdvantagesSkipsAdvantageWithoutIncludingTaxAmount() {
        String json = "{\"advantages\":[{\"offerCode\":\"OFF1\",\"discountAmount\":{}}]}";
        assertTrue(service.parseAdvantages(json).isEmpty());
    }

    /**
     * A complete advantage is declared with its offer code and absolute amount.
     */
    @Test
    void parseAdvantagesDeclaresCompleteAdvantageWithAbsoluteAmount() {
        List<ConsumptionService.DeclaredAdvantage> declared = service.parseAdvantages(ONE_ADVANTAGE);
        assertEquals(1, declared.size());
        assertEquals("OFF1", declared.get(0).offerCode());
        assertEquals(new BigDecimal("5.00"), declared.get(0).amount());
    }

    /**
     * A valuation carrying two engine advantages declares both, each with its
     * offer code and absolute amount (spec §6-1).
     */
    @Test
    void parseAdvantagesDeclaresTwoAdvantages() {
        String json = "{\"advantages\":["
                + "{\"offerCode\":\"OFF1\",\"discountAmount\":{\"amountIncludingTax\":-5.00}},"
                + "{\"offerCode\":\"OFF2\",\"discountAmount\":{\"amountIncludingTax\":3.50}}]}";
        List<ConsumptionService.DeclaredAdvantage> declared = service.parseAdvantages(json);
        assertEquals(2, declared.size());
        assertEquals("OFF1", declared.get(0).offerCode());
        assertEquals(new BigDecimal("5.00"), declared.get(0).amount());
        assertEquals("OFF2", declared.get(1).offerCode());
        assertEquals(new BigDecimal("3.50"), declared.get(1).amount());
    }

    /** An advantage joined to a two-line offer, carrying an accounting key. */
    private static final String ADVANTAGE_WITH_OFFER =
            "{\"offers\":[{\"offerId\":\"O1\",\"type\":\"T1\",\"items\":["
            + "{\"lineId\":\"L1\",\"produceEan\":\"EAN1\",\"amount\":{\"amountIncludingTax\":6.00}},"
            + "{\"lineId\":\"L2\",\"produceEan\":\"EAN2\",\"amount\":{\"amountIncludingTax\":4.00}}]}],"
            + "\"advantages\":[{\"offerCode\":\"OFF1\",\"offer\":\"O1\",\"accountingCode\":\"706100\","
            + "\"discountAmount\":{\"amountIncludingTax\":-5.00}}]}";

    /**
     * The accounting code is copied verbatim onto the declared advantage
     * (spec §2), and the per-article lines are derived from the offer's valued
     * shares (spec §4): 5,00 € split 6:4 gives 3,00 € on EAN1 and 2,00 € on
     * EAN2, each in absolute value.
     */
    @Test
    void parseAdvantagesCopiesAccountingCodeAndDerivesLines() {
        List<ConsumptionService.DeclaredAdvantage> declared =
                service.parseAdvantages(ADVANTAGE_WITH_OFFER);
        assertEquals(1, declared.size());
        ConsumptionService.DeclaredAdvantage advantage = declared.get(0);
        assertEquals("706100", advantage.accountingCode());
        assertEquals(2, advantage.lines().size());
        assertEquals("EAN1", advantage.lines().get(0).ean());
        assertEquals(new BigDecimal("3.00"), advantage.lines().get(0).amount());
        assertEquals("EAN2", advantage.lines().get(1).ean());
        assertEquals(new BigDecimal("2.00"), advantage.lines().get(1).amount());
    }

    /**
     * The derived lines sum back to the advantage amount: a rounding residual
     * lands on the largest share (spec §4). 1,00 € over three equal 1,00 €
     * shares rounds to 0,33 each, and the leftover cent falls on the first,
     * largest-ranked share (0,34 + 0,33 + 0,33 = 1,00).
     */
    @Test
    void parseAdvantagesPutsTheRoundingResidualOnTheLargestShare() {
        String json = "{\"offers\":[{\"offerId\":\"O1\",\"items\":["
                + "{\"lineId\":\"L1\",\"produceEan\":\"E1\",\"amount\":{\"amountIncludingTax\":1.00}},"
                + "{\"lineId\":\"L2\",\"produceEan\":\"E2\",\"amount\":{\"amountIncludingTax\":1.00}},"
                + "{\"lineId\":\"L3\",\"produceEan\":\"E3\",\"amount\":{\"amountIncludingTax\":1.00}}]}],"
                + "\"advantages\":[{\"offerCode\":\"OFF1\",\"offer\":\"O1\","
                + "\"discountAmount\":{\"amountIncludingTax\":-1.00}}]}";
        List<ConsumptionService.DeclaredAdvantage> declared = service.parseAdvantages(json);
        List<ConsumptionService.DeclaredLine> lines = declared.get(0).lines();
        assertEquals(new BigDecimal("0.34"), lines.get(0).amount());
        assertEquals(new BigDecimal("0.33"), lines.get(1).amount());
        assertEquals(new BigDecimal("0.33"), lines.get(2).amount());
        BigDecimal sum = lines.get(0).amount().add(lines.get(1).amount()).add(lines.get(2).amount());
        assertEquals(new BigDecimal("1.00"), sum);
    }

    /**
     * Without the new fields the declaration is unchanged: an advantage with no
     * accounting code and no resolvable offer carries a null accounting code and
     * null lines (spec §2/§4, the absent arms).
     */
    @Test
    void parseAdvantagesLeavesNewFieldsNullWhenAbsent() {
        ConsumptionService.DeclaredAdvantage advantage = service.parseAdvantages(ONE_ADVANTAGE).get(0);
        assertNull(advantage.accountingCode());
        assertNull(advantage.lines());
    }

    /**
     * An advantage whose offer cannot be resolved derives no lines (the
     * unresolvable-offer arm): the join key names an offer that is not there.
     */
    @Test
    void parseAdvantagesDerivesNoLinesForUnresolvableOffer() {
        String json = "{\"offers\":[{\"offerId\":\"O1\",\"items\":["
                + "{\"lineId\":\"L1\",\"produceEan\":\"E1\",\"amount\":{\"amountIncludingTax\":1.00}}]}],"
                + "\"advantages\":[{\"offerCode\":\"OFF1\",\"offer\":\"UNKNOWN\","
                + "\"discountAmount\":{\"amountIncludingTax\":-1.00}}]}";
        assertNull(service.parseAdvantages(json).get(0).lines());
    }

    /**
     * An advantage joined to an offer whose items carry no valued share derives
     * no lines (the zero-total-share arm).
     */
    @Test
    void parseAdvantagesDerivesNoLinesWhenOfferHasNoValuedShare() {
        String json = "{\"offers\":[{\"offerId\":\"O1\",\"items\":["
                + "{\"lineId\":\"L1\",\"produceEan\":\"E1\"}]}],"
                + "\"advantages\":[{\"offerCode\":\"OFF1\",\"offer\":\"O1\","
                + "\"discountAmount\":{\"amountIncludingTax\":-1.00}}]}";
        assertNull(service.parseAdvantages(json).get(0).lines());
    }

    /**
     * An advantage joined by its type string, not a stable id, resolves its
     * offer all the same (the type-fallback arm of the join).
     */
    @Test
    void parseAdvantagesResolvesOfferByTypeString() {
        String json = "{\"offers\":[{\"type\":\"T1\",\"items\":["
                + "{\"lineId\":\"L1\",\"produceEan\":\"E1\",\"amount\":{\"amountIncludingTax\":2.00}}]}],"
                + "\"advantages\":[{\"offerCode\":\"OFF1\",\"offer\":\"T1\","
                + "\"discountAmount\":{\"amountIncludingTax\":-2.00}}]}";
        List<ConsumptionService.DeclaredLine> lines = service.parseAdvantages(json).get(0).lines();
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("2.00"), lines.get(0).amount());
    }

    /**
     * The enqueued JSON omits the new fields when they are absent: the
     * declaration is byte-for-byte the one produced before they existed (spec
     * §2/§4, retro-compatibility).
     */
    @Test
    void enqueueSaleOmitsNewFieldsWhenAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, null);
            service.enqueueSale("T1", "C04", LocalDateTime.now(), ONE_ADVANTAGE);
            String json = mc.constructed().get(0).advantagesJson;
            assertEquals("[{\"offerCode\":\"OFF1\",\"amount\":5.00}]", json);
        }
    }

    /**
     * The enqueued JSON carries the accounting code and the derived lines when
     * the response supplies them (spec §2/§4, the present arms).
     */
    @Test
    void enqueueSaleCarriesAccountingCodeAndLinesWhenPresent() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, null);
            service.enqueueSale("T1", "C04", LocalDateTime.now(), ADVANTAGE_WITH_OFFER);
            String json = mc.constructed().get(0).advantagesJson;
            assertTrue(json.contains("\"accountingCode\":\"706100\""));
            assertTrue(json.contains("\"ean\":\"EAN1\""));
            assertTrue(json.contains("\"ean\":\"EAN2\""));
        }
    }

    /**
     * {@code enqueueSale} declares nothing when the valuation carries no engine
     * advantage (no row constructed).
     */
    @Test
    void enqueueSaleDeclaresNothingWithoutAdvantages() {
        try (MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            service.enqueueSale("T1", "C04", LocalDateTime.now(), null);
            assertTrue(mc.constructed().isEmpty());
        }
    }

    /**
     * {@code enqueueSale} is idempotent: an already-enqueued SALE key is not
     * enqueued again.
     */
    @Test
    void enqueueSaleSkipsWhenAlreadyEnqueued() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE,
                    mock(ConsumptionOutbox.class));
            service.enqueueSale("T1", "C04", LocalDateTime.now(), ONE_ADVANTAGE);
            assertTrue(mc.constructed().isEmpty());
        }
    }

    /**
     * {@code enqueueSale} persists a new SALE row carrying the advantages when
     * the key is free.
     */
    @Test
    void enqueueSalePersistsNewDeclaration() {
        LocalDateTime finalizedAt = LocalDateTime.of(2026, 8, 2, 10, 30);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, null);
            service.enqueueSale("T1", "C04", finalizedAt, ONE_ADVANTAGE);
            ConsumptionOutbox row = mc.constructed().get(0);
            verify(row).persist();
            assertEquals("T1", row.ticketNumber);
            assertEquals("C04", row.storeCode);
            assertEquals(finalizedAt, row.finalizedAt);
            assertEquals(ConsumptionOutbox.Direction.SALE, row.direction);
            assertTrue(row.advantagesJson.contains("OFF1"));
        }
    }

    /**
     * {@code enqueueCancellation} does nothing when no SALE was declared for the
     * ticket (the no-sale arm).
     */
    @Test
    void enqueueCancellationSkipsWithoutASale() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, null);
            service.enqueueCancellation("T1", "C04", LocalDateTime.now());
            assertTrue(mc.constructed().isEmpty());
        }
    }

    /**
     * {@code enqueueCancellation} is idempotent: an already-enqueued CANCELLATION
     * is not enqueued again.
     */
    @Test
    void enqueueCancellationSkipsWhenAlreadyEnqueued() {
        ConsumptionOutbox sale = new ConsumptionOutbox();
        sale.advantagesJson = "[{\"offerCode\":\"OFF1\",\"amount\":5.00}]";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, sale);
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.CANCELLATION,
                    mock(ConsumptionOutbox.class));
            service.enqueueCancellation("T1", "C04", LocalDateTime.now());
            assertTrue(mc.constructed().isEmpty());
        }
    }

    /**
     * {@code enqueueCancellation} persists a symmetric CANCELLATION carrying the
     * SALE's advantages when a sale exists and no cancellation does yet.
     */
    @Test
    void enqueueCancellationPersistsSymmetricReversal() {
        ConsumptionOutbox sale = new ConsumptionOutbox();
        sale.advantagesJson = "[{\"offerCode\":\"OFF1\",\"amount\":5.00}]";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<ConsumptionOutbox> mc = mockConstruction(ConsumptionOutbox.class)) {
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.SALE, sale);
            stubKey(panache, "C04", "T1", ConsumptionOutbox.Direction.CANCELLATION, null);
            service.enqueueCancellation("T1", "C04", LocalDateTime.now());
            ConsumptionOutbox row = mc.constructed().get(0);
            verify(row).persist();
            assertEquals(ConsumptionOutbox.Direction.CANCELLATION, row.direction);
            assertEquals(sale.advantagesJson, row.advantagesJson);
        }
    }

    /**
     * {@code pending} projects the pending rows to detached snapshots.
     */
    @Test
    void pendingProjectsSnapshots() {
        ConsumptionOutbox row = new ConsumptionOutbox();
        row.id = 7L;
        row.ticketNumber = "T1";
        row.storeCode = "C04";
        row.direction = ConsumptionOutbox.Direction.SALE;
        row.advantagesJson = "[]";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<ConsumptionOutbox> query = mock(PanacheQuery.class);
            when(query.list()).thenReturn(List.of(row));
            panache.when(() -> ConsumptionOutbox.find("applied = false order by id")).thenReturn(query);
            List<ConsumptionService.PendingConsumption> pending = service.pending();
            assertEquals(1, pending.size());
            assertEquals(7L, pending.get(0).id());
            assertEquals("T1", pending.get(0).ticketNumber());
        }
    }

    /**
     * {@code markApplied} flips the acknowledged flag and clears the error on a
     * found row.
     */
    @Test
    void markAppliedFlagsTheFoundRow() {
        ConsumptionOutbox row = mock(ConsumptionOutbox.class);
        row.lastError = "boom";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ConsumptionOutbox.findById(7L)).thenReturn(row);
            service.markApplied(7L);
            assertTrue(row.applied);
            assertNull(row.lastError);
            verify(row).persist();
        }
    }

    /**
     * {@code markApplied} does nothing when the row is gone (the null arm).
     */
    @Test
    void markAppliedIgnoresAMissingRow() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ConsumptionOutbox.findById(7L)).thenReturn(null);
            service.markApplied(7L);
        }
    }

    /**
     * {@code recordFailure} counts the attempt and stores the error on a found
     * row.
     */
    @Test
    void recordFailureCountsTheAttempt() {
        ConsumptionOutbox row = mock(ConsumptionOutbox.class);
        row.attempts = 2;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ConsumptionOutbox.findById(7L)).thenReturn(row);
            service.recordFailure(7L, "HTTP 500");
            assertEquals(3, row.attempts);
            assertEquals("HTTP 500", row.lastError);
            verify(row).persist();
        }
    }

    /**
     * {@code recordFailure} does nothing when the row is gone (the null arm).
     */
    @Test
    void recordFailureIgnoresAMissingRow() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> ConsumptionOutbox.findById(7L)).thenReturn(null);
            service.recordFailure(7L, "HTTP 500");
        }
    }
}
