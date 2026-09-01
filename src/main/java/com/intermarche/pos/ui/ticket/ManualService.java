package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Feeds the SAISIE DIRECTE screen: a drill-down of the family tree showing
 * only the branches that lead to EAN-only products (PLU products are
 * excluded — their home is the weighing screen), so the cashier never
 * opens an empty category. Selection ends on an EAN, added through the
 * normal ticket path.
 * <p>
 * The group grid honours the back-office touch configuration (Lot 5F),
 * administered on the families and the SETTINGS domain and reaching the
 * register at the tirage:
 * <ul>
 *   <li>the display ORDER (BO-03-01-10/11/13) — alphabetical, custom rank or
 *       sales volume — set by {@code touch.display-order};</li>
 *   <li>the number of group touches PER PAGE (BO-03-01-06) set by
 *       {@code touch.groups-per-page}, with a previous/next pager;</li>
 *   <li>the PINNED groups (BO-03-01-07) shown permanently, on every page and
 *       every drilled-down level, whatever the navigation;</li>
 *   <li>the button SIZE (BO-03-01-08) carried as a CSS class on each tile.</li>
 * </ul>
 */
@ApplicationScoped
public class ManualService {

    /** The page size used when no settings service is wired (unit context). */
    static final int DEFAULT_PER_PAGE = 12;

    /**
     * The back-office parameters: the display order and page size. Null on a
     * bare instance built without CDI (a unit test that does not exercise the
     * configuration), in which case the defaults apply.
     */
    @Inject
    PosSettingsService posSettings;

    // DTOs internes
    /**
     * One tile of the drill-down: a category to open or a product to add.
     */
    public static class ManualItem {
        public String label; public boolean isCategory; public String url; public String ean;
        /** The size CSS class of the tile (BO-03-01-08). */
        public String sizeClass;
        /** Whether this tile is a pinned group shown permanently (BO-03-01-07). */
        public boolean pinned;

        /**
         * Creates a tile.
         *
         * @param label the tile label
         * @param isCategory true for a category
         * @param url the drill-down url, or null
         * @param ean the product EAN, or null
         * @param sizeClass the size CSS class of the tile
         * @param pinned whether the tile is a pinned group
         */
        public ManualItem(String label, boolean isCategory, String url, String ean,
                          String sizeClass, boolean pinned) {
            this.label = label; this.isCategory = isCategory; this.url = url; this.ean = ean;
            this.sizeClass = sizeClass; this.pinned = pinned;
        }
    }
    /**
     * One rendered level of the drill-down: tiles, breadcrumb, the way back up
     * and the pager of the group grid.
     */
    public static class ManualViewData {
        public List<ManualItem> items; public String breadcrumb; public boolean isRoot; public String parentUrl;
        /** The 1-based current page of the group grid. */
        public int page;
        /** The total number of pages of the group grid. */
        public int totalPages;
        /** The previous-page url, or null on the first page. */
        public String prevUrl;
        /** The next-page url, or null on the last page. */
        public String nextUrl;

        /**
         * Creates a rendered level.
         *
         * @param items the tiles
         * @param breadcrumb the breadcrumb text
         * @param isRoot true at the root
         * @param parentUrl the parent url, or null
         * @param page the 1-based current page
         * @param totalPages the total page count
         * @param prevUrl the previous-page url, or null
         * @param nextUrl the next-page url, or null
         */
        public ManualViewData(List<ManualItem> items, String breadcrumb, boolean isRoot, String parentUrl,
                              int page, int totalPages, String prevUrl, String nextUrl) {
            this.items = items; this.breadcrumb = breadcrumb; this.isRoot = isRoot; this.parentUrl = parentUrl;
            this.page = page; this.totalPages = totalPages; this.prevUrl = prevUrl; this.nextUrl = nextUrl;
        }
    }

