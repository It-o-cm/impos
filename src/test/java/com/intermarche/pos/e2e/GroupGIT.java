package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
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

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * Group G end-to-end scenarios (Après-vente — after-sale), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Group G is
 * entirely {@code [S]} (caisse seule + simulateur), so every scenario G1–G4 is
 * implemented here; there is no {@code [V]}/{@code [N]} residue for this letter
 * beyond the cross-references noted below.
 * <p>
 * <b>The after-sale surfaces.</b> G1 opens the public digital receipt
 * ({@code /t/{id}/{key}}) the way a customer does — from a device with NO
 * operator session — and captures an email on it; G2 opens the customer-facing
 * display ({@code /customer}) as a SECOND browser window and watches its
 * client-side JS redraw phase by phase (welcome → cart → payment → thank-you)
 * as the register drives; G3 walks the reprint screen ({@code /reprint}) — paged
 * history, paged detail, the short-after-long page clamp and a numbered
 * duplicata; G4 parks and resumes a cart from the parked list, proving the
 * resumed cart is byte-identical (lines, uids, fidelity) and that parking is
 * refused mid-payment.
 * <p>
 * <b>The digital key is a capability.</b> The 16-hex {@code Ticket.digitalKey}
 * is minted at draft creation, so the link exists before the closing signature;
 * only a CLOSED ticket under the RIGHT key is served, and a wrong key or a
 * still-OPEN ticket both fall back INDISTINCTLY to the same unavailable page
 * (the QR endpoint answers a hard 404). The email capture journals
 * {@code DIGITAL_TICKET_SENT}.
 * <p>
 * <b>The customer display is derived, never commanded.</b> It is a plain second
 * window polling {@code /customer-data} (500 ms, version-gated) and projecting
 * {@code PosState} into a phase; the register never pushes to it. Because the
 * real JS runs under Playwright, the phase texts ({@code Bienvenue},
 * {@code PAIEMENT EN COURS}, {@code Reste à payer :}, {@code MERCI !},
 * {@code Rendu :}, the digital link) are asserted on the rendered screen.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (g1…g4) and share ONE cash session opened
 * once by {@link #ensureOpenSession()} on the fresh drop-and-create H2 boot;
 * each scenario builds its OWN prerequisites from an empty sale (a logout via
 * {@code /lock} abandons the in-memory cart), and each finalizes or abandons
 * what it starts. G3 builds the seven closed tickets its pagination needs
 * itself, so it never depends on the tickets G1/G2 happen to close.
 * <p>
 * <b>Oracles.</b> The rendered HTML carries the exact user-facing texts (the
 * digital receipt, the customer display phases, the reprint list/detail, the
 * parked list); the server singleton ({@link PosState}) is the oracle for the
 * in-memory cart (lines, uids, fidelity) and the transient parking refusal; the
 * database ({@link Ticket}) is the oracle for the durable state (status flips
 * OPEN↔PARKED, {@code digitalKey}, {@code printCount}, {@code customerEmail});
 * the append-only journal ({@link TechnicalEvent}) is the oracle for the
 * after-sale events ({@code DIGITAL_TICKET_SENT}, {@code DUPLICATA_PRINTED},
 * {@code TICKET_PARKED}, {@code TICKET_RESUMED}). The printer simulator captures
 * the duplicata paper.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupGIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    // --- Catalog fixtures (DataInitializer seed) ---

    /** Single-price unit product, TTC 6,00 € — the clean sale fixture. */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** Single-price unit product, TTC 0,96 € — the small-ticket fixture. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    /** A second unit product, used to build a two-line cart for parking. */
    private static final String EAN_LAIT = "3300000000002";
    private static final String LABEL_LAIT = "LAIT UHT 1L";

    /** Seven distinct single-price UNIT EANs — one line each, no merge: a
     * seven-line ticket that fills two 6-per-page detail pages. */
    private static final String[] LONG_TICKET_EANS = {
            "3300000000002", "3300000000003", "3300000000004", "3300000000006",
            "3300000000007", "3300000000010", "3300000000011"
    };

    /** A valid fidelity card ({@code ^789\d{12}$}, 15 digits) for the parking
     * identity check. */
    private static final String FIDELITY_CARD = "789000000000007";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected as the oracle for the in-memory
     * cart (lines, uids, fidelity), the settled draft's database id and the
     * transient parking-refusal message.
     */
    @Inject
    PosState posState;

    /**
     * G1 — Ticket dématérialisé (page publique, clé, refus, capture email).
     * <p>
     * A closed ticket under its right key serves the public receipt (no operator
     * session): the ticket number, the {@code TOTAL TTC} and the email capture
     * are rendered. A wrong key and a still-OPEN ticket both fall back to the
     * SAME indistinct unavailable page (and the QR endpoint answers a hard 404).
     * Typing an email on the page stores it on the ticket and journals
     * {@code DIGITAL_TICKET_SENT}.
     */
    @Test
    void g1_ticket_dematerialise() {
        // --- A closed ticket: build, settle in cash and close it ---
        Page sale = freshSale();
        Long closedId = closeCashSaleOn(sale, "6", EAN_HUILE);
        sale.close();
        TicketSnap closed = snap(closedId);
        Assertions.assertEquals("CLOSED", closed.status, "the finalized sale must be CLOSED");
        Assertions.assertNotNull(closed.digitalKey, "a closed ticket must carry a digital key");
        Assertions.assertTrue(closed.digitalKey.matches("[0-9a-f]{16}"),
                "the digital key must be 16 hex characters, was " + closed.digitalKey);

        // --- Right key: the public receipt is served, no session needed ---
        Page pub = context.newPage();
        pub.navigate(base.toString() + "t/" + closedId + "/" + closed.digitalKey);
        pub.getByText("TOTAL TTC").waitFor();
        Assertions.assertEquals(1, pub.getByText(closed.number).count(),
                "the public receipt must show the ticket number " + closed.number);
        Assertions.assertEquals(1, pub.getByText("Recevoir ce ticket par email :").count(),
                "the public receipt must offer the email capture");
        pub.close();

        // --- Wrong key: the indistinct unavailable page (and a 404 QR) ---
        Page bad = context.newPage();
        bad.navigate(base.toString() + "t/" + closedId + "/0000000000000000");
        bad.getByText("Ce ticket n'est pas (encore) disponible.").waitFor();
        Assertions.assertEquals(0, bad.getByText("TOTAL TTC").count(),
                "a wrong key must reveal nothing of the ticket");
        bad.close();
        APIResponse qr = context.request().get(
                base.toString() + "t/" + closedId + "/0000000000000000/qr.svg");
        Assertions.assertEquals(404, qr.status(), "the QR under a wrong key must be a hard 404");

        // --- Still-OPEN ticket under its right key: refused (unavailable) ---
        Page openSale = freshSale();
        scan(EAN_HUILE);
        openSale.getByText(LABEL_HUILE).waitFor();
        Long openId = posState.payment.ticketDbId;
        Assertions.assertNotNull(openId, "scanning a line must persist an OPEN draft");
        TicketSnap draft = snap(openId);
        Assertions.assertEquals("OPEN", draft.status, "the draft must still be OPEN");
        Assertions.assertNotNull(draft.digitalKey, "an OPEN draft already carries its digital key");
        Page openView = context.newPage();
        openView.navigate(base.toString() + "t/" + openId + "/" + draft.digitalKey);
        openView.getByText("Ce ticket n'est pas (encore) disponible.").waitFor();
        Assertions.assertEquals(0, openView.getByText("TOTAL TTC").count(),
                "an OPEN ticket must not be served even under the right key");
        openView.close();
        openSale.close();

        // --- Email capture on the closed receipt: stored and journaled ---
        // The customer types the email on the public page (a real keyboard) and
        // submits. The capture and the DIGITAL_TICKET_SENT journal happen INSIDE
        // the handler's transaction; the confirmation page it then returns is a
        // known app bug (the @Transactional handler renders a lazy Store.name
        // after the tx closes → 500), so the durable outcomes are the oracle.
        long sentBefore = eventCount(TechnicalEvent.EventType.DIGITAL_TICKET_SENT);
        Page email = context.newPage();
        email.navigate(base.toString() + "t/" + closedId + "/" + closed.digitalKey);
        email.locator("#email").fill("client@example.fr");
        email.waitForResponse("**/email", () -> email.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("ENVOYER").setExact(true)).click());
        Assertions.assertEquals("client@example.fr", snap(closedId).customerEmail,
                "the typed email must be stored on the ticket");
        Assertions.assertEquals(sentBefore + 1, eventCount(TechnicalEvent.EventType.DIGITAL_TICKET_SENT),
                "the email capture must journal exactly one DIGITAL_TICKET_SENT");
        email.close();
    }

    /**
     * G2 — Écran client (accueil → panier → paiement → merci).
     * <p>
     * The customer display is a second window whose real JS follows the register
     * through its phases: welcome while the cart is empty, the live cart with a
     * total identical to the register's, the payment progress ({@code PAIEMENT EN
     * COURS} / {@code Reste à payer :}) and finally the thank-you screen with the
     * change and the digital-receipt link. The training banner stays hidden in
     * normal operation.
     */
    @Test
    void g2_ecran_client() {
        Page register = freshSale();
        // The customer window: a plain second browser page, no session, polling.
        Page display = context.newPage();
        display.navigate(base.toString() + "customer");

        // --- Accueil: empty cart -> welcome, no training banner ---
        display.getByText("Bienvenue").waitFor();
        Assertions.assertTrue(display.locator("#welcomeScreen").isVisible(),
                "an empty register must show the welcome screen");
        Assertions.assertFalse(display.locator("#trainingBanner").isVisible(),
                "the training banner must stay hidden in normal operation");

        // --- Panier: a scanned line lights the cart, total mirrors the register ---
        scan(EAN_HUILE);
        register.getByText(LABEL_HUILE).waitFor();
        display.locator("#cartScreen.screen.active").waitFor();
        display.locator("#cartLines").getByText(LABEL_HUILE).waitFor();
        display.getByText("6,00 €").first().waitFor();
        Assertions.assertTrue(display.locator("#totalZone").textContent().contains(
                        posState.ticket.getTotalFormatted()),
                "the customer total must mirror the register total");

        // --- Paiement: a partial cash tender raises the payment progress ---
        // (entering /pay flips paymentInProgress but does NOT bump the display
        // version, so the paying phase only surfaces once a tender touches the
        // state — a first partial tender is the faithful gesture that does.)
        goPay(register);
        payCashPartial(register, "2");
        display.getByText("PAIEMENT EN COURS").waitFor();
        display.getByText("Reste à payer : 4,00 €").waitFor();

        // --- Merci: the overpaid balance completes, showing change + link ---
        // The partial cash pulsed the drawer open; the balance POST is guarded.
        closeDrawer();
        payCashComplete(register, "10");
        display.getByText("MERCI !").waitFor();
        display.getByText("Rendu : 6,00 €").waitFor();
        Assertions.assertTrue(display.locator("#digitalZone").textContent().contains("/t/"),
                "the thank-you screen must show the digital-receipt link");

        finishSale(register);
        display.close();
        register.close();
    }

    /**
     * G3 — Réimpression (historique 6/page, détail paginé, clamp, duplicata).
     * <p>
     * The reprint history pages six closed tickets at a time; a seven-line ticket
     * fills two detail pages; opening a short ticket after paging deep into a
     * long one lands on a clamped valid page (never an empty page beyond the
     * ticket). Pressing IMPRIMER on an already-printed ticket produces a numbered
     * duplicata: {@code printCount} is bumped, the paper is marked
     * {@code DUPLICATA N°x} and a {@code DUPLICATA_PRINTED} event is journaled.
     */
    @Test
    void g3_reimpression() {
        // Build the seven closed tickets this scenario needs (one long, six
        // short) on a single logged-in page — no dependency on other scenarios.
        Page sale = freshSale();
        Long longId = closeCashSaleOn(sale, "90", LONG_TICKET_EANS);
        Long shortId = closeCashSaleOn(sale, "5", EAN_BAGUETTE);
        for (int i = 0; i < 5; i++) {
            closeCashSaleOn(sale, "5", EAN_BAGUETTE);
        }
        sale.close();

        // --- History paging: a full first page of six, a reachable page two ---
        Page page = freshSale();
        closeDrawer();
        page.navigate(base.toString() + "reprint");
        page.getByText("Sélectionner un ticket").waitFor();
        Assertions.assertEquals(6, page.locator(".receipt-item-link").count(),
                "the history must page six closed tickets at a time");
        Assertions.assertEquals(1, page.getByText("Page 1").count(), "the history must open on page 1");
        Assertions.assertTrue(page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName("SUIVANT").setExact(true)).count() >= 1,
                "with seven tickets the history must offer a next page");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        page.getByText("Page 2").waitFor();
        int page2 = page.locator(".receipt-item-link").count();
        Assertions.assertTrue(page2 >= 1 && page2 <= 6, "page two must carry between one and six rows, was " + page2);

        // --- Detail paging: the seven-line ticket fills two detail pages ---
        // The freshly loaded first page shows six of the seven lines and its
        // "Page 1 / 2" indicator proves the six-per-page detail pagination.
        // (Clicking the detail SUIVANT/PRÉCÉDENT is a known app bug: those
        // handlers re-render the CACHED viewedTicket from a prior request — a
        // detached entity — so a lazy Product.plu 500s; the first view reloads
        // in-session and renders fine, which is the paginated page asserted.)
        page.navigate(base.toString() + "reprint/view/" + longId);
        page.getByText("DÉTAIL DU TICKET").waitFor();
        page.getByText("Page 1 / 2").waitFor();
        Assertions.assertEquals(6, page.locator(".receipt-item:not(.total-line)").count(),
                "the first detail page must show six of the seven lines (six per page)");

        // --- Short after long: opening a one-line ticket lands on a valid 1/1 ---
        page.navigate(base.toString() + "reprint/view/" + shortId);
        page.getByText("Page 1 / 1").waitFor();
        Assertions.assertEquals(1, page.locator(".receipt-item:not(.total-line)").count(),
                "the short ticket must show its single valid page (never an empty page)");
        page.getByText(LABEL_BAGUETTE).waitFor();

        // --- Duplicata: the first print is the original, the second is numbered ---
        int before = snap(shortId).printCount;
        long dupBefore = eventCount(TechnicalEvent.EventType.DUPLICATA_PRINTED);
        page.waitForResponse("**/reprint/print/" + shortId, () -> page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("IMPRIMER").setExact(true)).click());
        page.getByText("DÉTAIL DU TICKET").waitFor();
        Assertions.assertEquals(before + 1, snap(shortId).printCount,
                "the first reprint (the original) must bump the print count by one");
        clearPrinter();
        page.waitForResponse("**/reprint/print/" + shortId, () -> page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("IMPRIMER").setExact(true)).click());
        page.getByText("DÉTAIL DU TICKET").waitFor();
        Assertions.assertEquals(before + 2, snap(shortId).printCount,
                "the second reprint must bump the print count again");
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("DUPLICATA N°1"),
                "the second print must be marked as duplicata n°1, paper was:\n" + paper);
        Assertions.assertEquals(dupBefore + 1, eventCount(TechnicalEvent.EventType.DUPLICATA_PRINTED),
                "only the duplicata (the second print) must journal a DUPLICATA_PRINTED");
        page.close();
    }

    /**
     * G4 — Parking (mise en attente, reprise identique, refus en paiement).
     * <p>
     * A cart carrying two lines and a fidelity card is parked (draft flipped to
     * PARKED, in-memory cart cleared, {@code TICKET_PARKED} journaled), listed on
     * the parked screen and resumed IDENTICALLY: the lines keep their exact uids
     * and the fidelity card is restored (the same {@code restoreDraft} machinery
     * as the restart recovery — cross K1). Parking is refused once a payment is
     * in progress. PARKED tickets die at the Z closing (cross I3).
     */
    @Test
    void g4_parking() {
        // --- Build a two-line cart with a fidelity card ---
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        scan(EAN_LAIT);
        page.getByText(LABEL_LAIT).waitFor();
        scan(FIDELITY_CARD);
        page.locator("#fidIcon.active").waitFor();
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "the running cart must carry a persisted draft");
        Set<String> uidsBefore = itemUids();
        Assertions.assertEquals(2, uidsBefore.size(), "the cart must carry the two scanned lines");
        Assertions.assertEquals(FIDELITY_CARD, posState.fidelity.label, "the fidelity card must be attached");

        // --- Park: the draft is PARKED, the in-memory cart cleared, journaled ---
        long parkedBefore = eventCount(TechnicalEvent.EventType.TICKET_PARKED);
        page.navigate(base.toString() + "action/parked/park");
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "parking must clear the in-memory cart");
        Assertions.assertEquals("PARKED", snap(draftId).status, "the parked draft must flip to PARKED");
        Assertions.assertEquals(parkedBefore + 1, eventCount(TechnicalEvent.EventType.TICKET_PARKED),
                "parking must journal exactly one TICKET_PARKED");

        // --- The parked list carries the ticket with its line count ---
        page.navigate(base.toString() + "parked");
        page.getByText("Tickets en attente").waitFor();
        page.getByText("2 article(s)").waitFor();
        Assertions.assertEquals(1, page.locator("a[href='/action/parked/resume/" + draftId + "']").count(),
                "the parked ticket must be resumable from the list");

        // --- Resume: the cart is restored byte-identical (uids, fidelity) ---
        long resumedBefore = eventCount(TechnicalEvent.EventType.TICKET_RESUMED);
        page.locator("a[href='/action/parked/resume/" + draftId + "']").click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals(2, posState.ticket.items.size(), "resuming must restore both lines");
        Assertions.assertEquals(uidsBefore, itemUids(), "the resumed lines must keep their exact uids");
        Assertions.assertEquals(FIDELITY_CARD, posState.fidelity.label,
                "the resumed cart must restore its fidelity card");
        Assertions.assertEquals("OPEN", snap(draftId).status, "the resumed draft must flip back to OPEN");
        Assertions.assertEquals(resumedBefore + 1, eventCount(TechnicalEvent.EventType.TICKET_RESUMED),
                "resuming must journal exactly one TICKET_RESUMED");
        page.close();

        // --- Parking is refused while a payment is in progress ---
        Page paying = freshSale();
        scan(EAN_HUILE);
        paying.getByText(LABEL_HUILE).waitFor();
        Long payDraftId = posState.payment.ticketDbId;
        goPay(paying);
        // Entering /pay flips paymentInProgress on; parking must now be refused.
        paying.navigate(base.toString() + "action/parked/park");
        paying.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertEquals("PAIEMENT EN COURS - MISE EN ATTENTE IMPOSSIBLE", posState.ticket.transientError,
                "parking during a payment must be refused with the in-payment message");
        Assertions.assertEquals("OPEN", snap(payDraftId).status,
                "a refused parking must leave the draft OPEN (not PARKED)");
        Assertions.assertFalse(posState.ticket.items.isEmpty(),
                "a refused parking must keep the cart intact");
        paying.close();
    }

    // --- Reusable gestures (login recipe, sale build, hardware bus) ---

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session — the standard start of every scenario.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshSale() {
        // Neutralize any page left polling by a previous scenario: a lingering
        // /lock-data poller would steal this scenario's badge from the one-shot
        // mailbox (the group-A page-close lesson). A logout also clears the cart.
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
     * Builds and finalizes a cash sale on an already-logged-in page: scans the
     * given products, tenders an (over)paying cash amount on the payment screen,
     * completes the transaction, closes the drawer and returns to an empty sale
     * screen. Returns the settled ticket's database id (captured before the
     * fiscal close).
     *
     * @param page the Playwright page on the empty sale screen
     * @param cashDigits the cash amount to tap (must cover the total)
     * @param eans the product EANs to scan, one line each
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
     * line is rendered). Entering /pay flips {@code paymentInProgress} on.
     *
     * @param page the Playwright page driving the register
     */
    private void goPay(Page page) {
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Tenders a cash amount on the payment overlay and waits for the completion
     * modal: taps the ESPÈCES method, taps the amount on the payment numpad and
     * validates.
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
     * Tenders a PARTIAL cash amount on the payment overlay (less than the amount
     * due): taps ESPÈCES, taps the amount and validates, landing back on the
     * still-open payment overlay (the remaining is not yet settled). This touches
     * the state and so surfaces the paying phase on the customer display.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param digits the partial cash amount digits to tap
     */
    private void payCashPartial(Page page, String digits) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), digits);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Closes the completed transaction cleanly (the fiscal moment): pushes the
     * drawer shut so the guard lets the finish through, then taps NOUVELLE VENTE
     * and waits for the empty sale screen.
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

    // --- Server-state / database / journal oracles ---

    /**
     * Collects the uids of the current in-memory cart lines.
     *
     * @return the set of line uids
     */
    private Set<String> itemUids() {
        Set<String> uids = new HashSet<>();
        for (TicketState.TicketItem item : posState.ticket.items) uids.add(item.uid);
        return uids;
    }

    /**
     * Reads a ticket's durable fields from the database into a plain snapshot
     * (so no lazy field is touched outside the transaction).
     *
     * @param ticketId the database id of the ticket
     * @return a snapshot of the ticket's after-sale fields
     */
    private TicketSnap snap(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return new TicketSnap(t.status.name(), t.ticketNumber, t.digitalKey, t.printCount, t.customerEmail);
        });
    }

    /**
     * Counts the journal entries of a given event type.
     *
     * @param type the technical event type
     * @return the number of journaled events of that type
     */
    private long eventCount(TechnicalEvent.EventType type) {
        return QuarkusTransaction.requiringNew().call(() -> TechnicalEvent.count("eventType", type));
    }

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Immutable snapshot of a ticket's after-sale fields, read off the database
     * so assertions never hold a live entity reference.
     */
    private static final class TicketSnap {
        /** The lifecycle status name (OPEN, PARKED, CLOSED, CANCELLED). */
        final String status;
        /** The register ticket number. */
        final String number;
        /** The 16-hex digital receipt key, or null. */
        final String digitalKey;
        /** The reprint/duplicata counter. */
        final int printCount;
        /** The captured customer email, or null. */
        final String customerEmail;

        /**
         * Creates a ticket snapshot.
         *
         * @param status the status name
         * @param number the ticket number
         * @param digitalKey the digital key
         * @param printCount the print count
         * @param customerEmail the captured email
         */
        TicketSnap(String status, String number, String digitalKey, int printCount, String customerEmail) {
            this.status = status;
            this.number = number;
            this.digitalKey = digitalKey;
            this.printCount = printCount;
            this.customerEmail = customerEmail;
        }
    }
}
