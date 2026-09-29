package com.intermarche.pos.ui.cash;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
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
import org.jboss.logging.Logger;

/**
 * JAX-RS resource of the change-order slip (bordereau de commande de monnaie,
 * LC-12-05): the cashier prepares an order of coins and notes for making
 * change. The screen lists every euro denomination; the operator may dial, per
 * denomination, either a desired amount or a number of rolls (LC-12-05-03/04),
 * choosing which of the two drives the row (LC-12-05-05), and the other figure
 * is computed from the administered roll-value correspondence (LC-12-05-06/07).
 * The slip may be printed empty for the shop to fill by hand (LC-12-05-02) or
 * filled with the dialed figures (LC-12-05-08).
 * <p>
 * A change order is a PREPARATION document, not a drawer operation: it moves no
 * money and is not tied to a session, so it carries no endorsement ceremony.
 */
@Path("/")
public class ChangeOrderResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ChangeOrderResource.class);

    @Inject @Location("change-order") Template changeOrder;
    @Inject CashCountService cashCountService;
    @Inject PosSettingsService posSettingsService;
    @Inject TicketPrinterService ticketPrinterService;
    @Inject TicketNumberService ticketNumberService;
    @Inject PosState state;

    /**
     * One denomination row of the change-order screen.
     *
     * @param id the denomination id (the {@link CashItem} id)
     * @param label the denomination wording
     * @param amount the dialed amount (formatted), or an empty string
     * @param rolls the dialed roll count, or an empty string
     * @param rollAvailable whether the correspondence knows a roll value for it
     */
    public record Denom(String id, String label, String amount, String rolls, boolean rollAvailable) {
    }

    /**
     * Shows the change-order form: every denomination with the operator's
     * dialed figures, and any outcome message from a redirect.
     *
     * @param error an optional error code from a redirect
     * @param ok a non-null flag when a slip was just printed
     * @return the change-order page
     */
    @GET
    @Path("/change-order")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance changeOrderPage(@QueryParam("error") String error,
                                            @QueryParam("ok") String ok) {
        LOGGER.info("Entering method changeOrderPage with error: " + error + ", ok: " + ok);
        LOGGER.info("Exiting method changeOrderPage");
        return changeOrder
                .data("state", state)
                .data("denominations", denominations())
                .data("saved", ok != null)
                .data("error", messageOf(error));
    }

    /**
     * Dials an amount or a roll count on one denomination and computes the cross
     * value (LC-12-05-03/04/05/06/07).
     *
     * @param id the denomination id
     * @param type the driving figure: {@code AMOUNT} or {@code ROLLS}
     * @param value the typed figure (French comma tolerated for an amount)
     * @return a redirect back to the change-order page
     */
    @POST
    @Path("/action/change-order/entry")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response entry(@FormParam("id") String id,
                          @FormParam("type") String type,
                          @FormParam("value") String value) {
        LOGGER.info("Entering method entry with id: " + id + ", type: " + type + ", value: " + value);
        CashItem item = denominationById(id);
        if (item == null) {
            LOGGER.info("Exiting method entry");
            return redirect("/change-order?error=bad-denom");
        }
        // The type is validated BEFORE the entry is created, so an unknown type
        // leaves no empty row behind.
        if (!ChangeOrderState.TYPE_AMOUNT.equals(type) && !ChangeOrderState.TYPE_ROLLS.equals(type)) {
            LOGGER.info("Exiting method entry");
            return redirect("/change-order?error=bad-type");
        }
        BigDecimal rollValue = ChangeOrderCalculator.rollValueOf(
                ChangeOrderCalculator.parseRollValues(posSettingsService.changeOrderRollValues()),
                BigDecimal.valueOf(item.value));
        ChangeOrderState.Entry current = state.changeOrder.entries
                .computeIfAbsent(id, k -> new ChangeOrderState.Entry());
        if (ChangeOrderState.TYPE_AMOUNT.equals(type)) {
            BigDecimal amount = parseAmount(value);
            current.type = ChangeOrderState.TYPE_AMOUNT;
            current.amount = amount;
            current.rolls = ChangeOrderCalculator.rollsForAmount(rollValue, amount);
        } else {
            int rolls = parseRolls(value);
            current.type = ChangeOrderState.TYPE_ROLLS;
            current.rolls = rolls;
            current.amount = ChangeOrderCalculator.amountForRolls(rollValue, rolls);
        }
        state.touch();
        LOGGER.info("Exiting method entry");
        return redirect("/change-order");
    }

    /**
     * Clears every dialed figure.
     *
     * @return a redirect back to the change-order page
     */
    @POST
    @Path("/action/change-order/clear")
    public Response clear() {
        LOGGER.info("Entering method clear");
        state.changeOrder.clear();
        state.touch();
        LOGGER.info("Exiting method clear");
        return redirect("/change-order");
    }

    /**
     * Prints an EMPTY change-order slip (LC-12-05-02): the denomination list
     * only, for the shop to fill in by hand. Always available — it is a blank
     * form.
     *
     * @return a redirect back to the change-order page
     */
    @POST
    @Path("/action/change-order/print-empty")
    public Response printEmpty() {
        LOGGER.info("Entering method printEmpty");
        List<String[]> rows = new ArrayList<>();
        for (CashItem item : allDenominations()) {
            rows.add(new String[] {item.label, "", ""});
        }
        ticketPrinterService.printChangeOrder(ticketNumberService.getTerminalId(),
                state.auth.operatorBadgeId, state.getOperatorName(), rows);
        LOGGER.info("Exiting method printEmpty");
        return redirect("/change-order?ok=empty");
    }

    /**
     * Prints the FILLED change-order slip (LC-12-05-08): each denomination with
     * the amount and roll count the operator dialed, filled or blank as chosen.
     * Gated by the administered {@code change-order.print} setting.
     *
     * @return a redirect back to the change-order page
     */
    @POST
    @Path("/action/change-order/print")
    public Response print() {
        LOGGER.info("Entering method print");
        if (!posSettingsService.changeOrderPrint()) {
            LOGGER.info("Exiting method print");
            return redirect("/change-order?error=print-disabled");
        }
        List<String[]> rows = new ArrayList<>();
        for (CashItem item : allDenominations()) {
            rows.add(new String[] {item.label,
                    state.changeOrder.getAmountFormatted(item.id),
                    state.changeOrder.getRollsFormatted(item.id)});
        }
        ticketPrinterService.printChangeOrder(ticketNumberService.getTerminalId(),
                state.auth.operatorBadgeId, state.getOperatorName(), rows);
        LOGGER.info("Exiting method print");
        return redirect("/change-order?ok=1");
    }

    /**
     * The denominations shown on the screen, each with the operator's dialed
     * figures and whether the correspondence knows a roll value for it.
     *
     * @return the denomination rows, largest first
     */
    private List<Denom> denominations() {
        List<ChangeOrderCalculator.RollValue> rollValues =
                ChangeOrderCalculator.parseRollValues(posSettingsService.changeOrderRollValues());
        List<Denom> denoms = new ArrayList<>();
        for (CashItem item : allDenominations()) {
            boolean rollAvailable = ChangeOrderCalculator.rollValueOf(
                    rollValues, BigDecimal.valueOf(item.value)) != null;
            denoms.add(new Denom(item.id, item.label,
                    state.changeOrder.getAmountFormatted(item.id),
                    state.changeOrder.getRollsFormatted(item.id), rollAvailable));
        }
        return denoms;
    }

    /**
     * The full euro denomination list: banknotes then coins.
     *
     * @return the denominations, largest first
     */
    private List<CashItem> allDenominations() {
        List<CashItem> items = new ArrayList<>();
        items.addAll(cashCountService.getBills());
        items.addAll(cashCountService.getCoins());
        return items;
    }

    /**
     * The denomination of an id, or null when the id names none.
     *
     * @param id the denomination id
     * @return the denomination, or null
     */
    private CashItem denominationById(String id) {
        if (id == null) {
            return null;
        }
        for (CashItem item : allDenominations()) {
            if (item.id.equals(id)) {
                return item;
            }
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
        if ("bad-denom".equals(error)) {
            return "DÉNOMINATION INCONNUE";
        } else if ("bad-type".equals(error)) {
            return "TYPE DE SAISIE INVALIDE";
        } else if ("print-disabled".equals(error)) {
            return "IMPRESSION DU BORDEREAU DÉSACTIVÉE";
        }
        return null;
    }

    /**
     * Parses a form amount (French comma tolerated) into a non-negative
     * BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank, invalid or negative
     */
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", "."));
            return parsed.signum() < 0 ? BigDecimal.ZERO : parsed;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Parses a form roll count into a non-negative integer.
     *
     * @param value the raw form value
     * @return the parsed count, or 0 when blank, invalid or negative
     */
    private int parseRolls(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException e) {
            return 0;
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
