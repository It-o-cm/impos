package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.domain.ProductType;
import com.intermarche.pos.domain.RefState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefApplyService}.
 * <p>
 * The service is a Panache active-record consumer: every domain method
 * upserts entities through static finders ({@code X.find(...)},
 * {@code X.listAll()}, {@code Price.deleteAll()}) and persists them. All
 * static access is intercepted with {@link org.mockito.Mockito#mockStatic}
 * on {@link PanacheEntityBase}, and every {@code new X()} on the insert path
 * is neutralized with {@link org.mockito.Mockito#mockConstruction} so the
 * constructed instance is a mock whose {@code persist()} is a no-op. Found
 * and listed entities are plain Mockito mocks (fields read/written directly,
 * {@code persist()} inert). No database and no Quarkus context is booted.
 * <p>
 * Every branch is covered: {@code applyFamilies} (insert / update arms),
 * {@code applyProducts} (insert / update, the {@code productType} ternary
 * both arms, and the three deactivation arms — seen, absent-active,
 * absent-inactive), {@code applyPrices} (the {@code productEan} ternary both
 * arms, product-not-found via null-EAN and via a missing row, the
 * {@code priority} ternary both arms, and both arms of the shared
 * {@code parse} helper via start/end timestamps), {@code applyEmployees}
 * (insert / update, the {@code theme == null} guard both arms, and the three
 * deactivation arms), {@code applyCouponTypes} (insert / update and the three
 * deactivation arms), {@code recordApplied} (insert / update) and
 * {@code lastApplied} (present / absent). Branch enumeration: 26 two-way
 * decision points (52 branches), every arm exercised — 100%.
 */
class RefApplyServiceTest {

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * value.
     *
     * @param result the value the query must return
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // applyFamilies
    // --------------------------------------------------

    /**
     * Covers both arms of {@code applyFamilies}: the first row has no existing
     * family (insert path, constructed instance gets its code) while the second
     * matches an existing family (update path), and both are populated and
     * persisted.
     */
    @Test
    void applyFamiliesInsertsAndUpdates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.FamilyDto insert = new RefPayloads.FamilyDto();
        insert.code = "F1";
        insert.description = "Fruits";
        insert.flags = "BIO";
        RefPayloads.FamilyDto update = new RefPayloads.FamilyDto();
        update.code = "F2";
        update.description = "Légumes";
        update.flags = "LOCAL";
        ProductFamily existing = mock(ProductFamily.class);
        PanacheQuery<ProductFamily> absentQuery = queryReturning(null);
        PanacheQuery<ProductFamily> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<ProductFamily> created = mockConstruction(ProductFamily.class)) {
            mocked.when(() -> ProductFamily.find("code", "F1")).thenReturn(absentQuery);
            mocked.when(() -> ProductFamily.find("code", "F2")).thenReturn(existingQuery);
            service.applyFamilies(List.of(insert, update));
            ProductFamily inserted = created.constructed().get(0);
            assertEquals("F1", inserted.code);
            assertEquals("Fruits", inserted.description);
            assertEquals("BIO", inserted.flags);
            verify(inserted, times(1)).persist();
            assertEquals("Légumes", existing.description);
            assertEquals("LOCAL", existing.flags);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // applyProducts
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyProducts}: an inserted row with a
     * non-null {@code productType} (ternary true arm), an updated row with a
     * null {@code productType} (ternary false arm), then the three
     * deactivation arms — a seen product left untouched, an absent active
     * product deactivated, and an absent inactive product left untouched.
     */
    @Test
    void applyProductsInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.ProductDto insert = new RefPayloads.ProductDto();
        insert.ean = "E1";
        insert.plu = "100";
        insert.name = "Pomme";
        insert.description = "desc";
        insert.icon = "icon";
        insert.brand = "brand";
        insert.referenceWeight = new BigDecimal("1.000");
        insert.referenceVolume = new BigDecimal("2.000");
        insert.productType = "WEIGHT";
        insert.unitName = "kg";
        insert.active = true;
        insert.forbiddenToSale = false;
        RefPayloads.ProductDto update = new RefPayloads.ProductDto();
        update.ean = "E2";
        update.productType = null;
        update.active = true;
        Product existing = mock(Product.class);
        Product seen = mock(Product.class);
        seen.ean = "E1";
        seen.active = true;
        Product absentActive = mock(Product.class);
        absentActive.ean = "Z1";
        absentActive.active = true;
        Product absentInactive = mock(Product.class);
        absentInactive.ean = "Z2";
        absentInactive.active = false;
        PanacheQuery<Product> absentQuery = queryReturning(null);
        PanacheQuery<Product> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Product> created = mockConstruction(Product.class)) {
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(absentQuery);
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(existingQuery);
            mocked.when(Product::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyProducts(List.of(insert, update));
            Product inserted = created.constructed().get(0);
            assertEquals("E1", inserted.ean);
            assertEquals(ProductType.WEIGHT, inserted.productType);
            assertEquals("Pomme", inserted.name);
            verify(inserted, times(1)).persist();
            assertNull(existing.productType);
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --------------------------------------------------
    // applyPrices
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyPrices}: the whole table is cleared, one
     * row references an existing product with a non-null priority and a
     * non-null start / null end (parse both arms), one row references an
     * existing product with a null priority (defaulted to zero) and a null
     * start / non-null end, one row carries a null EAN (ternary false arm,
     * skipped) and one row references a missing product (skipped).
     */
    @Test
    void applyPricesReplacesAndSkipsOrphans() {
        RefApplyService service = new RefApplyService();
        RefPayloads.PriceDto priced = new RefPayloads.PriceDto();
        priced.productEan = "E1";
        priced.priceExcludingTax = new BigDecimal("1.00");
        priced.priceIncludingTax = new BigDecimal("1.20");
        priced.vatRate = new BigDecimal("0.2000");
        priced.priority = 5;
        priced.startDateTime = "2026-01-01T10:00:00";
        priced.endDateTime = null;
        RefPayloads.PriceDto defaulted = new RefPayloads.PriceDto();
        defaulted.productEan = "E2";
        defaulted.priceExcludingTax = new BigDecimal("2.00");
        defaulted.priceIncludingTax = new BigDecimal("2.20");
        defaulted.vatRate = new BigDecimal("0.1000");
        defaulted.priority = null;
        defaulted.startDateTime = null;
        defaulted.endDateTime = "2026-12-31T23:59:59";
        RefPayloads.PriceDto nullEan = new RefPayloads.PriceDto();
        nullEan.productEan = null;
        RefPayloads.PriceDto orphan = new RefPayloads.PriceDto();
        orphan.productEan = "E9";
        Product p1 = mock(Product.class);
        Product p2 = mock(Product.class);
        PanacheQuery<Product> q1 = queryReturning(p1);
        PanacheQuery<Product> q2 = queryReturning(p2);
        PanacheQuery<Product> qOrphan = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Price> created = mockConstruction(Price.class)) {
            mocked.when(() -> Product.find("ean", "E1")).thenReturn(q1);
            mocked.when(() -> Product.find("ean", "E2")).thenReturn(q2);
            mocked.when(() -> Product.find("ean", "E9")).thenReturn(qOrphan);
            service.applyPrices(List.of(priced, defaulted, nullEan, orphan));
            mocked.verify(Price::deleteAll, times(1));
            assertEquals(2, created.constructed().size());
            Price first = created.constructed().get(0);
            assertSame(p1, first.product);
            assertEquals(5, first.priority);
            assertEquals(LocalDateTime.of(2026, 1, 1, 10, 0, 0), first.startDateTime);
            assertNull(first.endDateTime);
            verify(first, times(1)).persist();
            Price second = created.constructed().get(1);
            assertSame(p2, second.product);
            assertEquals(0, second.priority);
            assertNull(second.startDateTime);
            assertEquals(LocalDateTime.of(2026, 12, 31, 23, 59, 59), second.endDateTime);
            verify(second, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // applyEmployees
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyEmployees}: an inserted employee whose
     * fresh theme is null so the pulled theme seeds it (guard true arm), an
     * updated employee whose local theme survives the pull (guard false arm),
     * then the three deactivation arms — a seen employee left untouched, an
     * absent active employee deactivated, and an absent inactive employee left
     * untouched.
     */
    @Test
    void applyEmployeesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.EmployeeDto insert = new RefPayloads.EmployeeDto();
        insert.loginName = "alice";
        insert.firstName = "Alice";
        insert.lastName = "Martin";
        insert.password = "hash-alice";
        insert.email = "alice@x.fr";
        insert.role = "CASHIER";
        insert.badgeId = "B1";
        insert.theme = "dark";
        insert.active = true;
        RefPayloads.EmployeeDto update = new RefPayloads.EmployeeDto();
        update.loginName = "bob";
        update.firstName = "Bob";
        update.lastName = "Durand";
        update.password = "hash-bob";
        update.email = "bob@x.fr";
        update.role = "MANAGER";
        update.badgeId = "B2";
        update.theme = "dark";
        update.active = true;
        Employee existing = mock(Employee.class);
        existing.theme = "light";
        Employee seen = mock(Employee.class);
        seen.loginName = "alice";
        seen.active = true;
        Employee absentActive = mock(Employee.class);
        absentActive.loginName = "gone";
        absentActive.active = true;
        Employee absentInactive = mock(Employee.class);
        absentInactive.loginName = "old";
        absentInactive.active = false;
        PanacheQuery<Employee> absentQuery = queryReturning(null);
        PanacheQuery<Employee> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Employee> created = mockConstruction(Employee.class)) {
            mocked.when(() -> Employee.find("loginName", "alice")).thenReturn(absentQuery);
            mocked.when(() -> Employee.find("loginName", "bob")).thenReturn(existingQuery);
            mocked.when(Employee::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyEmployees(List.of(insert, update));
            Employee inserted = created.constructed().get(0);
            assertEquals("alice", inserted.loginName);
            assertEquals(Employee.EmployeeRole.CASHIER, inserted.role);
            assertEquals("dark", inserted.theme);
            verify(inserted, times(1)).persist();
            assertEquals("light", existing.theme);
            assertEquals(Employee.EmployeeRole.MANAGER, existing.role);
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --------------------------------------------------
    // applyCouponTypes
    // --------------------------------------------------

    /**
     * Covers every arm of {@code applyCouponTypes}: an inserted coupon type
     * (ENCODED source), an updated one (MANUAL source), then the three
     * deactivation arms — a seen type left untouched, an absent active type
     * deactivated, and an absent inactive type left untouched.
     */
    @Test
    void applyCouponTypesInsertsUpdatesAndDeactivates() {
        RefApplyService service = new RefApplyService();
        RefPayloads.CouponTypeDto insert = new RefPayloads.CouponTypeDto();
        insert.code = "C1";
        insert.label = "Bon 5€";
        insert.matchPattern = "^99.*";
        insert.amountSource = "ENCODED";
        insert.amountPattern = "(\\d+)";
        insert.priority = 10;
        insert.active = true;
        insert.depositLine = false;
        RefPayloads.CouponTypeDto update = new RefPayloads.CouponTypeDto();
        update.code = "C2";
        update.label = "Bon manuel";
        update.matchPattern = "";
        update.amountSource = "MANUAL";
        update.amountPattern = null;
        update.priority = 20;
        update.active = true;
        update.depositLine = true;
        CouponType existing = mock(CouponType.class);
        CouponType seen = mock(CouponType.class);
        seen.code = "C1";
        seen.active = true;
        CouponType absentActive = mock(CouponType.class);
        absentActive.code = "Z1";
        absentActive.active = true;
        CouponType absentInactive = mock(CouponType.class);
        absentInactive.code = "Z2";
        absentInactive.active = false;
        PanacheQuery<CouponType> absentQuery = queryReturning(null);
        PanacheQuery<CouponType> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<CouponType> created = mockConstruction(CouponType.class)) {
            mocked.when(() -> CouponType.find("code", "C1")).thenReturn(absentQuery);
            mocked.when(() -> CouponType.find("code", "C2")).thenReturn(existingQuery);
            mocked.when(CouponType::listAll).thenReturn(List.of(seen, absentActive, absentInactive));
            service.applyCouponTypes(List.of(insert, update));
            CouponType inserted = created.constructed().get(0);
            assertEquals("C1", inserted.code);
            assertEquals(CouponType.AmountSource.ENCODED, inserted.amountSource);
            verify(inserted, times(1)).persist();
            assertEquals(CouponType.AmountSource.MANUAL, existing.amountSource);
            assertTrue(existing.depositLine);
            verify(existing, times(1)).persist();
            assertTrue(seen.active);
            verify(seen, never()).persist();
            assertFalse(absentActive.active);
            verify(absentActive, times(1)).persist();
            assertFalse(absentInactive.active);
            verify(absentInactive, never()).persist();
        }
    }

    // --------------------------------------------------
    // recordApplied
    // --------------------------------------------------

    /**
     * Covers the insert arm of {@code recordApplied}: no state row exists for
     * the domain, so one is constructed, stamped and persisted.
     */
    @Test
    void recordAppliedInsertsWhenAbsent() {
        RefApplyService service = new RefApplyService();
        PanacheQuery<RefState> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<RefState> created = mockConstruction(RefState.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(absentQuery);
            service.recordApplied("products", "fp-123");
            RefState state = created.constructed().get(0);
            assertEquals("products", state.domain);
            assertEquals("fp-123", state.fingerprint);
            assertTrue(state.appliedAt != null);
            verify(state, times(1)).persist();
        }
    }

    /**
     * Covers the update arm of {@code recordApplied}: a state row already
     * exists, so its fingerprint and timestamp are refreshed and persisted.
     */
    @Test
    void recordAppliedUpdatesWhenPresent() {
        RefApplyService service = new RefApplyService();
        RefState existing = mock(RefState.class);
        PanacheQuery<RefState> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(existingQuery);
            service.recordApplied("products", "fp-456");
            assertEquals("fp-456", existing.fingerprint);
            assertTrue(existing.appliedAt != null);
            verify(existing, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // lastApplied
    // --------------------------------------------------

    /**
     * Covers the present arm of {@code lastApplied}: a state row exists, so its
     * fingerprint is returned.
     */
    @Test
    void lastAppliedReturnsFingerprintWhenPresent() {
        RefApplyService service = new RefApplyService();
        RefState existing = mock(RefState.class);
        existing.fingerprint = "fp-789";
        PanacheQuery<RefState> existingQuery = queryReturning(existing);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(existingQuery);
            assertEquals("fp-789", service.lastApplied("products"));
        }
    }

    /**
     * Covers the absent arm of {@code lastApplied}: no state row exists, so
     * null is returned.
     */
    @Test
    void lastAppliedReturnsNullWhenAbsent() {
        RefApplyService service = new RefApplyService();
        PanacheQuery<RefState> absentQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> RefState.find("domain", "products")).thenReturn(absentQuery);
            assertNull(service.lastApplied("products"));
        }
    }
}
