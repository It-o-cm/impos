package com.intermarche.pos.ui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PriceModState}, covering the modal open/close
 * lifecycle and every branch of the title resolution.
 */
class PriceModStateTest {

    /**
     * Verifies the default field values of a freshly constructed state.
     */
    @Test
    void defaultsAreInactiveAndNull() {
        PriceModState state = new PriceModState();
        Assertions.assertFalse(state.active);
        Assertions.assertNull(state.type);
        Assertions.assertNull(state.targetUid);
        Assertions.assertNull(state.targetLabel);
    }

    /**
     * Verifies that set() opens the modal and stores the target coordinates.
     */
    @Test
    void setActivatesAndStoresTarget() {
        PriceModState state = new PriceModState();
        state.set("REMISE", "uid-1", "Bananes");
        Assertions.assertTrue(state.active);
        Assertions.assertEquals("REMISE", state.type);
        Assertions.assertEquals("uid-1", state.targetUid);
        Assertions.assertEquals("Bananes", state.targetLabel);
    }

    /**
     * Verifies that clear() closes the modal and forgets the target.
     */
    @Test
    void clearDeactivatesAndForgetsTarget() {
        PriceModState state = new PriceModState();
        state.set("QUANTITY", "uid-2", "Pommes");
        state.clear();
        Assertions.assertFalse(state.active);
        Assertions.assertNull(state.type);
        Assertions.assertNull(state.targetUid);
        Assertions.assertNull(state.targetLabel);
    }

    /**
     * Verifies the REMISE title (first branch, true arm).
     */
    @Test
    void getTypeLabelRemise() {
        PriceModState state = new PriceModState();
        state.type = "REMISE";
        Assertions.assertEquals("SAISIE REMISE (€)", state.getTypeLabel());
    }

    /**
     * Verifies the DISCOUNT title (second branch, true arm).
     */
    @Test
    void getTypeLabelDiscount() {
        PriceModState state = new PriceModState();
        state.type = "DISCOUNT";
        Assertions.assertEquals("SAISIE DISCOUNT (%)", state.getTypeLabel());
    }

    /**
     * Verifies the FORCE_PRICE title (third branch, true arm).
     */
    @Test
    void getTypeLabelForcePrice() {
        PriceModState state = new PriceModState();
        state.type = "FORCE_PRICE";
        Assertions.assertEquals("NOUVEAU PRIX (€)", state.getTypeLabel());
    }

    /**
     * Verifies the QUANTITY title (fourth branch, true arm).
     */
    @Test
    void getTypeLabelQuantity() {
        PriceModState state = new PriceModState();
        state.type = "QUANTITY";
        Assertions.assertEquals("QUANTITÉ ARTICLE", state.getTypeLabel());
    }

    /**
     * Verifies the fallback title when the type matches no known value
     * (false arm of every branch, non-null type).
     */
    @Test
    void getTypeLabelUnknownFallsBack() {
        PriceModState state = new PriceModState();
        state.type = "SOMETHING_ELSE";
        Assertions.assertEquals("MODIFICATION", state.getTypeLabel());
    }

    /**
     * Verifies the fallback title when the type is null (false arm of every
     * branch reached via the null-safe equals receivers).
     */
    @Test
    void getTypeLabelNullFallsBack() {
        PriceModState state = new PriceModState();
        Assertions.assertNull(state.type);
        Assertions.assertEquals("MODIFICATION", state.getTypeLabel());
    }
}
