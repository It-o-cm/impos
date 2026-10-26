package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RangePurposeScanHandler} (BO-03-06-60, -61, -43 and
 * -41).
 *
 * <p>Plain JUnit 5 + Mockito, no Quarkus and no database: the two Panache
 * finders are neutralized on their declaring classes with
 * {@link org.mockito.Mockito#mockStatic}, the register state and its ticket are
 * mocks, and the administered permissions come from a mocked
 * {@link PosSettingsService}. The operator messages come from the French bundle,
 * so every assertion keeps the literal sentence the register shows (LC-13-06).
 *
 * <p>Branch enumeration: the already-handled short circuit; a null and a blank
 * code; no administered range; a range administered as ARTICLE and one
 * administered as BALANCE_TICKET, both left to the handlers that own them; the
 * check-digit guard, both arms; the tare path with a readable weight, an
 * unreadable one and a zero one; the return path with a price-carrying range and
 * a weight-carrying one, with and without a catalog price, with a zero weight,
 * with each of the two administered permissions refusing, with an unknown
 * article, and with the return mode already on so its restoration is seen both
 * ways; the promotion path (BO-03-06-43) with a name armed, a name already
 * armed, and a promotion range naming nothing at all; and the external-lookup
 * path (BO-03-06-41) with an answer naming a catalog EAN, an answer naming a
 * PLU, an answer the catalog does not know, no answer at all, an absent client
 * and a range naming no service.
 */
class RangePurposeScanHandlerTest {

    /** The stripped article code the fixtures name. */
    private static final String ARTICLE = "1234";

    /** The product EAN carried by every line. */
    private static final String EAN = "3560070000000";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /** A REFUND code carrying a price: article 01234, 150 cents, check digit 9. */
    private static final String REFUND_PRICE_CODE = "2101234001509";

    /** A REFUND code carrying a weight: article 01234, 500 grams, check digit 2. */
    private static final String REFUND_WEIGHT_CODE = "2101234005002";

    /** A REFUND code whose value segment carries nothing, check digit 7. */
    private static final String REFUND_ZERO_CODE = "2101234000007";

    /** A TARE code carrying 250 grams, check digit 4. */
    private static final String TARE_CODE = "2100000002504";

    /** A TARE code carrying no weight at all, check digit 5. */
    private static final String TARE_ZERO_CODE = "2100000000005";

    /** The 250-gram tare layout with the RIGHT EAN13 check digit. */
    private static final String TARE_GOOD_DIGIT = "2101234002506";

    /** The same layout with a WRONG check digit. */
    private static final String TARE_BAD_DIGIT = "2101234002509";

    /**
     * Builds a handler wired with the French bundle, permissive settings and the
     * fallback VAT.
     *
     * @return a ready-to-test handler
     */
    private RangePurposeScanHandler newHandler() {
        RangePurposeScanHandler handler = new RangePurposeScanHandler();
        handler.i18n = TestMessages.french();
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.returnWithoutOriginAllowed()).thenReturn(true);
        when(handler.posSettingsService.returnInSaleAllowed()).thenReturn(true);
        handler.rangeLookupClient = mock(RangeLookupClient.class);
        handler.orderCodeScanHandler = mock(OrderCodeScanHandler.class);
        handler.defaultVatRate = DEFAULT_VAT;
        return handler;
    }

    /**
     * Assembles a mock register state over the given ticket mock, with an empty
     * line list — a return rung on an empty ticket is the ordinary case.
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
     * Builds an administered range with the given destination, laid out as
     * {@code PP AAAAA VVVVV K} and carrying the pattern the node ships.
     *
     * @param purpose what the register does with a code of the range
     * @param source what the value segment carries
     * @return the administered range
     */
    private ArticleBarcodeRange administered(ArticleBarcodeRange.Purpose purpose,
                                             ArticleBarcodeRange.ValueSource source) {
        ArticleBarcodeRange range = new ArticleBarcodeRange();
        range.code = "PLAN_" + purpose;
        range.label = "Plan " + purpose;
        range.prefix = "21";
        range.codeLength = 13;
        range.codeKind = com.intermarche.pos.domain.barcode.CouponField.Kind.NUMERIC;
        range.articlePosition = 2;
        range.articleLength = 5;
        range.valueSource = source;
        range.valuePosition = 7;
        range.valueLength = 5;
        range.valueDecimals = source == ArticleBarcodeRange.ValueSource.PRICE ? 2 : 3;
        range.checkDigit = false;
        range.purpose = purpose;
        range.matchPattern = "^21\\d{11}$";
        return range;
    }

    /**
     * Opens a static mock answering the given range for every code.
     *
     * @param range the administered range, or null for none
     * @return the static mock
     */
    private MockedStatic<ArticleBarcodeRange> ranges(ArticleBarcodeRange range) {
        MockedStatic<ArticleBarcodeRange> mocked = mockStatic(ArticleBarcodeRange.class);
        mocked.when(() -> ArticleBarcodeRange.findMatching(any())).thenReturn(range);
        return mocked;
    }

    /**
     * Builds a product addressable by the fixtures' id.
     *
     * @return the assembled product
     */
    private Product newProduct() {
        Product product = new Product();
        product.id = 42L;
        product.name = "banane";
        product.ean = EAN;
        return product;
    }

    /**
     * Builds a Panache query answering the given product as its first result.
     *
     * <p>Answer-based rather than stubbed with {@code when}: building a mock
     * inside another mock's stubbing is what Mockito reports as an unfinished
     * stubbing.
     *
     * @param result the product the query answers, or null for none
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Product> query(Product result) {
        return mock(PanacheQuery.class, invocation ->
                "firstResult".equals(invocation.getMethod().getName()) ? result : null);
    }

    /**
     * Builds a current price with the given tax-inclusive amount and VAT.
     *
     * @param including the price including tax
     * @param vat the VAT rate
     * @return the assembled price
     */
    private Price newPrice(String including, String vat) {
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal(including);
        price.vat = new VatRate(1, new BigDecimal(vat), "Taux normal");
        return price;
    }

    /**
     * An already-handled context short-circuits, untouched.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
    }

    /**
     * A null and a blank code are not this handler's business, and neither
     * reaches the range finder.
     */
    @Test
    void nullAndBlankCodesAreNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = newHandler();
        ScanContext nullCode = new ScanContext(null, state);
        handler.handle(nullCode);
        ScanContext blankCode = new ScanContext("   ", state);
        handler.handle(blankCode);
        assertFalse(nullCode.handled);
        assertFalse(blankCode.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A code no administered range recognizes walks on down the chain.
     */
    @Test
    void anUnadministeredCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(null)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
    }

    /**
     * An ARTICLE range and a BALANCE_TICKET range are both left alone: the first
     * belongs to {@link WeightedEanScanHandler}, the second to
     * {@link BalanceTicketScanHandler}.
     */
    @Test
    void theRangesOwnedByOtherHandlersAreLeftAlone() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext article = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.ARTICLE,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            newHandler().handle(article);
        }
        ScanContext bag = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.BALANCE_TICKET,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            newHandler().handle(bag);
        }
        assertFalse(article.handled);
        assertFalse(bag.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A range whose check digit is administered refuses a code failing it, and
     * accepts the same layout once the digit is right (both arms of the guard).
     */
    @Test
    void theAdministeredCheckDigitIsHonoured() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        range.checkDigit = true;
        ScanContext wrong = new ScanContext(TARE_BAD_DIGIT, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(wrong);
        }
        assertFalse(wrong.handled);
        ScanContext right = new ScanContext(TARE_GOOD_DIGIT, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(right);
        }
        assertTrue(right.handled);
        assertEquals(new BigDecimal("0.250"), ticket.pendingTareKg);
    }

    /**
     * A TARE code announces the container weight, which waits on the ticket for
     * the next weighed line (BO-03-06-60).
     */
    @Test
    void aTareCodeAnnouncesTheContainerWeight() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(TARE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.TARE,
                        ArticleBarcodeRange.ValueSource.WEIGHT))) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertEquals(new BigDecimal("0.250"), ticket.pendingTareKg);
        verify(ticket).setNotice("TARE 0.250 KG PRISE EN COMPTE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A tare of zero and a tare whose segment carries no number are refused
     * rather than taken as nothing (both legs of the value guard).
     */
    @Test
    void anUnreadableOrZeroTareIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        ScanContext zero = new ScanContext(TARE_ZERO_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(zero);
        }
        assertTrue(zero.handled);
        assertNull(ticket.pendingTareKg);
        ArticleBarcodeRange unreadable = administered(ArticleBarcodeRange.Purpose.TARE,
                ArticleBarcodeRange.ValueSource.WEIGHT);
        unreadable.valuePosition = 40;
        ScanContext broken = new ScanContext(TARE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(unreadable)) {
            newHandler().handle(broken);
        }
        assertTrue(broken.handled);
        assertNull(ticket.pendingTareKg);
        verify(ticket, org.mockito.Mockito.times(2)).setError("TARE ILLISIBLE");
    }

    /**
     * A REFUND code rings the article it names, at the price it carries, as a
     * return — through the one funnel every entry goes through, with the return
     * mode armed for that entry only (BO-03-06-61).
     */
    @Test
    void aRefundCodeRingsTheArticleBackAtItsOwnPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        state.returnMode = false;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("1.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        assertFalse(state.returnMode);
    }

    /**
     * A register already in return mode is left in return mode: the arming is a
     * restoration, not a toggle (the other arm of the same guard).
     */
    @Test
    void aRegisterAlreadyInReturnModeStaysInIt() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        state.returnMode = true;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        assertTrue(state.returnMode);
    }

    /**
     * A REFUND range carrying a WEIGHT gives the article back at the price the
     * shop charges for it, for the weight the code states — and with the
     * fallback VAT when the catalog carries no current price (both legs of the
     * price and VAT ternaries).
     */
    @Test
    void aWeightCarryingRefundCodeUsesTheCatalogPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("12.00", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("12.00")), eq(new BigDecimal("0.500")),
                eq(new BigDecimal("0.055")));
    }

    /**
     * Without a catalog price the return falls back to a zero unit price and the
     * injected default VAT.
     */
    @Test
    void aRefundWithoutACatalogPriceFallsBack() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
    }

    /**
     * A weight-carrying REFUND code stating no weight is refused: a line
     * weighing nothing is not a return.
     */
    @Test
    void aRefundCodeWithoutWeightIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_ZERO_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.WEIGHT))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("12.00", "0.055"));
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("POIDS INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A store that forbids taking an article back without its origin ticket
     * forbids it here too: the barcode is not a way around the setting
     * (LC-05-02-09/14).
     */
    @Test
    void aStoreForbiddingReturnsWithoutOriginRefusesTheCode() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = newHandler();
        when(handler.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.REFUND,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A store that forbids a return inside a ticket carrying sales refuses the
     * code on a started ticket, and accepts it on an empty one (both legs of the
     * compound guard).
     */
    @Test
    void aStoreForbiddingReturnsInASaleRefusesOnlyAStartedTicket() {
        TicketState started = mock(TicketState.class);
        PosState startedState = newState(started);
        started.items.add(new TicketState.TicketItem());
        RangePurposeScanHandler handler = newHandler();
        when(handler.posSettingsService.returnInSaleAllowed()).thenReturn(false);
        ScanContext onSale = new ScanContext(REFUND_PRICE_CODE, startedState);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(
                administered(ArticleBarcodeRange.Purpose.REFUND,
                        ArticleBarcodeRange.ValueSource.PRICE))) {
            handler.handle(onSale);
        }
        assertTrue(onSale.handled);
        verify(started).setError("RETOUR DANS UN TICKET DE VENTE INTERDIT");

        TicketState empty = mock(TicketState.class);
        PosState emptyState = newState(empty);
        ScanContext onEmpty = new ScanContext(REFUND_PRICE_CODE, emptyState);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            handler.handle(onEmpty);
        }
        assertTrue(onEmpty.handled);
        verify(empty).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("1.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
    }

    /**
     * A REFUND code naming no active article raises the cashier error and
     * consumes the code.
     */
    @Test
    void aRefundCodeNamingNoArticleIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE BALANCE INTROUVABLE (1234)");
    }

    /**
     * A REFUND range whose segments fall outside the code reads nothing and
     * lets the code walk on, rather than refunding an article nobody named.
     */
    @Test
    void aRefundCodeWhoseSegmentsCannotBeReadIsNotConsumed() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.REFUND,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 40;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A PROMOTION range arms the promotion it names on the ticket and says so,
     * without ringing a line: a promotion is not an article (BO-03-06-43).
     */
    @Test
    void aPromotionCodeArmsTheNamedPromotion() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.PROMOTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.promotionCode = "PROMO_RENTREE";
        when(ticket.armPromotion("PROMO_RENTREE")).thenReturn(true);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).armPromotion("PROMO_RENTREE");
        verify(ticket).setNotice("PROMOTION PROMO_RENTREE DECLENCHEE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * The same promotion scanned twice is a CAUTION and not a refusal: the
     * gesture went through the first time, so the customer is told rather than
     * blocked (BO-03-06-43).
     */
    @Test
    void aPromotionAlreadyArmedIsAWarningAndNotARefusal() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.PROMOTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.promotionCode = "PROMO_RENTREE";
        when(ticket.armPromotion("PROMO_RENTREE")).thenReturn(false);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setWarning("PROMOTION PROMO_RENTREE DEJA DECLENCHEE");
        verify(ticket, never()).setNotice(any());
    }

    /**
     * A PROMOTION range naming NO promotion is an incomplete administered row:
     * it is refused loudly, and nothing is armed — the engine would have
     * nothing to resolve (BO-03-06-43, both the null and the blank name).
     */
    @Test
    void aPromotionRangeNamingNothingIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange nameless = administered(ArticleBarcodeRange.Purpose.PROMOTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        nameless.promotionCode = null;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(nameless)) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        ArticleBarcodeRange blank = administered(ArticleBarcodeRange.Purpose.PROMOTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        blank.promotionCode = "   ";
        ScanContext blankCtx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(blank)) {
            newHandler().handle(blankCtx);
        }
        assertTrue(blankCtx.handled);
        verify(ticket, org.mockito.Mockito.times(2))
                .setError("PROMOTION NON PARAMETREE SUR CETTE PLAGE");
        verify(ticket, never()).armPromotion(any());
    }

    /**
     * An EXTERNAL_LOOKUP range asks the administered service and RINGS the
     * article the answer names, at the shop's own price: the third party said
     * WHAT the code is, the shop still says what it costs (BO-03-06-41, the
     * EAN arm of the resolution).
     */
    @Test
    void anExternalAnswerNamingAnEanRingsTheArticle() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partenaire.test/bons/{code}";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE))
                .thenReturn(EAN);
        org.mockito.Mockito.doAnswer(call -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product product = newProduct();
        product.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        PanacheQuery<Product> found = query(product);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            panache.when(() -> Product.find("ean = ?1 and active = true", EAN))
                    .thenReturn(found);
            prices.when(() -> Price.findCurrentPrice(42L))
                    .thenReturn(newPrice("3.50", "0.0550"));
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).snapshotMealVoucherEligibility(product.ean, true);
        verify(ticket).snapshotRestrictedTenders(product.ean, "MEAL_VOUCHER_ELIGIBLE");
        verify(ticket).addItem(eq(EAN), eq(null), eq("BANANE"),
                eq(new BigDecimal("3.50")), eq(BigDecimal.ONE), eq(new BigDecimal("0.0550")));
    }

    /**
     * The answer may name a PLU rather than an EAN, and the price may be one
     * the catalog does not carry: the line is then rung at zero and at the
     * fallback VAT, which is what every other handler does with a priceless
     * article (the PLU arm and the two null-price arms).
     */
    @Test
    void anExternalAnswerNamingAPluRingsItAtTheFallbackPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partenaire.test/bons?c=";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE))
                .thenReturn(ARTICLE);
        org.mockito.Mockito.doAnswer(call -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product product = newProduct();
        product.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        PanacheQuery<Product> none = query(null);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            panache.when(() -> Product.find("ean = ?1 and active = true", ARTICLE))
                    .thenReturn(none);
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(product);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).snapshotMealVoucherEligibility(product.ean, true);
        verify(ticket).addItem(eq(EAN), eq(null), eq("BANANE"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * An answer the catalog knows nothing about is SHOWN AS IT CAME: a partner
     * refusing a voucher writes the sentence the customer needs to hear, and
     * the register has no business paraphrasing it (BO-03-06-41).
     */
    @Test
    void anExternalAnswerNamingNoArticleIsShownAsItCame() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partenaire.test/bons?c=";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE))
                .thenReturn("BON DEJA UTILISE LE 03/09");
        PanacheQuery<Product> none = query(null);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            panache.when(() -> Product.find("ean = ?1 and active = true",
                    "BON DEJA UTILISE LE 03/09")).thenReturn(none);
            products.when(() -> Product.findActiveByPlu("BON DEJA UTILISE LE 03/09"))
                    .thenReturn(null);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setNotice("BON DEJA UTILISE LE 03/09");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * No answer at all — an outage, a timeout, a refusal — is told plainly and
     * the code is CONSUMED: letting it fall through to "unknown code" would be
     * a lie about what happened. An absent client (outside CDI) behaves the
     * same way (both legs of the answer guard).
     */
    @Test
    void anUnreachableServiceIsToldPlainlyAndConsumesTheCode() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partenaire.test/bons?c=";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(any(), any())).thenReturn(null);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);

        RangePurposeScanHandler noClient = newHandler();
        noClient.rangeLookupClient = null;
        ScanContext bare = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            noClient.handle(bare);
        }
        assertTrue(bare.handled);
        verify(ticket, org.mockito.Mockito.times(2))
                .setError("SERVICE EXTERNE INJOIGNABLE");
    }

    /**
     * An EXTERNAL_LOOKUP range naming NO service asks nobody and says so: the
     * administered row is incomplete, and swallowing the code in silence would
     * leave the operator with no idea why (BO-03-06-41, both the null and the
     * blank URL).
     */
    @Test
    void anExternalRangeNamingNoServiceIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange nameless = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        nameless.lookupUrl = null;
        RangePurposeScanHandler handler = newHandler();
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(nameless)) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        ArticleBarcodeRange blank = administered(
                ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        blank.lookupUrl = "   ";
        ScanContext blankCtx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(blank)) {
            handler.handle(blankCtx);
        }
        assertTrue(blankCtx.handled);
        verify(ticket, org.mockito.Mockito.times(2))
                .setError("SERVICE EXTERNE NON PARAMETRE SUR CETTE PLAGE");
        verify(handler.rangeLookupClient, never()).lookup(any(), any());
    }

    /**
     * A CUSTOMER_ORDER range reads the order reference RAW — leading zeros kept,
     * because a reference is a key the shop matches character for character —
     * and loads the document the store node holds behind it, without ringing a
     * line of its own (BO-03-06-58).
     */
    @Test
    void anOrderCodeLoadsTheDocumentItNames() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.CUSTOMER_ORDER,
                ArticleBarcodeRange.ValueSource.PRICE);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        RangePurposeScanHandler handler = newHandler();
        when(handler.orderCodeScanHandler.open(state, "01234")).thenReturn(true);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        // The article segment of the fixture is 01234: the zero is KEPT.
        assertEquals("01234", ticket.recalledOrderReference);
        verify(handler.orderCodeScanHandler).open(state, "01234");
        verify(ticket, never()).setError(any());
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A reference the store node does not hold is refused by name
     * (BO-03-06-58).
     */
    @Test
    void anOrderTheNodeDoesNotHoldIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.CUSTOMER_ORDER,
                ArticleBarcodeRange.ValueSource.PRICE);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        RangePurposeScanHandler handler = newHandler();
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("COMMANDE 01234 INCONNUE DU NOEUD MAGASIN");
    }

    /**
     * A CUSTOMER_ORDER range whose segment falls outside the code reads no
     * reference and says so, rather than arming an empty key (BO-03-06-58).
     */
    @Test
    void anOrderCodeWhoseReferenceCannotBeReadIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ArticleBarcodeRange range = administered(
                ArticleBarcodeRange.Purpose.CUSTOMER_ORDER,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.articlePosition = 40;
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("REFERENCE DE COMMANDE ILLISIBLE");
        assertNull(ticket.recalledOrderReference);
    }
    /** BO-03-06: checksum guards cover non-EAN lengths and both sides of the digit range. */
    @Test
    void checksumGuardsRejectEachNonDigitSide() throws Exception {
        RangePurposeScanHandler handler = newHandler();
        var method = RangePurposeScanHandler.class.getDeclaredMethod("hasValidChecksum", String.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(handler, "SHORT"));
        assertEquals(false, method.invoke(handler, "/101234001509"));
        assertEquals(false, method.invoke(handler, ":101234001509"));
        handler.i18n = null;
        Product product = new Product();
        product.name = null;
        method = RangePurposeScanHandler.class.getDeclaredMethod("translatedLabel", Product.class);
        method.setAccessible(true);
        assertEquals("", method.invoke(handler, product));
    }

    /** BO-03-06-58/61: blank order references and missing return values never arm or ring a line. */
    @Test
    void unreadableRangeSegmentsKeepTheirOwnRefusalPaths() throws Exception {
        RangePurposeScanHandler handler = newHandler();
        TicketState ticket = mock(TicketState.class);
        ScanContext context = new ScanContext(REFUND_PRICE_CODE, newState(ticket));
        ArticleBarcodeRange range = mock(ArticleBarcodeRange.class);
        when(range.articleSegment(REFUND_PRICE_CODE)).thenReturn(" ");
        var method = RangePurposeScanHandler.class.getDeclaredMethod("recallOrder", ScanContext.class, ArticleBarcodeRange.class, String.class);
        method.setAccessible(true);
        method.invoke(handler, context, range, REFUND_PRICE_CODE);
        assertTrue(context.handled);
        verify(ticket).setError("REFERENCE DE COMMANDE ILLISIBLE");
        context.handled = false;
        when(range.articleCode(REFUND_PRICE_CODE)).thenReturn(ARTICLE);
        method = RangePurposeScanHandler.class.getDeclaredMethod("ringReturn", ScanContext.class, ArticleBarcodeRange.class, String.class);
        method.setAccessible(true);
        method.invoke(handler, context, range, REFUND_PRICE_CODE);
        assertFalse(context.handled);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /** BO-03-06-41: a resolved external article with missing catalog tax and price uses zero/default VAT. */
    @Test
    void externalLookupHandlesMissingPriceFields() {
        RangePurposeScanHandler handler = newHandler();
        TicketState ticket = mock(TicketState.class);
        ScanContext context = new ScanContext(REFUND_PRICE_CODE, newState(ticket));
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP, ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partner/";
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE)).thenReturn(EAN);
        org.mockito.Mockito.doAnswer(call -> {
            ticket.items.add(new TicketState.TicketItem());
            return null;
        }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
        Product product = newProduct();
        product.attributes.put("MEAL_VOUCHER_ELIGIBLE", "true");
        Price price = new Price();
        PanacheQuery<Product> found = query(product);
        try (var base = mockStatic(PanacheEntityBase.class);
             var prices = mockStatic(Price.class);
             var matched = ranges(range)) {
            base.when(() -> PanacheEntityBase.find("ean = ?1 and active = true", EAN)).thenReturn(found);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            handler.handle(context);
        }
        assertTrue(context.handled);
        verify(ticket).snapshotMealVoucherEligibility(product.ean, true);
        verify(ticket).addItem(eq(EAN), eq(null), eq("BANANE"), eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /** BO-03-06-61: a weight-coded return with incomplete pricing uses zero and the configured VAT fallback. */
    @Test
    void returnedWeightHandlesMissingPriceFields() {
        RangePurposeScanHandler handler = newHandler();
        TicketState ticket = mock(TicketState.class);
        ScanContext context = new ScanContext(REFUND_WEIGHT_CODE, newState(ticket));
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.REFUND, ArticleBarcodeRange.ValueSource.WEIGHT);
        Price price = new Price();
        try (var products = mockStatic(Product.class);
             var prices = mockStatic(Price.class);
             var matched = ranges(range)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            handler.handle(context);
        }
        assertTrue(context.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"), eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
    }

    /** LC-02-03-11: an external resolution cannot bypass the article's blocking message. */
    @Test
    void blockingArticleMessageRefusesExternallyResolvedArticle() {
        PosState state = new PosState();
        Product product = newProduct();
        product.infoMessage = "Check the package";
        product.attributes.put("INFO_MESSAGE_BLOCKING", "true");
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partner.test/";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE)).thenReturn(EAN);
        PanacheQuery<Product> found = query(product);
        ScanContext context = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<ArticleBarcodeRange> matched = ranges(range)) {
            panache.when(() -> Product.find("ean = ?1 and active = true", EAN)).thenReturn(found);
            handler.handle(context);
        }
        assertTrue(context.handled);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("Check the package", state.blockingArticleMessage);
    }

    /** LC-02-03-11: a return barcode also refuses an article carrying a blocking message. */
    @Test
    void blockingArticleMessageRefusesReturnBarcode() {
        PosState state = new PosState();
        Product product = newProduct();
        product.infoMessage = "Check the package";
        product.attributes.put("INFO_MESSAGE_BLOCKING", "true");
        ScanContext context = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ArticleBarcodeRange> matched = ranges(administered(
                     ArticleBarcodeRange.Purpose.REFUND, ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(product);
            newHandler().handle(context);
        }
        assertTrue(context.handled);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("Check the package", state.blockingArticleMessage);
    }


    // ------------------------------------------------------------ FUNCTION (BO-03-06-59)

    /**
     * Builds a FUNCTION range naming the given function, or none.
     *
     * @param function the administered function name, or null to read its number on the code
     * @return the administered range
     */
    private ArticleBarcodeRange functionRange(String function) {
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.FUNCTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.functionCode = function;
        return range;
    }

    /**
     * Builds a handler whose survey and external program collaborators are mocks.
     *
     * @return the handler
     */
    private RangePurposeScanHandler functionHandler() {
        RangePurposeScanHandler handler = newHandler();
        handler.surveyTriggerService = mock(com.intermarche.pos.ui.supervision.SurveyTriggerService.class);
        handler.externalProgramService = mock(com.intermarche.pos.ui.home.ExternalProgramService.class);
        return handler;
    }

    /**
     * Scans a code over a range.
     *
     * @param handler the handler
     * @param range the range answering every code
     * @param state the register state
     * @param code the scanned code
     * @return the scan context after the handler
     */
    private ScanContext scan(RangePurposeScanHandler handler, ArticleBarcodeRange range, PosState state, String code) {
        ScanContext ctx = new ScanContext(code, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            handler.handle(ctx);
        }
        return ctx;
    }

    /**
     * A range naming the metrology function opens its screen; the function is
     * named whatever its case.
     */
    @Test
    void aFunctionRangeNamingTheMetrologyOpensItsScreen() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = scan(functionHandler(), functionRange("metrology"), state, "2100000000000");
        assertTrue(ctx.handled);
        assertEquals("/maintenance/metrology", state.functionPath);
        verify(state).touch();
        verify(ticket, never()).setError(any());
    }

    /**
     * A range naming no function reads the function's number on the code: 4
     * opens the parked tickets, 5 the recall of a ticket.
     */
    @Test
    void aFunctionNumberReadOnTheCodeOpensItsScreen() {
        TicketState ticket = mock(TicketState.class);
        PosState parked = newState(ticket);
        scan(functionHandler(), functionRange(null), parked, "2100004000000");
        assertEquals("/parked", parked.functionPath);
        PosState recall = newState(mock(TicketState.class));
        scan(functionHandler(), functionRange(" "), recall, "2100005000000");
        assertEquals("/recall", recall.functionPath);
    }

    /**
     * A function neither named nor numbered on the code is refused: an
     * unknown name over an unknown number, an unreadable segment, letters.
     */
    @Test
    void anUnknownFunctionIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = scan(functionHandler(), functionRange("BOGUS"), state, "2101234000000");
        assertTrue(ctx.handled);
        ArticleBarcodeRange unreadable = functionRange(null);
        unreadable.articlePosition = 40;
        scan(functionHandler(), unreadable, state, "2100001000000");
        scan(functionHandler(), functionRange(null), state, "21ABCDE000000");
        verify(ticket, org.mockito.Mockito.times(3)).setError("FONCTION CAISSE INCONNUE POUR CE CODE");
        assertNull(state.functionPath);
        verify(state, never()).touch();
    }

    /**
     * The external program function launches the administered program and
     * names it, or shows why it could not.
     */
    @Test
    void theExternalProgramFunctionLaunchesTheProgram() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler launched = functionHandler();
        when(launched.externalProgramService.launch()).thenReturn(null);
        when(launched.externalProgramService.label()).thenReturn("Inventaire");
        scan(launched, functionRange("EXTERNAL_PROGRAM"), state, "2100000000000");
        verify(ticket).setNotice("PROGRAMME LANCÉ : Inventaire");
        RangePurposeScanHandler refused = functionHandler();
        when(refused.externalProgramService.launch()).thenReturn("AUCUN PROGRAMME PARAMÉTRÉ");
        scan(refused, functionRange(null), state, "2100003000000");
        verify(ticket).setError("AUCUN PROGRAMME PARAMÉTRÉ");
        verify(state, org.mockito.Mockito.times(2)).touch();
    }

    /**
     * The survey function starts the survey whose number the value segment
     * carries, whatever decimals the range reads; a segment carrying nothing is
     * refused.
     */
    @Test
    void theSurveyFunctionStartsTheNumberedSurvey() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = functionHandler();
        scan(handler, functionRange("SURVEY"), state, "2100000000420");
        verify(handler.surveyTriggerService).fireByScan(state, 42L);
        verify(state).touch();
        RangePurposeScanHandler empty = functionHandler();
        PosState other = newState(ticket);
        scan(empty, functionRange("SURVEY"), other, "2100000000000");
        ArticleBarcodeRange unreadable = functionRange("SURVEY");
        unreadable.valuePosition = 40;
        scan(empty, unreadable, other, "2100000000420");
        verify(ticket, org.mockito.Mockito.times(2)).setError("NUMÉRO DE SONDAGE ILLISIBLE");
        verifyNoInteractions(empty.surveyTriggerService);
        verify(other, never()).touch();
    }

    /**
     * Builds a PROMOTION range DEFINING its promotion (BO-03-06-43).
     *
     * @param type PERCENT or AMOUNT
     * @return the administered range
     */
    private ArticleBarcodeRange definedPromotion(String type) {
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.PROMOTION,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.promotionCode = "COUPON_BANANE";
        range.promotionType = type;
        return range;
    }

    /**
     * Scans a code over a defined promotion range, the article named by its
     * segment found by EAN or by PLU as the fixtures say.
     *
     * @param handler the handler
     * @param range the administered range
     * @param ticket the ticket mock
     * @param code the scanned code
     * @param byEan the article the EAN lookup answers, or null
     * @param byPlu the article the PLU lookup answers, or null
     * @return the scan context after handling
     */
    private ScanContext scanPromotion(RangePurposeScanHandler handler, ArticleBarcodeRange range,
                                      TicketState ticket, String code, Product byEan, Product byPlu) {
        ScanContext ctx = new ScanContext(code, newState(ticket));
        PanacheQuery<Product> found = query(byEan);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Product> products = mockStatic(Product.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            panache.when(() -> Product.find(eq("ean = ?1 and active = true"), any(Object[].class)))
                    .thenReturn(found);
            products.when(() -> Product.findActiveByPlu(any())).thenReturn(byPlu);
            handler.handle(ctx);
        }
        return ctx;
    }

    /**
     * BO-03-06-43: a range defining a PERCENT promotion arms the offer on the
     * article its segment names — here found by PLU — worth the value segment
     * as a fraction, arms the name and says so.
     */
    @Test
    void aDefinedPercentPromotionArmsTheOfferOnTheNamedArticle() {
        TicketState ticket = mock(TicketState.class);
        when(ticket.armOffer(any())).thenReturn(true);
        ScanContext ctx = scanPromotion(newHandler(),
                definedPromotion(ArticleBarcodeRange.PROMOTION_PERCENT), ticket, REFUND_PRICE_CODE,
                null, newProduct());
        assertTrue(ctx.handled);
        verify(ticket).armOffer(new TicketState.ScannedOffer(REFUND_PRICE_CODE, "COUPON_BANANE",
                EAN, "PERCENT", new BigDecimal("0.0150")));
        verify(ticket).armPromotion("COUPON_BANANE");
        verify(ticket).setNotice("PROMOTION COUPON_BANANE DECLENCHEE SUR BANANE");
    }

    /**
     * BO-03-06-43: an AMOUNT promotion is worth the value segment as it reads,
     * the article found by its EAN first.
     */
    @Test
    void aDefinedAmountPromotionIsWorthTheValueSegment() {
        TicketState ticket = mock(TicketState.class);
        when(ticket.armOffer(any())).thenReturn(true);
        scanPromotion(newHandler(), definedPromotion(ArticleBarcodeRange.PROMOTION_AMOUNT), ticket,
                REFUND_PRICE_CODE, newProduct(), null);
        verify(ticket).armOffer(new TicketState.ScannedOffer(REFUND_PRICE_CODE, "COUPON_BANANE",
                EAN, "AMOUNT", new BigDecimal("1.50")));
    }

    /**
     * BO-03-06-43: the same paper scanned twice is a caution, and nothing is
     * armed twice.
     */
    @Test
    void aDefinedPromotionScannedTwiceIsAWarning() {
        TicketState ticket = mock(TicketState.class);
        when(ticket.armOffer(any())).thenReturn(false);
        scanPromotion(newHandler(), definedPromotion(ArticleBarcodeRange.PROMOTION_AMOUNT), ticket,
                REFUND_PRICE_CODE, newProduct(), null);
        verify(ticket).setWarning("PROMOTION COUPON_BANANE DEJA DECLENCHEE");
        verify(ticket, never()).armPromotion(any());
    }

    /**
     * BO-03-06-43: a code whose article segment cannot be read, whose article
     * is unknown, whose value cannot be read or is zero triggers nothing and
     * says so — each leg of the guard.
     */
    @Test
    void aDefinedPromotionOnNothingOrWorthNothingIsRefused() {
        TicketState ticket = mock(TicketState.class);
        RangePurposeScanHandler handler = newHandler();
        ArticleBarcodeRange range = definedPromotion(ArticleBarcodeRange.PROMOTION_AMOUNT);
        assertTrue(scanPromotion(handler, range, ticket, "21012", newProduct(), null).handled);
        scanPromotion(handler, range, ticket, REFUND_PRICE_CODE, null, null);
        scanPromotion(handler, range, ticket, "2101234ABCDE0", newProduct(), null);
        scanPromotion(handler, range, ticket, REFUND_ZERO_CODE, newProduct(), null);
        verify(ticket, org.mockito.Mockito.times(4))
                .setError("PROMOTION ILLISIBLE : ARTICLE OU VALEUR INCONNU");
        verify(ticket, never()).armOffer(any());
    }

    /**
     * Builds a PROMOTION_ENGINE range (BO-03-06-42).
     *
     * @return the administered range
     */
    private ArticleBarcodeRange engineRange() {
        return administered(ArticleBarcodeRange.Purpose.PROMOTION_ENGINE,
                ArticleBarcodeRange.ValueSource.PRICE);
    }

    /**
     * BO-03-06-42: a code of an engine range is transmitted verbatim to the
     * promotion engine, once, and the operator is told.
     */
    @Test
    void anEngineCodeIsTransmittedToThePromotionEngine() {
        TicketState ticket = mock(TicketState.class);
        when(ticket.armEngineBarcode(REFUND_PRICE_CODE)).thenReturn(true, false);
        RangePurposeScanHandler handler = newHandler();
        handler.valuationService = mock(com.intermarche.pos.ui.valuation.ValuationService.class);
        when(handler.valuationService.isEnabled()).thenReturn(true);
        PosState state = newState(ticket);
        ScanContext first = new ScanContext(REFUND_PRICE_CODE, state);
        ScanContext second = new ScanContext(REFUND_PRICE_CODE, state);
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(engineRange())) {
            handler.handle(first);
            handler.handle(second);
        }
        assertTrue(first.handled);
        assertTrue(second.handled);
        verify(ticket).setNotice("CODE " + REFUND_PRICE_CODE + " TRANSMIS AU MOTEUR DE PROMOTION");
        verify(ticket).setWarning("CODE " + REFUND_PRICE_CODE + " DEJA TRANSMIS AU MOTEUR DE PROMOTION");
    }

    /**
     * BO-03-06-42: with the engine call switched off nothing can be
     * transmitted, and the operator is told rather than the customer misled.
     */
    @Test
    void anEngineCodeIsRefusedWhenTheEngineIsNotCalled() {
        TicketState ticket = mock(TicketState.class);
        RangePurposeScanHandler handler = newHandler();
        handler.valuationService = mock(com.intermarche.pos.ui.valuation.ValuationService.class);
        ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, newState(ticket));
        try (MockedStatic<ArticleBarcodeRange> mocked = ranges(engineRange())) {
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("MOTEUR DE PROMOTION NON APPELE : CODE NON TRANSMIS");
        verify(ticket, never()).armEngineBarcode(any());
    }

    /**
     * BO-03-06-41: the partner's answer is read through the field the range
     * maps — an answer not carrying it is reported as illegible, never shown
     * raw.
     */
    @Test
    void anExternalAnswerIsReadThroughTheMappedField() {
        TicketState ticket = mock(TicketState.class);
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP,
                ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partenaire.test/bons/{code}";
        range.lookupField = "statut.message";
        RangePurposeScanHandler handler = newHandler();
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE))
                .thenReturn("{\"statut\":{\"message\":\"Bon déjà utilisé\"}}", "{\"autre\":1}");
        PosState state = newState(ticket);
        ScanContext shown = new ScanContext(REFUND_PRICE_CODE, state);
        ScanContext illegible = new ScanContext(REFUND_PRICE_CODE, state);
        PanacheQuery<Product> none = query(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<Product> products = mockStatic(Product.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(range)) {
            panache.when(() -> Product.find(eq("ean = ?1 and active = true"), any(Object[].class)))
                    .thenReturn(none);
            products.when(() -> Product.findActiveByPlu(any())).thenReturn(null);
            handler.handle(shown);
            handler.handle(illegible);
        }
        assertTrue(shown.handled);
        assertTrue(illegible.handled);
        verify(ticket).setNotice("Bon déjà utilisé");
        verify(ticket).setError("REPONSE DU SERVICE EXTERNE ILLISIBLE");
    }

    /**
     * BO-03-13-12: a RETURN range attached to an administered return type is
     * ruled by it — a type allowing the return without its origin passes a
     * store-wide prohibition, and its name is said — while a type that does
     * not allow it is refused like an unattached code. The line is rung under
     * the attached type, and the type chosen before is given back after.
     */
    @Test
    void aReturnRangeAttachedToATypeIsRuledByIt() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        RangePurposeScanHandler handler = newHandler();
        handler.posListService = mock(com.intermarche.pos.service.PosListService.class);
        when(handler.posSettingsService.returnWithoutOriginAllowed()).thenReturn(false);
        when(handler.posListService.returnTypeOfRange("PLAN_REFUND")).thenReturn(
                new PosSettingsService.ReturnType("Retour tri", false, true, true, false));
        try (MockedStatic<Product> products = mockStatic(Product.class);
                MockedStatic<Price> prices = mockStatic(Price.class);
                MockedStatic<ArticleBarcodeRange> mocked = ranges(
                        administered(ArticleBarcodeRange.Purpose.REFUND,
                                ArticleBarcodeRange.ValueSource.PRICE))) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(newProduct());
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(newPrice("9.99", "0.055"));
            state.returnTypeLabel = "Avant";
            String[] seen = new String[1];
            org.mockito.Mockito.doAnswer(invocation -> {
                seen[0] = state.returnTypeLabel;
                return null;
            }).when(ticket).addItem(any(), any(), any(), any(), any(), any());
            ScanContext ctx = new ScanContext(REFUND_PRICE_CODE, state);
            handler.handle(ctx);
            assertTrue(ctx.handled);
            verify(ticket).setNotice("RETOUR Retour tri ENREGISTRE");
            assertEquals("Retour tri", seen[0]);
            assertEquals("Avant", state.returnTypeLabel);
            when(handler.posListService.returnTypeOfRange("PLAN_REFUND")).thenReturn(
                    new PosSettingsService.ReturnType("Retour strict", false, true, false, false));
            ScanContext strict = new ScanContext(REFUND_PRICE_CODE, state);
            handler.handle(strict);
            verify(ticket).setError("RETOUR SANS TICKET D'ORIGINE INTERDIT");
        }
        verify(ticket, org.mockito.Mockito.times(1)).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * LC-02-13-03/04: a decimal quantity armed before the unit article a partner
     * named is refused — « QUANTITÉ INVALIDE », the code consumed, no line rung.
     */
    @Test
    void anExternalArticleRefusesADecimalArmedBeforeIt() {
        RangePurposeScanHandler handler = newHandler();
        TicketState ticket = mock(TicketState.class);
        when(ticket.armedDecimalRefused(false)).thenReturn(true);
        ScanContext context = new ScanContext(REFUND_PRICE_CODE, newState(ticket));
        ArticleBarcodeRange range = administered(ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP, ArticleBarcodeRange.ValueSource.PRICE);
        range.lookupUrl = "https://partner/";
        when(handler.rangeLookupClient.lookup(range.lookupUrl, REFUND_PRICE_CODE)).thenReturn(EAN);
        PanacheQuery<Product> found = query(newProduct());
        try (var base = mockStatic(PanacheEntityBase.class);
             var matched = ranges(range)) {
            base.when(() -> PanacheEntityBase.find("ean = ?1 and active = true", EAN)).thenReturn(found);
            handler.handle(context);
        }
        assertTrue(context.handled);
        verify(ticket).setError("QUANTITÉ INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }
}
