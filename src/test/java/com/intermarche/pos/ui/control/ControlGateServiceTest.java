package com.intermarche.pos.ui.control;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.catalog.ProductFamily;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosListService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.SupervisorSettleService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.endorsement.FunctionApprovalService;
import com.intermarche.pos.ui.ticket.TicketService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ControlGateService}: the register alerts and the
 * function justifications (BO-04-03-08/09/10/12/14).
 */
class ControlGateServiceTest {

    /**
     * Builds a gate over mocks and a fresh state, with the given rules and
     * justification routes.
     *
     * @param rules the administered alerts
     * @param routes the justified routes
     * @return the gate
     */
    private ControlGateService gate(String rules, String routes) {
        ControlGateService gate = new ControlGateService();
        gate.state = new PosState();
        gate.settings = mock(PosSettingsService.class);
        gate.posListService = mock(PosListService.class);
        gate.technicalEventService = mock(TechnicalEventService.class);
        gate.supervisorSettleService = mock(SupervisorSettleService.class);
        gate.endorsementService = mock(EndorsementService.class);
        gate.functionApprovals = mock(FunctionApprovalService.class);
        gate.ticketService = mock(TicketService.class);
        gate.i18n = mock(Messages.class);
        when(gate.settings.controlAlertRules()).thenReturn(rules);
        when(gate.settings.justificationRoutes()).thenReturn(routes);
        when(gate.i18n.get(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(gate.i18n.code(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0) + "" + invocation.getArgument(1));
        when(gate.i18n.format(anyString(), any())).thenAnswer(invocation -> "ALERTE " + invocation.getArgument(1));
        return gate;
    }

    /**
     * A held gesture of a kind, for the tests that drive the gate's state.
     *
     * @param kind its kind
     * @param method its method
     * @param ruleKey its rule
     * @param function its function
     * @return the gesture
     */
    private static ControlGateService.Held held(ControlGateService.Kind kind, String method, String ruleKey,
                                                String function) {
        return new ControlGateService.Held(kind, method, "/action/x", "a=1", "code", BigDecimal.ONE, ruleKey,
                function, "question", "tok");
    }

    /**
     * A justification is asked only for a matching route whose function has a list.
     */
    @Test
    void justifiedFunctionNeedsARouteAndAList() {
        ControlGateService gate = gate("", "broken\nPOST\nPOST=Z\nGET /action/x=A\nPOST /action/y=B\nPOST /action/x/=C\\nPOST /action/z=");
        when(gate.posListService.justificationsOf("C")).thenReturn(List.of(new PosListService.ListChoice("1", "Un")));
        assertEquals("C", gate.justifiedFunction("POST", "/action/x"));
        assertNull(gate.justifiedFunction("POST", "/action/z"));
        assertNull(gate.justifiedFunction("POST", "/action/y"));
        assertNull(gate.justifiedFunction("POST", "/action/none"));
    }

    /**
     * The justifications offered follow the held function.
     */
    @Test
    void justificationsFollowThePendingFunction() {
        ControlGateService gate = gate("", "");
        assertTrue(gate.justifications().isEmpty());
        gate.pending = held(ControlGateService.Kind.HTTP, "POST", "k", null);
        assertTrue(gate.justifications().isEmpty());
        gate.pending = held(ControlGateService.Kind.HTTP, "POST", null, "F");
        List<PosListService.ListChoice> choices = List.of(new PosListService.ListChoice("1", "Un"));
        when(gate.posListService.justificationsOf("F")).thenReturn(choices);
        assertSame(choices, gate.justifications());
        assertSame(gate.pending, gate.pending());
    }

    /**
     * A replay passes once by its token, once more by an endorsement
     * continuation, and never otherwise.
     */
    @Test
    void replayPassesTheAnsweredGesture() {
        ControlGateService gate = gate("", "");
        assertFalse(gate.replay("POST", "/action/x", "tok", false));
        gate.passed = held(ControlGateService.Kind.HTTP, "POST", "k", null);
        assertFalse(gate.replay("GET", "/action/x", "tok", false));
        assertFalse(gate.replay("POST", "/action/other", "tok", false));
        assertFalse(gate.replay("POST", "/action/x", "wrong", false));
        assertTrue(gate.replay("POST", "/action/x?_posGate=tok", "tok", false));
        assertEquals(1, gate.state.ticket.alertCounts.get("k"));
        assertTrue(gate.replayed);
        assertFalse(gate.replay("POST", "/action/x", "tok", false));
        assertTrue(gate.replay("POST", "/action/x", null, true));
        assertNull(gate.passed);
        assertEquals(1, gate.state.ticket.alertCounts.get("k"));
        gate.passed = held(ControlGateService.Kind.HTTP, "POST", null, "F");
        assertTrue(gate.replay("POST", "/action/x", "tok", true));
        assertNull(gate.passed);
        gate.passed = held(ControlGateService.Kind.HTTP, "POST", "k", null);
        assertTrue(gate.replay("POST", "/action/x", null, true));
        assertEquals(2, gate.state.ticket.alertCounts.get("k"));
    }

    /**
     * The continuation tokens are stripped from a target.
     */
    @Test
    void stripRemovesTheTokens() {
        assertEquals("/a?b=1", ControlGateService.strip("/a?b=1&_posApproval=x"));
        assertEquals("/a", ControlGateService.strip("/a?_posGate=y"));
        assertTrue(ControlGateService.samePath("/a?_posGate=y", "/a"));
    }

    /**
     * A function: a question sends to the control screen, a refusal to the
     * sale, an information alert lets it on to its justification, nothing
     * lets it through.
     */
    @Test
    void onFunctionDecides() {
        ControlGateService ask = gate("function:POST /action/x=YESNO", "");
        assertEquals("/control-gate", ask.onFunction("POST", "/action/x", "/action/x", "a=1"));
        assertEquals("/action/x", ask.pending.target());
        ControlGateService block = gate("function:POST /action/x=BLOCK", "");
        assertEquals("/", block.onFunction("POST", "/action/x", "/action/x", ""));
        ControlGateService inform = gate("function:GET /action/y=BLOCK\nfunction:POST /action/x=BACKOFFICE",
                "POST /action/x=F");
        when(inform.posListService.justificationsOf("F")).thenReturn(List.of(new PosListService.ListChoice("1", "Un")));
        assertEquals("/control-gate", inform.onFunction("POST", "/action/x", "/action/x", ""));
        assertEquals("F", inform.pending.function());
        verify(inform.supervisorSettleService).controlAlert(eq("function:POST /action/x"), anyString(), eq(""));
        ControlGateService none = gate("", "");
        assertNull(none.onFunction("POST", "/action/x", "/action/x", ""));
    }

    /**
     * A scan: the barcode rules, and the offline rule while the node is lost.
     */
    @Test
    void onScanDecides() {
        assertEquals(ControlGateService.Outcome.NO_RULE, gate("", "").onScan("20", false));
        ControlGateService inform = gate("barcode:20*=BACKOFFICE\noffline=BLOCK", "");
        assertEquals(ControlGateService.Outcome.PASS, inform.onScan("2001", false));
        assertEquals(ControlGateService.Outcome.STOP, inform.onScan("3001", true));
        ControlGateService ask = gate("barcode:20*=YESNO", "");
        assertEquals(ControlGateService.Outcome.STOP, ask.onScan("2001", false));
        assertEquals("/control-gate", ask.state.functionPath);
        assertEquals("2001", ask.pending.code());
    }

    /**
     * An expired date: the « expired » rules alone decide.
     */
    @Test
    void onExpiredDecides() {
        assertEquals(ControlGateService.Outcome.NO_RULE, gate("barcode:1=BLOCK", "").onExpired("c"));
        assertEquals(ControlGateService.Outcome.PASS, gate("expired=BACKOFFICE", "").onExpired("c"));
        assertEquals(ControlGateService.Outcome.STOP, gate("expired=BLOCK", "").onExpired("c"));
    }

    /**
     * An article: its EAN or its family, the family read once and only for an
     * article rule.
     */
    @Test
    void onArticleDecides() {
        Product product = new Product();
        product.ean = "300";
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            assertEquals(ControlGateService.Outcome.NO_RULE, gate("barcode:1=BLOCK", "").onArticle(product, null));
            families.verifyNoInteractions();
            ProductFamily family = new ProductFamily();
            family.code = "1020";
            families.when(() -> ProductFamily.findDirectFamily(product)).thenReturn(family);
            ControlGateService inform = gate("article:999=BLOCK\narticle:F1020=BACKOFFICE", "");
            assertEquals(ControlGateService.Outcome.PASS, inform.onArticle(product, BigDecimal.ONE));
            families.verify(() -> ProductFamily.findDirectFamily(product));
            ControlGateService block = gate("article:300=BLOCK", "");
            assertEquals(ControlGateService.Outcome.STOP, block.onArticle(product, BigDecimal.ONE));
            families.when(() -> ProductFamily.findDirectFamily(product)).thenReturn(null);
            assertEquals(ControlGateService.Outcome.NO_RULE, gate("article:F1=BLOCK", "").onArticle(product, null));
        }
    }

    /**
     * An article held for a question is replayed the way it came in: a scan
     * by its code as read, a weighed article by its PLU, a keyed one by its EAN.
     */
    @Test
    void onArticleHoldsTheWayTheArticleCameIn() {
        Product product = new Product();
        product.ean = "300";
        try (MockedStatic<ProductFamily> families = mockStatic(ProductFamily.class)) {
            ControlGateService scanned = gate("article:300=YESNO", "");
            assertEquals(ControlGateService.Outcome.STOP,
                    scanned.onArticle(product, null, ControlGateService.Kind.SCAN, "0000300"));
            assertEquals(ControlGateService.Kind.SCAN, scanned.pending().kind());
            assertEquals("0000300", scanned.pending().code());
            ControlGateService weighed = gate("article:300=YESNO", "");
            weighed.onArticle(product, null, ControlGateService.Kind.PLU, "1234");
            assertEquals(ControlGateService.Kind.PLU, weighed.pending().kind());
            assertEquals("1234", weighed.pending().code());
            ControlGateService keyed = gate("article:300=YESNO", "");
            keyed.onArticle(product, BigDecimal.TEN);
            assertEquals(ControlGateService.Kind.EAN, keyed.pending().kind());
            assertEquals("300", keyed.pending().code());
            assertEquals(BigDecimal.TEN, keyed.pending().quantity());
        }
    }

    /**
     * The bypass of a replay and the transaction maximum both let the gesture
     * through; a notification goes beside every alert but a refusal.
     */
    @Test
    void fireHonoursBypassMaximumAndNotification() {
        ControlGateService gate = gate("", "");
        ControlRule rule = ControlRule.parseLine("barcode:1=BLOCK;max=1;notify;text=Stop");
        gate.bypass = rule.key();
        assertEquals(ControlGateService.Outcome.PASS, gate.fire(rule, held(ControlGateService.Kind.SCAN, null, rule.key(), null)));
        assertNull(gate.bypass);
        assertEquals(ControlGateService.Outcome.PASS, gate.fire(rule, held(ControlGateService.Kind.SCAN, null, rule.key(), null)));
        ControlGateService fresh = gate("", "");
        assertEquals(ControlGateService.Outcome.STOP, fresh.fire(rule, held(ControlGateService.Kind.SCAN, null, rule.key(), null)));
        assertEquals("question", fresh.state.ticket.transientError);
        ControlGateService notified = gate("", "");
        ControlRule inform = ControlRule.parseLine("barcode:1=BACKOFFICE;notify");
        notified.fire(inform, held(ControlGateService.Kind.SCAN, null, inform.key(), null));
        verify(notified.technicalEventService).record(eq(TechnicalEvent.EventType.CONTROL_ALERT),
                eq("barcode:1 BACKOFFICE"), isNull(), isNull(), isNull(), eq("barcode:1"));
    }

    /**
     * A supervisor's validation: a supervisor at the till passes when the
     * rule names no profile; otherwise the gesture is held, an HTTP one through
     * the function approvals, a scan through the gate's own action, the rule's
     * profiles kept.
     */
    @Test
    void fireHoldsForASupervisor() {
        ControlGateService self = gate("", "");
        ControlRule plain = ControlRule.parseLine("barcode:1=SUPERVISOR");
        when(self.endorsementService.selfAuthorized(self.state)).thenReturn(true);
        assertEquals(ControlGateService.Outcome.PASS, self.fire(plain, held(ControlGateService.Kind.SCAN, null, plain.key(), null)));
        ControlGateService http = gate("", "");
        ControlRule profiled = ControlRule.parseLine("function:POST /action/x=SUPERVISOR;profiles=Chef");
        when(http.endorsementService.selfAuthorized(http.state)).thenReturn(true);
        ControlGateService.Held gesture = held(ControlGateService.Kind.HTTP, "POST", profiled.key(), null);
        assertEquals(ControlGateService.Outcome.STOP, http.fire(profiled, gesture));
        verify(http.functionApprovals).hold("POST", "/action/x", "a=1");
        verify(http.endorsementService).requestAuthorization(http.state, FunctionApprovalService.ACTION);
        assertSame(gesture, http.passed);
        assertEquals(List.of("Chef"), http.state.endorsement.requiredProfiles);
        ControlGateService scan = gate("", "");
        ControlGateService.Held code = held(ControlGateService.Kind.SCAN, null, plain.key(), null);
        assertEquals(ControlGateService.Outcome.STOP, scan.fire(plain, code));
        verify(scan.endorsementService).requestAuthorization(scan.state, ControlGateService.ACTION);
        assertSame(code, scan.passed);
    }

    /**
     * A validated scan or article is read again once; nothing else is.
     */
    @Test
    void resumeAfterEndorsementReplaysScansAndArticles() {
        ControlGateService gate = gate("", "");
        gate.resumeAfterEndorsement();
        gate.passed = held(ControlGateService.Kind.HTTP, "POST", "k", null);
        gate.resumeAfterEndorsement();
        verify(gate.ticketService, never()).processScan(anyString());
        gate.passed = held(ControlGateService.Kind.SCAN, null, "k", null);
        gate.resumeAfterEndorsement();
        verify(gate.ticketService).processScan("code");
        gate.passed = held(ControlGateService.Kind.EAN, null, "k", null);
        gate.resumeAfterEndorsement();
        verify(gate.ticketService).addItemByEan(gate.state, "code", BigDecimal.ONE);
        gate.passed = held(ControlGateService.Kind.PLU, null, "k", null);
        gate.resumeAfterEndorsement();
        verify(gate.ticketService).addItemByPlu(gate.state, "code");
        assertNull(gate.bypass);
    }

    /**
     * The answer to a justification: none or an unknown one abandons, a known
     * one is journalled and replays the gesture.
     */
    @Test
    void answerToAJustification() {
        ControlGateService gate = gate("", "");
        assertEquals("/", gate.answer("x"));
        gate.pending = held(ControlGateService.Kind.HTTP, "POST", null, "F");
        assertEquals("/", gate.answer(null));
        assertEquals("ctl.justificationAbandonnee", gate.state.ticket.transientError);
        gate.pending = held(ControlGateService.Kind.HTTP, "POST", null, "F");
        assertEquals("/", gate.answer("9"));
        gate.pending = held(ControlGateService.Kind.HTTP, "POST", null, "F");
        when(gate.posListService.justificationOf("F", "1")).thenReturn(new PosListService.ListChoice("1", "Erreur"));
        assertEquals("/control-gate/continue?token=tok", gate.answer("1"));
        verify(gate.technicalEventService).record(TechnicalEvent.EventType.JUSTIFICATION_CHOSEN, "1 - Erreur",
                null, null, null, "F");
        assertEquals("tok", gate.continuation("tok").token());
        assertNull(gate.continuation("other"));
    }

    /**
     * The answer to a question: NO abandons, YES replays a GET by its URL, a
     * POST through its continuation, a scan at once.
     */
    @Test
    void answerToAQuestion() {
        ControlGateService gate = gate("", "");
        assertNull(gate.continuation("tok"));
        gate.pending = held(ControlGateService.Kind.HTTP, "GET", "k", null);
        assertEquals("/", gate.answer("NO"));
        assertEquals("ctl.fonctionAbandonnee", gate.state.ticket.transientError);
        gate.pending = held(ControlGateService.Kind.HTTP, "GET", "k", null);
        assertEquals("/action/x?_posGate=tok", gate.answer("YES"));
        gate.pending = new ControlGateService.Held(ControlGateService.Kind.HTTP, "GET", "/action/x?a=1", "",
                null, null, "k", null, "q", "tok");
        assertEquals("/action/x?a=1&_posGate=tok", gate.answer("YES"));
        gate.pending = held(ControlGateService.Kind.SCAN, null, "k", null);
        assertEquals("/", gate.answer("YES"));
        verify(gate.ticketService).processScan("code");
    }

    /**
     * The journal names the ticket under way when there is one.
     */
    @Test
    void traceNamesTheDraft() {
        ControlGateService gate = gate("", "");
        gate.state.payment.ticketDbId = 5L;
        gate.state.auth.operatorBadgeId = "12341234";
        Ticket draft = new Ticket();
        draft.ticketNumber = "T5";
        ControlRule rule = ControlRule.parseLine("barcode:1=BACKOFFICE");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(draft);
            gate.fire(rule, held(ControlGateService.Kind.SCAN, null, rule.key(), null));
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(null);
            gate.fire(rule, held(ControlGateService.Kind.SCAN, null, rule.key(), null));
        }
        verify(gate.technicalEventService).record(TechnicalEvent.EventType.CONTROL_ALERT, "barcode:1 BACKOFFICE",
                "12341234", "T5", null, "barcode:1");
        verify(gate.technicalEventService).record(TechnicalEvent.EventType.CONTROL_ALERT, "barcode:1 BACKOFFICE",
                "12341234", null, null, "barcode:1");
    }

