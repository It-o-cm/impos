package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ManualService {

    // DTOs internes
    public static class ManualItem {
        public String label; public boolean isCategory; public String url; public String ean;
        public ManualItem(String label, boolean isCategory, String url, String ean) {
            this.label = label; this.isCategory = isCategory; this.url = url; this.ean = ean;
        }
    }
    public static class ManualViewData {
        public List<ManualItem> items; public String breadcrumb; public boolean isRoot; public String parentUrl;
        public ManualViewData(List<ManualItem> items, String breadcrumb, boolean isRoot, String parentUrl) {
            this.items = items; this.breadcrumb = breadcrumb; this.isRoot = isRoot; this.parentUrl = parentUrl;
        }
    }

    // Méthode getPluProducts déplacée vers FruitService

    public ManualViewData getManualRootData() {
        List<ProductFamily> allFamilies = ProductFamily.listAll();
        List<ManualItem> items = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (hasManualProducts(f)) {
                items.add(new ManualItem(f.description, true, "/manual/cat/" + f.code, null));
            }
        }
        return new ManualViewData(items, "Accueil", true, null);
    }

    public ManualViewData getManualCategoryData(String code) {
        List<ManualItem> items = new ArrayList<>();
        String breadcrumb = "Accueil";
        String parentUrl = "/manual";
        ProductFamily family = ProductFamily.findByCode(code);
        if (family != null) {
            breadcrumb = "Accueil > " + family.description;
            if (family.productFamilies != null) {
                for (ProductFamily child : family.productFamilies) {
                    if (hasManualProducts(child)) items.add(new ManualItem(child.description, true, "/manual/cat/" + child.code, null));
                }
            }
            if (family.products != null) {
                for (Product p : family.products) {
                    if (p.plu == null) items.add(new ManualItem(p.name, false, null, p.ean));
                }
            }
        }
        return new ManualViewData(items, breadcrumb, false, parentUrl);
    }

    private boolean hasManualProducts(ProductFamily family) {
        if (family.products != null) { for (Product p : family.products) { if (p.plu == null) return true; } }
        if (family.productFamilies != null) { for (ProductFamily child : family.productFamilies) { if (hasManualProducts(child)) return true; } }
        return false;
    }
}