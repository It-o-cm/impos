package com.intermarche.pos.domain.barcode.gs1;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link Gs1Element}: the accessors, the typed readings and the guards
 * that keep a badly encoded element from ever throwing ({@code LC-11-03-02/03}).
 *
 * <p>Each guard leg is exercised on its own — every leg of every compound test, both
 * arms of every ternary, and the try/catch escape of the two parsing readings — so the
 * branch coverage of the record is complete for the current year's century window.
 */
class Gs1ElementTest {

    /** The known GTIN identifier the table carries, used for the known-element legs. */
    private static final Gs1ApplicationIdentifier GTIN = Gs1ApplicationIdentifier.of("01");

    /**
     * A known element answers true to {@link Gs1Element#isKnown()} — the non-null leg.
     */
    @Test
    void aKnownElementIsKnown() {
        assertTrue(new Gs1Element("01", GTIN, "09526000134367", 0).isKnown());
    }

    /**
     * An element carrying no identifier answers false — the null leg.
     */
    @Test
    void anUnknownElementIsNotKnown() {
        assertFalse(new Gs1Element("99", null, "ZZ", 0).isKnown());
    }

    /**
     * A known element's label is the identifier's own — the non-null arm of the ternary.
     */
    @Test
    void aKnownElementLabelsWithItsIdentifier() {
        assertEquals(GTIN.label(), new Gs1Element("01", GTIN, "09526000134367", 0).getLabel());
    }

    /**
     * An unknown element is labelled "AI inconnu" — the null arm of the ternary.
     */
    @Test
    void anUnknownElementLabelsAsUnknown() {
        assertEquals("AI inconnu", new Gs1Element("99", null, "ZZ", 0).getLabel());
    }

    /**
     * A null value is not a date — the null leg of the first guard.
     */
    @Test
    void aNullValueIsNotADate() {
        assertNull(new Gs1Element("17", null, null, 0).asDate());
    }

    /**
     * A value of the wrong length is not a date — the length leg of the first guard.
     */
    @Test
    void aValueOfTheWrongLengthIsNotADate() {
        assertNull(new Gs1Element("15", null, "2600", 0).asDate());
    }

    /**
     * A six-character value that is not all digits is not a date — the digits leg.
     */
    @Test
    void aNonNumericValueIsNotADate() {
        assertNull(new Gs1Element("10", null, "ABC123", 0).asDate());
    }

    /**
     * A valid date reads as itself, both guard legs passing and the day-non-zero arm of
     * the ternary taken, in the fall-through century (both century ifs false).
     */
    @Test
    void aValidDateReadsAsItself() {
        assertEquals(LocalDate.of(2026, 1, 15), new Gs1Element("17", null, "260115", 0).asDate());
    }

    /**
     * A day of {@code 00} means the end of that month — the day-zero arm of the ternary.
     */
    @Test
    void aDayOfZeroMeansTheEndOfTheMonth() {
        assertEquals(LocalDate.of(2026, 2, 28), new Gs1Element("17", null, "260200", 0).asDate());
    }

    /**
     * A month of {@code 00} is not a date — the {@code month < 1} leg of the month guard.
     */
    @Test
    void aMonthBelowOneIsNotADate() {
        assertNull(new Gs1Element("17", null, "260015", 0).asDate());
    }

    /**
     * A month above twelve is not a date — the {@code month > 12} leg, the first leg
     * being false.
     */
    @Test
    void aMonthAboveTwelveIsNotADate() {
        assertNull(new Gs1Element("17", null, "261315", 0).asDate());
    }

    /**
     * A day the month does not have is not a date — the {@link java.time.DateTimeException}
     * catch of the reading.
     */
    @Test
    void animpossibleDayIsNotADate() {
        assertNull(new Gs1Element("17", null, "260431", 0).asDate());
    }

    /**
     * A two-digit year far ahead of today names the previous century — the true leg of
     * the first century guard ({@code candidate - currentYear > 49}).
     */
    @Test
    void aFarAheadYearNamesThePreviousCentury() {
        assertEquals(LocalDate.of(1999, 12, 31), new Gs1Element("17", null, "991231", 0).asDate());
    }

    /**
     * A null value is not a number — the null leg of the decimal guard.
     */
    @Test
    void aNullValueIsNotADecimal() {
        assertNull(new Gs1Element("310", GTIN, null, 3).asDecimal());
    }

