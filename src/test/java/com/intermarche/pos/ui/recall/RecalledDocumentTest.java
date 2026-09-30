package com.intermarche.pos.ui.recall;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unit tests for {@link RecalledDocument}, targeting 100% branch coverage of its
 * kind-label switch, its acompte guard and its constructor's null-handling.
 * Plain POJO — no collaborators, no Panache, so no mocking is required. Each
 * test is fully isolated and asserts absolute expected values.
 */
class RecalledDocumentTest {

    /**
     * The default constructor leaves an unset kind, an empty line list and a zero
     * acompte, and {@code getKindLabel} answers the generic label for a null kind.
     */
    @Test
    void defaultConstructorLeavesEmptyDocument() {
        RecalledDocument doc = new RecalledDocument();
        Assertions.assertNull(doc.kind);
        Assertions.assertTrue(doc.lines.isEmpty());
        Assertions.assertEquals(0, doc.acompteAlreadyPaid.compareTo(BigDecimal.ZERO));
        Assertions.assertEquals("DOCUMENT", doc.getKindLabel());
        Assertions.assertFalse(doc.hasAcompte());
    }

    /**
     * The full constructor keeps its fields, and a null line list and null
     * acompte are normalised to an empty list and ZERO.
     */
    @Test
    void fullConstructorNormalisesNulls() {
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.COMMANDE, "CMD1", null, null);
        Assertions.assertEquals(RecalledDocument.Kind.COMMANDE, doc.kind);
        Assertions.assertEquals("CMD1", doc.code);
        Assertions.assertTrue(doc.lines.isEmpty());
        Assertions.assertEquals(0, doc.acompteAlreadyPaid.compareTo(BigDecimal.ZERO));
    }

    /**
     * The full constructor keeps a supplied line list and acompte.
     */
    @Test
    void fullConstructorKeepsSuppliedValues() {
        RecalledDocument.Line line = new RecalledDocument.Line("300", "PAIN",
                BigDecimal.ONE, new BigDecimal("2.00"), new BigDecimal("0.055"));
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.CLICK_COLLECT,
                "CC1", List.of(line), new BigDecimal("5.00"));
        Assertions.assertEquals(1, doc.lines.size());
        Assertions.assertEquals("PAIN", doc.lines.get(0).label());
        Assertions.assertEquals(0, doc.acompteAlreadyPaid.compareTo(new BigDecimal("5.00")));
    }

    /**
     * {@code getKindLabel} maps each declared kind to its French label.
     */
    @Test
    void kindLabelMapsEveryKind() {
        Assertions.assertEquals("DEVIS", labelOf(RecalledDocument.Kind.DEVIS));
        Assertions.assertEquals("COMMANDE", labelOf(RecalledDocument.Kind.COMMANDE));
        Assertions.assertEquals("BON DE VENTE", labelOf(RecalledDocument.Kind.BON_VENTE));
        Assertions.assertEquals("CLICK AND COLLECT", labelOf(RecalledDocument.Kind.CLICK_COLLECT));
    }

    /**
     * {@code hasAcompte} is true only for a strictly positive down-payment
     * (positive true arm; zero and null false arms).
     */
    @Test
    void hasAcompteOnlyWhenStrictlyPositive() {
        Assertions.assertTrue(new RecalledDocument(RecalledDocument.Kind.COMMANDE, "c",
                List.of(), new BigDecimal("0.01")).hasAcompte());
        Assertions.assertFalse(new RecalledDocument(RecalledDocument.Kind.COMMANDE, "c",
                List.of(), BigDecimal.ZERO).hasAcompte());
        RecalledDocument nullAcompte = new RecalledDocument();
        nullAcompte.acompteAlreadyPaid = null;
        Assertions.assertFalse(nullAcompte.hasAcompte());
    }

    /**
     * Builds a document of the given kind and returns its label.
     *
     * @param kind the kind under test
     * @return the label of a document carrying that kind
     */
    private String labelOf(RecalledDocument.Kind kind) {
        RecalledDocument doc = new RecalledDocument();
        doc.kind = kind;
        return doc.getKindLabel();
    }
}
