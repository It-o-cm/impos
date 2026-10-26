package com.intermarche.pos.ui.recall;

/**
 * The EXTERNAL CHANNELS a basket integrated at the till comes from
 * (BO-10-01): each one carries its own administered rules — activation, price
 * source, forbidden articles, unknown articles, promotion channel, retrieval
 * mode — read by {@link ChannelRules} and applied by {@link ChannelGate}.
 *
 * <p>A channel is not a new document: it is the ORIGIN of a document the
 * register already recalls by its code. The kinds of {@link RecalledDocument}
 * map onto it — a quote and an order both come from the commercial
 * management tool — and a kind with no channel (the click-and-collect basket)
 * keeps the behaviour it always had, untouched by any rule of this lot.
 */
public enum SalesChannel {

    /** The e-commerce platform (BO-10-01-01 to -14). */
    ECOMMERCE,

    /** The in-store self-scanning devices (BO-10-01-15 to -23). */
    SELF_SCAN,

    /** The commercial management tool: quotes and orders (BO-10-01-24 to -29). */
    COMMERCIAL,

    /** The queue-busting handhelds (BO-10-01-30 to -33). */
    QBUSTING,

    /** The mobility devices: mobile checkout (BO-10-01-34 to -39). */
    MOBILITY,

    /** The sale vouchers written on the shop floor (BO-10-01-40, -41). */
    SALES_SLIP;

    /**
     * The channel a recalled document comes from.
     *
     * @param kind the kind of the document, possibly null
     * @return the channel, or null for a kind no channel rule applies to
     */
    public static SalesChannel of(RecalledDocument.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case DEVIS, COMMANDE -> COMMERCIAL;
            case BON_VENTE -> SALES_SLIP;
            case SELF_SCAN -> SELF_SCAN;
            case QBUSTING -> QBUSTING;
            case ECOMMERCE -> ECOMMERCE;
            case MOBILITY -> MOBILITY;
            case CLICK_COLLECT -> null;
        };
    }
}
