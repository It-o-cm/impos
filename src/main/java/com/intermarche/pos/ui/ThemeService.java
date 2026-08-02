package com.intermarche.pos.ui;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import io.quarkus.arc.Arc;
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
public class ThemeService {

    /** The built-in default theme name (the historical dark look). */
    public static final String DEFAULT_THEME = "sombre";

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
            return Arc.container().instance(ThemeService.class).get().currentTheme();
        }
    }
}
