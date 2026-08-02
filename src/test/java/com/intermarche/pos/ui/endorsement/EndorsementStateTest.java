package com.intermarche.pos.ui.endorsement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EndorsementState}.
 * <p>
 * The class is a plain serializable POJO holding the parked-gesture half of the
 * endorsement pattern. Its only branch is the {@code value != null} ternary in
 * {@link EndorsementState#requestPriceModification}; both arms are covered
 * (non-null value kept, null value defaulted to {@link BigDecimal#ZERO}). The
 * remaining tests pin the default field state and the full effect of every
 * lifecycle method. Assertions use absolute expected values and every test is
 * fully isolated.
 */
class EndorsementStateTest {

    /**
     * Freshly constructed state carries the documented defaults.
     */
    @Test
    void defaultsAreInactiveAndEmpty() {
        EndorsementState state = new EndorsementState();
        assertFalse(state.active);
        assertNull(state.requestedAction);
        assertNull(state.scannedBadge);
        assertNull(state.error);
        assertNull(state.pendingPriceType);
        assertNull(state.pendingTargetUid);
        assertEquals(BigDecimal.ZERO, state.pendingValue);
    }

    /**
     * {@code request} activates the state, records the action, and clears any
     * previous error, badge and pending price fields.
     */
    @Test
    void requestActivatesAndClearsPreviousState() {
        EndorsementState state = new EndorsementState();
        state.error = "boom";
        state.scannedBadge = "B1";
        state.pendingPriceType = "REMISE";
        state.pendingTargetUid = "L1";
        state.pendingValue = new BigDecimal("5.00");
        state.request("CANCEL_TICKET");
        assertTrue(state.active);
        assertEquals("CANCEL_TICKET", state.requestedAction);
        assertNull(state.error);
        assertNull(state.scannedBadge);
        assertNull(state.pendingPriceType);
        assertNull(state.pendingTargetUid);
        assertEquals(BigDecimal.ZERO, state.pendingValue);
    }

    /**
     * {@code requestPriceModification} with a non-null value keeps that value
     * (the {@code value != null} true arm) and populates the price triple.
     */
    @Test
    void requestPriceModificationKeepsNonNullValue() {
        EndorsementState state = new EndorsementState();
        state.error = "boom";
        state.scannedBadge = "B1";
        BigDecimal value = new BigDecimal("12.34");
        state.requestPriceModification("DISCOUNT", "LINE-9", value);
        assertTrue(state.active);
        assertEquals("PRICE_MODIFICATION", state.requestedAction);
        assertEquals("DISCOUNT", state.pendingPriceType);
        assertEquals("LINE-9", state.pendingTargetUid);
        assertEquals(value, state.pendingValue);
        assertNull(state.error);
        assertNull(state.scannedBadge);
    }

    /**
     * {@code requestPriceModification} with a null value defaults it to
     * {@link BigDecimal#ZERO} (the {@code value != null} false arm).
     */
    @Test
    void requestPriceModificationDefaultsNullValueToZero() {
        EndorsementState state = new EndorsementState();
        state.requestPriceModification("FORCE_PRICE", "LINE-1", null);
        assertTrue(state.active);
        assertEquals("PRICE_MODIFICATION", state.requestedAction);
        assertEquals("FORCE_PRICE", state.pendingPriceType);
        assertEquals("LINE-1", state.pendingTargetUid);
        assertEquals(BigDecimal.ZERO, state.pendingValue);
        assertNull(state.error);
        assertNull(state.scannedBadge);
    }

    /**
     * {@code clear} deactivates the state and resets action, error, badge and
     * pending price fields.
     */
    @Test
    void clearResetsEverything() {
        EndorsementState state = new EndorsementState();
        state.requestPriceModification("REMISE", "LINE-2", new BigDecimal("7.50"));
        state.error = "denied";
        state.scannedBadge = "B2";
        state.clear();
        assertFalse(state.active);
        assertNull(state.requestedAction);
        assertNull(state.error);
        assertNull(state.scannedBadge);
        assertNull(state.pendingPriceType);
        assertNull(state.pendingTargetUid);
        assertEquals(BigDecimal.ZERO, state.pendingValue);
    }

    /**
     * {@code setScannedBadge} stores the given badge id.
     */
    @Test
    void setScannedBadgeStoresBadge() {
        EndorsementState state = new EndorsementState();
        state.setScannedBadge("BADGE-42");
        assertEquals("BADGE-42", state.scannedBadge);
    }

    /**
     * {@code clearScannedBadge} resets the scanned badge to null.
     */
    @Test
    void clearScannedBadgeResetsBadge() {
        EndorsementState state = new EndorsementState();
        state.setScannedBadge("BADGE-42");
        state.clearScannedBadge();
        assertNull(state.scannedBadge);
    }
}
