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

    @Inject @Location("recall") Template recall;
    @Inject BackOfficeDocumentClient documentClient;
    @Inject TicketService ticketService;
    @Inject CashSessionService cashSessionService;
    @Inject CashMovementService cashMovementService;
    @Inject PosState state;

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
        String message = "no-session".equals(error) ? "AUCUNE SESSION OUVERTE"
                : ("amount".equals(error) ? "MONTANT OU COMMANDE INVALIDE" : null);
        LOGGER.info("Exiting method recallPage");
        return recall.data("state", state).data("saved", ok != null).data("error", message);
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
        RecalledDocument doc = documentClient.fetch(code == null ? null : code.trim());
        ticketService.loadRecalledDocument(state, doc);
        LOGGER.info("Exiting method recallDocument");
        return Response.seeOther(URI.create("/")).build();
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
        var movement = cashMovementService.recordAcompte(
                session, cashier, parseAmount(amountStr), orderReference);
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
