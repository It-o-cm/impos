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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.net.URL;
import java.util.Base64;
import java.util.Map;

/**
 * PRE-FLIGHT of the demo's LOYALTY leg (scenario-demo-complet.pdf — modules
 * IV « la cagnotte en direct », VI.4bis CAGNOTTE payment, and the printed
 * CAGNOTTE DU JOUR section of module VII): replays the fidelity path through
 * the browser against the real application, the REAL engine on :8090 AND the
 * REAL imfid on :8060 — the demo's triple-process setup, certified.
 * <p>
 * <b>Prerequisites (deliberate, same philosophy as {@link DemoIT} for the
 * engine and {@link DemoStoreIT} for the store node).</b> The engine is
 * REQUIRED ({@code fid00} aborts with the message to display). imfid is an
 * ASSUMPTION: absent, the whole class SKIPS — the fidelity module is an
 * option of the demo script, its absence must not redden a 30'/60'
 * pre-flight. The orchestrator ({@code demo-stack.sh}) starts both, so an
 * orchestrated run always exercises this class.
 * <p>
 * <b>Idempotence note.</b> The burn consumes imfid's daily rule for the card
 * — a second run the same fiscal day against the SAME imfid instance would
 * refuse it. The orchestrator restarts imfid (dev drop-and-create) before
 * each pre-flight, which resets that state; standalone reruns should restart
 * imfid too (the demo script's DAILY_RULE plan-B documents the same trap for
 * the human rehearsal).
 */
