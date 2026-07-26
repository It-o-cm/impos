package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-facing display (phase 4): a dedicated page opened on the second
 * screen, polling its own fragment with the same version mechanism as the
 * main screen. Shows the running cart, the totals, the payment progress and,
 * once complete, the change and the digital receipt link. No lock applies —
 * it is a display, not a control surface.
 * <p>
 * The screen is DERIVED, never commanded: {@code /customer-data} projects
 * the register's {@code PosState} into a display phase (welcome when locked
 * or empty, running cart, payment in progress, thank-you with change and
 * digital link, orange banner in training) and the page's versioned poll
 * redraws on change — the register never pushes anything to the display,
 * which is what lets it be a plain second browser window with zero setup.
 * Because it reads shared state without a lock gate, it must stay
 * READ-ONLY: any control added here would bypass the operator lock.
 */
@Path("/")
public class CustomerDisplayResource {

    @Inject @Location("customer") Template customer;
    @Inject PosState state;

    /**
     * Shows the customer display page.
     *
     * @return the customer display page
     */
    @GET
    @Path("/customer")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance customerPage() {
        return customer.data("state", state);
    }

    /**
     * Polling endpoint of the customer display, version-gated like the main
     * screen fragment.
     *
     * @param clientVersion the version known by the display, or null
     * @return a JSON map with the change flag and, when changed, the display data
     */
    @GET
    @Path("/customer-data")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> customerData(@QueryParam("v") Long clientVersion) {
        Map<String, Object> result = new HashMap<>();
        if (clientVersion != null && state.version == clientVersion) {
            result.put("changed", false);
            return result;
        }
        result.put("changed", true);
        result.put("version", state.version);
        result.put("locked", state.isLocked());
        result.put("training", state.trainingMode);
        result.put("empty", state.ticket.items.isEmpty());
        result.put("total", state.ticket.getTotalFormatted());
        result.put("remaining", state.getRemainingFormatted());
        result.put("paying", state.payment.paymentInProgress);
        result.put("complete", state.payment.transactionComplete);
        result.put("change", state.payment.lastChangeAmount != null
                ? state.payment.lastChangeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",")
                : "");
        result.put("digitalPath", digitalPath());

        List<Map<String, String>> items = new ArrayList<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            Map<String, String> row = new HashMap<>();
            row.put("label", item.label);
            row.put("qty", quantityDisplay(item));
            row.put("amount", item.getPriceFormatted());
            items.add(row);
        }
        result.put("items", items);
        return result;
    }

    /**
     * Builds the digital receipt path of the current draft, or an empty string.
     *
     * @return the digital receipt path, or ""
     */
    private String digitalPath() {
        if (state.payment.ticketDbId == null) return "";
        Ticket ticket = Ticket.findById(state.payment.ticketDbId);
        if (ticket == null || ticket.digitalKey == null) return "";
        return "/t/" + ticket.id + "/" + ticket.digitalKey;
    }

    /**
     * Formats an item quantity for the customer display: kilograms for
     * weighed lines, a unit count otherwise.
     *
     * @param item the ticket item
     * @return the formatted quantity
     */
    private String quantityDisplay(TicketState.TicketItem item) {
        if (item.plu != null && !item.plu.isEmpty()) {
            return String.format("%.3f kg", item.quantity).replace(".", ",");
        }
        if (item.quantity.stripTrailingZeros().scale() <= 0) {
            return "x" + item.quantity.stripTrailingZeros().toPlainString();
        }
        return "x" + String.format("%.2f", item.quantity).replace(".", ",");
    }
}
