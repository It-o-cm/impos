package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitForSelectorState;
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
 * Group J end-to-end scenarios (Mode formation), played THROUGH THE SCREEN
 * with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Every group-J
 * scenario is {@code [S]} (caisse seule + simulateur), so J1–J3 are all
 * implemented here and nothing is left as residue.
 * <p>
 * <b>Training mode, exactly as the store trains a new cashier.</b> Training is a
 * manager-endorsed cross-cutting flag on the {@link PosState} singleton — a
 * sandbox where the register still LOOKS and REACTS like the real thing but
 * touches nothing fiscal. J1 proves the entrance/exit round-trip and the orange
 * banners it raises on both faces of the terminal (the cashier's sale/payment
 * screens and the customer display) and their clean disappearance on exit; J2
 * proves the fiscal neutralization of a whole sale (scans accepted, NO draft
 * written, payments only simulated, the cash drawer never pulsed open, the
 * receipt a memory-built {@code TICKET NON VALABLE}); J3 proves the three real
 * documents are refused in training — a duplicata reprint, a refund creation and
 * every session mutation — on BOTH the on-screen button and the direct URL.
 * <p>
 * <b>Oracles.</b> The rendered banner ({@code .overlay-training} on the cashier
 * screens, {@code #trainingBanner} on the customer display) is the oracle for
 * the visible training state; the database ({@link Ticket} row count on C04,
 * {@link Refund} count, {@link CashSession} status) is the oracle for the fiscal
 * silence; the printer simulator is the oracle for the {@code TICKET NON
 * VALABLE} receipt and for the ABSENCE of a real duplicata; the drawer
 * simulator status is the oracle for the never-opened till; and the injected
 * {@link PosState} is the oracle for the client-invisible refusals set on the
 * ticket / refund state (the group-A "message without a poll-bumping touch"
 * lesson, here read straight off the singleton).
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (j1…j3); each opens through the full
 * prise-de-poste on the fresh drop-and-create H2 boot (a session is opened once
 * and stays open — training never touches it), enters training through the
 * endorsed toggle and RESTORES the register to normal mode before it returns, so
 * no scenario leaks the training flag onto the next. Ticket and session counters
 * are perpetual, so nothing is asserted as an absolute number.
 * <p>
 * <b>Justified residue.</b> The catalog's "vente SANS session" wording of J2
 * (login routed straight to the sale, skipping the session screen, because
 * training needs no open session — {@code AuthResource}) cannot be bootstrapped
 * purely through the screen: the training toggle lives only on the sale screen,
 * which itself requires an open session to reach, and that session cannot then
 * be closed in training (the Z is blocked — see I4/J3). The fiscal ESSENCE of
 * "sans session" — that the training sale consumes and mutates no session, writes
 * no draft and leaves the open session pristine — IS proven here on the database;
 * the pure routing bypass is listed as residue.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupJIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The opening float of the shared session (euros, comma decimal). */
    private static final String OPENING_FLOAT = "200,00";

    /** Single-price unit product, TTC 6,00 € — the clean cart fixture. */
    private static final String EAN_HUILE = "3300000000006";

    /** The uppercased line label of {@link #EAN_HUILE} on a ticket. */
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** A second single-price unit product, TTC 6,00 € — the second cart line. */
    private static final String EAN_MIEL = "3300000000024";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected as the oracle for the training
     * flag, the neutralized draft id, the simulated payments, and the
     * client-invisible refusals set on the ticket and refund state.
     */
    @Inject
    PosState posState;

    /**
     * J1 — Entrée/sortie sous avenant (bannières orange sur tous les écrans, état propre à la sortie).
     * <p>
     * The manager-endorsed toggle enters training mode: the sale screen and the
     * customer display both raise their orange {@code MODE FORMATION} banner, and
     * the injected flag flips true. A second endorsed toggle leaves training: both
     * banners disappear (the cashier's is dropped from the DOM, the customer's is
     * re-hidden by its poll) and the flag flips back false — a clean state with no
     * training residue.
     */
    @Test
    void j1_entree_sortie_sous_avenant() {
        Page register = freshSale();
        // The customer face: a plain second page polling /customer-data.
        Page display = context.newPage();
        display.navigate(base.toString() + "customer");
        display.getByText("Bienvenue").waitFor();
        Assertions.assertFalse(display.locator("#trainingBanner").isVisible(),
                "the customer banner must stay hidden in normal operation");
        Assertions.assertEquals(0, register.locator(".overlay-training").count(),
                "the cashier banner must be absent in normal operation");
        // --- Enter training (endorsed toggle): both faces raise the banner ---
        toggleTraining(register);
        Assertions.assertTrue(posState.trainingMode, "the endorsed toggle must enter training mode");
        register.navigate(base.toString());
        Assertions.assertTrue(register.locator(".overlay-training").isVisible(),
                "the sale screen must raise the orange training banner");
        Assertions.assertTrue(register.locator(".overlay-training").textContent().contains("MODE FORMATION"),
                "the cashier banner must read MODE FORMATION");
        display.locator("#trainingBanner").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        Assertions.assertEquals("MODE FORMATION", display.locator("#trainingBanner").textContent().trim(),
                "the customer display must raise the MODE FORMATION banner");
        // --- Leave training (endorsed toggle): both banners disappear, clean state ---
        toggleTraining(register);
        Assertions.assertFalse(posState.trainingMode, "leaving training must clear the training flag");
        register.navigate(base.toString());
        Assertions.assertEquals(0, register.locator(".overlay-training").count(),
                "the sale screen banner must disappear when training ends");
        display.locator("#trainingBanner").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        Assertions.assertTrue(posState.ticket.items.isEmpty(),
                "the training round-trip must leave a clean, empty cart");
        register.close();
        display.close();
    }

    /**
     * J2 — Neutralisation fiscale (vente sans écriture : aucun draft, paiements simulés, tiroir clos, reçu NON VALABLE).
     * <p>
     * A whole sale is played in training: two products scan into the cart, the
     * payment overlay raises its own training banner, a cash tender completes the
     * transaction — yet NOTHING fiscal happens. No draft ticket is written (the
     * C04 row count is unchanged and {@code ticketDbId} stays null), the cash
     * payment does NOT pulse the drawer open, the receipt printed from the
     * completion modal is a memory-built {@code MODE FORMATION / TICKET NON
     * VALABLE} slip carrying no register number, and the open session is left
     * pristine. The payments are only simulated (registered in memory, the
     * transaction flagged complete).
     */
    @Test
    void j2_neutralisation_fiscale() {
        Page page = freshSale();
        CashSession sessionBefore = openSessionOnC04();
        Assertions.assertNotNull(sessionBefore, "a session is open on the register before the training sale");
        String sessionNumber = sessionBefore.sessionNumber;
        long ticketsBefore = ticketCountOnC04();
        // --- Enter training, make sure the drawer starts shut ---
        toggleTraining(page);
        Assertions.assertTrue(posState.trainingMode, "the sale must be played in training mode");
        closeDrawer();
        Assertions.assertEquals("CLOSED", drawerStatus(), "the drawer starts shut before the training sale");
        // --- Scans are accepted: two lines light up the cart (6,00 + 6,00) ---
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        scan(EAN_MIEL);
        Assertions.assertEquals(2, posState.ticket.items.size(),
                "both training scans must be accepted onto the cart");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(new java.math.BigDecimal("12.00")),
                "the two 6,00 € lines must total 12,00 € in memory");
        // --- The payment overlay raises its own training banner ---
        goPay(page);
        Assertions.assertTrue(page.locator(".overlay-training").isVisible(),
                "the payment screen must raise the orange training banner");
        // --- A cash tender completes the transaction (only simulated) ---
        payCashComplete(page, "20");
        Assertions.assertTrue(posState.payment.transactionComplete,
                "the training cash tender must complete the transaction in memory");
        Assertions.assertFalse(posState.payment.payments.isEmpty(),
                "the training payment must be registered in memory");
        // --- Fiscal silence: drawer never opened, no draft written ---
        Assertions.assertEquals("CLOSED", drawerStatus(),
                "a cash payment in training must NEVER pulse the drawer open");
        Assertions.assertNull(posState.payment.ticketDbId,
                "no draft must be reserved for the training sale");
        Assertions.assertEquals(ticketsBefore, ticketCountOnC04(),
                "no ticket row must be written for the training sale");
        // --- The receipt is a memory-built NON VALABLE slip, no register number ---
        clearPrinter();
        page.locator("form[action='/action/print']").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("IMPRIMER")).click();
        String receipt = printerContent();
        Assertions.assertTrue(receipt.contains("MODE FORMATION"),
                "the training receipt must be framed MODE FORMATION");
        Assertions.assertTrue(receipt.contains("TICKET NON VALABLE"),
                "the training receipt must be stamped TICKET NON VALABLE");
        Assertions.assertTrue(receipt.contains("FORMATION - SANS VALEUR"),
                "the training receipt must carry the SANS VALEUR footer");
        Assertions.assertFalse(receipt.matches("(?s).*C04-\\d{8}.*"),
                "the training receipt must carry no fiscal register number");
        // --- Close the sale: still nothing persisted, cart cleared ---
        finishSale(page);
        Assertions.assertEquals(ticketsBefore, ticketCountOnC04(),
                "closing the training sale must still write no ticket row");
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the training sale must end on a clean cart");
        // --- The open session is left pristine ---
        CashSession sessionAfter = openSessionOnC04();
        Assertions.assertNotNull(sessionAfter, "the training sale must leave the session OPEN");
        Assertions.assertEquals(sessionNumber, sessionAfter.sessionNumber,
                "the training sale must not renumber the session");
        Assertions.assertNull(closingDateOf(sessionNumber), "the training sale must not close the session");
        // --- Restore: leave training for a clean register ---
        toggleTraining(page);
        Assertions.assertFalse(posState.trainingMode, "the register must end back in normal mode");
        page.close();
    }

    /**
     * J3 — Actions bloquées (réimpression, création de retour, mutations de session) sur les deux chemins.
     * <p>
     * A real closed ticket is built BEFORE training. In training every real
     * document is refused, and the refusal holds on BOTH the on-screen button and
     * the direct URL: a duplicata reprint sets {@code RÉIMPRESSION INDISPONIBLE EN
     * FORMATION} and prints nothing (no register number reaches the paper); a
     * refund is refused with {@code RETOURS INDISPONIBLES EN FORMATION}, opens no
     * endorsement and creates no {@link Refund}; and every session mutation
     * ({@code close-start}, {@code open}, {@code close}) diverts to the session
     * screen with the {@code INDISPONIBLE EN FORMATION} flag, leaving the open
     * session untouched.
     */
    @Test
    void j3_actions_bloquees() {
        // --- Build a real closed ticket BEFORE entering training (reprint target) ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        CashSession sessionBefore = openSessionOnC04();
        Assertions.assertNotNull(sessionBefore, "a session is open on the register before the training blocks");
        String sessionNumber = sessionBefore.sessionNumber;
        // --- Enter training ---
        Page page = freshSale();
        toggleTraining(page);
        Assertions.assertTrue(posState.trainingMode, "the blocks are exercised in training mode");

        // === Reprint (duplicata) refused — button path ===
        page.navigate(base.toString() + "reprint/view/" + ticketId);
        page.getByText("TICKET " + number).waitFor();
        posState.ticket.transientError = null;
        clearPrinter();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("IMPRIMER").setExact(true)).click();
        page.getByText("TICKET " + number).waitFor();
        Assertions.assertEquals("RÉIMPRESSION INDISPONIBLE EN FORMATION", posState.ticket.transientError,
                "a reprint button in training must be refused with the training message");
        Assertions.assertFalse(printerContent().contains(number),
                "a refused reprint must print no duplicata of the ticket");
        // === Reprint (duplicata) refused — direct URL path ===
        posState.ticket.transientError = null;
        clearPrinter();
        page.navigate(base.toString() + "reprint/print/" + ticketId);
        page.getByText("TICKET " + number).waitFor();
        Assertions.assertEquals("RÉIMPRESSION INDISPONIBLE EN FORMATION", posState.ticket.transientError,
                "a direct reprint URL in training must be refused with the training message");
        Assertions.assertFalse(printerContent().contains(number),
                "a refused direct reprint must print no duplicata of the ticket");

        // === Refund creation refused — the refund-request route ===
        // The on-screen method buttons (ESPÈCES...) funnel through submitAction,
        // which submits the staging form and cancels the href, so the refund is
        // only ever REQUESTED via the GET /return/pay/{method} route the button
        // ultimately targets — exercised here on a fully staged refund.
        long refundsBefore = refundCountFor(ticketId);
        Long lineId = firstLineId(ticketId);
        openReturnDetail(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        Assertions.assertEquals(0, posState.refund.getReturnQty(lineId).compareTo(java.math.BigDecimal.ONE),
                "the refund line must be fully staged before the refused request");
        page.navigate(base.toString() + "return/pay/cash");
        page.getByText("RETOURS INDISPONIBLES EN FORMATION").waitFor();
        Assertions.assertEquals("RETOURS INDISPONIBLES EN FORMATION", posState.refund.errorMessage,
                "a refund request in training must be refused with the training message");
        Assertions.assertFalse(posState.endorsement.active,
                "a refund refused in training must open no endorsement");
        Assertions.assertEquals(refundsBefore, refundCountFor(ticketId),
                "a refund refused in training must create no refund document");

        // The register's session-mutation routes are drawer-guarded; the login
        // pulse may have left the drawer out, so shut it before probing them.
        closeDrawer();
        // === Session mutation (Z close-start) refused — direct URL path (GET) ===
        assertSessionMutationBlockedByUrl(page, "action/session/close-start");
        // === Session mutation (Z close-start) refused — on-screen button path ===
        page.navigate(base.toString() + "session");
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("CLÔTURE DE CAISSE (Z)").setExact(true)).click();
        page.getByText("Session en cours").waitFor();
        Assertions.assertTrue(page.url().contains("INDISPONIBLE"),
                "the Z button in training must divert with the INDISPONIBLE EN FORMATION flag");
        Assertions.assertFalse(page.url().contains("cash-count"),
                "the Z button in training must never reach the drawer-count page");
        // === The POST-only mutations (open, close) refused on the HTTP surface ===
        // Opening and closing are POST routes with no on-screen button while a
        // session is already open, so they are probed on the request bus: both
        // divert to the session screen carrying the INDISPONIBLE EN FORMATION flag.
        assertPostSessionRouteBlocked("action/session/open", "openingFloat=50,00");
        assertPostSessionRouteBlocked("action/session/close", "counted=210,00&withdrawn=0&detail={}");
        // --- The open session is untouched by any blocked mutation ---
        CashSession sessionAfter = openSessionOnC04();
        Assertions.assertNotNull(sessionAfter, "the blocked mutations must leave the session OPEN");
        Assertions.assertEquals(sessionNumber, sessionAfter.sessionNumber,
                "the blocked mutations must not renumber the session");
        Assertions.assertNull(closingDateOf(sessionNumber), "the blocked mutations must not close the session");

        // --- Restore: leave training for a clean register ---
        toggleTraining(page);
        Assertions.assertFalse(posState.trainingMode, "the register must end back in normal mode");
        page.close();
    }

    // --- Scenario-specific assertions ---

    /**
     * Navigates a session-mutation route directly by URL in training and asserts
     * it diverts to the session screen with the {@code INDISPONIBLE} flag without
     * reaching the drawer-count page.
     *
     * @param page the Playwright page driving the register
     * @param route the session-mutation route (relative to the base URL)
     */
    private void assertSessionMutationBlockedByUrl(Page page, String route) {
        page.navigate(base.toString() + route);
        page.getByText("Session en cours").waitFor();
        Assertions.assertTrue(page.url().contains("INDISPONIBLE"),
                "the direct URL " + route + " in training must divert with the INDISPONIBLE flag");
        Assertions.assertFalse(page.url().contains("cash-count"),
                "the direct URL " + route + " in training must never reach the drawer-count page");
    }

    /**
     * Posts a POST-only session-mutation route on the request bus and asserts it
     * diverts to the session screen with the {@code INDISPONIBLE} flag (the
     * training block) rather than mutating the session.
     *
     * @param route the session-mutation route (relative to the base URL)
     * @param form the url-encoded form body the route expects
     */
    private void assertPostSessionRouteBlocked(String route, String form) {
        APIResponse res = context.request().post(base.toString() + route,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData(form));
        Assertions.assertTrue(res.url().contains("INDISPONIBLE"),
                "the POST route " + route + " in training must divert with the INDISPONIBLE flag, was " + res.url());
    }

    // --- Reusable gestures (login recipe, sale build, training toggle) ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session — the standard start of every scenario.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshSale() {
        // Neutralize any page left polling by a previous scenario: a lingering
        // /lock-data or /endorsement-data poller would steal this scenario's
        // badge from the one-shot mailbox (the group-A page-close lesson).
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
        page.locator("#openingFloat").waitFor();
        closeDrawer();
        page.locator("#openingFloat").fill(OPENING_FLOAT);
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
     * Builds and finalizes a cash sale on an already-logged-in page: scans the
     * given products, tenders an (over)paying cash amount, completes the
     * transaction, closes the drawer and returns to an empty sale screen.
     * Returns the settled ticket's database id (captured before the fiscal
     * close).
     *
     * @param page the Playwright page on the empty sale screen
     * @param cashDigits the cash amount to tap (must cover the total)
     * @param eans the product EANs to scan, one line each (repeats merge)
     * @return the database id of the closed ticket
     */
    private Long closeCashSaleOn(Page page, String cashDigits, String... eans) {
        for (String ean : eans) scan(ean);
        goPay(page);
        payCashComplete(page, cashDigits);
        Long ticketId = posState.payment.ticketDbId;
        Assertions.assertNotNull(ticketId, "the settled sale must carry a persisted draft");
        finishSale(page);
        return ticketId;
    }

    /**
     * Walks onto the payment overlay and waits until it is ready (the remaining
     * line is rendered).
     *
     * @param page the Playwright page driving the register
     */
    private void goPay(Page page) {
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Tenders an (over)paying cash amount on the payment overlay and waits for
     * the completion modal.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param digits the cash amount digits to tap
     */
    private void payCashComplete(Page page, String digits) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), digits);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
    }

    /**
     * Closes the completed transaction cleanly: pushes the drawer shut so the
     * guard lets the finish through, then taps NOUVELLE VENTE and waits for the
     * empty sale screen.
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
     * Toggles the training mode through the endorsed toggle: requests the
     * {@code TRAINING_TOGGLE} endorsement from an empty cart and approves it with
     * the manager, landing back on the sale screen with the mode flipped.
     *
     * @param page the Playwright page driving the register
     */
    private void toggleTraining(Page page) {
        page.navigate(base.toString() + "action/training");
        approveEndorsementWithManager(page);
    }

    /**
     * Validates the pending endorsement as the manager: badges on the bus (the
     * modal jumps to PIN entry, the login prefilled from the mailbox), taps the
     * manager PIN on the modal numpad and submits — the four-eyes gesture ending
     * back on the sale screen.
     *
     * @param page the Playwright page carrying the endorsement modal
     */
    private void approveEndorsementWithManager(Page page) {
        scan(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), MANAGER_PIN);
        page.locator("#endorseModal").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    // --- Refund-screen gestures ---

    /**
     * Opens a found ticket's refund detail by its result row
     * ({@code /return/select/{id}}) and waits until the detail is rendered.
     *
     * @param page the Playwright page driving the register
     * @param ticketId the database id of the ticket to open
     */
    private void openReturnDetail(Page page, Long ticketId) {
        page.navigate(base.toString() + "return/select/" + ticketId);
        page.getByText("Montant Retour").waitFor();
        page.getByText(LABEL_HUILE).first().waitFor();
    }

    /**
     * Selects a refund line, opening its quantity controls (the {@code +}/{@code
     * -} steppers).
     *
     * @param page the Playwright page carrying the refund detail
     * @param lineId the database id of the ticket line
     */
    private void selectReturnLine(Page page, Long lineId) {
        page.locator("a.line-content[href='/return/select-line/" + lineId + "']").click();
        page.locator("a[href='/return/line/" + lineId + "/add']").waitFor();
    }

    /**
     * Taps a selected line's {@code +} stepper once, staging a return quantity.
     *
     * @param page the Playwright page carrying the refund detail with the line selected
     * @param lineId the database id of the ticket line
     */
    private void addReturnQty(Page page, Long lineId) {
        page.locator("a[href='/return/line/" + lineId + "/add']").click();
        page.getByText("Montant Retour").waitFor();
    }

    // --- Hardware bus & keypad ---

    /**
     * Presents a code on the hardware scan bus (scanner gun / simulator) and
     * asserts the bus accepted it — the uniform entry of every scanned code and
     * the manager badge on the endorsement modal.
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
     * Reads the drawer state from the hardware simulator.
     *
     * @return "OPEN" or "CLOSED"
     */
    private String drawerStatus() {
        return context.request().get(base.toString() + "api/hardware/drawer/status").text().trim();
    }

    /**
     * Pushes the drawer shut on the hardware bus (the embedded simulator),
     * clearing the drawer guard.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Empties the printer simulator buffer so the next print is read in
     * isolation.
     */
    private void clearPrinter() {
        context.request().post(base.toString() + "api/hardware/printer/clear", RequestOptions.create());
    }

    /**
     * Reads the accumulated printer simulator buffer (the printed paper).
     *
     * @return the printed content captured by the simulator
     */
    private String printerContent() {
        return context.request().get(base.toString() + "api/hardware/printer/content").text();
    }

    // --- Database oracles ---

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Reads the closing date of a session by its number (null while open).
     *
     * @param sessionNumber the session number
     * @return the closing date, or null when the session is still open
     */
    private java.time.LocalDateTime closingDateOf(String sessionNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            CashSession s = CashSession.find("sessionNumber", sessionNumber).firstResult();
            return s != null ? s.closingDate : null;
        });
    }

    /**
     * Counts the ticket rows on terminal C04 — the oracle for the training
     * sale's fiscal silence (no draft written).
     *
     * @return the number of {@link Ticket} rows on C04
     */
    private long ticketCountOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> Ticket.count("terminalId", "C04"));
    }

    /**
     * Reads a ticket's register number from the database.
     *
     * @param ticketId the database id of the ticket
     * @return the ticket number, e.g. "C04-00000007"
     */
    private String ticketNumber(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return t.ticketNumber;
        });
    }

    /**
     * Reads the database id of a ticket's first line.
     *
     * @param ticketId the database id of the ticket
     * @return the first line's id
     */
    private Long firstLineId(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return t.lines.get(0).id;
        });
    }

    /**
     * Counts the refunds attached to a ticket — the oracle for "no refund
     * created" in training.
     *
     * @param ticketId the database id of the original ticket
     * @return the number of {@link Refund} rows referencing the ticket
     */
    private long refundCountFor(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> Refund.count("originalTicketId", ticketId));
    }
}
