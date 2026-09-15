package com.intermarche.pos.domain.barcode.gs1;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the GS1 decoder ({@code LC-11-01-11}, {@code LC-11-02-01/03/06},
 * {@code LC-11-03-02} to {@code -23}).
 *
 * <p>THE TEST THAT MATTERS MOST IS THE ONE THAT REFUSES. A GS1 Element String has no
 * syntax of its own — bare, it is a run of digits and so is an EAN-13 — so a decoder
 * that reads greedily turns every shelf code at the till into elements nobody scanned.
 * Half of what follows therefore checks what the decoder DOES NOT claim.
 *
 * <p>The other half is delimiting, which is the whole difficulty of the format: fixed
 * lengths, separated variable lengths, a decimal-place digit that shifts a number, an
 * identifier this version does not know sitting between two it does, and a payload that
 * simply stops in the middle.
 */
class Gs1ParserTest {

    /** FNC1 as a reader transmits it, as a string, for building payloads. */
    private static final String GS = String.valueOf(Gs1Parser.SEPARATOR);

    // --------------------------------------------------
    // What is NOT GS1
    // --------------------------------------------------

    /**
     * An ordinary shelf EAN-13 is not GS1. It is the single most important refusal
     * here: read greedily, {@code 3560070123456} decodes as a measure identifier
     * followed by an unknown one, and the till registers an article nobody scanned.
     */
    @Test
    void anOrdinaryEanIsNotGs1() {
        assertTrue(Gs1Parser.parse("3560070123456").isEmpty());
    }

    /**
     * An in-store weighted label is not GS1 either — it belongs to the 2x handler.
     */
    @Test
    void anInStoreWeightedLabelIsNotGs1() {
        assertTrue(Gs1Parser.parse("2981234567890").isEmpty());
    }

    /**
     * A code that is not digits at all is not GS1 — the fall-through of the marker
     * guard.
     */
    @Test
    void alphanumericJunkIsNotGs1() {
        assertTrue(Gs1Parser.parse("ABC-123").isEmpty());
    }

    /**
     * Nothing at all decodes to nothing — the null leg.
     */
    @Test
    void aMissingPayloadIsNotGs1() {
        assertTrue(Gs1Parser.parse(null).isEmpty());
    }

    /**
     * Whitespace alone decodes to nothing — the blank leg.
     */
    @Test
    void aBlankPayloadIsNotGs1() {
        assertTrue(Gs1Parser.parse("   ").isEmpty());
    }

    /**
     * A payload starting with the article identifier but too short to carry it is not
     * claimed — the length leg of the bare-form exception.
     */
    @Test
    void aShortPayloadStartingWithTheArticleIdentifierIsNotGs1() {
        assertTrue(Gs1Parser.parse("0109526000134").isEmpty());
    }

    /**
     * A payload long enough but whose article identifier is not fourteen digits is not
     * claimed either — the all-digits leg of the same exception.
     */
    @Test
    void aLongPayloadWithoutFourteenDigitsIsNotGs1() {
        assertTrue(Gs1Parser.parse("01ABCDEFGHIJKLMN10XYZ").isEmpty());
    }

    // --------------------------------------------------
    // Telling a marked payload from a bare one
    // --------------------------------------------------

    /**
     * Each of the four markers is recognised on its own, and a bare payload carries
     * none — every leg of the guard the scan chain leans on.
     */
    @Test
    void everyMarkerIsRecognisedAndABarePayloadCarriesNone() {
        assertTrue(Gs1Parser.hasExplicitMarker("(01)09526000134367"));
        assertTrue(Gs1Parser.hasExplicitMarker("]C1010952600013436710A"));
        assertTrue(Gs1Parser.hasExplicitMarker("010952600013436710A" + GS + "3103000195"));
        assertTrue(Gs1Parser.hasExplicitMarker("https://example.com/01/09526000134367"));
        assertFalse(Gs1Parser.hasExplicitMarker("010952600013436710ABC123"));
        assertFalse(Gs1Parser.hasExplicitMarker(null));
        assertFalse(Gs1Parser.hasExplicitMarker(""));
        assertFalse(Gs1Parser.hasExplicitMarker("   "));
    }

