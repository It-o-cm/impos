package com.intermarche.pos.ui.resource;

import com.intermarche.pos.domain.catalog.Price;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.ui.valuation.ValuationPayloads;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MockValuationResource}, the embedded valuation-engine
 * simulator that lives in {@code src/mock/java} and ships in the main jar.
 * <p>
 * The resource is a plain JAX-RS bean with no injected collaborators, so it is
 * instantiated directly and its {@code valuate} method is driven with
 * hand-built request DTOs. Its only external reach is the catalog lookup in the
 * private {@code catalogPriceInclTax} helper: {@code Product.find(...)} resolves
 * to {@link PanacheEntityBase} under a plain (non-enhanced) unit run and is
 * neutralized with {@link org.mockito.Mockito#mockStatic}, while
 * {@code Price.findCurrentPrice}, declared on {@link Price} itself, is mocked on
 * a {@code MockedStatic} of {@code Price}. Every branch is asserted on the
 * returned DTO's absolute contents.
 */
class MockValuationResourceTest {

    /** The single EAN the simulated engine grants a meal-voucher base for. */
    private static final String ELIGIBLE_EAN = "3300000000001";

    /** The catalog query the helper issues for the eligible EAN. */
    private static final String CATALOG_QUERY = "ean = ?1 and active = true";

    /**
     * Builds a basket item with the given fields.
     *
     * @param ean          the product EAN, may be null
     * @param unitInclTax  the surcharge unit price tax included, may be null
     * @param quantity     the line quantity, may be null
     * @return the assembled item DTO
     */
    private ValuationPayloads.ItemDto item(String ean, BigDecimal unitInclTax, BigDecimal quantity) {
        ValuationPayloads.ItemDto item = new ValuationPayloads.ItemDto();
        item.produceEan = ean;
        item.pricePerUnitInclTax = unitInclTax;
        item.quantity = quantity;
        return item;
    }

    /**
     * Builds a basket holding the supplied items.
     *
     * @param items the items to place in the basket
     * @return the assembled basket DTO
     */
    private ValuationPayloads.BasketDto basket(ValuationPayloads.ItemDto... items) {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        for (ValuationPayloads.ItemDto item : items) {
            basket.items.add(item);
        }
        return basket;
    }

    /**
     * Empty basket: the loop is never entered and {@code eligible.signum()} is
     * zero (false arm), so the neutral response carries no advantage, no offer
     * and no total. Covers the loop-exit branch and the signum false arm.
     */
    @Test
    void valuateEmptyBasketReturnsNeutralResponse() {
        MockValuationResource resource = new MockValuationResource();
        ValuationPayloads.ValuationResponseDto response = resource.valuate(basket());
        assertTrue(response.advantages.isEmpty());
        assertTrue(response.offers.isEmpty());
        assertNull(response.totalPrice);
    }

    /**
     * Item with a null EAN: the first leg of the {@code ||} guard is true, the
     * item is skipped and no eligible amount accrues. Covers the
     * {@code produceEan == null} true arm.
     */
    @Test
    void valuateNullEanItemIsSkipped() {
        MockValuationResource resource = new MockValuationResource();
        ValuationPayloads.ValuationResponseDto response =
                resource.valuate(basket(item(null, new BigDecimal("5.00"), BigDecimal.ONE)));
        assertTrue(response.advantages.isEmpty());
    }

    /**
     * Item with a non-null EAN absent from the decision table: the first leg is
     * false, the second leg ({@code !contains}) is true, so the item is skipped.
     * Covers the {@code produceEan == null} false arm and the
     * non-matching-EAN branch.
     */
    @Test
    void valuateNonEligibleEanItemIsSkipped() {
        MockValuationResource resource = new MockValuationResource();
        ValuationPayloads.ValuationResponseDto response =
                resource.valuate(basket(item("9999999999999", new BigDecimal("5.00"), BigDecimal.ONE)));
        assertTrue(response.advantages.isEmpty());
    }

    /**
     * Eligible item carrying its own unit price and quantity: the guard passes
     * (matching EAN), the price ternary takes its non-null arm, the quantity
     * ternary takes its non-null arm and the positive sum triggers the
     * advantage. Covers the matching-EAN branch, both non-null ternary arms and
     * the signum true arm.
     */
    @Test
    void valuateEligibleItemWithSuppliedPriceAndQuantityGrantsAdvantage() {
        MockValuationResource resource = new MockValuationResource();
        ValuationPayloads.ValuationResponseDto response =
                resource.valuate(basket(item(ELIGIBLE_EAN, new BigDecimal("3.00"), new BigDecimal("2"))));
        assertEquals(1, response.advantages.size());
        ValuationPayloads.AdvantageDto advantage = response.advantages.get(0);
        assertEquals("MEAL_VOUCHER", advantage.type);
        assertEquals("MEAL_VOUCHER_SIM", advantage.offerCode);
        assertEquals(new BigDecimal("6.00"), advantage.totalEligibleAmount);
        assertEquals(new BigDecimal("25.00"), advantage.threshold);
    }

    /**
     * Eligible item with a null unit price and null quantity: the price ternary
     * takes its null arm (catalog lookup) and the quantity ternary defaults to
     * ONE. The catalog resolves the product and a current price, yielding a
     * positive base. Covers both null ternary arms, the {@code product == null}
     * false arm and the {@code price != null} true arm.
     */
    @Test
    void valuateEligibleItemFallsBackToCatalogPrice() {
        MockValuationResource resource = new MockValuationResource();
        Product product = new Product();
        product.id = 42L;
        Price price = new Price();
        price.priceIncludingTax = new BigDecimal("12.50");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(product);
            panache.when(() -> Product.find(CATALOG_QUERY, ELIGIBLE_EAN)).thenReturn(query);
            prices.when(() -> Price.findCurrentPrice(42L)).thenReturn(price);
            ValuationPayloads.ValuationResponseDto response =
                    resource.valuate(basket(item(ELIGIBLE_EAN, null, null)));
            assertEquals(1, response.advantages.size());
            assertEquals(new BigDecimal("12.50"), response.advantages.get(0).totalEligibleAmount);
        }
    }

    /**
     * Eligible item with a null unit price whose product exists but has no
     * current price: the catalog helper returns ZERO through the
     * {@code price != null} false arm, the eligible sum stays zero and no
     * advantage is granted. Covers the {@code price != null} false arm and the
     * signum false arm.
     */
    @Test
    void valuateEligibleItemWithProductButNoPriceYieldsNoAdvantage() {
        MockValuationResource resource = new MockValuationResource();
        Product product = new Product();
        product.id = 7L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> prices = mockStatic(Price.class)) {
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(product);
            panache.when(() -> Product.find(CATALOG_QUERY, ELIGIBLE_EAN)).thenReturn(query);
            prices.when(() -> Price.findCurrentPrice(7L)).thenReturn(null);
            ValuationPayloads.ValuationResponseDto response =
                    resource.valuate(basket(item(ELIGIBLE_EAN, null, new BigDecimal("3"))));
            assertTrue(response.advantages.isEmpty());
        }
    }

    /**
     * Eligible item with a null unit price whose product is unknown: the catalog
     * helper returns ZERO through the {@code product == null} true arm and no
     * advantage is granted. Covers the {@code product == null} true arm.
     */
    @Test
    void valuateEligibleItemWithUnknownProductYieldsNoAdvantage() {
        MockValuationResource resource = new MockValuationResource();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find(CATALOG_QUERY, ELIGIBLE_EAN)).thenReturn(query);
            ValuationPayloads.ValuationResponseDto response =
                    resource.valuate(basket(item(ELIGIBLE_EAN, null, BigDecimal.ONE)));
            assertTrue(response.advantages.isEmpty());
        }
    }
}
