package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.hardware.HardwareClient;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link TerminalClientProducer}: the mode string selects
 * the implementation, and an unknown mode falls back to the virtual
 * terminal (every switch arm covered). The produced bean is always a
 * {@link DegradedModePaymentTerminalClient}; each test unwraps it via
 * {@link DegradedModePaymentTerminalClient#configured()} to assert the
 * resolved implementation.
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
        // The producer hands its resolver to the two clients it builds; without
        // one, a terminal failure would name its refusal on a null resolver.
        producer.i18n = TestMessages.french();
        virtualClient = mock(VirtualTerminalClient.class);
        producer.virtualTerminalClient = virtualClient;
        producer.posSettingsService = mock(PosSettingsService.class);
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalType()).thenReturn("");
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn("");
        org.mockito.Mockito.when(producer.posSettingsService.islandPaymentLabel()).thenReturn("");
        producer.verifoneHost = "127.0.0.1";
        producer.verifonePort = 8200;
        producer.verifoneTimeoutMs = 1000;
        producer.hardwareClient = mock(HardwareClient.class);
        producer.bridgePollMs = 500L;
        producer.bridgeDeadlineMs = 300000L;
    }

    /**
     * Unwraps the degraded-mode gate produced for every mode.
     *
     * @return the configured (non-degraded) terminal
     */
    private PaymentTerminalClient configured() {
        PaymentTerminalClient produced = producer.paymentTerminalClient();
        assertInstanceOf(DegradedModePaymentTerminalClient.class, produced);
        return ((DegradedModePaymentTerminalClient) produced).configured();
    }

    /**
     * Mode {@code virtual} resolves to the simulator bean (virtual arm).
     */
    @Test
    void virtualModeReturnsVirtualBean() {
        producer.mode = "virtual";
        assertSame(virtualClient, configured());
    }

    /**
     * Mode {@code auto} resolves to the auto-accept implementation (auto
     * arm).
     */
    @Test
    void autoModeReturnsAutoAccept() {
        producer.mode = "auto";
        assertInstanceOf(AutoAcceptTerminalClient.class, configured());
    }

    /**
     * BO-04-01-48: behind the degraded gate, the immediate acceptance stands in
     * for the terminal the degraded mode forced onto the secondary server — its
     * outcome names server 4 and is stamped degraded, so the journal of the
     * store finds the ticket.
     */
    @Test
    void theDegradedGateAcceptsOnTheSecondaryServer() {
        producer.mode = "virtual";
        org.mockito.Mockito.when(producer.posSettingsService.paymentDegradedMode()).thenReturn(true);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        producer.paymentTerminalClient().requestDebit(new java.math.BigDecimal("7.50"), callback);
        org.mockito.Mockito.verify(callback).onAccepted(org.mockito.ArgumentMatchers.argThat(
                outcome -> "4".equals(outcome.server) && outcome.degradedMode));
        org.mockito.Mockito.verifyNoInteractions(virtualClient);
    }

    /**
     * Mode {@code verifone} resolves to the Verifone skeleton (verifone
     * arm).
     */
    @Test
    void verifoneModeReturnsVerifoneClient() {
        producer.mode = "verifone";
        assertInstanceOf(VerifoneTerminalClient.class, configured());
    }

    /**
     * Mode {@code bridge} resolves to the hardware-bridge client (bridge
     * arm).
     */
    @Test
    void bridgeModeReturnsHardwareBridgeClient() {
        producer.mode = "bridge";
        assertInstanceOf(HardwareBridgeTerminalClient.class, configured());
    }

    /**
     * An unknown mode falls back to the virtual bean (default arm).
     */
    @Test
    void unknownModeFallsBackToVirtual() {
        producer.mode = "typo";
        assertSame(virtualClient, configured());
    }

    /**
     * BO-03-09-19: the administered terminal family wins over the deployment's
     * mode — Verifone spoken by the register, the other families by the
     * hardware module.
     */
    @Test
    void theAdministeredTerminalFamilyWins() {
        producer.mode = "virtual";
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalType()).thenReturn("VERIFONE");
        assertEquals("verifone", producer.effectiveMode());
        assertInstanceOf(VerifoneTerminalClient.class, configured());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalType()).thenReturn("INGENICO_SIBS");
        assertEquals("bridge", producer.effectiveMode());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalType()).thenReturn("");
        assertEquals("virtual", producer.effectiveMode());
    }

    /**
     * BO-03-09-19: the administered address gives the host and the port; an
     * address without port, an unreadable port or none at all keep the
     * deployment's.
     */
    @Test
    void theAdministeredAddressGivesHostAndPort() {
        assertEquals("127.0.0.1", producer.host());
        assertEquals(8200, producer.port());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn("10.0.0.9:9100");
        assertEquals("10.0.0.9", producer.host());
        assertEquals(9100, producer.port());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn("10.0.0.9");
        assertEquals("10.0.0.9", producer.host());
        assertEquals(8200, producer.port());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn("10.0.0.9:abc");
        assertEquals(8200, producer.port());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn(":9100");
        assertEquals(":9100", producer.host());
        assertEquals(8200, producer.port());
    }

    /**
     * BO-03-09-19: the produced gate reads the administered family at each
     * transaction — the same administration reuses the client it built, a
     * family or an address posed after the start builds the matching one.
     */
    @Test
    void theAdministeredFamilyIsReadLive() {
        producer.mode = "virtual";
        PaymentTerminalClient produced = producer.paymentTerminalClient();
        DegradedModePaymentTerminalClient gate = (DegradedModePaymentTerminalClient) produced;
        assertSame(virtualClient, gate.configured());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalType()).thenReturn("VERIFONE");
        PaymentTerminalClient verifone = gate.configured();
        assertInstanceOf(VerifoneTerminalClient.class, verifone);
        assertSame(verifone, gate.configured());
        org.mockito.Mockito.when(producer.posSettingsService.paymentTerminalAddress()).thenReturn("10.0.0.9:9100");
        PaymentTerminalClient moved = gate.configured();
        assertInstanceOf(VerifoneTerminalClient.class, moved);
        assertNotSame(verifone, moved);
    }
}
