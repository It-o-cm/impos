package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReprintResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, its
 * {@link ReprintState} sub-state, a {@link ReprintService} and three Qute
 * {@link Template}s ({@code lock}, {@code reprint-ticket},
 * {@code reprint-ticket-detail}). Every collaborator is a Mockito mock:
 * templates echo a recognizable {@link TemplateInstance} so the returned view
 * can be identified, {@code PosState} exposes its {@code isLocked()} decision
 * and carries a mocked {@code reprint} holder whose paging decisions are
 * stubbed and whose public fields the tests drive and assert directly. The
 * detail-view lookup goes through the {@code Ticket.findById} static finder,
 * which resolves to {@link PanacheEntityBase} under plain {@code mvn test} and
 * is intercepted with {@link org.mockito.Mockito#mockStatic}. Tests assert
 * absolute expected values and verify delegation, covering both arms of every
 * lock guard, both paging guards, the {@code findById} null guard and the
 * compound viewed-ticket identity guard (30 branches).
 */
class ReprintResourceTest {

    /**
     * Builds a {@link ReprintResource} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState#reprint}
     * sub-state holder so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, service and templates
     */
    private ReprintResource newResource() {
        ReprintResource resource = new ReprintResource();
        resource.state = mock(PosState.class);
        resource.state.reprint = mock(ReprintState.class);
        resource.reprintService = mock(ReprintService.class);
        resource.lock = mock(Template.class);
        resource.reprintTicketPage = mock(Template.class);
        resource.reprintDetailPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code lock} template to return a recognizable view for the
     * given resource's state.
     *
     * @param resource the resource whose {@code lock} template is stubbed
     * @return the view {@code lock.data("state", state)} returns
     */
    private TemplateInstance stubLock(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code reprint-ticket} list template to return a recognizable
     * view for the given resource's state.
     *
     * @param resource the resource whose {@code reprintTicketPage} template is stubbed
     * @return the view {@code reprintTicketPage.data("state", state)} returns
     */
    private TemplateInstance stubList(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintTicketPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    /**
     * Stubs the {@code reprint-ticket-detail} template to return a recognizable
     * view for the given resource's state.
     *
     * @param resource the resource whose {@code reprintDetailPage} template is stubbed
     * @return the view {@code reprintDetailPage.data("state", state)} returns
     */
    private TemplateInstance stubDetail(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(view);
        return view;
    }

    // --- showReprintPage ---

    /**
     * {@code showReprintPage()} renders the lock page and loads no history when
     * the terminal is locked (guard true).
     */
    @Test
    void showReprintPageRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.showReprintPage());
        verifyNoInteractions(resource.reprintService);
        verifyNoInteractions(resource.reprintTicketPage);
    }

    /**
     * {@code showReprintPage()} loads a fresh history and renders the list page
     * when unlocked (guard false).
     */
    @Test
    void showReprintPageLoadsHistoryWhenUnlocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.showReprintPage());
        verify(resource.reprintService).loadHistory();
        verifyNoInteractions(resource.lock);
    }

    // --- reprintPrevPage ---

    /**
     * {@code reprintPrevPage()} renders the lock page and leaves the paging
     * untouched when the terminal is locked (lock guard true).
     */
    @Test
    void reprintPrevPageRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.reprintPrevPage());
        verify(resource.state, org.mockito.Mockito.never()).touch();
        verifyNoInteractions(resource.reprintTicketPage);
    }

    /**
     * {@code reprintPrevPage()} pages the list back when a previous page exists
     * (lock false, hasListPrev true).
     */
    @Test
    void reprintPrevPageDecrementsWhenHasPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListPrev()).thenReturn(true);
        resource.state.reprint.listPage = 3;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintPrevPage());
        assertEquals(2, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    /**
     * {@code reprintPrevPage()} leaves the page unchanged when no previous page
     * exists (lock false, hasListPrev false).
     */
    @Test
    void reprintPrevPageKeepsPageWhenNoPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListPrev()).thenReturn(false);
        resource.state.reprint.listPage = 0;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintPrevPage());
        assertEquals(0, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    // --- reprintNextPage ---

    /**
     * {@code reprintNextPage()} renders the lock page and leaves the paging
     * untouched when the terminal is locked (lock guard true).
     */
    @Test
    void reprintNextPageRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.reprintNextPage());
        verify(resource.state, org.mockito.Mockito.never()).touch();
        verifyNoInteractions(resource.reprintTicketPage);
    }

    /**
     * {@code reprintNextPage()} pages the list forward when a next page exists
     * (lock false, hasListNext true).
     */
    @Test
    void reprintNextPageIncrementsWhenHasNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListNext()).thenReturn(true);
        resource.state.reprint.listPage = 1;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintNextPage());
        assertEquals(2, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    /**
     * {@code reprintNextPage()} leaves the page unchanged when no next page
     * exists (lock false, hasListNext false).
     */
    @Test
    void reprintNextPageKeepsPageWhenNoNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.state.reprint.isHasListNext()).thenReturn(false);
        resource.state.reprint.listPage = 1;
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.reprintNextPage());
        assertEquals(1, resource.state.reprint.listPage);
        verify(resource.state).touch();
    }

    // --- showReprintDetail ---

    /**
     * {@code showReprintDetail()} renders the lock page and never queries the
     * ticket when the terminal is locked (lock guard true).
     */
    @Test
    void showReprintDetailRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.showReprintDetail(7L));
        verify(resource.state, org.mockito.Mockito.never()).touch();
        verifyNoInteractions(resource.reprintDetailPage);
    }

    /**
     * {@code showReprintDetail()} installs the found ticket in the detail view
     * when it exists (lock false, ticket non-null).
     */
    @Test
    void showReprintDetailOpensFoundTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.showReprintDetail(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.state).touch();
    }

    /**
     * {@code showReprintDetail()} leaves the viewed ticket untouched when no
     * ticket matches the id (lock false, ticket null).
     */
    @Test
    void showReprintDetailKeepsViewWhenTicketMissing() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(null);
            assertSame(detailView, resource.showReprintDetail(7L));
        }
        verify(resource.state.reprint, org.mockito.Mockito.never()).setViewedTicket(org.mockito.ArgumentMatchers.any());
        verify(resource.state).touch();
    }

    // --- detailPrevPage ---

    /**
     * {@code detailPrevPage()} renders the lock page when the terminal is locked
     * (lock guard true).
     */
    @Test
    void detailPrevPageRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.detailPrevPage(7L));
        verify(resource.state, org.mockito.Mockito.never()).touch();
        verifyNoInteractions(resource.reprintDetailPage);
    }

    /**
     * {@code detailPrevPage()} reopens the ticket when none is currently viewed
     * (identity guard first arm true: viewedTicket null).
     */
    @Test
    void detailPrevPageReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailPrevPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailPrevPage()} reopens the ticket when a different ticket is
     * viewed (identity guard first arm false, second arm true: id mismatch).
     */
    @Test
    void detailPrevPageReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailPrevPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailPrevPage()} pages the detail back when the same ticket is
     * viewed and a previous page exists (identity guard both arms false,
     * hasDetailPrev true).
     */
    @Test
    void detailPrevPageDecrementsWhenHasPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailPrev()).thenReturn(true);
        resource.state.reprint.detailPage = 2;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailPrevPage(7L));
        assertEquals(1, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    /**
     * {@code detailPrevPage()} keeps the detail page when the same ticket is
     * viewed but no previous page exists (identity guard both arms false,
     * hasDetailPrev false).
     */
    @Test
    void detailPrevPageKeepsPageWhenNoPrev() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailPrev()).thenReturn(false);
        resource.state.reprint.detailPage = 0;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailPrevPage(7L));
        assertEquals(0, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    // --- detailNextPage ---

    /**
     * {@code detailNextPage()} renders the lock page when the terminal is locked
     * (lock guard true).
     */
    @Test
    void detailNextPageRendersLockWhenLocked() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.detailNextPage(7L));
        verify(resource.state, org.mockito.Mockito.never()).touch();
        verifyNoInteractions(resource.reprintDetailPage);
    }

    /**
     * {@code detailNextPage()} reopens the ticket when none is currently viewed
     * (identity guard first arm true: viewedTicket null).
     */
    @Test
    void detailNextPageReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailNextPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailNextPage()} reopens the ticket when a different ticket is
     * viewed (identity guard first arm false, second arm true: id mismatch).
     */
    @Test
    void detailNextPageReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.detailNextPage(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
    }

    /**
     * {@code detailNextPage()} pages the detail forward when the same ticket is
     * viewed and a next page exists (identity guard both arms false,
     * hasDetailNext true).
     */
    @Test
    void detailNextPageIncrementsWhenHasNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailNext()).thenReturn(true);
        resource.state.reprint.detailPage = 1;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailNextPage(7L));
        assertEquals(2, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    /**
     * {@code detailNextPage()} keeps the detail page when the same ticket is
     * viewed but no next page exists (identity guard both arms false,
     * hasDetailNext false).
     */
    @Test
    void detailNextPageKeepsPageWhenNoNext() {
        ReprintResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        when(resource.state.reprint.isHasDetailNext()).thenReturn(false);
        resource.state.reprint.detailPage = 1;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.detailNextPage(7L));
        assertEquals(1, resource.state.reprint.detailPage);
        verify(resource.state).touch();
    }

    // --- doReprint ---

    /**
     * {@code doReprint()} delegates the print to the service and redirects
     * (303) to the ticket's detail view.
     */
    @Test
    void doReprintPrintsAndRedirectsToDetail() {
        ReprintResource resource = newResource();
        Response response = resource.doReprint(42L);
        verify(resource.reprintService).print(42L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/42", response.getLocation().toString());
    }
}
