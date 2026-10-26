package com.intermarche.pos.ui.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Symbology} (BO-03-06-01), targeting 100% branch
 * coverage.
 *
 * <p>Branch enumeration: a null, a short and an unmarked code read unchanged;
 * an exact identifier, a family identifier, an unknown one; the GS1
 * identifiers kept on the code; the acceptance with nothing administered, a
 * code announcing nothing, an unknown identifier, a listed and an unlisted
 * symbology.
 */
class SymbologyTest {

    /**
     * A code without identifier is read unchanged, whatever its length.
     */
    @Test
    void anUnmarkedCodeIsReadUnchanged() {
        Symbology.Reading none = Symbology.read(null);
        assertNull(none.payload());
        assertFalse(none.announced());
        assertEquals("]E", Symbology.read("]E").payload());
        Symbology.Reading plain = Symbology.read("3300000000002");
        assertNull(plain.symbology());
        assertEquals("3300000000002", plain.payload());
    }

    /**
     * An identifier is resolved exactly or by its family, and taken off the code;
     * an unknown identifier is announced without a symbology.
     */
    @Test
    void identifiersAreResolvedAndTakenOff() {
        Symbology.Reading ean = Symbology.read("]E03300000000002");
        assertSame(Symbology.EAN13, ean.symbology());
        assertTrue(ean.announced());
        assertEquals("3300000000002", ean.payload());
        assertSame(Symbology.EAN8, Symbology.read("]E412345670").symbology());
        assertSame(Symbology.CODE128, Symbology.read("]C0ABC123").symbology());
        assertSame(Symbology.QR, Symbology.read("]Q1hello").symbology());
        assertEquals("hello", Symbology.read("]Q1hello").payload());
        assertSame(Symbology.DATAMATRIX, Symbology.read("]d1X").symbology());
        assertSame(Symbology.CODE39, Symbology.read("]A0X").symbology());
        assertSame(Symbology.ITF, Symbology.read("]I012").symbology());
        Symbology.Reading unknown = Symbology.read("]X0ABC");
        assertNull(unknown.symbology());
        assertTrue(unknown.announced());
        assertEquals("ABC", unknown.payload());
    }

    /**
     * The GS1 identifiers stay on the code: they are the GS1 handler's marker.
     */
    @Test
    void gs1IdentifiersStayOnTheCode() {
        Symbology.Reading gs1 = Symbology.read("]C1010301762042200317261231");
        assertSame(Symbology.GS1, gs1.symbology());
        assertEquals("]C1010301762042200317261231", gs1.payload());
        assertSame(Symbology.GS1, Symbology.read("]Q3X").symbology());
        assertSame(Symbology.GS1, Symbology.read("]d2X").symbology());
        assertSame(Symbology.GS1, Symbology.read("]e0X").symbology());
    }

    /**
     * Nothing administered and an unmarked code are accepted; an unknown
     * identifier is refused once a list is administered; a listed symbology is
     * accepted whatever its case and padding, an unlisted one is refused.
     */
    @Test
    void theShopsListDecides() {
        Symbology.Reading qr = Symbology.read("]Q1hello");
        assertTrue(Symbology.accepted(qr, null));
        assertTrue(Symbology.accepted(qr, "  "));
        assertTrue(Symbology.accepted(Symbology.read("123"), "EAN13"));
        assertFalse(Symbology.accepted(Symbology.read("]X0ABC"), "EAN13"));
        assertTrue(Symbology.accepted(qr, "EAN13; qr "));
        assertFalse(Symbology.accepted(qr, "EAN13;CODE128"));
    }
}
