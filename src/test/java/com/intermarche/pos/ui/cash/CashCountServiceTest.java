package com.intermarche.pos.ui.cash;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CashCountService}.
 * <p>
 * The service is a pure, branch-free provider of the three denomination lists
 * shown on the drawer-count page. Each test drives one getter and asserts the
 * absolute list size and every entry's {@code id}, {@code label} and unit
 * {@code value}, pinning the till convention (banknotes stop at 50 €, rolls
 * carry the whole-roll value). No collaborators exist, so nothing is mocked.
 */
class CashCountServiceTest {

    /** The service under test; stateless, so a single fresh instance is enough. */
    private final CashCountService service = new CashCountService();

    /**
     * Asserts that a denomination entry carries the expected id, label and value.
     *
     * @param item the entry to inspect
     * @param id the expected stable identifier
     * @param label the expected display label
     * @param value the expected unit value in euros
     */
    private void assertItem(CashItem item, String id, String label, double value) {
        assertEquals(id, item.id);
        assertEquals(label, item.label);
        assertEquals(value, item.value);
    }

    /**
     * Verifies that {@link CashCountService#getBills()} returns the four
     * banknote denominations, largest first, capped at 50 €.
     */
    @Test
    void getBillsReturnsBanknotesLargestFirst() {
        List<CashItem> items = service.getBills();
        assertEquals(4, items.size());
        assertItem(items.get(0), "b50", "Billet 50 €", 50.0);
        assertItem(items.get(1), "b20", "Billet 20 €", 20.0);
        assertItem(items.get(2), "b10", "Billet 10 €", 10.0);
        assertItem(items.get(3), "b5", "Billet 5 €", 5.0);
    }

    /**
     * Verifies that {@link CashCountService#getCoins()} returns the eight coin
     * denominations, largest first, down to 0,01 €.
     */
    @Test
    void getCoinsReturnsAllCoinsLargestFirst() {
        List<CashItem> items = service.getCoins();
        assertEquals(8, items.size());
        assertItem(items.get(0), "c2", "Pièce 2 €", 2.0);
        assertItem(items.get(1), "c1", "Pièce 1 €", 1.0);
        assertItem(items.get(2), "c050", "Pièce 0,50 €", 0.5);
        assertItem(items.get(3), "c020", "Pièce 0,20 €", 0.2);
        assertItem(items.get(4), "c010", "Pièce 0,10 €", 0.1);
        assertItem(items.get(5), "c005", "Pièce 0,05 €", 0.05);
        assertItem(items.get(6), "c002", "Pièce 0,02 €", 0.02);
        assertItem(items.get(7), "c001", "Pièce 0,01 €", 0.01);
    }

    /**
     * Verifies that {@link CashCountService#getRolls()} returns the six
     * coin-roll denominations, each valued at the whole roll, largest first.
     */
    @Test
    void getRollsReturnsRollsValuedAtWholeRoll() {
        List<CashItem> items = service.getRolls();
        assertEquals(6, items.size());
        assertItem(items.get(0), "r2", "Rouleau 2€ (50€)", 50.0);
        assertItem(items.get(1), "r1", "Rouleau 1€ (25€)", 25.0);
        assertItem(items.get(2), "r050", "Rouleau 0.50€ (20€)", 20.0);
        assertItem(items.get(3), "r020", "Rouleau 0.20€ (8€)", 8.0);
        assertItem(items.get(4), "r010", "Rouleau 0.10€ (4€)", 4.0);
        assertItem(items.get(5), "r005", "Rouleau 0.05€ (2€)", 2.0);
    }
}
