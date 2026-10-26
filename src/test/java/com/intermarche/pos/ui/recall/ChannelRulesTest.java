package com.intermarche.pos.ui.recall;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelRules}: each family of {@code channel.*} keys is
 * dispatched to the right channel, every closed word is read tolerantly (case,
 * blanks, null), and an unknown or empty word falls back on the register's
 * historical behaviour.
 */
class ChannelRulesTest {

    /**
     * Rules over a fresh mocked catalogue, every getter answering null.
     *
     * @return the rules and their catalogue
     */
    private ChannelRules rules() {
        return new ChannelRules(mock(PosSettingsService.class));
    }

    /**
     * The e-commerce and self-scanning channels have an activation of their own;
     * every other channel, and no channel at all, is open (BO-10-01-01, -15).
     */
    @Test
    void activationIsAdministeredForTheEcommerceAndSelfScanningOnly() {
        ChannelRules rules = rules();
        assertFalse(rules.enabled(SalesChannel.ECOMMERCE));
        assertFalse(rules.enabled(SalesChannel.SELF_SCAN));
        when(rules.posSettingsService.ecommerceEnabled()).thenReturn(true);
        when(rules.posSettingsService.selfScanEnabled()).thenReturn(true);
        assertTrue(rules.enabled(SalesChannel.ECOMMERCE));
        assertTrue(rules.enabled(SalesChannel.SELF_SCAN));
        assertTrue(rules.enabled(SalesChannel.COMMERCIAL));
        assertTrue(rules.enabled(null));
    }

