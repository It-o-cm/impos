package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.store.CheckoutPoint;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the SECURITY SCALE of a self-service lane must see laid down in its
 * bagging area for the basket on screen (BO-02-03-24, BO-02-03-25).
 *
 * <p>The register knows what was rung; the scale knows what was laid down. The
 * deposit control compares the two, and this resource is the register's half:
 * one entry per line with the weight it expects and whether the line is
 * controlled at all.
 * <ul>
 *   <li>a WEIGHED line expects its own measured weight — the product's weight
 *       is deduced from the deposit, which is what BO-02-03-24 asks of a
 *       self-service lane;</li>
 *   <li>a unit line expects the article's reference weight times its
 *       quantity, or nothing measurable when the referential carries none;</li>
 *   <li>a BULKY article is not controlled (BO-02-03-25): the customer cannot
 *       lay a garden chair in the bagging area, and the scale must not stop the
 *       lane waiting for it;</li>
 *   <li>a negative line (a return, a deposit refund) puts nothing down.</li>
 * </ul>
 * The administered {@code article.deposit-control} switches the whole control
 * off. A register that is not a self-service lane answers too — the lane kind
 * travels in the answer — so a lane administered as a checkout is never left
 * without an answer.
 */
@Path("/api/pos/deposit-control")
public class DepositControlResource {

    /** The register state, holding the basket on screen. */
    @Inject
    PosState state;

    /** Whether the shop controls the deposit. */
    @Inject
    PosSettingsService posSettingsService;

    /** This register's identity, to read the kind of its checkout point. */
    @Inject
    TicketNumberService ticketNumberService;

    /**
     * Answers the deposit expected for the basket on screen.
     *
     * @return the control: whether it is active, the lane kind, the lines and
     *         the total expected weight in kilograms
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> expected() {
        CheckoutPoint point = CheckoutPoint.findByTerminal(ticketNumberService.getTerminalId());
        boolean selfService = point != null && point.isSelfService();
        return control(state.ticket.items, posSettingsService.depositControlEnabled(), selfService);
    }

    /**
     * Builds the control of a list of lines — the pure rule, apart from where
     * the lines and the switches come from.
     *
     * @param items the basket lines
     * @param enabled whether the shop controls the deposit
     * @param selfService whether this register is a self-service lane
     * @return the control
     */
    static Map<String, Object> control(List<TicketState.TicketItem> items, boolean enabled, boolean selfService) {
        Map<String, Object> answer = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TicketState.TicketItem item : items) {
            boolean weighed = item.plu != null && !item.plu.isEmpty();
            BigDecimal expected = expectedWeight(item, weighed);
            boolean controlled = enabled && !item.bulky && item.getTotalPrice().signum() > 0;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("uid", item.uid);
            line.put("ean", item.ean);
            line.put("label", item.label);
            line.put("quantity", item.quantity);
            line.put("weighed", weighed);
            line.put("bulky", item.bulky);
            line.put("controlled", controlled);
            line.put("expectedKg", controlled ? expected : null);
            if (controlled && expected != null) {
                total = total.add(expected);
            }
            lines.add(line);
        }
        answer.put("enabled", enabled);
        answer.put("selfService", selfService);
        answer.put("lines", lines);
        answer.put("expectedTotalKg", total);
        return answer;
    }

    /**
     * The weight one line puts down: its measured weight when weighed, its
     * reference weight times its quantity otherwise, null when unknown.
     *
     * @param item the line
     * @param weighed whether the line is a weighing
     * @return the weight in kilograms, or null
     */
    private static BigDecimal expectedWeight(TicketState.TicketItem item, boolean weighed) {
        if (weighed) {
            return item.quantity;
        }
        if (item.referenceWeightKg == null) {
            return null;
        }
        return item.referenceWeightKg.multiply(item.quantity).setScale(3, RoundingMode.HALF_UP);
    }
}
