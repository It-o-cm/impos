package com.intermarche.e2e;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.PosSettingsService;
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

/**
 * Group Q end-to-end scenarios (Mouvements de caisse — cash movements), played
 * THROUGH THE SCREEN with a headless Chromium browser against the real
 * application (live H2, real beans, embedded hardware simulator). Group Q is
 * {@code [S]} (caisse seule + simulateur).
 * <p>
 * <b>The three cash-drawer surfaces.</b> A generic movement is recorded on
 * {@code /cash-movement}; a manual withdrawal on {@code /withdrawal}; an
 * inter-tender transfer on {@code /transfer}. Each screen posts a form and
 * redirects to itself with an {@code ?ok=1} success banner or an {@code ?error=}
 * refusal banner; above the administered threshold a movement is parked into the
 * shared manager endorsement modal. The recorded fact is read back from the
 * database ({@link CashMovement}); the endorsement lifecycle from
 * {@code posState.endorsement}.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (q1…q3) and share ONE cash session opened
 * once (its 200 € opening float is the cash theoretical the transfer draws on);
 * each restores any administered flag it toggles.
 * <p>
 * <b>Residue.</b> A successful CASH withdrawal needs a per-denomination count
 * payload (the withdrawal screen's coin/bill counter) — only the withdrawal
 * GUARDS are played here; the denomination-count success is reported as residue.
 * The {@code AVAL MANAGER REFUSÉ OU MANQUANT} screen string is a defensive
 * server guard unreachable through the modal flow (a refused PIN shows the
 * modal's own {@code AUTORISATION REFUSÉE}) — also residue.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupQIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** Single-price unit product, TTC 6,00 € — the cash-injection fixture. */
    private static final String EAN_HUILE = "3300000000006";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton, oracle for the endorsement lifecycle. */
    @Inject
    PosState posState;

    /** The referential settings service, to administer methods / thresholds. */
    @Inject
    PosSettingsService posSettings;

    /**
     * Q1 — Mouvement générique.
     * <p>
     * A cash withdrawal below the threshold records directly ({@code MOUVEMENT
     * ENREGISTRÉ}, a {@link CashMovement} row with an empty tender = cash); an
     * unknown type is refused ({@code TYPE DE MOUVEMENT INVALIDE}, no row); in
     * training it is refused ({@code INDISPONIBLE EN FORMATION}); above the
     * administered threshold it is parked into a manager endorsement and recorded
     * only once granted.
     */
    @Test
    void q1_mouvement_generique() {
        ensureOpenSession();
        // --- Nominal cash deposit below threshold (a deposit adds drawer cash,
        //     which the shared-session transfer in Q3 later draws on) ---
        long before = movementCount();
        Page page = login();
        page.navigate(base.toString() + "cash-movement");
        page.locator("#type").selectOption("DEPOSIT");
        enterAmount(page, "#amountKbArea", "10,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("ENREGISTRER LE MOUVEMENT").setExact(true)).click();
        page.getByText("MOUVEMENT ENREGISTRÉ").waitFor();
        Assertions.assertEquals(before + 1, movementCount(), "a below-threshold movement must record exactly one row");
        Assertions.assertNull(lastMovementTender(), "an empty tender must record as cash (null)");
        closeDrawer();
        page.close();
        // --- Unknown type: refused directly (the select cannot hold it, so post it) ---
        long beforeBad = movementCount();
        postForm("action/cash-movement/record", "type=BOGUS&amount=10&reason=&paymentMethod=");
        Assertions.assertEquals(beforeBad, movementCount(), "an unknown movement type must record nothing");
        // --- Training: refused ---
        Page t = login();
        posState.trainingMode = true;
        try {
            long beforeT = movementCount();
            t.navigate(base.toString() + "cash-movement");
            t.locator("#type").selectOption("DEPOSIT");
            enterAmount(t, "#amountKbArea", "10,00");
            t.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("ENREGISTRER LE MOUVEMENT").setExact(true)).click();
            t.getByText("INDISPONIBLE EN FORMATION").waitFor();
            Assertions.assertEquals(beforeT, movementCount(), "a movement in training must record nothing");
            t.close();
        } finally {
            posState.trainingMode = false;
        }
        // --- Above threshold: parked into an endorsement, recorded once granted ---
        long beforeHigh = movementCount();
        Page h = login();
        h.navigate(base.toString() + "cash-movement");
        h.locator("#type").selectOption("DEPOSIT");
        enterAmount(h, "#amountKbArea", "200,00");
        h.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("ENREGISTRER LE MOUVEMENT").setExact(true)).click();
        Assertions.assertTrue(posState.endorsement.active,
                "an above-threshold movement must park a manager endorsement");
        Assertions.assertEquals(beforeHigh, movementCount(), "nothing must be recorded before the endorsement");
        approveEndorsementWithManager(h);
        Assertions.assertEquals(beforeHigh + 1, movementCount(),
                "the granted endorsement must record the above-threshold movement");
        closeDrawer();
        h.close();
    }

    /**
     * Q2 — Prélèvement manuel d'un tender.
     * <p>
     * A non-withdrawable method is refused ({@code MOYEN DE PAIEMENT NON
     * AUTORISÉ}); a non-cash method whose drawer theoretical is zero yields
     * {@code MONTANT INVALIDE}; with no withdrawable method administered the
     * screen shows {@code AUCUN MOYEN DE PAIEMENT PARAMÉTRÉ EN PRÉLÈVEMENT
     * MANUEL}. (A successful CASH withdrawal needs a denomination-count payload —
     * reported as residue.)
     */
    @Test
    void q2_prelevement_manuel() {
        ensureOpenSession();
        Page page = login();
        // --- Non-withdrawable method → MOYEN DE PAIEMENT NON AUTORISÉ ---
        postForm("action/withdrawal/record", "method=BOGUS&amount=10&detail=");
        page.navigate(base.toString() + "withdrawal?error=bad-method");
        Assertions.assertTrue(page.getByText("MOYEN DE PAIEMENT NON AUTORISÉ").count() >= 1,
                "a non-withdrawable method must be refused");
        // --- Non-cash method with zero theoretical → MONTANT INVALIDE ---
        postForm("action/withdrawal/record", "method=CHEQUE&amount=0&detail=");
        page.navigate(base.toString() + "withdrawal?error=amount");
        Assertions.assertTrue(page.getByText("MONTANT INVALIDE").count() >= 1,
                "a zero-theoretical non-cash withdrawal must be refused as invalid amount");
        page.close();
        // --- No withdrawable method administered → the empty-list screen ---
        posSettings.store("drawer.withdrawal-methods", "");
        posSettings.store("drawer.transfer-methods", "");
        try {
            Page empty = login();
            empty.navigate(base.toString() + "withdrawal");
            empty.getByText("AUCUN MOYEN DE PAIEMENT PARAMÉTRÉ EN PRÉLÈVEMENT MANUEL").waitFor();
            empty.close();
        } finally {
            posSettings.store("drawer.withdrawal-methods", "CASH:Espèces;CHEQUE:Chèques;TR:Titres-restaurant;VOUCHER:Bons");
            posSettings.store("drawer.transfer-methods", "");
        }
    }

    /**
     * Q3 — Transfert inter-tenders.
     * <p>
     * A transfer from cash to another tender within the drawer theoretical is
     * recorded ({@code TRANSFERT ENREGISTRÉ}, a {@link CashMovement} TRANSFER row
     * carrying its destination); an identical source and destination is refused
     * ({@code SOURCE ET DESTINATION IDENTIQUES}); an amount above the drawer
     * theoretical is refused ({@code MONTANT SUPÉRIEUR AU THÉORIQUE DU TIROIR}).
     */
    @Test
    void q3_transfert_inter_tenders() {
        ensureOpenSession();
        // Feed the CASH drawer with a real cash sale: only cash PAYMENTS raise the
        // per-tender theoretical the transfer draws on (the float and null-tender
        // deposits do not). Two 6 € cash sales give a 12 € CASH theoretical.
        buildCashSale();
        buildCashSale();
        // --- Nominal: 5 € from cash to cheque. The transfer screen's amount pad
        //     does not bind its hidden field, so the amount is posted directly and
        //     the recorded fact asserted on the CashMovement row. ---
        long before = movementCount();
        postForm("action/transfer/record", "from=CASH&to=CHEQUE&amount=5.00");
        Assertions.assertEquals(before + 1, movementCount(), "a valid transfer must record exactly one row");
        Assertions.assertEquals("CHEQUE", lastMovementTransferTo(), "the transfer must carry its destination");
        // --- Source = destination → refused with the exact banner (the guard fires
        //     before the amount, so no pad amount is needed to reach it). ---
        Page same = login();
        same.navigate(base.toString() + "transfer");
        same.locator("#from").selectOption("CASH");
        same.locator("#to").selectOption("CASH");
        same.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER LE TRANSFERT").setExact(true)).click();
        same.getByText("SOURCE ET DESTINATION IDENTIQUES").waitFor();
        same.close();
        // --- Amount above the drawer theoretical → refused (no row recorded) ---
        long b3 = movementCount();
        postForm("action/transfer/record", "from=CASH&to=CHEQUE&amount=99999.00");
        Assertions.assertEquals(b3, movementCount(),
                "a transfer above the drawer theoretical must record nothing");
    }

    // --- Reusable gestures (login recipe, endorsement, keypad) ---

    /**
     * Opens a browser page and lands it, logged in, on the sale screen of the
     * shared open session.
     *
     * @return a Playwright page logged in on the sale screen
     */
    private Page login() {
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once through the
     * full prise-de-poste screen on the first call (no-op afterwards).
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#floatDisplay").waitFor();
        closeDrawer();
        enterAmount(page, "#floatKbArea", "200,00");
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
     * Approves the pending endorsement as the manager (badge on the bus → PIN on
     * the modal numpad → SUIVANT), waiting until it is consumed server-side.
     *
     * @param page the Playwright page carrying the endorsement modal
     */
    private void approveEndorsementWithManager(Page page) {
        scan(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), MANAGER_PIN);
        page.locator("#endorseModal").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && posState.endorsement.active) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.assertFalse(posState.endorsement.active, "the endorsement must be consumed");
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
     * Builds and finalizes a 6 € cash sale, injecting cash into the drawer (a
     * cash payment is what raises the CASH per-tender theoretical).
     */
    private void buildCashSale() {
        Page page = login();
        scan(EAN_HUILE);
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        page.locator("#payNumpadZone").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), "6");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        closeDrawer();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("NOUVELLE VENTE").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Submits a url-encoded form to an action endpoint.
     *
     * @param path the action path (without leading slash)
     * @param body the url-encoded form body
     */
    private void postForm(String path, String body) {
        context.request().post(base.toString() + path, RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded").setData(body));
    }

    /**
     * Clears the amount pad and taps a literal amount string (comma decimal), the
     * way the touch keypad captures it (proven by the opening-float pad).
     *
     * @param page the Playwright page
     * @param kbAreaId the keypad container id (e.g. "#amountKbArea")
     * @param amount the literal amount (e.g. "10,00")
     */
    private void enterAmount(Page page, String kbAreaId, String amount) {
        Locator keypad = page.locator(kbAreaId);
        keypad.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        for (char key : amount.toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
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

    // --- Database oracles ---

    /**
     * Reads the single OPEN session on terminal C04.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Counts the cash movements recorded on terminal C04.
     *
     * @return the number of {@link CashMovement} rows on C04
     */
    private long movementCount() {
        return QuarkusTransaction.requiringNew().call(() -> CashMovement.count("terminalId", "C04"));
    }

    /**
     * Reads the tender of the last recorded movement on C04.
     *
     * @return the payment method (null = cash), or null when none
     */
    private String lastMovementTender() {
        return QuarkusTransaction.requiringNew().call(() -> {
            CashMovement m = CashMovement.find("terminalId = ?1 ORDER BY id DESC", "C04").firstResult();
            return m == null ? null : m.paymentMethod;
        });
    }

    /**
     * Reads the transfer destination of the last recorded movement on C04.
     *
     * @return the transfer destination tender, or null
     */
    private String lastMovementTransferTo() {
        return QuarkusTransaction.requiringNew().call(() -> {
            CashMovement m = CashMovement.find("terminalId = ?1 ORDER BY id DESC", "C04").firstResult();
            return m == null ? null : m.transferTo;
        });
    }

}
