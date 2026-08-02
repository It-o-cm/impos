package com.intermarche.pos.ui.cash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link CashItem}.
 * <p>
 * The class is a branch-free, display-only value holder: a single constructor
 * copies its three arguments into the public {@code id}, {@code label} and
 * {@code value} fields. The tests pin that each argument lands in its own field
 * unchanged, including edge values (nulls for the strings, zero and negative
 * for the {@code double}), so no collaborator exists and nothing is mocked.
 */
class CashItemTest {

    /**
     * Verifies that the constructor assigns each argument to its matching field
     * with a representative denomination.
     */
    @Test
    void constructorAssignsEveryFieldFromArguments() {
        CashItem item = new CashItem("b50", "Billet 50 €", 50.0);
        assertEquals("b50", item.id);
        assertEquals("Billet 50 €", item.label);
        assertEquals(50.0, item.value);
    }

    /**
     * Verifies that null string arguments are stored verbatim, since the
     * constructor performs no null guarding.
     */
    @Test
    void constructorStoresNullStringsVerbatim() {
        CashItem item = new CashItem(null, null, 0.0);
        assertNull(item.id);
        assertNull(item.label);
        assertEquals(0.0, item.value);
    }

    /**
     * Verifies that a negative unit value is stored without transformation,
     * confirming the field is a plain copy of the argument.
     */
    @Test
    void constructorStoresNegativeValueVerbatim() {
        CashItem item = new CashItem("c001", "Pièce 0,01 €", -0.01);
        assertEquals("c001", item.id);
        assertEquals("Pièce 0,01 €", item.label);
        assertEquals(-0.01, item.value);
    }
}
