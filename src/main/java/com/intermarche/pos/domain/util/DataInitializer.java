package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.*;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Development/test data seeder.
 * <p>
 * Restricted to the dev and test build profiles: this bean wipes and reloads
 * the referential tables at startup, which must never run in production
 * (each reboot would purge the register's database). Production
 * referentials are fed through the CSV imports on the store node and pulled
 * by the registers (phase 6 centralized referentials).
 * <p>
 * Scope of the wipe — referentials ONLY, in foreign-key order (prices before
 * products, products before families). Fiscal history is deliberately kept
 * across dev restarts: tickets, sessions, refunds, counters and the journal
 * survive, so the restart recovery, the fiscal chaining and the Z reports
 * can be exercised over multiple boots.
 * <p>
 * Seeded test credentials (badge / login / PIN / role):
 * <ul>
 *   <li>11111111 / mcurie / 1111 / MANAGER</li>
 *   <li>22222222 / aeinstein / 2222 / PICKER (role unused since the picking
 *       abandonment)</li>
 *   <li>00000000 / manager / 0000 / ADMIN</li>
 *   <li>12341234 / jdupont / 1234 / CASHIER</li>
 * </ul>
 * Catalog conventions: fruits and vegetables carry real-world 4xxx PLU codes
 * (these are what the 2x scale labels of the simulator resolve against);
 * bakery and dairy use the fictional 366xxx EAN range; every price is at the
 * 5.5% food VAT rate; two forbidden-to-sale products exercise the sale
 * guard. Voucher patterns are EXAMPLES (real Intermarché formats are not
 * public); STORE_VOUCHER (50 + 12 digits) is also the format the refund
 * vouchers print into, closing the payment loop, and DEPOSIT_VOUCHER (298 +
 * serial + cents) feeds the deposit-return line at scan time. Store code
 * "0101" is the storeCode travelling in the sync payloads.
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class DataInitializer {

    /**
     * Wipes and reseeds the referentials at startup (dev/test only), in
     * foreign-key order; fiscal tables are untouched on purpose.
     *
     * @param ev the Quarkus startup event
     */
    @Transactional
    void onStart(@Observes StartupEvent ev) {
        Employee.deleteAll();
        Price.deleteAll();
        Product.deleteAll();
        ProductFamily.deleteAll();
        CouponType.deleteAll();

        createStore();
        loadEmployees();
        loadProductsAndFamilies();
        loadCouponTypes();
    }

    /**
     * Seeds the test employees (credentials in the class documentation).
     */
    private void loadEmployees() {
        // Format : createEmployee(BadgeID 8chiffres, PIN 4chiffres, Prénom, Nom, Rôle)
        createEmployee("11111111", "mcurie", "1111", "Marie", "Curie", Employee.EmployeeRole.MANAGER);
        createEmployee("22222222", "aeinstein", "2222", "Albert", "Einstein", Employee.EmployeeRole.PICKER);
        createEmployee("00000000", "manager", "0000", "Le", "Manager", Employee.EmployeeRole.ADMIN);
        createEmployee("12341234",  "jdupont","1234", "Jean", "Dupont", Employee.EmployeeRole.CASHIER);
    }

    /**
     * Seeds the family tree and the catalog: weighed fruits and vegetables
     * with real 4xxx PLUs, bakery and dairy on the fictional 366xxx EAN
     * range, and two forbidden-to-sale fixtures.
     */
    private void loadProductsAndFamilies() {
        ProductFamily fruits = createFamily("FRUITS", "Fruits");
        ProductFamily legumes = createFamily("LEGUMES", "Légumes");
        ProductFamily boulangerie = createFamily("BOULANGERIE", "Boulangerie");
        ProductFamily cremerie = createFamily("CREMERIE", "Crèmerie");

        // FRUITS & LÉGUMES
        createAndLinkProduct(fruits, "Banane", "4011", "3400401100007", "🍌", 1.99);
        createAndLinkProduct(fruits, "Pomme Golden", "4020", "3400401200009", "🍎", 2.49);
        createAndLinkProduct(fruits, "Pomme Gala", "4022", "3400402200008", "🍏", 2.29);
        createAndLinkProduct(fruits, "Pomme Pink Lady", "4101", "3400410100000", "🍎", 3.50);
        createAndLinkProduct(fruits, "Poire", "4030", "3400403000002", "🍐", 3.10);
        createAndLinkProduct(fruits, "Orange", "4012", "3400401200004", "🍊", 2.80);
        createAndLinkProduct(fruits, "Citron", "4033", "3400403300005", "🍋", 3.50);
        createAndLinkProduct(fruits, "Fraise", "4042", "3400404200006", "🍓", 5.90);
        createAndLinkProduct(fruits, "Avocat", "4046", "3400404600003", "🥑", 1.60);

        createAndLinkProduct(legumes, "Tomate Ronde", "4060", "3400406000005", "🍅", 3.20);
        createAndLinkProduct(legumes, "Tomate Cœur", "4061", "3400406100002", "🍅", 4.50);
        createAndLinkProduct(legumes, "Courgette", "4064", "3400406400006", "🥒", 2.40);
        createAndLinkProduct(legumes, "Carotte", "4068", "3400406800003", "🥕", 1.60);
        createAndLinkProduct(legumes, "Pomme de Terre", "4069", "3400406900000", "🥔", 1.20);
        createAndLinkProduct(legumes, "Salade", "4074", "3400407400005", "🥬", 1.10);

        // Produits interdits à la vente (exemples de test)
        createForbiddenProduct(legumes, "Champignon sauvage non contrôlé", "4099", "3400409900001", "🍄", 4.00);
        createForbiddenProduct(cremerie, "Lot rappelé (retrait conso)", null, "3660000099999", "⛔", 5.00);

        // BOULANGERIE
        ProductFamily boulPains = createFamily("BOUL_PAINS", "Pains");
        boulangerie.productFamilies.add(boulPains);
        createAndLinkProduct(boulPains, "Baguette", null, "3660000000010", "🥖", 1.30);
        createAndLinkProduct(boulPains, "Pain Campagne", null, "3660000000027", "🍞", 2.40);
        createAndLinkProduct(boulPains, "Pain Complet", null, "3660000000034", "🍞", 2.60);
        createAndLinkProduct(boulPains, "Croissant", null, "3660000000041", "🥐", 1.20);
        createAndLinkProduct(boulPains, "Pain Chocolat", null, "3660000000058", "🥐", 1.40);
        createAndLinkProduct(boulPains, "Brioche", null, "3660000000065", "🍞", 8.50);

        // CREMERIE
        ProductFamily cremLait = createFamily("CREM_LAIT", "Laits");
        cremerie.productFamilies.add(cremLait);
        createAndLinkProduct(cremLait, "Lait 1L", null, "3660000000072", "🥛", 1.10);
        createAndLinkProduct(cremLait, "Œufs x6", null, "3660000000089", "🥚", 2.30);
        createAndLinkProduct(cremLait, "Beurre", null, "3660000000096", "🧈", 2.80);
        createAndLinkProduct(cremLait, "Comté", null, "3660000000102", "🧀", 18.00);
        createAndLinkProduct(cremLait, "Camembert", null, "3660000000119", "🧀", 2.50);
        createAndLinkProduct(cremLait, "Yaourt x4", null, "3660000000126", "🥛", 1.50);

        fruits.persist();
        legumes.persist();
        boulangerie.persist();
        cremerie.persist();
    }

    /**
     * Seeds the voucher types: encoded payment vouchers (gift, store,
     * loyalty), the manual-amount Catalina, the deposit-return type
     * (negative line at scan) and the numberless generic voucher. Priorities
     * drive the resolution order at scan time (lower first).
     */
    private void loadCouponTypes() {
        // NB : les formats réels des bons Intermarché ne sont pas publics ;
        //      les motifs ci-dessous sont des exemples à adapter au format réel.
        // amountPattern : 1er groupe capturant = montant en centimes (si ENCODED).

        // Chèque cadeau : 10 chiffres, 4 derniers = montant en centimes.
        createCouponType("GIFT_VOUCHER", "Chèque cadeau", "^\\d{10}$",
                CouponType.AmountSource.ENCODED, "\\d{6}(\\d{4})$", 10);

        // Bon enseigne : préfixe 50 + 12 chiffres, 4 derniers = montant en centimes.
        createCouponType("STORE_VOUCHER", "Bon enseigne", "^50\\d{12}$",
                CouponType.AmountSource.ENCODED, "\\d{10}(\\d{4})$", 20);

        // Chèque fidélité : préfixe 789 + 12 chiffres, 4 derniers = montant en centimes.
        createCouponType("LOYALTY_CHEQUE", "Chèque fidélité", "^789\\d{12}$",
                CouponType.AmountSource.ENCODED, "\\d{11}(\\d{4})$", 30);

        // Catalina : préfixe 0482 + 10 chiffres ; montant non déductible -> saisie manuelle.
        createCouponType("CATALINA", "Catalina", "^0482\\d{10}$",
                CouponType.AmountSource.MANUAL, null, 40);

        // Deposit-return vouchers (reverse vending machine): 298 + 6-digit
        // serial + 4-digit amount in cents. Scanned on the sale screen, they
        // become a negative ticket line, never a payment.
        createDepositCouponType("DEPOSIT_VOUCHER", "Bon de consigne", "^298\\d{10}$",
                "^298\\d{6}(\\d{4})$", 5);

        // Bon générique / éphémère : aucun numéro -> montant seul.
        createCouponType("GENERIC", "Bon générique", "",
                CouponType.AmountSource.MANUAL, null, 100);
    }

    /**
     * Creates and persists a test employee; the PIN is stored hashed, like
     * production credentials.
     *
     * @param badgeId the 8-digit physical badge identifier
     * @param loginName the login
     * @param pin the 4-digit PIN, hashed before storage
     * @param firstName the first name
     * @param lastName the last name
     * @param role the employee role
     */
    private void createEmployee(String badgeId, String loginName, String pin, String firstName, String lastName, Employee.EmployeeRole role) {
        Employee emp = new Employee();
        emp.badgeId = badgeId; // Identifiant physique du badge (8 chiffres)
        emp.loginName = loginName;
        emp.password = Employee.hashPassword(pin); // Hash du PIN (4 chiffres)
        emp.firstName = firstName;
        emp.lastName = lastName;
        emp.email = firstName.toLowerCase() + "." + lastName.toLowerCase() + "@test.com";
        emp.role = role;
        emp.active = true;
        emp.persist();
    }

    /**
     * Creates and persists a product family.
     *
     * @param code the family code
     * @param description the display description
     * @return the persisted family
     */
    private ProductFamily createFamily(String code, String description) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        f.description = description;
        f.persist();
        return f;
    }

    /**
     * Creates a sellable unit product with its current price and links it to
     * a family.
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param plu the PLU code (weighed sale), or null
     * @param ean the EAN code
     * @param icon the display icon
     * @param priceTTC the price including tax
     */
    private void createAndLinkProduct(ProductFamily family, String name, String plu, String ean, String icon, double priceTTC) {
        Product p = new Product();
        p.name = name;
        p.plu = plu;
        p.ean = ean;
        p.icon = icon;
        p.productType = ProductType.UNIT;
        p.unitName = "pc";
        p.active = true;
        p.persist();

        createPrice(p, priceTTC, "5.5");
        family.products.add(p);
    }

    /**
     * Creates a product flagged as forbidden to sale and links it to a family.
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param plu the PLU code, or null
     * @param ean the EAN code
     * @param icon the display icon
     * @param priceTTC the price including tax
     */
    private void createForbiddenProduct(ProductFamily family, String name, String plu, String ean, String icon, double priceTTC) {
        Product p = new Product();
        p.name = name;
        p.plu = plu;
        p.ean = ean;
        p.icon = icon;
        p.productType = ProductType.UNIT;
        p.unitName = "pc";
        p.active = true;
        p.forbiddenToSale = true;
        p.persist();

        createPrice(p, priceTTC, "5.5");
        family.products.add(p);
    }

    /**
     * Creates the current price of a product: tax-included price as given,
     * tax-excluded derived at scale 4, VAT rate given as a percentage.
     *
     * @param product the priced product
     * @param priceTTC the price including tax
     * @param vatRate the VAT rate as a percentage (e.g. "5.5")
     */
    private void createPrice(Product product, double priceTTC, String vatRate) {
        Price p = new Price();
        p.product = product;
        p.priceIncludingTax = new BigDecimal(priceTTC);
        p.vatRate = new BigDecimal(vatRate).divide(new BigDecimal("100"));
        p.priceExcludingTax = p.priceIncludingTax.divide(BigDecimal.ONE.add(p.vatRate), 4, RoundingMode.HALF_UP);
        p.priority = 0;
        p.persist();
    }

    /**
     * Creates and persists a payment coupon type.
     *
     * @param code the stable technical code of the type
     * @param label the label shown to the cashier
     * @param matchPattern the recognition regex, or empty for a numberless type
     * @param amountSource whether the amount is encoded in the number or typed
     * @param amountPattern the extraction regex (first group = cents), or null
     * @param priority the matching priority (lower runs first)
     */
    private void createCouponType(String code, String label, String matchPattern,
                                  CouponType.AmountSource amountSource, String amountPattern, int priority) {
        CouponType ct = new CouponType();
        ct.code = code;
        ct.label = label;
        ct.matchPattern = matchPattern;
        ct.amountSource = amountSource;
        ct.amountPattern = amountPattern;
        ct.active = true;
        ct.priority = priority;
        ct.persist();
    }

    /**
     * Creates and persists a deposit-return coupon type (negative ticket line
     * at scan time); the amount is necessarily encoded in the number.
     *
     * @param code the stable technical code of the type
     * @param label the label shown on the ticket line
     * @param matchPattern the recognition regex
     * @param amountPattern the extraction regex (first group = cents)
     * @param priority the matching priority (lower runs first)
     */
    private void createDepositCouponType(String code, String label, String matchPattern,
                                         String amountPattern, int priority) {
        CouponType ct = new CouponType();
        ct.code = code;
        ct.label = label;
        ct.matchPattern = matchPattern;
        ct.amountSource = CouponType.AmountSource.ENCODED;
        ct.amountPattern = amountPattern;
        ct.active = true;
        ct.priority = priority;
        ct.depositLine = true;
        ct.persist();
    }

    /**
     * Creates the single local store; its code is the storeCode of the sync
     * payloads.
     */
    private void createStore() {
        Store s = new Store();
        s.name = "Intermarché Triffouilly";
        s.code = "0101";
        s.address = new Address();
        s.address.streetLine1 = "1 rue du Rond Carré";
        s.address.postalCode = "03544";
        s.address.country = "France";
        s.address.city = "Triffouilly";
        s.persist();
    }
}
