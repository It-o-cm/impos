package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.VoucherService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Gs1ScanHandler}, the link of the scan chain that reads GS1
 * codes ({@code LC-11-03-02} to {@code -23}).
 *
 * <p>THE HANDLER IS JUDGED ON WHAT IT LEAVES BEHIND: a line on the ticket, a settlement
 * on the payment, a message to the cashier, an entry in the technical journal — or
 * nothing at all, {@code handled} still false, so the code goes on down the chain. That
 * last outcome has as many tests as the others, because a handler at this priority that
 * claims too much silently breaks every family of code that comes after it.
 *
 * <p>The state is the real {@link PosState} with a mocked ticket mailbox, so the lines
 * the handler creates can be read back and the messages it posts asserted.
 */
class Gs1ScanHandlerTest {

    /** A GS1 payload naming one article, in its human-readable form. */
    private static final String ARTICLE = "(01)03017620422003";

    /** The article's shelf code, as the catalog spells it. */
    private static final String EAN = "3017620422003";

    /** The article's stored name, upper-cased onto the ticket line. */
    private static final String NAME = "coca cola";

    /** How the cashier reads a date. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Builds a handler whose collaborators are all mocks, the shop administering
     * nothing: no expiry rule, no GS1 settlement type.
     *
     * @return the wired handler
     */
    private Gs1ScanHandler newHandler() {
        Gs1ScanHandler handler = new Gs1ScanHandler();
        handler.defaultVatRate = new BigDecimal("0.20");
        handler.voucherService = mock(VoucherService.class);
        handler.technicalEventService = mock(TechnicalEventService.class);
        handler.posSettingsService = mock(PosSettingsService.class);
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("NONE");
        when(handler.posSettingsService.gs1CouponExpiryAlert()).thenReturn("NONE");
        when(handler.posSettingsService.gs1GiftCouponTypes()).thenReturn("");
        when(handler.posSettingsService.gs1CouponType()).thenReturn("");
        return handler;
    }

    /**
     * Builds a register state carrying a real ticket, so lines can be read back.
     *
     * @return the state under test
     */
    private PosState newState() {
        PosState state = new PosState();
        state.ticket.setParent(state);
        // A register starts LOCKED, and a locked register decodes nothing — the first
        // guard of the chain. Every case here is a scan during a sale, so the operator
        // is taken to be logged in.
        state.auth.isLocked = false;
        return state;
    }

    /**
     * Builds a register state whose ticket is a mock, so messages can be asserted.
     *
     * @return the state under test
     */
    private PosState newStateWithMockedTicket() {
        PosState state = new PosState();
        state.ticket = mock(TicketState.class);
        // Registering an article reads the line back to snapshot what it may be paid
        // with, so the mocked ticket carries a real, empty list.
        state.ticket.items = new java.util.ArrayList<>();
        // Same reason as above: a register starts locked and decodes nothing.
        state.auth.isLocked = false;
        return state;
    }

    /**
     * Builds the catalog article the payloads name.
     *
     * @return the article
     */
    private Product newProduct() {
        Product product = new Product();
        product.id = 42L;
        product.ean = EAN;
        product.plu = "4200";
        product.name = NAME;
        return product;
    }

    /**
     * Builds a current price of two euros at twenty per cent.
     *
     * @return the price
     */
    private Price newPrice() {
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("2.00");
        price.vatRate = new BigDecimal("0.20");
        return price;
    }

