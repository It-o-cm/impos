package com.intermarche.e2e;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.domain.sync.EngineFeed;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Group R end-to-end scenarios (Consommation &amp; budgets moteur) — the {@code [S]}
 * subset R3, R5, R7 that is playable on the caisse + simulateur WITHOUT the demo
 * stack. R1/R2/R4/R6 are {@code [V]} (they need a real valorisateur emitting a
 * {@code discountAmount}-bearing advantage — the embedded mock never does) and
 * are reported as residue.
 * <p>
 * This class runs under a DEAD-ENGINE profile ({@code pos.valuation.url} present
 * but pointing at a dead port): a sale is valued LOCALLY (DEGRADED), so no engine
 * advantage is applied and NOTHING is declared (R3); the engine-feed import
 * status is exercised by seeding {@link EngineFeed} rows and reading
 * {@code /feeds/import/status} (R7). R5 (delivery disabled, url absent) needs a
 * different profile and lives in {@code GroupR5IT}.
 */
@QuarkusTest
@TestProfile(GroupRIT.DeadEngineProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupRIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The meal-eligible EAN — the only code the engine would value (if reachable). */
    private static final String EAN_POMMES = "3300000000001";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton — oracle for the valuation status. */
    @Inject
    PosState posState;

    /**
     * Dead-engine profile: the valuation URL is present (so the client is enabled
     * and tries) but points at a dead port, forcing the DEGRADED / local path.
     */
    public static class DeadEngineProfile extends E2eTestProfile {
        /**
         * Overrides the shared config, repointing the valuation engine at a dead
         * port so every revaluation fails into the local/degraded path.
         *
         * @return the config overrides with a dead valuation URL
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("pos.valuation.url", "http://localhost:59999");
            return overrides;
        }
    }

    /**
     * R3 — Mode dégradé LOCAL.
     * <p>
     * With the engine unreachable, a sale carrying a would-be-eligible line is
     * valued LOCALLY (status DEGRADED): no engine advantage is retained, so the
     * finalized ticket derives NO consumption declaration ({@link
     * ConsumptionOutbox} stays empty — nothing to declare).
     */
    @Test
    void r3_mode_degrade_local_rien_a_declarer() {
        long before = consumptionCount();
        Page page = freshSale();
        scan(EAN_POMMES);
        goPay(page);
        // The dead engine forces the local/degraded valuation (never ENGINE).
        Assertions.assertNotEquals("ENGINE", posState.payment.valuationStatus,
                "an unreachable engine must value the sale locally, was: " + posState.payment.valuationStatus);
        payThroughScreen(page, "ESPÈCES", "cashForm", "300", false);
        waitForSettlement();
        finishSale(page);
        Assertions.assertEquals(before, consumptionCount(),
                "a locally-valued sale must derive no consumption declaration (nothing to declare)");
        page.close();
    }

    /**
     * R7 — Statut des flux moteur.
     * <p>
     * {@code /feeds/import/status} reflects, per feed, {@code applied ⇔ version ==
     * appliedVersion}: a feed whose applied version matches its received version
     * reads {@code applied:true}; a feed with a newer received version (or an
     * import error) reads {@code applied:false}. Seeded directly via Panache since
     * an autonomous register receives no feed pull.
     */
    @Test
    void r7_statut_des_flux_moteur() {
        // A feed fully applied and a feed pending a newer version.
        seedFeed("PRODUCTS", "v1", "v1", null);
        seedFeed("OFFERS", "v2", "v1", "échec d'intégration");
        String status = context.request().get(base.toString() + "feeds/import/status").text();
        Assertions.assertTrue(status.contains("PRODUCTS") && status.contains("\"applied\":true"),
                "an up-to-date feed must read applied:true, status was:\n" + status);
        Assertions.assertTrue(status.contains("OFFERS") && status.contains("\"applied\":false"),
                "a feed pending a newer version must read applied:false, status was:\n" + status);
        Assertions.assertTrue(status.contains("échec d'intégration"),
                "the last import error must surface in the status, status was:\n" + status);
    }

    // --- Reusable gestures ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session.
     *
     * @return a Playwright page on the empty sale screen
     */
    private Page freshSale() {
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        loginToSaleWithOpenSession(page);
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once through the
     * full prise-de-poste screen.
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#floatDisplay").waitFor();
        closeDrawer();
        enterOpeningFloat(page, "200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Navigates to the lock screen, presents the badge and taps the PIN.
     *
     * @param page the Playwright page driving the register
     * @param badge the badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     */
    private void scanBadgeAndEnterPin(Page page, String badge, String pin) {
        String root = base.toString();
        closeDrawer();
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
     * Logs the cashier in and lands on an empty sale screen (session already open).
     *
     * @param page the Playwright page driving the register
     */
    private void loginToSaleWithOpenSession(Page page) {
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Walks onto the payment overlay and waits until it is ready.
     *
     * @param page the Playwright page driving the register
     */
    private void goPay(Page page) {
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Tenders through an on-screen payment method form.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param methodButton the exact visible method-button text
     * @param formId the id of the inline form to submit
     * @param digits the amount digits to tap, or null to keep the prefilled amount
     * @param clearFirst kept for call-site intent
     */
    private void payThroughScreen(Page page, String methodButton, String formId, String digits, boolean clearFirst) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(methodButton).setExact(true)).click();
        if (digits != null) {
            page.locator("#payNumpadZone").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
            tapDigits(page.locator("#payNumpadZone"), digits);
        }
        page.locator("#" + formId + " button[type=submit]").click();
    }

    /**
     * Waits for the SERVER to declare the sale settled.
     */
    private void waitForSettlement() {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (posState.payment.transactionComplete) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.fail("la vente n'est pas soldée — restant dû = " + posState.getRemaining());
    }

    /**
     * Closes the completed transaction cleanly (the fiscal moment).
     *
     * @param page the Playwright page carrying the completion modal
     */
    private void finishSale(Page page) {
        closeDrawer();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("NOUVELLE VENTE").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Presents a code on the hardware scan bus.
     *
     * @param code the code to present on the bus
     */
    private void scan(String code) {
        APIResponse res = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the code " + code);
    }

    /**
     * Taps digits one by one on a numeric keypad scoped under the container.
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
     * Pushes the drawer shut on the hardware bus (the embedded simulator).
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Clicks through the hardware status gate every successful unlock lands on.
     *
     * @param page the Playwright page driving the register
     */
    private void clickThroughHardwareGate(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Continuer").setExact(true)).click();
    }

    /**
     * Enters the opening float on the session screen's touch keypad.
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

    // --- Database oracles / fixtures ---

    /**
     * Reads the single OPEN session on terminal C04.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Counts the consumption-outbox declarations.
     *
     * @return the number of {@link ConsumptionOutbox} rows
     */
    private long consumptionCount() {
        return QuarkusTransaction.requiringNew().call(() -> ConsumptionOutbox.count());
    }

    /**
     * Seeds an engine-feed row (an autonomous register receives no feed pull, so
     * the applied ⇔ versions-equal branch is exercised via a persisted row).
     *
     * @param code the feed code (a catalog code, e.g. PRODUCTS, OFFERS)
     * @param version the received version
     * @param appliedVersion the applied version (equal ⇒ applied)
     * @param lastError the last import error, or null
     */
    private void seedFeed(String code, String version, String appliedVersion, String lastError) {
        QuarkusTransaction.requiringNew().run(() -> {
            EngineFeed feed = new EngineFeed();
            feed.code = code;
            feed.content = "{}";
            feed.version = version;
            feed.appliedVersion = appliedVersion;
            feed.receivedAt = LocalDateTime.now();
            feed.lastError = lastError;
            feed.persist();
        });
    }

}