@QuarkusTest
@TestProfile(DemoFidIT.FidProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class DemoFidIT {

    /** The seed cashier badge (Jean Dupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The Golden-apples EAN carrying the engine's 2FOR1 offer (valued cart). */
    private static final String APPLES_EAN = "3300000000001";

    /** The branded coffee EAN — imfid's everyday-brand socle rules hit brands. */
    private static final String COFFEE_EAN = "3300000000004";

    /** The branded biscuits EAN (bundle mate of the coffee). */
    private static final String BISCUITS_EAN = "3300000000013";

    /** The imfid demo card (ACTIVE, balance 56,70 € — the demo's card). */
    private static final String FID_CARD = "2990000000019";

    /** The external imfid base URL the demo runs against. */
    private static final String IMFID_URL = "http://localhost:8060";

    /** The imfid POS machine account (dev bootstrap). */
    private static final String IMFID_BASIC = "Basic "
            + Base64.getEncoder().encodeToString("pos:pos-password".getBytes());

    /** The Playwright browser context injected by the extension. */
    @InjectPlaywright
    BrowserContext context;

    /** imfid availability, probed once for the whole class run. */
    private static Boolean imfidUp;

    /**
     * Skips the WHOLE fidelity leg when imfid is absent: this class is
     * stack-attached (demo-stack starts imfid before the pre-flight), so an
     * autonomous run reports every scenario SKIPPED — never failed. The
     * probe runs once and is cached for the class.
     */
    @BeforeEach
    void assumeImfidUp() {
        if (imfidUp == null) {
            try {
                APIResponse health = context.request().get(IMFID_URL + "/q/health");
                imfidUp = health.status() == 200;
            } catch (Exception e) {
                imfidUp = false;
            }
        }
        Assumptions.assumeTrue(imfidUp,
                "imfid ne répond pas sur " + IMFID_URL + " — la jambe fidélité du "
                        + "pre-flight est SAUTÉE. Pour la jouer : démarrer imfid "
                        + "(quarkus:dev, port 8060) ou utiliser demo-stack.sh.");
    }

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton — the memory-truth oracle. */
    @Inject
    PosState posState;

    /** Movements count on the card BEFORE fid03's sale (hardened oracle). */
    private static Integer movementsBaseline;

    /**
     * The fidelity profile: everything of {@link E2eTestProfile} plus the
     * loyalty wiring of the demo — imfid URL, POS machine account, and the
     * spec's card discriminant (299 + 10 digits).
     */
    public static class FidProfile extends E2eTestProfile implements QuarkusTestProfile {
        /**
         * Adds the loyalty configuration on top of the shared e2e profile.
         *
         * @return the merged configuration overrides
         */
        @Override
        public Map<String, String> getConfigOverrides() {
            java.util.Map<String, String> config =
                    new java.util.HashMap<>(super.getConfigOverrides());
            // Real engine: this demo class runs under the full stack.
            config.put("pos.valuation.url", "http://localhost:8090");
            config.put("pos.fid.url", IMFID_URL);
            config.put("pos.fid.user", "pos");
            config.put("pos.fid.password", "pos-password");
            config.put("scan.pattern.fidelity", "^299\\d{10}$");
            return config;
        }
    }

    /**
     * fid00 — imfid answers on :8060, or the whole class SKIPS.
     * <p>
     * The health endpoint is public by spec (§2.2). A skip is the correct
     * verdict for a demo without the fidelity option; the failure message
     * still says how to get it.
     */
    @Test
    void fid00_imfid_repond_sur_8060_sinon_skip() {
        boolean up;
        try {
            APIResponse health = context.request().get(IMFID_URL + "/q/health");
            up = health.status() == 200;
        } catch (Exception e) {
            up = false;
        }
        Assumptions.assumeTrue(up,
                "imfid ne répond pas sur " + IMFID_URL + " — la jambe fidélité du "
                        + "pre-flight est SAUTÉE. Pour la jouer : démarrer imfid "
                        + "(quarkus:dev, port 8060) ou utiliser demo-stack.sh.");
    }

    /**
     * fid01 — INVARIANT I2 (imfid): a basket ENTIRELY absorbed by an offer
     * earns ZERO — and that zero is a GREEN, not a defect.
     * <p>
     * Coffee + biscuits alone form the engine's bundle: every item is
     * consumed by the offer, imfid's socle assiette is empty by design, the
     * projection answers 0. The assertion pins the invariant: zero HERE is
     * the correct figure (a positive earn on this cart would mean the
     * exclusion broke).
     */
    @Test
    void fid01_invariant_I2_panier_absorbe_par_le_bundle() {
        Page page = openSaleScreen();
        scanCode(COFFEE_EAN);
        scanCode(BISCUITS_EAN);
        scanCode(FID_CARD);
        BigDecimal bundleEarn = waitForEarn(8000);
        Assertions.assertNotNull(bundleEarn,
                "invariant I2: la projection doit RÉPONDRE (le tuyau caisse→imfid "
                        + "est en cause : auth, couple, pattern)");
        Assertions.assertEquals(0, bundleEarn.signum(),
                "invariant I2: un panier entièrement absorbé par le bundle doit "
                        + "projeter un earn NUL (l'exclusion des articles sous offre "
                        + "a cassé si ce montant est positif)");
        page.close();
    }

    /**
     * fid01b — Module IV : the SOCLE basket earns, alongside the bundle.
     * <p>
     * Milk + water + yogurt (three socle units OUTSIDE any offer — imfid's
     * exit criterion) join the bundle cart of fid01: the projection must
     * turn POSITIVE, proving both that the socle rules fire on the mirror
     * EANs and that I2's exclusion is PER-ITEM, not per-ticket (the bundle
     * items still earn nothing, the socle items do). The EANs are resolved
     * from the register's own referential by name — never hard-coded from
     * memory.
     */
    @Test
    void fid01b_module4_le_panier_socle_cagnotte() {
        Page page = openSaleScreen();
        scanCode(resolveEanByNamePrefix("lait"));
        scanCode(resolveEanByNamePrefix("eau"));
        scanCode(resolveEanByNamePrefix("yaourt"));
        BigDecimal socleEarn = waitForEarn(8000);
        Assertions.assertNotNull(socleEarn,
                "module IV: la projection doit répondre sur le panier socle");
        Assertions.assertTrue(socleEarn.signum() > 0,
                "module IV: earn POSITIF attendu sur lait+eau+yaourt (3 unités "
                        + "socle hors offres — le critère de sortie du seed imfid) ; "
                        + "un zéro ici = les règles socle ne mordent pas les EAN miroirs");
        page.close();
    }

    /**
     * Resolves a product EAN from the register's own referential by name
     * prefix (case-insensitive). Skips the class with an actionable message
     * when the product is absent — a seed drift must name itself.
     *
     * @param namePrefix the lowercase product-name prefix (e.g. "lait")
     * @return the product's EAN
     */
    private String resolveEanByNamePrefix(String namePrefix) {
        final String[] ean = new String[1];
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            com.intermarche.pos.domain.catalog.Product product =
                    com.intermarche.pos.domain.catalog.Product
                            .<com.intermarche.pos.domain.catalog.Product>find(
                                    "lower(name) like ?1", namePrefix + "%")
                            .firstResult();
            if (product != null) {
                ean[0] = product.ean;
            }
        });
        Assumptions.assumeTrue(ean[0] != null,
                "produit socle « " + namePrefix + "… » absent du référentiel caisse — "
                        + "aligner les seeds (le critère imfid attend lait/eau/yaourt)");
        return ean[0];
    }

    /**
     * fid02 — Module IV : the in-store consultation shows the account.
     * <p>
     * The /fidelity page must render the consultation panel: status, balance
     * and the AVAILABLE figure (the one that caps a payment).
     */
    @Test
    void fid02_module4_consultation_en_caisse() {
        Page page = context.newPage();
        page.navigate(base.toString() + "fidelity");
        page.getByText("DISPONIBLE").first().waitFor();
        Assertions.assertTrue(page.content().contains("STATUT"),
                "module IV: le panneau de consultation doit afficher le statut du compte");
        page.close();
    }

    /**
     * fid03 — Modules VI.4bis + VII : the CAGNOTTE payment and its printed
     * trace.
     * <p>
     * CAGNOTTE 1,00 € — the lease must be granted (reservation id on the
     * payment state, amount registered) — then cash settles, IMPRIMER proves
     * the CAGNOTTE DU JOUR section on paper, and NOUVELLE VENTE seals the
     * sale (the confirm + ticket-closed enqueue path runs).
     */
    @Test
    void fid03_module6_paiement_cagnotte_et_ticket() {
        Page page = openSaleScreen();
        // SOCLE basket (imfid's exit criterion): the printed CAGNOTTE DU JOUR
        // section only exists when an earn exists — a bundle-absorbed cart
        // projects a legitimate zero (invariant I2) and prints nothing.
        scanCode(resolveEanByNamePrefix("lait"));
        scanCode(resolveEanByNamePrefix("eau"));
        scanCode(resolveEanByNamePrefix("yaourt"));
        scanCode(APPLES_EAN);
        scanCode(FID_CARD);
        Assertions.assertNotNull(waitForEarn(8000),
                "module VI.4bis: la projection doit précéder le paiement (burnableBase)");
        movementsBaseline = readMovementsCount();
        // The sale screen was rendered with an EMPTY cart: the ENCAISSER
        // link only exists in a server render that SEES the cart — reload
        // first (the demo's own "F5 sans crainte" doctrine).
        page.reload();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ENCAISSER").setExact(true)).click();
        // Wait for the pay screen through the thing we actually use next —
        // never through a banner text no green run has ever validated.
        typeAmountVerified(page, "CAGNOTTE", "#fidDisplay", "#fidForm", "1", "1");
        Assertions.assertNotNull(posState.payment.fidReservationId,
                "module VI.4bis: le bail imfid doit être posé (reservationId)");
        typeAmountVerified(page, "ESPÈCES", "#cashDisplay", "#cashForm", "50", "50");
        String printed = printAndWait(page, "CAGNOTTE DU JOUR");
        Assertions.assertTrue(printed.contains("CAGNOTTE DU JOUR"),
                "module VII: le ticket imprimé doit porter la section CAGNOTTE DU JOUR "
                        + "(earn projeté = " + posState.fidelity.earnTotal
                        + ", ticket courant = " + posState.payment.ticketDbId
                        + ", dernier clos = " + posState.lastClosedTicketId
                        + ") — buffer: "
                        + printed.substring(0, Math.min(400, printed.length())));
        // Direct /action/finish — the modal click races pay.html's reload.
        page.navigate(base.toString() + "action/finish");
        page.close();
    }

    /**
     * fid04 — The outbox proof: the real credit lands on the card.
     * <p>
     * Polls imfid's movements for the demo card (POS machine account) until
     * an EARN and the BURN of fid03 both appear — within the 35 s window
     * that covers the 10 s outbox drain plus imfid's ingestion. This is the
     * end-to-end certificate: caisse → outbox → ingestion → fiche carte.
     */
    @Test
    void fid04_l_outbox_credite_la_fiche_carte() throws InterruptedException {
        // Hardened oracle: the card sheet may already carry movements from
        // earlier manual runs — the proof is that NEW ones land AFTER fid03
        // (count strictly above the captured baseline), and that EARN and
        // BURN are both present.
        long deadline = System.currentTimeMillis() + 35000;
        boolean grew = false;
        boolean earnSeen = false;
        boolean burnSeen = false;
        while (System.currentTimeMillis() < deadline && !(grew && earnSeen && burnSeen)) {
            String body = readMovementsBody();
            if (body != null) {
                Integer count = extractTotalCount(body);
                grew = movementsBaseline == null
                        || (count != null && count > movementsBaseline);
                earnSeen = body.contains("\"EARN\"");
                burnSeen = body.contains("\"BURN\"");
            }
            if (!(grew && earnSeen && burnSeen)) {
                Thread.sleep(2000);
            }
        }
        Assertions.assertTrue(grew,
                "l'outbox doit produire de NOUVEAUX mouvements après la vente de fid03 "
                        + "(totalCount > " + movementsBaseline + ", fenêtre 35 s). "
                        + "CAUSE PROBABLE si imfid n'a pas été redémarré : la caisse de "
                        + "test repart de C04-000001 à chaque boot, son ticketRef "
                        + "(année-numéro) a DÉJÀ été ingéré lors d'un run précédent et "
                        + "l'idempotence d'imfid absorbe l'événement — redémarrer imfid "
                        + "(le demo-stack le fait quand c'est LUI qui l'a lancé).");
        Assertions.assertTrue(earnSeen,
                "l'événement ticket-closed doit produire le mouvement EARN sur la fiche carte");
        Assertions.assertTrue(burnSeen,
                "le bail confirmé doit produire le mouvement BURN sur la fiche carte");
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
     * Reads the card's movements page body, or null when unreachable.
     *
     * @return the raw JSON body, or null
     */
    private String readMovementsBody() {
        try {
            APIResponse movements = context.request().get(
                    IMFID_URL + "/api/accounts/" + FID_CARD + "/movements?page=0&size=50",
                    RequestOptions.create().setHeader("Authorization", IMFID_BASIC));
            return movements.status() == 200 ? movements.text() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the card's current movements count, or null when unreachable.
     *
     * @return the totalCount figure, or null
     */
    private Integer readMovementsCount() {
        return extractTotalCount(readMovementsBody());
    }

    /**
     * Extracts the totalCount figure from a movements body.
     *
     * @param body the raw JSON body, or null
     * @return the count, or null
     */
    private Integer extractTotalCount(String body) {
        if (body == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"totalCount\"\\s*:\\s*(\\d+)").matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
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
     * Opens a ready sale screen: badge on the bus, PIN, session floor when
     * asked, drawer pushed shut — the demo's module I recipe.
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
     * Waits for the earn projection to land on the server-side state.
     *
     * @param timeoutMillis the polling window
     * @return the earn total, or null when the window elapsed empty
     */
    private BigDecimal waitForEarn(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (posState.fidelity.earnTotal != null) {
                return posState.fidelity.earnTotal;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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
