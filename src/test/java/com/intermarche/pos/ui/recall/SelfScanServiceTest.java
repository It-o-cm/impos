package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SelfScanService}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5 + Mockito: the settings, the draw and the sale loader are mocks,
 * the register catalog is reached through static Panache mocks, the messages are
 * the French bundle. Branch enumeration: the control decided at the start (total
 * or partial asked by the device, drawn by the rate or not, unknown or absent
 * request) and the partial target (bounded by the basket and at least one); the
 * provoked total control with and without a basket; every arm of a re-scan (no
 * control, declared article with and without label, article the register knows
 * or not, partial confirmation, partial target reached, partial discrepancy on
 * an excess or an undeclared article, total count); the loading (no basket,
 * partial pending, as declared, as re-scanned with its reductions, surplus and
 * undeclared articles priced by the register or not); the abandon and the rows.
 */
class SelfScanServiceTest {

    /**
     * A service over fresh mocks, its draw answering the given number.
     *
     * @param draw what the draw answers
     * @return the service
     */
    private SelfScanService service(int draw) {
        SelfScanService service = new SelfScanService();
        service.i18n = TestMessages.french();
        service.posSettingsService = mock(PosSettingsService.class);
        service.ticketService = mock(TicketService.class);
        service.random = mock(Random.class);
        service.channelGate = mock(ChannelGate.class);
        when(service.random.nextInt(100)).thenReturn(draw);
        when(service.posSettingsService.selfScanPartialControlItems()).thenReturn(3);
        return service;
    }

    /**
     * A basket: two milks on one line, one bread, a free line and a null line.
     *
     * @param control the control the device asks for
     * @return the basket
     */
    private RecalledDocument basket(String control) {
        List<RecalledDocument.Line> lines = new ArrayList<>(Arrays.asList(
                new RecalledDocument.Line("301", "LAIT", new BigDecimal("2"), new BigDecimal("1.00"),
                        new BigDecimal("0.055")),
                new RecalledDocument.Line("302", null, BigDecimal.ONE, new BigDecimal("2.00"),
                        new BigDecimal("0.055")),
                new RecalledDocument.Line(null, "FORFAIT", BigDecimal.ONE, new BigDecimal("5.00"),
                        new BigDecimal("0.2")),
                null));
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", lines, null);
        basket.control = control;
        basket.label = "Panier";
        basket.vouchers = List.of(new RecalledDocument.Voucher("BR1", "BON", new BigDecimal("1.00")));
        return basket;
    }

    /**
     * A product the register knows.
     *
     * @param id its id
     * @param name its name
     * @return the product
     */
    private Product product(long id, String name) {
        Product product = new Product();
        product.id = id;
        product.name = name;
        return product;
    }

