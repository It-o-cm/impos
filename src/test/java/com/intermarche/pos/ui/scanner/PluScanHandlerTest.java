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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PluScanHandler}.
 * <p>
 * The handler is a {@code @Priority(3)} link of the scan chain: it recognizes
 * a 1-to-5 digit short PLU code, looks up the active {@link Product}, and — the
 * snapshot moment of a sale line — freezes its resolved price and VAT onto a
 * quantity-one line placed on the PLU column (its EAN column left null). A
 * product forbidden to sell is refused with the cashier error; a product with
 * no current {@link Price} falls back to a zero price and the injected default
 * VAT rate. Every collaborator is a Mockito mock: the {@link PosState} whose
 * public {@code ticket} sub-state is itself a {@link TicketState} mock, and the
 * two Panache finders. Because plain {@code mvn test} leaves entities
 * un-enhanced, {@code Product.find} (behind {@code findActiveByPlu}) resolves
 * to {@link PanacheEntityBase} and is neutralized with
 * {@link org.mockito.Mockito#mockStatic}, while {@code Price.findCurrentPrice},
 * declared on {@link Price} itself, is neutralized on its own class. The
 * {@code defaultVatRate} config field is a package-private collaborator set
 * directly. Six decision points, twelve branches, are exercised by six
 * isolated cases.
 */
class PluScanHandlerTest {

    /** A well-formed short PLU code fed to the handler. */
    private static final String CODE = "1234";

    /** The product's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "poire williams";

    /** The injected fallback VAT rate used when no current price exists. */
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    /**
     * Builds a handler wired with the fallback VAT rate.
     *
     * @return a ready-to-test handler
     */
    private PluScanHandler newHandler() {
        PluScanHandler handler = new PluScanHandler();
        handler.defaultVatRate = DEFAULT_VAT;
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
        panache.when(() -> Product.find("plu = ?1 and active = true", CODE)).thenReturn(query);
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
     * A code that is not a 1-to-5 digit PLU is not recognized: no catalog
     * lookup happens and the context stays unhandled for the next link.
     */
    @Test
    void nonPluCodeIsNotRecognized() {
        TicketState ticket = mock(TicketState.class);
        PosState state = newState(ticket);
        ScanContext ctx = new ScanContext("123456", state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(ticket);
    }

    /**
     * A well-formed PLU matching no active product leaves the context
     * unhandled and the ticket untouched.
     */
    @Test
    void unknownPluLeavesContextUnhandled() {
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
        verify(ticket, never()).addItem(isNull(), eq(CODE), eq(NAME.toUpperCase()),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
    }

    /**
     * A sellable product with a current price freezes that price and its real
     * VAT rate onto an upper-cased quantity-one PLU line and consumes the
     * context.
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
        verify(ticket).addItem(isNull(), eq(CODE), eq("POIRE WILLIAMS"),
                eq(new BigDecimal("1.5000")), eq(BigDecimal.ONE), eq(new BigDecimal("0.0550")));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }

    /**
     * A sellable product with no current price falls back to a zero price and
     * the injected default VAT rate, still adding the quantity-one PLU line.
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
        verify(ticket).addItem(isNull(), eq(CODE), eq("POIRE WILLIAMS"),
                eq(BigDecimal.ZERO), eq(BigDecimal.ONE), eq(DEFAULT_VAT));
        verify(ticket, never()).setError("PRODUIT INTERDIT À LA VENTE");
    }
}
