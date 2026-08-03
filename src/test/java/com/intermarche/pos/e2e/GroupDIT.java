package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Ticket;
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
 * Group D end-to-end scenarios (Fidélité), played THROUGH THE SCREEN with a
 * headless Chromium browser (quarkus-playwright) against the real application
 * (live H2, real beans, embedded hardware simulator). Group D is entirely
 * {@code [S]} (caisse seule + simulateur), so every scenario D1–D3 is
 * implemented here; there is no {@code [V]}/{@code [N]} residue for this letter.
 * <p>
 * <b>The card is scanned on the bus, or typed on the fallback page.</b> A
 * fidelity card is recognized by the {@code scan.pattern.fidelity} regex
 * ({@code ^789\d{12}$}, a 15-digit card) presented on the hardware bus
 * ({@code POST /api/pos/scan}) — the primary path (D1). When the card does not
 * scan, the cashier types it on the {@code /fidelity} fallback page and submits
 * (D2). On the lock screen a card is meaningless and stays inert (D3).
 * <p>
 * <b>Attachment is a working copy over a durable draft.</b> Attaching a card
 * flips the in-memory {@link com.intermarche.pos.ui.fidelity.FidelityState}
 * ({@code active}/{@code label}) and lights the loyalty icon
 * ({@code #fidIcon.active}, driven by the 1s poll's {@code fidelityActive}); the
 * card is copied onto the persisted draft ({@code Ticket.fidelityCard}) at the
 * next scan-driven sync, which is why it SURVIVES a register restart. The
 * scan path syncs immediately (a draft already exists), so D1 asserts the
 * durable draft carries the card — the precondition the recovery path
 * ({@code TicketRecoveryService}) reads back on boot. The actual kill/restart
 * recovery is K1's remit (cross-referenced, not re-played here).
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (d01…d03) and share ONE cash session:
 * {@link #ensureOpenSession()} opens it once (on the fresh drop-and-create H2
 * boot) through the full prise-de-poste screen and every later call is a no-op
 * because the register already carries an OPEN {@code C04-Sxxxxx} session. Each
 * sale scenario then builds its OWN cart from an empty sale (a logout via
 * {@code /lock} abandons the in-memory cart and its fidelity card).
 * <p>
 * <b>Oracles.</b> The reactive screen ({@code TOTAL À PAYER}, the loyalty icon
 * {@code #fidIcon}, the lock overlay) is asserted where the mutation touches the
 * polling version; the server state ({@link PosState#fidelity}) is the oracle
 * for the in-memory attachment (active flag, label, last-wins replacement) and
 * for the INVISIBLE inertness on the lock screen; the database
 * ({@code Ticket.fidelityCard}) is the durable oracle of the draft.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupDIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** Single-price unit product used to start a cart: EAN, TTC 0,96 €. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    /** A second unit product, used to trigger a fresh sync after manual entry. */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** A valid fidelity card ({@code ^789\d{12}$}, 15 digits). */
    private static final String CARD_1 = "789000000000001";

    /** A second valid fidelity card, to prove last-presented wins (D2). */
    private static final String CARD_2 = "789000000000002";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected to assert the in-memory fidelity
     * attachment (active flag, label, last-wins replacement) and the invisible
     * inertness of a card scanned on the lock screen.
     */
    @Inject
    PosState posState;

    /**
     * D1 — Scan carte en cours de panier.
     * <p>
     * A fidelity card scanned on the bus during a running cart is attached: the
     * loyalty icon lights ({@code #fidIcon.active}, driven by the poll), the
     * in-memory state carries the card, and — because a draft already exists —
     * the scan-driven sync copies the card onto the persisted draft
     * ({@code Ticket.fidelityCard}). That durable draft is exactly what the
     * recovery path reads back on boot, so the card survives a restart (the
     * kill/restart itself is K1's remit, cross-referenced here).
     */
    @Test
    void d01_scan_carte_en_cours_de_panier() {
        Page page = freshSale();
        // A running cart first: it creates the draft the card will land on.
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "scanning a line must persist the sale's draft");
        // Scan the fidelity card on the bus: attached to the running ticket.
        scan(CARD_1);
        // Screen oracle: the poll lights the loyalty icon.
        page.locator("#fidIcon.active").waitFor();
        // In-memory oracle: the card is attached with its exact number.
        Assertions.assertTrue(posState.fidelity.active, "a scanned card must attach to the ticket");
        Assertions.assertEquals(CARD_1, posState.fidelity.label, "the attached label must be the scanned card");
        // Durable oracle: the scan-driven sync copied the card onto the draft.
        String persisted = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).fidelityCard);
        Assertions.assertEquals(CARD_1, persisted,
                "the card must persist on the draft (the boot recovery reads it back — cross K1)");
        page.close();
    }

    /**
     * D2 — Saisie manuelle (fallback) &amp; dernière carte gagne.
     * <p>
     * The manual-entry page ({@code /fidelity}) is the fallback when the card
     * does not scan: typing a card and validating attaches it exactly as a scan
     * would (icon lit, in-memory state carrying the card). A second manual entry
     * replaces the first (last presented wins), and a later scan-driven sync
     * carries the winning card onto the durable draft.
     */
    @Test
    void d02_saisie_manuelle_derniere_carte_gagne() {
        Page page = freshSale();
        // A running cart first (so a later scan can sync the card to the draft).
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "scanning a line must persist the sale's draft");
        // Manual entry of the first card on the fallback page.
        typeCardOnFidelityPage(page, CARD_1);
        // Screen oracle: the returned home page shows the loyalty icon lit.
        page.locator("#fidIcon.active").waitFor();
        Assertions.assertTrue(posState.fidelity.active, "a typed card must attach like a scan");
        Assertions.assertEquals(CARD_1, posState.fidelity.label, "the first typed card must be attached");
        // Second manual entry: the last presented card wins.
        typeCardOnFidelityPage(page, CARD_2);
        page.locator("#fidIcon.active").waitFor();
        Assertions.assertEquals(CARD_2, posState.fidelity.label,
                "a second card must replace the first (last presented wins)");
        // Durable oracle: a fresh scan-driven sync carries the winning card to the draft.
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        String persisted = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).fidelityCard);
        Assertions.assertEquals(CARD_2, persisted, "the winning card must persist on the draft");
        page.close();
    }

    /**
     * D3 — Carte sur écran de lock → inerte.
     * <p>
     * On the lock screen the register is locked: a fidelity card scanned on the
     * bus is inert on both guards ({@code processScan}'s locked short-circuit and
     * the handler's {@code !isLocked()}), so nothing is attached. Being 15 digits
     * it is not a badge either, so the lock overlay never flips to PIN entry —
     * no login prefill, the screen stays on the badge/PIN prompt.
     */
    @Test
    void d03_carte_sur_ecran_de_lock_inerte() {
        // Not a sale scenario: land on the lock screen (a logout, which clears
        // any leftover cart and its fidelity card), never on the sale.
        for (Page open : context.pages()) open.close();
        Page page = context.newPage();
        page.navigate(base.toString() + "lock");
        // The initial lock state (before any badge) shows the closed-lane screen.
        page.getByText("CAISSE FERMÉE").waitFor();
        // Scan the card while locked: it must do nothing at all.
        scan(CARD_1);
        Assertions.assertFalse(posState.fidelity.active,
                "a card scanned on the lock screen must not attach");
        Assertions.assertEquals("", posState.fidelity.label,
                "the inert card must leave no label");
        // Not a badge (15 digits, not 8): the overlay never flips to PIN entry.
        Assertions.assertEquals(0, page.getByText("Entrez votre code PIN :").count(),
                "a fidelity card must not prefill the login nor open PIN entry");
        Assertions.assertTrue(posState.isLocked(), "the register must stay locked");
        page.close();
    }

    // --- Reusable gestures (login recipe & hardware bus) ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session — the standard start of every sale scenario.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshSale() {
        // Neutralize any page left polling by a previous scenario: a lingering
        // /lock-data poller would steal this scenario's badge from the one-shot
        // mailbox (the group-A page-close lesson).
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        loginToSaleWithOpenSession(page);
        return page;
    }

    /**
     * Ensures the register carries an OPEN session, opening one once through the
     * full prise-de-poste screen on the first call (no-op afterwards).
     */
    private void ensureOpenSession() {
        if (openSessionOnC04() != null) return;
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        // No session yet: the unlock lands on the SESSION screen (drawer open,
        // that screen is not guarded). Close the drawer, install the float and
        // open the session, which flows straight into the sale.
        page.locator("#openingFloat").waitFor();
        closeDrawer();
        page.locator("#openingFloat").fill("200,00");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * Navigates to the lock screen, presents the badge on the hardware scan bus
     * and taps the PIN, submitting the unlock — the shared login gesture.
     *
     * @param page the Playwright page driving the register
     * @param badge the 8-digit badge presented on the scan bus
     * @param pin the PIN tapped on the numpad
     */
    private void scanBadgeAndEnterPin(Page page, String badge, String pin) {
        String root = base.toString();
        // /lock FIRST (logs out, empties the one-shot mailbox), THEN the badge.
        page.navigate(root + "lock");
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(badge));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page.locator("#keyboardArea"), pin);
        page.locator("#actionBtn").click();
    }

    /**
     * Logs the cashier in and lands on an empty sale screen, assuming a session
     * is already open: the unlock pulse opens the drawer, the drawer guard
     * diverts to /drawer-error, and the physical drawer close carries the
     * drawer-status poll to the sale.
     *
     * @param page the Playwright page driving the register
     */
    private void loginToSaleWithOpenSession(Page page) {
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Types a card on the {@code /fidelity} fallback page and submits it — the
     * manual-entry gesture (the card does not scan). Navigates to the page,
     * taps the digits on the numpad and clicks VALIDER, landing back on the
     * home screen.
     *
     * @param page the Playwright page driving the register
     * @param card the card number to type
     */
    private void typeCardOnFidelityPage(Page page, String card) {
        page.navigate(base.toString() + "fidelity");
        page.locator("#fidKeyboardArea").waitFor();
        tapDigits(page.locator("#fidKeyboardArea"), card);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Presents a code on the hardware scan bus (scanner gun / simulator) and
     * asserts the bus accepted it — the uniform entry of every scanned code.
     *
     * @param code the code to present on the bus
     */
    private void scan(String code) {
        APIResponse res = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the code " + code);
    }

    /**
     * Taps digits one by one on a numeric keypad, scoped under the given
     * container so no mode toggle is ever hit.
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
     * Pushes the drawer shut on the hardware bus (the embedded simulator),
     * clearing the drawer guard.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }
}
