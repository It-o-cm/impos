package com.intermarche.pos.ui.recall;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SelfScanControl}, targeting 100% branch coverage.
 * <p>
 * Plain JUnit 5, no mock: the control is a value holder over a basket. Branch
 * enumeration: the declared quantities skip null lines, lines without EAN and
 * lines with a blank EAN and merge repeated EANs; the three modes; the partial
 * target reached or not; the loadability of each mode; the discrepancies outside
 * and inside a total control, with and without a gap.
 */
class SelfScanControlTest {

    /**
     * A basket of two milks on two lines, one bread, a free line, a blank-EAN
     * line and a null line.
     *
     * @return the basket
     */
    private RecalledDocument basket() {
        List<RecalledDocument.Line> lines = new ArrayList<>(Arrays.asList(
                new RecalledDocument.Line("301", "LAIT", BigDecimal.ONE, new BigDecimal("1.00"), null),
                new RecalledDocument.Line("302", "PAIN", BigDecimal.ONE, new BigDecimal("2.00"), null),
                new RecalledDocument.Line("301", "LAIT BIS", BigDecimal.ONE, new BigDecimal("1.00"), null),
                new RecalledDocument.Line(null, "FORFAIT", BigDecimal.ONE, new BigDecimal("5.00"), null),
                new RecalledDocument.Line(" ", "VIDE", BigDecimal.ONE, new BigDecimal("5.00"), null),
                null));
        return new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", lines, null);
    }

    /**
     * The declared quantities merge the lines of one EAN and keep the first
     * label; free, blank and null lines declare nothing.
     */
    @Test
    void theDeclaredQuantitiesMergeThePerEanLines() {
        SelfScanControl control = new SelfScanControl(basket(), RecalledDocument.CONTROL_NONE, 2);
        assertEquals(2, control.declared.size());
        assertEquals(0, new BigDecimal("2").compareTo(control.declaredOf("301")));
        assertEquals(BigDecimal.ZERO, control.declaredOf("999"));
        assertEquals("LAIT", control.labels.get("301"));
        assertEquals(BigDecimal.ZERO, control.controlledOf("301"));
    }

    /**
     * Each mode answers its own question, and only a partial control that has
     * not reached its target cannot be loaded.
     */
    @Test
    void theModesAndTheLoadability() {
        SelfScanControl none = new SelfScanControl(basket(), RecalledDocument.CONTROL_NONE, 2);
        assertFalse(none.isPartial());
        assertFalse(none.isTotal());
        assertTrue(none.isLoadable());
        SelfScanControl partial = new SelfScanControl(basket(), RecalledDocument.CONTROL_PARTIAL, 2);
        assertTrue(partial.isPartial());
        assertFalse(partial.isPartialPassed());
        assertFalse(partial.isLoadable());
        partial.partialChecked = 2;
        assertTrue(partial.isPartialPassed());
        assertTrue(partial.isLoadable());
        SelfScanControl total = new SelfScanControl(basket(), RecalledDocument.CONTROL_TOTAL, 2);
        assertTrue(total.isTotal());
        assertTrue(total.isLoadable());
    }

    /**
     * Counting adds units and keeps the label of an EAN first met there.
     */
    @Test
    void countingAddsUnits() {
        SelfScanControl control = new SelfScanControl(basket(), RecalledDocument.CONTROL_TOTAL, 2);
        control.count("301", "AUTRE");
        control.count("301", "AUTRE");
        control.count("777", "CAFE");
        assertEquals(0, new BigDecimal("2").compareTo(control.controlledOf("301")));
        assertEquals("LAIT", control.labels.get("301"));
        assertEquals("CAFE", control.labels.get("777"));
    }

    /**
     * Outside a total control there is no discrepancy; in a total control every
     * EAN whose re-scanned quantity differs from the declared one is one.
     */
    @Test
    void theDiscrepanciesOfATotalControl() {
        SelfScanControl partial = new SelfScanControl(basket(), RecalledDocument.CONTROL_PARTIAL, 2);
        partial.count("777", "CAFE");
        assertEquals(0, partial.discrepancies());
        SelfScanControl total = new SelfScanControl(basket(), RecalledDocument.CONTROL_TOTAL, 2);
        total.count("301", "LAIT");
        total.count("301", "LAIT");
        assertEquals(1, total.discrepancies());
        total.count("777", "CAFE");
        assertEquals(2, total.discrepancies());
        total.count("302", "PAIN");
        assertEquals(1, total.discrepancies());
    }
}
