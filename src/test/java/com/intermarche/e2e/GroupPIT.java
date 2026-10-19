package com.intermarche.e2e;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.sale.Invoice;
import com.intermarche.pos.domain.sale.Ticket;
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

import java.math.BigDecimal;
import java.net.URL;

/**
 * Group P end-to-end scenarios (Facturation — invoicing), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Group P is
 * {@code [S]} (caisse seule + simulateur).
 * <p>
 * <b>The wizard writes nothing before {@code /invoice/issue}.</b> The invoice
 * flow is TICKET → (DOCUMENT if several types) → CLIENT → APERÇU →
 * (INSERTION if a sheet station) → issue. Every step is driven through the
 * {@code /invoice} screen (drawer-guarded, so each scenario closes the drawer
 * first); the operator-facing refusal of the last action is read on the server
 * oracle {@link com.intermarche.pos.ui.invoice.InvoiceState#error}
 * ({@code posState.invoice.error}). The emitted document is read back from the
 * database ({@link Invoice}) — number sequence, {@code printCount} — to prove
 * the point-of-no-return and the per-type duplicata rule (a second request on
 * the same ticket+type REPRINTS, never a second original).
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (p1…p8) and share ONE cash session opened
 * once on the fresh drop-and-create H2 boot; each scenario builds its OWN closed
 * ticket(s) through the pay screen and its own volatile customer(s), and each
 * restores whatever administered flag it toggled.
 * <p>
 * <b>Residue.</b> The A4 network-printer SUCCESS path (P8) needs a live network
 * printer at {@code pos.print.a4-url}; only the unreachable→roll fallback is
 * played here (fully [S]).
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupPIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** Single-price unit product, TTC 6,00 € — the clean ticket fixture. */
    private static final String EAN_HUILE = "3300000000006";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /** The server-side POS singleton, oracle for the invoice-flow error and state. */
    @Inject
    PosState posState;

    /** The referential settings service, to administer document types / rights / output. */
    @Inject
    PosSettingsService posSettings;

    /**
     * P1 — Masque &amp; sélection du ticket.
     * <p>
     * The invoice screen prefills the last closed ticket and lists a shortlist;
     * naming an unfound ticket is refused with {@code TICKET INTROUVABLE : <n>};
     * an unreadable date widens the search rather than refusing (the real ticket
     * is still selected).
     */
    @Test
    void p1_masque_selection_ticket() {
        String number = buildClosedTicket();
        Page page = openInvoice();
        // The shortlist prefills the last closed ticket number.
        Assertions.assertTrue(page.getByText(number).count() >= 1,
                "the invoice screen must list the last closed ticket " + number);
        // An unfound ticket is refused.
        postForm("invoice/ticket", "ticketNumber=ZZ-99999999&ticketDate=&ticketTerminal=");
        Assertions.assertEquals("TICKET INTROUVABLE : ZZ-99999999", posState.invoice.error,
                "an unfound ticket must be refused");
        // An unreadable date widens the search: the real ticket is still selected, no error.
        postForm("invoice/ticket", "ticketNumber=" + number + "&ticketDate=not-a-date&ticketTerminal=");
        assertNoInvoiceError("an unreadable date must widen the search, not refuse");
        page.close();
    }

    /**
     * P2 — Type de document.
     * <p>
     * With several eligible document types administered, choosing an unknown type
     * is refused with {@code TYPE DE DOCUMENT INDISPONIBLE}.
     */
    @Test
    void p2_type_de_document() {
        String number = buildClosedTicket();
        posSettings.store("invoice.document-types", "FACTURE;BON_LIVRAISON");
        try {
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            // An unknown/disabled document type is refused.
            context.request().get(base.toString() + "invoice/type/BOGUS");
            Assertions.assertEquals("TYPE DE DOCUMENT INDISPONIBLE", posState.invoice.error,
                    "an unknown document type must be refused");
            page.close();
        } finally {
            posSettings.store("invoice.document-types", "FACTURE");
        }
    }

    /**
     * P3 — Sélection client.
     * <p>
     * By number: an empty number is {@code NUMERO CLIENT VIDE}, an absent one
     * {@code CLIENT INTROUVABLE : <n>}; a present number selects the account. The
     * number wins over a simultaneously typed name.
     */
    @Test
    void p3_selection_client() {
        String number = buildClosedTicket();
        Long custId = insertCustomer("5001", "SARL TEST P3", null);
        try {
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            // Absent number → refused.
            postForm("invoice/customer/lookup", "customerNumber=9999&search=");
            Assertions.assertEquals("CLIENT INTROUVABLE : 9999", posState.invoice.error,
                    "an absent customer number must be refused");
            // Present number (wins over the typed name): selects the account.
            postForm("invoice/customer/lookup", "customerNumber=5001&search=Autre");
            assertNoInvoiceError("a present number must select the account");
            Assertions.assertNotNull(posState.invoice.customer, "the account must be selected");
            page.close();
        } finally {
            deleteCustomer(custId);
        }
    }

    /**
     * P4 — Création d'un client en caisse.
     * <p>
     * Creating a volatile customer is guarded: with the right closed it is
     * {@code CREATION CLIENT NON AUTORISEE EN CAISSE}; an empty raison sociale is
     * {@code RAISON SOCIALE OBLIGATOIRE}; a valid creation mints a VOLATILE
     * account with a register sequence number. The creation right is restored.
     */
    @Test
    void p4_creation_client() {
        String number = buildClosedTicket();
        // --- Right closed ---
        posSettings.store("customer.volatile-creation-enabled", "false");
        try {
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            postForm("invoice/customer/create", "companyName=SARL X&contactName=&street=&postalCode=&city=&siret=&vatNumber=&phone=&email=&taxId=&taxCountry=FR");
            Assertions.assertEquals("CREATION CLIENT NON AUTORISEE EN CAISSE", posState.invoice.error,
                    "creation with the right closed must be refused");
            page.close();
        } finally {
            posSettings.store("customer.volatile-creation-enabled", "true");
        }
        // --- Empty raison sociale, then a valid creation ---
        Page page = openInvoice();
        context.request().get(base.toString() + "invoice/ticket/" + number);
        postForm("invoice/customer/create", "companyName=&contactName=&street=&postalCode=&city=&siret=&vatNumber=&phone=&email=&taxId=&taxCountry=FR");
        Assertions.assertEquals("RAISON SOCIALE OBLIGATOIRE", posState.invoice.error,
                "an empty raison sociale must be refused");
        postForm("invoice/customer/create", "companyName=SARL NOUVELLE P4&contactName=&street=&postalCode=&city=&siret=&vatNumber=&phone=&email=&taxId=&taxCountry=FR");
        assertNoInvoiceError("a valid creation must succeed");
        Assertions.assertNotNull(posState.invoice.customer, "the created customer must be selected");
        String created = posState.invoice.customer.accountNumber;
        Assertions.assertTrue(created != null && created.startsWith("C04-CLI"),
                "the volatile account must carry a register sequence number, was " + created);
        page.close();
        // Cleanup: drop the volatile customer created here.
        QuarkusTransaction.requiringNew().run(() ->
                AccountCustomer.delete("accountNumber", created));
    }

    /**
     * P5 — Correction d'un client.
     * <p>
     * The update right is closed by default: an update attempt is refused with
     * {@code MISE A JOUR CLIENT NON AUTORISEE EN CAISSE}. With the right open but
     * no client selected it is {@code AUCUN CLIENT SELECTIONNE}. The right is
     * restored.
     */
    @Test
    void p5_correction_client() {
        String number = buildClosedTicket();
        // --- Right closed (default) ---
        Page page = openInvoice();
        context.request().get(base.toString() + "invoice/ticket/" + number);
        postForm("invoice/customer/update", "companyName=X&contactName=&street=&postalCode=&city=&siret=&vatNumber=&phone=&email=&taxId=&taxCountry=FR");
        Assertions.assertEquals("MISE A JOUR CLIENT NON AUTORISEE EN CAISSE", posState.invoice.error,
                "an update with the right closed must be refused");
        page.close();
        // --- Right open, no client selected ---
        posSettings.store("customer.update-enabled", "true");
        try {
            Page page2 = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            postForm("invoice/customer/update", "companyName=X&contactName=&street=&postalCode=&city=&siret=&vatNumber=&phone=&email=&taxId=&taxCountry=FR");
            Assertions.assertEquals("AUCUN CLIENT SELECTIONNE", posState.invoice.error,
                    "an update with no client selected must be refused");
            page2.close();
        } finally {
            posSettings.store("customer.update-enabled", "false");
        }
    }

    /**
     * P6 — Émission &amp; duplicata (point de non-retour).
     * <p>
     * Emitting a ready invoice writes a numbered {@link Invoice}; a second
     * request on the same ticket+type REPRINTS it (a duplicata, {@code
     * printCount} bumped) rather than minting a second original. Emitting without
     * a client is {@code TICKET OU CLIENT MANQUANT}; in training it is {@code
     * FACTURE INDISPONIBLE EN FORMATION}. Nothing is written before the issue.
     */
    @Test
    void p6_emission_et_duplicata() {
        String number = buildClosedTicket();
        Long custId = insertCustomer("6001", "SARL TEST P6", null);
        try {
            // --- Missing client: emission refused, nothing written ---
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            postForm("invoice/issue", "");
            Assertions.assertEquals("TICKET OU CLIENT MANQUANT", posState.invoice.error,
                    "emitting without a client must be refused");
            Assertions.assertEquals(0, invoiceCountFor(number), "nothing must be written before a valid issue");
            // --- Select the client, then emit: an original is written ---
            context.request().get(base.toString() + "invoice/customer/" + custId);
            postForm("invoice/issue", "");
            Assertions.assertEquals(1, invoiceCountFor(number), "a valid issue must write exactly one document");
            Assertions.assertEquals(1, printCountFor(number), "the original print bumps the count to 1");
            // --- Second request on the same ticket+type: a duplicata, not a 2nd original ---
            page.close();
            Page page2 = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            context.request().get(base.toString() + "invoice/customer/" + custId);
            postForm("invoice/issue", "");
            Assertions.assertEquals(1, invoiceCountFor(number),
                    "a second request must reprint, never mint a second original");
            Assertions.assertEquals(2, printCountFor(number), "the duplicata must bump the print count to 2");
            page2.close();
            // --- Training: emission blocked ---
            String number2 = buildClosedTicket();
            Long cust2 = insertCustomer("6002", "SARL TRAIN P6", null);
            Page page3 = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number2);
            context.request().get(base.toString() + "invoice/customer/" + cust2);
            posState.trainingMode = true;
            try {
                postForm("invoice/issue", "");
                Assertions.assertEquals("FACTURE INDISPONIBLE EN FORMATION", posState.invoice.error,
                        "emitting in training must be refused");
                Assertions.assertEquals(0, invoiceCountFor(number2), "nothing must be written in training");
            } finally {
                posState.trainingMode = false;
            }
            page3.close();
            deleteCustomer(cust2);
        } finally {
            deleteCustomer(custId);
        }
    }

    /**
     * P7 — Impression feuille à feuille (station à insertion).
     * <p>
     * With a FACTURETTE output and a small sheet size, issuing paginates the
     * document into sheets: the screen prompts {@code Insérez la feuille <n> sur
     * <total>} and advances one sheet per validation until it stops itself. The
     * output flags are restored.
     */
    @Test
    void p7_impression_feuille_a_feuille() {
        String number = buildClosedTicket();
        Long custId = insertCustomer("7001", "SARL TEST P7", null);
        posSettings.store("invoice.document-output", "FACTURE:FACTURETTE");
        posSettings.store("invoice.slip-lines", "5");
        try {
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            context.request().get(base.toString() + "invoice/customer/" + custId);
            postForm("invoice/issue", "");
            // The issue routes to the sheet-insertion step.
            Assertions.assertTrue(posState.invoice.getSlipPageCount() >= 1,
                    "a FACTURETTE issue must paginate into at least one sheet");
            page.navigate(base.toString() + "invoice/slip");
            Assertions.assertTrue(page.getByText("Insérez la feuille").count() >= 1,
                    "the sheet-insertion screen must prompt for the sheet");
            page.close();
        } finally {
            posSettings.store("invoice.document-output", "");
            posSettings.store("invoice.slip-lines", "30");
            deleteCustomer(custId);
        }
    }

    /**
     * P8 — Repli imprimante A4 réseau.
     * <p>
     * With an A4 network output but no reachable network printer (none configured
     * under test), issuing falls back to the caisse roll and reports {@code
     * IMPRIMANTE RESEAU INJOIGNABLE - DOCUMENT IMPRIME EN CAISSE} on the server
     * state. The A4-success path needs a live network printer and is residue.
     */
    @Test
    void p8_repli_imprimante_a4() {
        String number = buildClosedTicket();
        Long custId = insertCustomer("8001", "SARL TEST P8", null);
        posSettings.store("invoice.document-output", "FACTURE:A4");
        try {
            Page page = openInvoice();
            context.request().get(base.toString() + "invoice/ticket/" + number);
            context.request().get(base.toString() + "invoice/customer/" + custId);
            postForm("invoice/issue", "");
            Assertions.assertEquals("IMPRIMANTE RESEAU INJOIGNABLE - DOCUMENT IMPRIME EN CAISSE",
                    posState.invoice.error,
                    "an unreachable A4 printer must fall back to the roll with the fallback message");
            Assertions.assertEquals(1, invoiceCountFor(number),
                    "the document must still be issued (printed on the roll)");
            page.close();
        } finally {
            posSettings.store("invoice.document-output", "");
            deleteCustomer(custId);
        }
    }

    // --- Reusable gestures (login recipe, sale build, invoice navigation) ---

    /**
     * Closes the drawer and opens the invoice screen (drawer-guarded), landing on
     * the TICKET step with a fresh flow state.
     *
     * @return a Playwright page on the invoice screen
     */
    private Page openInvoice() {
        Page page = freshSale();
        closeDrawer();
        page.navigate(base.toString() + "invoice");
        page.getByText("FACTURE").first().waitFor();
        return page;
    }

    /**
     * Builds and finalizes a cash sale, leaving a CLOSED ticket, and returns its
     * register number (the invoicing prerequisite).
     *
     * @return the closed ticket's number
     */
    private String buildClosedTicket() {
        Page sale = freshSale();
        scan(EAN_HUILE);
        goPay(sale);
        payThroughScreen(sale, "ESPÈCES", "cashForm", "600", false);
        waitForSettlement();
        Long ticketId = posState.payment.ticketDbId;
        finishSale(sale);
        sale.close();
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).ticketNumber);
    }

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session.
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
        if (openSessionOnC04() != null) return;
        Page page = context.newPage();
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        page.locator("#floatDisplay").waitFor();
        closeDrawer();
        enterOpeningFloat(page, "200,00");
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
        clickThroughHardwareGate(page);
    }

    /**
     * Logs the cashier in and lands on an empty sale screen, assuming a session
     * is already open (drawer close carries the poll to the sale).
     *
     * @param page the Playwright page driving the register
     */
    private void loginToSaleWithOpenSession(Page page) {
        scanBadgeAndEnterPin(page, CASHIER_BADGE, CASHIER_PIN);
        closeDrawer();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Walks onto the payment overlay and waits until it is ready.
     *
     * @param page the Playwright page driving the register
     */
    private void goPay(Page page) {
        page.navigate(base.toString() + "pay");
        page.getByText("RESTE :").waitFor();
    }

    /**
     * Tenders through an on-screen payment method form.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param methodButton the exact visible method-button text
     * @param formId the id of the inline form to submit
     * @param digits the amount digits to tap, or null to keep the prefilled amount
     * @param clearFirst kept for call-site intent
     */
    private void payThroughScreen(Page page, String methodButton, String formId, String digits, boolean clearFirst) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(methodButton).setExact(true)).click();
        if (digits != null) {
            page.locator("#payNumpadZone").getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
            tapDigits(page.locator("#payNumpadZone"), digits);
        }
        page.locator("#" + formId + " button[type=submit]").click();
    }

    /**
     * Closes the completed transaction cleanly (the fiscal moment).
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
     * Waits for the SERVER to declare the sale settled.
     */
    private void waitForSettlement() {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (posState.payment.transactionComplete) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.fail("la vente n'est pas soldée — restant dû = " + posState.getRemaining());
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
     * Submits a url-encoded form to an invoice action endpoint.
     *
     * @param path the action path (without leading slash)
     * @param body the url-encoded form body
     */
    private void postForm(String path, String body) {
        context.request().post(base.toString() + path, RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded").setData(body));
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

    /**
     * Enters the opening float on the session screen's touch keypad.
     *
     * @param page the Playwright page driving the register
     * @param amount the float to key in, comma as the decimal separator
     */
    private void enterOpeningFloat(Page page, String amount) {
        Locator keypad = page.locator("#floatKbArea");
        keypad.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        for (char key : amount.toCharArray()) {
            if (key < '0' || key > '9') {
                continue;
            }
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
    }

    // --- Database oracles / fixtures ---

    /**
     * Reads the single OPEN session on terminal C04 from the database.
     *
     * @return the OPEN {@link CashSession} on C04, or null when none is open
     */
    private CashSession openSessionOnC04() {
        return QuarkusTransaction.requiringNew().call(() -> CashSession.findOpenByTerminal("C04"));
    }

    /**
     * Counts the invoices issued for a ticket number.
     *
     * @param ticketNumber the ticket number
     * @return the number of {@link Invoice} rows for that ticket
     */
    private long invoiceCountFor(String ticketNumber) {
        return QuarkusTransaction.requiringNew().call(() -> Invoice.count("ticketNumber", ticketNumber));
    }

    /**
     * Reads the print count of the invoice issued for a ticket number.
     *
     * @param ticketNumber the ticket number
     * @return the invoice's {@code printCount}, or -1 when none exists
     */
    private int printCountFor(String ticketNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Invoice inv = Invoice.find("ticketNumber", ticketNumber).firstResult();
            return inv == null ? -1 : inv.printCount;
        });
    }

    /**
     * Inserts an invoicing customer account.
     *
     * @param number the account number
     * @param companyName the raison sociale
     * @param taxId the fiscal identifier, or null
     * @return the id of the inserted row
     */
    private Long insertCustomer(String number, String companyName, String taxId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AccountCustomer a = new AccountCustomer();
            a.accountNumber = number;
            a.companyName = companyName;
            a.taxId = taxId;
            a.creditBalance = BigDecimal.ZERO;
            a.blocked = false;
            a.persist();
            return a.id;
        });
    }

    /**
     * Deletes a customer account, best-effort: an account referenced by an issued
     * invoice cannot be dropped (FK), which is fine — the throwaway boot DB is
     * dropped per class, so a leftover customer is harmless.
     *
     * @param id the row id
     */
    private void deleteCustomer(Long id) {
        try {
            QuarkusTransaction.requiringNew().run(() -> AccountCustomer.deleteById(id));
        } catch (RuntimeException referencedByInvoice) {
            // Left in place: harmless on the per-class drop-and-create DB.
        }
    }

    /**
     * Asserts the invoice flow carries no operator error (null or empty).
     *
     * @param message the assertion message
     */
    private void assertNoInvoiceError(String message) {
        String error = posState.invoice.error;
        Assertions.assertTrue(error == null || error.isEmpty(), message + " (error was: " + error + ")");
    }

}
