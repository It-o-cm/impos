package com.intermarche.pos.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests of {@link PosMenu}: the five menus of the bottom bar and the tolerant
 * parsing of the key their links carry.
 */
class PosMenuTest {

    /**
     * The bar carries exactly five keys, in the order the day follows.
     */
    @Test
    void fiveMenusInTabOrder() {
        assertEquals(5, PosMenu.ALL.size());
        assertSame(PosMenu.VENTE, PosMenu.ALL.get(0));
        assertSame(PosMenu.TICKET, PosMenu.ALL.get(1));
        assertSame(PosMenu.CLIENT, PosMenu.ALL.get(2));
        assertSame(PosMenu.CAISSE, PosMenu.ALL.get(3));
        assertSame(PosMenu.POSTE, PosMenu.ALL.get(4));
    }

    /**
     * Every menu carries a URL key and an operator-facing label.
     */
    @Test
    void everyMenuIsNamed() {
        for (PosMenu menu : PosMenu.ALL) {
            assertFalse(menu.getKey() == null || menu.getKey().isBlank(),
                    "clé manquante pour " + menu);
            assertFalse(menu.getLabel() == null || menu.getLabel().isBlank(),
                    "libellé manquant pour " + menu);
        }
    }

    /**
     * The keys are distinct — two menus sharing one would make a link ambiguous.
     */
    @Test
    void theKeysAreDistinct() {
        assertEquals(PosMenu.ALL.size(),
                PosMenu.ALL.stream().map(PosMenu::getKey).distinct().count());
    }

    /**
     * Every declared key parses back to its own menu.
     */
    @Test
    void ofParsesEveryDeclaredKey() {
        for (PosMenu menu : PosMenu.ALL) {
            assertSame(menu, PosMenu.of(menu.getKey()));
        }
    }

    /**
     * Case and surrounding blanks are ignored.
     */
    @Test
    void ofIgnoresCaseAndBlanks() {
        assertSame(PosMenu.CAISSE, PosMenu.of("  CAISSE "));
    }

    /**
     * The historical {@code main} key lands on the sale gestures it used to name.
     */
    @Test
    void ofUnderstandsTheHistoricalMainKey() {
        assertSame(PosMenu.VENTE, PosMenu.of("main"));
    }

    /**
     * The historical {@code secondary} key lands on the menu that inherited its
     * contents.
     */
    @Test
    void ofUnderstandsTheHistoricalSecondaryKey() {
        assertSame(PosMenu.TICKET, PosMenu.of("secondary"));
    }

    /**
     * A missing key lands on the sale menu (null arm).
     */
    @Test
    void ofNullFallsBackToTheSaleMenu() {
        assertSame(PosMenu.VENTE, PosMenu.of(null));
    }

    /**
     * An unknown key lands on the sale menu rather than on a blank grid.
     */
    @Test
    void ofUnknownFallsBackToTheSaleMenu() {
        assertSame(PosMenu.VENTE, PosMenu.of("rayon"));
    }

    /**
     * A blank key lands there too.
     */
    @Test
    void ofBlankFallsBackToTheSaleMenu() {
        assertSame(PosMenu.VENTE, PosMenu.of("   "));
    }
}
