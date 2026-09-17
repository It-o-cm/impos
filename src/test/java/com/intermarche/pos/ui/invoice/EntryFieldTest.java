package com.intermarche.pos.ui.invoice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link EntryField}: the administered customer mask
 * ({@code LC-08-04-10}) and the two masks the flow builds on its own — the original
 * ticket ({@code LC-08-04-02}) and the customer lookup ({@code LC-08-04-06/07}).
 */
class EntryFieldTest {

    /**
     * A blank administered mask falls back to the whole catalog, the business name
     * mandatory: a parameter emptied by accident must not leave a form that asks for
     * nothing.
     */
    @Test
    void blankMaskFallsBackToTheCatalog() {
        List<EntryField> fields = EntryField.customerFields("");
        assertEquals(10, fields.size());
        assertEquals("companyName", fields.get(0).name());
        assertTrue(fields.get(0).required());
    }

    /**
     * A missing administered mask falls back the same way (null arm of the guard).
     */
    @Test
    void missingMaskFallsBackToTheCatalog() {
        assertEquals(10, EntryField.customerFields(null).size());
    }

    /**
     * A whitespace-only mask falls back the same way (blank arm of the guard).
     */
    @Test
    void whitespaceMaskFallsBackToTheCatalog() {
        assertEquals(10, EntryField.customerFields("   ").size());
    }

    /**
     * The administered mask decides which fields are asked for, and in which order.
     */
    @Test
    void maskDecidesTheFieldsAndTheirOrder() {
        List<EntryField> fields = EntryField.customerFields("city;companyName;siret");
        assertEquals(3, fields.size());
        assertEquals("city", fields.get(0).name());
        assertEquals("companyName", fields.get(1).name());
        assertEquals("siret", fields.get(2).name());
    }

    /**
     * A trailing star marks a field mandatory, and only that field.
     */
    @Test
    void starMarksTheFieldMandatory() {
        List<EntryField> fields = EntryField.customerFields("companyName*;city");
        assertTrue(fields.get(0).required());
        assertFalse(fields.get(1).required());
    }

    /**
     * Blanks around a name and empty entries are tolerated.
     */
    @Test
    void maskToleratesBlanksAndEmptyEntries() {
        List<EntryField> fields = EntryField.customerFields("  companyName * ;; city ;");
        assertEquals(2, fields.size());
        assertTrue(fields.get(0).required());
        assertEquals("city", fields.get(1).name());
    }

    /**
     * A name the register does not know is dropped: it can only ask for a field it
     * knows how to store.
     */
    @Test
    void unknownNameIsDropped() {
        List<EntryField> fields = EntryField.customerFields("companyName;planete");
        assertEquals(1, fields.size());
        assertEquals("companyName", fields.get(0).name());
    }

    /**
     * A mask made ONLY of unknown names falls back to the catalog rather than leaving
     * an empty form.
     */
    @Test
    void maskOfUnknownNamesFallsBackToTheCatalog() {
        assertEquals(10, EntryField.customerFields("planete;galaxie").size());
    }

    /**
     * Every catalog field carries a keyboard and a length cap.
     */
    @Test
    void everyCatalogFieldIsTyped() {
        for (EntryField field : EntryField.customerFields("")) {
            assertFalse(field.keyboard() == null || field.keyboard().isBlank(),
                    "clavier manquant pour " + field.name());
            assertTrue(field.maxLength() > 0, "longueur manquante pour " + field.name());
        }
    }

    /**
     * A mandatory field is starred on screen, an optional one is not.
     */
    @Test
    void displayLabelMarksTheMandatoryFields() {
        List<EntryField> fields = EntryField.customerFields("companyName*;city");
        assertEquals("Raison sociale *", fields.get(0).getDisplayLabel());
        assertEquals("Ville", fields.get(1).getDisplayLabel());
    }



    /**
     * The ticket mask asks for the three things printed on the paper the customer
     * hands back, the number alone mandatory.
     */
    @Test
    void ticketMaskCarriesNumberDateAndRegister() {
        List<EntryField> fields = EntryField.ticketFields("C04-000417", "09/09/2026", "C04");
        assertEquals(3, fields.size());
        assertEquals("ticketNumber", fields.get(0).name());
        assertTrue(fields.get(0).required());
        assertEquals("09/09/2026", fields.get(1).value());
        assertFalse(fields.get(1).required());
        assertEquals("C04", fields.get(2).value());
        assertFalse(fields.get(2).required());
    }

    /**
     * A ticket mask built with nothing pre-filled carries empty values, never nulls.
     */
    @Test
    void ticketMaskTurnsMissingValuesIntoEmptyOnes() {
        for (EntryField field : EntryField.ticketFields(null, null, null)) {
            assertEquals("", field.value());
        }
    }

    /**
     * The lookup mask carries the name fragment and the account number, neither
     * mandatory: the operator fills whichever they know.
     */
    @Test
    void lookupMaskCarriesNameAndNumber() {
        List<EntryField> fields = EntryField.customerLookupFields("BOU", "C04-CLI000007");
        assertEquals(2, fields.size());
        assertEquals("search", fields.get(0).name());
        assertEquals("BOU", fields.get(0).value());
        assertFalse(fields.get(0).required());
        assertEquals("customerNumber", fields.get(1).name());
        assertEquals("C04-CLI000007", fields.get(1).value());
        assertFalse(fields.get(1).required());
    }

    /**
     * A lookup mask built with nothing typed carries empty values, never nulls.
     */
    @Test
    void lookupMaskTurnsMissingValuesIntoEmptyOnes() {
        for (EntryField field : EntryField.customerLookupFields(null, null)) {
            assertEquals("", field.value());
        }
    }

    /**
     * {@code filled} carries a known value onto its field and leaves a field the
     * map does not name empty — both arms of the lookup ({@code BO-10-04-03}).
     */
    @Test
    void filledCarriesTheKnownValuesAndEmptiesTheRest() {
        java.util.List<EntryField> mask = EntryField.customerFields("companyName*;city");
        java.util.List<EntryField> filled = EntryField.filled(mask,
                java.util.Map.of("companyName", "ACME"));
        assertEquals(2, filled.size());
        assertEquals("ACME", filled.get(0).value());
        assertEquals("", filled.get(1).value());
        assertEquals("companyName", filled.get(0).name());
        assertTrue(filled.get(0).required());
        assertFalse(filled.get(1).required());
    }

    /**
     * {@code filled} preserves every property of the field it copies — the label,
     * the keyboard, the capitalisation and the length cap are the field's own and
     * must survive the filling.
     */
    @Test
    void filledPreservesTheFieldsOwnProperties() {
        java.util.List<EntryField> mask = EntryField.customerFields("postalCode");
        EntryField before = mask.get(0);
        EntryField after = EntryField.filled(mask,
                java.util.Map.of("postalCode", "92420")).get(0);
        assertEquals(before.label(), after.label());
        assertEquals(before.keyboard(), after.keyboard());
        assertEquals(before.uppercase(), after.uppercase());
        assertEquals(before.maxLength(), after.maxLength());
        assertEquals("92420", after.value());
    }
}
