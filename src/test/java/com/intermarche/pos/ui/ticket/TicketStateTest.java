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
        ts.transientOk = true;
        long before = parent.version;
        ts.clear();
        assertTrue(ts.items.isEmpty());
        assertTrue(ts.scannedStickerCodes.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
        assertEquals(0.0, ts.currentWeight);
        assertTrue(Double.isNaN(ts.lastRecordedWeight));
        assertNull(ts.transientError);
        assertFalse(ts.transientOk);
        assertEquals(before + 1, parent.version);
    }

    /**
     * setError stores the message, notifies, and marks the zone as a REFUSAL.
     */
    @Test
    void setErrorStoresMessage() {
        TicketState ts = new TicketState();
        ts.setError("oops");
        assertEquals("oops", ts.transientError);
        assertFalse(ts.transientOk);
    }

    /**
     * setNotice stores the message in the same zone and marks it as a
     * CONFIRMATION, so a successful print is not drawn as an error.
     */
    @Test
    void setNoticeStoresConfirmation() {
        TicketState ts = new TicketState();
        PosState parent = new PosState();
        ts.setParent(parent);
        long before = parent.version;
        ts.setNotice("TICKET RÉIMPRIMÉ");
        assertEquals("TICKET RÉIMPRIMÉ", ts.transientError);
        assertTrue(ts.transientOk);
        assertEquals(before + 1, parent.version);
    }

    /**
     * A refusal after a confirmation takes the zone back: the OK marker must not
     * survive the next failure.
     */
    @Test
    void setErrorAfterNoticeClearsTheConfirmationFlag() {
        TicketState ts = new TicketState();
        ts.setNotice("TICKET RÉIMPRIMÉ");
        ts.setError("AUCUN TICKET");
        assertEquals("AUCUN TICKET", ts.transientError);
        assertFalse(ts.transientOk);
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
     * getHtml renders a weighed line with three decimals (plu non-empty arm,
     * priceEmbedded false arm).
     */
    @Test
    void getHtmlWeighedLine() {
        TicketItem item = new TicketItem("1", "9", "Apples", new BigDecimal("1.00"), new BigDecimal("1.234"), null);
        assertEquals("<span class='qty'>1,234 kg</span> Apples", item.getHtml());
    }

    /**
     * getHtml on a price-embedded sticker line (PLU present) renders the label
     * alone: the sticker fixed the price, the weight is unknown, so no
     * quantity fragment — never a phantom "1,000 kg" (priceEmbedded true arm).
     */
    @Test
    void getHtmlPriceEmbeddedStickerShowsNoWeight() {
        TicketItem item = new TicketItem("1", "9", "Apples", new BigDecimal("2.67"), BigDecimal.ONE, null);
        item.priceEmbedded = true;
        assertEquals("Apples", item.getHtml());
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

    // --------------------------------------------------
    // setGlobalDiscount
    // --------------------------------------------------

    /**
     * A POSITIVE value ARMS the whole-ticket request: the kind and the value
     * are stored verbatim (guard false arm). The request is what survives on
     * the draft; the allocated amount is derived later.
     */
    @Test
    void setGlobalDiscountStoresPositiveRequest() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        assertEquals("GLOBAL_REMISE", ts.globalDiscountType);
        assertEquals(0, new BigDecimal("5.00").compareTo(ts.globalDiscountValue));
    }

    /**
     * A null value ERASES a previously armed request (first leg of the guard
     * true): the ticket goes back to its undiscounted state — this is how the
     * cashier cancels a whole-ticket gesture, and the kind is dropped too so
     * no orphan type survives.
     */
    @Test
    void setGlobalDiscountNullValueErasesPreviousRequest() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", new BigDecimal("10"));
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", null);
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A ZERO value erases as well (second leg, {@code signum() == 0}): typing
     * 0 on the modal is the documented way to clear the discount, and the
     * KIND is ignored on that path — it must not linger.
     */
    @Test
    void setGlobalDiscountZeroErases() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3"));
        ts.setGlobalDiscount("GLOBAL_REMISE", BigDecimal.ZERO);
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A NEGATIVE value erases too (second leg, {@code signum() < 0}): the
     * guard is {@code <= 0}, so no gesture can ever INFLATE a ticket total.
     */
    @Test
    void setGlobalDiscountNegativeErases() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("2"));
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("-1"));
        assertNull(ts.globalDiscountType);
        assertNull(ts.globalDiscountValue);
    }

    /**
     * A second positive request REPLACES the first, kind included: the
     * request is a single slot, never an accumulation — two gestures in a row
     * must not stack into a double discount.
     */
    @Test
    void setGlobalDiscountReplacesRatherThanAccumulates() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.setGlobalDiscount("GLOBAL_DISCOUNT", new BigDecimal("10"));
        assertEquals("GLOBAL_DISCOUNT", ts.globalDiscountType);
        assertEquals(0, new BigDecimal("10").compareTo(ts.globalDiscountValue));
    }

    /**
     * The setter records the REQUEST only — it never computes the allocated
     * amount, which the next total recomputation produces (the request
     * survives, the allocation is derived).
     */
    @Test
    void setGlobalDiscountLeavesAllocationUntouched() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        assertNull(ts.globalDiscountApplied);
    }

    // --------------------------------------------------
    // allocateGlobalDiscount (through recomputeTotal)
    // --------------------------------------------------

    /**
     * Adds a line to the ticket and returns it, for building allocation cases.
     *
     * @param ts the ticket under test
     * @param price the unit price
     * @param qty the quantity
     * @return the created line
     */
    private TicketItem addLine(TicketState ts, String price, String qty) {
        TicketItem item = unitItem("EAN" + ts.items.size(), price, qty);
        ts.items.add(item);
        return item;
    }

    /**
     * With NO request armed, every share is wiped and no amount is applied
     * (first guard, {@code globalDiscountType == null} leg).
     */
    @Test
    void allocateWithoutRequestClearsShares() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        line.globalDiscountShare = new BigDecimal("3.00");
        ts.recomputeTotal();
        assertNull(line.globalDiscountShare);
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * An armed request on an EMPTY ticket applies nothing (first guard,
     * {@code items.isEmpty()} leg).
     */
    @Test
    void allocateOnEmptyTicketAppliesNothing() {
        TicketState ts = new TicketState();
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * A euro request is capped at the base: asking 50 € on a 10 € ticket
     * discounts 10 €, never more — the total can reach zero but never turns
     * negative ({@code min(value, base)} leg).
     */
    @Test
    void allocateEurosIsCappedAtTheBase() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("50.00"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("10.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("10.00").compareTo(line.globalDiscountShare));
        assertEquals(0, BigDecimal.ZERO.compareTo(ts.totalAmount));
    }

    /**
     * A percentage request is computed on the base and spread pro rata: 10 %
     * on 10 € + 30 € gives 4,00 € split 1,00 / 3,00 (PERCENT leg).
     */
    @Test
    void allocatePercentSpreadsProRata() {
        TicketState ts = new TicketState();
        TicketItem small = addLine(ts, "10.00", "1");
        TicketItem large = addLine(ts, "30.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("4.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(small.globalDiscountShare));
        assertEquals(0, new BigDecimal("3.00").compareTo(large.globalDiscountShare));
        assertEquals(0, new BigDecimal("36.00").compareTo(ts.totalAmount));
    }

    /**
     * The rounding RESIDUE lands on the LARGEST line so the shares always sum
     * back to the applied amount to the cent: 1,00 € over three 10 € lines
     * spreads 0,33 × 3 = 0,99, and the missing cent is added to one line.
     */
    @Test
    void allocateResidueGoesToTheLargestLine() {
        TicketState ts = new TicketState();
        TicketItem first = addLine(ts, "10.00", "1");
        TicketItem second = addLine(ts, "10.00", "1");
        TicketItem third = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("1.00"));
        ts.recomputeTotal();
        BigDecimal sum = first.globalDiscountShare
                .add(second.globalDiscountShare).add(third.globalDiscountShare);
        assertEquals(0, new BigDecimal("1.00").compareTo(sum));
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("29.00").compareTo(ts.totalAmount));
    }

    /**
     * MONEY PRODUCTS are excluded from the base AND from the allocation: a
     * gift card is value, not goods — discounting it would create money.
     */
    @Test
    void allocateExcludesMoneyProducts() {
        TicketState ts = new TicketState();
        TicketItem goods = addLine(ts, "10.00", "1");
        TicketItem giftCard = addLine(ts, "50.00", "1");
        giftCard.moneyProduct = true;
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(goods.globalDiscountShare));
        assertNull(giftCard.globalDiscountShare);
    }

    /**
     * NEGATIVE lines (deposits) stay out of the base and receive no share:
     * a discount never reduces what the customer is owed back.
     */
    @Test
    void allocateIgnoresNegativeLines() {
        TicketState ts = new TicketState();
        TicketItem goods = addLine(ts, "10.00", "1");
        TicketItem deposit = addLine(ts, "-2.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(goods.globalDiscountShare));
        assertNull(deposit.globalDiscountShare);
    }

    /**
     * A ticket whose only positive value is a gift card has an EMPTY base:
     * nothing is applied ({@code base.signum() <= 0} guard).
     */
    @Test
    void allocateReturnsWhenBaseIsEmpty() {
        TicketState ts = new TicketState();
        TicketItem giftCard = addLine(ts, "50.00", "1");
        giftCard.moneyProduct = true;
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("5.00"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
        assertNull(giftCard.globalDiscountShare);
    }

    /**
     * A percentage so small it rounds to zero applies NOTHING rather than a
     * zero-amount discount ({@code amount.signum() <= 0} guard).
     */
    @Test
    void allocateReturnsWhenAmountRoundsToZero() {
        TicketState ts = new TicketState();
        addLine(ts, "0.10", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("1"));
        ts.recomputeTotal();
        assertNull(ts.globalDiscountApplied);
    }

    /**
     * The allocation is IDEMPOTENT across recomputations: shares are reset
     * and rebuilt from the pre-share line totals, so a second scan (or a
     * second call) never compounds the discount.
     */
    @Test
    void allocateIsIdempotentAcrossRecomputations() {
        TicketState ts = new TicketState();
        TicketItem line = addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        ts.recomputeTotal();
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("1.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("1.00").compareTo(line.globalDiscountShare));
        assertEquals(0, new BigDecimal("9.00").compareTo(ts.totalAmount));
    }

    /**
     * The request SURVIVES a cart change and RE-ALLOCATES on the new base:
     * 10 % of 10 € becomes 10 % of 30 € when two lines join — the demo's
     * "la remise se ré-alloue au scan suivant".
     */
    @Test
    void allocateReallocatesWhenCartGrows() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        addLine(ts, "20.00", "1");
        ts.recomputeTotal();
        assertEquals(0, new BigDecimal("3.00").compareTo(ts.globalDiscountApplied));
        assertEquals(0, new BigDecimal("27.00").compareTo(ts.totalAmount));
    }

    // --------------------------------------------------
    // getGlobalDiscountLabel / getGlobalDiscountAppliedFormatted
    // --------------------------------------------------

    /**
     * getGlobalDiscountLabel returns null when nothing was applied
     * (globalDiscountApplied == null, true arm of the L381 guard).
     */
    @Test
    void getGlobalDiscountLabelNullWhenNoDiscountApplied() {
        TicketState ts = new TicketState();
        assertNull(ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountLabel on an applied PERCENT request suffixes the rate,
     * trailing zeros stripped (applied non-null false arm of L381, PERCENT true
     * arm of L382).
     */
    @Test
    void getGlobalDiscountLabelPercentSuffixesRate() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("PERCENT", new BigDecimal("10"));
        ts.recomputeTotal();
        assertEquals("REMISE TICKET (10 %)", ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountLabel on an applied non-PERCENT (euro) request returns
     * the bare label with no suffix (PERCENT false arm of L382).
     */
    @Test
    void getGlobalDiscountLabelAmountHasNoSuffix() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3.00"));
        ts.recomputeTotal();
        assertEquals("REMISE TICKET", ts.getGlobalDiscountLabel());
    }

    /**
     * getGlobalDiscountAppliedFormatted returns null when nothing was applied
     * (globalDiscountApplied == null, true arm of the L395 guard).
     */
    @Test
    void getGlobalDiscountAppliedFormattedNullWhenNoDiscount() {
        TicketState ts = new TicketState();
        assertNull(ts.getGlobalDiscountAppliedFormatted());
    }

    /**
     * getGlobalDiscountAppliedFormatted renders the applied amount signed and
     * with a French comma (applied non-null, false arm of the L395 guard).
     */
    @Test
    void getGlobalDiscountAppliedFormattedSignedFrenchComma() {
        TicketState ts = new TicketState();
        addLine(ts, "10.00", "1");
        ts.setGlobalDiscount("GLOBAL_REMISE", new BigDecimal("3.00"));
        ts.recomputeTotal();
        assertEquals("-3,00", ts.getGlobalDiscountAppliedFormatted());
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
