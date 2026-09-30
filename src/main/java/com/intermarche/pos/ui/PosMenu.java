package com.intermarche.pos.ui;

import java.util.List;

/**
 * The five BUTTON MENUS of the sale screen, one per key of the bottom bar.
 *
 * <p>The register used to carry two menus and a toggle, with five direct actions
 * squatting the bottom bar. That bar is now the menu selector: five keys, five menus,
 * ten buttons each — fifty functions reachable in two taps, and never more than two.
 *
 * <p>TEN PER MENU IS THE WHOLE POINT: two columns of five, at the button size the
 * register has always used. Three columns fit more but shrink the target and force the
 * labels onto two lines, which on a till is how a cashier presses the wrong thing.
 * A menu is free to carry fewer than ten — an empty slot costs nothing, a cramped one
 * costs a mistake.
 *
 * <p>The order below is the tab order, and it follows the day: what one does DURING a
 * sale first, then the paper it produces, then what the customer brings, then the
 * register's own money, then the operator's own settings.
 */
public enum PosMenu {

    /** Article entry: scan, quantity, weighing, direct entry, search, subtotal, info. */
    ARTICLES("articles", "ARTICLES"),

    /** The post itself: lock, close, session, float calculator, training. */
    CAISSE("caisse", "CAISSE"),

    /** Settlement: pay, and the price/discount gestures that move the amount due. */
    REGLEMENT("reglement", "RÈGLEMENT"),

    /** The ticket's own life: parking, cancelling, printing, billing, return, recall. */
    TICKET("ticket", "TICKET"),

    /** Generosity: fidelity, loyalty balance. */
    GENEROSITE("generosite", "GÉNÉROSITÉ"),

    /** The drawer's money: movements, withdrawal, coin supply, transfer, deposit-return. */
    TIROIR("tiroir", "TIROIR"),

    /** Everything else: supervisor, degraded mode, badge, PIN, theme, recall. */
    AUTRE("autre", "AUTRE");

    /** The seven menus in tab order (the expert's nomenclature) — what the bottom bar renders. */
    public static final List<PosMenu> ALL = List.of(values());

    /** The key carried by the menu's URL. */
    private final String key;

    /** The operator-facing label of the menu key. */
    private final String label;

    /**
     * Builds a menu with its URL key and its label.
     *
     * @param key the key carried by the menu's URL
     * @param label the operator-facing label
     */
    PosMenu(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /**
     * Returns the key carried by the menu's URL.
     *
     * @return the URL key
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the operator-facing label of the menu key.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Parses a menu key coming from a URL.
     *
     * <p>Historical keys are still understood so a link kept in a bookmark, a
     * script or a test lands somewhere sensible instead of on a 404: {@code main}
     * and the old {@code vente} resolve to {@link #ARTICLES}, {@code secondary} to
     * {@link #TICKET}, the old {@code client} to {@link #GENEROSITE} and the old
     * {@code poste} to {@link #AUTRE}. {@code ticket} and {@code caisse} kept their
     * keys.
     *
     * <p>Anything else falls back to {@link #ARTICLES}: an unreadable key must
     * leave the cashier on the menu they use all day, never on a blank grid.
     *
     * @param key the raw key, possibly null
     * @return the menu, never null
     */
    public static PosMenu of(String key) {
        if (key == null) {
            return ARTICLES;
        }
        String value = key.trim().toLowerCase();
        if (value.equals("main") || value.equals("vente")) {
            return ARTICLES;
        }
        if (value.equals("secondary")) {
            return TICKET;
        }
        if (value.equals("client")) {
            return GENEROSITE;
        }
        if (value.equals("poste")) {
            return AUTRE;
        }
        for (PosMenu menu : ALL) {
            if (menu.key.equals(value)) {
                return menu;
            }
        }
        return ARTICLES;
    }
}
