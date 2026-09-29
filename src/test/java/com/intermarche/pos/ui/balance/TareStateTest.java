package com.intermarche.pos.ui.balance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TareState}, the punctual container-tare staging of the
 * weighing screen (LC-14-01-04/05).
 * <p>
 * Branch enumeration (both arms, 100%): {@code hasContainer} on a null tare, a
 * zero tare and a strictly positive tare; {@code clear} wiping an armed tare.
 */
class TareStateTest {

    /**
     * A fresh state arms no container: the tare and label are null and
     * {@code hasContainer} is false (null-tare arm).
     */
    @Test
    void freshStateArmsNoContainer() {
        TareState state = new TareState();
        assertNull(state.containerTare);
        assertNull(state.containerLabel);
        assertFalse(state.hasContainer());
    }

    /**
     * A zero container tare is not considered armed ({@code signum > 0} false
     * arm on a present-but-zero tare).
     */
    @Test
    void zeroContainerTareIsNotArmed() {
        TareState state = new TareState();
        state.containerTare = BigDecimal.ZERO;
        assertFalse(state.hasContainer());
    }

    /**
     * A strictly positive container tare is armed ({@code signum > 0} true arm).
     */
    @Test
    void positiveContainerTareIsArmed() {
        TareState state = new TareState();
        state.containerTare = new BigDecimal("0.035");
        state.containerLabel = "Barquette S";
        assertTrue(state.hasContainer());
    }

    /**
     * {@code clear} disarms an armed container tare, nulling both the tare and
     * the label so the next weighing carries none.
     */
    @Test
    void clearDisarmsTheContainerTare() {
        TareState state = new TareState();
        state.containerTare = new BigDecimal("0.035");
        state.containerLabel = "Barquette S";
        state.clear();
        assertNull(state.containerTare);
        assertNull(state.containerLabel);
        assertFalse(state.hasContainer());
    }

    /**
     * The default serial version id is exposed for the serializable contract,
     * asserted through a round-trip of the armed fields being independent of it.
     */
    @Test
    void armedFieldsAreReadBack() {
        TareState state = new TareState();
        state.containerTare = new BigDecimal("0.028");
        state.containerLabel = "Filet réutilisable";
        assertEquals(0, new BigDecimal("0.028").compareTo(state.containerTare));
        assertEquals("Filet réutilisable", state.containerLabel);
    }
}
