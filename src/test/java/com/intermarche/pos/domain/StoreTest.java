package com.intermarche.pos.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Store}, targeting 100% branch coverage.
 * <p>
 * The static {@code findByCode} finder resolves the Panache {@code find}
 * query, which under plain {@code mvn test} falls back to
 * {@link PanacheEntityBase}, so it is intercepted with
 * {@link org.mockito.Mockito#mockStatic} together with a mock
 * {@link PanacheQuery}. The {@code getChecksum} ternary is exercised on both
 * the null and non-null address arms on plain instances. Each test is fully
 * isolated and asserts absolute expected values.
 */
class StoreTest {

    /**
     * Builds a fully populated address value object for checksum assertions.
     *
     * @return a non-null address with every field set
     */
    private Address sampleAddress() {
        return new Address("10 Rue Centrale", "Bat A", "69001", "Lyon",
                "France", 45.76, 4.83);
    }

    /**
     * findByCode delegates to the code finder and returns its first result.
     */
    @Test
    void findByCodeDelegatesToFinder() {
        Store expected = new Store();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(expected);
            panache.when(() -> Store.find("code", "0034")).thenReturn(query);
            Assertions.assertSame(expected, Store.findByCode("0034"));
        }
    }

    /**
     * findByCode propagates a null first result when no row matches.
     */
    @Test
    void findByCodeReturnsNullWhenNoMatch() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Store> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Store.find("code", "9999")).thenReturn(query);
            Assertions.assertNull(Store.findByCode("9999"));
        }
    }

    /**
     * getChecksum substitutes zero for a null address (ternary null arm)
     * and matches the reference hash.
     */
    @Test
    void getChecksumWithNullAddressUsesZero() {
        Store store = new Store();
        store.code = "0034";
        store.name = "Intermarche Lyon Centre";
        store.address = null;
        int expected = Objects.hash("0034", "Intermarche Lyon Centre", 0);
        Assertions.assertEquals(expected, store.getChecksum());
    }

    /**
     * getChecksum folds in the address checksum for a non-null address
     * (ternary non-null arm) and matches the reference hash.
     */
    @Test
    void getChecksumWithNonNullAddressUsesAddressChecksum() {
        Store store = new Store();
        store.code = "0034";
        store.name = "Intermarche Lyon Centre";
        store.address = sampleAddress();
        int expected = Objects.hash("0034", "Intermarche Lyon Centre",
                sampleAddress().getChecksum());
        Assertions.assertEquals(expected, store.getChecksum());
    }
}
