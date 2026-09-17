package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.barcode.ArticleBarcodeRange;
import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;

/**
 * Scan handler decoding in-store weighted EAN13 codes (prefix 2x), printed by
 * the scale or the service counter.
 * <p>
 * Layout: {@code PP AAAAA VVVVV K} — 2-digit prefix, 5-digit article code
 * (matched against the product PLU, leading zeros stripped), 5-digit embedded
 * value, EAN13 check digit (verified; an invalid checksum lets the code fall
 * through to the next handlers — falling through, not erroring, is what
 * lets a regular 2-prefixed retail EAN still reach the catalog handler).
 * A price-embedded sticker names one PHYSICAL object, so scanning the same
 * sticker twice is refused through the transient scanned-codes set (cleared
 * by a restart — accepted limit).
 * <ul>
 *   <li>Price-embedded prefixes ({@code pos.scan.embedded-price-prefixes}):
 *       the value is the line total in cents; the line is created with
 *       quantity 1 at that total and never merges (no EAN, no PLU carried).</li>
 *   <li>Weight-embedded prefixes ({@code pos.scan.embedded-weight-prefixes}):
 *       the value is the weight in grams; the line carries the article PLU and
 *       the catalog price per kilogram.</li>
 * </ul>
 * The VAT rate comes from the current catalog price, defaulting to
 * {@code pos.vat.default-rate}.
 * <p>
 * ADMINISTERED RANGES (BO-03-06-02/03/04/05/10): the layout above is the
 * FALLBACK, not the rule. When the back office administers an
 * {@link ArticleBarcodeRange} recognizing the code, that range says everything —
 * the literal prefix, the total length, the character kind, where the article
 * segment and the value segment sit, how many decimals the value carries, and
 * the currency a price is expressed in. A store whose scales print another plan
 * administers rows; it does not wait for a release. The two prefix properties
 * keep working for a node that administers no range at all, which is what lets
 * the two generations coexist exactly as they do on the voucher side.
 */
@ApplicationScoped
@Priority(1)
public class WeightedEanScanHandler implements ScanContext.ScanHandler {

    /** Prefixes whose embedded value is the line total in cents. */
    @ConfigProperty(name = "pos.scan.embedded-price-prefixes", defaultValue = "21,22")
    List<String> pricePrefixes;

    /** Prefixes whose embedded value is the weight in grams. */
    @ConfigProperty(name = "pos.scan.embedded-weight-prefixes", defaultValue = "23,24,25,26")
    List<String> weightPrefixes;

    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Handles a 2x-prefixed in-store EAN13 by decoding its embedded price or
     * weight and adding the matching line to the ticket.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        String code = ctx.code;
        if (code == null || code.isBlank()) return;
        // The administered ranges are consulted FIRST and only once: a store
        // that administers its scale plan is not bound to the 2x EAN13 shape.
        ArticleBarcodeRange range = ArticleBarcodeRange.findMatching(code);
        if (range == null && !code.matches("2\\d{12}")) return;

        boolean priceEmbedded;
        boolean weightEmbedded;
        String articleCode;
        BigDecimal embeddedAmount;
        BigDecimal embeddedWeight;
        if (range != null) {
            priceEmbedded = range.isPriceEmbedded();
            weightEmbedded = !priceEmbedded;
            if (range.checkDigit && !hasValidChecksum(code)) return;
            articleCode = range.articleCode(code);
            BigDecimal value = range.rawValue(code);
            if (articleCode == null || value == null) return;
            if (priceEmbedded) {
                // BO-03-06-05: a range administered in another currency carries
                // its price in that currency; the referential rate converts it,
                // and a rate nobody administered refuses the code rather than
                // charging francs as euros.
                embeddedAmount = toEuros(ctx, range, value);
                if (embeddedAmount == null) {
                    ctx.handled = true;
                    return;
                }
                embeddedWeight = null;
            } else {
                embeddedAmount = null;
                embeddedWeight = value;
            }
        } else {
            String prefix = code.substring(0, 2);
            priceEmbedded = pricePrefixes.contains(prefix);
            weightEmbedded = weightPrefixes.contains(prefix);
            if (!priceEmbedded && !weightEmbedded) return;

            if (!hasValidChecksum(code)) return; // let the generic EAN handler try

            articleCode = stripLeadingZeros(code.substring(2, 7));
            long embeddedValue = Long.parseLong(code.substring(7, 12));
            embeddedAmount = priceEmbedded ? BigDecimal.valueOf(embeddedValue, 2) : null;
            embeddedWeight = priceEmbedded ? null : BigDecimal.valueOf(embeddedValue, 3);
        }

