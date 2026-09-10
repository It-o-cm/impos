package com.intermarche.pos.ui.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CashRounding}.
 * <p>
 * Three pure functions, and every guard of each: the null amount, the step that
 * switches rounding off (zero, one, and a negative parameter a corrupt row could
 * produce), the amount already on the step, and the two directions the rounding can
 * take — including the halfway case, which the legal rule sends up. The reference
 * values are the ones the tender itself gives: 23,42 to 23,40, 23,43 to 23,45,
 * 23,48 to 23,50. The signed difference is checked on both sides, since the whole
 * point of {@code LC-07-03-06} is that the rounding settlement can be either.
 */
class CashRoundingTest {

    /** The Belgian legal step. */
    private static final int FIVE = 5;

    /**
     * Asserts a rounded amount by value, ignoring the scale representation.
     *
     * @param expected the expected amount
     * @param actual the amount under test
     */
    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "attendu " + expected + ", obtenu " + actual);
    }

    // --------------------------------------------------
    // round
    // --------------------------------------------------

    /**
     * A null amount rounds to zero rather than throwing at the till.
     */
    @Test
    void nullAmountRoundsToZero() {
        assertAmount("0.00", CashRounding.round(null, FIVE));
    }

    /**
     * A step of zero is a shop that does not round: the amount comes back as it is.
     */
    @Test
    void zeroStepDoesNotRound() {
        assertAmount("23.42", CashRounding.round(new BigDecimal("23.42"), 0));
    }

    /**
     * A step of one cent is not a rounding either — every amount already falls on
     * it — and takes the same short-circuit.
     */
    @Test
    void oneCentStepDoesNotRound() {
        assertAmount("23.42", CashRounding.round(new BigDecimal("23.42"), 1));
    }

    /**
     * A negative step, which only a corrupt parameter row could produce, is treated
     * as no rounding rather than as an arithmetic error.
     */
    @Test
    void negativeStepDoesNotRound() {
        assertAmount("23.42", CashRounding.round(new BigDecimal("23.42"), -5));
    }

    /**
     * Two cents below the step rounds down (the tender's own example).
     */
    @Test
    void twoCentsBelowRoundsDown() {
        assertAmount("23.40", CashRounding.round(new BigDecimal("23.42"), FIVE));
    }

    /**
     * Three cents below the step rounds up (the tender's own example).
     */
    @Test
    void threeCentsBelowRoundsUp() {
        assertAmount("23.45", CashRounding.round(new BigDecimal("23.43"), FIVE));
    }

    /**
     * Two cents below the next step rounds up (the tender's own example).
     */
    @Test
    void twoCentsBelowTheNextStepRoundsUp() {
        assertAmount("23.50", CashRounding.round(new BigDecimal("23.48"), FIVE));
    }

    /**
     * An amount already on the step is left alone.
     */
    @Test
    void amountOnTheStepIsUnchanged() {
        assertAmount("23.45", CashRounding.round(new BigDecimal("23.45"), FIVE));
    }

    /**
     * Zero rounds to zero: nothing due is nothing to hand over.
     */
    @Test
    void zeroRoundsToZero() {
        assertAmount("0.00", CashRounding.round(BigDecimal.ZERO, FIVE));
    }

    /**
     * A ten-cent step rounds on its own multiples, so the rule is not hard-wired to
     * five cents.
     */
    @Test
    void anotherStepIsHonoured() {
        assertAmount("23.40", CashRounding.round(new BigDecimal("23.44"), 10));
        assertAmount("23.50", CashRounding.round(new BigDecimal("23.45"), 10));
    }

    // --------------------------------------------------
    // difference
    // --------------------------------------------------

    /**
     * A null amount produces no difference.
     */
    @Test
    void nullAmountHasNoDifference() {
        assertAmount("0.00", CashRounding.difference(null, FIVE));
    }

    /**
     * With rounding off there is nothing to book.
     */
    @Test
    void noRoundingHasNoDifference() {
        assertAmount("0.00", CashRounding.difference(new BigDecimal("23.42"), 0));
    }

    /**
     * Rounding DOWN books a POSITIVE difference, so the settlements of the sale
     * still sum to its total ({@code LC-07-03-06}).
     */
    @Test
    void roundingDownBooksAPositiveDifference() {
        assertAmount("0.02", CashRounding.difference(new BigDecimal("24.62"), FIVE));
    }

    /**
     * Rounding UP books a NEGATIVE difference — the other side of the same rule.
     */
    @Test
    void roundingUpBooksANegativeDifference() {
        assertAmount("-0.02", CashRounding.difference(new BigDecimal("23.43"), FIVE));
    }

    /**
     * An amount already on the step books nothing at all.
     */
    @Test
    void amountOnTheStepBooksNothing() {
        assertAmount("0.00", CashRounding.difference(new BigDecimal("23.45"), FIVE));
    }

    // --------------------------------------------------
    // isTenderable
    // --------------------------------------------------

    /**
     * A null amount is not tenderable.
     */
    @Test
    void nullAmountIsNotTenderable() {
        assertFalse(CashRounding.isTenderable(null, FIVE));
    }

    /**
     * With rounding off every amount is tenderable — France still has one-cent
     * coins.
     */
    @Test
    void everyAmountIsTenderableWithoutRounding() {
        assertTrue(CashRounding.isTenderable(new BigDecimal("23.42"), 0));
    }

    /**
     * A one-cent step takes the same short-circuit.
     */
    @Test
    void everyAmountIsTenderableOnAOneCentStep() {
        assertTrue(CashRounding.isTenderable(new BigDecimal("23.42"), 1));
    }

    /**
     * An amount on the step may be handed over.
     */
    @Test
    void amountOnTheStepIsTenderable() {
        assertTrue(CashRounding.isTenderable(new BigDecimal("23.45"), FIVE));
    }

    /**
     * An amount off the step may not: the coins to make it up no longer circulate
     * ({@code LC-07-03-05}).
     */
    @Test
    void amountOffTheStepIsNotTenderable() {
        assertFalse(CashRounding.isTenderable(new BigDecimal("23.42"), FIVE));
    }

    /**
     * A round note is tenderable, which is the ordinary case at the till.
     */
    @Test
    void aNoteIsTenderable() {
        assertTrue(CashRounding.isTenderable(new BigDecimal("50.00"), FIVE));
    }
}
