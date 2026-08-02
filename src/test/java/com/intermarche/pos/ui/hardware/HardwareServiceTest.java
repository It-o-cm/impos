package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HardwareService}.
 * <p>
 * {@code HardwareService} is a degraded-mode facade over {@link HardwareClient}:
 * every peripheral call wraps the client in a {@code try/catch} that swallows
 * failures so a dead device never blocks a sale. Each method is therefore
 * exercised on both its success arm and its exception arm; {@code isDrawerOpen}
 * additionally covers the OPEN and non-OPEN readings of its status compare. The
 * package-private {@code hardwareClient} field is populated with a Mockito mock
 * from within the same package — no CDI, no application boot.
 */
class HardwareServiceTest {

    /**
     * Builds a service wired to the supplied mock client, bypassing CDI by
     * assigning the package-private field directly.
     *
     * @param client the mock hardware client to inject
     * @return a service under test bound to that client
     */
    private HardwareService serviceWith(HardwareClient client) {
        HardwareService service = new HardwareService();
        service.hardwareClient = client;
        return service;
    }

    /**
     * {@code requestWeighing()} parses a well-formed French-decimal payload,
     * translating the comma to a dot before parsing.
     */
    @Test
    void requestWeighingParsesFrenchDecimal() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("1,234");
        assertEquals(1.234, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} parses a dot-decimal payload unchanged, the
     * comma replacement being a no-op on such input.
     */
    @Test
    void requestWeighingParsesDotDecimal() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("2.5");
        assertEquals(2.5, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} answers 0.0 when the client throws, taking the
     * degraded-mode catch arm so a dead scale never blocks the sale.
     */
    @Test
    void requestWeighingReturnsZeroOnClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenThrow(new RuntimeException("scale down"));
        assertEquals(0.0, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code requestWeighing()} answers 0.0 when the payload is unparseable, the
     * {@link NumberFormatException} being caught like any other failure.
     */
    @Test
    void requestWeighingReturnsZeroOnUnparseablePayload() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("not-a-number");
        assertEquals(0.0, serviceWith(client).requestWeighing());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code displayMessage()} forwards the text to the customer line display on
     * the success arm.
     */
    @Test
    void displayMessageForwardsText() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).displayMessage("BONJOUR");
        verify(client).setDisplay("BONJOUR");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code displayMessage()} swallows a client failure, returning normally so a
     * dead display never blocks the sale.
     */
    @Test
    void displayMessageSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("display down")).when(client).setDisplay("KO");
        serviceWith(client).displayMessage("KO");
        verify(client).setDisplay("KO");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code openDrawer()} fires the drawer-opening pulse on the success arm.
     */
    @Test
    void openDrawerFiresPulse() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).openDrawer();
        verify(client).openDrawer();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code openDrawer()} swallows a client failure, returning normally.
     */
    @Test
    void openDrawerSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("drawer stuck")).when(client).openDrawer();
        serviceWith(client).openDrawer();
        verify(client).openDrawer();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers true when the sensor reports the OPEN
     * sentinel exactly.
     */
    @Test
    void isDrawerOpenReturnsTrueOnOpen() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("OPEN");
        assertTrue(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers true for a case-insensitive OPEN reading,
     * proving the compare ignores case.
     */
    @Test
    void isDrawerOpenReturnsTrueOnLowercaseOpen() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("open");
        assertTrue(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers false for any non-OPEN reading, covering the
     * closed-drawer arm of the status compare.
     */
    @Test
    void isDrawerOpenReturnsFalseOnClosed() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        assertFalse(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code isDrawerOpen()} answers false when the sensor throws, so a dead
     * drawer sensor never traps the register behind the drawer guard.
     */
    @Test
    void isDrawerOpenReturnsFalseOnClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenThrow(new RuntimeException("sensor dead"));
        assertFalse(serviceWith(client).isDrawerOpen());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code printReceipt()} forwards the formatted content to the printer on the
     * success arm.
     */
    @Test
    void printReceiptForwardsContent() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).printReceipt("LINE 1\nLINE 2");
        verify(client).printTicket("LINE 1\nLINE 2");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code printReceipt()} swallows a client failure, returning normally so a
     * dead printer never blocks the sale.
     */
    @Test
    void printReceiptSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("printer down")).when(client).printTicket("KO");
        serviceWith(client).printReceipt("KO");
        verify(client).printTicket("KO");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code cutPaper()} issues the paper-cut command on the success arm.
     */
    @Test
    void cutPaperCutsOnce() {
        HardwareClient client = mock(HardwareClient.class);
        serviceWith(client).cutPaper();
        verify(client).cutPaper();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code cutPaper()} swallows a client failure, returning normally.
     */
    @Test
    void cutPaperSwallowsClientFailure() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("cutter jammed")).when(client).cutPaper();
        serviceWith(client).cutPaper();
        verify(client).cutPaper();
        verifyNoMoreInteractions(client);
    }
}
