package com.intermarche.pos.domain.gs1;

import java.util.Locale;

/**
 * What the register does when a GS1 date says the article or the coupon is past its
 * time ({@code LC-11-03-13}).
 *
 * <p>THE SHOP DECIDES, NOT THE REGISTER. The same expired best-before is a reason to
 * stop the sale in one store and a reason to warn the cashier in another — the rule
 * depends on the article's nature, on what the shop does with its short-dated stock and
 * on who is standing at the till. So the level is administered, and the register only
 * applies it.
 *
 * <p>The default is the one that cannot break a lane: {@link #NONE}. A register that
 * suddenly started refusing articles because a supplier began encoding an expiry date
 * would be a checkout outage, and a shop that wants the refusal will ask for it.
 */
public enum Gs1AlertLevel {

    /** Say nothing: the date is decoded, journalled, and acted on by nobody. */
    NONE,

    /** Tell the cashier and register the line anyway. */
    INFO,

    /** Refuse: no line is created. */
    BLOCK;

    /**
     * Reads an administered level, tolerating case and padding.
     *
     * @param administered the level as the back office holds it, possibly null
     * @return the level, {@link #NONE} when nothing readable was administered
     */
    public static Gs1AlertLevel of(String administered) {
        if (administered == null || administered.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(administered.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unknown level is a mistyped parameter. Falling back to silence keeps
            // the lane running, where falling back to a refusal would stop it.
            return NONE;
        }
    }

    /**
     * Tells whether this level says anything at all to the cashier.
     *
     * @return true for an informative or a blocking level
     */
    public boolean speaks() {
        return this != NONE;
    }

    /**
     * Tells whether this level refuses the line.
     *
     * @return true for the blocking level only
     */
    public boolean blocks() {
        return this == BLOCK;
    }
}
