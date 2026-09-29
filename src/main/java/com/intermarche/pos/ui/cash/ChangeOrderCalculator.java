package com.intermarche.pos.ui.cash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure cross-calculation of a change-order slip (LC-12-05-06/07): converts a
 * desired euro amount of a denomination into a number of coin rolls and back,
 * using the administered correspondence "one full roll of a given face value is
 * worth so many euros" ({@code change-order.roll-values}).
 * <p>
 * The two directions are exact inverses of each other on multiples of the roll
 * value — {@code amount = rolls * rollValue} and
 * {@code rolls = round(amount / rollValue)} — which is what lets the screen
 * offer either entry and show the other, each verifying the operator's figure.
 * Everything here is static and side-effect free, so it is fully unit-testable
 * without a register.
 */
public final class ChangeOrderCalculator {

    /**
     * Not instantiable.
     */
    private ChangeOrderCalculator() {
    }

    /**
     * One administered correspondence: the euro value of a full roll of coins
     * of a given face value.
     *
     * @param faceValue the coin/note face value, in euros
     * @param rollValue the euro value of one full roll of that denomination
     */
    public record RollValue(BigDecimal faceValue, BigDecimal rollValue) {
    }

    /**
     * Parses the administered {@code FACIALE:VALEUR_ROULEAU} list, dropping
     * malformed rows, non-positive values and duplicate face values; never null.
     *
     * @param administered the raw semicolon list, or null
     * @return the parsed correspondences, in administered order
     */
    public static List<RollValue> parseRollValues(String administered) {
        List<RollValue> values = new ArrayList<>();
        if (administered == null) {
            return values;
        }
        for (String entry : administered.split(";")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            BigDecimal face = toDecimal(parts[0]);
            BigDecimal roll = toDecimal(parts[1]);
            if (face == null || roll == null || face.signum() <= 0 || roll.signum() <= 0) {
                continue;
            }
            if (rollValueOf(values, face) != null) {
                continue;
            }
            values.add(new RollValue(face, roll));
        }
        return values;
    }

    /**
     * The euro value of a full roll of the given face value, or null when the
     * correspondence administers none for it.
     *
     * @param values the parsed correspondences
     * @param faceValue the face value to look up, possibly null
     * @return the roll value, or null when absent
     */
    public static BigDecimal rollValueOf(List<RollValue> values, BigDecimal faceValue) {
        if (faceValue == null) {
            return null;
        }
        for (RollValue value : values) {
            if (value.faceValue().compareTo(faceValue) == 0) {
                return value.rollValue();
            }
        }
        return null;
    }

    /**
     * The number of rolls covering a desired amount (LC-12-05-06): the amount
     * divided by the roll value, rounded to the nearest whole roll.
     *
     * @param rollValue the euro value of one roll, or null when unknown
     * @param amount the desired amount, or null
     * @return the number of rolls, or null when the amount cannot be converted
     *         (unknown or non-positive roll value, or null/negative amount)
     */
    public static Integer rollsForAmount(BigDecimal rollValue, BigDecimal amount) {
        if (rollValue == null || rollValue.signum() <= 0 || amount == null || amount.signum() < 0) {
            return null;
        }
        return amount.divide(rollValue, 0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * The amount that a number of rolls represents (LC-12-05-07): the roll count
     * times the roll value, at two decimals.
     *
     * @param rollValue the euro value of one roll, or null when unknown
     * @param rolls the number of rolls
     * @return the amount, or null when the rolls cannot be converted (unknown or
     *         non-positive roll value, or a negative roll count)
     */
    public static BigDecimal amountForRolls(BigDecimal rollValue, int rolls) {
        if (rollValue == null || rollValue.signum() <= 0 || rolls < 0) {
            return null;
        }
        return rollValue.multiply(BigDecimal.valueOf(rolls)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Parses a decimal string, tolerating the French comma; null when blank or
     * unparsable.
     *
     * @param value the raw value, or null
     * @return the parsed decimal, or null
     */
    private static BigDecimal toDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