    /**
     * Stubs the register's catalog lookup of an EAN.
     *
     * @param panache the static mock
     * @param ean the EAN
     * @param product the product it answers, or null
     */
    @SuppressWarnings("unchecked")
    private void catalog(MockedStatic<PanacheEntityBase> panache, String ean, Product product) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        panache.when(() -> Product.find("ean", ean)).thenReturn(query);
    }

    /**
     * A total control asked by the device is total, whatever the draw.
     */
    @Test
    void aTotalControlAskedByTheDeviceIsTotal() {
        SelfScanService service = service(0);
        PosState state = new PosState();
        SelfScanControl control = service.start(state, basket(" TOTAL "));
        assertTrue(control.isTotal());
        assertSame(control, state.selfScan);
        assertTrue(state.version > 0);
        assertEquals(3, control.partialTarget);
    }

    /**
     * A partial control asked by the device is partial and not drawn.
     */
    @Test
    void aPartialControlAskedByTheDeviceIsPartial() {
        SelfScanService service = service(99);
        SelfScanControl control = service.start(new PosState(), basket("PARTIAL"));
        assertTrue(control.isPartial());
        assertFalse(control.drawn);
    }

    /**
     * Without a request, the store's rate draws a partial control or none; an
     * unknown request reads as none.
     */
    @Test
    void theRateDrawsThePartialControls() {
        SelfScanService drawn = service(9);
        when(drawn.posSettingsService.selfScanPartialControlRate()).thenReturn(10);
        SelfScanControl partial = drawn.start(new PosState(), basket(null));
        assertTrue(partial.isPartial());
        assertTrue(partial.drawn);
        SelfScanService missed = service(10);
        when(missed.posSettingsService.selfScanPartialControlRate()).thenReturn(10);
        SelfScanControl none = missed.start(new PosState(), basket("SOMETIMES"));
        assertEquals(RecalledDocument.CONTROL_NONE, none.mode);
        assertFalse(none.drawn);
    }

    /**
     * The partial target is bounded by the articles of the basket, and is at
     * least one even for a basket without EAN.
     */
    @Test
    void thePartialTargetIsBoundedByTheBasket() {
        SelfScanService service = service(0);
        when(service.posSettingsService.selfScanPartialControlItems()).thenReturn(10);
        assertEquals(3, service.start(new PosState(), basket("PARTIAL")).partialTarget);
        RecalledDocument free = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS2",
                List.of(new RecalledDocument.Line(" ", "VIDE", BigDecimal.ONE, BigDecimal.ONE, null)), null);
        assertEquals(1, service.start(new PosState(), free).partialTarget);
    }

    /**
     * Provoking a total control needs a basket; with one, the control becomes
     * total and says so.
     */
    @Test
    void provokingATotalControl() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        assertFalse(service.provokeTotal(state));
        service.start(state, basket(null));
        assertTrue(service.provokeTotal(state));
        assertTrue(state.selfScan.isTotal());
        assertTrue(state.selfScan.provoked);
        assertTrue(state.selfScan.messageOk);
        assertEquals("CONTRÔLE TOTAL PROVOQUÉ : RE-SCANNER TOUS LES ARTICLES", state.selfScan.message);
    }

    /**
     * Without a control in progress a re-scan counts nothing.
     */
    @Test
    void aRescanWithoutControlCountsNothing() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket(null));
        assertFalse(service.rescan(state, "301"));
        assertTrue(state.selfScan.controlled.isEmpty());
        assertFalse(state.selfScan.messageOk);
    }

    /**
     * A partial control confirms the declared articles up to their quantity,
     * says the progress and then the success.
     */
    @Test
    void aPartialControlConfirmsTheDeclaredArticles() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("PARTIAL"));
        assertTrue(service.rescan(state, " 301 "));
        assertEquals("ARTICLE CONTRÔLÉ : LAIT (1/3)", state.selfScan.message);
        assertTrue(service.rescan(state, "301"));
        assertTrue(service.rescan(state, "302"));
        assertEquals("CONTRÔLE PARTIEL RÉUSSI", state.selfScan.message);
        assertTrue(state.selfScan.isPartialPassed());
        assertEquals("302", state.selfScan.labels.get("302"));
    }

    /**
     * An article re-scanned beyond its declared quantity is a discrepancy: the
     * partial control becomes total.
     */
    @Test
    void anExcessTurnsThePartialControlTotal() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("PARTIAL"));
        service.rescan(state, "302");
        assertTrue(service.rescan(state, "302"));
        assertTrue(state.selfScan.isTotal());
        assertEquals("ÉCART SUR 302 : CONTRÔLE TOTAL", state.selfScan.message);
        assertFalse(state.selfScan.messageOk);
    }

    /**
     * An article the basket does not declare is a discrepancy when the
     * register knows it, and is refused when it does not.
     */
    @Test
    void anUndeclaredArticleTurnsThePartialControlTotal() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("PARTIAL"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            catalog(panache, "999", null);
            catalog(panache, "777", product(7L, "CAFE"));
            assertFalse(service.rescan(state, "999"));
            assertEquals("ARTICLE INCONNU : 999", state.selfScan.message);
            assertTrue(state.selfScan.isPartial());
            assertTrue(service.rescan(state, "777"));
        }
        assertTrue(state.selfScan.isTotal());
        assertEquals("CAFE", state.selfScan.labels.get("777"));
    }

    /**
     * In a total control every re-scan is counted.
     */
    @Test
    void aTotalControlCountsEveryRescan() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("TOTAL"));
        assertTrue(service.rescan(state, "301"));
        assertEquals("ARTICLE COMPTÉ : LAIT", state.selfScan.message);
        assertTrue(state.selfScan.messageOk);
        assertTrue(service.rescan(state, "302"));
        assertEquals("ARTICLE COMPTÉ : 302", state.selfScan.message);
    }

    /**
     * Loading needs a basket, and a partial control done.
     */
    @Test
    void loadingNeedsABasketAndAControlDone() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        assertEquals(SelfScanService.Outcome.NO_BASKET, service.load(state));
        service.start(state, basket("PARTIAL"));
        assertEquals(SelfScanService.Outcome.PARTIAL_PENDING, service.load(state));
        assertEquals("CONTRÔLE PARTIEL INACHEVÉ", state.selfScan.message);
        verify(service.ticketService, never()).loadRecalledDocument(any(), any());
    }

    /**
     * Without a control the basket is loaded as the device declared it, its
     * label, control and vouchers riding along.
     */
    @Test
    void anUncontrolledBasketLoadsAsDeclared() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        RecalledDocument basket = basket(null);
        service.start(state, basket);
        state.selfScanRequested = true;
        assertEquals(SelfScanService.Outcome.LOADED, service.load(state));
        assertFalse(state.selfScanRequested);
        ArgumentCaptor<RecalledDocument> loaded = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(service.ticketService).loadRecalledDocument(eq(state), loaded.capture());
        assertSame(basket.lines, loaded.getValue().lines);
        assertEquals("SS1", loaded.getValue().code);
        assertEquals("Panier", loaded.getValue().label);
        assertEquals(RecalledDocument.CONTROL_NONE, loaded.getValue().control);
        assertSame(basket.vouchers, loaded.getValue().vouchers);
        assertNull(state.selfScan);
    }

    /**
     * After a total control the sale is what was re-scanned: a declared line
     * reduced, a line not re-scanned dropped, the free line kept, the surplus of
     * a declared article at its declared price and an undeclared article at the
     * register's price — or at nothing when the register has no price for it.
     */
    @Test
    void aTotallyControlledBasketLoadsAsRescanned() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        List<RecalledDocument.Line> lines = new ArrayList<>(basket(null).lines);
        lines.add(new RecalledDocument.Line("301", "LAIT", BigDecimal.ONE, new BigDecimal("1.00"),
                new BigDecimal("0.055")));
        lines.add(new RecalledDocument.Line(" ", "SAC", BigDecimal.ONE, new BigDecimal("0.10"), null));
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", lines, null);
        service.start(state, basket);
        service.provokeTotal(state);
        state.selfScan.count("301", "LAIT");
        state.selfScan.count("301", "LAIT");
        state.selfScan.count("301", "LAIT");
        state.selfScan.count("301", "LAIT");
        state.selfScan.count("777", "CAFE");
        state.selfScan.count("888", "THE");
        state.selfScan.controlled.put("666", BigDecimal.ZERO);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("3.50");
        price.vat = new VatRate(1, new BigDecimal("0.2"), "Normal");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            catalog(panache, "777", product(7L, "CAFE"));
            catalog(panache, "888", null);
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(price);
            assertEquals(SelfScanService.Outcome.LOADED, service.load(state));
        }
        ArgumentCaptor<RecalledDocument> loaded = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(service.ticketService).loadRecalledDocument(eq(state), loaded.capture());
        List<RecalledDocument.Line> sold = loaded.getValue().lines;
        assertEquals(7, sold.size());
        assertEquals("SAC", sold.get(3).label());
        sold = new ArrayList<>(sold);
        sold.remove(3);
        assertEquals("301", sold.get(0).ean());
        assertEquals(0, new BigDecimal("2").compareTo(sold.get(0).quantity()));
        assertEquals("FORFAIT", sold.get(1).label());
        assertEquals(0, BigDecimal.ONE.compareTo(sold.get(2).quantity()));
        assertEquals("301", sold.get(3).ean());
        assertEquals(0, BigDecimal.ONE.compareTo(sold.get(3).quantity()));
        assertEquals(new BigDecimal("1.00"), sold.get(3).unitPrice());
        assertEquals("777", sold.get(4).ean());
        assertEquals(new BigDecimal("3.50"), sold.get(4).unitPrice());
        assertEquals(new BigDecimal("0.2"), sold.get(4).vatRate());
        assertEquals("888", sold.get(5).ean());
        assertEquals(BigDecimal.ZERO, sold.get(5).unitPrice());
        assertNull(sold.get(5).vatRate());
        assertEquals(RecalledDocument.CONTROL_TOTAL, loaded.getValue().control);
    }

    /**
     * An undeclared article the register knows without a price is loaded at
     * nothing, its rate left to the register.
     */
    @Test
    void anUndeclaredArticleWithoutPriceLoadsAtNothing() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("TOTAL"));
        state.selfScan.count("777", "CAFE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            catalog(panache, "777", product(7L, "CAFE"));
            prices.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.load(state);
        }
        ArgumentCaptor<RecalledDocument> loaded = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(service.ticketService).loadRecalledDocument(eq(state), loaded.capture());
        RecalledDocument.Line cafe = loaded.getValue().lines.get(loaded.getValue().lines.size() - 1);
        assertEquals("777", cafe.ean());
        assertEquals(BigDecimal.ZERO, cafe.unitPrice());
    }

    /**
     * Putting the basket aside forgets it.
     */
    @Test
    void abandoningForgetsTheBasket() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket(null));
        state.selfScanRequested = true;
        long before = state.version;
        service.abandon(state);
        assertNull(state.selfScan);
        assertFalse(state.selfScanRequested);
        assertTrue(state.version > before);
    }

    /**
     * The rows name every article met, its EAN when it has no label, its
     * declared and re-scanned quantities, and flag a gap only in a total control.
     */
    @Test
    void theRowsOfTheControlScreen() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        service.start(state, basket("PARTIAL"));
        state.selfScan.count("301", "LAIT");
        List<Map<String, String>> partial = service.rows(state.selfScan);
        assertEquals(2, partial.size());
        assertEquals(Map.of("label", "LAIT", "ean", "301", "declared", "2", "controlled", "1", "gap", "false"),
                partial.get(0));
        assertEquals("302", partial.get(1).get("label"));
        state.selfScan.mode = RecalledDocument.CONTROL_TOTAL;
        state.selfScan.count("301", "LAIT");
        List<Map<String, String>> total = service.rows(state.selfScan);
        assertEquals("false", total.get(0).get("gap"));
        assertEquals("true", total.get(1).get("gap"));
    }

    /**
     * BO-10-01-15 to -18, -23: a basket its channel refuses opens no control; the
     * reason is on the band and any basket waiting is dropped.
     */
    @Test
    void aBasketItsChannelRefusesOpensNoControl() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        RecalledDocument basket = basket(null);
        when(service.channelGate.refusal(state, basket)).thenReturn("REFUS");
        state.selfScan = new SelfScanControl(basket(null), RecalledDocument.CONTROL_NONE, 1);
        assertNull(service.start(state, basket));
        assertNull(state.selfScan);
        assertEquals("REFUS", state.ticket.transientError);
    }

    /**
     * BO-10-01-16 / -18 / -40: how the basket was retrieved, the customer it
     * was built for and its date travel to the load, so the channel judges it
     * as it judged the recall.
     */
    @Test
    void theRetrievalOfTheBasketTravelsToItsLoad() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        RecalledDocument basket = basket(null);
        basket.keyed = true;
        basket.byCustomer = true;
        basket.customerNumber = "C9";
        basket.orderDate = "2026-10-24T08:00";
        service.start(state, basket);
        service.load(state);
        ArgumentCaptor<RecalledDocument> loaded = ArgumentCaptor.forClass(RecalledDocument.class);
        verify(service.ticketService).loadRecalledDocument(eq(state), loaded.capture());
        assertTrue(loaded.getValue().keyed);
        assertTrue(loaded.getValue().byCustomer);
        assertEquals("C9", loaded.getValue().customerNumber);
        assertEquals("2026-10-24T08:00", loaded.getValue().orderDate);
    }

    /**
     * Makes the mocked load ring one line, as a real load does.
     *
     * @param service the service whose ticket service rings it
     * @param state the state the line lands on
     */
    private void ringsALine(SelfScanService service, PosState state) {
        org.mockito.Mockito.doAnswer(invocation -> {
            state.ticket.addItem("301", null, "LAIT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
            return null;
        }).when(service.ticketService).loadRecalledDocument(eq(state), org.mockito.ArgumentMatchers.any());
    }

    /**
     * BO-04-01-41: a loaded basket marks the sale self-scanning; an uncontrolled
     * one journals no control, and a register without journal loads all the same.
     */
    @Test
    void aLoadedBasketMarksTheSaleSelfScanning() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        ringsALine(service, state);
        service.start(state, basket(null));
        assertEquals(SelfScanService.Outcome.LOADED, service.load(state));
        assertTrue(state.ticket.selfScanning);
        assertFalse(state.ticket.selfScanControlled);
        assertFalse(state.ticket.selfScanVerified);
        SelfScanService journalled = service(99);
        journalled.technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        PosState other = new PosState();
        other.ticket.selfScanControlled = true;
        ringsALine(journalled, other);
        journalled.start(other, basket(null));
        journalled.load(other);
        assertTrue(other.ticket.selfScanControlled);
        org.mockito.Mockito.verifyNoInteractions(journalled.technicalEventService);
    }

    /**
     * BO-04-01-42: a control the hostess provoked is hers, journalled with her badge.
     */
    @Test
    void aProvokedControlIsTheHostesss() {
        SelfScanService service = service(99);
        service.technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        PosState state = new PosState();
        state.auth.operatorBadgeId = "12341234";
        ringsALine(service, state);
        service.start(state, basket(null));
        service.provokeTotal(state);
        service.load(state);
        assertTrue(state.ticket.selfScanControlled);
        assertFalse(state.ticket.selfScanVerified);
        verify(service.technicalEventService).record(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.SELF_SCAN_CONTROLLED,
                "Contrôle self-scanning décidé par l'hôtesse 12341234", "12341234", null, null, "SS1");
    }

    /**
     * BO-04-01-43: a control the system drew, or the basket required, is the
     * system's; drawn then provoked, it is both.
     */
    @Test
    void aDrawnOrRequiredControlIsTheSystems() {
        SelfScanService drawn = service(0);
        drawn.technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        when(drawn.posSettingsService.selfScanPartialControlRate()).thenReturn(50);
        PosState state = new PosState();
        ringsALine(drawn, state);
        SelfScanControl control = drawn.start(state, basket(null));
        control.partialChecked = control.partialTarget;
        drawn.provokeTotal(state);
        drawn.load(state);
        assertTrue(state.ticket.selfScanVerified);
        assertTrue(state.ticket.selfScanControlled);
        verify(drawn.technicalEventService).record(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.SELF_SCAN_VERIFIED,
                "Vérification self-scanning décidée par le système", null, null, null, "SS1");
        SelfScanService required = service(99);
        PosState total = new PosState();
        ringsALine(required, total);
        required.start(total, basket(RecalledDocument.CONTROL_TOTAL));
        required.load(total);
        assertTrue(total.ticket.selfScanVerified);
        assertFalse(total.ticket.selfScanControlled);
    }

    /**
     * A load that rang nothing leaves the sale as it was.
     */
    @Test
    void aLoadThatRangNothingLeavesTheSaleAlone() {
        SelfScanService service = service(99);
        PosState state = new PosState();
        state.ticket.selfScanVerified = true;
        service.start(state, basket(RecalledDocument.CONTROL_TOTAL));
        service.load(state);
        assertFalse(state.ticket.selfScanning);
        assertTrue(state.ticket.selfScanVerified);
        assertFalse(state.ticket.selfScanControlled);
    }
}
