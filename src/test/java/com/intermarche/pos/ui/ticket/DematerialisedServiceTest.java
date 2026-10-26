package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.payment.NexoHubClient;
import com.intermarche.pos.domain.setting.ProviderMessage;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DematerialisedService} (LC-02-09-01 to -03), targeting
 * 100% branch coverage.
 *
 * <p>The hub and the printer are mocks; the state and the ticket are real.
 * Branch enumeration: the hub active, absent, or present in training; an
 * activation without amount (null price, zero price), refused, unavailable,
 * accepted with and without a balance, with and without a provider named by the
 * line; a reversal of a line never activated, refused and accepted; the
 * reversal of a whole ticket with and without a refusal; the activation tickets
 * of activated and plain lines; the reference with and without a draft.
 */
class DematerialisedServiceTest {

    /**
     * A service over a mocked hub and printer.
     *
     * @return the service
     */
    private DematerialisedService service() {
        DematerialisedService service = new DematerialisedService();
        service.i18n = TestMessages.french();
        service.nexoHubClient = mock(NexoHubClient.class);
        service.ticketPrinterService = mock(TicketPrinterService.class);
        return service;
    }

    /**
     * A dematerialised line with its code scanned.
     *
     * @param price the unit price, or null
     * @return the line
     */
    private TicketState.TicketItem line(String price) {
        TicketState.TicketItem line = new TicketState.TicketItem("3560", null, "CARTE CADEAU",
                price == null ? null : new BigDecimal(price), BigDecimal.ONE, BigDecimal.ZERO);
        line.activationCode = "9001";
        return line;
    }

    /**
     * Opens a Panache static mock under which the provider message table holds
     * the given substitution for every lookup (BO-03-08-06, -07).
     *
     * @param message the substitution every lookup finds, or null for none
     * @return the opened static mock, closed by the caller
     */
    @SuppressWarnings("unchecked")
    private static MockedStatic<PanacheEntityBase> providerMessages(ProviderMessage message) {
        MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
        PanacheQuery<Object> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(message);
        panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(query);
        return panache;
    }

    /**
     * A provider message substitution.
     *
     * @param screen the screen text, or null
     * @param ticket the ticket text, or null
     * @return the substitution
     */
    private static ProviderMessage substitution(String screen, String ticket) {
        ProviderMessage message = new ProviderMessage();
        message.providerCode = "HUBSIM";
        message.messageCode = "ARTICLE DEJA ACTIVE";
        message.screenText = screen;
        message.ticketText = ticket;
        return message;
    }

    /**
     * An accepted hub answer.
     *
     * @param balance the balance, or null
     * @param provider the provider, or null
     * @return the answer
     */
    private NexoHubClient.HubResult accepted(String balance, String provider) {
        return new NexoHubClient.HubResult(true, null, null, "HUB1", null,
                balance == null ? null : new BigDecimal(balance), provider, "S1");
    }

    /**
     * The hub takes part only when it is administered and the register is not
     * training.
     */
    @Test
    void theHubTakesPartOutsideTrainingOnly() {
        DematerialisedService service = service();
        PosState state = new PosState();
        assertFalse(service.hubActive(state));
        when(service.nexoHubClient.isConfigured()).thenReturn(true);
        assertTrue(service.hubActive(state));
        state.trainingMode = true;
        assertFalse(service.hubActive(state));
    }

    /**
     * An article with no amount is refused before anything is sent, whether its
     * price is absent or zero.
     */
    @Test
    void anArticleWithoutAmountIsRefused() {
        DematerialisedService service = service();
        PosState state = new PosState();
        assertEquals("ARTICLE SANS MONTANT : ACTIVATION IMPOSSIBLE", service.activate(state, line(null)));
        assertEquals("ARTICLE SANS MONTANT : ACTIVATION IMPOSSIBLE", service.activate(state, line("0.00")));
        verify(service.nexoHubClient, never()).activate(any(), any(), any(), any(), any());
    }

