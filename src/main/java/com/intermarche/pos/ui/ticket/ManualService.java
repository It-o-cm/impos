package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Feeds the SAISIE DIRECTE screen: a drill-down of the family tree showing
 * only the branches that lead to EAN-only products (PLU products are
 * excluded — their home is the weighing screen), so the cashier never
 * opens an empty category. Selection ends on an EAN, added through the
 * normal ticket path.
 */
@ApplicationScoped
public class ManualService {

    // DTOs internes
    /**
     * One tile of the drill-down: a category to open or a product to add.
     */
    public static class ManualItem {
        public String label; public boolean isCategory; public String url; public String ean;

        /**
         * Creates a tile.
         *
         * @param label the tile label
         * @param isCategory true for a category
         * @param url the drill-down url, or null
         * @param ean the product EAN, or null
         */
        public ManualItem(String label, boolean isCategory, String url, String ean) {
            this.label = label; this.isCategory = isCategory; this.url = url; this.ean = ean;
        }
    }
    /**
     * One rendered level of the drill-down: tiles, breadcrumb and the way
     * back up.
     */
    public static class ManualViewData {
        public List<ManualItem> items; public String breadcrumb; public boolean isRoot; public String parentUrl;

        /**
         * Creates a rendered level.
         *
         * @param items the tiles
         * @param breadcrumb the breadcrumb text
         * @param isRoot true at the root
         * @param parentUrl the parent url, or null
         */
        public ManualViewData(List<ManualItem> items, String breadcrumb, boolean isRoot, String parentUrl) {
            this.items = items; this.breadcrumb = breadcrumb; this.isRoot = isRoot; this.parentUrl = parentUrl;
        }
    }

    // Méthode getPluProducts déplacée vers FruitService

    /**
     * Builds the root level: the TOP families of the tree (those that are
     * not a sub-family of any other) leading to at least one EAN-only
     * product. Filtering the children out here is what makes the drill-down
     * a tree walk — before this filter every qualified family, child or
     * not, showed at the root, which duplicated whole branches once the
     * referential carried a real hierarchy.
     *
     * @return the root level
     */
    public ManualViewData getManualRootData() {
        List<ProductFamily> allFamilies = ProductFamily.listAll();
        java.util.Set<Long> childIds = new java.util.HashSet<>();
        for (ProductFamily f : allFamilies) {
            for (ProductFamily child : f.productFamilies) {
                childIds.add(child.id);
            }
        }
        List<ManualItem> items = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (!childIds.contains(f.id) && hasManualProducts(f)) {
                items.add(new ManualItem(f.description, true, "/manual/cat/" + f.code, null));
            }
        }
        return new ManualViewData(items, "Accueil", true, null);
    }

    /**
     * Builds one category level: qualifying sub-families first, then the
     * category's own EAN-only products.
     *
     * @param code the family code
     * @return the category level
     */
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

    /**
     * Tells whether a family leads, directly or through descendants, to an
     * EAN-only product.
     *
     * @param family the family to probe
     * @return true when the branch is worth showing
     */
    private boolean hasManualProducts(ProductFamily family) {
        if (family.products != null) { for (Product p : family.products) { if (p.plu == null) return true; } }
        if (family.productFamilies != null) { for (ProductFamily child : family.productFamilies) { if (hasManualProducts(child)) return true; } }
        return false;
    }
}