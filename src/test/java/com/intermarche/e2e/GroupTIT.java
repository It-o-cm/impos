package com.intermarche.e2e;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
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
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URL;

/**
 * Group T end-to-end scenarios (Langues, LC-13-06), played THROUGH THE SCREEN
 * with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). The whole
 * group is {@code [S]} (caisse seule + simulateur).
 * <p>
 * <b>The interface language, exactly as it resolves and is chosen.</b> The
 * rendered language is a cascade resolved once per request by {@code Messages}:
 * the operator's on-screen choice ({@code state.language}) wins, else the
 * signed-in operator's administered language ({@code Employee.language}), else
 * the store's {@code language.system}, else French; a tag no bundle ships is
 * skipped at every rung. The selection screen ({@code /language-select},
 * {@code POST /action/language}) is reachable off-session and its choice lives
 * in the register state, dying at sign-out — it is never written on the employee
 * row.
 * <p>
 * <b>Oracle.</b> The sale screen hardcodes {@code <html lang="fr">}, so the
 * resolution oracle is the language-select screen (and the lock screen), which
 * BOTH render {@code <html lang="{i18n.lang}">} and an i18n title: the
 * {@code <html lang>} attribute is the resolved tag and the title is its
 * localized label ("Langue de l'interface" / "Taal van de interface" / "Idioma
 * da interface"). The database ({@link Employee#language}) and the injected
 * {@link PosState} are the server oracles for the operator rung and the chosen
 * rung.
 * <p>
 * <b>Ordering &amp; isolation.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (t1…t8); a session is opened once on the
 * fresh drop-and-create H2 boot and stays open (language never touches it).
 * {@link #resetLanguageState()} clears the chosen language, Jean's administered
 * language and the three language parameters after every scenario, so none
 * leaks onto the next.
 * <p>
 * <b>Justified residue.</b> T5 (double affichage client bilingue,
 * LC-13-06-06/-11) is NOT written: {@code Messages.both(...)} and
 * {@code getSecondLang()} are wired to NO template or printer in {@code
 * src/main} (no caller), so the second language is not observable through any
 * screen or document — it cannot be asserted e2e without inventing a surface.
 * Reported as a code/catalogue gap, not faked here.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupTIT {

    /** The seed cashier badge (Jean Dupont / jdupont, no administered language). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed cashier login name, used as the DB oracle key. */
    private static final String CASHIER_LOGIN = "jdupont";

    /** The opening float of the shared session (euros, comma decimal). */
    private static final String OPENING_FLOAT = "200,00";

    /** The French language-select title (the default-language oracle). */
    private static final String TITLE_FR = "Langue de l'interface";

    /** The Dutch language-select title. */
    private static final String TITLE_NL = "Taal van de interface";

    /** The Portuguese language-select title. */
    private static final String TITLE_PT = "Idioma da interface";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The administered settings, to toggle the three language parameters. */
    @Inject
    PosSettingsService posSettings;

    /** The register state, to read and clear the chosen language. */
    @Inject
    PosState posState;

    /**
     * The label resolver: told when this scenario writes, as the back office,
     * the administered language on an employee row (see
     * {@link #setEmployeeLanguage(String, String)}).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /**
     * Clears every language state a scenario may have set, so each starts from
     * the seed defaults: the chosen language (register state), Jean's
     * administered language (database) and the three language parameters
     * (operator-change on, the three bundled languages offered, system French).
     */
    @AfterEach
    void resetLanguageState() {
        posState.language = null;
        setEmployeeLanguage(CASHIER_LOGIN, null);
        posSettings.store("language.operator-change", "true");
        posSettings.store("language.available", "fr;nl;pt");
        posSettings.store("language.system", "fr");
    }

    /**
     * T1 — Choix de langue par l'opérateur (LC-13-06-03).
     * <p>
     * The language-select screen is reached, shows the current language
     * highlighted, and picking NÉERLANDAIS flips the resolved language to Dutch
     * (the title and {@code <html lang>} follow) without writing anything on
     * Jean's employee row — the choice lives in the register state.
     */
    @Test
    void t1_choix_operateur() {
        Page page = freshLogin();
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(page), "before any choice the default language is French");
        Assertions.assertTrue(isCurrent(page, "Français"), "the current language must be highlighted");
        // Pick Dutch through the screen: the hidden-field form posts the tag.
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Nederlands").setExact(true)).click();
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_NL).waitFor();
        Assertions.assertEquals("nl", langOf(page), "choosing Nederlands must render the interface in Dutch");
        Assertions.assertNull(employeeLanguage(CASHIER_LOGIN),
                "the on-screen choice must NOT be written on the employee row");
        page.close();
    }

    /**
     * T2 — Chaîne de résolution (LC-13-06-01/-02/-05).
     * <p>
     * With no chosen language: the signed-in operator's administered language
     * wins over the store system language, and the store system language applies
     * when the operator has none; an operator language outranks a store system
     * language set to something else.
     */
    @Test
    void t2_chaine_resolution() {
        Page page = freshLogin();
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(page), "no operator language, system French: French");
        // Operator language administered to Dutch wins over the French system.
        setEmployeeLanguage(CASHIER_LOGIN, "nl");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_NL).waitFor();
        Assertions.assertEquals("nl", langOf(page), "the operator's administered language wins over the system");
        // The system raised to Portuguese: the operator language still wins.
        posSettings.store("language.system", "pt");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_NL).waitFor();
        Assertions.assertEquals("nl", langOf(page), "operator language outranks the store system language");
        // The operator language cleared: the store system language (Portuguese) applies.
        setEmployeeLanguage(CASHIER_LOGIN, null);
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_PT).waitFor();
        Assertions.assertEquals("pt", langOf(page), "with no operator language the store system language applies");
        page.close();
    }

    /**
     * T3 — Changement interdit pendant la session (LC-13-06-04).
     * <p>
     * With {@code language.operator-change} off and a session open, the selection
     * screen states the refusal and a forged choice is NOT taken: the resolved
     * language stays French.
     */
    @Test
    void t3_changement_interdit_en_session() {
        Page page = freshLogin();
        posSettings.store("language.operator-change", "false");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertTrue(
                page.getByText("Le changement de langue n'est pas autorisé pendant la session.").count() > 0,
                "a session-open register with operator-change off must state the refusal");
        // Forge the choice past the disabled button: the server must still refuse it.
        APIResponse post = context.request().post(base.toString() + "action/language",
                RequestOptions.create().setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData("language=nl"));
        Assertions.assertTrue(post.ok(), "the refused choice still answers (a redirect back)");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(page), "the refused mid-session choice must not change the language");
        page.close();
    }

    /**
     * T4 — Liste restreinte aux langues offertes (LC-13-06-04).
     * <p>
     * {@code language.available} narrows the offered buttons to the bundled
     * languages it lists: with {@code fr;nl} only French and Dutch are offered,
     * Portuguese is gone.
     */
    @Test
    void t4_liste_restreinte() {
        Page page = freshLogin();
        posSettings.store("language.available", "fr;nl");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertTrue(languageButton(page, "Français").count() > 0, "French must stay offered");
        Assertions.assertTrue(languageButton(page, "Nederlands").count() > 0, "Dutch must stay offered");
        Assertions.assertEquals(0, languageButton(page, "Português").count(),
                "Portuguese must not be offered when it is not in language.available");
        page.close();
    }

    /**
     * T6 — Déconnexion remet la langue à zéro (LC-13-06).
     * <p>
     * A chosen Dutch language is dropped by a sign-out ({@code /lock}): the next
     * session resolves back to the default French, the register state having been
     * cleared.
     */
    @Test
    void t6_logout_remet_la_langue() {
        Page page = freshLogin();
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Nederlands").setExact(true)).click();
        // The choice redirects to the sale; re-open the selector to confirm it took.
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_NL).waitFor();
        Assertions.assertEquals("nl", langOf(page), "the chosen Dutch must apply before the logout");
        // Sign out: /lock logs the operator out and clears the chosen language.
        page.navigate(base.toString() + "lock");
        page.getByText("CAISSE FERMÉE").waitFor();
        Assertions.assertNull(posState.language, "the logout must clear the chosen language in the register state");
        // A fresh session resolves back to the default French.
        Page next = freshLogin();
        next.navigate(base.toString() + "language-select");
        next.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(next), "after the logout the next session is back to French");
        next.close();
        page.close();
    }

    /**
     * T7 — Langue inconnue ou bundle absent → français (LC-13-06-05/-10).
     * <p>
     * A store system language set to an unbundled tag ({@code de}) falls back to
     * French rather than rendering raw keys: the title is the French label, not
     * {@code language.title}.
     */
    @Test
    void t7_langue_inconnue_repli_francais() {
        Page page = freshLogin();
        posSettings.store("language.system", "de");
        page.navigate(base.toString() + "language-select");
        page.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(page), "an unbundled system language must fall back to French");
        Assertions.assertEquals(0, page.getByText("language.title").count(),
                "the fallback must render the French label, never a raw message key");
        page.close();
    }

    /**
     * T8 — Routes langue et gardes session/verrou (LC-13-06-03).
     * <p>
     * The selection screen is out of the SESSION gate (reachable with the shared
     * session open, as every other scenario shows) AND out of the LOCK gate:
     * {@code /language-select} is one of {@code LockCheckFilter}'s
     * {@code ALLOWED_PREFIXES}, so it renders on a locked register instead of
     * being diverted. That is the point of the requirement rather than a hole in
     * the gate: an operator who cannot read the till's language has to choose
     * one BEFORE taking the post (LC-13-06-03), and the lock screen's own
     * language key — which would otherwise lead nowhere — is what they press.
     */
    @Test
    void t8_routes_session_et_verrou() {
        // Session open: the screen is reachable (not session-gated).
        Page inSession = freshLogin();
        inSession.navigate(base.toString() + "language-select");
        inSession.getByText(TITLE_FR).waitFor();
        Assertions.assertEquals("fr", langOf(inSession),
                "the language screen is reachable during an open session (not session-gated)");
        // Locked: the language screen is allowed through the lock gate and the
        // lock screen's language key leads to it.
        inSession.navigate(base.toString() + "lock");
        inSession.getByText("CAISSE FERMÉE").waitFor();
        inSession.navigate(base.toString() + "language-select");
        inSession.getByText(TITLE_FR).waitFor();
        Assertions.assertTrue(inSession.url().endsWith("/language-select"),
                "a locked register must still render /language-select (LockCheckFilter ALLOWED_PREFIXES)");
        Assertions.assertEquals("fr", langOf(inSession),
                "the screen a locked register offers renders in the resolved language");
        inSession.close();
    }

    // --- language oracles ---

    /**
     * Reads the resolved language rendered on a page from its {@code <html>}
     * {@code lang} attribute (set to {@code i18n.lang} on the lock and
     * language-select screens).
     *
     * @param page the Playwright page to read
     * @return the rendered language tag (e.g. "fr", "nl", "pt")
     */
    private String langOf(Page page) {
        return page.locator("html").getAttribute("lang");
    }

    /**
     * Locates a language choice button by its rendered endonym label.
     *
     * @param page the Playwright page carrying the selection screen
     * @param name the exact button label (e.g. "Français", "Nederlands")
     * @return the button locator (possibly matching zero elements)
     */
    private Locator languageButton(Page page, String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    /**
     * Tells whether a language choice button carries the {@code current}
     * highlight (the resolved language).
     *
     * @param page the Playwright page carrying the selection screen
     * @param name the exact button label
     * @return true when that choice is highlighted as current
     */
    private boolean isCurrent(Page page, String name) {
        String cls = languageButton(page, name).getAttribute("class");
        return cls != null && cls.contains("current");
    }

    // --- database oracles / scaffolding ---

    /**
     * Reads an employee's administered interface language by login name.
     *
     * @param loginName the employee's login name
     * @return the {@link Employee#language} value, or null when none is set
     */
    private String employeeLanguage(String loginName) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Employee e = Employee.find("loginName", loginName).firstResult();
            return e != null ? e.language : null;
        });
    }

    /**
     * Writes an employee's administered interface language — the DB scaffolding
     * for the operator rung of the resolution chain (there is no till route to
     * set it, it being referential data the back office owns), restored by
     * {@link #resetLanguageState()}.
     *
     * @param loginName the employee's login name
     * @param language the language tag to set, or null to clear it
     */
    private void setEmployeeLanguage(String loginName, String language) {
        QuarkusTransaction.requiringNew().run(() -> {
            Employee e = Employee.find("loginName", loginName).firstResult();
            if (e != null) {
                e.language = language;
            }
        });
        // AND THE REGISTER IS TOLD. Writing the row is the back office's
        // gesture, not the till's: the resolver memorises what it resolved from
        // (the chosen language, who is signed in, the system language) rather
        // than reading the employee row once per label, and that row is not one
        // of those inputs. Whoever rewrites it says so — here, as the back
        // office will.
        i18n.forgetResolvedLanguage();
    }

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    // --- login recipe (shared with the other groups) ---

    /**
     * Closes any lingering pages, ensures the shared session is open, then opens
     * a fresh page logged in as Jean on the empty sale screen.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshLogin() {
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once through the
     * prise-de-poste screen on the first call (no-op afterwards).
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#floatDisplay").waitFor();
        closeDrawer();
        enterOpeningFloat(page, OPENING_FLOAT);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Navigates to the lock screen, presents the badge on the hardware scan bus
     * and taps the PIN, submitting the unlock and clicking through the hardware
     * gate.
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
        clickThroughHardwareGate(page);
    }

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
     * Pushes the drawer shut on the hardware bus so the drawer guard keeps the
     * guarded screens reachable.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Clicks through the hardware status gate that every successful unlock lands
     * on (Continuer takes the override that opens the lane).
     *
     * @param page the Playwright page driving the register
     */
    private void clickThroughHardwareGate(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Continuer").setExact(true)).click();
    }

    /**
     * Enters the opening float on the session screen's touch keypad after a
     * CLEAR of the prefilled default.
     *
     * @param page the Playwright page driving the register
     * @param amount the float to key in, comma as the decimal separator
     */
    private void enterOpeningFloat(Page page, String amount) {
        Locator keypad = page.locator("#floatKbArea");
        keypad.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        for (char key : amount.toCharArray()) {
            if (key < '0' || key > '9') {
                continue;
            }
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
    }
}