    /**
     * A refused activation names the hub's words, or its condition when it gave
     * none; an unreachable hub says so.
     */
    @Test
    void aRefusedActivationNamesTheHubsReason() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem line = line("25.00");
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(NexoHubClient.HubResult.failure("Refusal", "DEJA ACTIVE"));
        assertEquals("ACTIVATION REFUSÉE (DEJA ACTIVE) : ARTICLE ANNULÉ", service.activate(state, line));
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", null));
        assertEquals("ACTIVATION REFUSÉE (NotAllowed) : ARTICLE ANNULÉ", service.activate(state, line));
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(NexoHubClient.HubResult.failure(NexoHubClient.UNAVAILABLE, null));
        assertEquals("HUB DE PAIEMENT INJOIGNABLE", service.activate(state, line));
        assertNull(line.activationReference);
    }

    /**
     * An accepted activation keeps the hub's reference, the provider's code and
     * the balances, the provider the line named or the hub's, and tells the
     * operator; a hub that states no balance leaves the amount as the balance.
     */
    @Test
    void anAcceptedActivationIsKeptOnTheLine() {
        DematerialisedService service = service();
        PosState state = new PosState();
        state.payment.ticketDbId = 42L;
        TicketState.TicketItem line = line("25.00");
        line.activationProvider = "TRANSACTIS";
        when(service.nexoHubClient.activate("42-" + line.uid, "TRANSACTIS", "3560", "9001",
                new BigDecimal("25.00"))).thenReturn(accepted("30.00", "HUBSIM"));
        try (MockedStatic<PanacheEntityBase> panache = providerMessages(null)) {
            assertNull(service.activate(state, line));
        }
        assertNull(line.activationTicketText);
        assertEquals("HUB1", line.activationReference);
        assertEquals("S1", line.activationApproval);
        assertEquals("TRANSACTIS", line.activationProvider);
        assertEquals(0, new BigDecimal("30.00").compareTo(line.activationBalanceAfter));
        assertEquals(0, new BigDecimal("5.00").compareTo(line.activationBalanceBefore));
        assertEquals("ARTICLE ACTIVÉ - CODE 9001 - SOLDE 30,00 €", state.ticket.transientError);
        TicketState.TicketItem bare = line("10.00");
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(accepted(null, "ILLICADO"));
        try (MockedStatic<PanacheEntityBase> panache = providerMessages(null)) {
            assertNull(service.activate(state, bare));
        }
        assertEquals("ILLICADO", bare.activationProvider);
        assertEquals(0, new BigDecimal("10.00").compareTo(bare.activationBalanceAfter));
        assertEquals(0, BigDecimal.ZERO.compareTo(bare.activationBalanceBefore));
    }

    /**
     * A line never activated needs no reversal; a refused reversal keeps the
     * activation and says why; an accepted one forgets it and says so.
     */
    @Test
    void reversingALine() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem plain = line("25.00");
        assertTrue(service.reverse(state, plain));
        TicketState.TicketItem activated = line("25.00");
        activated.activationReference = "HUB1";
        when(service.nexoHubClient.reverseStoredValue("" + activated.uid, "HUB1"))
                .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", "DEJA UTILISEE"));
        assertFalse(service.reverse(state, activated));
        assertEquals("HUB1", activated.activationReference);
        assertEquals("ANNULATION DE L'ACTIVATION REFUSÉE (DEJA UTILISEE) : ARTICLE CONSERVÉ",
                state.ticket.transientError);
        when(service.nexoHubClient.reverseStoredValue("" + activated.uid, "HUB1")).thenReturn(accepted(null, null));
        assertTrue(service.reverse(state, activated));
        assertNull(activated.activationReference);
        assertEquals("ACTIVATION ANNULÉE - CODE 9001", state.ticket.transientError);
    }

    /**
     * A whole ticket is reversed line by line, and stops at the first refusal.
     */
    @Test
    void reversingAWholeTicket() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem first = line("10.00");
        first.activationReference = "HUB1";
        TicketState.TicketItem second = line("20.00");
        second.activationReference = "HUB2";
        state.ticket.items.add(first);
        state.ticket.items.add(second);
        when(service.nexoHubClient.reverseStoredValue(anyString(), any()))
                .thenReturn(accepted(null, null))
                .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", null));
        assertFalse(service.reverseAll(state));
        assertNull(first.activationReference);
        assertEquals("HUB2", second.activationReference);
        when(service.nexoHubClient.reverseStoredValue(anyString(), any())).thenReturn(accepted(null, null));
        assertTrue(service.reverseAll(state));
        assertNull(second.activationReference);
    }

    /**
     * Each activated line of the closed sale gets its activation ticket; a plain
     * line gets none.
     */
    @Test
    void theActivationTicketsOfTheClosedSale() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem activated = line("25.00");
        activated.activationReference = "HUB1";
        activated.activationProvider = "TRANSACTIS";
        activated.activationApproval = "S1";
        activated.activationBalanceBefore = BigDecimal.ZERO;
        activated.activationBalanceAfter = new BigDecimal("25.00");
        state.ticket.items.add(activated);
        state.ticket.items.add(line("5.00"));
        assertEquals(1, service.printActivationTickets(state, "POS01-00000003"));
        verify(service.ticketPrinterService).printActivationTicket("POS01-00000003", "CARTE CADEAU", "9001",
                new BigDecimal("25.00"), BigDecimal.ZERO, new BigDecimal("25.00"), "HUB1", "TRANSACTIS", "S1",
                null);
    }

    /**
     * BO-03-08-06: the shop's screen text for the provider's code replaces the
     * register's refusal — « ARTICLE DEJA ACTIVE » read as « Produit déjà
     * activé » — on an activation and on a reversal alike.
     */
    @Test
    void theShopsSentenceReplacesTheProvidersRefusal() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem line = line("25.00");
        line.activationProvider = "HUBSIM";
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", "ARTICLE DEJA ACTIVE"));
        line.activationReference = null;
        try (MockedStatic<PanacheEntityBase> panache = providerMessages(substitution("Produit déjà activé", null))) {
            assertEquals("Produit déjà activé", service.activate(state, line));
            line.activationReference = "HUB1";
            when(service.nexoHubClient.reverseStoredValue(anyString(), any()))
                    .thenReturn(NexoHubClient.HubResult.failure("NotAllowed", "ARTICLE DEJA ACTIVE"));
            assertFalse(service.reverse(state, line));
            assertEquals("Produit déjà activé", state.ticket.transientError);
        }
    }

    /**
     * BO-03-08-06 / -07: an accepted activation shows the shop's screen text
     * in place of the register's notice, and keeps the shop's ticket text for
     * the activation ticket.
     */
    @Test
    void anAcceptedActivationCarriesTheShopsSentences() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem line = line("25.00");
        line.activationProvider = "HUBSIM";
        when(service.nexoHubClient.activate(anyString(), any(), any(), any(), any()))
                .thenReturn(accepted("25.00", "HUBSIM"));
        try (MockedStatic<PanacheEntityBase> panache = providerMessages(
                substitution("Carte activée", "Carte activée : {montant}"))) {
            assertNull(service.activate(state, line));
        }
        assertEquals("Carte activée", state.ticket.transientError);
        assertEquals("Carte activée : {montant}", line.activationTicketText);
    }

    /**
     * BO-03-02-37: a code goes to the hub when the hub takes part and its
     * prepaid range is not handled locally — no range, a NEXO range, or a
     * referential that cannot be read; a LOCAL range, or no hub, keeps it at
     * the register.
     */
    @Test
    void thePrepaidRangeSaysWhetherTheHubActivates() {
        DematerialisedService service = service();
        PosState state = new PosState();
        when(service.nexoHubClient.isConfigured()).thenReturn(false);
        assertFalse(service.thirdPartyActivation(state, "2961"));
        when(service.nexoHubClient.isConfigured()).thenReturn(true);
        assertTrue(service.thirdPartyActivation(state, "2961"));
        try (org.mockito.MockedStatic<com.intermarche.pos.domain.barcode.CouponType> types =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.barcode.CouponType.class)) {
            types.when(() -> com.intermarche.pos.domain.barcode.CouponType.protocolOf("2961")).thenReturn("NEXO");
            types.when(() -> com.intermarche.pos.domain.barcode.CouponType.protocolOf("2971")).thenReturn("LOCAL");
            assertTrue(service.thirdPartyActivation(state, "2961"));
            assertFalse(service.thirdPartyActivation(state, "2971"));
        }
    }

    /**
     * Builds a type of prepaid card.
     *
     * @param protocol its protocol
     * @param activation whether it may be activated at the sale
     * @return the type
     */
    private com.intermarche.pos.domain.payment.PrepaidCardType prepaid(String protocol, boolean activation) {
        com.intermarche.pos.domain.payment.PrepaidCardType type =
                new com.intermarche.pos.domain.payment.PrepaidCardType();
        type.label = "Cadeau";
        type.protocol = protocol;
        type.provider = "ILLICADO";
        type.activationAllowed = activation;
        return type;
    }

    /**
     * BO-03-02-37: the administered type of prepaid card names the protocol
     * first — a NEXO type goes to an active hub only, a LOCAL type never — and
     * says whether the code may be activated at all.
     */
    @Test
    void thePrepaidCardTypeRulesTheActivation() {
        DematerialisedService service = service();
        PosState state = new PosState();
        try (org.mockito.MockedStatic<com.intermarche.pos.domain.payment.PrepaidCardType> types =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.payment.PrepaidCardType.class)) {
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("6299"))
                    .thenReturn(prepaid("NEXO", true));
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("2971"))
                    .thenReturn(prepaid("LOCAL", false));
            when(service.nexoHubClient.isConfigured()).thenReturn(false);
            assertFalse(service.thirdPartyActivation(state, "6299"));
            when(service.nexoHubClient.isConfigured()).thenReturn(true);
            assertTrue(service.thirdPartyActivation(state, "6299"));
            assertFalse(service.thirdPartyActivation(state, "2971"));
            assertNull(service.activationRefusal("6299"));
            assertEquals("ACTIVATION NON AUTORISEE POUR LA CARTE Cadeau", service.activationRefusal("2971"));
            assertNull(service.activationRefusal("0000"));
        }
    }

    /**
     * BO-03-02-37: an article naming no provider is activated under the
     * provider of its card's administered type; a provider the article names
     * is kept.
     */
    @Test
    void theProviderOfTheTypeIsNamedToTheHub() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem unnamed = line("20.00");
        unnamed.activationCode = "6299";
        TicketState.TicketItem named = line("20.00");
        named.activationCode = "6299";
        named.activationProvider = "OWN";
        when(service.nexoHubClient.activate(any(), any(), any(), any(), any()))
                .thenReturn(NexoHubClient.HubResult.failure("Refused", "NON"));
        try (org.mockito.MockedStatic<com.intermarche.pos.domain.payment.PrepaidCardType> types =
                     org.mockito.Mockito.mockStatic(com.intermarche.pos.domain.payment.PrepaidCardType.class);
             MockedStatic<PanacheEntityBase> messages = providerMessages(null)) {
            types.when(() -> com.intermarche.pos.domain.payment.PrepaidCardType.findFor("6299"))
                    .thenReturn(prepaid("NEXO", true));
            service.activate(state, unnamed);
            service.activate(state, named);
        }
        verify(service.nexoHubClient).activate(any(), eq("ILLICADO"), any(), eq("6299"), any());
        verify(service.nexoHubClient).activate(any(), eq("OWN"), any(), eq("6299"), any());
    }

    /**
     * BO-11-03-04: the administered moment of the NEXO exchange is read from
     * the settings.
     */
    @Test
    void theActivationMomentIsAdministered() {
        DematerialisedService service = service();
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        assertFalse(service.activationAtPayment());
        when(service.posSettingsService.nexoActivationAtPayment()).thenReturn(true);
        assertTrue(service.activationAtPayment());
    }

    /**
     * BO-11-03-04: entering payment activates every dematerialised article
     * still awaiting its activation — cancelled, returned, plain and already
     * activated lines left alone — and names the refused ones, the refusal
     * shown; without a hub nothing is activated.
     */
    @Test
    void enteringPaymentActivatesThePendingArticles() {
        DematerialisedService service = service();
        PosState state = new PosState();
        TicketState.TicketItem pending = line("20.00");
        TicketState.TicketItem refused = line("30.00");
        refused.activationCode = "9002";
        TicketState.TicketItem cancelled = line("10.00");
        cancelled.cancelled = true;
        TicketState.TicketItem returned = line("10.00");
        returned.returnLine = true;
        TicketState.TicketItem plain = line("10.00");
        plain.activationCode = null;
        TicketState.TicketItem done = line("10.00");
        done.activationReference = "HUB0";
        state.ticket.items.addAll(java.util.List.of(pending, refused, cancelled, returned, plain, done));
        assertEquals(java.util.List.of(), service.activatePending(state));
        when(service.nexoHubClient.isConfigured()).thenReturn(true);
        when(service.nexoHubClient.activate(any(), any(), any(), eq("9001"), any())).thenReturn(accepted("20.00", "HUBSIM"));
        when(service.nexoHubClient.activate(any(), any(), any(), eq("9002"), any()))
                .thenReturn(NexoHubClient.HubResult.failure("Refusal", null));
        try (MockedStatic<PanacheEntityBase> panache = providerMessages(null)) {
            assertEquals(java.util.List.of(refused.uid), service.activatePending(state));
        }
        assertEquals("HUB1", pending.activationReference);
        assertNotNull(state.ticket.transientError);
        verify(service.nexoHubClient, times(2)).activate(any(), any(), any(), any(), any());
    }
}
