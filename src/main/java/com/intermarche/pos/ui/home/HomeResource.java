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
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

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

    // --- Gestion Erreur Tiroir ---

    @GET
    @Path("/drawer-error")
    @Produces(MediaType.TEXT_HTML)
    @DrawerMayBeOpen
    public TemplateInstance drawerErrorPage() {
        return drawerError.data("state", state);
    }

    @GET
    @Path("/action/resume-after-drawer")
    @DrawerMayBeOpen
    public Response resumeAfterDrawer() {
        String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
        state.returnUrl = null;
        state.touch();
        return Response.seeOther(URI.create(target)).build();
    }

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

    // --- Pages Principales ---

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        if (state.isLocked()) return lock.data("state", state);
        return main.data("state", state);
    }

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

    @GET
    @Path("/supervisor")
    @DrawerMayBeOpen
    public TemplateInstance supervisorPage() {
        if (state.isLocked()) return lock.data("state", state);
        return supervisor.data("state", state);
    }

    // --- Navigation Menus ---

    @GET
    @Path("/action/menu/secondary")
    public TemplateInstance showSecondaryMenu() {
        homeService.toggleSecondaryMenu(true);
        return home();
    }

    @GET
    @Path("/action/menu/main")
    public TemplateInstance showMainMenu() {
        homeService.toggleSecondaryMenu(false);
        return home();
    }

    // --- Navigation Ticket ---

    @GET
    @Path("/action/ticket/prev")
    public TemplateInstance ticketPrev() {
        state.prevPage();
        return home();
    }

    @GET
    @Path("/action/ticket/next")
    public TemplateInstance ticketNext() {
        state.nextPage();
        return home();
    }

    // --- Sélection & Annulation ---

    @GET
    @Path("/action/select/{index}")
    public TemplateInstance selectLine(@PathParam("index") int index) {
        if (state.isLocked()) return lock.data("state", state);
        homeService.selectLine(index);
        return home();
    }

    @GET
    @Path("/action/cancelLine")
    public TemplateInstance cancelLine() {
        if (state.isLocked()) return lock.data("state", state);
        homeService.cancelLine();
        return home();
    }

    // --- Gestion Modale Prix ---

    @GET
    @Path("/action/price-mod/{type}")
    public TemplateInstance openPriceMod(@PathParam("type") String type) {
        if (state.isLocked()) return lock.data("state", state);
        homeService.openPriceMod(type);
        return home();
    }

    @GET
    @Path("/action/price-mod/cancel")
    public TemplateInstance cancelPriceMod() {
        homeService.cancelPriceMod();
        return home();
    }

    @POST
    @Path("/action/price-mod/submit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance submitPriceMod(
            @FormParam("type") String type,
            @FormParam("uid") String uid,
            @FormParam("rawValue") String rawValue) {

        if (state.isLocked()) return lock.data("state", state);

        double value = 0.0;
        try {
            if (rawValue == null || rawValue.isEmpty()) rawValue = "0";
            value = Double.parseDouble(rawValue.replace(",", "."));
        } catch (NumberFormatException e) {
            state.ticket.setError("VALEUR INVALIDE");
            state.priceModState.clear();
            state.touch();
            return home();
        }

        homeService.submitPriceMod(type, uid, value);
        return home();
    }

    // --- Autres Actions ---

    @GET
    @Path("/action/add/{code}")
    public TemplateInstance addPlu(@PathParam("code") String code) {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addItemByPlu(state, code);
        return home();
    }

    @POST
    @Path("/action/manual-add-known")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance addManualKnown(@FormParam("ean") String ean, @FormParam("quantity") String quantityStr) {
        if (state.isLocked()) return lock.data("state", state);
        int qty = 1;
        try { if(quantityStr != null && !quantityStr.isEmpty()) qty = Integer.parseInt(quantityStr); } catch(Exception e) {}
        if(qty <= 0) qty = 1;
        ticketService.addItemByEan(state, ean, qty);
        return home();
    }

    @POST
    @Path("/action/manual-add-unknown")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance addManualUnknown(@FormParam("label") String label, @FormParam("price") String priceStr) {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addUnknownItem(state, label, priceStr);
        return home();
    }

    @GET
    @Path("/action/deposit")
    public TemplateInstance addDepositReturn() {
        if (state.isLocked()) return lock.data("state", state);
        ticketService.addDeposit(state);
        return home();
    }

    @GET
    @Path("/action/cancelTicket")
    public TemplateInstance cancelTicket() {
        homeService.cancelTicket();
        return home();
    }

    @GET
    @Path("/action/cancel")
    public TemplateInstance cancel() {
        return cancelTicket();
    }

    @GET
    @Path("/action/print-last")
    public TemplateInstance printLast() {
        homeService.printLastTicket();
        return home();
    }
}