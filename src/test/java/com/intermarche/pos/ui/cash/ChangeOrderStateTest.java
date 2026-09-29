package com.intermarche.pos.ui.cash;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChangeOrderState}.
 * <p>
 * A plain in-memory staging holder for the change-order slip; no collaborators.
 * <p>
 * Branch enumeration (both arms, 100%): {@code get} (null id arm, present arm);
 * {@code getAmountFormatted} (null entry arm, null amount arm, present arm);
 * {@code getRollsFormatted} (null entry arm, null rolls arm, present arm);
 * {@code clear}.
 */
class ChangeOrderStateTest {

    /**
     * Builds a state carrying one entry with the given amount and rolls.
     *
     * @param id the denomination id
     * @param amount the amount, or null
     * @param rolls the roll count, or null
     * @return the state
     */
    private ChangeOrderState stateWith(String id, BigDecimal amount, Integer rolls) {
        ChangeOrderState state = new ChangeOrderState();
        ChangeOrderState.Entry entry = new ChangeOrderState.Entry();
        entry.amount = amount;
        entry.rolls = rolls;
        state.entries.put(id, entry);
        return state;
    }

    /**
     * {@code get} returns null for a null id (null arm) and for an unknown id,
     * and the stored entry for a present one (present arm).
     */
    @Test
    void getResolvesNullAndPresent() {
        ChangeOrderState state = stateWith("b50", new BigDecimal("100.00"), 2);
        assertNull(state.get(null));
        assertNull(state.get("c001"));
        assertEquals(2, state.get("b50").rolls);
    }

    /**
     * {@code getAmountFormatted} yields an empty string when no entry exists
     * (null-entry arm) and when the entry carries no amount (null-amount arm),
     * and the two-decimal French format otherwise (present arm).
     */
    @Test
    void getAmountFormattedCoversEveryArm() {
        assertEquals("", new ChangeOrderState().getAmountFormatted("b50"));
        assertEquals("", stateWith("b50", null, 2).getAmountFormatted("b50"));
        assertEquals("100,00", stateWith("b50", new BigDecimal("100"), 2).getAmountFormatted("b50"));
    }

    /**
     * {@code getRollsFormatted} yields an empty string when no entry exists
     * (null-entry arm) and when the entry carries no roll count (null-rolls arm),
     * and the count as text otherwise (present arm).
     */
    @Test
    void getRollsFormattedCoversEveryArm() {
        assertEquals("", new ChangeOrderState().getRollsFormatted("b50"));
        assertEquals("", stateWith("b50", new BigDecimal("100"), null).getRollsFormatted("b50"));
        assertEquals("2", stateWith("b50", new BigDecimal("100"), 2).getRollsFormatted("b50"));
    }

    /**
     * {@code clear} empties every dialed entry.
     */
    @Test
    void clearEmptiesTheEntries() {
        ChangeOrderState state = stateWith("b50", new BigDecimal("100"), 2);
        state.clear();
        assertTrue(state.entries.isEmpty());
    }
}
