package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributeCatalog;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** LC-02-03-04: a deposit belongs to its bottle throughout sale corrections. */
class LinkedArticleServiceTest {
    /** Builds an ordinary or linked line without catalog/database dependencies. */
    private TicketState.TicketItem line(PosState state, String ean, String linked, String quantity) {
        TicketState.TicketItem item = new TicketState.TicketItem(ean, null, ean,
                BigDecimal.ONE, new BigDecimal(quantity), BigDecimal.ZERO);
        item.linkedArticleEan = linked;
        state.ticket.items.add(item);
        return item;
    }

    /** Makes localized refusals observable without booting CDI. */
    private Messages messages() {
        Messages i18n = mock(Messages.class);
        when(i18n.format(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        return i18n;
    }

    /** Normal admission honors the parent context and signed return mode. */
    private void admit(PosState state, String ean, BigDecimal quantity) {
        state.ticket.addItem(ean, null, ean, BigDecimal.ONE, quantity, BigDecimal.ZERO);
        state.ticket.items.getLast().linkedArticleEan = "";
    }

    /** Null product, mismatched EAN and previously captured configuration are preserved. */
    @Test void snapshotFreezesTheAssociationOnMatchingLines() {
        PosState state = new PosState();
        var a = line(state, "A", null, "1");
        var b = line(state, "B", null, "1");
        var old = line(state, "A", "C", "1");
        LinkedArticleService.snapshot(state.ticket, null);
        Product product = new Product();
        product.ean = "A";
        product.attributes.put(ProductAttributeCatalog.LINKED_ARTICLE_EAN, " B ");
        LinkedArticleService.snapshot(state.ticket, product);
        assertEquals("B", a.linkedArticleEan);
        assertNull(b.linkedArticleEan);
        assertEquals("C", old.linkedArticleEan);
    }

    /** Empty, ordinary and reentrant carts do not invoke admission or touch their totals. */
    @Test void ordinaryAndReentrantCartsAreUntouched() {
        PosState state = new PosState();
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        line(state, "A", null, "1");
        line(state, "B", "  ", "1");
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        state.linkedArticlesUpdating = true;
        line(state, "C", "D", "1");
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        assertEquals(3, state.ticket.items.size());
    }

    /** Three bottles produce three deposits; later corrections update the same companion. */
    @Test void depositFollowsBottleQuantityAndNeverMergesWithIndependentSale() {
        PosState state = new PosState();
        var independent = line(state, "B", "", "2");
        var root = line(state, "A", "B", "3");
        state.lastEnteredItemId = root.uid;
        AtomicInteger calls = new AtomicInteger();
        LinkedArticleService.synchronize(state, (ean, qty) -> {
            calls.incrementAndGet(); admit(state, ean, qty);
        }, messages());
        var deposit = state.ticket.items.getLast();
        assertEquals(root.uid, deposit.linkedParentUid);
        assertEquals(new BigDecimal("3"), deposit.quantity);
        assertEquals(new BigDecimal("2"), independent.quantity);
        assertEquals(root.uid, state.lastEnteredItemId);
        deposit.valuedTotal = BigDecimal.TEN;
        root.quantity = new BigDecimal("2");
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        assertEquals(root.quantity, deposit.quantity);
        assertNull(deposit.valuedTotal);
        deposit.valuedTotal = BigDecimal.ONE;
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        assertEquals(BigDecimal.ONE, deposit.valuedTotal);
        assertEquals(1, calls.get());
        assertEquals(List.of(root.uid, deposit.uid), LinkedArticleService.familyUids(state.ticket, root.uid));
    }

    /** Each principal has its own companion, and recursive associations form one family. */
    @Test void separatePrincipalsAndChainsRemainDistinct() {
        PosState state = new PosState();
        var root = line(state, "A", "B", "1");
        var other = line(state, "D", "B", "2");
        var unrelated = line(state, "X", "", "1");
        unrelated.linkedParentUid = root.uid;
        LinkedArticleService.synchronize(state, (ean, qty) -> {
            admit(state, ean, qty);
            if (ean.equals("B")) state.ticket.items.getLast().linkedArticleEan = "C";
        }, messages());
        assertEquals(7, state.ticket.items.size());
        assertEquals(4, LinkedArticleService.familyUids(state.ticket, root.uid).size());
        assertEquals(3, LinkedArticleService.familyUids(state.ticket, other.uid).size());
        assertEquals(java.util.Arrays.asList((String) null), LinkedArticleService.familyUids(state.ticket, null));
        // A malformed persisted cycle remains finite during family collection.
        root.linkedParentUid = unrelated.uid;
        assertEquals(4, LinkedArticleService.familyUids(state.ticket, root.uid).size());
    }

    /** Returns and collection vouchers retain their sign, destination and operator context. */
    @Test void returnedDepositAndAdmissionContextFollowThePrincipal() {
        PosState state = new PosState();
        var root = line(state, "A", "B", "-3");
        root.toCollect = true;
        state.linkedArticleParentUid = "saved";
        state.armedQuantity = BigDecimal.TEN;
        state.collectArmed = true;
        state.lineJustEntered = false;
        LinkedArticleService.synchronize(state, (ean, qty) -> {
            assertEquals(new BigDecimal("3"), qty);
            assertTrue(state.returnMode);
            assertTrue(state.collectArmed);
            assertNull(state.armedQuantity);
            admit(state, ean, qty);
        }, messages());
        var deposit = state.ticket.items.getLast();
        assertEquals(new BigDecimal("-3"), deposit.quantity);
        assertTrue(deposit.returnLine);
        assertTrue(deposit.toCollect);
        assertFalse(state.returnMode);
        assertTrue(state.collectArmed);
        assertFalse(state.lineJustEntered);
        assertEquals(BigDecimal.TEN, state.armedQuantity);
        assertEquals("saved", state.linkedArticleParentUid);
        assertNull(state.lastEnteredItemId);
    }

    /** Missing or refused deposits remove a new bottle and clear deferred admission prompts. */
    @Test void refusedCompanionNeverLeavesAnUnchargedBottle() {
        PosState state = new PosState();
        line(state, "A", "B", "1");
        state.entryPrompt.active = true;
        state.ageCheck.active = true;
        LinkedArticleService.synchronize(state, (ean, qty) -> {}, messages());
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("op.articleLieIndisponible", state.ticket.transientError);
        assertFalse(state.entryPrompt.active);
        assertFalse(state.ageCheck.active);
        line(state, "A", "B", "1");
        state.ticket.setError("original refusal");
        LinkedArticleService.synchronize(state, (ean, qty) -> {}, messages());
        assertEquals("original refusal", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /** Self and indirect loops undo all newly admitted descendants. */
    @Test void cyclesRollbackTheCompleteNewFamily() {
        PosState state = new PosState();
        line(state, "A", "A", "1");
        LinkedArticleService.synchronize(state, (ean, qty) -> fail(), messages());
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("op.articleLieCycle", state.ticket.transientError);
        line(state, "A", "B", "1");
        LinkedArticleService.synchronize(state, (ean, qty) -> {
            admit(state, ean, qty);
            state.ticket.items.getLast().linkedArticleEan = "A";
        }, messages());
        assertTrue(state.ticket.items.isEmpty());
    }

    /** A failed later companion restores the last accepted bottle and deposit amounts. */
    @Test void failedCorrectionRestoresPreviouslyRecordedFamily() {
        PosState state = new PosState();
        var root = line(state, "A", "B", "3");
        root.linkedRecordedQuantity = BigDecimal.ONE;
        var deposit = line(state, "B", "C", "1");
        deposit.linkedParentUid = root.uid;
        deposit.linkedRecordedQuantity = BigDecimal.ONE;
        deposit.valuedTotal = BigDecimal.TEN;
        LinkedArticleService.synchronize(state, (ean, qty) -> {}, messages());
        assertEquals(2, state.ticket.items.size());
        assertEquals(BigDecimal.ONE, root.quantity);
        assertEquals(BigDecimal.ONE, deposit.quantity);
        assertEquals(BigDecimal.TEN, deposit.valuedTotal);
        root.linkedRecordedQuantity = BigDecimal.ZERO;
        LinkedArticleService.synchronize(state, (ean, qty) -> {}, messages());
        assertFalse(state.ticket.items.contains(root));
    }

    /** Unexpected admission errors release the guard and restore the operator context. */
    @Test void exceptionReleasesTheAdmissionContext() {
        PosState state = new PosState();
        var root = line(state, "A", "B", "1");
        state.lastEnteredItemId = root.uid;
        assertThrows(IllegalStateException.class, () -> LinkedArticleService.synchronize(state,
                (ean, qty) -> { throw new IllegalStateException("test"); }, messages()));
        assertFalse(state.linkedArticlesUpdating);
        assertNull(state.linkedArticleParentUid);
        assertEquals(root.uid, state.lastEnteredItemId);
        state.linkedArticleParentUid = "old";
        state.linkedArticlesUpdating = true;
        state.clearTicket();
        assertNull(state.linkedArticleParentUid);
        assertFalse(state.linkedArticlesUpdating);
    }
}
