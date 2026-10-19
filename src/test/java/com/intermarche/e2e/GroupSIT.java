package com.intermarche.e2e;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.domain.sync.SyncOutbox;
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

/**
 * Group S end-to-end scenario (Supervision locale des échanges), played THROUGH
 * THE SCREEN with a headless Chromium browser against the real application. Group
 * S is {@code [S]} (caisse seule + simulateur).
 * <p>
 * <b>The « Santé des échanges » panel.</b> {@code /supervisor} shows four LOCAL
 * channels; the panel and its {@code /supervisor/health/*} fragments are reserved
 * to a connected SUPERVISOR (a plain cashier gets {@code 403} on the fragments
 * and no panel). The green/red banner is driven by the local exchange state
 * ({@code SyncOutbox} empty ⇒ green): the test forces green by clearing the
 * outbox and red by persisting an outbox row. {@code Relancer le tirage} hits the
 * store node, which is absent in {@code [S]}, so only its FAILURE notice is
 * reachable here.
 * <p>
 * <b>Residue.</b> The {@code Relancer le tirage} SUCCESS notice ({@code Tirage
 * des référentiels relancé.}) needs a reachable store node ({@code [N]}).
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupSIT {

    /** The seed cashier badge (Jean Dupont / jdupont, CASHIER — not a supervisor). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER = supervisor). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton. */
    @Inject
    PosState posState;

    /**
     * S1 — Panneau « Santé des échanges ».
     * <p>
     * The health fragments are forbidden (403) to a non-supervisor cashier. To a
     * connected manager the panel shows the green banner when the local exchanges
     * are clean (outbox empty, no pending consumption) and the red banner once an
     * outbox row is pending. Relaunching the referential pull with no store node
     * surfaces the failure notice.
     */
    @Test
    void s1_sante_des_echanges() {
        ensureOpenSession();
        // --- A non-supervisor cashier is forbidden the health fragments (403) ---
        loginAs(CASHIER_BADGE, CASHIER_PIN).close();
        APIResponse forbidden = context.request().get(base.toString() + "supervisor/health/uplink");
        Assertions.assertEquals(403, forbidden.status(),
                "the health fragment must be forbidden to a non-supervisor");
        // --- A connected manager: GREEN when the local exchanges are clean ---
        Page mgr = loginAs(MANAGER_BADGE, MANAGER_PIN);
        clearExchanges();
        mgr.navigate(base.toString() + "supervisor");
        mgr.getByText("Tous les canaux de cette caisse sont au vert.").waitFor();
        Assertions.assertTrue(mgr.getByText("Aucun flux reçu sur cette caisse.").count() >= 1,
                "a fresh register must show the empty-feeds state");
        // --- RED once an outbox row is pending ---
        pendOutboxRow();
        mgr.navigate(base.toString() + "supervisor");
        mgr.getByText("Un canal de cette caisse demande attention.").waitFor();
        // --- Relancer le tirage with no store node → failure notice ---
        mgr.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Relancer le tirage").setExact(true)).click();
        mgr.getByText("Tirage des référentiels en échec").waitFor();
        mgr.close();
    }

    // --- Reusable gestures ---

    /**
     * Logs in as the given operator and lands on the sale screen of the shared
     * open session.
     *
     * @param badge the badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     * @return a Playwright page logged in on the sale screen
     */
    private Page loginAs(String badge, String pin) {
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, badge, pin);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once (as the
     * cashier) through the full prise-de-poste screen.
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#floatDisplay").waitFor();
        closeDrawer();
        Locator keypad = page.locator("#floatKbArea");
        keypad.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        // KEYED IN CENTS: the float pad carries NO comma key (the pad places
        // the comma itself), so 200,00 € is tapped 2-0-0-0-0 and the separator
        // of the amount written here is skipped rather than looked for.
        for (char key : "200,00".toCharArray()) {
            if (key < '0' || key > '9') {
                continue;
            }
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Navigates to the lock screen, presents the badge on the hardware scan bus
     * and taps the PIN, submitting the unlock.
     *
     * @param page the Playwright page driving the register
     * @param badge the 8-digit badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     */
    private void scanBadgeAndEnterPin(Page page, String badge, String pin) {
        String root = base.toString();
        posState.endorsement.clear();
        posState.touch();
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

    // --- Exchange-state fixtures / oracles ---

    /**
     * Clears the local exchange backlog so the health panel reports green: empties
     * the uplink outbox and any pending consumption.
     */
    private void clearExchanges() {
        QuarkusTransaction.requiringNew().run(() -> {
            SyncOutbox.deleteAll();
            ConsumptionOutbox.delete("applied = false");
        });
    }

    /**
     * Persists one pending uplink-outbox row directly (the enqueue service is a
     * no-op with no store URL), forcing the health panel red.
     */
    private void pendOutboxRow() {
        QuarkusTransaction.requiringNew().run(() -> {
            SyncOutbox row = new SyncOutbox();
            row.entityType = SyncOutbox.EntityType.MOVEMENT;
            row.entityId = 1L;
            row.createdAt = LocalDateTime.now();
            row.persist();
        });
    }

    /**
     * Reads the single OPEN session on terminal C04.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

}