    /**
     * POS takes the register's price, TRANSACTION the document's, and any other
     * word — or no channel — defers to the global rule, whichever way it says
     * (BO-10-01-07, -19, -29, -33, -36, -41).
     */
    @Test
    void thePriceSourceOfEachChannelFallsBackOnTheGlobalRule() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        when(s.ecommercePriceSource()).thenReturn(" pos ");
        when(s.selfScanPriceSource()).thenReturn("TRANSACTION");
        when(s.commercialPriceSource()).thenReturn("POS");
        when(s.qbustingPriceSource()).thenReturn("transaction");
        when(s.mobilityPriceSource()).thenReturn("POS");
        when(s.salesSlipPriceSource()).thenReturn("TRANSACTION");
        assertTrue(rules.priceFromRegister(SalesChannel.ECOMMERCE));
        assertFalse(rules.priceFromRegister(SalesChannel.SELF_SCAN));
        assertTrue(rules.priceFromRegister(SalesChannel.COMMERCIAL));
        assertFalse(rules.priceFromRegister(SalesChannel.QBUSTING));
        assertTrue(rules.priceFromRegister(SalesChannel.MOBILITY));
        assertFalse(rules.priceFromRegister(SalesChannel.SALES_SLIP));
        assertFalse(rules.priceFromRegister(null));
        when(s.orderRecallPriceFromRegister()).thenReturn(true);
        assertTrue(rules.priceFromRegister(null));
        when(s.mobilityPriceSource()).thenReturn("");
        assertTrue(rules.priceFromRegister(SalesChannel.MOBILITY));
    }

    /**
     * REJECT rejects, any other word accepts; a sale voucher follows the
     * commercial management tool's rule, and no channel rejects nothing
     * (BO-10-01-14, -23, -24, -32, -37).
     */
    @Test
    void theForbiddenArticleRuleOfEachChannel() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        assertFalse(rules.rejectsForbidden(null));
        assertFalse(rules.rejectsForbidden(SalesChannel.ECOMMERCE));
        when(s.ecommerceForbiddenItems()).thenReturn("reject");
        when(s.selfScanForbiddenItems()).thenReturn("REJECT");
        when(s.commercialForbiddenItems()).thenReturn("REJECT");
        when(s.qbustingForbiddenItems()).thenReturn("ACCEPT");
        when(s.mobilityForbiddenItems()).thenReturn("REJECT");
        assertTrue(rules.rejectsForbidden(SalesChannel.ECOMMERCE));
        assertTrue(rules.rejectsForbidden(SalesChannel.SELF_SCAN));
        assertTrue(rules.rejectsForbidden(SalesChannel.COMMERCIAL));
        assertTrue(rules.rejectsForbidden(SalesChannel.SALES_SLIP));
        assertFalse(rules.rejectsForbidden(SalesChannel.QBUSTING));
        assertTrue(rules.rejectsForbidden(SalesChannel.MOBILITY));
    }

    /**
     * Each channel names its promotion channel, upper-cased; an empty value, or
     * no channel, names none (BO-10-01-13, -22, -28, -31, -34).
     */
    @Test
    void thePromotionChannelOfEachChannel() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        assertNull(rules.promotionChannel(null));
        assertNull(rules.promotionChannel(SalesChannel.ECOMMERCE));
        when(s.ecommercePromotionChannel()).thenReturn("web");
        when(s.selfScanPromotionChannel()).thenReturn("POINT_OF_SALE");
        when(s.commercialPromotionChannel()).thenReturn("OTHER");
        when(s.qbustingPromotionChannel()).thenReturn("WEB");
        when(s.mobilityPromotionChannel()).thenReturn(" ");
        assertEquals("WEB", rules.promotionChannel(SalesChannel.ECOMMERCE));
        assertEquals("POINT_OF_SALE", rules.promotionChannel(SalesChannel.SELF_SCAN));
        assertEquals("OTHER", rules.promotionChannel(SalesChannel.COMMERCIAL));
        assertEquals("OTHER", rules.promotionChannel(SalesChannel.SALES_SLIP));
        assertEquals("WEB", rules.promotionChannel(SalesChannel.QBUSTING));
        assertNull(rules.promotionChannel(SalesChannel.MOBILITY));
    }

    /**
     * The generic EAN of the unknown articles exists for the e-commerce and the
     * self-scanning channels only (BO-10-01-06, -21).
     */
    @Test
    void theGenericEanOfTheUnknownArticles() {
        ChannelRules rules = rules();
        assertEquals("", rules.unknownItemEan(SalesChannel.ECOMMERCE));
        when(rules.posSettingsService.ecommerceUnknownItemEan()).thenReturn(" 2999 ");
        when(rules.posSettingsService.selfScanUnknownItemEan()).thenReturn("2888");
        assertEquals("2999", rules.unknownItemEan(SalesChannel.ECOMMERCE));
        assertEquals("2888", rules.unknownItemEan(SalesChannel.SELF_SCAN));
        assertEquals("", rules.unknownItemEan(SalesChannel.MOBILITY));
    }

    /**
     * SCAN admits the scanned code only, KEYED the keyed code only, BOTH — or a
     * channel with no retrieval mode — both (BO-10-01-16, -25, -30, -40).
     */
    @Test
    void theRetrievalModeOfEachChannel() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        when(s.selfScanRetrievalMode()).thenReturn("scan");
        when(s.commercialRetrievalMode()).thenReturn("KEYED");
        when(s.qbustingRetrievalMode()).thenReturn("BOTH");
        when(s.salesSlipRetrievalMode()).thenReturn("KEYED");
        assertTrue(rules.retrievalAllowed(SalesChannel.SELF_SCAN, false));
        assertFalse(rules.retrievalAllowed(SalesChannel.SELF_SCAN, true));
        assertTrue(rules.retrievalAllowed(SalesChannel.COMMERCIAL, true));
        assertFalse(rules.retrievalAllowed(SalesChannel.COMMERCIAL, false));
        assertTrue(rules.retrievalAllowed(SalesChannel.QBUSTING, true));
        assertFalse(rules.retrievalAllowed(SalesChannel.SALES_SLIP, false));
        assertTrue(rules.retrievalAllowed(SalesChannel.ECOMMERCE, false));
    }

    /**
     * BEFORE and AFTER each forbid the other side, NONE forbids both, BOTH and
     * an unreadable word allow both (BO-10-01-17).
     */
    @Test
    void theAdditionsAroundASelfScanningBasket() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        assertTrue(rules.selfScanAddsBefore());
        assertTrue(rules.selfScanAddsAfter());
        when(s.selfScanAddItems()).thenReturn("BEFORE");
        assertTrue(rules.selfScanAddsBefore());
        assertFalse(rules.selfScanAddsAfter());
        when(s.selfScanAddItems()).thenReturn("after");
        assertFalse(rules.selfScanAddsBefore());
        assertTrue(rules.selfScanAddsAfter());
        when(s.selfScanAddItems()).thenReturn("NONE");
        assertFalse(rules.selfScanAddsBefore());
        assertFalse(rules.selfScanAddsAfter());
    }

    /**
     * The identifier, the coupons, the complementary tender, the deposit
     * tender, the printed template and the virtual register read as
     * administered (BO-10-01-08, -18, -20, -26, -27, -39).
     */
    @Test
    void theSingleValuedRules() {
        ChannelRules rules = rules();
        PosSettingsService s = rules.posSettingsService;
        assertFalse(rules.selfScanByCustomer());
        assertFalse(rules.selfScanCoupons());
        assertEquals("", rules.mobilityFictitiousTender());
        assertNull(rules.commercialDepositTender());
        assertEquals("", rules.commercialAutoPrintTemplate());
        assertEquals("", rules.ecommerceTerminal());
        when(s.selfScanIdentifier()).thenReturn("customer");
        when(s.selfScanCoupons()).thenReturn(true);
        when(s.mobilityFictitiousTender()).thenReturn("voucher");
        when(s.commercialDepositTender()).thenReturn("card");
        when(s.commercialAutoPrintTemplate()).thenReturn(" BL ");
        when(s.ecommerceTerminal()).thenReturn("ECOM");
        assertTrue(rules.selfScanByCustomer());
        assertTrue(rules.selfScanCoupons());
        assertEquals("VOUCHER", rules.mobilityFictitiousTender());
        assertEquals("CARD", rules.commercialDepositTender());
        assertEquals("BL", rules.commercialAutoPrintTemplate());
        assertEquals("ECOM", rules.ecommerceTerminal());
    }

    /**
     * The return types of an e-commerce ticket are read per kind of return, the
     * empty entries dropped (BO-10-01-04, -05).
     */
    @Test
    void theReturnTypesOfAnEcommerceTicket() {
        ChannelRules rules = rules();
        assertEquals(List.of(), rules.ecommerceReturnTypes(true));
        when(rules.posSettingsService.ecommerceReturnTicketTypes()).thenReturn("Retour web ; ;Défectueux");
        when(rules.posSettingsService.ecommerceReturnArticleTypes()).thenReturn("Défectueux");
        assertEquals(List.of("Retour web", "Défectueux"), rules.ecommerceReturnTypes(true));
        assertEquals(List.of("Défectueux"), rules.ecommerceReturnTypes(false));
    }

    /**
     * The CDI constructor builds rules with nothing injected yet.
     */
    @Test
    void theCdiConstructorInjectsNothing() {
        assertNull(new ChannelRules().posSettingsService);
    }
}
