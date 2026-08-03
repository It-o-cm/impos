package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState.TicketItem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketState} and its inner {@link TicketItem}.
 * <p>
 * Plain JUnit 5 tests: {@code TicketState} is a POJO with no Panache
 * collaborators, so a real {@link PosState} parent is used to observe the
 * {@code touch()} / {@code lastEnteredItemId} side effects, and the null
 * arm is exercised by leaving the parent unset.
 */
class TicketStateTest {

    /**
     * Builds a unit EAN item (mergeable candidate) with the given price.
     *
     * @param ean the EAN code
     * @param price the unit price
     * @param qty the quantity
     * @return the built ticket item
     */
    private TicketItem unitItem(String ean, String price, String qty) {
        return new TicketItem(ean, null, "L", new BigDecimal(price), new BigDecimal(qty), null);
    }

    /**
     * onChange with a null parent must be a silent no-op (null arm).
     */
    @Test
    void onChangeWithNullParentDoesNothing() {
        TicketState ts = new TicketState();
        ts.setError("boom");
        assertEquals("boom", ts.transientError);
    }

    /**
     * onChange with a non-null parent bumps the parent version (non-null arm).
     */
    @Test
    void onChangeWithParentTouchesIt() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        long before = parent.version;
        ts.setWeight(1.5);
        assertEquals(before + 1, parent.version);
        assertEquals(1.5, ts.currentWeight);
    }

    /**
     * addItem on an empty ticket with a mergeable unit EAN creates a line and,
     * with a parent, records lastEnteredItemId (line 130 non-null arm).
     */
    @Test
    void addItemToEmptyTicketCreatesLineAndRecordsId() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(1, ts.items.size());
        assertEquals(ts.items.get(0).uid, parent.lastEnteredItemId);
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.totalAmount));
    }

    /**
     * addItem without a parent still creates the line (line 130 null arm).
     */
    @Test
    void addItemWithoutParentCreatesLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
    }

    /**
     * Two identical unmodified positive same-price unit EAN scans merge into
     * one line: quantity added, total recomputed, lastEnteredItemId updated
     * (merge success path, line 116 non-null arm).
     */
    @Test
    void addItemMergesIdenticalUnitEan() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        String uid = ts.items.get(0).uid;
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
        assertEquals(uid, parent.lastEnteredItemId);
    }

    /**
     * A merge on a parentless ticket still merges (line 116 null arm).
     */
    @Test
    void addItemMergesWithoutParent() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(ts.items.get(0).quantity));
    }

    /**
     * A weighed incoming line (non-empty PLU) is never mergeable and always
     * starts its own line (plu non-empty arm of mergeable).
     */
    @Test
    void addItemWeighedNeverMerges() {
        TicketState ts = new TicketState();
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        assertEquals(2, ts.items.size());
    }

    /**
     * An empty PLU keeps the line mergeable (plu.isEmpty() true arm).
     */
    @Test
    void addItemEmptyPluIsMergeable() {
        TicketState ts = new TicketState();
        ts.addItem("123", "", "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", "", "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(1, ts.items.size());
    }

    /**
     * A null EAN is not mergeable and starts a new line (ean == null arm).
     */
    @Test
    void addItemNullEanNotMergeable() {
        TicketState ts = new TicketState();
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * An empty EAN is not mergeable (ean.isEmpty() true arm).
     */
    @Test
    void addItemEmptyEanNotMergeable() {
        TicketState ts = new TicketState();
        ts.addItem("", null, "X", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("", null, "X", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item whose PLU is non-empty is not a merge target: sameEan false
     * on the plu clause, loop continues, new line created.
     */
    @Test
    void addItemExistingWeighedIsNotMergeTarget() {
        TicketState ts = new TicketState();
        ts.addItem("123", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.000"), null);
        ts.addItem("123", null, "Apples", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item with a null EAN is skipped in the merge scan
     * (item.ean == null arm of sameEan).
     */
    @Test
    void addItemExistingNullEanSkipped() {
        TicketState ts = new TicketState();
        ts.addItem(null, null, "Deposit", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A stored item with a different EAN is skipped (equals false arm).
     */
    @Test
    void addItemDifferentEanSkipped() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching EAN carrying a modifier label cannot merge: unmodified false on
     * the modifierLabel clause, break, distinct line created.
     */
    @Test
    void addItemModifiedLineBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.items.get(0).modifierLabel = "REMISE";
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching EAN whose unit price diverged from its original cannot merge
     * (unmodified false on the compareTo clause), break, new line.
     */
    @Test
    void addItemPriceDivergedBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.items.get(0).unitPrice = new BigDecimal("2.00");
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching unmodified EAN at a different price cannot merge (samePrice
     * false), break, new line.
     */
    @Test
    void addItemDifferentPriceBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Milk", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * A matching unmodified same-price EAN whose total is negative cannot merge
     * (positive false), break, new line.
     */
    @Test
    void addItemNegativeLineBreaksToNewLine() {
        TicketState ts = new TicketState();
        ts.addItem("123", null, "Return", new BigDecimal("-1.00"), BigDecimal.ONE, null);
        ts.addItem("123", null, "Return", new BigDecimal("-1.00"), BigDecimal.ONE, null);
        assertEquals(2, ts.items.size());
    }

    /**
     * removeLastItem on a non-empty ticket drops the last line and recomputes.
     */
    @Test
    void removeLastItemNonEmpty() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("2.00"), BigDecimal.ONE, null);
        ts.removeLastItem();
        assertEquals(1, ts.items.size());
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.totalAmount));
    }

    /**
     * removeLastItem on an empty ticket is a no-op (isEmpty true arm).
     */
    @Test
    void removeLastItemEmpty() {
        TicketState ts = new TicketState();
        ts.removeLastItem();
        assertTrue(ts.items.isEmpty());
    }

    /**
     * removeItemById with a null uid returns immediately (null arm).
     */
    @Test
    void removeItemByIdNullUid() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.removeItemById(null);
        assertEquals(1, ts.items.size());
    }

    /**
     * removeItemById with a matching uid removes that line (match arm).
     */
    @Test
    void removeItemByIdMatch() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("2.00"), BigDecimal.ONE, null);
        String uid = ts.items.get(0).uid;
        ts.removeItemById(uid);
        assertEquals(1, ts.items.size());
        assertEquals("222", ts.items.get(0).ean);
    }

    /**
     * removeItemById with an unknown uid removes nothing (no-match arm).
     */
    @Test
    void removeItemByIdNoMatch() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.removeItemById("does-not-exist");
        assertEquals(1, ts.items.size());
    }

    /**
     * recomputeTotal sums line totals rounded to the cent (loop with items).
     */
    @Test
    void recomputeTotalSumsRoundedLineTotals() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.005"), BigDecimal.ONE, null);
        ts.addItem("222", null, "B", new BigDecimal("2.00"), BigDecimal.ONE, null);
        assertEquals(0, new BigDecimal("3.01").compareTo(ts.totalAmount));
    }

    /**
     * recomputeTotal on an empty ticket yields zero (loop with no items).
     */
    @Test
    void recomputeTotalEmpty() {
        TicketState ts = new TicketState();
        ts.recomputeTotal();
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
    }

    /**
     * clear resets every field and notifies the parent.
     */
    @Test
    void clearResetsEverything() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        ts.scannedStickerCodes.add("code");
        ts.currentWeight = 2.0;
        ts.lastRecordedWeight = 3.0;
        ts.transientError = "err";
        long before = parent.version;
        ts.clear();
        assertTrue(ts.items.isEmpty());
        assertTrue(ts.scannedStickerCodes.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
        assertEquals(0.0, ts.currentWeight);
        assertTrue(Double.isNaN(ts.lastRecordedWeight));
        assertNull(ts.transientError);
        assertEquals(before + 1, parent.version);
    }

    /**
     * setError stores the message and notifies.
     */
    @Test
    void setErrorStoresMessage() {
        TicketState ts = new TicketState();
        ts.setError("oops");
        assertEquals("oops", ts.transientError);
    }

    /**
     * getTotalFormatted renders the total with two decimals and a French comma.
     */
    @Test
    void getTotalFormattedRendersFrenchComma() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.50"), BigDecimal.ONE, null);
        assertEquals("1,50", ts.getTotalFormatted());
    }

    /**
     * getTotalAmount and getItems expose the backing fields.
     */
    @Test
    void gettersExposeState() {
        TicketState ts = new TicketState();
        ts.addItem("111", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertSame(ts.items, ts.getItems());
        assertSame(ts.totalAmount, ts.getTotalAmount());
    }

    /**
     * The item constructor keeps a supplied VAT rate (non-null arm).
     */
    @Test
    void itemConstructorKeepsVatRate() {
        TicketItem item = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.2000"));
        assertEquals(0, new BigDecimal("0.2000").compareTo(item.vatRate));
    }

    /**
     * The item constructor defaults a null VAT rate to zero (null arm).
     */
    @Test
    void itemConstructorDefaultsNullVatRate() {
        TicketItem item = new TicketItem("1", null, "L", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.vatRate));
    }

    /**
     * getTotalPrice returns the engine-valued total when present (non-null arm).
     */
    @Test
    void getTotalPriceUsesValuedTotal() {
        TicketItem item = unitItem("1", "1.00", "2");
        item.valuedTotal = new BigDecimal("5.00");
        assertEquals(0, new BigDecimal("5.00").compareTo(item.getTotalPrice()));
    }

    /**
     * getTotalPrice falls back to the local math when unvalued (null arm).
     */
    @Test
    void getTotalPriceFallsBackToLocalMath() {
        TicketItem item = unitItem("1", "1.50", "2");
        assertEquals(0, new BigDecimal("3.00").compareTo(item.getTotalPrice()));
    }

    /**
     * getLocalTotalPrice ignores any valuation.
     */
    @Test
    void getLocalTotalPriceIgnoresValuation() {
        TicketItem item = unitItem("1", "1.50", "2");
        item.valuedTotal = new BigDecimal("99.00");
        assertEquals(0, new BigDecimal("3.00").compareTo(item.getLocalTotalPrice()));
    }

    /**
     * getHtml returns the raw label for a code-less span line (both arms true).
     */
    @Test
    void getHtmlReturnsSpanLabelForCodelessLine() {
        TicketItem item = new TicketItem(null, null, "<span>Donation</span>", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span>Donation</span>", item.getHtml());
    }

    /**
     * getHtml with a null EAN but a plain label falls through (contains false arm).
     */
    @Test
    void getHtmlCodelessPlainLabelFallsThrough() {
        TicketItem item = new TicketItem(null, null, "Plain", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> Plain", item.getHtml());
    }

    /**
     * getHtml with a non-null EAN never returns the span label (ean != null arm).
     */
    @Test
    void getHtmlWithEanUsesUnitBranch() {
        TicketItem item = new TicketItem("1", null, "<span>x</span>", BigDecimal.ONE, BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> <span>x</span>", item.getHtml());
    }

    /**
     * getHtml renders a weighed line with three decimals (plu non-empty arm).
     */
    @Test
    void getHtmlWeighedLine() {
        TicketItem item = new TicketItem("1", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.234"), null);
        assertEquals("<span class='qty'>1,234 kg</span> Apples", item.getHtml());
    }

    /**
     * getHtml renders a non-one whole unit quantity with the plain qty class
     * (isOne false, isWhole true arms).
     */
    @Test
    void getHtmlUnitWholeNonOne() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), new BigDecimal("2"), null);
        assertEquals("<span class='qty'>x2</span> A", item.getHtml());
    }

    /**
     * getHtml renders a fractional unit quantity with two decimals
     * (isWhole false arm).
     */
    @Test
    void getHtmlUnitFractional() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), new BigDecimal("2.50"), null);
        assertEquals("<span class='qty'>x2,50</span> A", item.getHtml());
    }

    /**
     * getHtml with a null PLU takes the unit branch (plu == null arm).
     */
    @Test
    void getHtmlNullPluIsUnitBranch() {
        TicketItem item = new TicketItem("1", null, "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> A", item.getHtml());
    }

    /**
     * getHtml with an empty PLU also takes the unit branch (plu.isEmpty() arm).
     */
    @Test
    void getHtmlEmptyPluIsUnitBranch() {
        TicketItem item = new TicketItem("1", "", "A", new BigDecimal("1.00"), BigDecimal.ONE, null);
        assertEquals("<span class='qty unit-qty'>x1</span> A", item.getHtml());
    }

    /**
     * getPriceFormatted rounds and renders with a French comma.
     */
    @Test
    void getPriceFormattedRendersFrenchComma() {
        TicketItem item = unitItem("1", "1.005", "1");
        assertEquals("1,01", item.getPriceFormatted());
    }

    /**
     * getModifierLabel returns the stored label, null by default.
     */
    @Test
    void getModifierLabelReturnsField() {
        TicketItem item = unitItem("1", "1.00", "1");
        assertNull(item.getModifierLabel());
        item.modifierLabel = "REMISE";
        assertEquals("REMISE", item.getModifierLabel());
    }

    /**
     * isNegative and its Qute alias are true for a negative total.
     */
    @Test
    void isNegativeTrueForNegativeTotal() {
        TicketItem item = unitItem("1", "-1.00", "1");
        assertTrue(item.isNegative());
        assertTrue(item.getNegative());
    }

    /**
     * isNegative and its Qute alias are false for a positive total.
     */
    @Test
    void isNegativeFalseForPositiveTotal() {
        TicketItem item = unitItem("1", "1.00", "1");
        assertFalse(item.isNegative());
        assertFalse(item.getNegative());
    }

    /**
     * The no-arg item constructor yields a serialization-friendly blank line.
     */
    @Test
    void itemDefaultConstructor() {
        TicketItem item = new TicketItem();
        assertNull(item.ean);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.vatRate));
    }
}
