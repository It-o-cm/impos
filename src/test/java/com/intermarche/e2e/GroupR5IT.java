package com.intermarche.e2e;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.sync.ConsumptionOutbox;
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
 * Group R, scenario R5 (Livraison désactivée) — {@code [S]}, played on the caisse
 * + simulateur under a profile that REMOVES {@code pos.valuation.url}. With the
 * valuation URL absent, both the consumption delivery and the engine-feed
 * delivery are disabled: queued declarations accumulate (visible on the
 * supervision consumption channel) and stay undelivered, while the sale is valued
 * locally and completes normally. R5 lives in its own class because it needs a
 * different profile than R3/R7 (which need the URL present-but-dead).
 */
@QuarkusTest
@TestProfile(GroupR5IT.UrlAbsentProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupR5IT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** A single-price unit product, TTC 6,00 €. */
    private static final String EAN_HUILE = "3300000000006";

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
     * URL-absent profile: removes {@code pos.valuation.url} so the delivery loops
     * are disabled and the valuation is LOCAL-only.
     */
    public static class UrlAbsentProfile extends E2eTestProfile {
        /**
         * Overrides the shared config, removing the valuation URL entirely.
         *
         * @return the config overrides without any valuation URL
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.remove("pos.valuation.url");
            return overrides;
        }
    }

    /**
     * R5 — Livraison désactivée.
     * <p>
     * With no valuation URL, queued consumption declarations ACCUMULATE and stay
     * pending (the delivery loop is disabled) — they are visible on the
     * supervision consumption channel ({@code count(applied = false)}) — while a
     * sale is still valued locally and completes without breaking.
     */
    @Test
    void r5_livraison_desactivee() {
        // --- Seed two pending declarations (the derivation side is R1/R2's [V]
        //     remit; here the DELIVERY side is under test) ---
        seedPendingConsumption("C04-00090001");
        seedPendingConsumption("C04-00090002");
        Assertions.assertEquals(2, pendingConsumptionCount(),
                "queued declarations must accumulate, visible on the supervision consumption channel");
        // --- A sale is valued locally and completes without breaking ---
        Page page = freshSale();
        scan(EAN_HUILE);
        goPay(page);
        payThroughScreen(page, "ESPÈCES", "cashForm", "600", false);
        waitForSettlement();
        finishSale(page);
        page.close();
        // --- Delivery stays disabled: the pending declarations were never shipped ---
        Assertions.assertEquals(2, pendingConsumptionCount(),
                "with delivery disabled the pending declarations must stay undelivered");
    }

    // --- Reusable gestures ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen.
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
     * Ensures the register carries an OPEN session, opening one once.
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
     * Counts the pending (undelivered) consumption declarations — the exact query
     * the supervision consumption channel uses.
     *
     * @return the number of pending {@link ConsumptionOutbox} rows
     */
    private long pendingConsumptionCount() {
        return QuarkusTransaction.requiringNew().call(() -> ConsumptionOutbox.count("applied = false"));
    }

    /**
     * Seeds a pending SALE consumption declaration (the delivery side is under
     * test, so the row is enqueued directly).
     *
     * @param ticketNumber the ticket number key
     */
    private void seedPendingConsumption(String ticketNumber) {
        QuarkusTransaction.requiringNew().run(() -> {
            ConsumptionOutbox row = new ConsumptionOutbox();
            row.ticketNumber = ticketNumber;
            row.storeCode = "C04";
            row.finalizedAt = LocalDateTime.now();
            row.direction = ConsumptionOutbox.Direction.SALE;
            row.advantagesJson = "[]";
            row.applied = false;
            row.persist();
        });
    }

}
