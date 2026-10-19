package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link ControlPoint}, the ten gestures of the sale a shop may
 * watch (BO-10-02-01 to -10, -16 and -17).
 * <p>
 * The case that matters is the LAST one: a control point states a fallback
 * level and the catalogue states a default for the same key, and the two must
 * be the same sentence said twice. Nothing in the code holds them together, so
 * this test does — a catalogue default changed without its enum would otherwise
 * make a register behave one way and the back office claim another.
 */
class ControlPointTest {

    /**
     * Every control point is published by the back-office catalogue: a point
     * whose key no screen offers is a control nobody can administer.
     *
     * @param point the point to look up
     * @return its catalogue entry
     */
    private PosSettingsService.Def definitionOf(ControlPoint point) {
        for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
            if (def.key().equals(point.getSettingKey())) {
                return def;
            }
        }
        return null;
    }

    /**
     * Each point's key is published by the catalogue, under the control-points
     * section, so a paramétreur finds the ten together.
     */
    @Test
    void everyControlPointIsPublishedByTheCatalogue() {
        for (ControlPoint point : ControlPoint.values()) {
            PosSettingsService.Def def = definitionOf(point);
            assertNotNull(def, point + " n'est pas au catalogue");
            assertEquals("POINTS DE CONTRÔLE", def.section(), point.name());
        }
    }

    /**
     * THE DRIFT TEST: the level a point falls back on and the default the
     * catalogue publishes for its key are the same level.
     */
    @Test
    void theCatalogueDefaultAndTheEnumFallbackAgree() {
        for (ControlPoint point : ControlPoint.values()) {
            PosSettingsService.Def def = definitionOf(point);
            assertEquals(point.getDefaultLevel(),
                    ControlLevel.of(def.defaultValue(), ControlLevel.WARN),
                    point + " : le défaut du catalogue et celui de l'énumération divergent");
        }
    }

    /**
     * The four thresholds and the six points that compare nothing — both arms
     * of {@code hasThreshold} — and each threshold key is itself at the
     * catalogue.
     */
    @Test
    void onlyFourPointsCompareAThreshold() {
        assertTrue(ControlPoint.QUANTITY.hasThreshold());
        assertTrue(ControlPoint.TICKET_TOTAL.hasThreshold());
        assertTrue(ControlPoint.ITEM_AMOUNT.hasThreshold());
        assertTrue(ControlPoint.PAYMENT_AMOUNT.hasThreshold());
        assertFalse(ControlPoint.CANCEL_LINE.hasThreshold());
        assertFalse(ControlPoint.CARD_PAYMENT.hasThreshold());
        for (ControlPoint point : ControlPoint.values()) {
            if (!point.hasThreshold()) {
                continue;
            }
            boolean published = false;
            for (PosSettingsService.Def def : PosSettingsService.CATALOG) {
                published = published || def.key().equals(point.getThresholdKey());
            }
            assertTrue(published, point + " : son seuil n'est pas au catalogue");
        }
    }

    /**
     * Five points fire where the endorsement ceremony exists and five where it
     * does not — both arms of {@code isEndorsable}, named one by one because
     * the list is a decision and not a derivation.
     */
    @Test
    void fivePointsOfTenCanBeEndorsed() {
        assertTrue(ControlPoint.CANCEL_LINE.isEndorsable());
        assertTrue(ControlPoint.CANCEL_PAYMENT.isEndorsable());
        assertTrue(ControlPoint.REFUND.isEndorsable());
        assertTrue(ControlPoint.QUANTITY.isEndorsable());
        assertTrue(ControlPoint.ITEM_AMOUNT.isEndorsable());
        assertFalse(ControlPoint.DRAWER_OUT_OF_SALE.isEndorsable());
        assertFalse(ControlPoint.TICKET_TOTAL.isEndorsable());
        assertFalse(ControlPoint.PARKED_AT_CLOSE.isEndorsable());
        assertFalse(ControlPoint.CARD_PAYMENT.isEndorsable());
        assertFalse(ControlPoint.PAYMENT_AMOUNT.isEndorsable());
    }

    /**
     * The two amount points carry the keys this lot added, on both sides: the
     * level key they are read by and the ceiling key they compare to
     * (BO-10-02-16 and -17).
     */
    @Test
    void theTwoAmountPointsCarryTheirOwnKeys() {
        assertEquals("control.item-amount", ControlPoint.ITEM_AMOUNT.getSettingKey());
        assertEquals("control.item-amount-max", ControlPoint.ITEM_AMOUNT.getThresholdKey());
        assertEquals(ControlLevel.NONE, ControlPoint.ITEM_AMOUNT.getDefaultLevel());
        assertEquals("control.payment-amount", ControlPoint.PAYMENT_AMOUNT.getSettingKey());
        assertEquals("control.payment-amount-max", ControlPoint.PAYMENT_AMOUNT.getThresholdKey());
        assertEquals(ControlLevel.NONE, ControlPoint.PAYMENT_AMOUNT.getDefaultLevel());
    }

    /**
     * Each point names itself to the operator through its own message key: two
     * points sharing a wording would make one control look like another.
     */
    @Test
    void everyPointCarriesItsOwnOperatorWording() {
        for (ControlPoint point : ControlPoint.values()) {
            assertNotNull(point.getLabelKey(), point.name());
            assertTrue(point.getLabelKey().startsWith("op.controlePoint"), point.name());
            for (ControlPoint other : ControlPoint.values()) {
                if (other != point) {
                    assertFalse(other.getLabelKey().equals(point.getLabelKey()),
                            point + " et " + other + " partagent un libellé");
                }
            }
        }
    }
}
