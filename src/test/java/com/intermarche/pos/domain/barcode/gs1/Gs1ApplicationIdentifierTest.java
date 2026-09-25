package com.intermarche.pos.domain.barcode.gs1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Gs1ApplicationIdentifier}.
 * <p>
 * The class is a pure lookup record with no injected collaborators, so the tests
 * are plain JUnit 5 with no mocking. They enumerate every reachable branch of
 * {@link Gs1ApplicationIdentifier#isFixedLength()},
 * {@link Gs1ApplicationIdentifier#at(String, int)} and
 * {@link Gs1ApplicationIdentifier#of(String)}: both arms of the fixed-length
 * guard, all three legs of the position guard, the longest-match walk with its
 * continue/skip arms, the decimal-stem resolution and both null/non-null arms of
 * {@code of}. Assertions use absolute expected values and each test is fully
 * isolated.
 */
class Gs1ApplicationIdentifierTest {

    /**
     * A predefined-length identifier reports a fixed length (the
     * {@code dataLength > 0} true arm).
     */
    @Test
    void isFixedLengthTrueForPredefinedLength() {
        Gs1ApplicationIdentifier gtin = Gs1ApplicationIdentifier.of(Gs1ApplicationIdentifier.GTIN);
        assertEquals(14, gtin.dataLength());
        assertTrue(gtin.isFixedLength());
    }

    /**
     * A variable-length identifier reports no fixed length (the
     * {@code dataLength > 0} false arm).
     */
    @Test
    void isFixedLengthFalseForVariableLength() {
        Gs1ApplicationIdentifier batch = Gs1ApplicationIdentifier.of(Gs1ApplicationIdentifier.BATCH);
        assertEquals(0, batch.dataLength());
        assertFalse(batch.isFixedLength());
    }

    /**
     * {@code at} returns null when the payload is null (the first leg of the
     * position guard).
     */
    @Test
    void atReturnsNullWhenPayloadNull() {
        assertNull(Gs1ApplicationIdentifier.at(null, 0));
    }

    /**
     * {@code at} returns null when the start index is negative (the second leg of
     * the position guard, the payload being non-null).
     */
    @Test
    void atReturnsNullWhenFromNegative() {
        assertNull(Gs1ApplicationIdentifier.at("01", -1));
    }

    /**
     * {@code at} returns null when the start index is at or beyond the payload
     * length (the third leg of the position guard).
     */
    @Test
    void atReturnsNullWhenFromBeyondLength() {
        assertNull(Gs1ApplicationIdentifier.at("01", 2));
    }

    /**
     * {@code at} skips the four- and three-digit candidates that overrun the
     * payload (the {@code from + length > length} true arm) and matches the
     * two-digit identifier (known non-null, non-decimal true arm).
     */
    @Test
    void atMatchesTwoDigitAfterSkippingLongerCandidates() {
        Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at("01", 0);
        assertEquals(Gs1ApplicationIdentifier.GTIN, found.code());
        assertEquals("GTIN (code article)", found.label());
        assertFalse(found.decimal());
    }

    /**
     * {@code at} returns null for an unknown code, walking every length with no
     * match (known null arm, stem null arm, and both arms of {@code length > 2}).
     */
    @Test
    void atReturnsNullForUnknownCode() {
        assertNull(Gs1ApplicationIdentifier.at("9999", 0));
    }

    /**
     * {@code at} resolves a decimal measure identifier by its stem when the digit
     * following the code is present (stem non-null and stem decimal true arms).
     */
    @Test
    void atResolvesDecimalMeasureByStem() {
        Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at("3103", 0);
        assertEquals(Gs1ApplicationIdentifier.NET_WEIGHT_KG, found.code());
        assertTrue(found.decimal());
        assertEquals(6, found.dataLength());
    }

    /**
     * {@code at} does not return a decimal identifier matched at its full code
     * length (the {@code !known.decimal()} false arm) and, finding no separable
     * stem, returns null.
     */
    @Test
    void atSkipsDecimalKnownMatchedAtFullLength() {
        assertNull(Gs1ApplicationIdentifier.at("310", 0));
    }

    /**
     * {@code at} passes over a non-decimal stem (the {@code stem.decimal()} false
     * arm) and then matches that same non-decimal identifier at its own length.
     */
    @Test
    void atSkipsNonDecimalStemThenMatchesAtCodeLength() {
        Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at("4220", 0);
        assertEquals(Gs1ApplicationIdentifier.ORIGIN, found.code());
        assertFalse(found.decimal());
    }

    /**
     * {@code of} returns null for a null code (the ternary null arm).
     */
    @Test
    void ofReturnsNullForNullCode() {
        assertNull(Gs1ApplicationIdentifier.of(null));
    }

    /**
     * {@code of} returns the identifier for a known code (the ternary non-null
     * arm) with all its fields.
     */
    @Test
    void ofReturnsKnownIdentifier() {
        Gs1ApplicationIdentifier batch = Gs1ApplicationIdentifier.of(Gs1ApplicationIdentifier.BATCH);
        assertEquals("10", batch.code());
        assertEquals("Numéro de lot", batch.label());
        assertEquals(0, batch.dataLength());
        assertEquals(20, batch.maxLength());
        assertFalse(batch.decimal());
    }

    /**
     * {@code of} returns null for a code this version does not know (the non-null
     * arm resolving to an absent map entry).
     */
    @Test
    void ofReturnsNullForUnknownCode() {
        assertNull(Gs1ApplicationIdentifier.of("ZZ"));
    }
}
