package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;

/**
 * Every database read the SAISIE DIRECTE screen needs, and nothing else.
 * <p>
 * This class exists as a SEAM. The screen used to walk the family graph in Java,
 * which is unit-testable but collapses on a real catalogue: asking a family whether
 * it holds an article materialises its whole product collection, thousands of
 * entities loaded to answer a yes-or-no. Doing it in SQL fixes that but makes the
 * service untestable without a database, and this project does not want a database
 * in its unit tests. So the queries live here, behind four methods a test can
 * substitute with a handful of maps.
 * <p>
 * Nothing above this class knows JPQL, and nothing in this class decides anything:
 * ordering, paging and what a page may hold belong to {@link ManualService}.
 */
@ApplicationScoped
public class ManualRepository {

    /** LC-02-07-03: distributed article priorities, administered at the existing five levels. */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettings;


    /**
     * Maps every sub-family to its parent.
     * <p>
     * One query for the whole tree. It answers two questions at once: which families
     * are children — those must not appear at the root — and where a family sits, so
     * that "leads to an article" can be carried upwards.
     *
     * @return the parent of each child family, by child id
     */
    public Map<Long, Long> parentByChild() {
        List<Object[]> links = Panache.getEntityManager().createQuery(
                "select parent.id, child.id from ProductFamily parent join parent.productFamilies child",
                Object[].class).getResultList();
        Map<Long, Long> parentOf = new HashMap<>();
        for (Object[] link : links) {
            parentOf.put((Long) link[1], (Long) link[0]);
        }
        return parentOf;
    }

    /**
     * The families that DIRECTLY hold at least one EAN-only product.
     * <p>
     * PLU products are excluded: their home is the weighing screen, and a family
     * holding only those must not open an empty category. A DEACTIVATED article
     * is excluded too (BO-03-01-17): it no longer appears in the groups of any
     * register of the shop, and it is back, image and all, the moment the back
     * office reactivates it — nothing was deleted, only filtered.
     *
     * @return the ids of those families
     */
    public Set<Long> familiesHoldingProducts() {
        return new HashSet<>(Panache.getEntityManager().createQuery(
                "select distinct pf.id from ProductFamily pf join pf.products p"
                        + " where p.plu is null and p.active = true",
                Long.class).getResultList());
    }

    /**
     * Counts the EAN-only products of one family, without loading them.
     *
     * @param familyId the family id
     * @return how many products the category shows
     */
    public long countProducts(Long familyId) {
        return Panache.getEntityManager().createQuery(
                "select count(p) from ProductFamily pf join pf.products p"
                        + " where pf.id = ?1 and p.plu is null and p.active = true", Long.class)
                .setParameter(1, familyId).getSingleResult();
    }

    /**
     * Reads one page of the EAN-only products of a family.
     * <p>
     * Ordered by name then EAN: the association is a Set, so without an explicit
     * order the page boundaries would move between two requests and an article could
     * show twice, or never.
     *
     * @param familyId the family id
     * @param offset   how many products to skip
     * @param limit    how many products to read
     * @return the products of that page
     */
    public List<Product> products(Long familyId, int offset, int limit) {
        return products(familyId, offset, limit, ManualService.ArticleSort.DEFAULT);
    }

    /**
     * LC-02-07-18: selects the complete group's order in SQL before applying page boundaries.
     * <p>
     * The order applied when no temporary one is chosen at the till is the
     * administered one (BO-03-01-10, BO-03-01-13): alphabetical by label, or best
     * seller first by the sales volume the store node computed — the
     * administered article priorities ahead of either. Deactivated articles are
     * never read (BO-03-01-17).
     * @param familyId family identity
     * @param offset products to skip
     * @param limit products to read
     * @param sort safe screen ordering choice
     * @return the requested page without materializing the whole group
     */
    public List<Product> products(Long familyId, int offset, int limit, ManualService.ArticleSort sort) {
        List<String> priorities = sort != ManualService.ArticleSort.DEFAULT || posSettings == null
                ? List.of() : posSettings.touchArticleOrder();
        StringBuilder order = new StringBuilder();
        if (!priorities.isEmpty()) {
            order.append("case p.ean ");
            for (int i = 0; i < priorities.size(); i++) {
                order.append("when ?").append(i + 2).append(" then ").append(i).append(' ');
            }
            order.append("else ").append(priorities.size()).append(" end, ");
        }
        String articleOrder = switch (sort) {
            case DEFAULT -> posSettings != null && posSettings.touchArticleSortVolume()
                    ? "p.salesVolume desc, p.name, p.ean" : "p.name, p.ean";
            case NAME_ASC -> "p.name, p.ean";
            case NAME_DESC -> "p.name desc, p.ean";
            case CODE_ASC -> "p.ean, p.name";
            case CODE_DESC -> "p.ean desc, p.name";
        };
        var query = Panache.getEntityManager().createQuery(
                "select p from ProductFamily pf join pf.products p"
                        + " where pf.id = ?1 and p.plu is null and p.active = true order by "
                        + order + articleOrder, Product.class)
                .setParameter(1, familyId).setFirstResult(offset).setMaxResults(limit);
        for (int i = 0; i < priorities.size(); i++) {
            query.setParameter(i + 2, priorities.get(i));
        }
        return query.getResultList();
    }
    /** LC-02-07-11: resolves the same current-price window and priority used when the article is sold.
     * @param productId the article identity
     * @return its current tax-included amount, or null when unpriced
     */
    public java.math.BigDecimal currentPrice(Long productId) {
        var price = com.intermarche.pos.domain.catalog.Price.findCurrentPrice(productId);
        return price == null ? null : price.priceIncludingTax;
    }
}

