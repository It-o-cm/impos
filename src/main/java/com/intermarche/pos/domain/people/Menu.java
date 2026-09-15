package com.intermarche.pos.domain.people;

/**
 * A back-office MENU: the coherent aggregate under which several
 * {@link Feature functionalities} are grouped (BO-01-04-06).
 * <p>
 * The aggregate is what lets a profile be administered "in whole or function
 * by function": granting an option on every {@link Feature} of a menu is the
 * same gesture repeated over {@link Feature#inMenu(Menu)}, while a single
 * feature stays independently addressable. The menu is therefore a pure
 * grouping — it carries no right of its own, only a label and the features
 * that declare themselves part of it.
 */
public enum Menu {

    /** The parameters aggregate: register settings and point-of-sale data. */
    PARAMETRAGE("Paramétrage"),

    /** The administration aggregate: users and profiles. */
    ADMINISTRATION("Administration"),

    /** The supervision aggregate: the line's real-time monitoring. */
    SUPERVISION("Supervision");

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a menu with its operator-facing label.
     *
     * @param label the French label shown in the administration screens
     */
    Menu(String label) {
        this.label = label;
    }

    /**
     * Returns the operator-facing label of this menu.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }
}
