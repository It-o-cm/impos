package com.intermarche.pos.ui.cash;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory state of the change-order slip (LC-12-05): the amount and/or number
 * of rolls the operator dialed for each denomination before printing the
 * bordereau. Nothing here is fiscal — a change order is a preparation document,
 * so the entries are staging only and are cleared once printed.
 * <p>
 * Each entry records which figure the operator typed ({@code AMOUNT} or
 * {@code ROLLS}, LC-12-05-05) so the screen can highlight the driven cell and
 * show the other one as the computed cross value (LC-12-05-06/07).
 */
public class ChangeOrderState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The operator dialed an amount, the roll count is computed. */
    public static final String TYPE_AMOUNT = "AMOUNT";

    /** The operator dialed a roll count, the amount is computed. */
    public static final String TYPE_ROLLS = "ROLLS";

    /** The entries keyed by denomination id (the {@link CashItem} id). */
    public Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * One denomination's dialed figures.
     */
    public static class Entry implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Which figure the operator typed: {@code AMOUNT} or {@code ROLLS}. */
        public String type;

        /** The amount for this denomination, or null when none. */
        public BigDecimal amount;

        /** The number of rolls for this denomination, or null when none. */
        public Integer rolls;
    }

    /**
     * The entry of a denomination, or null when none was dialed.
     *
     * @param id the denomination id
     * @return the entry, or null
     */
    public Entry get(String id) {
        return id == null ? null : entries.get(id);
    }

    /**
     * The amount dialed for a denomination, formatted with two decimals and a
     * French comma, or an empty string when none.
     *
     * @param id the denomination id
     * @return the formatted amount, or an empty string
     */
    public String getAmountFormatted(String id) {
        Entry entry = get(id);
        if (entry == null || entry.amount == null) {
            return "";
        }
        return entry.amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /**
     * The number of rolls dialed for a denomination, or an empty string when
     * none.
     *
     * @param id the denomination id
     * @return the roll count as text, or an empty string
     */
    public String getRollsFormatted(String id) {
        Entry entry = get(id);
        if (entry == null || entry.rolls == null) {
            return "";
        }
        return Integer.toString(entry.rolls);
    }

    /**
     * The TOTAL of the dialed amounts: what the order is worth, and so what the
     * cashier hands over to be given the change.
     *
     * <p>The slip listed the denominations and nothing else, which left the amount to
     * pay for them to be added up by hand — and that figure is half of the procedure:
     * the paper the cashier gives her manager says which rolls she needs AND how much
     * she is paying for them.
     *
     * <p>An entry dialed in ROLLS carries its computed amount like any other, so the
     * total is the sum of the amounts whichever figure was typed; an entry with no
     * amount counts for nothing.
     *
     * @return the total of the dialed amounts, never null
     */
    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (Entry entry : entries.values()) {
            if (entry != null && entry.amount != null) {
                total = total.add(entry.amount);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The total of the dialed amounts, formatted with two decimals and a French
     * comma.
     *
     * @return the formatted total
     */
    public String getTotalFormatted() {
        return total().toPlainString().replace('.', ',');
    }

    /**
     * Clears every dialed entry.
     */
    public void clear() {
        entries.clear();
    }
}
