package com.intermarche.pos.ui.recall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link SalesChannel}: every kind of recalled document maps onto
 * its channel, the click-and-collect basket and a missing kind onto none.
 */
class SalesChannelTest {

    /**
     * Each kind names its channel: a quote and an order the commercial
     * management tool, a sale voucher the sales slips, the others their own.
     */
    @Test
    void everyKindNamesItsChannel() {
        assertEquals(SalesChannel.COMMERCIAL, SalesChannel.of(RecalledDocument.Kind.DEVIS));
        assertEquals(SalesChannel.COMMERCIAL, SalesChannel.of(RecalledDocument.Kind.COMMANDE));
        assertEquals(SalesChannel.SALES_SLIP, SalesChannel.of(RecalledDocument.Kind.BON_VENTE));
        assertEquals(SalesChannel.SELF_SCAN, SalesChannel.of(RecalledDocument.Kind.SELF_SCAN));
        assertEquals(SalesChannel.QBUSTING, SalesChannel.of(RecalledDocument.Kind.QBUSTING));
        assertEquals(SalesChannel.ECOMMERCE, SalesChannel.of(RecalledDocument.Kind.ECOMMERCE));
        assertEquals(SalesChannel.MOBILITY, SalesChannel.of(RecalledDocument.Kind.MOBILITY));
    }

    /**
     * The click-and-collect basket and a document of unknown kind belong to no
     * channel, so no rule of the lot touches them.
     */
    @Test
    void clickAndCollectAndAMissingKindHaveNoChannel() {
        assertNull(SalesChannel.of(RecalledDocument.Kind.CLICK_COLLECT));
        assertNull(SalesChannel.of(null));
    }
}
