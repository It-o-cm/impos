package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenSaleProbe} (BO-10-08-25): the shop's protection
 * off, an idle register, a ticket carrying a line, a settlement under way.
 */
class OpenSaleProbeTest {

    /**
     * Builds a probe over a real register state and mocked parameters.
     *
     * @param protect whether the shop protects the sale in progress
     * @return the probe
     */
    private OpenSaleProbe probe(boolean protect) {
        OpenSaleProbe probe = new OpenSaleProbe();
        probe.state = new PosState();
        probe.posSettingsService = mock(PosSettingsService.class);
        when(probe.posSettingsService.referentialDeferDuringSale()).thenReturn(protect);
        return probe;
    }

    /**
     * A shop that does not protect the sale never defers, even mid-ticket.
     */
    @Test
    void anUnprotectedSaleNeverDefers() {
        OpenSaleProbe probe = probe(false);
        probe.state.ticket.items.add(new TicketState.TicketItem("3000", null, "LAIT",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
        assertFalse(probe.saleInProgress());
    }

    /**
     * An idle register is free; a ticket with a line, or a settlement under
     * way, is a sale in progress.
     */
    @Test
    void aProtectedSaleDefersWhileTheTicketIsOpen() {
        OpenSaleProbe probe = probe(true);
        assertFalse(probe.saleInProgress());
        probe.state.payment.paymentInProgress = true;
        assertTrue(probe.saleInProgress());
        probe.state.payment.paymentInProgress = false;
        probe.state.ticket.items.add(new TicketState.TicketItem("3000", null, "LAIT",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));
        assertTrue(probe.saleInProgress());
    }
}
