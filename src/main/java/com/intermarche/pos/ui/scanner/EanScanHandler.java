package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.attribute.ProductAttributes;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Scan handler adding a product to the ticket from a scanned EAN code.
 * <p>
 * Phase 0: the price is handed to the ticket as {@link BigDecimal} without any
 * double round-trip. Phase 1: the real VAT rate of the Price is captured on
 * the line (default rate when no price is found).
 * <p>
 * This is THE SNAPSHOT MOMENT of a sale line: price and VAT are resolved
 * here (current-price window + priority) and frozen onto the line — a later
 * price change never rewrites what was sold. Forbidden-to-sale products are
 * refused here with the cashier error, and the resulting unit line is the
 * only line kind eligible for merging and quantity edition.
 */
@ApplicationScoped
@Priority(2)
public class EanScanHandler implements ScanContext.ScanHandler {

    /** Whether an article may be rung on THIS register (BO-03-01-18). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.ticket.ArticleScopeService articleScopeService;

    /**
     * The localised operator messages: every sentence this class stamps on the
     * register's message band is a bundle key, never a French literal (LC-13-06).
     */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.Messages i18n;

    @jakarta.inject.Inject
    com.intermarche.pos.ui.ticket.TicketService ticketService;

    /** The back-office parameters (EAN13 check-digit control — BO-10-02-21). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The administered alerts on articles (BO-04-03-08). */
    @jakarta.inject.Inject
    com.intermarche.pos.ui.control.ControlGateService controlGateService;

    /** An article code beyond the EAN shapes: 1 to 38 letters and digits (BO-03-06-01/-10). */
    static final String ARTICLE_CODE = "[A-Za-z0-9]{1,38}";

    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Handles a scanned EAN code by adding the matching product to the ticket.
     * <p>
     * A product flagged as forbidden to sell is refused: no line is created and an
     * error is shown to the cashier.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;

