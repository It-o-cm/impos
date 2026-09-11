package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.CashMovement;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
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
import java.util.List;

/**
 * The two gestures that move money inside the drawer without a customer in front of
 * the register: the manual withdrawal of a tender ({@code LC-12-03}) and the transfer
 * of a settlement from one tender to another ({@code LC-12-10}).
 *
 * <p>THEY ARE THE SAME GESTURE SEEN TWICE. A withdrawal takes an amount OUT of one
 * tender; a transfer takes it out of one and puts it into another. Both are recorded
 * as a {@link CashMovement} naming the tender, both are checked against what the
 * register believes the drawer holds, and both leave a paper the shop can switch off.
 * Sharing one resource is what keeps the two theoreticals — the tender's and the
 * drawer's — moved by one code path instead of two that drift.
 *
 * <p>WHAT THE OPERATOR MAY CHANGE DIFFERS BY TENDER. Cash is counted denomination by
 * denomination and the register adds it up ({@code LC-12-03-04/05}); every other
 * tender is withdrawn whole, at the quantity and amount the register states, with no
 * field to type in ({@code LC-12-03-06}) — a cheque bundle is either handed over or it
 * is not, and letting a cashier withdraw "some of the cheques" would invent a
 * theoretical nobody can count back.
 *
 * <p>THE AMOUNT CONTROL OF A TRANSFER CANNOT BE OVERRIDDEN ({@code LC-12-10-03}).
 * Transferring more than a tender holds does not repair a mis-keying, it creates a
 * negative theoretical the closing would have to explain.
 *
 * <p>Both screens are DELIBERATE menu destinations reached with an open session; they
 * never interrupt a sale.
 */
@Path("/")
public class DrawerOperationsResource {

    /** Action code journaled when a manager endorses a drawer operation. */
    static final String ENDORSEMENT_ACTION = "DRAWER_OPERATION";

    /** The withdrawal screen. */
    @Inject @Location("withdrawal") Template withdrawal;

    /** The settlement-transfer screen. */
    @Inject @Location("transfer") Template transfer;

    /** The tenders the drawer holds and their theoreticals. */
    @Inject DrawerMethodService drawerMethodService;

    /** The open session the movements belong to. */
    @Inject CashSessionService cashSessionService;

    /** Records the movements and pushes them to the store node. */
    @Inject CashMovementService cashMovementService;

    /** The administered settings of the two gestures. */
    @Inject PosSettingsService posSettingsService;

    /** Checks and journals a manager endorsement. */
    @Inject EndorsementService endorsementService;

    /** Prints the withdrawal and transfer tickets. */
    @Inject TicketPrinterService ticketPrinterService;

    /** Names the register on the printed tickets. */
    @Inject TicketNumberService ticketNumberService;

    /** The denominations of a cash count. */
    @Inject CashCountService cashCountService;

    /** The screen state, for the operator and the training mode. */
    @Inject PosState state;

    /**
     * Shows the withdrawal screen: the tender list when none is chosen, then either the
     * denomination count of cash or the read-only theoretical of another tender.
     *
     * @param method the chosen tender key, or null while none is chosen
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous withdrawal was recorded
     * @return the withdrawal page
     */
    @GET
    @Path("/withdrawal")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance withdrawalPage(@QueryParam("method") String method,
                                           @QueryParam("error") String error,
                                           @QueryParam("ok") String ok) {
        List<DrawerMethodService.DrawerMethod> methods = drawerMethodService.withdrawable();
        DrawerMethodService.DrawerMethod selected = chosen(methods, method);
        return withdrawal
                .data("state", state)
                .data("methods", methods)
                .data("selected", selected)
                .data("isCash", selected != null && CashMovement.CASH.equals(selected.key()))
                .data("needsEndorsement", selected != null
                        && cashMovementService.requiresEndorsement(selected.amount()))
                .data("bills", cashCountService.getBills())
                .data("coins", cashCountService.getCoins())
                .data("rolls", cashCountService.getRolls())
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", messageOf(error));
    }

    /**
     * Shows the settlement-transfer screen.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when the previous transfer was recorded
     * @return the transfer page
     */
    @GET
    @Path("/transfer")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance transferPage(@QueryParam("error") String error,
                                         @QueryParam("ok") String ok) {
        return transfer
                .data("state", state)
                .data("methods", drawerMethodService.transferable())
                .data("threshold", posSettingsService.cashMovementEndorsementThreshold().toPlainString())
                .data("saved", ok != null)
                .data("error", messageOf(error));
    }

