package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
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
        if (code == null || !code.matches("2\\d{12}")) return;

        String prefix = code.substring(0, 2);
        boolean priceEmbedded = pricePrefixes.contains(prefix);
        boolean weightEmbedded = weightPrefixes.contains(prefix);
        if (!priceEmbedded && !weightEmbedded) return;

        if (!hasValidChecksum(code)) return; // let the generic EAN handler try

        String articleCode = stripLeadingZeros(code.substring(2, 7));
        long embeddedValue = Long.parseLong(code.substring(7, 12));

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

        Price price = Price.findCurrentPrice(product.id);
        BigDecimal vatRate = (price != null) ? price.vatRate : defaultVatRate;

        if (priceEmbedded) {
            // One physical sticker = one line: refuse the accidental double scan
            if (!ctx.state.ticket.scannedStickerCodes.add(code)) {
                ctx.state.ticket.setError("ÉTIQUETTE DÉJÀ SCANNÉE");
                ctx.handled = true;
                return;
            }
            // Embedded total in cents: quantity 1, no EAN/PLU carried so the
            // line never merges (each sticker is its own line).
            BigDecimal total = BigDecimal.valueOf(embeddedValue, 2);
            ctx.state.ticket.addItem(null, null, product.name.toUpperCase(),
                    total, BigDecimal.ONE, vatRate);
        } else {
            // Embedded weight in grams: catalog price per kilogram.
            BigDecimal quantityKg = BigDecimal.valueOf(embeddedValue, 3).setScale(3, RoundingMode.HALF_UP);
            if (quantityKg.signum() <= 0) {
                ctx.state.ticket.setError("POIDS INVALIDE");
                ctx.handled = true;
                return;
            }
            BigDecimal unitPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
            ctx.state.ticket.addItem(null, articleCode, product.name.toUpperCase(),
                    unitPrice, quantityKg, vatRate);
        }
        ctx.handled = true;
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
