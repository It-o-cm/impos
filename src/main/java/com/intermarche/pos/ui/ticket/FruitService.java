package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
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
     * Returns the active PLU products shown on the weighing grid.
     *
     * @return the weighable catalog
     */
    public List<Product> getPluProducts() {
        return Product.list("plu is not null and active = true");
    }
}