    /**
     * Runs one payload through the handler with the catalog answering that article.
     *
     * @param handler the handler under test
     * @param state   the register state
     * @param payload the scanned payload
     * @param product the article the catalog knows, or null when it knows none
     * @param price   the article's current price, or null when it has none
     * @return the context, carrying whether the handler consumed the scan
     */
    private ScanContext scan(Gs1ScanHandler handler, PosState state, String payload,
            Product product, Price price) {
        ScanContext ctx = new ScanContext(payload, state);
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan(any())).thenReturn(product);
            prices.when(() -> Price.findCurrentPrice(any())).thenReturn(price);
            handler.handle(ctx);
        }
        return ctx;
    }

    /**
     * Returns the only line the ticket carries.
     *
     * @param state the register state
     * @return the line
     */
    private TicketState.TicketItem onlyLine(PosState state) {
        assertEquals(1, state.ticket.items.size());
        return state.ticket.items.get(0);
    }

    // --------------------------------------------------
    // What the handler does NOT claim
    // --------------------------------------------------

    /**
     * An already-consumed scan short-circuits before anything is decoded.
     */
    @Test
    void anAlreadyConsumedScanShortCircuits() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = new ScanContext(ARTICLE, state);
        ctx.handled = true;
        handler.handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state.ticket);
        verifyNoInteractions(handler.technicalEventService);
    }

    /**
     * A locked register decodes nothing.
     */
    @Test
    void aLockedRegisterDecodesNothing() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        state.auth.isLocked = true;
        ScanContext ctx = new ScanContext(ARTICLE, state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(handler.technicalEventService);
    }

    /**
     * An ordinary shelf EAN is left to the catalog handler: the single most important
     * thing this handler must not do.
     */
    @Test
    void anOrdinaryEanIsLeftToTheChain() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = new ScanContext(EAN, state);
        handler.handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(state.ticket);
        verifyNoInteractions(handler.technicalEventService);
    }

    /**
     * A GS1 payload naming neither an article nor a settlement is released to the
     * chain: it decoded, and says nothing this register acts on.
     */
    @Test
    void aPayloadNamingNothingIsReleasedToTheChain() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scan(handler, state, "(10)LOT4242", null, null);
        assertFalse(ctx.handled);
        verifyNoInteractions(state.ticket);
    }

    /**
     * An UNMARKED payload whose article the catalog does not know is released in
     * silence: it may be a long numeric card the next handlers want, and claiming it
     * would break them.
     */
    @Test
    void anUnmarkedPayloadWithAnUnknownArticleIsReleasedInSilence() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scan(handler, state, "010301762042200310LOT42", null, null);
        assertFalse(ctx.handled);
        verifyNoInteractions(state.ticket);
        verifyNoInteractions(handler.technicalEventService);
    }

    /**
     * An unmarked payload whose article the catalog DOES know is claimed, and only
     * then journalled — the other arm of that same fork.
     */
    @Test
    void anUnmarkedPayloadWithAKnownArticleIsClaimed() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        ScanContext ctx = scan(handler, state, "010301762042200310LOT42",
                newProduct(), newPrice());
        assertTrue(ctx.handled);
        assertEquals(NAME.toUpperCase(), onlyLine(state).label);
        verify(handler.technicalEventService)
                .log(eq(TechnicalEvent.EventType.GS1_DECODED), any());
    }

    /**
     * A MARKED payload whose article the catalog does not know is claimed and said out
     * loud: nothing else in the chain could have wanted it.
     */
    @Test
    void aMarkedPayloadWithAnUnknownArticleIsRefusedOutLoud() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scan(handler, state, ARTICLE, null, null);
        assertTrue(ctx.handled);
        verify(state.ticket).setError("ARTICLE GS1 INTROUVABLE (3017620422003)");
        verify(handler.technicalEventService)
                .log(eq(TechnicalEvent.EventType.GS1_DECODED), any());
    }

    // --------------------------------------------------
    // The article and what rides with it
    // --------------------------------------------------

    /**
     * A plain article payload creates a unit line at the catalog price, and records
     * every identifier it carried on the line ({@code LC-11-03-02/04}).
     */
    @Test
    void aPlainArticlePayloadCreatesAUnitLine() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        ScanContext ctx = scan(handler, state, ARTICLE + "(10)LOT42", newProduct(), newPrice());
        assertTrue(ctx.handled);
        TicketState.TicketItem line = onlyLine(state);
        assertEquals(EAN, line.ean);
        assertEquals(new BigDecimal("2.00"), line.unitPrice);
        assertEquals(BigDecimal.ONE, line.quantity);
        assertFalse(line.priceEmbedded);
        assertTrue(line.gs1Data.contains("(10)LOT42"));
        assertNull(line.gs1ExpiryDate);
    }

    /**
     * An identifier this version has no rule for is recorded on the line all the same —
     * {@code LC-11-03-02} in one assertion, and {@code -07} to {@code -19} with it.
     */
    @Test
    void anIdentifierWithoutARuleIsStillRecorded() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(10)LOT42(21)SN7(422)250(91)MKT",
                newProduct(), newPrice());
        String recorded = onlyLine(state).gs1Data;
        assertTrue(recorded.contains("(10)LOT42"));
        assertTrue(recorded.contains("(21)SN7"));
        assertTrue(recorded.contains("(422)250"));
        assertTrue(recorded.contains("(91)MKT"));
    }

    /**
     * An identifier NO version knows does not stop the article being registered
     * ({@code LC-11-03-03}) and is recorded like the others.
     */
    @Test
    void anUnknownIdentifierDoesNotStopTheArticle() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        ScanContext ctx = scan(handler, state, ARTICLE + "(99)ZZZ(10)LOT42",
                newProduct(), newPrice());
        assertTrue(ctx.handled);
        assertEquals(EAN, onlyLine(state).ean);
        assertTrue(onlyLine(state).gs1Data.contains("(99)ZZZ"));
    }

    /**
     * The quantity identifier becomes the line's quantity ({@code LC-11-03-16}).
     */
    @Test
    void theCountIdentifierBecomesTheQuantity() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(30)12", newProduct(), newPrice());
        assertEquals(new BigDecimal("12"), onlyLine(state).quantity);
    }

    /**
     * A quantity of zero is not a quantity: the line is a unit line — the sign leg of
     * that reading.
     */
    @Test
    void aZeroCountLeavesAUnitLine() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(30)0", newProduct(), newPrice());
        assertEquals(BigDecimal.ONE, onlyLine(state).quantity);
    }

    /**
     * A quantity that is not a number is not a quantity either — the digits leg.
     */
    @Test
    void aNonNumericCountLeavesAUnitLine() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(30)AB", newProduct(), newPrice());
        assertEquals(BigDecimal.ONE, onlyLine(state).quantity);
    }

    /**
     * A code carrying a quantity while the cashier armed the quantity key is refused
     * ({@code LC-11-03-16}): two answers to one question, and the register may not pick.
     */
    @Test
    void aCountConflictingWithTheQuantityKeyIsRefused() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        state.priceModState.set(PriceModType.QUANTITY, "uid", "ARTICLE");
        ScanContext ctx = scan(handler, state, ARTICLE + "(30)12", newProduct(), newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket).setError("QUANTITÉ DÉJÀ PORTÉE PAR LE CODE GS1");
        verify(state.ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * Another armed gesture is not a conflict: only the quantity key answers the same
     * question as the quantity identifier.
     */
    @Test
    void anotherArmedGestureIsNotAConflict() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        state.priceModState.set(PriceModType.REMISE, "uid", "ARTICLE");
        scan(handler, state, ARTICLE + "(30)12", newProduct(), newPrice());
        assertEquals(new BigDecimal("12"), onlyLine(state).quantity);
    }

    /**
     * The quantity key armed on a code that carries NO quantity is not a conflict
     * either — the other leg of the compound guard.
     */
    @Test
    void theQuantityKeyWithoutACountIsNotAConflict() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        state.priceModState.set(PriceModType.QUANTITY, "uid", "ARTICLE");
        scan(handler, state, ARTICLE, newProduct(), newPrice());
        assertEquals(BigDecimal.ONE, onlyLine(state).quantity);
    }

    /**
     * A net weight becomes the quantity, priced at the catalog's price per kilogram
     * ({@code LC-11-03-20}).
     */
    @Test
    void aNetWeightIsPricedFromTheCatalog() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3103)000195", newProduct(), newPrice());
        TicketState.TicketItem line = onlyLine(state);
        assertEquals(new BigDecimal("0.195"), line.quantity);
        assertEquals(new BigDecimal("2.00"), line.unitPrice);
        assertFalse(line.priceEmbedded);
    }

    /**
     * An amount payable IS the line total and the register does not re-price it
     * ({@code LC-11-03-21}).
     */
    @Test
    void anAmountPayableIsTheLineTotal() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3902)0357", newProduct(), newPrice());
        TicketState.TicketItem line = onlyLine(state);
        assertEquals(new BigDecimal("3.57"), line.unitPrice);
        assertEquals(BigDecimal.ONE, line.quantity);
        assertTrue(line.priceEmbedded);
    }

    /**
     * The variable-measure amount WINS over the plain one: it is the more specific of
     * the two ({@code LC-11-03-22}).
     */
    @Test
    void theVariableMeasureAmountWins() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3902)0357(3922)0499", newProduct(), newPrice());
        assertEquals(new BigDecimal("4.99"), onlyLine(state).unitPrice);
    }

    /**
     * An amount wins over a weight: a label that states what to pay has already done
     * the weighing.
     */
    @Test
    void anAmountWinsOverAWeight() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3103)000195(3922)0499", newProduct(), newPrice());
        TicketState.TicketItem line = onlyLine(state);
        assertEquals(new BigDecimal("4.99"), line.unitPrice);
        assertEquals(BigDecimal.ONE, line.quantity);
    }

    /**
     * An amount of zero is no amount: the line falls back to the catalog — the sign leg
     * of that guard.
     */
    @Test
    void aZeroAmountFallsBackToTheCatalog() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3902)0000", newProduct(), newPrice());
        assertEquals(new BigDecimal("2.00"), onlyLine(state).unitPrice);
        assertFalse(onlyLine(state).priceEmbedded);
    }

    /**
     * A weight of zero is no weight either — the same leg on the other reading.
     */
    @Test
    void aZeroWeightLeavesAUnitLine() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(3103)000000", newProduct(), newPrice());
        assertEquals(BigDecimal.ONE, onlyLine(state).quantity);
    }

    /**
     * An article with no current price is registered at zero with the configured
     * default rate, exactly as the catalog handler does.
     */
    @Test
    void anArticleWithoutAPriceIsRegisteredAtZero() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE, newProduct(), null);
        TicketState.TicketItem line = onlyLine(state);
        assertEquals(BigDecimal.ZERO, line.unitPrice);
        assertEquals(new BigDecimal("0.20"), line.vatRate);
    }

    /**
     * An article forbidden to sell is refused, no line.
     */
    @Test
    void aForbiddenArticleIsRefused() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        Product product = newProduct();
        product.forbiddenToSale = true;
        ScanContext ctx = scan(handler, state, ARTICLE, product, newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket).setError("PRODUIT INTERDIT À LA VENTE");
        verify(state.ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    // --------------------------------------------------
    // The expiry rules (LC-11-03-12 / -13)
    // --------------------------------------------------

    /**
     * The expiry date is recorded on the line and reaches the valuation engine through
     * it, whatever the shop's alert level ({@code LC-11-03-12}).
     */
    @Test
    void theExpiryDateIsRecordedOnTheLine() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        LocalDate future = LocalDate.now().plusMonths(6);
        scan(handler, state, ARTICLE + "(17)" + future.format(DateTimeFormatter.ofPattern("yyMMdd")),
                newProduct(), newPrice());
        assertEquals(future, onlyLine(state).gs1ExpiryDate);
    }

    /**
     * A shop that administers no rule says nothing about an expired article and
     * registers it — the silence the default is chosen for.
     */
    @Test
    void withoutARuleAnExpiredArticleIsRegisteredInSilence() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newState();
        scan(handler, state, ARTICLE + "(17)200101", newProduct(), newPrice());
        assertEquals(1, state.ticket.items.size());
    }

    /**
     * At the informative level, an expired article is announced AND registered.
     */
    @Test
    void informativeLevelAnnouncesAnExpiredArticleAndRegistersIt() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("INFO");
        PosState state = newState();
        scan(handler, state, ARTICLE + "(17)200101", newProduct(), newPrice());
        assertEquals(1, state.ticket.items.size());
        assertEquals("ARTICLE PERIME LE 01/01/2020", state.ticket.transientError);
    }

    /**
     * At the blocking level, an expired article is refused.
     */
    @Test
    void blockingLevelRefusesAnExpiredArticle() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("BLOCK");
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scan(handler, state, ARTICLE + "(17)200101", newProduct(), newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket).setError("ARTICLE PERIME LE 01/01/2020");
        verify(state.ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A date that is NEAR is a warning even at the blocking level: the article is
     * still within its date, and refusing it would refuse a sale the shop allows.
     */
    @Test
    void aNearDateIsOnlyAWarningEvenWhenBlocking() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("BLOCK");
        when(handler.posSettingsService.gs1ExpiryWarnDays()).thenReturn(10);
        PosState state = newState();
        LocalDate soon = LocalDate.now().plusDays(3);
        scan(handler, state, ARTICLE + "(17)" + soon.format(DateTimeFormatter.ofPattern("yyMMdd")),
                newProduct(), newPrice());
        assertEquals(1, state.ticket.items.size());
        assertEquals("DATE COURTE : " + soon.format(DAY), state.ticket.transientError);
    }

    /**
     * A date beyond the warning window says nothing at all — the far leg of the same
     * comparison.
     */
    @Test
    void aFarDateSaysNothing() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("BLOCK");
        when(handler.posSettingsService.gs1ExpiryWarnDays()).thenReturn(10);
        PosState state = newState();
        LocalDate far = LocalDate.now().plusMonths(6);
        scan(handler, state, ARTICLE + "(17)" + far.format(DateTimeFormatter.ofPattern("yyMMdd")),
                newProduct(), newPrice());
        assertEquals(1, state.ticket.items.size());
        assertNull(state.ticket.transientError);
    }

    /**
     * A negative warning window is read as none: today's date still warns, and a
     * mistyped parameter cannot make the register silent about an expired article.
     */
    @Test
    void aNegativeWarningWindowIsReadAsNone() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("INFO");
        when(handler.posSettingsService.gs1ExpiryWarnDays()).thenReturn(-5);
        PosState state = newState();
        LocalDate today = LocalDate.now();
        scan(handler, state, ARTICLE + "(17)" + today.format(DateTimeFormatter.ofPattern("yyMMdd")),
                newProduct(), newPrice());
        assertEquals("DATE COURTE : " + today.format(DAY), state.ticket.transientError);
    }

    /**
     * An unreadable expiry date is no date: the article is registered and nothing is
     * said — the null leg of the rule.
     */
    @Test
    void anUnreadableExpiryDateSaysNothing() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1ExpiryAlert()).thenReturn("BLOCK");
        PosState state = newState();
        scan(handler, state, ARTICLE + "(17)261340", newProduct(), newPrice());
        assertEquals(1, state.ticket.items.size());
        assertNull(state.ticket.transientError);
    }

    // --------------------------------------------------
    // The settlements (LC-11-03-05 / -06)
    // --------------------------------------------------

    /**
     * Builds an active settlement type with a manual amount.
     *
     * @param code the type's code
     * @return the type
     */
    private CouponType newCouponType(String code) {
        CouponType type = new CouponType();
        type.code = code;
        type.label = "CHEQUE CADEAU";
        type.amountSource = CouponType.AmountSource.MANUAL;
        return type;
    }

    /**
     * Runs a settlement payload through the handler with the referential answering
     * that type.
     *
     * @param handler the handler under test
     * @param state   the register state
     * @param payload the scanned payload
     * @param type    the settlement type the referential knows, or null
     * @return the context
     */
    private ScanContext scanSettlement(Gs1ScanHandler handler, PosState state, String payload,
            CouponType type) {
        ScanContext ctx = new ScanContext(payload, state);
        try (MockedStatic<CouponType> types = mockStatic(CouponType.class)) {
            types.when(() -> CouponType.findActiveByCode(any())).thenReturn(type);
            handler.handle(ctx);
        }
        return ctx;
    }

    /**
     * A gift document whose issuer the shop administered settles the amount the paper
     * carries ({@code LC-11-03-05}, {@code -21}).
     */
    @Test
    void anAdministeredGiftDocumentSettlesItsEncodedAmount() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1GiftCouponTypes()).thenReturn("9526000:CADEAU");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        CouponType type = newCouponType("CADEAU");
        ScanContext ctx = scanSettlement(handler, state,
                "(253)9526000134367ABC(3902)1500", type);
        assertTrue(ctx.handled);
        verify(handler.voucherService).applyManualVoucher(eq(state), eq(type),
                eq("9526000134367ABC"), eq(new BigDecimal("15.00")));
    }

    /**
     * Without an encoded amount, a manual-amount type opens the amount panel, exactly
     * as a scanned voucher does.
     */
    @Test
    void aGiftDocumentWithoutAnAmountOpensThePanel() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1GiftCouponTypes()).thenReturn("9526000:CADEAU");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        ScanContext ctx = scanSettlement(handler, state, "(253)9526000134367ABC",
                newCouponType("CADEAU"));
        assertTrue(ctx.handled);
        assertTrue(state.payment.voucherPanelOpen);
        assertEquals("9526000134367ABC", state.payment.pendingVoucherNumber);
    }

    /**
     * A registry-backed type never asks for an amount: the registry knows the balance.
     */
    @Test
    void aRegistryBackedGiftDocumentAsksNoAmount() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1GiftCouponTypes()).thenReturn("9526000:CADEAU");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        CouponType type = newCouponType("CADEAU");
        type.amountSource = CouponType.AmountSource.REGISTRY;
        scanSettlement(handler, state, "(253)9526000134367ABC", type);
        verify(handler.voucherService).applyRegistryVoucher(eq(state), eq(type), any());
    }

    /**
     * A type whose amount is encoded in the number is applied straight — the last leg
     * of the amount fork.
     */
    @Test
    void anEncodedAmountTypeIsAppliedStraight() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1GiftCouponTypes()).thenReturn("9526000:CADEAU");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        CouponType type = newCouponType("CADEAU");
        type.amountSource = CouponType.AmountSource.ENCODED;
        scanSettlement(handler, state, "(253)9526000134367ABC", type);
        verify(handler.voucherService).applyEncodedVoucher(eq(state), eq(type), any());
    }

    /**
     * The LONGEST administered issuer prefix wins: prefixes are nested by
     * construction, and a shop's finer rule must beat its general one.
     */
    @Test
    void theLongestIssuerPrefixWins() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1GiftCouponTypes())
                .thenReturn("952:GENERAL;9526000:PARTENAIRE;;bad-entry");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        ScanContext ctx = new ScanContext("(253)9526000134367ABC", state);
        try (MockedStatic<CouponType> types = mockStatic(CouponType.class)) {
            types.when(() -> CouponType.findActiveByCode("PARTENAIRE"))
                    .thenReturn(newCouponType("PARTENAIRE"));
            handler.handle(ctx);
        }
        assertTrue(ctx.handled);
        // A coupon whose amount is keyed and whose code carries none opens the amount
        // panel: applyManualVoucher settles only when the code ITSELF states the amount
        // in (390n), which this payload does not.
        assertTrue(state.payment.voucherPanelOpen);
        assertTrue(state.payment.pendingVoucherNeedsAmount);
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * A gift document whose issuer the shop administered for nobody is refused with a
     * message naming the reason.
     */
    @Test
    void anUnadministeredGiftDocumentIsRefused() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        ScanContext ctx = scanSettlement(handler, state, "(253)9526000134367ABC", null);
        assertTrue(ctx.handled);
        verify(state.ticket).setError("CHEQUE CADEAU GS1 NON PARAMETRE");
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * A coupon uses its own administered type ({@code LC-11-03-06}).
     */
    @Test
    void anAdministeredCouponUsesItsOwnType() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        CouponType type = newCouponType("COUPON");
        ScanContext ctx = scanSettlement(handler, state, "(255)952600013436712345(3902)0100", type);
        assertTrue(ctx.handled);
        verify(handler.voucherService).applyManualVoucher(eq(state), eq(type), any(),
                eq(new BigDecimal("1.00")));
    }

    /**
     * A coupon the shop administered for nothing is refused with its own message.
     */
    @Test
    void anUnadministeredCouponIsRefused() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        ScanContext ctx = scanSettlement(handler, state, "(255)952600013436712345", null);
        assertTrue(ctx.handled);
        verify(state.ticket).setError("COUPON GS1 NON PARAMETRE");
    }

    /**
     * A settlement paper presented outside the payment is recognised, and the cashier
     * is told the moment rather than that the code is unknown.
     */
    @Test
    void aSettlementOutsideThePaymentTellsTheMoment() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scanSettlement(handler, state, "(255)952600013436712345",
                newCouponType("COUPON"));
        assertTrue(ctx.handled);
        verify(state.ticket).setError("BON VALABLE EN PHASE PAIEMENT");
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * An expired coupon is refused at the blocking level ({@code LC-11-03-13}).
     */
    @Test
    void anExpiredCouponIsRefusedWhenBlocking() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        when(handler.posSettingsService.gs1CouponExpiryAlert()).thenReturn("BLOCK");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        ScanContext ctx = scanSettlement(handler, state,
                "(255)952600013436712345(17)200101", newCouponType("COUPON"));
        assertTrue(ctx.handled);
        verify(state.ticket).setError("BON EXPIRE LE 01/01/2020");
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * At the informative level it is announced and applied all the same.
     */
    @Test
    void anExpiredCouponIsAnnouncedAndAppliedWhenInformative() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        when(handler.posSettingsService.gs1CouponExpiryAlert()).thenReturn("INFO");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        scanSettlement(handler, state, "(255)952600013436712345(17)200101",
                newCouponType("COUPON"));
        verify(state.ticket).setError("BON EXPIRE LE 01/01/2020");
        // A coupon whose amount is keyed and whose code carries none opens the amount
        // panel: applyManualVoucher settles only when the code ITSELF states the amount
        // in (390n), which this payload does not.
        assertTrue(state.payment.voucherPanelOpen);
        assertTrue(state.payment.pendingVoucherNeedsAmount);
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * A coupon whose date is TODAY is still valid: the day printed on the paper is a
     * day the customer may use it.
     */
    @Test
    void aCouponExpiringTodayIsStillValid() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        when(handler.posSettingsService.gs1CouponExpiryAlert()).thenReturn("BLOCK");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        scanSettlement(handler, state, "(255)952600013436712345(17)" + today,
                newCouponType("COUPON"));
        // A coupon whose amount is keyed and whose code carries none opens the amount
        // panel: applyManualVoucher settles only when the code ITSELF states the amount
        // in (390n), which this payload does not.
        assertTrue(state.payment.voucherPanelOpen);
        assertTrue(state.payment.pendingVoucherNeedsAmount);
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * Without an administered level, an expired coupon passes — the silence leg.
     */
    @Test
    void withoutALevelAnExpiredCouponPasses() {
        Gs1ScanHandler handler = newHandler();
        when(handler.posSettingsService.gs1CouponType()).thenReturn("COUPON");
        PosState state = newStateWithMockedTicket();
        state.payment.paymentInProgress = true;
        scanSettlement(handler, state, "(255)952600013436712345(17)200101",
                newCouponType("COUPON"));
        // A coupon whose amount is keyed and whose code carries none opens the amount
        // panel: applyManualVoucher settles only when the code ITSELF states the amount
        // in (390n), which this payload does not.
        assertTrue(state.payment.voucherPanelOpen);
        assertTrue(state.payment.pendingVoucherNeedsAmount);
        verifyNoInteractions(handler.voucherService);
    }

    /**
     * A code that names the lot AND names a recalled one is refused: this is the one
     * case where the register knows which pack is in the basket, so it does not ask
     * the cashier to read it ({@code LC-02-03-12}).
     */
    @Test
    void aScannedRecalledLotIsRefused() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        Product product = newProduct();
        product.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123;L456");
        ScanContext ctx = scan(handler, state, ARTICLE + "(10)L456", product, newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket).setError("PRODUIT EN RAPPEL - LOT L456");
        verify(state.ticket, never()).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A code that names a lot the recall does NOT cover rings the line and says
     * nothing: the pack in the basket is not one of the withdrawn ones.
     */
    @Test
    void aScannedLotOutsideTheRecallIsRungWithoutAWord() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        Product product = newProduct();
        product.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123");
        ScanContext ctx = scan(handler, state, ARTICLE + "(10)L999", product, newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket, never()).setError(anyString());
        verify(state.ticket, never()).setNotice(anyString());
        verify(state.ticket).addItem(any(), any(), any(), any(), any(), any());
    }

    /**
     * A code that names NO lot on an article under lot recall rings the line and lists
     * the recalled lots: only the cashier, holding the pack, can read the one printed
     * on it ({@code LC-02-03-13}).
     */
    @Test
    void aCodeWithoutALotListsTheRecalledOnes() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        Product product = newProduct();
        product.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .RECALL_LOTS, "L123;L456");
        ScanContext ctx = scan(handler, state, ARTICLE, product, newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket).addItem(any(), any(), any(), any(), any(), any());
        verify(state.ticket).setNotice("PRODUIT EN RAPPEL - LOTS : L123, L456");
    }

    /**
     * An article under NO lot recall says nothing at all, whether or not the code
     * names a lot — the arm that keeps the message off every ordinary sale.
     */
    @Test
    void anArticleWithoutALotRecallSaysNothing() {
        Gs1ScanHandler handler = newHandler();
        PosState state = newStateWithMockedTicket();
        ScanContext ctx = scan(handler, state, ARTICLE + "(10)L456", newProduct(), newPrice());
        assertTrue(ctx.handled);
        verify(state.ticket, never()).setNotice(anyString());
        PosState other = newStateWithMockedTicket();
        scan(handler, other, ARTICLE, newProduct(), newPrice());
        verify(other.ticket, never()).setNotice(anyString());
    }
}
