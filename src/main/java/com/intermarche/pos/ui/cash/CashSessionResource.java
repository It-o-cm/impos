package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;

/**
 * JAX-RS resource driving the cash-session screen: session status page,
 * opening with an initial float, X report printing, and the Z closing flow
 * that runs through the cash-count page.
 * <p>
 * Closing is a TWO-STEP flow by design: {@code /action/session/close-start}
 * only checks that a session exists and hands over to the drawer-count page
 * ({@code CashCountResource}), and the actual closing happens when the
 * counted total comes back on {@code /action/session/close} with its
 * per-denomination JSON and the optional withdrawal — the Z is therefore
 * always backed by a physical count, never typed blind. The X report is
 * printable at any time and mutates nothing. Phase 6: the three mutating
 * routes (open, close-start, close) are blocked in training mode with a
 * redirect message; the status page and the X stay reachable.
 * <p>
 * Navigation contract: a successful OPENING lands on the SALE screen (the
 * take-over flow reads badge, PIN, float, sale — see AuthResource); this
 * page is where the cashier comes back DELIBERATELY, via the menu, for the
 * mid-day X and the evening Z.
 */
@Path("/")
public class CashSessionResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashSessionResource.class);

    @Inject @Location("session") Template session;
    /** The X report, read on screen before it is asked for on paper. */
    @Inject @Location("session-report") Template sessionReportPage;
    /** The over-threshold discrepancy arbitration page (EXP-82). */
    @Inject @Location("variance-alert") Template varianceAlert;
    /** Checks and journals the supervisor validation of a discrepancy (EXP-82). */
    @Inject com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;
    @Inject CashSessionService cashSessionService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject TechnicalEventService technicalEventService;
    @Inject HardwareService hardwareService;
    @Inject PosState state;
    /** The back-office parameters (opening-float default, close-drawer gate). */
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * Shows the session page: current open session, or the opening form.
     *
     * @param error an optional error code from a redirect
     * @return the session page
     */
    @GET
    @Path("/session")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance sessionPage(@QueryParam("error") String error) {
        LOGGER.info("Entering method sessionPage with error: " + error);
        String message = null;
        if ("open-failed".equals(error)) {
            message = "OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)";
        } else if ("no-session".equals(error)) {
            message = "AUCUNE SESSION OUVERTE";
        }
        // EXP-02: the float shown at opening is the report of the previous close
        // (what stayed in the drawer), not the administered default — which only
        // applies on a register that was never closed here.
        BigDecimal theoretical = cashSessionService.theoreticalOpeningFloat();
        String prefill = theoretical != null
                ? String.format("%.2f", theoretical).replace('.', ',')
                : posSettingsService.defaultOpeningFloat();
        LOGGER.info("Exiting method sessionPage");
        return session
                .data("state", state)
                .data("current", cashSessionService.getOpenSession())
                .data("defaultFloat", prefill)
                .data("theoreticalFloat", theoretical != null
                        ? String.format("%.2f", theoretical).replace('.', ',') : null)
                .data("error", message);
    }

    /**
     * Opens a session with the typed initial float.
     *
     * @param floatStr the initial float typed by the cashier
     * @return a redirect to the session page
     */
    @POST
    @Path("/action/session/open")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response openSession(@FormParam("openingFloat") String floatStr) {
        LOGGER.info("Entering method openSession with floatStr: " + floatStr);
        if (state.trainingMode) {
            LOGGER.info("Exiting method openSession");
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        BigDecimal openingFloat = parseAmount(floatStr);
        CashSession opened = cashSessionService.openSession(state.auth.operatorId, openingFloat);
        state.touch();
        if (opened == null) {
            LOGGER.info("Exiting method openSession");
            return Response.seeOther(URI.create("/session?error=open-failed")).build();
        }
        // A successful opening flows straight into the sale: the status page
        // (X report, Z closing) is a MENU destination, not a post-opening
        // stop — right after opening, its only visible actions would be
        // reading and CLOSING, which is absurd at that point of the day.
        LOGGER.info("Exiting method openSession");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * SHOWS the X report of the open session (read-only snapshot).
     *
     * <p>An X is a READING: its whole purpose is to look at where the session stands
     * — mid-shift check, handover between cashiers, control before a withdrawal. It
     * used to exist as a print alone, so an operator pressing the key saw nothing at
     * all, and nothing whatsoever on a register whose printer is out of paper. The
     * paper is now what one asks for AFTER reading, not instead of it.
     *
     * <p>Nothing is journalled here: a reading on screen is not an edition. The
     * journal entry belongs to the print, which is the copy that leaves the register.
     *
     * @return the report page, or a redirect to the session page when none is open
     */
    @GET
    @Path("/session/x-report")
    @Produces(MediaType.TEXT_HTML)
    public Object showXReport() {
        LOGGER.info("Entering method showXReport");
        CashSession current = cashSessionService.getOpenSession();
        if (current == null) {
            LOGGER.info("Exiting method showXReport");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        LOGGER.info("Exiting method showXReport");
        return sessionReportPage.data("report", cashSessionService.buildReport(current));
    }

    /**
     * Prints the X report of the open session, from the report screen.
     *
     * @return a redirect back to the report page
     */
    @GET
    @Path("/action/session/x-report")
    public Response printXReport() {
        LOGGER.info("Entering method printXReport");
        CashSession current = cashSessionService.getOpenSession();
        if (current == null) {
            LOGGER.info("Exiting method printXReport");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        CashSessionService.SessionReport report = cashSessionService.buildReport(current);
        ticketPrinterService.printSessionReport(report);
        technicalEventService.log(TechnicalEvent.EventType.X_REPORT_PRINTED, current.sessionNumber);
        LOGGER.info("Exiting method printXReport");
        return Response.seeOther(URI.create("/session/x-report")).build();
    }

    /**
     * Starts the Z closing flow: opens the drawer and routes to the counting
     * page.
     *
     * @return a redirect to the cash-count page
     */
    @GET
    @Path("/action/session/close-start")
    public Response startClosing() {
        LOGGER.info("Entering method startClosing");
        if (state.trainingMode) {
            LOGGER.info("Exiting method startClosing");
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        if (cashSessionService.getOpenSession() == null) {
            LOGGER.info("Exiting method startClosing");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        // BO-03-10-10: when the island declares the end of period automatically,
        // the hostess is dispensed from counting — the session closes at once on
        // its theoretical, the Z report prints, and the register locks, without
        // ever showing the counting screen.
        if (posSettingsService.islandAutoDeclaration()) {
            CashSessionService.SessionReport report = cashSessionService.closeSession(
                    state.auth.operatorId, null, null, null, true);
            if (report == null) {
                LOGGER.info("Exiting method startClosing");
                return Response.seeOther(URI.create("/session?error=no-session")).build();
            }
            ticketPrinterService.printSessionReport(report);
            state.touch();
            LOGGER.info("Exiting method startClosing");
            return Response.seeOther(URI.create("/lock")).build();
        }
        // EXP-95: in FLOATING mode, only the full-count days (week-end and the
        // last day of the month) count the drawer. On any other evening the
        // register withdraws the surplus above the initial float and arbitrates
        // NO variance — the counting screen is skipped entirely.
        if ("FLOATING".equals(posSettingsService.floatMode())
                && !cashSessionService.isFullCountDay(java.time.LocalDate.now())) {
            CashSessionService.SessionReport report = cashSessionService.closeSession(
                    state.auth.operatorId, null, null, null, false, null, null, true);
            if (report == null) {
                LOGGER.info("Exiting method startClosing");
                return Response.seeOther(URI.create("/session?error=no-session")).build();
            }
            ticketPrinterService.printSessionReport(report);
            state.touch();
            LOGGER.info("Exiting method startClosing");
            return Response.seeOther(URI.create("/lock")).build();
        }
        // BO-10-02-26: the Z count opens the drawer only when the back office
        // asks for it; disabled, the count is done drawer-closed.
        if (posSettingsService.drawerOpenOnSessionClose()) {
            hardwareService.openDrawer();
        }
        LOGGER.info("Exiting method startClosing");
        return Response.seeOther(URI.create("/cash-count")).build();
    }

    /**
     * Closes the open session (Z report) with the counted amount, the
     * denominations detail and the withdrawal, prints the Z report and locks
     * the register.
     *
     * @param countedStr the counted cash total from the counting page
     * @param withdrawnStr the withdrawn amount typed by the cashier
     * @param detail the denominations detail as entered (JSON)
     * @return a redirect to the lock page, or back to the session page when
     *         no session is open
     */
    @POST
    @Path("/action/session/close")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response closeSession(@FormParam("counted") String countedStr,
                                 @FormParam("withdrawn") String withdrawnStr,
                                 @FormParam("detail") String detail,
                                 @FormParam("paperDeclaration") String paperDeclaration) {
        LOGGER.info("Entering method closeSession with countedStr: " + countedStr + ", withdrawnStr: " + withdrawnStr + ", detail: " + detail);
        if (state.trainingMode) {
            LOGGER.info("Exiting method closeSession");
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        CashSession open = cashSessionService.getOpenSession();
        if (open == null) {
            LOGGER.info("Exiting method closeSession");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        BigDecimal counted = parseAmount(countedStr);
        BigDecimal withdrawn = parseAmount(withdrawnStr);
        // EXP-82: the discrepancy is arbitrated BEFORE the close commits. Over the
        // administered threshold in absolute value, the close is held back and the
        // operator must recount or have a supervisor validate — the close does not
        // pass on its own.
        BigDecimal theoretical = cashSessionService.buildReport(open).theoreticalCash;
        BigDecimal variance = counted.subtract(theoretical);
        if (variance.abs().compareTo(posSettingsService.varianceThreshold()) > 0) {
            state.pendingClose = new PosState.PendingClose(counted, withdrawn, detail,
                    paperDeclaration, variance);
            state.touch();
            LOGGER.info("Exiting method closeSession (variance held for arbitration)");
            return Response.seeOther(URI.create("/session/variance")).build();
        }
        CashSessionService.SessionReport report = cashSessionService.closeSession(
                state.auth.operatorId, counted, withdrawn, detail, false, null, paperDeclaration, false);
        if (report == null) {
            LOGGER.info("Exiting method closeSession");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        ticketPrinterService.printSessionReport(report);
        state.touch();
        LOGGER.info("Exiting method closeSession");
        return Response.seeOther(URI.create("/lock")).build();
    }

    /**
     * Shows the discrepancy arbitration page (EXP-82): the écart that crossed the
     * threshold, and the two exits — recount or supervisor validation.
     *
     * @param error an optional refusal from a rejected supervisor validation
     * @return the arbitration page, or a redirect when nothing is pending
     */
    @GET
    @Path("/session/variance")
    @Produces(MediaType.TEXT_HTML)
    public Response variancePage(@QueryParam("error") String error) {
        LOGGER.info("Entering method variancePage");
        if (state.pendingClose == null) {
            LOGGER.info("Exiting method variancePage");
            return Response.seeOther(URI.create("/session")).build();
        }
        String message = "supervisor".equals(error) ? "AUTORISATION SUPERVISEUR REFUSÉE" : null;
        LOGGER.info("Exiting method variancePage");
        return Response.ok(varianceAlert
                .data("state", state)
                .data("pending", state.pendingClose)
                .data("threshold", posSettingsService.varianceThreshold().toPlainString())
                .data("error", message), MediaType.TEXT_HTML).build();
    }

    /**
     * Closes the session on a supervisor-validated over-threshold discrepancy
     * (EXP-82): the validation is checked and journaled, the close carries the
     * supervisor's badge as the nominative trace.
     *
     * @param supervisor the supervisor's identifier
     * @param supervisorPassword the supervisor's password
     * @return a redirect to the lock page on success, or back to the arbitration
     *         page with a refusal
     */
    @POST
    @Path("/action/session/close-endorsed")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @DrawerMayBeOpen
    public Response closeEndorsed(@FormParam("supervisor") String supervisor,
                                  @FormParam("supervisorPassword") String supervisorPassword) {
        LOGGER.info("Entering method closeEndorsed");
        if (state.pendingClose == null) {
            LOGGER.info("Exiting method closeEndorsed");
            return Response.seeOther(URI.create("/session")).build();
        }
        if (!endorsementService.authorize(supervisor, supervisorPassword, "SESSION_VARIANCE")) {
            LOGGER.info("Exiting method closeEndorsed");
            return Response.seeOther(URI.create("/session/variance?error=supervisor")).build();
        }
        PosState.PendingClose pending = state.pendingClose;
        CashSessionService.SessionReport report = cashSessionService.closeSession(
                state.auth.operatorId, pending.counted, pending.withdrawn, pending.detail,
                false, supervisor, pending.paperDeclaration, false);
        state.pendingClose = null;
        if (report == null) {
            LOGGER.info("Exiting method closeEndorsed");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        ticketPrinterService.printSessionReport(report);
        state.touch();
        LOGGER.info("Exiting method closeEndorsed");
        return Response.seeOther(URI.create("/lock")).build();
    }

    /**
     * Prints the standalone settlement voucher (EXP-80): a control document
     * listing the session's settlements per method, autonomous from the
     * withdrawal ticket.
     *
     * @return a redirect to the session page carrying the outcome
     */
    @GET
    @Path("/action/session/settlement-voucher")
    public Response printSettlementVoucher() {
        LOGGER.info("Entering method printSettlementVoucher");
        CashSession current = cashSessionService.getOpenSession();
        if (current == null) {
            LOGGER.info("Exiting method printSettlementVoucher");
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        CashSessionService.SessionReport report = cashSessionService.buildReport(current);
        ticketPrinterService.printSettlementVoucher(report, state.getOperatorName());
        state.touch();
        LOGGER.info("Exiting method printSettlementVoucher");
        return Response.seeOther(URI.create("/session")).build();
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or invalid
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
