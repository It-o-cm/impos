package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link WeightedEanScanHandler}.
 * <p>
 * The handler is a {@code @Priority(1)} link decoding in-store weighted EAN13
 * stickers laid out as {@code PP AAAAA VVVVV K}: a 2-digit prefix classifying
 * the sticker as price-embedded or weight-embedded, a 5-digit article code
 * matched against the product PLU (leading zeros stripped), a 5-digit embedded
 * value (cents or grams) and a verified EAN13 check digit. Prefixes it does not
 * own, non-2x codes and codes with a bad checksum fall through untouched; a
 * missing or forbidden article raises the cashier error; a price-embedded
 * sticker seen twice is refused; a weight of zero grams is invalid. The VAT and
 * unit price come from the current catalog {@link Price}, falling back to a zero
 * price and the injected default VAT when none exists. Every collaborator is a
 * Mockito mock — the {@link PosState} whose public {@code ticket} sub-state is a
 * {@link TicketState} mock, and the two Panache finders neutralized on their own
 * declaring classes with {@link org.mockito.Mockito#mockStatic}. Config fields
 * are package-private collaborators set directly.
 */
class WeightedEanScanHandlerTest {

    /** A valid price-embedded sticker: prefix 21, article 01234, 150 cents. */
    private static final String PRICE_CODE = "2101234001509";

    /** A valid price-embedded sticker whose article is all zeros (strips to "0"). */
    private static final String PRICE_ZERO_ARTICLE_CODE = "2100000001507";

    /** A valid weight-embedded sticker: prefix 23, article 01234, 500 grams. */
    private static final String WEIGHT_CODE = "2301234005006";

    /** A valid weight-embedded sticker carrying zero grams (invalid weight). */
    private static final String WEIGHT_ZERO_CODE = "2301234000001";

    /** A 2x code whose prefix (29) belongs to neither prefix list. */
    private static final String OTHER_PREFIX_CODE = "2901234001505";

    /** The stripped article code shared by the well-formed stickers. */
    private static final String ARTICLE = "1234";

    /** The product's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "banane";

    /** The product's EAN carried by weight-embedded lines. */
    private static final String EAN = "3560070000000";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a handler wired with the two prefix lists and the fallback VAT.
     *
     * @return a ready-to-test handler
     */
    private WeightedEanScanHandler newHandler() {
        WeightedEanScanHandler handler = new WeightedEanScanHandler();
        handler.pricePrefixes = Arrays.asList("21", "22");
        handler.weightPrefixes = Arrays.asList("23", "24", "25", "26");
        handler.defaultVatRate = DEFAULT_VAT;
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock, seeding the ticket's transient scanned-codes set.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        ticket.scannedStickerCodes = new HashSet<>();
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        return state;
    }

    /**
     * Builds a {@link Product} with the given forbidden flag, addressable by id.
     *
     * @param forbidden the forbidden-to-sale flag
     * @return the assembled product
     */
    private Product newProduct(boolean forbidden) {
        Product p = new Product();
        p.id = 42L;
        p.name = NAME;
        p.ean = EAN;
        p.forbiddenToSale = forbidden;
        return p;
    }

    /**
     * Builds a current {@link Price} with the given tax-inclusive price and VAT.
     *
     * @param including the price including tax
     * @param vat the VAT rate
     * @return the assembled price
     */
    private Price newPrice(String including, String vat) {
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal(including);
        price.vatRate = new BigDecimal(vat);
        return price;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verify(ticket, never()).setError(any());
    }

    /**
     * A null code is not a 2x sticker: the handler returns and the context
     * stays unhandled for the next link.
     */
    @Test
    void nullCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(null, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A code that is not a 13-digit 2x EAN is not recognized: no lookup happens
     * and the context stays unhandled.
     */
    @Test
    void nonWeightedEanCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("3017620422003", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A 2x code whose prefix belongs to neither the price nor the weight list
     * is left for the next handler: the context stays unhandled.
     */
    @Test
    void unrecognizedPrefixIsNotConsumed() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(OTHER_PREFIX_CODE, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A recognized prefix but an invalid EAN13 checksum falls through to the
     * generic handlers: the context stays unhandled and no lookup happens.
     */
    @Test
    void invalidChecksumFallsThrough() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("2101234001500", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verify(ticket, never()).setError(any());
    }

    /**
     * A valid sticker whose article matches no active product raises the cashier
     * error and consumes the context; the all-zero article strips to "0".
     */
    @Test
    void unknownArticleSetsErrorAndConsumes() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(PRICE_ZERO_ARTICLE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu("0")).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ARTICLE BALANCE INTROUVABLE (0)");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A product flagged forbidden to sell is refused: the ticket carries the
     * cashier error, the context is consumed and no line is created.
     */
    @Test
    void forbiddenProductSetsErrorAndAddsNoLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(true);
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("PRODUIT INTERDIT À LA VENTE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A price-embedded sticker with a current price creates a single line of
     * quantity one at the embedded total, carrying no EAN/PLU so it never
     * merges, freezing the catalog VAT and consuming the context.
     */
    @Test
    void priceEmbeddedAddsLocalLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = newPrice("9.99", "0.055");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(isNull(), isNull(), eq("BANANE"),
                eq(BigDecimal.valueOf(150, 2)), eq(BigDecimal.ONE), eq(new BigDecimal("0.055")));
        verify(ticket, never()).setError(any());
    }

    /**
     * The same price-embedded sticker scanned twice is refused: the second scan
     * raises the duplicate-sticker error, consumes the context and adds no line.
     */
    @Test
    void priceEmbeddedDuplicateStickerIsRefused() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ticket.scannedStickerCodes.add(PRICE_CODE);
        Product p = newProduct(false);
        Price price = newPrice("9.99", "0.055");
        ScanContext ctx = new ScanContext(PRICE_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("ÉTIQUETTE DÉJÀ SCANNÉE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A weight-embedded sticker with a current price creates a weighed line
     * carrying the product EAN and article PLU, valued at the catalog price per
     * kilogram, freezing the catalog VAT and consuming the context.
     */
    @Test
    void weightEmbeddedAddsWeighedLineWithPrice() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = newPrice("2.99", "0.055");
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(new BigDecimal("2.99")), eq(new BigDecimal("0.500")), eq(new BigDecimal("0.055")));
        verify(ticket, never()).setError(any());
    }

    /**
     * A weight-embedded sticker with no current price falls back to a zero unit
     * price and the injected default VAT rate, still creating the weighed line.
     */
    @Test
    void weightEmbeddedWithoutPriceUsesZeroAndDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(WEIGHT_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(EAN), eq(ARTICLE), eq("BANANE"),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.500")), eq(DEFAULT_VAT));
        verify(ticket, never()).setError(any());
    }

    /**
     * A weight-embedded sticker carrying zero grams is invalid: the ticket
     * carries the weight error, the context is consumed and no line is created.
     */
    @Test
    void weightEmbeddedZeroWeightSetsError() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(WEIGHT_ZERO_CODE, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByPlu(ARTICLE)).thenReturn(p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("POIDS INVALIDE");
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }
}
