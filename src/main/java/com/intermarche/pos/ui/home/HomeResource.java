package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * JAX-RS resource driving the home screen: drawer-error handling, main pages,
 * navigation, line selection/cancellation, price-modification modal and item
 * addition actions.
 * <p>
 * Phase 0: monetary form values are parsed straight into {@link BigDecimal}.
 * <p>
 * This resource also owns the REACTIVE BACKBONE of the register: the
 * versioned fragment poll. Every mutation ends in {@code state.touch()}
 * (the invalidation signal), and {@code /ticket-fragment?v=} answers 204
 * when the client's version is current or the fresh fragment plus the new
 * version otherwise — the main screen, the payment page and the customer
 * display all live on this one contract, which is why a forgotten
 * {@code touch()} shows up as "the screen does not react" and nothing else.
 * The message zone rendered by the fragment is shared by errors AND
 * confirmations (SUPERVISEUR PRÉVENU rides the same channel as the
 * refusals) — a naming wart worth knowing, not a bug.
 */
@Path("/")
@DrawerMustBeClosed
public class HomeResource {

    @Inject Template main;
    @Inject Template lock;
    @Inject Template supervisor;
    @Inject Template ticket;

    @Inject @Location("drawer-error") Template drawerError;

    @Inject HomeService homeService;
    @Inject TicketService ticketService;
    @Inject HardwareService hardwareService;
    @Inject PosState state;

    // --- Drawer error handling ---

    /**
     * Shows the drawer-open error page.
     *
     * @return the drawer-error page
     */
    @GET
    @Path("/drawer-error")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance drawerErrorPage() {
        return drawerError.data("state", state);
    }

    /**
     * Resumes navigation after the drawer has been closed (manual fallback).
     *
     * @return a redirect to the stored return URL, or the home page
     */
    @GET
    @Path("/action/resume-after-drawer")
    @DrawerMayBeOpen
    public Response resumeAfterDrawer() {
        String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
        state.returnUrl = null;
        state.touch();
        return Response.seeOther(URI.create(target)).build();
    }

    /**
     * Polling endpoint reporting the drawer status and the redirect target
     * once it is closed.
     *
     * @return a JSON map with the drawer state and redirect URL
     */
    @GET
    @Path("/api/drawer-status")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> checkDrawerStatus() {
        if (!hardwareService.isDrawerOpen()) {
            String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
            state.returnUrl = null;
            state.touch();
            return Map.of("open", false, "redirect", target);
        }
        return Map.of("open", true, "redirect", "");
    }

    // --- Main pages ---

    /**
     * Shows the home page, or the lock page when no operator is logged in.
     *
     * @return the home or lock page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        if (state.isLocked()) return lock.data("state", state);
        return main.data("state", state);
    }

    /**
     * Polling endpoint returning the ticket fragment when the state version changed.
     *
     * @param clientVersion the version known by the client, or null
     * @return a JSON map with the change flag and, when changed, the fragment data
     */
    @GET
    @Path("/ticket-fragment")
    @Produces(MediaType.APPLICATION_JSON)
    @DrawerMayBeOpen
    public Map<String, Object> getTicketFragment(@QueryParam("v") Long clientVersion) {
        Map<String, Object> result = new HashMap<>();
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        result.put("html", ticket.data("state", state).render());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("amount", state.ticket.getTotalAmount());
        result.put("fidelityActive", state.fidelity.active);
        return result;
    }

    /**
     * Shows the supervisor-call page.
     *
     * @return the supervisor or lock page
     */
    @GET
    @Path("/supervisor")
    @DrawerMayBeOpen
    public TemplateInstance supervisorPage() {
        if (state.isLocked()) return lock.data("state", state);
        return supervisor.data("state", state);
    }