        // BO-03-06-01/-10: an article is named by an EAN-8 or EAN-13, or by an
        // article code of up to 38 letters and digits — a Code 128 or the text of a
        // 2D code. The longer codes are claimed only when the catalog carries them,
        // so the vouchers and badges of the same shape keep walking the chain.
        // BO-02-03-03/04: a code that is not an article's EAN may still be one of
        // its additional sale codes or its internal code — an alphanumeric Code
        // 128 included. Such a code was declared by the referential, so the EAN
        // check-digit control does not apply to it.
        // An EAN-shaped code is still checked BEFORE any lookup (LC-11-01-01);
        // a longer article code is claimed only when the catalog carries it.
        boolean numeric = ctx.code.matches("\\d{8,13}");
        Product byEan = null;
        Product alias = null;
        if (!numeric && ctx.code.matches(ARTICLE_CODE)) {
            byEan = Product.find("ean = ?1 and active = true", ctx.code).firstResult();
            alias = byEan == null ? Product.findActiveByAlias(ctx.code) : null;
        }
        if (numeric || byEan != null || alias != null) {
            // BO-10-02-15: an article scanned while a settlement is under way,
            // where the shop forbids it. Said and consumed: a code refused here
            // must not walk on to the handlers behind, which would ring it by
            // another road.
            if (ticketService.saleRefusedDuringPayment(ctx.state)) {
                ctx.handled = true;
                return;
            }
            // LC-11-01-01: EAN-8 keys are mandatory; BO-10-02-21 keeps the
            // existing administered EAN-13 control. Reject before any lookup.
            if ((ctx.code.length() == 8 || posSettingsService.ean13CheckDigitEnabled() && ctx.code.length() == 13)
                    && !hasValidChecksum(ctx.code)) {
                ctx.state.ticket.setError(i18n.get("op.codeEanInvalide"));
                ctx.handled = true;
                return;
            }
            if (numeric) {
                byEan = Product.find("ean = ?1 and active = true", ctx.code).firstResult();
                alias = byEan == null ? Product.findActiveByAlias(ctx.code) : null;
            }
            Product p = byEan != null ? byEan : alias;
            // The line names the article by its EAN whatever code was read.
            String lineEan = alias != null ? p.ean : ctx.code;
            if (p != null) {
                if (com.intermarche.pos.ui.ticket.ArticleMessageService.refuse(ctx.state, p)) {
                    ctx.handled = true;
                    return;
                }
                if (p.forbiddenToSale) {
                    ctx.state.ticket.setError(com.intermarche.pos.ui.ticket.ForbiddenSaleMessage.forbidden(i18n, posSettingsService, p));
                    ctx.handled = true;
                    return;
                }
                // BO-02-03-24: an article that must be weighed goes to the scale
                // (a price-embedded label never reaches this handler).
                if (ticketService.routeWeighed(ctx.state, p)) {
                    ctx.handled = true;
                    return;
                }

                // BO-03-01-18: an article the store took out of THIS island or
                // THIS register is refused here and nowhere else.
                if (articleScopeService != null && !articleScopeService.soldHere(p)) {
                    ctx.state.ticket.setError(
                            i18n.get("op.articleNonVenduSurCetteCaisse"));
                    ctx.handled = true;
                    return;
                }

                // BO-02-03-11: a recalled article is refused at scan, no line.
                if (ProductAttributes.recall(p)) {
                    ctx.state.ticket.setError(com.intermarche.pos.ui.ticket.ForbiddenSaleMessage.recall(i18n, posSettingsService, p));
                    ctx.handled = true;
                    return;
                }

                // LC-02-13-03/04: a decimal quantity armed before a unit article
                // is refused, and the article is not rung.
                if (ctx.state.ticket.armedDecimalRefused(ProductAttributes.decimalQuantityToEnter(p))) {
                    ctx.state.ticket.setError(i18n.get("op.quantiteInvalide"));
                    ctx.handled = true;
                    return;
                }
                // Age gate: a restricted product parks the scan behind the
                // ID-check prompt; the confirmation replays this very code
                // through the chain (phase: age control).
                if (ticketService.suspendForAgeCheck(ctx.state, p, "SCAN", ctx.code, null)) {
                    ctx.handled = true;
                    return;
                }
                // BO-04-03-08: an administered alert on this article (or its
                // family) decides before the line is rung; its replay re-reads
                // this very code through the chain.
                if (controlGateService.onArticle(p, null,
                        com.intermarche.pos.ui.control.ControlGateService.Kind.SCAN, ctx.code)
                        == com.intermarche.pos.ui.control.ControlGateService.Outcome.STOP) {
                    ctx.handled = true;
                    return;
                }

                // LC-02-03-01/03: an article whose price or whose quantity the
                // referential does not carry parks the add behind the entry prompt.
                if (ticketService.suspendForEntry(ctx.state, p, null)) {
                    ctx.handled = true;
                    return;
                }

                Price price = Price.findCurrentPrice(p.id);
                // BO-02-03-05: an "appel prix" article without a price is refused.
                if (ticketService.priceCallRefused(ctx.state, p, price)) {
                    ctx.handled = true;
                    return;
                }
                BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
                // BO-02-03-26/27: VAT-exempt article ventilated at rate 0 under its
                // own heading; the amount due is unchanged, only its VAT breakdown.
                com.intermarche.pos.domain.catalog.ArticleVat.Resolution vat =
                        com.intermarche.pos.domain.catalog.ArticleVat.resolve(p, price, defaultVatRate);
                // Line label is the checkout label when set, else the name. The
                // line names the article by its EAN whatever code was read.
                ctx.state.ticket.addItem(lineEan, null, translatedLabel(p), finalPrice, BigDecimal.ONE, vat.rate());
                // Only reach into the line when there is a snapshot to carry —
                // a plain product leaves the freshly added line untouched.
                com.intermarche.pos.ui.ticket.TicketState.TicketItem line =
                        ctx.state.ticket.items.get(ctx.state.ticket.items.size() - 1);
                if (p.giftCardAmount != null) {
                    line.moneyProduct = true;
                }
                if (ProductAttributes.discountForbidden(p)) {
                    line.discountForbidden = true;
                }
                // LC-09-01-11 to -18: what this article may be paid with, snapshotted
                // on the line so the eligible bases can be totalled without a lookup.
                ctx.state.ticket.snapshotMealVoucherEligibility(p.ean, ProductAttributes.mealVoucherEligible(p));
                com.intermarche.pos.ui.ticket.LinkedArticleService.snapshot(ctx.state.ticket, p);
                // LC-10-01-09/11: by article, since a repeated scan may merge into an
                // earlier line while another article stays last.
                ctx.state.ticket.snapshotRestrictedTenders(lineEan, com.intermarche.pos.domain
                        .catalog.attribute.RestrictedTender.snapshot(p));
                ticketService.stampArticle(ctx.state, p, vat);
                // LC-02-03-13: a plain EAN names no lot, so the register cannot tell
                // whether this pack is one of the recalled ones. It lists them and lets
                // the sale go on — the cashier reads the lot printed on the pack.
                String recalledLots = ProductAttributes.recalledLotsJoined(p);
                if (recalledLots != null) {
                    ctx.state.ticket.setNotice(i18n.format("op.produitRappelLots", recalledLots));
                }

                // BO-03-06-44: a DEMATERIALISED article is sold by its code and
                // not by its packet. The line is rung first — the customer is
                // paying for it — and the register then asks for the activation
                // code, which the next scan answers. The provider is
                // snapshotted on the line beside the code it owes.
                if (ProductAttributes.activationRequired(p)) {
                    line.activationProvider = ProductAttributes.activationProvider(p);
                    ctx.state.ticket.pendingActivationUid = line.uid;
                    ctx.state.ticket.setWarning(i18n.get("op.codeActivationAttendu"));
                }

                ctx.handled = true;
            }
        }
    }

    /**
     * Verifies EAN-8 and EAN-13 keys (LC-11-01-01, BO-10-02-21). Starting
     * at the last data digit gives weights 3,1 for both symbol lengths.
     *
     * @param code the numeric EAN-8 or EAN-13 code
     * @return true when the check digit matches
     */
    private boolean hasValidChecksum(String code) {
        int sum = 0;
        int weight = 3;
        for (int i = code.length() - 2; i >= 0; i--) {
            sum += (code.charAt(i) - '0') * weight;
            weight = 4 - weight;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == (code.charAt(code.length() - 1) - '0');
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
