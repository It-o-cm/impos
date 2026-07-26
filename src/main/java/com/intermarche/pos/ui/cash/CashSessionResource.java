package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
 */
@Path("/")
public class CashSessionResource {

    @Inject @Location("session") Template session;
    @Inject Template lock;
    @Inject CashSessionService cashSessionService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject TechnicalEventService technicalEventService;
    @Inject HardwareService hardwareService;
    @Inject PosState state;

    /**
     * Shows the session page: current open session, or the opening form.
     *
     * @param error an optional error code from a redirect
     * @return the session page, or the lock page when no operator is logged in
     */
    @GET
    @Path("/session")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance sessionPage(@QueryParam("error") String error) {
        if (state.isLocked()) return lock.data("state", state).data("error", null);
        String message = null;
        if ("open-failed".equals(error)) {
            message = "OUVERTURE IMPOSSIBLE (SESSION DÉJÀ OUVERTE ?)";
        } else if ("no-session".equals(error)) {
            message = "AUCUNE SESSION OUVERTE";
        }
        return session
                .data("state", state)
                .data("current", cashSessionService.getOpenSession())
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
        if (state.trainingMode) {
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        BigDecimal openingFloat = parseAmount(floatStr);
        CashSession opened = cashSessionService.openSession(state.auth.operatorId, openingFloat);
        state.touch();
        if (opened == null) {
            return Response.seeOther(URI.create("/session?error=open-failed")).build();
        }
        return Response.seeOther(URI.create("/session")).build();
    }

    /**
     * Prints an X report (read-only snapshot) of the open session.
     *
     * @return a redirect to the session page
     */
    @GET
    @Path("/action/session/x-report")
    public Response printXReport() {
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        CashSession current = cashSessionService.getOpenSession();
        if (current == null) {
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        CashSessionService.SessionReport report = cashSessionService.buildReport(current);
        ticketPrinterService.printSessionReport(report);
        technicalEventService.log(TechnicalEvent.EventType.X_REPORT_PRINTED, current.sessionNumber);
        return Response.seeOther(URI.create("/session")).build();
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
        if (state.trainingMode) {
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        if (cashSessionService.getOpenSession() == null) {
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        hardwareService.openDrawer();
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
                                 @FormParam("detail") String detail) {
        if (state.trainingMode) {
            return Response.seeOther(URI.create("/session?error=INDISPONIBLE+EN+FORMATION")).build();
        }
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        CashSessionService.SessionReport report = cashSessionService.closeSession(
                state.auth.operatorId, parseAmount(countedStr), parseAmount(withdrawnStr), detail);
        if (report == null) {
            return Response.seeOther(URI.create("/session?error=no-session")).build();
        }
        ticketPrinterService.printSessionReport(report);
        state.touch();
        return Response.seeOther(URI.create("/lock")).build();
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