    /**
     * An alert on an article read before the ticket exists waits for the
     * ticket to name it; one already named, one the journal kept no row of
     * and one on a function outside a sale do not wait.
     */
    @Test
    void anArticleAlertWaitsForItsTicket() {
        ControlGateService gate = gate("", "");
        TechnicalEvent waiting = new TechnicalEvent();
        waiting.id = 8L;
        TechnicalEvent named = new TechnicalEvent();
        named.id = 9L;
        named.ticketNumber = "T5";
        TechnicalEvent unsaved = new TechnicalEvent();
        TechnicalEvent function = new TechnicalEvent();
        function.id = 10L;
        when(gate.technicalEventService.record(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(waiting, named, unsaved, null, function);
        ControlRule rule = ControlRule.parseLine("article:1=BACKOFFICE");
        for (int i = 0; i < 4; i++) {
            gate.fire(rule, held(ControlGateService.Kind.EAN, null, rule.key(), null));
        }
        gate.fire(rule, held(ControlGateService.Kind.HTTP, "GET", rule.key(), null));
        assertEquals(java.util.List.of(8L), gate.state.ticket.eventsAwaitingTicket);
    }

    /**
     * The message of a rule is its own, or the register's naming the target.
     */
    @Test
    void ruleMessages() {
        ControlGateService gate = gate("barcode:1=YESNO;text=Sûr ?\nbarcode:2=YESNO\noffline=YESNO", "");
        gate.onScan("1", false);
        assertEquals("Sûr ?", gate.pending.question());
        gate.onScan("2", false);
        assertEquals("ALERTE ctl.rs21-cible-barcode 2", gate.pending.question());
        gate.onScan("3", true);
        assertEquals("ALERTE ctl.rs21-cible-offline", gate.pending.question());
    }
}