    // --------------------------------------------------
    // The three shapes of an Element String
    // --------------------------------------------------

    /**
     * The human-readable form a specification writes decodes.
     */
    @Test
    void theHumanReadableFormDecodes() {
        Gs1Message message = Gs1Parser.parse("(01)09526000134367(10)ABC123");
        assertEquals(2, message.getElements().size());
        assertEquals("09526000134367", message.value("01"));
        assertEquals("ABC123", message.value("10"));
    }

    /**
     * The bare concatenation decodes, its variable-length element ending the payload.
     */
    @Test
    void theBareConcatenationDecodes() {
        Gs1Message message = Gs1Parser.parse("010952600013436710ABC123");
        assertEquals("09526000134367", message.value("01"));
        assertEquals("ABC123", message.value("10"));
    }

    /**
     * The transmitted form decodes: a symbology identifier is dropped and the
     * separators close the variable-length elements.
     */
    @Test
    void theTransmittedFormDecodes() {
        Gs1Message message =
                Gs1Parser.parse("]C1010952600013436710ABC123" + GS + "3103000195");
        assertEquals(3, message.getElements().size());
        assertEquals("ABC123", message.value("10"));
        assertEquals(new BigDecimal("0.195"), message.decimal("310"));
    }

    /**
     * A run of separators between two elements is stepped over rather than read as
     * elements of its own — the separator leg of the reading loop.
     */
    @Test
    void repeatedSeparatorsAreSteppedOver() {
        Gs1Message message =
                Gs1Parser.parse("]C110ABC" + GS + GS + "3103000195");
        assertEquals(2, message.getElements().size());
        assertEquals("ABC", message.value("10"));
    }

    /**
     * An unclosed parenthesis does not lose the payload: what follows is read as it
     * stands rather than refused.
     */
    @Test
    void anUnclosedParenthesisDoesNotLoseThePayload() {
        Gs1Message message = Gs1Parser.parse("(01)09526000134367(10ABC123");
        assertEquals("09526000134367", message.value("01"));
    }

    // --------------------------------------------------
    // Delimiting
    // --------------------------------------------------

    /**
     * A fixed-length element needs no separator: three of them run together and each
     * comes back whole.
     */
    @Test
    void fixedLengthElementsRunTogether() {
        Gs1Message message = Gs1Parser.parse("(01)03560070123456(3103)000195(17)261130");
        assertEquals(3, message.getElements().size());
        assertEquals("03560070123456", message.value("01"));
        assertEquals(new BigDecimal("0.195"), message.decimal("310"));
        assertEquals(LocalDate.of(2026, 11, 30), message.date("17"));
    }

    /**
     * A payload that stops in the middle of a fixed-length element yields what was
     * really there rather than reading past its end.
     */
    @Test
    void aTruncatedFixedLengthElementYieldsWhatIsThere() {
        Gs1Message message = Gs1Parser.parse("(15)2600");
        assertEquals("2600", message.value("15"));
        assertNull(message.date("15"));
    }

    /**
     * A four-digit identifier wins over the three- and two-digit readings of the same
     * digits: {@code 8005} is a price per unit and not the family {@code 80}.
     */
    @Test
    void theLongestIdentifierWins() {
        Gs1Message message = Gs1Parser.parse("(8005)001234");
        assertEquals("001234", message.value("8005"));
    }

