package com.intermarche.pos.domain.setting;

import com.intermarche.pos.domain.people.Menu;

/**
 * A back-office FUNCTIONALITY, unitarily addressable to a profile
 * (BO-01-04-01).
 * <p>
 * Each feature is the smallest thing a grant can point at: a profile holds a
 * set of ({@code feature}, {@link CrudOption option}) pairs, so a right can
 * be given on one screen without giving it on the next. Features declare the
 * {@link Menu} they belong to, which is the aggregate the profile grid groups
 * them under (BO-01-04-06) — the menu carries no right of its own.
 * <p>
 * The catalog is intentionally an enum, not a table: a functionality is a
 * seam in the CODE (a resource, a gesture), so it appears when the code that
 * serves it appears, and a profile can never grant a right on a screen that
 * does not exist. The {@code key} is the stable token stored in a grant and
 * posted by the form; renaming an enum constant never rewrites stored grants
 * as long as its key is kept.
 */
public enum Feature {

    /** The register PARAMETERS screen ({@code /admin/settings}). */
    SETTINGS("settings", Menu.PARAMETRAGE, "Paramètres caisse"),

    /** The point-of-sale INFORMATION screen ({@code /admin/store}). */
    STORE("store", Menu.PARAMETRAGE, "Point de vente"),

    /** The PROFILES administration screen ({@code /admin/profiles}). */
    PROFILE("profile", Menu.ADMINISTRATION, "Profils"),

    /** The USER-to-profile assignment screen ({@code /admin/users}). */
    USER("user", Menu.ADMINISTRATION, "Utilisateurs"),

    /** The SUPERVISION dashboard ({@code /dashboard}). */
    SUPERVISION("supervision", Menu.SUPERVISION, "Supervision de la ligne");

    /** The stable storage/form token of this feature. */
    private final String key;

    /** The aggregate this feature is grouped under. */
    private final Menu menu;

    /** The operator-facing label (French). */
    private final String label;

    /**
     * Builds a feature with its stable key, its menu and its label.
     *
     * @param key the stable storage/form token
     * @param menu the aggregate this feature belongs to
     * @param label the French label shown in the profile grid
     */
    Feature(String key, Menu menu, String label) {
        this.key = key;
        this.menu = menu;
        this.label = label;
    }

    /**
     * Returns the stable storage/form token of this feature.
     *
     * @return the key stored in a grant
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the aggregate this feature is grouped under.
     *
     * @return the menu
     */
    public Menu getMenu() {
        return menu;
    }

    /**
     * Returns the operator-facing label of this feature.
     *
     * @return the French label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Indicates whether this feature belongs to a given menu.
     *
     * @param candidate the menu to test
     * @return true when this feature declares the given menu
     */
    public boolean inMenu(Menu candidate) {
        return menu == candidate;
    }

    /**
     * Resolves a feature from its stable key.
     *
     * @param key the stored/posted key, possibly null or unknown
     * @return the matching feature, or null when the key is null or unknown
     */
    public static Feature fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (Feature feature : values()) {
            if (feature.key.equals(key)) {
                return feature;
            }
        }
        return null;
    }
}
