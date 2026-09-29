package com.intermarche.e2e;

import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.domain.payment.TenderDefinition;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sync.FidEvent;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Group H end-to-end scenarios (Retours — customer refunds), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Group H is
 * tagged {@code [S]} (caisse seule + simulateur); every scenario H1–H5 is
 * implemented here.
 * <p>
 * <b>The refund chain.</b> A refund is a THREE-step screen dance riding on the
 * shared manager-endorsement modal. The cashier searches a closed ticket
 * ({@code /return/search}, the search form), opens it by its rendered result
 * row ({@code /return/select/{id}}), dials the returned quantities on the line
 * ({@code /return/select-line/{lineId}} to open the line's controls, then the
 * {@code +}/{@code -} steppers) or types a free amount ({@code /return/edit-amount}
 * + the numpad committed by a method button), and finally picks a refund method
 * ({@code /return/pay/cash|card|voucher|loyalty}) — which PARKS a
 * {@code REFUND_<METHOD>_<ticketId>} endorsement (nothing is created yet). A
 * manager then endorses over the cashier's shoulder exactly as in group C: the
 * badge is presented on the hardware bus ({@code POST /api/pos/scan}), the
 * 500&nbsp;ms {@code /endorsement-data} poll flips the modal to PIN entry, the
 * PIN is tapped on {@code #endorseKeyboardArea} and {@code SUIVANT} executes the
 * parked refund transactionally.
 * <p>
 * <b>The two-layer guard.</b> The refund guards run TWICE on purpose — once at
 * staging (the per-line stepper CAPS the quantity at what is still refundable,
 * so a double refund is impossible to even dial: H2) and again INSIDE the
 * creation transaction (the authoritative re-check). The transaction re-check is
 * reachable from the screen for the ticket-level cap because the free amount is
 * NOT capped at staging: keying an amount above the ticket total sails through
 * staging and is rolled back in the transaction with {@code PLAFOND DU TICKET
 * DÉPASSÉ} (H3). The per-line transaction re-check needs a second refund
 * intercalated BETWEEN one refund's staging and its execution; the register is a
 * SINGLE server-side {@link PosState} (one operator, one staging area at a time),
 * so that concurrent interleave cannot be produced through the screen — it is
 * listed as justified residue below.
 * <p>
 * <b>Method side effects.</b> A CASH refund opens the drawer and lowers the
 * session's theoretical cash (H1); a VOUCHER refund prints a store voucher whose
 * number matches the {@code STORE_VOUCHER} pattern ({@code 50} + 8-digit serial
 * + 4-digit cents) and is therefore scannable as a payment on a later sale
 * (H4); a refund is a REAL fiscal document and is blocked entirely in training
 * mode (H5). Every created refund journals a {@code REFUND_CREATED} event and
 * restitutes the VAT of the refunded lines.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (h1…h5) and share ONE cash session opened
 * once by {@link #ensureOpenSession()} on the fresh drop-and-create H2 boot;
 * each scenario builds its OWN closed ticket(s) to refund and its own cart,
 * and each restores whatever it alters (H5 toggles training back off).
 * <p>
 * <b>Oracles.</b> The rendered HTML carries the exact user-facing texts (the
 * search results, the refund detail, the guard messages); the database
 * ({@link Refund}) is the oracle for the created document (method, totals, VAT
 * restitution, session attachment); {@link CashSessionService#buildReport} is
 * the oracle for the theoretical drawer decrease; the append-only journal
 * ({@link TechnicalEvent}) is the oracle for {@code REFUND_CREATED}; the printer
 * simulator captures the printed store voucher, whose encoded number is then fed
 * back through the scan bus to prove the loop closes; the server singleton
 * ({@link PosState}) is the oracle for the staging cap, the in-memory refund
 * state and the training block.
 * <p>
 * <b>Justified residue.</b> The per-line transaction re-check race of H2 (a
 * second refund intercalated between staging and execution) is unreachable
 * through the screen with a single-operator register (one shared {@link PosState}
 * staging area) and is not forced through DB scaffolding, which the campaign
 * limits to timestamp aging; the ticket-level transaction re-check with full
 * rollback IS proven from the screen in H3.
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupHIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    // --- Catalog fixtures (DataInitializer seed) ---

    /** Single-price unit product, TTC 6,00 € at 20 % VAT — the refund fixture. */
    private static final String EAN_HUILE = "3300000000006";

    /** The uppercased line label of {@link #EAN_HUILE} on a ticket. */
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** A valid fidelity card ({@code ^299\d{10}$}) attached to the H6 sale. */
    private static final String FIDELITY_CARD = "2990000000033";

    /** A gift-card money product, EAN, 25,00 € — the H7 non-returnable line. */
    private static final String EAN_GIFT25 = "3400025000001";

    /** The store-voucher number format printed by a voucher refund. */
    private static final Pattern STORE_VOUCHER = Pattern.compile("50\\d{12}");

    /** A registry credit-note number: the 297 prefix plus the row id. */
    private static final Pattern CREDIT_NOTE = Pattern.compile("297\\d{12}");

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected as the oracle for the staging
     * refund quantities, the in-memory refund state, the training flag and the
     * voucher payment registered on encashment.
     */
    @Inject
    PosState posState;

    /**
     * The cash session service, injected as the oracle for the theoretical
     * drawer amount (float + cash payments - cash refunds).
     */
    @Inject
    CashSessionService cashSessionService;

    /**
     * H1 — Retour nominal espèces.
     * <p>
     * A closed cash ticket is searched, opened, its single line fully returned
     * and refunded in CASH under a manager endorsement: a {@link Refund} is
     * created (CASH, CLOSED, attached to the ticket), the VAT of the refunded
     * line is restituted (tax-excluded and VAT totals set), the session's
     * theoretical cash drops by the refunded amount and a
     * {@code REFUND_CREATED} event is journaled.
     */
    @Test
    void h1_retour_nominal_especes() {
        // --- Build the closed cash ticket to refund (6,00 € TTC, 20 % VAT) ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        // --- Baselines: theoretical cash and the journal count BEFORE the refund ---
        BigDecimal theoBefore = theoreticalCash();
        long createdBefore = eventCount(TechnicalEvent.EventType.REFUND_CREATED);
        // --- Search the ticket, open it, return its full line ---
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        Assertions.assertEquals(0, posState.refund.getReturnQty(lineId).compareTo(BigDecimal.ONE),
                "the stepper must stage a return quantity of one on the single-unit line");
        Assertions.assertEquals(0, posState.refund.getTotalRefundAmount().compareTo(new BigDecimal("6.00")),
                "the staged refund total must be the line's 6,00 €");
        // --- Choose CASH, endorse it: the refund is created transactionally ---
        page.navigate(base.toString() + "return/pay/cash");
        approveEndorsementWithManager(page);
        // --- The created refund: method, totals, VAT restitution ---
        List<RefundSnap> refunds = refundsFor(ticketId);
        Assertions.assertEquals(1, refunds.size(), "a single refund must be created for the ticket");
        RefundSnap r = refunds.get(0);
        Assertions.assertEquals("CASH", r.method, "the refund method must be CASH");
        Assertions.assertEquals("CLOSED", r.status, "the refund must be created closed");
        Assertions.assertEquals(0, r.totalAmount.compareTo(new BigDecimal("6.00")),
                "the refunded total must be 6,00 €");
        Assertions.assertNotNull(r.totalExcludingTax, "a line refund must carry its tax-excluded total");
        Assertions.assertNotNull(r.totalVat, "a line refund must restitute the VAT");
        Assertions.assertEquals(0, r.totalVat.compareTo(new BigDecimal("1.0000")),
                "20 % of the 5,00 € HT must restitute 1,00 € of VAT, was " + r.totalVat);
        Assertions.assertEquals(0, r.totalExcludingTax.compareTo(new BigDecimal("5.0000")),
                "the 6,00 € TTC line must break down to 5,00 € HT, was " + r.totalExcludingTax);
        // --- The journal and the theoretical drawer ---
        Assertions.assertEquals(createdBefore + 1, eventCount(TechnicalEvent.EventType.REFUND_CREATED),
                "the cash refund must journal exactly one REFUND_CREATED");
        Assertions.assertEquals(0, theoreticalCash().compareTo(theoBefore.subtract(new BigDecimal("6.00"))),
                "the cash refund must lower the theoretical drawer by 6,00 €");
        closeDrawer();
        page.close();
    }

    /**
     * H2 — Anti-double remboursement.
     * <p>
     * A two-unit line fully refunded once cannot be refunded again: the per-line
     * stepper CAPS the return quantity at the remaining refundable (zero), so a
     * second refund cannot even be dialled, and requesting a method on a
     * zero-total staging is refused with {@code RIEN À REMBOURSER}. No second
     * refund is created. (The authoritative transaction re-check of this same
     * per-line cap needs a concurrent second staging the single-operator
     * register cannot host — see the class residue note; the transaction
     * re-check with rollback is proven for the ticket cap in H3.)
     */
    @Test
    void h2_anti_double_remboursement() {
        // --- Build a two-unit closed ticket (12,00 € TTC) ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "20", EAN_HUILE, EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        Assertions.assertEquals(0, lineQuantity(ticketId, lineId).compareTo(new BigDecimal("2")),
                "two scans of the same EAN must have merged into a quantity-2 line");
        // --- First refund: the whole line (quantity 2), endorsed in cash ---
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        addReturnQty(page, lineId);
        Assertions.assertEquals(0, posState.refund.getReturnQty(lineId).compareTo(new BigDecimal("2")),
                "the stepper must stage the full quantity of two");
        page.navigate(base.toString() + "return/pay/cash");
        approveEndorsementWithManager(page);
        Assertions.assertEquals(1, refundsFor(ticketId).size(), "the first full refund must be created");
        closeDrawer();
        page.close();
        // --- Second attempt: the stepper caps at the exhausted remainder (zero) ---
        Page again = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(again, ticketId);
        selectReturnLine(again, lineId);
        addReturnQty(again, lineId);
        Assertions.assertEquals(0, posState.refund.getReturnQty(lineId).compareTo(BigDecimal.ZERO),
                "the stepper must cap the already-exhausted line at zero (staging double-refund guard)");
        // --- Requesting a method on a zero staging is refused, no refund created ---
        again.navigate(base.toString() + "return/pay/cash");
        again.getByText("RIEN À REMBOURSER").waitFor();
        Assertions.assertFalse(posState.endorsement.active,
                "a zero-total refund request must not open an endorsement");
        Assertions.assertEquals(1, refundsFor(ticketId).size(),
                "no second refund must be created against the exhausted ticket");
        again.close();
    }

    /**
     * H3 — Plafond ticket avec montant libre.
     * <p>
     * A keyed free amount is NOT capped at staging, so an amount above the
     * ticket total sails through the staging and reaches the creation
     * transaction, where the ticket-level cap refuses it with {@code PLAFOND DU
     * TICKET DÉPASSÉ} and rolls the whole creation back — proving the guard
     * re-verifies in transaction. No refund is created.
     */
    @Test
    void h3_plafond_ticket_montant_libre() {
        // --- Build a 6,00 € closed ticket ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        // --- Open it, switch to free-amount entry and key 10,00 € (> 6,00 €) ---
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        page.navigate(base.toString() + "return/edit-amount");
        page.locator("#mainInput").waitFor();
        tapDigits(page.locator("#numpadContainer"), "10");
        // The method buttons commit the keyed amount by submitting the staging
        // form first (their onclick), so ESPÈCES here only stages the amount.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        page.getByText("Montant Retour").waitFor();
        Assertions.assertEquals(0, posState.refund.getTotalRefundAmount().compareTo(new BigDecimal("10")),
                "the free amount must stage uncapped above the ticket total");
        // --- Request the method and endorse: the transaction rolls it back ---
        page.navigate(base.toString() + "return/pay/cash");
        approveEndorsementWithManager(page);
        // --- The refusal surfaces on the refund screen, nothing was created ---
        page.navigate(base.toString() + "return");
        page.getByText("PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 0.00 €)").waitFor();
        Assertions.assertTrue(refundsFor(ticketId).isEmpty(),
                "the ticket-cap refusal must roll the creation back — no refund persisted");
        page.close();
    }

    /**
     * H4 — Bon de remboursement.
     * <p>
     * A VOUCHER refund prints a store voucher whose number matches the
     * {@code STORE_VOUCHER} pattern ({@code 50} + serial + cents, ≤ 99,99 €).
     * That printed number, scanned during a later sale's payment, is recognized
     * as a store voucher and registered as a payment for its encoded amount —
     * the refund loop closes end to end.
     */
    @Test
    void h4_bon_de_remboursement() {
        // --- Build a 6,00 € ticket and refund it as a store voucher ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        clearPrinter();
        page.navigate(base.toString() + "return/pay/voucher");
        approveEndorsementWithManager(page);
        List<RefundSnap> refunds = refundsFor(ticketId);
        Assertions.assertEquals(1, refunds.size(), "the voucher refund must be created");
        Assertions.assertEquals("VOUCHER", refunds.get(0).method, "the refund method must be VOUCHER");
        page.close();
        // --- The printed credit note: a REGISTRY number, not an encoded one ---
        // The refund no longer prints a 50-prefixed voucher carrying its own
        // amount: it issues an AVOIR in the stored-value registry, whose
        // number is a pure identifier (297 + the row id) and whose balance
        // lives server-side. The 99,99 € cap of the encoded format is gone
        // with it — the registry holds any amount.
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("AVOIR"),
                "the printed credit note must be titled AVOIR, paper was:\n" + paper);
        Assertions.assertTrue(paper.contains("solde au registre"),
                "the credit note must point at the registry, paper was:\n" + paper);
        Matcher m = CREDIT_NOTE.matcher(paper);
        Assertions.assertTrue(m.find(),
                "the credit note must carry a 297-prefixed registry number, paper was:\n" + paper);
        String voucherNumber = m.group();
        // --- Encash it on a later sale: scanned during payment, it pays 6,00 € ---
        Page next = freshSale();
        scan(EAN_HUILE);
        next.getByText(LABEL_HUILE).waitFor();
        scan(EAN_HUILE);
        goPay(next);
        scan(voucherNumber);
        PaymentState.PaymentEntry voucher = posState.payment.payments.stream()
                .filter(PaymentState.PaymentEntry::isVoucher).findFirst().orElse(null);
        Assertions.assertNotNull(voucher, "scanning the store voucher during payment must register a voucher payment");
        Assertions.assertEquals(0, voucher.amount.compareTo(new BigDecimal("6.00")),
                "the credit note must pay the balance the REGISTRY holds, not a printed figure");
        Assertions.assertEquals("Avoir", voucher.method,
                "the payment must carry the credit-note type label");
        Assertions.assertEquals(0, posState.getRemaining().compareTo(new BigDecimal("6.00")),
                "the 6,00 € voucher must leave 6,00 € due on the 12,00 € sale");
        next.close();
    }

    /**
     * H5 — Retour en formation.
     * <p>
     * A refund is a real fiscal document, so it is blocked in training mode: with
     * training toggled on, opening a closed ticket and requesting any method is
     * refused with {@code RETOURS INDISPONIBLES EN FORMATION}, no endorsement is
     * opened and no refund is created. The scenario toggles training back off to
     * leave the register clean for the isolation contract.
     */
    @Test
    void h5_retour_en_formation_bloque() {
        // --- Build a real closed ticket BEFORE entering training ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        // --- Enter training mode (endorsed toggle) ---
        Page page = freshSale();
        toggleTraining(page);
        Assertions.assertTrue(posState.trainingMode, "the endorsed toggle must enter training mode");
        // --- Try to refund: blocked, no endorsement, no document ---
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        page.navigate(base.toString() + "return/pay/cash");
        page.getByText("RETOURS INDISPONIBLES EN FORMATION").waitFor();
        Assertions.assertEquals("RETOURS INDISPONIBLES EN FORMATION", posState.refund.errorMessage,
                "a refund in training must be refused with the training message");
        Assertions.assertFalse(posState.endorsement.active,
                "a refund blocked in training must not open an endorsement");
        Assertions.assertTrue(refundsFor(ticketId).isEmpty(),
                "no refund document must be created in training mode");
        // --- Restore: leave training so the register ends clean ---
        toggleTraining(page);
        Assertions.assertFalse(posState.trainingMode, "training must be toggled back off");
        page.close();
    }

    /**
     * H6 — Remboursement en cagnotte (fidélité).
     * <p>
     * A LOYALTY refund credits the customer's loyalty balance: on a closed ticket
     * that CARRIES a fidelity card, the CAGNOTTE method shows {@code CREDIT
     * FIDELITE <x> E} on the customer display, prints a credit receipt
     * ({@code REMBOURSEMENT EN CAGNOTTE}) and posts a {@code TICKET_RETURN}
     * fidelity event to the outbox — the refund is created as {@code LOYALTY}. On
     * a closed ticket WITHOUT a fidelity card the same method is refused INSIDE
     * the creation transaction with {@code AUCUNE CARTE FIDÉLITÉ SUR LE TICKET
     * D'ORIGINE}: the transaction commits nothing (rollback), so no refund is
     * persisted.
     */
    @Test
    void h6_remboursement_en_cagnotte() {
        // --- Case 1: original ticket WITH a fidelity card → loyalty credit ---
        Page sale = freshSale();
        Long withCard = closeCashSaleWithCardOn(sale, "10", FIDELITY_CARD, EAN_HUILE);
        sale.close();
        Assertions.assertEquals(FIDELITY_CARD, fidelityCardOf(withCard),
                "the built ticket must carry the scanned fidelity card (H6 precondition)");
        String numberWith = ticketNumber(withCard);
        Long lineWith = firstLineId(withCard);
        long fidBefore = fidEventCount(FidEvent.EventType.TICKET_RETURN);
        Page page = freshSale();
        assertTicketFoundBySearch(numberWith);
        openReturnDetail(page, withCard);
        selectReturnLine(page, lineWith);
        addReturnQty(page, lineWith);
        clearPrinter();
        page.navigate(base.toString() + "return/pay/loyalty");
        // Approve WITHOUT navigating away, so the customer display still carries
        // the credit message when it is read (a later sale-screen poll would
        // overwrite the customer display).
        approveEndorsementNoNav(page);
        // The customer display carries the raw-scale amount (4 decimals), unlike
        // the 2-decimal printed receipt below — assert the display's exact form.
        Assertions.assertTrue(displayContent().contains("CREDIT FIDELITE 6,0000 E"),
                "the loyalty refund must show the credit on the customer display, was: " + displayContent());
        List<RefundSnap> refunds = refundsFor(withCard);
        Assertions.assertEquals(1, refunds.size(), "the loyalty refund must be created");
        Assertions.assertEquals("LOYALTY", refunds.get(0).method, "the refund method must be LOYALTY");
        Assertions.assertEquals(0, refunds.get(0).totalAmount.compareTo(new BigDecimal("6.00")),
                "the loyalty refund must credit the 6,00 € line");
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("REMBOURSEMENT EN CAGNOTTE"),
                "a loyalty refund must print the credit receipt, paper was:\n" + paper);
        Assertions.assertTrue(paper.contains("CREDIT CARTE") && paper.contains("+6,00 E"),
                "the credit receipt must carry the +6,00 € credit line, paper was:\n" + paper);
        Assertions.assertEquals(fidBefore + 1, fidEventCount(FidEvent.EventType.TICKET_RETURN),
                "the loyalty refund must post exactly one TICKET_RETURN fidelity event");
        closeDrawer();
        page.close();
        // --- Case 2: original ticket WITHOUT a fidelity card → transaction refusal ---
        Page sale2 = freshSale();
        Long noCard = closeCashSaleOn(sale2, "10", EAN_HUILE);
        sale2.close();
        Assertions.assertNull(fidelityCardOf(noCard),
                "the control ticket must carry NO fidelity card");
        String numberNo = ticketNumber(noCard);
        Long lineNo = firstLineId(noCard);
        Page page2 = freshSale();
        assertTicketFoundBySearch(numberNo);
        openReturnDetail(page2, noCard);
        selectReturnLine(page2, lineNo);
        addReturnQty(page2, lineNo);
        page2.navigate(base.toString() + "return/pay/loyalty");
        // The card-presence guard is inside the creation transaction, so the
        // endorsement IS opened and approved; the transaction then refuses.
        approveEndorsementWithManager(page2);
        page2.navigate(base.toString() + "return");
        page2.getByText("AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE").waitFor();
        Assertions.assertEquals("AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE", posState.refund.errorMessage,
                "a loyalty refund on a card-less ticket must be refused in transaction");
        Assertions.assertTrue(refundsFor(noCard).isEmpty(),
                "the in-transaction refusal must roll back — no refund persisted");
        page2.close();
    }

    /**
     * H7 — Retour interdit sur carte cadeau.
     * <p>
     * A gift-card (money product) line cannot be refunded: selecting it and
     * dialling a return quantity is refused at STAGING with {@code RETOUR
     * INTERDIT SUR CARTE CADEAU}, the staged quantity stays zero, no endorsement
     * is opened and no refund is created.
     */
    @Test
    void h7_retour_interdit_carte_cadeau() {
        // --- Build a closed ticket whose only line is a gift card (25,00 €) ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "30", EAN_GIFT25);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        // --- Open it, select the gift-card line and try to dial a quantity ---
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetailNoLabel(page, ticketId);
        selectReturnLine(page, lineId);
        addReturnQty(page, lineId);
        // --- Staging refusal: message set, quantity zero, no endorsement/refund ---
        page.getByText("RETOUR INTERDIT SUR CARTE CADEAU").waitFor();
        Assertions.assertEquals("RETOUR INTERDIT SUR CARTE CADEAU", posState.refund.errorMessage,
                "a gift-card line must be refused for refund at staging");
        Assertions.assertEquals(0, posState.refund.getReturnQty(lineId).compareTo(BigDecimal.ZERO),
                "the gift-card line must stage no returnable quantity");
        Assertions.assertFalse(posState.endorsement.active,
                "a staging refusal must not open an endorsement");
        Assertions.assertTrue(refundsFor(ticketId).isEmpty(),
                "no refund must be created on a gift-card line");
        page.close();
    }

    /**
     * H8 — Méthode non autorisée au remboursement &amp; rien à rembourser.
     * <p>
     * Two guards of {@code RefundService.requestRefund}, exercised on a closed
     * ticket: requesting any method on a ZERO staging is refused with {@code RIEN
     * À REMBOURSER}; and a refund method whose administered tender forbids refunds
     * ({@code refundAllowed = false}) is refused with {@code MOYEN NON AUTORISE AU
     * REMBOURSEMENT}. Neither opens an endorsement nor creates a refund. The
     * administered {@code CARD} tender row is removed afterwards to leave the
     * shared referential clean.
     */
    @Test
    void h8_methode_non_autorisee_et_rien_a_rembourser() {
        // --- Build a plain 6,00 € closed ticket ---
        Page sale = freshSale();
        Long ticketId = closeCashSaleOn(sale, "10", EAN_HUILE);
        sale.close();
        String number = ticketNumber(ticketId);
        Long lineId = firstLineId(ticketId);
        // --- Guard 1: request a method on a zero staging → RIEN À REMBOURSER ---
        Page a = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(a, ticketId);
        a.navigate(base.toString() + "return/pay/cash");
        a.getByText("RIEN À REMBOURSER").waitFor();
        Assertions.assertFalse(posState.endorsement.active,
                "a zero-total refund request must not open an endorsement");
        Assertions.assertTrue(refundsFor(ticketId).isEmpty(),
                "a zero staging must create no refund");
        a.close();
        // --- Guard 2: administer CARD as refund-forbidden and request it ---
        forbidCardRefund();
        try {
            Page b = freshSale();
            assertTicketFoundBySearch(number);
            openReturnDetail(b, ticketId);
            selectReturnLine(b, lineId);
            addReturnQty(b, lineId);
            b.navigate(base.toString() + "return/pay/card");
            b.getByText("MOYEN NON AUTORISE AU REMBOURSEMENT").waitFor();
            Assertions.assertEquals("MOYEN NON AUTORISE AU REMBOURSEMENT", posState.refund.errorMessage,
                    "a refund-forbidden tender must be refused before any endorsement");
            Assertions.assertFalse(posState.endorsement.active,
                    "a forbidden refund method must not open an endorsement");
            Assertions.assertTrue(refundsFor(ticketId).isEmpty(),
                    "a forbidden refund method must create no refund");
            b.close();
        } finally {
            // Restore the referential: drop the administered CARD tender row.
            allowAllRefunds();
        }
    }

    /**
     * H9 — Bon d'avoir (VOUCHER), registre sans plafond.
     * <p>
     * A VOUCHER refund now issues a stored-value {@code CREDIT_NOTE} of ANY amount
     * — the historical 99,99 € encoded-format cap is lifted. A ticket well above
     * that cap (102,00 €) refunded as a voucher prints an {@code AVOIR} bearing a
     * {@code 297}-prefixed registry number, and the registry holds a note whose
     * initial amount and balance are the full 102,00 € (consultable — cross F19).
     * The ticket-search guards are also proven: a pattern under three characters
     * yields {@code Saisir au moins 3 caractères}, and a three-plus pattern with
     * no match yields {@code Aucun ticket trouvé}.
     */
    @Test
    void h9_bon_avoir_registre_sans_plafond() {
        // --- Search guards (no ticket needed) ---
        APIResponse tooShort = context.request().post(base.toString() + "return/search",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData("rawValue=ab"));
        Assertions.assertTrue(tooShort.text().contains("Saisir au moins 3 caractères"),
                "a search pattern under three characters must be refused with the floor message");
        APIResponse noHit = context.request().post(base.toString() + "return/search",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData("rawValue=ZZ999X"));
        Assertions.assertTrue(noHit.text().contains("Aucun ticket trouvé"),
                "a three-plus pattern with no match must render the empty-result message");
        // --- Build a 102,00 € ticket (17 × 6,00 €), above the old 99,99 € cap ---
        Page sale = freshSale();
        for (int i = 0; i < 17; i++) scan(EAN_HUILE);
        goPay(sale);
        payCashComplete(sale, "200");
        Long ticketId = posState.payment.ticketDbId;
        Assertions.assertNotNull(ticketId, "the settled sale must carry a persisted draft");
        finishSale(sale);
        sale.close();
        String number = ticketNumber(ticketId);
        // --- Refund the full 102,00 € as a voucher (free amount = ticket total) ---
        Page page = freshSale();
        assertTicketFoundBySearch(number);
        openReturnDetail(page, ticketId);
        page.navigate(base.toString() + "return/edit-amount");
        page.locator("#mainInput").waitFor();
        tapDigits(page.locator("#numpadContainer"), "102");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ESPÈCES").setExact(true)).click();
        page.getByText("Montant Retour").waitFor();
        Assertions.assertEquals(0, posState.refund.getTotalRefundAmount().compareTo(new BigDecimal("102")),
                "the full 102,00 € must stage within the ticket cap");
        clearPrinter();
        page.navigate(base.toString() + "return/pay/voucher");
        approveEndorsementWithManager(page);
        // --- The created refund and the printed AVOIR ---
        List<RefundSnap> refunds = refundsFor(ticketId);
        Assertions.assertEquals(1, refunds.size(), "the voucher refund must be created");
        Assertions.assertEquals("VOUCHER", refunds.get(0).method, "the refund method must be VOUCHER");
        Assertions.assertEquals(0, refunds.get(0).totalAmount.compareTo(new BigDecimal("102.00")),
                "the credit note must carry the full 102,00 €, above the old 99,99 € cap");
        String paper = printerContent();
        Assertions.assertTrue(paper.contains("AVOIR"),
                "the printed credit note must be titled AVOIR, paper was:\n" + paper);
        Matcher m = CREDIT_NOTE.matcher(paper);
        Assertions.assertTrue(m.find(),
                "the credit note must carry a 297-prefixed registry number, paper was:\n" + paper);
        String noteNumber = m.group();
        // --- The registry holds the note at its full, uncapped amount ---
        StoredValueSnap sv = storedValue(noteNumber);
        Assertions.assertNotNull(sv, "the registry must hold the issued credit note " + noteNumber);
        Assertions.assertEquals("CREDIT_NOTE", sv.kind, "the instrument must be a CREDIT_NOTE");
        Assertions.assertEquals("ACTIVE", sv.status, "a fresh credit note must be ACTIVE");
        Assertions.assertEquals(0, sv.initialAmount.compareTo(new BigDecimal("102.00")),
                "the note's initial amount must be the full 102,00 €");
        Assertions.assertEquals(0, sv.balance.compareTo(new BigDecimal("102.00")),
                "an unused note keeps its full 102,00 € balance (consultable — cross F19)");
        Assertions.assertTrue(sv.initialAmount.compareTo(new BigDecimal("99.99")) > 0,
                "the 102,00 € note proves the historical 99,99 € cap is lifted");
        page.close();
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
        // A badge only reaches the AUTHENTICATION handler on a register that
        // is locked AND has no modal claiming the scan chain first: an
        // endorsement prompt takes precedence over the lock, and an age-check
        // prompt parks whatever is scanned. Either one left behind by a
        // scenario that ended mid-gesture swallows this badge, and the PIN
        // prompt never appears — a bare 30 s timeout. Clear the ground.
        clearPendingPrompts();
        closeDrawer();
        page.navigate(root + "lock");
        Assertions.assertTrue(posState.isLocked(),
                "the register must be LOCKED after /lock — état: " + describeRegister());
        APIResponse scan = context.request().post(root + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(badge));
        Assertions.assertTrue(scan.ok(), "the hardware scan bus should accept the badge");
        try {
            page.getByText("Entrez votre code PIN :")
                    .waitFor(new Locator.WaitForOptions().setTimeout(10000));
        } catch (RuntimeException promptNeverCame) {
            Assertions.fail("le badge n'a pas atteint l'authentification — état: "
                    + describeRegister() + ", page: " + page.url());
        }
        tapDigits(page.locator("#keyboardArea"), pin);
        page.locator("#actionBtn").click();
        clickThroughHardwareGate(page);
    }

    /**
     * Dismisses any modal that would claim the scan chain before the
     * authentication handler, so a badge presented next reaches the lock.
     * Both prompts live in memory only: a scenario that ended mid-gesture has
     * no other way to put them down.
     */
    private void clearPendingPrompts() {
        posState.endorsement.clear();
        posState.ageCheck.clear();
        posState.touch();
    }

    /**
     * Describes the register state that decides where a scanned badge goes,
     * and whether a gesture can land at all.
     *
     * @return a one-line state description for failure messages
     */
    private String describeRegister() {
        return "locked=" + posState.isLocked()
                + ", endorsement=" + posState.endorsement.active
                + ", ageCheck=" + posState.ageCheck.active
                + ", paymentInProgress=" + posState.payment.paymentInProgress;
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
     * line is rendered). Entering /pay flips {@code paymentInProgress} on, which
     * is what makes a subsequently scanned voucher be treated as a payment.
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

    // --- Refund-screen gestures ---

    /**
     * Runs a ticket search on the refund search form (the exact endpoint the
     * on-screen keypad auto-submits) and asserts the ticket surfaces in the
     * rendered result list — the "search then click the result" gesture, whose
     * result row is then opened by {@link #openReturnDetail(Page, Long)}.
     *
     * @param ticketNumber the exact number of the ticket to find
     */
    private void assertTicketFoundBySearch(String ticketNumber) {
        APIResponse res = context.request().post(base.toString() + "return/search",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                        .setData("rawValue=" + ticketNumber));
        Assertions.assertTrue(res.ok(), "the refund search endpoint must accept the pattern");
        String html = res.text();
        Assertions.assertTrue(html.contains(ticketNumber),
                "the search must list the closed ticket " + ticketNumber + " in its results");
        Assertions.assertTrue(html.contains("/return/select/"),
                "each search result must be an openable ticket row");
    }

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
     * -} steppers). Clicking the line TOGGLES the selection, so this is called
     * once per opened detail before dialling with {@link #addReturnQty(Page,
     * Long)}.
     *
     * @param page the Playwright page carrying the refund detail
     * @param lineId the database id of the ticket line
     */
    private void selectReturnLine(Page page, Long lineId) {
        page.locator("a.line-content[href='/return/select-line/" + lineId + "']").click();
        page.locator("a[href='/return/line/" + lineId + "/add']").waitFor();
    }

    /**
     * Taps a selected line's {@code +} stepper once, incrementing the staged
     * return quantity — the stepper caps at what is still refundable, so a tap
     * beyond the remainder leaves the quantity unchanged (the staging
     * double-refund guard).
     *
     * @param page the Playwright page carrying the refund detail with the line selected
     * @param lineId the database id of the ticket line
     */
    private void addReturnQty(Page page, Long lineId) {
        page.locator("a[href='/return/line/" + lineId + "/add']").click();
        page.getByText("Montant Retour").waitFor();
    }

    /**
     * Validates the pending endorsement as the manager: badges on the bus (the
     * modal jumps to PIN entry, the login prefilled from the mailbox), taps the
     * manager PIN on the modal numpad and submits — the four-eyes gesture ending
     * back on the main sale screen (whether the endorsed refund succeeded or was
     * rolled back by a guard).
     *
     * @param page the Playwright page carrying the endorsement modal
     */
    private void approveEndorsementWithManager(Page page) {
        scan(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), MANAGER_PIN);
        // Scope to the modal: the return detail page also carries a (disabled)
        // ticket-pagination SUIVANT button that would otherwise collide.
        page.locator("#endorseModal").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        // The endorsed gesture may open the drawer (a cash refund) or print,
        // and the register then answers with the drawer interstitial instead
        // of the sale screen — waiting for the sale text alone would hang on
        // a page that is legitimately elsewhere. The SERVER fact is that the
        // endorsement is consumed; the sale screen is reached after pushing
        // the drawer shut.
        waitForEndorsementConsumed();
        closeDrawer();
        page.navigate(base.toString());
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Waits for the endorsement prompt to be consumed server-side (granted or
     * refused), failing with the register state rather than on a rendering
     * detail.
     */
    private void waitForEndorsementConsumed() {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (!posState.endorsement.active) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assertions.fail("l'avenant n'a pas été consommé — état: " + describeRegister()
                + ", message = " + posState.ticket.transientError);
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

    // --- Hardware bus & keypad ---

    /**
     * Presents a code on the hardware scan bus (scanner gun / simulator) and
     * asserts the bus accepted it — the uniform entry of every scanned code, the
     * manager badge on the endorsement modal and the store voucher on encashment.
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

    // --- Database / journal oracles ---

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
     * Reads the sold quantity of a ticket line.
     *
     * @param ticketId the database id of the ticket
     * @param lineId the database id of the line
     * @return the line's sold quantity
     */
    private BigDecimal lineQuantity(Long ticketId, Long lineId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Ticket t = Ticket.findById(ticketId);
            return t.lines.stream().filter(l -> l.id.equals(lineId)).findFirst().orElseThrow().quantity;
        });
    }

    /**
     * Reads every refund created against a ticket into plain snapshots (so no
     * lazy field is touched outside the transaction).
     *
     * @param ticketId the database id of the original ticket
     * @return the refund snapshots, newest-agnostic order
     */
    private List<RefundSnap> refundsFor(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() ->
                Refund.<Refund>list("originalTicketId", ticketId).stream()
                        .map(r -> new RefundSnap(
                                r.refundMethod != null ? r.refundMethod.name() : null,
                                r.status.name(), r.totalAmount, r.totalVat, r.totalExcludingTax))
                        .collect(Collectors.toList()));
    }

    /**
     * Computes the current theoretical drawer cash of the open C04 session
     * (float + cash payments - cash refunds) through the real report builder.
     *
     * @return the theoretical cash amount
     */
    private BigDecimal theoreticalCash() {
        return QuarkusTransaction.requiringNew().call(() -> {
            CashSession s = CashSession.findOpenByTerminal("C04");
            return cashSessionService.buildReport(s).theoreticalCash;
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
     * Immutable snapshot of a refund's durable fields, read off the database so
     * assertions never hold a live entity reference.
     */
    private static final class RefundSnap {
        /** The refund method name (CASH, CARD, VOUCHER, LOYALTY), or null. */
        final String method;
        /** The lifecycle status name (OPEN, CLOSED). */
        final String status;
        /** The refunded total including tax. */
        final BigDecimal totalAmount;
        /** The restituted VAT total, or null when unbreakable. */
        final BigDecimal totalVat;
        /** The tax-excluded total, or null when unbreakable. */
        final BigDecimal totalExcludingTax;

        /**
         * Creates a refund snapshot.
         *
         * @param method the refund method name
         * @param status the status name
         * @param totalAmount the tax-included total
         * @param totalVat the restituted VAT total
         * @param totalExcludingTax the tax-excluded total
         */
        RefundSnap(String method, String status, BigDecimal totalAmount,
                   BigDecimal totalVat, BigDecimal totalExcludingTax) {
            this.method = method;
            this.status = status;
            this.totalAmount = totalAmount;
            this.totalVat = totalVat;
            this.totalExcludingTax = totalExcludingTax;
        }
    }

    /**
     * Clicks through the hardware status gate that every successful unlock lands
     * on (AuthResource.unlock renders the hardware-unavailable page): Continuer
     * takes the override that opens the lane and routes on to the session or the
     * sale screen.
     *
     * @param page the Playwright page driving the register
     */
    private void clickThroughHardwareGate(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Continuer").setExact(true)).click();
    }

    /**
     * Enters the opening float on the session screen's touch keypad
     * ({@code #floatKbArea}): the native input was replaced by a PosInput pad
     * writing a hidden field, so the amount is tapped digit by digit after a
     * CLEAR of the prefilled default, as the cashier would on the borne.
     *
     * @param page the Playwright page driving the register
     * @param amount the float to key in, comma as the decimal separator
     */
    private void enterOpeningFloat(Page page, String amount) {
        Locator keypad = page.locator("#floatKbArea");
        keypad.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("C").setExact(true)).click();
        for (char key : amount.toCharArray()) {
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
    }

    // --- Lot-1 (H6–H9) additions: fidelity refund, gift card, tender rules, registry ---

    /**
     * Builds and finalizes a cash sale that also attaches a fidelity card:
     * scans the products, presents the card on the bus (synced onto the draft),
     * tenders cash and completes — the origin ticket for a loyalty refund. The
     * card is scanned AFTER a product so a draft already exists and the
     * scan-driven sync copies it onto the persisted ticket.
     *
     * @param page the Playwright page on the empty sale screen
     * @param cashDigits the cash amount to tap (must cover the total)
     * @param card the fidelity card to attach
     * @param eans the product EANs to scan
     * @return the database id of the closed ticket
     */
    private Long closeCashSaleWithCardOn(Page page, String cashDigits, String card, String... eans) {
        for (String ean : eans) scan(ean);
        scan(card);
        page.locator("#fidIcon.active").waitFor();
        goPay(page);
        payCashComplete(page, cashDigits);
        Long ticketId = posState.payment.ticketDbId;
        Assertions.assertNotNull(ticketId, "the settled sale must carry a persisted draft");
        finishSale(page);
        return ticketId;
    }

    /**
     * Opens a found ticket's refund detail without asserting any product label —
     * used when the line is not the HUILE fixture (e.g. a gift card).
     *
     * @param page the Playwright page driving the register
     * @param ticketId the database id of the ticket to open
     */
    private void openReturnDetailNoLabel(Page page, Long ticketId) {
        page.navigate(base.toString() + "return/select/" + ticketId);
        page.getByText("Montant Retour").waitFor();
    }

    /**
     * Validates the pending endorsement as the manager WITHOUT navigating back to
     * the sale afterwards, so a side effect written on the customer display (the
     * loyalty credit message) can be read before a later poll overwrites it.
     *
     * @param page the Playwright page carrying the endorsement modal
     */
    private void approveEndorsementNoNav(Page page) {
        scan(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), MANAGER_PIN);
        page.locator("#endorseModal").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        waitForEndorsementConsumed();
    }

    /**
     * Reads the current customer-display text off the hardware simulator.
     *
     * @return the last message pushed to the customer display
     */
    private String displayContent() {
        return context.request().get(base.toString() + "api/hardware/display").text();
    }

    /**
     * Counts the fidelity outbox events of a given type.
     *
     * @param type the fidelity event type
     * @return the number of queued events of that type
     */
    private long fidEventCount(FidEvent.EventType type) {
        return QuarkusTransaction.requiringNew().call(() -> FidEvent.count("eventType", type));
    }

    /**
     * Reads the fidelity card persisted on a ticket.
     *
     * @param ticketId the database id of the ticket
     * @return the ticket's fidelity card number, or null when none is attached
     */
    private String fidelityCardOf(Long ticketId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Ticket) Ticket.findById(ticketId)).fidelityCard);
    }

    /**
     * Administers a {@code CARD} tender row that forbids refunds (BO-03-02-15),
     * so a card refund is refused with {@code MOYEN NON AUTORISE AU
     * REMBOURSEMENT}. No tender is seeded by default (a missing tender is
     * allowed), so the row is created for the guard and dropped afterwards.
     */
    private void forbidCardRefund() {
        QuarkusTransaction.requiringNew().run(() -> {
            TenderDefinition t = new TenderDefinition();
            t.code = "CARD";
            t.functionalId = "CARDRF";
            t.label = "Carte bancaire (refund forbidden, test)";
            t.refundAllowed = false;
            t.persist();
        });
    }

    /**
     * Removes the administered {@code CARD} tender row, restoring the default
     * "no tender defined = refund allowed" state for the shared referential.
     */
    private void allowAllRefunds() {
        QuarkusTransaction.requiringNew().run(() -> TenderDefinition.delete("code = ?1", "CARD"));
    }

    /**
     * Reads a stored-value instrument off the registry into an immutable
     * snapshot, so assertions never hold a live entity.
     *
     * @param number the registry number to look up
     * @return the snapshot, or null when the registry never issued the number
     */
    private StoredValueSnap storedValue(String number) {
        return QuarkusTransaction.requiringNew().call(() -> {
            StoredValue sv = StoredValue.findByNumber(number);
            return sv == null ? null
                    : new StoredValueSnap(sv.kind.name(), sv.status.name(), sv.initialAmount, sv.balance);
        });
    }

    /**
     * Immutable snapshot of a stored-value instrument's durable fields.
     */
    private static final class StoredValueSnap {
        /** The instrument kind (CREDIT_NOTE, GIFT_CARD). */
        final String kind;
        /** The lifecycle status (ACTIVE, EXHAUSTED). */
        final String status;
        /** The amount the instrument was issued with. */
        final BigDecimal initialAmount;
        /** The current redeemable balance. */
        final BigDecimal balance;

        /**
         * Creates a stored-value snapshot.
         *
         * @param kind the instrument kind
         * @param status the lifecycle status
         * @param initialAmount the issued amount
         * @param balance the current balance
         */
        StoredValueSnap(String kind, String status, BigDecimal initialAmount, BigDecimal balance) {
            this.kind = kind;
            this.status = status;
            this.initialAmount = initialAmount;
            this.balance = balance;
        }
    }

}