    /**
     * An identifier this version does not know, sitting BETWEEN two it does, is kept
     * and stepped over — {@code LC-11-03-03} in one test: the article is still there
     * after it.
     */
    @Test
    void anUnknownIdentifierIsKeptAndSteppedOver() {
        Gs1Message message = Gs1Parser.parse("(01)03560070123456(99)XYZ(17)261130");
        assertEquals("03560070123456", message.value("01"));
        assertEquals(LocalDate.of(2026, 11, 30), message.date("17"));
        assertEquals(1, message.getUnknownElements().size());
        assertEquals("XYZ", message.getUnknownElements().get(0).value());
        assertFalse(message.getUnknownElements().get(0).isKnown());
        assertEquals("AI inconnu", message.getUnknownElements().get(0).getLabel());
    }

    /**
     * An unknown identifier that nothing closes takes the rest of the payload and the
     * reading stops there: everything after it is undelimitable, and inventing elements
     * out of it would be worse than admitting the payload ended.
     */
    @Test
    void anUnclosedUnknownIdentifierEndsTheReading() {
        Gs1Message message = Gs1Parser.parse("(01)03560070123456" + GS + "99XYZ123");
        assertEquals("03560070123456", message.value("01"));
        assertEquals(1, message.getUnknownElements().size());
        assertEquals("XYZ123", message.getUnknownElements().get(0).value());
    }

    /**
     * A payload ending on an identifier too short to be one stops rather than reading
     * past the end — the bounds leg of the unknown reading.
     */
    @Test
    void aDanglingSingleCharacterEndsTheReading() {
        Gs1Message message = Gs1Parser.parse("(01)03560070123456" + GS + "9");
        assertEquals("03560070123456", message.value("01"));
        assertEquals(1, message.getElements().size());
    }

    // --------------------------------------------------
    // The Digital Link URI (LC-11-02-03 / -06)
    // --------------------------------------------------

    /**
     * A Digital Link URI decodes from its path, in pairs.
     */
    @Test
    void aDigitalLinkPathDecodes() {
        Gs1Message message =
                Gs1Parser.parse("https://example.com/01/09526000134367/10/ABC123");
        assertEquals("09526000134367", message.value("01"));
        assertEquals("ABC123", message.value("10"));
    }

    /**
     * Its query string carries elements too, the decimal-place digit included.
     */
    @Test
    void aDigitalLinkQueryStringDecodes() {
        Gs1Message message = Gs1Parser
                .parse("https://example.com/01/09526000134367?3103=000195&17=271231");
        assertEquals(new BigDecimal("0.195"), message.decimal("310"));
        assertEquals(LocalDate.of(2027, 12, 31), message.date("17"));
    }

    /**
     * A plain {@code http} address decodes like a secure one — the other leg of the
     * scheme guard.
     */
    @Test
    void aPlainHttpDigitalLinkDecodes() {
        assertEquals("09526000134367",
                Gs1Parser.parse("http://example.com/01/09526000134367").value("01"));
    }

    /**
     * Percent-encoded values are decoded: a lot number may carry a character a URL
     * cannot.
     */
    @Test
    void percentEncodedValuesAreDecoded() {
        assertEquals("A B",
                Gs1Parser.parse("https://x.io/01/09526000134367/10/A%20B").value("10"));
    }

    /**
     * A malformed percent escape keeps the value as it stands rather than losing it.
     */
    @Test
    void aMalformedEscapeKeepsTheValue() {
        assertEquals("A%ZZ",
                Gs1Parser.parse("https://x.io/01/09526000134367/10/A%ZZ").value("10"));
    }

    /**
     * A path segment that is not an identifier is skipped and the pairs after it are
     * still read — a Digital Link may carry ordinary segments.
     */
    @Test
    void aNonIdentifierSegmentIsSkipped() {
        Gs1Message message =
                Gs1Parser.parse("https://example.com/gtin/01/09526000134367");
        assertEquals("09526000134367", message.value("01"));
    }

    /**
     * An address carrying no identifier at all decodes to nothing, so an ordinary URL
     * scanned by accident is not claimed.
     */
    @Test
    void anAddressWithoutIdentifiersIsNotGs1() {
        assertTrue(Gs1Parser.parse("https://example.com/promo/ete").isEmpty());
    }

