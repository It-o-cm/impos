package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * JAX-RS resource driving the refund screens: ticket search, line quantity
 * edition and refund-method choice.
 * <p>
 * Phase 3 lot 4: the four method buttons request a manager endorsement
 * carrying the method; the refund itself is executed by the endorsement
 * dispatch on grant (the previously unguarded direct execution routes and
 * the dead validate route are gone).
 * <p>
 * The action string REFUND_&lt;METHOD&gt;_&lt;ticketId&gt; carries the
 * ticket id ON PURPOSE: the endorsement dispatch re-resolves the ticket
 * from the string rather than trusting the screen state, so the endorsed
 * gesture and the staged screen cannot drift apart between the request and
 * the manager's PIN.
 */
@Path("/return")
public class RefundResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RefundResource.class);

    @Inject
    PosState state;
    @Inject RefundService refundService;
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;
    @Inject @Location("return-search") Template returnSearchPage;
    @Inject @Location("return-detail") Template returnDetailPage;

    /**
     * Shows the refund search page, or the detail page when a ticket is
     * already selected.
     *
     * @return the appropriate refund page
     */
    @GET
    public TemplateInstance showSearchPage() {
        LOGGER.info("Entering method showSearchPage");
        if (state.refund.isTicketSelected()) { LOGGER.info("Exiting method showSearchPage"); return detail(); }
        LOGGER.info("Exiting method showSearchPage");
        return returnSearchPage.data("state", state);
    }

    /**
     * Runs the ticket search with the typed pattern.
     *
     * @param rawValue the typed number fragment
     * @return a 303 redirect to the refund screen (PRG pattern, so a browser
     *         reload never replays the POST); the GET re-renders the search
     *         results from the refund state
     */
    @POST
    @Path("/search")
    public Response doSearch(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method doSearch with rawValue: " + rawValue);
        // A new search supersedes any stale selection (e.g. a refused refund
        // left its detail open): the PRG GET must render the result list.
        state.refund.clearSelection();
        state.refund.searchPattern = rawValue != null ? rawValue.trim() : "";
        refundService.searchTickets(state);
        LOGGER.info("Exiting method doSearch");
        return redirectReturn();
    }

    /**
     * Selects a ticket to refund.
     *
     * @param id the database id of the ticket
     * @return the detail page
     */
    @GET
    @Path("/select/{id}")
    public TemplateInstance selectTicket(@PathParam("id") Long id) {
        LOGGER.info("Entering method selectTicket with id: " + id);
        refundService.selectTicket(state, id);
        LOGGER.info("Exiting method selectTicket");
        return detail();
    }

    /**
     * Toggles the selection of a line for direct quantity typing.
     *
     * @param id the database id of the line
     * @return the detail page
     */
    @GET
    @Path("/select-line/{id}")
    public TemplateInstance selectLine(@PathParam("id") Long id) {
        LOGGER.info("Entering method selectLine with id: " + id);
        refundService.selectLine(state, id);
        LOGGER.info("Exiting method selectLine");
        return detail();
    }

    /**
     * Switches the input to global-amount edition.
     *
     * @return the detail page
     */
    @GET
    @Path("/edit-amount")
    public TemplateInstance editAmount() {
        LOGGER.info("Entering method editAmount");
        refundService.startAmountEdit(state);
        LOGGER.info("Exiting method editAmount");
        return detail();
    }

    /**
     * Applies a typed refund quantity on a line.
     *
     * @param lineId the database id of the line
     * @param rawValue the typed quantity
     * @return a 303 redirect to the refund screen (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/submit-line")
    public Response submitLine(@FormParam("lineId") Long lineId, @FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method submitLine with lineId: " + lineId + ", rawValue: " + rawValue);
        refundService.submitLineQuantity(state, lineId, rawValue);
        LOGGER.info("Exiting method submitLine");
        return redirectReturn();
    }

    /**
     * Applies a typed global refund amount.
     *
     * @param rawValue the typed amount
     * @return a 303 redirect to the refund screen (PRG pattern, so a browser
     *         reload never replays the POST)
     */
    @POST
    @Path("/submit-amount")
    public Response submitAmount(@FormParam("rawValue") String rawValue) {
        LOGGER.info("Entering method submitAmount with rawValue: " + rawValue);
        refundService.submitManualAmount(state, rawValue);
        LOGGER.info("Exiting method submitAmount");
        return redirectReturn();
    }

    /**
     * Increments the refund quantity of a line.
     *
     * @param lineId the database id of the line
     * @return the detail page
     */
    @GET
    @Path("/line/{id}/add")
    public TemplateInstance addQty(@PathParam("id") Long lineId) {
        LOGGER.info("Entering method addQty with lineId: " + lineId);
        refundService.incrementQty(state, lineId);
        LOGGER.info("Exiting method addQty");
        return detail();
    }

    /**
     * Decrements the refund quantity of a line.
     *
     * @param lineId the database id of the line
     * @return the detail page
     */
    @GET
    @Path("/line/{id}/sub")
    public TemplateInstance subQty(@PathParam("id") Long lineId) {
        LOGGER.info("Entering method subQty with lineId: " + lineId);
        refundService.decrementQty(state, lineId);
        LOGGER.info("Exiting method subQty");
        return detail();
    }

    /**
     * Changes the detail page.
     *
     * @param page the target page index
     * @return the detail page
     */
    @GET
    @Path("/page/{p}")
    public TemplateInstance changePage(@PathParam("p") int page) {
        LOGGER.info("Entering method changePage with page: " + page);
        state.refund.detailPage = page;
        LOGGER.info("Exiting method changePage");
        return detail();
    }

    /**
     * Requests an endorsed cash refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/cash")
    public TemplateInstance payCash() {
        LOGGER.info("Entering method payCash");
        refundService.requestRefund(state, Refund.RefundMethod.CASH);
        LOGGER.info("Exiting method payCash");
        return detail();
    }

    /**
     * Requests an endorsed card refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/card")
    public TemplateInstance payCard() {
        LOGGER.info("Entering method payCard");
        refundService.requestRefund(state, Refund.RefundMethod.CARD);
        LOGGER.info("Exiting method payCard");
        return detail();
    }

    /**
     * Requests an endorsed store-voucher refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/voucher")
    public TemplateInstance payVoucher() {
        LOGGER.info("Entering method payVoucher");
        refundService.requestRefund(state, Refund.RefundMethod.VOUCHER);
        LOGGER.info("Exiting method payVoucher");
        return detail();
    }

    /**
     * Requests an endorsed loyalty refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/loyalty")
    public TemplateInstance payLoyalty() {
        LOGGER.info("Entering method payLoyalty");
        refundService.requestRefund(state, Refund.RefundMethod.LOYALTY);
        LOGGER.info("Exiting method payLoyalty");
        return detail();
    }

    /**
     * Selects the in-account customer a credit refund will lower the balance of
     * (LC-05-06-05); the service resolves the account by number.
     *
     * @param number the account number the cashier scanned or typed
     * @return the detail page, now naming the selected customer
     */
    @GET
    @Path("/credit-account")
    public TemplateInstance creditAccount(@QueryParam("number") String number) {
        LOGGER.info("Entering method creditAccount with number: " + number);
        refundService.selectCreditAccount(state, number);
        LOGGER.info("Exiting method creditAccount");
        return detail();
    }

    /**
     * Requests an endorsed credit refund on the selected in-account customer
     * (LC-05-06-05).
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/credit")
    public TemplateInstance payCredit() {
        LOGGER.info("Entering method payCredit");
        refundService.requestRefund(state, Refund.RefundMethod.CREDIT);
        LOGGER.info("Exiting method payCredit");
        return detail();
    }
    /**
     * Records the return reason chosen by the cashier (LC-05-05-01). The
     * service validates it against the administered list; an off-list or blank
     * value clears the reason.
     *
     * @param reason the reason the cashier tapped (query param, spaces and
     *        accents tolerated)
     * @return the detail page, now highlighting the chosen reason
     */
    @GET
    @Path("/reason")
    public TemplateInstance reason(@QueryParam("reason") String reason) {
        LOGGER.info("Entering method reason with reason: " + reason);
        refundService.selectReason(state, reason);
        LOGGER.info("Exiting method reason");
        return detail();
    }

    /**
     * Renders the return detail page, handing it the refund state and the
     * administered return reasons (LC-05-05-01) so the reason chooser can list
     * them and mark the chosen one.
     *
     * @return the detail page view
     */
    private TemplateInstance detail() {
        return returnDetailPage.data("state", state)
                .data("reasons", posSettingsService.returnReasons());
    }

    /**
     * Builds the 303 redirect to the refund screen used by every POST action
     * (PRG pattern); GET /return re-renders the search or detail page from
     * the refund state.
     *
     * @return a 303 See Other response targeting "/return"
     */
    private Response redirectReturn() {
        return Response.seeOther(URI.create("/return")).build();
    }
}