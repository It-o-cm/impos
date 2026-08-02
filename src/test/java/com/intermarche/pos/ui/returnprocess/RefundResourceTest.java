package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefundResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link RefundState} sub-state, a {@link RefundService} and three Qute
 * {@link Template}s ({@code lock}, {@code return-search}, {@code return-detail}).
 * Every collaborator is a Mockito mock: templates echo a recognizable
 * {@link TemplateInstance} so the returned view can be identified, {@code PosState}
 * exposes its {@code isLocked()} decision and carries a mocked {@code refund}
 * holder whose {@code isTicketSelected()} decision and public fields the tests
 * drive directly. Tests assert absolute expected values and verify delegation,
 * covering both arms of the lock guard, the ticket-selected guard and the
 * {@code rawValue} null ternary.
 */
class RefundResourceTest {

    /**
     * Builds a {@link RefundResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#refund}
     * sub-state holder so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private RefundResource newResource() {
        RefundResource resource = new RefundResource();
        resource.state = mock(PosState.class);
        resource.state.refund = mock(RefundState.class);
        resource.refundService = mock(RefundService.class);
        resource.lock = mock(Template.class);
        resource.returnSearchPage = mock(Template.class);
        resource.returnDetailPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code lock} template chain to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code lock} template is stubbed
     * @return the view {@code lock.data("state", state).data("error", null)} returns
     */
    private TemplateInstance stubLock(RefundResource resource) {
        TemplateInstance seeded = mock(TemplateInstance.class);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(seeded);
        when(seeded.data("error", null)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code return-search} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code returnSearchPage} template is stubbed
     * @return the view {@code returnSearchPage.data("state", state)} returns
     */
    private TemplateInstance stubSearch(RefundResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.returnSearchPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code return-detail} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code returnDetailPage} template is stubbed
     * @return the view {@code returnDetailPage.data("state", state)} returns
     */
    private TemplateInstance stubDetail(RefundResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.returnDetailPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    // --- showSearchPage ---

    /**
     * {@code showSearchPage()} renders the lock page when the terminal is locked
     * (first guard true).
     */
    @Test
    void showSearchPageRendersLockWhenLocked() {
        RefundResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.showSearchPage());
        verifyNoInteractions(resource.returnSearchPage);
        verifyNoInteractions(resource.returnDetailPage);
    }

    /**
     * {@code showSearchPage()} renders the detail page when unlocked and a ticket is
     * already selected (first guard false, second guard true).
     */
    @Test
    void showSearchPageRendersDetailWhenTicketSelected() {
        RefundResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.refund.isTicketSelected()).thenReturn(true);
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.showSearchPage());
        verifyNoInteractions(resource.lock);
        verifyNoInteractions(resource.returnSearchPage);
    }

    /**
     * {@code showSearchPage()} renders the search page when unlocked and no ticket is
     * selected (both guards false).
     */
    @Test
    void showSearchPageRendersSearchWhenNoTicketSelected() {
        RefundResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.refund.isTicketSelected()).thenReturn(false);
        TemplateInstance searchView = stubSearch(resource);
        assertSame(searchView, resource.showSearchPage());
        verifyNoInteractions(resource.lock);
        verifyNoInteractions(resource.returnDetailPage);
    }

    // --- doSearch ---

    /**
     * {@code doSearch()} trims a non-null pattern, runs the search and returns the
     * search view (ternary true arm).
     */
    @Test
    void doSearchTrimsNonNullPattern() {
        RefundResource resource = newResource();
        TemplateInstance searchView = stubSearch(resource);
        assertSame(searchView, resource.doSearch("  42  "));
        assertEquals("42", resource.state.refund.searchPattern);
        verify(resource.refundService).searchTickets(resource.state);
    }

    /**
     * {@code doSearch()} stores an empty pattern when the raw value is null (ternary
     * false arm) and still runs the search.
     */
    @Test
    void doSearchDefaultsNullPatternToEmpty() {
        RefundResource resource = newResource();
        TemplateInstance searchView = stubSearch(resource);
        assertSame(searchView, resource.doSearch(null));
        assertEquals("", resource.state.refund.searchPattern);
        verify(resource.refundService).searchTickets(resource.state);
    }

    // --- selection & line editing ---

    /**
     * {@code selectTicket()} selects the ticket by id and returns the detail view.
     */
    @Test
    void selectTicketSelectsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.selectTicket(5L));
        verify(resource.refundService).selectTicket(resource.state, 5L);
    }

    /**
     * {@code selectLine()} toggles the line selection and returns the detail view.
     */
    @Test
    void selectLineSelectsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.selectLine(9L));
        verify(resource.refundService).selectLine(resource.state, 9L);
    }

    /**
     * {@code editAmount()} switches to global-amount edition and returns the detail
     * view.
     */
    @Test
    void editAmountStartsAmountEditAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.editAmount());
        verify(resource.refundService).startAmountEdit(resource.state);
    }

    /**
     * {@code submitLine()} applies the typed line quantity and returns the detail
     * view.
     */
    @Test
    void submitLineAppliesQuantityAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.submitLine(3L, "2"));
        verify(resource.refundService).submitLineQuantity(resource.state, 3L, "2");
    }

    /**
     * {@code submitAmount()} applies the typed global amount and returns the detail
     * view.
     */
    @Test
    void submitAmountAppliesAmountAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.submitAmount("12,50"));
        verify(resource.refundService).submitManualAmount(resource.state, "12,50");
    }

    /**
     * {@code addQty()} increments the line refund quantity and returns the detail
     * view.
     */
    @Test
    void addQtyIncrementsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.addQty(7L));
        verify(resource.refundService).incrementQty(resource.state, 7L);
    }

    /**
     * {@code subQty()} decrements the line refund quantity and returns the detail
     * view.
     */
    @Test
    void subQtyDecrementsAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.subQty(7L));
        verify(resource.refundService).decrementQty(resource.state, 7L);
    }

    /**
     * {@code changePage()} sets the detail page index and returns the detail view.
     */
    @Test
    void changePageSetsPageAndReturnsDetail() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.changePage(4));
        assertEquals(4, resource.state.refund.detailPage);
    }

    // --- endorsed refund requests ---

    /**
     * {@code payCash()} requests an endorsed cash refund and returns the detail view.
     */
    @Test
    void payCashRequestsCashRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payCash());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.CASH);
    }

    /**
     * {@code payCard()} requests an endorsed card refund and returns the detail view.
     */
    @Test
    void payCardRequestsCardRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payCard());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.CARD);
    }

    /**
     * {@code payVoucher()} requests an endorsed store-voucher refund and returns the
     * detail view.
     */
    @Test
    void payVoucherRequestsVoucherRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payVoucher());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.VOUCHER);
    }

    /**
     * {@code payLoyalty()} requests an endorsed loyalty refund and returns the detail
     * view.
     */
    @Test
    void payLoyaltyRequestsLoyaltyRefund() {
        RefundResource resource = newResource();
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.payLoyalty());
        verify(resource.refundService).requestRefund(resource.state, Refund.RefundMethod.LOYALTY);
    }
}
