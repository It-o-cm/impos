package com.intermarche.pos.domain.ticket;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the printer each kind of document comes out of
 * ({@code LC-08-04-11}).
 *
 * <p>The mapping is typed into a back-office field as {@code TYPE:IMPRIMANTE} pairs, so
 * every shape a store can get wrong is exercised: a missing colon, two colons, a kind
 * this version does not know, a printer it does not know, and the parameter left empty.
 * NONE of them may map a kind to nothing — a kind absent from the map falls back to the
 * roll at the call site, and the roll is the printer a register always has.
 */
class DocumentOutputTest {

    // --------------------------------------------------
    // The targets themselves
    // --------------------------------------------------

    /**
     * Only the slip station stops between sheets: the roll is continuous paper and the
     * network printer prints away from the till, so neither costs the operator a
     * gesture.
     */
    @Test
    void onlyTheSlipStationNeedsAHand() {
        assertTrue(DocumentOutput.FACTURETTE.needsInsertion());
        assertFalse(DocumentOutput.TICKET.needsInsertion());
        assertFalse(DocumentOutput.A4.needsInsertion());
    }

    /**
     * Each target carries the name the back office and the screen show.
     */
    @Test
    void eachTargetCarriesItsLabel() {
        assertEquals("Rouleau de caisse", DocumentOutput.TICKET.getLabel());
        assertEquals("Facturette à insertion", DocumentOutput.FACTURETTE.getLabel());
        assertEquals("Imprimante réseau A4", DocumentOutput.A4.getLabel());
    }

    // --------------------------------------------------
    // Reading one name
    // --------------------------------------------------

    /**
     * A name that is exactly a target name resolves, whatever its case and padding.
     */
    @Test
    void aTargetNameResolvesWhateverItsCase() {
        assertSame(DocumentOutput.A4, DocumentOutput.byName(" a4 "));
    }

    /**
     * A missing name resolves to nothing — the null leg.
     */
    @Test
    void aMissingTargetNameResolvesToNothing() {
        assertNull(DocumentOutput.byName(null));
    }

    /**
     * A name no target carries resolves to nothing — the loop-fell-through leg.
     */
    @Test
    void anUnknownTargetNameResolvesToNothing() {
        assertNull(DocumentOutput.byName("MATRICIELLE"));
    }

    // --------------------------------------------------
    // Reading the administered mapping
    // --------------------------------------------------

    /**
     * A parameter that was never set maps nothing — the null leg. Every kind then
     * falls back to the roll at the call site.
     */
    @Test
    void aMissingMappingMapsNothing() {
        assertTrue(DocumentOutput.administered(null).isEmpty());
    }

    /**
     * A parameter emptied by a store maps nothing either — the blank leg.
     */
    @Test
    void aBlankMappingMapsNothing() {
        assertTrue(DocumentOutput.administered("  ").isEmpty());
    }

    /**
     * A well-formed parameter maps each kind to its printer.
     */
    @Test
    void aWellFormedMappingIsRead() {
        Map<DocumentType, DocumentOutput> targets =
                DocumentOutput.administered("FACTURE:A4;BON_LIVRAISON:TICKET");
        assertEquals(2, targets.size());
        assertSame(DocumentOutput.A4, targets.get(DocumentType.FACTURE));
        assertSame(DocumentOutput.TICKET, targets.get(DocumentType.BON_LIVRAISON));
    }

    /**
     * An entry that is not a pair at all is dropped and the others survive — the
     * missing-colon leg.
     */
    @Test
    void anEntryWithoutAColonIsDropped() {
        Map<DocumentType, DocumentOutput> targets =
                DocumentOutput.administered("FACTURE;BON_LIVRAISON:FACTURETTE");
        assertEquals(1, targets.size());
        assertSame(DocumentOutput.FACTURETTE, targets.get(DocumentType.BON_LIVRAISON));
    }

    /**
     * An entry carrying two colons is dropped too — the other side of the same
     * length check, which a stray separator produces.
     */
    @Test
    void anEntryWithTwoColonsIsDropped() {
        assertTrue(DocumentOutput.administered("FACTURE:A4:TICKET").isEmpty());
    }

    /**
     * An entry naming a kind this version does not know is dropped — the unknown-kind
     * leg.
     */
    @Test
    void anEntryNamingAnUnknownKindIsDropped() {
        assertTrue(DocumentOutput.administered("FATURA:A4").isEmpty());
    }

    /**
     * An entry naming a printer this version does not know is dropped — the
     * unknown-target leg.
     */
    @Test
    void anEntryNamingAnUnknownPrinterIsDropped() {
        assertTrue(DocumentOutput.administered("FACTURE:MATRICIELLE").isEmpty());
    }

    /**
     * A kind named twice keeps the LAST target: the parameter is read left to right
     * and a store correcting itself at the end of the line means the end of the line.
     */
    @Test
    void aKindNamedTwiceKeepsTheLastTarget() {
        assertSame(DocumentOutput.FACTURETTE,
                DocumentOutput.administered("FACTURE:A4;FACTURE:FACTURETTE")
                        .get(DocumentType.FACTURE));
    }
}
