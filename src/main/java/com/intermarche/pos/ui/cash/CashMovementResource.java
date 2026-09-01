package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.CashMovement;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
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

    /** Action code journaled when a manager endorses a cash movement. */
    static final String ENDORSEMENT_ACTION = "CASH_MOVEMENT";

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
        return cashMovement
                .data("state", state)
                .data("reasons", posSettingsService.cashMovementReasons())
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", message);
    }

    /**
     * Records a cash movement of the open session. Above the administered
     * threshold, a manager endorsement is required and resolved to a badge
     * before the movement is written; below it, no credential is asked.
     *
     * @param typeStr the movement type name
     * @param amountStr the amount typed by the cashier (French comma tolerated)
     * @param reason the movement reason, or null
     * @param managerLogin the endorsing manager's badge or login, or null
     * @param managerPin the endorsing manager's PIN, or null
     * @return a redirect back to the cash-movement page carrying the outcome
     */
    @POST
    @Path("/action/cash-movement/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response record(@FormParam("type") String typeStr,
                           @FormParam("amount") String amountStr,
                           @FormParam("reason") String reason,
                           @FormParam("managerLogin") String managerLogin,
                           @FormParam("managerPin") String managerPin) {
        if (state.trainingMode) {
            return redirect("/cash-movement?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            return redirect("/cash-movement?error=no-session");
        }
        CashMovement.MovementType type = parseType(typeStr);
        if (type == null) {
            return redirect("/cash-movement?error=bad-type");
        }
        BigDecimal amount = parseAmount(amountStr);
        String endorsedBy = null;
        if (cashMovementService.requiresEndorsement(amount)) {
            endorsedBy = resolveEndorsement(managerLogin, managerPin);
            if (endorsedBy == null) {
                return redirect("/cash-movement?error=endorsement");
            }
        }
        Employee cashier = (state.auth.operatorId != null)
                ? Employee.findById(state.auth.operatorId) : null;
        CashMovement recorded = cashMovementService.record(session, cashier, type, amount, reason, endorsedBy);
        state.touch();
        if (recorded == null) {
            return redirect("/cash-movement?error=endorsement");
        }
        return redirect("/cash-movement?ok=1");
    }

    /**
     * Resolves the endorsing manager's badge for an above-threshold movement:
     * the connected supervisor endorses with their own badge, otherwise the
     * presented credentials are checked and journaled and their login stands as
     * the badge. Returns null when no manager authorized the movement.
     *
     * @param login the presented badge or login, or null
     * @param pin the presented PIN, or null
     * @return the endorsing manager's badge, or null when unauthorized
     */
    private String resolveEndorsement(String login, String pin) {
        if (endorsementService.operatorIsSupervisor(state)) {
            return state.auth.operatorBadgeId;
        }
        if (endorsementService.authorize(login, pin, ENDORSEMENT_ACTION)) {
            return login;
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
