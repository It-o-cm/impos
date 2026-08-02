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
        resource.state.priceModState = mock(PriceModState.class);
        resource.homeService = mock(HomeService.class);
        resource.ticketService = mock(TicketService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.main = mock(Template.class);
        resource.lock = mock(Template.class);
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

    /**
     * Stubs the {@code lock} template to return a recognizable view for the given
     * resource's state.
     *
     * @param resource the resource whose {@code lock} template is stubbed
     * @return the view {@code lock.data("state", state)} will return
     */
    private TemplateInstance stubLock(HomeResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(view);
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
     * {@code home()} renders the lock page when the terminal is locked.
     */
    @Test
    void homeRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.home());
        verifyNoInteractions(resource.main);
    }

    /**
     * {@code home()} renders the main page when the terminal is unlocked.
     */
    @Test
    void homeRendersMainWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.home());
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code getTicketFragment()} reports no change when the client version matches
     * the current state version.
     */
    @Test
    void getTicketFragmentReportsUnchangedWhenVersionsMatch() {
        HomeResource resource = newResource();
        resource.state.version = 7L;
        Map<String, Object> result = resource.getTicketFragment(7L);
        assertEquals(false, result.get("changed"));
        assertFalse(result.containsKey("html"));
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
        assertEquals(true, result.get("fidelityActive"));
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
        assertEquals(false, result.get("fidelityActive"));
    }

    /**
     * {@code supervisorPage()} renders the lock page when the terminal is locked.
     */
    @Test
    void supervisorPageRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.supervisorPage());
        verifyNoInteractions(resource.supervisor);
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
        verifyNoInteractions(resource.lock);
    }

    /**
     * {@code callSupervisor()} redirects to the lock page without calling the service
     * when the terminal is locked.
     */
    @Test
    void callSupervisorRedirectsToLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.callSupervisor("no-change");
        assertEquals("/lock", response.getLocation().toString());
        verifyNoInteractions(resource.homeService);
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
     * {@code toggleTraining()} redirects to the lock page without requesting the
     * toggle when the terminal is locked.
     */
    @Test
    void toggleTrainingRedirectsToLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        Response response = resource.toggleTraining();
        assertEquals("/lock", response.getLocation().toString());
        verifyNoInteractions(resource.homeService);
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
     * {@code selectLine()} renders the lock page without touching the service when
     * the terminal is locked.
     */
    @Test
    void selectLineRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.selectLine(2));
        verifyNoInteractions(resource.homeService);
    }

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
     * {@code cancelLine()} renders the lock page without touching the service when
     * the terminal is locked.
     */
    @Test
    void cancelLineRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.cancelLine());
        verifyNoInteractions(resource.homeService);
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
     * {@code openPriceMod()} renders the lock page without touching the service when
     * the terminal is locked.
     */
    @Test
    void openPriceModRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.openPriceMod("remise"));
        verifyNoInteractions(resource.homeService);
    }

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
     * {@code submitPriceMod()} renders the lock page without submitting when the
     * terminal is locked.
     */
    @Test
    void submitPriceModRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.submitPriceMod("REMISE", "u1", "1,5"));
        verifyNoInteractions(resource.homeService);
    }

    /**
     * {@code submitPriceMod()} parses a comma value and submits it when unlocked.
     */
    @Test
    void submitPriceModSubmitsParsedValue() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.submitPriceMod("REMISE", "u1", "1,5"));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.submitPriceMod("REMISE", "u1", null));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.submitPriceMod("REMISE", "u1", ""));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.submitPriceMod("REMISE", "u1", "abc"));
        verify(resource.state.ticket).setError("VALEUR INVALIDE");
        verify(resource.state.priceModState).clear();
        verify(resource.state).touch();
        verify(resource.homeService, never()).submitPriceMod(any(), any(), any());
    }

    // --- Other actions ---

    /**
     * {@code addPlu()} renders the lock page without touching the service when the
     * terminal is locked.
     */
    @Test
    void addPluRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.addPlu("123"));
        verifyNoInteractions(resource.ticketService);
    }

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
     * {@code addManualKnown()} renders the lock page without touching the service when
     * the terminal is locked.
     */
    @Test
    void addManualKnownRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.addManualKnown("EAN", "3"));
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code addManualKnown()} parses a positive quantity and adds the product when
     * unlocked (both null/empty guards true, {@code qty <= 0} false).
     */
    @Test
    void addManualKnownAddsParsedQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualKnown("EAN", "3"));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualKnown("EAN", null));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualKnown("EAN", ""));
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualKnown()} swallows an unparsable quantity and falls back to one.
     */
    @Test
    void addManualKnownFallsBackOnUnparsableQuantity() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualKnown("EAN", "abc"));
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
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualKnown("EAN", "0"));
        verify(resource.ticketService).addItemByEan(resource.state, "EAN", BigDecimal.valueOf(1));
    }

    /**
     * {@code addManualUnknown()} renders the lock page without touching the service
     * when the terminal is locked.
     */
    @Test
    void addManualUnknownRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.addManualUnknown("Label", "2,00"));
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code addManualUnknown()} adds the unlisted item and returns the home view when
     * unlocked.
     */
    @Test
    void addManualUnknownAddsWhenUnlocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        TemplateInstance mainView = stubMain(resource);
        assertSame(mainView, resource.addManualUnknown("Label", "2,00"));
        verify(resource.ticketService).addUnknownItem(resource.state, "Label", "2,00");
    }

    /**
     * {@code addDepositReturn()} renders the lock page without touching the service
     * when the terminal is locked.
     */
    @Test
    void addDepositReturnRendersLockWhenLocked() {
        HomeResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLock(resource);
        assertSame(lockView, resource.addDepositReturn());
        verifyNoInteractions(resource.ticketService);
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
}
