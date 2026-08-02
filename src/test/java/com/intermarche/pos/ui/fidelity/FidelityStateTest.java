package com.intermarche.pos.ui.fidelity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FidelityState}.
 * <p>
 * The class is a two-field working copy with a single guarded mutator
 * ({@link FidelityState#assignCard(String)}) and a reset ({@link FidelityState#clear()}).
 * The mutator carries the only conditional logic: the compound guard
 * {@code card != null && card.length() > 2}, whose four branches are exercised
 * here — null card, non-null too-short card, non-null boundary-length card, and a
 * valid card. Assertions use absolute expected values and there is no shared state.
 */
class FidelityStateTest {

    /**
     * A freshly constructed state is inactive and carries an empty label.
     */
    @Test
    void newStateIsInactiveWithEmptyLabel() {
        FidelityState state = new FidelityState();
        assertFalse(state.active);
        assertEquals("", state.label);
    }

    /**
     * A null card is silently ignored: the false arm of the {@code card != null}
     * guard leaves the state untouched.
     */
    @Test
    void assignCardIgnoresNull() {
        FidelityState state = new FidelityState();
        state.assignCard(null);
        assertFalse(state.active);
        assertEquals("", state.label);
    }

    /**
     * A card of exactly two characters is too short: the false arm of the
     * {@code length() > 2} guard leaves the state untouched.
     */
    @Test
    void assignCardIgnoresTooShort() {
        FidelityState state = new FidelityState();
        state.assignCard("12");
        assertFalse(state.active);
        assertEquals("", state.label);
    }

    /**
     * A card of exactly three characters clears the length boundary: the true arm
     * of both guards attaches the card and activates the state.
     */
    @Test
    void assignCardAcceptsBoundaryLength() {
        FidelityState state = new FidelityState();
        state.assignCard("123");
        assertTrue(state.active);
        assertEquals("123", state.label);
    }

    /**
     * A valid card activates the state and stores the card number verbatim.
     */
    @Test
    void assignCardAttachesValidCard() {
        FidelityState state = new FidelityState();
        state.assignCard("9876543210");
        assertTrue(state.active);
        assertEquals("9876543210", state.label);
    }

    /**
     * A second valid card replaces the first (last presented wins).
     */
    @Test
    void assignCardReplacesPreviousCard() {
        FidelityState state = new FidelityState();
        state.assignCard("111");
        state.assignCard("222");
        assertTrue(state.active);
        assertEquals("222", state.label);
    }

    /**
     * {@link FidelityState#clear()} detaches an attached card, resetting both fields.
     */
    @Test
    void clearResetsAttachedState() {
        FidelityState state = new FidelityState();
        state.assignCard("9876543210");
        state.clear();
        assertFalse(state.active);
        assertEquals("", state.label);
    }

    /**
     * {@link FidelityState#clear()} is idempotent on an already-empty state.
     */
    @Test
    void clearIsIdempotentOnEmptyState() {
        FidelityState state = new FidelityState();
        state.clear();
        assertFalse(state.active);
        assertEquals("", state.label);
    }
}
