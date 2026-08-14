package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EanScanHandler}.
 * <p>
 * The handler is a {@code @Priority(2)} link of the scan chain: it recognizes
 * an 8-to-13 digit catalog EAN, looks up the active {@link Product}, and — the
 * snapshot moment of a sale line — freezes its resolved price and VAT onto a
 * unit line. A product forbidden to sell is refused with the cashier error; a
 * product with no current {@link Price} falls back to a zero price and the
 * injected default VAT rate. Every collaborator is a Mockito mock: the
 * {@link PosState} whose public {@code ticket} sub-state is itself a
 * {@link TicketState} mock, and the two Panache finders. Because plain
 * {@code mvn test} leaves entities un-enhanced, {@code Product.find} resolves
 * to {@link PanacheEntityBase} (neutralized with {@link org.mockito.Mockito#mockStatic})
 * and {@code Price.findCurrentPrice}, declared on {@link Price} itself, is
 * neutralized on its own class. The {@code defaultVatRate} config field is a
 * package-private collaborator set directly. Six decision points, twelve
 * branches, are exercised by six isolated cases.
 */
class EanScanHandlerTest {

    /** A well-formed catalog EAN fed to the handler. */
    private static final String CODE = "3017620422003";

    /** The product's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "coca cola";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a handler wired with the fallback VAT rate.
     *
     * @return a ready-to-test handler
     */
    private EanScanHandler newHandler() {
        return newHandler(false);
    }

    /**
     * Builds a handler wired with the fallback VAT rate and an age-gate
     * collaborator answering the given verdict.
     * <p>
     * The gate is a MANDATORY collaborator, never an optional one: the
     * handler asks it before every line, and a silently absent gate would
     * mean age-restricted goods pass unchecked. The mock therefore replaces
     * it in tests rather than the handler tolerating its absence.
     *
     * @param parksTheScan true to simulate a restricted product parking the
     *        scan behind the ID-check prompt
     * @return a ready-to-test handler
     */
    private EanScanHandler newHandler(boolean parksTheScan) {
        EanScanHandler handler = new EanScanHandler();
        handler.defaultVatRate = DEFAULT_VAT;
        handler.ticketService = mock(com.intermarche.pos.ui.ticket.TicketService.class);
        when(handler.ticketService.suspendForAgeCheck(any(), any(), any(), any(), any()))
                .thenReturn(parksTheScan);
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose ticket sub-state is the supplied
     * mock.
     *
     * @param ticket the ticket mailbox mock
     * @return the wired state mock
     */
    private PosState newState(TicketState ticket) {
        PosState state = mock(PosState.class);
        state.ticket = ticket;
        return state;
    }

    /**
     * Builds a {@link Product} with the given fields, addressable by its id.
     *
     * @param forbidden the forbidden-to-sale flag
     * @return the assembled product
     */
    private Product newProduct(boolean forbidden) {
        Product p = new Product();
        p.id = 42L;
        p.name = NAME;
        p.forbiddenToSale = forbidden;
        return p;
    }

    /**
     * Wires {@code Product.find(...)} on the given static mock to yield the
     * supplied product through a mock {@link PanacheQuery}.
     *
     * @param panache the open {@link PanacheEntityBase} static mock
     * @param product the product the finder must return (may be null)
     */
    @SuppressWarnings("unchecked")
    private void stubProductFind(MockedStatic<PanacheEntityBase> panache, Product product) {
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        panache.when(() -> Product.find("ean = ?1 and active = true", CODE)).thenReturn(query);
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state or the ticket, leaving the flag set.
     */
    @Test
    void alreadyHandledShortCircuits() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(ticket);
    }

    /**
     * A code that is not an 8-to-13 digit EAN is not recognized: no catalog
     * lookup happens and the context stays unhandled for the next link.
     */
    @Test
    void nonEanCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("ABC123", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * A well-formed EAN matching no active product leaves the context
     * unhandled and the ticket untouched.
     */
    @Test
    void unknownEanLeavesContextUnhandled() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, null);
            newHandler().handle(ctx);
        }
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
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
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).setError("PRODUIT INTERDIT À LA VENTE");
        verify(ticket, never()).addItem(eq(CODE), isNull(), eq(NAME.toUpperCase()),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * A sellable product with a current price freezes that price and its real
     * VAT rate onto an upper-cased unit line and consumes the context.
     */
    @Test
    void productWithCurrentPriceAddsSnapshotLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("1.5000");
        price.vatRate = new BigDecimal("0.0550");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(new BigDecimal("1.5000")), eq(BigDecimal.ONE), eq(new BigDecimal("0.0550")));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * A sellable product with no current price falls back to a zero price and
     * the injected default VAT rate, still adding the unit line.
     */
    @Test
    void productWithoutCurrentPriceUsesZeroAndDefaultVat() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(ticket).addItem(eq(CODE), isNull(), eq("COCA COLA"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * An AGE-RESTRICTED product PARKS the scan: the gate answers true, so the
     * context is consumed and NO line is created — the article only appears
     * once the ID check is confirmed, and the confirmation replays this very
     * code through the chain.
     */
    @Test
    void ageRestrictedProductParksTheScanAndAddsNoLine() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        verify(handler.ticketService).suspendForAgeCheck(state, p, "SCAN", CODE, null);
        verify(ticket, never()).addItem(any(), any(), any(), any(), any(), any());
        verify(ticket, never()).setError(any());
    }

    /**
     * The gate is asked with the SCAN kind and no quantity: a scanned line is
     * always a unit, and the kind is what tells the confirmation to replay it
     * through the recognition chain rather than through a direct add.
     */
    @Test
    void ageGateIsAskedWithScanKindAndNoQuantity() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(false);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(false);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            handler.handle(ctx);
        }
        verify(handler.ticketService).suspendForAgeCheck(state, p, "SCAN", CODE, null);
        assertTrue(ctx.handled);
    }

    /**
     * A FORBIDDEN product is refused BEFORE the age gate is even consulted:
     * an unsellable article never parks a scan.
     */
    @Test
    void forbiddenProductNeverReachesTheAgeGate() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        Product p = newProduct(true);
        ScanContext ctx = new ScanContext(CODE, state);
        EanScanHandler handler = newHandler(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubProductFind(panache, p);
            handler.handle(ctx);
        }
        verify(handler.ticketService, never())
                .suspendForAgeCheck(any(), any(), any(), any(), any());
    }

    /**
     * A GIFT CARD is flagged as a money product on the freshly added line:
     * the line carries VALUE, not goods. The flag is what later excludes it
     * from discounts, gestures and valuation, and forbids its refund.
     */
    @Test
    void giftCardLineIsFlaggedAsMoneyProduct() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.giftCardAmount = new BigDecimal("25.00");
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertTrue(added.moneyProduct);
    }

    /**
     * An ORDINARY product leaves the line unflagged (null giftCardAmount
     * arm): only instruments carry value.
     */
    @Test
    void ordinaryLineIsNotFlaggedAsMoneyProduct() {
        TicketState ticket = mock(TicketState.class);
        TicketState.TicketItem added = new TicketState.TicketItem();
        ticket.items = new java.util.ArrayList<>(java.util.List.of(added));
        PosState state = newState(ticket);
        Product p = newProduct(false);
        p.giftCardAmount = null;
        ScanContext ctx = new ScanContext(CODE, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            stubProductFind(panache, p);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(null);
            newHandler().handle(ctx);
        }
        assertFalse(added.moneyProduct);
    }
}
