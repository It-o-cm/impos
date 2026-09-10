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

    /** The gestures of a sale in progress, and the payment itself. */
    VENTE("vente", "VENTE"),

    /** The ticket's own life: parking, cancelling, printing, billing. */
    TICKET("ticket", "TICKET"),

    /** What the customer brings to the till: card, voucher, deposit, return. */
    CLIENT("client", "CLIENT"),

    /** The register's money and its session. */
    CAISSE("caisse", "CAISSE"),

    /** The operator's own post: badge, code, display, session end. */
    POSTE("poste", "POSTE");

    /** The five menus in tab order — what the bottom bar renders. */
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
     * <p>The two historical keys are still understood: {@code main} was the sale
     * gestures and {@code secondary} everything else, so they resolve to the menus
     * that inherited those contents. A link kept in a bookmark, a script or a test
     * therefore still lands somewhere sensible instead of on a 404.
     *
     * <p>Anything else falls back to {@link #VENTE}: an unreadable key must leave the
     * cashier on the menu they use all day, never on a blank grid.
     *
     * @param key the raw key, possibly null
     * @return the menu, never null
     */
    public static PosMenu of(String key) {
        if (key == null) {
            return VENTE;
        }
        String value = key.trim().toLowerCase();
        if (value.equals("main")) {
            return VENTE;
        }
        if (value.equals("secondary")) {
            return TICKET;
        }
        for (PosMenu menu : ALL) {
            if (menu.key.equals(value)) {
                return menu;
            }
        }
        return VENTE;
    }
}