    /**
     * A Digital Link keeps an identifier this version does not know: the address
     * delimits it for us, so there is nothing preventing the record
     * ({@code LC-11-03-02}).
     */
    @Test
    void aDigitalLinkKeepsUnknownIdentifiers() {
        Gs1Message message =
                Gs1Parser.parse("https://x.io/01/09526000134367/99/ZZZ");
        assertEquals(1, message.getUnknownElements().size());
        assertEquals("ZZZ", message.getUnknownElements().get(0).value());
    }

    /**
     * A query parameter without a value is dropped rather than read as an empty
     * element — the equals-sign leg.
     */
    @Test
    void aValuelessQueryParameterIsDropped() {
        Gs1Message message =
                Gs1Parser.parse("https://x.io/01/09526000134367?flag&17=271231");
        assertEquals(2, message.getElements().size());
    }

    // --------------------------------------------------
    // Reading the values
    // --------------------------------------------------

    /**
     * The decimal-place digit shifts the point: the same digits are a weight or an
     * amount depending on it alone.
     */
    @Test
    void theDecimalDigitShiftsThePoint() {
        assertEquals(new BigDecimal("0.195"), Gs1Parser.parse("(3103)000195").decimal("310"));
        assertEquals(new BigDecimal("195"), Gs1Parser.parse("(3100)000195").decimal("310"));
        assertEquals(new BigDecimal("12.50"), Gs1Parser.parse("(3922)1250").decimal("392"));
    }

    /**
     * A day of {@code 00} means the end of that month, which is how a best-before is
     * encoded when only the month matters.
     */
    @Test
    void aDayOfZeroMeansTheEndOfTheMonth() {
        assertEquals(LocalDate.of(2026, 2, 28), Gs1Parser.parse("(17)260200").date("17"));
    }

    /**
     * A month outside the year is not a date — the month leg of the guard.
     */
    @Test
    void animpossibleMonthIsNotADate() {
        assertNull(Gs1Parser.parse("(17)261330").date("17"));
    }

    /**
     * A day the month does not have is not a date either — the calendar leg.
     */
    @Test
    void animpossibleDayIsNotADate() {
        assertNull(Gs1Parser.parse("(17)260431").date("17"));
    }

    /**
     * A value that is not digits is not a date — the digits leg.
     */
    @Test
    void aNonNumericValueIsNotADate() {
        assertNull(Gs1Parser.parse("(10)ABC123").get("10").asDate());
    }

    /**
     * A value of the wrong length is not a date — the length leg.
     */
    @Test
    void aValueOfTheWrongLengthIsNotADate() {
        assertNull(Gs1Parser.parse("(15)2600").date("15"));
    }

    /**
     * A non-numeric value is not a number and not a count either — both digit guards.
     */
    @Test
    void aNonNumericValueIsNeitherAmountNorCount() {
        Gs1Element element = Gs1Parser.parse("(10)ABC123").get("10");
        assertNull(element.asDecimal());
        assertNull(element.asInteger());
    }

    /**
     * A count with more digits than a whole number holds is not a count.
     */
    @Test
    void anOversizedCountIsNotACount() {
        assertNull(new Gs1Element("30", Gs1ApplicationIdentifier.of("30"),
                "99999999999999", 0).asInteger());
    }

    // --------------------------------------------------
    // The article code (LC-11-03-04)
    // --------------------------------------------------

    /**
     * A GTIN that is a padded EAN-13 offers the thirteen-digit spelling first, the
     * padded one after: the catalog holds the code printed on the shelf.
     */
    @Test
    void aPaddedEan13OffersItsPrintedSpellingFirst() {
        List<String> codes = Gs1Parser.parse("(01)03560070123456").getArticleCodes();
        assertEquals("3560070123456", codes.get(0));
        assertTrue(codes.contains("03560070123456"));
    }

