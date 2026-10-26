package com.intermarche.pos.ui.home;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.VoucherService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerScanResource} (BO-03-09-18): the empty code,
 * the scanner not activated, a card of the deployment's and of the
 * administered range, a voucher, and an article refused.
 */
class CustomerScanResourceTest {

    /**
     * Builds the resource.
     *
     * @param enabled whether the second scanner is activated
     * @param administeredPattern the administered card range
     * @return the resource
     */
    private CustomerScanResource resource(boolean enabled, String administeredPattern) {
        CustomerScanResource resource = new CustomerScanResource();
        resource.ticketService = mock(TicketService.class);
        resource.voucherService = mock(VoucherService.class);
        resource.posSettingsService = mock(PosSettingsService.class);
        when(resource.posSettingsService.secondScannerEnabled()).thenReturn(enabled);
        when(resource.posSettingsService.fidelityCardPattern()).thenReturn(administeredPattern);
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.i18n = com.intermarche.pos.ui.TestMessages.french();
        resource.fidelityPattern = "^299\\d{10}$";
        return resource;
    }

    /** An empty code is a bad request, a scanner not activated is ignored. */
    @Test
    void emptyAndInactiveAreRefused() {
        assertEquals(400, resource(true, null).handle(null).getStatus());
        assertEquals(400, resource(true, null).handle(" ").getStatus());
        CustomerScanResource off = resource(false, null);
        assertEquals(204, off.handle("2990000000001").getStatus());
        verify(off.ticketService, never()).processScan(anyString());
    }

    /** A card of the deployment's range, then of the administered one, goes through the chain. */
    @Test
    void aFidelityCardGoesThrough() {
        CustomerScanResource deployed = resource(true, " ");
        assertEquals(200, deployed.handle(" 2990000000001 ").getStatus());
        verify(deployed.ticketService).processScan("2990000000001");
        CustomerScanResource administered = resource(true, "^77\\d{3}$");
        assertEquals(200, administered.handle("77123").getStatus());
        verify(administered.ticketService).processScan("77123");
        assertEquals("^299\\d{10}$", resource(true, null).cardPattern());
    }

    /** A voucher goes through; an article is refused and said so. */
    @Test
    void aVoucherGoesThroughAnArticleDoesNot() {
        CustomerScanResource resource = resource(true, null);
        when(resource.voucherService.resolveType("BON123")).thenReturn(new CouponType());
        assertEquals(200, resource.handle("BON123").getStatus());
        verify(resource.ticketService).processScan("BON123");
        assertEquals(422, resource.handle("3250390000000").getStatus());
        verify(resource.state.ticket).setError("Le scanner client ne lit que les cartes de fidélité et les coupons.");
        verify(resource.ticketService, never()).processScan("3250390000000");
    }
}
