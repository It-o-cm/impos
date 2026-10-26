package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.catalog.Product;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link ManualRepository}, the SAISIE DIRECTE database seam.
 * <p>
 * The class holds no decision of its own: every method delegates to a JPQL query
 * obtained through {@link Panache#getEntityManager()}. The only bytecode branch is
 * the enhanced-for in {@link ManualRepository#parentByChild()}, whose
 * {@code Iterator.hasNext()} yields the two arms (enter the body / leave the loop).
 * Both arms are exercised: a non-empty result set flows through the body then exits,
 * and an empty result set leaves at once. Every collaborator is a Mockito mock and
 * no database, Quarkus boot or Panache enhancement is involved.
 */
class ManualRepositoryTest {

    /**
     * parentByChild — non-empty arm: the {@code hasNext()} true leg is taken, each
     * link is inverted into a child-to-parent entry, and the loop is left afterwards.
     */
    @Test
    void parentByChildMapsEveryChildToItsParent() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Object[]> query = Mockito.mock(TypedQuery.class);
        List<Object[]> links = new ArrayList<>();
        links.add(new Object[] {10L, 20L});
        links.add(new Object[] {10L, 30L});
        Mockito.when(query.getResultList()).thenReturn(links);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Object[].class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            Map<Long, Long> result = new ManualRepository().parentByChild();
            Assertions.assertEquals(2, result.size());
            Assertions.assertEquals(10L, result.get(20L));
            Assertions.assertEquals(10L, result.get(30L));
        }
    }

    /**
     * parentByChild — empty arm: with no link the {@code hasNext()} false leg is taken
     * immediately, the body never runs and an empty map is returned.
     */
    @Test
    void parentByChildReturnsEmptyMapWhenNoLinks() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Object[]> query = Mockito.mock(TypedQuery.class);
        Mockito.when(query.getResultList()).thenReturn(new ArrayList<>());
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Object[].class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            Map<Long, Long> result = new ManualRepository().parentByChild();
            Assertions.assertTrue(result.isEmpty());
        }
    }

    /**
     * familiesHoldingProducts — the queried family ids are wrapped into a Set,
     * de-duplicating the raw list.
     */
    @Test
    void familiesHoldingProductsWrapsIdsIntoASet() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Long> query = Mockito.mock(TypedQuery.class);
        Mockito.when(query.getResultList()).thenReturn(List.of(1L, 2L, 2L, 3L));
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Long.class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            Set<Long> result = new ManualRepository().familiesHoldingProducts();
            Assertions.assertEquals(Set.of(1L, 2L, 3L), result);
        }
    }

    /**
     * countProducts — the family id is bound as parameter 1 and the scalar count is
     * returned verbatim.
     */
    @Test
    void countProductsReturnsTheScalarCount() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Long> query = Mockito.mock(TypedQuery.class);
        Mockito.when(query.setParameter(Mockito.anyInt(), Mockito.any())).thenReturn(query);
        Mockito.when(query.getSingleResult()).thenReturn(7L);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Long.class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            long result = new ManualRepository().countProducts(42L);
            Assertions.assertEquals(7L, result);
            Mockito.verify(query).setParameter(1, 42L);
        }
    }

    /**
     * products — the family id, offset and limit are applied (bind, first result, max
     * results) and the resulting product page is returned as-is.
     */
    @Test
    void productsReadsThePageWithOffsetAndLimit() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Product> query = Mockito.mock(TypedQuery.class);
        Product product = Mockito.mock(Product.class);
        List<Product> page = List.of(product);
        Mockito.when(query.setParameter(Mockito.anyInt(), Mockito.any())).thenReturn(query);
        Mockito.when(query.setFirstResult(Mockito.anyInt())).thenReturn(query);
        Mockito.when(query.setMaxResults(Mockito.anyInt())).thenReturn(query);
        Mockito.when(query.getResultList()).thenReturn(page);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Product.class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            List<Product> result = new ManualRepository().products(42L, 20, 10);
            Assertions.assertSame(page, result);
            Mockito.verify(query).setParameter(1, 42L);
            Mockito.verify(query).setFirstResult(20);
            Mockito.verify(query).setMaxResults(10);
        }
    }
    /** LC-02-07-11: the displayed price is the current sale price, preserving null amounts and absent price rows. */
    @Test
    void currentPriceUsesTheSalePriceResolver() {
        var repository = new ManualRepository();
        var price = new com.intermarche.pos.domain.catalog.Price();
        price.priceIncludingTax = new java.math.BigDecimal("2.345");
        try (var prices = Mockito.mockStatic(com.intermarche.pos.domain.catalog.Price.class)) {
            prices.when(() -> com.intermarche.pos.domain.catalog.Price.findCurrentPrice(7L)).thenReturn(price);
            Assertions.assertEquals(new java.math.BigDecimal("2.345"), repository.currentPrice(7L));
            price.priceIncludingTax = null;
            Assertions.assertNull(repository.currentPrice(7L));
            Assertions.assertNull(repository.currentPrice(8L));
        }
    }


    /** LC-02-07-03: SQL ordering precedes pagination, binds EANs as data and retains deterministic fallback. */
    @Test
    void productsApplyAdministeredPrioritiesBeforePaging() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Product> query = Mockito.mock(TypedQuery.class, Mockito.RETURNS_SELF);
        Mockito.when(query.getResultList()).thenReturn(List.of());
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Product.class))).thenReturn(query);
        ManualRepository repository = new ManualRepository();
        repository.posSettings = Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            Mockito.when(repository.posSettings.touchArticleOrder()).thenReturn(List.of("300", "100"));
            repository.products(42L, 4, 6);
            Mockito.verify(em).createQuery("select p from ProductFamily pf join pf.products p"
                    + " where pf.id = ?1 and p.plu is null and p.active = true order by case p.ean when ?2 then 0 when ?3 then 1 else 2 end, p.name, p.ean", Product.class);
            Mockito.verify(query).setParameter(2, "300");
            Mockito.verify(query).setParameter(3, "100");
            Mockito.verify(query).setFirstResult(4);
            Mockito.verify(query).setMaxResults(6);
            Mockito.when(repository.posSettings.touchArticleOrder()).thenReturn(List.of());
            repository.products(42L, 0, 6);
            Mockito.verify(em).createQuery("select p from ProductFamily pf join pf.products p"
                    + " where pf.id = ?1 and p.plu is null and p.active = true order by p.name, p.ean", Product.class);
        }
    }

    /** LC-02-07-18: each temporary order bypasses BackOffice priorities and orders before page slicing. */
    @Test
    void temporarySortUsesFixedSqlOrdersAndNeverReadsAllProducts() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Product> query = Mockito.mock(TypedQuery.class, Mockito.RETURNS_SELF);
        Mockito.when(query.getResultList()).thenReturn(List.of());
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Product.class))).thenReturn(query);
        ManualRepository repository = new ManualRepository();
        repository.posSettings = Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        var orders = Map.of(ManualService.ArticleSort.NAME_ASC, "p.name, p.ean",
                ManualService.ArticleSort.NAME_DESC, "p.name desc, p.ean",
                ManualService.ArticleSort.CODE_ASC, "p.ean, p.name",
                ManualService.ArticleSort.CODE_DESC, "p.ean desc, p.name");
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            for (var entry : orders.entrySet()) {
                repository.products(42L, 10, 5, entry.getKey());
                Mockito.verify(em).createQuery("select p from ProductFamily pf join pf.products p"
                        + " where pf.id = ?1 and p.plu is null and p.active = true order by " + entry.getValue(), Product.class);
            }
            Mockito.verify(query, Mockito.times(4)).setFirstResult(10);
            Mockito.verify(query, Mockito.times(4)).setMaxResults(5);
            Mockito.verifyNoInteractions(repository.posSettings);
        }
    }

    /**
     * BO-03-01-13: with the sales-volume order administered, a group entered
     * without a temporary order shows its best sellers first, after the
     * administered priorities, the label breaking ties.
     */
    @Test
    void theAdministeredVolumeOrderPutsTheBestSellersFirst() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Product> query = Mockito.mock(TypedQuery.class, Mockito.RETURNS_SELF);
        Mockito.when(query.getResultList()).thenReturn(List.of());
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Product.class))).thenReturn(query);
        ManualRepository repository = new ManualRepository();
        repository.posSettings = Mockito.mock(com.intermarche.pos.service.PosSettingsService.class);
        Mockito.when(repository.posSettings.touchArticleOrder()).thenReturn(List.of());
        Mockito.when(repository.posSettings.touchArticleSortVolume()).thenReturn(true);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            repository.products(42L, 0, 6);
            Mockito.verify(em).createQuery("select p from ProductFamily pf join pf.products p"
                    + " where pf.id = ?1 and p.plu is null and p.active = true"
                    + " order by p.salesVolume desc, p.name, p.ean", Product.class);
        }
    }

    /**
     * BO-03-01-17: a deactivated article is never counted nor read, so neither
     * the group touches nor the article tiles of any register show it.
     */
    @Test
    void deactivatedArticlesAreNeitherCountedNorHeld() {
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        TypedQuery<Long> query = Mockito.mock(TypedQuery.class, Mockito.RETURNS_SELF);
        Mockito.when(query.getResultList()).thenReturn(List.of());
        Mockito.when(query.getSingleResult()).thenReturn(0L);
        Mockito.when(em.createQuery(Mockito.anyString(), Mockito.eq(Long.class))).thenReturn(query);
        try (MockedStatic<Panache> panache = Mockito.mockStatic(Panache.class)) {
            panache.when(Panache::getEntityManager).thenReturn(em);
            ManualRepository repository = new ManualRepository();
            repository.familiesHoldingProducts();
            repository.countProducts(5L);
            Mockito.verify(em).createQuery("select distinct pf.id from ProductFamily pf join pf.products p"
                    + " where p.plu is null and p.active = true", Long.class);
            Mockito.verify(em).createQuery("select count(p) from ProductFamily pf join pf.products p"
                    + " where pf.id = ?1 and p.plu is null and p.active = true", Long.class);
        }
    }

}
