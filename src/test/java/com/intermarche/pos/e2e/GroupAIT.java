package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;

/**
 * Group A end-to-end scenarios (Prise de poste &amp; authentification), played
 * THROUGH THE SCREEN with a headless Chromium browser (quarkus-playwright)
 * against the real application (live H2, real beans). Only [S]-tagged
 * scenarios are implemented; this calibration campaign carries A1 alone.
 * <p>
 * Every scenario resets the register by navigating to {@code /lock} (which
 * logs the current operator out) so the server-side auth singleton does not
 * leak between tests, and asserts the exact user-facing texts the catalog
 * quotes plus the resulting database state read straight from Panache.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
public class GroupAIT {

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * A1 — Prise de poste nominale, rejouée intégralement au navigateur.
     * <p>
     * The cashier badges in on the lock screen (badge scanned on the hardware
     * bus, the on-screen poll flips the screen to PIN entry), taps the PIN on
     * the numpad, is carried to the SESSION screen (no session yet), types the
     * 200 € float and opens the session — landing on the SALE screen. Asserts
     * the exact catalog texts along the way and the resulting OPEN
     * {@code C04-Sxxxxx} session in the database.
     */
    @Test
    void a1_prise_de_poste_nominale_au_navigateur() {
        String root = base.toString();
        Page page = context.newPage();
        // Reset: /lock logs any leaked operator out and re-locks the register.
        page.navigate(root + "lock");
        // Badge reader peripheral: a badge scanned while locked lands in the
        // lock mailbox; the 1s /lock-data poll consumes it and opens the
        // authentication overlay in PIN-entry mode. This is the only way to
        // present a badge to a headless browser — there is no on-screen badge.
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData("12341234"));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        // The poll-driven overlay switches to "enter your PIN": wait on the
        // catalog text, never on a sleep.
        page.getByText("Entrez votre code PIN :").waitFor();
        // Tap the four PIN digits on the on-screen numpad.
        Locator keypad = page.locator("#keyboardArea");
        for (char digit : "1234".toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(digit)).setExact(true)).click();
        }
        // CONNEXION submits login+PIN through the screen (not a raw form post).
        page.locator("#actionBtn").click();
        // Prise de poste with no open session lands on the SESSION screen.
        page.locator("#openingFloat").waitFor();
        Assertions.assertTrue(page.getByText("Aucune session ouverte").isVisible(),
                "the take-over flow must reach the empty-session screen");
        // The unlock pulse really opened the drawer (live embedded simulator):
        // the cashier installs the float and pushes the drawer shut before
        // opening the session, else the @DrawerMustBeClosed guard on the sale
        // screen would divert to /drawer-error. Closing is a physical act,
        // simulated on the hardware bus like the badge.
        context.request().post(root + "api/hardware/drawer/close", RequestOptions.create());
        // Type the 200 € float at the keypad (the session screen input is a
        // native numeric field, no PosInput numpad here) and open the session.
        page.locator("#openingFloat").fill("200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        // A successful opening flows straight into the SALE screen.
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(page.locator("#opInfo").textContent().contains("Jean Dupont"),
                "the sale screen must show the logged-in operator");
        // Database oracle: exactly one OPEN session on C04, C04-Sxxxxx format,
        // 200,00 € float. Never assert an absolute session counter.
        CashSession session = QuarkusTransaction.requiringNew()
                .call(() -> CashSession.findOpenByTerminal("C04"));
        Assertions.assertNotNull(session, "an OPEN session must exist on terminal C04");
        Assertions.assertEquals(CashSession.SessionStatus.OPEN, session.status,
                "the session must be OPEN");
        Assertions.assertTrue(session.sessionNumber.matches("C04-S\\d{5}"),
                "the session number must match the C04-Sxxxxx format, was " + session.sessionNumber);
        Assertions.assertEquals(0, session.openingFloat.compareTo(new BigDecimal("200.00")),
                "the opening float must be 200,00 €");
    }
}
