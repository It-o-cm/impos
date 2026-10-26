package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.PosState;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestoFlashScanFilter}: a page navigation disarms the
 * Resto Flash scan of the payment screen (EXP-67), a script call or a poll does
 * not, and a request naming no {@code Accept} at all does not either — the two
 * legs of the guard.
 */
class RestoFlashScanFilterTest {

    /**
     * Builds the filter over a register state whose Resto Flash zone is armed.
     *
     * @return the filter under test
     */
    private RestoFlashScanFilter armedFilter() {
        RestoFlashScanFilter filter = new RestoFlashScanFilter();
        filter.state = new PosState();
        filter.state.payment.restoFlashScanArmed = true;
        return filter;
    }

    /**
     * Builds a request carrying the given {@code Accept} header.
     *
     * @param accept the header, or null for none
     * @return the request
     */
    private ContainerRequestContext request(String accept) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getHeaderString("Accept")).thenReturn(accept);
        return request;
    }

    /**
     * A page navigation — the payment screen redrawn, the sale screen — drops the
     * arming: the page comes back with every zone closed.
     */
    @Test
    void aPageNavigationDisarmsTheZone() {
        RestoFlashScanFilter filter = armedFilter();
        filter.filter(request("text/html,application/xhtml+xml,*/*;q=0.8"));
        assertFalse(filter.state.payment.restoFlashScanArmed);
    }

    /**
     * The script call arming the zone, and the screen's poll, keep it armed.
     */
    @Test
    void aScriptCallKeepsTheZoneArmed() {
        RestoFlashScanFilter filter = armedFilter();
        filter.filter(request("*/*"));
        assertTrue(filter.state.payment.restoFlashScanArmed);
    }

    /**
     * A machine call naming no {@code Accept} keeps the zone armed.
     */
    @Test
    void aRequestWithoutAcceptKeepsTheZoneArmed() {
        RestoFlashScanFilter filter = armedFilter();
        filter.filter(request(null));
        assertTrue(filter.state.payment.restoFlashScanArmed);
    }
}
