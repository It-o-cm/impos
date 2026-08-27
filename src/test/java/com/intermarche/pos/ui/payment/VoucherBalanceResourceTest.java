package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.StoredValue;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VoucherBalanceResource}.
 * <p>
 * The resource is a Qute-backed consultation over the {@link StoredValue}
 * registry: the lookup resolves to {@link PanacheEntityBase#find} and is
 * intercepted with {@link org.mockito.Mockito#mockStatic}, and the template is
 * a Mockito mock whose chained {@code data(...)} returns a mocked instance.
 * The rendered {@link VoucherBalanceResource.BalanceView} is captured to
 * assert its projection. No database and no Quarkus context is booted.
 * <p>
 * Branch enumeration (every arm exercised — 100%): {@code consult} covers the
 * number null arm, the number-blank arm, an unknown valid number
 * (found-false / find-null arm), a found gift card (kind GIFT_CARD arm, status
 * ACTIVE arm) and a found credit note (kind CREDIT_NOTE arm, status EXHAUSTED
 * arm). 3 two-way decision points plus the null/blank short-circuit — every
 * arm exercised.
 */
class VoucherBalanceResourceTest {

    /**
     * Builds a resource with a mocked template and state.
     *
     * @return the wired resource
     */
    private VoucherBalanceResource newResource() {
        VoucherBalanceResource resource = new VoucherBalanceResource();
        resource.voucherBalance = mock(Template.class);
        resource.state = mock(PosState.class);
        return resource;
    }

    /**
     * Wires the chained {@code data("state",...).data("result",...)} of the
     * template to a single self-returning instance.
     *
     * @param resource the resource whose template to wire
     * @return the mocked template instance the chain returns
     */
    private TemplateInstance wireTemplate(VoucherBalanceResource resource) {
        TemplateInstance instance = mock(TemplateInstance.class);
        when(resource.voucherBalance.data(eq("state"), any())).thenReturn(instance);
        when(instance.data(eq("result"), any())).thenReturn(instance);
        return instance;
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the value.
     *
     * @param result the instrument the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<StoredValue> query(StoredValue result) {
        PanacheQuery<StoredValue> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    /**
     * {@code voucherBalancePage} renders the page with a null result.
     */
    @Test
    void pageRendersWithoutResult() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        assertSame(instance, resource.voucherBalancePage());
    }

    /**
     * {@code consult} on a GIFT_CARD / ACTIVE instrument projects the CARTE
     * CADEAU kind label, the ACTIVE status label and the French balance.
     */
    @Test
    void consultFoundActiveGiftCard() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        StoredValue instrument = new StoredValue();
        instrument.number = "2960001";
        instrument.kind = StoredValue.Kind.GIFT_CARD;
        instrument.status = StoredValue.Status.ACTIVE;
        instrument.balance = new BigDecimal("12.5");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<StoredValue> found = query(instrument);
            ms.when(() -> PanacheEntityBase.find("number", "2960001")).thenReturn(found);
            resource.consult(" 2960001 ");
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertTrue(view.found);
        assertEquals("2960001", view.number);
        assertEquals("CARTE CADEAU", view.kindLabel);
        assertEquals("ACTIVE", view.statusLabel);
        assertEquals("12,50", view.balanceFormatted);
    }

    /**
     * {@code consult} on a CREDIT_NOTE / EXHAUSTED instrument projects the
     * AVOIR kind label and the ÉPUISÉ status label.
     */
    @Test
    void consultFoundExhaustedCreditNote() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        StoredValue instrument = new StoredValue();
        instrument.number = "0007";
        instrument.kind = StoredValue.Kind.CREDIT_NOTE;
        instrument.status = StoredValue.Status.EXHAUSTED;
        instrument.balance = new BigDecimal("0");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<StoredValue> found = query(instrument);
            ms.when(() -> PanacheEntityBase.find("number", "0007")).thenReturn(found);
            resource.consult("0007");
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertTrue(view.found);
        assertEquals("AVOIR", view.kindLabel);
        assertEquals("ÉPUISÉ", view.statusLabel);
        assertEquals("0,00", view.balanceFormatted);
    }

    /**
     * {@code consult} on a valid but unknown number returns an unfound view
     * (find-null arm).
     */
    @Test
    void consultUnknownNumber() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<StoredValue> none = query(null);
            ms.when(() -> PanacheEntityBase.find("number", "9999")).thenReturn(none);
            resource.consult("9999");
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertFalse(view.found);
        assertNull(view.number);
    }

    /**
     * {@code consult} on a blank number returns an unfound view without ever
     * touching the registry (blank arm).
     */
    @Test
    void consultBlankNumber() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            resource.consult("   ");
            ms.verifyNoInteractions();
        }
        assertFalse(capturedView(instance).found);
    }

    /**
     * {@code consult} on a null number returns an unfound view without ever
     * touching the registry (null arm).
     */
    @Test
    void consultNullNumber() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            resource.consult(null);
            ms.verifyNoInteractions();
        }
        assertFalse(capturedView(instance).found);
    }

    /**
     * Captures the {@link VoucherBalanceResource.BalanceView} handed to the
     * template's {@code result} slot.
     *
     * @param instance the wired template instance that received the view
     * @return the captured view
     */
    private VoucherBalanceResource.BalanceView capturedView(TemplateInstance instance) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(instance).data(eq("result"), captor.capture());
        return (VoucherBalanceResource.BalanceView) captor.getValue();
    }
}
