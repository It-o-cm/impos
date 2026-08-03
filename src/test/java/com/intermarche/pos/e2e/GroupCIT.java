package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
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

/**
 * Group C end-to-end scenarios (Gestes de prix sous avenant — price gestures
 * under manager endorsement), played THROUGH THE SCREEN with a headless
 * Chromium browser (quarkus-playwright) against the real application (live H2,
 * real beans, embedded hardware simulator). Group C is entirely {@code [S]}
 * (caisse seule + simulateur), so every scenario C1–C6 is implemented here;
 * there is no {@code [V]}/{@code [N]} residue for this letter.
 * <p>
 * <b>The gesture chain.</b> Every price gesture is a THREE-step screen dance:
 * the cashier picks the gesture button on the sale screen ({@code REMISE} and
 * {@code QUANTITÉ} live in the primary menu, {@code DISCOUNT} and
 * {@code FORÇAGE PRIX} in the secondary one behind {@code AUTRES...}), types
 * the value on the {@code #priceKbArea} decimal numpad and validates — which
 * PARKS the gesture into a manager endorsement (nothing is applied yet). A
 * manager then endorses over the cashier's shoulder: the badge is presented on
 * the hardware bus ({@code POST /api/pos/scan}), the 500 ms
 * {@code /endorsement-data} poll flips the modal to PIN entry, the PIN is tapped
 * on {@code #endorseKeyboardArea} and {@code SUIVANT} executes the parked
 * gesture. Only {@code QUANTITY} skips the endorsement (a normal sale action);
 * the three price types always cross it.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (c1…c6) and share ONE cash session opened
 * once by {@link #ensureOpenSession()} on the fresh drop-and-create H2 boot;
 * each scenario builds its OWN cart on an empty sale (a logout clears the
 * in-memory cart). C4 deliberately BURNS the manager account into its 5-minute
 * lockout (three wrong endorsement PINs) to prove the failure counter is SHARED
 * with the register login, then RESTORES it (counter and lockout cleared) so
 * C5/C6 — which endorse again — meet a clean manager.
 * <p>
 * <b>Oracles.</b> The applied gesture is read on the injected {@link PosState}
 * (structured {@code modifierType}/{@code modifierValue}, the recomputed
 * {@code unitPrice}/line total) AND on the rendered ticket; refused values
 * (a &gt;100 % discount, a floored REMISE) return SILENTLY with no message, so
 * they are asserted as the ABSENCE of a modification, not as an error string.
 * The manager lockout is read straight from the {@link Employee} row. C2's
 * cent-rounding is proven on both the rendered ticket line and the PRINTED
 * paper captured from the embedded printer simulator
 * ({@code GET /api/hardware/printer/content}) after a completing cash payment.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupCIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    // --- Catalog fixtures (DataInitializer seed) ---

    /** Single-price unit product, TTC 6,00 € (uppercased label on the line). */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** Single-price unit product, TTC 0,96 € — the rounding fixture of C2. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected as the oracle for the applied
     * gesture (structured {@code modifierType}/{@code modifierValue}, the
     * recomputed unit price and line total) and for the silent-refusal cases.
     */
    @Inject
    PosState posState;

    /**
     * C1 — REMISE €.
     * <p>
     * A 1,00 € REMISE on a 6,00 € line, endorsed by the manager, drops the line
     * total to 5,00 € and stamps the structured modifier (label + delta). A
     * REMISE larger than the line total floors the line at 0,00 € (plancher 0),
     * never a negative total.
     */
    @Test
    void c1_remise_euros_et_plancher_zero() {
        // --- Nominal: 1,00 € off a 6,00 € line ---
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        approveEndorsementWithManager(page);
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals("REMISE", line.modifierType, "the gesture must stamp a REMISE modifier");
        Assertions.assertEquals(0, line.modifierValue.compareTo(new BigDecimal("1")),
                "the structured delta must be the 1,00 € typed amount");
        Assertions.assertTrue(line.modifierLabel.startsWith("Remise"),
                "the modifier label must read as a Remise, was " + line.modifierLabel);
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("5.00")),
                "6,00 € minus a 1,00 € remise must leave 5,00 €");
        page.close();

        // --- Floor: a remise larger than the line total clamps at 0,00 € ---
        Page page2 = freshSale();
        scan(EAN_HUILE);
        page2.getByText(LABEL_HUILE).waitFor();
        openGesture(page2, "remise", "REMISE");
        typeAndValidate(page2, "10");
        approveEndorsementWithManager(page2);
        Assertions.assertEquals(0, posState.ticket.items.get(0).getTotalPrice().compareTo(BigDecimal.ZERO),
                "a remise above the line total must floor the line at 0,00 €");
        page2.close();
    }

    /**
     * C2 — DISCOUNT %.
     * <p>
     * A 0 % discount is a no-op (guarded), a 100 % discount zeroes the line, and
     * a 101 % discount is refused silently (no change). A non-round percentage
     * (15 % on 0,96 € → 0,816 → 0,82 €) proves the cent rounding is IDENTICAL on
     * the rendered ticket line and on the printed paper captured from the
     * embedded printer simulator.
     */
    @Test
    void c2_discount_pourcent_bornes_et_arrondi() {
        // --- 0 %: guarded no-op, the line keeps its catalog price ---
        Page page0 = freshSale();
        scan(EAN_BAGUETTE);
        page0.getByText(LABEL_BAGUETTE).waitFor();
        openGesture(page0, "discount", "DISCOUNT");
        typeAndValidate(page0, "0");
        approveEndorsementWithManager(page0);
        TicketState.TicketItem zero = posState.ticket.items.get(0);
        Assertions.assertNull(zero.modifierType, "a 0 % discount must apply no modifier");
        Assertions.assertEquals(0, zero.getTotalPrice().compareTo(new BigDecimal("0.96")),
                "a 0 % discount must leave the 0,96 € catalog price untouched");
        page0.close();

        // --- 100 %: the line total collapses to 0,00 € ---
        Page page100 = freshSale();
        scan(EAN_BAGUETTE);
        page100.getByText(LABEL_BAGUETTE).waitFor();
        openGesture(page100, "discount", "DISCOUNT");
        typeAndValidate(page100, "100");
        approveEndorsementWithManager(page100);
        TicketState.TicketItem full = posState.ticket.items.get(0);
        Assertions.assertEquals("DISCOUNT", full.modifierType, "a 100 % discount must stamp a DISCOUNT modifier");
        Assertions.assertEquals(0, full.getTotalPrice().compareTo(BigDecimal.ZERO),
                "a 100 % discount must zero the line");
        page100.close();

        // --- 101 %: refused, silent, no change ---
        Page page101 = freshSale();
        scan(EAN_BAGUETTE);
        page101.getByText(LABEL_BAGUETTE).waitFor();
        openGesture(page101, "discount", "DISCOUNT");
        typeAndValidate(page101, "101");
        approveEndorsementWithManager(page101);
        TicketState.TicketItem over = posState.ticket.items.get(0);
        Assertions.assertNull(over.modifierType, "a 101 % discount must be refused (no modifier)");
        Assertions.assertEquals(0, over.getTotalPrice().compareTo(new BigDecimal("0.96")),
                "a refused 101 % discount must leave the catalog price untouched");
        page101.close();

        // --- Rounding: 15 % on 0,96 € → 0,816 → 0,82 €, screen AND paper ---
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        openGesture(page, "discount", "DISCOUNT");
        typeAndValidate(page, "15");
        approveEndorsementWithManager(page);
        Assertions.assertEquals(0, posState.ticket.items.get(0).getTotalPrice()
                        .setScale(2, java.math.RoundingMode.HALF_UP).compareTo(new BigDecimal("0.82")),
                "15 % off 0,96 € must round to 0,82 € at the cent");
        Assertions.assertTrue(page.locator("#ticket-container").textContent().contains("0,82"),
                "the rounded 0,82 € must show on the rendered ticket line");
        // Complete a cash payment (overpaid) to reach the completion modal, then
        // print the still-OPEN draft and read the paper from the simulator.
        clearPrinter();
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), "5");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        // The cash payment pulsed the drawer open (deposit + change); the print
        // POST is drawer-guarded, so push the drawer shut first or it diverts to
        // /drawer-error and nothing reaches the paper.
        closeDrawer();
        // Print the still-OPEN draft and WAIT for the /action/print response so
        // the printer simulator has finished writing before the buffer is read.
        page.waitForResponse("**/action/print", () ->
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("IMPRIMER TICKET")).click());
        String paper = printerContent().replace(",", ".");
        Assertions.assertTrue(paper.contains("0.82"),
                "the printed ticket must carry the same 0,82 € rounded line total, paper was:\n" + paper);
        // Close the sale cleanly (fiscal moment) so the register is empty again.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("NOUVELLE VENTE").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        page.close();
    }

    /**
     * C3 — FORÇAGE prix.
     * <p>
     * Forcing a new LINE total recomputes the unit price as total / quantity: on
     * a two-unit line (6,00 € each, 12,00 € total) forced to 5,00 €, the unit
     * price becomes 2,50 € and the line total 5,00 €, the modifier recording the
     * original 12,00 € total.
     */
    @Test
    void c3_forcage_prix_recalcul_unitaire() {
        Page page = freshSale();
        // Two units of the same unit product merge into one line (qty 2).
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        scan(EAN_HUILE);
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("2")),
                "two scans of a unit EAN must merge into a quantity-2 line");
        openGesture(page, "force_price", "FORÇAGE PRIX");
        typeAndValidate(page, "5");
        approveEndorsementWithManager(page);
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals("FORCE_PRICE", line.modifierType, "the gesture must stamp a FORCE_PRICE modifier");
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("5.00")),
                "the forced line total must be 5,00 €");
        Assertions.assertEquals(0, line.unitPrice.compareTo(new BigDecimal("2.50")),
                "the unit price must be recomputed as total / quantity (5,00 / 2 = 2,50)");
        Assertions.assertTrue(line.modifierLabel.startsWith("Prix initial:"),
                "the modifier label must record the original total, was " + line.modifierLabel);
        page.close();
    }

    /**
     * C4 — Avenant refusé.
     * <p>
     * Cancelling the endorsement modal leaves the parked gesture INERT (nothing
     * applied). Three wrong manager PINs LOCK the manager account for the
     * configured window — the failure counter is SHARED with the register login,
     * proven here by a subsequently CORRECT PIN still being refused while the
     * lockout stands. The line is never modified throughout, and the scenario
     * restores the manager account (counter and lockout cleared) for C5/C6.
     */
    @Test
    void c4_avenant_refuse_annulation_et_verrouillage() {
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        // --- Cancel: a parked gesture abandoned on the modal stays inert ---
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertNull(posState.ticket.items.get(0).modifierType,
                "a cancelled endorsement must not apply the parked gesture");
        Assertions.assertEquals(0, posState.ticket.items.get(0).getTotalPrice().compareTo(new BigDecimal("6.00")),
                "the line total must be untouched after a cancelled endorsement");
        // --- Lockout: three wrong manager PINs lock the account ---
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        attemptRefusedEndorsement(page, MANAGER_BADGE, "9999");
        attemptRefusedEndorsement(page, MANAGER_BADGE, "9999");
        attemptRefusedEndorsement(page, MANAGER_BADGE, "9999");
        Assertions.assertTrue(manager().isCurrentlyLocked(),
                "three wrong endorsement PINs must lock the manager account");
        Assertions.assertNull(posState.ticket.items.get(0).modifierType,
                "a refused endorsement must not apply the parked gesture");
        // --- Shared counter: a CORRECT PIN is still refused while locked ---
        attemptRefusedEndorsement(page, MANAGER_BADGE, MANAGER_PIN);
        Assertions.assertNull(posState.ticket.items.get(0).modifierType,
                "the lockout (shared with the login) must gate even a correct endorsement PIN");
        // Abandon the parked gesture and restore the manager for the next scenarios.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        restoreManagerAccount();
        page.close();
    }

    /**
     * C5 — Badge manager sur la modale.
     * <p>
     * The manager badges directly on the endorsement modal: the badge prefills
     * the manager login through the one-shot mailbox (precedence over typing —
     * the A4 gesture, applied to the endorsement rather than the lock screen),
     * only the PIN is tapped, and the gesture applies. The manager login is
     * never typed, so a successful application proves the badge prefilled it.
     */
    @Test
    void c5_badge_manager_sur_la_modale() {
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        // The manager badges over the shoulder (no login typed): the mailbox
        // prefills the login, the modal jumps to PIN, the PIN applies the remise.
        approveEndorsementWithManager(page);
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals("REMISE", line.modifierType,
                "the badge-prefilled endorsement must apply the gesture");
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("5.00")),
                "the remise applied through the badge must drop the line to 5,00 €");
        page.close();
    }

    /**
     * C6 — Geste sur ligne déjà modifiée.
     * <p>
     * A second gesture on an already-modified line REPLACES the first: after a
     * REMISE, a FORÇAGE resets the structured modifier (type and value) and the
     * label, the recomputed total no longer reflecting the first gesture. The
     * modified line stays non-mergeable — a fresh scan of the same EAN opens a
     * distinct line rather than merging.
     */
    @Test
    void c6_geste_sur_ligne_deja_modifiee() {
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        // First gesture: a 1,00 € REMISE (6,00 → 5,00).
        openGesture(page, "remise", "REMISE");
        typeAndValidate(page, "1");
        approveEndorsementWithManager(page);
        Assertions.assertEquals("REMISE", posState.ticket.items.get(0).modifierType,
                "the first gesture must stamp a REMISE modifier");
        // Second gesture: a FORÇAGE to 4,00 € REPLACES the REMISE.
        openGesture(page, "force_price", "FORÇAGE PRIX");
        typeAndValidate(page, "4");
        approveEndorsementWithManager(page);
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals("FORCE_PRICE", line.modifierType,
                "the second gesture must replace the modifier type");
        Assertions.assertEquals(0, line.modifierValue.compareTo(new BigDecimal("4")),
                "the structured value must be the second gesture's, not the first's");
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("4.00")),
                "the forced total must replace the remised one (4,00 €)");
        // Merge stays forbidden on a modified line: a re-scan opens a new line.
        scan(EAN_HUILE);
        Assertions.assertEquals(2, posState.ticket.items.size(),
                "a modified line must never absorb a new scan of the same EAN");
        page.close();
    }

    // --- Reusable gestures (login recipe, hardware bus, price gestures) ---

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
     * Opens the price-modification modal for a gesture, forcing the menu that
     * carries its button first (primary for REMISE, secondary — behind
     * {@code AUTRES...} — for DISCOUNT and FORÇAGE) so a secondary-menu state
     * leaked from a prior scenario cannot hide the button. Waits until the modal
     * numpad is ready.
     *
     * @param page the Playwright page driving the register
     * @param type the price-mod type slug (remise, discount, force_price)
     * @param linkName the exact visible button text
     */
    private void openGesture(Page page, String type, String linkName) {
        boolean secondary = "discount".equals(type) || "force_price".equals(type);
        // Deterministically show the right menu (same GET the visible toggle
        // fires), then tap the now-visible gesture button.
        page.navigate(base.toString() + "action/menu/" + (secondary ? "secondary" : "main"));
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(linkName).setExact(true)).click();
        page.locator("#priceKbArea").waitFor();
    }

    /**
     * Taps the value on the price-modal numpad and validates, which parks the
     * gesture into a manager endorsement.
     *
     * @param page the Playwright page carrying the price modal
     * @param digits the value digits to tap
     */
    private void typeAndValidate(Page page, String digits) {
        tapDigits(page.locator("#priceKbArea"), digits);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
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
     * Presents a manager PIN on the endorsement modal that is expected to be
     * REFUSED: badges on the bus (the modal jumps to PIN entry), taps the PIN,
     * submits and waits for the {@code AUTORISATION REFUSÉE} message — a wrong
     * PIN or a locked account both land here.
     *
     * @param page the Playwright page carrying the endorsement modal
     * @param badge the manager badge presented on the bus
     * @param pin the PIN tapped on the modal numpad
     */
    private void attemptRefusedEndorsement(Page page, String badge, String pin) {
        scan(badge);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), pin);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        page.getByText("AUTORISATION REFUSÉE").waitFor();
    }

    /**
     * Presents a code on the hardware scan bus (scanner gun / simulator) and
     * asserts the bus accepted it — the uniform entry of every scanned code and
     * of the manager badge presented on the endorsement modal.
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
     * Reads the seed manager employee from the database.
     *
     * @return the manager {@link Employee}
     */
    private Employee manager() {
        return QuarkusTransaction.requiringNew().call(() -> Employee.findActiveLogin(MANAGER_BADGE));
    }

    /**
     * Restores the manager account after C4's lockout: clears the failure
     * counter and the lockout timestamp so C5/C6 endorse against a clean
     * manager (the isolation contract — each scenario restores what it altered).
     */
    private void restoreManagerAccount() {
        QuarkusTransaction.requiringNew().run(() -> {
            Employee m = Employee.findActiveLogin(MANAGER_BADGE);
            m.failedAttempts = 0;
            m.lockedUntil = null;
            m.persist();
        });
    }
}
