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
        state.set(PriceModType.REMISE, "uid-1", "Bananes");
        Assertions.assertTrue(state.active);
        Assertions.assertEquals(PriceModType.REMISE, state.type);
        Assertions.assertEquals("uid-1", state.targetUid);
        Assertions.assertEquals("Bananes", state.targetLabel);
    }

    /**
     * The three-argument set() is the ticket-level gesture: it opens the modal
     * with no line to recall.
     */
    @Test
    void setWithoutRecallLeavesNoLineToShow() {
        PriceModState state = new PriceModState();
        state.set(PriceModType.GLOBAL_REMISE, null, "TICKET COMPLET");
        Assertions.assertFalse(state.isTargetLine());
        Assertions.assertNull(state.targetHtml);
        Assertions.assertNull(state.targetPriceFormatted);
        Assertions.assertNull(state.targetModifierLabel);
    }

    /**
     * The six-argument set() carries the recall of the targeted line — the
     * fragment the ticket shows, the current total and any modification already
     * applied.
     */
    @Test
    void setWithRecallStoresTheLineAsTheTicketShowsIt() {
        PriceModState state = new PriceModState();
        state.set(PriceModType.REMISE, "uid-1", "Bananes", "<span class='qty'>x2</span> Bananes",
                "4,60", "REMISE 1,00");
        Assertions.assertTrue(state.isTargetLine());
        Assertions.assertEquals("<span class='qty'>x2</span> Bananes", state.targetHtml);
        Assertions.assertEquals("4,60", state.targetPriceFormatted);
        Assertions.assertEquals("REMISE 1,00", state.targetModifierLabel);
    }

    /**
     * A line carrying no modification yet recalls the line all the same, with no
     * modification line to show (modifier null arm).
     */
    @Test
    void setWithRecallAcceptsALineWithoutModification() {
        PriceModState state = new PriceModState();
        state.set(PriceModType.FORCE_PRICE, "uid-3", "Pain", "<span class='qty'>x1</span> Pain",
                "1,20", null);
        Assertions.assertTrue(state.isTargetLine());
        Assertions.assertNull(state.targetModifierLabel);
    }

    /**
     * Verifies that clear() closes the modal and forgets the target, recall
     * included — the next gesture must not inherit the previous line.
     */
    @Test
    void clearDeactivatesAndForgetsTarget() {
        PriceModState state = new PriceModState();
        state.set(PriceModType.QUANTITY, "uid-2", "Pommes", "<span class='qty'>x3</span> Pommes",
                "6,00", "DISCOUNT 10%");
        state.clear();
        Assertions.assertFalse(state.active);
        Assertions.assertNull(state.type);
        Assertions.assertNull(state.targetUid);
        Assertions.assertNull(state.targetLabel);
        Assertions.assertNull(state.targetHtml);
        Assertions.assertNull(state.targetPriceFormatted);
        Assertions.assertNull(state.targetModifierLabel);
        Assertions.assertFalse(state.isTargetLine());
    }

    // --- isLineModes: one case per leg of the three-legged disjunction ---

    /**
     * REMISE offers the mode selector (first leg true).
     */
    @Test
    void isLineModesTrueForRemise() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.REMISE;
        Assertions.assertTrue(state.isLineModes());
    }

    /**
     * DISCOUNT offers the mode selector (second leg true).
     */
    @Test
    void isLineModesTrueForDiscount() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.DISCOUNT;
        Assertions.assertTrue(state.isLineModes());
    }

    /**
     * FORCE_PRICE offers the mode selector (third leg true).
     */
    @Test
    void isLineModesTrueForForcePrice() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.FORCE_PRICE;
        Assertions.assertTrue(state.isLineModes());
    }

    /**
     * QUANTITY shares the modal but asks another question — how many, not how
     * much — so it gets no mode selector (all three legs false).
     */
    @Test
    void isLineModesFalseForQuantity() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.QUANTITY;
        Assertions.assertFalse(state.isLineModes());
    }

    /**
     * A ticket-level gesture is not a line gesture: it has its own two modes
     * (all three legs false).
     */
    @Test
    void isLineModesFalseForTicketGesture() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.GLOBAL_REMISE;
        Assertions.assertFalse(state.isLineModes());
    }

    /**
     * A null type answers false rather than throwing — the constants are on the
     * left of every equals (null arm).
     */
    @Test
    void isLineModesFalseWhenTypeIsNull() {
        PriceModState state = new PriceModState();
        Assertions.assertFalse(state.isLineModes());
    }

    // --- isTicketModes: one case per leg of the two-legged disjunction ---

    /**
     * GLOBAL_REMISE offers the euro/percent selector (first leg true).
     */
    @Test
    void isTicketModesTrueForGlobalRemise() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.GLOBAL_REMISE;
        Assertions.assertTrue(state.isTicketModes());
    }

    /**
     * GLOBAL_DISCOUNT offers the euro/percent selector (second leg true).
     */
    @Test
    void isTicketModesTrueForGlobalDiscount() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.GLOBAL_DISCOUNT;
        Assertions.assertTrue(state.isTicketModes());
    }

    /**
     * A line gesture gets the line selector, not this one (both legs false).
     */
    @Test
    void isTicketModesFalseForLineGesture() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.REMISE;
        Assertions.assertFalse(state.isTicketModes());
    }

    /**
     * A null type answers false rather than throwing (null arm).
     */
    @Test
    void isTicketModesFalseWhenTypeIsNull() {
        PriceModState state = new PriceModState();
        Assertions.assertFalse(state.isTicketModes());
    }

    /**
     * Verifies the REMISE title (first branch, true arm).
     */
    @Test
    void getTypeLabelRemise() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.REMISE;
        Assertions.assertEquals("SAISIE REMISE (€)", state.getTypeLabel());
    }

    /**
     * Verifies the DISCOUNT title (second branch, true arm).
     */
    @Test
    void getTypeLabelDiscount() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.DISCOUNT;
        Assertions.assertEquals("SAISIE DISCOUNT (%)", state.getTypeLabel());
    }

    /**
     * Verifies the FORCE_PRICE title (third branch, true arm).
     */
    @Test
    void getTypeLabelForcePrice() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.FORCE_PRICE;
        Assertions.assertEquals("NOUVEAU PRIX (€)", state.getTypeLabel());
    }

    /**
     * Verifies the QUANTITY title (fourth branch, true arm).
     */
    @Test
    void getTypeLabelQuantity() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.QUANTITY;
        Assertions.assertEquals("QUANTITÉ ARTICLE", state.getTypeLabel());
    }

    /**
     * Verifies the GLOBAL_REMISE title (fifth branch, true arm): the euro
     * gesture aimed at the WHOLE ticket, distinct from the per-line REMISE —
     * the title is what tells the cashier which target the amount will hit.
     */
    @Test
    void getTypeLabelGlobalRemise() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.GLOBAL_REMISE;
        Assertions.assertEquals("REMISE TICKET (€)", state.getTypeLabel());
    }

    /**
     * Verifies the GLOBAL_DISCOUNT title (sixth branch, true arm): the
     * percentage flavour of the same whole-ticket gesture.
     */
    @Test
    void getTypeLabelGlobalDiscount() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.GLOBAL_DISCOUNT;
        Assertions.assertEquals("REMISE TICKET (%)", state.getTypeLabel());
    }

    /**
     * The four per-line titles and the two whole-ticket ones are pairwise
     * DISTINCT: an endorsed gesture must never be presented under another
     * gesture's title, and the euro/percent pairs differ only by their suffix.
     */
    @Test
    void getTypeLabelsAreAllDistinct() {
        PriceModState state = new PriceModState();
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (PriceModType type : PriceModType.values()) {
            state.type = type;
            Assertions.assertTrue(titles.add(state.getTypeLabel()),
                    "titre en doublon pour " + type + ": " + state.getTypeLabel());
        }
    }

    /**
     * A word that names no mode leaves the state carrying no mode, and the
     * modal therefore falls back — the only way an unknown gesture can reach
     * the state now that the type is an enum.
     */
    @Test
    void getTypeLabelUnknownWordFallsBack() {
        PriceModState state = new PriceModState();
        state.type = PriceModType.of("SOMETHING_ELSE");
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
