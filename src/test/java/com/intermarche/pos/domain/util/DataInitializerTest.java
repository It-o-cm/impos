package com.intermarche.pos.domain.util;

import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.catalog.*;
import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.store.Store;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DataInitializer}.
 * <p>
 * The seeder carries ONE branch — the guard that steps aside on a register
 * that already holds a referential — and both of its legs have their case:
 * the empty register seeded end to end, and the populated one left strictly
 * untouched. Past that guard it is branch-free (no conditional, ternary or
 * loop): it wipes the referential tables and rebuilds them through Panache
 * active-record calls.
 * Under plain {@code mvn test} the entities are not bytecode-enhanced, so the
 * static finders and {@code deleteAll} resolve to {@link PanacheEntityBase}
 * (intercepted with {@link org.mockito.Mockito#mockStatic}) and every
 * {@code new X()} is neutralized with {@link org.mockito.Mockito#mockConstruction}
 * so its {@code persist()} is a no-op. The {@link ProductFamily} mocks get their
 * {@code products}/{@code productFamilies} collections initialized, since the
 * mock constructor bypasses field initializers and the seeder wires the family
 * tree through those sets. A single end-to-end {@code onStart} run exercises
 * every line and every private helper; the assertions pin the absolute number
 * of persisted rows per entity type, the eight table wipes, Marie's light
 * theme override and the admin account's exemption from the forced password
 * change.
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
     * quantity, that the eight referential tables are wiped once, and that
     * Marie's cashier-level light-theme preference is applied, and that the
     * Intermarché nomenclature is seeded with its levels and its nodes.
     */
    @Test
    void onStartWipesAndReloadsTheReferential() {
        DataInitializer initializer = new DataInitializer();
        Employee marie = mock(Employee.class);
        Employee admin = mock(Employee.class);
        admin.mustChangePassword = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Employee> employees = mockConstruction(Employee.class);
             MockedConstruction<ProductFamily> families = mockConstruction(ProductFamily.class,
                     (family, ctx) -> {
                         family.products = new HashSet<>();
                         family.productFamilies = new HashSet<>();
                     });
             MockedConstruction<Product> products = mockConstruction(Product.class);
             MockedConstruction<Price> prices = mockConstruction(Price.class);
             MockedConstruction<VatRate> vatRates = mockConstruction(VatRate.class);
             MockedConstruction<CouponType> couponTypes = mockConstruction(CouponType.class);
             MockedConstruction<Store> stores = mockConstruction(Store.class);
             MockedConstruction<Nomenclature> schemes = mockConstruction(Nomenclature.class);
             MockedConstruction<NomenclatureLevel> levels =
                     mockConstruction(NomenclatureLevel.class)) {
            // The seeder files four articles under their sous-famille by name;
            // an unstubbed find would hand back null and the walk would stop
            // before the nomenclature is wired.
            // An empty register: the guard lets the seed through. Left
            // explicit rather than relying on the 0 a mocked static returns
            // by default, since that zero IS the leg under test.
            panache.when(() -> Product.count()).thenReturn(0L);
            PanacheQuery<Product> noArticle = mock(PanacheQuery.class);
            when(noArticle.firstResult()).thenReturn(null);
            panache.when(() -> Product.find(eq("name"), any(Object[].class)))
                    .thenReturn(noArticle);
            PanacheQuery<Employee> marieQuery = employeeQuery(marie);
            panache.when(() -> Employee.find("loginName", "mcurie")).thenReturn(marieQuery);
            PanacheQuery<Employee> adminQuery = employeeQuery(admin);
            panache.when(() -> Employee.find("loginName", "admin")).thenReturn(adminQuery);
            initializer.onStart(null);
            panache.verify(() -> Employee.deleteAll(), times(8));
            assertEquals(4, employees.constructed().size());
            // Ten touch groups plus the twenty-one seeded nomenclature nodes.
            assertEquals(31, families.constructed().size());
            // One scheme, its four levels: the screen opens on real data.
            assertEquals(1, schemes.constructed().size());
            assertEquals(4, levels.constructed().size());
            assertEquals(38, products.constructed().size());
            assertEquals(41, prices.constructed().size());
            // The five regimes of the French referential, written once before
            // any price names one.
            assertEquals(5, vatRates.constructed().size());
            assertEquals(8, couponTypes.constructed().size());
            assertEquals(1, stores.constructed().size());
            for (Employee employee : employees.constructed()) {
                verify(employee).persist();
            }
            // A touch group is persisted twice — once on creation, once after its
            // edges and flags are wired — while a nomenclature node is written
            // once, complete: it has no edge to wire, its parent being its own
            // code prefix.
            for (int i = 0; i < families.constructed().size(); i++) {
                ProductFamily family = families.constructed().get(i);
                verify(family, times(i < 10 ? 2 : 1)).persist();
            }
            for (Product product : products.constructed()) {
                verify(product).persist();
            }
            for (Price price : prices.constructed()) {
                verify(price).persist();
            }
            for (VatRate rate : vatRates.constructed()) {
                verify(rate).persist();
            }
            for (CouponType couponType : couponTypes.constructed()) {
                verify(couponType).persist();
            }
            verify(stores.constructed().get(0)).persist();
            assertEquals("clair", marie.theme);
            assertFalse(admin.mustChangePassword);
        }
    }

    /**
     * Drives {@code onStart} against a register that already holds articles
     * and verifies that NOTHING happens: no table is wiped and no row is
     * built. This is the leg that protects a demonstrator whose database
     * survives a restart — the referential pulled from the store node and
     * the sales of the day must not be replaced by the demonstration set,
     * and the wipe would fail anyway, the articles being referenced by their
     * families.
     */
    @Test
    void onStartLeavesAPopulatedRegisterAlone() {
        DataInitializer initializer = new DataInitializer();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Employee> employees = mockConstruction(Employee.class);
             MockedConstruction<ProductFamily> families = mockConstruction(ProductFamily.class);
             MockedConstruction<Product> products = mockConstruction(Product.class);
             MockedConstruction<Price> prices = mockConstruction(Price.class);
             MockedConstruction<VatRate> vatRates = mockConstruction(VatRate.class);
             MockedConstruction<CouponType> couponTypes = mockConstruction(CouponType.class);
             MockedConstruction<Store> stores = mockConstruction(Store.class);
             MockedConstruction<Nomenclature> schemes = mockConstruction(Nomenclature.class);
             MockedConstruction<NomenclatureLevel> levels =
                     mockConstruction(NomenclatureLevel.class)) {
            panache.when(() -> Product.count()).thenReturn(312L);
            initializer.onStart(null);
            panache.verify(() -> Employee.deleteAll(), never());
            assertEquals(0, employees.constructed().size());
            assertEquals(0, families.constructed().size());
            assertEquals(0, products.constructed().size());
            assertEquals(0, prices.constructed().size());
            assertEquals(0, vatRates.constructed().size());
            assertEquals(0, couponTypes.constructed().size());
            assertEquals(0, stores.constructed().size());
            assertEquals(0, schemes.constructed().size());
            assertEquals(0, levels.constructed().size());
        }
    }
}
