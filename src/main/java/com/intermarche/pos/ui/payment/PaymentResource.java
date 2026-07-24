package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.DrawerMayBeOpen;
import com.intermarche.pos.ui.DrawerMustBeClosed;
import com.intermarche.pos.ui.PosState;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import java.math.BigDecimal;
import java.net.URI;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS resource driving the payment screen: payment method actions, voucher
 * entry flow, payment history pagination, finalization and printing.
 * <p>
 * Phase 0: form amounts are parsed straight into {@link BigDecimal}.
 */
@Path("/")
@DrawerMustBeClosed
public class PaymentResource {

    @Inject Template pay;
    @Inject Template main;
    @Inject PaymentService paymentService;
    @Inject VoucherService voucherService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject PosState state;

    /**
     * Shows the payment page, creating the draft ticket on first entry.
     *
     * @return the payment page
     */
    @GET
    @Path("/pay")
    @DrawerMayBeOpen
    public TemplateInstance showPaymentPage() {
        paymentService.initPayment(state);
        state.payment.inputMode = null;
        state.payment.temporaryInput = "0,00";
        return pay.data("state", state)
                .data("couponTypes", CouponType.listActivePaymentTypes())
                .data("digitalPath", digitalPath());
    }

    /**
     * Builds the online digital-receipt path of the current draft, or null.
     *
     * @return the digital receipt path, or null when unavailable
     */
    private String digitalPath() {
        if (state.payment.ticketDbId == null) return null;
        Ticket ticket = Ticket.findById(state.payment.ticketDbId);
        if (ticket == null || ticket.digitalKey == null) return null;
        return "/t/" + ticket.id + "/" + ticket.digitalKey;
    }

    /**
     * Cancels the pending virtual-terminal card request from the register.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/card-cancel")
    public Response cancelPendingCard() {
        paymentService.cancelPendingCard(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Toggles the solidarity round-up line on the current ticket.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/donation")
    public Response toggleDonation() {
        paymentService.toggleDonationRoundup(state);
        return Response.seeOther(URI.create("/pay")).build();
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

    /**
     * Registers a card payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-card")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCardPayment(@FormParam("amount") String amountStr) {
        paymentService.processCard(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a cash payment from the tendered amount.
     *
     * @param givenStr the cash amount handed over by the customer
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-cash")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCashPayment(@FormParam("given") String givenStr) {
        paymentService.processCash(state, parseAmount(givenStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a meal-ticket payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-tr")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doTrPayment(@FormParam("amount") String amountStr) {
        paymentService.processTicketResto(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a fidelity payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-fid")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doFidelityPayment(@FormParam("amount") String amountStr) {
        paymentService.processFidelity(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a cheque payment.
     *
     * @param amountStr the amount typed by the cashier
     * @return a redirect back to the payment page
     */
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
        BigDecimal amount = parseAmount(amountStr);
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

    /**
     * Finalizes the transaction (closes the ticket) and returns to the main page.
     *
     * @return the main page
     */
    @GET
    @Path("/action/finish")
    public TemplateInstance validatePayment() {
        paymentService.finalizeTransaction(state);
        return main.data("state", state);
    }

    /**
     * Cancels the registered payments (in memory and on the draft) and
     * returns to the main page.
     *
     * @return the main page
     */
    @GET
    @Path("/action/cancel")
    public TemplateInstance cancelPayment() {
        paymentService.cancelPayments(state);
        return main.data("state", state);
    }

    /**
     * Prints the current ticket and returns to the appropriate page.
     *
     * @return the payment page while the transaction modal is shown, otherwise the main page
     */
    @POST
    @Path("/action/print")
    public TemplateInstance printTicket() {
        Long ticketId = state.payment.ticketDbId;
        if (state.trainingMode) {
            ticketPrinterService.printTrainingReceipt(state);
        } else if (ticketId != null) {
            try {
                ticketPrinterService.printTicket(ticketId);
            } catch (Exception e) {
                System.err.println("Erreur impression: " + e.getMessage());
            }
        }
        if (state.payment.transactionComplete) {
            return pay.data("state", state)
                    .data("couponTypes", CouponType.listActivePaymentTypes())
                    .data("digitalPath", digitalPath());
        }
        return main.data("state", state);
    }

    /**
     * Reprints the last closed ticket and returns to the home page.
     *
     * @return a redirect to the home page
     */
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
