package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PriceModType;

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
import org.jboss.logging.Logger;

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

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(HomeResource.class);

    @Inject Template main;
    @Inject Template supervisor;
    @Inject Template ticket;

    @Inject @Location("drawer-error") Template drawerError;

    @Inject HomeService homeService;
    @Inject TicketService ticketService;
    @Inject HardwareService hardwareService;
    @Inject PosState state;
    /** The forced monetics degraded mode (LC-07-08-03/04). */
    @Inject com.intermarche.pos.ui.payment.MoneticsDegradedService moneticsDegradedService;

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
        LOGGER.info("Entering method drawerErrorPage");
        LOGGER.info("Exiting method drawerErrorPage");
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
        LOGGER.info("Entering method resumeAfterDrawer");
        String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
        state.returnUrl = null;
        state.touch();
        LOGGER.info("Exiting method resumeAfterDrawer");
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
        LOGGER.info("Entering method checkDrawerStatus");
        if (!hardwareService.isDrawerOpen()) {
            String target = (state.returnUrl != null && !state.returnUrl.isEmpty()) ? state.returnUrl : "/";
            state.returnUrl = null;
            state.touch();
            LOGGER.info("Exiting method checkDrawerStatus");
            return Map.of("open", false, "redirect", target);
        }
        LOGGER.info("Exiting method checkDrawerStatus");
        return Map.of("open", true, "redirect", "");
    }

    // --- Main pages ---

    /**
     * Shows the home page.
     *
     * @return the home page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        LOGGER.info("Entering method home");
        LOGGER.info("Exiting method home");
        return main.data("state", state)
                .data("restrictedTenders", homeService.restrictedTenderRows());
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
        LOGGER.info("Entering method getTicketFragment with clientVersion: " + clientVersion);
        Map<String, Object> result = new HashMap<>();
        // The lock state rides on EVERY answer, version match included: the
        // poll is how an already-open screen learns that the register locked
        // and must navigate to /lock (see LockCheckFilter).
        result.put("locked", state.isLocked());
        // Payability rides along for the same reason: the ENCAISSER control
        // is server-rendered OUTSIDE the polled fragment, so a cart filled
        // through the scan bus (hardware scanner, simulator) would leave the
        // button in its render-time state until a navigation. The client
        // reloads once when this flag stops matching the rendered control.
        result.put("payable", state.ticket.getTotalAmount().signum() > 0);
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            LOGGER.info("Exiting method getTicketFragment");
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        // The eligible bases ride the FRAGMENT and not a field of this answer: they are
        // shown "en permanence jusqu'au paiement" inside the ticket zone, which the
        // poll replaces wholesale (LC-09-01-12/14/16/18).
        result.put("html", ticket.data("state", state)
                .data("restrictedTenders", homeService.restrictedTenderRows())
                .render());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("amount", state.ticket.getTotalAmount());
        // The age-check prompt is rendered SERVER-SIDE in main.html, so a scan
        // that parks a restricted article changes the state without changing
        // the page: the poll must know, or the cashier faces a screen that
        // simply stops responding to scans.
        result.put("ageCheckActive", state.ageCheck.active);
        // LC-02-03-01/03: same reason — the entry prompt is server-rendered and a
        // scan over the bus is what raises it.
        result.put("entryPromptActive", state.entryPrompt.active);
        result.put("fidelityActive", state.fidelity.active);
        // Attached-card summary (holder from the lookup, card, balance) —
        // shown permanently next to the fidelity icon, cleared with the card.
        result.put("fidelitySummary", state.fidelity.getDisplaySummary());
        result.put("fidelityEarn", state.fidelity.earnTotal != null
                && state.fidelity.earnTotal.signum() > 0
                ? String.format("AVANTAGE CARTE %.2f €", state.fidelity.earnTotal).replace('.', ',')
                : null);
        LOGGER.info("Exiting method getTicketFragment");
        return result;
    }

    /**
     * Shows the supervisor-call page.
     *
     * @return the supervisor page
     */
    @GET
    @Path("/supervisor")
    @DrawerMayBeOpen
    public TemplateInstance supervisorPage() {
        LOGGER.info("Entering method supervisorPage");
        LOGGER.info("Exiting method supervisorPage");
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
        LOGGER.info("Entering method callSupervisor with reason: " + reason);
        homeService.callSupervisor(reason.toUpperCase().replace('-', ' '));
        LOGGER.info("Exiting method callSupervisor");
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
        LOGGER.info("Entering method toggleTraining");
        homeService.requestTrainingToggle();
        LOGGER.info("Exiting method toggleTraining");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Forces the monetics into degraded mode, or releases it (LC-07-08-03/04).
     *
     * <p>ONE key for both directions, like the training toggle beside it: the
     * operator sees one state and one gesture, and the register knows which of the
     * two the gesture means.
     *
     * @param login the supervisor's login, when the shop requires an endorsement
     * @param password the supervisor's password
     * @return a redirect to the home page
     */
    @POST
    @Path("/action/monetics-degraded")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response toggleMoneticsDegraded(@FormParam("login") String login,
            @FormParam("password") String password) {
        LOGGER.info("Entering method toggleMoneticsDegraded with login: " + login + ", password: ***");
        if (state.isMoneticsDegradedForced()) {
            moneticsDegradedService.deactivate(state, login, password);
        } else {
            moneticsDegradedService.activate(state, login, password);
        }
        LOGGER.info("Exiting method toggleMoneticsDegraded");
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Menu navigation ---

    /**
     * Shows one of the five button menus.
     *
     * <p>One route for the five keys of the bottom bar. An unknown key lands on the
     * sale menu rather than on an error: a mistyped or stale link must never leave a
     * cashier in front of a blank grid.
     *
     * @param key the menu key carried by the bottom bar's link
     * @return the home page
     */
    @GET
    @Path("/action/menu/{key}")
    public TemplateInstance showMenu(@jakarta.ws.rs.PathParam("key") String key) {
        LOGGER.info("Entering method showMenu with key: " + key);
        homeService.selectMenu(com.intermarche.pos.ui.PosMenu.of(key));
        LOGGER.info("Exiting method showMenu");
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
        LOGGER.info("Entering method ticketPrev");
        state.prevPage();
        LOGGER.info("Exiting method ticketPrev");
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
        LOGGER.info("Entering method ticketNext");
        state.nextPage();
        LOGGER.info("Exiting method ticketNext");
        return home();
    }

    // --- Selection & cancellation ---

    /**
     * Toggles the selection of a ticket line.
     *
     * @param index the index of the line in the full ticket
     * @return the home page
     */
    @GET
    @Path("/action/select/{index}")
    public TemplateInstance selectLine(@PathParam("index") int index) {
        LOGGER.info("Entering method selectLine with index: " + index);
        homeService.selectLine(index);
        LOGGER.info("Exiting method selectLine");
        return home();
    }

    /**
     * Arms a quantity for the next article named ({@code LC-02-13-04/05/06}).
     *
     * @param rawValue the quantity typed on the modal's keypad
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/price-mod/arm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response armQuantity(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method armQuantity with rawValue: " + rawValue);
        homeService.armQuantity(parseAmount(rawValue));
        LOGGER.info("Exiting method armQuantity");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Repeats the registration of the last article ({@code LC-02-13-12}).
     *
     * @return the home page
     */
    @GET
    @Path("/action/repeat")
    public TemplateInstance repeatLastItem() {
        LOGGER.info("Entering method repeatLastItem");
        homeService.repeatLastItem();
        LOGGER.info("Exiting method repeatLastItem");
        return home();
    }

    /**
     * Takes the price or the decimal quantity the operator keyed on the entry prompt
     * ({@code LC-02-03-01/03}).
     *
     * @param rawValue the figure typed on the prompt's keypad
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/entry/confirm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response confirmEntry(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method confirmEntry with rawValue: " + rawValue);
        ticketService.confirmEntry(state, parseAmount(rawValue));
        LOGGER.info("Exiting method confirmEntry");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Gives up the suspended add: nothing is registered.
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/entry/cancel")
    public Response cancelEntry() {
        LOGGER.info("Entering method cancelEntry");
        ticketService.cancelEntry(state);
        LOGGER.info("Exiting method cancelEntry");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Reads a figure typed on an on-screen keypad, which sends it as plain digits.
     *
     * @param rawValue the typed value, possibly null or unreadable
     * @return the figure, or null when nothing readable was typed
     */
    private java.math.BigDecimal parseAmount(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(rawValue.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            // A keypad cannot produce this; a hand-made request can. It is a refusal,
            // not an incident.
            return null;
        }
    }

    /**
     * Marks the selected line "à enlever", or arms the next article for it
     * ({@code LC-02-08-01/02/03}).
     *
     * @return the home page
     */
    @GET
    @Path("/action/collect")
    public TemplateInstance toggleCollect() {
        LOGGER.info("Entering method toggleCollect");
        homeService.toggleCollect();
        LOGGER.info("Exiting method toggleCollect");
        return home();
    }

    /**
     * Cancels the targeted line (directly or via endorsement).
     *
     * @return the home page
     */
    @GET
    @Path("/action/cancelLine")
    public TemplateInstance cancelLine() {
        LOGGER.info("Entering method cancelLine");
        homeService.cancelLine();
        LOGGER.info("Exiting method cancelLine");
        return home();
    }

    // --- Price-modification modal ---

    /**
     * Opens the price-modification modal for the targeted line.
     *
     * @param type the modification type (remise, discount, force_price)
     * @return the home page
     */
    @GET
    @Path("/action/price-mod/{type}")
    public TemplateInstance openPriceMod(@PathParam("type") String type) {
        LOGGER.info("Entering method openPriceMod with type: " + type);
        homeService.openPriceMod(type);
        LOGGER.info("Exiting method openPriceMod");
        return home();
    }

    /**
     * Closes the price-modification modal without applying anything.
     *
     * @return the home page
     */
    /**
     * Confirms the pending age check (ID verified): the parked add replays
     * and the sale resumes (phase: age control).
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/age-check/confirm")
    public Response ageCheckConfirm() {
        LOGGER.info("Entering method ageCheckConfirm");
        ticketService.confirmAgeCheck(state);
        LOGGER.info("Exiting method ageCheckConfirm");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Refuses the sale of the age-restricted product: nothing is added and
     * the refusal is journalized (phase: age control).
     *
     * @return a redirect to the sale screen
     */
    @GET
    @Path("/action/age-check/refuse")
    public Response ageCheckRefuse() {
        LOGGER.info("Entering method ageCheckRefuse");
        ticketService.refuseAgeCheck(state);
        LOGGER.info("Exiting method ageCheckRefuse");
        return Response.seeOther(URI.create("/")).build();
    }

    @GET
    @Path("/action/price-mod/cancel")
    public TemplateInstance cancelPriceMod() {
        LOGGER.info("Entering method cancelPriceMod");
        homeService.cancelPriceMod();
        LOGGER.info("Exiting method cancelPriceMod");
        return home();
    }

    /**
     * Submits the price-modification value typed in the modal.
     *
     * @param type the name of the modification mode
     * @param uid the uid of the targeted ticket line
     * @param rawValue the raw typed value (French comma tolerated)
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/price-mod/submit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response submitPriceMod(
            @FormParam("type") String type,
            @FormParam("uid") String uid,
            @FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method submitPriceMod with type: " + type + ", uid: " + uid + ", rawValue: " + rawValue);

        BigDecimal value;
        try {
            if (rawValue == null || rawValue.isEmpty()) rawValue = "0";
            value = new BigDecimal(rawValue.replace(",", "."));
        } catch (NumberFormatException e) {
            state.ticket.setError("VALEUR INVALIDE");
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method submitPriceMod");
            return Response.seeOther(URI.create("/")).build();
        }

        // The posted word is the last place the mode is still a string. A stale page
        // or a forged post naming no mode applies nothing and says so, rather than
        // falling through the dispatch in silence.
        PriceModType mode = PriceModType.of(type);
        if (mode == null) {
            state.ticket.setError("MODIFICATION INCONNUE");
            state.priceModState.clear();
            state.touch();
            LOGGER.info("Exiting method submitPriceMod");
            return Response.seeOther(URI.create("/")).build();
        }
        homeService.submitPriceMod(mode, uid, value);
        LOGGER.info("Exiting method submitPriceMod");
        return Response.seeOther(URI.create("/")).build();
    }

    // --- Other actions ---

    /**
     * Adds a weighed product by its PLU code.
     *
     * @param code the PLU code
     * @return the home page
     */
    @GET
    @Path("/action/add/{code}")
    public TemplateInstance addPlu(@PathParam("code") String code) {
        LOGGER.info("Entering method addPlu with code: " + code);
        ticketService.addItemByPlu(state, code);
        LOGGER.info("Exiting method addPlu");
        return home();
    }

    /**
     * Adds a known product by its EAN with a typed quantity.
     *
     * @param ean the EAN code
     * @param quantityStr the typed quantity (defaults to 1)
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/manual-add-known")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addManualKnown(@FormParam("ean") String ean, @FormParam("quantity") String quantityStr) {
        LOGGER.info("Entering method addManualKnown with ean: " + ean + ", quantityStr: " + quantityStr);
        int qty = 1;
        try { if(quantityStr != null && !quantityStr.isEmpty()) qty = Integer.parseInt(quantityStr); } catch(Exception e) {}
        if(qty <= 0) qty = 1;
        ticketService.addItemByEan(state, ean, BigDecimal.valueOf(qty));
        LOGGER.info("Exiting method addManualKnown");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     * @return a 303 redirect to the home page (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/action/manual-add-unknown")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addManualUnknown(@FormParam("label") String label, @FormParam("price") String priceStr) {
        LOGGER.info("Entering method addManualUnknown with label: " + label + ", priceStr: " + priceStr);
        ticketService.addUnknownItem(state, label, priceStr);
        LOGGER.info("Exiting method addManualUnknown");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Adds a deposit-return line to the ticket.
     *
     * @return the home page
     */
    @GET
    @Path("/action/deposit")
    public TemplateInstance addDepositReturn() {
        LOGGER.info("Entering method addDepositReturn");
        ticketService.addDeposit(state);
        LOGGER.info("Exiting method addDepositReturn");
        return home();
    }

    /**
     * Sends the operator to the abandon screen, where the shop's rules are applied
     * before the ticket is given up ({@code LC-04-04-06} to {@code -12}).
     *
     * <p>It no longer requests the endorsement itself: the reason, the fate of a
     * settlement already taken and the printing are decided there, and a second road
     * to the same gesture would be a road around them.
     *
     * @return a redirect to the abandon screen
     */
    @GET
    @Path("/action/cancelTicket")
    public Response cancelTicket() {
        LOGGER.info("Entering method cancelTicket");
        LOGGER.info("Exiting method cancelTicket");
        return Response.seeOther(URI.create("/abandon")).build();
    }

    /**
     * Reprints the last closed ticket.
     *
     * <p>Answers with a redirect, like every other function of the TICKET menu:
     * these three used to re-render the sale screen under their own action URL,
     * so the address bar stayed on /action/print-last and a refresh printed the
     * ticket a second time.
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last")
    public Response printLast() {
        LOGGER.info("Entering method printLast");
        homeService.printLastTicket();
        LOGGER.info("Exiting method printLast");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prints the identification barcode of the last closed ticket, alone
     * (LC-08-01-04).
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last-barcode")
    public Response printLastBarcode() {
        LOGGER.info("Entering method printLastBarcode");
        homeService.printLastTicketBarcode();
        LOGGER.info("Exiting method printLastBarcode");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prints a duplicate of the last closed ticket's card receipt (LC-08-05-09).
     *
     * @return a redirect to the home page
     */
    @GET
    @Path("/action/print-last-card")
    public Response printLastCard() {
        LOGGER.info("Entering method printLastCard");
        homeService.printLastCardReceiptDuplicate();
        LOGGER.info("Exiting method printLastCard");
        return Response.seeOther(URI.create("/")).build();
    }
}
