package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RealHardwareResource} — the production hardware
 * boundary in its stub state: every endpoint answers OK, the read endpoints
 * answer the register's neutral conventions (no weight, drawer closed), and
 * no gesture throws. When the real drivers land, these tests evolve with
 * them.
 */
class RealHardwareResourceTest {

    /**
     * {@code getWeight()} answers the "nothing on the plate" convention,
     * French decimal.
     */
    @Test
    void getWeightAnswersZero() {
        assertEquals("0,000", new RealHardwareResource().getWeight());
    }

    /**
     * {@code getDrawerStatus()} answers CLOSED — the state that never traps
     * the register behind the drawer guard.
     */
    @Test
    void getDrawerStatusAnswersClosed() {
        assertEquals("CLOSED", new RealHardwareResource().getDrawerStatus());
    }

    /**
     * The write stubs — display, drawer pulse, print (null content
     * included), cut — all accept the gesture without throwing.
     */
    @Test
    void writeStubsAcceptEveryGesture() {
        RealHardwareResource resource = new RealHardwareResource();
        assertDoesNotThrow(() -> resource.setDisplay("BONJOUR"));
        assertDoesNotThrow(resource::openDrawer);
        assertDoesNotThrow(() -> resource.printTicket("TICKET"));
        assertDoesNotThrow(() -> resource.printTicket(null));
        assertDoesNotThrow(resource::cutPaper);
    }
}
