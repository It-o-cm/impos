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
        assertNull(Gs1ApplicationIdentifier.at("2999", 0));
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

    /**
     * Lot-19 defect: the additional product identification 240 is found by its
     * true three-digit code, not taken for the two-digit 24 that does not exist.
     */
    @Test
    void atFindsTheAdditionalProductIdentificationByItsTrueCode() {
        Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at("240ABC", 0);
        assertEquals("240", found.code());
        assertEquals("Identification complémentaire du produit", found.label());
        assertEquals(30, found.maxLength());
        assertFalse(found.isFixedLength());
    }

    /**
     * The location numbers 410 to 417 are three-digit identifiers of thirteen
     * fixed digits, so the payload's data starts after the third digit.
     */
    @Test
    void theLocationNumbersAreThreeDigitFixedLengthIdentifiers() {
        for (int code = 410; code <= 417; code++) {
            Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at(code + "3012345678901", 0);
            assertEquals(String.valueOf(code), found.code());
            assertEquals(13, found.dataLength());
            assertTrue(found.isFixedLength());
        }
        assertNull(Gs1ApplicationIdentifier.of("41"));
    }

    /**
     * Every standard identifier commonly found on retail items is known under
     * its own code, so no administered alert or rule on it can miss.
     */
    @Test
    void everyCommonRetailIdentifierIsKnownByItsCode() {
        String[] codes = {"00", "01", "02", "10", "11", "13", "15", "16", "17", "20", "21", "22",
            "240", "241", "242", "243", "250", "251", "253", "254", "255", "30", "37",
            "400", "401", "402", "403", "420", "421", "422", "423", "424", "425", "426", "427",
            "7001", "7002", "7003", "7004", "7005", "7006", "7007", "7008", "7009", "7010",
            "8001", "8002", "8003", "8004", "8005", "8006", "8007", "8008", "8012",
            "8017", "8018", "8019", "8020", "8110", "8111", "8112", "8200"};
        for (String code : codes) {
            assertEquals(code, Gs1ApplicationIdentifier.at(code + "1", 0).code(), code);
        }
    }

    /**
     * The decimal families 310n to 369n and 390n to 395n are found by their
     * three-digit stem whatever the decimal digit that follows.
     */
    @Test
    void everyDecimalFamilyIsFoundByItsStem() {
        String[] stems = {"310", "316", "320", "329", "330", "337", "340", "349", "350", "357",
            "360", "369", "390", "391", "392", "393", "394", "395"};
        for (String stem : stems) {
            Gs1ApplicationIdentifier found = Gs1ApplicationIdentifier.at(stem + "2" + "000100", 0);
            assertEquals(stem, found.code(), stem);
            assertTrue(found.decimal(), stem);
        }
    }
}
