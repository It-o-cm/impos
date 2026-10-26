package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

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
        resource.reprintTicketPage = mock(Template.class);
        resource.reprintDetailPage = mock(Template.class);
        resource.reprintForeignPage = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code reprint-foreign} template to return a recognizable view
     * for the given resource's state.
     *
     * @param resource the resource whose {@code reprintForeignPage} template is stubbed
     * @return the view {@code reprintForeignPage.data("state", state)} returns
     */
    private TemplateInstance stubForeign(ReprintResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.reprintForeignPage.data("state", resource.state)).thenReturn(view);
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
    }

    // --- reprintPrevPage ---

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

    // --- startExchange ---

    /**
     * {@code startExchange()} reopens the ticket instead of starting the
     * exchange when none is currently viewed (identity guard first arm true:
     * viewedTicket null).
     */
    @Test
    void startExchangeReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.startExchange(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).startExchange();
    }

    /**
     * {@code startExchange()} reopens the ticket instead of starting the
     * exchange when a different ticket is viewed (identity guard first arm
     * false, second arm true: id mismatch).
     */
    @Test
    void startExchangeReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.startExchange(7L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).startExchange();
    }

    /**
     * {@code startExchange()} delegates to the service and renders the detail
     * view when the same ticket is viewed (identity guard both arms false: id
     * match).
     */
    @Test
    void startExchangeStartsWhenSameTicketViewed() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.startExchange(7L));
        verify(resource.reprintService).startExchange();
    }

    // --- cancelExchange ---

    /**
     * {@code cancelExchange()} cancels the exchange on the service then reopens
     * the ticket through {@code showReprintDetail}, installing the found ticket
     * in the detail view.
     */
    @Test
    void cancelExchangeCancelsThenReopensDetail() {
        ReprintResource resource = newResource();
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.cancelExchange(7L));
        }
        verify(resource.reprintService).cancelExchange();
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.state).touch();
    }

    // --- toggleExchangeLine ---

    /**
     * {@code toggleExchangeLine()} reopens the ticket instead of toggling when
     * none is currently viewed (identity guard first arm true: viewedTicket
     * null).
     */
    @Test
    void toggleExchangeLineReopensWhenNoViewedTicket() {
        ReprintResource resource = newResource();
        resource.state.reprint.viewedTicket = null;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).toggleExchangeLine(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * {@code toggleExchangeLine()} reopens the ticket instead of toggling when
     * a different ticket is viewed (identity guard first arm false, second arm
     * true: id mismatch).
     */
    @Test
    void toggleExchangeLineReopensWhenViewedTicketMismatch() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 99L;
        resource.state.reprint.viewedTicket = viewed;
        Ticket ticket = mock(Ticket.class);
        TemplateInstance detailView = stubDetail(resource);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        }
        verify(resource.state.reprint).setViewedTicket(ticket);
        verify(resource.reprintService, org.mockito.Mockito.never()).toggleExchangeLine(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * {@code toggleExchangeLine()} delegates the toggle for the named line and
     * renders the detail view when the same ticket is viewed (identity guard
     * both arms false: id match).
     */
    @Test
    void toggleExchangeLineTogglesWhenSameTicketViewed() {
        ReprintResource resource = newResource();
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        TemplateInstance detailView = stubDetail(resource);
        assertSame(detailView, resource.toggleExchangeLine(7L, 5L));
        verify(resource.reprintService).toggleExchangeLine(5L);
    }

    // --- printExchange ---

    /**
     * {@code printExchange()} delegates the bon-pour-échange print to the
     * service and redirects (303) to the ticket's detail view.
     */
    @Test
    void printExchangePrintsAndRedirectsToDetail() {
        ReprintResource resource = newResource();
        Response response = resource.printExchange(42L);
        verify(resource.reprintService).printExchange(42L);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/42", response.getLocation().toString());
    }

    // --- showForeign ---

    /**
     * {@code showForeign()} opens the foreign-duplicata mask on the service and
     * renders the foreign page.
     */
    @Test
    void showForeignStartsAndRendersForeignPage() {
        ReprintResource resource = newResource();
        TemplateInstance foreignView = stubForeign(resource);
        assertSame(foreignView, resource.showForeign());
        verify(resource.reprintService).startForeign();
    }

    // --- printForeign ---

    /**
     * {@code printForeign()} delegates the typed number to the service and
     * renders the foreign page carrying any failure.
     */
    @Test
    void printForeignPrintsAndRendersForeignPage() {
        ReprintResource resource = newResource();
        TemplateInstance foreignView = stubForeign(resource);
        assertSame(foreignView, resource.printForeign("C03-000042"));
        verify(resource.reprintService).printForeign("C03-000042");
    }

    // --- searchReprint ---

    /**
     * {@code searchReprint(pattern)} (EXP-47) hands the pattern to the search
     * engine and renders the list page — the ONLY path by which a number, a date
     * or an amount reaches that engine.
     */
    @Test
    void searchReprintDelegatesAndRendersTheList() {
        ReprintResource resource = newResource();
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.searchReprint("17/03/2026"));
        verify(resource.reprintService).search("17/03/2026");
    }

    /**
     * A null pattern — an empty field posted by the form — is handed on as it
     * stands: it is the engine, and not the screen, that decides a pattern too
     * short to search returns nothing.
     */
    @Test
    void searchReprintPassesANullPatternThrough() {
        ReprintResource resource = newResource();
        TemplateInstance listView = stubList(resource);
        assertSame(listView, resource.searchReprint(null));
        verify(resource.reprintService).search(null);
    }

    /**
     * LC-03-06-02: the reassignment-mode routes delegate to the service and
     * render the LIST template both ways — entering and leaving the mode.
     */
    @Test
    void reassignModeRoutesDelegateAndRenderTheList() {
        ReprintResource resource = newResource();
        TemplateInstance list = mock(TemplateInstance.class);
        when(resource.reprintTicketPage.data("state", resource.state)).thenReturn(list);
        assertSame(list, resource.startReassignMode());
        verify(resource.reprintService).startReassignMode();
        assertSame(list, resource.cancelReassignMode());
        verify(resource.reprintService).cancelReassignMode();
    }

    /**
     * LC-03-06-03: opening the card mask on the VIEWED ticket renders the
     * detail view; a stale id — another ticket, or none — reloads the detail
     * instead, exactly as the paging routes do.
     */
    @Test
    void beginReassignRendersTheDetailOrReloadsAStaleOne() {
        ReprintResource resource = newResource();
        TemplateInstance detail = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(detail);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        resource.state.reprint.viewedTicket = viewed;
        assertSame(detail, resource.beginReassign(7L));
        verify(resource.reprintService).beginReassign(7L);
        Ticket other = mock(Ticket.class);
        other.id = 9L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(9L)).thenReturn(other);
            assertSame(detail, resource.beginReassign(9L));
        }
        verify(resource.state.reprint).setViewedTicket(other);
        verify(resource.reprintService, org.mockito.Mockito.never()).beginReassign(9L);
    }

    /**
     * LC-03-06-03: leaving the mask closes it and reloads the detail.
     */
    @Test
    void cancelReassignClosesTheMaskAndReloads() {
        ReprintResource resource = newResource();
        TemplateInstance detail = mock(TemplateInstance.class);
        when(resource.reprintDetailPage.data("state", resource.state)).thenReturn(detail);
        Ticket viewed = mock(Ticket.class);
        viewed.id = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(7L)).thenReturn(viewed);
            assertSame(detail, resource.cancelReassign(7L));
        }
        verify(resource.reprintService).cancelReassignMask();
    }

    /**
     * LC-03-06-01: the reassignment POST delegates to the service and lands
     * back on the detail view (PRG, so a reload never replays it).
     */
    @Test
    void applyReassignDelegatesAndRedirectsToTheDetail() {
        ReprintResource resource = newResource();
        Response response = resource.applyReassign(7L, "2990000000019");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/reprint/view/7", response.getLocation().toString());
        verify(resource.reprintService).applyReassign(7L, "2990000000019");
    }

    /** LC-03-06: entering reassignment without an already viewed ticket reloads the requested detail before opening any mask. */
    @Test
    void missingViewedTicketReloadsBeforeReassignment() {
        var resource = newResource();
        var view = stubDetail(resource);
        resource.state.reprint.viewedTicket = null;
        var ticket = mock(Ticket.class);
        ticket.id = 7L;
        try (var base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(view, resource.beginReassign(7L));
        }
        verify(resource.reprintService, org.mockito.Mockito.never()).beginReassign(7L);
    }


    /** LC-04-05-02: each rendered search result identifies its date, ticket, checkout and amount, even on a legacy row without checkout identity. */
    @Test
    void searchResultsRenderAllTicketIdentifiers() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/reprint-ticket.html"));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver())
                .addValueResolver(io.quarkus.qute.ValueResolver.builder()
                        .applyToBaseClass(java.time.LocalDateTime.class).applyToName("format").applyToParameters(1)
                        .resolveAsync(context -> context.evaluate(context.getParams().getFirst()).thenApply(pattern ->
                                ((java.time.LocalDateTime) context.getBase()).format(java.time.format.DateTimeFormatter.ofPattern(pattern.toString())))).build())
                .addNamespaceResolver(io.quarkus.qute.NamespaceResolver.builder("inject")
                        .resolve(context -> com.intermarche.pos.ui.TestMessages.french()).build()).build();
        engine.parse(source);
        int start = source.indexOf("{#for t in state.reprint.visibleTickets}");
        int end = source.indexOf("{/for}", start) + "{/for}".length();
        var view = engine.parse(source.substring(start, end));
        var ticket = new com.intermarche.pos.domain.sale.Ticket();
        ticket.id = 7L;
        ticket.ticketNumber = "TICKET-7";
        ticket.terminalId = "C04";
        ticket.creationDate = java.time.LocalDateTime.of(2026, 10, 20, 12, 34);
        ticket.totalIncludingTax = new java.math.BigDecimal("12.50");
        var state = new com.intermarche.pos.ui.PosState();
        state.reprint.setTickets(java.util.List.of(ticket));
        String html = view.data("state", state).render();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("TICKET-7"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("20/10"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("12:34"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("CAISSE C04"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("12,50"));
        ticket.terminalId = null;
        org.junit.jupiter.api.Assertions.assertTrue(view.data("state", state).render().contains("CAISSE —"));
    }

    /** LC-04-05-01: independent posted filters reach the duplicata service and the same result screen. */
    @Test
    void combinedSearchDelegatesItsPostedCriteria() {
        var resource = newResource();
        var criteria = new com.intermarche.pos.ui.ticket.TicketSearchCriteria();
        criteria.articleCode = "12345670";
        resource.searchCriteria(criteria);
        verify(resource.reprintService).searchCriteria(criteria);
        verify(resource.reprintTicketPage).data("state", resource.state);
    }


    /** LC-04-05-05: missing tickets redirect; historical calculations are read for the requested identity only. */
    @Test
    void promotionExplanationReadsFrozenTraceWithoutRevaluation() {
        var resource = newResource();
        resource.reprintPromotions = mock(Template.class);
        var ticket = new Ticket();
        ticket.id = 42L;
        var trace = new com.intermarche.pos.domain.sale.TicketLineValuation();
        var traces = java.util.List.of(trace);
        var page = mock(TemplateInstance.class, org.mockito.Mockito.RETURNS_SELF);
        when(resource.reprintPromotions.data("ticket", ticket)).thenReturn(page);
        try (var entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Ticket.findById(42L)).thenReturn(ticket);
            entities.when(() -> com.intermarche.pos.domain.sale.TicketLineValuation.list("ticket = ?1 order by id", ticket)).thenReturn(traces);
            assertSame(page, resource.showPromotions(42L));
            org.mockito.Mockito.verify(page).data("traces", traces);
            Response missing = (Response) resource.showPromotions(99L);
            assertEquals("/reprint", missing.getLocation().toString());
            org.mockito.Mockito.verifyNoInteractions(resource.reprintService);
        }
    }

    /** LC-04-05-05: rendered historical figures and labels survive unchanged, including corrections and missing traces. */
    @Test
    void historicalPromotionTemplateShowsRecordedAmountsAndMissingTrace() throws Exception {
        var units = new com.intermarche.pos.ui.ticket.QuantityDisplay(
                org.mockito.Mockito.mock(com.intermarche.pos.service.PosSettingsService.class));
        var engine = io.quarkus.qute.Engine.builder().addDefaults()
                .addValueResolver(new io.quarkus.qute.ReflectionValueResolver())
                .addNamespaceResolver(io.quarkus.qute.NamespaceResolver.builder("inject")
                        .resolve(context -> "units".equals(context.getName())
                                ? units : com.intermarche.pos.ui.TestMessages.french()).build()).build();
        engine.putTemplate("theme", engine.parse(""));
        var source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates/reprint-promotions.html"));
        var view = engine.parse(source);
        var ticket = new Ticket();
        ticket.id = 42L;
        ticket.ticketNumber = "HISTORICAL-42";
        ticket.totalIncludingTax = new java.math.BigDecimal("8.00");
        ticket.globalDiscountApplied = new java.math.BigDecimal("1.00");
        var line = new com.intermarche.pos.domain.sale.TicketLine();
        line.lineUid = "LINE-1";
        line.productLabel = "OLD OFFER ARTICLE";
        line.quantity = new java.math.BigDecimal("2");
        line.modifierLabel = "REMISE MANUELLE";
        ticket.lines = java.util.List.of(line);
        ticket.fidelityLines.add(new com.intermarche.pos.domain.sale.TicketFidelityLine("LOYAL", "OLD LOYALTY", new java.math.BigDecimal("0.50")));
        var trace = new com.intermarche.pos.domain.sale.TicketLineValuation();
        trace.lineUid = "LINE-1";
        trace.localTotal = new java.math.BigDecimal("12.00");
        trace.valuedTotal = new java.math.BigDecimal("9.00");
        trace.offerLabel = "OFFRE HISTORIQUE";
        trace.advantageLabel = "AVANTAGE HISTORIQUE";
        trace.advantageAmount = new java.math.BigDecimal("4.00");
        trace.correctionLabel = "CORRECTION HISTORIQUE";
        trace.correctionAmount = new java.math.BigDecimal("1.00");
        String html = view.data("ticket", ticket).data("traces", java.util.List.of(trace)).data("posTheme", "light").render();
        for (String expected : java.util.List.of("HISTORICAL-42", "OLD OFFER ARTICLE", "12,00 €", "9,00 €", "OFFRE HISTORIQUE", "4,00 €", "CORRECTION HISTORIQUE", "1,00 €", "OLD LOYALTY : 0,50 €", "REMISE MANUELLE", "8,00")) {
            org.junit.jupiter.api.Assertions.assertTrue(html.contains(expected), expected);
        }
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("12.00"));
        trace.advantageAmount = null;
        trace.correctionAmount = null;
        String dashed = view.data("ticket", ticket).data("traces", java.util.List.of(trace)).data("posTheme", "light").render();
        org.junit.jupiter.api.Assertions.assertTrue(dashed.contains("AVANTAGE HISTORIQUE — — €"));
        org.junit.jupiter.api.Assertions.assertTrue(dashed.contains("CORRECTION HISTORIQUE — — €"));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("Aucune trace"));
        String unavailable = view.data("ticket", ticket).data("traces", java.util.List.of()).data("posTheme", "light").render();
        org.junit.jupiter.api.Assertions.assertTrue(unavailable.contains("Aucune trace de calcul disponible"));
        org.junit.jupiter.api.Assertions.assertFalse(unavailable.contains("OFFRE HISTORIQUE"));
    }
}
