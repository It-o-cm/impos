package com.intermarche.pos.ui.scanner;

import java.util.Arrays;
import java.util.Locale;

/**
 * The barcode symbologies a scanner announces in front of what it read, by
 * the ISO/IEC 15424 symbology identifier — {@code ]E0} for an EAN-13,
 * {@code ]C0} for a Code 128, {@code ]Q1} for a QR code, {@code ]d1} for a
 * Data Matrix (BO-03-06-01).
 *
 * <p>The identifier says HOW the code was printed, never what it means: it is
 * read off, checked against the symbologies the shop accepts, and taken away
 * before the code walks the scan chain — except for the GS1 identifiers
 * ({@code ]C1}, {@code ]e0}, {@code ]d2}, {@code ]Q3}), which the GS1 handler
 * reads as the very marker telling a GS1 payload from a bare run of digits, and
 * which are therefore left on the code.
 */
public enum Symbology {

    /** EAN-13 and UPC-A, with or without add-on. */
    EAN13("]E0", "]E1", "]E2", "]E3"),

    /** EAN-8. */
    EAN8("]E4"),

    /** Code 128, alphanumeric. */
    CODE128("]C0", "]C2", "]C4"),

    /** Code 39. */
    CODE39("]A"),

    /** Interleaved 2 of 5. */
    ITF("]I"),

    /** GS1-128, GS1 DataBar, GS1 Data Matrix and GS1 QR code. */
    GS1("]C1", "]e0", "]d2", "]Q3"),

    /** QR code. */
    QR("]Q"),

    /** Data Matrix. */
    DATAMATRIX("]d");

    /** The identifiers announcing this symbology, a two-character one standing for its whole family. */
    private final String[] identifiers;

    /**
     * Declares a symbology by its identifiers.
     *
     * @param identifiers the identifiers announcing it
     */
    Symbology(String... identifiers) {
        this.identifiers = identifiers;
    }

    /**
     * What a scanner sent, split into the symbology it announced and the code.
     *
     * @param symbology the announced symbology, or null when none was announced
     *                  or the identifier is not one this register knows
     * @param announced whether the code carried a symbology identifier at all
     * @param payload the code the scan chain reads
     */
    public record Reading(Symbology symbology, boolean announced, String payload) {
    }

    /**
     * Splits what a scanner sent into its symbology and its code.
     *
     * @param raw what the scanner sent, possibly null
     * @return the reading; a code without identifier is read unchanged
     */
    public static Reading read(String raw) {
        if (raw == null || raw.length() < 3 || raw.charAt(0) != ']') {
            return new Reading(null, false, raw);
        }
        String identifier = raw.substring(0, 3);
        Symbology symbology = byIdentifier(identifier);
        String payload = symbology == GS1 ? raw : raw.substring(3);
        return new Reading(symbology, true, payload);
    }

    /**
     * Finds the symbology an identifier announces: the exact identifiers first,
     * then the families.
     *
     * @param identifier the three-character identifier
     * @return the symbology, or null when the identifier is unknown
     */
    static Symbology byIdentifier(String identifier) {
        for (Symbology symbology : values()) {
            if (Arrays.asList(symbology.identifiers).contains(identifier)) {
                return symbology;
            }
        }
        for (Symbology symbology : values()) {
            for (String family : symbology.identifiers) {
                if (family.length() == 2 && identifier.startsWith(family)) {
                    return symbology;
                }
            }
        }
        return null;
    }

    /**
     * Tells whether the shop accepts a reading.
     *
     * <p>Nothing administered accepts everything. A code that announced no
     * symbology is accepted: a scanner set to send none cannot be filtered, and
     * refusing every code it reads would close the lane. An announced
     * symbology is accepted only when the administered list names it, an
     * identifier this register does not know being refused.
     *
     * @param reading the reading
     * @param administered the semicolon list of accepted symbologies, possibly blank
     * @return true when the code may walk the scan chain
     */
    public static boolean accepted(Reading reading, String administered) {
        if (administered == null || administered.isBlank() || !reading.announced()) {
            return true;
        }
        if (reading.symbology() == null) {
            return false;
        }
        for (String name : administered.split(";")) {
            if (name.trim().toUpperCase(Locale.ROOT).equals(reading.symbology().name())) {
                return true;
            }
        }
        return false;
    }
}
