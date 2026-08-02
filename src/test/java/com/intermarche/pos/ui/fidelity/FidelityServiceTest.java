package com.intermarche.pos.ui.fidelity;

import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link FidelityService}.
 * <p>
 * The service is a two-line delegator: it forwards the card to the
 * {@link FidelityState#assignCard(String)} of a mocked {@link PosState} and then
 * wakes the polling via {@link PosState#touch()}. The method is branch-free, so
 * these tests assert exact delegation, the invocation order of the two calls, and
 * that a {@code null} card is forwarded verbatim rather than filtered.
 */
class FidelityServiceTest {

    /**
     * Builds a {@link PosState} mock whose public {@code fidelity} field is itself
     * a mock, so both collaborators can be verified independently.
     *
     * @return a mocked state carrying a mocked {@link FidelityState}
     */
    private PosState newState() {
        PosState state = mock(PosState.class);
        state.fidelity = mock(FidelityState.class);
        return state;
    }

    /**
     * A non-null card is assigned to the fidelity state and the polling is woken,
     * in that order, with no other interaction on the state.
     */
    @Test
    void validateCardAssignsCardThenTouches() {
        FidelityService service = new FidelityService();
        PosState state = newState();
        service.validateCard(state, "1234567890123");
        InOrder order = inOrder(state.fidelity, state);
        order.verify(state.fidelity).assignCard("1234567890123");
        order.verify(state).touch();
        verifyNoMoreInteractions(state.fidelity);
    }

    /**
     * A {@code null} card is forwarded verbatim to {@code assignCard}; the service
     * applies no guard, so the touch still fires.
     */
    @Test
    void validateCardForwardsNullCardVerbatim() {
        FidelityService service = new FidelityService();
        PosState state = newState();
        service.validateCard(state, null);
        verify(state.fidelity).assignCard(null);
        verify(state).touch();
        verifyNoMoreInteractions(state.fidelity);
    }

    /**
     * The verified {@code fidelity} target is the exact instance held by the state,
     * documenting that the service reads {@code state.fidelity} rather than caching
     * its own reference.
     */
    @Test
    void validateCardUsesStatesOwnFidelityInstance() {
        FidelityService service = new FidelityService();
        PosState state = newState();
        FidelityState fidelity = state.fidelity;
        service.validateCard(state, "42");
        assertSame(fidelity, state.fidelity);
        verify(fidelity).assignCard("42");
    }
}
