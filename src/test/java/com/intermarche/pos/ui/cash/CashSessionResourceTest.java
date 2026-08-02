package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashSessionResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link PosState}, a
 * {@link CashSessionService}, a {@link TicketPrinterService}, a
 * {@link TechnicalEventService}, a {@link HardwareService} and two Qute
 * {@link Template}s ({@code session}, {@code lock}). Every collaborator is a
 * Mockito mock: the templates echo a recognizable {@link TemplateInstance}
 * chain so the returned view can be identified, and {@code PosState} is a mock
 * whose {@code isLocked()}/{@code trainingMode} gates and {@code auth} holder
 * are driven directly. Tests assert absolute expected values (redirect targets,
 * rendered views) and verify delegation, covering both arms of every guard: the
 * lock and training gates on all mutating routes, the {@code open-failed} /
 * {@code no-session} / unknown error dispatch, the {@code opened == null},
 * {@code current == null}, {@code getOpenSession() == null} and
 * {@code report == null} branches, and the four {@code parseAmount} cases
 * (null, blank, valid, invalid).
 */
class CashSessionResourceTest {

    /**
     * Builds a {@link CashSessionResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, with a real {@link AuthState}
     * carrying a fixed operator id so success paths can read
     * {@code state.auth.operatorId} without hitting a null.
     *
     * @return a resource with fully mocked state, services and templates
     */
    private CashSessionResource newResource() {
        CashSessionResource resource = new CashSessionResource();
        resource.state = mock(PosState.class);
        AuthState auth = new AuthState();
        auth.operatorId = 7L;
        resource.state.auth = auth;
        resource.cashSessionService = mock(CashSessionService.class);
        resource.ticketPrinterService = mock(TicketPrinterService.class);
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.hardwareService = mock(HardwareService.class);
        resource.session = mock(Template.class);
        resource.lock = mock(Template.class);
        return resource;
    }

