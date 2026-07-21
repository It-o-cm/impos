package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.net.URI;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@DrawerMustBeClosed
public class PaymentResource {

    @Inject Template pay;
    @Inject Template main;
    @Inject PaymentService paymentService;
    @Inject VoucherService voucherService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject PosState state;

    @GET
    @Path("/pay")
    @DrawerMayBeOpen
    public TemplateInstance showPaymentPage() {
        paymentService.initPayment(state);
        state.payment.inputMode = null;
        state.payment.temporaryInput = "0,00";
        return pay.data("state", state)
                .data("couponTypes", CouponType.listActiveByPriority());
    }

    // Utilitaire de parsing
    private double parseAmount(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @POST
    @Path("/action/pay-card")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCardPayment(@FormParam("amount") String amountStr) {
        paymentService.processCard(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    @POST
    @Path("/action/pay-cash")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCashPayment(@FormParam("given") String givenStr) {
        paymentService.processCash(state, parseAmount(givenStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    @POST
    @Path("/action/pay-tr")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doTrPayment(@FormParam("amount") String amountStr) {
        paymentService.processTicketResto(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    @POST
    @Path("/action/pay-fid")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doFidelityPayment(@FormParam("amount") String amountStr) {
        paymentService.processFidelity(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    @POST
    @Path("/action/pay-cheque")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doChequePayment(@FormParam("amount") String amountStr) {
        paymentService.processCheque(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Opens the voucher panel so the cashier can choose a type.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-open")
    public Response openVoucherPanel() {
        state.payment.clearPendingVoucher();
        state.payment.voucherPanelOpen = true;
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Starts a manual voucher entry for the selected type.
     * <p>
     * For a type carrying a number, the cashier is asked to type the number next;
     * for a numberless type, the amount is requested directly.
     *
     * @param code the technical code of the selected coupon type
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectVoucherType(@FormParam("code") String code) {
        CouponType type = CouponType.find("code = ?1 and active = true", code).firstResult();
        state.payment.clearPendingVoucher();
        state.payment.voucherPanelOpen = true;
        if (type != null) {
            state.payment.pendingVoucherTypeCode = type.code;
            state.payment.pendingVoucherLabel = type.label;
            if (!type.hasNumber()) {
                state.payment.pendingVoucherNeedsAmount = true;
            }
        }
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Validates a manually typed voucher number against the selected type.
     * <p>
     * If the number does not match the type's pattern, an error is shown (likely a
     * typing mistake). If the amount is encoded, the payment is registered; otherwise
     * the amount is requested.
     *
     * @param number the voucher number typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-number")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateVoucherNumber(@FormParam("number") String number) {
        String code = state.payment.pendingVoucherTypeCode;
        CouponType type = (code != null)
                ? CouponType.find("code = ?1 and active = true", code).firstResult()
                : null;

        if (type == null) {
            state.payment.voucherError = "Type de bon inconnu";
            state.touch();
            return Response.seeOther(URI.create("/pay")).build();
        }

        if (number == null || !type.matches(number)) {
            state.payment.voucherError = "Numéro non reconnu — vérifiez la saisie";
            state.touch();
            return Response.seeOther(URI.create("/pay")).build();
        }

        state.payment.voucherError = null;
        state.payment.pendingVoucherNumber = number;

        if (type.requiresManualAmount()) {
            state.payment.pendingVoucherNeedsAmount = true;
            state.touch();
        } else {
            voucherService.applyEncodedVoucher(state, type, number);
            state.payment.clearPendingVoucher();
            state.touch();
        }
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers the pending voucher with the amount entered by the cashier.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-amount")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateVoucherAmount(@FormParam("amount") String amountStr) {
        String code = state.payment.pendingVoucherTypeCode;
        CouponType type = (code != null)
                ? CouponType.find("code = ?1 and active = true", code).firstResult()
                : null;
        double amount = parseAmount(amountStr);
        voucherService.applyManualVoucher(state, type, state.payment.pendingVoucherNumber, amount);
        state.payment.clearPendingVoucher();
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Cancels the in-progress voucher entry.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/voucher-cancel")
    public Response cancelVoucher() {
        state.payment.clearPendingVoucher();
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Moves the payment history to the previous page.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/payments/prev")
    @DrawerMayBeOpen
    public Response paymentsPrev() {
        state.payment.prevPage();
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Moves the payment history to the next page.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/payments/next")
    @DrawerMayBeOpen
    public Response paymentsNext() {
        state.payment.nextPage();
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    @GET
    @Path("/action/finish")
    public TemplateInstance validatePayment() {
        paymentService.finalizeTransaction(state);
        return main.data("state", state);
    }

    @GET
    @Path("/action/cancel")
    public TemplateInstance cancelPayment() {
        state.clearPayments();
        return main.data("state", state);
    }

    @POST
    @Path("/action/print")
    public TemplateInstance printTicket() {
        Long ticketId = state.payment.ticketDbId;
        if (ticketId != null) {
            try {
                ticketPrinterService.printTicket(ticketId);
            } catch (Exception e) {
                System.err.println("Erreur impression: " + e.getMessage());
            }
        }
        if (state.payment.transactionComplete) {
            return pay.data("state", state)
                    .data("couponTypes", CouponType.listActiveByPriority());
        }
        return main.data("state", state);
    }

    @GET
    @Path("/action/reprint-last")
    public Response reprintLastTicket() {
        if (state.lastClosedTicketId != null) {
            try {
                ticketPrinterService.printTicket(state.lastClosedTicketId);
            } catch (Exception e) {
                System.err.println("Erreur réimpression: " + e.getMessage());
            }
        }
        return Response.seeOther(URI.create("/")).build();
    }
}
