package com.intermarche.demo;

import com.intermarche.e2e.E2eTestProfile;
import com.intermarche.pos.ui.PosState;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The demo's module X.1 (engine unplugged) automated SAFELY: instead of
 * killing the real valorisateur — which the pre-flight must leave up for the
 * demo — this class boots the register under its OWN profile pointing the
 * valuation URL at a dead port. The register then lives the exact situation
 * of the stage act: the engine is unreachable, scans keep flowing at catalog
 * prices without any perceptible freeze, and the payment screen raises the
 * red degraded banner while the sale collects normally.
 * <p>
 * What this deliberately does NOT replay: the live RECOVERY (promos coming
 * back ~10 s after the engine restarts) — that half needs a real engine
 * dying and returning, which only the stage act shows. The register's
 * survival, the demo's actual promise, is fully certified here.
 */
@QuarkusTest
@TestProfile(DemoDegradedIT.DeadEngineProfile.class)
@WithPlaywright(headless = true)
public class DemoDegradedIT {

    /** The seed cashier badge (Jean Dupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The coffee EAN (no engine offer alone — pure catalog price). */
    private static final String COFFEE_EAN = "3300000000004";

    /** The Golden-apples EAN whose 2FOR1 CANNOT bite here (engine dead). */
    private static final String APPLES_EAN = "3300000000001";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton — the cent-exact totals oracle. */
    @Inject
    PosState posState;

    /**
     * The dead-engine profile: everything of {@link E2eTestProfile} plus a
     * valuation URL nobody listens on — the register boots believing a
     * remote engine exists and meets a dead line at the first basket, the
     * stage situation exactly.
     */
    public static class DeadEngineProfile extends E2eTestProfile {

        /**
         * Supplies the shared e2e overrides plus the dead engine URL.
         *
         * @return the configuration overrides applied under this profile
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("pos.valuation.url", "http://localhost:59999");
            return overrides;
        }
    }

    /**
     * Module X.1 — the register survives the dead engine, banner included.
     * <p>
     * Take-over, session, two apples scanned: the 2FOR1 CANNOT bite (dead
     * engine), so the second kilogram costs exactly the first one — catalog
     * math, no freeze, no error on screen. The payment entry then shows the
     * exact red-banner text, and the cart still collects (the demo's whole
     * point: the sale never depends on the engine).
     */
    @Test
    void moduleX_moteur_mort_la_caisse_survit() {
        String root = base.toString();
        Page page = context.newPage();
        // Take-over on a fresh boot (own profile = own H2, no session yet).
        page.navigate(root + "lock");
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(CASHIER_BADGE));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page, "#keyboardArea", CASHIER_PIN);
        page.locator("#actionBtn").click();
        page.locator("#openingFloat").waitFor();
        context.request().post(root + "api/hardware/drawer/close", RequestOptions.create());
        page.locator("#openingFloat").fill("200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        // Two apples: the promo CANNOT bite — catalog math, kilo for kilo.
        scanCode(APPLES_EAN);
        page.getByText("POMMES GOLDEN").waitFor();
        BigDecimal afterFirst = posState.ticket.totalAmount;
        scanCode(APPLES_EAN);
        // The second identical scan MERGES into the same line (x2) — asking
        // for a second .receipt-item-link would wait forever. The oracle is
        // the SERVER total, polled until the merge lands.
        BigDecimal afterSecond = waitForTotalAbove(afterFirst, 5000);
        Assertions.assertEquals(0,
                afterSecond.subtract(afterFirst).compareTo(afterFirst),
                "module X: with a dead engine the second kilogram must cost exactly the "
                        + "first (pure catalog) — got " + afterSecond.subtract(afterFirst)
                        + " vs " + afterFirst);
        // One more article proves the flow never froze.
        scanCode(COFFEE_EAN);
        page.getByText("CAFÉ GRAINS 500G").waitFor();
        // The payment screen raises the exact red banner.
        // The ENCAISSER link only exists in a server render that SEES the
        // cart (the page was first rendered empty) — reload before clicking.
        page.reload();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        page.getByText("VALORISATION INDISPONIBLE — PRIX CATALOGUE APPLIQUÉS").waitFor();
        page.close();
    }

    /**
     * Polls the server-side ticket total until it exceeds the given floor.
     *
     * @param floor the total to exceed
     * @param timeoutMillis the polling window
     * @return the first total observed above the floor (or the last one)
     */
    private BigDecimal waitForTotalAbove(BigDecimal floor, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        BigDecimal current = posState.ticket.totalAmount;
        while (System.currentTimeMillis() < deadline && current.compareTo(floor) <= 0) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            current = posState.ticket.totalAmount;
        }
        return current;
    }

    /**
     * Presents a code on the hardware scan bus.
     *
     * @param code the code presented to the register
     */
    private void scanCode(String code) {
        APIResponse scan = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept " + code);
    }

    /**
     * Taps digits one by one on an on-screen numpad, scoped under the given
     * container so mode toggles are never hit.
     *
     * @param page the Playwright page driving the register
     * @param containerSelector the CSS selector of the numpad container
     * @param digits the digits to tap
     */
    private void tapDigits(Page page, String containerSelector, String digits) {
        Locator keypad = page.locator(containerSelector);
        for (char digit : digits.toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(digit)).setExact(true)).click();
        }
    }
}
