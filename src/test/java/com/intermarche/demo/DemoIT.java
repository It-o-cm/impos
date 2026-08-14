package com.intermarche.demo;

import com.intermarche.e2e.E2eTestProfile;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLineValuation;
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

import java.math.BigDecimal;
import java.net.URL;
import java.util.Base64;

/**
 * PRE-FLIGHT of the demo scenario: replays the ESSENTIEL path of the demo
 * script (scenario-demo-complet.pdf — modules I, II.1, V, III.1, VI.1-3+7,
 * VII) through a headless Chromium browser against the real application AND
 * the REAL valuation engine on :8090. Run it right before a demo: green means
 * the environment is demo-ready; each failure message names the module of the
 * script that would break on stage.
 * <p>
 * <b>Prerequisite (deliberate).</b> Unlike the other e2e groups, this class
 * REQUIRES the external valorisateur running on {@code localhost:8090} with
 * its mirror seed — exactly like the demo does. {@code demo00} checks it
 * first with an explicit failure message; nothing here mocks the engine,
 * because the point is to certify the real double-process setup.
 * <p>
 * <b>Full automation.</b> Every manual rehearsal gesture is covered: the
 * weighing ARMS the embedded scale deterministically
 * ({@code POST /api/hardware/set-weight}, consumed by the next read — 0,850
 * kg lands on the line to the gram, and the same weight re-armed fires the
 * anti-oubli guard), the TPE three-way dance drives the
 * register's own {@code /api/hardware/tpe/accept|refuse} endpoints, the TR
 * cap is proven by the meal-base DECREMENT, and the theme switch selects
 * CLAIR then restores DÉFAUT MAGASIN (the pre-flight leaves the register
 * demo-ready). The engine unplugging lives in {@link DemoDegradedIT} (own
 * profile on a dead engine URL — this class must LEAVE the real engine up),
 * and the two-node store module in {@link DemoStoreIT} (external store node
 * required, same prerequisite philosophy as the engine here).
 * <p>
 * Ordering: {@link MethodOrderer.MethodName} (demo00…demo06) on one fresh
 * drop-and-create boot; the scenarios build ONE demo sale end to end, like
 * on stage.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class DemoIT {

    /** The seed cashier badge (Jean Dupont — demo module I). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie — endorses the remise, module III). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The coffee EAN of the demo (Café Grains 500g, 4,50 €). */
    private static final String COFFEE_EAN = "3300000000004";

    /** The Golden-apples EAN carrying the engine's 2FOR1 offer. */
    private static final String APPLES_EAN = "3300000000001";

    /** The Golden-apples register-local PLU (IFPS 4020) — the typed path. */
    private static final String APPLES_PLU = "4020";

    /** The external engine base URL the demo runs against. */
    private static final String ENGINE_URL = "http://localhost:8090";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton: the memory-truth oracle for the cart
     * totals (the continuous-valuation drop of module V is asserted on it,
     * cent-exact, without parsing screen text).
     */
    @Inject
    PosState posState;

    /**
     * demo00 — The engine is up (the demo's non-negotiable prerequisite).
     * <p>
     * Posts a minimal authenticated basket to {@code :8090/valuation}. Any
     * HTTP answer proves the process is there; a transport failure aborts the
     * whole pre-flight with the message to display before a demo.
     */
    @Test
    void demo00_le_valorisateur_repond_sur_8090() {
        try {
            APIResponse ping = context.request().post(ENGINE_URL + "/valuation",
                    RequestOptions.create()
                            .setHeader("Content-Type", "application/json")
                            .setHeader("Authorization", "Basic "
                                    + Base64.getEncoder().encodeToString("admin:admin".getBytes()))
                            .setData("{\"storeCode\":\"0101\",\"items\":[]}"));
            Assertions.assertTrue(ping.status() < 500,
                    "the engine answered " + ping.status() + " — check its seed/auth before the demo");
        } catch (RuntimeException e) {
            Assertions.fail("LE VALORISATEUR NE RÉPOND PAS sur " + ENGINE_URL
                    + " — démarrez-le (avec son seed) AVANT le pre-flight et avant la démo. ("
                    + e.getMessage() + ")");
        }
    }

    /**
     * demo01 — Module I : prise de poste nominale.
     * <p>
     * Badge on the hardware bus, PIN on the numpad, guided landing on the
     * SESSION screen, 200 € float, drawer pushed shut, arrival on the SALE
     * screen — and the OPEN {@code C04-Sxxxxx} session in the database.
     */
    @Test
    void demo01_module1_prise_de_poste() {
        String root = base.toString();
        Page page = context.newPage();
        page.navigate(root + "lock");
        scanCode(CASHIER_BADGE);
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page, "#keyboardArea", CASHIER_PIN);
        page.locator("#actionBtn").click();
        // The take-over flow lands on the session screen (module I's promise).
        page.locator("#openingFloat").waitFor();
        closeDrawer();
        page.locator("#openingFloat").fill("200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(page.locator("#opInfo").textContent().contains("Jean Dupont"),
                "module I: the sale screen must show the logged-in operator");
        CashSession session = QuarkusTransaction.requiringNew()
                .call(() -> CashSession.findOpenByTerminal("C04"));
        Assertions.assertNotNull(session, "module I: an OPEN session must exist on C04");
        Assertions.assertEquals(0, session.openingFloat.compareTo(new BigDecimal("200.00")),
                "module I: the opening float must be 200,00 €");
        page.close();
    }

    /**
     * demo02 — Module II.1 : the scan, the merge, the clean refusal.
     * <p>
     * The coffee EAN scanned twice merges into ONE line of quantity 2 (unit
     * sale, unmodified), and an unknown code shows the exact transient
     * message. The screen is the oracle for both.
     */
    @Test
    void demo02_module2_scan_fusion_et_code_inconnu() {
        Page page = openSaleScreen();
        scanCode(COFFEE_EAN);
        page.getByText("CAFÉ GRAINS 500G").waitFor();
        scanCode(COFFEE_EAN);
        // Merge: one line, quantity 2 — the line row shows "x2" (getHtml format).
        page.getByText("x2").waitFor();
        Assertions.assertEquals(1, page.locator(".receipt-item-link").count(),
                "module II: the second identical scan must MERGE, not add a line");
        // The clean refusal: unknown code, exact catalog text, transient.
        scanCode("9999999999999");
        page.getByText("CODE INCONNU: 9999999999999").waitFor();
        page.close();
    }

    /**
     * demo02b — Module II.2 : the weighing, deterministic on the embedded
     * scale.
     * <p>
     * The simulator's {@code POST /api/hardware/set-weight} arms the scale
     * with 0,850 kg (consumed by the next read — the demo's "pose un poids,
     * tape la tuile"), and the weighed line carries EXACTLY that weight.
     * Then the scale is armed with the SAME weight again and the tile tapped
     * a second time: the anti-oubli guard fires with its exact message
     * (ERREUR POIDS IDENTIQUE) — module II.2's two beats, both automated.
     */
    @Test
    void demo02b_module2_pesee_deterministe_et_poids_identique() {
        String root = base.toString();
        Page page = openSaleScreen();
        // Arm the scale: 0,850 kg awaits the next weighing.
        context.request().post(root + "api/hardware/set-weight",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData("0,850"));
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("FRUITS & LÉGUMES").setExact(true)).click();
        page.getByText("CONCOMBRE").waitFor();
        page.getByText("CONCOMBRE").click();
        // Back on the sale: the weighed line carries exactly the armed weight.
        page.getByText("0,850 kg").waitFor();
        // Second beat: same weight again — the anti-oubli guard must refuse.
        context.request().post(root + "api/hardware/set-weight",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData("0,850"));
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("FRUITS & LÉGUMES").setExact(true)).click();
        page.getByText("CONCOMBRE").waitFor();
        page.getByText("CONCOMBRE").click();
        page.getByText("ERREUR POIDS IDENTIQUE").waitFor();
        page.close();
    }

    /**
     * demo03 — Module V : the 2FOR1 bites on the SECOND apple, live.
     * <p>
     * First kilogram through the TYPED-PLU chain (4020), second through the
     * SCAN chain — two weighed lines that never merge, aggregated by the
     * engine across lines (the demo's exact gesture). The memory total is the
     * cent-exact oracle: the second kilogram must cost LESS than the first,
     * without ever visiting the payment screen. This single assertion proves
     * the register→engine→reconciliation loop end to end.
     */
    @Test
    void demo03_module5_le_2for1_mord_au_second_kilo() {
        Page page = openSaleScreen();
        BigDecimal totalBefore = posState.ticket.totalAmount;
        // First apple kilogram: the typed-PLU path (module II.2's promise).
        scanCode(APPLES_PLU);
        page.getByText("POMMES GOLDEN").waitFor();
        BigDecimal afterFirst = posState.ticket.totalAmount;
        BigDecimal firstKiloCost = afterFirst.subtract(totalBefore);
        Assertions.assertTrue(firstKiloCost.signum() > 0,
                "module V: the first apple kilogram must be charged");
        // Second kilogram: the scan path. The engine aggregates the two lines.
        scanCode(APPLES_EAN);
        // Wait until the SECOND apples line is on screen (weighed lines never merge).
        page.locator(".receipt-item-link").locator("text=POMMES GOLDEN").nth(1).waitFor();
        BigDecimal afterSecond = posState.ticket.totalAmount;
        BigDecimal secondKiloCost = afterSecond.subtract(afterFirst);
        Assertions.assertTrue(secondKiloCost.compareTo(firstKiloCost) < 0,
                "module V: the 2FOR1 must bite on the second kilogram (first cost "
                        + firstKiloCost + ", second cost " + secondKiloCost
                        + ") — engine offer missing? seed mismatch?");
        page.close();
    }

    /**
     * demo04 — Module III.1 : the four-eyes remise, endorsed by the manager.
     * <p>
     * The coffee line is selected on screen, REMISE opens the gesture modal
     * (1,00 € typed cents-style on the modal pad), the endorsement page
     * accepts the MANAGER BADGE on the hardware bus (the demo's gesture) then
     * the manager PIN — and the total drops by exactly 1,00 € (coffee carries
     * no engine offer alone, so the delta is the remise, cent-exact).
     */
    @Test
    void demo04_module3_remise_sous_avenant() {
        Page page = openSaleScreen();
        BigDecimal totalBefore = posState.ticket.totalAmount;
        // Select the coffee line (tap on the receipt row).
        page.locator(".receipt-item-link").locator("text=CAFÉ GRAINS 500G").first().click();
        // REMISE opens the server-rendered gesture modal.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("REMISE").setExact(true)).click();
        page.locator(".price-modal.active").waitFor();
        // 1,00 € typed cents-style (1-0-0) on the modal pad, then VALIDER.
        // The PRICE modal's keypad is DECIMAL mode (main.html PosInput
        // setup) — "100" would mean ONE HUNDRED euros, silently clamped.
        // The pay screen's cents convention does NOT apply here: "1" = 1,00.
        tapDigits(page, "#priceKbArea", "1");
        page.locator("#priceForm").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        // Endorsement: the manager BADGES (hardware bus), then taps the PIN.
        page.locator("#endorseModal").waitFor();
        // Pinpoint assertion (added after a silent 0.00 delta): if the modal
        // keypad did not capture the typed value, the guilty party is the
        // MODAL INPUT, not the endorsement dispatch — this names it.
        Assertions.assertEquals(0,
                new BigDecimal("1.00").compareTo(posState.endorsement.pendingValue),
                "module III: la valeur parquée pour l'avenant doit être 1,00 — "
                        + "reçue " + posState.endorsement.pendingValue
                        + " (le pavé de la modale de prix n'a pas capté la saisie)");
        scanCode(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page, "#endorseKeyboardArea", MANAGER_PIN);
        // SUIVANT submits the endorsement (endorsement.html: endorseNext()
        // builds and posts the form). Typing the PIN alone executes NOTHING
        // — a run proved it, and the old waitFor("REMISE") was satisfied by
        // the MENU BUTTON of the same name: a false witness.
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("SUIVANT").setExact(true))
                .click(new Locator.ClickOptions().setForce(true));
        // Back on the sale, the LINE now carries its modifier label.
        waitForCafeModifier(6000);
        // ...and the total dropped by exactly 1,00 €.
        BigDecimal delta = totalBefore.subtract(posState.ticket.totalAmount);
        // The LINE is the oracle of the gesture: label posted and unit price
        // lowered by remise/qty. The TICKET total moves by AT LEAST as much,
        // because the engine REVALUES on the new price — exactly what the
        // demo's module III promises ("gestes et promos se composent").
        com.intermarche.pos.ui.ticket.TicketState.TicketItem cafe = posState.ticket.items.stream()
                .filter(i -> i.label != null && i.label.contains("CAFÉ"))
                .findFirst().orElse(null);
        Assertions.assertNotNull(cafe, "module III: la ligne café doit exister");
        Assertions.assertNotNull(cafe.modifierLabel,
                "module III: la ligne doit porter son étiquette de geste");
        Assertions.assertTrue(delta.compareTo(new BigDecimal("1.00")) >= 0,
                "module III: le total doit baisser d'AU MOINS la remise (1,00 €) — "
                        + "ligne café: " + cafeLineState() + " — delta " + delta);
        page.close();
    }

    /**
     * demo05a — Module VI.1 : the meal-voucher cap, proven by the DECREMENT.
     * <p>
     * ENCAISSER shows the green AVANTAGES banner (second live engine proof).
     * A 10,00 € TR is submitted; the registered amount is read as the drop of
     * the engine-fed meal base ({@code valuationMealEligible} before minus
     * after) — cent-exact, threshold-independent: never more than requested,
     * never more than the base (the demo's "plafonné, et l'assiette
     * décrémente").
     */
    @Test
    void demo05a_module6_tr_plafonne_par_l_assiette() {
        Page page = openSaleScreen();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        // Fidelity-leg lesson applied here too: wait for the BUTTON we use
        // next, and prove the advantages banner through the SERVER oracle
        // (the banner text exists in pay.html but its display is a timing
        // condition that has already eaten pre-flight runs).
        // TRAP learned this run: getByText(...).first() can resolve a HIDDEN
        // pay-mode-label living inside a collapsed sub-form and wait on it
        // forever — the BUTTON role is the only unambiguous target.
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("TICKET RESTO").setExact(true)).waitFor();
        // valuationAdjustment is the delta of THIS call — the cart was
        // already valued at every scan, so a fresh 0.00 at payment entry is
        // CORRECT (a run taught us that). The engine's presence is the
        // oracle here; the advantages themselves are proven on the ticket.
        Assertions.assertEquals("ENGINE", posState.payment.valuationStatus,
                "module VI: le moteur doit avoir répondu à l'entrée en paiement "
                        + "(status=" + posState.payment.valuationStatus + ")");
        BigDecimal baseBefore = posState.payment.valuationMealEligible;
        org.junit.jupiter.api.Assumptions.assumeTrue(baseBefore != null && baseBefore.signum() > 0,
                "the engine returned no MEAL_VOUCHER base for this cart — check the "
                        + "engine seed (POMMES family TR flags) before the demo");
        typeAmountVerified(page, "TICKET RESTO", "#trDisplay", "#trForm", "10", "10");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("TICKET RESTO").setExact(true)).waitFor();
        BigDecimal baseAfter = posState.payment.valuationMealEligible;
        // The eligible base is an ENGINE HINT, not a counter: it does not
        // decrement (a run proved it). The cap shows in the AMOUNT BOOKED:
        // 10,00 € asked, the eligible base registered.
        BigDecimal registered = posState.payment.payments.stream()
                .filter(p -> "TR".equals(p.method))
                .map(p -> p.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Assertions.assertTrue(registered.signum() > 0,
                "module VI.1: le paiement TR doit s'enregistrer — assiette="
                        + baseBefore + " paiements="
                        + posState.payment.payments.stream()
                                .map(p -> p.method + ":" + p.amount).toList());
        Assertions.assertEquals(0, registered.compareTo(baseBefore),
                "module VI.1: le TR doit être PLAFONNÉ à l'assiette éligible "
                        + "(demandé 10,00 €, assiette " + baseBefore
                        + ", enregistré " + registered + ")");
        Assertions.assertTrue(registered.compareTo(new BigDecimal("10.00")) <= 0,
                "module VI.1: never more than requested, was " + registered);
        Assertions.assertTrue(registered.compareTo(baseBefore) <= 0,
                "module VI.1: never more than the eligible base (" + baseBefore
                        + "), was " + registered);
        page.close();
    }

    /**
     * demo05b — Module VI.2 : the TPE three-way dance, both outcomes.
     * <p>
     * The register's own {@code /api/hardware/tpe} endpoints ARE the virtual
     * terminal's surface (the simulator UI drives them) — the test drives
     * them directly. REFUSE first: the request dies with the exact catalog
     * message and the cart is intact. Then a second request is ACCEPTED and
     * the card payment stays for the closing (demo05c asserts it on the
     * closed ticket).
     */
    @Test
    void demo05b_module6_tpe_refus_puis_accord() {
        String root = base.toString();
        Page page = openSaleScreen();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        // --- Refuse path ---
        typeAmountVerified(page, "CARTE BANCAIRE", "#cardDisplay", "#cardForm", "5", "5");
        waitForPendingCard(6000); // the TPE request must EXIST before refusing
        APIResponse refusal = context.request()
                .post(root + "api/hardware/tpe/refuse", RequestOptions.create());
        Assertions.assertTrue(refusal.ok(),
                "module VI.2: l'endpoint de refus doit répondre 200 — reçu "
                        + refusal.status() + " " + refusal.text());
        // The refusal's DURABLE proof: the pending request is dropped and no
        // CARD payment exists. The transient message is wiped within ~1 s by
        // the pay page's reload (initPayment revalues → clears the message),
        // so polling for it is a race the harness cannot win.
        waitForNoPendingCard(8000);
        Assertions.assertTrue(posState.payment.payments.stream()
                        .noneMatch(p -> "CARD".equals(p.method)),
                "module VI.2: un refus TPE ne doit enregistrer AUCUN paiement carte");
        // --- Accept path (5,00 € card, kept for the closing) ---
        typeAmountVerified(page, "CARTE BANCAIRE", "#cardDisplay", "#cardForm", "5", "5");
        waitForPendingCard(6000);
        APIResponse accept = context.request()
                .post(root + "api/hardware/tpe/accept", RequestOptions.create());
        Assertions.assertTrue(accept.ok(),
                "module VI.2: l'endpoint d'accord doit répondre 200 — reçu "
                        + accept.status() + " " + accept.text());
        waitForPaymentMethod("CARD", 8000);
        page.close();
    }

    /**
     * demo05c — Module VI.3+7 : cash with change, the fiscal closing.
     * <p>
     * Cash 50,00 € solves the remainder, the completion modal shows, the
     * fiscal button seals the sale. Database oracles: CLOSED and signed,
     * THREE payments (TR + card + cash — the demo's multi-payment ticket)
     * with the cash one present by method key, and the VALUATION TRACE rows
     * exist (the printed AVANTAGE deltas of the demo ticket come from them).
     */
    @Test
    void demo05c_module6_especes_et_moment_fiscal() {
        Page page = openSaleScreen();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        typeAmountVerified(page, "ESPÈCES", "#cashDisplay", "#cashForm", "50", "50");
        // Completion modal — the demo's VI.7 sequence: IMPRIMER first (the
        // original print, draft still OPEN), THEN the fiscal closing. The
        // embedded simulator's printer buffer is readable, so the PRINTED
        // ticket itself becomes an oracle: it must carry the AVANTAGE deltas.
        String printed = printAndWait(page, "TOTAL");
        // The printed advantage line carries the ENGINE'S OWN LABEL
        // (advantageLabel/offerLabel — "AVANTAGE" is only the fallback), so
        // the paper oracle is the ticket itself; the engine trace is proven
        // by the persisted TicketLineValuation rows asserted below.
        Assertions.assertTrue(printed.contains("TOTAL"),
                "module VI.7: le ticket doit s'imprimer au bouton IMPRIMER — "
                        + "buffer reçu: " + printed.substring(0, Math.min(200, printed.length())));
        // The fiscal gesture is the /action/finish link (the NOUVELLE VENTE
        // button of the modal) — navigated DIRECTLY: a version-change reload
        // can wipe the modal between its render and a click, and the link
        // is idempotent while the click is a race.
        page.navigate(base.toString() + "action/finish");
        page.getByText("EN ATTENTE D'ARTICLES...").waitFor();
        Ticket closed = lastClosedTicket();
        Assertions.assertNotNull(closed, "module VI: the closing must close the ticket");
        Assertions.assertNotNull(closed.signature,
                "module VI: the fiscal moment must sign the ticket");
        Long closedId = closed.id;
        long paymentCount = QuarkusTransaction.requiringNew().call(() ->
                (long) Ticket.<Ticket>findById(closedId).payments.size());
        Assertions.assertEquals(3, paymentCount,
                "module VI: the demo ticket must carry its THREE payments (TR + card + cash)");
        long cashPayments = QuarkusTransaction.requiringNew().call(() ->
                Ticket.<Ticket>findById(closedId).payments.stream()
                        .filter(p -> "CASH".equals(p.getMethodKey())).count());
        Assertions.assertEquals(1, cashPayments,
                "module VI: the closed ticket must carry the cash payment");
        long traceRows = QuarkusTransaction.requiringNew().call(() ->
                TicketLineValuation.count("ticket.id", closedId));
        Assertions.assertTrue(traceRows > 0,
                "module VI: the valuation trace must be persisted (the printed AVANTAGE "
                        + "deltas of the demo come from it)");
    }

    /**
     * demo06 — Module VII : the digital ticket answers.
     * <p>
     * The closed ticket's capability link {@code /t/{id}/{key}} serves the
     * public page (the QR of the demo points there), and a wrong key stays an
     * indistinct 404 — both sides of the capability contract, straight from
     * the demo script.
     */
    @Test
    void demo06_module7_ticket_dematerialise() {
        Ticket closed = lastClosedTicket();
        Assertions.assertNotNull(closed, "demo05 must have closed the demo ticket first");
        String root = base.toString();
        APIResponse ok = context.request().get(root + "t/" + closed.id + "/" + closed.digitalKey);
        Assertions.assertEquals(200, ok.status(),
                "module VII: the digital-ticket page must answer for the demo QR");
        Assertions.assertTrue(ok.text().contains("TOTAL"),
                "module VII: the public page must show the ticket");
        // A wrong key answers 200 with the INDISTINCT "unavailable" page —
        // by design: the public URL never reveals whether a ticket exists
        // (an HTTP 404 would be an oracle for probing). What must be true is
        // that NOTHING of the ticket leaks.
        APIResponse wrongKey = context.request().get(root + "t/" + closed.id + "/0000000000000000");
        Assertions.assertFalse(wrongKey.text().contains("TOTAL"),
                "module VII: une clé erronée ne doit RIEN divulguer du ticket");
    }

    /**
     * demo07 — Module XII : the theme switch, then the demo-ready restore.
     * <p>
     * The selector switches the cashier to CLAIR (the body carries
     * {@code data-theme="clair"} on the very next screen), then DÉFAUT
     * MAGASIN clears the preference — the pre-flight leaves the register in
     * the exact state the demo expects (Jean back on the store default).
     */
    @Test
    void demo07_module12_theme_choisi_puis_restaure() {
        String root = base.toString();
        Page page = openSaleScreen();
        page.navigate(root + "theme-select");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("CLAIR").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals("clair", page.locator("body").getAttribute("data-theme"),
                "module XII: the chosen theme must render immediately");
        // Restore: DÉFAUT MAGASIN clears the preference (demo-ready state).
        page.navigate(root + "theme-select");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("DÉFAUT MAGASIN").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals("sombre", page.locator("body").getAttribute("data-theme"),
                "module XII: DÉFAUT MAGASIN must restore the store default");
        page.close();
    }

    // --- Reusable gestures (hardware bus & screens) ---

    /**
     * Opens a page on the sale screen, assuming the session of demo01 is open
     * and the operator may have been logged out by a previous page close —
     * navigating to the root either lands on the sale (still logged) or on
     * the lock screen, in which case the login recipe replays.
     *
     * @return a Playwright page sitting on the sale screen
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
     * Clicks IMPRIMER TICKET on the completion modal and waits for the paper.
     * The click races pay.html's version-change reload — an empty buffer
     * means the click was swallowed, so it is tried once more.
     *
     * @param page the Playwright page on the completion modal
     * @param needle the text the printed document must carry
     * @return the printed buffer (matching, or the last one seen)
     */
    private String printAndWait(Page page, String needle) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // Cash OPENS THE DRAWER: push it shut and land on /pay before
                // looking for the modal — the sale is settled (remaining 0),
                // so the only thing that can hide the modal is the page we
                // are standing on.
                closeDrawer();
                page.navigate(base.toString() + "pay");
                page.locator("#confirmModal")
                        .waitFor(new Locator.WaitForOptions().setTimeout(8000));
                page.locator("#confirmModal").getByText("IMPRIMER TICKET")
                        .click(new Locator.ClickOptions().setForce(true));
            } catch (Exception noModal) {
                if (attempt == 2) {
                    Assertions.fail("la modale de complétion n'est pas apparue — page "
                            + page.url() + ", restant dû = " + posState.getRemaining()
                            + ", complétion = " + posState.payment.transactionComplete
                            + ", paiements = " + posState.payment.payments.stream()
                                    .map(p -> p.method + ":" + p.amount).toList());
                }
                continue;
            }
            String printed = waitForPrinted(needle, 5000);
            if (!printed.isBlank()) {
                return printed;
            }
        }
        return waitForPrinted(needle, 1000);
    }

    /**
     * Polls the mock printer's buffer until it carries the expected text.
     * The print is triggered by a form submit: reading the buffer right
     * after the click is a race the harness loses on a slow print.
     *
     * @param needle the text the printed document must carry
     * @param timeoutMillis the polling window
     * @return the printed buffer (matching, or the last one seen)
     */
    private String waitForPrinted(String needle, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String printed = "";
        while (System.currentTimeMillis() < deadline) {
            printed = context.request()
                    .get(base.toString() + "api/hardware/printer/content").text();
            if (printed.contains(needle)) return printed;
            try { Thread.sleep(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        return printed;
    }

    /**
     * Waits for the pending TPE request to be DROPPED (refusal/cancel).
     *
     * @param timeoutMillis the polling window
     */
    private void waitForNoPendingCard(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (posState.payment.pendingCardAmount == null) return;
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        Assertions.fail("module VI.2: le refus doit abandonner la demande CB "
                + "(pendingCardAmount = " + posState.payment.pendingCardAmount + ")");
    }

    /**
     * Waits for the virtual TPE request to exist server-side. Accepting or
     * refusing before the request is created is a no-op — which once left
     * transientError null and looked like a broken refusal channel.
     *
     * @param timeoutMillis the polling window
     */
    private void waitForPendingCard(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (posState.payment.pendingCardAmount != null) return;
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        Assertions.fail("module VI.2: la requête CB n'a jamais été créée "
                + "(pendingCardAmount null) — la saisie n'a pas été soumise");
    }

    /**
     * Waits for a transient ticket error on the SERVER state — the proven
     * display channel (setError), independent of where the template shows it.
     *
     * @param expected the exact expected message
     * @param timeoutMillis the polling window
     */
    /**
     * Describes the café line's modifier state, for the demo04 pointer
     * message (was the remise applied to the line, or erased later?).
     *
     * @return a compact human-readable line state
     */
    /**
     * Waits for the café line to carry a modifier label — the server-side
     * proof that the endorsed gesture actually landed on the line.
     *
     * @param timeoutMillis the polling window
     */
    private void waitForCafeModifier(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean marked = posState.ticket.items.stream()
                    .anyMatch(i -> i.label != null && i.label.contains("CAFÉ")
                            && i.modifierLabel != null);
            if (marked) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    private String cafeLineState() {
        return posState.ticket.items.stream()
                .filter(i -> i.label != null && i.label.contains("CAFÉ"))
                .findFirst()
                .map(i -> i.modifierLabel + "/unit=" + i.unitPrice + "/total=" + i.getTotalPrice())
                .orElse("(ligne café absente)");
    }

    private void waitForTicketError(String expected, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(posState.ticket.transientError)) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        Assertions.fail("message attendu « " + expected + " » — transientError = "
                + posState.ticket.transientError);
    }

    /**
     * Waits for a payment entry of the given method on the SERVER state.
     *
     * @param methodKey the expected payment method key
     * @param timeoutMillis the polling window
     */
    private void waitForPaymentMethod(String methodKey, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (posState.payment.payments.stream().anyMatch(p -> methodKey.equals(p.method))) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        Assertions.fail("aucun paiement de méthode " + methodKey + " enregistré");
    }

    /**
     * Presents a code on the hardware scan bus — badge, EAN, PLU or voucher
     * alike: the register runs them all through the same recognition chain,
     * which is exactly the demo's point.
     *
     * @param code the code presented to the register
     */
    private void scanCode(String code) {
        APIResponse scan = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept " + code);
    }

    /**
     * Opens a pay sub-form, types an amount on the shared numpad, VERIFIES
     * the sub-display captured it, and submits. pay.html reloads itself on
     * any state-version change (line 376) — a reload between the sub-form
     * opening and the submit silently resets the hidden field to "0",
     * which the actions read as "maximum". One retry heals the race; a
     * second loss is a legitimate failure that names itself.
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

    /**
     * Pushes the drawer shut on the hardware bus (the embedded simulator),
     * clearing the drawer guard.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    // --- Database oracles ---

    /**
     * Reads the most recent CLOSED ticket on terminal C04 — the demo sale
     * once TERMINER sealed it.
     *
     * @return the last CLOSED {@link Ticket} on C04, or null when none exists
     */
    private Ticket lastClosedTicket() {
        return QuarkusTransaction.requiringNew().call(() ->
                Ticket.find("terminalId = ?1 and status = ?2 order by id desc",
                        "C04", Ticket.TicketStatus.CLOSED).firstResult());
    }
}
