package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.endorsement.EndorsementState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthScanHandler}.
 * <p>
 * The handler is the {@code @Priority(0)} link of the scan chain: it
 * recognizes employee badges via the {@code scan.pattern.badge} regex and
 * routes a match into the endorsement mailbox (top precedence) or the lock
 * mailbox, ignoring the badge when an operator is logged in with no modal
 * open. Every collaborator is a Mockito mock: the {@link PosState} whose
 * public {@code endorsement}/{@code auth} sub-states are themselves mocks
 * ({@link EndorsementState}, {@link AuthState}) so their {@code active}
 * field and {@code setScannedBadge}/{@code touch}/{@code isLocked}
 * interactions can be driven and verified. The {@code badgePattern} field
 * is a package-private collaborator set directly. Four decision points,
 * eight branches, are exercised by five isolated cases.
 */
class AuthScanHandlerTest {

    /** Regex recognizing a four-digit badge, injected into the handler. */
    private static final String BADGE_PATTERN = "[0-9]{4}";

    /** A code matching {@link #BADGE_PATTERN}. */
    private static final String BADGE_CODE = "1234";

    /** A code NOT matching {@link #BADGE_PATTERN}. */
    private static final String NON_BADGE_CODE = "ABC";

    /**
     * Builds a handler wired with the four-digit badge pattern.
     *
     * @return a ready-to-test handler
     */
    private AuthScanHandler newHandler() {
        AuthScanHandler handler = new AuthScanHandler();
        handler.badgePattern = BADGE_PATTERN;
        return handler;
    }

    /**
     * Assembles a mock {@link PosState} whose endorsement and auth
     * sub-states are the supplied mocks.
     *
     * @param endorsement the endorsement mailbox mock
     * @param auth the auth mailbox mock
     * @return the wired state mock
     */
    private PosState newState(EndorsementState endorsement, AuthState auth) {
        PosState state = mock(PosState.class);
        state.endorsement = endorsement;
        state.auth = auth;
        return state;
    }

    /**
     * An already-handled context short-circuits: the handler returns before
     * touching the state, its code or either mailbox.
     */
    @Test
    void alreadyHandledShortCircuits() {
        EndorsementState endorsement = mock(EndorsementState.class);
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        ctx.handled = true;
        newHandler().handle(ctx);
        assertTrue(ctx.handled);
        verifyNoInteractions(state);
        verifyNoInteractions(endorsement);
        verifyNoInteractions(auth);
    }

    /**
     * A matching badge with an active endorsement lands in the endorsement
     * mailbox, touches the state, marks the context handled and never
     * consults the lock screen.
     */
    @Test
    void matchingBadgeGoesToActiveEndorsement() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = true;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(endorsement).setScannedBadge(BADGE_CODE);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(state, never()).isLocked();
        verify(auth, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A matching badge with no active endorsement but a locked register
     * lands in the auth mailbox, touches the state and marks the context
     * handled.
     */
    @Test
    void matchingBadgeGoesToLockScreenWhenLocked() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(true);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(auth).setScannedBadge(BADGE_CODE);
        verify(state).touch();
        assertTrue(ctx.handled);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
    }

    /**
     * A matching badge with no active endorsement and an unlocked register
     * is deliberately ignored: no mailbox write, no touch, context stays
     * unhandled.
     */
    @Test
    void matchingBadgeIgnoredWhenUnlockedAndNoEndorsement() {
        EndorsementState endorsement = mock(EndorsementState.class);
        endorsement.active = false;
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        when(state.isLocked()).thenReturn(false);
        ScanContext ctx = new ScanContext(BADGE_CODE, state);
        newHandler().handle(ctx);
        verify(endorsement, never()).setScannedBadge(BADGE_CODE);
        verify(auth, never()).setScannedBadge(BADGE_CODE);
        verify(state, never()).touch();
        assertFalse(ctx.handled);
    }

    /**
     * A non-badge code is not recognized: no mailbox is consulted, the state
     * is untouched and the context stays unhandled for the next link.
     */
    @Test
    void nonBadgeCodeIsNotRecognized() {
        EndorsementState endorsement = mock(EndorsementState.class);
        AuthState auth = mock(AuthState.class);
        PosState state = newState(endorsement, auth);
        ScanContext ctx = new ScanContext(NON_BADGE_CODE, state);
        newHandler().handle(ctx);
        assertFalse(ctx.handled);
        verifyNoInteractions(endorsement);
        verifyNoInteractions(auth);
        verify(state, never()).touch();
    }
}
