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
        Assertions.assertNull(state.operatorBadgeId);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertNull(state.getOperatorId());
    }

    /**
     * Verifies that login stores the operator identity and badge, unlocks the
     * register and clears any previously scanned badge.
     */
    @Test
    void loginStoresOperatorUnlocksAndClearsBadge() {
        AuthState state = new AuthState();
        state.setScannedBadge("BADGE-42");
        state.login(7L, "Alice", "12341234");
        Assertions.assertEquals(7L, state.operatorId);
        Assertions.assertEquals("Alice", state.operatorName);
        Assertions.assertEquals("12341234", state.operatorBadgeId);
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
        state.login(7L, "Alice", "12341234");
        state.setScannedBadge("BADGE-99");
        state.logout();
        Assertions.assertTrue(state.isLocked);
        Assertions.assertEquals("", state.operatorName);
        Assertions.assertNull(state.operatorId);
        Assertions.assertNull(state.operatorBadgeId);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertNull(state.getOperatorId());
    }

    /**
     * Verifies that getOperatorId returns the id set by login.
     */
    @Test
    void getOperatorIdReturnsLoggedInId() {
        AuthState state = new AuthState();
        state.login(123L, "Bob", "99999999");
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

    /**
     * Verifies that pause moves the logged-in operator identity aside into the
     * lockedOperator fields, clears the active operator, locks the register and
     * empties the badge and close-request mailboxes.
     */
    @Test
    void pauseMovesOperatorAsideAndLocks() {
        AuthState state = new AuthState();
        state.login(7L, "Alice", "12341234");
        state.setScannedBadge("BADGE-77");
        state.closeRequestedByBadge = true;
        state.pause();
        Assertions.assertEquals(7L, state.lockedOperatorId);
        Assertions.assertEquals("Alice", state.lockedOperatorName);
        Assertions.assertEquals("12341234", state.lockedOperatorBadgeId);
        Assertions.assertTrue(state.isLocked);
        Assertions.assertNull(state.operatorId);
        Assertions.assertEquals("", state.operatorName);
        Assertions.assertNull(state.operatorBadgeId);
        Assertions.assertNull(state.scannedBadgeId);
        Assertions.assertFalse(state.closeRequestedByBadge);
    }

    /**
     * Verifies that isPaused is false on a register that is not locked, even
     * when a locked-operator id lingers (the isLocked arm false).
     */
    @Test
    void isPausedFalseWhenNotLocked() {
        AuthState state = new AuthState();
        state.isLocked = false;
        state.lockedOperatorId = 7L;
        Assertions.assertFalse(state.isPaused());
    }

    /**
     * Verifies that isPaused is false on a locked register with no locked
     * operator (a free lock / fermeture, the lockedOperatorId arm null).
     */
    @Test
    void isPausedFalseWhenLockedWithoutLockedOperator() {
        AuthState state = new AuthState();
        state.isLocked = true;
        state.lockedOperatorId = null;
        Assertions.assertFalse(state.isPaused());
    }

    /**
     * Verifies that isPaused is true on a locked register that holds a locked
     * operator (both arms true).
     */
    @Test
    void isPausedTrueWhenLockedWithLockedOperator() {
        AuthState state = new AuthState();
        state.login(7L, "Alice", "12341234");
        state.pause();
        Assertions.assertTrue(state.isPaused());
    }

    /**
     * Verifies that login clears any lingering locked-operator identity and the
     * forced-password-change flag left by a previous pause.
     */
    @Test
    void loginClearsLockedOperatorAndPasswordFlag() {
        AuthState state = new AuthState();
        state.lockedOperatorId = 3L;
        state.lockedOperatorName = "Old";
        state.lockedOperatorBadgeId = "00000000";
        state.passwordChangeRequired = true;
        state.login(7L, "Alice", "12341234");
        Assertions.assertNull(state.lockedOperatorId);
        Assertions.assertEquals("", state.lockedOperatorName);
        Assertions.assertNull(state.lockedOperatorBadgeId);
        Assertions.assertTrue(state.passwordChangeRequired);
    }

    /**
     * Verifies that logout clears the locked-operator identity and the
     * forced-password-change flag (a fermeture forgets everything).
     */
    @Test
    void logoutClearsLockedOperatorAndPasswordFlag() {
        AuthState state = new AuthState();
        state.login(7L, "Alice", "12341234");
        state.pause();
        state.passwordChangeRequired = true;
        state.logout();
        Assertions.assertNull(state.lockedOperatorId);
        Assertions.assertEquals("", state.lockedOperatorName);
        Assertions.assertNull(state.lockedOperatorBadgeId);
        Assertions.assertFalse(state.passwordChangeRequired);
    }
}
