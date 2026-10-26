package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
 * Branch enumeration (every arm exercised — 100%): {@code voucherBalancePage}
 * covers the number null arm, the number-blank arm, an unknown valid number
 * (found-false / find-null arm), a found gift card (kind GIFT_CARD arm, status
 * ACTIVE arm) and a found credit note (kind CREDIT_NOTE arm, status EXHAUSTED
 * arm); {@code consult} covers the null and non-null arms of its PRG redirect
 * (the POST never renders — it 303s the number to the page).
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
        // The page echoes the consulted number back into the entry, so the chain
        // carries one more link than it used to.
        when(instance.data(eq("number"), any())).thenReturn(instance);
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
     * {@code voucherBalancePage} renders the page with a null result when no
     * number travels in the query string (null arm), never touching the
     * registry.
     */
    @Test
    void pageRendersWithoutResult() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertSame(instance, resource.voucherBalancePage(null, false));
            ms.verifyNoInteractions();
        }
        verify(instance).data("result", null);
    }

    /**
     * {@code voucherBalancePage} renders a null result on a blank number
     * (blank arm), never touching the registry.
     */
    @Test
    void pageRendersNullResultOnBlankNumber() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            assertSame(instance, resource.voucherBalancePage("   ", false));
            ms.verifyNoInteractions();
        }
        verify(instance).data("result", null);
    }

    /**
     * {@code consult} 303-redirects the trimmed number to the consultation
     * page (PRG pattern — the POST never renders).
     */
    @Test
    void consultRedirectsWithNumber() {
        VoucherBalanceResource resource = newResource();
        Response response = resource.consult(" 2960001 ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/voucher-balance?number=2960001&asked=true", response.getLocation().toString());
    }

    /**
     * {@code consult} 303-redirects with an empty number when null is posted
     * (null arm of the encoding ternary).
     */
    @Test
    void consultRedirectsWithEmptyNumberOnNull() {
        VoucherBalanceResource resource = newResource();
        Response response = resource.consult(null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/voucher-balance?number=&asked=true", response.getLocation().toString());
    }

    /**
     * {@code voucherBalancePage} on a GIFT_CARD / ACTIVE instrument projects
     * the CARTE CADEAU kind label, the ACTIVE status label and the French
     * balance.
     */
    @Test
    void pageFoundActiveGiftCard() {
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
            resource.voucherBalancePage(" 2960001 ", true);
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertTrue(view.found);
        assertEquals("2960001", view.number);
        assertEquals("CARTE CADEAU", view.kindLabel);
        assertEquals("ACTIVE", view.statusLabel);
        assertEquals("12,50", view.balanceFormatted);
    }

    /**
     * {@code voucherBalancePage} on a CREDIT_NOTE / EXHAUSTED instrument
     * projects the AVOIR kind label and the ÉPUISÉ status label.
     */
    @Test
    void pageFoundExhaustedCreditNote() {
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
            resource.voucherBalancePage("0007", true);
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertTrue(view.found);
        assertEquals("AVOIR", view.kindLabel);
        assertEquals("ÉPUISÉ", view.statusLabel);
        assertEquals("0,00", view.balanceFormatted);
    }

    /**
     * {@code voucherBalancePage} on a valid but unknown number renders an
     * unfound view (find-null arm).
     */
    @Test
    void pageUnknownNumber() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<StoredValue> none = query(null);
            ms.when(() -> PanacheEntityBase.find("number", "9999")).thenReturn(none);
            resource.voucherBalancePage("9999", true);
        }
        VoucherBalanceResource.BalanceView view = capturedView(instance);
        assertFalse(view.found);
        assertNull(view.number);
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

    /**
     * An EMPTY consultation is answered rather than ignored: the operator pressed the
     * button, so the screen says what is missing instead of redrawing itself
     * ({@code asked} true, number blank).
     */
    @Test
    void blankConsultationIsAnswered() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        org.mockito.ArgumentCaptor<VoucherBalanceResource.BalanceView> captor =
                org.mockito.ArgumentCaptor.forClass(VoucherBalanceResource.BalanceView.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertSame(instance, resource.voucherBalancePage("", true));
        }
        verify(instance).data(eq("result"), captor.capture());
        VoucherBalanceResource.BalanceView view = captor.getValue();
        assertTrue(view.blank);
        assertFalse(view.found);
    }

    /**
     * A bare arrival — nobody pressed anything — shows no answer at all
     * ({@code asked} false, number blank).
     */
    @Test
    void bareArrivalShowsNoAnswer() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertSame(instance, resource.voucherBalancePage("", false));
        }
        verify(instance).data("result", null);
    }

    /**
     * The consulted number is echoed back, trimmed, so the operator sees what was
     * looked up instead of an emptied field.
     */
    @Test
    void theConsultedNumberIsEchoedBack() {
        VoucherBalanceResource resource = newResource();
        TemplateInstance instance = wireTemplate(resource);
        StoredValue instrument = new StoredValue();
        instrument.number = "2960001";
        instrument.kind = StoredValue.Kind.GIFT_CARD;
        instrument.status = StoredValue.Status.ACTIVE;
        instrument.balance = new BigDecimal("12.5");
        // The lookup runs on a non-blank number: without the finder stubbed it
        // resolves to a null query and the page throws before echoing anything
        // back. The query is built BEFORE the finder is stubbed — query() stubs
        // firstResult(), and a stubbing opened inside another one is what
        // Mockito reports as unfinished.
        PanacheQuery<StoredValue> found = query(instrument);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("number", "2960001")).thenReturn(found);
            resource.voucherBalancePage("  2960001  ", true);
        }
        verify(instance).data("number", "2960001");
    }

    /**
     * Runs a consultation of a number under a stubbed type of prepaid card
     * and captures the view the page received.
     *
     * @param resource the resource
     * @param type the type the number belongs to, or null
     * @param number the number consulted
     * @return the captured view
     */
    private VoucherBalanceResource.BalanceView consultUnder(VoucherBalanceResource resource,
            com.intermarche.pos.domain.payment.PrepaidCardType type, String number) {
        TemplateInstance instance = wireTemplate(resource);
        try (MockedStatic<com.intermarche.pos.domain.payment.PrepaidCardType> types =
                     mockStatic(com.intermarche.pos.domain.payment.PrepaidCardType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor(number)).thenReturn(type);
            PanacheQuery<StoredValue> none = query(null);
            panache.when(() -> StoredValue.find("number", number)).thenReturn(none);
            resource.voucherBalancePage(number, true);
        }
        ArgumentCaptor<Object> view = ArgumentCaptor.forClass(Object.class);
        verify(instance).data(eq("result"), view.capture());
        return (VoucherBalanceResource.BalanceView) view.getValue();
    }

    /**
     * Builds a type of prepaid card.
     *
     * @param protocol its protocol
     * @param balance whether its balance may be consulted
     * @return the type
     */
    private com.intermarche.pos.domain.payment.PrepaidCardType prepaid(String protocol, boolean balance) {
        com.intermarche.pos.domain.payment.PrepaidCardType type =
                new com.intermarche.pos.domain.payment.PrepaidCardType();
        type.label = "Cadeau";
        type.protocol = protocol;
        type.balanceAllowed = balance;
        return type;
    }

    /**
     * Wires the hub and the messages a prepaid consultation uses.
     *
     * @param resource the resource
     * @param configured whether a hub is administered
     */
    private void withHub(VoucherBalanceResource resource, boolean configured) {
        resource.nexoHubClient = mock(NexoHubClient.class);
        when(resource.nexoHubClient.isConfigured()).thenReturn(configured);
        resource.i18n = com.intermarche.pos.ui.TestMessages.french();
    }

    /**
     * BO-03-02-37: a type forbidding the consultation answers a refusal.
     */
    @Test
    void aTypeForbiddingTheBalanceRefusesIt() {
        VoucherBalanceResource resource = newResource();
        withHub(resource, true);
        VoucherBalanceResource.BalanceView view = consultUnder(resource, prepaid("NEXO", false), "6299");
        assertEquals("CONSULTATION DU SOLDE NON AUTORISEE POUR LA CARTE Cadeau", view.refusal);
        assertFalse(view.found);
    }

    /**
     * BO-03-02-37: a NEXO card is consulted on the hub — its balance, a
     * missing balance reading zero, or the hub's refusal with or without its
     * wording.
     */
    @Test
    void aNexoCardIsConsultedOnTheHub() {
        VoucherBalanceResource resource = newResource();
        withHub(resource, true);
        when(resource.nexoHubClient.balance("6299")).thenReturn(
                new NexoHubClient.HubResult(true, null, null, "T", null, new BigDecimal("30"), "ILLICADO", "A"),
                new NexoHubClient.HubResult(true, null, null, "T", null, null, "ILLICADO", "A"),
                NexoHubClient.HubResult.failure("Refused", "CARTE BLOQUEE"),
                new NexoHubClient.HubResult(false, "Refused", null, null, null, null, null, null));
        VoucherBalanceResource.BalanceView funded = consultUnder(resource, prepaid("NEXO", true), "6299");
        assertTrue(funded.found);
        assertEquals("30,00", funded.balanceFormatted);
        assertEquals("Cadeau", funded.kindLabel);
        assertEquals("6299", funded.number);
        assertEquals("ACTIVE", funded.statusLabel);
        assertEquals("0,00", consultUnder(newWired(resource), prepaid("NEXO", true), "6299").balanceFormatted);
        assertEquals("SOLDE Cadeau REFUSE : CARTE BLOQUEE",
                consultUnder(newWired(resource), prepaid("NEXO", true), "6299").refusal);
        assertEquals("SOLDE Cadeau REFUSE : ",
                consultUnder(newWired(resource), prepaid("NEXO", true), "6299").refusal);
    }

    /**
     * Rebuilds a resource sharing the hub and the messages of another, its
     * template fresh.
     *
     * @param other the resource whose hub is kept
     * @return the new resource
     */
    private VoucherBalanceResource newWired(VoucherBalanceResource other) {
        VoucherBalanceResource resource = newResource();
        resource.nexoHubClient = other.nexoHubClient;
        resource.i18n = other.i18n;
        return resource;
    }

    /**
     * BO-03-02-37: a LOCAL card, a NEXO card with no hub, a NEXO card in
     * training and an unreadable referential all read the register's registry.
     */
    @Test
    void otherCardsReadTheRegistry() {
        VoucherBalanceResource local = newResource();
        withHub(local, true);
        assertFalse(consultUnder(local, prepaid("LOCAL", true), "2971").found);
        VoucherBalanceResource noHub = newResource();
        withHub(noHub, false);
        assertNull(consultUnder(noHub, prepaid("NEXO", true), "6299").refusal);
        VoucherBalanceResource training = newResource();
        withHub(training, true);
        training.state.trainingMode = true;
        assertNull(consultUnder(training, prepaid("NEXO", true), "6299").refusal);
        verify(training.nexoHubClient, never()).balance(any());
        VoucherBalanceResource broken = newResource();
        TemplateInstance instance = wireTemplate(broken);
        try (MockedStatic<com.intermarche.pos.domain.payment.PrepaidCardType> types =
                     mockStatic(com.intermarche.pos.domain.payment.PrepaidCardType.class);
             MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("1"))
                    .thenThrow(new IllegalStateException("no table"));
            PanacheQuery<StoredValue> none = query(null);
            panache.when(() -> StoredValue.find("number", "1")).thenReturn(none);
            broken.voucherBalancePage("1", true);
        }
        verify(instance).data(eq("result"), any());
    }
}
