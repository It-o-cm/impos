package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModState;
import com.intermarche.pos.ui.fidelity.FidelityState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, three services
 * ({@link HomeService}, {@link TicketService}, {@link HardwareService}) and five
 * Qute {@link Template}s. Every collaborator is a Mockito mock: templates echo a
 * {@link TemplateInstance} so the returned view can be identified, {@code PosState}
 * exposes its {@code isLocked()} decision and carries plain public fields
 * ({@code returnUrl}, {@code version}, sub-states) that the tests set directly.
 * Tests assert absolute expected values and verify delegation, covering both arms
 * of every lock guard, drawer check, version comparison, quantity/price parsing
 * branch and the {@code null}/empty short-circuits.
 */
class HomeResourceTest {

    /**
     * Builds a {@link HomeResource} whose collaborators are fresh mocks wired onto
     * its package-private fields, including the {@link PosState} sub-state holders
     * so no direct field access hits a null.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private HomeResource newResource() {
        HomeResource resource = new HomeResource();
        resource.state = mock(PosState.class);
        resource.state.ticket = mock(TicketState.class);
        resource.state.fidelity = mock(FidelityState.class);
        resource.state.ageCheck = mock(PosState.AgeCheckState.class);
        resource.state.priceModState = mock(PriceModState.class);
        resource.homeService = mock(HomeService.class);
        resource.ticketService = mock(TicketService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.main = mock(Template.class);
        resource.supervisor = mock(Template.class);
        resource.ticket = mock(Template.class);
        resource.drawerError = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the {@code main} template to return a recognizable view for the given
     * resource's state.
     *
     * @param resource the resource whose {@code main} template is stubbed
     * @return the view {@code main.data("state", state)} will return
     */
    private TemplateInstance stubMain(HomeResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.main.data("state", resource.state)).thenReturn(view);
        return view;
    }

    // --- Drawer error handling ---

    /**
     * {@code drawerErrorPage()} renders the drawer-error page seeded with the state.
     */
    @Test
    void drawerErrorPageRendersDrawerError() {
        HomeResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.drawerError.data("state", resource.state)).thenReturn(view);
        TemplateInstance result = resource.drawerErrorPage();
        assertSame(view, result);
    }

    /**
     * {@code resumeAfterDrawer()} redirects to the stored return URL, clears it and
     * touches the state when a non-empty return URL is present.
     */
    @Test
    void resumeAfterDrawerRedirectsToStoredUrl() {
        HomeResource resource = newResource();
        resource.state.returnUrl = "/pay";
        Response response = resource.resumeAfterDrawer();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/pay", response.getLocation().toString());
        assertNull(resource.state.returnUrl);
        verify(resource.state).touch();
    }

    /**
     * {@code resumeAfterDrawer()} falls back to the home page when the return URL is
     * null (first arm of the guard false).
     */
    @Test
    void resumeAfterDrawerRedirectsHomeWhenUrlNull() {
        HomeResource resource = newResource();
        resource.state.returnUrl = null;
        Response response = resource.resumeAfterDrawer();
        assertEquals("/", response.getLocation().toString());
        verify(resource.state).touch();
    }

    /**
     * {@code resumeAfterDrawer()} falls back to the home page when the return URL is
     * empty (second arm of the guard false).
     */
    @Test
    void resumeAfterDrawerRedirectsHomeWhenUrlEmpty() {
        HomeResource resource = newResource();
        resource.state.returnUrl = "";
        Response response = resource.resumeAfterDrawer();
        assertEquals("/", response.getLocation().toString());
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer still open and no redirect,
     * without clearing the return URL, when the hardware drawer is open.
     */
    @Test
    void checkDrawerStatusReportsOpen() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(true);
        resource.state.returnUrl = "/pay";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(true, result.get("open"));
        assertEquals("", result.get("redirect"));
        assertEquals("/pay", resource.state.returnUrl);
        verify(resource.state, never()).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects to the
     * stored return URL when it is non-empty.
     */
    @Test
    void checkDrawerStatusReportsClosedWithReturnUrl() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = "/pay";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(false, result.get("open"));
        assertEquals("/pay", result.get("redirect"));
        assertNull(resource.state.returnUrl);
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects home when
     * the return URL is null (first arm of the ternary false).
     */
    @Test
    void checkDrawerStatusReportsClosedHomeWhenUrlNull() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = null;
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals(false, result.get("open"));
        assertEquals("/", result.get("redirect"));
        verify(resource.state).touch();
    }

    /**
     * {@code checkDrawerStatus()} reports the drawer closed and redirects home when
     * the return URL is empty (second arm of the ternary false).
     */
    @Test
    void checkDrawerStatusReportsClosedHomeWhenUrlEmpty() {
        HomeResource resource = newResource();
        when(resource.hardwareService.isDrawerOpen()).thenReturn(false);
        resource.state.returnUrl = "";
        Map<String, Object> result = resource.checkDrawerStatus();
        assertEquals("/", result.get("redirect"));
        verify(resource.state).touch();
    }

    // --- Main pages ---

    /**
     * {@code home()} renders the main page when the terminal is unlocked.
     */
    @Test
    void homeRendersMainWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.home());
    }

    /**
     * {@code getTicketFragment()} reports no change when the client version matches
     * the current state version.
     */
    @Test
    void getTicketFragmentReportsUnchangedWhenVersionsMatch() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals(false, result.get("changed"));
        assertFalse(result.containsKey("html"));
        // The lock and payability flags ride on EVERY answer, version match
        // included: they are how an open page learns it must reload.
        assertEquals(false, result.get("locked"));
        assertEquals(false, result.get("payable"));
        verifyNoInteractions(resource.ticket);
    }

    /**
     * {@code getTicketFragment()} reports a change and returns the rendered fragment
     * when the client version is null (first arm of the guard false).
     */
    @Test
    void getTicketFragmentReportsChangedWhenClientVersionNull() {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = true;
        when(resource.state.fidelity.getDisplaySummary()).thenReturn("DUPONT · 2990000000019");
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(new BigDecimal("12.00"));
        TemplateInstance ticketView = mock(TemplateInstance.class);
        when(resource.ticket.data("state", resource.state)).thenReturn(ticketView);
        when(ticketView.render()).thenReturn("<html>");
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals(true, result.get("changed"));
        assertEquals(3L, result.get("version"));
        assertEquals("<html>", result.get("html"));
        assertEquals("12,00", result.get("total"));
        assertEquals(new BigDecimal("12.00"), result.get("amount"));
        assertEquals(false, result.get("locked"));
        assertEquals(true, result.get("payable"));
        assertEquals(true, result.get("fidelityActive"));
        // The attached-card summary rides on the changed answer next to the icon.
        assertEquals("DUPONT · 2990000000019", result.get("fidelitySummary"));
    }

    /**
     * {@code getTicketFragment()} reports a change when the client version differs
     * from the current one (second arm of the guard false).
     */
    @Test
    void getTicketFragmentReportsChangedWhenVersionsDiffer() {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = false;
        when(resource.state.ticket.getTotalFormatted()).thenReturn("0,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        TemplateInstance ticketView = mock(TemplateInstance.class);
        when(resource.ticket.data("state", resource.state)).thenReturn(ticketView);
        when(ticketView.render()).thenReturn("<html>");
        Map<String, Object> result = resource.getTicketFragment(99L);
        assertEquals(true, result.get("changed"));
        assertEquals(3L, result.get("version"));
        assertEquals(false, result.get("locked"));
        assertEquals(false, result.get("payable"));
        assertEquals(false, result.get("fidelityActive"));
        // No card attached: the summary is null on the changed answer too.
        assertNull(result.get("fidelitySummary"));
    }

    /**
     * {@code supervisorPage()} renders the supervisor page when the terminal is
     * unlocked.
     */
    @Test
    void supervisorPageRendersSupervisorWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.supervisor.data("state", resource.state)).thenReturn(view);
        assertSame(view, resource.supervisorPage());
    }

    /**
     * {@code callSupervisor()} normalizes the reason, calls the service and redirects
     * home when the terminal is unlocked.
     */
    @Test
    void callSupervisorSendsReasonWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.callSupervisor("no-change");
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).callSupervisor("NO CHANGE");
    }

    /**
     * {@code toggleTraining()} requests the endorsed toggle and redirects home when
     * the terminal is unlocked.
     */
    @Test
    void toggleTrainingRequestsToggleWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.toggleTraining();
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).requestTrainingToggle();
    }

    // --- Menu navigation ---

    /**
     * {@code showSecondaryMenu()} toggles the secondary menu on and returns the home
     * view.
     */
    @Test
    void showSecondaryMenuTogglesOnAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.showSecondaryMenu());
        verify(resource.homeService).toggleSecondaryMenu(true);
    }

    /**
     * {@code showMainMenu()} toggles the secondary menu off and returns the home
     * view.
     */
    @Test
    void showMainMenuTogglesOffAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.showMainMenu());
        verify(resource.homeService).toggleSecondaryMenu(false);
    }

    // --- Ticket navigation ---

    /**
     * {@code ticketPrev()} moves the ticket display to the previous page and returns
     * the home view.
     */
    @Test
    void ticketPrevMovesBackAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.ticketPrev());
        verify(resource.state).prevPage();
    }

    /**
     * {@code ticketNext()} moves the ticket display to the next page and returns the
     * home view.
     */
    @Test
    void ticketNextMovesForwardAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.ticketNext());
        verify(resource.state).nextPage();
    }

    // --- Selection & cancellation ---

    /**
     * {@code selectLine()} toggles the line selection and returns the home view when
     * unlocked.
     */
    @Test
    void selectLineSelectsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.selectLine(2));
        verify(resource.homeService).selectLine(2);
    }

    /**
     * {@code cancelLine()} cancels the targeted line and returns the home view when
     * unlocked.
     */
    @Test
    void cancelLineCancelsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelLine());
        verify(resource.homeService).cancelLine();
    }

    // --- Price-modification modal ---

    /**
     * {@code openPriceMod()} opens the modal for the given type and returns the home
     * view when unlocked.
     */
    @Test
    void openPriceModOpensWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.openPriceMod("remise"));
        verify(resource.homeService).openPriceMod("remise");
    }

    /**
     * {@code cancelPriceMod()} closes the modal and returns the home view.
     */
    @Test
    void cancelPriceModClosesAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelPriceMod());
        verify(resource.homeService).cancelPriceMod();
    }

    /**
     * {@code submitPriceMod()} parses a comma value and submits it when unlocked.
     */
    @Test
    void submitPriceModSubmitsParsedValue() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "1,5");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod("REMISE", "u1", new BigDecimal("1.5"));
    }

    /**
     * {@code submitPriceMod()} defaults a null raw value to zero (first arm of the
     * empty guard true) and submits it.
     */
    @Test
    void submitPriceModDefaultsNullValueToZero() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod("REMISE", "u1", new BigDecimal("0"));
    }

    /**
     * {@code submitPriceMod()} defaults an empty raw value to zero (second arm of the
     * empty guard true) and submits it.
     */
    @Test
    void submitPriceModDefaultsEmptyValueToZero() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.homeService).submitPriceMod("REMISE", "u1", new BigDecimal("0"));
    }

    /**
     * {@code submitPriceMod()} sets an error, clears the modal and touches the state
     * without submitting when the raw value is not a number.
     */
    @Test
    void submitPriceModRejectsInvalidValue() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.submitPriceMod("REMISE", "u1", "abc");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.state.ticket).setError("VALEUR INVALIDE");
        verify(resource.state.priceModState).clear();
        verify(resource.state).touch();
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    // --- Other actions ---

    /**
     * {@code addPlu()} adds the weighed product by PLU and returns the home view when
     * unlocked.
     */
    @Test
    void addPluAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addPlu("123"));
        verify(resource.ticketService).addItemByPlu(resource.state, "123");
    }

    /**
     * {@code addManualKnown()} parses a positive quantity and adds the product when
     * unlocked (both null/empty guards true, {@code qty <= 0} false).
     */
    @Test
    void addManualKnownAddsParsedQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "3");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(3));
    }

    /**
     * {@code addManualKnown()} defaults a null quantity to one (first arm of the guard
     * false) and adds the product.
     */
    @Test
    void addManualKnownDefaultsNullQuantityToOne() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} defaults an empty quantity to one (second arm of the
     * guard false) and adds the product.
     */
    @Test
    void addManualKnownDefaultsEmptyQuantityToOne() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} swallows an unparsable quantity and falls back to one.
     */
    @Test
    void addManualKnownFallsBackOnUnparsableQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "abc");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} clamps a non-positive quantity to one ({@code qty <= 0}
     * true arm).
     */
    @Test
    void addManualKnownClampsNonPositiveQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualKnown("EAN", "0");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualUnknown()} adds the unlisted item and redirects to the home page
     * when unlocked.
     */
    @Test
    void addManualUnknownAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.addManualUnknown("Label", "2,00");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/", response.getLocation().toString());
        verify(resource.ticketService).addUnknownItem(resource.state, "Label", "2,00");
    }

    /**
     * {@code addDepositReturn()} adds a deposit-return line and returns the home view
     * when unlocked.
     */
    @Test
    void addDepositReturnAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addDepositReturn());
        verify(resource.ticketService).addDeposit(resource.state);
    }

    /**
     * {@code cancelTicket()} requests the whole-ticket cancellation and returns the
     * home view.
     */
    @Test
    void cancelTicketRequestsCancellationAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.cancelTicket());
        verify(resource.homeService).cancelTicket();
    }

    /**
     * {@code printLast()} reprints the last closed ticket and returns the home view.
     */
    @Test
    void printLastReprintsAndReturnsHome() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.printLast());
        verify(resource.homeService).printLastTicket();
    }

    /**
     * Prepares a resource whose fragment can be rendered, with the given earn
     * projection on the card.
     *
     * @param earnTotal the projected earn, or null when none
     * @return the ready resource
     */
    private HomeResource fragmentResourceWithEarn(java.math.BigDecimal earnTotal) {
        HomeResource resource = newResource();
        resource.state.version = 3L;
        resource.state.fidelity.active = true;
        resource.state.fidelity.earnTotal = earnTotal;
        when(resource.state.ticket.getTotalFormatted()).thenReturn("12,00");
        when(resource.state.ticket.getTotalAmount()).thenReturn(new BigDecimal("12.00"));
        TemplateInstance ticketView = mock(TemplateInstance.class);
        when(resource.ticket.data("state", resource.state)).thenReturn(ticketView);
        when(ticketView.render()).thenReturn("<html>");
        return resource;
    }

    /**
     * A POSITIVE projection produces the badge, formatted to the cent and in
     * FRENCH notation — the comma matters: the badge is read by a cashier,
     * not by a parser.
     */
    @Test
    void getTicketFragmentFormatsTheEarnBadge() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("1.03"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals("AVANTAGE CARTE 1,03 €", result.get("fidelityEarn"));
    }

    /**
     * The amount is ROUNDED to two decimals like any money on screen.
     */
    @Test
    void getTicketFragmentRoundsTheEarnBadgeToCents() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("2.345"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals("AVANTAGE CARTE 2,35 €", result.get("fidelityEarn"));
    }

    /**
     * A NULL projection yields a null badge (first leg of the guard): this is
     * the DEGRADED display — imfid is unreachable, and the register says so
     * by saying nothing rather than by showing a zero it cannot vouch for.
     */
    @Test
    void getTicketFragmentHidesTheBadgeWhenNoProjection() {
        HomeResource resource = fragmentResourceWithEarn(null);
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
        assertEquals(true, result.get("fidelityActive"));
    }

    /**
     * A ZERO projection hides the badge too (second leg, {@code signum() ==
     * 0}): a cart entirely absorbed by an offer earns nothing, and "AVANTAGE
     * CARTE 0,00 €" would look like a bug to the customer.
     */
    @Test
    void getTicketFragmentHidesTheBadgeOnAZeroProjection() {
        HomeResource resource = fragmentResourceWithEarn(BigDecimal.ZERO);
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
    }

    /**
     * A NEGATIVE projection hides the badge as well (second leg, {@code
     * signum() < 0}): the guard is {@code > 0}, so no arithmetic accident
     * can ever advertise a negative advantage.
     */
    @Test
    void getTicketFragmentHidesTheBadgeOnANegativeProjection() {
        HomeResource resource = fragmentResourceWithEarn(new BigDecimal("-1.00"));
        Map<String, Object> result = resource.getTicketFragment(null);
        assertNull(result.get("fidelityEarn"));
    }

    /**
     * The badge is INDEPENDENT of the card flag: what drives it is the
     * projection, so an attached card with no projection shows nothing.
     */
    @Test
    void getTicketFragmentBadgeFollowsTheProjectionNotTheCardFlag() {
        HomeResource resource = fragmentResourceWithEarn(null);
        resource.state.fidelity.active = true;
        Map<String, Object> result = resource.getTicketFragment(null);
        assertEquals(true, result.get("fidelityActive"));
        assertNull(result.get("fidelityEarn"));
    }

    // --- age check ---

    /**
     * Confirming the ID check replays the parked gesture and returns the
     * cashier to the sale screen.
     */
    @Test
    void ageCheckConfirmReplaysAndRedirects() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.ageCheckConfirm();
        verify(resource.ticketService).confirmAgeCheck(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/"), response.getLocation());
    }

    /**
     * Refusing journals the refusal, clears the parked gesture and returns to
     * the sale screen.
     */
    @Test
    void ageCheckRefuseJournalsAndRedirects() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        Response response = resource.ageCheckRefuse();
        verify(resource.ticketService).refuseAgeCheck(resource.state);
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/"), response.getLocation());
    }

}
