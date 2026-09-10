package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
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
 * <p>
 * The completion modal's two buttons are a CONTRACT with a deliberate
 * order: IMPRIMER prints the still-OPEN draft by id (the original print,
 * before any duplicata counting), TERMINER triggers the fiscal moment
 * (validation, chaining, number) and clears the screen — printing after
 * TERMINER goes through the reprint flow with its numbered duplicatas. In
 * training the print branch swaps to the in-memory training receipt, since
 * no draft exists to print.
 */
@Path("/")
@DrawerMustBeClosed
public class PaymentResource {

    @Inject Template pay;
    @Inject PaymentService paymentService;
    @Inject VoucherService voucherService;
    @Inject
    TicketPrinterService ticketPrinterService;
    @Inject PosState state;
    /** The back-office parameters read by the payment page (LC-07-07-09). */
    @Inject com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Ticket lifecycle service — used by the payment-phase ticket abandon. */
    @Inject com.intermarche.pos.ui.ticket.TicketService ticketService;
    /** Loyalty lease renewal on payment-screen refresh (imfid lot 2). */
    @Inject com.intermarche.pos.ui.fidelity.FidelityService fidelityService;
    /** The conditional-printing rule — drives the end-of-transaction choice (LC-08-03). */
    @Inject com.intermarche.pos.ui.hardware.PrintPolicy printPolicy;
    /** The customer-credit panel of the payment screen (LC-07-09). */
    @Inject CreditClientService creditClientService;
    /** The foreign-currency panel of the payment screen (LC-07-14). */
    @Inject ForeignCurrencyService foreignCurrencyService;
    /** The backup-monetics panel of the payment screen (LC-07-07-06/09). */
    @Inject BackupPaymentService backupPaymentService;

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
        // Opportunistic half-life renewal of the fidelity lease (spec §5.1).
        fidelityService.maybeRenewLease(state);
        state.payment.inputMode = null;
        state.payment.temporaryInput = "0,00";
        return pay.data("state", state)
                .data("couponTypes", CouponType.listActivePaymentTypes())
                .data("currencies", foreignCurrencyService.listCurrencies())
                .data("backupEndorsement", posSettingsService.backupManualEndorsement())
                .data("digitalPath", digitalPath())
                .data("printConditional", printPolicy.isConditionalEnabled())
                .data("printChoices",
                        java.util.List.of(com.intermarche.pos.ui.hardware.PrintChoice.values()));
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
     * Cancels the pending cheque reading from the register.
     *
     * @return a redirect back to the payment page
     */
    @GET
    @Path("/action/cheque-cancel")
    public Response cancelPendingCheque() {
        paymentService.cancelPendingCheque(state);
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

    // --------------------------------------------------
    // Backup monetics (LC-07-07-06/09)
    // --------------------------------------------------

    /**
     * Opens the backup-monetics panel and emits the request QR code
     * (LC-07-07-06/07).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-open")
    public Response openBackupPanel() {
        backupPaymentService.openPanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the backup-monetics panel, abandoning the pending request.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-cancel")
    public Response closeBackupPanel() {
        backupPaymentService.closePanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Validates the answer scanned off the mobile terminal (LC-07-07-08).
     *
     * @param payload the scanned text
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-scan")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response validateBackupScan(@FormParam("payload") String payload) {
        backupPaymentService.validateScanned(state, payload);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Switches the panel to keying the outcome in, for a till whose scanner cannot
     * read 2D codes (LC-07-07-09).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/backup-manual")
    public Response openBackupManualEntry() {
        state.payment.backupManualEntry = true;
        state.payment.backupError = null;
        state.touch();
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers the amount the operator keyed in (LC-07-07-09).
     *
     * @param amountStr the amount the mobile terminal accepted
     * @param login the supervisor's login, when one is required
     * @param password the supervisor's password
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-backup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doBackupPayment(@FormParam("amount") String amountStr,
            @FormParam("login") String login, @FormParam("password") String password) {
        backupPaymentService.validateManually(state, parseAmount(amountStr), login, password);
        return Response.seeOther(URI.create("/pay")).build();
    }

    // --------------------------------------------------
    // Foreign currency (LC-07-14)
    // --------------------------------------------------

    /**
     * Opens the foreign-currency panel over the payment screen.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-open")
    public Response openCurrencyPanel() {
        foreignCurrencyService.openPanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the foreign-currency panel without settling anything.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-cancel")
    public Response closeCurrencyPanel() {
        foreignCurrencyService.closePanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Selects the currency the customer is paying in, which reveals the rate and
     * the amount due in that currency (LC-07-14-04).
     *
     * @param code the ISO code of the selected currency
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/currency-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCurrency(@FormParam("code") String code) {
        foreignCurrencyService.selectCurrency(state, code);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Registers a settlement handed over in the selected currency (LC-07-14-03).
     *
     * @param amountStr the amount handed over, in that currency
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-currency")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCurrencyPayment(@FormParam("amount") String amountStr) {
        foreignCurrencyService.processCurrency(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    // --------------------------------------------------
    // Customer credit (LC-07-09)
    // --------------------------------------------------

    /**
     * Opens the customer-credit panel over the payment screen.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-open")
    public Response openCreditPanel() {
        creditClientService.openPanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Closes the customer-credit panel without settling anything.
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-cancel")
    public Response closeCreditPanel() {
        creditClientService.closePanel(state);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Names the account by its number (LC-07-09-02).
     *
     * @param number the account number typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-number")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCreditAccountByNumber(@FormParam("number") String number) {
        creditClientService.selectByNumber(state, number);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Looks accounts up by name (LC-07-09-07).
     *
     * @param search the name fragment typed by the cashier
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-search")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response searchCreditAccounts(@FormParam("search") String search) {
        creditClientService.searchByName(state, search);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Names the account picked from the search results (LC-07-09-08).
     *
     * @param customerId the database id of the picked account
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-select")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response selectCreditAccount(@FormParam("customerId") String customerId) {
        Long id = null;
        try {
            id = customerId == null ? null : Long.valueOf(customerId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        creditClientService.selectById(state, id);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Charges the named account (LC-07-09-01/03).
     *
     * @param amountStr the amount typed by the cashier, blank for the whole due
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/pay-credit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response doCreditPayment(@FormParam("amount") String amountStr) {
        creditClientService.processCredit(state, parseAmount(amountStr));
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Lets a supervisor allow the account's ceiling to be passed (LC-07-09-04).
     *
     * @param login the supervisor's login
     * @param password the supervisor's password
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorizeCreditOverLimit(@FormParam("login") String login,
            @FormParam("password") String password) {
        creditClientService.authorizeOverLimit(state, login, password);
        return Response.seeOther(URI.create("/pay")).build();
    }

    /**
     * Gives up on the settlement held back for authorization, keeping the account
     * named so a smaller amount can be typed (LC-07-09-04).
     *
     * @return a redirect back to the payment page
     */
    @POST
    @Path("/action/credit-authorize-cancel")
    public Response cancelCreditOverLimit() {
        creditClientService.cancelOverLimit(state);
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
     * Finalizes the transaction (closes the ticket) and redirects to the
     * main page.
     *
     * @return a 303 redirect to the main page — this GET mutates state, so
     *         it must never RENDER a page the browser then sits on: the sale
     *         screen reloads itself on a payability flip, and a reload of a
     *         rendered {@code /action/finish} would replay the fiscal close
     *         onto the NEXT freshly started draft (same replay class as the
     *         PRG-converted POSTs)
     */
    @GET
    @Path("/action/finish")
    // The drawer is OPEN at this exact moment on a cash sale, and closing the
    // sale is not "selling on": the guard must not block it, or the cashier
    // cannot finish and is sent back to the payment page once the drawer is
    // shut. The guard still stands on the home page this redirects to, so no
    // NEW sale starts with the drawer out.
    @DrawerMayBeOpen
    public Response validatePayment() {
        paymentService.finalizeTransaction(state);
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Cancels the registered payments (in memory and on the draft) and
     * redirects to the main page.
     *
     * @return a 303 redirect to the main page (same replay-safety rule as
     *         {@link #validatePayment()}: a mutating GET never renders the
     *         page it lands on)
     */
    @GET
    @Path("/action/cancel")
    public Response cancelPayment() {
        paymentService.cancelPayments(state);
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Prints the current ticket and redirects to the appropriate page.
     *
     * @return a 303 redirect (PRG pattern, so a browser reload never replays
     *         the POST): to the payment page while the transaction modal is
     *         shown, otherwise to the main page
     */
    @POST
    @Path("/action/print")
    public Response printTicket() {
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
            return Response.seeOther(URI.create("/pay")).build();
        }
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Applies the cashier's end-of-transaction printing choice (LC-08-03-01 to
     * LC-08-03-06) and returns to the completion modal, which then shows the
     * choice as applied.
     * <p>
     * In training nothing is persisted, so the choice can only produce the
     * in-memory training receipt: it is printed when the choice asks for the
     * sale ticket, and the choice is recorded so the buttons stop offering
     * themselves — exactly as on a real sale.
     *
     * @param choice the raw choice name posted by the modal
     * @return a 303 redirect to the payment page (PRG pattern, so a browser
     *         reload never prints a second time)
     */
    @POST
    @Path("/action/print-choice")
    public Response applyPrintChoice(@FormParam("choice") String choice) {
        com.intermarche.pos.ui.hardware.PrintChoice picked =
                com.intermarche.pos.ui.hardware.PrintChoice.of(choice);
        if (state.trainingMode) {
            if (picked.isSaleTicket()) {
                ticketPrinterService.printTrainingReceipt(state);
            }
            state.payment.printChoice = picked;
            state.payment.printApplied = true;
        } else {
            paymentService.applyPrintChoice(state, picked);
        }
        return Response.seeOther(URI.create("/pay")).build();
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
    /**
     * Abandons the WHOLE ticket from the payment phase, without going back
     * to the article-entry screen first (LC-04-04-05): the partial payments
     * are cancelled the usual way (lease released, valuation reverted,
     * payments removed from the draft — the automatic handling this register
     * applies instead of blocking), then the ticket itself is cancelled.
     *
     * @return a redirect to the sale screen (empty cart)
     */
    @GET
    @Path("/action/pay/abandon-ticket")
    public Response abandonTicketFromPayment() {
        paymentService.cancelPayments(state);
        ticketService.cancelTicket(state);
        return Response.seeOther(URI.create("/")).build();
    }
}