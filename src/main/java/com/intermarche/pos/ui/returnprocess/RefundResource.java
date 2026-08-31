package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

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

    @Inject
    PosState state;
    @Inject RefundService refundService;
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
        if (state.refund.isTicketSelected()) return returnDetailPage.data("state", state);
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
        // A new search supersedes any stale selection (e.g. a refused refund
        // left its detail open): the PRG GET must render the result list.
        state.refund.clearSelection();
        state.refund.searchPattern = rawValue != null ? rawValue.trim() : "";
        refundService.searchTickets(state);
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
        refundService.selectTicket(state, id);
        return returnDetailPage.data("state", state);
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
        refundService.selectLine(state, id);
        return returnDetailPage.data("state", state);
    }

    /**
     * Switches the input to global-amount edition.
     *
     * @return the detail page
     */
    @GET
    @Path("/edit-amount")
    public TemplateInstance editAmount() {
        refundService.startAmountEdit(state);
        return returnDetailPage.data("state", state);
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
        refundService.submitLineQuantity(state, lineId, rawValue);
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
        refundService.submitManualAmount(state, rawValue);
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
        refundService.incrementQty(state, lineId);
        return returnDetailPage.data("state", state);
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
        refundService.decrementQty(state, lineId);
        return returnDetailPage.data("state", state);
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
        state.refund.detailPage = page;
        return returnDetailPage.data("state", state);
    }

    /**
     * Requests an endorsed cash refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/cash")
    public TemplateInstance payCash() {
        refundService.requestRefund(state, Refund.RefundMethod.CASH);
        return returnDetailPage.data("state", state);
    }

    /**
     * Requests an endorsed card refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/card")
    public TemplateInstance payCard() {
        refundService.requestRefund(state, Refund.RefundMethod.CARD);
        return returnDetailPage.data("state", state);
    }

    /**
     * Requests an endorsed store-voucher refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/voucher")
    public TemplateInstance payVoucher() {
        refundService.requestRefund(state, Refund.RefundMethod.VOUCHER);
        return returnDetailPage.data("state", state);
    }

    /**
     * Requests an endorsed loyalty refund.
     *
     * @return the detail page (the endorsement modal opens over it)
     */
    @GET
    @Path("/pay/loyalty")
    public TemplateInstance payLoyalty() {
        refundService.requestRefund(state, Refund.RefundMethod.LOYALTY);
        return returnDetailPage.data("state", state);
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