    /**
     * A GTIN that is a padded EAN-8 offers the eight-digit spelling first — shortest
     * first, because an EAN-8 the standard padded is an EAN-8.
     */
    @Test
    void aPaddedEan8OffersItsPrintedSpellingFirst() {
        assertEquals("12345670",
                Gs1Parser.parse("(01)00000012345670").getArticleCode());
    }

    /**
     * A GTIN whose significant digits fit no shelf length offers every length that
     * could hold them, rather than guessing one.
     */
    @Test
    void anAmbiguousGtinOffersEverySpelling() {
        List<String> codes = Gs1Parser.parse("(01)00003560070129").getArticleCodes();
        assertEquals(List.of("003560070129", "0003560070129", "00003560070129"), codes);
    }

    /**
     * A true fourteen-digit GTIN — a case or a pallet — is offered as it stands: its
     * leading digit is a packaging indicator and not padding.
     */
    @Test
    void aPackagingGtinIsOfferedWhole() {
        assertEquals(List.of("13560070123456"),
                Gs1Parser.parse("(01)13560070123456").getArticleCodes());
    }

    /**
     * A payload naming no article offers nothing, and its likeliest spelling is
     * nothing — both legs of the caller's guard.
     */
    @Test
    void aPayloadWithoutAnArticleOffersNothing() {
        Gs1Message message = Gs1Parser.parse("(10)ABC123");
        assertTrue(message.getArticleCodes().isEmpty());
        assertNull(message.getArticleCode());
    }

    // --------------------------------------------------
    // The message itself
    // --------------------------------------------------

    /**
     * The message answers what it holds and what it does not, and hands back the
     * payload it was given.
     */
    @Test
    void theMessageAnswersWhatItHolds() {
        Gs1Message message = Gs1Parser.parse("(01)03560070123456(10)L42");
        assertTrue(message.has("01"));
        assertFalse(message.has("17"));
        assertNull(message.value("17"));
        assertNull(message.decimal("17"));
        assertNull(message.date("17"));
        assertNull(message.get("17"));
        assertEquals("(01)03560070123456(10)L42", message.getRawPayload());
    }

    /**
     * The journal line names every identifier, its meaning and its value — the shape
     * {@code LC-11-03-02} is recorded in.
     */
    @Test
    void theJournalLineNamesEveryIdentifier() {
        String described = Gs1Parser.parse("(01)03560070123456(99)ZZ").describe();
        assertTrue(described.contains("(01)03560070123456"));
        assertTrue(described.contains("GTIN"));
        assertTrue(described.contains("(99)ZZ"));
        assertTrue(described.contains("AI inconnu"));
    }

    /**
     * An identifier resolved on its own answers its own shape, and one no version
     * knows resolves to nothing.
     */
    @Test
    void identifiersAnswerTheirOwnShape() {
        assertTrue(Gs1ApplicationIdentifier.of("01").isFixedLength());
        assertFalse(Gs1ApplicationIdentifier.of("10").isFixedLength());
        assertTrue(Gs1ApplicationIdentifier.of("310").decimal());
        assertNull(Gs1ApplicationIdentifier.of("777"));
        assertNull(Gs1ApplicationIdentifier.of(null));
    }

    /**
     * Reading an identifier out of bounds answers nothing rather than failing — the
     * three bounds legs of the lookup.
     */
    @Test
    void readingAnIdentifierOutOfBoundsAnswersNothing() {
        assertNull(Gs1ApplicationIdentifier.at(null, 0));
        assertNull(Gs1ApplicationIdentifier.at("01", -1));
        assertNull(Gs1ApplicationIdentifier.at("01", 5));
    }

    /**
     * The identifier of a known element is the table's own instance, so a caller can
     * compare it by identity.
     */
    @Test
    void aKnownElementCarriesTheTablesIdentifier() {
        assertSame(Gs1ApplicationIdentifier.of("01"),
                Gs1Parser.parse("(01)03560070123456").get("01").identifier());
    }
}
