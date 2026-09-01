package com.intermarche.pos.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Entity representing a logical grouping of Products (ProductFamily).
 * <p>
 * (Historical note: modeled after the valuation engine's store grouping —
 * the stale cross-project link is gone, the two projects are separate.)
 * In this POS, families primarily drive the FRUITS &amp; LÉGUMES weighing
 * screen; {@code code} is the referential-sync upsert key and {@code flags}
 * carries display hints.
 * Supports a hierarchical (or graph) structure where a ProductFamily can contain:
 * <ul>
 *   <li>A list of {@link Product} entities (Leaves).</li>
 *   <li>A list of other {@link ProductFamily} entities (Sub-families).</li>
 * </ul>
 * <p>
 * <b>Multiple Parents:</b> A family (or a product) can belong to multiple parent families.
 * This requires traversing the structure as a Directed Acyclic Graph (DAG).
 * <p>
 * This entity extends {@link BaseEntity} to inherit ID, versioning, and audit fields.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "product_families",
        uniqueConstraints = @UniqueConstraint(columnNames = "code")
)
@Cacheable
public class ProductFamily extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Family code is mandatory")
    public String code;

    @Column(name = "description", length = 255)
    public String description;

    /**
     * A comma-separated string of tokens (flags) associated with this family.
     * Example: "ORGANIC,SEASONAL,FRUIT".
     */
    @Column(name = "flags", length = 1000)
    public String flags;

    // --------------------------------------------------
    // Relations: Direct Products
    // --------------------------------------------------

    /**
     * The list of products directly belonging to this family.
     * Unidirectional relationship via a foreign key in the 'products' table.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public Set<Product> products = new HashSet<>();

    // --------------------------------------------------
    // Relations: Child Families (Hierarchy)
    // --------------------------------------------------

    /**
     * The list of sub-families (ProductFamilies) contained within this family.
     * <p>
     * Unidirectional relationship via a foreign key in the 'product_families' table.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "parent_product_family_id") // FK in the child product_family row
    public Set<ProductFamily> productFamilies = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a ProductFamily by its unique code.
     *
     * @param code The family code.
     * @return The ProductFamily entity or null if not found.
     */
    public static ProductFamily findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Returns the DIRECT family a product belongs to — the family whose
     * {@code products} collection contains it — but ONLY when that family is
     * unambiguous. The model allows a product to be attached directly to
     * several families, and nothing in the product says which of them names a
     * sale; picking one would be inventing a rule, and the line snapshot is
     * permanent, so the invented rule would be frozen into every consolidated
     * line. An ambiguous product therefore leaves the line's nomenclature blank
     * until the rule is decided (campaign lot C3 / BO-04-01-11).
     * <p>
     * Also returns null when the product is null, unpersisted, or attached to
     * no family at all.
     *
     * @param product the sold product
     * @return the single direct family, or null when there is none or several
     */
    public static ProductFamily findDirectFamily(Product product) {
        if (product == null || product.id == null) {
            return null;
        }
        List<ProductFamily> direct = ProductFamily.<ProductFamily>find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id
        ).list();
        return direct.size() == 1 ? direct.get(0) : null;
    }

    /**
     * Retrieves all parent ProductFamilies for a given Product, traversing up the hierarchy.
     * <p>
     * This method supports graphs where a family can have multiple parents.
     * It performs a Depth-First Search (DFS) to find all ancestors.
     *
     * @param product The product to search for.
     * @return A Set of unique {@link ProductFamily}.
     *         Returns an empty Set if the product belongs to no family or if the product is null.
     */
    public static Set<ProductFamily> findAllFamiliesForProduct(Product product) {
        Set<ProductFamily> hierarchy = new HashSet<>();
        if (product == null || product.id == null) {
            return hierarchy;
        }

        // 1. Find all direct parents of the Product
        // Query: Select ProductFamily where the collection 'products' contains the specific product ID
        List<ProductFamily> directParents = find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id
        ).list();

        // 2. Recursively find all parent families for each direct parent
        for (ProductFamily parent : directParents) {
            findAncestorsRecursive(parent, hierarchy);
        }

        return hierarchy;
    }

    /**
     * Checks if a specific flag token is present in any of the families (direct or ancestor)
     * associated with the given product.
     *
     * @param product The product to check.
     * @param flag    The flag token to search for (case-sensitive).
     * @return true if the flag is found in the hierarchy of the product, false otherwise.
     */
    public static boolean productHasFlag(Product product, String flag) {
        if (product == null || flag == null || flag.isBlank()) {
            return false;
        }

        // Retrieve all families in the hierarchy
        Set<ProductFamily> families = findAllFamiliesForProduct(product);

        // Iterate through families to find the flag
        for (ProductFamily family : families) {
            if (family.hasFlag(flag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Recursive helper to find all ancestors of a specific family.
     * Handles cycles and multiple parents.
     *
     * @param family    The family whose parents we are looking for.
     * @param ancestors The accumulating set of ancestor families to avoid duplicates.
     */
    static void findAncestorsRecursive(ProductFamily family, Set<ProductFamily> ancestors) {
        // Stop if null, already visited, or missing ID
        if (ancestors.contains(family)) {
            return;
        }

        // Add current family to ancestors
        ancestors.add(family);

        // Find all parents of the current family
        // Query: Select Parent ProductFamily where the collection 'productFamilies' contains the current family
        List<ProductFamily> parents = find(
                "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1",
                family.id
        ).list();

        // Recurse for each parent found (handling multiple branches)
        for (ProductFamily parent : parents) {
            findAncestorsRecursive(parent, ancestors);
        }
    }

    // --------------------------------------------------
    // Flag Management Methods
    // --------------------------------------------------

    /**
     * Adds a token to the flags string if it is not already present.
     * <p>
     * The token is trimmed before being added. The flags string is updated automatically.
     *
     * @param token The token to add.
     */
    public void addFlag(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        Set<String> currentFlags = getFlagsSet();
        String trimmedToken = token.trim();

        // Set.add returns true if the set did not already contain the element
        if (currentFlags.add(trimmedToken)) {
            updateFlagsFromSet(currentFlags);
        }
    }

    /**
     * Removes a token from the flags string.
     * <p>
     * The token is trimmed before removal. If the flags list becomes empty,
     * the field is set to null.
     *
     * @param token The token to remove.
     */
    public boolean removeFlag(String token) {
        if (token == null) {
            return false;
        }

        Set<String> currentFlags = getFlagsSet();
        String trimmedToken = token.trim();

        if (currentFlags.remove(trimmedToken)) {
            if (currentFlags.isEmpty()) {
                this.flags = null;
            } else {
                updateFlagsFromSet(currentFlags);
            }
            return true;
        }
        return false;
    }

    /**
     * Checks if a specific token is present in the flags string.
     * <p>
     * The check is case-sensitive and ignores leading/trailing whitespace in both the stored flags and the query token.
     *
     * @param token The token to check for.
     * @return true if the token is present, false otherwise.
     */
    public boolean hasFlag(String token) {
        if (token == null || token.isBlank() || this.flags == null || this.flags.isBlank()) {
            return false;
        }
        return getFlagsSet().contains(token.trim());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Helper method to parse the comma-separated flags string into a Set.
     *
     * @return A Set of trimmed flag strings.
     */
    private Set<String> getFlagsSet() {
        return Arrays.stream(this.flags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Helper method to update the 'flags' string from a Set.
     *
     * @param flagSet The set of flags to serialize.
     */
    private void updateFlagsFromSet(Set<String> flagSet) {
        this.flags = String.join(",", flagSet);
    }

    @Override
    public int getChecksum() {
        // Excluding children from checksum for performance and stability.
        return Objects.hash(code, description, flags);
    }
}