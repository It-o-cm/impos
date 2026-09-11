package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the rules that surround the abandon of a ticket
 * ({@code LC-04-04-06} to {@code -12}).
 *
 * <p>Three administered rules, and each is exercised on every value a shop can give it
 * plus the one it can mistype: the partial-settlement policy on both its arms and on
 * the two kinds of settlement it tells apart, the printing rule on its three values,
 * and the reason on the four ways an operator or a stale page can get it wrong.
 *
 * <p>The settings are a hand-written stand-in rather than a mock, so the whole class
 * runs without Mockito.
 */
class TicketAbandonServiceTest {

    /** Settings whose administered values each test sets directly. */
    private static class FakeSettings extends PosSettingsService {

        /** The administered abandon reasons. */
        private String reasons = "Erreur de saisie;Client parti";

        /** The administered partial-settlement policy. */
        private String partialPayment = "CONFIRM";

        /** The administered printing rule. */
        private String print = "NEVER";

        /** {@inheritDoc} */
        @Override
        public String abandonReasons() {
            return reasons;
        }

        /** {@inheritDoc} */
        @Override
        public String abandonPartialPayment() {
            return partialPayment;
        }

        /** {@inheritDoc} */
        @Override
        public String abandonPrint() {
            return print;
        }
    }

    /** The service under test. */
    private TicketAbandonService service;

    /** The real register state the service reads and writes. */
    private PosState state;

    /** The stand-in settings. */
    private FakeSettings settings;

    /**
     * Wires a fresh service to a fresh state before each test.
     */
    @BeforeEach
    void setUp() {
        service = new TicketAbandonService();
        state = new PosState();
        settings = new FakeSettings();
        service.state = state;
        service.posSettingsService = settings;
    }

    /**
     * Registers a settlement on the sale.
     *
     * @param method        the settlement method key
     * @param amount        the amount settled
     * @param authorization the external authorization number, or null
     */
    private void settle(String method, String amount, String authorization) {
        PaymentState.PaymentEntry entry =
                new PaymentState.PaymentEntry(method, new BigDecimal(amount));
        entry.authorizationNumber = authorization;
        state.payment.payments.add(entry);
    }

    // --------------------------------------------------
    // The reasons (LC-04-04-10)
    // --------------------------------------------------

    /**
     * The administered reasons are offered in order, padding trimmed.
     */
    @Test
    void theAdministeredReasonsAreOffered() {
        settings.reasons = " Erreur de saisie ;Client parti; ";
        assertEquals(List.of("Erreur de saisie", "Client parti"), service.reasons());
        assertTrue(service.requiresReason());
    }

    /**
     * A shop that administers no reason is not asked for one — the blank leg, which
     * keeps the abandon a one-touch gesture where that is the policy.
     */
    @Test
    void withoutAdministeredReasonsNoneIsRequired() {
        settings.reasons = "";
        assertTrue(service.reasons().isEmpty());
        assertFalse(service.requiresReason());
    }

    /**
     * A reason the shop offers is recorded on the state.
     */
    @Test
    void anOfferedReasonIsRecorded() {
        assertNull(service.prepare("Client parti", false));
        assertEquals("Client parti", state.abandonReason);
    }

    /**
     * No reason at all is refused where the shop asks for one.
     */
    @Test
    void aMissingReasonIsRefused() {
        assertEquals("MOTIF D'ABANDON OBLIGATOIRE", service.prepare(null, false));
        assertEquals("", state.abandonReason);
        assertEquals("MOTIF D'ABANDON OBLIGATOIRE", service.prepare("   ", false));
    }

    /**
     * A reason the shop does not offer is refused: the screen is a list of buttons, so
     * anything else came from a stale page, and the reason is what the reports count.
     */
    @Test
    void anUnknownReasonIsRefused() {
        assertEquals("MOTIF D'ABANDON INCONNU", service.prepare("Parce que", false));
        assertEquals("", state.abandonReason);
    }

    /**
     * Where no reason is administered, none is asked and the abandon proceeds — the
     * other arm of the mandatory check.
     */
    @Test
    void withoutAdministeredReasonsTheAbandonProceeds() {
        settings.reasons = "";
        assertNull(service.prepare("", false));
        assertEquals("", state.abandonReason);
    }

    // --------------------------------------------------
    // The partial settlement (LC-04-04-06 to -09)
    // --------------------------------------------------

    /**
     * Under the CONFIRM policy nothing is blocked, whatever was settled: the screen
     * names the settlements and the operator validates.
     */
    @Test
    void underConfirmNothingIsBlocked() {
        settle("CASH", "10.00", null);
        settle("CARD", "5.00", "A1B2C3");
        assertNull(service.blockingReason());
        assertNull(service.prepare("Client parti", false));
    }

    /**
     * Under BLOCK, a settlement taken WITHOUT an external call is refused with its own
     * message ({@code LC-04-04-06}).
     */
    @Test
    void underBlockALocalSettlementIsRefused() {
        settings.partialPayment = "BLOCK";
        settle("CASH", "10.00", null);
        assertEquals("REGLEMENT PARTIEL ENREGISTRE - ANNULEZ-LE D'ABORD",
                service.blockingReason());
        assertEquals("REGLEMENT PARTIEL ENREGISTRE - ANNULEZ-LE D'ABORD",
                service.prepare("Client parti", false));
        // The refusal comes BEFORE the reason is recorded: nothing was abandoned.
        assertEquals("", state.abandonReason);
    }

