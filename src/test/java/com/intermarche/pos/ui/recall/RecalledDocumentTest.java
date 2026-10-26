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
        Assertions.assertEquals("SELFSCANNING", labelOf(RecalledDocument.Kind.SELF_SCAN));
        Assertions.assertEquals("QBUSTING", labelOf(RecalledDocument.Kind.QBUSTING));
        Assertions.assertEquals("E-COMMERCE", labelOf(RecalledDocument.Kind.ECOMMERCE));
        Assertions.assertEquals("MOBILITÉ", labelOf(RecalledDocument.Kind.MOBILITY));
    }

    /**
     * {@code getKindKey} names the bundle key of the kind's label, the generic
     * DOCUMENT key for an unset kind, and every key exists in the French
     * bundle with the French label the screens used to show.
     */
    @Test
    void kindKeyNamesTheTranslatedLabel() {
        RecalledDocument none = new RecalledDocument(null, "X", null, null);
        Assertions.assertEquals("recall.kind.DOCUMENT", none.getKindKey());
        com.intermarche.pos.ui.Messages french = com.intermarche.pos.ui.TestMessages.french();
        Assertions.assertEquals("DOCUMENT", french.get(none.getKindKey()));
        for (RecalledDocument.Kind kind : RecalledDocument.Kind.values()) {
            RecalledDocument doc = new RecalledDocument(kind, "X", null, null);
            Assertions.assertEquals("recall.kind." + kind.name(), doc.getKindKey());
            Assertions.assertEquals(doc.getKindLabel(), french.get(doc.getKindKey()));
        }
    }

    /**
     * BO-10-01-18 / -39 / -40: the customer number, the issue date and the
     * settlements a mobility device collected are read from the node's answer,
     * while the register-side marks of the channel rules never travel.
     *
     * @throws Exception if the JSON cannot be read or written
     */
    @Test
    void theChannelFieldsTravelAndTheRegisterMarksDoNot() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        RecalledDocument doc = mapper.readValue("{\"kind\":\"MOBILITY\",\"code\":\"MB1\",\"customerNumber\":\"C9\","
                + "\"orderDate\":\"2026-10-24T08:00\",\"payments\":[{\"tender\":\"CARD\",\"amount\":4.5}]}",
                RecalledDocument.class);
        Assertions.assertEquals("C9", doc.customerNumber);
        Assertions.assertEquals("2026-10-24T08:00", doc.orderDate);
        Assertions.assertEquals(new RecalledDocument.Payment("CARD", new BigDecimal("4.5")), doc.payments.get(0));
        doc.keyed = true;
        doc.byCustomer = true;
        doc.unknownMarked = 1;
        doc.couponsDropped = 1;
        String written = mapper.writeValueAsString(doc);
        Assertions.assertFalse(written.contains("keyed"));
        Assertions.assertFalse(written.contains("byCustomer"));
        Assertions.assertFalse(written.contains("unknownMarked"));
        Assertions.assertFalse(written.contains("couponsDropped"));
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
    /**
     * The five-argument line states no delivered quantity and sells its own
     * quantity (delivered null arm, quantity non-null arm).
     */
    @Test
    void singleQuantityLineSellsItsQuantity() {
        RecalledDocument.Line line = new RecalledDocument.Line("301", "LAIT", new BigDecimal("3"),
                new BigDecimal("1.25"), new BigDecimal("5.5"));
        Assertions.assertNull(line.deliveredQuantity());
        Assertions.assertEquals(new BigDecimal("3"), line.soldQuantity());
        Assertions.assertEquals(new BigDecimal("3.75"), line.total());
    }

    /**
     * A line stating a delivered quantity sells the delivered one (LC-06-02-02,
     * delivered non-null arm).
     */
    @Test
    void deliveredQuantityIsTheOneSold() {
        RecalledDocument.Line line = new RecalledDocument.Line("301", "LAIT", new BigDecimal("5"),
                new BigDecimal("2.00"), new BigDecimal("20"), new BigDecimal("4"));
        Assertions.assertEquals(new BigDecimal("4"), line.soldQuantity());
        Assertions.assertEquals(new BigDecimal("8.00"), line.total());
    }

    /**
     * A line stating no quantity at all sells one, and a line without price
     * totals zero (quantity null arm, unit price null arm).
     */
    @Test
    void quantityAndPriceDefaults() {
        RecalledDocument.Line line = new RecalledDocument.Line("301", "LAIT", null, null, null);
        Assertions.assertEquals(BigDecimal.ONE, line.soldQuantity());
        Assertions.assertEquals(new BigDecimal("0.00"), line.total());
    }

    /**
     * Only an optional or a mandatory rule offers a deposit (LC-06-02-04): null,
     * NONE and an unknown rule offer none.
     */
    @Test
    void offersDepositOnlyForOptionalOrMandatory() {
        RecalledDocument doc = new RecalledDocument();
        Assertions.assertFalse(doc.offersDeposit());
        doc.depositRule = RecalledDocument.DEPOSIT_NONE;
        Assertions.assertFalse(doc.offersDeposit());
        doc.depositRule = "OTHER";
        Assertions.assertFalse(doc.offersDeposit());
        doc.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        Assertions.assertTrue(doc.offersDeposit());
        doc.depositRule = RecalledDocument.DEPOSIT_MANDATORY;
        Assertions.assertTrue(doc.offersDeposit());
    }

    /**
     * A deposit is required only while a mandatory document carries none
     * (both arms of the conjunction).
     */
    @Test
    void depositRequiredOnlyWhileMandatoryAndUnpaid() {
        RecalledDocument doc = new RecalledDocument();
        doc.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        Assertions.assertFalse(doc.depositRequired());
        doc.depositRule = RecalledDocument.DEPOSIT_MANDATORY;
        Assertions.assertTrue(doc.depositRequired());
        doc.acompteAlreadyPaid = new BigDecimal("10.00");
        Assertions.assertFalse(doc.depositRequired());
    }
}
