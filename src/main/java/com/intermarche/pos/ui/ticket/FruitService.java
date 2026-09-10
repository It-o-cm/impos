package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Feeds the FRUITS &amp; LÉGUMES weighing screen: the grid of PLU products
 * the cashier taps, each tap selling the CURRENT SCALE WEIGHT of that
 * product — this screen and the 2x labels are the only real weighing paths
 * of the register (a typed PLU sells quantity 1).
 */
@ApplicationScoped
public class FruitService {

    /**
     * Family flag marking the Fruits &amp; Légumes aisle. Resolved through the
     * family hierarchy, so tagging an aisle root covers every sub-family
     * below it; administered in the referential like every other flag.
     */
    public static final String FRUITS_VEGETABLES_FLAG = "FRUITS_VEGETABLES";

    /**
     * Returns the products shown on the weighing grid: active, sold loose
     * ({@code variableWeight} — the weight is only known at weighing time),
     * carrying a PLU (the grid sells through the PLU route), and belonging to
     * a family whose hierarchy carries {@link #FRUITS_VEGETABLES_FLAG}.
     * Pre-packed goods — even WEIGHT-typed ones — and the other aisles never
     * appear here.
     *
     * @return the weighable Fruits &amp; Légumes catalog
     */
    public List<Product> getPluProducts() {
        List<Product> weighable =
                Product.list("variableWeight = true and plu is not null and active = true");
        List<Product> result = new ArrayList<>();
        for (Product product : weighable) {
            if (ProductFamily.productHasFlag(product, FRUITS_VEGETABLES_FLAG)) {
                result.add(product);
            }
        }
        return result;
    }
}