    /**
     * Under BLOCK, a settlement that reached an external system is refused with the
     * OTHER message ({@code LC-04-04-08}): undoing a card authorization is not undoing
     * a cash tender, and the operator has to be told which is in front of them.
     */
    @Test
    void underBlockAnExternalSettlementIsRefusedWithItsOwnMessage() {
        settings.partialPayment = "BLOCK";
        settle("CARD", "5.00", null);
        assertEquals("REGLEMENT PARTIEL AVEC APPEL EXTERNE - ANNULEZ-LE D'ABORD",
                service.blockingReason());
    }

    /**
     * An authorization number makes a settlement external whatever its method key —
     * the other leg of that test, which a gift card debited at a registry takes.
     */
    @Test
    void anAuthorizationNumberMakesASettlementExternal() {
        settings.partialPayment = "BLOCK";
        settle("CARTE CADEAU", "5.00", "REG-42");
        assertEquals("REGLEMENT PARTIEL AVEC APPEL EXTERNE - ANNULEZ-LE D'ABORD",
                service.blockingReason());
    }

    /**
     * A blank authorization number is no authorization — the emptiness leg, which a
     * degraded monetics leaves behind.
     */
    @Test
    void aBlankAuthorizationNumberIsNotExternal() {
        settings.partialPayment = "BLOCK";
        settle("CHEQUE", "5.00", "  ");
        assertEquals("REGLEMENT PARTIEL ENREGISTRE - ANNULEZ-LE D'ABORD",
                service.blockingReason());
    }

    /**
     * Both kinds settled at once: the external message wins, because it names the one
     * the operator cannot undo alone.
     */
    @Test
    void theExternalMessageWinsOverTheLocalOne() {
        settings.partialPayment = "BLOCK";
        settle("CASH", "10.00", null);
        settle("CARD", "5.00", "A1B2C3");
        assertEquals("REGLEMENT PARTIEL AVEC APPEL EXTERNE - ANNULEZ-LE D'ABORD",
                service.blockingReason());
    }

    /**
     * Under BLOCK with nothing settled, nothing is blocked — the empty leg, and by far
     * the commonest abandon.
     */
    @Test
    void underBlockAnUnsettledSaleIsNotBlocked() {
        settings.partialPayment = "BLOCK";
        assertNull(service.blockingReason());
    }

    /**
     * A policy nobody administered, or one a shop mistyped, does not block: a lane
     * stopped by a typing mistake is worse than an abandon that goes through.
     */
    @Test
    void anUnknownPolicyDoesNotBlock() {
        settings.partialPayment = "";
        settle("CASH", "10.00", null);
        assertNull(service.blockingReason());
        settings.partialPayment = "BLOQUANT";
        assertNull(service.blockingReason());
    }

    /**
     * The settlements and their total are reported for the screen that names them.
     */
    @Test
    void theSettlementsAndTheirTotalAreReported() {
        settle("CASH", "10.00", null);
        settle("CARD", "5.50", "A1");
        assertEquals(2, service.partialPayments().size());
        assertEquals(new BigDecimal("15.50"), service.partialPaymentTotal());
    }

    /**
     * A settlement without an amount counts as nothing rather than failing — the null
     * leg of the sum.
     */
    @Test
    void aSettlementWithoutAnAmountCountsAsNothing() {
        state.payment.payments.add(new PaymentState.PaymentEntry("CASH", null));
        assertEquals(BigDecimal.ZERO, service.partialPaymentTotal());
    }

    // --------------------------------------------------
    // The printing (LC-04-04-12)
    // --------------------------------------------------

    /**
     * NEVER prints nothing and offers no choice.
     */
    @Test
    void neverPrintsNothing() {
        assertFalse(service.printOnDemand());
        assertFalse(service.printsTicket());
    }

    /**
     * ALWAYS prints without asking.
     */
    @Test
    void alwaysPrintsWithoutAsking() {
        settings.print = "ALWAYS";
        assertFalse(service.printOnDemand());
        assertTrue(service.printsTicket());
    }

    /**
     * ON_DEMAND offers the choice and follows it, on both its answers.
     */
    @Test
    void onDemandFollowsTheOperator() {
        settings.print = "ON_DEMAND";
        assertTrue(service.printOnDemand());
        assertNull(service.prepare("Client parti", true));
        assertTrue(service.printsTicket());
        assertNull(service.prepare("Client parti", false));
        assertFalse(service.printsTicket());
    }

    /**
     * A printing rule a shop mistyped prints nothing: the silent behaviour is the one
     * that cannot surprise a lane.
     */
    @Test
    void anUnknownPrintingRulePrintsNothing() {
        settings.print = "PARFOIS";
        assertFalse(service.printOnDemand());
        assertFalse(service.printsTicket());
    }

    /**
     * The operator's printing choice is recorded even where the shop prints anyway,
     * so switching the rule mid-shift cannot leave a stale request behind.
     */
    @Test
    void thePrintingChoiceIsRecorded() {
        assertNull(service.prepare("Client parti", true));
        assertTrue(state.abandonPrintRequested);
        assertNull(service.prepare("Client parti", false));
        assertFalse(state.abandonPrintRequested);
    }
}
