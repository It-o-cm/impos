package com.intermarche.pos.ui.cash;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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
 */
@Path("/")
public class CashCalculatorResource {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(CashCalculatorResource.class);

    /** The float-calculator screen. */
    @Inject @Location("cash-calculator") Template cashCalculator;

    /** The opposable count, reused only for its coin and roll denominations. */
    @Inject CashCountService cashCountService;

    /**
     * Shows the float calculator: banknotes up to 200 €, the coins and rolls of
     * the count page, and a live total. Nothing is recorded.
     *
     * @return the float-calculator page
     */
    @GET
    @Path("/cash-calculator")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance calculatorPage() {
        LOGGER.info("Entering method calculatorPage");
        LOGGER.info("Exiting method calculatorPage");
        return cashCalculator
                .data("bills", calculatorBills())
                .data("coins", cashCountService.getCoins())
                .data("rolls", cashCountService.getRolls());
    }

    /**
     * The banknotes the calculator offers: the till notes plus the 100 € and
     * 200 € the cashier may hold when making up the float, largest first.
     *
     * @return the calculator banknote denominations
     */
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
