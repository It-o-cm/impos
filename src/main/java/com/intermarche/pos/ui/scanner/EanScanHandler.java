package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;

@ApplicationScoped
@Priority(2)
public class EanScanHandler implements ScanContext.ScanHandler {

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

        if (ctx.code.matches("\\d{8,13}")) {
            Product p = Product.find("ean = ?1 and active = true", ctx.code).firstResult();
            if (p != null) {
                if (p.forbiddenToSale) {
                    ctx.state.ticket.setError("PRODUIT INTERDIT À LA VENTE");
                    ctx.handled = true;
                    return;
                }

                Price price = Price.findCurrentPrice(p.id);
                double finalPrice = (price != null) ? price.priceIncludingTax.doubleValue() : 0.0;

                // Correction : ctx.state.ticket.addItem
                ctx.state.ticket.addItem(ctx.code, null, p.name.toUpperCase(), finalPrice, 1);

                ctx.handled = true;
            }
        }
    }
}
