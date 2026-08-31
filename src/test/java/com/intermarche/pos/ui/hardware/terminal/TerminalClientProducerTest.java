package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TerminalClientProducer}: the mode string selects
 * the implementation, and an unknown mode falls back to the virtual
 * terminal (every switch arm covered).
 */
class TerminalClientProducerTest {

    /** The producer under test. */
    private TerminalClientProducer producer;

    /** The virtual client bean the producer hands out in virtual mode. */
    private VirtualTerminalClient virtualClient;

    /**
     * Wires a fresh producer with a mocked virtual client and the Verifone
     * connection defaults.
     */
    @BeforeEach
    void setUp() {
        producer = new TerminalClientProducer();
        virtualClient = mock(VirtualTerminalClient.class);
        producer.virtualTerminalClient = virtualClient;
        producer.verifoneHost = "127.0.0.1";
        producer.verifonePort = 8200;
        producer.verifoneTimeoutMs = 1000;
    }

    /**
     * Mode {@code virtual} returns the simulator bean (virtual arm).
     */
    @Test
    void virtualModeReturnsVirtualBean() {
        producer.mode = "virtual";
        assertSame(virtualClient, producer.paymentTerminalClient());
    }

    /**
     * Mode {@code auto} returns the auto-accept implementation (auto arm).
     */
    @Test
    void autoModeReturnsAutoAccept() {
        producer.mode = "auto";
        assertTrue(producer.paymentTerminalClient() instanceof AutoAcceptTerminalClient);
    }

    /**
     * Mode {@code verifone} returns the Verifone skeleton (verifone arm).
     */
    @Test
    void verifoneModeReturnsVerifoneClient() {
        producer.mode = "verifone";
        assertTrue(producer.paymentTerminalClient() instanceof VerifoneTerminalClient);
    }

    /**
     * An unknown mode falls back to the virtual bean (default arm).
     */
    @Test
    void unknownModeFallsBackToVirtual() {
        producer.mode = "typo";
        assertSame(virtualClient, producer.paymentTerminalClient());
    }
}
