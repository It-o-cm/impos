package com.intermarche.pos.ui.balance;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.SyncPayloads;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Turns a counter reference scanned at the till into ticket lines (LC-06-01-02).
 *
 * <p>THE RULE THAT GOVERNS EVERYTHING HERE: when the shop cannot arbitrate, the
 * register does NOT integrate. The degraded mode of a counter ticket is the cashier
 * keying the paper in by hand, not a blind integration — the paper carries a price
 * the shop may already have served to another lane, and a register cannot know.
 * So the three refusals (no store node, unreachable, already served) all end the
 * same way: an explicit message, and no line.
 *
 * <p>The lines arrive priced BY THE SCALE. The register does not re-weigh and does
 * not re-price: each line is created at quantity one for the counter's own total,
 * flagged {@code priceEmbedded} so the valuation engine leaves that amount alone,
 * and the weight is carried in the label because that is where the customer reads
 * it. Total to the cent equals the paper the customer is holding, by construction.
 */
@ApplicationScoped
public class BalanceTicketService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(BalanceTicketService.class);

    /** Beyond this many remembered references, the oldest are forgotten. */
    private static final int CONSUMED_MEMORY_SIZE = 500;

    /** Answer given when the register itself already integrated this paper. */
    static final String ALREADY_PICKED_HERE = "TICKET COMPTOIR DÉJÀ INTÉGRÉ";

    /** Answer given when the shop no longer holds the paper (served elsewhere). */
    static final String ALREADY_PICKED_ELSEWHERE = "TICKET COMPTOIR INCONNU OU DÉJÀ UTILISÉ - SAISIE MANUELLE";

    /** Answer given when this register knows no store node at all. */
    static final String NO_STORE_NODE = "COMPTOIR NON RACCORDÉ - SAISIE MANUELLE";

    /** Answer given when the shop is configured but did not answer. */
    static final String UNREACHABLE = "COMPTOIR INJOIGNABLE - SAISIE MANUELLE";

    /** Pulls the paper from the shop and consumes it there in the same call. */
    @Inject
    BalanceTicketClient balanceTicketClient;

    /** Names this register to the shop, so the shop records who picked up. */
    @Inject
    TicketNumberService ticketNumberService;

    /** Default VAT rate applied when neither the counter nor the catalog gives one. */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /** The back-office rule choosing the counter's price or the catalog's. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /**
     * References this register already integrated, so the second scan of a paper
     * still on the counter costs no call. It is deliberately in memory only: it is
     * a courtesy, not the arbitration — the shop is, and a restart simply gives the
     * shop back its job.
     */
    private final Set<String> consumedHere = new LinkedHashSet<>();

    /**
     * Picks a counter reference up and adds its lines to the running ticket.
     *
     * @param state the register state receiving the lines
     * @param reference the reference read off the counter paper
     * @return true when lines were added, false when the paper was refused
     */
    public boolean integrate(PosState state, String reference) {
        LOGGER.info("Entering method integrate with state: " + state + ", reference: " + reference);
        if (isAlreadyPickedHere(reference)) {
            state.ticket.setError(ALREADY_PICKED_HERE);
            LOGGER.info("Exiting method integrate");
            return false;
        }
        BalanceTicketClient.Answer answer =
                balanceTicketClient.pickUp(reference, ticketNumberService.getTerminalId());
        switch (answer.outcome()) {
            case NO_STORE_NODE:
                state.ticket.setError(NO_STORE_NODE);
                LOGGER.info("Exiting method integrate");
                return false;
            case UNREACHABLE:
                state.ticket.setError(UNREACHABLE);
                LOGGER.info("Exiting method integrate");
                return false;
            case ALREADY_CONSUMED:
                state.ticket.setError(ALREADY_PICKED_ELSEWHERE);
                LOGGER.info("Exiting method integrate");
                return false;
            default:
                break;
        }
        remember(reference);
        LOGGER.info("Exiting method integrate");
        return addLines(state, reference, answer.ticket());
    }

    /**
     * Tells whether this register already integrated that reference.
     *
     * @param reference the reference read off the counter paper
     * @return true when the paper was already picked up here
     */
    private boolean isAlreadyPickedHere(String reference) {
        synchronized (consumedHere) {
            return reference != null && consumedHere.contains(reference);
        }
    }

    /**
     * Records a reference as picked up here, forgetting the oldest ones beyond
     * the memory size.
     *
     * @param reference the reference just served by the shop
     */
    private void remember(String reference) {
        synchronized (consumedHere) {
            consumedHere.add(reference);
            while (consumedHere.size() > CONSUMED_MEMORY_SIZE) {
                String oldest = consumedHere.iterator().next();
                consumedHere.remove(oldest);
            }
        }
    }

    /**
     * Adds the served lines to the ticket, refusing the ones the register may not
     * sell, and reports what happened on screen.
     *
     * @param state the register state receiving the lines
     * @param reference the reference the operator presented, named back on screen
     * @param dto the counter ticket the shop served
     * @return true when at least one line was added
     */
    private boolean addLines(PosState state, String reference,
            SyncPayloads.BalanceTicketDto dto) {
        List<String> refused = new ArrayList<>();
        List<String> recalled = new ArrayList<>();
        int added = 0;
        for (SyncPayloads.BalanceTicketLineDto line : dto.lines) {
            Product product = line.ean == null ? null : Product.findActiveByEan(line.ean);
            // A recalled or unsellable article never reaches the customer, even
            // weighed and priced by the counter. The paper is already consumed at
            // the shop, so it is named on screen rather than silently dropped.
            if (product != null && (product.forbiddenToSale || ProductAttributes.recall(product))) {
                refused.add(labelOf(line, product));
                continue;
            }
            addOneLine(state, line, product);
            // LC-02-03-13: a counter line names no lot, so an article under lot recall
            // has its lots COLLECTED here rather than announced line by line — the
            // register has one message area, and a paper carrying several such articles
            // would show only the last of them.
            List<String> lots = ProductAttributes.recalledLots(product);
            if (!lots.isEmpty()) {
                recalled.add(labelOf(line, product) + " (" + String.join(", ", lots) + ")");
            }
            added++;
        }
        if (added == 0) {
            state.ticket.setError("TICKET COMPTOIR REFUSÉ : " + String.join(", ", refused));
            return false;
        }
        if (!refused.isEmpty()) {
            state.ticket.setError("ARTICLE RETIRÉ DU COMPTOIR : " + String.join(", ", refused)
                    + recallSuffix(recalled));
            return true;
        }
        // LC-06-01-04: the reference is named back. The operator scanned a paper and
        // must be able to check, without touching anything, that the lines that just
        // appeared came from THAT paper and not from the one still on the counter.
        state.ticket.setNotice("TICKET COMPTOIR " + reference
                + " INTÉGRÉ (" + added + " LIGNE(S))" + recallSuffix(recalled));
        return true;
    }

    /**
     * The recall clause appended to the message a counter integration ends with
     * ({@code LC-02-03-13}).
     *
     * <p>IT IS A SUFFIX AND NOT A MESSAGE OF ITS OWN. The register has a single
     * message area: a second call would erase the reference the operator needs to
     * check the paper against ({@code LC-06-01-04}), and the choice between naming the
     * paper and naming the recall is one nobody should have to make. Appending keeps
     * both, in the order the gesture reads: what was integrated, then what to look at.
     *
     * @param recalled the articles under lot recall, each with its lots, empty when
     *                 the paper carried none
     * @return the clause, empty when there is nothing to warn about
     */
    private String recallSuffix(List<String> recalled) {
        if (recalled.isEmpty()) {
            return "";
        }
        return " — RAPPEL : " + String.join(", ", recalled);
    }

    /**
     * Adds one counter line to the ticket, at the counter's own total.
     *
     * @param state the register state receiving the line
     * @param line the counter line as the scale priced it
     * @param product the catalog article behind the EAN, or null when unknown here
     */
    private void addOneLine(PosState state, SyncPayloads.BalanceTicketLineDto line, Product product) {
        // LC-06-01-04: the shop chooses which price is booked. AT THE COUNTER'S
        // price, the line is the paper the customer already holds, to the cent. AT
        // THE CATALOG'S, the counter is only a weigher and the register re-prices
        // the weight it reported — which is what a shop wants when its promotions
        // live in the register and not in the scale.
        boolean counterPrice = posSettingsService.balanceCounterPrice();
        BigDecimal total = counterPrice || product == null
                ? counterTotal(line)
                : catalogTotal(line, product);
        // Quantity one at the resolved total: the register's total then equals the
        // figure it just decided, which a unit price times a weight cannot guarantee
        // once either has been rounded.
        state.ticket.addItem(line.ean, product == null ? null : product.plu,
                labelOf(line, product), total, BigDecimal.ONE, vatRateOf(line, product));
        // The line just added is the one to flag, and reading it back is the only way
        // to reach it — addItem returns nothing. An empty list here means the ticket
        // did not take the line: nothing to flag, and certainly nothing to index.
        if (state.ticket.items.isEmpty()) {
            return;
        }
        TicketState.TicketItem added = state.ticket.items.get(state.ticket.items.size() - 1);
        // The flag follows the SOURCE, not the line. A counter price is the line's
        // own truth and the valuation engine must leave it alone; a catalog price is
        // an ordinary price, and flagging it would lock the line out of the very
        // promotions the shop turned this option on to get.
        added.priceEmbedded = counterPrice || product == null;
        // BO-02-03-09: snapshot the discount ban onto the freshly added line.
        if (product != null && ProductAttributes.discountForbidden(product)) {
            added.discountForbidden = true;
        }
        // LC-09-01-11 to -18: what the article may be paid with.
        added.restrictedTenders =
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product);
    }

    /**
     * The line total as the counter computed it.
     *
     * @param line the counter line as the scale priced it
     * @return the counter's total, two decimals, zero when it sent none
     */
    private BigDecimal counterTotal(SyncPayloads.BalanceTicketLineDto line) {
        return line.totalIncludingTax == null
                ? BigDecimal.ZERO
                : line.totalIncludingTax.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The line total recomputed from the catalog: the weight the counter reported,
     * at the register's own price per kilogram ({@code LC-06-01-04}).
     *
     * <p>Falls back to the counter's total when the catalog has no current price or
     * the counter reported no weight — half a rule applied is worse than the rule
     * the operator can read off the paper.
     *
     * @param line the counter line as the scale priced it
     * @param product the catalog article behind the EAN
     * @return the total to book, two decimals
     */
    private BigDecimal catalogTotal(SyncPayloads.BalanceTicketLineDto line, Product product) {
        Price price = Price.findCurrentPrice(product.id);
        if (price == null || price.priceIncludingTax == null
                || line.quantity == null || line.quantity.signum() <= 0) {
            return counterTotal(line);
        }
        return price.priceIncludingTax.multiply(line.quantity).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves the VAT rate of a counter line: the counter's own rate first, then
     * the catalog, then the configured default — and zero when the article is
     * VAT-exempt (BO-02-03-26/27), which outranks all three.
     *
     * @param line the counter line as the scale priced it
     * @param product the catalog article behind the EAN, or null when unknown here
     * @return the VAT rate to capture on the line
     */
    private BigDecimal vatRateOf(SyncPayloads.BalanceTicketLineDto line, Product product) {
        if (product != null && ProductAttributes.vatExempt(product)) {
            return BigDecimal.ZERO;
        }
        if (line.vatRate != null) {
            return line.vatRate;
        }
        Price price = product == null ? null : Price.findCurrentPrice(product.id);
        return price != null && price.vatRate() != null ? price.vatRate() : defaultVatRate;
    }

    /**
     * Builds the line label: the counter's own wording when it sent one, the
     * catalog's otherwise, with the weighed quantity appended because that is
     * where the customer reads it.
     *
     * @param line the counter line as the scale priced it
     * @param product the catalog article behind the EAN, or null when unknown here
     * @return the label to print and display, upper-cased
     */
    private String labelOf(SyncPayloads.BalanceTicketLineDto line, Product product) {
        String base = line.label != null && !line.label.isBlank()
                ? line.label
                : (product != null ? product.saleLabel() : "ARTICLE COMPTOIR");
        if (line.quantity != null && line.quantity.signum() > 0) {
            base = base + " " + line.quantity.setScale(3, RoundingMode.HALF_UP).toPlainString() + "KG";
        }
        return base.toUpperCase();
    }
}
