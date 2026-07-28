package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.*;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Development/test data seeder.
 * <p>
 * Restricted to the dev and test build profiles: this bean wipes and reloads
 * the referential tables at startup, which must never run in production
 * (each reboot would purge the register's database). Production
 * referentials are fed through the CSV imports.
 * <p>
 * VALUATION-ENGINE COMPATIBILITY (phase 7): the catalog seeded here is the
 * MIRROR of the valuation engine's own seed (its ProductImporterClient /
 * PriceImporterClient / ProductFamilyImporterClient / StoreImporterClient
 * files are the reference) so both systems resolve the SAME base prices:
 * same store {@code 0101}, same EANs {@code 33000000000xx}, same
 * HT/TTC/VAT/priority price rows (DEFAULT usage only — BASE_FOR_DISCOUNT is
 * an engine-internal concept), same start date 2026-01-12, same promo
 * layers (EANs ...001, ...002 and ...020 carry a priority-1 price both
 * sides). PLUs and icons are REGISTER-LOCAL concepts added on top (the EAN
 * is the shared key); meal-voucher eligibility and family flags live in the
 * ENGINE only. The two forbidden test products keep non-engine EANs on
 * purpose: they can never reach a basket.
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class DataInitializer {

    /** Price window start shared with the engine's seed (12 Jan 2026). */
    private static final LocalDateTime PRICE_START = LocalDateTime.of(2026, 1, 12, 0, 0, 0);

    /**
     * Wipes and reloads the referential tables at startup (dev/test only).
     *
     * @param ev the startup event
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
     * Seeds the test employees (badge, login, PIN, role).
     */
    private void loadEmployees() {
        // Format : createEmployee(BadgeID 8chiffres, PIN 4chiffres, Prénom, Nom, Rôle)
        createEmployee("11111111", "mcurie", "1111", "Marie", "Curie", Employee.EmployeeRole.MANAGER);
        createEmployee("22222222", "aeinstein", "2222", "Albert", "Einstein", Employee.EmployeeRole.PICKER);
        createEmployee("00000000", "manager", "0000", "Le", "Manager", Employee.EmployeeRole.ADMIN);
        createEmployee("12341234",  "jdupont","1234", "Jean", "Dupont", Employee.EmployeeRole.CASHIER);
    }

    /**
     * Seeds the engine-mirrored catalog: 33 products, their DEFAULT price
     * rows for store 0101 (promo layers included) and the engine's family
     * tree, plus two register-only forbidden test products.
     */
    private void loadProductsAndFamilies() {
        // --- Engine family tree (ProductFamilyImporterClient mirror) ---
        ProductFamily pommes = createFamily("POMMES", "Pommes à croquer");
        ProductFamily racines = createFamily("RACINES", "Légumes racines");
        ProductFamily fruits = createFamily("FRUITS", "Rayon Fruits");
        ProductFamily legumes = createFamily("LEGUMES", "Rayon Légumes");
        ProductFamily eaux = createFamily("EAU_MINERALE", "Eaux Minérales");
        ProductFamily sodas = createFamily("SODAS", "Sodas");
        ProductFamily boissons = createFamily("BOISSONS", "Rayon Boissons");
        ProductFamily alimentaire = createFamily("ALIMENTAIRE", "Rayon Alimentaire");
        ProductFamily cuisson = createFamily("CUISSON", "Instruments de Cuisine");
        // Register-only shelf for the engine products without an engine family
        ProductFamily epicerie = createFamily("EPICERIE", "Épicerie & Divers");

        fruits.productFamilies.add(pommes);
        legumes.productFamilies.add(racines);
        boissons.productFamilies.add(eaux);
        boissons.productFamilies.add(sodas);
        alimentaire.productFamilies.add(fruits);
        alimentaire.productFamilies.add(legumes);
        alimentaire.productFamilies.add(boissons);

        // --- Engine catalog (ProductImporterClient mirror; PLU/icon = register-local) ---
        Product p01 = createProduct(pommes, "Pommes Golden", "Pommes fraîches bio", "Brand A", "4001", "3300000000001", "🍎", "1.000", "2.500", ProductType.WEIGHT, "kg");
        Product p02 = createProduct(epicerie, "Lait UHT 1L", "Lait demi-écrémé", "Brand B", null, "3300000000002", "🥛", "1.000", "1.000", ProductType.UNIT, "L");
        Product p03 = createProduct(epicerie, "Baguette Tradition", "Pain de tradition", "Brand C", null, "3300000000003", "🥖", "0.250", "0.600", ProductType.UNIT, "kg");
        Product p04 = createProduct(pommes, "Café Grains 500g", "Café moulu arabica", "Brand D", null, "3300000000004", "☕", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p05 = createProduct(epicerie, "Pâtes Penne 500g", "Pâtes alimentaires", "Brand E", "4005", "3300000000005", "🍝", "0.500", "1.250", ProductType.WEIGHT, "kg");
        Product p06 = createProduct(epicerie, "Huile d'Olive 1L", "Huile vierge extra", "Brand F", null, "3300000000006", "🫒", "1.000", "1.000", ProductType.UNIT, "L");
        Product p07 = createProduct(eaux, "Eau Minérale 1.5L", "Eau de source", "Brand G", null, "3300000000007", "💧", "1.500", "1.500", ProductType.UNIT, "L");
        Product p08 = createProduct(epicerie, "Jambon Blanc 100g", "Tranches de jambon", "Brand H", "4008", "3300000000008", "🥓", "0.100", "0.250", ProductType.WEIGHT, "kg");
        Product p09 = createProduct(epicerie, "Beurre Doux 250g", "Motte de beurre", "Brand I", "4009", "3300000000009", "🧈", "0.250", "0.600", ProductType.WEIGHT, "kg");
        Product p10 = createProduct(epicerie, "Yaourt Nature 4x125g", "Pots de yaourt", "Brand J", null, "3300000000010", "🥛", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p11 = createProduct(sodas, "Coca-Cola 1.5L", "Boisson gazeuse", "Brand K", null, "3300000000011", "🥤", "1.500", "1.500", ProductType.UNIT, "L");
        Product p12 = createProduct(sodas, "Orangina 1.25L", "Boisson aux agrumes", "Brand L", null, "3300000000012", "🥤", "1.250", "1.250", ProductType.UNIT, "L");
        Product p13 = createProduct(epicerie, "Biscuits Chocolat 200g", "Paquet de biscuits", "Brand M", null, "3300000000013", "🍪", "0.200", "0.500", ProductType.UNIT, "kg");
        Product p14 = createProduct(epicerie, "Chips Classiques 150g", "Chips de pomme de terre", "Brand N", null, "3300000000014", "🍟", "0.150", "0.400", ProductType.UNIT, "kg");
        Product p15 = createProduct(epicerie, "Sauce Tomate 500g", "Sauce bolognaise", "Brand O", null, "3300000000015", "🍅", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p16 = createProduct(epicerie, "Purée de Pomme de Terre 500g", "Purée instantanée", "Brand P", null, "3300000000016", "🥔", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p17 = createProduct(racines, "Concombre", "Légume frais", "Brand Q", "4017", "3300000000017", "🥒", "0.300", "0.750", ProductType.WEIGHT, "kg");
        Product p18 = createProduct(racines, "Tomates Cerises 500g", "Tomates rondes", "Brand R", "4018", "3300000000018", "🍅", "0.500", "1.250", ProductType.WEIGHT, "kg");
        Product p19 = createProduct(epicerie, "Oeufs Bio 6 unités", "Oeufs frais gros", "Brand S", null, "3300000000019", "🥚", "0.360", "0.900", ProductType.UNIT, "kg");
        Product p20 = createProduct(epicerie, "Poulet Rôti 1.2kg", "Poulet fermier", "Brand T", "4020", "3300000000020", "🍗", "1.200", "3.000", ProductType.WEIGHT, "kg");
        Product p21 = createProduct(epicerie, "Saumon Fume 200g", "Tranches de saumon", "Brand U", "4021", "3300000000021", "🐟", "0.200", "0.500", ProductType.WEIGHT, "kg");
        Product p22 = createProduct(epicerie, "Riz Basmati 1kg", "Riz long grain", "Brand V", null, "3300000000022", "🍚", "1.000", "2.500", ProductType.UNIT, "kg");
        Product p23 = createProduct(epicerie, "Lentilles Vertes 500g", "Légumes secs", "Brand W", null, "3300000000023", "🫘", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p24 = createProduct(epicerie, "Miel d'Acacia 500g", "Pot de miel", "Brand X", null, "3300000000024", "🍯", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p25 = createProduct(epicerie, "Lessive Liquide 1.5L", "Lessive linge", "Brand Y", null, "3300000000025", "🧴", "1.500", "1.500", ProductType.UNIT, "L");
        Product p26 = createProduct(epicerie, "Eponge Vaisselle 3 unités", "Eponges abrasives", "Brand Z", null, "3300000000026", "🧽", "0.100", "0.250", ProductType.UNIT, "kg");
        Product p27 = createProduct(epicerie, "Coton Bio 500g", "Disques de coton", "Brand A1", null, "3300000000027", "🧻", "0.500", "1.250", ProductType.UNIT, "kg");
        Product p28 = createProduct(epicerie, "Piles AA 4 unités", "Piles alcalines", "Brand B1", null, "3300000000028", "🔋", "0.080", "0.200", ProductType.UNIT, "kg");
        Product p29 = createProduct(epicerie, "Chewing-Gum Menthe", "Pommes de menthe", "Brand C1", null, "3300000000029", "🍬", "0.050", "0.125", ProductType.UNIT, "kg");
        Product p30 = createProduct(epicerie, "Dentifrice Menthe 100ml", "Tube dentifrice", "Brand D1", null, "3300000000030", "🪥", "0.100", "0.100", ProductType.UNIT, "L");
        Product p31 = createProduct(cuisson, "Poêle Antiadhésive 28cm", "Poêle fonte alum", "Tefal", null, "3300000000031", "🍳", "0.800", "0.000", ProductType.UNIT, "pcs");
        Product p32 = createProduct(cuisson, "Casserole Inox 20cm", "Casserole acier inox", "Staub", null, "3300000000032", "🍲", "1.200", "0.000", ProductType.UNIT, "pcs");
        Product p33 = createProduct(cuisson, "Set de Couteaux Chef", "Couteaux acier inox", "Sabatier", null, "3300000000033", "🔪", "0.500", "0.000", ProductType.UNIT, "pcs");

        // --- Engine price rows for store 0101 (PriceImporterClient mirror,
        //     DEFAULT usage only; ...001/...002/...020 carry a priority-1 promo) ---
        createPrice(p01, "1.00", "1.20", "0.2000", 0);
        createPrice(p01, "0.90", "1.08", "0.2000", 1);
        createPrice(p02, "2.50", "3.00", "0.2000", 0);
        createPrice(p02, "2.30", "2.76", "0.2000", 1);
        createPrice(p03, "0.80", "0.96", "0.2000", 0);
        createPrice(p04, "3.50", "4.20", "0.2000", 0);
        createPrice(p05, "1.20", "1.44", "0.2000", 0);
        createPrice(p06, "5.00", "6.00", "0.2000", 0);
        createPrice(p07, "0.50", "0.60", "0.2000", 0);
        createPrice(p08, "2.00", "2.40", "0.2000", 0);
        createPrice(p09, "2.50", "3.00", "0.2000", 0);
        createPrice(p10, "1.50", "1.80", "0.2000", 0);
        createPrice(p11, "1.80", "2.16", "0.2000", 0);
        createPrice(p12, "1.90", "2.28", "0.2000", 0);
        createPrice(p13, "2.00", "2.40", "0.2000", 0);
        createPrice(p14, "1.50", "1.80", "0.2000", 0);
        createPrice(p15, "1.80", "2.16", "0.2000", 0);
        createPrice(p16, "2.00", "2.40", "0.2000", 0);
        createPrice(p17, "3.00", "3.60", "0.2000", 0);
        createPrice(p18, "4.00", "4.80", "0.2000", 0);
        createPrice(p19, "3.50", "4.20", "0.2000", 0);
        createPrice(p20, "10.00", "10.55", "0.0550", 0);
        createPrice(p20, "9.50", "10.02", "0.0550", 1);
        createPrice(p21, "8.00", "9.60", "0.2000", 0);
        createPrice(p22, "2.50", "2.64", "0.0550", 0);
        createPrice(p23, "2.00", "2.11", "0.0550", 0);
        createPrice(p24, "5.00", "6.00", "0.2000", 0);
        createPrice(p25, "3.00", "3.60", "0.2000", 0);
        createPrice(p26, "2.00", "2.40", "0.2000", 0);
        createPrice(p27, "4.00", "4.80", "0.2000", 0);
        createPrice(p28, "6.00", "7.20", "0.2000", 0);
        createPrice(p29, "1.50", "1.80", "0.2000", 0);
        createPrice(p30, "2.50", "3.00", "0.2000", 0);
        createPrice(p31, "12.00", "14.40", "0.2000", 0);
        createPrice(p32, "15.00", "18.00", "0.2000", 0);
        createPrice(p33, "25.00", "30.00", "0.2000", 0);

        // --- Register-only forbidden test products (non-engine EANs on purpose) ---
        createForbiddenProduct(racines, "Champignon sauvage non contrôlé", "4099", "3400409900001", "🍄", "3.79", "4.00");
        createForbiddenProduct(epicerie, "Lot rappelé (retrait conso)", null, "3660000099999", "⛔", "4.74", "5.00");

        pommes.persist();
        racines.persist();
        fruits.persist();
        legumes.persist();
        eaux.persist();
        sodas.persist();
        boissons.persist();
        alimentaire.persist();
        cuisson.persist();
        epicerie.persist();
    }

    /**
     * Seeds the coupon types (payment vouchers and the deposit-return type).
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
     * Creates and persists a test employee.
     *
     * @param badgeId the 8-digit physical badge identifier
     * @param loginName the login name
     * @param pin the 4-digit PIN (hashed)
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
     * @param code the stable family code
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
     * Creates a product mirroring one engine catalog row and links it to a
     * family. The EAN, name, description, brand, reference weight/volume,
     * type and unit come verbatim from the engine's ProductImporterClient
     * CSV; the PLU and icon are register-local additions.
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param description the product description
     * @param brand the product brand
     * @param plu the register-local PLU code, or null
     * @param ean the EAN code (shared key with the engine)
     * @param icon the register-local display icon
     * @param referenceWeight the reference weight (engine CSV value)
     * @param referenceVolume the reference volume (engine CSV value)
     * @param productType the product type (UNIT or WEIGHT)
     * @param unitName the unit name (engine CSV value)
     * @return the persisted product
     */
    private Product createProduct(ProductFamily family, String name, String description, String brand,
                                  String plu, String ean, String icon,
                                  String referenceWeight, String referenceVolume,
                                  ProductType productType, String unitName) {
        Product p = new Product();
        p.name = name;
        p.description = description;
        p.brand = brand;
        p.plu = plu;
        p.ean = ean;
        p.icon = icon;
        p.referenceWeight = new BigDecimal(referenceWeight);
        p.referenceVolume = new BigDecimal(referenceVolume);
        p.productType = productType;
        p.unitName = unitName;
        p.active = true;
        p.persist();
        family.products.add(p);
        return p;
    }

    /**
     * Creates a product flagged as forbidden to sale and links it to a
     * family (register-only test fixture, non-engine EAN on purpose).
     *
     * @param family the family to attach the product to
     * @param name the product name
     * @param plu the PLU code, or null
     * @param ean the EAN code
     * @param icon the display icon
     * @param priceHT the price excluding tax (5.5% rate)
     * @param priceTTC the price including tax
     */
    private void createForbiddenProduct(ProductFamily family, String name, String plu, String ean, String icon,
                                        String priceHT, String priceTTC) {
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

        createPrice(p, priceHT, priceTTC, "0.0550", 0);
        family.products.add(p);
    }

    /**
     * Creates a price row mirroring one engine DEFAULT price row: exact
     * HT/TTC/rate/priority, window opening at {@link #PRICE_START} with no
     * end — so both systems resolve the same figure for any in-window date.
     *
     * @param product the priced product
     * @param priceHT the price excluding tax (engine CSV value)
     * @param priceTTC the price including tax (engine CSV value)
     * @param vatRate the VAT rate (engine CSV value, e.g. "0.2000")
     * @param priority the resolution priority (higher wins)
     */
    private void createPrice(Product product, String priceHT, String priceTTC, String vatRate, int priority) {
        Price p = new Price();
        p.product = product;
        p.priceExcludingTax = new BigDecimal(priceHT);
        p.priceIncludingTax = new BigDecimal(priceTTC);
        p.vatRate = new BigDecimal(vatRate);
        p.priority = priority;
        p.startDateTime = PRICE_START;
        p.endDateTime = null;
        p.persist();
    }

    /**
     * Creates and persists a payment coupon type.
     *
     * @param code the stable technical code of the type
     * @param label the label shown on the payment screen
     * @param matchPattern the recognition regex
     * @param amountSource where the amount comes from (ENCODED or MANUAL)
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
     * Creates the register's store row — code 0101, the engine's primary
     * test store (Lille), so every valuation request targets a store the
     * engine knows.
     */
    private void createStore() {
        Store s = new Store();
        s.name = "Intermarché Test 1";
        s.code = "0101";
        s.address = new Address();
        s.address.streetLine1 = "1 Rue du Test";
        s.address.streetLine2 = "ZI Nord";
        s.address.postalCode = "59000";
        s.address.country = "France";
        s.address.city = "Lille";
        s.persist();
    }
}
