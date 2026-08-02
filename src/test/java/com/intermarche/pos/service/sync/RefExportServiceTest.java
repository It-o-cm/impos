package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.CouponType;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.ProductFamily;
import com.intermarche.pos.domain.ProductType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefExportService}.
 * <p>
 * The service reads every domain through Panache static finders
 * ({@code X.find("order by ...").page(p, s).list()}) and folds a SHA-256
 * fingerprint over the canonical row stream. All static access is intercepted
 * with {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase};
 * finder chains are plain Mockito mocks returning entity mocks whose public
 * fields are set directly. No database and no Quarkus context is booted.
 * <p>
 * The fingerprint oracle is an independent SHA-256 over hand-written canonical
 * strings ({@link #sha256hex(String)}); the empty-input fingerprint is also
 * pinned to the well-known constant so the hex encoding is checked against a
 * value not derived from the class under test. The two private-map caches are
 * seeded through reflection to exercise the fresh and expired cache arms
 * deterministically without mocking the clock.
 * <p>
 * Branch enumeration: {@code getFingerprints} — the four arms of
 * {@code cachedAt == null || now - cachedAt > TTL} (absent / expired / fresh);
 * {@code getPage} — the five domain switch arms plus the default throw;
 * {@code computeFingerprint} — the do-while both arms (empty exit, non-empty
 * continue) and the catch block; {@code canonical} — the five {@code instanceof}
 * true arms plus the final all-false / default arm; {@code toDto(Product)} the
 * {@code productType} ternary both arms; {@code toDto(Price)} the {@code product}
 * ternary both arms; {@code iso} both arms; {@code n} both arms.
 */
class RefExportServiceTest {

    /** The well-known SHA-256 of the empty byte stream. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * Computes the lowercase hex SHA-256 of a UTF-8 string, an oracle
     * independent of the class under test.
     *
     * @param input the string to digest
     * @return the fingerprint, hex encoded
     * @throws Exception if the SHA-256 algorithm is unavailable
     */
    private String sha256hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(input.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Builds a finder-chain mock whose {@code page(0, 1000)} yields the given
     * first page and whose {@code page(1, 1000)} yields an empty page, matching
     * the paging loop of {@code computeFingerprint}.
     *
     * @param firstPage the rows returned for page 0
     * @param <T> the entity type
     * @return the mocked query returned by {@code find(...)}
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> pagedQuery(List<T> firstPage) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        PanacheQuery<T> page0 = mock(PanacheQuery.class);
        PanacheQuery<T> page1 = mock(PanacheQuery.class);
        when(query.page(0, 1000)).thenReturn(page0);
        when(query.page(1, 1000)).thenReturn(page1);
        when(page0.list()).thenReturn(firstPage);
        when(page1.list()).thenReturn(List.of());
        return query;
    }

    /**
     * Builds a finder-chain mock whose {@code page(page, size)} yields the given
     * rows, for a single {@code getPage} call.
     *
     * @param page the expected page index
     * @param size the expected page size
     * @param rows the rows returned
     * @param <T> the entity type
     * @return the mocked query returned by {@code find(...)}
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> singlePage(int page, int size, List<T> rows) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        PanacheQuery<T> paged = mock(PanacheQuery.class);
        when(query.page(page, size)).thenReturn(paged);
        when(paged.list()).thenReturn(rows);
        return query;
    }

    /**
     * Seeds the private fingerprint caches of a service via reflection.
     *
     * @param service the service to seed
     * @param domain the domain key
     * @param fingerprint the cached fingerprint value
     * @param cachedAt the cache timestamp (epoch millis)
     * @throws Exception if reflection fails
     */
    @SuppressWarnings("unchecked")
    private void seed(RefExportService service, String domain, String fingerprint, long cachedAt)
            throws Exception {
        Field cacheField = RefExportService.class.getDeclaredField("fingerprintCache");
        cacheField.setAccessible(true);
        ((Map<String, String>) cacheField.get(service)).put(domain, fingerprint);
        Field atField = RefExportService.class.getDeclaredField("fingerprintCachedAt");
        atField.setAccessible(true);
        ((Map<String, Long>) atField.get(service)).put(domain, cachedAt);
    }

    // --------------------------------------------------
    // getPage — switch arms, mappings and ternaries
    // --------------------------------------------------

    /**
     * Covers the default switch arm of {@code getPage}: an unknown domain
     * raises an {@link IllegalArgumentException} with the offending name.
     */
    @Test
    void getPageThrowsOnUnknownDomain() {
        RefExportService service = new RefExportService();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getPage("UNKNOWN", 0, 10));
        assertEquals("Domaine inconnu: UNKNOWN", ex.getMessage());
    }

    /**
     * Covers the FAMILIES switch arm and {@code toDto(ProductFamily)}: the
     * family fields flow verbatim into the payload.
     */
    @Test
    void getPageMapsFamilies() {
        RefExportService service = new RefExportService();
        ProductFamily family = mock(ProductFamily.class);
        family.code = "F1";
        family.description = "Fruits";
        family.flags = "BIO";
        PanacheQuery<ProductFamily> query = singlePage(0, 10, List.of(family));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(query);
            List<?> result = service.getPage("FAMILIES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.FamilyDto dto = (RefPayloads.FamilyDto) result.get(0);
            assertEquals("F1", dto.code);
            assertEquals("Fruits", dto.description);
            assertEquals("BIO", dto.flags);
        }
    }

    /**
     * Covers the PRODUCTS switch arm and both arms of the {@code productType}
     * ternary in {@code toDto(Product)}: the first product carries a type
     * (rendered by name), the second has a null type (rendered null).
     */
    @Test
    void getPageMapsProductsWithAndWithoutType() {
        RefExportService service = new RefExportService();
        Product typed = mock(Product.class);
        typed.ean = "E1";
        typed.plu = "100";
        typed.name = "Pomme";
        typed.description = "Desc";
        typed.icon = "IC";
        typed.brand = "BR";
        typed.referenceWeight = new BigDecimal("1.000");
        typed.referenceVolume = new BigDecimal("2.000");
        typed.productType = ProductType.WEIGHT;
        typed.unitName = "kg";
        typed.active = true;
        typed.forbiddenToSale = false;
        Product untyped = mock(Product.class);
        untyped.ean = "E2";
        untyped.name = "Poire";
        untyped.productType = null;
        untyped.active = false;
        untyped.forbiddenToSale = true;
        PanacheQuery<Product> query = singlePage(0, 10, List.of(typed, untyped));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Product.find("order by ean")).thenReturn(query);
            List<?> result = service.getPage("PRODUCTS", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.ProductDto first = (RefPayloads.ProductDto) result.get(0);
            assertEquals("E1", first.ean);
            assertEquals("100", first.plu);
            assertEquals("Pomme", first.name);
            assertEquals(new BigDecimal("1.000"), first.referenceWeight);
            assertEquals("WEIGHT", first.productType);
            assertTrue(first.active);
            RefPayloads.ProductDto second = (RefPayloads.ProductDto) result.get(1);
            assertEquals("E2", second.ean);
            assertNull(second.productType);
            assertTrue(second.forbiddenToSale);
        }
    }

    /**
     * Covers the PRICES switch arm and every conditional in {@code toDto(Price)}
     * and {@code iso}: the first row has a product (EAN taken) and a start but no
     * end (iso both arms), the second row has no product (EAN null) and an end
     * but no start (iso both arms again).
     */
    @Test
    void getPageMapsPricesWithAndWithoutProduct() {
        RefExportService service = new RefExportService();
        Product product = mock(Product.class);
        product.ean = "E1";
        Price linked = mock(Price.class);
        linked.product = product;
        linked.priceExcludingTax = new BigDecimal("1.00");
        linked.priceIncludingTax = new BigDecimal("1.20");
        linked.vatRate = new BigDecimal("0.2000");
        linked.priority = 5;
        linked.startDateTime = LocalDateTime.of(2026, 1, 1, 10, 0, 30);
        linked.endDateTime = null;
        Price orphan = mock(Price.class);
        orphan.product = null;
        orphan.priceExcludingTax = new BigDecimal("2.00");
        orphan.priceIncludingTax = new BigDecimal("2.20");
        orphan.vatRate = new BigDecimal("0.1000");
        orphan.priority = null;
        orphan.startDateTime = null;
        orphan.endDateTime = LocalDateTime.of(2026, 12, 31, 23, 59, 59);
        PanacheQuery<Price> query = singlePage(0, 10, List.of(linked, orphan));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Price.find("order by id")).thenReturn(query);
            List<?> result = service.getPage("PRICES", 0, 10);
            assertEquals(2, result.size());
            RefPayloads.PriceDto first = (RefPayloads.PriceDto) result.get(0);
            assertEquals("E1", first.productEan);
            assertEquals(5, first.priority);
            assertEquals("2026-01-01T10:00:30", first.startDateTime);
            assertNull(first.endDateTime);
            RefPayloads.PriceDto second = (RefPayloads.PriceDto) result.get(1);
            assertNull(second.productEan);
            assertNull(second.priority);
            assertNull(second.startDateTime);
            assertEquals("2026-12-31T23:59:59", second.endDateTime);
        }
    }

    /**
     * Covers the EMPLOYEES switch arm and {@code toDto(Employee)}: the role is
     * rendered by name and the remaining fields flow verbatim.
     */
    @Test
    void getPageMapsEmployees() {
        RefExportService service = new RefExportService();
        Employee employee = mock(Employee.class);
        employee.loginName = "alice";
        employee.firstName = "Alice";
        employee.lastName = "Martin";
        employee.password = "hash";
        employee.email = "a@x.fr";
        employee.role = Employee.EmployeeRole.CASHIER;
        employee.badgeId = "B1";
        employee.theme = "dark";
        employee.active = true;
        PanacheQuery<Employee> query = singlePage(0, 10, List.of(employee));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(query);
            List<?> result = service.getPage("EMPLOYEES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.EmployeeDto dto = (RefPayloads.EmployeeDto) result.get(0);
            assertEquals("alice", dto.loginName);
            assertEquals("CASHIER", dto.role);
            assertEquals("dark", dto.theme);
            assertTrue(dto.active);
        }
    }

    /**
     * Covers the COUPON_TYPES switch arm and {@code toDto(CouponType)}: the
     * amount source is rendered by name and the remaining fields flow verbatim.
     */
    @Test
    void getPageMapsCouponTypes() {
        RefExportService service = new RefExportService();
        CouponType type = mock(CouponType.class);
        type.code = "C1";
        type.label = "Bon";
        type.matchPattern = "^9";
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "D";
        type.priority = 10;
        type.active = true;
        type.depositLine = false;
        PanacheQuery<CouponType> query = singlePage(0, 10, List.of(type));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.find("order by code")).thenReturn(query);
            List<?> result = service.getPage("COUPON_TYPES", 0, 10);
            assertEquals(1, result.size());
            RefPayloads.CouponTypeDto dto = (RefPayloads.CouponTypeDto) result.get(0);
            assertEquals("C1", dto.code);
            assertEquals("ENCODED", dto.amountSource);
            assertEquals(10, dto.priority);
            assertTrue(dto.active);
        }
    }

    // --------------------------------------------------
    // getFingerprints — computation, caching, failure
    // --------------------------------------------------

    /**
     * Covers the {@code cachedAt == null} arm with empty domains: every finder
     * yields an empty first page so the do-while exits immediately and each
     * fingerprint is the pinned empty-stream SHA-256, in domain order.
     */
    @Test
    void getFingerprintsComputesEmptyDomains() {
        RefExportService service = new RefExportService();
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of());
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            Map<String, String> fingerprints = service.getFingerprints();
            assertEquals(RefExportService.DOMAINS, List.copyOf(fingerprints.keySet()));
            assertEquals(EMPTY_SHA256, fingerprints.get("FAMILIES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRODUCTS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRICES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("EMPLOYEES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("COUPON_TYPES"));
        }
    }

    /**
     * Covers the do-while continue arm of {@code computeFingerprint} and the
     * end-to-end fold: PRODUCTS returns two rows on page 0 then an empty page 1,
     * so the loop iterates before exiting and the domain fingerprint equals the
     * independent SHA-256 of the two canonical rows concatenated, while the
     * other (empty) domains hash to the empty stream. PRODUCTS is chosen because
     * its {@code "order by ean"} finder key is unique, whereas FAMILIES and
     * COUPON_TYPES share {@code "order by code"} and cannot be populated apart
     * under a single {@link PanacheEntityBase} static mock.
     */
    @Test
    void getFingerprintsFoldsRowsIntoFingerprint() throws Exception {
        RefExportService service = new RefExportService();
        Product first = mock(Product.class);
        first.ean = "E1";
        first.plu = "100";
        first.name = "Pomme";
        first.description = "Desc";
        first.icon = "IC";
        first.brand = "BR";
        first.referenceWeight = new BigDecimal("1.000");
        first.referenceVolume = new BigDecimal("2.000");
        first.productType = ProductType.WEIGHT;
        first.unitName = "kg";
        first.active = true;
        first.forbiddenToSale = false;
        Product second = mock(Product.class);
        second.ean = "E2";
        second.name = "Poire";
        second.productType = null;
        second.active = false;
        second.forbiddenToSale = true;
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of(first, second));
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            Map<String, String> fingerprints = service.getFingerprints();
            assertEquals(sha256hex("E1|100|Pomme|Desc|IC|BR|1.000|2.000|WEIGHT|kg|true|false"
                    + "E2||Poire||||||||false|true"), fingerprints.get("PRODUCTS"));
            assertEquals(EMPTY_SHA256, fingerprints.get("FAMILIES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("PRICES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("EMPLOYEES"));
            assertEquals(EMPTY_SHA256, fingerprints.get("COUPON_TYPES"));
        }
    }

    /**
     * Covers the fresh-cache arm ({@code cachedAt != null} and
     * {@code now - cachedAt <= TTL}): every domain is seeded with a recent
     * sentinel, so {@code getFingerprints} returns the sentinels verbatim
     * without touching any finder.
     */
    @Test
    void getFingerprintsServesCacheWithinTtl() throws Exception {
        RefExportService service = new RefExportService();
        long now = System.currentTimeMillis();
        for (String domain : RefExportService.DOMAINS) {
            seed(service, domain, "FRESH-" + domain, now);
        }
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            Map<String, String> fingerprints = service.getFingerprints();
            for (String domain : RefExportService.DOMAINS) {
                assertEquals("FRESH-" + domain, fingerprints.get(domain));
            }
        }
    }

    /**
     * Covers the expired-cache arm ({@code cachedAt != null} and
     * {@code now - cachedAt > TTL}): every domain is seeded with an old sentinel
     * and empty finders, so {@code getFingerprints} discards the sentinel and
     * recomputes the empty-stream fingerprint.
     */
    @Test
    void getFingerprintsRecomputesAfterTtl() throws Exception {
        RefExportService service = new RefExportService();
        long expired = System.currentTimeMillis() - 120_000;
        for (String domain : RefExportService.DOMAINS) {
            seed(service, domain, "STALE-" + domain, expired);
        }
        PanacheQuery<ProductFamily> families = pagedQuery(List.of());
        PanacheQuery<Product> products = pagedQuery(List.of());
        PanacheQuery<Price> prices = pagedQuery(List.of());
        PanacheQuery<Employee> employees = pagedQuery(List.of());
        PanacheQuery<CouponType> couponTypes = pagedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenReturn(families);
            mocked.when(() -> Product.find("order by ean")).thenReturn(products);
            mocked.when(() -> Price.find("order by id")).thenReturn(prices);
            mocked.when(() -> Employee.find("order by loginName")).thenReturn(employees);
            mocked.when(() -> CouponType.find("order by code")).thenReturn(couponTypes);
            Map<String, String> fingerprints = service.getFingerprints();
            for (String domain : RefExportService.DOMAINS) {
                assertEquals(EMPTY_SHA256, fingerprints.get(domain));
            }
        }
    }

    /**
     * Covers the catch block of {@code computeFingerprint}: the first domain's
     * finder throws, so the failure is wrapped in an {@link IllegalStateException}
     * naming the domain and carrying the original cause.
     */
    @Test
    void getFingerprintsWrapsComputationFailure() {
        RefExportService service = new RefExportService();
        RuntimeException boom = new RuntimeException("boom");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> ProductFamily.find("order by code")).thenThrow(boom);
            IllegalStateException ex = assertThrows(IllegalStateException.class, service::getFingerprints);
            assertEquals("Empreinte incalculable pour FAMILIES", ex.getMessage());
            assertSame(boom, ex.getCause());
        }
    }

    // --------------------------------------------------
    // canonical — default arm
    // --------------------------------------------------

    /**
     * Covers every arm of {@code canonical} and both arms of {@code n}: each of
     * the five payload types renders through its own {@code instanceof} true arm
     * (with a null field to exercise the empty {@code n} arm alongside non-null
     * fields), and a plain object falls through every {@code instanceof} false
     * arm to the default {@code String.valueOf}. Invoked reflectively because
     * the production flow only ever feeds already-mapped DTOs to
     * {@code canonical}, so the default arm is otherwise unreachable.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void canonicalRendersEveryPayloadTypeAndUnknownRow() throws Exception {
        RefExportService service = new RefExportService();
        Method canonical = RefExportService.class.getDeclaredMethod("canonical", Object.class);
        canonical.setAccessible(true);
        RefPayloads.FamilyDto family = new RefPayloads.FamilyDto();
        family.code = "F1";
        family.description = "Fruits";
        family.flags = null;
        assertEquals("F1|Fruits|", canonical.invoke(service, family));
        RefPayloads.ProductDto product = new RefPayloads.ProductDto();
        product.ean = "E1";
        product.plu = "100";
        product.name = "Pomme";
        product.description = "Desc";
        product.icon = "IC";
        product.brand = "BR";
        product.referenceWeight = new BigDecimal("1.000");
        product.referenceVolume = new BigDecimal("2.000");
        product.productType = "WEIGHT";
        product.unitName = null;
        product.active = true;
        product.forbiddenToSale = false;
        assertEquals("E1|100|Pomme|Desc|IC|BR|1.000|2.000|WEIGHT||true|false",
                canonical.invoke(service, product));
        RefPayloads.PriceDto price = new RefPayloads.PriceDto();
        price.productEan = "E1";
        price.priceExcludingTax = new BigDecimal("1.00");
        price.priceIncludingTax = new BigDecimal("1.20");
        price.vatRate = new BigDecimal("0.2000");
        price.priority = 5;
        price.startDateTime = "2026-01-01T10:00:30";
        price.endDateTime = null;
        assertEquals("E1|1.00|1.20|0.2000|5|2026-01-01T10:00:30|",
                canonical.invoke(service, price));
        RefPayloads.EmployeeDto employee = new RefPayloads.EmployeeDto();
        employee.loginName = "alice";
        employee.firstName = "Alice";
        employee.lastName = "Martin";
        employee.password = "hash";
        employee.email = "a@x.fr";
        employee.role = "CASHIER";
        employee.badgeId = null;
        employee.theme = "dark";
        employee.active = true;
        assertEquals("alice|Alice|Martin|hash|a@x.fr|CASHIER||dark|true",
                canonical.invoke(service, employee));
        RefPayloads.CouponTypeDto type = new RefPayloads.CouponTypeDto();
        type.code = "C1";
        type.label = "Bon";
        type.matchPattern = "^9";
        type.amountSource = "ENCODED";
        type.amountPattern = null;
        type.priority = 10;
        type.active = true;
        type.depositLine = false;
        assertEquals("C1|Bon|^9|ENCODED||10|true|false", canonical.invoke(service, type));
        assertEquals("RAW", canonical.invoke(service, "RAW"));
    }
}
