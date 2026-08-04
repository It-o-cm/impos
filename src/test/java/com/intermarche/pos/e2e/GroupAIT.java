package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
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
import java.time.LocalDateTime;

/**
 * Group A end-to-end scenarios (Prise de poste &amp; authentification), played
 * THROUGH THE SCREEN with a headless Chromium browser (quarkus-playwright)
 * against the real application (live H2, real beans, embedded hardware
 * simulator). Group A is entirely {@code [S]} (caisse seule + simulateur), so
 * every scenario A1–A7 is implemented here; there is no {@code [V]}/{@code [N]}
 * residue for this letter.
 * <p>
 * <b>Ordering &amp; shared session.</b> The seven scenarios run under
 * {@link MethodOrderer.MethodName} (a1…a7) and share ONE cash session opened by
 * A1 on the fresh, drop-and-create H2 boot: A1 is the only scenario that
 * requires "no session yet" and, being first, meets a clean base; A2 relocks
 * and returns to it without opening a second one, and A3–A7 all act inside that
 * same open session (the model is one session per register, not per cashier).
 * Nothing in the group closes the session (a Z would), so it stays OPEN for the
 * whole class — one Quarkus boot for the letter, per the campaign contract.
 * <p>
 * <b>Login recipe.</b> Every scenario resets the register by navigating to
 * {@code /lock} (which logs the current operator out and empties the one-shot
 * badge mailbox) and then presents the badge as a HARDWARE gesture on the scan
 * bus ({@code POST /api/pos/scan}); the 1s {@code /lock-data} poll flips the
 * overlay to PIN entry. The PIN is tapped on the {@code #keyboardArea} numpad
 * and submitted with {@code #actionBtn} — auto-wait only, never a sleep. A
 * successful unlock really opens the drawer (the embedded simulator), so a
 * relock into an already-open session lands on {@code /} while the drawer is
 * out and the {@code @DrawerMustBeClosed} guard diverts to
 * {@code /drawer-error}; the cashier's physical close
 * ({@code POST /api/hardware/drawer/close}) then lets the drawer-status poll
 * carry the screen to the sale. Assertions are the exact catalog texts, the
 * resulting database state read straight from Panache, and — for A3 — the
 * employee lockout counters.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupAIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected to assert the invisible half of
     * A5: a badge scanned while logged in leaves NO reactive touch (the unknown
     * handler writes the message without bumping the version), so the "ignored"
     * outcome is observed on the state, not the polled screen.
     */
    @Inject
    PosState posState;

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
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(CASHIER_BADGE));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        // The poll-driven overlay switches to "enter your PIN": wait on the
        // catalog text, never on a sleep.
        page.getByText("Entrez votre code PIN :").waitFor();
        // Tap the four PIN digits on the on-screen numpad.
        tapPin(page, CASHIER_PIN);
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
        closeDrawer();
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
        CashSession session = openSessionOnC04();
        Assertions.assertNotNull(session, "an OPEN session must exist on terminal C04");
        Assertions.assertEquals(CashSession.SessionStatus.OPEN, session.status,
                "the session must be OPEN");
        Assertions.assertTrue(session.sessionNumber.matches("C04-S\\d{5}"),
                "the session number must match the C04-Sxxxxx format, was " + session.sessionNumber);
        Assertions.assertEquals(0, session.openingFloat.compareTo(new BigDecimal("200.00")),
                "the opening float must be 200,00 €");
        page.close();
    }

    /**
     * A2 — Reverrouillage en journée.
     * <p>
     * A session is already open (A1). The cashier locks the register and badges
     * back in: the unlock routes STRAIGHT to the sale (no session detour), the
     * drawer pulse is closed on the way, and NO second session is created — the
     * same {@code C04-Sxxxxx} number the register carried before the relock is
     * still the one open session in the database.
     */
    @Test
    void a2_reverrouillage_en_journee_retour_direct_vente() {
        // Prerequisite: an OPEN session must already exist on this register.
        String numberBefore = openSessionOnC04().sessionNumber;
        Page page = context.newPage();
        // Mid-day relock, then badge back in.
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        // Session already open: the unlock redirects to "/" (never "/session"),
        // where the drawer guard diverts to /drawer-error until the drawer is
        // pushed shut — the physical close then carries the poll to the sale.
        // Reaching "TOTAL À PAYER" (and not the "Aucune session ouverte"
        // heading) proves the direct-to-sale routing with no session detour.
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        // No new session was opened: same number, still the single OPEN session.
        CashSession after = openSessionOnC04();
        Assertions.assertNotNull(after, "the session must still be OPEN after the relock");
        Assertions.assertEquals(CashSession.SessionStatus.OPEN, after.status,
                "the session must remain OPEN");
        Assertions.assertEquals(numberBefore, after.sessionNumber,
                "a mid-day relock must NOT open a second session");
        page.close();
    }

    /**
     * A3 — Verrouillage PIN.
     * <p>
     * Three wrong PINs lock the cashier account for the configured window: the
     * lock screen shows {@code COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD}. A good
     * PIN presented DURING the lockout is still refused with the same page
     * message. Once the lockout has expired (time simulated by aging
     * {@code lockedUntil} in the database — five real minutes cannot be waited),
     * the good PIN is accepted, and the success RESETS the counters
     * ({@code failedAttempts = 0}, {@code lockedUntil = null}).
     */
    @Test
    void a3_verrouillage_pin_apres_trois_echecs() {
        Page page = context.newPage();
        // Three consecutive wrong PINs. The third crosses the threshold and
        // flips the page message from IDENTIFIANTS INCORRECTS to the lockout.
        scanBadgeAndEnterPin(page, CASHIER_BADGE, "0000");
        scanBadgeAndEnterPin(page, CASHIER_BADGE, "0000");
        scanBadgeAndEnterPin(page, CASHIER_BADGE, "0000");
        page.getByText("COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD").waitFor();
        // The account is locked in the database.
        Assertions.assertTrue(cashier().isCurrentlyLocked(),
                "the cashier account must be locked after three failures");
        // A CORRECT PIN during the lockout is refused without even checking it:
        // the same lockout message stays on the page.
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.getByText("COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD").waitFor();
        // Simulate the lockout window elapsing (no five-minute sleep): age the
        // stored lockedUntil into the past.
        expireCashierLockout();
        // Now the good PIN is accepted: prise de poste with the session already
        // open lands on the sale (via the drawer-close detour).
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        // The success reset both counters to zero.
        Employee c = cashier();
        Assertions.assertEquals(0, c.failedAttempts,
                "a successful login must reset the failure counter");
        Assertions.assertNull(c.lockedUntil,
                "a successful login must clear the lockout timestamp");
        page.close();
    }

    /**
     * A4 — Badge sur écran de lock.
     * <p>
     * Scanning a badge on the lock screen prefills the login through the
     * one-shot mailbox: the overlay flips to PIN entry and the cashier only taps
     * the PIN — the login field is never typed — yet the unlock succeeds,
     * proving the badge prefilled the login. A subsequent fresh {@code /lock}
     * does NOT re-trigger PIN entry (the mailbox is one-shot: the badge was
     * consumed, not persisted).
     */
    @Test
    void a4_badge_sur_ecran_de_lock_prefill_login() {
        String root = base.toString();
        Page page = context.newPage();
        // Present the badge; the poll flips the overlay to PIN entry, login
        // prefilled from the mailbox.
        page.navigate(root + "lock");
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(CASHIER_BADGE));
        Assertions.assertTrue(scan.ok(), "the scan bus should accept the badge");
        page.getByText("Entrez votre code PIN :").waitFor();
        // Tap ONLY the PIN and submit: no login was ever typed, so a successful
        // unlock proves the badge prefilled the login field.
        tapPin(page, CASHIER_PIN);
        page.locator("#actionBtn").click();
        // Session already open (A1): direct to sale via the drawer-close detour.
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(page.locator("#opInfo").textContent().contains("Jean Dupont"),
                "the badge-prefilled login must unlock as the cashier");
        // One-shot mailbox: a fresh lock screen without a new scan stays in its
        // initial state — the PIN prompt is NOT shown (badge not persisted).
        Page fresh = context.newPage();
        fresh.navigate(root + "lock");
        Assertions.assertEquals(0, fresh.getByText("Entrez votre code PIN :").count(),
                "the badge mailbox is one-shot: a fresh lock must not re-enter PIN mode");
        // Close both pages: a lingering lock screen keeps polling /lock-data and
        // would steal the one-shot badge mailbox from the next scenario.
        fresh.close();
        page.close();
    }

    /**
     * A5 — Badge en session ouverte, sans modale : ignoré.
     * <p>
     * With an operator logged in on the sale screen and no modal open, a badge
     * scanned on the bus triggers NO automatic operator switch: the auth handler
     * ignores it (no operator change), so the code falls all the way through the
     * chain to the unknown-code fallback. That fallback writes its message
     * WITHOUT a {@code touch()}, so it never reaches the polled screen — the
     * "ignored" outcome is therefore asserted on the server state (operator
     * unchanged, no line, the code landed on the unknown handler and not on
     * auth), plus the still-rendered sale screen (same operator, empty cart).
     */
    @Test
    void a5_badge_en_session_ouverte_sans_modale_ignore() {
        String root = base.toString();
        Page page = context.newPage();
        // Log in on the sale screen (session already open).
        loginToSaleWithOpenSession(page);
        Long operatorBefore = posState.auth.operatorId;
        Assertions.assertNotNull(operatorBefore, "the cashier must be logged in before the scan");
        // Scan the badge while logged in with no modal open.
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(CASHIER_BADGE));
        Assertions.assertTrue(scan.ok(), "the scan bus should accept the badge");
        // Server oracle: no operator switch, no sale line, and the badge fell
        // through the auth handler down to the unknown-code fallback (proving it
        // was NOT consumed as a login), which sets the message with no touch.
        Assertions.assertEquals(operatorBefore, posState.auth.operatorId,
                "an ignored badge must not switch the operator");
        Assertions.assertTrue(posState.ticket.items.isEmpty(),
                "an ignored badge must not create a sale line");
        Assertions.assertEquals("CODE INCONNU: " + CASHIER_BADGE, posState.ticket.transientError,
                "the ignored badge must fall through auth to the unknown-code fallback");
        // Screen oracle: the sale is unchanged — same operator, empty cart (the
        // untouched message never reaches the poll, which is the point).
        Assertions.assertTrue(page.locator("#opInfo").textContent().contains("Jean Dupont"),
                "the sale screen must keep the same operator");
        Assertions.assertTrue(page.getByText("EN ATTENTE D'ARTICLES...").isVisible(),
                "the sale screen must show no line for an ignored badge");
        page.close();
    }

    /**
     * A6 — Changement de PIN.
     * <p>
     * On the PIN-change page: a wrong current PIN is refused
     * ({@code Code PIN actuel incorrect}); a new PIN that does not match its
     * confirmation is refused ({@code Les nouveaux codes ne correspondent pas});
     * a valid change succeeds ({@code Code PIN modifié}) and the operator can
     * reconnect with the new PIN. The scenario restores the seed PIN at the end
     * to keep the class isolated.
     */
    @Test
    void a6_changement_de_pin() {
        String root = base.toString();
        Page page = context.newPage();
        // Reach the PIN-change page as the logged-in cashier.
        loginToSaleWithOpenSession(page);
        page.navigate(root + "pin-change");
        page.locator("#dispCurrent").waitFor();
        // Wrong current PIN → refusal.
        submitPinChange(page, "0000", "9999", "9999");
        page.getByText("Code PIN actuel incorrect").waitFor();
        // Correct current PIN but mismatched confirmation → refusal.
        submitPinChange(page, CASHIER_PIN, "9999", "8888");
        page.getByText("Les nouveaux codes ne correspondent pas").waitFor();
        // Valid change → success.
        submitPinChange(page, CASHIER_PIN, "9999", "9999");
        page.getByText("Code PIN modifié").waitFor();
        // Reconnect with the NEW PIN (session open → sale via the drawer detour).
        scanBadgeAndEnterPin(page, CASHIER_BADGE, "9999");
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(page.locator("#opInfo").textContent().contains("Jean Dupont"),
                "the operator must reconnect with the new PIN");
        // Restore the seed PIN so the rest of the class keeps using 1234.
        page.navigate(root + "pin-change");
        page.locator("#dispCurrent").waitFor();
        submitPinChange(page, "9999", CASHIER_PIN, CASHIER_PIN);
        page.getByText("Code PIN modifié").waitFor();
        page.close();
    }

    /**
     * A7 — Logout avec panier en cours.
     * <p>
     * With one line scanned into the cart (an OPEN draft persisted in the
     * database), a logout ({@code /lock}) abandons the in-memory cart but leaves
     * the draft INTACT and still OPEN in the database (the stale draft is only
     * cancelled by the next register-restart recovery, never by the logout). At
     * the next login the sale screen comes back empty — the memory cart was
     * abandoned — while the persisted draft still stands.
     */
    @Test
    void a7_logout_abandonne_le_panier_memoire_draft_intact() {
        String root = base.toString();
        Page page = context.newPage();
        loginToSaleWithOpenSession(page);
        // Scan a catalog unit product: a line appears and the draft is persisted.
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData("3300000000002"));
        Assertions.assertTrue(scan.ok(), "the scan bus should accept the EAN");
        page.getByText("LAIT UHT 1L").waitFor();
        // Database oracle: the OPEN draft on C04 carries the scanned line.
        Ticket draftBefore = openDraftOnC04();
        Assertions.assertNotNull(draftBefore, "scanning a line must persist an OPEN draft");
        Long draftId = draftBefore.id;
        int lineCount = QuarkusTransaction.requiringNew()
                .call(() -> Ticket.<Ticket>findById(draftId).lines.size());
        Assertions.assertEquals(1, lineCount, "the draft must hold the single scanned line");
        // Logout abandons the in-memory cart: /lock re-locks the register (the
        // lane-closed screen is the visible proof of the logged-out state).
        page.navigate(root + "lock");
        page.getByText("CAISSE FERMÉE").waitFor();
        // The draft is INTACT in the database: same id, still OPEN, line kept.
        Ticket draftAfter = QuarkusTransaction.requiringNew()
                .call(() -> Ticket.findById(draftId));
        Assertions.assertNotNull(draftAfter, "the logout must not delete the draft");
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, draftAfter.status,
                "the logout must leave the draft OPEN (recovery cancels it, not the logout)");
        int lineCountAfter = QuarkusTransaction.requiringNew()
                .call(() -> Ticket.<Ticket>findById(draftId).lines.size());
        Assertions.assertEquals(1, lineCountAfter, "the draft line must survive the logout");
        // At the next login the memory cart is gone: the sale screen is empty.
        loginToSaleWithOpenSession(page);
        Assertions.assertTrue(page.getByText("EN ATTENTE D'ARTICLES...").isVisible(),
                "the in-memory cart must be abandoned at logout (empty sale at next login)");
        page.close();
    }

    // --- Reusable gestures (login recipe & hardware bus) ---

    /**
     * Navigates to the lock screen, presents the badge on the hardware scan bus
     * and taps the PIN, submitting the unlock — the shared login gesture. Does
     * not assert the landing screen (which depends on the session state and is
     * asserted by each scenario).
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
        // The 1s /lock-data poll flips the overlay to PIN entry.
        page.getByText("Entrez votre code PIN :").waitFor();
        tapPin(page, pin);
        page.locator("#actionBtn").click();
    }

    /**
     * Logs the cashier in and lands on the sale screen, assuming a session is
     * already open on the register: the unlock pulse opens the drawer, the
     * drawer guard diverts to /drawer-error, and the physical drawer close
     * carries the drawer-status poll to the sale.
     *
     * @param page the Playwright page driving the register
     */
    private void loginToSaleWithOpenSession(Page page) {
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Taps a PIN digit by digit on the lock-screen numpad, scoped under
     * {@code #keyboardArea} so the mode toggles are never hit.
     *
     * @param page the Playwright page driving the register
     * @param pin the digits to tap
     */
    private void tapPin(Page page, String pin) {
        Locator keypad = page.locator("#keyboardArea");
        for (char digit : pin.toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(digit)).setExact(true)).click();
        }
    }

    /**
     * Fills the three PIN-change fields and submits the form. Each field is
     * activated by tapping its readonly display, then its digits are tapped on
     * the {@code #pinKeyboardArea} numpad; VALIDER submits.
     *
     * @param page the Playwright page driving the register
     * @param current the current PIN typed
     * @param newPin the new PIN typed
     * @param confirm the confirmation PIN typed
     */
    private void submitPinChange(Page page, String current, String newPin, String confirm) {
        fillPinChangeField(page, "dispCurrent", current);
        fillPinChangeField(page, "dispNew", newPin);
        fillPinChangeField(page, "dispConfirm", confirm);
        page.locator("#btnValidate").click();
    }

    /**
     * Activates one PIN-change field and taps its digits on the numpad.
     *
     * @param page the Playwright page driving the register
     * @param displayId the id of the field's readonly display input
     * @param digits the digits to tap into the field
     */
    private void fillPinChangeField(Page page, String displayId, String digits) {
        page.locator("#" + displayId).click();
        Locator keypad = page.locator("#pinKeyboardArea");
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
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Reads the OPEN draft ticket on terminal C04 from the database.
     *
     * @return the OPEN draft {@link Ticket} on C04, or null when none exists
     */
    private Ticket openDraftOnC04() {
        return QuarkusTransaction.requiringNew().call(() ->
                Ticket.find("terminalId = ?1 and status = ?2", "C04", Ticket.TicketStatus.OPEN).firstResult());
    }

    /**
     * Reads the seed cashier employee from the database.
     *
     * @return the cashier {@link Employee}
     */
    private Employee cashier() {
        return QuarkusTransaction.requiringNew().call(() -> Employee.findActiveLogin(CASHIER_BADGE));
    }

    /**
     * Ages the cashier's lockout timestamp into the past to simulate the
     * lockout window elapsing without waiting real minutes.
     */
    private void expireCashierLockout() {
        QuarkusTransaction.requiringNew().run(() -> {
            Employee e = Employee.findActiveLogin(CASHIER_BADGE);
            e.lockedUntil = LocalDateTime.now().minusMinutes(1);
            e.persist();
        });
    }
}
