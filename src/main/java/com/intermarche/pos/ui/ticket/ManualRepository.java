package com.intermarche.pos.ui.ticket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;

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
     * holding only those must not open an empty category.
     *
     * @return the ids of those families
     */
    public Set<Long> familiesHoldingProducts() {
        return new HashSet<>(Panache.getEntityManager().createQuery(
                "select distinct pf.id from ProductFamily pf join pf.products p where p.plu is null",
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
                        + " where pf.id = ?1 and p.plu is null", Long.class)
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
        return Panache.getEntityManager().createQuery(
                "select p from ProductFamily pf join pf.products p"
                        + " where pf.id = ?1 and p.plu is null order by p.name, p.ean", Product.class)
                .setParameter(1, familyId).setFirstResult(offset).setMaxResults(limit).getResultList();
    }
}
