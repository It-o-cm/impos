package com.intermarche.pos.ui.endorsement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link ControlLevel}, what a control point does when the
 * gesture it watches is about to run (BO-10-02-01 to -10).
 * <p>
 * The reader has three arms — a missing value, a value it recognises, a value
 * it does not — and each is exercised with the fallback held at a level the
 * assertion can tell apart from the answer, so a reader that silently ignored
 * its fallback would fail.
 */
class ControlLevelTest {

    /**
     * A missing value answers the fallback the caller named, which is the
     * control point's own default.
     */
    @Test
    void aMissingValueAnswersTheFallback() {
        assertEquals(ControlLevel.ENDORSE, ControlLevel.of(null, ControlLevel.ENDORSE));
        assertEquals(ControlLevel.NONE, ControlLevel.of(null, ControlLevel.NONE));
    }

    /**
     * The four levels are read whatever the case and the surrounding blanks:
     * the value is typed into a back-office field by a person.
     */
    @Test
    void theFourLevelsAreReadWhateverTheCase() {
        assertEquals(ControlLevel.NONE, ControlLevel.of("NONE", ControlLevel.ENDORSE));
        assertEquals(ControlLevel.WARN, ControlLevel.of("  warn ", ControlLevel.ENDORSE));
        assertEquals(ControlLevel.BLOCK, ControlLevel.of("Block", ControlLevel.ENDORSE));
        assertEquals(ControlLevel.ENDORSE, ControlLevel.of("endorse", ControlLevel.NONE));
    }

    /**
     * A blank value and a level this register does not know both answer the
     * fallback: a value nobody can read must not silently remove a control, nor
     * silently add one.
     */
    @Test
    void anUnreadableValueAnswersTheFallback() {
        assertEquals(ControlLevel.ENDORSE, ControlLevel.of("   ", ControlLevel.ENDORSE));
        assertEquals(ControlLevel.NONE, ControlLevel.of("", ControlLevel.NONE));
        assertEquals(ControlLevel.BLOCK, ControlLevel.of("SUPERVISEUR", ControlLevel.BLOCK));
    }

    /**
     * Two levels let the gesture through and two stop it — the single question
     * every call site asks.
     */
    @Test
    void twoLevelsLetTheGestureThrough() {
        assertTrue(ControlLevel.NONE.allows());
        assertTrue(ControlLevel.WARN.allows());
        assertFalse(ControlLevel.BLOCK.allows());
        assertFalse(ControlLevel.ENDORSE.allows());
    }
}
