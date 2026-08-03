package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentService;
import com.intermarche.pos.ui.ticket.TicketState;
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
 * Group O end-to-end scenarios (Inventaire exhaustif — messages d'erreur &amp;
 * modales), played THROUGH THE SCREEN with a headless Chromium browser
 * (quarkus-playwright) against the real application (live H2, real beans,
 * embedded hardware simulator). The oracle of this group is the message
 * catalogue itself (e2e-scenarios.md §O), not JaCoCo: every reachable row is a
 * trigger → EXACT user-facing text → clearing, driven on the public HTTP
 * surface (scan bus, {@code /action/*} routes, the peripheral simulator) and
 * asserted either on the rendered screen or, when a handler writes state that a
 * poll/redirect never surfaces, on the injected singleton {@link PosState}.
 * <p>
 * <b>The two visibility classes of a ticket-zone message.</b> The transient
 * message lives in {@code state.ticket.transientError} and renders as the top
 * {@code .error-line} of the polled {@code /ticket-fragment}. Handlers that call
 * {@code TicketState.setError} bump the poll version, so their message reaches
 * the sale screen and is asserted on the rendered {@code .error-line}. Two
 * handlers assign the field DIRECTLY without a version bump — the unknown-code
 * fallback ({@code CODE INCONNU}) and the session gate reached off the sale
 * screen ({@code AUCUNE SESSION OUVERTE}); their message never polls, so it is
 * asserted server-side on {@link PosState}. The transitory contract (the
 * message is cleared on the next scan/mutation) is proven per entry by scanning
 * a valid product afterwards and watching the {@code .error-line} detach (or the
 * field null out).
 * <p>
 * <b>Ordering &amp; shared session.</b> Scenarios run under
 * {@link MethodOrderer.MethodName} (oa1…oe3). {@code oa1} runs first on the
 * fresh drop-and-create H2 boot: it proves the no-session gate BEFORE opening a
 * session, then opens the single shared session that the later scenarios reuse
 * through {@link #ensureOpenSession()}. Each scenario restores whatever it
 * alters — it empties the shared server-side cart with an endorsed cancel, ends
 * training mode, restores the seed PIN, and resets any account lockout it
 * provoked — so no scenario leaks state onto the next. Nothing is asserted as an
 * absolute counter; session numbers, when read, match {@code C04-S\d{5}}.
 * <p>
 * <b>Justified residue (unreachable on a caisse-seule simulator).</b>
 * <ul>
 *   <li>Supervisor call outcomes {@code SUPERVISEUR PRÉVENU},
 *       {@code APPEL SUPERVISEUR REFUSÉ (&lt;code&gt;)},
 *       {@code APPEL SUPERVISEUR IMPOSSIBLE} and
 *       {@code APPEL SUPERVISEUR INTERROMPU} all require a reachable/failing
 *       store node (biface {@code [N]} territory) or a thread interruption;
 *       under the caisse-seule profile {@code pos.sync.store-url} is unset, so
 *       only {@code SUPERVISION NON CONFIGURÉE} is reachable and asserted.</li>
 *   <li>{@code QUANTITÉ DÉJÀ REMBOURSÉE (&lt;détail&gt;)} needs an interleaved
 *       second refund to open a staging≠execution gap; a single-operator
 *       register (one {@link PosState}) cannot host two concurrent stagings, so
 *       only the free-amount {@code PLAFOND} re-check exercises the
 *       in-transaction guard here.</li>
 *   <li>{@code ACTION DE REMBOURSEMENT INCONNUE} is a defensive dispatch branch
 *       reached only by a malformed {@code REFUND_&lt;method&gt;} action; no
 *       HTTP route produces one (the {@code /return/pay/*} endpoints only park
 *       valid enum methods).</li>
 *   <li>{@code Aucun opérateur connecté} is a defensive branch of
 *       {@code AuthService.changePin}; the PIN-change POST is guarded by
 *       {@code state.isLocked()} which diverts a logged-out caller to the lock
 *       page before {@code changePin} ever runs, so the null-operator arm is
 *       unreachable on the screen.</li>
 *   <li>The valuation banners (VALORISATION INDISPONIBLE z-49, AVANTAGES z-48,
 *       SUGGESTION z-47) belong to the dedicated valuation module ({@code [V]},
 *       seeded mirror engine) explicitly left non-approfondi by §O; only the
 *       FORMATION banner (z-50) is proven here.</li>
 * </ul>
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupOIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed cashier login name, used as the account-lock oracle key. */
    private static final String CASHIER_LOGIN = "jdupont";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    /** The seed manager login name, used as the account-lock oracle key. */
    private static final String MANAGER_LOGIN = "mcurie";

    /** The opening float of the shared session (euros, comma decimal). */
    private static final String OPENING_FLOAT = "200,00";

    /** Seed unit EAN (LAIT UHT 1L, 1,00 €), mergeable — the clearing/quantity line. */
    private static final String EAN_LAIT = "3300000000002";

    /** Uppercased ticket label of {@link #EAN_LAIT}. */
    private static final String LABEL_LAIT = "LAIT UHT 1L";

    /** Seed unit EAN (HUILE D'OLIVE 1L, 6,00 €) — the payment/return line. */
    private static final String EAN_HUILE = "3300000000006";

    /** Seed EAN flagged forbidden to sale. */
    private static final String EAN_FORBIDDEN = "3660000099999";

    /** Seed weighed PLU (Pommes Golden). */
    private static final String PLU_POMMES = "4020";

    /** A PLU absent from the catalogue. */
    private static final String PLU_UNKNOWN = "9999";

    /** A 13-digit code no scan handler claims (unknown code). */
    private static final String CODE_UNKNOWN = "9999999999999";

    /** An EAN absent from the active catalogue (not a 2x in-store code). */
    private static final String EAN_UNKNOWN = "3300000099999";

    /** A deposit voucher (298 + 10 digits) whose encoded amount is zero → illisible. */
    private static final String DEPOSIT_ZERO = "2980000000000";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The single server-side POS state — the oracle for client-invisible outcomes. */
    @Inject
    PosState posState;

    /**
     * The payment orchestrator, injected to play the virtual TPE's accept/refuse
     * decisions exactly as GroupFIT/GroupKIT do — the {@code /api/hardware/tpe/*}
     * HTTP routes are shadowed on the test classpath by the peripheral simulator
     * mounted at the same {@code /api/hardware} root, so the simulator's TPE
     * buttons are played through the bean (the register's own decision handler).
     */
    @Inject
    PaymentService paymentService;

    // ================================================================= O-A =
    // Ticket message zone (transient — cleared at the next scan).

    /**
     * O-A (scan zone) — every scan-driven transient message and its clearing.
     * <p>
     * Proves, on one sale screen: the session gate off the sale
     * ({@code AUCUNE SESSION OUVERTE - MENU CAISSE}, server-side before the
     * shared session opens), then {@code PRODUIT INTERDIT À LA VENTE},
     * {@code CODE INCONNU: &lt;code&gt;} (server-side, no version bump),
     * {@code PRODUIT INTROUVABLE}, {@code PLU INTROUVABLE},
     * {@code ARTICLE BALANCE INTROUVABLE (&lt;plu&gt;)},
     * {@code ÉTIQUETTE DÉJÀ SCANNÉE}, {@code POIDS INVALIDE},
     * {@code ERREUR POIDS IDENTIQUE}, {@code ERREUR PRIX SAISI} and
     * {@code BON DE CONSIGNE ILLISIBLE}. Each is asserted shown then cleared by
     * a following valid scan (the transitory contract).
     */
    @Test
    void oa1_scan_zone_messages() {
        // --- Session gate, proven BEFORE the shared session is opened ---
        for (Page open : context.pages()) open.close();
        Assertions.assertNull(openSessionOnC04(), "oa1 must run first, on the session-less fresh boot");
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.getByText("Aucune session ouverte").waitFor();
        closeDrawer();
        scan(EAN_LAIT);
        Assertions.assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", posState.ticket.transientError,
                "a sale scan with no session open must set the session-gate message");
        // Open the shared session through the screen; the rest of the group reuses it.
        page.locator("#openingFloat").fill(OPENING_FLOAT);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("OUVRIR LA SESSION").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
        // The gate message clears the moment a real scan lands (session now open).
        scan(EAN_LAIT);
        page.getByText(LABEL_LAIT).first().waitFor();
        Assertions.assertNull(posState.ticket.transientError, "opening a session and scanning clears the gate message");

        // --- PRODUIT INTERDIT À LA VENTE (rendered) ---
        scan(EAN_FORBIDDEN);
        assertSaleErrorShown(page, "PRODUIT INTERDIT À LA VENTE");
        assertSaleErrorClears(page);

        // --- CODE INCONNU: <code> (server-side: direct assign, no version bump) ---
        scan(CODE_UNKNOWN);
        Assertions.assertEquals("CODE INCONNU: " + CODE_UNKNOWN, posState.ticket.transientError,
                "an unclaimed code must set the unknown-code message on the state");
        scan(EAN_LAIT);
        Assertions.assertNull(posState.ticket.transientError, "the next scan clears the unknown-code message");

        // --- PRODUIT INTROUVABLE (direct add of an unknown EAN) ---
        postForm("action/manual-add-known", "ean=" + EAN_UNKNOWN + "&quantity=1");
        assertSaleErrorShown(page, "PRODUIT INTROUVABLE");
        assertSaleErrorClears(page);

        // --- PLU INTROUVABLE (typed PLU absent from the catalogue) ---
        get("action/add/" + PLU_UNKNOWN);
        assertSaleErrorShown(page, "PLU INTROUVABLE");
        assertSaleErrorClears(page);

        // --- ARTICLE BALANCE INTROUVABLE (<plu>) — 2x label, unknown article ---
        scan(weightLabel("00009", "01000"));
        assertSaleErrorShown(page, "ARTICLE BALANCE INTROUVABLE (9)");
        assertSaleErrorClears(page);

        // --- ÉTIQUETTE DÉJÀ SCANNÉE — same price-embedded sticker twice ---
        String sticker = priceLabel("04020", "00150");
        scan(sticker);
        scan(sticker);
        assertSaleErrorShown(page, "ÉTIQUETTE DÉJÀ SCANNÉE");
        assertSaleErrorClears(page);

        // --- POIDS INVALIDE — weighed add with a null scale weight ---
        setWeight("0");
        get("action/add/" + PLU_POMMES);
        assertSaleErrorShown(page, "POIDS INVALIDE");
        assertSaleErrorClears(page);

        // --- ERREUR POIDS IDENTIQUE — two consecutive equal weighings ---
        setWeight("1.250");
        get("action/add/" + PLU_POMMES);
        page.getByText("POMMES GOLDEN").first().waitFor();
        setWeight("1.250");
        get("action/add/" + PLU_POMMES);
        assertSaleErrorShown(page, "ERREUR POIDS IDENTIQUE");
        assertSaleErrorClears(page);

        // --- ERREUR PRIX SAISI — unknown item with an unparseable price ---
        postForm("action/manual-add-unknown", "label=TEST&price=abc");
        assertSaleErrorShown(page, "ERREUR PRIX SAISI");
        assertSaleErrorClears(page);

        // --- BON DE CONSIGNE ILLISIBLE — deposit voucher with a zero amount ---
        scan(DEPOSIT_ZERO);
        assertSaleErrorShown(page, "BON DE CONSIGNE ILLISIBLE");
        assertSaleErrorClears(page);

        clearCartWithManager(page);
        page.close();
    }

    /**
     * O-A (line-action zone) — the selection / quantity / gesture-value
     * messages. Proves {@code AUCUNE LIGNE SÉLECTIONNÉE} (a price gesture with
     * nothing selected), {@code LIGNE INTROUVABLE} (a stale line uid),
     * {@code QUANTITÉ INVALIDE (1-999)} (out-of-bounds on a unit line),
     * {@code QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE} (a weighed line) and
     * {@code VALEUR INVALIDE} (an unparseable gesture value), each shown then
     * cleared.
     */
    @Test
    void oa2_line_action_messages() {
        Page page = freshSaleLogin();
        clearCartWithManager(page);

        // --- AUCUNE LIGNE SÉLECTIONNÉE — a gesture with an empty cart (no target) ---
        get("action/price-mod/remise");
        assertSaleErrorShown(page, "AUCUNE LIGNE SÉLECTIONNÉE");
        assertSaleErrorClears(page);

        // A unit line (quantity-modifiable) and a weighed line (not).
        page.getByText(LABEL_LAIT).first().waitFor();
        setWeight("1.000");
        get("action/add/" + PLU_POMMES);
        page.getByText("POMMES GOLDEN").first().waitFor();
        String unitUid = unitLine().uid;
        String weighedUid = weighedLine().uid;

        // --- LIGNE INTROUVABLE — a QUANTITY submit on a forged uid ---
        postForm("action/price-mod/submit", "type=QUANTITY&uid=stale-uid-x&rawValue=2");
        assertSaleErrorShown(page, "LIGNE INTROUVABLE");
        assertSaleErrorClears(page);

        // --- QUANTITÉ INVALIDE (1-999) — out of bounds on the unit line ---
        postForm("action/price-mod/submit", "type=QUANTITY&uid=" + unitUid + "&rawValue=1000");
        assertSaleErrorShown(page, "QUANTITÉ INVALIDE (1-999)");
        assertSaleErrorClears(page);

        // --- QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE — a weighed line ---
        postForm("action/price-mod/submit", "type=QUANTITY&uid=" + weighedUid + "&rawValue=2");
        assertSaleErrorShown(page, "QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        assertSaleErrorClears(page);

        // --- VALEUR INVALIDE — an unparseable gesture value ---
        postForm("action/price-mod/submit", "type=REMISE&uid=" + unitUid + "&rawValue=abc");
        assertSaleErrorShown(page, "VALEUR INVALIDE");
        assertSaleErrorClears(page);

        clearCartWithManager(page);
        page.close();
    }

    /**
     * O-A (state-guard zone) — the messages gated by cart / training / payment
     * state. Proves {@code TERMINEZ OU ANNULEZ LE TICKET D'ABORD} (a training
     * request over a non-empty cart), {@code RÉIMPRESSION INDISPONIBLE EN
     * FORMATION} (reprint in training), {@code SUPERVISION NON CONFIGURÉE SUR
     * CETTE CAISSE} (a supervisor call with no store-url) and
     * {@code PAIEMENT REFUSÉ PAR LE TPE} (a refused virtual TPE). The four
     * store-node supervisor outcomes are justified residue (see the class
     * Javadoc).
     */
    @Test
    void oa3_state_guard_messages() {
        Page page = freshSaleLogin();
        clearCartWithManager(page);

        // --- TERMINEZ OU ANNULEZ LE TICKET D'ABORD — training over a live cart ---
        scan(EAN_LAIT);
        page.getByText(LABEL_LAIT).first().waitFor();
        get("action/training");
        assertSaleErrorShown(page, "TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        assertSaleErrorClears(page);
        clearCartWithManager(page);

        // --- RÉIMPRESSION INDISPONIBLE EN FORMATION — reprint while training ---
        setTraining(page, true);
        get("action/print-last");
        assertSaleErrorShown(page, "RÉIMPRESSION INDISPONIBLE EN FORMATION");
        assertSaleErrorClears(page);
        clearCartWithManager(page);
        setTraining(page, false);

        // --- SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE — no store-url ---
        get("action/supervisor/PROBLEME-TECHNIQUE");
        assertSaleErrorShown(page, "SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
        assertSaleErrorClears(page);

        // --- PAIEMENT REFUSÉ PAR LE TPE — a refused virtual terminal ---
        clearCartWithManager(page);
        scan(EAN_HUILE);
        page.getByText("HUILE D'OLIVE 1L").first().waitFor();
        goPay(page);
        postForm("action/pay-card", "amount=6,00");
        paymentService.refusePendingCard(posState);
        Assertions.assertEquals("PAIEMENT REFUSÉ PAR LE TPE", posState.ticket.transientError,
                "a refused virtual TPE must set the refusal message");
        Assertions.assertNull(posState.payment.pendingCardAmount, "the refused card request must be withdrawn");
        get("action/cancel");
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        clearCartWithManager(page);
        page.close();
    }

    // ================================================================= O-B =
    // Page messages (query param / state).

    /**
     * O-B — the page-level messages of the lock, session and PIN-change screens.
     * <p>
     * Lock: wrong credentials → {@code IDENTIFIANTS INCORRECTS}; a locked
     * account → {@code COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD} which persists
     * even for a correct PIN through the lockout. Session: a normal re-open over
     * an already-open session → {@code OUVERTURE IMPOSSIBLE (SESSION DÉJÀ
     * OUVERTE ?)}; a training open → the block is REAL (no session created) but
     * its {@code INDISPONIBLE EN FORMATION} text is carried only in the redirect
     * query and dropped by {@code GET /session}, so it is asserted on the 303
     * Location, not on the page. PIN change: {@code Code PIN actuel incorrect},
     * {@code Le nouveau code doit comporter 4 chiffres}, {@code Les nouveaux
     * codes ne correspondent pas} and the {@code Code PIN modifié} confirmation.
     */
    @Test
    void ob_page_messages() {
        ensureOpenSession();
        for (Page open : context.pages()) open.close();

        // --- PIN change messages (while an operator is logged in) ---
        String pinCurrentWrong = postForm("action/pin-change",
                "currentPin=9999&newPin=1234&confirmPin=1234").text();
        Assertions.assertTrue(pinCurrentWrong.contains("Code PIN actuel incorrect"),
                "a wrong current PIN must render its message");
        String pinFormat = postForm("action/pin-change",
                "currentPin=" + CASHIER_PIN + "&newPin=12&confirmPin=12").text();
        Assertions.assertTrue(pinFormat.contains("Le nouveau code doit comporter 4 chiffres"),
                "a non-4-digit new PIN must render the format message");
        String pinMismatch = postForm("action/pin-change",
                "currentPin=" + CASHIER_PIN + "&newPin=1234&confirmPin=5678").text();
        Assertions.assertTrue(pinMismatch.contains("Les nouveaux codes ne correspondent pas"),
                "a confirmation mismatch must render its message");
        String pinOk = postForm("action/pin-change",
                "currentPin=" + CASHIER_PIN + "&newPin=" + CASHIER_PIN + "&confirmPin=" + CASHIER_PIN).text();
        Assertions.assertTrue(pinOk.contains("Code PIN modifié"),
                "a valid PIN change must render the confirmation (seed PIN unchanged)");

        // --- Session: OUVERTURE IMPOSSIBLE over an already-open session ---
        String reopen = postForm("action/session/open", "openingFloat=100,00").text();
        Assertions.assertTrue(reopen.contains("OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)"),
                "re-opening an open session must render the open-failed message");

        // --- Session: INDISPONIBLE EN FORMATION carried only on the redirect ---
        Page sale = freshSaleLogin();
        setTraining(sale, true);
        APIResponse trainingOpen = postFormNoRedirect("action/session/open", "openingFloat=100,00", null);
        Assertions.assertEquals(303, trainingOpen.status(), "a training session-open must redirect");
        Assertions.assertTrue(trainingOpen.headers().get("location").contains("INDISPONIBLE+EN+FORMATION"),
                "the training block must be carried on the redirect query");
        Assertions.assertFalse(get("session").text().contains("INDISPONIBLE EN FORMATION"),
                "GET /session drops the training code: the text is never rendered");
        setTraining(sale, false);
        sale.close();

        // --- Lock: IDENTIFIANTS INCORRECTS then COMPTE VERROUILLÉ (persisting) ---
        String first = postForm("action/unlock", "login=" + CASHIER_LOGIN + "&password=0000").text();
        Assertions.assertTrue(first.contains("IDENTIFIANTS INCORRECTS"),
                "a wrong PIN must render the invalid-credentials message");
        postForm("action/unlock", "login=" + CASHIER_LOGIN + "&password=0000");
        String locked = postForm("action/unlock", "login=" + CASHIER_LOGIN + "&password=0000").text();
        Assertions.assertTrue(locked.contains("COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD"),
                "crossing the attempt threshold must render the lockout message");
        String stillLocked = postForm("action/unlock",
                "login=" + CASHIER_LOGIN + "&password=" + CASHIER_PIN).text();
        Assertions.assertTrue(stillLocked.contains("COMPTE VERROUILLÉ - RÉESSAYEZ PLUS TARD"),
                "the lockout must persist even for a correct PIN during the lockout window");
        resetAccountLock(CASHIER_LOGIN);
    }

    // ================================================================= O-C =
    // Return screen (staging — persists until corrected).

    /**
     * O-C — the refund staging guards. Produces a closed cash ticket, then
     * proves {@code RIEN À REMBOURSER} (nothing staged), the in-transaction
     * {@code PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : &lt;montant&gt;)}
     * (a free amount over the ticket cap, refused only AFTER the manager
     * endorsement grant, then fully rolled back) and {@code RETOURS
     * INDISPONIBLES EN FORMATION} (a method requested in training). The
     * interleaved-second-refund {@code QUANTITÉ DÉJÀ REMBOURSÉE} variant is
     * justified residue on a single-operator register.
     */
    @Test
    void oc_return_staging_guards() {
        Page page = freshSaleLogin();
        Long ticketId = closeCashSaleOn(page, "20", EAN_HUILE);

        // --- RIEN À REMBOURSER — nothing staged ---
        page.navigate(base.toString() + "return/select/" + ticketId);
        page.getByText("Montant Retour").waitFor();
        page.navigate(base.toString() + "return/pay/cash");
        page.getByText("RIEN À REMBOURSER").waitFor();
        Assertions.assertEquals("RIEN À REMBOURSER", posState.refund.errorMessage,
                "an empty staging must refuse with RIEN À REMBOURSER");

        // --- PLAFOND DU TICKET DÉPASSÉ — free amount over cap, re-checked in tx ---
        page.navigate(base.toString() + "return/select/" + ticketId);
        page.getByText("Montant Retour").waitFor();
        postForm("return/submit-amount", "rawValue=999");
        page.navigate(base.toString() + "return/pay/cash");
        approveEndorsementWithManager(page);
        Assertions.assertNotNull(posState.refund.errorMessage, "the endorsed over-cap refund must leave a message");
        Assertions.assertTrue(posState.refund.errorMessage.startsWith("PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ :"),
                "the in-transaction cap guard must refuse with PLAFOND and roll back");

        // --- RETOURS INDISPONIBLES EN FORMATION — a method in training ---
        setTraining(page, true);
        page.navigate(base.toString() + "return/pay/cash");
        page.getByText("RETOURS INDISPONIBLES EN FORMATION").waitFor();
        Assertions.assertEquals("RETOURS INDISPONIBLES EN FORMATION", posState.refund.errorMessage,
                "a refund method requested in training must be refused");
        setTraining(page, false);
        page.close();
    }

    // ================================================================= O-D =
    // Payment vouchers (bon zone of the pay screen).

    /**
     * O-D — the voucher-number errors of the pay screen. On a live payment,
     * proves {@code Numéro non reconnu — vérifiez la saisie} (a MANUAL Catalina
     * type with a number failing its pattern) and {@code Type de bon inconnu}
     * (a number submitted with no type selected). The message renders in the
     * {@code .voucher-error} zone and is mirrored on {@code state.payment
     * .voucherError}.
     */
    @Test
    void od_payment_voucher_errors() {
        Page page = freshSaleLogin();
        scan(EAN_HUILE);
        page.getByText("HUILE D'OLIVE 1L").first().waitFor();
        goPay(page);

        // --- Numéro non reconnu — a Catalina number failing its pattern ---
        post("action/voucher-open");
        postForm("action/voucher-select", "code=CATALINA");
        postForm("action/voucher-number", "number=0000");
        Assertions.assertEquals("Numéro non reconnu — vérifiez la saisie", posState.payment.voucherError,
                "a number failing the type pattern must be rejected");
        page.navigate(base.toString() + "pay");
        page.getByText("Numéro non reconnu — vérifiez la saisie").waitFor();

        // --- Type de bon inconnu — a number with no type selected ---
        post("action/voucher-cancel");
        postForm("action/voucher-number", "number=123");
        Assertions.assertEquals("Type de bon inconnu", posState.payment.voucherError,
                "a number with no selected type must be rejected as an unknown type");

        post("action/voucher-cancel");
        get("action/cancel");
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        clearCartWithManager(page);
        page.close();
    }

    // ================================================================= O-E =
    // Modals & overlays — full cycle of each surface.

    /**
     * O-E (gesture &amp; endorsement modals). Opens the gesture modal for the
     * four types (REMISE / DISCOUNT / FORCE_PRICE / QUANTITY) — the PosInput is
     * wired ({@code #priceKbArea}); an unparseable value → {@code VALEUR
     * INVALIDE}; ANNULER parks the gesture inert (no trace on the line); VALIDER
     * on QUANTITY applies directly (no endorsement) while REMISE routes through
     * the endorsement modal. The endorsement modal then proves a wrong manager
     * PIN → {@code AUTORISATION REFUSÉE} (shared lockout counter), a manager
     * badge prefill → execution, and ANNULER → the gesture abandoned.
     */
    @Test
    void oe1_gesture_and_endorsement_modals() {
        Page page = freshSaleLogin();
        clearCartWithManager(page);
        scan(EAN_LAIT);
        page.getByText(LABEL_LAIT).first().waitFor();
        String uid = unitLine().uid;

        // --- The four gesture modals open with the PosInput wired ---
        for (String type : new String[] {"remise", "discount", "force_price", "quantity"}) {
            get("action/select/0");
            page.navigate(base.toString() + "action/price-mod/" + type);
            Assertions.assertTrue(page.locator(".price-modal.active").isVisible(),
                    "the gesture modal must open for type " + type);
            page.locator("#priceKbArea").waitFor();
            get("action/price-mod/cancel");
        }

        // --- VALEUR INVALIDE — an unparseable gesture value ---
        get("action/select/0");
        get("action/price-mod/remise");
        postForm("action/price-mod/submit", "type=REMISE&uid=" + uid + "&rawValue=abc");
        assertSaleErrorShown(page, "VALEUR INVALIDE");
        assertSaleErrorClears(page);

        // --- ANNULER leaves no trace (gesture parked inert) ---
        get("action/select/0");
        get("action/price-mod/remise");
        get("action/price-mod/cancel");
        Assertions.assertNull(unitLine().modifierType, "cancelling the gesture must leave the line untouched");

        // --- VALIDER on QUANTITY is direct (no endorsement) ---
        get("action/select/0");
        get("action/price-mod/quantity");
        postForm("action/price-mod/submit", "type=QUANTITY&uid=" + uid + "&rawValue=3");
        Assertions.assertFalse(posState.endorsement.active, "a QUANTITY validate must NOT open the endorsement modal");
        Assertions.assertEquals(0, unitLine().quantity.compareTo(new java.math.BigDecimal("3")),
                "a QUANTITY validate must apply the quantity directly");

        // --- REMISE validate parks the endorsement; wrong PIN → AUTORISATION REFUSÉE ---
        get("action/select/0");
        get("action/price-mod/remise");
        postForm("action/price-mod/submit", "type=REMISE&uid=" + uid + "&rawValue=1");
        Assertions.assertTrue(posState.endorsement.active, "a REMISE validate must open the endorsement modal");
        postForm("action/endorse-validate", "login=" + MANAGER_LOGIN + "&password=9999");
        Assertions.assertEquals("AUTORISATION REFUSÉE", posState.endorsement.error,
                "a wrong manager PIN must refuse the endorsement");
        resetAccountLock(MANAGER_LOGIN);

        // --- Manager badge prefill → execution of the still-parked gesture ---
        approveEndorsementWithManager(page);
        Assertions.assertEquals("REMISE", unitLine().modifierType,
                "the endorsed gesture must execute once the manager PIN validates");

        // --- ANNULER abandons a freshly-parked gesture (no execution) ---
        get("action/select/0");
        get("action/price-mod/discount");
        postForm("action/price-mod/submit", "type=DISCOUNT&uid=" + uid + "&rawValue=5");
        Assertions.assertTrue(posState.endorsement.active, "the DISCOUNT gesture must be parked pending endorsement");
        get("action/endorse-cancel");
        Assertions.assertFalse(posState.endorsement.active, "ANNULER must clear the pending endorsement");
        Assertions.assertEquals("REMISE", unitLine().modifierType,
                "the abandoned DISCOUNT must not overwrite the earlier REMISE");

        clearCartWithManager(page);
        page.close();
    }

    /**
     * O-E (TPE overlay &amp; completion modal). The TPE overlay appears on a
     * card request (amount shown), ACCEPT settles and completes, REFUSE
     * withdraws with {@code PAIEMENT REFUSÉ PAR LE TPE}, and register CANCEL
     * withdraws the request. The completion modal appears at a null balance: the
     * IMPRIMER button prints the still-OPEN draft (no closure), NOUVELLE VENTE
     * is the fiscal close, and an overpaying cash tender shows the {@code Rendu
     * Client} change.
     */
    @Test
    void oe2_tpe_and_completion_modals() {
        Page page = freshSaleLogin();
        clearCartWithManager(page);

        // --- TPE overlay: appears, then ACCEPT settles and completes ---
        scan(EAN_HUILE);
        page.getByText("HUILE D'OLIVE 1L").first().waitFor();
        goPay(page);
        postForm("action/pay-card", "amount=6,00");
        page.navigate(base.toString() + "pay");
        page.getByText("PAIEMENT CARTE EN COURS").waitFor();
        Assertions.assertNotNull(posState.payment.pendingCardAmount, "the TPE overlay must carry a pending amount");
        paymentService.confirmPendingCard(posState);
        page.navigate(base.toString() + "pay");
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        Assertions.assertTrue(posState.payment.transactionComplete, "an accepted card at par must complete the sale");
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "the completed sale must carry a persisted draft");
        Long closedBefore = posState.lastClosedTicketId;

        // --- Completion IMPRIMER prints the still-OPEN draft (no closure) ---
        post("action/print");
        Assertions.assertEquals(draftId, posState.payment.ticketDbId,
                "IMPRIMER must keep the same OPEN draft (no fiscal close)");
        Assertions.assertEquals(closedBefore, posState.lastClosedTicketId,
                "IMPRIMER must not close any ticket (the last-closed id is unchanged)");
        // --- NOUVELLE VENTE is the fiscal close ---
        closeDrawer();
        get("action/finish");
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertNull(posState.payment.ticketDbId, "NOUVELLE VENTE must clear the settled draft");
        Assertions.assertEquals(draftId, posState.lastClosedTicketId,
                "NOUVELLE VENTE is the fiscal close: this ticket becomes the last closed one");

        // --- Completion overpay: the Rendu Client change is shown ---
        clearCartWithManager(page);
        scan(EAN_HUILE);
        page.getByText("HUILE D'OLIVE 1L").first().waitFor();
        goPay(page);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), "20");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        page.getByText("Rendu Client").waitFor();
        Assertions.assertNotNull(posState.payment.lastChangeAmount, "an overpay must record the change due");
        Assertions.assertTrue(posState.payment.lastChangeAmount.signum() > 0, "the change due must be positive");
        finishSale(page);

        // --- TPE overlay: REFUSE withdraws with the refusal message ---
        clearCartWithManager(page);
        scan(EAN_HUILE);
        page.getByText("HUILE D'OLIVE 1L").first().waitFor();
        goPay(page);
        postForm("action/pay-card", "amount=6,00");
        paymentService.refusePendingCard(posState);
        Assertions.assertEquals("PAIEMENT REFUSÉ PAR LE TPE", posState.ticket.transientError,
                "a refused TPE must set the refusal message");
        Assertions.assertNull(posState.payment.pendingCardAmount, "REFUSE must withdraw the card request");

        // --- TPE overlay: register CANCEL withdraws the request ---
        postForm("action/pay-card", "amount=6,00");
        Assertions.assertNotNull(posState.payment.pendingCardAmount, "a fresh card request must re-arm the overlay");
        get("action/card-cancel");
        Assertions.assertNull(posState.payment.pendingCardAmount, "register CANCEL must withdraw the card request");

        get("action/cancel");
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        clearCartWithManager(page);
        page.close();
    }

    /**
     * O-E (drawer interstitial &amp; the FORMATION banner). A blocked GET on a
     * {@code @DrawerMustBeClosed} screen diverts to {@code /drawer-error} and,
     * once the drawer is shut, returns to the very PATH that was blocked. A
     * blocked POST diverts likewise but returns to the REFERER (never a replay
     * of the stale mutation). The FORMATION banner (z-50) overlays every screen
     * when training is on.
     */
    @Test
    void oe3_drawer_interstitial_and_banners() {
        Page page = freshSaleLogin();
        clearCartWithManager(page);

        // --- GET block on a guarded screen → drawer-error, return to the PATH ---
        openDrawer();
        page.navigate(base.toString() + "manual");
        page.locator("a[href='/action/resume-after-drawer']").waitFor();
        Assertions.assertTrue(page.url().endsWith("/drawer-error"),
                "a guarded GET with the drawer open must divert to the drawer interstitial");
        closeDrawer();
        // The interstitial polls /api/drawer-status and, once shut, auto-returns
        // to the blocked path (/manual) — proven by its content reappearing.
        page.getByText("SAISIE DIRECTE").waitFor(new Locator.WaitForOptions().setTimeout(15000));
        Assertions.assertTrue(page.url().endsWith("/manual"),
                "closing the drawer must return to the very path that was blocked");

        // --- POST block → return to the REFERER, never a replay ---
        openDrawer();
        APIResponse blocked = postFormNoRedirect("action/print", "", base.toString() + "pay");
        Assertions.assertEquals(303, blocked.status(), "a POST to a guarded route with the drawer open must divert");
        Assertions.assertTrue(blocked.headers().get("location").endsWith("/drawer-error"),
                "the blocked POST must divert to the drawer interstitial");
        closeDrawer();
        String redirect = get("api/drawer-status").text();
        Assertions.assertTrue(redirect.contains("\"redirect\":\"/pay\""),
                "the blocked POST must return to the referer path, not replay the mutation");
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();

        // --- FORMATION banner (z-50) overlays the sale screen while training ---
        setTraining(page, true);
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
        Locator banner = page.locator(".overlay-training");
        banner.waitFor();
        Assertions.assertTrue(banner.textContent().contains("MODE FORMATION"),
                "the FORMATION banner must carry its warning text");
        Object z = banner.evaluate("el => parseInt(getComputedStyle(el).zIndex)");
        Assertions.assertEquals(50, ((Number) z).intValue(), "the FORMATION banner must sit at z-index 50");
        setTraining(page, false);
        clearCartWithManager(page);
        page.close();
    }

    // --- Ticket-zone assertions ---------------------------------------------

    /**
     * Asserts a transient message is shown in the sale-screen message zone (the
     * polled {@code .error-line}), waiting for the 1s poll to swap it in.
     *
     * @param page the Playwright page sitting on the sale screen
     * @param message the exact user-facing message expected
     */
    private void assertSaleErrorShown(Page page, String message) {
        page.locator(".error-line").getByText(message).waitFor();
    }

    /**
     * Proves the transitory contract for the current message: a following valid
     * scan clears it and the whole {@code .error-line} detaches on the next
     * poll.
     *
     * @param page the Playwright page sitting on the sale screen
     */
    private void assertSaleErrorClears(Page page) {
        scan(EAN_LAIT);
        page.locator(".error-line").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED));
        Assertions.assertNull(posState.ticket.transientError, "the transient message must clear on the next scan");
    }

    // --- Cart / ticket-line oracles -----------------------------------------

    /**
     * Finds the first unit line of the current cart (an EAN line carrying no PLU).
     *
     * @return the unit ticket line, or null when none is present
     */
    private TicketState.TicketItem unitLine() {
        return posState.ticket.items.stream()
                .filter(i -> i.ean != null && !i.ean.isEmpty() && (i.plu == null || i.plu.isEmpty()))
                .findFirst().orElse(null);
    }

    /**
     * Finds the first weighed line of the current cart (a line carrying a PLU).
     *
     * @return the weighed ticket line, or null when none is present
     */
    private TicketState.TicketItem weighedLine() {
        return posState.ticket.items.stream()
                .filter(i -> i.plu != null && !i.plu.isEmpty())
                .findFirst().orElse(null);
    }

    // --- Reusable journeys --------------------------------------------------

    /**
     * Closes any lingering pages, ensures the shared session is open, and lands
     * a fresh page logged in as the cashier on the empty sale screen.
     *
     * @return a Playwright page on the sale screen
     */
    private Page freshSaleLogin() {
        for (Page open : context.pages()) open.close();
        ensureOpenSession();
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
        return page;
    }

    /**
     * Ensures an OPEN session exists on C04, opening one once through the
     * prise-de-poste screen (as the cashier) when none is open.
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
     * Builds and finalizes a cash sale on a logged-in page: scans the products,
     * tenders an (over)paying cash amount, completes and closes the transaction,
     * returning the settled ticket's database id (captured before the close).
     *
     * @param page the Playwright page on the empty sale screen
     * @param cashDigits the cash amount digits to tap (must cover the total)
     * @param eans the product EANs to scan (repeats merge)
     * @return the database id of the closed ticket
     */
    private Long closeCashSaleOn(Page page, String cashDigits, String... eans) {
        clearCartWithManager(page);
        for (String ean : eans) scan(ean);
        goPay(page);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        tapDigits(page.locator("#payNumpadZone"), cashDigits);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("VALIDER ESPÈCES").setExact(true)).click();
        page.getByText("TRANSACTION TERMINÉE").waitFor();
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
     * Closes the completed transaction cleanly (the fiscal moment): shuts the
     * drawer so the guard lets the finish through, taps NOUVELLE VENTE and waits
     * for the empty sale screen.
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
     * Empties the shared server-side cart through the endorsed cancel gesture
     * (the only way to clear the cart on the HTTP surface), a no-op when the
     * cart is already empty.
     *
     * @param page the Playwright page on the sale screen
     */
    private void clearCartWithManager(Page page) {
        if (posState.ticket.items.isEmpty()) return;
        get("action/cancelTicket");
        approveEndorsementWithManager(page);
    }

    /**
     * Flips the training mode to the wanted value through the endorsed toggle
     * (empty cart required by the guard), a no-op when already in that mode.
     *
     * @param page the Playwright page on the sale screen
     * @param wanted the desired training-mode value
     */
    private void setTraining(Page page, boolean wanted) {
        if (posState.trainingMode == wanted) return;
        clearCartWithManager(page);
        get("action/training");
        approveEndorsementWithManager(page);
        Assertions.assertEquals(wanted, posState.trainingMode, "the endorsed toggle must flip the training mode");
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
        APIResponse res = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(badge));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the badge");
        page.getByText("Entrez votre code PIN :").waitFor();
        tapDigits(page.locator("#keyboardArea"), pin);
        page.locator("#actionBtn").click();
    }

    // --- Hardware bus & keypad ----------------------------------------------

    /**
     * Presents a code on the hardware scan bus and asserts the bus accepted it.
     *
     * @param code the code to present on the bus
     */
    private void scan(String code) {
        APIResponse res = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the code " + code);
    }

    /**
     * Sets the next weight the scale simulator will report (kilograms).
     *
     * @param kg the weight to arm, as text (dot or comma decimal)
     */
    private void setWeight(String kg) {
        context.request().post(base.toString() + "api/hardware/set-weight",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(kg));
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
     * Pushes the drawer shut on the hardware bus (the embedded simulator).
     */
    private void closeDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/close", RequestOptions.create());
    }

    /**
     * Pops the drawer open on the hardware bus (to exercise the drawer guard).
     */
    private void openDrawer() {
        context.request().post(base.toString() + "api/hardware/drawer/open", RequestOptions.create());
    }

    // --- HTTP surface helpers -----------------------------------------------

    /**
     * Issues a GET on an app-relative path (following redirects).
     *
     * @param path the app-relative path (no leading slash)
     * @return the API response
     */
    private APIResponse get(String path) {
        return context.request().get(base.toString() + path);
    }

    /**
     * Issues an empty-body POST on an app-relative path (following redirects).
     *
     * @param path the app-relative path (no leading slash)
     * @return the API response
     */
    private APIResponse post(String path) {
        return context.request().post(base.toString() + path, RequestOptions.create());
    }

    /**
     * Issues a form-urlencoded POST on an app-relative path (following redirects).
     *
     * @param path the app-relative path (no leading slash)
     * @param body the urlencoded form body ({@code k=v&k2=v2})
     * @return the API response
     */
    private APIResponse postForm(String path, String body) {
        return context.request().post(base.toString() + path,
                RequestOptions.create()
                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData(body));
    }

    /**
     * Issues a form-urlencoded POST that does NOT follow redirects, optionally
     * with a Referer — so the raw 303 Location can be asserted.
     *
     * @param path the app-relative path (no leading slash)
     * @param body the urlencoded form body
     * @param referer the Referer header to send, or null for none
     * @return the API response (a 303 when a redirect is issued)
     */
    private APIResponse postFormNoRedirect(String path, String body, String referer) {
        RequestOptions opts = RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setMaxRedirects(0)
                .setData(body);
        if (referer != null) opts.setHeader("Referer", referer);
        return context.request().post(base.toString() + path, opts);
    }

    // --- Database oracles ---------------------------------------------------

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Resets an account's lockout (the only DB scaffolding of this group, and
     * legitimate account-state restoration): clears the failed-attempt counter
     * and the lockout timestamp so a provoked lockout never leaks onto the next
     * scenario.
     *
     * @param login the employee login name to unlock
     */
    private void resetAccountLock(String login) {
        QuarkusTransaction.requiringNew().run(() -> {
            Employee e = Employee.find("loginName", login).firstResult();
            if (e != null) {
                e.failedAttempts = 0;
                e.lockedUntil = null;
            }
        });
    }

    // --- In-store EAN13 label builders --------------------------------------

    /**
     * Builds a valid price-embedded in-store EAN13 (prefix 21): the embedded
     * value is the line total in cents.
     *
     * @param article5 the 5-digit zero-padded article code (matched to a PLU)
     * @param cents5 the 5-digit zero-padded total in cents
     * @return the 13-digit label with a correct check digit
     */
    private String priceLabel(String article5, String cents5) {
        return ean13("21" + article5 + cents5);
    }

    /**
     * Builds a valid weight-embedded in-store EAN13 (prefix 25): the embedded
     * value is the weight in grams.
     *
     * @param article5 the 5-digit zero-padded article code (matched to a PLU)
     * @param grams5 the 5-digit zero-padded weight in grams
     * @return the 13-digit label with a correct check digit
     */
    private String weightLabel(String article5, String grams5) {
        return ean13("25" + article5 + grams5);
    }

    /**
     * Appends the EAN13 check digit to a 12-digit body, so the built labels pass
     * the scan handler's checksum instead of falling through.
     *
     * @param body12 the 12-digit body
     * @return the 13-digit code with its check digit
     */
    private String ean13(String body12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = body12.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return body12 + check;
    }
}
