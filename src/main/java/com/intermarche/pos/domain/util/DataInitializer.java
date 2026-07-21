package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;

@ApplicationScoped
public class DataInitializer {

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

    private void loadEmployees() {
        // Format : createEmployee(BadgeID 8chiffres, PIN 4chiffres, Prénom, Nom, Rôle)
        createEmployee("11111111", "mcurie", "1111", "Marie", "Curie", Employee.EmployeeRole.MANAGER);
        createEmployee("22222222", "aeinstein", "2222", "Albert", "Einstein", Employee.EmployeeRole.PICKER);
        createEmployee("00000000", "manager", "0000", "Le", "Manager", Employee.EmployeeRole.ADMIN);
        createEmployee("12341234",  "jdupont","1234", "Jean", "Dupont", Employee.EmployeeRole.CASHIER);
    }

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

        // Bon générique / éphémère : aucun numéro -> montant seul.
        createCouponType("GENERIC", "Bon générique", "",
                CouponType.AmountSource.MANUAL, null, 100);
    }

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

    private ProductFamily createFamily(String code, String description) {
        ProductFamily f = new ProductFamily();
        f.code = code;
        f.description = description;
        f.persist();
        return f;
    }

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

    private void createPrice(Product product, double priceTTC, String vatRate) {
        Price p = new Price();
        p.product = product;
        p.priceIncludingTax = new BigDecimal(priceTTC);
        p.vatRate = new BigDecimal(vatRate).divide(new BigDecimal("100"));
        p.priceExcludingTax = p.priceIncludingTax.divide(BigDecimal.ONE.add(p.vatRate), 4, RoundingMode.HALF_UP);
        p.priority = 0;
        p.persist();
    }

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
