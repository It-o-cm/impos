package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class FruitService {

    public List<Product> getPluProducts() {
        return Product.list("plu is not null and active = true");
    }
}