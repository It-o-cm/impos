package com.intermarche.pos.ui.cash;

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The float CALCULATOR (EXP-04): a scratchpad the cashier uses to count their
 * opening float denomination by denomination, with the total displayed live.
 *
 * <p>It is a SECOND, distinct function from the Z-closing count, and it is built
 * as one on purpose — not as a flag on {@link CashCountService}. The two share
 * only their arithmetic (the denominations and how a quantity multiplies a
 * value): a count that WRITES nothing and an opposable count that persists a
 * {@code countDetail} and journals a session close must not share a code path
 * where a stray flag could make the calculator record, or the Z count forget to.
 *
 * <p>So nothing here persists and no {@link com.intermarche.pos.domain.session.TechnicalEvent}
 * is emitted: the page serves the denominations and the total is added up in the
 * browser. Its banknotes go up to 200 € — the calculator counts what the cashier
 * physically holds when preparing the float, which is not limited to the notes a
 * till drawer keeps (the Z count stops at 50 €).
 *
 * <p>IT PRINTS, THOUGH, and printing is not recording. The morning count ends with
 * a slip the cashier keeps and shows her manager when the float is not right; the
 * screen had no way to produce one, so the count lived on the screen until the next
 * tap erased it. The slip states the denominations, the total, and that it is a
 * control document carrying no movement.
 */
@Path("/")
public class CashCalculatorResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashCalculatorResource.class);

    /** The float-calculator screen. */
    @Inject @Location("cash-calculator") Template cashCalculator;

    /** The opposable count, reused only for its coin and roll denominations. */
    @Inject CashCountService cashCountService;

    /** Prints the control slip of the count. */
    @Inject TicketPrinterService ticketPrinterService;

    /** Names the register on the slip. */
    @Inject TicketNumberService ticketNumberService;

    /** Names the operator on the slip. */
    @Inject PosState state;

    /**
     * Shows the float calculator: banknotes up to 200 €, the coins and rolls of
     * the count page, and a live total. Nothing is recorded.
     *
     * @param printed a non-null flag when the previous count was printed
     * @return the float-calculator page
     */
    @GET
    @Path("/cash-calculator")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance calculatorPage(@jakarta.ws.rs.QueryParam("printed") String printed) {
        LOGGER.info("Entering method calculatorPage with printed: " + printed);
        LOGGER.info("Exiting method calculatorPage");
        return cashCalculator
                .data("bills", calculatorBills())
                .data("coins", cashCountService.getCoins())
                .data("rolls", cashCountService.getRolls())
                .data("printed", printed != null);
    }

    /**
     * The banknotes the calculator offers: the till notes plus the 100 € and
     * 200 € the cashier may hold when making up the float, largest first.
     *
     * @return the calculator banknote denominations
     */
    /**
     * Prints the control count and comes back to the calculator, which keeps its
     * figures: the cashier may count again, or print a second slip, and nothing she
     * does here is written anywhere.
     *
     * <p>A count that adds up to NOTHING prints nothing — there is no document to
     * make of an empty drawer the cashier has not counted yet — and the screen comes
     * back plainly, without an error: an empty total is not a mistake, it is a count
     * not yet made.
     *
     * @param amountStr the counted total, computed in the browser (French comma tolerated)
     * @param detail the per-denomination JSON of the count, or null
     * @return a redirect back to the calculator
     */
    @POST
    @Path("/action/cash-calculator/print")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response printCount(@FormParam("amount") String amountStr,
                               @FormParam("detail") String detail) {
        LOGGER.info("Entering method printCount with amountStr: " + amountStr + ", detail: " + detail);
        BigDecimal total = parseAmount(amountStr);
        if (total.signum() <= 0) {
            LOGGER.info("Exiting method printCount: rien a imprimer");
            return Response.seeOther(URI.create("/cash-calculator")).build();
        }
        List<CashItem> items = new ArrayList<>(calculatorBills());
        items.addAll(cashCountService.getCoins());
        items.addAll(cashCountService.getRolls());
        ticketPrinterService.printFloatCountTicket(total,
                cashCountService.denominationLines(detail, items),
                state.getOperatorName(), ticketNumberService.getTerminalId());
        LOGGER.info("Exiting method printCount");
        return Response.seeOther(URI.create("/cash-calculator?printed=1")).build();
    }

    /**
     * Parses a form amount (French comma tolerated) into a BigDecimal.
     *
     * @param value the raw form value
     * @return the parsed amount, or ZERO when blank or unreadable
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

    List<CashItem> calculatorBills() {
        List<CashItem> bills = new ArrayList<>();
        bills.add(new CashItem("b200", "Billet 200 €", 200.0));
        bills.add(new CashItem("b100", "Billet 100 €", 100.0));
        bills.add(new CashItem("b50", "Billet 50 €", 50.0));
        bills.add(new CashItem("b20", "Billet 20 €", 20.0));
        bills.add(new CashItem("b10", "Billet 10 €", 10.0));
        bills.add(new CashItem("b5", "Billet 5 €", 5.0));
        return bills;
    }
}
