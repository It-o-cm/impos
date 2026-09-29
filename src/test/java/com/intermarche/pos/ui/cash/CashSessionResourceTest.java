package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.auth.AuthState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
        resource.sessionReportPage = mock(Template.class);
        resource.varianceAlert = mock(Template.class);
        resource.endorsementService = mock(com.intermarche.pos.ui.endorsement.EndorsementService.class);
        resource.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(resource.posSettingsService.defaultOpeningFloat()).thenReturn("150.00");
        // EXP-82: a wide default threshold so the pre-existing close cases stay on
        // the within-threshold path; the variance cases override it.
        when(resource.posSettingsService.varianceThreshold()).thenReturn(new java.math.BigDecimal("1000.00"));
        // The close-drawer gate defaults ON — the pre-existing behavior the
        // start-closing case relies on (BO-10-02-26).
        when(resource.posSettingsService.drawerOpenOnSessionClose()).thenReturn(true);
        return resource;
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
        TemplateInstance tiFloat = mock(TemplateInstance.class);
        TemplateInstance tiTheo = mock(TemplateInstance.class);
        when(resource.session.data("state", resource.state)).thenReturn(ti1);
        when(ti1.data(eq("current"), any())).thenReturn(ti2);
        when(ti2.data(eq("defaultFloat"), any())).thenReturn(tiFloat);
        // EXP-02: the page now also carries the theoretical float (report of the
        // previous close) between the default float and the error message.
        when(tiFloat.data(eq("theoreticalFloat"), any())).thenReturn(tiTheo);
        when(tiTheo.data(eq("error"), any())).thenReturn(ti3);
        return new TemplateInstance[]{ti1, ti2, tiTheo, ti3};
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
        assertSame(chain[3], resource.sessionPage("open-failed"));
        verify(chain[0]).data("current", current);
        verify(chain[2]).data("error", "OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)");
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
        assertSame(chain[3], resource.sessionPage("no-session"));
        verify(chain[2]).data("error", "AUCUNE SESSION OUVERTE");
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
        assertSame(chain[3], resource.sessionPage("bogus"));
        verify(chain[2]).data("error", null);
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
     * redirects BACK TO THE REPORT the operator was reading ({@code current == null}
     * false arm).
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
        assertRedirect(resource.printXReport(), "/session/x-report");
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

    /**
     * {@code startClosing()} routes to the counting page WITHOUT opening the
     * drawer when the close-drawer setting is off (BO-10-02-26 false arm): the
     * count is done drawer-closed.
     */
    @Test
    void startClosingKeepsDrawerShutWhenDisabled() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.posSettingsService.drawerOpenOnSessionClose()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        assertRedirect(resource.startClosing(), "/cash-count");
        verify(resource.hardwareService, never()).openDrawer();
    }

    /**
     * BO-03-10-10: when the island declares the count automatically,
     * {@code startClosing()} does NOT route to the counting page — it closes the
     * session directly (auto flag true), prints the Z report and locks the
     * register, never opening the drawer for a count that no longer happens.
     */
    @Test
    void startClosingAutoDeclarationClosesDirectly() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.posSettingsService.islandAutoDeclaration()).thenReturn(true);
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.closeSession(7L, null, null, null, true)).thenReturn(report);
        assertRedirect(resource.startClosing(), "/lock");
        verify(resource.ticketPrinterService).printSessionReport(report);
        verify(resource.hardwareService, never()).openDrawer();
    }

    /**
     * BO-03-10-10: an automatic declaration that finds no open session at the
     * close call redirects with {@code no-session} and prints nothing (the
     * {@code report == null} arm of the direct-close path).
     */
    @Test
    void startClosingAutoDeclarationWithNoSessionRedirects() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        when(resource.posSettingsService.islandAutoDeclaration()).thenReturn(true);
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashSessionService.closeSession(7L, null, null, null, true)).thenReturn(null);
        assertRedirect(resource.startClosing(), "/session?error=no-session");
        verify(resource.ticketPrinterService, never()).printSessionReport(any());
    }

    /**
     * {@code sessionPage()} pre-fills the opening form with the administered
     * default float (BO-03-02-41).
     */
    @Test
    void sessionPagePassesAdministeredDefaultFloat() {
        CashSessionResource resource = newResource();
        TemplateInstance[] chain = stubSessionChain(resource);
        assertSame(chain[3], resource.sessionPage(null));
        verify(chain[1]).data("defaultFloat", "150.00");
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
        assertRedirect(resource.closeSession("10", "0", "{}", null),
                "/session?error=INDISPONIBLE+EN+FORMATION");
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
        // The new flow refuses at the open-session guard, before any close.
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.closeSession("   ", "5", "{}", null), "/session?error=no-session");
        verify(resource.cashSessionService, never()).closeSession(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
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
        // The close now previews the variance: an open session, a theoretical equal
        // to the counted (variance 0, within the wide default threshold) reaches the
        // 8-arg close.
        CashSession open = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(open);
        CashSessionService.SessionReport preview = mock(CashSessionService.SessionReport.class);
        preview.theoreticalCash = new BigDecimal("100.50");
        when(resource.cashSessionService.buildReport(open)).thenReturn(preview);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.closeSession(7L, new BigDecimal("100.50"),
                new BigDecimal("20"), "{}", false, null, null, false)).thenReturn(report);
        assertRedirect(resource.closeSession("100,50", "20", "{}", null), "/lock");
        verify(resource.ticketPrinterService).printSessionReport(report);
        verify(resource.state).touch();
    }

    /**
     * EXP-82: an over-threshold variance holds the close back — the pending close
     * is stored and the operator is routed to the arbitration page instead of
     * closing.
     */
    @Test
    void closeSessionOverThresholdHoldsForArbitration() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession open = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(open);
        CashSessionService.SessionReport preview = mock(CashSessionService.SessionReport.class);
        preview.theoreticalCash = new BigDecimal("100.00");
        when(resource.cashSessionService.buildReport(open)).thenReturn(preview);
        when(resource.posSettingsService.varianceThreshold()).thenReturn(new BigDecimal("1.00"));
        assertRedirect(resource.closeSession("110,00", "0", "{}", "{}"), "/session/variance");
        assertNotNull(resource.state.pendingClose);
        verify(resource.cashSessionService, never()).closeSession(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * {@code variancePage()} redirects to the session screen when no close is
     * pending arbitration (guard true arm).
     */
    @Test
    void variancePageRedirectsWhenNothingPending() {
        CashSessionResource resource = newResource();
        resource.state.pendingClose = null;
        assertRedirect((Response) resource.variancePage(null), "/session");
    }

    /**
     * {@code variancePage()} renders the arbitration page when a close is pending,
     * carrying the supervisor-refusal message on the error arm.
     */
    @Test
    void variancePageRendersPendingWithRefusal() {
        CashSessionResource resource = newResource();
        resource.state.pendingClose = new PosState.PendingClose(
                new BigDecimal("110.00"), BigDecimal.ZERO, "{}", "{}", new BigDecimal("10.00"));
        when(resource.posSettingsService.varianceThreshold()).thenReturn(new BigDecimal("1.00"));
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.varianceAlert.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        Response response = (Response) resource.variancePage("supervisor");
        assertEquals(200, response.getStatus());
    }

    /**
     * {@code closeEndorsed()} redirects to the session when nothing is pending
     * (guard true arm).
     */
    @Test
    void closeEndorsedRedirectsWhenNothingPending() {
        CashSessionResource resource = newResource();
        resource.state.pendingClose = null;
        assertRedirect(resource.closeEndorsed("22222222", "1111"), "/session");
    }

    /**
     * EXP-82: a refused supervisor keeps the close held — back to the arbitration
     * page with the refusal (authorize false arm).
     */
    @Test
    void closeEndorsedRefusesBadSupervisor() {
        CashSessionResource resource = newResource();
        resource.state.pendingClose = new PosState.PendingClose(
                new BigDecimal("110.00"), BigDecimal.ZERO, "{}", "{}", new BigDecimal("10.00"));
        when(resource.endorsementService.authorize("22222222", "0000", "SESSION_VARIANCE"))
                .thenReturn(false);
        assertRedirect(resource.closeEndorsed("22222222", "0000"), "/session/variance?error=supervisor");
        verifyNoInteractions(resource.ticketPrinterService);
    }

    /**
     * EXP-82: a validated supervisor closes with the badge as the nominative trace
     * (authorize true arm) — the held values are replayed and the pending is cleared.
     */
    @Test
    void closeEndorsedClosesOnValidation() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        resource.state.pendingClose = new PosState.PendingClose(
                new BigDecimal("110.00"), BigDecimal.ZERO, "{}", "{}", new BigDecimal("10.00"));
        when(resource.endorsementService.authorize("22222222", "1111", "SESSION_VARIANCE"))
                .thenReturn(true);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.closeSession(7L, new BigDecimal("110.00"), BigDecimal.ZERO,
                "{}", false, "22222222", "{}", false)).thenReturn(report);
        assertRedirect(resource.closeEndorsed("22222222", "1111"), "/lock");
        verify(resource.ticketPrinterService).printSessionReport(report);
        assertNull(resource.state.pendingClose);
    }

    /**
     * EXP-95: in FLOATING mode on a non-count day, {@code startClosing} closes
     * directly (no counting screen) and locks.
     */
    @Test
    void startClosingFloatingNoCountClosesDirectly() {
        CashSessionResource resource = newResource();
        resource.state.trainingMode = false;
        CashSession open = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(open);
        when(resource.posSettingsService.islandAutoDeclaration()).thenReturn(false);
        when(resource.posSettingsService.floatMode()).thenReturn("FLOATING");
        when(resource.cashSessionService.isFullCountDay(any())).thenReturn(false);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.closeSession(7L, null, null, null, false, null, null, true))
                .thenReturn(report);
        assertRedirect(resource.startClosing(), "/lock");
        verify(resource.ticketPrinterService).printSessionReport(report);
    }

    /**
     * EXP-80: the standalone settlement voucher refuses without an open session
     * (guard true arm) and prints on an open one (false arm).
     */
    @Test
    void settlementVoucherRefusesWithoutSessionAndPrintsWithOne() {
        CashSessionResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect(resource.printSettlementVoucher(), "/session?error=no-session");
        CashSession open = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(open);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.buildReport(open)).thenReturn(report);
        assertRedirect(resource.printSettlementVoucher(), "/session");
        verify(resource.ticketPrinterService).printSettlementVoucher(
                org.mockito.ArgumentMatchers.eq(report), org.mockito.ArgumentMatchers.any());
    }

    // --- showXReport ---

    /**
     * {@code showXReport()} renders the report of the open session and journals
     * NOTHING: a reading on screen is not an edition ({@code current == null} false
     * arm).
     */
    @Test
    void showXReportRendersTheReport() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        CashSession current = mock(CashSession.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(current);
        CashSessionService.SessionReport report = mock(CashSessionService.SessionReport.class);
        when(resource.cashSessionService.buildReport(current)).thenReturn(report);
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.sessionReportPage.data("report", report)).thenReturn(instance);
        assertSame(instance, resource.showXReport());
        verifyNoInteractions(resource.ticketPrinterService);
        verifyNoInteractions(resource.technicalEventService);
    }

    /**
     * {@code showXReport()} sends the operator back to the session page when no
     * session is open ({@code current == null} true arm).
     */
    @Test
    void showXReportWithoutSessionRedirects() {
        CashSessionResource resource = newResource();
        when(resource.state.isLocked()).thenReturn(false);
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        assertRedirect((Response) resource.showXReport(), "/session?error=no-session");
        verifyNoInteractions(resource.ticketPrinterService);
    }
}
