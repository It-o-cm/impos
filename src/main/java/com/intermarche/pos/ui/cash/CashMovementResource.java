package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
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
 * JAX-RS resource of the cash-movement screen: the cashier records a drawer
 * movement (withdrawal, deposit, expense, customer down-payment or cash-count
 * declaration) with its amount and reason, and the movement is written to the
 * register's own database and pushed to the store node through the outbox by
 * {@link CashMovementService} — no synchronous network call sits in this path,
 * so a register cut off from the network keeps recording movements.
 * <p>
 * ENDORSEMENT ceremony (BO-04-01-44 threshold): a movement strictly above the
 * administered threshold ({@code cash.movement-endorsement-threshold}) needs a
 * manager. Two roads, both landing a manager BADGE on the movement — never a
 * secret: the LOGGED operator already holding the MANAGER/ADMIN role
 * self-endorses (their own badge is carried), otherwise a manager presents
 * their credentials, checked and journaled through {@link EndorsementService}.
 * When the amount is at or below the threshold, no credential is asked. The
 * resource resolves the endorsing badge; the service is the guard that refuses
 * an above-threshold movement without one.
 * <p>
 * This screen is a DELIBERATE menu destination like the session page, reached
 * with an open session; it never interrupts a sale.
 */
@Path("/")
public class CashMovementResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashMovementResource.class);

    /** Action code journaled when a manager endorses a cash movement. */
    public static final String ENDORSEMENT_ACTION = "CASH_MOVEMENT";

    @Inject @Location("cash-movement") Template cashMovement;
    @Inject CashSessionService cashSessionService;
    @Inject CashMovementService cashMovementService;
    @Inject PosSettingsService posSettingsService;
    @Inject EndorsementService endorsementService;
    @Inject PosState state;

    /**
     * Shows the cash-movement form: the movement types, the administered reasons
     * and the endorsement threshold, plus any outcome message from a redirect.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous movement was recorded
     * @return the cash-movement page
     */
    @GET
    @Path("/cash-movement")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cashMovementPage(@QueryParam("error") String error,
                                             @QueryParam("ok") String ok) {
        LOGGER.info("Entering method cashMovementPage with error: " + error + ", ok: " + ok);
        String message;
        if ("no-session".equals(error)) {
            message = "AUCUNE SESSION OUVERTE";
        } else if ("bad-type".equals(error)) {
            message = "TYPE DE MOUVEMENT INVALIDE";
        } else if ("endorsement".equals(error)) {
            message = "AVAL MANAGER REFUSÉ OU MANQUANT";
        } else if ("training".equals(error)) {
            message = "INDISPONIBLE EN FORMATION";
        } else {
            message = null;
        }
        LOGGER.info("Exiting method cashMovementPage");
        return cashMovement
                .data("state", state)
                .data("reasons", posSettingsService.cashMovementReasons())
                .data("tenders", posSettingsService.cashMovementTenders())
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", message);
    }

    /**
     * Records a cash movement of the open session. Above the administered
     * threshold a manager must endorse it; below it, nothing is asked.
     *
     * <p>The endorsement is asked through the SHARED modal, not through boxes
     * of this screen's own: the form is parked and the movement is written
     * only once a manager credential passes. The screen used to carry its own
     * badge and PIN inputs, which duplicated the modal and — being plain text
     * fields — could not be typed at all on a register with no keyboard.
     *
     * @param typeStr the movement type name
     * @param amountStr the amount typed by the cashier (French comma tolerated)
     * @param reason the movement reason, or null
     * @param paymentMethod the tender the movement concerns, or null for cash
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    @POST
    @Path("/action/cash-movement/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response record(@FormParam("type") String typeStr,
                           @FormParam("amount") String amountStr,
                           @FormParam("reason") String reason,
                           @FormParam("paymentMethod") String paymentMethod) {
        LOGGER.info("Entering method record with typeStr: " + typeStr + ", amountStr: " + amountStr + ", reason: " + reason + ", paymentMethod: " + paymentMethod);
        if (state.trainingMode) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=training");
        }
        if (cashSessionService.getOpenSession() == null) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=no-session");
        }
        if (parseType(typeStr) == null) {
            LOGGER.info("Exiting method record");
            return redirect("/cash-movement?error=bad-type");
        }
        if (cashMovementService.requiresEndorsement(parseAmount(amountStr))
                && !endorsementService.operatorIsSupervisor(state)) {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            form.put("type", typeStr == null ? "" : typeStr);
            form.put("amount", amountStr == null ? "" : amountStr);
            form.put("reason", reason == null ? "" : reason);
            form.put("paymentMethod", paymentMethod == null ? "" : paymentMethod);
            endorsementService.requestAuthorization(state, ENDORSEMENT_ACTION, form, "/cash-movement");
            LOGGER.info("Exiting method record: aval demande");
            return redirect("/cash-movement");
        }
        LOGGER.info("Exiting method record");
        return perform(typeStr, amountStr, reason, paymentMethod, supervisorBadge());
    }

    /**
     * Replays the parked movement once the shared modal validated a manager.
     *
     * @param form the parked form fields
     * @param endorsedBy the endorsing manager's badge or login
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    public Response performEndorsed(java.util.Map<String, String> form, String endorsedBy) {
        LOGGER.info("Entering method performEndorsed with endorsedBy: " + endorsedBy);
        LOGGER.info("Exiting method performEndorsed");
        return perform(form.get("type"), form.get("amount"), form.get("reason"),
                form.get("paymentMethod"), endorsedBy);
    }

    /**
     * Writes the movement, re-running every guard: the parked form goes
     * through the same checks as a form posted directly, so a session closed
     * while the modal was open cannot slip a movement through.
     *
     * @param typeStr the movement type name
     * @param amountStr the raw amount
     * @param reason the movement reason, or null
     * @param paymentMethod the tender the movement concerns, or null for cash
     * @param endorsedBy the endorsing manager's badge, or null when none was needed
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    private Response perform(String typeStr, String amountStr, String reason,
                             String paymentMethod, String endorsedBy) {
        LOGGER.info("Entering method perform with typeStr: " + typeStr + ", amountStr: " + amountStr);
        if (state.trainingMode) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=no-session");
        }
        CashMovement.MovementType type = parseType(typeStr);
        if (type == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=bad-type");
        }
        BigDecimal amount = parseAmount(amountStr);
        if (cashMovementService.requiresEndorsement(amount) && endorsedBy == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=endorsement");
        }
        Employee cashier = (state.auth.operatorId != null)
                ? Employee.findById(state.auth.operatorId) : null;
        // BO-03-02-20: the movement names the tender it concerns when the form
        // carries one; a blank selection lands as null, i.e. a cash movement.
        String tender = (paymentMethod == null || paymentMethod.isBlank()) ? null : paymentMethod.trim();
        CashMovement recorded = cashMovementService.record(
                session, cashier, type, amount, reason, endorsedBy, tender, null, null);
        state.touch();
        if (recorded == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/cash-movement?error=endorsement");
        }
        LOGGER.info("Exiting method perform");
        return redirect("/cash-movement?ok=1");
    }

    /**
     * The badge of the logged operator when they are themselves a supervisor,
     * the connected-supervisor shortcut of LC-01-05-07; null otherwise.
     *
     * @return the endorsing badge, or null when the operator cannot self-endorse
     */
    private String supervisorBadge() {
        if (endorsementService.operatorIsSupervisor(state)) {
            return state.auth.operatorBadgeId;
        }
        return null;
    }

    /**
     * Parses a movement type name into its enum value, tolerating a null or an
     * unknown name (returns null).
     *
     * @param value the raw type name
     * @return the parsed type, or null when blank or unknown
     */
    private CashMovement.MovementType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CashMovement.MovementType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    /**
     * Builds a 303 redirect to the given location (PRG pattern, so a browser
     * reload never replays the POST).
     *
     * @param location the target location
     * @return a 303 See Other response
     */
    private Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}
