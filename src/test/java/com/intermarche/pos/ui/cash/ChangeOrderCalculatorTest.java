package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.cash.ChangeOrderCalculator.RollValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChangeOrderCalculator}.
 * <p>
 * The calculator is a pure static helper: a roll-value list parser and the two
 * mutually-inverse conversions amount&harr;rolls (LC-12-05-06/07). No
 * collaborators, no register.
 * <p>
 * Branch enumeration (both arms of every decision, 100%): {@code parseRollValues}
 * (null list arm, malformed pair arm, unparsable face arm, unparsable roll arm,
 * non-positive face arm, non-positive roll arm, duplicate face arm, valid arm);
 * {@code rollValueOf} (null face arm, match arm, no-match arm);
 * {@code rollsForAmount} (null roll value, non-positive roll value, null amount,
 * negative amount, valid arm); {@code amountForRolls} (null roll value,
 * non-positive roll value, negative rolls, valid arm); plus the reversibility of
 * the two directions.
 */
class ChangeOrderCalculatorTest {

    /**
     * A null administered list yields an empty correspondence (null arm).
     */
    @Test
    void parseRollValuesNullYieldsEmpty() {
        assertTrue(ChangeOrderCalculator.parseRollValues(null).isEmpty());
    }

    /**
     * The parser keeps well-formed rows and drops the malformed, unparsable,
     * non-positive and duplicate ones (every skip arm plus the valid arm).
     */
    @Test
    void parseRollValuesKeepsValidAndDropsTheRest() {
        List<RollValue> values = ChangeOrderCalculator.parseRollValues(
                "0.20:8.00;bad;x:8;0.10:y;0:5;0.05:0;0.20:9.99;0.01:0.50");
        assertEquals(2, values.size());
        assertEquals(0, new BigDecimal("0.20").compareTo(values.get(0).faceValue()));
        assertEquals(0, new BigDecimal("8.00").compareTo(values.get(0).rollValue()));
        assertEquals(0, new BigDecimal("0.01").compareTo(values.get(1).faceValue()));
        assertEquals(0, new BigDecimal("0.50").compareTo(values.get(1).rollValue()));
    }

    /**
     * The duplicate face value keeps the FIRST administered roll value, not the
     * later one (duplicate-skip arm).
     */
    @Test
    void parseRollValuesKeepsFirstOnDuplicateFace() {
        List<RollValue> values = ChangeOrderCalculator.parseRollValues("0.20:8.00;0.20:9.99");
        assertEquals(1, values.size());
        assertEquals(0, new BigDecimal("8.00").compareTo(values.get(0).rollValue()));
    }

    /**
     * {@code rollValueOf} returns null for a null face (null arm).
     */
    @Test
    void rollValueOfNullFaceIsNull() {
        List<RollValue> values = ChangeOrderCalculator.parseRollValues("0.20:8.00");
        assertNull(ChangeOrderCalculator.rollValueOf(values, null));
    }

    /**
     * {@code rollValueOf} returns the matching roll value (match arm) and null
     * for an absent face (no-match arm), matching by numeric value not string.
     */
    @Test
    void rollValueOfMatchesByNumericValue() {
        List<RollValue> values = ChangeOrderCalculator.parseRollValues("0.20:8.00");
        assertEquals(0, new BigDecimal("8.00")
                .compareTo(ChangeOrderCalculator.rollValueOf(values, new BigDecimal("0.2"))));
        assertNull(ChangeOrderCalculator.rollValueOf(values, new BigDecimal("0.10")));
    }

    /**
     * {@code rollsForAmount} refuses a null roll value (null arm).
     */
    @Test
    void rollsForAmountNullRollValueIsNull() {
        assertNull(ChangeOrderCalculator.rollsForAmount(null, new BigDecimal("8.00")));
    }

    /**
     * {@code rollsForAmount} refuses a non-positive roll value (non-positive arm).
     */
    @Test
    void rollsForAmountNonPositiveRollValueIsNull() {
        assertNull(ChangeOrderCalculator.rollsForAmount(BigDecimal.ZERO, new BigDecimal("8.00")));
    }

    /**
     * {@code rollsForAmount} refuses a null amount (null-amount arm).
     */
    @Test
    void rollsForAmountNullAmountIsNull() {
        assertNull(ChangeOrderCalculator.rollsForAmount(new BigDecimal("8.00"), null));
    }