    /**
     * An empty value is not a number — the empty leg of the decimal guard.
     */
    @Test
    void anEmptyValueIsNotADecimal() {
        assertNull(new Gs1Element("310", GTIN, "", 3).asDecimal());
    }

    /**
     * A non-numeric value is not a number — the digits leg of the decimal guard.
     */
    @Test
    void aNonNumericValueIsNotADecimal() {
        assertNull(new Gs1Element("10", null, "ABC123", 0).asDecimal());
    }

    /**
     * A numeric value reads with the decimal-place digit applied — the guard passing and
     * the point shifted.
     */
    @Test
    void aNumericValueReadsWithItsDecimals() {
        assertEquals(new BigDecimal("0.195"), new Gs1Element("310", GTIN, "000195", 3).asDecimal());
    }

    /**
     * A numeric value with zero decimals reads whole — the zero-shift path.
     */
    @Test
    void aNumericValueWithNoDecimalsReadsWhole() {
        assertEquals(new BigDecimal("195"), new Gs1Element("310", GTIN, "000195", 0).asDecimal());
    }

    /**
     * A null value is not a count — the null leg of the integer guard.
     */
    @Test
    void aNullValueIsNotAnInteger() {
        assertNull(new Gs1Element("30", null, null, 0).asInteger());
    }

    /**
     * An empty value is not a count — the empty leg of the integer guard.
     */
    @Test
    void anEmptyValueIsNotAnInteger() {
        assertNull(new Gs1Element("30", null, "", 0).asInteger());
    }

    /**
     * A non-numeric value is not a count — the digits leg of the integer guard.
     */
    @Test
    void aNonNumericValueIsNotAnInteger() {
        assertNull(new Gs1Element("10", null, "ABC123", 0).asInteger());
    }

    /**
     * A numeric value reads as a whole number — the guard passing.
     */
    @Test
    void aNumericValueReadsAsAnInteger() {
        assertEquals(Integer.valueOf(42), new Gs1Element("30", null, "42", 0).asInteger());
    }

    /**
     * A count with more digits than an int holds is not a count — the
     * {@link NumberFormatException} catch of the reading.
     */
    @Test
    void anOversizedCountIsNotAnInteger() {
        assertNull(new Gs1Element("30", null, "99999999999999", 0).asInteger());
    }

    /**
     * The journal line names the code, the value and the label of a known element.
     */
    @Test
    void toStringNamesCodeValueAndLabelOfAKnownElement() {
        assertEquals("(01)09526000134367 [" + GTIN.label() + "]",
                new Gs1Element("01", GTIN, "09526000134367", 0).toString());
    }

    /**
     * The journal line of an unknown element names it "AI inconnu" — the null-label arm.
     */
    @Test
    void toStringNamesAnUnknownElementAsUnknown() {
        assertEquals("(99)ZZ [AI inconnu]", new Gs1Element("99", null, "ZZ", 0).toString());
    }

    /**
     * Two elements with the same components are equal and share a hash — the equal leg.
     */
    @Test
    void twoIdenticalElementsAreEqual() {
        Gs1Element one = new Gs1Element("01", GTIN, "09526000134367", 0);
        Gs1Element other = new Gs1Element("01", GTIN, "09526000134367", 0);
        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
    }

    /**
     * An element differing in one component is not equal — the differing leg.
     */
    @Test
    void anElementDifferingInOneComponentIsNotEqual() {
        Gs1Element one = new Gs1Element("01", GTIN, "09526000134367", 0);
        assertNotEquals(one, new Gs1Element("01", GTIN, "09526000134368", 0));
    }

    /**
     * An element is not equal to null nor to a different type — the null and type legs.
     */
    @Test
    void anElementEqualsNeitherNullNorAnotherType() {
        Gs1Element one = new Gs1Element("01", GTIN, "09526000134367", 0);
        assertNotEquals(null, one);
        assertNotEquals("09526000134367", one);
    }
    /** LC-11-03: the fifty-year date window crosses into the next century after the current century's midpoint. */
    @Test
    void dateWindowCanResolveTheNextCentury() {
        java.time.LocalDate future = java.time.LocalDate.of(2076, 1, 1);
        java.time.LocalDate expected = java.time.LocalDate.of(2101, 1, 1);
        try (var dates = org.mockito.Mockito.mockStatic(java.time.LocalDate.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            dates.when(java.time.LocalDate::now).thenReturn(future);
            assertEquals(expected, new Gs1Element("17", null, "010101", 0).asDate());
        }
    }

}
