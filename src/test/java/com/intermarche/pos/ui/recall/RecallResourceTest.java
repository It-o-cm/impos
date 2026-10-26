package com.intermarche.pos.ui.recall;

import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.service.CashMovementService;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecallResource}. Every collaborator is a Mockito mock;
 * {@link PosState} is real so {@code state.auth} and {@code touch()} work. Tests
 * assert the delegation to {@link TicketService#loadRecalledDocument} and the
 * 303 routing, and cover both arms of the acompte guards (no session, invalid
 * movement, success) and the recall-page message selection.
 */
class RecallResourceTest {

    /**
     * Builds a resource with fresh mocks and a real {@link PosState}.
     *
     * @return the wired resource
     */
    private RecallResource newResource() {
        RecallResource resource = new RecallResource();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        resource.i18n = TestMessages.french();
        resource.documentClient = mock(BackOfficeDocumentClient.class);
        resource.ticketService = mock(TicketService.class);
        resource.cashSessionService = mock(CashSessionService.class);
        resource.cashMovementService = mock(CashMovementService.class);
        resource.recall = mock(Template.class);
        resource.state = new PosState();
        resource.depositService = mock(DocumentDepositService.class);
        resource.selfScanService = mock(SelfScanService.class);
        resource.channelGate = mock(ChannelGate.class);
        resource.channelRules = mock(ChannelRules.class);
        return resource;
    }

    /**
     * {@code recallDocument} fetches by code, loads the document into the sale and
     * redirects to the sale screen.
     */
    @Test
    void recallDocumentLoadsAndRedirects() {
        RecallResource resource = newResource();
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.COMMANDE, "CMD1", null, null);
        when(resource.documentClient.fetch("CMD1")).thenReturn(doc);
        Response response = resource.recallDocument("CMD1");
        verify(resource.ticketService).loadRecalledDocument(resource.state, doc);
        assertEquals(303, response.getStatus());
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code recallDocument} on an unknown code still delegates (with a null
     * document) and redirects — the service posts the DOCUMENT INTROUVABLE error.
     */
    @Test
    void recallDocumentUnknownStillDelegates() {
        RecallResource resource = newResource();
        when(resource.documentClient.fetch("X")).thenReturn(null);
        Response response = resource.recallDocument("X");
        verify(resource.ticketService).loadRecalledDocument(resource.state, null);
        assertEquals("/", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} refuses when no session is open (session-null arm).
     */
    @Test
    void recordAcompteWithoutSessionRedirectsWithError() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(null);
        Response response = resource.recordAcompte("CMD1", "5,00");
        assertEquals("/recall?error=no-session", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} redirects with the amount error when the service
     * refuses the movement (invalid amount / blank reference arm).
     */
    @Test
    void recordAcompteInvalidRedirectsWithError() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), any(), any())).thenReturn(null);
        Response response = resource.recordAcompte("", "0");
        assertEquals("/recall?error=amount", response.getLocation().toString());
    }

    /**
     * {@code recordAcompte} confirms with the ok flag when the movement is recorded.
     */
    @Test
    void recordAcompteSuccessRedirectsWithOk() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), eq("CMD1"), any()))
                .thenReturn(mock(com.intermarche.pos.domain.session.CashMovement.class));
        Response response = resource.recordAcompte("CMD1", "5,00");
        assertEquals("/recall?ok=1", response.getLocation().toString());
    }

    /**
     * {@code recallPage} renders the no-session message, the amount message, the
     * generic no-message arm and the saved flag through the template.
     */
    @Test
    void recallPageSelectsTheMessage() {
        RecallResource resource = newResource();
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.recall.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        assertSame(view, resource.recallPage("no-session", null));
        assertSame(view, resource.recallPage("amount", null));
        assertSame(view, resource.recallPage(null, "1"));
    }
    /** LC-06: missing recall codes stay null and empty deposit amounts retain their zero-value refusal. */
    @Test
    void missingRecallCodeAndDepositAmountsKeepTheirFallbacks() {
        var resource = newResource();
        resource.recallDocument(null);
        verify(resource.documentClient).fetch(null);
        resource.state.auth.operatorId = 1L;
        var operator = mock(com.intermarche.pos.domain.people.Employee.class);
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (var base = org.mockito.Mockito.mockStatic(io.quarkus.hibernate.orm.panache.PanacheEntityBase.class)) {
            base.when(() -> io.quarkus.hibernate.orm.panache.PanacheEntityBase.findById(1L)).thenReturn(operator);
            assertEquals("/recall?error=amount", resource.recordAcompte("ORDER", null).getLocation().toString());
            assertEquals("/recall?error=amount", resource.recordAcompte("ORDER", " ").getLocation().toString());
        }
        verify(resource.cashMovementService, org.mockito.Mockito.times(2)).recordAcompte(any(), eq(operator), eq(BigDecimal.ZERO), eq("ORDER"), org.mockito.ArgumentMatchers.isNull());
    }

    /** LC-12 and LC-04: malformed monetary input is neutralized before creating any financial document; covers numeric parsing failures. */
    @Test
    void malformedMoneyCannotBecomeAFinancialAmount() throws Exception {
        var method = RecallResource.class.getDeclaredMethod("parseAmount", String.class);
        method.setAccessible(true);
        assertEquals(java.math.BigDecimal.ZERO, method.invoke(newResource(), "12,3,4"));
        assertEquals(java.math.BigDecimal.ZERO, method.invoke(newResource(), "NaN"));
    }

    /**
     * Stubs the recall template so every data call answers the same view.
     *
     * @param resource the resource whose template is stubbed
     * @return the view the page answers
     */
    private TemplateInstance view(RecallResource resource) {
        TemplateInstance view = mock(TemplateInstance.class);
        when(resource.recall.data(anyString(), any())).thenReturn(view);
        when(view.data(anyString(), any())).thenReturn(view);
        return view;
    }

    /**
     * A waiting optional-deposit quote: one line stating its delivered quantity,
     * one stating neither quantity, label nor price.
     *
     * @return the document
     */
    private RecalledDocument waitingQuote() {
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.DEVIS, "DV-1", java.util.List.of(
                new RecalledDocument.Line("301", "LAIT", new BigDecimal("5"), new BigDecimal("2.50"),
                        new BigDecimal("0.055"), new BigDecimal("4")),
                new RecalledDocument.Line(null, null, null, null, null)), new BigDecimal("3.00"));
        doc.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        return doc;
    }

    /**
     * LC-06-02-02, -04: with no document waiting the page carries no document,
     * and every deposit refusal code is worded — an unknown code and a code
     * outside the deposit family words nothing.
     */
    @Test
    void recallPageWordsEveryDepositRefusal() {
        RecallResource resource = newResource();
        TemplateInstance view = view(resource);
        resource.recallPage("deposit-no_document", null);
        verify(view).data("error", "AUCUN DOCUMENT EN ATTENTE");
        verify(view).data("document", null);
        verify(view).data("documentRows", java.util.List.of());
        verify(view, org.mockito.Mockito.times(3)).data(anyString(), eq(""));
        resource.recallPage("deposit-no_session", null);
        verify(view).data("error", "AUCUNE SESSION OUVERTE");
        resource.recallPage("deposit-training", null);
        verify(view).data("error", "AUCUN ACOMPTE EN MODE FORMATION");
        resource.recallPage("deposit-not_allowed", null);
        verify(view).data("error", "CE DOCUMENT N'ADMET PAS D'ACOMPTE");
        resource.recallPage("deposit-bad_amount", null);
        verify(view).data("error", "MONTANT OU COMMANDE INVALIDE");
        resource.recallPage("deposit-over_remaining", null);
        verify(view).data("error", "ACOMPTE SUPÉRIEUR AU RESTE DÛ");
        resource.recallPage("deposit-required", null);
        verify(view).data("error", "ACOMPTE OBLIGATOIRE AVANT LE RÈGLEMENT");
        resource.recallPage("deposit-refused", null);
        verify(view).data("error", "DOCUMENT REFUSÉ PAR LES RÈGLES DE SON CANAL");
        resource.recallPage("deposit-other", null);
        resource.recallPage("other", null);
        verify(view, org.mockito.Mockito.times(2)).data("error", null);
        org.mockito.Mockito.verifyNoInteractions(resource.depositService);
    }

    /**
     * LC-06-02-02, -08: the waiting document is shown line by line at the
     * store's price — ordered and delivered quantities, unit price, total — with
     * its total, what was paid on it and what remains.
     */
    @Test
    void recallPageShowsTheWaitingDocument() {
        RecallResource resource = newResource();
        TemplateInstance view = view(resource);
        RecalledDocument doc = waitingQuote();
        resource.state.pendingDocument = doc;
        when(resource.ticketService.pricedLines(doc)).thenReturn(doc.lines);
        when(resource.depositService.total(doc)).thenReturn(new BigDecimal("10"));
        when(resource.depositService.remaining(doc)).thenReturn(new BigDecimal("7.005"));
        resource.recallPage(null, null);
        verify(view).data("document", doc);
        verify(view).data("documentRows", java.util.List.of(
                java.util.Map.of("label", "LAIT", "ordered", "5", "delivered", "4",
                        "unitPrice", "2,50\u00a0€", "total", "10,00\u00a0€"),
                java.util.Map.of("label", "", "ordered", "1", "delivered", "",
                        "unitPrice", "0,00\u00a0€", "total", "0,00\u00a0€")));
        verify(view).data("documentTotal", "10,00\u00a0€");
        verify(view).data("documentPaid", "3,00\u00a0€");
        verify(view).data("documentRemaining", "7,01\u00a0€");
    }

    /**
     * LC-06-02-04, -08: a recalled document admitting a deposit waits for the
     * cashier's choice on the recall screen instead of loading.
     */
    @Test
    void recallDocumentOfferingADepositWaitsForTheChoice() {
        RecallResource resource = newResource();
        RecalledDocument doc = waitingQuote();
        when(resource.documentClient.fetch("DV-1")).thenReturn(doc);
        long before = resource.state.version;
        Response response = resource.recallDocument(" DV-1 ");
        assertSame(doc, resource.state.pendingDocument);
        assertEquals("/recall", response.getLocation().toString());
        org.mockito.Mockito.verify(resource.ticketService, org.mockito.Mockito.never())
                .loadRecalledDocument(any(), any());
        org.junit.jupiter.api.Assertions.assertTrue(resource.state.version > before);
    }

    /**
     * LC-06-02-04, -07: a deposit collected returns to the recall screen with the
     * ok flag, a refusal with its code (French comma tolerated).
     */
    @Test
    void depositOnDocumentRoutesByOutcome() {
        RecallResource resource = newResource();
        when(resource.depositService.collect(resource.state, new BigDecimal("12.50")))
                .thenReturn(DocumentDepositService.Outcome.COLLECTED);
        assertEquals("/recall?ok=1", resource.depositOnDocument("12,50").getLocation().toString());
        when(resource.depositService.collect(resource.state, new BigDecimal("99")))
                .thenReturn(DocumentDepositService.Outcome.OVER_REMAINING);
        assertEquals("/recall?error=deposit-over_remaining",
                resource.depositOnDocument("99").getLocation().toString());
    }

    /**
     * LC-06-02-08: settling with no document waiting, or with a mandatory deposit
     * still due, returns to the recall screen; otherwise the document loads into
     * the sale and stops waiting.
     */
    @Test
    void settleDocumentGuardsThenLoads() {
        RecallResource resource = newResource();
        assertEquals("/recall?error=deposit-no_document", resource.settleDocument().getLocation().toString());
        RecalledDocument doc = waitingQuote();
        doc.depositRule = RecalledDocument.DEPOSIT_MANDATORY;
        doc.acompteAlreadyPaid = BigDecimal.ZERO;
        resource.state.pendingDocument = doc;
        assertEquals("/recall?error=deposit-required", resource.settleDocument().getLocation().toString());
        assertSame(doc, resource.state.pendingDocument);
        doc.acompteAlreadyPaid = new BigDecimal("5.00");
        assertEquals("/", resource.settleDocument().getLocation().toString());
        org.junit.jupiter.api.Assertions.assertNull(resource.state.pendingDocument);
        verify(resource.ticketService).loadRecalledDocument(resource.state, doc);
    }

    /**
     * Forgetting the waiting document clears it and returns to the recall screen.
     */
    @Test
    void forgetDocumentClearsTheWaitingDocument() {
        RecallResource resource = newResource();
        resource.state.pendingDocument = waitingQuote();
        long before = resource.state.version;
        assertEquals("/recall", resource.forgetDocument().getLocation().toString());
        org.junit.jupiter.api.Assertions.assertNull(resource.state.pendingDocument);
        org.junit.jupiter.api.Assertions.assertTrue(resource.state.version > before);
    }

    /**
     * LC-06-05-03: a selfscanning basket keyed on the recall screen opens its
     * control instead of loading.
     */
    @Test
    void recallDocumentOfASelfScanBasketOpensItsControl() {
        RecallResource resource = newResource();
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", null, null);
        when(resource.documentClient.fetch("SS1")).thenReturn(basket);
        assertEquals("/selfscan", resource.recallDocument("SS1").getLocation().toString());
        verify(resource.selfScanService).start(resource.state, basket);
        org.mockito.Mockito.verify(resource.ticketService, org.mockito.Mockito.never())
                .loadRecalledDocument(any(), any());
    }

    /**
     * BO-10-01-16: a code keyed on this screen is marked as keyed, so the
     * retrieval mode of its channel judges it.
     */
    @Test
    void aKeyedCodeIsMarkedKeyed() {
        RecallResource resource = newResource();
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.QBUSTING, "QB1", null, null);
        when(resource.documentClient.fetch("QB1")).thenReturn(doc);
        resource.recallDocument(" QB1 ");
        assertTrue(doc.keyed);
        assertFalse(doc.byCustomer);
        verify(resource.documentClient, org.mockito.Mockito.never()).fetchByCustomer(any(), any());
    }

    /**
     * BO-10-01-18: where the baskets are identified by the customer, the keyed
     * number first names that customer's self-scanning basket, which goes to
     * its control marked as found by the customer.
     */
    @Test
    void theCustomerNumberNamesTheCustomersBasket() {
        RecallResource resource = newResource();
        when(resource.channelRules.selfScanByCustomer()).thenReturn(true);
        RecalledDocument basket = new RecalledDocument(RecalledDocument.Kind.SELF_SCAN, "SS1", null, null);
        when(resource.documentClient.fetchByCustomer("C9", RecalledDocument.Kind.SELF_SCAN)).thenReturn(basket);
        assertEquals("/selfscan", resource.recallDocument("C9").getLocation().toString());
        assertTrue(basket.byCustomer);
        assertTrue(basket.keyed);
        verify(resource.selfScanService).start(resource.state, basket);
        verify(resource.documentClient, org.mockito.Mockito.never()).fetch(any());
    }

    /**
     * BO-10-01-18: a number naming no customer's basket is tried as a document
     * code, which then is not marked as found by the customer.
     */
    @Test
    void aNumberNamingNoBasketIsTriedAsACode() {
        RecallResource resource = newResource();
        when(resource.channelRules.selfScanByCustomer()).thenReturn(true);
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.COMMANDE, "CM1", null, null);
        when(resource.documentClient.fetch("CM1")).thenReturn(doc);
        resource.recallDocument("CM1");
        assertFalse(doc.byCustomer);
        verify(resource.ticketService).loadRecalledDocument(resource.state, doc);
    }

    /**
     * BO-10-01: a document admitting a deposit that its channel refuses does
     * not wait for the cashier's choice: the reason goes to the sale screen.
     */
    @Test
    void aDocumentItsChannelRefusesDoesNotWaitForADeposit() {
        RecallResource resource = newResource();
        RecalledDocument doc = new RecalledDocument(RecalledDocument.Kind.DEVIS, "DV1", null, null);
        doc.depositRule = RecalledDocument.DEPOSIT_OPTIONAL;
        when(resource.documentClient.fetch("DV1")).thenReturn(doc);
        when(resource.channelGate.refusal(resource.state, doc)).thenReturn("REFUS");
        assertEquals("/", resource.recallDocument("DV1").getLocation().toString());
        assertEquals("REFUS", resource.state.ticket.transientError);
        assertNull(resource.state.pendingDocument);
    }

    /**
     * BO-10-01-27: the deposit attached to an order is recorded in the tender
     * the store administers.
     */
    @Test
    void theOrderDepositIsTakenInTheAdministeredTender() {
        RecallResource resource = newResource();
        when(resource.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(resource.channelRules.commercialDepositTender()).thenReturn("CARD");
        when(resource.cashMovementService.recordAcompte(any(), any(), any(), eq("CMD1"), eq("CARD")))
                .thenReturn(mock(com.intermarche.pos.domain.session.CashMovement.class));
        assertEquals("/recall?ok=1", resource.recordAcompte("CMD1", "5,00").getLocation().toString());
    }
}
