package com.intermarche.pos.ui.scanner;

import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;

@ApplicationScoped
@Priority(3)
public class PluScanHandler implements ScanContext.ScanHandler {

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
                double finalPrice = (price != null) ? price.priceIncludingTax.doubleValue() : 0.0;
                ctx.state.ticket.addItem(null, ctx.code, p.name.toUpperCase(), finalPrice, 1);
                ctx.handled = true;
            }
        }
    }
}