    /**
     * Builds the root level: the TOP families of the tree (those that are
     * not a sub-family of any other) leading to at least one EAN-only
     * product. Pinned qualifying groups are shown first, permanently, then the
     * requested page of the remaining groups, both ordered by the configured
     * mode and each carrying its size class (Lot 5F).
     *
     * @param page the 1-based page requested for the non-pinned groups
     * @return the root level
     */
    public ManualViewData getManualRootData(int page) {
        List<ProductFamily> allFamilies = ProductFamily.listAll();
        Set<Long> childIds = collectChildIds(allFamilies);
        Comparator<ProductFamily> order = orderComparator(mode());
        List<ProductFamily> pinned = new ArrayList<>();
        List<ProductFamily> rest = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (childIds.contains(f.id) || !hasManualProducts(f)) {
                continue;
            }
            if (f.pinned) {
                pinned.add(f);
            } else {
                rest.add(f);
            }
        }
        pinned.sort(order);
        rest.sort(order);
        int perPage = perPage();
        int totalPages = rest.isEmpty() ? 1 : (rest.size() + perPage - 1) / perPage;
        int current = clamp(page, totalPages);
        List<ManualItem> items = new ArrayList<>();
        for (ProductFamily f : pinned) {
            items.add(categoryItem(f));
        }
        int from = (current - 1) * perPage;
        int to = Math.min(from + perPage, rest.size());
        for (int i = from; i < to; i++) {
            items.add(categoryItem(rest.get(i)));
        }
        String prevUrl = current > 1 ? "/manual?page=" + (current - 1) : null;
        String nextUrl = current < totalPages ? "/manual?page=" + (current + 1) : null;
        return new ManualViewData(items, "Accueil", true, null, current, totalPages, prevUrl, nextUrl);
    }

    /**
     * Builds one category level: the permanently pinned groups first (Lot 5F),
     * then qualifying sub-families ordered by the configured mode, then the
     * category's own EAN-only products.
     *
     * @param code the family code
     * @return the category level
     */
    public ManualViewData getManualCategoryData(String code) {
        List<ManualItem> items = new ArrayList<>();
        String breadcrumb = "Accueil";
        String parentUrl = "/manual";
        List<ProductFamily> allFamilies = ProductFamily.listAll();
        Set<Long> childIds = collectChildIds(allFamilies);
        Comparator<ProductFamily> order = orderComparator(mode());
        for (ProductFamily f : pinnedTopFamilies(allFamilies, childIds, order)) {
            items.add(categoryItem(f));
        }
        ProductFamily family = ProductFamily.findByCode(code);
        if (family != null) {
            breadcrumb = "Accueil > " + family.description;
            if (family.productFamilies != null) {
                List<ProductFamily> children = new ArrayList<>();
                for (ProductFamily child : family.productFamilies) {
                    if (hasManualProducts(child)) {
                        children.add(child);
                    }
                }
                children.sort(order);
                for (ProductFamily child : children) {
                    items.add(categoryItem(child));
                }
            }
            if (family.products != null) {
                for (Product p : family.products) {
                    if (p.plu == null) {
                        items.add(new ManualItem(p.name, false, null, p.ean, "size-normal", false));
                    }
                }
            }
        }
        return new ManualViewData(items, breadcrumb, false, parentUrl, 1, 1, null, null);
    }

    /**
     * Collects the ids of every family that is a sub-family of another, so a
     * child is never shown at the root.
     *
     * @param allFamilies every family
     * @return the ids of the child families
     */
    private Set<Long> collectChildIds(List<ProductFamily> allFamilies) {
        Set<Long> childIds = new HashSet<>();
        for (ProductFamily f : allFamilies) {
            for (ProductFamily child : f.productFamilies) {
                childIds.add(child.id);
            }
        }
        return childIds;
    }

    /**
     * Returns the pinned qualifying top families, ordered — the groups shown
     * permanently whatever the navigation (BO-03-01-07).
     *
     * @param allFamilies every family
     * @param childIds the ids of the child families
     * @param order the configured ordering
     * @return the ordered pinned top families
     */
    private List<ProductFamily> pinnedTopFamilies(List<ProductFamily> allFamilies, Set<Long> childIds,
                                                  Comparator<ProductFamily> order) {
        List<ProductFamily> pinned = new ArrayList<>();
        for (ProductFamily f : allFamilies) {
            if (f.pinned && !childIds.contains(f.id) && hasManualProducts(f)) {
                pinned.add(f);
            }
        }
        pinned.sort(order);
        return pinned;
    }

    /**
     * Builds a category tile for a family, carrying its size class.
     *
     * @param family the family
     * @return the category tile
     */
    private ManualItem categoryItem(ProductFamily family) {
        return new ManualItem(family.description, true, "/manual/cat/" + family.code, null,
                sizeClass(family.buttonSize), family.pinned);
    }

    /**
     * Maps a family button size to its grid CSS class (BO-03-01-08): a large or
     * small size, or the normal size for anything else.
     *
     * @param buttonSize the administered size, or null
     * @return the size CSS class
     */
    private String sizeClass(String buttonSize) {
        if ("LARGE".equalsIgnoreCase(buttonSize)) {
            return "size-large";
        }
        if ("SMALL".equalsIgnoreCase(buttonSize)) {
            return "size-small";
        }
        return "size-normal";
    }

    /**
     * The configured display-order mode, defaulting to alphabetical when no
     * settings service is wired.
     *
     * @return the order mode
     */
    private String mode() {
        return posSettings == null ? "ALPHA" : posSettings.touchDisplayOrder();
    }

    /**
     * The configured page size, defaulting to {@link #DEFAULT_PER_PAGE} when no
     * settings service is wired.
     *
     * @return the strictly positive page size
     */
    private int perPage() {
        return posSettings == null ? DEFAULT_PER_PAGE : posSettings.touchGroupsPerPage();
    }

    /**
     * Builds the family ordering for a mode: custom rank, sales volume
     * (descending) or alphabetical, each falling back to a case-insensitive
     * description order for ties and for the unknown mode.
     *
     * @param mode the order mode
     * @return the comparator
     */
    private Comparator<ProductFamily> orderComparator(String mode) {
        Comparator<ProductFamily> byDescription = Comparator.comparing(
                f -> f.description == null ? "" : f.description, String.CASE_INSENSITIVE_ORDER);
        if ("CUSTOM".equals(mode)) {
            return Comparator.comparingInt((ProductFamily f) -> f.displayOrder).thenComparing(byDescription);
        }
        if ("VOLUME".equals(mode)) {
            return Comparator.comparingLong((ProductFamily f) -> f.salesVolume).reversed()
                    .thenComparing(byDescription);
        }
        return byDescription;
    }

    /**
     * Clamps a requested page into the valid range for the total page count.
     *
     * @param page the requested 1-based page
     * @param totalPages the total page count
     * @return the page bounded to [1, totalPages]
     */
    private int clamp(int page, int totalPages) {
        if (page < 1) {
            return 1;
        }
        if (page > totalPages) {
            return totalPages;
        }
        return page;
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
