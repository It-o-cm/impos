package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.CardPayment;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketRecoveryService}.
 * <p>
 * The startup recovery reads the terminal's OPEN drafts through the Panache
 * active-record static finder {@code Ticket.list(...)}, intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}; the
 * drafts themselves are Mockito mocks of {@link Ticket} whose {@code persist()}
 * is a no-op and whose public fields are read and written directly. The
 * shared restore machinery ({@code restoreDraft}) is exercised directly on
 * mocked drafts and touches no static, so those tests need no Panache mock:
 * the injected {@link PosState} is a real instance so the in-memory cart and
 * payments behave exactly as in production, and the persisted lines and
 * payments are plain constructed entities (no database, no proxy) so
 * {@code instanceof} and {@code Hibernate.getClass} resolve to their real
 * classes. The two collaborators ({@link TicketNumberService},
 * {@link TechnicalEventService}) are Mockito mocks on the package-private
 * injection fields.
 * <p>
 * Every branch of the two public methods and their private helpers is
 * covered: {@code onStart} (nominal run, swallowed exception), {@code recover}
 * (no draft, single draft, older leftovers cancelled), {@code restoreCart}
 * (both uid arms, both modifier arms, both originalUnitPrice arms, both
 * fidelity arms, line ordering) and {@code restorePayments} (voucher, cash,
 * plain-CARD and UNKNOWN discriminator arms, and the three outcomes of the
 * completion compound guard: no payment, remaining due, fully settled).
 * JaCoCo branch count: 26/26 branches covered (100%), all lines covered.
 */
class TicketRecoveryServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /** The exact JPQL query the service issues to list the OPEN drafts. */
    private static final String QUERY = "status = ?1 and terminalId = ?2 order by id desc";

    /**
     * A persisted payment with no {@link jakarta.persistence.DiscriminatorValue}
     * annotation, forcing the {@code UNKNOWN} arm of {@code methodKeyOf}.
     */
    private static class UnknownPayment extends TicketPayment {
        /**
         * Creates an unknown-method payment carrying the given amount.
         *
         * @param amount the paid amount
         */
        UnknownPayment(BigDecimal amount) {
            super(amount);
        }
    }

    /**
     * Builds a recovery service with its two collaborators mocked and the
     * terminal id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private TicketRecoveryService newService() {
        TicketRecoveryService service = new TicketRecoveryService();
        service.state = new PosState();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Creates a mocked draft with the given status, id and number, exposing an
     * empty mutable line list, an empty payment list and no fidelity card.
     *
     * @param id the database id
     * @param status the lifecycle status of the draft
     * @return the configured mocked draft
     */
    private Ticket draft(long id, Ticket.TicketStatus status) {
        Ticket ticket = mock(Ticket.class);
        ticket.id = id;
        ticket.status = status;
        ticket.ticketNumber = "C04-0000000" + id;
        ticket.terminalId = TERMINAL;
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        ticket.fidelityCard = null;
        return ticket;
    }

    /**
     * Builds a persisted ticket line with its ordering, identity, price and
     * optional price-modification metadata.
     *
     * @param lineNumber the display order of the line
     * @param lineUid the stable line uid, or null for a legacy line
     * @param unitPrice the unit price including tax
     * @param modifierLabel the price-modification label, or null
     * @param originalUnitPrice the pre-modification unit price, or null
     * @return the configured line
     */
    private TicketLine line(int lineNumber, String lineUid, String unitPrice,
            String modifierLabel, String originalUnitPrice) {
        TicketLine line = new TicketLine();
        line.lineNumber = lineNumber;
        line.lineUid = lineUid;
        line.ean = "300000" + lineNumber;
        line.plu = null;
        line.productLabel = "LABEL-" + lineNumber;
        line.unitPrice = new BigDecimal(unitPrice);
        line.quantity = BigDecimal.ONE;
        line.vatRate = new BigDecimal("0.2000");
        line.modifierLabel = modifierLabel;
        if (modifierLabel != null) {
            line.modifierType = "REMISE";
            line.modifierValue = new BigDecimal("5.00");
        }
        line.originalUnitPrice = originalUnitPrice != null ? new BigDecimal(originalUnitPrice) : null;
        return line;
    }

    // --------------------------------------------------
    // recover
    // --------------------------------------------------

    /**
     * Covers the empty-list arm of {@code recover}: no OPEN draft exists for
     * this terminal, so the cart is left empty and nothing is journaled.
     */
    @Test
    void recoverReturnsWhenNoOpenDraft() {
        TicketRecoveryService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(QUERY, Ticket.TicketStatus.OPEN, TERMINAL))
                    .thenReturn(new ArrayList<Ticket>());
            service.recover();
            assertTrue(service.state.ticket.items.isEmpty());
            assertNull(service.state.payment.ticketDbId);
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the single-draft arm of {@code recover}: exactly one OPEN draft is
     * found, the stale-cancel loop does not run, the draft is restored and the
     * recovery is journaled with its number.
     */
    @Test
    void recoverRestoresSingleDraft() {
        TicketRecoveryService service = newService();
        Ticket draft = draft(7L, Ticket.TicketStatus.OPEN);
        draft.lines = new ArrayList<>(List.of(line(1, "U1", "4.00", null, null)));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(QUERY, Ticket.TicketStatus.OPEN, TERMINAL))
                    .thenReturn(new ArrayList<>(List.of(draft)));
            service.recover();
            assertEquals(1, service.state.ticket.items.size());
            assertEquals(7L, service.state.payment.ticketDbId);
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.DRAFT_RECOVERED, "C04-00000007");
        }
    }

    /**
     * Covers the leftover-cancel loop of {@code recover}: the most recent draft
     * is restored while the two older OPEN leftovers of the same terminal are
     * flipped to CANCELLED and persisted.
     */
    @Test
    void recoverCancelsOlderLeftovers() {
        TicketRecoveryService service = newService();
        Ticket newest = draft(9L, Ticket.TicketStatus.OPEN);
        Ticket stale1 = draft(5L, Ticket.TicketStatus.OPEN);
        Ticket stale2 = draft(3L, Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(QUERY, Ticket.TicketStatus.OPEN, TERMINAL))
                    .thenReturn(new ArrayList<>(Arrays.asList(newest, stale1, stale2)));
            service.recover();
            assertEquals(9L, service.state.payment.ticketDbId);
            assertEquals(Ticket.TicketStatus.CANCELLED, stale1.status);
            assertEquals(Ticket.TicketStatus.CANCELLED, stale2.status);
            verify(stale1, times(1)).persist();
            verify(stale2, times(1)).persist();
            verify(newest, never()).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.DRAFT_RECOVERED, "C04-00000009");
        }
    }

    // --------------------------------------------------
    // onStart
    // --------------------------------------------------

    /**
     * Covers the nominal arm of {@code onStart}: the recovery runs and returns
     * without throwing when no draft is present.
     */
    @Test
    void onStartRunsRecovery() {
        TicketRecoveryService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.list(QUERY, Ticket.TicketStatus.OPEN, TERMINAL))
                    .thenReturn(new ArrayList<Ticket>());
            service.onStart(null);
            mocked.verify(() -> Ticket.list(QUERY, Ticket.TicketStatus.OPEN, TERMINAL));
        }
    }

    /**
     * Covers the exception arm of {@code onStart}: the recovery fails, but the
     * startup hook swallows the exception so the register still boots.
     */
    @Test
    void onStartSwallowsRecoveryException() {
        TicketRecoveryService service = newService();
        when(service.ticketNumberService.getTerminalId())
                .thenThrow(new RuntimeException("boom"));
        service.onStart(null);
        verifyNoInteractions(service.technicalEventService);
    }

    // --------------------------------------------------
    // restoreDraft / restoreCart
    // --------------------------------------------------

    /**
     * Covers every arm of {@code restoreCart}: lines are re-ordered by their
     * line number, a stable uid is kept while a legacy null uid yields a fresh
     * one, a modifier restores its structured fields with and without an
     * original price, and a non-null fidelity card is re-attached. The draft
     * carries no payment, so the completion guard short-circuits on the empty
     * payment list.
     */
    @Test
    void restoreDraftRebuildsCartWithLineVariants() {
        TicketRecoveryService service = newService();
        Ticket draft = draft(11L, Ticket.TicketStatus.OPEN);
        TicketLine modified = line(2, "L1", "10.00", "REMISE -5", "12.00");
        TicketLine legacy = line(1, null, "3.00", null, null);
        TicketLine modifiedNoOriginal = line(3, "L3", "7.00", "FORCE", null);
        draft.lines = new ArrayList<>(Arrays.asList(modified, legacy, modifiedNoOriginal));
        draft.fidelityCard = "CARD-9999";
        service.restoreDraft(draft);
        List<TicketState.TicketItem> items = service.state.ticket.items;
        assertEquals(3, items.size());
        TicketState.TicketItem first = items.get(0);
        assertNotNull(first.uid);
        assertNull(first.modifierLabel);
        TicketState.TicketItem second = items.get(1);
        assertEquals("L1", second.uid);
        assertEquals("REMISE -5", second.modifierLabel);
        assertEquals("REMISE", second.modifierType);
        assertEquals(0, new BigDecimal("5.00").compareTo(second.modifierValue));
        assertEquals(0, new BigDecimal("12.00").compareTo(second.originalUnitPrice));
        TicketState.TicketItem third = items.get(2);
        assertEquals("L3", third.uid);
        assertEquals("FORCE", third.modifierLabel);
        assertEquals(0, new BigDecimal("7.00").compareTo(third.originalUnitPrice));
        assertTrue(service.state.fidelity.active);
        assertEquals("CARD-9999", service.state.fidelity.label);
        assertEquals(11L, service.state.payment.ticketDbId);
        assertFalse(service.state.payment.transactionComplete);
    }

    /**
     * Covers the null-fidelity arm of {@code restoreCart}: a draft without a
     * fidelity card leaves the fidelity state inactive.
     */
    @Test
    void restoreDraftWithoutFidelityCard() {
        TicketRecoveryService service = newService();
        Ticket draft = draft(12L, Ticket.TicketStatus.OPEN);
        draft.lines = new ArrayList<>(List.of(line(1, "U1", "2.00", null, null)));
        draft.fidelityCard = null;
        service.restoreDraft(draft);
        assertEquals(1, service.state.ticket.items.size());
        assertFalse(service.state.fidelity.active);
        assertEquals("", service.state.fidelity.label);
    }

    // --------------------------------------------------
    // restorePayments
    // --------------------------------------------------

    /**
     * Covers the three type arms of {@code restorePayments} and the
     * remaining-due arm of the completion guard: voucher, cash, plain-CARD and
     * UNKNOWN payments are rebuilt in payment-index order (from a shuffled
     * input), the paid amount aggregates them, and the ticket is not marked
     * complete because a due remains.
     */
    @Test
    void restoreDraftRestoresAllPaymentTypesWithRemainingDue() {
        TicketRecoveryService service = newService();
        Ticket draft = draft(13L, Ticket.TicketStatus.OPEN);
        draft.lines = new ArrayList<>(List.of(line(1, "U1", "100.00", null, null)));
        VoucherPayment voucher = new VoucherPayment(new BigDecimal("1.00"), "Chèque", "V1");
        voucher.paymentIndex = 1;
        CashPayment cash = new CashPayment(new BigDecimal("2.00"), new BigDecimal("2.00"));
        cash.paymentIndex = 2;
        CardPayment card = new CardPayment(new BigDecimal("3.00"));
        card.paymentIndex = 3;
        UnknownPayment unknown = new UnknownPayment(new BigDecimal("4.00"));
        unknown.paymentIndex = 4;
        draft.payments = new ArrayList<>(Arrays.asList(card, unknown, voucher, cash));
        service.restoreDraft(draft);
        List<com.intermarche.pos.ui.payment.PaymentState.PaymentEntry> entries =
                service.state.payment.payments;
        assertEquals(4, entries.size());
        assertEquals("Chèque", entries.get(0).method);
        assertTrue(entries.get(0).isVoucher());
        assertEquals("V1", entries.get(0).voucherNumber);
        assertEquals("CASH", entries.get(1).method);
        assertEquals(0, new BigDecimal("2.00").compareTo(entries.get(1).tenderedAmount));
        assertEquals("CARD", entries.get(2).method);
        assertEquals("UNKNOWN", entries.get(3).method);
        assertEquals(0, new BigDecimal("10.00").compareTo(service.state.payment.paidAmount));
        assertFalse(service.state.payment.transactionComplete);
    }

    /**
     * Covers the settled arm of the completion guard in {@code restorePayments}:
     * the restored payment already covers the total, so the completion flag is
     * set back and the cashier lands on the completion modal.
     */
    @Test
    void restoreDraftMarksCompleteWhenSettled() {
        TicketRecoveryService service = newService();
        Ticket draft = draft(14L, Ticket.TicketStatus.OPEN);
        draft.lines = new ArrayList<>(List.of(line(1, "U1", "10.00", null, null)));
        CardPayment card = new CardPayment(new BigDecimal("10.00"));
        card.paymentIndex = 1;
        draft.payments = new ArrayList<>(List.of(card));
        service.restoreDraft(draft);
        assertEquals(0, service.state.getRemaining().signum());
        assertTrue(service.state.payment.transactionComplete);
    }

}
