package com.intermarche.e2e;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductType;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
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

import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDateTime;

/**
 * Group N end-to-end scenarios (Compléments — the 2nd-audit "seams"), played
 * THROUGH THE SCREEN with a headless Chromium browser against the real
 * application. Only the {@code [S]} scenarios are implemented here (N1, N2, N6,
 * N7, N8, N10, N11); N3/N4 are {@code [V]}, N5/N9 are {@code [N]} — reported as
 * residue.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (n01…n11) and share ONE cash session; each
 * builds its own cart and restores what it alters.
 * <p>
 * <b>Écart code/catalogue (N1).</b> The catalog expects a cart-phase payment
 * voucher (50…/789…) to fall to {@code CODE INCONNU}; the code instead consumes
 * it with {@code BON VALABLE EN PHASE PAIEMENT} (the deposit/payment split is
 * explicit, not a fall-through) — this test asserts the REAL behavior.
 * <b>Residue (N11).</b> The "register without a logo file" half is unreachable
 * from a shared-classpath {@code @QuarkusTest} (the logo is a cached classpath
 * resource with no disabling flag) — only the with-logo conventions are asserted.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupNIT {

    /** The seed cashier badge (Jean Dupont / jdupont, CASHIER). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER) — operator B for N8. */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** Single-price unit product, TTC 0,96 €. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    /** Single-price unit product, TTC 6,00 €. */
    private static final String EAN_HUILE = "3300000000006";

    /** Deposit-return voucher: 298 + serial + 4-digit cents (0150 → 1,50 €). */
    private static final String DEPOSIT_VOUCHER = "2981234560150";

    /** A store-voucher payment number (^50\d{12}$) — a payment instrument. */
    private static final String STORE_VOUCHER = "50000000000150";

    /** A code no handler recognizes (13 digits, absent from the catalog). */
    private static final String UNKNOWN_CODE = "9999999999999";

    /** A purpose-built product whose only price is expired (N6). */
    private static final String EAN_EXPIRED = "3300000009999";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton — oracle for the invisible outcomes. */
    @Inject
    PosState posState;

    /**
     * N1 — Bon de paiement scanné en phase panier.
     * <p>
     * Only the deposit-return 298 lives in the cart (a negative, VAT-0 line); a
     * payment voucher (50…/789…) scanned OUT of payment is consumed with {@code
     * BON VALABLE EN PHASE PAIEMENT} (the deposit/payment split is explicit — the
     * catalog's stale {@code CODE INCONNU} is only what a GENUINELY unknown code
     * yields). Proves the split on both sides.
     */
    @Test
    void n01_bon_paiement_scanne_phase_panier() {
        Page page = freshSale();
        // Deposit 298: accepted in the cart as a negative, VAT-0 line.
        scan(DEPOSIT_VOUCHER);
        page.getByText("BON DE CONSIGNE").waitFor();
        TicketState.TicketItem deposit = posState.ticket.items.get(0);
        Assertions.assertTrue(deposit.isNegative(), "the deposit must be a negative line");
        Assertions.assertEquals(0, deposit.vatRate.compareTo(BigDecimal.ZERO), "a deposit line is out of VAT scope");
        // A payment voucher out of payment: consumed, deferred to payment phase.
        scan(STORE_VOUCHER);
        Assertions.assertEquals("BON VALABLE EN PHASE PAIEMENT", posState.ticket.transientError,
                "a payment voucher scanned in the cart must be deferred (not CODE INCONNU)");
        // A genuinely unknown code: the unknown-code fallback.
        scan(UNKNOWN_CODE);
        Assertions.assertEquals("CODE INCONNU: " + UNKNOWN_CODE, posState.ticket.transientError,
                "an unrecognized code must reach the unknown-code fallback");
        page.close();
    }

    /**
     * N2 — Rejeu d'un bon de déconsigne.
     * <p>
     * The SAME 298 deposit number scanned on two independent sales is accepted
     * both times — there is no deposit registry, a known limit the test documents.
     */
    @Test
    void n02_rejeu_bon_deconsigne() {
        Page first = freshSale();
        scan(DEPOSIT_VOUCHER);
        first.getByText("BON DE CONSIGNE").waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(), "the first sale accepts the deposit");
        first.close();
        // A fresh sale (logout clears the cart): the SAME number is accepted again.
        Page second = freshSale();
        scan(DEPOSIT_VOUCHER);
        second.getByText("BON DE CONSIGNE").waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "the same 298 is accepted again on a second sale (no deposit registry)");
        Assertions.assertNull(posState.ticket.transientError,
                "the replayed deposit raises no blocking message");
        second.close();
    }

    /**
     * N6 — Fenêtre de prix expirée.
     * <p>
     * A product whose only price row has a past {@code endDateTime} resolves with
     * NO current price: the line is added anyway at 0,00 € with the default VAT
     * rate (the assumed no-current-price behavior — no refusal). The fixture is
     * created and removed afterwards.
     */
    @Test
    void n06_fenetre_prix_expiree() {
        Long[] ids = insertProductWithExpiredPrice();
        try {
            Page page = freshSale();
            scan(EAN_EXPIRED);
            page.getByText("PRODUIT PRIX EXPIRE").waitFor();
            TicketState.TicketItem line = posState.ticket.items.get(0);
            Assertions.assertEquals(0, line.getTotalPrice().compareTo(BigDecimal.ZERO),
                    "a product with no current price must add a 0,00 € line, not refuse");
            Assertions.assertEquals(0, line.vatRate.compareTo(new BigDecimal("0.2000")),
                    "a priceless line falls back to the default VAT rate");
            page.close();
        } finally {
            // Best-effort: the scanned line references the product (FK), so the
            // product row may survive — harmless on the per-class drop-and-create DB.
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    Price.deleteById(ids[1]);
                    Product.deleteById(ids[0]);
                });
            } catch (RuntimeException referencedByLine) {
                // Left in place.
            }
        }
    }

    /**
     * N7 — Mécanique du return-URL tiroir.
     * <p>
     * With the drawer open, a blocked GET stores the BLOCKED PATH as the return
     * url ({@code fruits}); a blocked POST stores the ORIGIN PAGE (its Referer
     * path) and NEVER replays the mutation. Graved in {@code DrawerCheckFilter}.
     */
    @Test
    void n07_mecanique_return_url_tiroir() {
        login().close();
        // --- Blocked GET → the blocked path itself ---
        openDrawerHw();
        context.request().get(base.toString() + "fruits");
        Assertions.assertTrue(posState.returnUrl != null && posState.returnUrl.endsWith("fruits"),
                "a blocked GET must store the blocked path, was: " + posState.returnUrl);
        closeDrawer();
        // --- Blocked POST → the referer (origin page), mutation NOT replayed ---
        loginFresh();
        openDrawerHw();
        context.request().post(base.toString() + "action/fidelity", RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setHeader("Referer", base.toString() + "fidelity")
                .setData("card=2990000000019"));
        Assertions.assertTrue(posState.returnUrl != null && posState.returnUrl.endsWith("fidelity"),
                "a blocked POST must store the referer path, was: " + posState.returnUrl);
        Assertions.assertFalse(posState.fidelity.active,
                "a blocked POST must NOT replay the fidelity attach");
        closeDrawer();
    }

    /**
     * N8 — Session par caisse, pas par caissier.
     * <p>
     * Operator A opens the session; after a lock, operator B unlocks and sells —
     * B sells in A's session (the mono-register model): the OPEN session on the
     * register is the SAME row (same number), still imputed to its opening cashier
     * A, not to B.
     */
    @Test
    void n08_session_par_caisse_pas_par_caissier() {
        ensureOpenSession();
        CashSession sessionBefore = openSessionOnC04();
        String numberBefore = sessionBefore.sessionNumber;
        Long openerBefore = QuarkusTransaction.requiringNew()
                .call(() -> openSessionOnC04().openingCashier.id);
        // Operator B (the manager) unlocks and sells.
        Page b = context.newPage();
        scanBadgeAndEnterPin(b, MANAGER_BADGE, MANAGER_PIN);
        closeDrawer();
        b.getByText("TOTAL À PAYER").waitFor();
        scan(EAN_BAGUETTE);
        b.getByText(LABEL_BAGUETTE).waitFor();
        // The session on the register is unchanged: same row, same opener A.
        CashSession sessionAfter = openSessionOnC04();
        Assertions.assertEquals(numberBefore, sessionAfter.sessionNumber,
                "operator B must sell in the SAME session, not open a new one");
        Assertions.assertEquals(CashSession.SessionStatus.OPEN, sessionAfter.status,
                "the session must stay OPEN");
        Long openerAfter = QuarkusTransaction.requiringNew()
                .call(() -> openSessionOnC04().openingCashier.id);
        Assertions.assertEquals(openerBefore, openerAfter,
                "the session must stay imputed to its opening cashier A, not B");
        Long cashierId = QuarkusTransaction.requiringNew()
                .call(() -> Employee.findActiveLogin(CASHIER_BADGE).id);
        Assertions.assertEquals(cashierId, openerAfter, "the opener must be cashier A (jdupont)");
        b.close();
    }

    /**
     * N10 — Appel superviseur sans store-url.
     * <p>
     * On an autonomous register (no store url), choosing a supervisor-call reason
     * fails cleanly with {@code SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE} — no
     * crash, no block.
     */
    @Test
    void n10_appel_superviseur_sans_store_url() {
        login().close();
        context.request().get(base.toString() + "action/supervisor/PRIX");
        Assertions.assertEquals("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE", posState.ticket.transientError,
                "a supervisor call with no store url must fail cleanly with the message");
    }

    /**
     * N11 — Logo sur tous les documents.
     * <p>
     * The sale ticket carries the logo directive ({@code [[IMAGE …]]}) in its
     * header IN ADDITION to the store name (the decision inscribed in
     * {@code renderTicket}). (The avoir / loyalty-credit "logo IN PLACE OF the
     * name" convention is exercised by H4/H6; the "no logo file → name in text"
     * half is unreachable from this shared-classpath run and reported as residue.)
     */
    @Test
    void n11_logo_sur_les_documents() {
        Page page = freshSale();
        scan(EAN_HUILE);
        goPay(page);
        payThroughScreen(page, "ESPÈCES", "cashForm", "6", false);
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        closeDrawer();
        clearPrinter();
        page.waitForResponse("**/action/print", () -> page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("IMPRIMER TICKET")).click());
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("[[IMAGE "),
                "the sale ticket header must carry the logo directive, paper was:\n" + paper);
        Assertions.assertTrue(paper.contains("INTERMARCHE"),
                "the sale ticket must ALSO carry the store name (logo in addition to the name)");
        // Close cleanly.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("NOUVELLE VENTE").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
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
     * Logs in the cashier on a fresh page and returns it on the sale screen.
     *
     * @return a Playwright page on the sale screen
     */
    private Page login() {
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        loginToSaleWithOpenSession(page);
        return page;
    }

    /**
     * Logs the cashier back in without closing other pages (used after a
     * drawer-blocked gesture to re-establish a clean sale screen).
     */
    private void loginFresh() {
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        loginToSaleWithOpenSession(page);
        page.close();
    }

    /**
     * Ensures the register carries an OPEN session, opening one once (as the
     * cashier) through the full prise-de-poste screen.
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
     * Opens the drawer on the hardware bus (to arm the drawer guard for N7).
     */
    private void openDrawerHw() {
        context.request().post(base.toString() + "api/hardware/drawer/open", RequestOptions.create());
    }

    /**
     * Pushes the drawer shut on the hardware bus (the embedded simulator).
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Empties the printer simulator buffer.
     */
    private void clearPrinter() {
        context.request().post(base.toString() + "api/hardware/printer/clear", RequestOptions.create());
    }

    /**
     * Reads the accumulated printer simulator buffer.
     *
     * @return the printed content captured by the simulator
     */
    private String printerContent() {
        return context.request().get(base.toString() + "api/hardware/printer/content").text();
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
     * Creates a product whose only price row is expired (past endDateTime), so it
     * resolves with no current price — the N6 fixture.
     *
     * @return {productId, priceId}
     */
    private Long[] insertProductWithExpiredPrice() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product p = new Product();
            p.ean = EAN_EXPIRED;
            p.name = "PRODUIT PRIX EXPIRE";
            p.productType = ProductType.UNIT;
            p.active = true;
            p.persist();
            Price price = new Price();
            price.product = p;
            price.priceExcludingTax = new BigDecimal("5.00");
            price.priceIncludingTax = new BigDecimal("6.00");
            // Reuse a MANAGED VatRate (constructing a fresh one is transient →
            // it would fail the flush; the rate is irrelevant here as the price
            // is expired and never resolved).
            price.vat = VatRate.<VatRate>findAll().firstResult();
            price.priority = 1;
            price.startDateTime = LocalDateTime.now().minusDays(30);
            price.endDateTime = LocalDateTime.now().minusDays(1);
            price.persist();
            return new Long[] {p.id, price.id};
        });
    }

}
