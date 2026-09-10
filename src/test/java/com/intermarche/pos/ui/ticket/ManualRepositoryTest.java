package com.intermarche.pos.ui.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.intermarche.pos.domain.Product;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

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
}
