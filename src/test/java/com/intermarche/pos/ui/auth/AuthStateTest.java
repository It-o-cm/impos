package com.intermarche.pos.ui.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthState}, covering the boot-locked defaults and
 * every field mutation performed by login, logout and the badge mailbox
 * accessors.
 */
class AuthStateTest {

    /**
     * Verifies that a freshly constructed state boots locked with an empty
     * operator name, no operator id and an empty badge mailbox.
     */
    @Test
    void defaultsAreLockedWithNoOperator() {
        AuthState state = new AuthState();
        Assertions.assertTrue(state.isLocked);
        Assertions.assertEquals("", state.operatorName);
        Assertions.assertNull(state.operatorId);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertNull(state.getOperatorId());
    }

    /**
     * Verifies that login stores the operator identity, unlocks the register
     * and clears any previously scanned badge.
     */
    @Test
    void loginStoresOperatorUnlocksAndClearsBadge() {
        AuthState state = new AuthState();
        state.setScannedBadge("BADGE-42");
        state.login(7L, "Alice");
        Assertions.assertEquals(7L, state.operatorId);
        Assertions.assertEquals("Alice", state.operatorName);
        Assertions.assertFalse(state.isLocked);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertEquals(7L, state.getOperatorId());
    }

    /**
     * Verifies that logout re-locks the register, forgets the operator
     * identity and clears the badge mailbox.
     */
    @Test
    void logoutLocksAndForgetsOperator() {
        AuthState state = new AuthState();
        state.login(7L, "Alice");
        state.setScannedBadge("BADGE-99");
        state.logout();
        Assertions.assertTrue(state.isLocked);
        Assertions.assertEquals("", state.operatorName);
        Assertions.assertNull(state.operatorId);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertNull(state.getOperatorId());
    }

    /**
     * Verifies that getOperatorId returns the id set by login.
     */
    @Test
    void getOperatorIdReturnsLoggedInId() {
        AuthState state = new AuthState();
        state.login(123L, "Bob");
        Assertions.assertEquals(123L, state.getOperatorId());
    }

    /**
     * Verifies that setScannedBadge deposits the badge into the mailbox.
     */
    @Test
    void setScannedBadgeDepositsBadge() {
        AuthState state = new AuthState();
        state.setScannedBadge("BADGE-1");
        Assertions.assertEquals("BADGE-1", state.scannedBadgeId);
    }

    /**
     * Verifies that clearScannedBadge empties the mailbox.
     */
    @Test
    void clearScannedBadgeEmptiesMailbox() {
        AuthState state = new AuthState();
        state.setScannedBadge("BADGE-1");
        state.clearScannedBadge();
        Assertions.assertNull(state.scannedBadgeId);
    }
}
