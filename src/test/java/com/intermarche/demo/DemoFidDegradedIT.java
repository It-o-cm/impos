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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URL;
import java.util.Map;

/**
 * PRE-FLIGHT of the demo's LOYALTY-DEGRADED act (scenario-demo-complet.pdf —
 * module X.1bis): the register wired to a DEAD imfid URL, the REAL engine
 * still up. Certifies the three commitments of the degraded doctrine (spec
 * §2.2): the earn projection stays hidden (nothing anxiogenic), the CAGNOTTE
 * payment refuses with the exact catalog message, and the sale closes
 * normally — the loyalty outage never blocks a customer.
 * <p>
 * Same philosophy as {@link DemoDegradedIT} for the engine: a dedicated
 * profile on a dead port, the REAL imfid (if any) never touched — this class
 * can run alongside a live demo stack without harming it. The outbox
 * catch-up half of the act (restart imfid, watch the movements land) stays a
 * stage gesture: it requires killing a real process, which a pre-flight must
 * never do.
 */
@QuarkusTest
@TestProfile(DemoFidDegradedIT.DeadImfidProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class DemoFidDegradedIT {

    /** The seed cashier badge. */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The Golden-apples EAN (valued cart, engine required). */
    private static final String APPLES_EAN = "3300000000001";

    /** The imfid demo card — scanned, attached, but the service is dead. */
    private static final String FID_CARD = "2990000000019";

    /** The Playwright browser context injected by the extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton — the memory-truth oracle. */
    @Inject
    PosState posState;

    /**
     * The dead-imfid profile: the loyalty wiring of {@link DemoFidIT}, the
     * URL pointed at a port where nothing listens.
     */
    public static class DeadImfidProfile extends E2eTestProfile implements QuarkusTestProfile {
        /**
         * Adds the dead loyalty configuration on top of the shared profile.
         *
         * @return the merged configuration overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            java.util.Map<String, String> config =
                    new java.util.HashMap<>(super.getConfigOverrides());
            config.put("pos.fid.url", "http://localhost:59998");
            config.put("pos.fid.user", "pos");
            config.put("pos.fid.password", "pos-password");
            config.put("scan.pattern.fidelity", "^299\\d{10}$");
            return config;
        }
    }

    /**
     * fiddeg01 — Module X.1bis, the whole act: hidden projection, refused
     * CAGNOTTE with the exact message, and a sale that closes anyway.
     * <p>
     * The card attaches (local gesture), two valued apples pass, the earn
     * NEVER appears (three seconds of grace — the breaker eats the first
     * timeout), the CAGNOTTE attempt shows SERVICE FIDÉLITÉ INDISPONIBLE,
     * and cash settles the sale to the fiscal close. One method: the act is
     * one continuous scene on stage.
     */
    @Test
    void fiddeg01_moduleX1bis_degrade_fidelite_complet() throws InterruptedException {
        Page page = openSaleScreen();
        scanCode(APPLES_EAN);
        scanCode(APPLES_EAN);
        scanCode(FID_CARD);
        Assertions.assertTrue(posState.fidelity.active,
                "module X.1bis: la carte s'attache localement même imfid mort");
        Thread.sleep(3000);
        Assertions.assertNull(posState.fidelity.earnTotal,
                "module X.1bis: la projection doit rester MASQUÉE (le dégradé "
                        + "s'affiche en n'affichant rien)");
        // Re-render: the ENCAISSER link only exists in a server render that
        // SEES the cart (the page was first rendered empty).
        page.reload();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        // Wait for the pay screen through the thing we actually use next —
        // never through a banner text no green run has ever validated.
        typeAmountVerified(page, "CAGNOTTE", "#fidDisplay", "#fidForm", "1", "1");
        waitForTicketError("SERVICE FIDÉLITÉ INDISPONIBLE", 8000);
        Assertions.assertNull(posState.payment.fidReservationId,
                "module X.1bis: aucun bail ne doit exister sur un service mort");
        typeAmountVerified(page, "ESPÈCES", "#cashDisplay", "#cashForm", "50", "50");
        // Direct /action/finish navigation — the modal click is a race
        // against pay.html's version-change reload (it ate a run).
        page.navigate(base.toString() + "action/finish");
        closeDrawer(); // cash opened the drawer; the sale screen demands it shut
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    // --- Shared demo gestures (DemoIT's conventions) ---

    /**
     * Opens a pay sub-form, types an amount on the shared numpad, VERIFIES
     * the sub-display captured it, and submits. pay.html reloads itself on
     * any state-version change — a reload between the sub-form opening and
     * the submit silently resets the hidden field to "0", which the actions
     * read as "maximum". One retry heals the race; a second loss fails with
     * its own name.
     *
     * @param page the Playwright page on the payment screen
     * @param modeButton the exact pay-mode button label (e.g. "ESPÈCES")
     * @param displayId the sub-form display id (e.g. "#cashDisplay")
     * @param formId the sub-form id (e.g. "#cashForm")
     * @param digits the digits to tap — the amount IN EUROS: keyboard-lib's
     *        payment mode accumulates keys verbatim (no cents convention —
     *        typing "5000" would mean five thousand euros)
     * @param expectedDisplay the display value proving capture — the MIRROR
     *        of the keys typed (e.g. "50" for fifty euros)
     */
    private void typeAmountVerified(Page page, String modeButton, String displayId,
                                    String formId, String digits, String expectedDisplay) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Locator button = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(modeButton).setExact(true));
                button.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                button.click(new Locator.ClickOptions().setForce(true));
                // The numpad only EXISTS once openInput rendered it into the
                // sub-form: wait for the sub-display, not for the keys. A
                // mode click swallowed by a re-render used to blow up 30 s
                // later on a key that never came.
                page.locator(displayId)
                        .waitFor(new Locator.WaitForOptions().setTimeout(5000));
                // CARTE/CHÈQUE/CAGNOTTE arrive PRE-FILLED with the remaining
                // due, and a buffer already holding two decimals REJECTS every
                // further key — clearing first is what the cashier does.
                page.locator("#payNumpadZone").getByRole(AriaRole.BUTTON,
                                new Locator.GetByRoleOptions().setName("C").setExact(true))
                        .click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                tapDigits(page, "#payNumpadZone", digits);
                if (expectedDisplay.equals(page.locator(displayId).inputValue())) {
                    page.locator(formId + " button[type=submit]").click();
                    return;
                }
            } catch (Exception retryable) {
                // Swallowed click or version-change reload: start clean.
            }
            page.reload();
        }
        Assertions.fail("saisie impossible sur " + modeButton + " après 3 essais — "
                + "afficheur " + displayId + " jamais à " + expectedDisplay);
    }


    /**
     * Opens a ready sale screen — DemoIT's proven recipe.
     *
     * @return the page landed on the sale screen
     */
    private Page openSaleScreen() {
        String root = base.toString();
        Page page = context.newPage();
        // The register is a SHARED server singleton: a previous test may have
        // left the drawer open (cash) or a payment in progress, and both make
        // the sale screen unreachable. Push the drawer shut, then WAIT for the
        // screen before concluding anything — isVisible() is a snapshot and
        // answers false on a still-loading page, which once sent this helper
        // cancelling payments (and reverting valuations) for nothing.
        closeDrawer();
        page.navigate(root);
        if (page.getByText("CAISSE FERMÉE").isVisible()) {
            scanCode(CASHIER_BADGE);
            page.getByText("Entrez votre code PIN :").waitFor();
            tapDigits(page, "#keyboardArea", CASHIER_PIN);
            page.locator("#actionBtn").click();
            // OPEN THE SESSION when asked: without it every scan is refused
            // (no session = no sale). DemoIT opens it in demo01; the fidelity
            // classes have no such predecessor — dropping this step is what
            // silently detached the card for a whole run.
            if (page.locator("#openingFloat").isVisible()) {
                closeDrawer();
                page.locator("#openingFloat").fill("200,00");
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
            }
            closeDrawer();
        }
        try {
            page.getByText("TOTAL À PAYER")
                    .waitFor(new Locator.WaitForOptions().setTimeout(5000));
            return page;
        } catch (Exception stillNotThere) {
            // Remediation, only after the wait really failed: abandon a
            // pending card request and any payment in progress, then retry.
            page.navigate(root + "action/card-cancel");
            page.navigate(root + "action/cancel");
            closeDrawer();
            page.navigate(root);
        }
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Waits for a transient ticket error on the SERVER state — the display
     * channel proven by the register (setError), independent of where the
     * template renders it.
     *
     * @param expected the exact expected message
     * @param timeoutMillis the polling window
     */
    private void waitForTicketError(String expected, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(posState.ticket.transientError)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.fail("module X.1bis: le refus « " + expected
                + " » doit être posé sur l'état (transientError = "
                + posState.ticket.transientError + ")");
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
     * Taps digits one by one on an on-screen numpad.
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

    /**
     * Pushes the drawer shut on the hardware bus.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }
}
