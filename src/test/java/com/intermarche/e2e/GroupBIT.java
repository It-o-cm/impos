package com.intermarche.e2e;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.VatRate;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
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
     * The referential settings service, injected so B16/B17/B23 can toggle their
     * flags (GS1 expiry alerts, EAN13 key control) and restore them afterwards.
     */
    @Inject
    PosSettingsService posSettings;

    /** A 27-prefix balance ticket with a VALID EAN13 key (checksum 7). */
    private static final String BALANCE_TICKET_VALID = "2700000000007";

    /** The same balance ticket with a broken key: silent fall-through to CODE INCONNU. */
    private static final String BALANCE_TICKET_BAD = "2700000000000";

    /** A GS1 marked payload naming HUILE by GTIN (leading-zero-padded EAN13). */
    private static final String GS1_HUILE = "(01)03300000000006";

    /** A GS1 marked payload naming POMMES (weighed, per-kg) by GTIN. */
    private static final String GS1_POMMES = "(01)03300000000001";

    /** A GS1 marked payload naming the forbidden product by GTIN. */
    private static final String GS1_FORBIDDEN = "(01)03660000099999";

    /** A GS1 marked payload whose GTIN is absent from the catalog. */
    private static final String GS1_UNKNOWN = "(01)09999999999994";

    /** A 13-digit code with a WRONG EAN13 key (check digit should be 4). */
    private static final String EAN13_BAD_KEY = "9999999999999";

    /** A wine product, age-restricted 18+ (non-engine EAN), for B20. */
    private static final String EAN_WINE = "3400018000001";
    private static final String LABEL_WINE = "VIN ROUGE BORDEAUX 75CL";

    /** A unit product (Miel, 6,00 €) borrowed by B21/B22 for entry prompts/arming. */
    private static final String EAN_MIEL = "3300000000024";

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
        // The separate menu links were folded into a single "REMISE / PRIX" entry
        // whose modal carries the mode selector, so the mode is reached by its own
        // GET rather than by clicking a button named after it.
        page.navigate(base.toString() + "action/price-mod/remise");
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
     * A PLU is NOT a barcode (the scan bus deliberately has no PLU handler):
     * the cashier TYPES it on the FRUITS screen's CODE PLU numpad, which
     * targets {@code /action/add/{code}} and WEIGHS the item — the line
     * carries the product EAN and the typed PLU at the armed scale weight.
     * An identical consecutive weight is refused ({@code ERREUR POIDS
     * IDENTIQUE}, the anti-oubli guard), and a fresh weighing builds its OWN
     * line: weighed lines never merge.
     */
    @Test
    void b03_plu_tape_pese_le_poids_balance() {
        Page page = freshSale();
        page.navigate(base.toString() + "fruits");
        setScaleWeight("0.850");
        typePluCode(page, PLU_POMMES);
        page.getByText(LABEL_POMMES).waitFor();
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "a typed PLU must create one weighed line");
        TicketState.TicketItem line = items.get(0);
        Assertions.assertEquals(PLU_POMMES, line.plu, "the line must carry the typed PLU");
        Assertions.assertEquals(EAN_POMMES, line.ean,
                "the line must carry the product EAN (the engine must see it)");
        Assertions.assertEquals(0, line.quantity.compareTo(new BigDecimal("0.850")),
                "the typed PLU must sell the armed scale weight (0,850 kg)");
        // Same weight again: the anti-oubli guard refuses the re-weighing.
        page.navigate(base.toString() + "fruits");
        setScaleWeight("0.850");
        typePluCode(page, PLU_POMMES);
        page.getByText("ERREUR POIDS IDENTIQUE").waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(),
                "an identical consecutive weight must add no line");
        // A fresh weighing: weighed lines never merge — its own line.
        page.navigate(base.toString() + "fruits");
        setScaleWeight("0.900");
        typePluCode(page, PLU_POMMES);
        page.getByText(LABEL_POMMES).first().waitFor();
        Assertions.assertEquals(2, posState.ticket.items.size(), "weighed lines never merge");
        page.close();
    }

    /**
     * Types a PLU on the FRUITS screen's CODE PLU numpad and validates it —
     * the register's REAL typed-PLU entry ({@code /action/add/{code}}),
     * selling the armed scale weight. The page must sit on the FRUITS screen.
     *
     * @param page the Playwright page on the FRUITS screen
     * @param plu the short PLU code to type
     */
    private void typePluCode(Page page, String plu) {
        page.getByText("Saisir un code PLU").click();
        tapDigits(page.locator("#pluCodeNumpad"), plu);
        page.locator("#pluCodeValidate").click();
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
     * carrying its product identity (EAN and PLU, so the valuation engine sees
     * the line) and flagged price-embedded; it never merges because re-scanning
     * the SAME physical sticker is refused ({@code ÉTIQUETTE DÉJÀ SCANNÉE}).
     * The per-ticket sticker set is in-memory only, so a fresh ticket (the
     * assumed-limit "restart") accepts the same sticker again.
     */
    @Test
    void b06_etiquette_2x_prix_anti_double_scan() {
        Page page = freshSale();
        scan(PRICE_LABEL);
        page.getByText(LABEL_POMMES).waitFor();
        TicketState.TicketItem line = posState.ticket.items.get(0);
        Assertions.assertEquals(EAN_POMMES, line.ean,
                "a sticker line carries its product EAN (the engine must see the line)");
        Assertions.assertEquals(PLU_POMMES, line.plu,
                "a sticker line carries its product PLU");
        Assertions.assertTrue(line.priceEmbedded,
                "a sticker line is flagged price-embedded (its printed total is the line's truth)");
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
     * line, and the "NON RECONNU" tile at the root adds a reserved-EAN line at the
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
        // The ticket LINE, not the label anywhere on screen: the message zone of
        // the polled fragment renders its own text (a high-price warning names the
        // article), and only a line is a link.
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(LABEL_POELE.toUpperCase())).first().waitFor();
        List<TicketState.TicketItem> items = posState.ticket.items;
        Assertions.assertEquals(1, items.size(), "the drill-down add must create one line");
        Assertions.assertEquals(EAN_POELE, items.get(0).ean, "the added line must carry the chosen EAN");
        Assertions.assertEquals(0, items.get(0).quantity.compareTo(new BigDecimal("2")),
                "the typed quantity (2) must be applied");

        // Unknown product at a typed price -> the reserved unknown-item EAN.
        page.close();
        Page page2 = freshSale();
        page2.navigate(base.toString() + "manual");
        page2.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("NON RECONNU").setExact(true)).click();
        // KEYED IN CENTS: every amount pad of the register is a cents pad, so
        // 2,00 € is tapped 2-0-0 (the pad places the comma and carries none).
        tapDigits(page2.locator("#keyboardArea"), "200");
        page2.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("VALIDER").setExact(true)).click();
        page2.getByText("PRODUIT NON RECONNU").waitFor();
        TicketState.TicketItem unknown = posState.ticket.items.get(0);
        Assertions.assertEquals("3990000000003", unknown.ean,
                "an unknown-price line carries the reserved unknown-item EAN (pos.ean.unknown-item)");
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
     * endorsement): the line goes, the total is recomputed and the draft is
     * resynchronized on what the cart still holds.
     * <p>
     * TWO LINES AND NOT ONE, because the ONLY line of a ticket is no longer
     * cancellable: the anti-fraud guard refuses it and names the exit, the
     * ticket abandon (EXP-35, catalogued as C11). Emptying a cart by cancelling
     * its last line is therefore a gesture that no longer exists, and the draft
     * of an emptied cart is what B14 exercises through the abandon.
     */
    @Test
    void b12_annulation_de_ligne() {
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        scan(EAN_HUILE);
        // Waited through a LOCATOR and not through waitForFunction: the latter
        // polls on the animation frame, which a page the browser is not showing
        // no longer serves — the condition became true and the wait never saw
        // it. Every other wait of this suite is a locator for that reason.
        page.locator("a.receipt-item-link").nth(1).waitFor();
        // The current sale's draft id (drafts accumulate across scenarios, so
        // read the in-memory id, never the oldest OPEN draft in the database).
        Long draftId = posState.payment.ticketDbId;
        Assertions.assertNotNull(draftId, "scanning a line must persist the sale's draft");
        BigDecimal keptLineTotal = posState.ticket.items.get(0).getTotalPrice();
        // Select the last-entered line, then cancel it (last entered -> direct cancel).
        page.locator("a.receipt-item-link").last().click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER LIGNE").setExact(true)).click();
        // THE CANCELLED LINE LEAVES THE CART AND STAYS ON THE SCREEN, struck
        // through and no longer clickable (its href falls back to "#"): what was
        // cancelled is a fact of the sale the operator and the customer must
        // still be able to point at. The receipt therefore still carries two
        // links, and what is waited for is the strike-through, not a removal —
        // the cart itself is read on the state below, where the line is gone.
        page.locator(".receipt-item.cancelled").first().waitFor();
        Assertions.assertEquals(1, posState.ticket.items.size(), "the cancelled line must be removed");
        Assertions.assertEquals(EAN_BAGUETTE, posState.ticket.items.get(0).ean,
                "the line left standing must be the one that was not cancelled");
        Assertions.assertEquals(0, posState.ticket.totalAmount.compareTo(keptLineTotal),
                "the total must be recomputed on the single remaining line");
        // Draft resynchronized: the single write funnel keeps the row it already
        // owns and reconciles its lines on what the cart now holds.
        Assertions.assertEquals(draftId, posState.payment.ticketDbId,
                "a cart that still holds a line must keep its draft");
        Ticket.TicketStatus status = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).status);
        Assertions.assertEquals(Ticket.TicketStatus.OPEN, status,
                "the resynchronized draft of a non-empty cart stays OPEN");
        page.close();
    }

    /**
     * B13 — QUANTITÉ.
     * <p>
     * The QUANTITÉ modal applies a whole quantity (1-999) directly on a unit
     * line, with no endorsement; it is REFUSED on a weighed line and on a
     * negative one ({@code QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE}).
     * <p>
     * A WEIGHED LINE CARRIES A MEASUREMENT, not a quantity: the figure came
     * from the scale, and no key overwrites it (LC-02-13-15 — the register
     * cancels such a line whole and weighs again). The catalogue says the same:
     * "refusée sur ligne pesée/négative".
     */
    @Test
    void b13_quantite() {
        // --- Valid: a unit line accepts a typed quantity ---
        Page page = freshSale();
        scan(EAN_BAGUETTE);
        page.getByText(LABEL_BAGUETTE).waitFor();
        // THE LINE IS SELECTED FIRST. The QUANTITÉ key reads the explicit
        // selection and nothing else: with no line chosen the modal opens with
        // no target at all and VALIDER ARMS the quantity for the article about
        // to be rung (LC-02-13-04/05/06) instead of changing this one.
        page.locator("a.receipt-item-link").first().click();
        applyQuantityModal(page, "3");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("3")),
                "the typed quantity (3) must be applied on the unit line");
        page.close();

        // --- Refused on a weighed line: its quantity is a measurement ---
        Page page2 = freshSale();
        page2.navigate(base.toString() + "fruits");
        setScaleWeight("1.250");
        typePluCode(page2, PLU_POMMES);
        page2.getByText(LABEL_POMMES).waitFor();
        page2.locator("a.receipt-item-link").first().click();
        applyQuantityModal(page2, "2");
        Assertions.assertEquals("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE", posState.ticket.transientError,
                "a weighed line must refuse a quantity change");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("1.250")),
                "the weighed line must keep the weight the scale measured (1,250 kg)");
        page2.close();

        // --- Refused on a negative line ---
        Page page3 = freshSale();
        scan(DEPOSIT_VOUCHER);
        page3.getByText("BON DE CONSIGNE").waitFor();
        page3.locator("a.receipt-item-link").first().click();
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
        // Request the guarded whole-ticket cancellation, then endorse it. The key
        // lives in the TICKET menu since the five-menu rework: the bottom bar is
        // the menu selector, so the menu carrying the key is selected first.
        page.navigate(base.toString() + "action/menu/ticket");
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("ANNULER TICKET").setExact(true)).click();
        // THE KEY NO LONGER CANCELS: it opens the ABANDON screen (LC-04-04),
        // which asks the administered reason — mandatory as soon as the shop
        // offers a list, which the default does — and only then carries the
        // gesture to the manager's aval. Any reason of the list will do here;
        // which ones are offered and their refusals belong to the abandon
        // scenarios, not to this one.
        // THE LABEL IS THE BUTTON: the native radio is hidden by the screen's
        // own CSS (.reason-btn input { display: none }) and the label around it
        // carries the look and the touch target, as a finger needs.
        page.locator("label.reason-btn").first().click();
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("ABANDONNER LE TICKET").setExact(true)).click();
        approveEndorsementWithManager(page);
        page.getByText("CLIENT SUIVANT").waitFor();
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the cancelled ticket must clear the screen");
        // Database oracle: the draft is CANCELLED.
        Ticket.TicketStatus status = QuarkusTransaction.requiringNew()
                .call(() -> ((Ticket) Ticket.findById(draftId)).status);
        Assertions.assertEquals(Ticket.TicketStatus.CANCELLED, status,
                "the persisted draft must be marked CANCELLED");
        page.close();
    }

    /**
     * B15 — Ticket balance comptoir (préfixe 27).
     * <p>
     * A 27-prefix code with a broken EAN13 key falls SILENTLY through to the
     * unknown-code fallback ({@code CODE INCONNU}). A 27-prefix code with a valid
     * key is CLAIMED by {@code BalanceTicketScanHandler}, which — with no store
     * node wired in the autonomous e2e run — poses its own refusal ({@code
     * COMPTOIR NON RACCORDÉ - SAISIE MANUELLE}) that is NEVER overwritten by
     * {@code CODE INCONNU}, and adds no line. The "lines integrated" happy path
     * needs a store node and is reported as residue.
     */
    @Test
    void b15_ticket_balance_comptoir() {
        // --- Invalid key: silent fall-through to CODE INCONNU ---
        Page page = freshSale();
        scan(BALANCE_TICKET_BAD);
        Assertions.assertEquals("CODE INCONNU: " + BALANCE_TICKET_BAD, posState.ticket.transientError,
                "a bad 27-key must fall through to the unknown-code fallback");
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the rejected ticket must add no line");
        page.close();
        // --- Valid key: the handler claims it, its refusal wins over CODE INCONNU ---
        Page page2 = freshSale();
        scan(BALANCE_TICKET_VALID);
        Assertions.assertEquals("COMPTOIR NON RACCORDÉ - SAISIE MANUELLE", posState.ticket.transientError,
                "the balance handler's refusal must win, never overwritten by CODE INCONNU");
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "the un-integrated ticket must add no line");
        page2.close();
    }

    /**
     * B16 — Code GS1 Databar/DataMatrix — article.
     * <p>
     * A marked GS1 payload naming an article adds a line, priced by its element
     * string: an embedded amount (392n) sets a price-embedded unit line; an
     * embedded weight (310n) sells that weight; a carried count (30) sets the
     * quantity. A marked payload whose GTIN is absent yields {@code ARTICLE GS1
     * INTROUVABLE}; a forbidden product yields {@code PRODUIT INTERDIT À LA
     * VENTE}; an expired lot under a BLOCK alert yields {@code ARTICLE PERIME LE
     * <date>} and adds nothing; and a carried count while the quantity key is
     * armed yields {@code QUANTITÉ DÉJÀ PORTÉE PAR LE CODE GS1}. (Recall/short-date
     * branches need recall-seed / dynamic dates and are reported as residue.)
     */
    @Test
    void b16_code_gs1_article() {
        // --- Embedded amount (392n) → price-embedded unit line at 5,00 € ---
        Page page = freshSale();
        scan(GS1_HUILE + "(3902)0500");
        page.getByText(LABEL_HUILE).waitFor();
        TicketState.TicketItem embedded = posState.ticket.items.get(0);
        Assertions.assertTrue(embedded.priceEmbedded, "a 392n amount must flag the line price-embedded");
        Assertions.assertEquals(0, embedded.getTotalPrice().compareTo(new BigDecimal("5.00")),
                "the embedded amount (5,00 €) must be the line total");
        page.close();
        // --- Embedded weight (3103) → 1,250 kg weighed line ---
        Page pageW = freshSale();
        scan(GS1_POMMES + "(3103)001250");
        pageW.getByText(LABEL_POMMES).waitFor();
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("1.250")),
                "the embedded weight (3103=1,250 kg) must be the line quantity");
        pageW.close();
        // --- Carried count (30) → quantity 3 ---
        Page pageC = freshSale();
        scan(GS1_HUILE + "(30)3");
        pageC.getByText(LABEL_HUILE).waitFor();
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("3")),
                "a carried count (30) must set the line quantity");
        pageC.close();
        // --- Marked but absent GTIN → ARTICLE GS1 INTROUVABLE ---
        Page pageU = freshSale();
        scan(GS1_UNKNOWN);
        Assertions.assertTrue(posState.ticket.transientError != null
                        && posState.ticket.transientError.startsWith("ARTICLE GS1 INTROUVABLE ("),
                "a marked, absent GTIN must be reported introuvable, was: " + posState.ticket.transientError);
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "an absent GS1 article adds no line");
        pageU.close();
        // --- Forbidden product → PRODUIT INTERDIT À LA VENTE ---
        Page pageF = freshSale();
        scan(GS1_FORBIDDEN);
        Assertions.assertEquals("PRODUIT INTERDIT À LA VENTE", posState.ticket.transientError,
                "a forbidden GS1 article must be refused");
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "a forbidden article adds no line");
        pageF.close();
        // --- Expired lot under BLOCK → ARTICLE PERIME LE <date>, no line ---
        posSettings.store("gs1.expiry-alert", "BLOCK");
        try {
            Page pageE = freshSale();
            scan(GS1_HUILE + "(17)200101");
            Assertions.assertEquals("ARTICLE PERIME LE 01/01/2020", posState.ticket.transientError,
                    "an expired GS1 lot under BLOCK must be refused with its date");
            Assertions.assertTrue(posState.ticket.items.isEmpty(), "an expired blocked article adds no line");
            pageE.close();
        } finally {
            posSettings.store("gs1.expiry-alert", "NONE");
        }
        // --- Carried count while the quantity key is armed → QUANTITÉ DÉJÀ PORTÉE ---
        Page pageQ = freshSale();
        scan(EAN_HUILE);
        pageQ.getByText(LABEL_HUILE).waitFor();
        pageQ.navigate(base.toString() + "action/price-mod/quantity");
        pageQ.locator("#priceKbArea").waitFor();
        scan(GS1_HUILE + "(30)3");
        Assertions.assertEquals("QUANTITÉ DÉJÀ PORTÉE PAR LE CODE GS1", posState.ticket.transientError,
                "a carried count with the quantity key armed must be refused");
        pageQ.close();
    }

    /**
     * B17 — Code GS1 — règlement (hors phase paiement).
     * <p>
     * A GS1 gift cheque (GDTI 253) or coupon (GCN 255) whose type is NOT
     * parameterized is refused with {@code CHEQUE CADEAU GS1 NON PARAMETRE} /
     * {@code COUPON GS1 NON PARAMETRE}. Once the gift type IS parameterized,
     * scanning it in the cart phase (outside payment) is refused with {@code BON
     * VALABLE EN PHASE PAIEMENT}. (The expired-coupon branch needs a configured
     * coupon type with an expiry and is reported as residue.)
     */
    @Test
    void b17_code_gs1_reglement() {
        // --- Gift cheque, no type parameterized ---
        Page page = freshSale();
        scan("(253)9526000134367ABC");
        Assertions.assertEquals("CHEQUE CADEAU GS1 NON PARAMETRE", posState.ticket.transientError,
                "an unparameterized GS1 gift cheque must be refused");
        page.close();
        // --- Coupon, no type parameterized ---
        Page pageC = freshSale();
        scan("(255)952600013436712345");
        Assertions.assertEquals("COUPON GS1 NON PARAMETRE", posState.ticket.transientError,
                "an unparameterized GS1 coupon must be refused");
        pageC.close();
        // --- Gift type parameterized but scanned outside payment ---
        posSettings.store("gs1.gdti-coupon-types", "9526000:GIFT_VOUCHER");
        try {
            Page pageP = freshSale();
            scan("(253)9526000134367ABC");
            Assertions.assertEquals("BON VALABLE EN PHASE PAIEMENT", posState.ticket.transientError,
                    "a parameterized gift cheque scanned in the cart phase must be deferred to payment");
            pageP.close();
        } finally {
            posSettings.store("gs1.gdti-coupon-types", "");
        }
    }

    /**
     * B18 — Répéter article.
     * <p>
     * {@code RÉPÉTER ARTICLE} adds one to the last unit line and posts {@code
     * ARTICLE RÉPÉTÉ : <label>}; with no article it is {@code AUCUN ARTICLE À
     * RÉPÉTER}; on a weighed line {@code RÉPÉTITION IMPOSSIBLE : ARTICLE EN
     * PESÉE}; on a price-embedded line {@code RÉPÉTITION IMPOSSIBLE : ARTICLE PRIX
     * EMBARQUÉ}.
     */
    @Test
    void b18_repeter_article() {
        // --- No article yet ---
        Page page = freshSale();
        page.navigate(base.toString() + "action/repeat");
        Assertions.assertEquals("AUCUN ARTICLE À RÉPÉTER", posState.ticket.transientError,
                "repeat with an empty cart must be refused");
        // --- A unit line: +1 and the repeated notice ---
        scan(EAN_HUILE);
        // Waited on the RECEIPT LINE and not on the label: the message zone
        // names the article too ("ARTICLE RÉPÉTÉ : <label>"), so a bare text
        // match resolves two elements and Playwright's strict mode refuses it.
        page.locator("a.receipt-item-link").first().waitFor();
        page.navigate(base.toString() + "action/repeat");
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("2")),
                "repeat must add one to the last unit line");
        Assertions.assertEquals("ARTICLE RÉPÉTÉ : " + LABEL_HUILE, posState.ticket.transientError,
                "repeat must post the repeated-article notice");
        page.close();
        // --- A weighed line: repetition impossible ---
        Page pageW = freshSale();
        scan(WEIGHT_LABEL_VALID);
        pageW.getByText(LABEL_POMMES).waitFor();
        pageW.navigate(base.toString() + "action/repeat");
        Assertions.assertEquals("RÉPÉTITION IMPOSSIBLE : ARTICLE EN PESÉE", posState.ticket.transientError,
                "a weighed line cannot be repeated");
        pageW.close();
        // --- A price-embedded line WITHOUT a PLU: repetition impossible ---
        // (a 2x price sticker also carries a PLU, so it would hit the weighed
        // branch first; a GS1 392n embedded amount on a unit product is
        // price-embedded with no PLU, which is the branch under test.)
        Page pageE = freshSale();
        scan(GS1_HUILE + "(3902)0500");
        pageE.getByText(LABEL_HUILE).waitFor();
        pageE.navigate(base.toString() + "action/repeat");
        Assertions.assertEquals("RÉPÉTITION IMPOSSIBLE : ARTICLE PRIX EMBARQUÉ", posState.ticket.transientError,
                "a price-embedded line cannot be repeated");
        pageE.close();
    }

    /**
     * B19 — Marquage « À enlever ».
     * <p>
     * With a line selected, {@code /action/collect} toggles its to-collect mark
     * ({@code ARTICLE MARQUÉ À ENLEVER} / {@code MARQUAGE À ENLEVER RETIRÉ}); with
     * nothing selected it arms the NEXT article ({@code PROCHAIN ARTICLE À
     * ENLEVER}), and two articles at different destinations do not merge.
     */
    @Test
    void b19_marquage_a_enlever() {
        // --- Toggle on a selected line ---
        Page page = freshSale();
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        // SELECTED BY UID. The route takes the line's identity and no longer a
        // page offset (P2), so "0" selects nothing — and an unselected collect
        // arms the NEXT article, which is the section below.
        page.navigate(base.toString() + "action/select/" + posState.ticket.items.get(0).uid);
        page.navigate(base.toString() + "action/collect");
        Assertions.assertEquals("ARTICLE MARQUÉ À ENLEVER", posState.ticket.transientError,
                "toggling a selected line must mark it to collect");
        Assertions.assertTrue(posState.ticket.items.get(0).toCollect, "the line must carry the to-collect flag");
        // The collect action clears the selection, so re-select before toggling back.
        page.navigate(base.toString() + "action/select/" + posState.ticket.items.get(0).uid);
        page.navigate(base.toString() + "action/collect");
        Assertions.assertEquals("MARQUAGE À ENLEVER RETIRÉ", posState.ticket.transientError,
                "toggling again must clear the mark");
        Assertions.assertFalse(posState.ticket.items.get(0).toCollect, "the flag must be cleared");
        page.close();
        // --- Arm the next article, and non-merge across destinations ---
        Page page2 = freshSale();
        scan(EAN_HUILE);
        page2.getByText(LABEL_HUILE).waitFor();
        page2.navigate(base.toString() + "action/collect");
        Assertions.assertEquals("PROCHAIN ARTICLE À ENLEVER", posState.ticket.transientError,
                "with nothing selected, collect must arm the next article");
        // The armed next scan lands to-collect: it does NOT merge with the plain
        // line0 of the same EAN (different destinations) — two lines, one marked.
        scan(EAN_HUILE);
        Assertions.assertEquals(2, posState.ticket.items.size(),
                "a to-collect line must not merge with a plain line of the same EAN");
        Assertions.assertTrue(posState.ticket.items.get(0).toCollect != posState.ticket.items.get(1).toCollect,
                "exactly one of the two same-EAN lines must carry the to-collect mark");
        page2.close();
    }

    /**
     * B20 — Contrôle d'âge.
     * <p>
     * Scanning an age-restricted article above the verified threshold opens the
     * age-check modal; CLIENT MAJEUR replays the code (line added), journals
     * {@code AGE_CHECK_CONFIRMED} and remembers the threshold; REFUSER journals
     * {@code AGE_CHECK_REFUSED} and posts {@code VENTE REFUSÉE - CONTRÔLE D'ÂGE}
     * with no line.
     */
    @Test
    void b20_controle_age() {
        // --- Confirm: the line is added, the check journaled ---
        Page page = freshSale();
        scan(EAN_WINE);
        Assertions.assertTrue(posState.ageCheck.active, "an age-restricted article must open the age check");
        Assertions.assertEquals(18, posState.ageCheck.threshold, "the wine threshold must be 18");
        long confirmedBefore = eventCount(TechnicalEvent.EventType.AGE_CHECK_CONFIRMED);
        page.navigate(base.toString() + "action/age-check/confirm");
        page.getByText(LABEL_WINE).waitFor();
        Assertions.assertFalse(posState.ageCheck.active, "confirming must close the modal");
        Assertions.assertEquals(1, posState.ticket.items.size(), "confirming must add the age-restricted line");
        Assertions.assertEquals(confirmedBefore + 1, eventCount(TechnicalEvent.EventType.AGE_CHECK_CONFIRMED),
                "confirming must journal AGE_CHECK_CONFIRMED");
        page.close();
        // --- Refuse: no line, the refusal journaled ---
        Page page2 = freshSale();
        scan(EAN_WINE);
        Assertions.assertTrue(posState.ageCheck.active, "the age check must reopen on a fresh cart");
        long refusedBefore = eventCount(TechnicalEvent.EventType.AGE_CHECK_REFUSED);
        page2.navigate(base.toString() + "action/age-check/refuse");
        Assertions.assertEquals("VENTE REFUSÉE - CONTRÔLE D'ÂGE", posState.ticket.transientError,
                "refusing must post the refusal message");
        Assertions.assertTrue(posState.ticket.items.isEmpty(), "refusing must add no line");
        Assertions.assertEquals(refusedBefore + 1, eventCount(TechnicalEvent.EventType.AGE_CHECK_REFUSED),
                "refusing must journal AGE_CHECK_REFUSED");
        page2.close();
    }

    /**
     * B21 — Invite prix / quantité à saisir.
     * <p>
     * A product carrying the PRICE_TO_ENTER attribute opens the {@code PRIX À
     * SAISIR} modal: a value ≤ 0 is {@code VALEUR INVALIDE}, ANNULER is {@code
     * SAISIE ABANDONNÉE}, a valid value adds the line at that price. A product
     * carrying QUANTITY_TO_ENTER opens {@code QUANTITÉ À SAISIR}. The attributes
     * are set in the database and removed afterwards.
     */
    @Test
    void b21_invite_prix_quantite() {
        // --- PRICE_TO_ENTER: invalid, cancel, then a valid price ---
        setProductAttribute(EAN_MIEL, "PRICE_TO_ENTER", "true");
        try {
            Page page = freshSale();
            scan(EAN_MIEL);
            Assertions.assertTrue(posState.entryPrompt.active, "a price-to-enter product must open the entry prompt");
            Assertions.assertEquals("op.prixSaisir", posState.entryPrompt.getTitleKey(),
                    "the modal must ask for a price");
            submitEntry("0");
            Assertions.assertEquals("VALEUR INVALIDE", posState.ticket.transientError, "a price ≤ 0 must be refused");
            scan(EAN_MIEL);
            page.navigate(base.toString() + "action/entry/cancel");
            Assertions.assertEquals("SAISIE ABANDONNÉE", posState.ticket.transientError, "cancelling must abandon the entry");
            scan(EAN_MIEL);
            submitEntry("3");
            Assertions.assertEquals(1, posState.ticket.items.size(), "a valid price must add the line");
            Assertions.assertEquals(0, posState.ticket.items.get(0).unitPrice.compareTo(new BigDecimal("3")),
                    "the typed price must be applied");
            page.close();
        } finally {
            removeProductAttribute(EAN_MIEL, "PRICE_TO_ENTER");
        }
        // --- QUANTITY_TO_ENTER: the modal asks for a quantity ---
        setProductAttribute(EAN_MIEL, "QUANTITY_TO_ENTER", "true");
        try {
            Page pageQ = freshSale();
            scan(EAN_MIEL);
            Assertions.assertTrue(posState.entryPrompt.active,
                    "a quantity-to-enter product must open the entry prompt");
            Assertions.assertEquals("op.quantiteSaisir", posState.entryPrompt.getTitleKey(),
                    "the modal must ask for a quantity");
            pageQ.close();
        } finally {
            removeProductAttribute(EAN_MIEL, "QUANTITY_TO_ENTER");
        }
    }

    /**
     * B22 — Quantité armée pour le prochain article.
     * <p>
     * {@code POUR LE PROCHAIN ARTICLE} arms a quantity ({@code QUANTITÉ <n> —
     * SAISISSEZ L'ARTICLE}); {@code n ≤ 0} is {@code QUANTITÉ INVALIDE}; the armed
     * quantity is consumed by the next unit line.
     */
    @Test
    void b22_quantite_armee_prochain_article() {
        // --- Invalid armed quantity ---
        Page page = freshSale();
        armQuantity("0");
        Assertions.assertEquals("QUANTITÉ INVALIDE", posState.ticket.transientError,
                "an armed quantity ≤ 0 must be refused");
        // --- Valid arming, consumed by the next unit line ---
        armQuantity("3");
        Assertions.assertEquals("QUANTITÉ 3 — SAISISSEZ L'ARTICLE", posState.ticket.transientError,
                "a valid arming must post the arming notice");
        scan(EAN_HUILE);
        page.getByText(LABEL_HUILE).waitFor();
        Assertions.assertEquals(0, posState.ticket.items.get(0).quantity.compareTo(new BigDecimal("3")),
                "the armed quantity must be consumed by the next unit line");
        page.close();
    }

    /**
     * B23 — Clé EAN13.
     * <p>
     * With the EAN13 check-digit control enabled, a 13-digit code with a wrong key
     * is refused with {@code CODE EAN INVALIDE} and never reaches the unknown-code
     * handler. The control flag is restored afterwards.
     */
    @Test
    void b23_cle_ean13() {
        posSettings.store("scan.ean13-check-digit", "true");
        try {
            Page page = freshSale();
            scan(EAN13_BAD_KEY);
            Assertions.assertEquals("CODE EAN INVALIDE", posState.ticket.transientError,
                    "a 13-digit code with a wrong key must be refused as CODE EAN INVALIDE");
            Assertions.assertTrue(posState.ticket.items.isEmpty(), "an invalid-key code adds no line");
            page.close();
        } finally {
            posSettings.store("scan.ean13-check-digit", "false");
        }
    }

    /**
     * B24 — Étiquette balance en devise non euro.
     * <p>
     * An administered price barcode range declared in a currency that is NOT
     * parameterized is refused with {@code DEVISE <ISO> NON PARAMÉTRÉE}. The range
     * is inserted for the scenario and removed afterwards.
     */
    @Test
    void b24_etiquette_devise_non_euro() {
        Long rangeId = insertForeignCurrencyRange();
        try {
            Page page = freshSale();
            scan("2640200001500");
            Assertions.assertTrue(posState.ticket.transientError != null
                            && posState.ticket.transientError.contains("DEVISE FRF NON PARAMÉTRÉE"),
                    "a range in an unconfigured currency must be refused, was: " + posState.ticket.transientError);
            Assertions.assertTrue(posState.ticket.items.isEmpty(), "the refused label must add no line");
            page.close();
        } finally {
            QuarkusTransaction.requiringNew().run(() -> ArticleBarcodeRange.deleteById(rangeId));
        }
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
        // /lock FIRST (logs out, empties the one-shot mailbox), THEN the badge.
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
            // THE REGIME IS READ, NOT BUILT. A transient VatRate hung on a
            // persisted Price is not cascaded, and the flush refuses the graph
            // ("references an unsaved transient instance"). The seed writes the
            // five regimes before any price names one: number 1 is the 20 %.
            np.vat = VatRate.find("number", 1).firstResult();
            np.priority = 1;
            np.startDateTime = LocalDateTime.of(2026, 1, 12, 0, 0);
            np.endDateTime = null;
            np.persist();
            return np.id;
        });
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
            if (key < '0' || key > '9') {
                continue;
            }
            keypad.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName(String.valueOf(key)).setExact(true)).click();
        }
    }

    // --- Lot-2 (B15–B24) additions: journal, entry/arm endpoints, DB fixtures ---

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
     * Submits the entry prompt ({@code POST /action/entry/confirm}) with a raw
     * value — the path the prompt keypad posts to.
     *
     * @param rawValue the value to submit
     */
    private void submitEntry(String rawValue) {
        context.request().post(base.toString() + "action/entry/confirm", RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setData("rawValue=" + rawValue));
    }

    /**
     * Arms a quantity for the next article ({@code POST /action/price-mod/arm}).
     *
     * @param rawValue the quantity to arm
     */
    private void armQuantity(String rawValue) {
        context.request().post(base.toString() + "action/price-mod/arm", RequestOptions.create()
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setData("rawValue=" + rawValue));
    }

    /**
     * Sets a product attribute in the database (the referential vector for the
     * entry prompts) and flushes it.
     *
     * @param ean the product EAN
     * @param key the attribute code
     * @param value the attribute value
     */
    private void setProductAttribute(String ean, String key, String value) {
        QuarkusTransaction.requiringNew().run(() -> {
            Product p = Product.find("ean = ?1", ean).firstResult();
            p.attributes.put(key, value);
            p.persist();
        });
    }

    /**
     * Removes a product attribute, restoring the seed for the shared boot.
     *
     * @param ean the product EAN
     * @param key the attribute code to remove
     */
    private void removeProductAttribute(String ean, String key) {
        QuarkusTransaction.requiringNew().run(() -> {
            Product p = Product.find("ean = ?1", ean).firstResult();
            p.attributes.remove(key);
            p.persist();
        });
    }

    /**
     * Inserts an active PRICE barcode range declared in FRF (a non-euro,
     * unparameterized currency) whose article segment names PLU 4020 — the B24
     * fixture. The caller deletes it afterwards.
     *
     * @return the id of the inserted range
     */
    private Long insertForeignCurrencyRange() {
        return QuarkusTransaction.requiringNew().call(() -> {
            ArticleBarcodeRange r = new ArticleBarcodeRange();
            r.code = "TEST_FRF_26";
            r.label = "Test FRF 26";
            r.active = true;
            r.priority = 1;
            r.prefix = "26";
            r.codeLength = 13;
            r.codeKind = CouponField.Kind.NUMERIC;
            r.articlePosition = 2;
            r.articleLength = 4;
            r.valueSource = ArticleBarcodeRange.ValueSource.PRICE;
            r.valuePosition = 6;
            r.valueLength = 6;
            r.valueDecimals = 2;
            r.currency = CouponField.PriceCurrency.FRF;
            r.checkDigit = false;
            r.matchPattern = "^26\\d{11}$";
            r.persist();
            return r.id;
        });
    }

}
