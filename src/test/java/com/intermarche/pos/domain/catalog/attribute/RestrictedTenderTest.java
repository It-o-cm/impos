package com.intermarche.pos.domain.catalog.attribute;

import com.intermarche.pos.domain.catalog.Product;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the restricted tenders and their administered list
 * ({@code LC-09-01-11} to {@code -18}).
 *
 * <p>THE LIST IS TYPED BY A SHOP, so every way it can arrive damaged is exercised: a
 * missing colon, a fourth field, an attribute this version does not know as an
 * eligibility, a code named twice, an empty wording. None of them may produce an empty
 * list — a register showing no eligible base at all is a register whose cashier stops
 * being able to answer "puis-je payer en titres-restaurant ?".
 */
class RestrictedTenderTest {

    /**
     * Builds a product carrying the given attributes as true.
     *
     * @param attributes the attribute codes the article carries
     * @return the product
     */
    private Product productWith(String... attributes) {
        Product product = new Product();
        product.attributes = new HashMap<>();
        for (String attribute : attributes) {
            product.attributes.put(attribute, "true");
        }
        return product;
    }

    // --------------------------------------------------
    // The administered list
    // --------------------------------------------------

    /**
     * A parameter that was never set gives the three tenders the questionnaire names
     * — the null leg, and the shape every shop gets without touching anything.
     */
    @Test
    void aMissingListGivesTheThreeNamedTenders() {
        List<RestrictedTender> tenders = RestrictedTender.administered(null);
        assertEquals(3, tenders.size());
        assertEquals("TR", tenders.get(0).code());
        assertEquals("Titre-restaurant", tenders.get(0).label());
        assertEquals("ECO", tenders.get(1).code());
        assertEquals("CAS", tenders.get(2).code());
    }

    /**
     * A parameter emptied by a shop gives the same three — the blank leg.
     */
    @Test
    void aBlankListGivesTheThreeNamedTenders() {
        assertEquals(3, RestrictedTender.administered("  ").size());
    }

    /**
     * A well-formed list is read in administered order.
     */
    @Test
    void aWellFormedListIsReadInOrder() {
        List<RestrictedTender> tenders = RestrictedTender.administered(
                "CAS:" + ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE + ":Carte;"
                + "TR:" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ":Titre");
        assertEquals(2, tenders.size());
        assertEquals("CAS", tenders.get(0).code());
        assertEquals("Titre", tenders.get(1).label());
    }

    /**
     * An entry that is not a triple is dropped — the arity leg.
     */
    @Test
    void anEntryWithoutThreeFieldsIsDropped() {
        List<RestrictedTender> tenders = RestrictedTender.administered(
                "TR:" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ";"
                + "ECO:" + ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE + ":Éco");
        assertEquals(1, tenders.size());
        assertEquals("ECO", tenders.get(0).code());
    }

    /**
     * An entry naming an attribute this version does not treat as an eligibility is
     * dropped: a wording shown against an attribute nothing ever sets would be a
     * permanent zero.
     */
    @Test
    void anEntryNamingANonEligibilityAttributeIsDropped() {
        assertEquals(3, RestrictedTender.administered(
                "BULK:" + ProductAttributeCatalog.BULKY + ":Encombrant").size());
    }

    /**
     * An entry with an empty code is dropped — the code leg.
     */
    @Test
    void anEntryWithoutACodeIsDropped() {
        assertEquals(3, RestrictedTender.administered(
                " :" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ":Titre").size());
    }

    /**
     * An entry with an empty wording is dropped — the label leg. A tender the cashier
     * cannot name is a figure with nothing beside it.
     */
    @Test
    void anEntryWithoutALabelIsDropped() {
        assertEquals(3, RestrictedTender.administered(
                "TR:" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ": ").size());
    }

    /**
     * A code named twice is kept once, the FIRST entry winning: two identical rows
     * under the total would be a defect of the parameter showing through.
     */
    @Test
    void aCodeNamedTwiceIsKeptOnce() {
        List<RestrictedTender> tenders = RestrictedTender.administered(
                "TR:" + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ":Premier;"
                + "TR:" + ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE + ":Second");
        assertEquals(1, tenders.size());
        assertEquals("Premier", tenders.get(0).label());
    }

    /**
     * A list where NOTHING is usable falls back to the three named tenders rather
     * than to nothing — the last leg, and the one that decides whether a mistyped
     * parameter blinds the cashier.
     */
    @Test
    void anUnusableListFallsBackToTheNamedTenders() {
        assertEquals(3, RestrictedTender.administered(";;bad;also:bad").size());
    }

    // --------------------------------------------------
    // The article's snapshot
    // --------------------------------------------------

    /**
     * An article carrying no eligibility snapshots to nothing, so a line without a
     * snapshot is the ordinary case and costs no string.
     */
    @Test
    void anArticleWithoutEligibilitySnapshotsToNothing() {
        assertNull(RestrictedTender.snapshot(productWith()));
    }

    /**
     * A line without an article snapshots to nothing either — the null leg, which the
     * deposit and unknown-item lines take.
     */
    @Test
    void aLineWithoutAnArticleSnapshotsToNothing() {
        assertNull(RestrictedTender.snapshot(null));
    }

    /**
     * An article carrying one eligibility snapshots it.
     */
    @Test
    void oneEligibilityIsSnapshotted() {
        assertEquals(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE,
                RestrictedTender.snapshot(
                        productWith(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE)));
    }

    /**
     * An article carrying several snapshots them all, in catalog order.
     */
    @Test
    void severalEligibilitiesAreSnapshottedInCatalogOrder() {
        assertEquals(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE + ","
                        + ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE,
                RestrictedTender.snapshot(productWith(
                        ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE,
                        ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE)));
    }

    /**
     * An attribute that is not an eligibility never enters the snapshot.
     */
    @Test
    void aNonEligibilityAttributeIsNotSnapshotted() {
        assertNull(RestrictedTender.snapshot(productWith(ProductAttributeCatalog.BULKY)));
    }

    // --------------------------------------------------
    // Matching a line to a tender
    // --------------------------------------------------

    /**
     * A tender covers a line whose snapshot carries its attribute, whether it stands
     * alone or among others.
     */
    @Test
    void aTenderCoversTheLinesCarryingItsAttribute() {
        RestrictedTender meal = RestrictedTender.administered(null).get(0);
        assertTrue(meal.covers(ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE));
        assertTrue(meal.covers(ProductAttributeCatalog.SOCIAL_CARD_ELIGIBLE + ","
                + ProductAttributeCatalog.MEAL_VOUCHER_ELIGIBLE));
    }

    /**
     * It covers neither a line carrying another eligibility, nor one carrying none —
     * the two legs of the guard, the second being every ordinary article.
     */
    @Test
    void aTenderCoversNeitherOthersNorNothing() {
        RestrictedTender meal = RestrictedTender.administered(null).get(0);
        assertFalse(meal.covers(ProductAttributeCatalog.ECO_VOUCHER_ELIGIBLE));
        assertFalse(meal.covers(null));
        assertFalse(meal.covers(""));
    }
}