    /**
     * Finds the tender the screen is showing among the ones it offers.
     *
     * <p>The chosen key is READ BACK FROM THE OFFERED LIST rather than trusted: a key
     * the shop no longer administers must land on the tender list again, not on a
     * screen offering to withdraw something the drawer does not hold.
     *
     * @param methods the tenders offered
     * @param key the key carried by the query, or null while none is chosen
     * @return the matching tender, or null when none matches
     */
    DrawerMethodService.DrawerMethod chosen(List<DrawerMethodService.DrawerMethod> methods,
                                            String key) {
        if (key == null) {
            return null;
        }
        for (DrawerMethodService.DrawerMethod candidate : methods) {
            if (candidate.key().equals(key)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Records a manual withdrawal of one tender and prints its ticket when the shop
     * asks for one.
     *
     * <p>The amount of a non-cash tender is NOT read from the form: the register
     * withdraws exactly what it believes the drawer holds ({@code LC-12-03-06}), so a
     * forged post cannot invent one.
     *
     * @param method the tender key chosen on the screen
     * @param amountStr the counted total, for cash only (French comma tolerated)
     * @param detail the per-denomination JSON of a cash count, or null
     * @param managerLogin the endorsing manager's badge or login, or null
     * @param managerPin the endorsing manager's PIN, or null
     * @return a redirect back to the withdrawal screen carrying the outcome
     */
    @POST
    @Path("/action/withdrawal/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response recordWithdrawal(@FormParam("method") String method,
                                     @FormParam("amount") String amountStr,
                                     @FormParam("detail") String detail,
                                     @FormParam("managerLogin") String managerLogin,
                                     @FormParam("managerPin") String managerPin) {
        if (state.trainingMode) {
            return redirect("/withdrawal?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            return redirect("/withdrawal?error=no-session");
        }
        if (!drawerMethodService.isWithdrawable(method)) {
            return redirect("/withdrawal?error=bad-method");
        }
        boolean cash = CashMovement.CASH.equals(method);
        BigDecimal amount = cash ? parseAmount(amountStr) : drawerMethodService.theoreticalOf(method);
        if (amount.signum() <= 0) {
            return redirect("/withdrawal?error=amount&method=" + method);
        }
        String endorsedBy = null;
        if (cashMovementService.requiresEndorsement(amount)) {
            endorsedBy = resolveEndorsement(managerLogin, managerPin);
            if (endorsedBy == null) {
                return redirect("/withdrawal?error=endorsement&method=" + method);
            }
        }
        String label = drawerMethodService.labelOf(method);
        CashMovement recorded = cashMovementService.record(session, cashierOf(),
                CashMovement.MovementType.WITHDRAWAL, amount, "Prélèvement " + label, endorsedBy,
                method, null, cash ? detail : null);
        state.touch();
        if (recorded == null) {
            return redirect("/withdrawal?error=endorsement&method=" + method);
        }
        if (posSettingsService.drawerWithdrawalPrint()) {
            ticketPrinterService.printWithdrawalTicket(label, amount,
                    cash ? denominationLines(detail) : drawerMethodService.transactionsOf(method),
                    state.getOperatorName(), ticketNumberService.getTerminalId());
        }
        return redirect("/withdrawal?ok=1");
    }

    /**
     * Records a settlement transfer between two tenders and prints its ticket when the
     * shop asks for one.
     *
     * @param from the source tender key
     * @param to the destination tender key
     * @param amountStr the amount to move (French comma tolerated)
     * @param managerLogin the endorsing manager's badge or login, or null
     * @param managerPin the endorsing manager's PIN, or null
     * @return a redirect back to the transfer screen carrying the outcome
     */
    @POST
    @Path("/action/transfer/record")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response recordTransfer(@FormParam("from") String from,
                                   @FormParam("to") String to,
                                   @FormParam("amount") String amountStr,
                                   @FormParam("managerLogin") String managerLogin,
                                   @FormParam("managerPin") String managerPin) {
        if (state.trainingMode) {
            return redirect("/transfer?error=training");
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            return redirect("/transfer?error=no-session");
        }
        if (!drawerMethodService.isTransferable(from) || !drawerMethodService.isTransferable(to)) {
            return redirect("/transfer?error=bad-method");
        }
        if (from.equals(to)) {
            return redirect("/transfer?error=same-method");
        }
        BigDecimal amount = parseAmount(amountStr);
        if (amount.signum() <= 0) {
            return redirect("/transfer?error=amount");
        }
        if (amount.compareTo(drawerMethodService.theoreticalOf(from)) > 0) {
            return redirect("/transfer?error=insufficient");
        }
        String endorsedBy = null;
        if (cashMovementService.requiresEndorsement(amount)) {
            endorsedBy = resolveEndorsement(managerLogin, managerPin);
            if (endorsedBy == null) {
                return redirect("/transfer?error=endorsement");
            }
        }
        String fromLabel = drawerMethodService.labelOf(from);
        String toLabel = drawerMethodService.labelOf(to);
        CashMovement recorded = cashMovementService.record(session, cashierOf(),
                CashMovement.MovementType.TRANSFER, amount,
                "Transfert " + fromLabel + " vers " + toLabel, endorsedBy, from, to, null);
        state.touch();
        if (recorded == null) {
            return redirect("/transfer?error=endorsement");
        }
        if (posSettingsService.drawerTransferPrint()) {
            ticketPrinterService.printTransferTicket(fromLabel, toLabel, amount,
                    state.getOperatorName(), ticketNumberService.getTerminalId());
        }
        return redirect("/transfer?ok=1");
    }

    /**
     * Turns the per-denomination JSON of a cash count into the ticket lines that state
     * how many of each note and coin left the drawer ({@code LC-12-03-07}).
     *
     * <p>Only the denominations the count screen knows are kept: the JSON is a screen
     * artefact, and an unknown key has no value to multiply by.
     *
     * @param detail the {@code {"b50":2,"c1":3}} JSON posted by the screen, or null
     * @return one {@code {denomination, amount}} pair per counted denomination
     */
    private List<String[]> denominationLines(String detail) {
        List<String[]> lines = new ArrayList<>();
        if (detail == null || detail.isBlank()) {
            return lines;
        }
        List<CashItem> items = new ArrayList<>();
        items.addAll(cashCountService.getBills());
        items.addAll(cashCountService.getCoins());
        items.addAll(cashCountService.getRolls());
        String body = detail.replace("{", "").replace("}", "").replace("\"", "");
        for (String entry : body.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) {
                continue;
            }
            String id = parts[0].trim();
            int quantity;
            try {
                quantity = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (quantity <= 0) {
                continue;
            }
            for (CashItem item : items) {
                if (item.id.equals(id)) {
                    BigDecimal amount = BigDecimal.valueOf(item.value)
                            .multiply(BigDecimal.valueOf(quantity));
                    lines.add(new String[] {item.label + " x" + quantity,
                            String.format("%.2f", amount).replace('.', ',') + " E"});
                }
            }
        }
        return lines;
    }

    /**
     * Resolves the endorsing manager's badge for an above-threshold operation: the
     * connected supervisor endorses with their own badge, otherwise the presented
     * credentials are checked and journaled and their login stands as the badge.
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
     * The employee the movement is recorded against.
     *
     * @return the logged operator, or null when none is identified
     */
    private Employee cashierOf() {
        return (state.auth.operatorId != null) ? Employee.findById(state.auth.operatorId) : null;
    }

    /**
     * Turns an error code carried by a redirect into the wording the screen shows.
     *
     * @param error the error code, or null
     * @return the message, or null when the redirect carried no error
     */
    String messageOf(String error) {
        if (error == null) {
            return null;
        }
        return switch (error) {
            case "no-session" -> "AUCUNE SESSION OUVERTE";
            case "bad-method" -> "MOYEN DE PAIEMENT NON AUTORISÉ";
            case "same-method" -> "SOURCE ET DESTINATION IDENTIQUES";
            case "amount" -> "MONTANT INVALIDE";
            case "insufficient" -> "MONTANT SUPÉRIEUR AU THÉORIQUE DU TIROIR";
            case "endorsement" -> "AVAL MANAGER REFUSÉ OU MANQUANT";
            case "training" -> "INDISPONIBLE EN FORMATION";
            default -> null;
        };
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
     * Builds a 303 redirect to the given location (PRG pattern, so a browser reload
     * never replays the POST).
     *
     * @param location the target location
     * @return a 303 See Other response
     */
    private Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}
