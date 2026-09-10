package com.intermarche.pos.ui.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The legal cash-rounding arithmetic ({@code LC-07-03-01}), and nothing else.
 *
 * <p>An amount settled in cash is rounded to the nearest multiple of the
 * administered step — five cents in Belgium since December 2019, so that one- and
 * two-cent coins stop circulating. Mathematical rounding: 23,42 gives 23,40 and
 * 23,43 gives 23,45, the halfway case going up.
 *
 * <p>Pure functions on a step expressed IN CENTS, deliberately: the rule is about
 * coins, an amount is exact to the cent, and a step carried as a decimal would let
 * a parameter of 0,05 arrive as 0,050000000000000003. A step of zero means the
 * shop does not round — France does not — and every method here then hands the
 * amount back untouched rather than making the caller special-case it.
 */
public final class CashRounding {

    /**
     * Not instantiable.
     */
    private CashRounding() {
    }

    /**
     * Rounds an amount to the nearest multiple of the step.
     *
     * @param amount the exact amount, tax included
     * @param stepCents the rounding step in cents, zero or less to not round
     * @return the amount a customer settles in cash, two decimals
     */
    public static BigDecimal round(BigDecimal amount, int stepCents) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (stepCents <= 1) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal step = BigDecimal.valueOf(stepCents, 2);
        return amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2);
    }

    /**
     * Returns the signed difference the rounding creates, as the payment ledger
     * needs it ({@code LC-07-03-06}): the real amount minus the rounded one, so
     * that the settlements of the sale still sum to its total.
     *
     * @param amount the exact amount, tax included
     * @param stepCents the rounding step in cents, zero or less to not round
     * @return the difference, positive when rounding down, negative when up, zero
     *         when the amount already falls on the step or rounding is off
     */
    public static BigDecimal difference(BigDecimal amount, int stepCents) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.setScale(2, RoundingMode.HALF_UP).subtract(round(amount, stepCents));
    }

    /**
     * Tells whether an amount the cashier typed is a valid cash amount
     * ({@code LC-07-03-05}): it must fall on the step, since the coins to make up
     * anything else no longer circulate.
     *
     * @param amount the amount typed, possibly null
     * @param stepCents the rounding step in cents, zero or less to not round
     * @return true when the amount may be tendered
     */
    public static boolean isTenderable(BigDecimal amount, int stepCents) {
        if (amount == null) {
            return false;
        }
        if (stepCents <= 1) {
            return true;
        }
        return amount.setScale(2, RoundingMode.HALF_UP)
                .remainder(BigDecimal.valueOf(stepCents, 2)).signum() == 0;
    }
}
