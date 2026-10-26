package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.recall.RecalledDocument;
import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.scanner.ScanContext;
import com.intermarche.pos.ui.valuation.ValuationService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Ticket-building service: scan processing, item additions, price
 * modifications and ticket-level actions.
 * <p>
 * All monetary values are {@link BigDecimal} (phase 0). Weights coming from
 * the scale remain double at the hardware boundary and are converted when a
 * line is created.
 * <p>
 * Phase 0 lot 2: every cart mutation is followed by a draft synchronization
 * ({@link TicketPersistenceService#syncDraft}), so the sale survives a
 * register restart from the first article on.
 * <p>
 * {@code processScan} is the UNIFORM ENTRY of everything that names a
 * product or a code: scanner gun, simulator, manual typing, search-result
 * taps — one gate, one session guard (bypassed in training), one handler
 * chain assembled here from CDI in {@code @Priority} order (missing
 * annotation = 100). Uniformity is the guarantee that no path can invent
 * its own semantics: whatever adds to the cart went through the same
 * chain.
 */
@ApplicationScoped
public class TicketService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TicketService.class);

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The control points of the sale (BO-10-02-07). */
    @Inject
    com.intermarche.pos.ui.endorsement.EndorsementService endorsementService;

    /** The back-office parameters (discount caps — LC-03-02). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The administered lists: return types (BO-04-02-07). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosListService posListService;

    /** The register's upward notices: unknown-article alert (BO-04-02-06). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.SupervisorSettleService supervisorSettleService;

    /** The rules of the external channels a recalled basket comes from (BO-10-01). */
    @Inject
    com.intermarche.pos.ui.recall.ChannelGate channelGate;

    /** The per-channel price source of a recalled basket (BO-10-01-07, -19, -29, -33, -36, -41). */
    @Inject
    com.intermarche.pos.ui.recall.ChannelRules channelRules;

    /** Internal scale used for intermediate unit-price divisions. */
    private static final int PRICE_SCALE = 4;

    /** Default VAT rate applied to unknown items without a catalog price (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Technical EAN of the manual deconsignment line (parameterized).
     * <p>
     * Register-generated lines carry a technical EAN like every other line:
     * the valuation engine prices the WHOLE ticket and must be able to
     * resolve each line it receives — a line without an EAN does not exist.
     * The referential must declare these codes on the engine side too.
     */
    @ConfigProperty(name = "pos.ean.deconsignment")
    String deconsignmentEan;

    /** Technical EAN of a manually typed unknown article (parameterized). */
    @ConfigProperty(name = "pos.ean.unknown-item")
    String unknownItemEan;

    @Inject
    HardwareService hardwareService;

    @Inject
    com.intermarche.pos.ui.balance.TareService tareService;

    @Inject
    TicketPersistenceService ticketPersistenceService;

    @Inject
    ValuationService valuationService;

    /** The survey trigger of the transaction's beginning (LC-03-07-01). */
    @Inject
    com.intermarche.pos.ui.supervision.SurveyTriggerService surveyTriggerService;

    /**
     * The single post-mutation funnel (phase 7 lot 4): persists the draft,
     * then revalues the cart against the engine — every cart mutation of
     * this service ends here, so pricing follows the cart in real time
     * (synchronous local call; training, payment phase and open circuit
     * are guarded inside {@code revalue}).
     *
     * <p>LC-03-07-01: this funnel is also where the register lives the
     * BEGINNING of a transaction — the first article that makes the cart
     * non-empty — and the survey administered for that moment is asked here,
     * once per transaction, tolerantly.
     *
     * @param state the current POS state
     */
    private void syncAndRevalue(PosState state) {
        // LC-02-03-04: complete companion additions before the single draft/valuation pass.
        if (state.linkedArticlesUpdating) return;
        LinkedArticleService.synchronize(state, (ean, quantity) -> addItemByEan(state, ean, quantity), i18n);
        ticketPersistenceService.syncDraft(state);
        valuationService.revalue(state);
        noticeOnArticleMessage(state);
        warnOnExpensiveEntry(state);
        suggestComplementary(state);
        surveyTriggerService.maybeFireOnTransactionStart(state);
    }

    /**
     * Suggests the COMPLEMENTARY articles of the article just rung
     * (BO-02-03-16): their labels are shown to the cashier, who proposes them to
     * the customer — the household equipment beside the drill, the batteries
     * beside the toy.
     *
     * <p>Consumes the pending article whatever happens, so a later gesture
     * never replays the suggestion. It yields to anything already said: a
     * refusal, the article's own message and the price warning all matter more
     * than a sales prompt. Only active articles are suggested, under the label
     * the till shows, and at most the administered number of them.
     *
     * @param state the current POS state
     */
    private void suggestComplementary(PosState state) {
        String ean = state.complementaryPendingEan;
        state.complementaryPendingEan = null;
        if (ean == null || state.ticket.transientError != null
                || !posSettingsService.articleComplementarySuggestions()) {
            return;
        }
        Product carrier = Product.findActiveByEan(ean);
        List<String> labels = new java.util.ArrayList<>();
        for (String complementary : ProductAttributes.complementaryEans(carrier)) {
            Product suggested = Product.findActiveByEan(complementary);
            if (suggested != null && labels.size() < posSettingsService.articleComplementaryMax()) {
                labels.add(translatedLabel(suggested));
            }
        }
        if (!labels.isEmpty()) {
            state.ticket.setNotice(i18n.format("op.articlesComplementaires", String.join(", ", labels)));
        }
    }

    /**
     * Sends an article that must be WEIGHED to the weighing (BO-02-03-24): a
     * scanned or keyed EAN of such an article does not ring a unit line, it asks
     * the scale, exactly as the article's PLU would on the fruits screen.
     *
     * <p>The weighing needs the article's PLU, which is the identity a weighed
     * line is recorded and consolidated under; an article declared weighed
     * without one cannot be weighed, and the register says so rather than
     * ringing it at one unit.
     *
     * @param state the current POS state
     * @param product the article named by its EAN
     * @return true when the entry was taken over by the weighing (or refused)
     */
    public boolean routeWeighed(PosState state, Product product) {
        if (!ProductAttributes.weighed(product)) {
            return false;
        }
        if (product.plu == null || product.plu.isBlank()) {
            state.ticket.setError(i18n.get("op.articlePeseSansPlu"));
            return true;
        }
        addItemByPlu(state, product.plu);
        return true;
    }

    /**
     * Refuses an article of the "appel prix" checkout mode that has no price in
     * the referential (BO-02-03-05): such an article is rung at the price of its
     * fiche and at no other, so without one it is not rung at all — never at
     * zero, never at a price nobody stated.
     *
     * @param state the current POS state
     * @param product the article
     * @param price its current price, or null
     * @return true when the article was refused
     */
    public boolean priceCallRefused(PosState state, Product product, Price price) {
        if (!ProductAttributes.priceCallOnly(product)
                || price != null && price.priceIncludingTax != null) {
            return false;
        }
        state.ticket.setError(i18n.get("op.prixArticleIntrouvable"));
        return true;
    }

    /**
     * Snapshots on the lines of the article just rung what the article says of
     * its VAT and of its handling (BO-02-03-24 to -26, -41, -42), and arms the
     * suggestion of its complementary articles (BO-02-03-16).
     *
     * @param state the current POS state
     * @param product the article rung
     * @param vat the VAT the line was ventilated at
     */
    public void stampArticle(PosState state, Product product, com.intermarche.pos.domain.catalog.ArticleVat.Resolution vat) {
        state.ticket.snapshotArticleTraits(product.ean, vat.exempt(), ProductAttributes.thirdPartySale(product),
                ProductAttributes.service(product), ProductAttributes.bulky(product), product.referenceWeight, ProductAttributes.priceCallOnly(product));
        state.complementaryPendingEan = product.ean;
    }

    /**
     * Shows the ARTICLE'S OWN MESSAGE when one has just been rung
     * (BO-03-08-09).
     *
     * <p>Informative messages leave the article registered and let the sale continue.
     * LC-02-03-11 blocking messages are intercepted by ArticleMessageService before
     * admission, so they cannot reach this post-registration notification.
     *
     * <p>Said BEFORE the price warning and not after, because the two share one
     * notice slot and the article's own message is the more specific of the
     * two: a store that wrote a sentence on an article wants that sentence
     * read, and a dear article says it is dear by its price.
     *
     * <p>An error already on the screen outranks both: a line that was refused
     * must not be covered by a notice about the line before it.
     *
     * @param state the current POS state
     */
    private void noticeOnArticleMessage(PosState state) {
        if (!state.lineJustEntered || state.ticket.transientError != null) {
            return;
        }
        // BO-03-10-13: the island may silence the messages tied to an article.
        // The flag is NOT consumed here: a blocked message is a message the
        // register never shows, and the next reader of lineJustEntered — the
        // high-price warning — must decide for itself.
        if (posSettingsService.islandBlockArticlePopups()) {
            return;
        }
        TicketState.TicketItem entered = enteredLine(state);
        if (entered == null || entered.ean == null || entered.ean.isBlank()) {
            return;
        }
        // The line carries the EAN and not the article's key, so the message is
        // read by the EAN the line was rung on — the same lookup the sale made.
        com.intermarche.pos.domain.catalog.Product product =
                com.intermarche.pos.domain.catalog.Product.find("ean", entered.ean).firstResult();
        if (product == null || product.infoMessage == null || product.infoMessage.isBlank()) {
            return;
        }
        state.lineJustEntered = false;
        state.ticket.setWarning(product.infoMessage.trim());
    }

    /**
     * Warns the cashier when the article just entered is dearer than the
     * administered threshold.
     *
     * <p>Placed on the post-mutation funnel rather than on each entry point:
     * a scan, a keyed EAN, a PLU, a group tile and the entry prompt all end
     * here, and all of them raise {@code lineJustEntered} through the single
     * {@code addItem} funnel. The flag is CONSUMED whatever the outcome, so a
     * gesture that follows the entry — a discount, a quantity change — never
     * replays the warning, and a second entry of the same article warns
     * again even though it merged into the line already there.
     *
     * <p>Three things it deliberately does not do. It never speaks over a
     * refusal or a confirmation already in the zone: the message that stopped
     * a gesture matters more than the one that comments on it. It reads the
     * UNIT price and not the line amount, because the rule is about the
     * article being dear, not about the basket growing — three articles at
     * four euros are not a dear article. And a threshold at zero switches it
     * off entirely rather than warning on everything.
     *
     * @param state the current POS state
     */
    private void warnOnExpensiveEntry(PosState state) {
        if (!state.lineJustEntered) {
            return;
        }
        state.lineJustEntered = false;
        if (state.ticket.transientError != null) {
            return;
        }
        // BO-03-10-13: silenced by the island, like the article message — the
        // flag above is still consumed, so the entry is accounted for either
        // way and the next entry starts clean.
        if (posSettingsService.islandBlockArticlePopups()) {
            return;
        }
        BigDecimal threshold = posSettingsService.saleLineWarningThreshold();
        if (threshold == null || threshold.signum() <= 0) {
            return;
        }
        TicketState.TicketItem entered = enteredLine(state);
        if (entered == null || entered.unitPrice == null
                || entered.unitPrice.compareTo(threshold) <= 0) {
            return;
        }
        state.ticket.setWarning(i18n.format("op.prixEleve", entered.label,
                entered.unitPrice.setScale(2, java.math.RoundingMode.HALF_UP)
                        .toPlainString().replace('.', ',')));
    }

    /**
     * Returns the line the last entry landed on, by the uid the funnel
     * recorded.
     *
     * @param state the current POS state
     * @return the entered line, or null when it is no longer in the ticket
     */
    private TicketState.TicketItem enteredLine(PosState state) {
        if (state.lastEnteredItemId == null) {
            return null;
        }
        for (TicketState.TicketItem item : state.ticket.items) {
            if (state.lastEnteredItemId.equals(item.uid)) {
                return item;
            }
        }
        return null;
    }

    @Inject
    CashSessionService cashSessionService;

    @Inject
    Instance<ScanContext.ScanHandler> scanHandlers;

    /** LC-02-14-12: read-only consultation owns the scan while its screen is active. */
    @Inject ArticleInfoScanState articleInfoScanState;

    /** Whether an article may be rung on THIS register (BO-03-01-18). */
    @jakarta.inject.Inject
    ArticleScopeService articleScopeService;

    /**
     * The return flow, for the one scan the sale screen does not own: on the
     * return screen a scanned article designates the ORIGIN line to take back
     * (LC-05-02-11).
     */
    @Inject
    com.intermarche.pos.ui.returnprocess.RefundService refundService;

    /** The sale-screen service, for the cancel-by-scan gesture (EXP-36). */
    @Inject
    com.intermarche.pos.ui.home.HomeService homeService;

    @Inject
    PosState state;

    @Inject
    TicketPrinterService ticketPrinterService;

    /** The abandon rules: printing, and the reason the operator gave (LC-04-04). */
    @jakarta.inject.Inject
    TicketAbandonService ticketAbandonService;

    /** The dematerialised articles and their activation on the payment hub (LC-02-09). */
    @Inject
    DematerialisedService dematerialisedService;

    @Inject
    com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** The administered register alerts (BO-04-03-08/09). */
    @Inject
    com.intermarche.pos.ui.control.ControlGateService controlGateService;

    /** The register's identity — which terminal's last sale to recover. */
    @Inject
    com.intermarche.pos.service.TicketNumberService ticketNumberService;

    /** The pulse of the register's link with the store node (BO-03-06-45). */
    @Inject
    com.intermarche.pos.service.sync.RefPullService refPullService;

    /**
     * Applies (or clears, value 0) the ticket-level discount after its
     * manager endorsement (phase: global ticket discount). The base is what
     * the customer actually pays — AFTER the engine's offers — and the
     * amount is ventilated onto the lines at every recomputation (see
     * TicketState.allocateGlobalDiscount). Refused during an active payment.
     *
     * @param state the current POS state
     * @param type {@link PriceModType#GLOBAL_REMISE} (euros) or
     *             {@link PriceModType#GLOBAL_DISCOUNT} (percent)
     * @param value the endorsed value
     */
    public void applyGlobalDiscount(PosState state, PriceModType type, BigDecimal value) {
        LOGGER.info("Entering method applyGlobalDiscount with state: " + state + ", type: " + type + ", value: " + value);
        if (state.payment.paymentInProgress) {
            state.ticket.setError(i18n.get("op.terminezAnnulezTicketAbord"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // The percentage cap is administered (LC-03-02-13); 100 stays the
        // absolute ceiling whatever the back office says.
        int cap = Math.min(100, posSettingsService.globalMaxDiscountPercent());
        if (value == null || value.signum() < 0
                || (type == PriceModType.GLOBAL_DISCOUNT
                        && value.compareTo(BigDecimal.valueOf(cap)) > 0)) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // The EURO cap is administered separately (BO-03-07-04) and governs the
        // amount gesture only: a percentage is already bounded by its own cap.
        // Zero disables it, so an unadministered register behaves as before.
        BigDecimal amountCap = posSettingsService.globalMaxDiscountAmount();
        if (type == PriceModType.GLOBAL_REMISE && amountCap.signum() > 0
                && value.compareTo(amountCap) > 0) {
            state.ticket.setError(i18n.get("op.remiseSuperieurePlafond"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // BO-03-07-12: the store decides whether the ticket-level gesture bears
        // on the lines that already carry a reduction. Read HERE and carried on
        // the ticket, because the allocation runs at every recomputation and has
        // no business reaching for the back office each time.
        state.ticket.globalDiscountSparesReducedLines =
                !posSettingsService.discountGlobalOnDiscounted();
        // LC-03-02-15: when the store spares the already-reduced lines, the
        // discount must have somewhere to land. A ticket whose every eligible
        // line is already reduced cannot carry it, and the questionnaire asks
        // the register to say so out loud rather than let the allocation
        // silently allocate nothing.
        if (state.ticket.globalDiscountSparesReducedLines
                && !state.ticket.items.isEmpty()
                && globalDiscountBase(state) == null) {
            state.ticket.setError(i18n.get("op.toutesLignesDejaRemisees"));
            LOGGER.info("Exiting method applyGlobalDiscount");
            return;
        }
        // LC-03-02-14: an AMOUNT gesture is bounded by the administered
        // PERCENTAGE ceiling too — a rabais whose implied share of the ticket
        // exceeds the ceiling is the same theft of margin as a keyed percentage
        // above it, and the operator is told with the ceiling's own word. The
        // base is the one the allocation will ventilate on, so the two figures
        // the operator compares are the same two the register uses.
        if (type == PriceModType.GLOBAL_REMISE) {
            java.math.BigDecimal base = globalDiscountBase(state);
            if (base != null) {
                java.math.BigDecimal impliedPercent = value.multiply(BigDecimal.valueOf(100))
                        .divide(base, PRICE_SCALE, RoundingMode.HALF_UP);
                if (impliedPercent.compareTo(BigDecimal.valueOf(cap)) > 0) {
                    state.ticket.setError(i18n.get("op.remiseSuperieurePlafond"));
                    LOGGER.info("Exiting method applyGlobalDiscount");
                    return;
                }
            }
        }
        state.ticket.setGlobalDiscount(
                type == PriceModType.GLOBAL_DISCOUNT ? "PERCENT" : "AMOUNT", value);
        recalculateTotal(state);
        LOGGER.info("Exiting method applyGlobalDiscount");
    }

    /**
     * Applies the discount the back office grants an account customer, without
     * a gesture or an endorsement (LC-03-02-09): the rate is the back office's
     * decision, written on the account's record, not the cashier's.
     *
     * <p>It is the ticket-level discount in percent, so it is shown, printed and
     * ventilated like the keyed one, and recomputed at every change of the
     * ticket: an account named before the first article covers the whole
     * basket. A ticket discount already granted is never replaced in silence —
     * the register says so and keeps it. The store's rule on lines already
     * reduced (BO-03-07-12, LC-03-02-15) is honoured exactly as for the keyed
     * gesture.
     *
     * @param state the current POS state
     * @param customer the named account, its discount applying
     */
    public void applyAccountCustomerDiscount(PosState state,
            com.intermarche.pos.domain.payment.AccountCustomer customer) {
        LOGGER.info("Entering method applyAccountCustomerDiscount with customer: " + customer.accountNumber);
        BigDecimal rate = customer.discountPercent;
        if (rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            LOGGER.info("Exiting method applyAccountCustomerDiscount");
            return;
        }
        if (state.ticket.globalDiscountType != null) {
            state.ticket.setError(i18n.get("op.remiseTicketDejaAppliquee"));
            LOGGER.info("Exiting method applyAccountCustomerDiscount");
            return;
        }
        state.ticket.globalDiscountSparesReducedLines = !posSettingsService.discountGlobalOnDiscounted();
        if (state.ticket.globalDiscountSparesReducedLines && !state.ticket.items.isEmpty()
                && globalDiscountBase(state) == null) {
            state.ticket.setError(i18n.get("op.toutesLignesDejaRemisees"));
            LOGGER.info("Exiting method applyAccountCustomerDiscount");
            return;
        }
        state.ticket.setGlobalDiscount("PERCENT", rate);
        // Said BEFORE the recomputation, so a control point the new total trips
        // (BO-10-02-07) has the last word on the message band.
        state.ticket.setNotice(i18n.format("op.remiseClientCompte", customer.getDisplayName(),
                rate.stripTrailingZeros().toPlainString()));
        recalculateTotal(state);
        LOGGER.info("Exiting method applyAccountCustomerDiscount");
    }

    /**
     * The base a ticket-level discount would ventilate on RIGHT NOW: the
     * positive totals of the eligible lines, excluding the money products and
     * the articles that ban discounts, and — when the store spares them
     * (BO-03-07-12) — the lines that already carry a reduction.
     *
     * <p>Returns null — and not zero — when NO line is eligible: that is the
     * signal the caller refuses the gesture on, because a base of zero from a
     * NON-empty ticket means the ceiling cannot even be computed, and the
     * all-reduced case of LC-03-02-15 must not read as "go ahead".
     *
     * @param state the current POS state
     * @return the eligible base, or null when no line can carry the discount
     */
    private java.math.BigDecimal globalDiscountBase(PosState state) {
        java.math.BigDecimal base = BigDecimal.ZERO;
        boolean found = false;
        for (TicketState.TicketItem item : state.ticket.items) {
            if (item == null || item.moneyProduct || item.discountForbidden) {
                continue;
            }
            if (state.ticket.globalDiscountSparesReducedLines && alreadyReduced(item)) {
                continue;
            }
            if (item.getTotalPrice().signum() > 0) {
                found = true;
                base = base.add(item.getTotalPrice());
            }
        }
        return found ? base : null;
    }

    /**
     * Suspends the sale on an age-restricted product: the add is PARKED and
     * the server-rendered prompt asks the cashier to check the customer's ID
     * (phase: age control). Returns false — no prompt — when the product is
     * unrestricted or the ticket already carries a verification at this
     * threshold or above (one ID check covers the whole sale).
     *
     * @param state the current POS state
     * @param product the resolved product
     * @param kind the replay kind (SCAN, PLU or EAN_QTY)
     * @param code the parked code to replay on confirmation
     * @param quantity the parked quantity (EAN_QTY kind), or null
     * @return true when the add was suspended behind the prompt
     */
    public boolean suspendForAgeCheck(PosState state, Product product, String kind,
                                      String code, BigDecimal quantity) {
        LOGGER.info("Entering method suspendForAgeCheck with state: " + state + ", product: " + product + ", kind: " + kind + ", code: " + code + ", quantity: " + quantity);
        if (product.ageRestriction == null
                || product.ageRestriction <= state.ticket.ageVerifiedThreshold) {
            LOGGER.info("Exiting method suspendForAgeCheck");
            return false;
        }
        state.ageCheck.active = true;
        state.ageCheck.productLabel = product.name.toUpperCase();
        state.ageCheck.threshold = product.ageRestriction;
        state.ageCheck.kind = kind;
        state.ageCheck.code = code;
        state.ageCheck.quantity = quantity;
        hardwareService.displayMessage(i18n.both("display.controleAgeCours"));
        state.touch();
        LOGGER.info("Exiting method suspendForAgeCheck");
        return true;
    }

    /**
     * Suspends the add on an article whose price or whose quantity the referential
     * does not carry ({@code LC-02-03-01} and {@code -03}).
     *
     * <p>THE PROMPT REPLACES NOTHING AND ADDS NOTHING: the article is the one that
     * was named, the line is the one that would have been created, and the only
     * difference is that a figure comes from the operator instead of the catalog. A
     * "prix requis" article — a gift card, a generic "Divers" line — has no price to
     * read; a decimal-quantity article has a price PER UNIT OF MEASURE and no
     * quantity, so the prompt shows that price and its unit while the operator keys
     * the metres or the litres.
     *
     * <p>The two CHAIN when an article carries both: the quantity is asked first,
     * because it is what the price will multiply, and the operator sees the figure
     * they already gave while giving the second.
     *
     * @param state    the current POS state
     * @param product  the resolved product
     * @param quantity the quantity already resolved, null treated as one
     * @return true when the add was suspended behind the prompt
     */
    public boolean suspendForEntry(PosState state, Product product, BigDecimal quantity) {
        LOGGER.info("Entering method suspendForEntry with state: " + state + ", product: " + product + ", quantity: " + quantity);
        // BO-02-03-22/23: a whole quantity or a decimal one, the same prompt.
        boolean decimal = ProductAttributes.decimalQuantityToEnter(product);
        boolean needsQuantity = decimal || ProductAttributes.quantityToEnter(product);
        // BO-03-13-10: on a manual return — one taken WITHOUT the original
        // ticket — the administered return type may ask the cashier to confirm
        // or change the price given back. It is the SAME prompt: the figure
        // comes from a person, the catalog price is shown beside it, and the
        // line is created once.
        boolean needsPrice = ProductAttributes.priceToEnter(product)
                || returnPriceConfirmed(state);
        if (!needsQuantity && !needsPrice) {
            LOGGER.info("Exiting method suspendForEntry");
            return false;
        }
        Price price = Price.findCurrentPrice(product.id);
        state.entryPrompt.clear();
        state.entryPrompt.active = true;
        state.entryPrompt.kind = needsQuantity
                ? PosState.EntryPromptState.QUANTITY
                : PosState.EntryPromptState.PRICE;
        state.entryPrompt.ean = product.ean;
        state.entryPrompt.label = translatedLabel(product);
        state.entryPrompt.unitName = product.unitName == null ? "" : product.unitName;
        state.entryPrompt.unitPriceFormatted = price == null || price.priceIncludingTax == null
                ? "" : String.format("%.2f", price.priceIncludingTax).replace('.', ',');
        state.entryPrompt.quantity = quantity == null ? BigDecimal.ONE : quantity;
        state.entryPrompt.wholeQuantity = needsQuantity && !decimal;
        state.touch();
        LOGGER.info("Exiting method suspendForEntry");
        return true;
    }

    /**
     * Whether the return in progress asks for its price to be confirmed
     * (BO-03-13-10).
     *
     * <p>Only in RETURN mode, and only for the administered type the operator
     * chose: a sale is never asked to confirm a catalog price, and a return
     * taken from its original ticket reads the price off that ticket.
     *
     * @param state the current POS state
     * @return true when the price of the returned line must be confirmed
     */
    private boolean returnPriceConfirmed(PosState state) {
        if (!state.returnMode) {
            return false;
        }
        com.intermarche.pos.service.PosSettingsService.ReturnType type =
                posListService.returnType(state.returnTypeLabel);
        return type != null && type.priceConfirmed();
    }

    /**
     * Takes the figure the operator keyed and either asks the second one or creates
     * the line ({@code LC-02-03-01/02/03}).
     *
     * @param state the current POS state
     * @param value the figure keyed: a decimal quantity or a price, depending on the
     *              prompt
     */
    public void confirmEntry(PosState state, BigDecimal value) {
        LOGGER.info("Entering method confirmEntry with state: " + state + ", value: " + value);
        if (!state.entryPrompt.active) {
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        if (value == null || value.signum() <= 0) {
            state.ticket.setError(i18n.get("op.valeurInvalide"));
            state.touch();
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        // A figure the prompt takes withdraws the refusal of the previous one.
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product product = state.entryPrompt.plu == null
                ? Product.findActiveByEan(state.entryPrompt.ean)
                : Product.findActiveByPlu(state.entryPrompt.plu);
        if (product == null) {
            state.entryPrompt.clear();
            state.ticket.setError(i18n.get("op.produitIntrouvable"));
            state.touch();
            LOGGER.info("Exiting method confirmEntry");
            return;
        }
        if (state.entryPrompt.isPriceKind()) {
            state.entryPrompt.price = value;
        } else {
            // BO-02-03-22: a "quantité à saisir" article takes a whole positive
            // number; the prompt stays open for the operator to correct it.
            if (state.entryPrompt.wholeQuantity && value.stripTrailingZeros().scale() > 0) {
                state.ticket.setError(i18n.get("op.quantiteEntiereRequise"));
                state.touch();
                LOGGER.info("Exiting method confirmEntry");
                return;
            }
            state.entryPrompt.quantity = value;
            // The article may want its price too: ask it now rather than ringing a
            // line at a price nobody gave.
            // LC-05-02-02: a return-type confirmation must survive quantity entry.
            if (ProductAttributes.priceToEnter(product) || returnPriceConfirmed(state)) {
                state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
                state.touch();
                LOGGER.info("Exiting method confirmEntry");
                return;
            }
        }
        BigDecimal quantity = state.entryPrompt.quantity;
        BigDecimal keyedPrice = state.entryPrompt.price;
        String plu = state.entryPrompt.plu;
        BigDecimal appliedTare = state.entryPrompt.appliedTareKg;
        state.entryPrompt.clear();
        addEnteredLine(state, product, quantity, keyedPrice, plu, appliedTare);
        LOGGER.info("Exiting method confirmEntry");
    }

    /**
     * Gives up the suspended add: nothing is registered.
     *
     * @param state the current POS state
     */
    public void cancelEntry(PosState state) {
        LOGGER.info("Entering method cancelEntry with state: " + state);
        state.entryPrompt.clear();
        state.ticket.setError(i18n.get("op.saisieAbandonnee"));
        state.touch();
        LOGGER.info("Exiting method cancelEntry");
    }

    /**
     * Creates the line an entry prompt resolved.
     *
     * @param state      the current POS state
     * @param product    the article
     * @param quantity   the quantity to register
     * @param keyedPrice the price the operator gave, or null to use the catalog's
     * @param plu weighed article identity, null for ordinary admission
     * @param appliedTare tare already applied to the net weighing
     */
    private void addEnteredLine(PosState state, Product product, BigDecimal quantity,
            BigDecimal keyedPrice, String plu, BigDecimal appliedTare) {
        if (ArticleMessageService.refuse(state, product)) return;
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal unitPrice = keyedPrice != null
                ? keyedPrice
                : (price != null ? price.priceIncludingTax : BigDecimal.ZERO);
        // BO-02-03-26/27: the article's own regime, exemption included.
        com.intermarche.pos.domain.catalog.ArticleVat.Resolution vat =
                com.intermarche.pos.domain.catalog.ArticleVat.resolve(product, price, defaultVatRate);
        BigDecimal vatRate = vat.rate();
        state.ticket.addItem(product.ean, plu, translatedLabel(product),
                unitPrice, quantity, vatRate);
        // addItem always leaves at least one line (it merges into an existing one
        // or appends a new one), so the list is never empty here.
        TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
        // LC-02-03-02: the unit of measure travels WITH the line, so the register
        // display, the customer display and the receipt all state "2,36 x 4,99 €/m"
        // without any of them going back to the referential.
        line.unitName = product.unitName;
        // BO-04-01-14: a price the operator typed is a « prix saisi ».
        if (keyedPrice != null) {
            line.priceEntered = true;
        }
        if (plu != null) {
            // LC-05-02-02: persist the original weighing, never read the scale twice.
            state.ticket.lastRecordedWeight = quantity.doubleValue();
            line.tareKg = ProductAttributes.tare(product);
            line.appliedTareKg = appliedTare;
        }
        // LC-02-13-02: the DECIMAL-QUANTITY type travels with the line too, so the
        // quantity key knows that decimals are legitimate on this one without
        // reading the referential back.
        line.decimalQuantity = ProductAttributes.decimalQuantityToEnter(product);
        if (product.giftCardAmount != null) {
            line.moneyProduct = true;
        }
        if (ProductAttributes.discountForbidden(product)) {
            line.discountForbidden = true;
        }
        // BO-02-03-06: the meal-voucher eligibility travels with the line, so a
        // register the engine never answered still knows what may be settled
        // with a meal ticket.
        state.ticket.snapshotMealVoucherEligibility(product.ean, ProductAttributes.mealVoucherEligible(product));
        LinkedArticleService.snapshot(state.ticket, product);
        // LC-10-01-09/11: by article, since a repeated entry may merge into an earlier line.
        state.ticket.snapshotRestrictedTenders(product.ean,
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product));
        stampArticle(state, product, vat);
        displayItem(line);
        syncAndRevalue(state);
        // LC-02-09-01: a dematerialised article sold at the amount the operator
        // keyed asks for its code like the one sold at its referential price.
        awaitActivationCode(state, line, product);
    }

    /**
     * Opens the wait for the unique code of a dematerialised article just rung
     * (BO-03-06-44, LC-02-09-01): the next scan is that code.
     *
     * @param state the current POS state
     * @param line the line just rung
     * @param product its article
     */
    private void awaitActivationCode(PosState state, TicketState.TicketItem line, Product product) {
        if (!ProductAttributes.activationRequired(product)) {
            return;
        }
        line.activationProvider = ProductAttributes.activationProvider(product);
        state.ticket.pendingActivationUid = line.uid;
        state.ticket.setWarning(i18n.get("op.codeActivationAttendu"));
    }

    /**
     * Confirms the ID check: the verification is remembered for the whole
     * ticket (highest threshold wins), the decision is journalized, and the
     * parked add REPLAYS through its original path — the sale resumes as if
     * the product had just been presented.
     *
     * @param state the current POS state
     */
    public void confirmAgeCheck(PosState state) {
        LOGGER.info("Entering method confirmAgeCheck with state: " + state);
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) { LOGGER.info("Exiting method confirmAgeCheck"); return; }
        state.ticket.ageVerifiedThreshold =
                Math.max(state.ticket.ageVerifiedThreshold, pending.threshold);
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.AGE_CHECK_CONFIRMED,
                pending.productLabel + " (" + pending.threshold + "+) - ID vérifiée par "
                        + state.auth.operatorName);
        String kind = pending.kind;
        String code = pending.code;
        BigDecimal quantity = pending.quantity;
        pending.clear();
        switch (kind) {
            case "PLU" -> addItemByPlu(state, code);
            case "EAN_QTY" -> addItemByEan(state, code, quantity);
            default -> processScan(code);
        }
        LOGGER.info("Exiting method confirmAgeCheck");
    }

    /**
     * Refuses the sale of the restricted product: nothing is added, the
     * refusal is journalized (the legal trace), and the cashier gets the
     * transient refusal message.
     *
     * @param state the current POS state
     */
    public void refuseAgeCheck(PosState state) {
        LOGGER.info("Entering method refuseAgeCheck with state: " + state);
        PosState.AgeCheckState pending = state.ageCheck;
        if (!pending.active) { LOGGER.info("Exiting method refuseAgeCheck"); return; }
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.AGE_CHECK_REFUSED,
                pending.productLabel + " (" + pending.threshold + "+) - vente refusée par "
                        + state.auth.operatorName);
        pending.clear();
        state.ticket.setError(i18n.get("op.venteRefuseeControleAge"));
        state.touch();
        LOGGER.info("Exiting method refuseAgeCheck");
    }

    /**
     * Processes a code the cashier KEYED rather than scanned (BO-06-04-05):
     * the same chain as a scan — typing is scanning — the articles it rings
     * being left unscanned, that is keyed.
     *
     * @param code the keyed code
     */
    public void processKeyed(String code) {
        LOGGER.info("Entering method processKeyed");
        state.keyedEntry = true;
        try {
            processScan(code);
        } finally {
            state.keyedEntry = false;
        }
        LOGGER.info("Exiting method processKeyed");
    }

    /**
     * Lists the identifiers of the lines of the cart.
     *
     * @return the line uids
     */
    private java.util.Set<String> uids() {
        java.util.Set<String> uids = new java.util.HashSet<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            uids.add(item.uid);
        }
        return uids;
    }

    /**
     * Processes a scanned code by running it through the ordered scan handler chain.
     *
     * @param code the scanned code
     */
    public void processScan(String code) {
        LOGGER.info("Entering method processScan with code: " + code);
        // BO-03-06-01: the symbology the scanner announced is checked against the
        // ones the shop accepts, and taken off before the code is read.
        com.intermarche.pos.ui.scanner.Symbology.Reading reading = com.intermarche.pos.ui.scanner.Symbology.read(code);
        if (!com.intermarche.pos.ui.scanner.Symbology.accepted(reading, posSettingsService.scanSymbologies())) {
            state.ticket.setError(i18n.format("op.symbologieRefusee", reading.symbology() == null
                    ? code.substring(0, 3) : reading.symbology().name()));
            state.touch();
            LOGGER.info("Exiting method processScan");
            return;
        }
        code = reading.payload();
        if (articleInfoScanState != null && articleInfoScanState.capture(code)) {
            return;
        }
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        state.selectedTicketIndex = -1;

        // Phase 2 session gate: sale scans are refused while no session is
        // open (badge scans on the lock screen are unaffected).
        if (!state.isLocked() && !requireOpenSession(state)) {
            LOGGER.info("Exiting method processScan");
            return;
        }

        // BO-04-03-08: the administered alerts on barcodes and on the offline register.
        if (!state.isLocked() && controlGateService != null
                && controlGateService.onScan(code, refPullService != null && refPullService.isNodeUnreachable())
                        == com.intermarche.pos.ui.control.ControlGateService.Outcome.STOP) {
            LOGGER.info("Exiting method processScan");
            return;
        }
        // BO-03-06-45: a code read while the register has lost the store node is
        // announced, or refused, as the back office chose.
        if (!state.isLocked() && refPullService != null && refPullService.isNodeUnreachable()) {
            com.intermarche.pos.domain.barcode.AlertLevel offline =
                    com.intermarche.pos.domain.barcode.AlertLevel.of(posSettingsService.scanOfflineAlert());
            if (offline.blocks()) {
                state.ticket.setError(i18n.get("op.lectureHorsLigneRefusee"));
                state.touch();
                LOGGER.info("Exiting method processScan");
                return;
            }
            if (offline.speaks()) {
                state.ticket.setWarning(i18n.get("op.lectureHorsLigne"));
            }
        }

        // EXP-36: the cancel-by-scan mode intercepts BEFORE the add chain — the
        // scanned code designates the line to cancel, it never rings a new one.
        // Handled at the single entry rather than as a chain handler, so it runs
        // ahead of every add-handler without disturbing their disjoint priorities.
        if (!state.isLocked() && state.cancelByScanArmed) {
            homeService.cancelByScan(code);
            if (!state.ticket.items.isEmpty() || state.payment.ticketDbId != null) {
                syncAndRevalue(state);
            }
            LOGGER.info("Exiting method processScan");
            return;
        }

        // LC-05-02-11: on the RETURN screen a scanned article designates the ORIGIN
        // line to take back, never a new sale line. Intercepted at the single
        // entry, like the cancel-by-scan mode above, so it runs ahead of every add
        // handler without disturbing their disjoint priorities. The gate is the
        // ticket selection itself, which the return screen drops when it is left.
        // A PENDING ENDORSEMENT takes precedence (BO-10-02): a refund awaiting a
        // manager's aval shows the endorsement modal over the return screen, and
        // the manager's badge must reach the badge handler to prefill it, not be
        // swallowed as an origin-line scan.
        if (!state.isLocked() && state.refund.isTicketSelected() && !state.endorsement.active) {
            refundService.enterArticleByCode(state, code);
            LOGGER.info("Exiting method processScan");
            return;
        }

        // BO-10-01-17: after a self-scanning basket whose rule forbids it, a
        // scanned article is refused before any handler can ring it.
        if (channelGate.refusesScannedArticle(state, code)) {
            LOGGER.info("Exiting method processScan");
            return;
        }
        ScanContext ctx = new ScanContext(code, state);
        List<ScanContext.ScanHandler> handlers = StreamSupport.stream(scanHandlers.spliterator(), false)
                .sorted(Comparator.comparing(this::getPriority)).toList();
        // BO-04-01-14 / BO-06-04-05: a line the scan chain created was rung by a
        // scan (« article scanné »), unless its code was KEYED through
        // processKeyed: such a line stays unscanned, a keyed article (« saisi »).
        java.util.Set<String> before = state.keyedEntry ? null : uids();
        handlers.stream().forEach(handler -> handler.handle(ctx));
        if (before != null) {
            for (TicketState.TicketItem item : state.ticket.items) {
                if (!before.contains(item.uid)) {
                    item.scanned = true;
                }
            }
        }


        // Lot 2: single sync point for every scan-driven mutation (EAN/PLU
        // handlers, fidelity card, vouchers). No-op cost only when locked or
        // before the first article.
        if (!state.isLocked() && (!state.ticket.items.isEmpty() || state.payment.ticketDbId != null)) {
            syncAndRevalue(state);
        }
        LOGGER.info("Exiting method processScan");
    }

    /**
     * Processes a weight reported by the scale.
     *
     * @param weightStr the weight as text (comma or dot separator)
     */
    public void processWeight(String weightStr) {
        LOGGER.info("Entering method processWeight with weightStr: " + weightStr);
        if (state.isLocked()) { LOGGER.info("Exiting method processWeight"); return; }
        try {
            double w = Double.parseDouble(weightStr.replace(',', '.'));
            state.ticket.setWeight(w);
        } catch (NumberFormatException e) { System.err.println("Poids invalide: " + weightStr); }
        LOGGER.info("Exiting method processWeight");
    }

    /**
     * Resolves the {@link Priority} value of a scan handler, unwrapping CDI proxies.
     *
     * @param handler the scan handler
     * @return the priority value, or 100 when the annotation is absent
     */
    private int getPriority(ScanContext.ScanHandler handler) {
        Class<?> effectiveClass = handler.getClass();
        if (effectiveClass.getName().contains("$Proxy") || effectiveClass.getName().contains("_ClientProxy")) {
            effectiveClass = effectiveClass.getSuperclass();
        }
        Priority priority = effectiveClass.getAnnotation(Priority.class);
        return (priority != null) ? priority.value() : 100;
    }

    /**
     * Verifies that a cash session is open on this register before a sale
     * mutation; shows an error to the cashier otherwise.
     *
     * @param state the current POS state
     * @return true when a session is open, false otherwise (mutation refused)
     */
    private boolean requireOpenSession(PosState state) {
        if (state.trainingMode) return true; // training sells without a session
        if (cashSessionService.getOpenSession() != null) return true;
        state.ticket.setError(i18n.get("op.aucuneSessionOuverteMenu"));
        return false;
    }

    // --- CUSTOMER DISPLAY LOGIC ---

    /**
     * Shows a ticket line on the customer display, or the welcome message.
     *
     * @param item the line to show, or null for the welcome message
     */
    private void displayItem(TicketState.TicketItem item) {
        if (item == null) {
            hardwareService.displayMessage(i18n.get("paper.intermarche"));
            return;
        }
        String label = item.label;
        BigDecimal price = item.getTotalPrice();
        String msg = String.format("%s\t%.2fE", label, price);
        hardwareService.displayMessage(msg);
    }

    /**
     * Shows the last ticket line on the customer display, or the welcome
     * message when the ticket is empty.
     *
     * @param state the current POS state
     */
    private void displayLastItemOrWelcome(PosState state) {
        if (state.ticket.items.isEmpty()) {
            hardwareService.displayMessage(i18n.get("paper.intermarche"));
        } else {
            displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        }
    }

    // --- PRICE MODIFICATIONS ---

    /**
     * Applies an absolute discount (euros) on the line total.
     *
     * @param item the target line
     * @param amount the discount amount in euros (strictly positive)
     */
    public void applyRemise(TicketState.TicketItem item, BigDecimal amount) {
        applyRemise(item, amount, posSettingsService.discountCumulativeOnDiscounted());
    }

    /**
     * Applies an amount remise with the cumulation rule DECIDED BY THE CALLER:
     * a ready-made key that carries CUMUL applies on an already-reduced line
     * whatever the default says (BO-03-07-11), while a manual gesture follows
     * the administered default.
     *
     * @param item the target line
     * @param amount the unit amount of the remise (strictly positive)
     * @param cumulates whether the remise may pile on an already-reduced line
     */
    void applyRemise(TicketState.TicketItem item, BigDecimal amount, boolean cumulates) {
        LOGGER.info("Entering method applyRemise with item: " + item + ", amount: " + amount);
        if (item == null || amount == null || amount.signum() <= 0) { LOGGER.info("Exiting method applyRemise"); return; }
        if (item.moneyProduct) { LOGGER.info("Exiting method applyRemise"); return; } // money products are never discounted
        if (item.discountForbidden) { LOGGER.info("Exiting method applyRemise"); return; } // BO-02-03-09: article bans discounts
        // BO-03-07-11: same rule as the percentage gesture — a second reduction
        // on an already-reduced line only lands where the caller allows it.
        if (alreadyReduced(item) && !cumulates) {
            LOGGER.info("Exiting method applyRemise");
            return;
        }
        // P8: the entered amount is a UNIT remise; its effect on the line is that
        // amount times the quantity (10 articles à 10 €, remise 2 € → 80 €, not 98 €),
        // and a same article scanned afterwards rings at full price. The line
        // discount is therefore amount × quantity throughout.
        BigDecimal lineDiscount = amount.multiply(item.quantity);
        // The euro ceiling of a LINE remise is administered (BO-03-07-04);
        // zero disables it. The ceiling caps the LINE discount, at it passes.
        BigDecimal amountCap = posSettingsService.lineMaxDiscountAmount();
        if (amountCap.signum() > 0 && lineDiscount.compareTo(amountCap) > 0) {
            LOGGER.info("Exiting method applyRemise");
            return;
        }
        // LC-03-02-12: an AMOUNT gesture is bounded by the administered
        // PERCENTAGE ceiling too — a rabais whose implied share of the line
        // exceeds the ceiling is the same theft of margin as a keyed percentage
        // above it, and it is refused here exactly like the euro cap above.
        // The quantity cancels out (both figures scale with it), so the check
        // reads the unit figures; a line priced at zero carries no percentage
        // and stays outside it. Silent like every line-level ceiling: the
        // ceiling that names the gesture is the GLOBAL one.
        BigDecimal currentTotal = item.unitPrice.multiply(item.quantity);
        if (currentTotal.signum() > 0) {
            int percentCap = Math.min(100, posSettingsService.lineMaxDiscountPercent());
            BigDecimal impliedPercent = lineDiscount.multiply(BigDecimal.valueOf(100))
                    .divide(currentTotal, PRICE_SCALE, RoundingMode.HALF_UP);
            if (impliedPercent.compareTo(BigDecimal.valueOf(percentCap)) > 0) {
                LOGGER.info("Exiting method applyRemise");
                return;
            }
        }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal newTotal = currentTotal.subtract(lineDiscount);
        if (newTotal.signum() < 0) newTotal = BigDecimal.ZERO;
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotal.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotal;
        }
        item.modifierLabel = i18n.format("ticket.rs21-remise", amount);
        item.modifierType = PriceModType.REMISE;
        item.modifierValue = amount;
        tracePriceModification(item, currentTotal.subtract(newTotal));
        displayItem(item);
        LOGGER.info("Exiting method applyRemise");
    }

    /**
     * Whether a line already carries a reduction (BO-03-07-11 and -12): a
     * manual gesture, or an automatic promotion the engine valued it below its
     * own price.
     *
     * <p>The two cases the questionnaire names, and the only two the line can
     * answer for: {@code modifierType} is the manual gesture, and a valued
     * total under the local one is the engine's offer.
     *
     * @param item the line, never null
     * @return true when the line is already reduced
     */
    private boolean alreadyReduced(TicketState.TicketItem item) {
        if (item.modifierType != null) {
            return true;
        }
        if (item.valuedTotal == null || item.unitPrice == null || item.quantity == null) {
            return false;
        }
        return item.valuedTotal.compareTo(item.unitPrice.multiply(item.quantity)) < 0;
    }

    /**
     * Whether a line already carries a reduction, read from OUTSIDE the
     * service (LC-03-02-10): the gesture screens compose it with the
     * administered cumulation rule to NAME the refusal — the questionnaire
     * asks for an error message, and the refusal itself stays decided here so
     * the two can never disagree.
     *
     * @param item the line, possibly null
     * @return true when the line carries a manual reduction or an engine offer
     */
    public boolean isAlreadyReduced(TicketState.TicketItem item) {
        LOGGER.info("Entering method isAlreadyReduced with item: " + item);
        boolean reduced = item != null && alreadyReduced(item);
        LOGGER.info("Exiting method isAlreadyReduced");
        return reduced;
    }

    /**
     * Applies the ready-made discount key the back office administers
     * (BO-03-07-03 and BO-03-07-06).
     *
     * <p>It decides NOTHING about the arithmetic: a PERCENT key runs
     * {@link #applyDiscount}, an AMOUNT key runs {@link #applyRemise}, so a
     * ready-made key and a keyed figure are capped, journaled and printed
     * exactly the same way. What the key adds is the figure — spared from the
     * numpad — and its own cumulation rule (BO-03-07-11): a key carrying
     * {@code CUMUL} applies on an already-reduced line whatever the default
     * says, and a key that does not carry it is refused there even where the
     * default allows it.
     *
     * @param item the target line, possibly null
     * @param label the label of the key the operator tapped, possibly null
     * @return true when the key was found and applied
     */
    public boolean applyPreset(TicketState.TicketItem item, String label) {
        LOGGER.info("Entering method applyPreset with label: " + label);
        com.intermarche.pos.service.PosSettingsService.DiscountPreset preset =
                posSettingsService.discountPreset(label);
        if (item == null || preset == null) {
            LOGGER.info("Exiting method applyPreset");
            return false;
        }
        LOGGER.info("Exiting method applyPreset");
        return applyPresetValue(item, preset.percent(), preset.value(), preset.cumulative());
    }

    /**
     * Applies a ready-made discount key described by its figures rather than
     * by its label — the key of an administered list (BO-03-07-03/-06/-09),
     * under the very rules {@link #applyPreset} states.
     *
     * @param item the target line, never null
     * @param percent true for a percentage, false for an amount in euros
     * @param value the figure of the key
     * @param cumulative whether the key may pile on an already-reduced line
     * @return true when the key was applied
     */
    public boolean applyPresetValue(TicketState.TicketItem item, boolean percent,
                                    BigDecimal value, boolean cumulative) {
        if (alreadyReduced(item) && !cumulative) {
            return false;
        }
        if (percent) {
            applyDiscount(item, value, cumulative);
        } else {
            applyRemise(item, value, cumulative);
        }
        return true;
    }

    /**
     * Journals a price modification the moment it lands on a line
     * (BO-04-03-07): the real-time watch of the back office shows the remise,
     * the discount or the forced price as the cashier makes it, not when the
     * ticket is closed.
     *
     * @param item the modified line, its modifier already set
     * @param amount the reduction given, or the forced line total
     */
    private void tracePriceModification(TicketState.TicketItem item, BigDecimal amount) {
        if (technicalEventService == null) {
            return;
        }
        journalOnTicket(state, technicalEventService.record(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.PRICE_MODIFIED,
                item.label + " " + item.modifierType.name(), state == null ? null : state.auth.operatorBadgeId,
                draftNumber(state), amount.setScale(2, RoundingMode.HALF_UP), item.modifierType.name()));
    }

    /**
     * The number of the sale's draft, which names the ticket the journal events
     * of its lines belong to (BO-04-01-34), or null before the draft exists.
     *
     * @param posState the register state, possibly null
     * @return the draft's number, or null
     */
    private static String draftNumber(PosState posState) {
        if (posState == null || posState.payment.ticketDbId == null) {
            return null;
        }
        Ticket draft = Ticket.findById(posState.payment.ticketDbId);
        return draft == null ? null : draft.ticketNumber;
    }

    /**
     * Keeps a journal event recorded before the draft existed, so the draft
     * names it once created (BO-04-01-34).
     *
     * @param posState the register state, possibly null
     * @param event the event recorded, possibly null
     */
    private static void journalOnTicket(PosState posState, com.intermarche.pos.domain.session.TechnicalEvent event) {
        if (posState != null) {
            posState.ticket.awaitTicket(event);
        }
    }

    /**
     * Applies a percentage discount on the unit price.
     *
     * @param item the target line
     * @param percent the discount percentage (0 exclusive to 100 inclusive)
     */
    public void applyDiscount(TicketState.TicketItem item, BigDecimal percent) {
        applyDiscount(item, percent, posSettingsService.discountCumulativeOnDiscounted());
    }

    /**
     * Applies a percentage discount on the unit price with the cumulation rule
     * DECIDED BY THE CALLER: a ready-made key that carries CUMUL applies on
     * an already-reduced line whatever the default says (BO-03-07-11), while
     * a manual gesture follows the administered default.
     *
     * @param item the target line
     * @param percent the discount percentage (0 exclusive to 100 inclusive)
     * @param cumulates whether the discount may pile on an already-reduced line
     */
    void applyDiscount(TicketState.TicketItem item, BigDecimal percent, boolean cumulates) {
        LOGGER.info("Entering method applyDiscount with item: " + item + ", percent: " + percent);
        if (item != null && item.moneyProduct) { LOGGER.info("Exiting method applyDiscount"); return; } // money products are never discounted
        if (item != null && item.discountForbidden) { LOGGER.info("Exiting method applyDiscount"); return; } // BO-02-03-09: article bans discounts
        // BO-03-07-11: the line may already carry a reduction — a manual one or
        // an automatic promotion. Whether a second one piles on top is
        // administered, and a ready-made key may override the default (see
        // applyPreset). Refused here rather than silently stacked: a margin
        // eaten twice is the defect the questionnaire names.
        if (item != null && alreadyReduced(item) && !cumulates) {
            LOGGER.info("Exiting method applyDiscount");
            return;
        }
        // The line cap is administered (LC-03-02-07); 100 stays absolute.
        int lineCap = Math.min(100, posSettingsService.lineMaxDiscountPercent());
        if (item == null || percent == null || percent.signum() <= 0
                || percent.compareTo(BigDecimal.valueOf(lineCap)) > 0) {
            LOGGER.info("Exiting method applyDiscount");
            return;
        }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal reduction = item.unitPrice.multiply(percent)
                .divide(BigDecimal.valueOf(100), PRICE_SCALE, RoundingMode.HALF_UP);
        item.unitPrice = item.unitPrice.subtract(reduction);
        item.modifierLabel = i18n.format("ticket.rs21-discount", percent);
        item.modifierType = PriceModType.DISCOUNT;
        item.modifierValue = percent;
        tracePriceModification(item, reduction.multiply(item.quantity));
        displayItem(item);
        LOGGER.info("Exiting method applyDiscount");
    }

    /**
     * Forces the line total to a new price.
     *
     * @param item the target line
     * @param newTotalPrice the new line total (zero or positive)
     */
    public void forcePrice(TicketState.TicketItem item, BigDecimal newTotalPrice) {
        LOGGER.info("Entering method forcePrice with item: " + item + ", newTotalPrice: " + newTotalPrice);
        if (item != null && item.moneyProduct) { LOGGER.info("Exiting method forcePrice"); return; } // money products are never discounted
        if (item == null || newTotalPrice == null || newTotalPrice.signum() < 0) { LOGGER.info("Exiting method forcePrice"); return; }
        if (item.originalUnitPrice.signum() == 0 || item.originalUnitPrice.compareTo(item.unitPrice) == 0) {
            item.originalUnitPrice = item.unitPrice;
        }
        BigDecimal oldTotalPrice = item.originalUnitPrice.multiply(item.quantity);
        if (item.quantity.signum() != 0) {
            item.unitPrice = newTotalPrice.divide(item.quantity, PRICE_SCALE, RoundingMode.HALF_UP);
        } else {
            item.unitPrice = newTotalPrice;
        }
        // BO-10-07-12: the original price is recalled on screen only when the
        // back office administers it; masked, the line carries no "Prix initial".
        item.modifierLabel = posSettingsService.priceShowOriginalOnForce()
                ? i18n.format("ticket.rs21-prixInitial", oldTotalPrice) : null;
        item.modifierType = PriceModType.FORCE_PRICE;
        item.modifierValue = newTotalPrice;
        tracePriceModification(item, newTotalPrice);
        displayItem(item);
        LOGGER.info("Exiting method forcePrice");
    }

    /**
     * Recomputes the ticket total (fiscal rule: sum of line totals rounded to
     * the cent) and synchronizes the draft (covers line cancellations and
     * endorsed price modifications).
     *
     * @param state the current POS state
     */
    public void recalculateTotal(PosState state) {
        LOGGER.info("Entering method recalculateTotal with state: " + state);
        state.ticket.recomputeTotal();
        state.ticket.onChange();
        applyTicketTotalControl(state);
        syncAndRevalue(state);
        LOGGER.info("Exiting method recalculateTotal");
    }

    /**
     * Applies the TICKET TOTAL control point (BO-10-02-07).
     *
     * <p>Said after the total is recomputed and not before an article is
     * registered, because the figure the shop administers is a TOTAL: it is
     * only known once the basket is summed. The articles stay in the basket
     * whatever the level — a line already scanned is a fact — and what changes
     * is what the register says about it: nothing, a warning, or a refusal
     * naming the ceiling. The point is not endorsable: see {@link
     * com.intermarche.pos.ui.endorsement.ControlPoint#isEndorsable()}.
     *
     * <p>A shop administering no ceiling never reaches the level at all, which
     * is the default and the register's historical silence.
     *
     * @param state the current POS state
     */
    private void applyTicketTotalControl(PosState state) {
        java.math.BigDecimal ceiling = posSettingsService.controlTicketTotalMax();
        if (ceiling == null || state.ticket.totalAmount == null
                || state.ticket.totalAmount.compareTo(ceiling) <= 0) {
            return;
        }
        com.intermarche.pos.ui.endorsement.ControlLevel level =
                endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL);
        if (level == com.intermarche.pos.ui.endorsement.ControlLevel.WARN) {
            state.ticket.setWarning(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL, level));
        } else if (!level.allows()) {
            state.ticket.setError(endorsementService.controlMessage(
                    com.intermarche.pos.ui.endorsement.ControlPoint.TICKET_TOTAL, level));
        }
    }

    // --- STANDARD ACTIONS ---

    /**
     * Adds a product to the ticket by its EAN code.
     *
     * @param state the current POS state
     * @param ean the EAN code
     * @param quantity the quantity to add
     */
    /**
     * Whether this register REFUSES an article because a settlement is under
     * way, saying so to the cashier (BO-10-02-15).
     *
     * <p>The rule is one and the same wherever an article comes in by — scan,
     * keyed code, PLU — which is why it lives here and not in each handler.
     * The shop that allows the gesture gets it unchanged; the shop that does
     * not gets a refusal and a sentence, because a basket that grows while the
     * total is being settled is a total that no longer holds.
     *
     * @param state the register state
     * @return true when the article must not be rung, the message already set
     */
    public boolean saleRefusedDuringPayment(PosState state) {
        LOGGER.info("Entering method saleRefusedDuringPayment");
        if (posSettingsService.saleAllowedDuringPayment() || !state.payment.paymentInProgress) {
            LOGGER.info("Exiting method saleRefusedDuringPayment");
            return false;
        }
        state.ticket.setError(i18n.get("op.venteInterditePendantReglement"));
        LOGGER.info("Exiting method saleRefusedDuringPayment");
        return true;
    }

    public void addItemByEan(PosState state, String ean, BigDecimal quantity) {
        LOGGER.info("Entering method addItemByEan with state: " + state + ", ean: " + ean + ", quantity: " + quantity);
        if (ean == null || ean.isEmpty()) { LOGGER.info("Exiting method addItemByEan"); return; }
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addItemByEan"); return; }
        if (saleRefusedDuringPayment(state)) { LOGGER.info("Exiting method addItemByEan"); return; }
        if (channelGate.refusesAddition(state)) { LOGGER.info("Exiting method addItemByEan"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.find("ean = ?1 and active = true", ean).firstResult();
        if (p == null) {
            // BO-02-03-03/04: keying an additional sale code or the internal code
            // reaches the same article as its EAN would.
            p = Product.findActiveByAlias(ean);
        }
        if (p != null) {
            if (ArticleMessageService.refuse(state, p)) return;
            if (p.forbiddenToSale) {
                state.ticket.setError(ForbiddenSaleMessage.forbidden(i18n, posSettingsService, p));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // BO-02-03-24: an article that must be weighed goes to the scale.
            if (routeWeighed(state, p)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // BO-03-01-18: an article the store took out of THIS island or
            // THIS register is refused here and nowhere else — the same
            // decision whatever road the article came in by.
            if (articleScopeService != null && !articleScopeService.soldHere(p)) {
                state.ticket.setError(i18n.get("op.articleNonVenduSurCetteCaisse"));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }

            if (ProductAttributes.recall(p)) {
                state.ticket.setError(ForbiddenSaleMessage.recall(i18n, posSettingsService, p));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // LC-02-13-03/04: a decimal quantity armed before a unit article is
            // refused — keyed EAN, keyed code and touch tiles alike.
            if (state.ticket.armedDecimalRefused(ProductAttributes.decimalQuantityToEnter(p))) {
                state.ticket.setError(i18n.get("op.quantiteInvalide"));
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            if (suspendForAgeCheck(state, p, "EAN_QTY", p.ean, quantity)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // BO-04-03-08: the administered alerts on this article or its family.
            if (articleAlertStops(p, quantity, com.intermarche.pos.ui.control.ControlGateService.Kind.EAN, p.ean)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            // LC-02-03-01/03 and LC-02-13-08: the quantity keyed or armed is carried
            // into the prompt, so an article that also wants its price is rung at the
            // quantity the operator gave and not at one.
            if (suspendForEntry(state, p, quantity)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            Price price = Price.findCurrentPrice(p.id);
            if (priceCallRefused(state, p, price)) {
                LOGGER.info("Exiting method addItemByEan");
                return;
            }
            BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            // BO-02-03-26/27: a VAT-exempt article is ventilated at rate 0 under
            // its own heading; the amount due (tax included) is unchanged.
            com.intermarche.pos.domain.catalog.ArticleVat.Resolution vat =
                    com.intermarche.pos.domain.catalog.ArticleVat.resolve(p, price, defaultVatRate);
            BigDecimal vatRate = vat.rate();
            // PLU is null: unit sale. Line label is the checkout label when set.
            state.ticket.addItem(p.ean, null, translatedLabel(p), finalPrice, quantity, vatRate);
            TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
            if (p.giftCardAmount != null) {
                line.moneyProduct = true;
            }
            if (ProductAttributes.discountForbidden(p)) {
                line.discountForbidden = true;
            }
            state.ticket.snapshotMealVoucherEligibility(p.ean, ProductAttributes.mealVoucherEligible(p));
            LinkedArticleService.snapshot(state.ticket, p);
            // LC-09-01-11 to -18: what the article may be paid with — by article
            // (LC-10-01-09/11), since a repeated entry may merge into an earlier line.
            state.ticket.snapshotRestrictedTenders(p.ean,
                    com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(p));
            stampArticle(state, p, vat);
            displayItem(line);
            syncAndRevalue(state);
            // LC-02-03-13: a keyed EAN or internal code names no lot, so the recalled
            // lots are listed and the cashier reads the one printed on the pack.
            noticeRecalledLots(state, p);
            // LC-02-09-01: a keyed dematerialised article asks for its code too.
            awaitActivationCode(state, line, p);
        } else {
            state.ticket.setError(i18n.get("op.produitIntrouvable"));
        }
        LOGGER.info("Exiting method addItemByEan");
    }

    /**
     * Applies the administered alerts on an article and its family
     * (BO-04-03-08) before it is rung.
     *
     * @param product the article
     * @param quantity the quantity keyed, possibly null
     * @param kind how the article is replayed: EAN or PLU
     * @param code the EAN or the PLU replayed
     * @return true when an alert refused or held it
     */
    private boolean articleAlertStops(Product product, BigDecimal quantity,
                                      com.intermarche.pos.ui.control.ControlGateService.Kind kind, String code) {
        return controlGateService != null && controlGateService.onArticle(product, quantity, kind, code)
                == com.intermarche.pos.ui.control.ControlGateService.Outcome.STOP;
    }

    /**
     * Lists the recalled lots of an article whose entry named none ({@code
     * LC-02-03-13}).
     *
     * <p>It is raised AFTER the line is registered, because registering clears the
     * message area — a notice set before would be wiped by the very gesture it is
     * about.
     *
     * @param state   the register state
     * @param product the article that was just registered
     */
    private void noticeRecalledLots(PosState state, Product product) {
        String lots = ProductAttributes.recalledLotsJoined(product);
        if (lots != null) {
            state.ticket.setNotice(i18n.format("op.produitRappelLots", lots));
        }
    }

    /**
     * Adds a weighed product to the ticket by its PLU code, requesting a weighing.
     *
     * @param state the current POS state
     * @param pluCode the PLU code
     */
    public void addItemByPlu(PosState state, String pluCode) {
        LOGGER.info("Entering method addItemByPlu with state: " + state + ", pluCode: " + pluCode);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addItemByPlu"); return; }
        if (saleRefusedDuringPayment(state)) { LOGGER.info("Exiting method addItemByPlu"); return; }
        if (channelGate.refusesAddition(state)) { LOGGER.info("Exiting method addItemByPlu"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        Product p = Product.findActiveByPlu(pluCode);
        if (p != null) {
            if (ArticleMessageService.refuse(state, p)) return;
            if (p.forbiddenToSale) {
                state.ticket.setError(ForbiddenSaleMessage.forbidden(i18n, posSettingsService, p));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // BO-03-01-18: an article the store took out of THIS island or
            // THIS register is refused here and nowhere else — the same
            // decision whatever road the article came in by.
            if (articleScopeService != null && !articleScopeService.soldHere(p)) {
                state.ticket.setError(i18n.get("op.articleNonVenduSurCetteCaisse"));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }

            if (ProductAttributes.recall(p)) {
                state.ticket.setError(ForbiddenSaleMessage.recall(i18n, posSettingsService, p));
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // Age gate BEFORE weighing: the scale read is consumed on the
            // replay only, so a confirmed check weighs exactly once.
            if (suspendForAgeCheck(state, p, "PLU", pluCode, null)) {
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // BO-04-03-08: an administered alert on the article decides before
            // the weighing; its replay keys the same PLU again.
            if (articleAlertStops(p, null, com.intermarche.pos.ui.control.ControlGateService.Kind.PLU, pluCode)) {
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }
            // LC-14-01-03/04/05/06/07: resolve the software tare (container >
            // article > default) and SEND it to the scale. The scale nets it
            // and returns the net weight, which is recorded as-is below — the
            // register never re-deducts (non-cumulation). The punctual
            // container tare is consumed straight away: it arms one weighing.
            BigDecimal tare = tareService.resolve(p, state);
            // LC-02-02-03: a GROSS weight keyed BEFORE the article replaces the
            // weighing. The arming is read here and not earlier so it survives
            // the age-check parking exactly as the scale read does, and it is
            // consumed whatever happens next — a refused weight must never land
            // on the article that follows.
            BigDecimal typedGross = state.pendingManualWeightKg;
            state.pendingManualWeightKg = null;
            double weight;
            if (typedGross != null) {
                // LC-14-01-08: no scale is in the loop, so the register deducts
                // the tare it would otherwise have SENT to the scale — the same
                // one, resolved by the same priority (container, article,
                // administered default). The line therefore carries a NET weight,
                // exactly as a scale would have returned it, and the duplicate-
                // weighing guard does not apply: it watches for a plate read
                // twice, whereas two identical punnets keyed by hand are a
                // legitimate repetition.
                BigDecimal net = typedGross.subtract(tare);
                tareService.consume(state);
                if (net.signum() <= 0) {
                    state.ticket.setError(i18n.get("op.poidsNetInvalide"));
                    LOGGER.info("Exiting method addItemByPlu");
                    return;
                }
                weight = net.doubleValue();
            } else {
                // The article is chosen, the plate may still be empty: wait for it to
                // be loaded instead of refusing the weighing outright. Choosing then
                // laying down is the order the screen asks for, and it did not work.
                weight = hardwareService.awaitWeighing(tare);
                tareService.consume(state);
                if (weight <= 0) { state.ticket.setError(i18n.get("op.poidsInvalide")); LOGGER.info("Exiting method addItemByPlu"); return; }
                if (!Double.isNaN(state.ticket.lastRecordedWeight) && Double.compare(weight, state.ticket.lastRecordedWeight) == 0) { state.ticket.setError(i18n.get("op.erreurPoidsIdentique")); LOGGER.info("Exiting method addItemByPlu"); return; }
            }

            Price price = Price.findCurrentPrice(p.id);
            BigDecimal unitPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            // BO-02-03-26/27: a VAT-exempt weighed article ventilates at rate 0.
            com.intermarche.pos.domain.catalog.ArticleVat.Resolution vat =
                    com.intermarche.pos.domain.catalog.ArticleVat.resolve(p, price, defaultVatRate);
            BigDecimal vatRate = vat.rate();
            BigDecimal quantityKg = BigDecimal.valueOf(weight).setScale(3, RoundingMode.HALF_UP);

            if (returnPriceConfirmed(state)) {
                // LC-05-02-02: keep the net weight and tare until the price is confirmed.
                state.entryPrompt.clear();
                state.entryPrompt.active = true;
                state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
                state.entryPrompt.ean = p.ean;
                state.entryPrompt.plu = pluCode;
                state.entryPrompt.appliedTareKg = tare;
                state.entryPrompt.label = translatedLabel(p);
                // BO-10-08-29: the administered weight symbol, as on every screen.
                state.entryPrompt.unitName = com.intermarche.pos.domain.catalog.UnitFormat
                        .orDefault(posSettingsService.unitFormat()).weightSymbol();
                state.entryPrompt.unitPriceFormatted = String.format("%.2f", unitPrice).replace('.', ',');
                state.entryPrompt.quantity = quantityKg;
                state.touch();
                LOGGER.info("Exiting method addItemByPlu");
                return;
            }

            state.ticket.lastRecordedWeight = weight;
            // Real PLU code carried by the line; EAN left null. Checkout label
            // when set, else the commercial name.
            state.ticket.addItem(p.ean, pluCode, translatedLabel(p), unitPrice, quantityKg, vatRate);
            TicketState.TicketItem line = state.ticket.items.get(state.ticket.items.size() - 1);
            // LC-14-01-07/08: snapshot the article tare so a later MANUAL weight
            // re-entry can deduct it without a fresh catalog lookup.
            line.tareKg = ProductAttributes.tare(p);
            // BO-10-05-04: and the tare that was actually sent to the scale,
            // which is the one the ticket may state.
            line.appliedTareKg = tare;
            if (ProductAttributes.discountForbidden(p)) {
                line.discountForbidden = true;
            }
            state.ticket.snapshotMealVoucherEligibility(p.ean, ProductAttributes.mealVoucherEligible(p));
            LinkedArticleService.snapshot(state.ticket, p);
            // LC-09-01-11 to -18: what the article may be paid with.
            line.restrictedTenders =
                    com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(p);
            stampArticle(state, p, vat);
            displayItem(line);
            syncAndRevalue(state);
            // LC-02-03-13: a PLU names no lot either.
            noticeRecalledLots(state, p);
        } else {
            state.ticket.setError(i18n.get("op.pluIntrouvable"));
        }
        LOGGER.info("Exiting method addItemByPlu");
    }

    /**
     * Raises the back-office alert for an article the referential does not
     * know ({@code BO-04-02-06}), when the shop administers it.
     *
     * <p>Null-tolerant on both collaborators: a service built by hand in a
     * unit test carries neither, and the sale must go through.
     *
     * @param state the current POS state
     * @param label what the cashier typed for the unknown article
     */
    private void alertUnknownItem(PosState state, String label) {
        if (posSettingsService == null || supervisorSettleService == null
                || !posSettingsService.unknownItemAlertEnabled()) {
            return;
        }
        supervisorSettleService.unknownItem(label,
                state == null ? null : state.getOperatorName());
    }

    /**
     * Adds an unknown (unlisted) item with a typed label and price.
     *
     * @param state the current POS state
     * @param label the label typed by the cashier
     * @param priceStr the price typed by the cashier
     */
    public void addUnknownItem(PosState state, String label, String priceStr) {
        LOGGER.info("Entering method addUnknownItem with state: " + state + ", label: " + label + ", priceStr: " + priceStr);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addUnknownItem"); return; }
        if (channelGate.refusesAddition(state)) { LOGGER.info("Exiting method addUnknownItem"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        // LC-02-13-03/04: an unlisted article is sold by the unit.
        if (state.ticket.armedDecimalRefused(false)) {
            state.ticket.setError(i18n.get("op.quantiteInvalide"));
            LOGGER.info("Exiting method addUnknownItem");
            return;
        }
        try {
            BigDecimal price = new BigDecimal(priceStr.replace(',', '.').replace(" ", ""));
            if (price.signum() >= 0 && label != null && !label.isEmpty()) {
                state.ticket.addItem(unknownItemEan, null, label.toUpperCase(),
                        price, BigDecimal.ONE, defaultVatRate);
                // BO-04-01-14: the price of an unknown article is keyed.
                state.ticket.items.get(state.ticket.items.size() - 1).priceEntered = true;
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
                syncAndRevalue(state);
                // BO-04-02-06: the back office is told, when the shop asked
                // for it, that a lane has just sold something the referential
                // does not know. Fire and forget: the article is already on
                // the ticket.
                alertUnknownItem(state, label);
            }
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
        }
        LOGGER.info("Exiting method addUnknownItem");
    }

    /**
     * Adds an article at a free price under a FAMILY CODE (EXP-24): the cashier
     * keys a family code then a price — "80" then "235" rings an "Epicerie 2,35 €"
     * line — the family carrying the LABEL. The family's VAT rate is not modelled
     * on {@link com.intermarche.pos.domain.catalog.ProductFamily} (it has no VAT
     * column), so the register's default VAT applies — a named assumption of
     * EXP-24 recorded here.
     *
     * @param state the current POS state
     * @param familyCode the family code keyed by the cashier
     * @param priceStr the price keyed by the cashier
     */
    public void addFamilyPricedItem(PosState state, String familyCode, String priceStr) {
        LOGGER.info("Entering method addFamilyPricedItem with familyCode: " + familyCode + ", priceStr: " + priceStr);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addFamilyPricedItem"); return; }
        if (channelGate.refusesAddition(state)) { LOGGER.info("Exiting method addFamilyPricedItem"); return; }
        state.selectedTicketIndex = -1;
        if (state.ticket.transientError != null) state.ticket.transientError = null;
        com.intermarche.pos.domain.catalog.ProductFamily family =
                (familyCode == null || familyCode.isBlank())
                        ? null : com.intermarche.pos.domain.catalog.ProductFamily.findByCode(familyCode.trim());
        if (family == null) {
            state.ticket.setError(i18n.get("op.familleInconnue"));
            state.touch();
            LOGGER.info("Exiting method addFamilyPricedItem");
            return;
        }
        // LC-02-13-03/04: a family sale is a unit line.
        if (state.ticket.armedDecimalRefused(false)) {
            state.ticket.setError(i18n.get("op.quantiteInvalide"));
            LOGGER.info("Exiting method addFamilyPricedItem");
            return;
        }
        try {
            BigDecimal price = new BigDecimal(priceStr.replace(',', '.').replace(" ", ""));
            if (price.signum() >= 0) {
                String label = family.description == null ? family.code : family.description;
                state.ticket.addItem(unknownItemEan, null, label.toUpperCase(),
                        price, BigDecimal.ONE, defaultVatRate);
                // BO-04-01-14: a family sale is a keyed price.
                state.ticket.items.get(state.ticket.items.size() - 1).priceEntered = true;
                displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
                syncAndRevalue(state);
            } else {
                state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
            }
        } catch (Exception e) {
            state.ticket.setError(i18n.get("op.erreurPrixSaisi"));
        }
        LOGGER.info("Exiting method addFamilyPricedItem");
    }

    /**
     * Adds a deposit-return line to the ticket (out of VAT scope: zero rate).
     *
     * @param state the current POS state
     */
    public void addDeposit(PosState state) {
        LOGGER.info("Entering method addDeposit with state: " + state);
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method addDeposit"); return; }
        state.selectedTicketIndex = -1;
        state.ticket.addItem(deconsignmentEan, null, "DECONSIGNATION",
                new BigDecimal("-1.00"), BigDecimal.ONE, BigDecimal.ZERO);
        displayItem(state.ticket.items.get(state.ticket.items.size() - 1));
        syncAndRevalue(state);
        LOGGER.info("Exiting method addDeposit");
    }

    /**
     * Loads a recalled back-office document (EXP-90/91/92) into the current sale:
     * a quote, an order, a sale voucher or a click-and-collect basket, whose lines
     * are added through the SAME {@code addItem} funnel as a scan so the valuation,
     * the subtotal marker and the persisted draft all behave. For an order that
     * carries a down-payment already collected (EXP-85), that acompte is registered
     * as a payment so it is DEDUCTED from the amount due — "repris sur commande".
     *
     * <p>Guards, out loud: no open session refuses as any sale scan does; a null
     * document (unknown code) sets "DOCUMENT INTROUVABLE"; a document with no line
     * sets "DOCUMENT VIDE" and loads nothing.
     *
     * @param state the current POS state
     * @param doc the recalled document, or null when the code resolved to nothing
     */
    public void loadRecalledDocument(PosState state, RecalledDocument doc) {
        LOGGER.info("Entering method loadRecalledDocument");
        if (!requireOpenSession(state)) { LOGGER.info("Exiting method loadRecalledDocument"); return; }
        if (doc == null) {
            state.ticket.setError(i18n.get("op.documentIntrouvable"));
            state.touch();
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        if (doc.lines == null || doc.lines.isEmpty()) {
            state.ticket.setError(i18n.get("op.documentVide"));
            state.touch();
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        // LC-06-02-12: several documents may be settled in one sale, but each
        // only once — a voucher scanned twice would sell its articles twice.
        if (doc.code != null && state.recalledDocumentCodes.contains(doc.code)) {
            state.ticket.setError(i18n.format("op.documentDejaCharge", doc.code));
            state.touch();
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        // LC-02-03-11: preflight the whole document so a refusal cannot leave a partial load.
        for (RecalledDocument.Line line : doc.lines) {
            if (line.ean() != null && ArticleMessageService.refuse(state,
                    com.intermarche.pos.domain.catalog.Product.find("ean", line.ean()).firstResult())) return;
        }
        // BO-10-01: the rules of the channel the basket comes from — refused out
        // loud, or prepared the way the channel wants it loaded.
        doc = channelGate.admit(state, doc);
        if (doc == null) {
            LOGGER.info("Exiting method loadRecalledDocument");
            return;
        }
        state.selectedTicketIndex = -1;
        for (RecalledDocument.Line line : pricedLines(doc)) {
            state.ticket.addItem(line.ean(), null, line.label(),
                    line.unitPrice(), line.soldQuantity(), line.vatRate());
            // LC-06-02-02 / -12: a document stating what was ordered AND what was
            // delivered sells the delivered quantity and keeps the ordered one on
            // the line, shown and printed beside it.
            if (line.deliveredQuantity() != null) {
                recordOrderedQuantity(state, line);
            }
            if (line.ean() != null) LinkedArticleService.snapshot(state.ticket,
                    com.intermarche.pos.domain.catalog.Product.find("ean", line.ean()).firstResult());
        }
        // LC-06-03-07: the ticket remembers the order it came from, so its
        // closing can tell the store node the order was settled.
        state.recalledOrderCode = doc.code != null && !doc.code.isBlank() ? doc.code : null;
        // BO-10-03-17: the order date travels to the promotion engine where administered.
        state.ticket.noteOrderDate(doc.orderDate);
        if (state.recalledOrderCode != null) {
            state.recalledDocumentCodes.add(doc.code);
        }
        PaymentState.PaymentEntry depositEntry = null;
        if (doc.hasAcompte()) {
            // EXP-85: the down-payment already collected on the order is credited
            // as a payment, so the remaining due is the balance to settle now.
            // LC-06-03-07/-08: the credit is a settlement of its own — the
            // "Reprise d'acompte" — and carries the deposit's per-rate VAT and
            // the order code, so the final receipt and the node both restate
            // the ventilation computed at collection time.
            state.payment.addDepositPayment(doc.acompteAlreadyPaid,
                    doc.depositVatDetail, doc.code);
            depositEntry = state.payment.payments.get(state.payment.payments.size() - 1);
        }
        recalculateTotal(state);
        displayLastItemOrWelcome(state);
        state.ticket.setNotice(i18n.format("op.documentCharge",
                i18n.get(doc.getKindKey()), (doc.code == null ? "" : doc.code)));
        syncAndRevalue(state);
        if (depositEntry != null && !state.trainingMode
                && state.payment.ticketDbId != null) {
            // The recovery is persisted like every other settlement, so a
            // restart rebuilds it and the final ticket prints it (LC-06-03-08).
            ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId, depositEntry);
        }
        applyDocumentVouchers(state, doc);
        channelGate.afterLoad(state, doc);
        LOGGER.info("Exiting method loadRecalledDocument");
    }

    /**
     * Applies the reduction vouchers a selfscanning device recorded on its basket
     * (LC-06-05-02): each one becomes a voucher settlement of the sale, bounded by
     * what remains due — a voucher never gives change — and persisted like every
     * other settlement, so the customer only pays the balance.
     *
     * @param state the current POS state
     * @param doc the recalled document
     */
    private void applyDocumentVouchers(PosState state, RecalledDocument doc) {
        if (doc.vouchers == null) {
            return;
        }
        for (RecalledDocument.Voucher voucher : doc.vouchers) {
            if (voucher == null || voucher.amount() == null || voucher.amount().signum() <= 0) {
                continue;
            }
            BigDecimal due = state.ticket.getTotalAmount().subtract(state.payment.paidAmount);
            if (due.signum() <= 0) {
                return;
            }
            state.payment.addVoucherPayment(voucher.label() == null || voucher.label().isBlank()
                    ? i18n.get("selfscan.bonReduction") : voucher.label(), voucher.code(), voucher.amount().min(due));
            if (!state.trainingMode && state.payment.ticketDbId != null) {
                ticketPersistenceService.addPaymentToTicket(state.payment.ticketDbId,
                        state.payment.payments.get(state.payment.payments.size() - 1));
            }
        }
    }

    /**
     * The lines of a recalled document at the price the store administers
     * (LC-06-02-08, -12): the document's own price by default, the register's
     * current price where the store asks for it — an article the register does
     * not know, or knows without a price, keeping the document's. A line carried
     * on the channel's generic EAN for unknown articles (BO-10-01-06, -21) keeps
     * the document's price too: the generic article only carries the line, its
     * own price is not the price of the article the customer bought.
     *
     * @param doc the recalled document
     * @return its lines, priced
     */
    public List<RecalledDocument.Line> pricedLines(RecalledDocument doc) {
        com.intermarche.pos.ui.recall.SalesChannel channel =
                com.intermarche.pos.ui.recall.SalesChannel.of(doc.kind);
        boolean registerPrice = channelRules.priceFromRegister(channel);
        String genericEan = channelRules.unknownItemEan(channel);
        List<RecalledDocument.Line> lines = new java.util.ArrayList<>();
        for (RecalledDocument.Line line : doc.lines) {
            BigDecimal price = line.unitPrice();
            BigDecimal vatRate = line.vatRate();
            boolean carriedUnknown = !genericEan.isEmpty() && genericEan.equals(line.ean());
            Product product = registerPrice && line.ean() != null && !carriedUnknown
                    ? Product.findByEan(line.ean()) : null;
            Price current = product == null ? null : Price.findCurrentPrice(product.id);
            if (current != null) {
                price = current.priceIncludingTax;
                vatRate = current.vatRate() == null ? vatRate : current.vatRate();
            }
            lines.add(new RecalledDocument.Line(line.ean(), line.label(), line.quantity(),
                    price, vatRate, line.deliveredQuantity()));
        }
        return lines;
    }

    /**
     * Keeps the ORDERED quantity of a document line on the sale line it was
     * entered into (LC-06-02-02, -12). A line it merged into was ordered as it
     * was sold, so its ordered quantity starts at what it held before.
     *
     * @param state the current POS state
     * @param line the document line just entered, delivered quantity stated
     */
    private void recordOrderedQuantity(PosState state, RecalledDocument.Line line) {
        for (TicketState.TicketItem item : state.ticket.items) {
            if (item.uid.equals(state.lastEnteredItemId)) {
                BigDecimal ordered = item.orderedQuantity == null
                        ? item.quantity.subtract(line.deliveredQuantity()) : item.orderedQuantity;
                item.orderedQuantity = ordered.add(line.quantity() == null ? BigDecimal.ZERO : line.quantity());
            }
        }
    }

    /**
     * Cancels a ticket line by its uid and refreshes total and display.
     *
     * @param state the current POS state
     * @param uid the uid of the line to cancel
     */
    public void cancelItemById(PosState state, String uid) {
        LOGGER.info("Entering method cancelItemById with state: " + state + ", uid: " + uid);
        if (state.ticket.items.stream().anyMatch(item -> java.util.Objects.equals(item.uid, uid)
                && item.linkedParentUid != null)) {
            state.ticket.setError(i18n.get("op.articleLieGereParPrincipal"));
            return;
        }
        List<String> family = LinkedArticleService.familyUids(state.ticket, uid);
        // LC-02-09-02: an activated dematerialised article is reversed on the hub
        // BEFORE it leaves the cart; a refusal keeps it, and the justification
        // asked for it is dropped with the gesture.
        for (TicketState.TicketItem member : state.ticket.items) {
            if (family.contains(member.uid) && !dematerialisedService.reverse(state, member)) {
                state.ticket.pendingCancelUid = null;
                state.ticket.pendingCancelReasonCode = null;
                state.ticket.pendingCancelReasonLabel = null;
                LOGGER.info("Exiting method cancelItemById (activation kept)");
                return;
            }
        }
        // Conserve the cancelled article (lot C4, BO-04-01-16): mark the draft
        // line BEFORE it leaves the cart, so the reconciliation keeps it as a
        // witness. Training and pre-draft carts have no id — the marking is
        // skipped exactly where nothing fiscal is persisted anyway.
        if (state.payment.ticketDbId != null) {
            // BO-06-06-04: the justification the cashier gave travels with the
            // witness. Read from the state and not from a parameter because the
            // gesture may have been parked for an aval between the two.
            for (String member : family) {
                ticketPersistenceService.markLineCancelled(
                        state.payment.ticketDbId, member, state.auth.operatorBadgeId,
                        state.ticket.pendingCancelReasonCode, state.ticket.pendingCancelReasonLabel);
            }
        }
        state.ticket.pendingCancelUid = null;
        state.ticket.pendingCancelReasonCode = null;
        state.ticket.pendingCancelReasonLabel = null;
        // The line LEAVES THE CART AND STAYS ON THE SCREEN, struck through: what
        // was cancelled is a fact of the sale the operator and the customer must
        // still be able to point at.
        traceLineCancellation(state, uid);
        for (String member : family) state.ticket.cancelLine(member);
        recalculateTotal(state);
        state.selectedTicketIndex = -1;
        displayLastItemOrWelcome(state);
        LOGGER.info("Exiting method cancelItemById");
    }

    /**
     * Journals an article cancellation the moment it is made (BO-04-03-07).
     *
     * @param state the current POS state
     * @param uid the uid of the cancelled line
     */
    private void traceLineCancellation(PosState state, String uid) {
        if (technicalEventService == null) {
            return;
        }
        for (TicketState.TicketItem item : state.ticket.items) {
            if (java.util.Objects.equals(item.uid, uid)) {
                journalOnTicket(state, technicalEventService.record(
                        com.intermarche.pos.domain.session.TechnicalEvent.EventType.LINE_CANCELLED,
                        item.label, state.auth.operatorBadgeId, draftNumber(state),
                        item.getTotalPrice().setScale(2, RoundingMode.HALF_UP), item.ean));
                return;
            }
        }
    }

    /**
     * Cancels the whole ticket: marks the database draft as cancelled, then
     * clears the in-memory state and resets the customer display.
     *
     * @param state the current POS state
     */
    public void cancelTicket(PosState state) {
        LOGGER.info("Entering method cancelTicket with state: " + state);
        // LC-07-11-01: a gift-card payment the hub refused to give back is still
        // money taken; the ticket cannot be abandoned over it.
        if (state.payment.payments.stream().anyMatch(entry -> entry.hubReference != null)) {
            state.ticket.setError(i18n.get("op.abandonCarteCadeauNonRestituee"));
            LOGGER.info("Exiting method cancelTicket (gift card kept)");
            return;
        }
        // LC-02-09-02: every activated dematerialised article is reversed first;
        // a refusal keeps the ticket.
        if (!dematerialisedService.reverseAll(state)) {
            LOGGER.info("Exiting method cancelTicket (activation kept)");
            return;
        }
        // Cancel the draft before clearTicket() nulls its id
        if (state.payment.ticketDbId != null) {
            // LC-04-04-11/12: the abandon ticket states a sale that is about to stop
            // existing, so it is printed BEFORE the draft is cancelled — after it,
            // there would be nothing left to read.
            if (ticketAbandonService.printsTicket()) {
                ticketPrinterService.printAbandonTicket(state.payment.ticketDbId,
                        state.abandonReason, posSettingsService.abandonPrintDetail(),
                        state.auth.operatorName);
            }
            ticketPersistenceService.cancelDraft(state.payment.ticketDbId);
        }
        // LC-04-04-10: the reason is journalled whether or not it was printed — the
        // back-office reports count abandons by reason, and a paper the shop chose
        // not to print must not take the reason with it.
        technicalEventService.log(
                com.intermarche.pos.domain.session.TechnicalEvent.EventType.TICKET_CANCELLED,
                state.abandonReason == null || state.abandonReason.isBlank()
                        ? "Abandon ticket" : "Abandon ticket : " + state.abandonReason);
        state.clearTicket();
        hardwareService.displayMessage(i18n.get("paper.intermarche"));
        LOGGER.info("Exiting method cancelTicket");
    }

    /**
     * Resolves the ticket the DERNIER keys work on: the last sale this register
     * closed.
     *
     * <p>{@code PosState.lastClosedTicketId} only remembers a sale closed by
     * THIS process. It is the right answer while the register runs, and the
     * wrong one the moment it restarts: the ticket is still in the database, the
     * register still printed it a minute ago, and every DERNIER key answered
     * "AUCUN TICKET" — four keys that looked broken because the memory of the
     * sale, not the sale, had gone. The database is asked once and the answer is
     * kept in the state.
     *
     * @param state the current POS state
     * @return the id of the last closed ticket of this register, or null when it
     *         has closed none
     */
    public Long resolveLastClosedTicketId(PosState state) {
        LOGGER.info("Entering method resolveLastClosedTicketId with state: " + state);
        if (state.lastClosedTicketId == null) {
            Ticket recovered = Ticket.findLastClosedByTerminal(ticketNumberService.getTerminalId());
            if (recovered != null) {
                state.lastClosedTicketId = recovered.id;
            }
        }
        LOGGER.info("Exiting method resolveLastClosedTicketId");
        return state.lastClosedTicketId;
    }

    /**
     * Reprints a closed ticket by its database id.
     *
     * @param ticketId the ticket database id
     */
    public void reprintTicket(Long ticketId) {
        LOGGER.info("Entering method reprintTicket with ticketId: " + ticketId);
        if (ticketId != null) {
            ticketPrinterService.printTicket(ticketId);
        }
        LOGGER.info("Exiting method reprintTicket");
    }

    /**
     * Prints the identification barcode of a closed ticket, alone (LC-08-01-04).
     *
     * @param ticketId the ticket database id
     */
    public void printTicketIdentityBarcode(Long ticketId) {
        LOGGER.info("Entering method printTicketIdentityBarcode with ticketId: " + ticketId);
        if (ticketId != null) {
            ticketPrinterService.printTicketIdentityBarcode(ticketId);
        }
        LOGGER.info("Exiting method printTicketIdentityBarcode");
    }

    /**
     * Prints a duplicate of a ticket's card receipt, carrying the DUPLICATA mention
     * (LC-08-05-09).
     *
     * @param ticketId the ticket database id
     * @return the number of slips printed; zero when the sale carried no card
     */
    public int printCardReceiptDuplicate(Long ticketId) {
        LOGGER.info("Entering method printCardReceiptDuplicate with ticketId: " + ticketId);
        if (ticketId != null) {
            LOGGER.info("Exiting method printCardReceiptDuplicate");
            return ticketPrinterService.printCardReceipt(ticketId, false, "DUPLICATA");
        }
        LOGGER.info("Exiting method printCardReceiptDuplicate");
        return 0;
    }

    /**
     * The article's wording in the language the till is set to, upper-cased as
     * every line label is (BO-03-01-20).
     *
     * <p>The administered translation wins; the catalog's own sale label is the
     * fallback, which is what every line carried before translations existed.
     *
     * @param product the article
     * @return the line label
     */
    private String translatedLabel(com.intermarche.pos.domain.catalog.Product product) {
        String language = i18n == null ? null : i18n.getLang();
        String label = com.intermarche.pos.domain.catalog.attribute.ProductAttributes
                .saleLabel(product, language);
        return label == null ? "" : label.toUpperCase();
    }
}
