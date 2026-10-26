package com.intermarche.pos.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of {@link PosMenu}: the seven menus of the bottom bar (the expert's
 * nomenclature, campaign step 8) and the tolerant parsing of the key their links
 * carry, including the historical aliases.
 */
class PosMenuTest {

    /**
     * The bar carries exactly seven keys, in the expert's tab order.
     */
    @Test
    void sevenMenusInTabOrder() {
        assertEquals(7, PosMenu.ALL.size());
        assertSame(PosMenu.ARTICLES, PosMenu.ALL.get(0));
        assertSame(PosMenu.CAISSE, PosMenu.ALL.get(1));
        assertSame(PosMenu.REGLEMENT, PosMenu.ALL.get(2));
        assertSame(PosMenu.TICKET, PosMenu.ALL.get(3));
        assertSame(PosMenu.GENEROSITE, PosMenu.ALL.get(4));
        assertSame(PosMenu.TIROIR, PosMenu.ALL.get(5));
        assertSame(PosMenu.AUTRE, PosMenu.ALL.get(6));
    }

    /**
     * Every menu carries a URL key and an operator-facing label.
     */
    @Test
    void everyMenuIsNamed() {
        for (PosMenu menu : PosMenu.ALL) {
            assertFalse(menu.getKey() == null || menu.getKey().isBlank(),
                    "clé manquante pour " + menu);
            assertFalse(menu.getLabelKey() == null || menu.getLabelKey().isBlank(),
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
     * The historical {@code main} and {@code vente} keys land on ARTICLES, the
     * article gestures they used to name.
     */
    @Test
    void ofUnderstandsTheHistoricalMainKey() {
        assertSame(PosMenu.ARTICLES, PosMenu.of("main"));
        assertSame(PosMenu.ARTICLES, PosMenu.of("vente"));
    }

    /**
     * The historical {@code secondary} key lands on the ticket menu that inherited
     * its contents.
     */
    @Test
    void ofUnderstandsTheHistoricalSecondaryKey() {
        assertSame(PosMenu.TICKET, PosMenu.of("secondary"));
    }

    /**
     * The historical {@code client} key lands on GÉNÉROSITÉ and {@code poste} on
     * AUTRE, where their contents were re-slotted.
     */
    @Test
    void ofUnderstandsTheHistoricalClientAndPosteKeys() {
        assertSame(PosMenu.GENEROSITE, PosMenu.of("client"));
        assertSame(PosMenu.AUTRE, PosMenu.of("poste"));
    }

    /**
     * A missing key lands on the ARTICLES menu (null arm).
     */
    @Test
    void ofNullFallsBackToArticles() {
        assertSame(PosMenu.ARTICLES, PosMenu.of(null));
    }

    /**
     * An unknown key lands on the ARTICLES menu rather than on a blank grid.
     */
    @Test
    void ofUnknownFallsBackToArticles() {
        assertSame(PosMenu.ARTICLES, PosMenu.of("rayon"));
    }

    /**
     * A blank key lands there too.
     */
    @Test
    void ofBlankFallsBackToArticles() {
        assertSame(PosMenu.ARTICLES, PosMenu.of("   "));
    }
    /** LC-07: the payment tab links directly to payment while ticket actions remain a menu. */
    @org.junit.jupiter.api.Test
    void menuDestinationsExposeTheDirectPaymentRoute() {
        org.junit.jupiter.api.Assertions.assertEquals("/pay", PosMenu.REGLEMENT.getDestination());
        org.junit.jupiter.api.Assertions.assertNull(PosMenu.TICKET.getDestination());
    }

}
