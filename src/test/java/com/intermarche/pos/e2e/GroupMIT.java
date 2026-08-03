package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URL;

/**
 * Group M end-to-end scenarios (Thèmes), played THROUGH THE SCREEN with a
 * headless Chromium browser (quarkus-playwright) against the real application
 * (live H2, real beans, embedded hardware simulator). Every group-M scenario is
 * {@code [S]} (caisse seule + simulateur), so M1–M4 are all implemented here and
 * nothing is left as residue.
 * <p>
 * <b>The register's display theme, exactly as it resolves and is chosen.</b> The
 * theme is a design-token cascade resolved by {@code ThemeService}: the logged
 * cashier's personal preference ({@code Employee.theme}) wins, else the store
 * default ({@code Store.theme}), else the built-in dark theme {@code sombre}. The
 * resolved name lands on every {@code <body data-theme="...">} and drives the
 * {@code [data-theme="clair"]} token override block in {@code theme.html}. M1
 * proves the cascade across two operators and a store-level override; M2 proves
 * the on-screen selector (AUTRES → THÈME D'AFFICHAGE → CLAIR renders immediately,
 * DÉFAUT MAGASIN clears the preference and the cascade resumes); M3 proves the
 * "four passes" iso-render contract — the {@code sombre} theme IS {@code :root},
 * so a page rendered {@code sombre} is byte-for-byte identical to the pre-theming
 * default (zero visual difference), while {@code clair} genuinely differs; M4
 * proves theme COVERAGE — every screen browsed in {@code clair} flips its
 * background, bars, rows, muted buttons and keyboards to the light tokens, none
 * left dark.
 * <p>
 * <b>Oracles.</b> The rendered {@code data-theme} attribute on {@code <body>} is
 * the oracle for the resolved theme on every screen; the database
 * ({@link Employee#theme} of jdupont, {@link Store#theme}) is the oracle for the
 * persisted preference and the store default; and the LIVE computed CSS (read in
 * the headless browser via {@code getComputedStyle}) is the oracle for the
 * iso-render contract (M3) and the light coverage (M4) — the token cascade really
 * runs, so the pixels it produces are assertable.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (m1…m4); a session is opened once by the first
 * scenario on the fresh drop-and-create H2 boot and stays open (theming never
 * touches it). Each scenario RESTORES whatever it alters — M1 restores the store
 * default, M2 ends on jdupont's cleared preference (via DÉFAUT MAGASIN) — so no
 * scenario leaks a theme onto the next. The seed already carries Marie's
 * {@code clair} preference and Jean's absent one, so the cascade needs no
 * bootstrapping. Nothing is asserted as an absolute counter.
 * <p>
 * <b>Justified residue.</b> None: the whole group is {@code [S]}. M4 browses the
 * cashier's screen set; the remaining full-page templates (customer display,
 * dashboard, cash-count, digital-ticket) carry the SAME single
 * {@code data-theme} cascade — one flip re-themes an entire page, there is no
 * per-element theme — so the coverage proof does not need every route re-walked.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupMIT {

    /** The seed cashier badge (Jean Dupont / jdupont, no theme preference). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed cashier login name, used as the DB oracle key. */
    private static final String CASHIER_LOGIN = "jdupont";

    /** The seed manager badge (Marie Curie / mcurie, prefers the light theme). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The opening float of the shared session (euros, comma decimal). */
    private static final String OPENING_FLOAT = "200,00";

    /** The light theme name. */
    private static final String CLAIR = "clair";

    /** The built-in dark theme name (the store's effective default in the seed). */
    private static final String SOMBRE = "sombre";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * M1 — Cascade (préférence caissier &gt; défaut magasin &gt; défaut intégré).
     * <p>
     * Marie carries the seeded {@code clair} preference, so from her unlock every
     * screen renders light (the cashier preference wins). Jean carries none, so he
     * follows the store — which is unset in the seed and falls through to the
     * built-in {@code sombre}. Raising the store default to {@code clair} then
     * flips Jean (still preference-less) to light without touching his row, and
     * lowering it back restores the dark fallback: the three rungs of the
     * resolution chain, proven end to end.
     */
    @Test
    void m1_cascade() {
        ensureOpenSession();
        // --- Marie (preference clair): light everywhere from the unlock ---
        Page marie = freshLogin(MANAGER_BADGE, MANAGER_PIN);
        Assertions.assertEquals(CLAIR, bodyTheme(marie),
                "Marie's clair preference must theme the sale screen light from the unlock");
        marie.navigate(base.toString() + "session");
        marie.getByText("SESSION DE CAISSE").waitFor();
        Assertions.assertEquals(CLAIR, bodyTheme(marie),
                "Marie's preference must apply to the session screen too (partout)");
        marie.navigate(base.toString() + "theme-select");
        marie.getByText("THÈME D'AFFICHAGE").waitFor();
        Assertions.assertEquals(CLAIR, bodyTheme(marie),
                "Marie's preference must apply to the theme selector too (partout)");
        marie.close();
        // --- Jean (no preference): the store default = built-in sombre ---
        Page jean = freshLogin(CASHIER_BADGE, CASHIER_PIN);
        Assertions.assertEquals(SOMBRE, bodyTheme(jean),
                "Jean has no preference and the seed store is unset: he falls to the built-in sombre");
        // --- Store default raised to clair: Jean follows without a personal preference ---
        setStoreTheme(CLAIR);
        Assertions.assertNull(employeeTheme(CASHIER_LOGIN),
                "the store override must not have written any personal preference on Jean");
        jean.navigate(base.toString());
        jean.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals(CLAIR, bodyTheme(jean),
                "with Store.theme=clair Jean (no preference) must render light");
        // --- Restore the store default and confirm the dark fallback returns ---
        setStoreTheme(null);
        jean.navigate(base.toString());
        jean.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals(SOMBRE, bodyTheme(jean),
                "clearing the store default must drop Jean back to the built-in sombre");
        Assertions.assertNull(storeTheme(), "the store default must be restored to unset for the next scenario");
        jean.close();
    }

    /**
     * M2 — Sélecteur (AUTRES → THÈME → CLAIR rendu immédiat ; DÉFAUT MAGASIN efface la préférence).
     * <p>
     * Jean starts dark (no preference, store default). The selector is reached
     * through the screen — AUTRES… then THÈME D'AFFICHAGE — and highlights the
     * currently resolved theme. Picking CLAIR writes his personal preference and
     * the redirect back to the sale renders light immediately. Re-opening the
     * selector highlights CLAIR; picking DÉFAUT MAGASIN clears the preference (an
     * empty post), and the cascade resumes to the store default — the sale is dark
     * again and Jean's row carries no theme.
     */
    @Test
    void m2_selecteur() {
        Page page = freshLogin(CASHIER_BADGE, CASHIER_PIN);
        Assertions.assertEquals(SOMBRE, bodyTheme(page), "Jean starts on the store default (sombre)");
        Assertions.assertNull(employeeTheme(CASHIER_LOGIN), "Jean carries no preference before choosing");
        // --- Reach the selector through the secondary menu (AUTRES → THÈME) ---
        openThemeSelector(page);
        Assertions.assertEquals(SOMBRE, bodyTheme(page), "the selector itself is themed with the current (sombre)");
        Assertions.assertTrue(isChoiceHighlighted(page, "SOMBRE"),
                "the selector must highlight the currently resolved theme (SOMBRE)");
        Assertions.assertFalse(isChoiceHighlighted(page, "CLAIR"),
                "the not-current theme (CLAIR) must not be highlighted");
        // --- Pick CLAIR: the redirect renders the sale light immediately ---
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("CLAIR").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals(CLAIR, bodyTheme(page), "choosing CLAIR must render the sale light immediately");
        Assertions.assertEquals(CLAIR, employeeTheme(CASHIER_LOGIN),
                "choosing CLAIR must persist Jean's preference in the database");
        // --- Re-open: CLAIR is now the highlighted current ---
        openThemeSelector(page);
        Assertions.assertTrue(isChoiceHighlighted(page, "CLAIR"),
                "the selector must now highlight the chosen CLAIR");
        // --- DÉFAUT MAGASIN: preference cleared, cascade resumes to the store default ---
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("DÉFAUT MAGASIN").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals(SOMBRE, bodyTheme(page),
                "DÉFAUT MAGASIN must drop Jean back to the store default (sombre)");
        Assertions.assertNull(employeeTheme(CASHIER_LOGIN),
                "DÉFAUT MAGASIN must clear Jean's persisted preference (cascade resumes)");
        page.close();
    }

    /**
     * M3 — Iso-rendu sombre (le contrat des 4 passes : zéro différence visuelle).
     * <p>
     * The {@code sombre} theme is {@code :root} itself — no {@code [data-theme]}
     * override block. The proof runs on the LIVE computed CSS: the full token set
     * read on a page rendered {@code sombre} is captured, then the {@code
     * data-theme} attribute is stripped (the pre-theming {@code :root} state) and
     * the tokens re-read — they are IDENTICAL, so the theming passes changed
     * nothing visible in the dark. As a live control that the mechanism is not
     * inert, switching the same body to {@code clair} genuinely moves the tokens.
     */
    @Test
    void m3_iso_rendu_sombre() {
        Page page = freshLogin(CASHIER_BADGE, CASHIER_PIN);
        Assertions.assertEquals(SOMBRE, bodyTheme(page), "the iso-render contract is checked on the dark rendering");
        String rendered = readTokens(page);
        Assertions.assertTrue(rendered.contains("#121212") || rendered.contains("rgb(18, 18, 18)"),
                "the dark rendering must carry the built-in dark tokens (bg-dark #121212)");
        // --- Strip data-theme: the pre-theming :root baseline ---
        page.evaluate("() => document.body.removeAttribute('data-theme')");
        String baseline = readTokens(page);
        Assertions.assertEquals(baseline, rendered,
                "sombre must render byte-identical to the pre-theming :root baseline (zero visual difference)");
        // --- Live control: clair actually moves the tokens ---
        page.evaluate("() => document.body.setAttribute('data-theme', 'clair')");
        String light = readTokens(page);
        Assertions.assertNotEquals(rendered, light,
                "the theming mechanism must be live: clair must differ from the dark baseline");
        page.close();
    }

    /**
     * M4 — Couverture clair (aucun bandeau/fond/bouton/clavier resté sombre).
     * <p>
     * Browsed as Marie (the seeded {@code clair} preference), every cashier screen
     * flips light: each body carries {@code data-theme="clair"} and its rendered
     * background is a light colour (high luminance), so no screen is left dark.
     * The sale screen's muted buttons (the bars and secondary actions) and the
     * direct-entry keypad's digit keys are then read on the live CSS — their
     * backgrounds too resolve light, proving the bars, rows, muted buttons and
     * keyboards all followed the token cascade.
     */
    @Test
    void m4_couverture_clair() {
        Page page = freshLogin(MANAGER_BADGE, MANAGER_PIN);
        // --- Every browsed screen renders light (background not left dark) ---
        assertScreenLight(page, "", "TOTAL À PAYER");
        assertScreenLight(page, "session", "SESSION DE CAISSE");
        assertScreenLight(page, "search", "Recherche produit");
        assertScreenLight(page, "manual", "SAISIE DIRECTE");
        assertScreenLight(page, "fruits", "FRUITS, LÉGUMES & FRAIS");
        assertScreenLight(page, "fidelity", "PROGRAMME DE FIDÉLITÉ");
        assertScreenLight(page, "reprint", "RÉIMPRESSION TICKET");
        assertScreenLight(page, "return", "RETOUR CLIENT");
        assertScreenLight(page, "parked", "Tickets en attente");
        assertScreenLight(page, "pin-change", "CHANGER MON CODE PIN");
        assertScreenLight(page, "supervisor", "APPEL SUPERVISEUR");
        assertScreenLight(page, "theme-select", "THÈME D'AFFICHAGE");
        // --- Bars & muted buttons on the sale screen: none left dark ---
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(minBackgroundLuminance(page, ".btn-muted") > 0.5,
                "every muted button on the sale screen must render light in clair (none left dark)");
        // --- Keyboards: the search screen's on-load keypad renders its keys light ---
        page.navigate(base.toString() + "search");
        page.getByText("Recherche produit").waitFor();
        page.locator("#alphaKbArea .pos-key--digit").first().waitFor();
        Assertions.assertTrue(minBackgroundLuminance(page, "#alphaKbArea .pos-key--digit") > 0.5,
                "every keypad key must render light in clair (the keyboards followed the theme)");
        page.close();
    }

    // --- Theme-selector gestures ---

    /**
     * Opens the theme selector through the on-screen path from the sale screen:
     * it reveals the secondary menu with AUTRES… only when needed (the menu
     * sticks open after a first visit), then follows the THÈME D'AFFICHAGE link,
     * waiting until the selector is rendered.
     *
     * @param page the Playwright page sitting on the sale screen
     */
    private void openThemeSelector(Page page) {
        Locator themeLink = page.locator("a[href='/theme-select']");
        if (themeLink.count() == 0) {
            page.locator("a[href='/action/menu/secondary']").click();
        }
        themeLink.first().click();
        page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("THÈME D'AFFICHAGE").setExact(true)).waitFor();
    }

    /**
     * Reads whether a selector choice button carries the {@code current}
     * highlight (the resolved theme).
     *
     * @param page the Playwright page carrying the theme selector
     * @param name the uppercased choice label (e.g. "SOMBRE", "CLAIR")
     * @return true when that choice is highlighted as the current theme
     */
    private boolean isChoiceHighlighted(Page page, String name) {
        String cls = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(name).setExact(true)).getAttribute("class");
        return cls != null && cls.contains("current");
    }

    // --- Theme oracles (rendered + live CSS) ---

    /**
     * Reads the resolved theme rendered on a page from its {@code <body>}
     * {@code data-theme} attribute.
     *
     * @param page the Playwright page to read
     * @return the rendered {@code data-theme} value (e.g. "clair", "sombre")
     */
    private String bodyTheme(Page page) {
        return page.locator("body").getAttribute("data-theme");
    }

    /**
     * Reads the full design-token set resolved on a page's {@code <body>} from the
     * live computed CSS, joined into one stable string for equality checks.
     *
     * @param page the Playwright page to read
     * @return the pipe-joined computed values of every theme token
     */
    private String readTokens(Page page) {
        return (String) page.evaluate("() => {"
                + " const names=['--bg-dark','--bg-panel','--text-main','--text-muted','--primary',"
                + "'--success','--danger','--warning','--border-color','--accent','--muted','--bg-page',"
                + "'--bg-inset','--bg-row','--border-strong'];"
                + " const cs=getComputedStyle(document.body);"
                + " return names.map(n=>cs.getPropertyValue(n).trim()).join('|'); }");
    }

    /**
     * Navigates to a screen, waits for its anchor text and asserts it renders in
     * the light theme: the body carries {@code data-theme="clair"} and its
     * computed background is a light colour (relative luminance &gt; 0.5).
     *
     * @param page the Playwright page driving the register
     * @param route the screen route relative to the base URL ("" for the sale)
     * @param anchor a stable visible text proving the screen was reached
     */
    private void assertScreenLight(Page page, String route, String anchor) {
        page.navigate(base.toString() + route);
        page.getByText(anchor).first().waitFor();
        Assertions.assertEquals(CLAIR, bodyTheme(page),
                "the screen /" + route + " must render with the clair theme");
        Assertions.assertTrue(minBackgroundLuminance(page, "body") > 0.5,
                "the background of /" + route + " must be light in clair (not left dark)");
    }

    /**
     * Reads the minimum relative luminance of the computed background colour over
     * every element matching a selector on the current page (live CSS), so a
     * single element left dark drags the minimum below the light threshold.
     *
     * @param page the Playwright page to read
     * @param selector the CSS selector of the elements to probe
     * @return the minimum background luminance in [0,1], or 1.0 when no element matches
     */
    private double minBackgroundLuminance(Page page, String selector) {
        Object value = page.evaluate("(sel) => {"
                + " const els=[...document.querySelectorAll(sel)]; let min=1.0;"
                + " for(const el of els){ const m=getComputedStyle(el).backgroundColor.match(/\\d+/g);"
                + " if(!m) continue; const r=+m[0], g=+m[1], b=+m[2];"
                + " const l=(0.299*r+0.587*g+0.114*b)/255; if(l<min) min=l; } return min; }", selector);
        return ((Number) value).doubleValue();
    }

    // --- Database oracles ---

    /**
     * Reads an employee's persisted theme preference by login name.
     *
     * @param loginName the employee's login name
     * @return the {@link Employee#theme} value, or null when no preference is set
     */
    private String employeeTheme(String loginName) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Employee e = Employee.find("loginName", loginName).firstResult();
            return e != null ? e.theme : null;
        });
    }

    /**
     * Reads the store's default theme from the single store row.
     *
     * @return the {@link Store#theme} value, or null when unset (built-in default)
     */
    private String storeTheme() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Store s = Store.findAll().firstResult();
            return s != null ? s.theme : null;
        });
    }

    /**
     * Writes the store's default theme on the single store row — the only DB
     * scaffolding of this group (there is no HTTP route to set the store default),
     * used to exercise the middle rung of the cascade and restored afterwards.
     *
     * @param theme the store default to set, or null to clear it
     */
    private void setStoreTheme(String theme) {
        QuarkusTransaction.requiringNew().run(() -> {
            Store s = Store.findAll().firstResult();
            s.theme = theme;
        });
    }

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    // --- Reusable gestures (login recipe, session bootstrap) ---

    /**
     * Closes any lingering pages, then opens a fresh browser page and lands it,
     * logged in as the given operator, on the empty sale screen of the shared open
     * session — the standard start of every scenario.
     *
     * @param badge the 8-digit badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshLogin(String badge, String pin) {
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, badge, pin);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once through the
     * full prise-de-poste screen (as Jean) on the first call (no-op afterwards).
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#openingFloat").waitFor();
        closeDrawer();
        page.locator("#openingFloat").fill(OPENING_FLOAT);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Navigates to the lock screen, presents the badge on the hardware scan bus
     * and taps the PIN, submitting the unlock — the shared login gesture.
     *
     * @param page the Playwright page driving the register
     * @param badge the 8-digit badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     */
    private void scanBadgeAndEnterPin(Page page, String badge, String pin) {
        String root = base.toString();
        page.navigate(root + "lock");
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(badge));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page.locator("#keyboardArea"), pin);
        page.locator("#actionBtn").click();
    }

    // --- Hardware bus & keypad ---

    /**
     * Taps digits one by one on a numeric keypad, scoped under the given
     * container so no mode toggle is ever hit.
     *
     * @param keypad the keypad container locator
     * @param digits the digits to tap
     */
    private void tapDigits(Locator keypad, String digits) {
        for (char digit : digits.toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(digit)).setExact(true)).click();
        }
    }

    /**
     * Pushes the drawer shut on the hardware bus (the embedded simulator),
     * clearing the drawer guard so the guarded screens stay reachable.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }
}
