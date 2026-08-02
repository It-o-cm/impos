package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.domain.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataInitializer}.
 * <p>
 * The seeder is branch-free (no conditional, ternary or loop): it wipes the
 * referential tables and rebuilds them through Panache active-record calls.
 * Under plain {@code mvn test} the entities are not bytecode-enhanced, so the
 * static finders and {@code deleteAll} resolve to {@link PanacheEntityBase}
 * (intercepted with {@link org.mockito.Mockito#mockStatic}) and every
 * {@code new X()} is neutralized with {@link org.mockito.Mockito#mockConstruction}
 * so its {@code persist()} is a no-op. The {@link ProductFamily} mocks get their
 * {@code products}/{@code productFamilies} collections initialized, since the
 * mock constructor bypasses field initializers and the seeder wires the family
 * tree through those sets. A single end-to-end {@code onStart} run exercises
 * every line and every private helper; the assertions pin the absolute number
 * of persisted rows per entity type, the five table wipes and Marie's light
 * theme override. The class carries 0 branches, so branch coverage is 0/0.
 */
class DataInitializerTest {

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult()} yields the
     * given employee, mirroring {@code Employee.find(...).firstResult()}.
     *
     * @param employee the employee to return, possibly null
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Employee> employeeQuery(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        return query;
    }

    /**
     * Drives {@code onStart} through a fully mocked Panache layer and verifies
     * that every entity type is constructed and persisted in the exact expected
     * quantity, that the five referential tables are wiped once, and that
     * Marie's cashier-level light-theme preference is applied.
     */
    @Test
    void onStartWipesAndReloadsTheReferential() {
        DataInitializer initializer = new DataInitializer();
        Employee marie = mock(Employee.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Employee> employees = mockConstruction(Employee.class);
             MockedConstruction<ProductFamily> families = mockConstruction(ProductFamily.class,
                     (family, ctx) -> {
                         family.products = new HashSet<>();
                         family.productFamilies = new HashSet<>();
                     });
             MockedConstruction<Product> products = mockConstruction(Product.class);
             MockedConstruction<Price> prices = mockConstruction(Price.class);
             MockedConstruction<CouponType> couponTypes = mockConstruction(CouponType.class);
             MockedConstruction<Store> stores = mockConstruction(Store.class)) {
            PanacheQuery<Employee> marieQuery = employeeQuery(marie);
            panache.when(() -> Employee.find("loginName", "mcurie")).thenReturn(marieQuery);
            initializer.onStart(null);
            panache.verify(() -> Employee.deleteAll(), times(5));
            assertEquals(4, employees.constructed().size());
            assertEquals(10, families.constructed().size());
            assertEquals(35, products.constructed().size());
            assertEquals(38, prices.constructed().size());
            assertEquals(6, couponTypes.constructed().size());
            assertEquals(1, stores.constructed().size());
            for (Employee employee : employees.constructed()) {
                verify(employee).persist();
            }
            for (ProductFamily family : families.constructed()) {
                verify(family, times(2)).persist();
            }
            for (Product product : products.constructed()) {
                verify(product).persist();
            }
            for (Price price : prices.constructed()) {
                verify(price).persist();
            }
            for (CouponType couponType : couponTypes.constructed()) {
                verify(couponType).persist();
            }
            verify(stores.constructed().get(0)).persist();
            assertEquals("clair", marie.theme);
        }
    }
}