        Product product = Product.findActiveByPlu(articleCode);
        if (product == null) {
            ctx.state.ticket.setError("ARTICLE BALANCE INTROUVABLE (" + articleCode + ")");
            ctx.handled = true;
            return;
        }
        if (product.forbiddenToSale) {
            ctx.state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
            ctx.handled = true;
            return;
        }
        // BO-02-03-11: a recalled article is refused at scan, no line.
        if (com.intermarche.pos.domain.catalog.attribute.ProductAttributes.recall(product)) {
            ctx.state.ticket.setError("ARTICLE EN RETRAIT/RAPPEL");
            ctx.handled = true;
            return;
        }

        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null && price.vatRate() != null)
                ? price.vatRate() : defaultVatRate;
        // BO-02-03-26/27: VAT-exempt article ventilated at rate 0.
        if (com.intermarche.pos.domain.catalog.attribute.ProductAttributes.vatExempt(product)) {
            vatRate = BigDecimal.ZERO;
        }

        if (priceEmbedded) {
            // One physical sticker = one line: refuse the accidental double scan
            if (!ctx.state.ticket.scannedStickerCodes.add(code)) {
                ctx.state.ticket.setError("ÉTIQUETTE DÉJÀ SCANNÉE");
                ctx.handled = true;
                return;
            }
            // Embedded total in cents, quantity 1. The line carries the
            // product EAN like every other: a line without an EAN does not
            // exist, and the valuation engine — which prices the WHOLE ticket
            // — must see this one too. Non-merging is guaranteed by the
            // sticker-code guard above (one physical sticker = one scan), not
            // by stripping the identity.
            ctx.state.ticket.addItem(product.ean, articleCode, product.saleLabel().toUpperCase(),
                    embeddedAmount, BigDecimal.ONE, vatRate);
            // The sticker price is the line's own truth: flag it so the
            // valuation request carries the surcharge trio and the engine
            // does not re-price this EAN from its catalog.
            ctx.state.ticket.items.get(ctx.state.ticket.items.size() - 1).priceEmbedded = true;
        } else {
            // Embedded weight in grams: catalog price per kilogram. The line
            // carries the product EAN: its price IS the catalog price, so the
            // valuation engine resolves the same base and the line is
            // eligible (unlike price-embedded stickers, which stay local).
            BigDecimal quantityKg = embeddedWeight.setScale(3, RoundingMode.HALF_UP);
            if (quantityKg.signum() <= 0) {
                ctx.state.ticket.setError("POIDS INVALIDE");
                ctx.handled = true;
                return;
            }
            BigDecimal unitPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            ctx.state.ticket.addItem(product.ean, articleCode, product.saleLabel().toUpperCase(),
                    unitPrice, quantityKg, vatRate);
        }
        com.intermarche.pos.ui.ticket.TicketState.TicketItem added =
                ctx.state.ticket.items.get(ctx.state.ticket.items.size() - 1);
        // BO-02-03-09: snapshot the discount ban onto the freshly added line.
        if (com.intermarche.pos.domain.catalog.attribute.ProductAttributes.discountForbidden(product)) {
            added.discountForbidden = true;
        }
        // LC-09-01-11 to -18: and what the article may be paid with.
        added.restrictedTenders =
                com.intermarche.pos.domain.catalog.attribute.RestrictedTender.snapshot(product);
        // LC-02-03-13: an in-store weighed label names no lot, so the recalled lots are
        // listed and the cashier reads the one printed on the pack.
        String recalledLots = com.intermarche.pos.domain.catalog.attribute.ProductAttributes
                .recalledLotsMessage(product);
        if (recalledLots != null) {
            ctx.state.ticket.setNotice(recalledLots);
        }
        ctx.handled = true;
    }

    /**
     * Converts an administered price into euros: a euro range is returned as
     * is, any other goes through the {@code Currency} referential at the rate
     * the store administers (BO-03-06-05).
     *
     * @param ctx the scan context, whose ticket carries the refusal message
     * @param range the administered range the code belongs to
     * @param value the price as the code carries it, in the range's currency
     * @return the price in euros, or null when no rate is administered
     */
    private BigDecimal toEuros(ScanContext ctx, ArticleBarcodeRange range, BigDecimal value) {
        if (range.isEuro()) {
            return value;
        }
        String iso = range.currency.name();
        com.intermarche.pos.domain.payment.Currency currency =
                com.intermarche.pos.domain.payment.Currency.findActiveByCode(iso);
        if (currency == null) {
            ctx.state.ticket.setError("DEVISE " + iso + " NON PARAMÉTRÉE");
            return null;
        }
        return currency.toEuro(value);
    }

    /**
     * Verifies the EAN13 check digit of the given 13-digit code.
     *
     * @param code the 13-digit code
     * @return true if the check digit is valid
     */
    private boolean hasValidChecksum(String code) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = code.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int expected = (10 - (sum % 10)) % 10;
        return expected == (code.charAt(12) - '0');
    }

    /**
     * Strips the leading zeros of a zero-padded article code.
     *
     * @param value the zero-padded code
     * @return the code without leading zeros (at least one digit kept)
     */
    private String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