    /**
     * {@code rollsForAmount} refuses a negative amount (negative-amount arm).
     */
    @Test
    void rollsForAmountNegativeAmountIsNull() {
        assertNull(ChangeOrderCalculator.rollsForAmount(new BigDecimal("8.00"), new BigDecimal("-1")));
    }

    /**
     * {@code rollsForAmount} divides the amount by the roll value and rounds to
     * the nearest whole roll (valid arm): 8 € at 8 €/roll is one roll.
     */
    @Test
    void rollsForAmountDividesAndRounds() {
        assertEquals(1, ChangeOrderCalculator.rollsForAmount(new BigDecimal("8.00"), new BigDecimal("8.00")));
        assertEquals(2, ChangeOrderCalculator.rollsForAmount(new BigDecimal("8.00"), new BigDecimal("16.00")));
        assertEquals(3, ChangeOrderCalculator.rollsForAmount(new BigDecimal("0.50"), new BigDecimal("1.50")));
    }

    /**
     * {@code amountForRolls} refuses a null roll value (null arm).
     */
    @Test
    void amountForRollsNullRollValueIsNull() {
        assertNull(ChangeOrderCalculator.amountForRolls(null, 3));
    }

    /**
     * {@code amountForRolls} refuses a non-positive roll value (non-positive arm).
     */
    @Test
    void amountForRollsNonPositiveRollValueIsNull() {
        assertNull(ChangeOrderCalculator.amountForRolls(new BigDecimal("-1"), 3));
    }

    /**
     * {@code amountForRolls} refuses a negative roll count (negative arm).
     */
    @Test
    void amountForRollsNegativeRollsIsNull() {
        assertNull(ChangeOrderCalculator.amountForRolls(new BigDecimal("0.50"), -1));
    }

    /**
     * {@code amountForRolls} multiplies the roll count by the roll value at two
     * decimals (valid arm): 3 rolls at 0,50 €/roll is 1,50 € (LC-12-05-07).
     */
    @Test
    void amountForRollsMultiplies() {
        assertEquals(0, new BigDecimal("1.50")
                .compareTo(ChangeOrderCalculator.amountForRolls(new BigDecimal("0.50"), 3)));
        assertEquals("1.50", ChangeOrderCalculator.amountForRolls(new BigDecimal("0.50"), 3).toPlainString());
    }

    /**
     * The two directions are exact inverses on multiples of the roll value: an
     * amount converted to rolls and back yields the amount, and rolls converted
     * to an amount and back yield the rolls.
     */
    @Test
    void bothDirectionsVerifyEachOther() {
        BigDecimal rollValue = new BigDecimal("8.00");
        BigDecimal amount = new BigDecimal("24.00");
        Integer rolls = ChangeOrderCalculator.rollsForAmount(rollValue, amount);
        assertEquals(0, amount.compareTo(ChangeOrderCalculator.amountForRolls(rollValue, rolls)));
        int startRolls = 5;
        BigDecimal computed = ChangeOrderCalculator.amountForRolls(rollValue, startRolls);
        assertEquals(startRolls, ChangeOrderCalculator.rollsForAmount(rollValue, computed));
    }

    /** LC-12-05: the decimal parser distinguishes absent and blank values from malformed numeric text. */
    @org.junit.jupiter.api.Test
    void coverageNullAndBlankDecimal() throws Exception {
        var method = ChangeOrderCalculator.class.getDeclaredMethod("toDecimal", String.class);
        method.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertNull(method.invoke(null, (Object) null));
        org.junit.jupiter.api.Assertions.assertNull(method.invoke(null, " "));
    }

    /**
     * BO-03-02-42: a composition may carry its number of coins; it is kept
     * when the three figures agree, dropped when they disagree or when the
     * count is unreadable or not positive; a pair keeps a derived count, and a
     * row of four fields is dropped.
     */
    @Test
    void parseRollValuesReadsTheCoinCount() {
        List<RollValue> values = ChangeOrderCalculator.parseRollValues(
                "0.20:8.00:40;0.10:4.00:41;0.05:2.00:x;0.02:1.00:0;0.01:0.50;1:25:25:9");
        assertEquals(2, values.size());
        assertEquals(40, values.get(0).coinCount());
        assertEquals(new BigDecimal("0.01"), values.get(1).faceValue());
        assertEquals(50, values.get(1).coinCount());
    }
}
