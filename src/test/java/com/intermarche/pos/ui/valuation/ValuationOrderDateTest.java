package com.intermarche.pos.ui.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ORDER DATE sent with the basket (BO-10-03-17) and for the
 * proof that the article SUB-FAMILY reaches the loyalty programme
 * (BO-10-08-22): the request the register serializes is the one it sends to
 * the engine AND, verbatim, to the loyalty service.
 *
 * <p>Branch enumeration of {@code fillOrderDate}: no order date (the setting is
 * not even read), an order date with the setting off, an order date with the
 * setting on.
 */
class ValuationOrderDateTest {

    /**
     * Builds a service whose settings and engine client are mocks.
     *
     * @return the service
     */
    private ValuationService newService() {
        ValuationService service = new ValuationService();
        service.posSettingsService = mock(PosSettingsService.class);
        service.valuationClient = mock(ValuationClient.class);
        service.objectMapper = new ObjectMapper();
        return service;
    }

    /**
     * A ticket that takes over no order sends no order date, and the setting
     * is not even consulted.
     */
    @Test
    void aTicketWithoutOrderSendsNoOrderDate() {
        ValuationService service = newService();
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        service.fillOrderDate(basket, new TicketState());
        assertNull(basket.orderDate);
        verify(service.posSettingsService, never()).fidelityOrderDateEnabled();
    }

    /**
     * With the setting off — the default — the order date stays home.
     */
    @Test
    void theOrderDateStaysHomeWhenTheSettingIsOff() {
        ValuationService service = newService();
        TicketState ticket = new TicketState();
        ticket.noteOrderDate("2026-09-30T10:15:00");
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        service.fillOrderDate(basket, ticket);
        assertNull(basket.orderDate);
    }

    /**
     * With the setting on, the order date travels with the basket.
     */
    @Test
    void theOrderDateTravelsWhenTheSettingIsOn() {
        ValuationService service = newService();
        when(service.posSettingsService.fidelityOrderDateEnabled()).thenReturn(true);
        TicketState ticket = new TicketState();
        ticket.noteOrderDate("2026-09-30T10:15:00");
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        service.fillOrderDate(basket, ticket);
        assertEquals("2026-09-30T10:15:00", basket.orderDate);
    }

    /**
     * End to end: the request the register serializes — the one the engine and,
     * verbatim, the loyalty programme receive — carries the order date and the
     * article's family and sub-family when both settings are on; without them
     * it carries neither field.
     *
     * @throws Exception on serialization
     */
    @Test
    void theCoupleSentToTheLoyaltyProgrammeCarriesOrderDateAndSubFamily() throws Exception {
        ValuationService service = newService();
        when(service.valuationClient.isEnabled()).thenReturn(true);
        ValuationPayloads.ValuationResponseDto response = new ValuationPayloads.ValuationResponseDto();
        when(service.valuationClient.valuate(any()))
                .thenReturn(new ValuationClient.ValuationResult(response, "{}"));
        TicketState ticket = new TicketState();
        TicketState.TicketItem item = new TicketState.TicketItem("3000000000018", null, "LAIT",
                new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.055"));
        item.uid = "L1";
        ticket.items.add(item);
        ticket.noteOrderDate("2026-09-30T10:15:00");
        Product product = mock(Product.class);
        ProductFamily family = mock(ProductFamily.class);
        family.code = "SUB12";
        when(family.parentCode()).thenReturn("FAM1");
        @SuppressWarnings("unchecked")
        PanacheQuery<Store> stores = mock(PanacheQuery.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            panache.when(Store::findAll).thenReturn(stores);
            products.when(() -> Product.findByEan("3000000000018")).thenReturn(product);
            families.when(() -> ProductFamily.findDirectFamily(product)).thenReturn(family);
            String bare = service.valuate(ticket, null, LocalDateTime.of(2026, 10, 24, 9, 0)).requestJson;
            assertFalse(bare.contains("orderDate"), bare);
            assertFalse(bare.contains("subFamilyCode"), bare);
            when(service.posSettingsService.fidelityOrderDateEnabled()).thenReturn(true);
            when(service.posSettingsService.fidelitySendSubFamily()).thenReturn(true);
            String sent = service.valuate(ticket, null, LocalDateTime.of(2026, 10, 24, 9, 0)).requestJson;
            assertTrue(sent.contains("\"orderDate\":\"2026-09-30T10:15:00\""), sent);
            assertTrue(sent.contains("\"familyCode\":\"FAM1\""), sent);
            assertTrue(sent.contains("\"subFamilyCode\":\"SUB12\""), sent);
        }
    }
}
