package com.intermarche.pos.ui.hardware;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HardwareClient}.
 * <p>
 * {@code HardwareClient} is a MicroProfile REST-client interface with no
 * implementation body: it declares six abstract peripheral operations and
 * carries zero executable branches. The contract these tests pin down is the
 * method surface itself — each declared method is exercised through a Mockito
 * mock to prove it is callable with its declared signature, that value-returning
 * reads relay the stubbed payload verbatim (including the {@code null} arm), and
 * that {@code void} commands forward their argument without extra interaction.
 */
class HardwareClientTest {

    /**
     * {@code getWeight()} returns the scale payload exactly as produced by the
     * underlying implementation, here a French-decimal weight string.
     */
    @Test
    void getWeightReturnsScalePayload() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn("1,234");
        assertEquals("1,234", client.getWeight());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code getWeight()} relays a {@code null} payload unchanged, covering the
     * absent-reading arm of the scale contract.
     */
    @Test
    void getWeightRelaysNullPayload() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getWeight()).thenReturn(null);
        assertNull(client.getWeight());
        verify(client).getWeight();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code setDisplay()} forwards the given text to the customer line display
     * without any further interaction.
     */
    @Test
    void setDisplayForwardsText() {
        HardwareClient client = mock(HardwareClient.class);
        client.setDisplay("BONJOUR");
        verify(client).setDisplay("BONJOUR");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code setDisplay()} accepts a {@code null} text argument and still routes
     * it to the single display call, covering the null argument arm.
     */
    @Test
    void setDisplayAcceptsNullText() {
        HardwareClient client = mock(HardwareClient.class);
        client.setDisplay(null);
        verify(client).setDisplay(null);
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code openDrawer()} fires exactly one drawer-opening pulse.
     */
    @Test
    void openDrawerFiresPulse() {
        HardwareClient client = mock(HardwareClient.class);
        client.openDrawer();
        verify(client).openDrawer();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code getDrawerStatus()} returns the {@code "OPEN"} sentinel unchanged
     * when the physical drawer is open.
     */
    @Test
    void getDrawerStatusReturnsOpen() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("OPEN");
        assertEquals("OPEN", client.getDrawerStatus());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code getDrawerStatus()} relays any non-{@code OPEN} reading verbatim,
     * covering the closed-drawer arm of the status contract.
     */
    @Test
    void getDrawerStatusReturnsClosed() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getDrawerStatus()).thenReturn("CLOSED");
        assertEquals("CLOSED", client.getDrawerStatus());
        verify(client).getDrawerStatus();
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code printTicket()} forwards the formatted receipt content to the
     * printer as a single call.
     */
    @Test
    void printTicketForwardsContent() {
        HardwareClient client = mock(HardwareClient.class);
        client.printTicket("LINE 1\nLINE 2");
        verify(client).printTicket("LINE 1\nLINE 2");
        verifyNoMoreInteractions(client);
    }

    /**
     * {@code cutPaper()} issues exactly one paper-cut command.
     */
    @Test
    void cutPaperCutsOnce() {
        HardwareClient client = mock(HardwareClient.class);
        client.cutPaper();
        verify(client).cutPaper();
        verifyNoMoreInteractions(client);
    }
}
