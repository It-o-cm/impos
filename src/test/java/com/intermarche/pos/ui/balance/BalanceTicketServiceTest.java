package com.intermarche.pos.ui.balance;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.SyncPayloads;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BalanceTicketService}.
 * <p>
 * The service is the register's half of the counter ticket, and its governing rule
 * is negative: when the shop cannot arbitrate, nothing is integrated. Each of the
 * three refusals the client can report — no store node, unreachable, already served
 * — is a distinct message and zero lines, and the register's own memory of what it
 * already picked up refuses a second scan of the same paper before any call leaves
 * the till. On a successful pick-up the lines are added AT THE COUNTER'S OWN TOTAL,
 * quantity one and {@code priceEmbedded}, so the register's total equals the paper
 * the customer holds to the cent; a recalled or unsellable article is named on
 * screen rather than sold. The VAT rate is resolved in four steps (exemption, the
 * counter's rate, the catalog, the default) and the label in three, each covered
 * here. Collaborators are Mockito mocks and the Panache finders are neutralized on
 * their declaring classes.
 */
class BalanceTicketServiceTest {

    /** The reference presented in every test. */
    private static final String REFERENCE = "0012345678";

    /** The register presenting it. */
    private static final String TERMINAL = "CAISSE-01";

    /** The EAN carried by the counter lines. */
    private static final String EAN = "3560070000000";

    /** The injected fallback VAT rate. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a service wired with the supplied client and a named register.
     *
     * @param client the pick-up client mock
     * @return the wired service
     */
    private BalanceTicketService newService(BalanceTicketClient client) {
        BalanceTicketService service = new BalanceTicketService();
        service.balanceTicketClient = client;
        service.ticketNumberService = mock(TicketNumberService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        service.defaultVatRate = DEFAULT_VAT;
        // Catalog default: the counter's own price is booked (LC-06-01-04).
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(true);
        return service;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock, with an empty item list the service can append to.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        ticket.items = new java.util.ArrayList<>();
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        return state;
    }

    /**
     * Builds a one-line counter ticket with the given quantity, total and rate.
     *
     * @param label the counter's own wording, or null
     * @param quantity the weighed quantity, or null
     * @param total the line total including tax, or null
     * @param vatRate the counter's VAT rate, or null
     * @return the served payload
     */
    private SyncPayloads.BalanceTicketDto oneLine(String label, String quantity,
                                                  String total, String vatRate) {
        SyncPayloads.BalanceTicketDto dto = new SyncPayloads.BalanceTicketDto();
        dto.reference = REFERENCE;
        SyncPayloads.BalanceTicketLineDto line = new SyncPayloads.BalanceTicketLineDto();
        line.ean = EAN;
        line.label = label;
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.totalIncludingTax = total == null ? null : new BigDecimal(total);
        line.vatRate = vatRate == null ? null : new BigDecimal(vatRate);
        dto.lines.add(line);
        return dto;
    }

    /**
     * Builds a catalog article addressable by id.
     *
     * @param forbidden the forbidden-to-sale flag
     * @return the assembled product
     */
    private Product newProduct(boolean forbidden) {
        Product product = new Product();
        product.id = 42L;
        product.name = "roti";
        product.ean = EAN;
        product.plu = "1234";
        product.forbiddenToSale = forbidden;
        return product;
    }

    /**
     * Stubs the client to serve the given payload for the reference under test.
     *
     * @param dto the payload the shop serves
     * @return the wired client mock
     */
    private BalanceTicketClient serving(SyncPayloads.BalanceTicketDto dto) {
        BalanceTicketClient client = mock(BalanceTicketClient.class);
        when(client.pickUp(anyString(), anyString()))
                .thenReturn(BalanceTicketClient.Answer.served(dto));
        return client;
    }

    /**
     * Stubs the client to report the given refusal.
     *
     * @param outcome the non-SERVED outcome
     * @return the wired client mock
     */
    private BalanceTicketClient refusing(BalanceTicketClient.Outcome outcome) {
        BalanceTicketClient client = mock(BalanceTicketClient.class);
        when(client.pickUp(anyString(), anyString()))
                .thenReturn(BalanceTicketClient.Answer.failed(outcome));
        return client;
    }

    /**
     * A register that knows no store node refuses and names the manual fallback.
     */
    @Test
    void noStoreNodeIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        boolean added = newService(refusing(BalanceTicketClient.Outcome.NO_STORE_NODE))
                .integrate(state, REFERENCE);
        assertFalse(added);
        verify(ticket).setError(BalanceTicketService.NO_STORE_NODE);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A shop that did not answer refuses: the degraded mode is manual entry, not
     * a blind integration.
     */
    @Test
    void unreachableShopIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        boolean added = newService(refusing(BalanceTicketClient.Outcome.UNREACHABLE))
                .integrate(state, REFERENCE);
        assertFalse(added);
        verify(ticket).setError(BalanceTicketService.UNREACHABLE);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A paper the shop no longer holds — served to another lane — is refused with
     * its own message, distinct from the technical failures.
     */
    @Test
    void alreadyServedElsewhereIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        boolean added = newService(refusing(BalanceTicketClient.Outcome.ALREADY_CONSUMED))
                .integrate(state, REFERENCE);
        assertFalse(added);
        verify(ticket).setError(BalanceTicketService.ALREADY_PICKED_ELSEWHERE);
    }

    /**
     * The second scan of a paper this register already integrated is refused by
     * the local memory, without a call leaving the till.
     */
    @Test
    void secondScanHereIsRefusedWithoutCalling() {
        BalanceTicketClient client = serving(oneLine("ROTI", "0.752", "13.54", "0.055"));
        BalanceTicketService service = newService(client);
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        // The ticket is a mock, so addItem appends nothing: the line the service
        // reads back to flag has to be seeded here.
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "ROTI",
                new BigDecimal("13.54"), BigDecimal.ONE, new BigDecimal("0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
            assertFalse(service.integrate(state, REFERENCE));
        }
        verify(client, times(1)).pickUp(eq(REFERENCE), eq(TERMINAL));
        verify(ticket).setError(BalanceTicketService.ALREADY_PICKED_HERE);
    }

    /**
     * A refused pick-up is NOT remembered: the paper must stay presentable once
     * the shop is reachable again.
     */
    @Test
    void refusedPickUpIsNotRemembered() {
        BalanceTicketClient client = refusing(BalanceTicketClient.Outcome.UNREACHABLE);
        BalanceTicketService service = newService(client);
        PosState state = newState(mock(TicketState.class));
        service.integrate(state, REFERENCE);
        service.integrate(state, REFERENCE);
        verify(client, times(2)).pickUp(eq(REFERENCE), eq(TERMINAL));
    }

    /**
     * A served line is added at the counter's own total, quantity one, carrying
     * the catalog PLU so two weighings never merge — and flagged price-embedded
     * so the valuation engine leaves the amount alone.
     */
    @Test
    void servedLineIsAddedAtTheCounterTotal() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.752", "13.54", "0.055")));
        TicketState.TicketItem added = new TicketState.TicketItem(
                EAN, "1234", "ROTI", new BigDecimal("13.54"), BigDecimal.ONE, new BigDecimal("0.055"));
        ticket.items.add(added);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> quantity = ArgumentCaptor.forClass(BigDecimal.class);
        verify(ticket).addItem(eq(EAN), eq("1234"), eq("ROTI 0.752KG"),
                price.capture(), quantity.capture(), eq(new BigDecimal("0.055")));
        assertEquals(0, new BigDecimal("13.54").compareTo(price.getValue()));
        assertEquals(0, BigDecimal.ONE.compareTo(quantity.getValue()));
        assertTrue(added.priceEmbedded);
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE + " INTÉGRÉ (1 LIGNE(S))");
    }

    /**
     * With no catalog article behind the EAN the line is still integrated — the
     * counter is the authority on what it weighed — carrying no PLU.
     */
    @Test
    void unknownArticleIsStillIntegrated() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, null, "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine(null, null, "5.00", null)));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(eq(EAN), eq(null), eq("ARTICLE COMPTOIR"),
                any(), any(), eq(DEFAULT_VAT));
    }

    /**
     * A line whose only article is forbidden to sale leaves the ticket empty:
     * the whole pick-up is refused and the article named.
     */
    @Test
    void forbiddenArticleEmptiesThePickUp() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.5", "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(true));
            assertFalse(service.integrate(state, REFERENCE));
        }
        verify(ticket).setError("TICKET COMPTOIR REFUSÉ : ROTI 0.500KG");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * One refused article among several does not lose the rest: the sellable
     * lines are added and the withdrawn one is named on screen.
     */
    @Test
    void refusedArticleAmongOthersIsNamedAndTheRestKept() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        SyncPayloads.BalanceTicketDto dto = oneLine("ROTI", "0.5", "5.00", "0.055");
        SyncPayloads.BalanceTicketLineDto second = new SyncPayloads.BalanceTicketLineDto();
        second.ean = "3560070000001";
        second.label = "JAMBON";
        second.quantity = new BigDecimal("0.2");
        second.totalIncludingTax = new BigDecimal("3.00");
        second.vatRate = new BigDecimal("0.055");
        dto.lines.add(second);
        ticket.items.add(new TicketState.TicketItem(second.ean, null, "JAMBON",
                new BigDecimal("3.00"), BigDecimal.ONE, second.vatRate));
        BalanceTicketService service = newService(serving(dto));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(true));
            products.when(() -> Product.findActiveByEan("3560070000001")).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(eq("3560070000001"), eq(null), eq("JAMBON 0.200KG"),
                any(), any(), eq(new BigDecimal("0.055")));
        verify(ticket).setError("ARTICLE RETIRÉ DU COMPTOIR : ROTI 0.500KG");
        verify(ticket, never()).setNotice(anyString());
    }

    /**
     * The counter's own VAT rate wins over the catalog when it sent one.
     */
    @Test
    void counterVatRateWins() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), any(), any(), any(), eq(new BigDecimal("0.055")));
    }

    /**
     * With no rate from the counter, the current catalog price gives it.
     */
    @Test
    void catalogVatRateIsTheSecondSource() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        Price price = new Price();
        price.vatRate = new BigDecimal("0.10");
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, "5.00", null)));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), any(), any(), any(), eq(new BigDecimal("0.10")));
    }

    /**
     * With neither a counter rate nor a catalog price, the configured default
     * applies.
     */
    @Test
    void defaultVatRateIsTheLastSource() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, "5.00", null)));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), any(), any(), any(), eq(DEFAULT_VAT));
    }

    /**
     * A missing total is booked at zero rather than throwing: the line stays
     * visible and the cashier can correct it.
     */
    @Test
    void missingTotalIsBookedAtZero() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, null, "0.055")));
        ArgumentCaptor<BigDecimal> price = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), any(), price.capture(), any(), any());
        assertEquals(0, BigDecimal.ZERO.compareTo(price.getValue()));
    }

    /**
     * A zero or absent quantity leaves the label bare: only a real weighing is
     * worth printing next to the article.
     */
    @Test
    void zeroQuantityLeavesTheLabelBare() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("roti", "0", "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), eq("ROTI"), any(), any(), any());
    }

    /**
     * With no wording from the counter, the catalog's sale label is used.
     */
    @Test
    void catalogLabelIsUsedWhenTheCounterSentNone() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("  ", null, "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            service.integrate(state, REFERENCE);
        }
        verify(ticket).addItem(any(), any(), eq("ROTI"), any(), any(), any());
    }

    // --------------------------------------------------
    // Price source (LC-06-01-04)
    // --------------------------------------------------

    /**
     * With the catalog price chosen, the line is RE-PRICED from the weight the
     * counter reported at the register's own price per kilogram, and it is NOT
     * flagged price-embedded — locking it would shut it out of the very promotions
     * the shop turned this option on to get.
     */
    @Test
    void catalogPriceReValuesTheWeighedLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        TicketState.TicketItem added = new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT);
        ticket.items.add(added);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.500", "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("20.00");
        price.vatRate = new BigDecimal("0.055");
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("10.00").compareTo(booked.getValue()));
        assertFalse(added.priceEmbedded);
    }

    /**
     * With the catalog price chosen but NO current price in the catalog, the
     * counter's total is booked instead: half a rule applied is worse than the rule
     * the operator can read off the paper.
     */
    @Test
    void catalogPriceFallsBackWithoutACatalogPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.500", "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("13.54").compareTo(booked.getValue()));
    }

    /**
     * With the catalog price chosen but no weight reported, there is nothing to
     * re-price: the counter's total is booked — the second leg of the same fallback.
     */
    @Test
    void catalogPriceFallsBackWithoutAWeight() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("20.00");
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("13.54").compareTo(booked.getValue()));
    }

    /**
     * With the catalog price chosen and an article the catalog does not know, the
     * counter's total is booked AND flagged price-embedded: there is no catalog
     * price to prefer, and the counter is the only authority left.
     */
    @Test
    void catalogPriceKeepsTheCounterTotalForAnUnknownArticle() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        TicketState.TicketItem added = new TicketState.TicketItem(EAN, null, "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT);
        ticket.items.add(added);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.500", "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("13.54").compareTo(booked.getValue()));
        assertTrue(added.priceEmbedded);
    }

    // --------------------------------------------------
    // Missed-branch fill
    // --------------------------------------------------

    /**
     * A null reference is not held by the local memory (the {@code reference != null}
     * guard's false arm) so the pick-up proceeds and the served line is integrated.
     */
    @Test
    void nullReferenceIsNotRefusedByMemory() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, null, "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketClient client = mock(BalanceTicketClient.class);
        when(client.pickUp(any(), anyString()))
                .thenReturn(BalanceTicketClient.Answer.served(oneLine("ROTI", null, "5.00", "0.055")));
        BalanceTicketService service = newService(client);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(null);
            assertTrue(service.integrate(state, null));
        }
        verify(ticket).addItem(eq(EAN), eq(null), eq("ROTI"), any(), any(), any());
        verify(ticket).setNotice("TICKET COMPTOIR null INTÉGRÉ (1 LIGNE(S))");
    }

    /**
     * A line carrying no EAN takes the {@code line.ean == null ? null} arm without
     * touching the catalog, and is still integrated as an unknown article.
     */
    @Test
    void lineWithoutEanSkipsTheCatalogLookup() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(null, null, "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        SyncPayloads.BalanceTicketDto dto = oneLine("ROTI", null, "5.00", "0.055");
        dto.lines.get(0).ean = null;
        BalanceTicketService service = newService(serving(dto));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            assertTrue(service.integrate(state, REFERENCE));
            products.verifyNoInteractions();
        }
        verify(ticket).addItem(eq(null), eq(null), eq("ROTI"), any(), any(), eq(new BigDecimal("0.055")));
    }

    /**
     * A recalled but sellable article takes the {@code recall} arm of the refusal
     * guard: the line is withdrawn and named, and the empty pick-up is refused.
     */
    @Test
    void recalledArticleIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.5", "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<com.intermarche.pos.domain.catalog.attribute.ProductAttributes> attrs =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.catalog.attribute.ProductAttributes.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            attrs.when(() -> com.intermarche.pos.domain.catalog.attribute.ProductAttributes.recall(any())).thenReturn(true);
            assertFalse(service.integrate(state, REFERENCE));
        }
        verify(ticket).setError("TICKET COMPTOIR REFUSÉ : ROTI 0.500KG");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * When {@code addItem} appends nothing (the mock ticket stays empty), the
     * {@code items.isEmpty()} true arm returns before any flag is read back, yet the
     * line still counts as added and the notice is issued.
     */
    @Test
    void emptyTicketAfterAddIsNotFlagged() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.5", "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(eq(EAN), eq("1234"), eq("ROTI 0.500KG"), any(), any(), any());
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE + " INTÉGRÉ (1 LIGNE(S))");
    }

    /**
     * A discount-banned article takes the {@code discountForbidden} true arm and the
     * ban is snapshotted onto the freshly added line (BO-02-03-09).
     */
    @Test
    void discountForbiddenIsSnapshotted() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        TicketState.TicketItem added = new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT);
        ticket.items.add(added);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.5", "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<com.intermarche.pos.domain.catalog.attribute.ProductAttributes> attrs =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.catalog.attribute.ProductAttributes.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            attrs.when(() -> com.intermarche.pos.domain.catalog.attribute.ProductAttributes.discountForbidden(any())).thenReturn(true);
            assertTrue(service.integrate(state, REFERENCE));
        }
        assertTrue(added.discountForbidden);
    }

    /**
     * With the catalog price chosen and a current price whose amount is null, the
     * {@code priceIncludingTax == null} arm falls back to the counter's own total.
     */
    @Test
    void catalogPriceFallsBackWithoutAPriceAmount() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.500", "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        Price price = new Price();
        price.priceIncludingTax = null;
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("13.54").compareTo(booked.getValue()));
    }

    /**
     * With the catalog price chosen and a non-positive reported weight, the
     * {@code quantity.signum() <= 0} arm falls back to the counter's own total.
     */
    @Test
    void catalogPriceFallsBackWithNonPositiveWeight() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0", "13.54", "0.055")));
        when(service.posSettingsService.balanceCounterPrice()).thenReturn(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("20.00");
        ArgumentCaptor<BigDecimal> booked = ArgumentCaptor.forClass(BigDecimal.class);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), booked.capture(), any(), any());
        assertEquals(0, new BigDecimal("13.54").compareTo(booked.getValue()));
    }

    /**
     * A VAT-exempt article takes the {@code vatExempt} true arm and the line is
     * captured at a zero rate, outranking the counter's own rate (BO-02-03-26/27).
     */
    @Test
    void vatExemptArticleGetsZeroRate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine("ROTI", null, "5.00", "0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<com.intermarche.pos.domain.catalog.attribute.ProductAttributes> attrs =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.catalog.attribute.ProductAttributes.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            attrs.when(() -> com.intermarche.pos.domain.catalog.attribute.ProductAttributes.vatExempt(any())).thenReturn(true);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).addItem(any(), any(), any(), any(), any(), eq(BigDecimal.ZERO));
    }

    /**
     * Past the memory size the {@code while} loop's true arm forgets the oldest
     * reference: re-scanning it calls the shop again instead of being refused by
     * the local memory.
     */
    @Test
    void memoryForgetsOldestBeyondLimit() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketClient client = serving(oneLine("ROTI", "0.5", "5.00", "0.055"));
        BalanceTicketService service = newService(client);
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            for (int i = 0; i <= 500; i++) {
                assertTrue(service.integrate(state, "R" + i));
            }
            assertTrue(service.integrate(state, "R0"));
        }
        verify(client, times(2)).pickUp(eq("R0"), eq(TERMINAL));
    }

    /**
     * A counter line names no lot, so an article under lot recall has its lots
     * APPENDED to the integration message rather than announced on its own
     * ({@code LC-02-03-13}): the register has one message area, and a separate notice
     * would erase the reference the operator checks the paper against
     * ({@code LC-06-01-04}).
     */
    @Test
    void aRecalledLotIsAppendedToTheIntegrationMessage() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.752", "13.54", "0.055")));
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "ROTI",
                new BigDecimal("13.54"), BigDecimal.ONE, new BigDecimal("0.055")));
        Product product = newProduct(false);
        product.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123;L456");
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(product);
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE
                + " INTÉGRÉ (1 LIGNE(S)) — RAPPEL : ROTI 0.752KG (L123, L456)");
    }

    /**
     * A paper carrying SEVERAL articles under lot recall names them all: collecting
     * them through the loop is what a line-by-line message could not do, since only
     * the last of them would have survived.
     */
    @Test
    void severalRecalledArticlesAreAllNamed() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        SyncPayloads.BalanceTicketDto dto = oneLine("ROTI", "0.5", "5.00", "0.055");
        SyncPayloads.BalanceTicketLineDto second = new SyncPayloads.BalanceTicketLineDto();
        second.ean = "3560070000001";
        second.label = "JAMBON";
        second.quantity = new BigDecimal("0.2");
        second.totalIncludingTax = new BigDecimal("3.00");
        second.vatRate = new BigDecimal("0.055");
        dto.lines.add(second);
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "ROTI",
                new BigDecimal("5.00"), BigDecimal.ONE, second.vatRate));
        ticket.items.add(new TicketState.TicketItem(second.ean, null, "JAMBON",
                new BigDecimal("3.00"), BigDecimal.ONE, second.vatRate));
        Product first = newProduct(false);
        first.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123");
        Product other = newProduct(false);
        other.ean = second.ean;
        other.plu = null;
        other.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L789");
        BalanceTicketService service = newService(serving(dto));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(first);
            products.when(() -> Product.findActiveByEan(second.ean)).thenReturn(other);
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE
                + " INTÉGRÉ (2 LIGNE(S)) — RAPPEL : ROTI 0.500KG (L123), JAMBON 0.200KG (L789)");
    }

    /**
     * The recall clause rides on the WITHDRAWAL message too when one article was
     * refused and another is under lot recall: both facts reach the operator, and
     * neither erases the other.
     */
    @Test
    void theRecallClauseRidesOnTheWithdrawalMessageToo() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        SyncPayloads.BalanceTicketDto dto = oneLine("ROTI", "0.5", "5.00", "0.055");
        SyncPayloads.BalanceTicketLineDto second = new SyncPayloads.BalanceTicketLineDto();
        second.ean = "3560070000001";
        second.label = "JAMBON";
        second.quantity = new BigDecimal("0.2");
        second.totalIncludingTax = new BigDecimal("3.00");
        second.vatRate = new BigDecimal("0.055");
        dto.lines.add(second);
        ticket.items.add(new TicketState.TicketItem(second.ean, null, "JAMBON",
                new BigDecimal("3.00"), BigDecimal.ONE, second.vatRate));
        Product other = newProduct(false);
        other.ean = second.ean;
        other.plu = null;
        other.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L789");
        BalanceTicketService service = newService(serving(dto));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(true));
            products.when(() -> Product.findActiveByEan(second.ean)).thenReturn(other);
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).setError("ARTICLE RETIRÉ DU COMPTOIR : ROTI 0.500KG"
                + " — RAPPEL : JAMBON 0.200KG (L789)");
    }

    /**
     * A paper whose every line is refused says only that: there is no integration to
     * append a recall to, and the articles that were kept out are not sold at all.
     */
    @Test
    void anEntirelyRefusedPaperCarriesNoRecallClause() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.5", "5.00", "0.055")));
        Product product = newProduct(true);
        product.attributes.put(com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123");
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(product);
            assertFalse(service.integrate(state, REFERENCE));
        }
        verify(ticket).setError("TICKET COMPTOIR REFUSÉ : ROTI 0.500KG");
    }

    /**
     * A paper carrying no article under lot recall keeps the message it always had —
     * the arm that keeps the clause off every ordinary pick-up, whether the article is
     * known to the catalog or not.
     */
    @Test
    void anOrdinaryPaperKeepsItsPlainMessage() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        BalanceTicketService service = newService(serving(oneLine("ROTI", "0.752", "13.54", "0.055")));
        ticket.items.add(new TicketState.TicketItem(EAN, "1234", "ROTI",
                new BigDecimal("13.54"), BigDecimal.ONE, new BigDecimal("0.055")));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class);
             MockedStatic<Price> prices = org.mockito.Mockito.mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(newProduct(false));
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE + " INTÉGRÉ (1 LIGNE(S))");
    }

    /**
     * An article the catalog does not know carries no recall either: there is no
     * referential entry to read lots from — the null-product arm of the collection.
     */
    @Test
    void anUnknownArticleCarriesNoRecallClause() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.items.add(new TicketState.TicketItem(EAN, null, "X",
                BigDecimal.ONE, BigDecimal.ONE, DEFAULT_VAT));
        BalanceTicketService service = newService(serving(oneLine(null, null, "5.00", null)));
        try (MockedStatic<Product> products = org.mockito.Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan(EAN)).thenReturn(null);
            assertTrue(service.integrate(state, REFERENCE));
        }
        verify(ticket).setNotice("TICKET COMPTOIR " + REFERENCE + " INTÉGRÉ (1 LIGNE(S))");
    }
}
