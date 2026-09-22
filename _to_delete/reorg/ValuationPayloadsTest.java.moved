package com.intermarche.pos.service.valuation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ValuationPayloads}.
 * <p>
 * The class is a non-instantiable container of plain data-transfer objects for
 * the remote valuation engine: it holds no logic, no branch and no
 * collaborator. Coverage is therefore achieved by exercising the private
 * container constructor through reflection and by instantiating every nested
 * DTO so its default field initializers are executed and its public fields are
 * shown to be freely readable and writable. No Quarkus context, no HTTP server
 * and no database is booted.
 */
class ValuationPayloadsTest {

    /**
     * Verifies the container class is final and cannot be extended, and that
     * its sole constructor is private, confirming the non-instantiable intent.
     */
    @Test
    void containerIsFinalAndConstructorIsPrivate() {
        assertTrue(Modifier.isFinal(ValuationPayloads.class.getModifiers()));
        Constructor<?>[] ctors = ValuationPayloads.class.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        assertTrue(Modifier.isPrivate(ctors[0].getModifiers()));
    }

    /**
     * Exercises the private container constructor through reflection so its
     * single line is covered, and confirms it yields a non-null instance.
     */
    @Test
    void privateConstructorIsInvokable() throws Exception {
        Constructor<ValuationPayloads> ctor = ValuationPayloads.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    /**
     * Verifies a freshly built {@link ValuationPayloads.BasketDto} carries its
     * default delivery mode and an empty, mutable item list, and that all its
     * fields round-trip the values assigned to them.
     */
    @Test
    void basketDtoDefaultsAndFields() {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        assertEquals("IN_STORE", basket.deliveryMode);
        assertNotNull(basket.items);
        assertTrue(basket.items.isEmpty());
        assertNull(basket.customerCode);
        assertNull(basket.storeCode);
        assertNull(basket.createdAt);
        ValuationPayloads.ItemDto item = new ValuationPayloads.ItemDto();
        basket.customerCode = "CARD-1";
        basket.storeCode = "STORE-9";
        basket.createdAt = "2026-08-03T10:00:00Z";
        basket.deliveryMode = "PICKUP";
        basket.items.add(item);
        assertEquals("CARD-1", basket.customerCode);
        assertEquals("STORE-9", basket.storeCode);
        assertEquals("2026-08-03T10:00:00Z", basket.createdAt);
        assertEquals("PICKUP", basket.deliveryMode);
        assertEquals(1, basket.items.size());
        assertSame(item, basket.items.get(0));
    }

    /**
     * Verifies every field of {@link ValuationPayloads.ItemDto} is null by
     * default and round-trips the values assigned to it.
     */
    @Test
    void itemDtoFields() {
        ValuationPayloads.ItemDto item = new ValuationPayloads.ItemDto();
        assertNull(item.lineId);
        assertNull(item.produceEan);
        assertNull(item.quantity);
        assertNull(item.pricePerUnitExclTax);
        assertNull(item.pricePerUnitInclTax);
        assertNull(item.vatRate);
        assertNull(item.manualDiscountAmount);
        assertNull(item.manualDiscountPercent);
        assertNull(item.manualForcedPrice);
        assertNull(item.priceDate);
        item.lineId = "L1";
        item.produceEan = "3000000000001";
        item.quantity = new BigDecimal("2");
        item.pricePerUnitExclTax = new BigDecimal("1.10");
        item.pricePerUnitInclTax = new BigDecimal("1.20");
        item.vatRate = new BigDecimal("5.5");
        item.manualDiscountAmount = new BigDecimal("0.50");
        item.manualDiscountPercent = new BigDecimal("10");
        item.manualForcedPrice = new BigDecimal("0.99");
        item.priceDate = "2026-08-03";
        assertEquals("L1", item.lineId);
        assertEquals("3000000000001", item.produceEan);
        assertEquals(new BigDecimal("2"), item.quantity);
        assertEquals(new BigDecimal("1.10"), item.pricePerUnitExclTax);
        assertEquals(new BigDecimal("1.20"), item.pricePerUnitInclTax);
        assertEquals(new BigDecimal("5.5"), item.vatRate);
        assertEquals(new BigDecimal("0.50"), item.manualDiscountAmount);
        assertEquals(new BigDecimal("10"), item.manualDiscountPercent);
        assertEquals(new BigDecimal("0.99"), item.manualForcedPrice);
        assertEquals("2026-08-03", item.priceDate);
    }

    /**
     * Verifies every field of {@link ValuationPayloads.AmountDto} is null by
     * default and round-trips the values assigned to it.
     */
    @Test
    void amountDtoFields() {
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        assertNull(amount.amountExcludingTax);
        assertNull(amount.amountIncludingTax);
        assertNull(amount.vatRate);
        amount.amountExcludingTax = new BigDecimal("9.48");
        amount.amountIncludingTax = new BigDecimal("10.00");
        amount.vatRate = new BigDecimal("5.5");
        assertEquals(new BigDecimal("9.48"), amount.amountExcludingTax);
        assertEquals(new BigDecimal("10.00"), amount.amountIncludingTax);
        assertEquals(new BigDecimal("5.5"), amount.vatRate);
    }

    /**
     * Verifies every field of {@link ValuationPayloads.OfferItemDto} is null by
     * default and round-trips the values assigned to it.
     */
    @Test
    void offerItemDtoFields() {
        ValuationPayloads.OfferItemDto offerItem = new ValuationPayloads.OfferItemDto();
        assertNull(offerItem.lineId);
        assertNull(offerItem.produceEan);
        assertNull(offerItem.quantity);
        assertNull(offerItem.amount);
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        offerItem.lineId = "L2";
        offerItem.produceEan = "3000000000002";
        offerItem.quantity = new BigDecimal("1");
        offerItem.amount = amount;
        assertEquals("L2", offerItem.lineId);
        assertEquals("3000000000002", offerItem.produceEan);
        assertEquals(new BigDecimal("1"), offerItem.quantity);
        assertSame(amount, offerItem.amount);
    }

    /**
     * Verifies {@link ValuationPayloads.OfferDto} exposes an empty, mutable
     * item list by default, null scalar fields, and round-trips assignments.
     */
    @Test
    void offerDtoDefaultsAndFields() {
        ValuationPayloads.OfferDto offer = new ValuationPayloads.OfferDto();
        assertNull(offer.offerId);
        assertNull(offer.type);
        assertNull(offer.amount);
        assertNotNull(offer.items);
        assertTrue(offer.items.isEmpty());
        ValuationPayloads.AmountDto amount = new ValuationPayloads.AmountDto();
        ValuationPayloads.OfferItemDto offerItem = new ValuationPayloads.OfferItemDto();
        offer.offerId = "OFF-1";
        offer.type = "3_FOR_2";
        offer.amount = amount;
        offer.items.add(offerItem);
        assertEquals("OFF-1", offer.offerId);
        assertEquals("3_FOR_2", offer.type);
        assertSame(amount, offer.amount);
        List<ValuationPayloads.OfferItemDto> items = offer.items;
        assertEquals(1, items.size());
        assertSame(offerItem, items.get(0));
    }

    /**
     * Verifies every field of {@link ValuationPayloads.SuggestionDto} is null
     * by default and round-trips the values assigned to it.
     */
    @Test
    void suggestionDtoFields() {
        ValuationPayloads.SuggestionDto suggestion = new ValuationPayloads.SuggestionDto();
        assertNull(suggestion.ean);
        assertNull(suggestion.quantity);
        assertNull(suggestion.offerCode);
        suggestion.ean = "3000000000003";
        suggestion.quantity = new BigDecimal("1");
        suggestion.offerCode = "UPSELL-1";
        assertEquals("3000000000003", suggestion.ean);
        assertEquals(new BigDecimal("1"), suggestion.quantity);
        assertEquals("UPSELL-1", suggestion.offerCode);
    }

    /**
     * Verifies every field of {@link ValuationPayloads.AdvantageDto} is null by
     * default and round-trips the values assigned to it.
     */
    @Test
    void advantageDtoFields() {
        ValuationPayloads.AdvantageDto advantage = new ValuationPayloads.AdvantageDto();
        assertNull(advantage.type);
        assertNull(advantage.offer);
        assertNull(advantage.offerCode);
        assertNull(advantage.discountAmount);
        assertNull(advantage.suggestion);
        assertNull(advantage.totalEligibleAmount);
        assertNull(advantage.threshold);
        ValuationPayloads.AmountDto discount = new ValuationPayloads.AmountDto();
        ValuationPayloads.SuggestionDto suggestion = new ValuationPayloads.SuggestionDto();
        advantage.type = "DISCOUNT";
        advantage.offer = "3_FOR_2";
        advantage.offerCode = "MEAL_VOUCHER";
        advantage.discountAmount = discount;
        advantage.suggestion = suggestion;
        advantage.totalEligibleAmount = new BigDecimal("20.00");
        advantage.threshold = new BigDecimal("19.00");
        assertEquals("DISCOUNT", advantage.type);
        assertEquals("3_FOR_2", advantage.offer);
        assertEquals("MEAL_VOUCHER", advantage.offerCode);
        assertSame(discount, advantage.discountAmount);
        assertSame(suggestion, advantage.suggestion);
        assertEquals(new BigDecimal("20.00"), advantage.totalEligibleAmount);
        assertEquals(new BigDecimal("19.00"), advantage.threshold);
    }

    /**
     * Verifies {@link ValuationPayloads.ValuationResponseDto} exposes empty,
     * mutable offer and advantage lists by default, a null total, and
     * round-trips the collaborators added to it.
     */
    @Test
    void valuationResponseDtoDefaultsAndFields() {
        ValuationPayloads.ValuationResponseDto response = new ValuationPayloads.ValuationResponseDto();
        assertNotNull(response.offers);
        assertTrue(response.offers.isEmpty());
        assertNotNull(response.advantages);
        assertTrue(response.advantages.isEmpty());
        assertNull(response.totalPrice);
        ValuationPayloads.OfferDto offer = new ValuationPayloads.OfferDto();
        ValuationPayloads.AdvantageDto advantage = new ValuationPayloads.AdvantageDto();
        ValuationPayloads.AmountDto total = new ValuationPayloads.AmountDto();
        response.offers.add(offer);
        response.advantages.add(advantage);
        response.totalPrice = total;
        assertEquals(1, response.offers.size());
        assertSame(offer, response.offers.get(0));
        assertEquals(1, response.advantages.size());
        assertSame(advantage, response.advantages.get(0));
        assertSame(total, response.totalPrice);
    }

    /**
     * Confirms the nested DTOs are serializable value holders by asserting each
     * carries the {@link java.io.Serializable} contract required by the engine
     * transport layer.
     */
    @Test
    void nestedDtosAreSerializable() {
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.BasketDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.ItemDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.AmountDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.OfferItemDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.OfferDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.SuggestionDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.AdvantageDto.class));
        assertTrue(java.io.Serializable.class.isAssignableFrom(ValuationPayloads.ValuationResponseDto.class));
    }
}
