package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketRecoveryService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentService;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Group K end-to-end scenarios (Reprise &amp; robustesse), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Every group-K
 * scenario is {@code [S]} (caisse seule + simulateur), so K1–K3 are all
 * implemented here.
 * <p>
 * <b>Crash &amp; restart, without a real kill -9.</b> A {@code @QuarkusTest}
 * cannot tear its own JVM down, but the register's crash-resilience does not
 * live in the process lifecycle — it lives in TWO facts the code makes explicit:
 * the sale is durable from the first article ({@code TicketPersistenceService.
 * syncDraft} writes an OPEN draft on every cart mutation and every payment), and
 * the in-memory {@link PosState} is "memory only: the durable truth is the draft,
 * this object is rebuilt from it at recovery". A crash is therefore faithfully
 * reproduced by WIPING the transactional memory ({@link PosState#clearTicket()} —
 * exactly what {@code /lock} logout does) and REPLAYING the boot-time
 * reconciliation ({@link TicketRecoveryService#recover()} — the very method the
 * {@code @Observes StartupEvent} hook runs). The draft on disk is untouched by
 * the wipe, so recovery rebuilds the cart from the single source of truth.
 * <p>
 * <b>K1 (crash mid-cart).</b> A three-line cart — an endorsed REMISE line, a
 * weighed (PLU) line and an attached fidelity card — is built through the screen
 * so the draft carries every structured gesture. After the wipe+recover the cart
 * comes back IDENTICAL: same line uids (the stable identity minted in memory and
 * persisted as {@code lineUid}), same structured REMISE modifier, same weighed
 * PLU, the fidelity card reattached, the SAME draft id reconciled — and no
 * duplicate draft (the single OPEN-draft-per-terminal invariant holds).
 * <p>
 * <b>K2 (crash mid-payment).</b> A partial CARD payment is registered on the
 * draft, then the memory is wiped and recovered: the draft stays OPEN, the
 * persisted payment is rebuilt from the database (not from memory residue), the
 * transaction is NOT prematurely completed (a balance remains), and completion is
 * then really carried through to a CLOSED ticket — proving the crash left a
 * coherent, finishable state.
 * <p>
 * <b>K3 (degraded hardware).</b> Three peripheral faults are injected on the
 * embedded simulator and the register's degraded-mode philosophy
 * ({@code HardwareService}: "every peripheral call swallows its exceptions") is
 * proven on the real code: a dead printer (no paper → {@code POST /printer/print}
 * answers 503) does not stop the sale — it still completes and CLOSES fiscally,
 * the receipt simply never reaches the paper; a dead drawer sensor
 * ({@code GET /drawer/status} answers 503 → {@code isDrawerOpen()} fails safe to
 * FALSE) disables the drawer guard instead of bricking the register behind
 * {@code /drawer-error}; a dead customer display ({@code POST /display} answers
 * 503) is swallowed in silence and a scan still lands its line.
 * <p>
 * <b>Simulator fault injectors.</b> The embedded simulator (src/mock, test
 * classpath only, never in the production jar) already carried a printer fault
 * toggle ({@code POST /printer/toggle-paper}); it had NO fault hook for the
 * drawer sensor or the display, so two symmetric injectors were added there —
 * {@code POST /drawer/toggle-sensor} and {@code POST /display/toggle} — flipping
 * the matching {@code GET /drawer/status} / {@code POST /display} to 503. No
 * production code (src/main) is touched; the faults exercise the REAL degraded
 * paths in {@code HardwareService}. See the report for the one production gap
 * (no {@code TechnicalEvent} is logged on a print failure — the "+ event" clause
 * of K3 has no code behind it, so it is reported, not asserted).
 * <p>
 * <b>Ordering &amp; isolation.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (k1…k3) on the fresh drop-and-create H2 boot
 * (zero drafts at k1). One session is opened by the first scenario and stays
 * open. K1 deliberately leaves its recovered draft OPEN (its own oracle is the
 * no-duplicate invariant on that single draft); K2's recovery then cancels that
 * older leftover as part of the single-draft invariant — a benign, documented
 * consequence — and K2 finishes its own sale to CLOSED. K3 finishes or cancels
 * everything it builds and restores every injected fault before returning, so no
 * fault and no in-progress sale leaks onto the next scenario. Ticket and session
 * counters are perpetual, so nothing is asserted as an absolute number.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupKIT {

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

    /** Single-price unit product, TTC 6,00 € — the discounted / plain cart line. */
    private static final String EAN_HUILE = "3300000000006";

    /** The uppercased line label of {@link #EAN_HUILE} on a ticket. */
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** A second single-price unit product, TTC 6,00 € — the second cart line. */
    private static final String EAN_MIEL = "3300000000024";

    /** In-store weight label (prefix 25, article PLU 4020, 1250 g), valid EAN13 — a weighed line. */
    private static final String WEIGHT_LABEL_VALID = "2504020012503";

    /** The line label of the weighed article (Pommes Golden, PLU 4020). */
    private static final String LABEL_POMMES = "POMMES GOLDEN";

    /** The article PLU carried by the weighed line. */
    private static final String PLU_POMMES = "4020";

    /** A valid fidelity card ({@code ^789\\d{12}$}, 15 digits). */
    private static final String CARD_1 = "789000000000001";

    /** The pinned terminal id of the E2E profile. */
    private static final String TERMINAL = "C04";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected both as the oracle for the
     * restored cart/payment and as the "memory wipe" lever of the crash
     * simulation ({@link PosState#clearTicket()}).
     */
    @Inject
    PosState posState;

    /**
     * The real recovery service, injected so the boot-time reconciliation
     * ({@link TicketRecoveryService#recover()}) can be replayed on demand as the
     * "restart" half of the crash simulation.
     */
    @Inject
    TicketRecoveryService recoveryService;

    /**
     * The real payment service, injected to confirm the virtual-TPE card demand
     * exactly as its {@code /api/hardware/tpe/accept} endpoint wraps it (the
     * inbound TPE endpoints are not on this app's surface — GroupF doctrine).
     */
    @Inject
    PaymentService paymentService;

    /**
     * K1 — Crash en plein panier : panier restauré à l'identique (uids, gestes structurés, fidélité), draft réconcilié, pas de doublon.
     * <p>
     * A three-line cart is built through the screen — an endorsed 1,00 € REMISE on
     * the huile line, a weighed PLU line from an in-store weight label, and an
     * attached fidelity card — so the persisted draft carries the stable uids, the
     * structured REMISE modifier, the weighed PLU and the fidelity card. The
     * transactional memory is then wiped ({@code clearTicket}, the crash) and the
     * boot reconciliation replayed ({@code recover}, the restart): the cart returns
     * with the SAME uids in the SAME order, the REMISE modifier and the weighed PLU
     * intact, the fidelity card reattached, the SAME draft id reconciled — and the
     * OPEN-draft count on C04 is still one (no duplicate). The restored cart also
     * renders on the sale screen with both lines and the lit loyalty icon.
     */
    @Test
    void k1_crash_en_plein_panier() {
        Page page = freshSale();
        // --- Build a three-line cart with structured gestures + fidelity ---
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        approveEndorsementWithManager(page);
        Assertions.assertEquals("REMISE", posState.ticket.items.get(0).modifierType,
                "the first line must carry a structured REMISE gesture");
        scan(EAN_MIEL);
        scan(WEIGHT_LABEL_VALID);
        page.getByText(LABEL_POMMES).waitFor();
        scan(CARD_1);
        page.locator("#fidIcon.active").waitFor();
        // --- Snapshot the pre-crash truth ---
        Assertions.assertEquals(3, posState.ticket.items.size(), "the pre-crash cart must hold three lines");
        List<String> uidsBefore = new ArrayList<>();
        for (TicketState.TicketItem it : posState.ticket.items) uidsBefore.add(it.uid);
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "building the cart must persist an OPEN draft");
        BigDecimal totalBefore = posState.ticket.totalAmount;
        Assertions.assertTrue(posState.fidelity.active, "the fidelity card must be attached before the crash");
        Assertions.assertEquals(CARD_1, posState.fidelity.label, "the attached card must be the scanned one");
        Assertions.assertEquals(1, openDraftCount(), "exactly one OPEN draft must exist before the crash");
        Assertions.assertEquals(3, draftLineCount(draftId), "the durable draft must persist the three lines");
        Assertions.assertEquals(CARD_1, draftFidelity(draftId), "the durable draft must persist the fidelity card");
        // --- CRASH: wipe the transactional memory (as /lock logout does) ---
        posState.clearTicket();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the crash must wipe the in-memory cart");
        Assertions.assertNull(posState.payment.ticketDbId, "the crash must drop the in-memory draft link");
        Assertions.assertFalse(posState.fidelity.active, "the crash must wipe the in-memory fidelity");
        // --- RESTART: replay the boot reconciliation ---
        recoveryService.recover();
        // --- The cart is restored IDENTICALLY ---
        Assertions.assertEquals(3, posState.ticket.items.size(), "recovery must restore the three lines");
        List<String> uidsAfter = new ArrayList<>();
        for (TicketState.TicketItem it : posState.ticket.items) uidsAfter.add(it.uid);
        Assertions.assertEquals(uidsBefore, uidsAfter, "recovery must restore the same line uids in the same order");
        Assertions.assertEquals("REMISE", posState.ticket.items.get(0).modifierType,
                "recovery must restore the structured REMISE modifier");
        Assertions.assertEquals(0, posState.ticket.items.get(0).modifierValue.compareTo(BigDecimal.ONE),
                "recovery must restore the 1,00 € REMISE value");
        Assertions.assertEquals(PLU_POMMES, posState.ticket.items.get(2).plu,
                "recovery must restore the weighed line's PLU");
        Assertions.assertEquals(0, posState.ticket.items.get(2).quantity.compareTo(new BigDecimal("1.250")),
                "recovery must restore the weighed line's quantity (1,250 kg)");
        Assertions.assertTrue(posState.fidelity.active, "recovery must reattach the fidelity card");
        Assertions.assertEquals(CARD_1, posState.fidelity.label, "recovery must restore the fidelity card label");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(totalBefore),
                "recovery must restore the same cart total");
        // --- Draft reconciled, no duplicate ---
        Assertions.assertEquals(draftId, posState.payment.ticketDbId,
                "recovery must reconcile onto the SAME draft, not a new one");
        Assertions.assertEquals(1, openDraftCount(), "recovery must create no duplicate OPEN draft");
        // --- The restored cart renders on the screen ---
        page.navigate(base.toString());
        page.getByText(LABEL_HUILE).waitFor();
        page.getByText(LABEL_POMMES).waitFor();
        page.locator("#fidIcon.active").waitFor();
        page.close();
    }

    /**
     * K2 — Crash en plein paiement : draft OPEN, paiements persistés, complétion possible.
     * <p>
     * A two-line cart (12,00 €) enters payment and a PARTIAL 5,00 € CARD payment is
     * registered on the draft. The transactional memory is wiped and recovered: the
     * draft stays OPEN, the CARD payment is rebuilt FROM THE DATABASE (the wipe
     * cleared it from memory first), the transaction is NOT prematurely completed
     * (7,00 € remain), and completion is then really carried through — a cash tender
     * of the balance completes the sale and finishing CLOSES the ticket, proving the
     * crash left a coherent, finishable state.
     */
    @Test
    void k2_crash_en_plein_paiement() {
        Page page = freshSale();
        // --- Build a plain 12,00 € cart and enter payment ---
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        scan(EAN_MIEL);
        Assertions.assertEquals(2, posState.ticket.items.size(), "the cart must hold the two lines");
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "the cart must persist an OPEN draft");
        goPay(page);
        // --- Register a PARTIAL card payment (5,00 € of 12,00 €) ---
        payThroughScreen(page, "CARTE BANCAIRE", "cardForm", "5", true);
        page.getByText("PAIEMENT CARTE EN COURS").waitFor();
        paymentService.confirmPendingCard(posState);
        Assertions.assertEquals(1, posState.payment.payments.size(), "the card payment must be registered");
        Assertions.assertEquals("CARD", posState.payment.payments.get(0).method, "the registered payment must be a card");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("7.00")),
                "the partial card must leave 7,00 € due");
        Assertions.assertEquals(1, draftPaymentCount(draftId), "the card payment must be persisted on the draft");
        Assertions.assertEquals(0, draftFirstPaymentAmount(draftId).compareTo(new BigDecimal("5.00")),
                "the persisted payment must be the 5,00 € card amount");
        // --- CRASH: wipe the transactional memory ---
        posState.clearTicket();
        Assertions.assertTrue(posState.payment.payments.isEmpty(), "the crash must wipe the in-memory payments");
        Assertions.assertNull(posState.payment.ticketDbId, "the crash must drop the in-memory draft link");
        // --- RESTART: replay the boot reconciliation ---
        recoveryService.recover();
        // --- Coherent restored state: OPEN draft, payment rebuilt from DB, not completed ---
        Assertions.assertEquals(draftId, posState.payment.ticketDbId, "recovery must reattach the same draft");
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, ticketStatus(draftId),
                "the draft must still be OPEN after the payment crash");
        Assertions.assertEquals(1, posState.payment.payments.size(),
                "recovery must rebuild the persisted card payment");
        Assertions.assertEquals("CARD", posState.payment.payments.get(0).method,
                "the rebuilt payment must be the card");
        Assertions.assertFalse(posState.payment.transactionComplete,
                "a partially-paid draft must NOT be marked complete after recovery");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("7.00")),
                "recovery must restore the 7,00 € still due");
        // --- Completion is possible: tender the balance and finish to CLOSED ---
        page.navigate(base.toString());
        page.getByText(LABEL_HUILE).waitFor();
        goPay(page);
        payCashComplete(page, "7");
        Assertions.assertTrue(posState.payment.transactionComplete, "the balance tender must complete the transaction");
        finishSale(page);
        Assertions.assertEquals(Ticket.TicketStatus.CLOSED, ticketStatus(draftId),
                "finishing the recovered sale must CLOSE the ticket");
        page.close();
    }

    /**
     * K3 — Matériel dégradé : imprimante coupée (vente continue), capteur tiroir mort (garde désactivée), afficheur coupé (silencieux).
     * <p>
     * Three peripheral faults are injected on the embedded simulator and the real
     * degraded-mode paths are proven. Printer cut (no paper): a sale still completes
     * and CLOSES fiscally, and an explicit receipt print reaches no paper (buffer
     * carries no ticket number, status NO_PAPER) yet never breaks the flow. Drawer
     * sensor dead: with the drawer physically OPEN, a live sensor diverts a guarded
     * screen to {@code /drawer-error} (baseline), but once the sensor answers 503,
     * {@code isDrawerOpen()} fails safe to false and the SAME guarded screen is
     * reached — the guard is disabled, not bricking. Display cut: a scan whose
     * handler pushes to the dead display still lands its line, in silence (no error).
     * Every fault is restored and every in-progress sale finished/cancelled before
     * returning.
     */
    @Test
    void k3_materiel_degrade() {
        Page page = freshSale();

        // === Printer cut → the sale continues and closes fiscally ===
        togglePaper(); // paper now ABSENT
        Assertions.assertEquals("NO_PAPER", printerStatus(), "the printer must be cut (no paper)");
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        Long ticketId = posState.payment.ticketDbId;
        Assertions.assertNotNull(ticketId, "the sale must persist despite the dead printer");
        goPay(page);
        payCashComplete(page, "6");
        // The cash tender opened the drawer; shut it so the drawer-guarded print
        // route is reachable, then print onto the dead printer (swallowed).
        closeDrawer();
        clearPrinter();
        page.locator("form[action='/action/print']").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("IMPRIMER")).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        String number = ticketNumber(ticketId);
        Assertions.assertFalse(printerContent().contains(number),
                "a cut printer must print no receipt (no ticket number on the paper)");
        Assertions.assertEquals("NO_PAPER", printerStatus(), "the printer must still read cut after the failed print");
        finishSale(page);
        Assertions.assertEquals(Ticket.TicketStatus.CLOSED, ticketStatus(ticketId),
                "the sale must complete and CLOSE despite the cut printer (vente continue)");
        togglePaper(); // restore paper

        // === Drawer sensor dead → the drawer guard is disabled, no brick ===
        // Baseline: a live sensor + an OPEN drawer diverts a guarded screen away.
        openDrawer();
        Assertions.assertEquals("OPEN", drawerStatus(), "the drawer is physically open for the baseline");
        page.navigate(base.toString());
        page.waitForURL("**/drawer-error");
        Assertions.assertTrue(page.url().contains("drawer-error"),
                "a live sensor reading OPEN must divert the guarded screen to /drawer-error");
        // Kill the sensor: isDrawerOpen() fails safe to false, the guard opens up.
        toggleDrawerSensor(); // sensor now DEAD (503)
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertFalse(page.url().contains("drawer-error"),
                "a dead sensor must disable the guard and reach the sale, not brick on /drawer-error");
        // Restore the sensor and shut the drawer for the rest of the scenario.
        toggleDrawerSensor(); // sensor ALIVE again
        closeDrawer();
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();

        // === Display cut → the scan is silent, the line still lands ===
        toggleDisplay(); // display now OFFLINE (503)
        posState.ticket.transientError = null;
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "a scan must land its line even with the display cut");
        Assertions.assertNull(posState.ticket.transientError,
                "a dead display must be swallowed in silence (no error)");
        toggleDisplay(); // restore the display
        // Clean up the in-progress sale (cancel the single line).
        page.navigate(base.toString() + "action/cancelLine");
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the K3 probe must end on a clean cart");
        page.close();
    }

    // --- Reusable gestures (login recipe, sale build) ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of the
     * shared open session — the standard start of every scenario.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshSale() {
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
        if (openSession() != null) return;
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
     * Navigates to the lock screen, presents the badge on the hardware scan bus and
     * taps the PIN, submitting the unlock — the shared login gesture.
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
     * Logs the cashier in and lands on an empty sale screen, assuming a session is
     * already open: the unlock pulse opens the drawer, the guard diverts to
     * /drawer-error, and the physical drawer close carries the drawer-status poll to
     * the sale.
     *
     * @param page the Playwright page driving the register
     */
    private void loginToSaleWithOpenSession(Page page) {
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Walks onto the payment overlay and waits until it is ready (the remaining line
     * is rendered).
     *
     * @param page the Playwright page driving the register
     */
    private void goPay(Page page) {
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Tenders an (over)paying cash amount on the payment overlay and waits for the
     * completion modal.
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
     * Drives a payment method through the screen: clicks its panel button,
     * optionally clears the prefilled amount, taps the digits and submits the
     * method's form.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param methodButton the exact visible method button text
     * @param formId the id of the method's submit form
     * @param digits the amount digits to tap, or null to submit the prefill
     * @param clearFirst whether to clear the prefilled amount before typing
     */
    private void payThroughScreen(Page page, String methodButton, String formId, String digits, boolean clearFirst) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(methodButton).setExact(true)).click();
        if (clearFirst) {
            page.locator("#payNumpadZone").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        }
        if (digits != null) tapDigits(page.locator("#payNumpadZone"), digits);
        page.locator("#" + formId + " button[type=submit]").click();
    }

    /**
     * Closes the completed transaction cleanly: pushes the drawer shut so the guard
     * lets the finish through, then taps NOUVELLE VENTE and waits for the empty sale
     * screen.
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
     * Opens the price-modification modal for a gesture, forcing the menu that
     * carries its button first (primary for REMISE), then taps the gesture button
     * and waits until the modal numpad is ready.
     *
     * @param page the Playwright page driving the register
     * @param type the price-mod type slug (remise)
     * @param linkName the exact visible button text
     */
    private void openGesture(Page page, String type, String linkName) {
        boolean secondary = "discount".equals(type) || "force_price".equals(type);
        page.navigate(base.toString() + "action/menu/" + (secondary ? "secondary" : "main"));
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(linkName).setExact(true)).click();
        page.locator("#priceKbArea").waitFor();
    }

    /**
     * Taps the value on the price-modal numpad and validates, parking the gesture
     * into a manager endorsement.
     *
     * @param page the Playwright page carrying the price modal
     * @param digits the value digits to tap
     */
    private void typeAndValidate(Page page, String digits) {
        tapDigits(page.locator("#priceKbArea"), digits);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
    }

    /**
     * Validates the pending endorsement as the manager: badges on the bus, taps the
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

    // --- Hardware bus & keypad ---

    /**
     * Presents a code on the hardware scan bus (scanner gun / simulator) and asserts
     * the bus accepted it — the uniform entry of every scanned code and the manager
     * badge on the endorsement modal.
     *
     * @param code the code to present on the bus
     */
    private void scan(String code) {
        APIResponse res = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the code " + code);
    }

    /**
     * Taps digits one by one on a numeric keypad, scoped under the given container
     * so no mode toggle is ever hit.
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
     * @return "OPEN"/"CLOSED", or the 503 error body when the sensor is dead
     */
    private String drawerStatus() {
        return context.request().get(base.toString() + "api/hardware/drawer/status").text().trim();
    }

    /**
     * Fires the drawer-opening pulse on the hardware bus (the embedded simulator).
     */
    private void openDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/open", RequestOptions.create());
    }

    /**
     * Pushes the drawer shut on the hardware bus (the embedded simulator), clearing
     * the drawer guard.
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Toggles the simulator's drawer sensor between alive and dead — a dead sensor
     * makes {@code GET /drawer/status} answer 503.
     */
    private void toggleDrawerSensor() {
        context.request().post(base.toString() + "api/hardware/drawer/toggle-sensor", RequestOptions.create());
    }

    /**
     * Toggles the simulator's customer display between alive and dead — a dead
     * display makes {@code POST /display} answer 503.
     */
    private void toggleDisplay() {
        context.request().post(base.toString() + "api/hardware/display/toggle", RequestOptions.create());
    }

    /**
     * Toggles the simulator's printer paper presence — absent paper makes
     * {@code POST /printer/print} answer 503.
     */
    private void togglePaper() {
        context.request().post(base.toString() + "api/hardware/printer/toggle-paper", RequestOptions.create());
    }

    /**
     * Reads the printer status from the simulator.
     *
     * @return "OK" or "NO_PAPER"
     */
    private String printerStatus() {
        return context.request().get(base.toString() + "api/hardware/printer/status").text().trim();
    }

    /**
     * Empties the printer simulator buffer so the next print is read in isolation.
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
     * Reads the single OPEN session on the pinned terminal from the database.
     *
     * @return the OPEN session on C04, or null when none is open
     */
    private com.intermarche.pos.domain.CashSession openSession() {
        return QuarkusTransaction.requiringNew().call(
                () -> com.intermarche.pos.domain.CashSession.findOpenByTerminal(TERMINAL));
    }

    /**
     * Counts the OPEN drafts of the pinned terminal — the oracle for the
     * single-draft (no-duplicate) invariant.
     *
     * @return the number of OPEN {@link Ticket} rows on C04
     */
    private long openDraftCount() {
        return QuarkusTransaction.requiringNew().call(
                () -> Ticket.count("status = ?1 and terminalId = ?2", Ticket.TicketStatus.OPEN, TERMINAL));
    }

    /**
     * Reads a ticket's status from the database.
     *
     * @param ticketId the database id of the ticket
     * @return the ticket status
     */
    private Ticket.TicketStatus ticketStatus(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).status);
    }

    /**
     * Reads a ticket's register number from the database.
     *
     * @param ticketId the database id of the ticket
     * @return the ticket number, e.g. "C04-00000007"
     */
    private String ticketNumber(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).ticketNumber);
    }

    /**
     * Counts the persisted lines of a draft.
     *
     * @param ticketId the database id of the draft
     * @return the number of persisted lines
     */
    private int draftLineCount(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).lines.size());
    }

    /**
     * Reads the persisted fidelity card of a draft.
     *
     * @param ticketId the database id of the draft
     * @return the persisted fidelity card, or null
     */
    private String draftFidelity(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).fidelityCard);
    }

    /**
     * Counts the persisted payments of a draft.
     *
     * @param ticketId the database id of the draft
     * @return the number of persisted payments
     */
    private int draftPaymentCount(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).payments.size());
    }

    /**
     * Reads the amount of a draft's first persisted payment.
     *
     * @param ticketId the database id of the draft
     * @return the first payment's amount
     */
    private BigDecimal draftFirstPaymentAmount(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Ticket) Ticket.findById(ticketId)).payments.get(0).amount);
    }
}
