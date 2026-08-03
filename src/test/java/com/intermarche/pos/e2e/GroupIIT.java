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

import java.math.BigDecimal;
import java.net.URL;

/**
 * Group I end-to-end scenarios (Session &amp; clôture Z), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Every group-I
 * scenario is {@code [S]} (caisse seule + simulateur), so I1–I4 are all
 * implemented here and nothing is left as residue.
 * <p>
 * <b>The day's bookkeeping, exactly as the cashier walks it.</b> The cashier
 * comes back to the SESSION screen DELIBERATELY (via the menu) for the mid-day
 * read and the evening close: I1 prints the read-only X report from the
 * {@code RAPPORT X (LECTURE)} link and proves it mutates nothing; I2 walks the
 * whole Z close — {@code CLÔTURE DE CAISSE (Z)} opens the drawer and lands on
 * the drawer-count page, whose CLIENT-SIDE calculator really runs (tapping
 * denomination counts on the numpad sums the total live), the counted total and
 * its per-denomination JSON travel to {@code /action/session/close}, the session
 * flips CLOSED with the écart computed, and a fresh session then opens (nouvelle
 * session possible); I3 proves the three side effects of the Z (PARKED tickets
 * cancelled, the sale blocked with {@code AUCUNE SESSION OUVERTE - MENU CAISSE}
 * until reopening, and the next login routed to the morning session screen —
 * the A1 flow); I4 proves the Z is refused in training mode.
 * <p>
 * <b>The drawer is real.</b> Both the unlock pulse and the Z's
 * {@code close-start} physically open the drawer, so every guarded screen
 * (sale) is reached only after the cashier's physical close
 * ({@code POST /api/hardware/drawer/close}); the counting page itself is
 * {@code @DrawerMayBeOpen} by design (counting happens drawer-out).
 * <p>
 * <b>Oracles.</b> The printer simulator is the oracle for the X/Z paper
 * ({@code RAPPORT X - LECTURE} vs {@code RAPPORT Z - CLOTURE}, the ECART line);
 * the database ({@link CashSession}, {@link Ticket}) is the oracle for the
 * fiscal moment — the session status flip, the counted / theoretical / variance
 * / withdrawal figures, the per-denomination detail and the PARKED → CANCELLED
 * transition; the drawer-count page's {@code #totalDisplay} is the oracle for
 * the live client-side calculator; the injected {@link PosState} is the oracle
 * for the invisible sale block (the session-gate message set WITHOUT a
 * poll-bumping touch, exactly the group-A unknown-code lesson).
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (i1…i4); each ensures an OPEN session at its
 * start (opened once through the full prise-de-poste on the fresh
 * drop-and-create H2 boot, re-opened by whichever scenario closed it) and
 * RESTORES an open session before it returns, so a scenario that closes the
 * register with a Z leaves the next one a clean, freshly-opened session.
 * Session and ticket counters are perpetual, so numbers are asserted by format
 * and by delta, never as absolute values.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupIIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The opening float of every session opened here (euros, comma decimal). */
    private static final String OPENING_FLOAT = "200,00";

    /** The opening float as a plain amount, for the theoretical-cash oracle. */
    private static final BigDecimal OPENING_FLOAT_AMOUNT = new BigDecimal("200.00");

    /** Single-price unit product, TTC 6,00 € — the clean cart fixture. */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected to assert the invisible half of
     * I3: the session-gate message set on a refused sale scan WITHOUT a
     * poll-bumping touch, and the training-mode flag of I4.
     */
    @Inject
    PosState posState;

    /**
     * I1 — Rapport X (lecture seule, imprimable à tout moment, ne mute rien).
     * <p>
     * From the session screen the cashier prints the X report at any time: the
     * paper is a {@code RAPPORT X - LECTURE} snapshot carrying the session
     * number, the float and the theoretical cash but NO closing block (no ECART,
     * no {@code RAPPORT Z}). Printing it mutates nothing — the session stays
     * OPEN with the same number and no closing date, and a second X prints just
     * the same, proving the read is repeatable.
     */
    @Test
    void i1_rapport_x_lecture_seule() {
        Page page = freshSale();
        CashSession before = openSessionOnC04();
        Assertions.assertNotNull(before, "the shared session must be open before the X read");
        String sessionNumber = before.sessionNumber;
        // Walk to the session screen and print the X report from its link.
        page.navigate(base.toString() + "session");
        clearPrinter();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("RAPPORT X (LECTURE)").setExact(true)).click();
        page.getByText("Session en cours").waitFor();
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("RAPPORT X - LECTURE"), "the paper must be an X read report");
        Assertions.assertTrue(paper.contains(sessionNumber), "the X report must carry the session number");
        Assertions.assertTrue(paper.contains("Especes theorique"), "the X report must carry the theoretical cash");
        Assertions.assertTrue(paper.contains("Fond de caisse"), "the X report must carry the opening float");
        Assertions.assertFalse(paper.contains("RAPPORT Z"), "an X read must never print a Z closing");
        Assertions.assertFalse(paper.contains("ECART"), "an X read must never print the closing variance");
        // A read mutates nothing: the session is still OPEN, same number, unclosed.
        CashSession after = openSessionOnC04();
        Assertions.assertNotNull(after, "the X read must leave the session OPEN");
        Assertions.assertEquals(sessionNumber, after.sessionNumber, "the X read must not renumber the session");
        Assertions.assertNull(closingDateOf(sessionNumber), "the X read must not set a closing date");
        // A second X prints identically — the read is repeatable at any time.
        clearPrinter();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("RAPPORT X (LECTURE)").setExact(true)).click();
        page.getByText("Session en cours").waitFor();
        Assertions.assertTrue(printerContent().contains("RAPPORT X - LECTURE"),
                "the X read must be printable again with the session still open");
        Assertions.assertNotNull(openSessionOnC04(), "two X reads must still leave the session OPEN");
        page.close();
    }

    /**
     * I2 — Comptage Z (calculette par coupures, écart, prélèvement → session CLOSED).
     * <p>
     * The Z runs through the drawer-count page: the client-side calculator sums
     * the tapped denomination counts live (4 × 50 € + 1 × 10 € = 210,00 €, read
     * back off {@code #totalDisplay}), the validated total travels as a String
     * with its per-denomination JSON, a 50,00 € withdrawal is entered, and the
     * session flips CLOSED. The database carries the counted amount, the
     * theoretical cash (the 200,00 € float, no sales), the computed écart
     * (210,00 − 200,00 = +10,00 €), the withdrawal and the denomination detail;
     * the paper is a {@code RAPPORT Z - CLOTURE} with an ECART line. A fresh
     * session then opens with the next sequential number — nouvelle session
     * possible.
     */
    @Test
    void i2_comptage_z_ecart_prelevement_cloture() {
        Page page = freshSale();
        CashSession open = openSessionOnC04();
        Assertions.assertNotNull(open, "the shared session must be open before the Z");
        String closingNumber = open.sessionNumber;
        // Walk the Z: session screen -> CLÔTURE (opens the drawer) -> counting page.
        page.navigate(base.toString() + "session");
        clearPrinter();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("CLÔTURE DE CAISSE (Z)").setExact(true)).click();
        page.getByText("Total Compté").waitFor();
        Assertions.assertEquals("OPEN", drawerStatus(), "starting the Z must physically open the drawer");
        // Tap the denominations — the client-side calculator sums them live.
        countDenomination(page, "Billet 50 €", "4");
        countDenomination(page, "Billet 10 €", "1");
        Assertions.assertEquals("210,00 €", page.locator("#totalDisplay").textContent().trim(),
                "the live calculator must sum 4×50 + 1×10 to 210,00 €");
        page.locator("#withdrawn").fill("50");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("CLÔTURER (Z)").setExact(true)).click();
        page.waitForURL("**/lock");
        // Database oracle: the session is closed with the counted/theoretical/écart.
        Assertions.assertNull(openSessionOnC04(), "the Z must leave no open session");
        ClosedSessionView z = closedSession(closingNumber);
        Assertions.assertEquals("CLOSED", z.status, "the Z must flip the session CLOSED");
        Assertions.assertNotNull(z.closingDate, "the Z must stamp a closing date");
        Assertions.assertEquals(0, z.countedAmount.compareTo(new BigDecimal("210.00")),
                "the counted amount must be the calculator total (210,00 €)");
        Assertions.assertEquals(0, z.theoreticalAmount.compareTo(OPENING_FLOAT_AMOUNT),
                "the theoretical cash must be the float with no sales (200,00 €)");
        Assertions.assertEquals(0, z.variance.compareTo(new BigDecimal("10.00")),
                "the écart must be counted minus theoretical (210,00 − 200,00 = +10,00 €)");
        Assertions.assertEquals(0, z.withdrawnAmount.compareTo(new BigDecimal("50.00")),
                "the withdrawal must be the entered 50,00 €");
        Assertions.assertNotNull(z.countDetail, "the Z must persist the per-denomination detail");
        Assertions.assertTrue(z.countDetail.contains("b50") && z.countDetail.contains("b10"),
                "the denomination detail must carry the counted coupures (b50, b10)");
        // Paper oracle: a Z closing with a variance line.
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("RAPPORT Z - CLOTURE"), "the paper must be a Z closing report");
        Assertions.assertTrue(paper.contains("ECART"), "the Z paper must carry the variance line");
        Assertions.assertTrue(paper.contains("Especes comptees"), "the Z paper must carry the counted cash");
        Assertions.assertTrue(paper.contains("Prelevement"), "the Z paper must carry the withdrawal");
        page.close();
        // Nouvelle session possible: a fresh session opens with the next number.
        ensureOpenSession();
        CashSession reopened = openSessionOnC04();
        Assertions.assertNotNull(reopened, "a new session must be openable right after the Z");
        Assertions.assertEquals(sequenceOfSession(closingNumber) + 1, sequenceOfSession(reopened.sessionNumber),
                "the reopened session must take the next sequential number");
    }

    /**
     * I3 — Effets du Z (PARKED annulés, vente bloquée MENU CAISSE, flux du matin).
     * <p>
     * A ticket parked before the Z is CANCELLED by the closing (it never
     * outlives its session). Once closed, a sale scan is refused with
     * {@code AUCUNE SESSION OUVERTE - MENU CAISSE} — the session gate sets the
     * message WITHOUT a poll-bumping touch, so it is read on the injected state,
     * and the cart stays empty. And the next login takes the A1 morning flow:
     * with no session open, the unlock lands the cashier straight on the session
     * screen ({@code Aucune session ouverte}) to open the day. Reopening a
     * session restores the register for the next scenario.
     */
    @Test
    void i3_effets_du_z_parked_annules_vente_bloquee() {
        Page page = freshSale();
        Assertions.assertNotNull(openSessionOnC04(), "the shared session must be open before the Z");
        // Park a cart: scan the oil, then park it — a PARKED ticket now exists.
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        page.navigate(base.toString() + "action/parked/park");
        page.getByText("TOTAL À PAYER").waitFor();
        Long parkedId = parkedTicketId();
        Assertions.assertNotNull(parkedId, "parking must leave a PARKED ticket on the register");
        Assertions.assertEquals("PARKED", ticketStatus(parkedId), "the parked ticket must be PARKED before the Z");
        // Close the session with a Z.
        page.navigate(base.toString() + "session");
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("CLÔTURE DE CAISSE (Z)").setExact(true)).click();
        page.getByText("Total Compté").waitFor();
        countDenomination(page, "Billet 50 €", "4");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("CLÔTURER (Z)").setExact(true)).click();
        page.waitForURL("**/lock");
        // Effect 1: the parked ticket died with the session.
        Assertions.assertNull(openSessionOnC04(), "the Z must leave no open session");
        Assertions.assertEquals("CANCELLED", ticketStatus(parkedId),
                "the Z must cancel the register's parked tickets");
        page.close();
        // Effect 3: the next login takes the A1 morning flow (session screen).
        Page morning = context.newPage();
        scanBadgeAndEnterPin(morning, CASHIER_BADGE, CASHIER_PIN);
        morning.getByText("Aucune session ouverte").waitFor();
        Assertions.assertFalse(posState.isLocked(), "the morning login must leave the cashier logged in");
        // Effect 2: with no session, a sale scan is blocked (message set without touch).
        posState.ticket.transientError = null;
        scan(EAN_HUILE);
        Assertions.assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", posState.ticket.transientError,
                "a sale scan without a session must be refused with the MENU CAISSE message");
        Assertions.assertTrue(posState.ticket.items.isEmpty(),
                "a blocked sale scan must add nothing to the cart");
        // Restore: reopen a session from the morning screen for the next scenario.
        closeDrawer();
        morning.locator("#openingFloat").fill(OPENING_FLOAT);
        morning.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        morning.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertNotNull(openSessionOnC04(), "the register must end with a fresh open session");
        morning.close();
    }

    /**
     * I4 — Z en formation → bloqué.
     * <p>
     * In training mode the Z is refused: the {@code close-start} route diverts
     * back to the session screen with the {@code INDISPONIBLE EN FORMATION}
     * query flag instead of reaching the drawer-count page, and the open session
     * is left untouched (never closed). Leaving training restores the clean
     * register.
     */
    @Test
    void i4_z_en_formation_bloque() {
        Page page = freshSale();
        CashSession open = openSessionOnC04();
        Assertions.assertNotNull(open, "the shared session must be open before the training Z");
        String sessionNumber = open.sessionNumber;
        // Enter training mode (manager endorsement).
        page.navigate(base.toString() + "action/training");
        approveEndorsementWithManager(page);
        page.getByText("MODE FORMATION").first().waitFor();
        Assertions.assertTrue(posState.trainingMode, "the endorsed toggle must enter training mode");
        // Attempt the Z: the close-start diverts to the session screen, blocked.
        page.navigate(base.toString() + "action/session/close-start");
        page.getByText("Session en cours").waitFor();
        Assertions.assertTrue(page.url().contains("INDISPONIBLE"),
                "the training Z must divert with the INDISPONIBLE EN FORMATION flag");
        Assertions.assertFalse(page.url().contains("cash-count"),
                "the training Z must never reach the drawer-count page");
        // The session is untouched: still OPEN, same number, no closing date.
        CashSession still = openSessionOnC04();
        Assertions.assertNotNull(still, "the training Z must leave the session OPEN");
        Assertions.assertEquals(sessionNumber, still.sessionNumber, "the training Z must not renumber the session");
        Assertions.assertNull(closingDateOf(sessionNumber), "the training Z must not close the session");
        // Restore: leave training mode for a clean register.
        page.navigate(base.toString() + "action/training");
        approveEndorsementWithManager(page);
        Assertions.assertFalse(posState.trainingMode, "leaving training must clear the training flag");
        page.close();
    }

    // --- Reusable gestures (login recipe, hardware bus, counting, endorsement) ---

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
     * Ensures the register carries an OPEN session, opening one through the full
     * prise-de-poste screen when none is open (the first scenario on the fresh
     * boot, and every scenario that closed the register with a Z).
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
     * and taps the PIN, submitting the unlock — the shared login gesture. The
     * cashier lands on the sale screen when a session is open, on the session
     * screen otherwise (the A1 morning flow).
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
     * Selects a denomination on the drawer-count page and taps its count on the
     * numpad; the page's client-side calculator adds it to the running total.
     *
     * @param page the Playwright page carrying the counting screen
     * @param label the exact denomination label (e.g. "Billet 50 €")
     * @param qty the count digits to tap on the numpad
     */
    private void countDenomination(Page page, String label, String qty) {
        page.getByText(label, new Page.GetByTextOptions().setExact(true)).click();
        tapDigits(page.locator("#numpadArea"), qty);
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
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
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
     * Empties the printer simulator buffer so the next report is read in
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
     * Reads a closed session's fiscal fields by number into a plain snapshot,
     * so no lazy field is touched outside the transaction.
     *
     * @param sessionNumber the session number
     * @return a snapshot of the session's closing fields
     */
    private ClosedSessionView closedSession(String sessionNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            CashSession s = CashSession.find("sessionNumber", sessionNumber).firstResult();
            return new ClosedSessionView(s.status.name(), s.closingDate, s.countedAmount,
                    s.theoreticalAmount, s.variance, s.withdrawnAmount, s.countDetail);
        });
    }

    /**
     * Reads the status name of a ticket from the database.
     *
     * @param ticketId the database id of the ticket
     * @return the ticket status name (e.g. PARKED, CANCELLED)
     */
    private String ticketStatus(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return t.status.name();
        });
    }

    /**
     * Reads the id of the first PARKED ticket on terminal C04, or null.
     *
     * @return the parked ticket id, or null when none is parked
     */
    private Long parkedTicketId() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.find("terminalId = ?1 and status = ?2",
                    "C04", Ticket.TicketStatus.PARKED).firstResult();
            return t != null ? t.id : null;
        });
    }

    /**
     * Extracts the numeric sequence of a session number ("C04-S00001" -> 1).
     *
     * @param sessionNumber the register session number
     * @return the 5-digit sequence as an int
     */
    private int sequenceOfSession(String sessionNumber) {
        return Integer.parseInt(sessionNumber.substring(sessionNumber.indexOf('S') + 1));
    }

    /**
     * Immutable snapshot of a closed session's closing fields, read off the
     * database so assertions never touch a lazy field or a live reference.
     */
    private static final class ClosedSessionView {
        /** The lifecycle status name. */
        final String status;
        /** The closing date, or null while open. */
        final java.time.LocalDateTime closingDate;
        /** The counted cash amount. */
        final BigDecimal countedAmount;
        /** The theoretical cash (float + cash payments - cash refunds). */
        final BigDecimal theoreticalAmount;
        /** The variance (counted - theoretical). */
        final BigDecimal variance;
        /** The withdrawn amount. */
        final BigDecimal withdrawnAmount;
        /** The per-denomination detail JSON. */
        final String countDetail;

        /**
         * Creates a closed-session snapshot.
         *
         * @param status the status name
         * @param closingDate the closing date
         * @param countedAmount the counted amount
         * @param theoreticalAmount the theoretical cash
         * @param variance the variance
         * @param withdrawnAmount the withdrawal
         * @param countDetail the denomination detail JSON
         */
        ClosedSessionView(String status, java.time.LocalDateTime closingDate, BigDecimal countedAmount,
                          BigDecimal theoreticalAmount, BigDecimal variance, BigDecimal withdrawnAmount,
                          String countDetail) {
            this.status = status;
            this.closingDate = closingDate;
            this.countedAmount = countedAmount;
            this.theoreticalAmount = theoreticalAmount;
            this.variance = variance;
            this.withdrawnAmount = withdrawnAmount;
            this.countDetail = countDetail;
        }
    }
}
