package com.intermarche.pos.ui.cash;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashCountResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link HardwareService},
 * {@link CashCountService}, {@link PosState} and the {@code cash-count} Qute
 * {@link Template}. Every collaborator is a Mockito mock: the template chain is
 * stubbed to echo a recognizable {@link TemplateInstance} so the returned view
 * can be identified, and the service denomination getters are stubbed to return
 * sentinel lists so delegation and tab selection can be asserted. Tests assert
 * absolute expected values and cover the three arms of the {@code getFragment}
 * tab switch ({@code "coins"}, {@code "rolls"}, default).
 */
class CashCountResourceTest {

    /**
     * Builds a {@link CashCountResource} whose collaborators are fresh mocks
     * wired onto its package-private fields.
     *
     * @return a resource with fully mocked state, services and template
     */
    private CashCountResource newResource() {
        CashCountResource resource = new CashCountResource();
        resource.cashCount = mock(Template.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.cashCountService = mock(CashCountService.class);
        resource.state = mock(PosState.class);
        return resource;
    }

    /**
     * {@code startCashCount()} opens the drawer and redirects to the counting
     * page.
     */
    @Test
    void startCashCountOpensDrawerAndRedirects() {
        CashCountResource resource = newResource();
        Object result = resource.startCashCount();
        Response response = (Response) result;
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/cash-count", response.getLocation().toString());
        verify(resource.hardwareService).openDrawer();
    }

    /**
     * {@code cashCountPage()} renders the counting page seeded with the state,
     * the banknote list and the non-fragment flag.
     */
    @Test
    void cashCountPageRendersFullPageWithBills() {
        CashCountResource resource = newResource();
        List<CashItem> bills = List.of(new CashItem("b50", "Billet 50 €", 50.0));
        when(resource.cashCountService.getBills()).thenReturn(bills);
        TemplateInstance withState = mock(TemplateInstance.class);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withFragment = mock(TemplateInstance.class);
        when(resource.cashCount.data("state", resource.state)).thenReturn(withState);
        when(withState.data("items", bills)).thenReturn(withItems);
        when(withItems.data("fragment", false)).thenReturn(withFragment);
        TemplateInstance result = resource.cashCountPage();
        assertSame(withFragment, result);
    }

    /**
     * {@code getFragment("coins")} renders the coins list fragment (first switch
     * arm).
     */
    @Test
    void getFragmentReturnsCoinsFragment() {
        CashCountResource resource = newResource();
        List<CashItem> coins = List.of(new CashItem("c2", "Pièce 2 €", 2.0));
        when(resource.cashCountService.getCoins()).thenReturn(coins);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withFragment = mock(TemplateInstance.class);
        when(resource.cashCount.data("items", coins)).thenReturn(withItems);
        when(withItems.data("fragment", true)).thenReturn(withFragment);
        TemplateInstance result = resource.getFragment("coins");
        assertSame(withFragment, result);
    }

    /**
     * {@code getFragment("rolls")} renders the coin-roll list fragment (second
     * switch arm).
     */
    @Test
    void getFragmentReturnsRollsFragment() {
        CashCountResource resource = newResource();
        List<CashItem> rolls = List.of(new CashItem("r2", "Rouleau 2€ (50€)", 50.0));
        when(resource.cashCountService.getRolls()).thenReturn(rolls);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withFragment = mock(TemplateInstance.class);
        when(resource.cashCount.data("items", rolls)).thenReturn(withItems);
        when(withItems.data("fragment", true)).thenReturn(withFragment);
        TemplateInstance result = resource.getFragment("rolls");
        assertSame(withFragment, result);
    }

    /**
     * {@code getFragment} with an unrecognized tab falls back to the banknote
     * list fragment (default switch arm).
     */
    @Test
    void getFragmentFallsBackToBillsFragment() {
        CashCountResource resource = newResource();
        List<CashItem> bills = List.of(new CashItem("b50", "Billet 50 €", 50.0));
        when(resource.cashCountService.getBills()).thenReturn(bills);
        TemplateInstance withItems = mock(TemplateInstance.class);
        TemplateInstance withFragment = mock(TemplateInstance.class);
        when(resource.cashCount.data("items", bills)).thenReturn(withItems);
        when(withItems.data("fragment", true)).thenReturn(withFragment);
        TemplateInstance result = resource.getFragment("bills");
        assertSame(withFragment, result);
    }
}
