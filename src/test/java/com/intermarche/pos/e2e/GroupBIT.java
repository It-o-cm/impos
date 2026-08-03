package com.intermarche.pos.e2e;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
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

import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Group B end-to-end scenarios (Vente — scan &amp; saisie), played THROUGH THE
 * SCREEN with a headless Chromium browser (quarkus-playwright) against the real
 * application (live H2, real beans, embedded hardware simulator). Group B is
 * entirely {@code [S]} (caisse seule + simulateur), so every scenario B1–B14 is
 * implemented here; there is no {@code [V]}/{@code [N]} residue for this letter.
 * <p>
 * <b>The scanner IS the bus.</b> The sale screen carries no visible code field:
 * a scanner gun, a keyboard wedge or a typed PLU all reach the register through
 * {@code POST /api/pos/scan} (the hardware bus, exactly as in group A), so every
 * "scan" and every "typed code" here is presented on that bus while the cashier
 * stands on the sale screen. Real weighing (B4) is driven by tapping a fruit
 * tile on {@code /fruits} after the scale simulator is loaded via
 * {@code POST /api/hardware/set-weight}; the drill-down (B9), the search (B10),
 * the quantity/price modals (B2/B13) and the endorsement modal (B2/B14) are all
 * driven by real taps on their screens.
 * <p>
 * <b>Ordering &amp; shared session.</b> The scenarios run under
 * {@link MethodOrderer.MethodName} (b01…b14) and share ONE cash session:
 * {@link #ensureOpenSession()} opens it once (on the fresh drop-and-create H2
 * boot) through the full prise-de-poste screen and every later call is a no-op
 * because the register already carries an OPEN {@code C04-Sxxxxx} session. Each
 * scenario then builds its OWN cart: a logout ({@code /lock}) abandons the
 * in-memory cart (and clears the per-ticket sticker set and last-weight guard),
 * so {@link #loginToSaleWithOpenSession(Page)} always lands on an empty sale.
 * <p>
 * <b>Oracles.</b> The reactive screen ({@code TOTAL À PAYER}, rendered line
 * labels, the message zone) is asserted where the mutation touches the polling
 * version; the server state ({@link PosState#ticket}) is the oracle for the
 * INVISIBLE outcomes — a code that only reaches the unknown-code fallback sets
 * its message WITHOUT a {@code touch()} (group-A lesson), so {@code CODE INCONNU}
 * is read on the injected state, not the polled screen. Prices asserted are the
 * local catalog figures (the remote valuation engine at {@code :8090} is not up
 * under test, so the cart stays on degraded/local totals — the sale is never
 * blocked).
 */
@QuarkusTest
@TestProfile(E2eTestProfile.class)
@WithPlaywright(headless = true)
@TestMethodOrder(MethodOrderer.MethodName.class)
public class GroupBIT {

    /** The seed cashier badge (Jean Dupont / jdupont). */
    private static final String CASHIER_BADGE = "12341234";

    /** The seed cashier PIN. */
    private static final String CASHIER_PIN = "1234";

    /** The seed manager badge (Marie Curie / mcurie, MANAGER role). */
    private static final String MANAGER_BADGE = "11111111";

    /** The seed manager PIN. */
    private static final String MANAGER_PIN = "1111";

    // --- Catalog fixtures (DataInitializer seed) ---

    /** Single-price unit product: EAN, uppercased label, TTC 0,96 €. */
    private static final String EAN_BAGUETTE = "3300000000003";
    private static final String LABEL_BAGUETTE = "BAGUETTE TRADITION";

    /** Single-price unit product: EAN, uppercased label, TTC 6,00 €. */
    private static final String EAN_HUILE = "3300000000006";
    private static final String LABEL_HUILE = "HUILE D'OLIVE 1L";

    /** Single-price unit product used for the catalog-price-change merge refusal. */
    private static final String EAN_BISCUITS = "3300000000013";

    /** Register-only product flagged forbidden to sale (non-engine EAN). */
    private static final String EAN_FORBIDDEN = "3660000099999";

    /** Weighed product carrying PLU 4020 (Pommes Golden), EAN and per-kg price. */
    private static final String PLU_POMMES = "4020";
    private static final String EAN_POMMES = "3300000000001";
    private static final String LABEL_POMMES = "POMMES GOLDEN";

    /** EAN-only unit product reached through the SAISIE DIRECTE drill-down. */
    private static final String EAN_POELE = "3300000000031";
    private static final String LABEL_POELE = "Poêle Antiadhésive 28cm";

    /** Deposit-return voucher: 298 + serial + 4-digit cents (0150 -> 1,50 €). */
    private static final String DEPOSIT_VOUCHER = "2981234560150";

    /** In-store 2x weight label (prefix 25, article 4020, 1250 g), valid EAN13. */
    private static final String WEIGHT_LABEL_VALID = "2504020012503";

    /** Same 2x weight label with a broken check digit: falls through to CODE INCONNU. */
    private static final String WEIGHT_LABEL_BAD = "2504020012504";

    /** In-store 2x price label (prefix 21, article 4020, 150 cents), valid EAN13. */
    private static final String PRICE_LABEL = "2104020001509";

    /** A code no handler recognizes (13 digits, absent from the catalog). */
    private static final String UNKNOWN_CODE = "9999999999999";

    /** The Playwright browser context injected by the quarkus-playwright extension. */
    @InjectPlaywright
    BrowserContext context;

    /** The live application base URL, auto-wired by @QuarkusTest. */
    @TestHTTPResource("/")
    URL base;

    /**
     * The server-side POS singleton, injected to assert the invisible half of
     * several scenarios: the untouched unknown-code message, the exact line
     * kinds (unit / weighed / negative), quantities and snapshotted prices.
     */
    @Inject
    PosState posState;

    /**
     * B1 — Scan EAN catalogue &amp; fusion.
     * <p>
     * A catalog EAN scanned on the bus creates a unit line with the price and
     * VAT snapshotted from the current catalog row; re-scanning the SAME EAN
     * merges into that line (quantity 2, still a single line), because only an
     * unmodified unit EAN line at the same price absorbs a new scan.
     */
    @Test
    void b01_scan_ean_catalogue_et_fusion() {
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        // Snapshot oracle: one unit line, catalog price 0,96 €, VAT 20 %.
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "a catalog EAN must create exactly one line");
        TicketState.TicketItem line = items.get(0);
        Assertions.assertEquals(EAN_BAGUETTE, line.ean, "the line must carry the scanned EAN");
        Assertions.assertNull(line.plu, "a catalog EAN sells a unit line (no PLU)");
        Assertions.assertEquals(0, line.unitPrice.compareTo(new BigDecimal("0.96")),
                "the unit price must be snapshotted from the catalog (0,96 €)");
        Assertions.assertEquals(0, line.vatRate.compareTo(new BigDecimal("0.2000")),
                "the VAT rate must be snapshotted from the catalog (20 %)");
        // Re-scan the same EAN: merge into the same line, quantity 2.
        scan(EAN_BAGUETTE);
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "re-scanning the same EAN must merge, not add a second line");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("2")),
                "the merged line must carry quantity 2");
        page.close();
    }

    /**
     * B2 — Refus de fusion.
     * <p>
     * Two independent reasons block the merge: a line already discounted (a
     * manager REMISE) never absorbs a new scan of the same EAN — two lines —
     * and a catalog price changed between two scans of the same EAN also
     * yields two lines, because the incoming price no longer matches the
     * snapshotted one.
     */
    @Test
    void b02_refus_de_fusion() {
        // --- Reason 1: a discounted line does not merge ---
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        // Manager REMISE of 1,00 € through the price modal + endorsement.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("REMISE").setExact(true)).click();
        page.locator("#priceKbArea").waitFor();
        tapDigits(page.locator("#priceKbArea"), "1");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        approveEndorsementWithManager(page);
        Assertions.assertNotNull(posState.ticket.items.get(0).modifierLabel,
                "the manager REMISE must mark the line as modified");
        // Re-scan the same EAN: the modified line cannot merge -> a second line.
        scan(EAN_HUILE);
        Assertions.assertEquals(2, posState.ticket.items.size(),
                "a discounted line must not absorb a new scan of the same EAN");
        page.close();

        // --- Reason 2: a catalog price change between two scans ---
        Page page2 = freshSale();
        scan(EAN_BISCUITS);
        Assertions.assertEquals(1, posState.ticket.items.size(), "the first scan creates one line");
        // Insert a higher-priority catalog price so the next scan snapshots a
        // different figure (higher priority wins in findCurrentPrice).
        Long priceId = insertPromoPrice(EAN_BISCUITS, "3.00");
        try {
            scan(EAN_BISCUITS);
            Assertions.assertEquals(2, posState.ticket.items.size(),
                    "a catalog price change between two scans must yield two lines");
        } finally {
            // Restore the catalog for the isolation of the following scenarios.
            QuarkusTransaction.requiringNew().run(() -> Price.deleteById(priceId));
        }
        page2.close();
    }

    /**
     * B3 — PLU tapé.
     * <p>
     * A typed PLU ({@code 4020}) sells QUANTITY 1 at the current price and,
     * being weighed by nature, starts a weighed line (PLU carried) that never
     * merges — the service-counter shortcut, distinct from the real weighing on
     * the FRUITS screen.
     */
    @Test
    void b03_plu_tape_quantite_un() {
        Page page = freshSale();
        scan(PLU_POMMES);
        page.getByText(LABEL_POMMES).waitFor();
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "a typed PLU must create one line");
        TicketState.TicketItem line = items.get(0);
        Assertions.assertEquals(PLU_POMMES, line.plu, "the line must carry the typed PLU (weighed line)");
        Assertions.assertEquals(0, line.quantity.compareTo(BigDecimal.ONE),
                "a typed PLU sells quantity 1 at the current price");
        // A weighed line never merges: a second typed PLU is its own line.
        scan(PLU_POMMES);
        Assertions.assertEquals(2, posState.ticket.items.size(),
                "weighed (PLU) lines never merge");
        page.close();
    }

    /**
     * B4 — Pesée FRUITS.
     * <p>
     * A tap on a fruit tile sells the CURRENT SCALE WEIGHT: with the simulator
     * at 1,250 kg the tap builds a 1,250 kg weighed line at the per-kilogram
     * catalog price. A null/absent weight is refused cleanly ({@code POIDS
     * INVALIDE}) with no line created.
     */
    @Test
    void b04_pesee_fruits() {
        Page page = freshSale();
        // Load the scale, then tap the Pommes Golden tile on the weighing grid.
        page.navigate(base.toString() + "fruits");
        setScaleWeight("1.250");
        page.locator("a.plu-btn[href='/action/add/" + PLU_POMMES + "']").click();
        page.getByText(LABEL_POMMES).waitFor();
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "a fruit weighing must create one line");
        Assertions.assertEquals(PLU_POMMES, items.get(0).plu, "the weighed line must carry the PLU");
        Assertions.assertEquals(0, items.get(0).quantity.compareTo(new BigDecimal("1.250")),
                "the weighed line quantity must be the scale weight (1,250 kg)");
        // Null weight: a fresh tap with the scale at 0 is refused cleanly.
        page.navigate(base.toString() + "fruits");
        setScaleWeight("0");
        page.locator("a.plu-btn[href='/action/add/" + PLU_POMMES + "']").click();
        page.getByText("POIDS INVALIDE").waitFor();
        Assertions.assertEquals("POIDS INVALIDE", posState.ticket.transientError,
                "a null weight must be refused with POIDS INVALIDE");
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "a refused weighing must not create a line");
        page.close();
    }

    /**
     * B5 — Étiquette 2x poids.
     * <p>
     * An in-store weight label (prefix 25) carrying the article PLU and an
     * embedded weight builds a weighed line at the catalog per-kilogram price;
     * an invalid EAN13 checksum makes the code fall SILENTLY through to the
     * following handlers and end on the unknown-code fallback ({@code CODE
     * INCONNU}).
     */
    @Test
    void b05_etiquette_2x_poids() {
        Page page = freshSale();
        scan(WEIGHT_LABEL_VALID);
        page.getByText(LABEL_POMMES).waitFor();
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals(PLU_POMMES, line.plu, "the 2x weight label must carry the article PLU");
        Assertions.assertEquals(EAN_POMMES, line.ean, "the weighed line must carry the catalog EAN");
        Assertions.assertEquals(0, line.quantity.compareTo(new BigDecimal("1.250")),
                "the embedded weight (1250 g) must become 1,250 kg");
        // Invalid checksum: silent fall-through to the unknown-code fallback,
        // which sets its message WITHOUT a touch -> assert on the server state.
        scan(WEIGHT_LABEL_BAD);
        Assertions.assertEquals("CODE INCONNU: " + WEIGHT_LABEL_BAD, posState.ticket.transientError,
                "an invalid checksum must fall through to CODE INCONNU");
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "the rejected label must not create a line");
        page.close();
    }

    /**
     * B6 — Étiquette 2x prix &amp; anti-double-scan.
     * <p>
     * An in-store price label (prefix 21) builds a line at the embedded total,
     * carrying no code so it never merges; re-scanning the SAME physical
     * sticker is refused ({@code ÉTIQUETTE DÉJÀ SCANNÉE}). The per-ticket
     * sticker set is in-memory only, so a fresh ticket (the assumed-limit
     * "restart") accepts the same sticker again.
     */
    @Test
    void b06_etiquette_2x_prix_anti_double_scan() {
        Page page = freshSale();
        scan(PRICE_LABEL);
        page.getByText(LABEL_POMMES).waitFor();
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertNull(line.ean, "a price-embedded sticker carries no EAN");
        Assertions.assertNull(line.plu, "a price-embedded sticker carries no PLU");
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("1.50")),
                "the line total must be the embedded price (1,50 €)");
        // Same sticker twice on the same ticket: refused.
        scan(PRICE_LABEL);
        page.getByText("ÉTIQUETTE DÉJÀ SCANNÉE").waitFor();
        Assertions.assertEquals("ÉTIQUETTE DÉJÀ SCANNÉE", posState.ticket.transientError,
                "a re-scanned sticker must be refused");
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "the refused re-scan must not add a line");
        // Fresh ticket (the in-memory sticker set is cleared with the cart):
        // the same sticker is accepted again (assumed limit).
        page.close();
        Page page2 = freshSale();
        scan(PRICE_LABEL);
        page2.getByText(LABEL_POMMES).waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "a fresh ticket forgets the sticker set and accepts the sticker again");
        page2.close();
    }

    /**
     * B7 — Code inconnu, effacé au scan suivant.
     * <p>
     * An unrecognized code sets the transient {@code CODE INCONNU} message on
     * the server state (untouched, so it never reaches the poll); the very next
     * scan clears it and adds its own line.
     */
    @Test
    void b07_code_inconnu_efface_au_scan_suivant() {
        Page page = freshSale();
        scan(UNKNOWN_CODE);
        Assertions.assertEquals("CODE INCONNU: " + UNKNOWN_CODE, posState.ticket.transientError,
                "an unrecognized code must set the CODE INCONNU message");
        Assertions.assertTrue(posState.ticket.items.isEmpty(),
                "an unknown code must not create a line");
        // The next scan clears the transient message and adds its line.
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        Assertions.assertNull(posState.ticket.transientError,
                "the next scan must clear the transient CODE INCONNU message");
        Assertions.assertEquals(1, posState.ticket.items.size(), "the next scan must add its line");
        page.close();
    }

    /**
     * B8 — Produit interdit à la vente.
     * <p>
     * A product flagged forbidden to sale is refused with {@code PRODUIT
     * INTERDIT À LA VENTE} and creates no line.
     */
    @Test
    void b08_produit_interdit_a_la_vente() {
        Page page = freshSale();
        scan(EAN_FORBIDDEN);
        page.getByText("PRODUIT INTERDIT À LA VENTE").waitFor();
        Assertions.assertEquals("PRODUIT INTERDIT À LA VENTE", posState.ticket.transientError,
                "a forbidden product must be refused");
        Assertions.assertTrue(posState.ticket.items.isEmpty(),
                "a forbidden product must not create a line");
        page.close();
    }

    /**
     * B9 — SAISIE DIRECTE.
     * <p>
     * The drill-down root shows only the head families (no branch duplication);
     * drilling to an EAN-only product and validating a quantity adds a unit
     * line, and the "NON RECONNU" tile at the root adds a code-less line at the
     * typed price.
     */
    @Test
    void b09_saisie_directe() {
        Page page = freshSale();
        page.navigate(base.toString() + "manual");
        // Root = head families only: the three top branches show, a child
        // family (Pommes à croquer) never appears at the root.
        page.getByText("Rayon Alimentaire").waitFor();
        Assertions.assertTrue(page.getByText("Instruments de Cuisine").isVisible(),
                "the head family Instruments de Cuisine must show at the root");
        Assertions.assertTrue(page.getByText("Épicerie & Divers").isVisible(),
                "the head family Épicerie & Divers must show at the root");
        Assertions.assertEquals(0, page.getByText("Pommes à croquer").count(),
                "a child family must not be duplicated at the root");
        // Drill into a leaf family and add a known product with quantity 2.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Instruments de Cuisine").setExact(true)).click();
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(LABEL_POELE).setExact(true)).click();
        tapDigits(page.locator("#keyboardArea"), "2");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        page.getByText(LABEL_POELE.toUpperCase()).waitFor();
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "the drill-down add must create one line");
        Assertions.assertEquals(EAN_POELE, items.get(0).ean, "the added line must carry the chosen EAN");
        Assertions.assertEquals(0, items.get(0).quantity.compareTo(new BigDecimal("2")),
                "the typed quantity (2) must be applied");

        // Unknown product at a typed price -> a code-less line.
        page.close();
        Page page2 = freshSale();
        page2.navigate(base.toString() + "manual");
        page2.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("NON RECONNU").setExact(true)).click();
        tapDigits(page2.locator("#keyboardArea"), "2");
        page2.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        page2.getByText("PRODUIT NON RECONNU").waitFor();
        TicketState.TicketItem unknown = posState.ticket.items.get(0);
        Assertions.assertNull(unknown.ean, "an unknown-price line must carry no EAN");
        Assertions.assertEquals("PRODUIT NON RECONNU", unknown.label, "the typed label must be kept");
        Assertions.assertEquals(0, unknown.unitPrice.compareTo(new BigDecimal("2")),
                "the typed price must be applied");
        page2.close();
    }

    /**
     * B10 — Recherche.
     * <p>
     * A query returns hits with their formatted price; tapping a hit adds it to
     * the cart (through the same ticket path as a scan); a query with no match
     * shows a clean empty state.
     */
    @Test
    void b10_recherche() {
        Page page = freshSale();
        page.navigate(base.toString() + "search?q=pomm");
        page.getByText(LABEL_POMMES).waitFor();
        // Tap the first hit: it is added to the cart.
        page.locator("a.hit-btn").first().click();
        page.getByText("TOTAL À PAYER").waitFor();
        Assertions.assertFalse(posState.ticket.items.isEmpty(), "tapping a hit must add a line");
        // A query with no match shows the clean empty state.
        page.navigate(base.toString() + "search?q=zzzqzz");
        page.getByText("AUCUN PRODUIT POUR « zzzqzz »").waitFor();
        Assertions.assertTrue(page.getByText("AUCUN PRODUIT POUR « zzzqzz »").isVisible(),
                "an empty search must show the clean empty state");
        page.close();
    }

    /**
     * B11 — Déconsigne.
     * <p>
     * A deposit-return voucher scanned on the sale screen becomes a negative,
     * zero-VAT line that never merges (two scans -> two lines). The SAME
     * voucher scanned DURING an active payment is ignored (a deposit is a sale
     * line, not a payment): it falls through to the unknown-code fallback and
     * adds nothing.
     */
    @Test
    void b11_deconsigne() {
        // --- Cart phase: a negative, non-mergeable line ---
        Page page = freshSale();
        scan(DEPOSIT_VOUCHER);
        page.getByText("BON DE CONSIGNE").waitFor();
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertTrue(line.isNegative(), "a deposit line must be negative");
        Assertions.assertEquals(0, line.getTotalPrice().compareTo(new BigDecimal("-1.50")),
                "the deposit line must carry the embedded amount, negated");
        Assertions.assertEquals(0, line.vatRate.compareTo(BigDecimal.ZERO), "a deposit line is out of VAT scope");
        scan(DEPOSIT_VOUCHER);
        Assertions.assertEquals(2, posState.ticket.items.size(), "a deposit line never merges");
        page.close();

        // --- Payment phase: the same voucher is ignored ---
        Page page2 = freshSale();
        scan(EAN_BAGUETTE);
        page2.getByText(LABEL_BAGUETTE).waitFor();
        // Enter the payment screen: the GET flips paymentInProgress on.
        page2.navigate(base.toString() + "pay");
        page2.getByText("RESTE :").waitFor();
        scan(DEPOSIT_VOUCHER);
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "a deposit voucher scanned during a payment must add no line");
        Assertions.assertEquals("CODE INCONNU: " + DEPOSIT_VOUCHER, posState.ticket.transientError,
                "during a payment the deposit falls through to the unknown-code fallback");
        page2.close();
    }

    /**
     * B12 — Annulation de ligne.
     * <p>
     * Selecting the last-entered line and cancelling removes it directly (no
     * endorsement): the total is recomputed and the persisted draft is
     * resynchronized to zero lines.
     */
    @Test
    void b12_annulation_de_ligne() {
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        // The current sale's draft id (drafts accumulate across scenarios, so
        // read the in-memory id, never the oldest OPEN draft in the database).
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "scanning a line must persist the sale's draft");
        // Select the line, then cancel it (last entered -> direct cancel).
        page.locator("a.receipt-item-link").first().click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER LIGNE").setExact(true)).click();
        page.getByText("EN ATTENTE D'ARTICLES...").waitFor();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the cancelled line must be removed");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(BigDecimal.ZERO),
                "the total must be recomputed to zero");
        // Draft resynchronized: the single write funnel cancels the draft of an
        // emptied cart (in-memory id reset to null, the row flipped CANCELLED).
        Assertions.assertNull(posState.payment.ticketDbId, "an emptied cart must drop the draft id");
        Ticket.TicketStatus status = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).status);
        Assertions.assertEquals(Ticket.TicketStatus.CANCELLED, status,
                "the resynchronized draft of an emptied cart must be CANCELLED");
        page.close();
    }

    /**
     * B13 — QUANTITÉ.
     * <p>
     * The QUANTITÉ modal applies a whole quantity (1-999) directly on a unit
     * line, with no endorsement; it is refused on a weighed line and on a
     * negative line ({@code QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE}).
     */
    @Test
    void b13_quantite() {
        // --- Valid: a unit line accepts a typed quantity ---
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        applyQuantityModal(page, "3");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("3")),
                "the typed quantity (3) must be applied on the unit line");
        page.close();

        // --- Refused on a weighed line ---
        Page page2 = freshSale();
        scan(PLU_POMMES);
        page2.getByText(LABEL_POMMES).waitFor();
        applyQuantityModal(page2, "2");
        Assertions.assertEquals("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE", posState.ticket.transientError,
                "a weighed line must refuse a quantity change");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(BigDecimal.ONE),
                "the weighed line quantity must be unchanged");
        page2.close();

        // --- Refused on a negative line ---
        Page page3 = freshSale();
        scan(DEPOSIT_VOUCHER);
        page3.getByText("BON DE CONSIGNE").waitFor();
        applyQuantityModal(page3, "2");
        Assertions.assertEquals("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE", posState.ticket.transientError,
                "a negative line must refuse a quantity change");
        page3.close();
    }

    /**
     * B14 — Annulation de ticket.
     * <p>
     * The whole-ticket cancellation is guarded: the endorsement modal opens,
     * the manager validates, the persisted draft is marked CANCELLED in the
     * database and the sale screen comes back blank.
     */
    @Test
    void b14_annulation_de_ticket() {
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        // The current sale's draft id (read in-memory: drafts accumulate).
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "scanning a line must persist the sale's draft");
        // Request the guarded whole-ticket cancellation, then endorse it.
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER TICKET").setExact(true)).click();
        approveEndorsementWithManager(page);
        page.getByText("EN ATTENTE D'ARTICLES...").waitFor();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the cancelled ticket must clear the screen");
        // Database oracle: the draft is CANCELLED.
        Ticket.TicketStatus status = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).status);
        Assertions.assertEquals(Ticket.TicketStatus.CANCELLED, status,
                "the persisted draft must be marked CANCELLED");
        page.close();
    }

    // --- Reusable gestures (login recipe & hardware bus) ---

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
        // No session yet: the unlock lands on the SESSION screen (drawer open,
        // that screen is not guarded). Close the drawer, install the float and
        // open the session, which flows straight into the sale.
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
        // /lock FIRST (logs out, empties the one-shot mailbox), THEN the badge.
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
     * Presents a code on the hardware scan bus (scanner gun / simulator) and
     * asserts the bus accepted it — the uniform entry of every scanned or typed
     * code.
     *
     * @param code the code to present on the bus
     */
    private void scan(String code) {
        APIResponse res = context.request().post(base.toString() + "api/pos/scan",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(code));
        Assertions.assertTrue(res.ok(), "the hardware scan bus should accept the code " + code);
    }

    /**
     * Loads a weight into the scale simulator so the next weighing request
     * consumes it.
     *
     * @param kilograms the weight in kilograms (dot decimal)
     */
    private void setScaleWeight(String kilograms) {
        APIResponse res = context.request().post(base.toString() + "api/hardware/set-weight",
                RequestOptions.create().setHeader("Content-Type", "text/plain").setData(kilograms));
        Assertions.assertTrue(res.ok(), "the scale simulator should accept the weight " + kilograms);
    }

    /**
     * Opens the QUANTITÉ modal on the current target line, taps the digits and
     * validates.
     *
     * @param page the Playwright page driving the register
     * @param digits the quantity digits to tap
     */
    private void applyQuantityModal(Page page, String digits) {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("QUANTITÉ").setExact(true)).click();
        page.locator("#priceKbArea").waitFor();
        tapDigits(page.locator("#priceKbArea"), digits);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
    }

    /**
     * Validates the pending endorsement as the manager: badges on the bus (the
     * modal jumps to PIN entry), taps the manager PIN on the modal numpad and
     * submits — the four-eyes gesture.
     *
     * @param page the Playwright page carrying the endorsement modal
     */
    private void approveEndorsementWithManager(Page page) {
        // The manager badges over the cashier's shoulder; the 500ms
        // /endorsement-data poll consumes the badge and asks for the PIN.
        scan(MANAGER_BADGE);
        page.getByText("Code PIN :").waitFor();
        tapDigits(page.locator("#endorseKeyboardArea"), MANAGER_PIN);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("SUIVANT").setExact(true)).click();
        page.getByText("TOTAL À PAYER").waitFor();
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
     * Inserts a higher-priority ("promo") current price row on a product, so a
     * scan snapshots a different figure — the catalog-price-change fixture of
     * B2. The caller deletes the row afterwards to restore isolation.
     *
     * @param ean the product EAN
     * @param priceTTC the new tax-included price
     * @return the id of the inserted price row
     */
    private Long insertPromoPrice(String ean, String priceTTC) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Product p = Product.find("ean = ?1", ean).firstResult();
            Price np = new Price();
            np.product = p;
            np.priceExcludingTax = new BigDecimal(priceTTC);
            np.priceIncludingTax = new BigDecimal(priceTTC);
            np.vatRate = new BigDecimal("0.2000");
            np.priority = 1;
            np.startDateTime = LocalDateTime.of(2026, 1, 12, 0, 0);
            np.endDateTime = null;
            np.persist();
            return np.id;
        });
    }
}