    /**
     * Stubs the two-link {@code lock} template chain
     * ({@code lock.data("state", state).data("error", null)}).
     *
     * @param resource the resource whose {@code lock} template is stubbed
     * @return the final rendered lock view
     */
    private TemplateInstance stubLockChain(CashSessionResource resource) {
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        when(resource.lock.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("error"), isNull())).thenReturn(ti2);
        return ti2;
    }

    /**
     * Stubs the three-link {@code session} template chain
     * ({@code session.data("state").data("current").data("error")}) with
     * permissive matchers on the current-session and error-message values.
     *
     * @param resource the resource whose {@code session} template is stubbed
     * @return the three chained {@link TemplateInstance} mocks, the last of
     *         which is the final rendered view
     */
    private TemplateInstance[] stubSessionChain(CashSessionResource resource) {
        TemplateInstance ti1 = mock(TemplateInstance.class);
        TemplateInstance ti2 = mock(TemplateInstance.class);
        TemplateInstance ti3 = mock(TemplateInstance.class);
        when(resource.session.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("current"), any())).thenReturn(ti2);
        when(ti2.data(eq("error"), any())).thenReturn(ti3);
        return new TemplateInstance[]{ti1, ti2, ti3};
    }

    /**
     * Asserts the given response is a 303 redirect to the expected location.
     *
     * @param response the response under test
     * @param location the expected {@code Location} header value
     */
    private void assertRedirect(Response response, String location) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(location, response.getLocation().toString());
    }

    // --- sessionPage ---

    /**
     * {@code sessionPage()} renders the lock page when the register is locked
     * ({@code isLocked()} true arm).
     */
    @Test
    void sessionPageLockedRendersLock() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        TemplateInstance lockView = stubLockChain(resource);
        assertSame(lockView, resource.sessionPage("whatever"));
        verifyNoInteractions(resource.cashSessionService);
    }

    /**
     * {@code sessionPage()} renders the already-open error message for the
     * {@code open-failed} code (first {@code equals} true arm).
     */
    @Test
    void sessionPageOpenFailedMessage() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        CashSession current = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(current);
        TemplateInstance[] chain = stubSessionChain(resource);
        assertSame(chain[2], resource.sessionPage("open-failed"));
        verify(chain[0]).data("current", current);
        verify(chain[1]).data("error", "OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)");
    }

    /**
     * {@code sessionPage()} renders the no-session error message for the
     * {@code no-session} code (first {@code equals} false, second true arm).
     */
    @Test
    void sessionPageNoSessionMessage() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        TemplateInstance[] chain = stubSessionChain(resource);
        assertSame(chain[2], resource.sessionPage("no-session"));
        verify(chain[1]).data("error", "AUCUNE SESSION OUVERTE");
    }

    /**
     * {@code sessionPage()} renders a null message for an unrecognized code
     * (both {@code equals} guards false arm).
     */
    @Test
    void sessionPageUnknownErrorNullMessage() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        TemplateInstance[] chain = stubSessionChain(resource);
        assertSame(chain[2], resource.sessionPage("bogus"));
        verify(chain[1]).data("error", (String) null);
    }

    // --- openSession ---

    /**
     * {@code openSession()} redirects with the training message in training
     * mode ({@code trainingMode} true arm), touching nothing.
     */
    @Test
    void openSessionTrainingModeBlocked() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.openSession("10"), "/session?error=INDISPONIBLE+EN+FORMATION");
        verifyNoInteractions(resource.cashSessionService);
        verify(resource.state, never()).touch();
    }

    /**
     * {@code openSession()} redirects to the lock page when locked
     * ({@code trainingMode} false, {@code isLocked()} true arm).
     */
    @Test
    void openSessionLockedRedirectsLock() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(true);
        assertRedirect(resource.openSession("10"), "/lock");
        verifyNoInteractions(resource.cashSessionService);
    }

    /**
     * {@code openSession()} redirects back with {@code open-failed} when the
     * service refuses to open ({@code opened == null} true arm); the null float
     * exercises the {@code parseAmount} {@code value == null} branch.
     */
    @Test
    void openSessionFailedRedirectsOpenFailed() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.openSession(7L, BigDecimal.ZERO)).thenReturn(null);
        assertRedirect(resource.openSession(null), "/session?error=open-failed");
        verify(resource.cashSessionService).openSession(7L, BigDecimal.ZERO);
        verify(resource.state).touch();
    }

    /**
     * {@code openSession()} flows straight into the sale when the session opens
     * ({@code opened == null} false arm); the valid float exercises the
     * {@code parseAmount} successful-parse branch with a French comma.
     */
    @Test
    void openSessionSuccessRedirectsHome() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        CashSession opened = mock(CashSession.class);
        when(resource.cashSessionService.openSession(7L, new BigDecimal("50.00"))).thenReturn(opened);
        assertRedirect(resource.openSession("50,00"), "/");
        verify(resource.cashSessionService).openSession(7L, new BigDecimal("50.00"));
        verify(resource.state).touch();
    }

    /**
     * {@code openSession()} treats an unparsable float as zero
     * ({@code parseAmount} {@code NumberFormatException} catch branch).
     */
    @Test
    void openSessionInvalidFloatParsedAsZero() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        CashSession opened = mock(CashSession.class);
        when(resource.cashSessionService.openSession(7L, BigDecimal.ZERO)).thenReturn(opened);
        assertRedirect(resource.openSession("abc"), "/");
        verify(resource.cashSessionService).openSession(7L, BigDecimal.ZERO);
    }

    // --- printXReport ---

    /**
     * {@code printXReport()} redirects to the lock page when locked
     * ({@code isLocked()} true arm).
     */
    @Test
    void printXReportLockedRedirectsLock() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(true);
        assertRedirect(resource.printXReport(), "/lock");
        verifyNoInteractions(resource.cashSessionService);
    }

    /**
     * {@code printXReport()} redirects with {@code no-session} when no session
     * is open ({@code isLocked()} false, {@code current == null} true arm).
     */
    @Test
    void printXReportNoSessionRedirects() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.printXReport(), "/session?error=no-session");
        verifyNoInteractions(resource.ticketPrinterService);
        verifyNoInteractions(resource.technicalEventService);
    }

    /**
     * {@code printXReport()} builds and prints the report, logs the event and
     * redirects to the session page ({@code current == null} false arm).
     */
    @Test
    void printXReportPrintsAndLogs() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        CashSession current = mock(CashSession.class);
        current.sessionNumber = "S-42";
        when(resource.cashSessionService.getOpenSession()).thenReturn(current);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.buildReport(current)).thenReturn(report);
        assertRedirect(resource.printXReport(), "/session");
        verify(resource.ticketPrinterService).printSessionReport(report);
        verify(resource.technicalEventService).log(TechnicalEvent.EventType.X_REPORT_PRINTED, "S-42");
    }

    // --- startClosing ---

    /**
     * {@code startClosing()} redirects with the training message in training
     * mode ({@code trainingMode} true arm).
     */
    @Test
    void startClosingTrainingModeBlocked() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.startClosing(), "/session?error=INDISPONIBLE+EN+FORMATION");
        verifyNoInteractions(resource.hardwareService);
    }

    /**
     * {@code startClosing()} redirects to the lock page when locked
     * ({@code trainingMode} false, {@code isLocked()} true arm).
     */
    @Test
    void startClosingLockedRedirectsLock() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(true);
        assertRedirect(resource.startClosing(), "/lock");
        verifyNoInteractions(resource.hardwareService);
    }

    /**
     * {@code startClosing()} redirects with {@code no-session} when no session
     * is open ({@code getOpenSession() == null} true arm).
     */
    @Test
    void startClosingNoSessionRedirects() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.startClosing(), "/session?error=no-session");
        verifyNoInteractions(resource.hardwareService);
    }

    /**
     * {@code startClosing()} opens the drawer and routes to the counting page
     * when a session is open ({@code getOpenSession() == null} false arm).
     */
    @Test
    void startClosingOpensDrawerAndRoutes() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.startClosing(), "/cash-count");
        verify(resource.hardwareService).openDrawer();
    }

    // --- closeSession ---

    /**
     * {@code closeSession()} redirects with the training message in training
     * mode ({@code trainingMode} true arm).
     */
    @Test
    void closeSessionTrainingModeBlocked() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = true;
        assertRedirect(resource.closeSession("10", "0", "{}"),
                "/session?error=INDISPONIBLE+EN+FORMATION");
        verifyNoInteractions(resource.cashSessionService);
    }

    /**
     * {@code closeSession()} redirects to the lock page when locked
     * ({@code trainingMode} false, {@code isLocked()} true arm).
     */
    @Test
    void closeSessionLockedRedirectsLock() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(true);
        assertRedirect(resource.closeSession("10", "0", "{}"), "/lock");
        verifyNoInteractions(resource.cashSessionService);
    }

    /**
     * {@code closeSession()} redirects with {@code no-session} when the service
     * finds nothing to close ({@code report == null} true arm); the blank
     * counted value exercises the {@code parseAmount} {@code isBlank} branch.
     */
    @Test
    void closeSessionNoSessionRedirects() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.closeSession(7L, BigDecimal.ZERO, new BigDecimal("5"), "{}"))
                .thenReturn(null);
        assertRedirect(resource.closeSession("   ", "5", "{}"), "/session?error=no-session");
        verify(resource.cashSessionService).closeSession(7L, BigDecimal.ZERO, new BigDecimal("5"), "{}");
        verifyNoInteractions(resource.ticketPrinterService);
        verify(resource.state, never()).touch();
    }

    /**
     * {@code closeSession()} prints the Z report, touches the state and locks
     * the register when the close succeeds ({@code report == null} false arm).
     */
    @Test
    void closeSessionSuccessPrintsAndLocks() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.state.isLocked()).thenReturn(false);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.closeSession(7L, new BigDecimal("100.50"), new BigDecimal("20"), "{}"))
                .thenReturn(report);
        assertRedirect(resource.closeSession("100,50", "20", "{}"), "/lock");
        verify(resource.ticketPrinterService).printSessionReport(report);
        verify(resource.state).touch();
    }
}
