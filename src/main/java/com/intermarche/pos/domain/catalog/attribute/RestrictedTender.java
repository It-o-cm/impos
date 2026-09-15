package com.intermarche.pos.domain.catalog.attribute;

import com.intermarche.pos.domain.catalog.Product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A means of payment that only some articles may be paid with, and the article
 * attribute that says which ({@code LC-09-01-11} to {@code -18}).
 *
 * <p>MEAL VOUCHERS, ECO-VOUCHERS AND THE SOCIAL PURCHASE CARD ARE THE SAME OBJECT seen
 * three times: a tender, an eligibility carried by the article, and a running total the
 * cashier watches. The questionnaire describes them one by one and then asks, in
 * {@code LC-09-01-17/18}, for "tout autre moyen de paiement restreint paramétré en
 * BackOffice" — so the register holds a LIST and not three fields, the three named ones
 * being its default, and the answer to "is there a limit to how many can be added" is
 * no.
 *
 * <p>THE ARTICLE'S ELIGIBILITY IS A FACT, THE LIST IS A CHOICE. A line snapshots every
 * restricted-tender attribute its article carries, whatever the shop currently shows;
 * the administered list then decides which totals appear on screen. Doing it the other
 * way round would make a mid-sale change to the parameter rewrite the past: lines rung
 * before it would carry an eligibility the shop had just redefined.
 *
 * @param code          the tender's own code, as the back office names it
 * @param attributeCode the article attribute that makes an article eligible for it
 * @param label         the wording the cashier reads on screen
 */
public record RestrictedTender(String code, String attributeCode, String label) {

    /** How a snapshot separates the attributes an article carries. */
    private static final String SEPARATOR = ",";

    /**
     * The article attributes this version knows as restricted-tender eligibilities.
     *
     * <p>A snapshot records those of them the article carries. An attribute outside
     * this list is an ordinary article attribute and has nothing to do with paying.
     */
    public static final List<String> ELIGIBILITY_ATTRIBUTES = List.of(
            ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE,
            ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE,
            ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE);

    /** The three tenders the questionnaire names, which a shop gets without asking. */
    public static final String DEFAULT_TENDERS =
            "TR:" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ":Titre-restaurant;"
            + "ECO:" + ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE + ":Éco-chèque;"
            + "CAS:" + ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE + ":Carte achat sociale";

    /**
     * Reads the administered list of restricted tenders ({@code LC-09-01-17/18}).
     *
     * <p>The syntax is the register's usual semicolon list, each entry naming a code, an
     * article attribute and a wording, separated by colons. An entry that is not a
     * triple, or that names an attribute this version does not know as an eligibility,
     * is dropped — a wording shown against an attribute nothing ever sets would be a
     * total permanently at zero, which reads as a defect rather than as a parameter.
     *
     * <p>An empty or unusable parameter falls back to the three tenders the
     * questionnaire names: a shop that never touched the setting still sees them.
     *
     * @param administered the administered list, possibly null or blank
     * @return the tenders to show, in administered order, never empty
     */
    public static List<RestrictedTender> administered(String administered) {
        if (administered == null || administered.isBlank()) {
            return administered(DEFAULT_TENDERS);
        }
        List<RestrictedTender> tenders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : administered.split(";")) {
            String[] parts = entry.split(":");
            if (parts.length != 3) {
                continue;
            }
            String code = parts[0].trim();
            String attribute = parts[1].trim();
            String label = parts[2].trim();
            if (code.isEmpty() || label.isEmpty()
                    || !ELIGIBILITY_ATTRIBUTES.contains(attribute)
                    || !seen.add(code)) {
                continue;
            }
            tenders.add(new RestrictedTender(code, attribute, label));
        }
        return tenders.isEmpty() ? administered(DEFAULT_TENDERS) : tenders;
    }

    /**
     * Snapshots the restricted-tender eligibilities an article carries.
     *
     * <p>Taken at the moment the line is created, like the price and the VAT rate and
     * for the same reason: what the article was eligible for when it was sold is a fact
     * of the sale, and a referential edited afterwards must not move it.
     *
     * @param product the article being rung, or null for a line without one
     * @return the attributes it carries, comma-joined, or null when it carries none
     */
    public static String snapshot(Product product) {
        if (product == null) {
            return null;
        }
        List<String> carried = new ArrayList<>();
        for (String attribute : ELIGIBILITY_ATTRIBUTES) {
            if (ProductAttributes.flag(product, attribute)) {
                carried.add(attribute);
            }
        }
        return carried.isEmpty() ? null : String.join(SEPARATOR, carried);
    }

    /**
     * Tells whether a line's snapshot makes it eligible for this tender.
     *
     * @param snapshot the line's snapshot, possibly null
     * @return true when the snapshot carries this tender's attribute
     */
    public boolean covers(String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        for (String carried : snapshot.split(SEPARATOR)) {
            if (carried.equals(attributeCode)) {
                return true;
            }
        }
        return false;
    }
}
