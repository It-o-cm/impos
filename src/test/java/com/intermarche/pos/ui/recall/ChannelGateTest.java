package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.payment.PaymentState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelGate}: each refusal a channel may oppose to a
 * basket, the preparation of an admitted basket (generic EAN of the unknown
 * articles, coupons, promotion channel) and the completion after its load
 * (additions lock, mobility settlements and complementary tender, notices).
 * The rules are a mock answering per test, the messages the French bundle, the
 * register state a real one.
 */
class ChannelGateTest {

    /**
     * A gate over mocked rules — every channel open, every retrieval admitted —
     * and a clock fixed on 24 October 2026.
     *
     * @return the gate
     */
    private ChannelGate gate() {
        ChannelGate gate = new ChannelGate();
        gate.rules = mock(ChannelRules.class);
        gate.i18n = TestMessages.french();
        gate.ticketPersistenceService = mock(TicketPersistenceService.class);
        gate.clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneId.of("Europe/Paris"));
        when(gate.rules.enabled(any())).thenReturn(true);
        when(gate.rules.retrievalAllowed(any(), anyBoolean())).thenReturn(true);
        when(gate.rules.selfScanAddsBefore()).thenReturn(true);
        when(gate.rules.selfScanAddsAfter()).thenReturn(true);
        when(gate.rules.selfScanCoupons()).thenReturn(true);
        when(gate.rules.unknownItemEan(any())).thenReturn("");
        when(gate.rules.mobilityFictitiousTender()).thenReturn("");
        return gate;
    }

    /**
     * A document of a kind, one line of each given EAN.
     *
     * @param kind the kind
     * @param eans the line EANs
     * @return the document
     */
    private RecalledDocument doc(RecalledDocument.Kind kind, String... eans) {
        List<RecalledDocument.Line> lines = new ArrayList<>();
        for (String ean : eans) {
            lines.add(new RecalledDocument.Line(ean, "ART " + ean, BigDecimal.ONE, new BigDecimal("2.00"),
                    new BigDecimal("0.2")));
        }
        return new RecalledDocument(kind, "D1", lines, null);
    }

    /**
     * A product of the referential.
     *
     * @param ean its EAN
     * @param forbidden whether it is forbidden to sale
     * @param label its checkout label, or null
     * @param message its information message, or null
     * @return the product
     */
    private Product product(String ean, boolean forbidden, String label, String message) {
        Product product = new Product();
        product.ean = ean;
        product.name = "NOM " + ean;
        product.checkoutLabel = label;
        product.forbiddenToSale = forbidden;
        product.infoMessage = message;
        return product;
    }

    /**
     * A kind no channel rule applies to passes every gate untouched.
     */
    @Test
    void aKindWithoutChannelIsNeverRefused() {
        ChannelGate gate = gate();
        when(gate.rules.enabled(any())).thenReturn(false);
        assertNull(gate.refusal(new PosState(), doc(RecalledDocument.Kind.CLICK_COLLECT, "1")));
    }

    /**
     * A channel the store did not open refuses its baskets (BO-10-01-01, -15).
     */
    @Test
    void aClosedChannelRefuses() {
        ChannelGate gate = gate();
        when(gate.rules.enabled(SalesChannel.SELF_SCAN)).thenReturn(false);
        assertEquals("SELFSCANNING : CANAL NON ACTIVÉ SUR CE MAGASIN",
                gate.refusal(new PosState(), doc(RecalledDocument.Kind.SELF_SCAN, "1")));
    }

    /**
     * A keyed code under a scan-only mode, and a scanned one under a keyed-only
     * mode, are each refused with the gesture to make (BO-10-01-16, -25, -30, -40).
     */
    @Test
    void aRetrievalTheModeDoesNotAdmitIsRefused() {
        ChannelGate gate = gate();
        when(gate.rules.retrievalAllowed(any(), anyBoolean())).thenReturn(false);
        RecalledDocument keyed = doc(RecalledDocument.Kind.QBUSTING, "1");
        keyed.keyed = true;
        assertEquals("CE DOCUMENT SE RÉCUPÈRE PAR LE SCAN DE SON CODE-BARRES", gate.refusal(new PosState(), keyed));
        assertEquals("CE DOCUMENT SE RÉCUPÈRE PAR LA SAISIE DE SON CODE À L'ÉCRAN RAPPEL",
                gate.refusal(new PosState(), doc(RecalledDocument.Kind.QBUSTING, "1")));
    }

    /**
     * Under the customer identifier, a basket found by its own code is refused
     * and one found by the customer number passes (BO-10-01-18).
     */
    @Test
    void theCustomerIdentifierWantsTheBasketFoundByCustomer() {
        ChannelGate gate = gate();
        when(gate.rules.selfScanByCustomer()).thenReturn(true);
        RecalledDocument basket = doc(RecalledDocument.Kind.SELF_SCAN, "1");
        assertEquals("PANIER SELFSCANNING : SAISIR LE N° CLIENT À L'ÉCRAN RAPPEL", gate.refusal(new PosState(), basket));
        basket.byCustomer = true;
        assertNull(gate.refusal(new PosState(), basket));
    }

    /**
     * Where no article may precede the basket, a sale already holding one
     * refuses it, an empty one takes it (BO-10-01-17).
     */
    @Test
    void articlesBeforeTheBasketAreJudgedOnTheSale() {
        ChannelGate gate = gate();
        when(gate.rules.selfScanAddsBefore()).thenReturn(false);
        PosState state = new PosState();
        assertNull(gate.refusal(state, doc(RecalledDocument.Kind.SELF_SCAN, "1")));
        state.ticket.addItem("9", null, "X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
        assertEquals("PANIER SELFSCANNING REFUSÉ : DES ARTICLES SONT DÉJÀ SAISIS",
                gate.refusal(state, doc(RecalledDocument.Kind.SELF_SCAN, "1")));
    }

    /**
     * A sale voucher of another day is refused; one of today, an undated one, a
     * short date and an unreadable date are not (BO-10-01-40).
     */
    @Test
    void aSaleVoucherIsValidOnTheDayItWasWrittenOnly() {
        ChannelGate gate = gate();
        RecalledDocument voucher = doc(RecalledDocument.Kind.BON_VENTE, "1");
        assertNull(gate.refusal(new PosState(), voucher));
        voucher.orderDate = "2026-10";
        assertNull(gate.refusal(new PosState(), voucher));
        voucher.orderDate = "2026-10-24T08:00:00";
        assertNull(gate.refusal(new PosState(), voucher));
        voucher.orderDate = "2026-13-45T08:00:00";
        assertNull(gate.refusal(new PosState(), voucher));
        voucher.orderDate = "2026-10-23T18:00:00";
        assertEquals("BON DE VENTE EXPIRÉ : VALABLE LE JOUR MÊME UNIQUEMENT", gate.refusal(new PosState(), voucher));
    }

    /**
     * Under a REJECT rule the first forbidden article is named — its label, its
     * code and its message — the null, codeless, unknown and allowed lines
     * passing; under ACCEPT nothing is looked at (BO-10-01-23, -24, -32, -37).
     */
    @Test
    void aForbiddenArticleUnderARejectRuleIsNamed() {
        ChannelGate gate = gate();
        RecalledDocument basket = doc(RecalledDocument.Kind.MOBILITY, "1", "2", "3", "4");
        basket.lines.add(0, null);
        basket.lines.add(1, new RecalledDocument.Line(null, "LIBRE", BigDecimal.ONE, BigDecimal.ONE, null));
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("2")).thenReturn(product("2", false, null, null));
            products.when(() -> Product.findByEan("3")).thenReturn(product("3", true, "WHISKY 70CL", "Majeurs uniquement"));
            products.when(() -> Product.findByEan("4")).thenReturn(product("4", true, null, null));
            assertNull(gate.refusal(new PosState(), basket));
            when(gate.rules.rejectsForbidden(SalesChannel.MOBILITY)).thenReturn(true);
            assertEquals("VENTE INTERDITE : WHISKY 70CL (CODE 3) Majeurs uniquement", gate.refusal(new PosState(), basket));
            basket.lines.remove(4);
            assertEquals("VENTE INTERDITE : NOM 4 (CODE 4)", gate.refusal(new PosState(), basket));
            basket.lines.remove(4);
            assertNull(gate.refusal(new PosState(), basket));
        }
    }

    /**
     * A refused basket is not admitted: the reason is on the band, nothing set.
     */
    @Test
    void aRefusedBasketIsNotAdmitted() {
        ChannelGate gate = gate();
        when(gate.rules.enabled(SalesChannel.ECOMMERCE)).thenReturn(false);
        when(gate.rules.promotionChannel(any())).thenReturn("WEB");
        PosState state = new PosState();
        assertNull(gate.admit(state, doc(RecalledDocument.Kind.ECOMMERCE, "1")));
        assertEquals("E-COMMERCE : CANAL NON ACTIVÉ SUR CE MAGASIN", state.ticket.transientError);
        assertNull(state.ticket.promotionChannel);
    }

    /**
     * An admitted basket sets the promotion channel its channel names, and leaves
     * the ticket's one alone when its channel names none (BO-10-01-13, -22).
     */
    @Test
    void anAdmittedBasketSetsItsPromotionChannel() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        RecalledDocument basket = doc(RecalledDocument.Kind.QBUSTING, "1");
        assertSame(basket, gate.admit(state, basket));
        assertNull(state.ticket.promotionChannel);
        when(gate.rules.promotionChannel(SalesChannel.QBUSTING)).thenReturn("OTHER");
        gate.admit(state, basket);
        assertEquals("OTHER", state.ticket.promotionChannel);
    }

    /**
     * With a generic EAN, an unknown article is carried on it, its label kept
     * and marked with its own code; the null, codeless, blank-coded, already
     * generic and known lines are kept as they are (BO-10-01-06, -21).
     */
    @Test
    void theUnknownArticlesAreCarriedOnTheGenericEanAndMarked() {
        ChannelGate gate = gate();
        when(gate.rules.unknownItemEan(SalesChannel.SELF_SCAN)).thenReturn("2999");
        RecalledDocument basket = doc(RecalledDocument.Kind.SELF_SCAN, "1", "7", "2999");
        basket.lines.add(null);
        basket.lines.add(new RecalledDocument.Line(null, "LIBRE", BigDecimal.ONE, BigDecimal.ONE, null));
        basket.lines.add(new RecalledDocument.Line(" ", "BLANC", BigDecimal.ONE, BigDecimal.ONE, null));
        basket.lines.add(new RecalledDocument.Line("8", null, new BigDecimal("2"), BigDecimal.TEN, null,
                BigDecimal.ONE));
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("1")).thenReturn(product("1", false, null, null));
            gate.admit(new PosState(), basket);
        }
        assertEquals(2, basket.unknownMarked);
        assertEquals("1", basket.lines.get(0).ean());
        assertEquals("2999", basket.lines.get(1).ean());
        assertEquals("ART 7 [ART. INCONNU 7]", basket.lines.get(1).label());
        assertEquals(new BigDecimal("2.00"), basket.lines.get(1).unitPrice());
        assertEquals("ART 2999", basket.lines.get(2).label());
        assertNull(basket.lines.get(3));
        assertEquals("LIBRE", basket.lines.get(4).label());
        assertEquals("BLANC", basket.lines.get(5).label());
        assertEquals("[ART. INCONNU 8]", basket.lines.get(6).label());
        assertEquals(BigDecimal.ONE, basket.lines.get(6).deliveredQuantity());
    }

    /**
     * Where the self-scanning coupons are not taken back, a basket carrying some
     * loses them and counts them; one carrying none, a null list, an accepting
     * store or another channel keep theirs (BO-10-01-20).
     */
    @Test
    void theCouponsOfASelfScanningBasketFollowTheRule() {
        ChannelGate gate = gate();
        RecalledDocument basket = doc(RecalledDocument.Kind.SELF_SCAN, "1");
        basket.vouchers = new ArrayList<>(List.of(new RecalledDocument.Voucher("B1", "BON", BigDecimal.ONE)));
        gate.admit(new PosState(), basket);
        assertEquals(1, basket.vouchers.size());
        when(gate.rules.selfScanCoupons()).thenReturn(false);
        RecalledDocument qbusting = doc(RecalledDocument.Kind.QBUSTING, "1");
        qbusting.vouchers = new ArrayList<>(basket.vouchers);
        gate.admit(new PosState(), qbusting);
        assertEquals(1, qbusting.vouchers.size());
        RecalledDocument empty = doc(RecalledDocument.Kind.SELF_SCAN, "1");
        gate.admit(new PosState(), empty);
        assertEquals(0, empty.couponsDropped);
        empty.vouchers = null;
        gate.admit(new PosState(), empty);
        assertNull(empty.vouchers);
        gate.admit(new PosState(), basket);
        assertEquals(1, basket.couponsDropped);
        assertTrue(basket.vouchers.isEmpty());
    }

    /**
     * After the load, a kind without channel is left alone; a self-scanning
     * basket locks the additions only where the rule forbids them, and its
     * notices say what was marked and what was dropped (BO-10-01-17, -20, -21).
     */
    @Test
    void afterTheLoadTheAdditionsLockAndTheNotices() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        gate.afterLoad(state, doc(RecalledDocument.Kind.CLICK_COLLECT, "1"));
        assertNull(state.ticket.recalledChannel);
        RecalledDocument basket = doc(RecalledDocument.Kind.SELF_SCAN, "1");
        gate.afterLoad(state, basket);
        assertEquals("SELF_SCAN", state.ticket.recalledChannel);
        assertFalse(state.ticket.additionsLocked);
        assertNull(state.ticket.transientError);
        when(gate.rules.selfScanAddsAfter()).thenReturn(false);
        basket.unknownMarked = 2;
        basket.couponsDropped = 1;
        gate.afterLoad(state, basket);
        assertTrue(state.ticket.additionsLocked);
        assertEquals("2 ARTICLE(S) INCONNU(S) MARQUÉ(S) — 1 COUPON(S) DU PANIER NON REPRIS", state.ticket.transientError);
        assertTrue(state.ticket.transientWarn);
    }

    /**
     * A mobility transaction restates the settlements its device collected —
     * a settlement naming no tender as a card, the empty, null and non-positive
     * ones skipped — and persists them on the draft (BO-10-01-39).
     */
    @Test
    void aMobilityTransactionRestatesItsSettlements() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        state.payment.ticketDbId = 5L;
        state.ticket.totalAmount = new BigDecimal("20.00");
        RecalledDocument transaction = doc(RecalledDocument.Kind.MOBILITY, "1");
        transaction.payments = new ArrayList<>(Arrays.asList(null,
                new RecalledDocument.Payment("CASH", null),
                new RecalledDocument.Payment("CASH", BigDecimal.ZERO),
                new RecalledDocument.Payment(null, new BigDecimal("5.00")),
                new RecalledDocument.Payment(" ", new BigDecimal("3.00")),
                new RecalledDocument.Payment(" TR ", new BigDecimal("2.00"))));
        gate.afterLoad(state, transaction);
        assertEquals(3, state.payment.payments.size());
        assertEquals("CARD", state.payment.payments.get(0).method);
        assertEquals("CARD", state.payment.payments.get(1).method);
        assertEquals("TR", state.payment.payments.get(2).method);
        assertEquals(new BigDecimal("10.00"), state.payment.paidAmount);
        verify(gate.ticketPersistenceService, times(3)).addPaymentToTicket(eq(5L), any(PaymentState.PaymentEntry.class));
        assertEquals("MOBILITY", state.ticket.recalledChannel);
    }

    /**
     * A mobility transaction paid short gets the complementary tender for the
     * difference where one is administered, and says so; paid in full, or with
     * no tender administered, it gets none (BO-10-01-39).
     */
    @Test
    void aMobilityTransactionPaidShortGetsTheComplementaryTender() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        state.ticket.totalAmount = new BigDecimal("20.00");
        RecalledDocument transaction = doc(RecalledDocument.Kind.MOBILITY, "1");
        transaction.payments = null;
        gate.afterLoad(state, transaction);
        assertTrue(state.payment.payments.isEmpty());
        when(gate.rules.mobilityFictitiousTender()).thenReturn("VOUCHER");
        transaction.payments = List.of(new RecalledDocument.Payment("CARD", new BigDecimal("18.50")));
        gate.afterLoad(state, transaction);
        assertEquals(2, state.payment.payments.size());
        assertEquals("VOUCHER", state.payment.payments.get(1).method);
        assertEquals(new BigDecimal("1.50"), state.payment.payments.get(1).amount);
        assertEquals("MOBILITÉ : VOUCHER AJOUTÉ POUR 1,50 €", state.ticket.transientError);
        verify(gate.ticketPersistenceService, never()).addPaymentToTicket(any(), any());
        PosState paid = new PosState();
        paid.ticket.totalAmount = new BigDecimal("10.00");
        transaction.payments = List.of(new RecalledDocument.Payment("CARD", new BigDecimal("10.00")));
        gate.afterLoad(paid, transaction);
        assertEquals(1, paid.payment.payments.size());
    }

    /**
     * In training mode the mobility settlements are taken but never persisted.
     */
    @Test
    void aTrainingSaleNeverPersistsTheMobilitySettlements() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        state.trainingMode = true;
        state.payment.ticketDbId = 5L;
        RecalledDocument transaction = doc(RecalledDocument.Kind.MOBILITY, "1");
        transaction.payments = List.of(new RecalledDocument.Payment("CARD", new BigDecimal("4.00")));
        gate.afterLoad(state, transaction);
        assertEquals(1, state.payment.payments.size());
        verify(gate.ticketPersistenceService, never()).addPaymentToTicket(any(), any());
    }

    /**
     * Once locked, any addition is refused out loud; unlocked, none is.
     */
    @Test
    void theAdditionsLockRefusesAnyAddition() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        assertFalse(gate.refusesAddition(state));
        state.ticket.additionsLocked = true;
        assertTrue(gate.refusesAddition(state));
        assertEquals("AJOUT D'ARTICLE INTERDIT APRÈS LE PANIER SELFSCANNING", state.ticket.transientError);
    }

    /**
     * Once locked, a scanned article of the referential, a weighed label or a
     * GS1 article code is refused, any other code goes on; unlocked, nothing is even looked up.
     */
    @Test
    void theAdditionsLockRefusesTheScannedArticlesOnly() {
        ChannelGate gate = gate();
        PosState state = new PosState();
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            assertFalse(gate.refusesScannedArticle(state, "3000000000001"));
            products.verify(() -> Product.findByEan(anyString()), never());
            state.ticket.additionsLocked = true;
            assertTrue(gate.refusesScannedArticle(state, "2123456789012"));
            assertTrue(gate.refusesScannedArticle(state, "0103012345678901172610311012"));
            products.when(() -> Product.findByEan("3000000000001")).thenReturn(product("3000000000001", false, null, null));
            assertTrue(gate.refusesScannedArticle(state, "3000000000001"));
            assertFalse(gate.refusesScannedArticle(state, "9999999999"));
        }
    }
}
