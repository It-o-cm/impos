package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import org.jboss.logging.Logger;

/**
 * The RECALL-BY-CODE screen (EXP-90/91/92): the cashier keys the code the back
 * office gave the customer and the register loads the named document — a quote,
 * an order, a sale voucher or a click-and-collect basket — into the current
 * sale, its lines going through the ordinary add funnel. It also records a
 * down-payment attached to an order (EXP-85, "acompte").
 *
 * <p>The documents live on the store node (imedge, out of this repo); this
 * screen only FETCHES by code through {@link BackOfficeDocumentClient} and
 * loads. On a standalone lane with no store configured the fetch resolves to
 * nothing and the screen answers "DOCUMENT INTROUVABLE"; the connected recall
 * of a real document is [N] residue.
 */
@Path("/")
public class RecallResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(RecallResource.class);

    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;


    @Inject @Location("recall") Template recall;
    @Inject BackOfficeDocumentClient documentClient;
    @Inject TicketService ticketService;
    @Inject CashSessionService cashSessionService;
    @Inject CashMovementService cashMovementService;
    @Inject PosState state;
    /** The deposit on a recalled quote or order (LC-06-02-04). */
    @Inject DocumentDepositService depositService;
    /** The control of a recalled selfscanning basket (LC-06-05-03). */
    @Inject SelfScanService selfScanService;
    /** The rules of the external channels (BO-10-01). */
    @Inject ChannelGate channelGate;
    /** The administered rules read before a fetch (BO-10-01-18, -27). */
    @Inject ChannelRules channelRules;

    /**
     * Shows the recall screen with an optional outcome message.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous acompte was recorded
     * @return the recall page
     */
    @GET
    @Path("/recall")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance recallPage(@QueryParam("error") String error,
                                       @QueryParam("ok") String ok) {
        LOGGER.info("Entering method recallPage");
        String message = "no-session".equals(error) ? i18n.get("op.aucuneSessionOuverte")
                : ("amount".equals(error) ? i18n.get("op.montantCommandeInvalide") : documentError(error));
        RecalledDocument document = state.pendingDocument;
        LOGGER.info("Exiting method recallPage");
        return recall.data("state", state).data("saved", ok != null).data("error", message)
                .data("document", document)
                .data("documentRows", document == null ? java.util.List.of() : rows(document))
                .data("documentTotal", document == null ? "" : euros(depositService.total(document)))
                .data("documentPaid", document == null ? "" : euros(document.acompteAlreadyPaid))
                .data("documentRemaining", document == null ? "" : euros(depositService.remaining(document)));
    }

    /**
     * The lines of a recalled document as the screen shows them, priced as the
     * store administers it (LC-06-02-02, -08): label, ordered and delivered
     * quantities, unit price and total.
     *
     * @param document the document
     * @return one row per line, every value written
     */
    private java.util.List<java.util.Map<String, String>> rows(RecalledDocument document) {
        java.util.List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
        for (RecalledDocument.Line line : ticketService.pricedLines(document)) {
            java.util.Map<String, String> row = new java.util.HashMap<>();
            row.put("label", line.label() == null ? "" : line.label());
            row.put("ordered", quantity(line.quantity() == null ? BigDecimal.ONE : line.quantity()));
            row.put("delivered", line.deliveredQuantity() == null ? "" : quantity(line.deliveredQuantity()));
            row.put("unitPrice", euros(line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice()));
            row.put("total", euros(line.total()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Writes an amount for the screen.
     *
     * @param amount the amount
     * @return the amount, two decimals, French comma, euro sign
     */
    private static String euros(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "\u00a0€";
    }

    /**
     * Writes a quantity for the screen.
     *
     * @param quantity the quantity
     * @return the quantity without trailing zeros, French comma
     */
    private static String quantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /**
     * Words the refusal of a gesture on the recalled document (LC-06-02-04, -08).
     *
     * @param error the error code of the redirect, possibly null
     * @return the operator message, null when the code names no refusal
     */
    private String documentError(String error) {
        if (error == null || !error.startsWith("deposit-")) {
            return null;
        }
        return switch (error.substring("deposit-".length())) {
            case "no_document" -> i18n.get("recall.aucunDocument");
            case "no_session" -> i18n.get("op.aucuneSessionOuverte");
            case "training" -> i18n.get("recall.acompteFormation");
            case "not_allowed" -> i18n.get("recall.acompteNonAdmis");
            case "bad_amount" -> i18n.get("op.montantCommandeInvalide");
            case "over_remaining" -> i18n.get("recall.acompteSuperieurReste");
            case "required" -> i18n.get("recall.acompteObligatoire");
            case "refused" -> i18n.get("recall.documentRefuseCanal");
            default -> null;
        };
    }

    /**
     * Recalls the document a code names and loads it into the sale (EXP-90/91/92),
     * then returns to the sale screen where its lines (and any error) show.
     *
     * @param code the recall code keyed by the cashier
     * @return a redirect to the sale screen
     */
    @POST
    @Path("/action/recall")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response recallDocument(@FormParam("code") String code) {
        LOGGER.info("Entering method recallDocument with code: " + code);
        String keyedCode = code == null ? null : code.trim();
        RecalledDocument doc = null;
        // BO-10-01-18: where the self-scanning baskets are identified by the
        // customer number, the keyed number first names that customer's basket.
        if (channelRules.selfScanByCustomer()) {
            doc = documentClient.fetchByCustomer(keyedCode, RecalledDocument.Kind.SELF_SCAN);
            if (doc != null) {
                doc.byCustomer = true;
            }
        }
        if (doc == null) {
            doc = documentClient.fetch(keyedCode);
        }
        // BO-10-01-16 / -25 / -30 / -40: the code was KEYED here, which the
        // retrieval mode of the channel judges.
        if (doc != null) {
            doc.keyed = true;
        }
        // LC-06-05-03: a selfscanning basket is controlled before it is loaded.
        if (doc != null && doc.kind == RecalledDocument.Kind.SELF_SCAN) {
            selfScanService.start(state, doc);
            LOGGER.info("Exiting method recallDocument (selfscanning)");
            return Response.seeOther(URI.create("/selfscan")).build();
        }
        // LC-06-02-04 / -08: a quote or an order that admits a deposit waits for
        // the cashier's choice — a deposit, or its settlement in the sale.
        if (doc != null && doc.offersDeposit()) {
            // BO-10-01: a document its channel refuses does not wait for a deposit.
            String refusal = channelGate.refusal(state, doc);
            if (refusal != null) {
                state.ticket.setError(refusal);
                state.touch();
                LOGGER.info("Exiting method recallDocument (refused by the channel)");
                return Response.seeOther(URI.create("/")).build();
            }
            state.pendingDocument = doc;
            state.touch();
            LOGGER.info("Exiting method recallDocument (choice)");
            return Response.seeOther(URI.create("/recall")).build();
        }
        ticketService.loadRecalledDocument(state, doc);
        LOGGER.info("Exiting method recallDocument");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Collects a deposit on the recalled document waiting for the cashier's
     * choice (LC-06-02-04, -07, LC-08-04-15).
     *
     * @param amountStr the deposit keyed, TTC
     * @return a redirect back to the recall screen carrying the outcome
     */
    @POST
    @Path("/action/recall/document/deposit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response depositOnDocument(@FormParam("amount") String amountStr) {
        LOGGER.info("Entering method depositOnDocument with amount: " + amountStr);
        DocumentDepositService.Outcome outcome = depositService.collect(state, parseAmount(amountStr));
        LOGGER.info("Exiting method depositOnDocument (" + outcome + ")");
        return Response.seeOther(URI.create(outcome == DocumentDepositService.Outcome.COLLECTED
                ? "/recall?ok=1"
                : "/recall?error=deposit-" + outcome.name().toLowerCase(java.util.Locale.ROOT))).build();
    }

    /**
     * Settles the recalled document waiting for the cashier's choice
     * (LC-06-02-08): its articles go into the sale, the deposits it carries are
     * taken back at payment. Refused while a mandatory deposit is still due.
     *
     * @return a redirect to the sale screen, or back to the recall screen
     */
    @POST
    @Path("/action/recall/document/settle")
    public Response settleDocument() {
        LOGGER.info("Entering method settleDocument");
        RecalledDocument doc = state.pendingDocument;
        if (doc == null) {
            LOGGER.info("Exiting method settleDocument (no document)");
            return Response.seeOther(URI.create("/recall?error=deposit-no_document")).build();
        }
        if (doc.depositRequired()) {
            LOGGER.info("Exiting method settleDocument (deposit required)");
            return Response.seeOther(URI.create("/recall?error=deposit-required")).build();
        }
        state.pendingDocument = null;
        ticketService.loadRecalledDocument(state, doc);
        LOGGER.info("Exiting method settleDocument");
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Puts the recalled document aside, the cashier having done with it.
     *
     * @return a redirect to the recall screen
     */
    @POST
    @Path("/action/recall/document/forget")
    public Response forgetDocument() {
        LOGGER.info("Entering method forgetDocument");
        state.pendingDocument = null;
        state.touch();
        LOGGER.info("Exiting method forgetDocument");
        return Response.seeOther(URI.create("/recall")).build();
    }

    /**
     * Records a down-payment attached to an order (EXP-85).
     *
     * @param orderReference the order the acompte is attached to
     * @param amountStr the acompte amount (French comma tolerated)
     * @return a redirect back to the recall screen carrying the outcome
     */
    @POST
    @Path("/action/recall/acompte")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response recordAcompte(@FormParam("orderReference") String orderReference,
                                  @FormParam("amount") String amountStr) {
        LOGGER.info("Entering method recordAcompte");
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method recordAcompte");
            return Response.seeOther(URI.create("/recall?error=no-session")).build();
        }
        Employee cashier = state.auth.operatorId != null
                ? Employee.findById(state.auth.operatorId) : null;
        // BO-10-01-27: the deposit is taken in the tender the store administers.
        var movement = cashMovementService.recordAcompte(
                session, cashier, parseAmount(amountStr), orderReference,
                channelRules.commercialDepositTender());
        state.touch();
        if (movement == null) {
            LOGGER.info("Exiting method recordAcompte");
            return Response.seeOther(URI.create("/recall?error=amount")).build();
        }
        LOGGER.info("Exiting method recordAcompte");
        return Response.seeOther(URI.create("/recall?ok=1")).build();
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or invalid
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
