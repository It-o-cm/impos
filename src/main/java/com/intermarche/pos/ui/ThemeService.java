package com.intermarche.pos.ui;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;
import io.quarkus.qute.TemplateGlobal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Resolves the display theme of the register's screens (phase: multi-theme).
 * <p>
 * RESOLUTION CHAIN, most specific wins: the logged cashier's personal
 * preference ({@code Employee.theme}, referential data travelling in the
 * phase 6 pull) → the store's default ({@code Store.theme}, node-local) →
 * the built-in dark theme ({@code sombre}). The resolved name lands on
 * every {@code <body data-theme="...">} and the token overrides live in
 * {@code theme.html} — adding a theme is a token block there plus a value
 * in a store or employee row, no template changes.
 * <p>
 * Exposed to EVERY template as the Qute global {@code {posTheme}} (see
 * {@link Globals}), so no resource has to plumb it into its template data.
 * Package placement: consumed exclusively by the screens — ui root, like
 * the other cross-screen concerns.
 */
@ApplicationScoped
@Unremovable // Nothing injects this bean (the Qute global reaches it through
             // Arc programmatically), so without this annotation ArC's
             // unused-bean elimination removes it at build time and the
             // first themed rendering dies on an empty instance handle.
public class ThemeService {

    /** The built-in default theme name (the historical dark look). */
    public static final String DEFAULT_THEME = "sombre";

    /**
     * The selectable theme names, in display order — every entry must have
     * its token block in theme.html (the default needs none: it is :root).
     */
    public static final java.util.List<String> AVAILABLE_THEMES = java.util.List.of("sombre", "clair");

    @Inject
    PosState state;

    /**
     * Resolves the current theme name for this register's screens.
     *
     * @return the data-theme value to render (never null)
     */
    public String currentTheme() {
        if (state.auth != null && state.auth.operatorId != null) {
            Employee operator = Employee.findById(state.auth.operatorId);
            if (operator != null && operator.theme != null && !operator.theme.isBlank()) {
                return operator.theme;
            }
        }
        Store store = Store.findAll().firstResult();
        if (store != null && store.theme != null && !store.theme.isBlank()) {
            return store.theme;
        }
        return DEFAULT_THEME;
    }

    /**
     * Persists the logged cashier's theme preference. A blank or unknown
     * value clears the preference (back to the store's default) — the
     * selector's "store default" choice posts an empty value on purpose.
     *
     * @param themeName the chosen theme, or blank to follow the store
     */
    @jakarta.transaction.Transactional
    public void setThemeForOperator(String themeName) {
        if (state.auth == null || state.auth.operatorId == null) {
            return;
        }
        Employee operator = Employee.findById(state.auth.operatorId);
        if (operator == null) {
            return;
        }
        operator.theme = (themeName != null && AVAILABLE_THEMES.contains(themeName)) ? themeName : null;
        state.touch();
    }

    /**
     * Qute globals of the theming system.
     */
    @TemplateGlobal
    public static class Globals {

        /**
         * The resolved theme name, available in every template as
         * {@code {posTheme}} without any per-resource plumbing.
         *
         * @return the data-theme value to render
         */
        public static String posTheme() {
            // Theming must never break a rendering: any resolution trouble
            // falls back to the default theme.
            try {
                InstanceHandle<ThemeService> handle = Arc.container().instance(ThemeService.class);
                return handle.isAvailable() ? handle.get().currentTheme() : DEFAULT_THEME;
            } catch (Exception e) {
                return DEFAULT_THEME;
            }
        }
    }
}