    /**
     * Sends a supervisor call with the chosen reason and returns to the home
     * page (the outcome is shown in the message zone).
     *
     * @param reason the reason chosen on the supervisor page
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/supervisor/{reason}")
    public Response callSupervisor(@PathParam("reason") String reason) {
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        homeService.callSupervisor(reason.toUpperCase().replace('-', ' '));
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Requests the endorsed training-mode toggle.
     *
     * @return a redirect to the home page (the endorsement modal opens)
     */
    @GET
    @Path("/action/training")
    public Response toggleTraining() {
        if (state.isLocked()) return Response.seeOther(URI.create("/lock")).build();
        homeService.requestTrainingToggle();
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Menu navigation ---

    /**
     * Shows the secondary menu.
     *
     * @return the home page
     */
    @GET
    @Path("/action/menu/secondary")
    public TemplateInstance showSecondaryMenu() {
        homeService.toggleSecondaryMenu(true);
        return home();
    }

    /**
     * Shows the main menu.
     *
     * @return the home page
     */
    @GET
    @Path("/action/menu/main")
    public TemplateInstance showMainMenu() {
        homeService.toggleSecondaryMenu(false);
        return home();
    }

    // --- Ticket navigation ---

    /**
     * Moves the ticket display to the previous page.
     *
     * @return the home page
     */
    @GET
    @Path("/action/ticket/prev")
    public TemplateInstance ticketPrev() {
        state.prevPage();
        return home();
    }

    /**
     * Moves the ticket display to the next page.
     *
     * @return the home page
     */
    @GET
    @Path("/action/ticket/next")
    public TemplateInstance ticketNext() {
        state.nextPage();
        return home();
    }

    // --- Selection & cancellation ---

    /**
     * Toggles the selection of a ticket line.
     *
     * @param index the index of the line in the full ticket
     * @return the home or lock page
     */
    @GET
    @Path("/action/select/{index}")
    public TemplateInstance selectLine(@PathParam("index") int index) {
        if (state.isLocked()) return lock.data("state", state);
        homeService.selectLine(index);
        return home();
    }

    /**
     * Cancels the targeted line (directly or via endorsement).
     *
     * @return the home or lock page
     */
    @GET
    @Path("/action/cancelLine")
    public TemplateInstance cancelLine() {
        if (state.isLocked()) return lock.data("state", state);
        homeService.cancelLine();
        return home();
    }

    // --- Price-modification modal ---

    /**
     * Opens the price-modification modal for the targeted line.
     *
     * @param type the modification type (remise, discount, force_price)
     * @return the home or lock page
     */
    @GET
    @Path("/action/price-mod/{type}")
    public TemplateInstance openPriceMod(@PathParam("type") String type) {
        if (state.isLocked()) return lock.data("state", state);
        homeService.openPriceMod(type);
        return home();
    }

    /**
     * Closes the price-modification modal without applying anything.
     *
     * @return the home page
     */
    @GET
    @Path("/action/price-mod/cancel")
    public TemplateInstance cancelPriceMod() {
        homeService.cancelPriceMod();
        return home();
    }

    /**
     * Submits the price-modification value typed in the modal.
     *
     * @param type the modification type (REMISE, DISCOUNT, FORCE_PRICE)
     * @param uid the uid of the targeted ticket line
     * @param rawValue the raw typed value (French comma tolerated)
     * @return the home or lock page
     */
    @POST
    @Path("/action/price-mod/submit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance submitPriceMod(
            @FormParam("type") String type,
            @FormParam("uid") String uid,
            @FormParam("rawValue") String rawValue) {

        if (state.isLocked()) return lock.data("state", state);

        BigDecimal value;
        try {
            if (rawValue == null || rawValue.isEmpty()) rawValue = "0";
            value = new BigDecimal(rawValue.replace(",", "."));
        } catch (NumberFormatException e) {
            state.ticket.setError("VALEUR INVALIDE");
            state.priceModState.clear();
            state.touch();
            return home();
        }

        homeService.submitPriceMod(type, uid, value);
        return home();
    }

    // --- Other actions ---

    /**
     * Adds a weighed product by its PLU code.
     *
     * @param code the PLU code
     * @return the home or lock page
     */
    @GET
    @Path("/action/add/{code}")
    public TemplateInstance addPlu(@PathParam("code") String code) {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addItemByPlu(state, code);
        return home();
    }

    /**
     * Adds a known product by its EAN with a typed quantity.
     *
     * @param ean the EAN code
     * @param quantityStr the typed quantity (defaults to 1)
     * @return the home or lock page
     */
    @POST
    @Path("/action/manual-add-known")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance addManualKnown(@FormParam("ean") String ean, @FormParam("quantity") String quantityStr) {
        if (state.isLocked()) return lock.data("state", state);
        int qty = 1;
        try { if(quantityStr != null && !quantityStr.isEmpty()) qty = Integer.parseInt(quantityStr); } catch(Exception e) {}
        if(qty <= 0) qty = 1;
        ticketService.addItemByEan(state, ean, BigDecimal.valueOf(qty));
        return home();
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     * @return the home or lock page
     */
    @POST
    @Path("/action/manual-add-unknown")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance addManualUnknown(@FormParam("label") String label, @FormParam("price") String priceStr) {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addUnknownItem(state, label, priceStr);
        return home();
    }

    /**
     * Adds a deposit-return line to the ticket.
     *
     * @return the home or lock page
     */
    @GET
    @Path("/action/deposit")
    public TemplateInstance addDepositReturn() {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addDeposit(state);
        return home();
    }

    /**
     * Requests a manager endorsement to cancel the whole ticket.
     *
     * @return the home page
     */
    @GET
    @Path("/action/cancelTicket")
    public TemplateInstance cancelTicket() {
        homeService.cancelTicket();
        return home();
    }

    /**
     * Reprints the last closed ticket.
     *
     * @return the home page
     */
    @GET
    @Path("/action/print-last")
    public TemplateInstance printLast() {
        homeService.printLastTicket();
        return home();
    }
}
