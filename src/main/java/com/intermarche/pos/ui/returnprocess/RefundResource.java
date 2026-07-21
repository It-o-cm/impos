package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

@Path("/return")
public class RefundResource {

    @Inject PosState state;
    @Inject RefundService refundService;

    @Inject Template lock;
    @Inject @Location("return-search") Template returnSearchPage;
    @Inject @Location("return-detail") Template returnDetailPage;

    @GET
    @Produces("text/html")
    public TemplateInstance showSearchPage() {
        if (state.isLocked()) return lock.data("state", state);
        state.refund.clear();
        return returnSearchPage.data("state", state);
    }

    @POST
    @Path("/search")
    public TemplateInstance doSearch(@FormParam("rawValue") String rawValue) {
        state.refund.searchPattern = rawValue != null ? rawValue : "";
        refundService.searchTickets(state);
        return returnSearchPage.data("state", state);
    }

    @GET
    @Path("/select/{id}")
    public TemplateInstance selectTicket(@PathParam("id") Long id) {
        refundService.selectTicket(state, id);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/select-line/{id}")
    public TemplateInstance selectLine(@PathParam("id") Long id) {
        refundService.selectLine(state, id);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/edit-amount")
    public TemplateInstance editAmount() {
        refundService.startAmountEdit(state);
        return returnDetailPage.data("state", state);
    }

    @POST
    @Path("/submit-line")
    public TemplateInstance submitLine(@FormParam("lineId") Long lineId, @FormParam("rawValue") String rawValue) {
        refundService.submitLineQuantity(state, lineId, rawValue);
        return returnDetailPage.data("state", state);
    }

    @POST
    @Path("/submit-amount")
    public TemplateInstance submitAmount(@FormParam("rawValue") String rawValue) {
        refundService.submitManualAmount(state, rawValue);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/line/{id}/add")
    public TemplateInstance addQty(@PathParam("id") Long lineId) {
        refundService.incrementQty(state, lineId);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/line/{id}/sub")
    public TemplateInstance subQty(@PathParam("id") Long lineId) {
        refundService.decrementQty(state, lineId);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/page/{p}")
    public TemplateInstance changePage(@PathParam("p") int page) {
        state.refund.detailPage = page;
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/validate")
    public TemplateInstance validate() {
        refundService.validateRefund(state);
        return returnDetailPage.data("state", state);
    }

    @GET
    @Path("/pay/cash")
    public TemplateInstance payCash() {
        refundService.performRefundCreation(state);
        return returnSearchPage.data("state", state);
    }

    @GET
    @Path("/pay/card")
    public TemplateInstance payCard() {
        refundService.performRefundCreation(state);
        return returnSearchPage.data("state", state);
    }

    @GET
    @Path("/pay/voucher")
    public TemplateInstance payVoucher() {
        refundService.generateVoucher(state);
        return returnSearchPage.data("state", state);
    }

    @GET
    @Path("/pay/loyalty")
    public TemplateInstance payLoyalty() {
        refundService.addToLoyalty(state);
        return returnSearchPage.data("state", state);
    }
}