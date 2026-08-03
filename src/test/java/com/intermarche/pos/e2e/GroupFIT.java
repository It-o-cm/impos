package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketPayment;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Group F end-to-end scenarios (Paiement &amp; clôture), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Group F is
 * {@code [S]} (caisse seule + simulateur) EXCEPT F7, tagged {@code [V]} in the
 * catalog — so F1–F6 and F8 are implemented here and F7 is listed as justified
 * residue (it belongs to the mirror-seeded valuation-engine campaign).
 * <p>
 * <b>The money is taken on the pay screen, exactly as the cashier acts.</b>
 * Each scenario builds its own cart on the sale screen (through the hardware
 * scan bus, {@code POST /api/pos/scan}, as in groups B/C), then walks onto the
 * payment overlay ({@code /pay}) and tenders through the on-screen method
 * buttons and the touch numpad ({@code #payNumpadZone}): cash (F1), a card on
 * the virtual terminal (F2/F3), payment vouchers (F4), cheque and meal tickets
 * (F5) and the solidarity round-up (F6). The virtual TPE is driven exactly as
 * the simulator drives it: the register parks the amount (through the screen),
 * then the terminal decides. That decision is the ONE gesture with no HTTP
 * surface under test — the register's inbound TPE endpoints
 * ({@code /api/hardware/tpe/accept} / {@code /refuse}) live on
 * {@code PosHardwareResource}, but under {@code @QuarkusTest} the embedded
 * {@code MockHardwareResource} owns the whole {@code /api/hardware} namespace and
 * implements no {@code /tpe}, so those sub-paths are shadowed (404). The
 * terminal's accept/refuse is therefore played at the service boundary the
 * endpoint merely wraps ({@link PaymentService#confirmPendingCard} /
 * {@link PaymentService#refusePendingCard}) — the same justified scaffolding
 * category as the injected-state and DB-aging oracles, the only step off the
 * HTTP surface. Parking, register cancel and completion all stay on the screen.
 * Completion is closed through the modal's own buttons —
 * {@code 🖨️ IMPRIMER TICKET} (prints the still-OPEN draft) and
 * {@code NOUVELLE VENTE} (the fiscal moment) — and F8 reads the closed rows
 * back from the database.
 * <p>
 * <b>The drawer is real.</b> Cash, cheque and meal-ticket payments pulse the
 * drawer open (something physical goes in); since the completion actions
 * ({@code /action/finish}, {@code /action/print}) are drawer-guarded, every
 * cash-like flow pushes the drawer shut ({@code POST /api/hardware/drawer/close})
 * before it finalizes or prints, else the guard diverts to {@code /drawer-error}.
 * Card, fidelity and voucher payments never open it.
 * <p>
 * <b>Oracles.</b> The reactive {@code /pay} overlay ({@code RESTE :}, the
 * completion modal {@code TRANSACTION TERMINÉE} / {@code Rendu Client}, the TPE
 * overlay, the voucher error zone) is asserted where the mutation touches the
 * poll version; the server singleton ({@link PosState#payment}) is the oracle
 * for the exact registered entries, the remaining due at each step and the
 * pending-card lifecycle; the database ({@link Ticket}, {@link TicketPayment})
 * is the oracle for the fiscal moment — status flip, sequential numbering,
 * SHA-256 signature chaining and the perpetual grand total. Since the remote
 * valuation engine at {@code :8090} is not up under test, the cart stays on
 * local/degraded totals (the sale is never blocked); amounts asserted are the
 * local catalog figures.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (f1…f8) and share ONE cash session opened
 * once by the first scenario on the fresh drop-and-create H2 boot. Each
 * scenario builds and finalizes its OWN sale; a logout ({@code /lock}) between
 * scenarios abandons the in-memory cart AND resets the whole payment state
 * ({@code PosState.clearTicket → payment.reset}), so no pending card, voucher
 * or completion leaks across scenarios. Ticket/session counters are perpetual,
 * so numbers are asserted by format and by delta, never as absolute values.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupFIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    // --- Catalog fixtures (DataInitializer seed) ---

    /** Single-price unit product, TTC exactly 6,00 € — the clean payment fixture. */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** Single-price unit product, TTC 0,96 € — the small-ticket fixture. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    // --- Voucher fixtures (seed CouponType formats) ---

    /** "Bon enseigne" (STORE_VOUCHER, ^50\d{12}$), amount = last 4 digits in cents: 1,50 €. */
    private static final String STORE_VOUCHER_150 = "50000000000150";

    /** Same type, encoding 50,00 € — larger than any ticket here, to prove the cap. */
    private static final String STORE_VOUCHER_5000 = "50000000005000";

    /** A number that does not match the "Bon enseigne" pattern (typing mistake). */
    private static final String STORE_VOUCHER_BAD = "12345";

    /** "Catalina" (CATALINA, ^0482\d{10}$), manual amount. */
    private static final String CATALINA_NUMBER = "04820000000000";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected to assert the invisible half of
     * several scenarios: the exact registered payment entries, the remaining
     * due at each step, the pending-card lifecycle and the transient TPE error.
     */
    @Inject
    PosState posState;

    /**
     * The payment orchestrator, injected to play the virtual terminal's
     * accept/refuse decision — the register's inbound TPE endpoints are shadowed
     * by the hardware simulator under test (see the class note), so the decision
     * is driven at the service boundary the endpoint wraps.
     */
    @Inject
    PaymentService paymentService;

    /**
     * F1 — Espèces exactes &amp; trop-perçu.
     * <p>
     * Exact cash completes the transaction with no change (the completion modal
     * shows no {@code Rendu Client}); an overpayment computes the change, shows
     * it on the modal and on the customer display, and pulses the drawer open.
     */
    @Test
    void f1_especes_exactes_et_trop_percu() {
        // --- Exact: 6,00 € tendered on a 6,00 € ticket -> complete, no change ---
        Page exact = freshSaleWithHuile();
        goPay(exact);
        payThroughScreen(exact, "ESPÈCES", "cashForm", "6", false);
        exact.getByText("TRANSACTION TERMINÉE").waitFor();
        Assertions.assertTrue(posState.payment.transactionComplete, "exact cash must complete the transaction");
        Assertions.assertEquals(0, posState.payment.lastChangeAmount.compareTo(BigDecimal.ZERO),
                "exact cash must leave no change");
        Assertions.assertEquals(0, exact.getByText("Rendu Client").count(),
                "the completion modal must show no change section on an exact payment");
        finishSale(exact);
        exact.close();

        // --- Overpaid: 10,00 € tendered on a 6,00 € ticket -> 4,00 € change ---
        Page over = freshSaleWithHuile();
        goPay(over);
        payThroughScreen(over, "ESPÈCES", "cashForm", "10", false);
        over.getByText("TRANSACTION TERMINÉE").waitFor();
        over.getByText("Rendu Client").waitFor();
        Assertions.assertTrue(over.locator(".change-amount").textContent().contains("4.00"),
                "the modal must display the 4,00 € change back");
        Assertions.assertEquals(0, posState.payment.lastChangeAmount.compareTo(new BigDecimal("4.00")),
                "the computed change must be 4,00 €");
        Assertions.assertEquals("OPEN", drawerStatus(),
                "a cash payment must pulse the drawer open (deposit + change)");
        finishSale(over);
        over.close();
    }

    /**
     * F2 — Multi-paiements (CB partielle + espèces solde).
     * <p>
     * A partial card payment on the virtual terminal (4,00 € of a 6,00 € ticket)
     * leaves 2,00 € due; the cash balance clears it. Two entries are registered
     * and the remaining due is exact at every step.
     */
    @Test
    void f2_multi_paiements_cb_partielle_puis_especes() {
        Page page = freshSaleWithHuile();
        goPay(page);
        // Card 4,00 €: parked on the virtual terminal, accepted by the simulator.
        payThroughScreen(page, "CARTE BANCAIRE", "cardForm", "4", true);
        page.getByText("PAIEMENT CARTE EN COURS").waitFor();
        Assertions.assertEquals(0, posState.payment.pendingCardAmount.compareTo(new BigDecimal("4.00")),
                "the parked card request must carry the 4,00 € partial amount");
        // The terminal accepts (the endpoint the simulator would hit is shadowed
        // under test — see the class note — so its wrapped service is called).
        paymentService.confirmPendingCard(posState);
        goPay(page);
        Assertions.assertEquals(1, posState.payment.payments.size(), "the accepted card must be one entry");
        Assertions.assertEquals("CARD", posState.payment.payments.get(0).method,
                "the first entry must be the card payment");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("2.00")),
                "6,00 € minus a 4,00 € card must leave 2,00 € due");
        // Cash 2,00 €: clears the balance.
        payThroughScreen(page, "ESPÈCES", "cashForm", "2", false);
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        Assertions.assertEquals(2, posState.payment.payments.size(), "two tenders must yield two entries");
        Assertions.assertEquals("CASH", posState.payment.payments.get(1).method,
                "the second entry must be the cash balance");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(BigDecimal.ZERO),
                "the balance must be fully settled");
        finishSale(page);
        page.close();
    }

    /**
     * F3 — TPE virtuel (accept / refuse / annulation caisse).
     * <p>
     * A card request parks the amount and raises the TPE overlay; the simulator
     * ACCEPT registers the payment; a fresh request REFUSED leaves the cart
     * intact and posts {@code PAIEMENT REFUSÉ PAR LE TPE}; a request CANCELLED
     * from the register withdraws the demand, so a late simulator accept is a
     * {@code 409}.
     */
    @Test
    void f3_tpe_virtuel_accept_refuse_annulation() {
        // --- Accept: the pending card is registered ---
        Page accept = freshSaleWithHuile();
        goPay(accept);
        payThroughScreen(accept, "CARTE BANCAIRE", "cardForm", null, false);
        accept.getByText("PAIEMENT CARTE EN COURS").waitFor();
        Assertions.assertNotNull(posState.payment.pendingCardAmount, "the card request must park an amount");
        paymentService.confirmPendingCard(posState);
        goPay(accept);
        Assertions.assertNull(posState.payment.pendingCardAmount, "the accept must clear the pending amount");
        Assertions.assertTrue(posState.payment.transactionComplete,
                "the accepted full card must complete the transaction");
        finishSale(accept);
        accept.close();

        // --- Refuse: the cart is untouched, the error is posted ---
        Page refuse = freshSaleWithHuile();
        goPay(refuse);
        payThroughScreen(refuse, "CARTE BANCAIRE", "cardForm", null, false);
        refuse.getByText("PAIEMENT CARTE EN COURS").waitFor();
        paymentService.refusePendingCard(posState);
        Assertions.assertNull(posState.payment.pendingCardAmount, "the refuse must clear the pending amount");
        Assertions.assertTrue(posState.payment.payments.isEmpty(),
                "a refused card must register no payment (cart intact)");
        Assertions.assertEquals("PAIEMENT REFUSÉ PAR LE TPE", posState.ticket.transientError,
                "a refused card must post the TPE refusal message");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("6.00")),
                "a refused card must leave the full amount due");
        refuse.close();

        // --- Register cancel: the demand is withdrawn, a late accept is inert ---
        Page cancel = freshSaleWithHuile();
        goPay(cancel);
        payThroughScreen(cancel, "CARTE BANCAIRE", "cardForm", null, false);
        cancel.getByText("PAIEMENT CARTE EN COURS").waitFor();
        cancel.locator("a[href='/action/card-cancel']").click();
        cancel.getByText("RESTE :").waitFor();
        Assertions.assertNull(posState.payment.pendingCardAmount,
                "cancelling from the register must withdraw the demand");
        // A late terminal accept finds no pending demand: the endpoint would answer
        // 409 and its wrapped service is a guarded no-op — nothing is replayed.
        paymentService.confirmPendingCard(posState);
        Assertions.assertTrue(posState.payment.payments.isEmpty(),
                "a withdrawn demand must not be replayable into a payment");
        cancel.close();
    }

    /**
     * F4 — Bons d'achat (encodé, Catalina, générique, motif inconnu, plafond).
     * <p>
     * An encoded voucher scanned during the payment registers the amount decoded
     * from its number; a Catalina scanned during the payment switches to manual
     * amount entry; a generic (numberless) voucher chosen on the panel takes an
     * amount directly; a number not matching the chosen type is refused with a
     * clean error and registers nothing; a voucher larger than the amount due is
     * capped at the remaining (vouchers never render change).
     * <p>
     * Encoded and Catalina vouchers are presented on the hardware scan bus
     * (their intended path: the {@code VoucherScanHandler} routes a scan onto the
     * payment while {@code paymentInProgress}), not typed — the payment numpad
     * collapses a leading zero, so a {@code 0482…} Catalina number cannot be
     * keyed digit by digit.
     */
    @Test
    void f4_bons_encode_catalina_generique_inconnu_plafond() {
        // --- Encoded "Bon enseigne" 1,50 €: amount decoded from the number ---
        Page encoded = freshSaleWithHuile();
        goPay(encoded);
        scan(STORE_VOUCHER_150);
        PaymentEntryView v1 = lastPayment();
        Assertions.assertTrue(v1.voucher, "an encoded voucher must be a voucher entry");
        Assertions.assertEquals("Bon enseigne", v1.method, "the entry must carry the voucher type label");
        Assertions.assertEquals(STORE_VOUCHER_150, v1.voucherNumber, "the entry must carry the voucher number");
        Assertions.assertEquals(0, v1.amount.compareTo(new BigDecimal("1.50")),
                "the amount must be decoded from the number (1,50 €)");
        encoded.close();

        // --- Catalina: scanned number then a manually typed amount (3,00 €) ---
        Page catalina = freshSaleWithHuile();
        goPay(catalina);
        scan(CATALINA_NUMBER);
        catalina.getByText("saisir le montant").waitFor();
        enterVoucherAmount(catalina, "3");
        catalina.getByText("RESTE :").waitFor();
        PaymentEntryView v2 = lastPayment();
        Assertions.assertEquals("Catalina", v2.method, "the Catalina entry must carry its label");
        Assertions.assertEquals(CATALINA_NUMBER, v2.voucherNumber, "the Catalina entry must carry its number");
        Assertions.assertEquals(0, v2.amount.compareTo(new BigDecimal("3.00")),
                "the manually typed Catalina amount must be 3,00 €");
        catalina.close();

        // --- Generic (numberless): an amount only (2,00 €) ---
        Page generic = freshSaleWithHuile();
        goPay(generic);
        selectVoucherType(generic, "GENERIC");
        generic.getByText("saisir le montant").waitFor();
        enterVoucherAmount(generic, "2");
        generic.getByText("RESTE :").waitFor();
        PaymentEntryView v3 = lastPayment();
        Assertions.assertEquals("Bon générique", v3.method, "the generic entry must carry its label");
        Assertions.assertTrue(v3.voucherNumber == null || v3.voucherNumber.isEmpty(),
                "a generic voucher carries no number");
        Assertions.assertEquals(0, v3.amount.compareTo(new BigDecimal("2.00")),
                "the manually typed generic amount must be 2,00 €");
        generic.close();

        // --- Unknown motif: a number not matching the chosen type -> clean error ---
        Page bad = freshSaleWithHuile();
        goPay(bad);
        selectVoucherType(bad, "STORE_VOUCHER");
        enterVoucherNumber(bad, STORE_VOUCHER_BAD);
        bad.getByText("Numéro non reconnu — vérifiez la saisie").waitFor();
        Assertions.assertTrue(posState.payment.payments.isEmpty(),
                "an unrecognized number must register no payment");
        bad.close();

        // --- Cap: a 50,00 € voucher on a 0,96 € ticket is capped at 0,96 € ---
        Page cap = freshSaleWithBaguette();
        goPay(cap);
        scan(STORE_VOUCHER_5000);
        cap.getByText("TRANSACTION TERMINÉE").waitFor();
        PaymentEntryView v4 = lastPayment();
        Assertions.assertEquals(0, v4.amount.compareTo(new BigDecimal("0.96")),
                "an overpaying voucher must be capped at the remaining due (0,96 €)");
        Assertions.assertTrue(posState.payment.transactionComplete,
                "the capped voucher must exactly settle the ticket");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(BigDecimal.ZERO),
                "a capped voucher leaves nothing due and renders no change");
        finishSale(cap);
        cap.close();
    }

    /**
     * F5 — Chèque et Ticket Restaurant.
     * <p>
     * A cheque and a meal-ticket payment both pulse the drawer open (they are
     * physically stored); neither is cash, so the drawer's theoretical cash is
     * untouched — proven on the closed ticket, whose only tenders are CHEQUE and
     * TR, with no CASH entry to raise the session's cash total.
     */
    @Test
    void f5_cheque_et_ticket_resto_tiroir_ouvert() {
        Page page = freshSaleWithHuile();
        goPay(page);
        // Cheque 3,00 € (partial): the cheque form prefills the full remaining.
        payThroughScreen(page, "CHÈQUE", "chequeForm", "3", true);
        page.getByText("RESTE :").waitFor();
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("3.00")),
                "a 3,00 € cheque must leave 3,00 € due");
        // The cheque pulsed the drawer open; the next payment POST is drawer-guarded,
        // so the cashier pushes the drawer shut before tendering the meal tickets.
        closeDrawer();
        // Meal tickets 3,00 € (balance): the TR form starts empty.
        payThroughScreen(page, "TICKET RESTO", "trForm", "3", false);
        page.getByText("TRANSACTION TERMINÉE").waitFor();
        Assertions.assertEquals("OPEN", drawerStatus(),
                "cheque and meal-ticket payments must pulse the drawer open (storage)");
        Long ticketId = posState.payment.ticketDbId;
        Assertions.assertNotNull(ticketId, "the settled sale must carry a persisted draft");
        finishSale(page);
        // The closed ticket's tenders are CHEQUE and TR only: no cash, so the
        // session's theoretical cash (float + cash payments) is unchanged.
        List<String> methods = paymentMethodsOf(ticketId);
        Assertions.assertEquals(2, methods.size(), "the closed ticket must carry the two tenders");
        Assertions.assertTrue(methods.contains("CHEQUE"), "the closed ticket must carry the cheque");
        Assertions.assertTrue(methods.contains("TR"), "the closed ticket must carry the meal ticket");
        Assertions.assertFalse(methods.contains("CASH"),
                "no cash tender means the theoretical cash stays unchanged");
        page.close();
    }

    /**
     * F6 — Arrondi solidaire (toggle / re-toggle).
     * <p>
     * The solidarity toggle adds a zero-VAT {@code ARRONDI SOLIDAIRE} donation
     * line that rounds the ticket up to the next whole euro (0,96 € → 1,00 €);
     * toggling again removes it and the total falls back to the exact figure.
     */
    @Test
    void f6_arrondi_solidaire_toggle() {
        Page page = freshSaleWithBaguette();
        goPay(page);
        // Toggle on: a 0,04 € donation line raises 0,96 € to 1,00 €.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ARRONDI SOLIDAIRE").setExact(true)).click();
        page.getByText("RESTE :").waitFor();
        Assertions.assertNotNull(posState.donationLineUid, "the toggle must add a donation line");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(new BigDecimal("1.00")),
                "the donation must round the total up to the next whole euro");
        TicketState.TicketItem donation = donationLine();
        Assertions.assertNotNull(donation, "the ARRONDI SOLIDAIRE line must be present");
        Assertions.assertEquals(0, donation.vatRate.compareTo(BigDecimal.ZERO),
                "the donation is collected out of VAT scope");
        Assertions.assertEquals(0, donation.getTotalPrice().compareTo(new BigDecimal("0.04")),
                "the donation must be the difference up to the whole euro (0,04 €)");
        // Toggle off: the donation line is removed, the total falls back.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("ARRONDI SOLIDAIRE").setExact(true)).click();
        page.getByText("RESTE :").waitFor();
        Assertions.assertNull(posState.donationLineUid, "re-toggling must remove the donation line");
        Assertions.assertNull(donationLine(), "the ARRONDI SOLIDAIRE line must be gone");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(new BigDecimal("0.96")),
                "removing the donation must restore the exact total");
        page.close();
    }

    /**
     * F8 — Après TERMINER (numérotation, chaîne de signatures, grand total, TVA).
     * <p>
     * Two consecutive closings prove the fiscal contract: the ticket number
     * advances by one with no gap, each signature is a 64-hex SHA-256 chained to
     * the previous one ({@code previousSignature == prior.signature}), the
     * perpetual grand total is incremented by the new ticket's tax-included
     * total, and the VAT ventilation is coherent (TTC = HT + TVA). The printed
     * ticket carries the settlement block, the VAT table and the total.
     */
    @Test
    void f8_apres_terminer_numerotation_signature_grand_total() {
        // --- Sale 1: settle, print the still-OPEN draft, then close it ---
        Page s1 = freshSaleWithHuile();
        goPay(s1);
        payThroughScreen(s1, "ESPÈCES", "cashForm", "6", false);
        s1.getByText("TRANSACTION TERMINÉE").waitFor();
        Long id1 = posState.payment.ticketDbId;
        Assertions.assertNotNull(id1, "the settled sale must carry a persisted draft");
        // The cash pulse opened the drawer; the print POST is drawer-guarded.
        clearPrinter();
        closeDrawer();
        s1.waitForResponse("**/action/print", () -> s1.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("IMPRIMER TICKET")).click());
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("REGLEMENT"), "the printed ticket must carry the settlement block");
        Assertions.assertTrue(paper.contains("TOTAL TTC"), "the printed ticket must carry the tax-included total");
        Assertions.assertTrue(paper.contains("Dont TVA"), "the printed ticket must carry the VAT line");
        // TERMINER: the still-OPEN draft prints, then the fiscal moment closes it.
        s1.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("NOUVELLE VENTE").setExact(true)).click();
        s1.getByText("TOTAL À PAYER").waitFor();
        s1.close();

        // --- Sale 2: settle and close ---
        Page s2 = freshSaleWithBaguette();
        goPay(s2);
        payThroughScreen(s2, "ESPÈCES", "cashForm", "1", false);
        s2.getByText("TRANSACTION TERMINÉE").waitFor();
        Long id2 = posState.payment.ticketDbId;
        Assertions.assertNotNull(id2, "the second settled sale must carry a persisted draft");
        finishSale(s2);
        s2.close();

        // --- Database oracle: the two closings form a coherent fiscal chain ---
        ClosedTicketView t1 = closedTicket(id1);
        ClosedTicketView t2 = closedTicket(id2);
        Assertions.assertEquals("CLOSED", t1.status, "the first ticket must be CLOSED by TERMINER");
        Assertions.assertEquals("CLOSED", t2.status, "the second ticket must be CLOSED by TERMINER");
        Assertions.assertTrue(t1.number.matches("C04-\\d{8}"), "the ticket number must be C04-<8 digits>");
        Assertions.assertTrue(t2.number.matches("C04-\\d{8}"), "the ticket number must be C04-<8 digits>");
        Assertions.assertEquals(sequenceOf(t1.number) + 1, sequenceOf(t2.number),
                "the ticket numbering must be sequential without a gap");
        Assertions.assertTrue(t1.signature.matches("[0-9a-f]{64}"), "the signature must be a 64-hex SHA-256");
        Assertions.assertTrue(t2.signature.matches("[0-9a-f]{64}"), "the signature must be a 64-hex SHA-256");
        Assertions.assertEquals(t1.signature, t2.previousSignature,
                "each signature must chain to the previous ticket's signature (hash n depends on n-1)");
        Assertions.assertEquals(0, t2.grandTotal.subtract(t1.grandTotal).compareTo(t2.totalIncludingTax),
                "the perpetual grand total must advance by the new ticket's tax-included total");
        Assertions.assertEquals(0, t2.totalIncludingTax.compareTo(t2.totalExcludingTax.add(t2.totalVat)),
                "the VAT ventilation must be coherent (TTC = HT + TVA)");
    }

    // --- Reusable gestures (login recipe, sale build, payment screen) ---

    /**
     * Opens a fresh page, logs the cashier onto the empty sale of the shared
     * open session and scans the 6,00 € oil — the standard clean-ticket start.
     *
     * @return a Playwright page on the sale screen with a 6,00 € cart
     */
    private Page freshSaleWithHuile() {
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        return page;
    }

    /**
     * Opens a fresh page, logs the cashier onto the empty sale of the shared
     * open session and scans the 0,96 € baguette — the small-ticket start.
     *
     * @return a Playwright page on the sale screen with a 0,96 € cart
     */
    private Page freshSaleWithBaguette() {
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        return page;
    }

    /**
     * Opens a browser page and lands it, logged in, on an empty sale screen of
     * the shared open session — the standard start of every scenario.
     *
     * @return a Playwright page sitting on the empty sale screen
     */
    private Page freshSale() {
        // Neutralize any page left polling by a previous scenario: a lingering
        // /lock-data poller would steal this scenario's badge from the one-shot
        // mailbox (the group-A page-close lesson). A logout also resets the
        // whole payment state, so no pending card/voucher leaks across scenarios.
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
     * Tenders through the on-screen payment method: taps the method button (which
     * reveals its inline form), optionally clears the prefilled amount, taps the
     * amount digits on the payment numpad and submits the form.
     *
     * @param page the Playwright page carrying the payment overlay
     * @param methodButton the exact visible method-button text (e.g. "ESPÈCES")
     * @param formId the id of the inline form to submit (e.g. "cashForm")
     * @param digits the amount digits to tap, or null to keep the prefilled amount
     * @param clearFirst true to clear the prefilled amount before typing
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
     * Opens the voucher type chooser and selects a type by its technical code
     * (the hidden {@code code} field of its {@code voucher-select} form).
     *
     * @param page the Playwright page carrying the payment overlay
     * @param code the coupon type code (e.g. "STORE_VOUCHER", "CATALINA", "GENERIC")
     */
    private void selectVoucherType(Page page, String code) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("BONS D'ACHAT").setExact(true)).click();
        page.locator("form:has(input[name='code'][value='" + code + "']) button[type=submit]").click();
    }

    /**
     * Types a voucher number on the payment numpad and validates it.
     *
     * @param page the Playwright page carrying the voucher number form
     * @param number the voucher number digits to tap
     */
    private void enterVoucherNumber(Page page, String number) {
        page.locator("#voucherNumberForm").waitFor();
        tapDigits(page.locator("#payNumpadZone"), number);
        page.locator("#voucherNumberForm button[type=submit]").click();
    }

    /**
     * Types a manual voucher amount on the payment numpad and validates it.
     *
     * @param page the Playwright page carrying the voucher amount form
     * @param digits the amount digits to tap
     */
    private void enterVoucherAmount(Page page, String digits) {
        page.locator("#voucherAmountForm").waitFor();
        tapDigits(page.locator("#payNumpadZone"), digits);
        page.locator("#voucherAmountForm button[type=submit]").click();
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

    // --- Server-state / database oracles ---

    /**
     * Returns the last registered payment entry as a plain snapshot (read off
     * the server singleton).
     *
     * @return a snapshot of the last payment entry
     */
    private PaymentEntryView lastPayment() {
        List<com.intermarche.pos.ui.payment.PaymentState.PaymentEntry> ps = posState.payment.payments;
        com.intermarche.pos.ui.payment.PaymentState.PaymentEntry e = ps.get(ps.size() - 1);
        return new PaymentEntryView(e.method, e.amount, e.voucherNumber, e.voucher);
    }

    /**
     * Returns the current donation ({@code ARRONDI SOLIDAIRE}) line, or null.
     *
     * @return the donation ticket item, or null when none is present
     */
    private TicketState.TicketItem donationLine() {
        for (TicketState.TicketItem item : posState.ticket.items) {
            if ("ARRONDI SOLIDAIRE".equals(item.label)) return item;
        }
        return null;
    }

    /**
     * Reads the method keys of a closed ticket's payments from the database.
     *
     * @param ticketId the database id of the ticket
     * @return the payment method keys (e.g. CHEQUE, TR), in registration order
     */
    private List<String> paymentMethodsOf(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket ticket = Ticket.findById(ticketId);
            return ticket.payments.stream().map(TicketPayment::getMethodKey).collect(Collectors.toList());
        });
    }

    /**
     * Reads the fiscal fields of a closed ticket from the database into a plain
     * snapshot (so no lazy field is touched outside the transaction).
     *
     * @param ticketId the database id of the ticket
     * @return a snapshot of the ticket's fiscal fields
     */
    private ClosedTicketView closedTicket(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return new ClosedTicketView(t.status.name(), t.ticketNumber, t.signature, t.previousSignature,
                    t.grandTotal, t.totalIncludingTax, t.totalExcludingTax, t.totalVat);
        });
    }

    /**
     * Extracts the numeric sequence of a ticket number ("C04-00000123" -> 123).
     *
     * @param ticketNumber the register ticket number
     * @return the 8-digit sequence as an int
     */
    private int sequenceOf(String ticketNumber) {
        return Integer.parseInt(ticketNumber.substring(ticketNumber.indexOf('-') + 1));
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
     * Immutable snapshot of a registered payment entry, read off the server
     * singleton so assertions never hold a live reference.
     */
    private static final class PaymentEntryView {
        /** The payment method key, or the voucher type label for a voucher. */
        final String method;
        /** The applied amount. */
        final BigDecimal amount;
        /** The voucher number, or null. */
        final String voucherNumber;
        /** True when the entry is a voucher payment. */
        final boolean voucher;

        /**
         * Creates a payment entry snapshot.
         *
         * @param method the method key or voucher label
         * @param amount the applied amount
         * @param voucherNumber the voucher number, or null
         * @param voucher true when a voucher payment
         */
        PaymentEntryView(String method, BigDecimal amount, String voucherNumber, boolean voucher) {
            this.method = method;
            this.amount = amount;
            this.voucherNumber = voucherNumber;
            this.voucher = voucher;
        }
    }

    /**
     * Immutable snapshot of a closed ticket's fiscal fields.
     */
    private static final class ClosedTicketView {
        /** The lifecycle status name. */
        final String status;
        /** The register ticket number. */
        final String number;
        /** The SHA-256 signature. */
        final String signature;
        /** The previous ticket's signature (or "GENESIS"). */
        final String previousSignature;
        /** The perpetual grand total snapshot. */
        final BigDecimal grandTotal;
        /** The tax-included total. */
        final BigDecimal totalIncludingTax;
        /** The tax-excluded total. */
        final BigDecimal totalExcludingTax;
        /** The total VAT. */
        final BigDecimal totalVat;

        /**
         * Creates a closed-ticket snapshot.
         *
         * @param status the status name
         * @param number the ticket number
         * @param signature the signature
         * @param previousSignature the previous signature
         * @param grandTotal the grand total
         * @param totalIncludingTax the tax-included total
         * @param totalExcludingTax the tax-excluded total
         * @param totalVat the total VAT
         */
        ClosedTicketView(String status, String number, String signature, String previousSignature,
                         BigDecimal grandTotal, BigDecimal totalIncludingTax,
                         BigDecimal totalExcludingTax, BigDecimal totalVat) {
            this.status = status;
            this.number = number;
            this.signature = signature;
            this.previousSignature = previousSignature;
            this.grandTotal = grandTotal;
            this.totalIncludingTax = totalIncludingTax;
            this.totalExcludingTax = totalExcludingTax;
            this.totalVat = totalVat;
        }
    }
}
