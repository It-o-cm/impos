package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Scan handler for the administered ranges that mean something OTHER than an
 * article line: a RETURN, a container TARE, a named PROMOTION and a code only a
 * third party can read (BO-03-06-60, -61, -43 and -41).
 *
 * <p>Nothing here decodes a code. The positional vocabulary of
 * {@link ArticleBarcodeRange} already did that work for the scale labels, and a
 * store that prints its returns on the same plan states the same four numbers.
 * What this handler adds is the MEANING the range carries, and only the two
 * meanings that need no new table and no new engine:
 *
 * <ul>
 *   <li>{@link ArticleBarcodeRange.Purpose#REFUND} — the code names the article
 *       and carries its price; the line is rung as a return on the sale in
 *       progress. The paper IS the authorisation, so the original ticket is
 *       never asked for; which is exactly why the two administered permissions
 *       on returns are honoured here, word for word as the return key honours
 *       them (LC-05-01-01, LC-05-02-09/14).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#TARE} — the code carries the weight
 *       of the container, remembered on the ticket and deducted from the next
 *       weighed line by {@link WeightedEanScanHandler}. The deduction belongs
 *       there because that is where a weight becomes a quantity.</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#PROMOTION} — the range NAMES a
 *       promotion and the code arms that name on the ticket, which the
 *       valuation engine then resolves. No line and no amount: the register
 *       never decides what a promotion is worth (BO-03-06-43).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#PROMOTION_ENGINE} — the code is handed
 *       verbatim to the promotion engine with the basket, which applies the
 *       promotion it associates with it (BO-03-06-42).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#EXTERNAL_LOOKUP} — the range names a
 *       THIRD-PARTY service, the register asks it about the code and acts on
 *       the answer: an article the catalog carries is rung, anything else is
 *       shown to the operator as the service wrote it (BO-03-06-41).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#CUSTOMER_ORDER} — the code names a
 *       customer order, quote or sale voucher, its reference is read RAW and
 *       the document the store node holds behind it is loaded (BO-03-06-58).</li>
 *   <li>{@link ArticleBarcodeRange.Purpose#FUNCTION} — the code calls a
 *       register function, the one the range names or the one whose number the
 *       code carries, with the argument its value segment carries
 *       (BO-03-06-59).</li>
 * </ul>
 *
 * <p>Priority 1, beside the other recognizers, and DISJOINT from them the way
 * the chain contract demands: this handler claims a code only when an
 * administered range recognizes it AND that range's purpose is one of the two
 * above, and {@link WeightedEanScanHandler} refuses the very same codes for the
 * same reason. One decision, taken on the range, in two places that agree.
 */
@ApplicationScoped
@Priority(1)
public class RangePurposeScanHandler implements ScanContext.ScanHandler {

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The administered permissions on returns. */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The return types a RETURN range may be attached to (BO-03-13-12). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosListService posListService;

    /** The survey a FUNCTION range starts (BO-03-06-59). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.supervision.SurveyTriggerService surveyTriggerService;

    /** The external program a FUNCTION range launches (BO-03-06-59). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.home.ExternalProgramService externalProgramService;

    /** The recall of the document a CUSTOMER_ORDER range names (BO-03-06-58). */
    @jakarta.inject.Inject
    OrderCodeScanHandler orderCodeScanHandler;

    /** The call an EXTERNAL_LOOKUP range makes at scan time (BO-03-06-41). */
    @jakarta.inject.Inject
    RangeLookupClient rangeLookupClient;

    /** Whether the promotion engine is called, for a PROMOTION_ENGINE range (BO-03-06-42). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.valuation.ValuationService valuationService;

    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Claims a code belonging to a range administered as a return or a tare.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        String code = ctx.code;
        if (code == null || code.isBlank()) return;
        ArticleBarcodeRange range = ArticleBarcodeRange.findMatching(code);
        if (range == null || range.isArticlePurpose()) return;
        if (range.purpose == ArticleBarcodeRange.Purpose.BALANCE_TICKET) {
            // Claimed by BalanceTicketScanHandler, which owns the pick-up.
            return;
        }
        if (range.checkDigit && !hasValidChecksum(code)) return;
        if (range.purpose == ArticleBarcodeRange.Purpose.PROMOTION) {
            armPromotion(ctx, range, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.PROMOTION_ENGINE) {
            transmitToEngine(ctx, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.EXTERNAL_LOOKUP) {
            askTheThirdParty(ctx, range, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.CUSTOMER_ORDER) {
            recallOrder(ctx, range, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.FUNCTION) {
            callFunction(ctx, range, code);
            return;
        }
        if (range.purpose == ArticleBarcodeRange.Purpose.TARE) {
            announceTare(ctx, range, code);
            return;
        }
        ringReturn(ctx, range, code);
    }

    /**
     * Remembers the container weight the code carries (BO-03-06-60).
     *
     * <p>The tare is NOT a line: it changes the next weighed line and nothing
     * else. An unreadable or non-positive weight is refused rather than taken
     * as zero — a tare of zero and a tare nobody could read are not the same
     * thing to a customer paying for a bowl.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void announceTare(ScanContext ctx, ArticleBarcodeRange range, String code) {
        BigDecimal value = range.rawValue(code);
        if (value == null || value.signum() <= 0) {
            ctx.state.ticket.setError(i18n.get("op.tareIllisible"));
            ctx.handled = true;
            return;
        }
        BigDecimal tare = value.setScale(3, RoundingMode.HALF_UP);
        ctx.state.ticket.pendingTareKg = tare;
        ctx.state.ticket.setNotice(i18n.format("op.tarePriseEnCompte", tare.toPlainString()));
        ctx.handled = true;
    }

    /**
     * Arms the promotion the range names on the sale in progress
     * (BO-03-06-43).
     *
     * <p>No line, no amount, no decision about what the promotion is worth: the
     * register NAMES it and the valuation engine resolves it. A second scan of
     * the same promotion is a caution and not a refusal — the gesture went
     * through the first time, and the customer is told rather than blocked.
     *
     * <p>A promotion range that names nothing is an incomplete administered
     * row; it is refused loudly, because silently arming an empty name would
     * send the engine a promotion nobody can resolve.
     *
     * <p>A range that DEFINES its promotion — a percentage or an amount off —
     * hands the engine the complete offer: the article the code's article
     * segment names and the value its value segment carries, keyed on the
     * paper scanned so two coupons of one range are two rights.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void armPromotion(ScanContext ctx, ArticleBarcodeRange range, String code) {
        if (!range.triggersPromotion()) {
            ctx.state.ticket.setError(i18n.get("op.promotionNonParametree"));
            ctx.handled = true;
            return;
        }
        if (range.definesPromotion()) {
            armDefinedPromotion(ctx, range, code);
            return;
        }
        if (!ctx.state.ticket.armPromotion(range.promotionCode)) {
            ctx.state.ticket.setWarning(
                    i18n.format("op.promotionDejaDeclenchee", range.promotionCode));
            ctx.handled = true;
            return;
        }
        ctx.state.ticket.setNotice(
                i18n.format("op.promotionDeclenchee", range.promotionCode));
        ctx.handled = true;
    }

    /**
     * Arms a promotion the range defines (BO-03-06-43): the targeted article,
     * named by the code's article segment, and what the promotion is worth,
     * carried by its value segment — a percentage, handed to the engine as a
     * fraction, or an amount off each unit.
     *
     * <p>A code whose article is not in the catalog, or whose value cannot be
     * read, triggers nothing and says so: an offer on nothing, or worth
     * nothing, is not one the customer can be promised.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void armDefinedPromotion(ScanContext ctx, ArticleBarcodeRange range, String code) {
        ctx.handled = true;
        Product target = targetedArticle(range, code);
        BigDecimal value = range.rawValue(code);
        if (target == null || value == null || value.signum() <= 0) {
            ctx.state.ticket.setError(i18n.get("op.promotionIllisible"));
            return;
        }
        BigDecimal worth = ArticleBarcodeRange.PROMOTION_PERCENT.equals(range.promotionType)
                ? value.movePointLeft(2) : value;
        if (!ctx.state.ticket.armOffer(new com.intermarche.pos.ui.ticket.TicketState.ScannedOffer(
                code, range.promotionCode, target.ean, range.promotionType, worth))) {
            ctx.state.ticket.setWarning(i18n.format("op.promotionDejaDeclenchee", range.promotionCode));
            return;
        }
        ctx.state.ticket.armPromotion(range.promotionCode);
        ctx.state.ticket.setNotice(i18n.format("op.promotionDeclencheeArticle",
                range.promotionCode, translatedLabel(target)));
    }

    /**
     * The article a promotion code targets: its article segment read as an
     * EAN first, then as a PLU, leading zeros stripped as the catalog holds it.
     *
     * @param range the administered range the code belongs to
     * @param code the scanned code
     * @return the active article, or null when the segment names none
     */
    private Product targetedArticle(ArticleBarcodeRange range, String code) {
        String segment = range.articleSegment(code);
        if (segment == null) {
            return null;
        }
        Product product = Product.find("ean = ?1 and active = true", segment).firstResult();
        return product != null ? product : Product.findActiveByPlu(range.articleCode(code));
    }

    /**
     * Transmits the scanned code to the promotion engine (BO-03-06-42).
     *
     * <p>The register does not read the code: it carries it verbatim with the
     * basket, and the engine applies the promotion it associates with it. A
     * register whose engine call is off cannot transmit anything, and says so
     * rather than letting the customer believe a promotion was taken; the same
     * code scanned twice is transmitted once.
     *
     * @param ctx the scan context
     * @param code the scanned code
     */
    private void transmitToEngine(ScanContext ctx, String code) {
        ctx.handled = true;
        if (!valuationService.isEnabled()) {
            ctx.state.ticket.setError(i18n.get("op.moteurPromotionInactif"));
            return;
        }
        if (!ctx.state.ticket.armEngineBarcode(code.trim())) {
            ctx.state.ticket.setWarning(i18n.format("op.codeDejaTransmisMoteur", code.trim()));
            return;
        }
        ctx.state.ticket.setNotice(i18n.format("op.codeTransmisMoteur", code.trim()));
    }

    /**
     * Asks the third-party service the range names what a code it alone can
     * read means, and acts on the answer (BO-03-06-41).
     *
     * <p>Three outcomes, and the register says which one it got. The answer
     * names an article the catalog carries: the line is rung at the shop's own
     * price, because the third party said WHAT the code is and the shop still
     * says what it costs. The answer names nothing the catalog knows: it is
     * shown as it came — a partner refusing a voucher writes the sentence the
     * customer needs to hear, and the register has no business paraphrasing it.
     * No answer at all, for any reason: the operator is told the service could
     * not be reached, and the code is consumed rather than falling through to
     * "unknown code", which would be a lie about what happened.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void askTheThirdParty(ScanContext ctx, ArticleBarcodeRange range, String code) {
        if (!range.hasExternalLookup()) {
            ctx.state.ticket.setError(i18n.get("op.serviceExterneNonParametre"));
            ctx.handled = true;
            return;
        }
        String answer = rangeLookupClient == null
                ? null
                : rangeLookupClient.lookup(range.lookupUrl, code);
        if (answer == null) {
            ctx.state.ticket.setError(i18n.get("op.serviceExterneInjoignable"));
            ctx.handled = true;
            return;
        }
        // The partner's response format, as the range maps it: the field of
        // its answer that carries the article code or the message.
        answer = RangeLookupClient.extract(answer, range.lookupField);
        if (answer == null) {
            ctx.state.ticket.setError(i18n.get("op.reponseServiceExterneIllisible"));
            ctx.handled = true;
            return;
        }
        Product product = Product.find("ean = ?1 and active = true", answer).firstResult();
        if (product == null) {
            product = Product.findActiveByPlu(answer);
        }
        if (product == null) {
            ctx.state.ticket.setNotice(answer);
            ctx.handled = true;
            return;
        }
        if (com.intermarche.pos.ui.ticket.ArticleMessageService.refuse(ctx.state, product)) {
            ctx.handled = true;
            return;
        }
        // LC-02-13-03/04: the partner's article takes a decimal armed quantity
        // only when it is of the decimal-quantity type.
        if (ctx.state.ticket.armedDecimalRefused(
                com.intermarche.pos.domain.catalog.attribute.ProductAttributes.decimalQuantityToEnter(product))) {
            ctx.state.ticket.setError(i18n.get("op.quantiteInvalide"));
            ctx.handled = true;
            return;
        }
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null && price.vatRate() != null)
                ? price.vatRate() : defaultVatRate;
        BigDecimal unitPrice = (price != null && price.priceIncludingTax != null)
                ? price.priceIncludingTax : BigDecimal.ZERO;
        ctx.state.ticket.addItem(product.ean, null,
                translatedLabel(product), unitPrice, BigDecimal.ONE, vatRate);
        ctx.state.ticket.snapshotMealVoucherEligibility(product.ean,
                com.intermarche.pos.domain.catalog.attribute.ProductAttributes.mealVoucherEligible(product));
        // LC-10-01-09/11: what the article may be paid with, as on every other entry path.
        ctx.state.ticket.snapshotRestrictedTenders(product.ean,
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product));
        com.intermarche.pos.ui.ticket.LinkedArticleService.snapshot(ctx.state.ticket, product);
        ctx.handled = true;
    }

    /**
     * Calls the register function a FUNCTION range stands for (BO-03-06-59).
     *
     * <p>The range names the function; a range naming none leaves the code to
     * carry the function's number in its article segment. The value segment
     * carries the argument a function takes — the survey's number. A function
     * that opens a screen asks the sale screen for it through the poll, the way
     * a scanned basket asks for its control.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void callFunction(ScanContext ctx, ArticleBarcodeRange range, String code) {
        ctx.handled = true;
        com.intermarche.pos.domain.barcode.RegisterFunction function =
                com.intermarche.pos.domain.barcode.RegisterFunction.byName(range.functionCode);
        if (function == null) {
            String number = range.articleCode(code);
            function = number == null || !number.matches("\\d{1,9}") ? null
                    : com.intermarche.pos.domain.barcode.RegisterFunction.byNumber(Integer.parseInt(number));
        }
        if (function == null) {
            ctx.state.ticket.setError(i18n.get("op.fonctionCaisseInconnue"));
            return;
        }
        switch (function) {
            case METROLOGY -> ctx.state.functionPath = "/maintenance/metrology";
            case PARKED_TICKETS -> ctx.state.functionPath = "/parked";
            case RECALL -> ctx.state.functionPath = "/recall";
            case EXTERNAL_PROGRAM -> {
                String refusal = externalProgramService.launch();
                if (refusal == null) {
                    ctx.state.ticket.setNotice(i18n.format("op.programmeExterneLance", externalProgramService.label()));
                } else {
                    ctx.state.ticket.setError(refusal);
                }
            }
            default -> {
                BigDecimal survey = range.rawValue(code);
                if (survey == null || survey.signum() <= 0) {
                    ctx.state.ticket.setError(i18n.get("op.sondageIllisible"));
                    return;
                }
                // The segment's digits ARE the number, whatever decimals the range reads.
                surveyTriggerService.fireByScan(ctx.state, survey.unscaledValue().longValue());
            }
        }
        ctx.state.touch();
    }

    /**
     * Arms the reference of the customer order, quote or sale voucher the code
     * names (BO-03-06-58).
     *
     * <p>The reference is read RAW from the article segment, leading zeros
     * included: an order reference is a key the shop matches character for
     * character, and stripping its zeros would name an order nobody printed —
     * the same reasoning the counter-ticket reference already follows.
     *
     * <p>The order itself lives on the store node, which the commercial
     * management feeds: the reference is recalled there and the document loaded
     * as its own barcode would load it; a reference the node does not hold is
     * refused by name.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void recallOrder(ScanContext ctx, ArticleBarcodeRange range, String code) {
        String reference = range.articleSegment(code);
        if (reference == null || reference.isBlank()) {
            ctx.state.ticket.setError(i18n.get("op.referenceCommandeIllisible"));
            ctx.handled = true;
            return;
        }
        ctx.state.ticket.recalledOrderReference = reference.trim();
        // BO-03-06-58: the reference names a document the store node holds — an
        // order, a quote or a sale voucher of the commercial management — and it is
        // loaded the way its own barcode would load it.
        if (!orderCodeScanHandler.open(ctx.state, reference.trim())) {
            ctx.state.ticket.setError(i18n.format("op.commandeInconnueNoeud", reference.trim()));
        }
        ctx.handled = true;
    }

    /**
     * Rings the article the code names as a RETURN (BO-03-06-61).
     *
     * <p>The price comes from the code when the range carries one, and from the
     * catalog when it carries a weight instead — a return of a weighed article
     * is given back at the price the shop charges for it.
     *
     * @param ctx the scan context
     * @param range the administered range the code belongs to
     * @param code the scanned code
     */
    private void ringReturn(ScanContext ctx, ArticleBarcodeRange range, String code) {
        String articleCode = range.articleCode(code);
        BigDecimal value = range.rawValue(code);
        if (articleCode == null || value == null) return;
        // BO-03-13-12: the range may be ATTACHED to an administered return
        // type, whose permissions then rule the scan and whose name is said.
        com.intermarche.pos.service.PosSettingsService.ReturnType type =
                posListService == null ? null : posListService.returnTypeOfRange(range.code);
        // LC-05-02-09/14: taking an article back without its origin ticket can be
        // forbidden store-wide — and this code IS a return without one; a type
        // administered NOORIGIN passes over that prohibition (BO-03-13-06).
        if (!posSettingsService.returnWithoutOriginAllowed()
                && (type == null || !type.originOptional())) {
            ctx.state.ticket.setError(i18n.get("op.retourSansTicketOrigine"));
            ctx.handled = true;
            return;
        }
        // LC-05-01-01: a return inside a ticket that already carries lines is a
        // permission of its own.
        if (!posSettingsService.returnInSaleAllowed() && !ctx.state.ticket.items.isEmpty()) {
            ctx.state.ticket.setError(i18n.get("op.retourTicketVenteInterdit"));
            ctx.handled = true;
            return;
        }
        Product product = Product.findActiveByPlu(articleCode);
        if (product == null) {
            ctx.state.ticket.setError(i18n.format("op.articleBalanceIntrouvable", articleCode));
            ctx.handled = true;
            return;
        }
        if (com.intermarche.pos.ui.ticket.ArticleMessageService.refuse(ctx.state, product)) {
            ctx.handled = true;
            return;
        }
        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null && price.vatRate() != null)
                ? price.vatRate() : defaultVatRate;
        BigDecimal unitPrice = range.isPriceEmbedded()
                ? value
                : (price != null && price.priceIncludingTax != null
                        ? price.priceIncludingTax : BigDecimal.ZERO);
        BigDecimal quantity = range.isPriceEmbedded()
                ? BigDecimal.ONE
                : value.setScale(3, RoundingMode.HALF_UP);
        if (quantity.signum() <= 0) {
            ctx.state.ticket.setError(i18n.get("op.poidsInvalide"));
            ctx.handled = true;
            return;
        }
        // The ONE funnel every entry goes through (TicketState.addItem) is what
        // negates the quantity and marks the line as a return, so a return rung
        // by a barcode and a return rung by the return key are the same line.
        // The mode is armed for this entry only: a code is one gesture, not a
        // change of what the register is doing.
        boolean wasReturnMode = ctx.state.returnMode;
        // BO-03-13-12: the line is filed under the type the range is attached to.
        String previousType = ctx.state.returnTypeLabel;
        ctx.state.returnMode = true;
        ctx.state.returnTypeLabel = type == null ? previousType : type.label();
        try {
            ctx.state.ticket.addItem(product.ean, articleCode,
                    translatedLabel(product), unitPrice, quantity, vatRate);
            com.intermarche.pos.ui.ticket.LinkedArticleService.snapshot(ctx.state.ticket, product);
        } finally {
            ctx.state.returnMode = wasReturnMode;
            ctx.state.returnTypeLabel = previousType;
        }
        if (type != null) {
            ctx.state.ticket.setNotice(i18n.format("op.retourTypeEnregistre", type.label()));
        }
        ctx.handled = true;
    }

    /**
     * Verifies the EAN13 modulo-10 check digit of a 13-character code.
     *
     * <p>A code of another length carries no EAN13 check digit, so it passes:
     * the administered range said the last character is checked, not that the
     * code is an EAN13.
     *
     * @param code the scanned code
     * @return true when the check digit is right, or not applicable
     */
    private boolean hasValidChecksum(String code) {
        if (code.length() != 13) {
            return true;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = code.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return false;
            }
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == code.charAt(12) - '0';
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
