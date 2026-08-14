package com.intermarche.demo;

import com.intermarche.e2e.E2eTestProfile;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo's module XIII (two-node store) automated on the SAME prerequisite
 * philosophy as the engine in {@link DemoIT}: an EXTERNAL store node must be
 * running on {@code localhost:8082} (the same application in store role),
 * exactly like the demo's second instance. The first check pings it with the
 * message to display before a demo; then a full sale is closed on the
 * register and the STORE's dashboard is polled until the consolidation
 * lands — the ticket count moves by one within the outbox drain window.
 * <p>
 * Start the store node before running (same command as the demo's module 0):
 * a second instance with the store profile listening on 8082. When it is not
 * there the class SKIPS (assumption, not failure): module XIII is the 90'
 * option of the demo, its absence must not redden the 30'/60' pre-flight.
 */
@QuarkusTest
@TestProfile(DemoStoreIT.StoreLinkedProfile.class)
@WithPlaywright(headless = true)
public class DemoStoreIT {

    /** The seed cashier badge (Jean Dupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The coffee EAN sold in the consolidation sale. */
    private static final String COFFEE_EAN = "3300000000004";

    /** The external store node the demo's module XIII runs against. */
    private static final String STORE_URL = "http://localhost:8082";

    /** The outbox drains every 10 s: poll up to three cycles. */
    private static final int DRAIN_TIMEOUT_SECONDS = 35;

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton (unused oracle here, kept for parity). */
    @Inject
    PosState posState;

    /**
     * The store-linked profile: the shared e2e overrides plus the store URL,
     * so the register under test pushes its outbox to the external node —
     * the demo's exact two-node wiring.
     */
    public static class StoreLinkedProfile extends E2eTestProfile {

        /**
         * Supplies the shared e2e overrides plus the store link.
         *
         * @return the configuration overrides applied under this profile
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("pos.sync.store-url", STORE_URL);
            return overrides;
        }
    }

    /**
     * Module XIII — a sale closed on the register lands on the store's
     * dashboard within the drain window.
     * <p>
     * The store's {@code /dashboard-data} ticket count is read BEFORE, a
     * full sale is closed on the register (take-over, coffee, exact cash,
     * fiscal closing), and the count is polled until it moved by at least
     * one — the consolidation chain (outbox → push → idempotent ingestion →
     * dashboard aggregation) proven end to end, deltas only (the external
     * store keeps its own history, absolute counters are never asserted).
     */
    @Test
    void moduleXIII_la_vente_atterrit_au_dashboard_du_magasin() {
        Integer before = storeTicketCount();
        Assumptions.assumeTrue(before != null,
                "LE NŒUD MAGASIN NE RÉPOND PAS sur " + STORE_URL
                        + " — démarrez la seconde instance (rôle store, port 8082) "
                        + "avant de certifier le module XIII, ou jouez la démo sans lui");
        // --- A full sale on the register ---
        String root = base.toString();
        Page page = context.newPage();
        page.navigate(root + "lock");
        context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(CASHIER_BADGE));
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page, "#keyboardArea", CASHIER_PIN);
        page.locator("#actionBtn").click();
        page.locator("#openingFloat").waitFor();
        context.request().post(root + "api/hardware/drawer/close", RequestOptions.create());
        page.locator("#openingFloat").fill("200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(COFFEE_EAN));
        page.getByText("CAFÉ GRAINS 500G").waitFor();
        // The ENCAISSER link only exists in a server render that SEES the
        // cart (this page was rendered while the cart was empty).
        page.reload();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        // Amounts are typed IN EUROS (the keypad accumulates keys verbatim),
        // and the numpad only exists once the sub-form is open.
        typeAmountVerified(page, "ESPÈCES", "#cashDisplay", "#cashForm", "50", "50");
        // Cash OPENS THE DRAWER and the sale screen is @DrawerMustBeClosed:
        // close it, then take the fiscal gesture by direct navigation (a
        // modal click races pay.html's version-change reload).
        context.request().post(root + "api/hardware/drawer/close", RequestOptions.create());
        page.navigate(root + "action/finish");
        context.request().post(root + "api/hardware/drawer/close", RequestOptions.create());
        page.navigate(root);
        page.getByText("EN ATTENTE D'ARTICLES...").waitFor();
        page.close();
        // --- The consolidation lands within the drain window ---
        long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_SECONDS * 1000L;
        Integer after = before;
        while (System.currentTimeMillis() < deadline) {
            after = storeTicketCount();
            if (after != null && after >= before + 1) {
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.assertNotNull(after, "module XIII: the store stopped answering mid-poll");
        Assertions.assertTrue(after >= before + 1,
                "module XIII: the closed sale must land on the store dashboard within "
                        + DRAIN_TIMEOUT_SECONDS + " s (tickets before=" + before
                        + ", after=" + after + ") — " + linkDiagnosis());
    }

    /**
     * Describes WHERE the store link broke, so the failure names its own
     * cause instead of leaving three hypotheses open: an outbox that never
     * drained (the register does not push), rows still pending (the store
     * refuses them), or an empty outbox facing an unchanged dashboard (the
     * store ingests but does not consolidate).
     *
     * @return a one-line diagnosis appended to the failure message
     */
    private String linkDiagnosis() {
        StringBuilder diagnosis = new StringBuilder();
        // A pushed row is DELETED from the outbox, so a leftover row means the
        // store refused it (or never answered); an empty outbox means either
        // nothing was ever enqueued, or everything went through.
        long remaining = QuarkusTransaction.requiringNew().call(
                () -> com.intermarche.pos.domain.SyncOutbox.count());
        String lastError = QuarkusTransaction.requiringNew().call(() -> {
            com.intermarche.pos.domain.SyncOutbox row =
                    com.intermarche.pos.domain.SyncOutbox
                            .<com.intermarche.pos.domain.SyncOutbox>find("order by id desc")
                            .firstResult();
            return row == null ? null : row.attempts + " essai(s), dernière erreur: " + row.lastError;
        });
        diagnosis.append("outbox caisse: ").append(remaining).append(" ligne(s) restante(s)");
        if (remaining > 0) {
            diagnosis.append(" [").append(lastError).append("]")
                    .append(" → LE MAGASIN REFUSE OU EST MUET: vérifier pos.role=store sur :8082")
                    .append(" et un éventuel pos.sync.token discordant");
        } else {
            diagnosis.append(" → soit la caisse n'a RIEN enfilé (pos.sync.store-url absent au")
                    .append(" boot ?), soit tout est poussé et le magasin n'a pas consolidé");
        }
        APIResponse probe = context.request().get(STORE_URL + "/dashboard-data");
        diagnosis.append(" | /dashboard-data: HTTP ").append(probe.status());
        return diagnosis.toString();
    }

    /**
     * Reads the ticket count from the store node's dashboard data.
     *
     * @return the store's consolidated ticket count, or null when the store
     *         does not answer or the payload carries no count
     */
    private Integer storeTicketCount() {
        try {
            APIResponse data = context.request().get(STORE_URL + "/dashboard-data");
            if (!data.ok()) {
                return null;
            }
            Matcher matcher = Pattern.compile("\"ticketCount\"\\s*:\\s*(\\d+)").matcher(data.text());
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }


    /**
     * Opens a pay sub-form, clears the pre-filled amount, types the wanted
     * one IN EUROS, verifies the sub-display captured it, and submits. Bounded
     * waits and three attempts: a click swallowed by pay.html's reload costs
     * a second, not a thirty-second timeout.
     *
     * @param page the Playwright page on the payment screen
     * @param modeButton the exact pay-mode button label
     * @param displayId the sub-form display id
     * @param formId the sub-form id
     * @param digits the amount in euros, as typed
     * @param expectedDisplay the display value proving capture
     */
    private void typeAmountVerified(Page page, String modeButton, String displayId,
                                    String formId, String digits, String expectedDisplay) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Locator button = page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(modeButton).setExact(true));
                button.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                button.click(new Locator.ClickOptions().setForce(true));
                page.locator(displayId)
                        .waitFor(new Locator.WaitForOptions().setTimeout(5000));
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
        Assertions.fail("saisie impossible sur " + modeButton + " après 3 essais");
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
