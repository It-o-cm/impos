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
    ARTICLES("articles", "menu.articles"),

    /** The post itself: lock, close, session, float calculator, training. */
    CAISSE("caisse", "menu.caisse"),

    /**
     * Settlement: the key goes STRAIGHT to the payment screen.
     *
     * <p>It carries no button list of its own. Paying is the one gesture of the
     * menu a cashier reaches twenty times an hour, and making it cost a tap on a
     * menu and a tap on a button was a tax on the commonest act of the day. The
     * two price gestures that used to sit beside it moved to {@link #GENEROSITE}.
     */
    REGLEMENT("reglement", "menu.reglement", "/pay"),

    /** The ticket's own life: parking, cancelling, printing, billing, return, recall. */
    TICKET("ticket", "menu.ticket"),

    /** Generosity: fidelity, loyalty balance. */
    GENEROSITE("generosite", "menu.generosite"),

    /** The drawer's money: movements, withdrawal, coin supply, transfer, deposit-return. */
    TIROIR("tiroir", "menu.tiroir"),

    /** Everything else: supervisor, degraded mode, badge, PIN, theme, recall. */
    AUTRE("autre", "menu.autre");

    /** The seven menus in tab order (the expert's nomenclature) — what the bottom bar renders. */
    public static final List<PosMenu> ALL = List.of(values());

    /** The key carried by the menu's URL. */
    private final String key;

    /** Bundle key of the operator-facing label of the menu key (LC-13-06). */
    private final String labelKey;

    /**
     * The screen this key opens straight away, or null for a menu of buttons.
     * <p>
     * A key with a destination is NOT a menu: the bottom bar links it to that
     * screen, and no button list is ever rendered for it.
     */
    private final String destination;

    /**
     * Builds a menu of buttons, with no destination of its own.
     *
     * @param key the key carried by the menu's URL
     * @param labelKey the bundle key of the operator-facing label
     */
    PosMenu(String key, String labelKey) {
        this(key, labelKey, null);
    }

    /**
     * Builds a menu key, with the screen it opens straight away when it has one.
     *
     * @param key the key carried by the menu's URL
     * @param labelKey the bundle key of the operator-facing label
     * @param destination the screen the key opens, or null for a menu of buttons
     */
    PosMenu(String key, String labelKey, String destination) {
        this.key = key;
        this.labelKey = labelKey;
        this.destination = destination;
    }

    /**
     * Returns the screen this key opens straight away.
     *
     * @return the path, or null when the key shows a menu of buttons
     */
    public String getDestination() {
        return destination;
    }

    /**
     * Tells whether this key opens a screen instead of showing buttons.
     *
     * @return true when the key carries a destination
     */
    public boolean isDirect() {
        return destination != null;
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
     * Returns the bundle key of the operator-facing label of the menu key.
     *
     * <p>A KEY AND NOT THE SENTENCE: an enum constant is built once, at class
     * loading, long before a request names an operator's language, so the
     * bottom bar resolves what it renders (LC-13-06).
     *
     * @return the bundle key of the label
     */
    public String getLabelKey() {
        return labelKey;
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
