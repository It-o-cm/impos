package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;

/**
 * Scan handler adding a product to the ticket from a scanned short PLU code.
 * <p>
 * Phase 0: the price is handed to the ticket as {@link BigDecimal} without any
 * double round-trip. Phase 1: the real VAT rate of the Price is captured on
 * the line (default rate when no price is found).
 * <p>
 * A typed PLU sells QUANTITY 1 AT THE CURRENT PRICE — even for a weighed
 * product, whose per-kilogram price then applies to one unit: the real
 * weighing lives in the FRUITS screen and the 2x labels, this handler is
 * the service-counter shortcut. Search-result taps route through the same
 * chain, so tapping a PLU-only product behaves exactly like typing its
 * code.
 */
@ApplicationScoped
@Priority(3)
public class PluScanHandler implements ScanContext.ScanHandler {

    /** Default VAT rate applied when no catalog price is found (e.g. 0.20). */
    @ConfigProperty(name = "pos.vat.default-rate", defaultValue = "0.20")
    BigDecimal defaultVatRate;

    /**
     * Handles a scanned short PLU code by adding the matching product to the ticket.
     * <p>
     * A product flagged as forbidden to sell is refused: no line is created and an
     * error is shown to the cashier.
     *
     * @param ctx the scan context carrying the scanned code and POS state
     */
    @Override
    public void handle(ScanContext ctx) {
        if (ctx.handled) return;
        if (ctx.code.matches("\\d{1,5}")) {
            Product p = Product.findActiveByPlu(ctx.code);
            if (p != null) {
                if (p.forbiddenToSale) {
                    ctx.state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                    ctx.handled = true;
                    return;
                }

                Price price = Price.findCurrentPrice(p.id);
                BigDecimal finalPrice = (price != null) ? price.priceIncludingTax : BigDecimal.ZERO;
                BigDecimal vatRate = (price != null) ? price.vatRate : defaultVatRate;
                ctx.state.ticket.addItem(null, ctx.code, p.name.toUpperCase(), finalPrice, BigDecimal.ONE, vatRate);
                ctx.handled = true;
            }
        }
    }
}
