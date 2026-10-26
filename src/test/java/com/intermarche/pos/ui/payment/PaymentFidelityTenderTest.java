package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.hardware.HardwareService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the TENDER a signed loyalty payment is registered under
 * (BO-10-03-06): the administered key when the register knows it, FIDELITY
 * otherwise.
 */
class PaymentFidelityTenderTest {

    /**
     * Confirms a signed 4 € loyalty payment on a 10 € ticket under the given
     * administered tender and returns the state.
     *
     * @param administered the administered tender key, or null
     * @return the state after the confirmation
     */
    private PosState confirm(String administered) {
        PaymentService service = new PaymentService();
        service.i18n = TestMessages.french();
        service.hardwareService = mock(HardwareService.class);
        service.ticketPersistenceService = mock(TicketPersistenceService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.fidelityPaymentTender()).thenReturn(administered);
        PosState state = new PosState();
        state.ticket.totalAmount = new BigDecimal("10.00");
        state.payment.ticketDbId = 3L;
        state.payment.pendingFidelitySignatureAmount = new BigDecimal("4.00");
        service.confirmFidelitySignature(state, true);
        verify(service.ticketPersistenceService).addPaymentToTicket(3L, state.payment.payments.get(0));
        return state;
    }

    /**
     * An administered tender the register knows is the one the settlement is
     * registered — and persisted — under.
     */
    @Test
    void theAdministeredTenderIsUsed() {
        PosState state = confirm("VOUCHER");
        assertEquals("VOUCHER", state.payment.payments.get(0).method);
        assertEquals(0, new BigDecimal("4.00").compareTo(state.payment.paidAmount));
    }

    /**
     * An unknown administered tender falls back on FIDELITY: the settlement is
     * never lost to a key the register cannot build.
     */
    @Test
    void anUnknownTenderFallsBackOnFidelity() {
        PosState state = confirm("NOPE");
        assertEquals("FIDELITY", state.payment.payments.get(0).method);
    }
}
