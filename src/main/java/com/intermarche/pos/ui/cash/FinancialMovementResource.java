package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.session.CashMovement;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the financial-movement screen (LC-12-06): the cashier
 * receives an amount in one payment mean and hands the SAME amount back in
 * another, OUTSIDE a sale — used notably to settle an in-account customer's
 * debt (Espèces or Carte bancaire towards Crédit client).
 * <p>
 * This is a DIFFERENT gesture from the drawer settlement transfer of
 * {@link DrawerOperationsResource} (LC-12-10), which repairs a mis-keying
 * inside the drawer and is capped at the source tender's drawer theoretical.
 * A financial movement is not a drawer repair: its destination — a customer
 * credit account, for one — is not a tender the drawer holds, so no
 * drawer-theoretical ceiling applies. Both, however, are recorded as a
 * {@link CashMovement.MovementType#TRANSFER} naming a source
 * ({@code paymentMethod}) and a destination ({@code transferTo}).
 * <p>
 * The source and destination lists are administered
 * ({@code movement.financial-sources} / {@code movement.financial-destinations},
 * LC-12-06-02); the posted ends are validated against them. Above the
 * administered endorsement threshold a manager must endorse the movement, the
 * same ceremony as the cash-movement and drawer screens: the logged supervisor
 * self-endorses, otherwise the form is parked and the shared modal opens.
 * <p>
 * This screen is a DELIBERATE menu destination reached with an open session; it
 * never interrupts a sale.
 */
@Path("/")
public class FinancialMovementResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(FinancialMovementResource.class);

    /** Action code journaled when a manager endorses a financial movement. */
    public static final String ENDORSEMENT_ACTION = "FINANCIAL_MOVEMENT";

    @Inject @Location("financial-movement") Template financialMovement;
    @Inject CashSessionService cashSessionService;
    @Inject CashMovementService cashMovementService;
    @Inject PosSettingsService posSettingsService;
    @Inject EndorsementService endorsementService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject TicketNumberService ticketNumberService;
    @Inject PosState state;

    /**
     * One payment mean offered on the financial-movement screen.
     *
     * @param key the payment-mean key the register records
     * @param label the wording the cashier reads
     */
    public record Option(String key, String label) {
    }

    /**
     * Shows the financial-movement form: the administered source and
     * destination lists and any outcome message from a redirect.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous movement was recorded
     * @return the financial-movement page
     */
    @GET
    @Path("/financial-movement")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance financialMovementPage(@QueryParam("error") String error,
                                                  @QueryParam("ok") String ok) {
        LOGGER.info("Entering method financialMovementPage with error: " + error + ", ok: " + ok);
        LOGGER.info("Exiting method financialMovementPage");
        return financialMovement
                .data("state", state)
                .data("sources", options(posSettingsService.financialMovementSources()))
                .data("destinations", options(posSettingsService.financialMovementDestinations()))
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", messageOf(error));
    }

    /**
     * Records a financial movement of the open session. Above the administered
     * threshold a manager must endorse it; below it, nothing is asked.
     *
     * @param source the payment mean the amount is received in
     * @param destination the payment mean the amount is handed back in
     * @param amountStr the amount typed by the cashier (French comma tolerated)
     * @return a redirect back to the financial-movement page carrying the outcome
     */
    @POST
    @Path("/action/financial-movement/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response record(@FormParam("source") String source,
                           @FormParam("destination") String destination,
                           @FormParam("amount") String amountStr) {
        LOGGER.info("Entering method record with source: " + source + ", destination: " + destination + ", amountStr: " + amountStr);
        if (state.trainingMode) {
            LOGGER.info("Exiting method record");
            return redirect("/financial-movement?error=training");
        }
        if (cashSessionService.getOpenSession() == null) {
            LOGGER.info("Exiting method record");
            return redirect("/financial-movement?error=no-session");
        }
        if (!isSource(source) || !isDestination(destination)) {
            LOGGER.info("Exiting method record");
            return redirect("/financial-movement?error=bad-tender");
        }
        if (source.equals(destination)) {
            LOGGER.info("Exiting method record");
            return redirect("/financial-movement?error=same-tender");
        }
        BigDecimal amount = parseAmount(amountStr);
        if (amount.signum() <= 0) {
            LOGGER.info("Exiting method record");
            return redirect("/financial-movement?error=amount");
        }
        String endorsedBy = supervisorBadge();
        if (cashMovementService.requiresEndorsement(amount) && endorsedBy == null) {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            // No null guard on the ends: the membership guard above refused the
            // line when either was null or off-list, so reaching here proves
            // both were given and name administered means.
            form.put("source", source);
            form.put("destination", destination);
            form.put("amount", amountStr == null ? "" : amountStr);
            endorsementService.requestAuthorization(state, ENDORSEMENT_ACTION, form, "/financial-movement");
            LOGGER.info("Exiting method record: aval demande");
            return redirect("/financial-movement");
        }
        LOGGER.info("Exiting method record");
        return perform(source, destination, amountStr, endorsedBy);
    }

    /**
     * Replays the parked financial movement once the shared modal validated a
     * manager.
     *
     * @param form the parked form fields
     * @param endorsedBy the endorsing manager's badge or login
     * @return a redirect back to the financial-movement page carrying the outcome
     */
    public Response performEndorsed(java.util.Map<String, String> form, String endorsedBy) {
        LOGGER.info("Entering method performEndorsed with endorsedBy: " + endorsedBy);
        LOGGER.info("Exiting method performEndorsed");
        return perform(form.get("source"), form.get("destination"), form.get("amount"), endorsedBy);
    }

    /**
     * Writes the movement, re-running every guard so a form parked while the
     * modal was open cannot slip through after the session closed.
     *
     * @param source the source payment mean
     * @param destination the destination payment mean
     * @param amountStr the raw amount
     * @param endorsedBy the endorsing manager's badge, or null when none was needed
     * @return a redirect back to the financial-movement page carrying the outcome
     */
    private Response perform(String source, String destination, String amountStr, String endorsedBy) {
        LOGGER.info("Entering method perform with source: " + source + ", destination: " + destination);
        if (state.trainingMode) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=no-session");
        }
        if (!isSource(source) || !isDestination(destination)) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=bad-tender");
        }
        if (source.equals(destination)) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=same-tender");
        }
        BigDecimal amount = parseAmount(amountStr);
        if (amount.signum() <= 0) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=amount");
        }
        if (cashMovementService.requiresEndorsement(amount) && endorsedBy == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=endorsement");
        }
        String sourceLabel = labelOf(source, posSettingsService.financialMovementSources());
        String destinationLabel = labelOf(destination, posSettingsService.financialMovementDestinations());
        Employee cashier = (state.auth.operatorId != null)
                ? Employee.findById(state.auth.operatorId) : null;
        CashMovement recorded = cashMovementService.record(session, cashier,
                CashMovement.MovementType.TRANSFER, amount,
                "Mouvement financier " + sourceLabel + " vers " + destinationLabel,
                endorsedBy, source, destination, null);
        state.touch();
        if (recorded == null) {
            LOGGER.info("Exiting method perform");
            return redirect("/financial-movement?error=endorsement");
        }
        if (posSettingsService.financialMovementPrint()) {
            ticketPrinterService.printTransferTicket(sourceLabel, destinationLabel, amount,
                    state.getOperatorName(), ticketNumberService.getTerminalId());
        }
        LOGGER.info("Exiting method perform");
        return redirect("/financial-movement?ok=1");
    }

    /**
     * Parses an administered {@code CLÉ:LIBELLÉ} list into ordered options,
     * dropping malformed and duplicate rows; never null.
     *
     * @param administered the raw list, or null
     * @return the parsed options in administered order
     */
    private List<Option> options(String administered) {
        List<Option> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (administered == null) {
            return options;
        }
        for (String entry : administered.split(";")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            String key = parts[0].trim().toUpperCase(Locale.ROOT);
            String label = parts[1].trim();
            if (key.isEmpty() || label.isEmpty() || !seen.add(key)) {
                continue;
            }
            options.add(new Option(key, label));
        }
        return options;
    }

    /**
     * Whether a key is an administered source of a financial movement.
     *
     * @param key the posted source key, possibly null
     * @return true when the key names an administered source
     */
    private boolean isSource(String key) {
        return keyPresent(key, posSettingsService.financialMovementSources());
    }

    /**
     * Whether a key is an administered destination of a financial movement.
     *
     * @param key the posted destination key, possibly null
     * @return true when the key names an administered destination
     */
    private boolean isDestination(String key) {
        return keyPresent(key, posSettingsService.financialMovementDestinations());
    }

    /**
     * Whether a key belongs to an administered {@code CLÉ:LIBELLÉ} list.
     *
     * @param key the posted key, possibly null
     * @param administered the raw administered list
     * @return true when the key is present
     */
    private boolean keyPresent(String key, String administered) {
        if (key == null) {
            return false;
        }
        for (Option option : options(administered)) {
            if (option.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The wording of a key in an administered list, the key itself when the
     * list names no label for it.
     *
     * @param key the payment-mean key
     * @param administered the raw administered list
     * @return the label, or the key when none is administered
     */
    private String labelOf(String key, String administered) {
        for (Option option : options(administered)) {
            if (option.key().equals(key)) {
                return option.label();
            }
        }
        return key;
    }

    /**
     * The badge of the logged operator when they are themselves a supervisor,
     * null otherwise.
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
     * Maps an error code from a redirect to its operator-facing French message.
     *
     * @param error the error code, or null
     * @return the message, or null when no error
     */
    private String messageOf(String error) {
        if ("no-session".equals(error)) {
            return "AUCUNE SESSION OUVERTE";
        } else if ("bad-tender".equals(error)) {
            return "MOYEN DE PAIEMENT INVALIDE";
        } else if ("same-tender".equals(error)) {
            return "MEME MOYEN EN SOURCE ET DESTINATION";
        } else if ("amount".equals(error)) {
            return "MONTANT INVALIDE";
        } else if ("endorsement".equals(error)) {
            return "AVAL MANAGER REFUSÉ OU MANQUANT";
        } else if ("training".equals(error)) {
            return "INDISPONIBLE EN FORMATION";
        }
        return null;
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
