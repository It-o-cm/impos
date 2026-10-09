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

    /**
     * A fresh register arms no supervisor identification: an unexpected badge
     * has nowhere to land (the zero-deadline arm).
     */
    @Test
    void supervisorBadgeIsNotArmedByDefault() {
        AuthState state = new AuthState();
        Assertions.assertFalse(state.supervisorBadgeArmed());
        Assertions.assertNull(state.supervisorBadgeId);
    }

    /**
     * Arming opens the supervisor mailbox for the given span (the
     * deadline-in-the-future arm).
     */
    @Test
    void armSupervisorBadgeOpensTheMailbox() {
        AuthState state = new AuthState();
        state.armSupervisorBadge(3000L);
        Assertions.assertTrue(state.supervisorBadgeArmed());
    }

    /**
     * An arming whose deadline has passed is no arming at all: a panel that
     * left the screen stops receiving badges without anyone disarming it (the
     * deadline-in-the-past arm).
     */
    @Test
    void anExpiredArmingNoLongerReceivesBadges() {
        AuthState state = new AuthState();
        state.armSupervisorBadge(-1L);
        Assertions.assertFalse(state.supervisorBadgeArmed());
    }

    /**
     * A badge deposited for a supervisor identification is also recorded as
     * PRESENTED, like the opening's own mailbox does: the proof that a badge was
     * physically read is one and the same for both identifications.
     */
    @Test
    void setSupervisorBadgeDepositsAndRecordsThePresentation() {
        AuthState state = new AuthState();
        state.setSupervisorBadge("BADGE-9");
        Assertions.assertEquals("BADGE-9", state.supervisorBadgeId);
        Assertions.assertEquals("BADGE-9", state.takePresentedBadge());
    }

    /**
     * Taking the supervisor mailbox empties it — it is a one-shot — and leaves
     * the arming standing, so a supervisor who presented the wrong badge may
     * present another.
     */
    @Test
    void takeSupervisorBadgeIsOneShotAndKeepsTheArming() {
        AuthState state = new AuthState();
        state.armSupervisorBadge(3000L);
        state.setSupervisorBadge("BADGE-9");
        Assertions.assertEquals("BADGE-9", state.takeSupervisorBadge());
        Assertions.assertNull(state.takeSupervisorBadge());
        Assertions.assertTrue(state.supervisorBadgeArmed());
    }

    /**
     * An empty supervisor mailbox answers null rather than throwing.
     */
    @Test
    void takeSupervisorBadgeOnAnEmptyMailboxAnswersNull() {
        Assertions.assertNull(new AuthState().takeSupervisorBadge());
    }

    /**
     * The three exits — login, logout and pause — each drop the supervisor
     * mailbox AND its arming: a badge or an arming surviving a change of
     * operator would route the next scan to a panel from the previous shift.
     */
    @Test
    void loginLogoutAndPauseAllDropTheSupervisorArming() {
        for (int exit = 0; exit < 3; exit++) {
            AuthState state = new AuthState();
            state.armSupervisorBadge(3000L);
            state.setSupervisorBadge("BADGE-9");
            if (exit == 0) {
                state.login(7L, "Alice", "007");
            } else if (exit == 1) {
                state.logout();
            } else {
                state.pause();
            }
            Assertions.assertNull(state.supervisorBadgeId);
            Assertions.assertFalse(state.supervisorBadgeArmed());
        }
    }
}
