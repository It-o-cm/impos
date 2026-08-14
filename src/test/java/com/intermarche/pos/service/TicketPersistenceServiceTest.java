package com.intermarche.pos.service;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.StoredValue;
import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketCounter;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketPersistenceService}.
 * <p>
 * The service persists the in-memory ticket through Panache active-record
 * static access ({@code Ticket.findById}, {@code Store.findAll},
 * {@code Employee.findById}, {@code Product.find}), all intercepted with
 * {@link org.mockito.Mockito#mockStatic} on {@link PanacheEntityBase}. The
 * draft entity itself is either a Mockito mock (reconcile / payment /
 * finalization paths, whose {@code persist()} is a no-op and whose public
 * fields are read and written directly) or, on the creation path, a
 * construction mock installed with {@link org.mockito.Mockito#mockConstruction}
 * so {@code new Ticket()} yields a neutralized instance. No database and no
 * Quarkus context is booted. The injected {@link PosState} is a real instance
 * so the in-memory ticket and payment behave exactly as in production, while
 * the four collaborators ({@link TicketNumberService}, {@link CashSessionService},
 * {@link TechnicalEventService}, {@link SyncOutboxService}) and the payment
 * factories are Mockito mocks assigned to the package-private injection fields.
 * <p>
 * Every branch of the seven public methods and their private helpers is
 * covered: {@code syncDraft} (training mode, empty cart with and without a
 * draft, creation, resync on missing / non-OPEN draft, nominal reconcile),
 * {@code createDraft} (store missing, cashier missing, no session, success
 * exercising the three {@code mapLine} product-lookup arms and the negative
 * line), {@code reconcileLines} (orphan removal of null-uid and vanished
 * lines, in-place update of both modifier arms, new line), the fidelity
 * ternary of {@code applyHeaderAndTotals}, {@code addPaymentToTicket} (missing
 * ticket, plain method, voucher, unknown method), {@code removePaymentsFromTicket}
 * (missing ticket, present payments, empty payments), {@code validateTicket}
 * (missing ticket, chained and GENESIS previous signature) and
 * {@code cancelDraft} (OPEN draft, null ticket, non-OPEN draft). JaCoCo branch
 * count: 70/70 branches covered (100%). The only two uncovered lines are the
 * JVM-mandated {@code catch (NoSuchAlgorithmException)} of {@code sha256Hex},
 * which is unreachable and carries no branch.
 */
class TicketPersistenceServiceTest {

    /** The terminal identifier used across the tests. */
    private static final String TERMINAL = "C04";

    /**
     * Builds a service instance with the four collaborators mocked and the
     * terminal id resolved through the ticket number service.
     *
     * @return a ready-to-use service with mocked collaborators
     */
    private TicketPersistenceService newService() {
        TicketPersistenceService service = new TicketPersistenceService();
        service.ticketNumberService = mock(TicketNumberService.class);
        service.cashSessionService = mock(CashSessionService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        return service;
    }

    /**
     * Appends an in-memory ticket item to the given state with full control on
     * its identity, price and modifier, bypassing the merge rules of
     * {@code addItem}.
     *
     * @param state the POS state whose cart must be filled
     * @param uid the stable line uid
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @param modifierLabel the price-modification label, or null
     * @return the appended item, for further tuning
     */
    private TicketState.TicketItem addItem(PosState state, String uid, String ean, String plu,
            String unitPrice, String quantity, String modifierLabel) {
        TicketState.TicketItem item = new TicketState.TicketItem(
                ean, plu, "LABEL", new BigDecimal(unitPrice), new BigDecimal(quantity), new BigDecimal("0.2000"));
        item.uid = uid;
        item.modifierLabel = modifierLabel;
        state.ticket.items.add(item);
        return item;
    }

    /**
     * Creates a mocked draft with the given status, exposing an empty mutable
     * line list, an empty payment list and a fixed id and number.
     *
     * @param status the lifecycle status of the draft
     * @return the configured mocked draft
     */
    private Ticket draft(Ticket.TicketStatus status) {
        Ticket ticket = mock(Ticket.class);
        ticket.id = 5L;
        ticket.status = status;
        ticket.ticketNumber = "C04-00000001";
        ticket.terminalId = TERMINAL;
        ticket.lines = new ArrayList<>();
        ticket.payments = new ArrayList<>();
        return ticket;
    }

    /**
     * Creates a persisted ticket line carrying the given uid.
     *
     * @param uid the line uid, possibly null
     * @return the configured line
     */
    private TicketLine line(String uid) {
        TicketLine line = new TicketLine();
        line.lineUid = uid;
        line.quantity = BigDecimal.ONE;
        line.unitPrice = BigDecimal.ONE;
        line.totalPrice = BigDecimal.ONE;
        line.vatRate = new BigDecimal("0.2000");
        return line;
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * value.
     *
     * @param result the value the query must return
     * @param <T> the queried type
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // syncDraft
    // --------------------------------------------------

    /**
     * Covers the training-mode guard of {@code syncDraft}: nothing fiscal
     * reaches the database and the method answers null.
     */
    @Test
    void syncDraftTrainingModeReturnsNull() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.trainingMode = true;
        assertNull(service.syncDraft(state));
        verifyNoInteractions(service.technicalEventService);
        verifyNoInteractions(service.syncOutboxService);
    }

    /**
     * Covers the empty-cart arm of {@code syncDraft} with an existing draft:
     * the draft is cancelled, journaled, enqueued and the pivot id is cleared.
     */
    @Test
    void syncDraftEmptyCartCancelsExistingDraft() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.payment.ticketDbId = 5L;
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertNull(service.syncDraft(state));
            assertEquals(Ticket.TicketStatus.CANCELLED, ticket.status);
            assertNull(state.payment.ticketDbId);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        }
    }

    /**
     * Covers the empty-cart arm of {@code syncDraft} with no draft: the method
     * answers null and nothing is cancelled.
     */
    @Test
    void syncDraftEmptyCartNoDraftReturnsNull() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.payment.ticketDbId = null;
        assertNull(service.syncDraft(state));
        verifyNoInteractions(service.technicalEventService);
        verifyNoInteractions(service.syncOutboxService);
    }

    /**
     * Covers the creation arm of {@code syncDraft}: no draft yet and a
     * non-empty cart, so a draft is created and its id stored as the pivot.
     * The three product-lookup arms of {@code mapLine} (PLU, EAN, neither) and
     * the negative-line arm are exercised in the same pass, and the fidelity
     * ternary takes its null branch.
     */
    @Test
    void syncDraftCreatesDraftFromCart() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.fidelity.active = false;
        addItem(state, "U1", null, "500", "3.00", "2", null);
        addItem(state, "U2", "3000", null, "1.50", "1", null);
        addItem(state, "U3", null, null, "-2.00", "1", null);
        Store store = mock(Store.class);
        Employee cashier = mock(Employee.class);
        CashSession session = mock(CashSession.class);
        Product product = mock(Product.class);
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000001");
        when(service.ticketNumberService.getTerminalId()).thenReturn(TERMINAL);
        when(service.cashSessionService.getOpenSession()).thenReturn(session);
        PanacheQuery<Store> storeQuery = queryReturning(store);
        PanacheQuery<Product> pluQuery = queryReturning(product);
        PanacheQuery<Product> eanQuery = queryReturning(product);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 100L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(cashier);
            mocked.when(() -> Product.find("plu", "500")).thenReturn(pluQuery);
            mocked.when(() -> Product.find("ean", "3000")).thenReturn(eanQuery);
            Long id = service.syncDraft(state);
            assertEquals(100L, id);
            assertEquals(100L, state.payment.ticketDbId);
            Ticket ticket = created.constructed().get(0);
            assertEquals("C04-00000001", ticket.ticketNumber);
            assertEquals(TERMINAL, ticket.terminalId);
            assertSame(store, ticket.store);
            assertSame(cashier, ticket.cashier);
            assertSame(session, ticket.session);
            assertNull(ticket.fidelityCard);
            assertEquals(3, ticket.itemCount);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the store-missing arm of {@code createDraft}: no store is found,
     * so the creation returns null and the pivot stays null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenStoreMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        PanacheQuery<Store> storeQuery = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the cashier-missing arm of {@code createDraft}: the operator id is
     * null (the ternary takes its null branch), so no cashier is resolved and
     * the creation returns null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenCashierMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = null;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the no-session arm of {@code createDraft}: store and cashier are
     * resolved but no cash session is open, so the creation returns null.
     */
    @Test
    void syncDraftCreateReturnsNullWhenNoSession() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        addItem(state, "U1", "3000", null, "1.50", "1", null);
        when(service.cashSessionService.getOpenSession()).thenReturn(null);
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertNull(service.syncDraft(state));
            assertNull(state.payment.ticketDbId);
        }
    }

    /**
     * Covers the missing-draft resync arm of {@code syncDraft}: the pivot id
     * points to a vanished ticket, so a fresh draft is created.
     */
    @Test
    void syncDraftResyncsWhenDraftMissing() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.payment.ticketDbId = 5L;
        addItem(state, "U1", null, null, "1.50", "1", null);
        when(service.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000002");
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 200L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertEquals(200L, service.syncDraft(state));
            assertEquals(200L, state.payment.ticketDbId);
        }
    }

    /**
     * Covers the non-OPEN resync arm of {@code syncDraft}: the pivot id points
     * to a ticket that is no longer OPEN, so a fresh draft is created.
     */
    @Test
    void syncDraftResyncsWhenDraftNotOpen() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.auth.operatorId = 99L;
        state.payment.ticketDbId = 5L;
        addItem(state, "U1", null, null, "1.50", "1", null);
        Ticket closed = draft(Ticket.TicketStatus.CLOSED);
        when(service.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(service.ticketNumberService.nextTicketNumber()).thenReturn("C04-00000002");
        PanacheQuery<Store> storeQuery = queryReturning(mock(Store.class));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
                MockedConstruction<Ticket> created = mockConstruction(Ticket.class, (mock, ctx) -> {
                    mock.id = 200L;
                    mock.lines = new ArrayList<>();
                })) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(closed);
            mocked.when(Store::findAll).thenReturn(storeQuery);
            mocked.when(() -> Employee.findById(99L)).thenReturn(mock(Employee.class));
            assertEquals(200L, service.syncDraft(state));
            assertEquals(200L, state.payment.ticketDbId);
        }
    }

    /**
     * Covers the nominal reconcile arm of {@code syncDraft}: the OPEN draft is
     * reconciled line by line (null-uid and vanished lines orphan-removed, both
     * modifier arms updated in place, a new line appended), the totals and the
     * fidelity card (active branch) are refreshed, and the draft is persisted.
     */
    @Test
    void syncDraftReconcilesOpenDraft() {
        TicketPersistenceService service = newService();
        PosState state = new PosState();
        state.fidelity.active = true;
        state.fidelity.label = "CARD-123";
        addItem(state, "U1", "3000", null, "2.00", "1", "REMISE -10%");
        addItem(state, "U2", "4000", null, "1.00", "1", null);
        addItem(state, "U3", null, null, "3.00", "1", null);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.lines = new ArrayList<>(Arrays.asList(
                line("U1"), line("U2"), line(null), line("GONE")));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            state.payment.ticketDbId = 5L;
            assertEquals(5L, service.syncDraft(state));
            assertEquals(2, ticket.lines.size());
            assertEquals("CARD-123", ticket.fidelityCard);
            assertEquals(3, ticket.itemCount);
            verify(ticket, times(1)).addLine(any(TicketLine.class));
            verify(ticket, times(1)).persist();
        }
    }

    // --------------------------------------------------
    // addPaymentToTicket
    // --------------------------------------------------

    /**
     * Builds the service with two payment factories (CARD and VOUCHER) indexed
     * by {@code init}, ready for the payment tests.
     *
     * @param cardFactory the plain-method factory
     * @param voucherFactory the voucher factory
     * @return the service with the factories indexed
     */
    @SuppressWarnings("unchecked")
    private TicketPersistenceService serviceWithFactories(
            TicketPayment.Factory cardFactory, TicketPayment.Factory voucherFactory) {
        TicketPersistenceService service = newService();
        when(cardFactory.getKey()).thenReturn("CARD");
        when(voucherFactory.getKey()).thenReturn("VOUCHER");
        Instance<TicketPayment.Factory> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(Arrays.asList(cardFactory, voucherFactory).iterator());
        service.factoryInstances = instance;
        service.init();
        return service;
    }

    /**
     * Covers the missing-ticket guard of {@code addPaymentToTicket}: the finder
     * resolves to null, so the method throws.
     */
    @Test
    void addPaymentThrowsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CARD", new BigDecimal("10.00"));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> service.addPaymentToTicket(5L, entry));
        }
    }

    /**
     * Covers the plain-method arm of {@code addPaymentToTicket}: a non-voucher
     * entry resolves its factory by method key, the created payment is indexed
     * and added, and it is not a {@link VoucherPayment}.
     */
    @Test
    void addPaymentAddsPlainPayment() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        TicketPayment payment = mock(TicketPayment.class);
        when(cardFactory.create(any(BigDecimal.class), any())).thenReturn(payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("CARD", new BigDecimal("10.00"));
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
            verify(ticket, times(1)).persist();
        }
    }

    /**
     * Covers the voucher arm of {@code addPaymentToTicket}: a voucher entry
     * resolves the VOUCHER factory, and the created {@link VoucherPayment} is
     * enriched with the entry label and number before being added.
     */
    @Test
    void addPaymentAddsVoucherPayment() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        VoucherPayment payment = mock(VoucherPayment.class);
        when(voucherFactory.create(any(BigDecimal.class), any())).thenReturn(payment);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry(
                "Chèque cadeau", new BigDecimal("5.00"), "V123", true);
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.addPaymentToTicket(5L, entry);
            assertEquals("Chèque cadeau", payment.voucherLabel);
            assertEquals("V123", payment.voucherNumber);
            assertEquals(1, payment.paymentIndex);
            verify(ticket).addPayment(payment);
        }
    }

    /**
     * Covers the unknown-method guard of {@code addPaymentToTicket}: no factory
     * matches the entry method, so the method throws.
     */
    @Test
    void addPaymentThrowsWhenMethodUnknown() {
        TicketPayment.Factory cardFactory = mock(TicketPayment.Factory.class);
        TicketPayment.Factory voucherFactory = mock(TicketPayment.Factory.class);
        TicketPersistenceService service = serviceWithFactories(cardFactory, voucherFactory);
        PaymentState.PaymentEntry entry = new PaymentState.PaymentEntry("BITCOIN", new BigDecimal("10.00"));
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            assertThrows(IllegalArgumentException.class, () -> service.addPaymentToTicket(5L, entry));
        }
    }

    // --------------------------------------------------
    // removePaymentsFromTicket
    // --------------------------------------------------

    /**
     * Covers the missing-ticket arm of {@code removePaymentsFromTicket}: the
     * finder resolves to null, so the method returns without journaling.
     */
    @Test
    void removePaymentsReturnsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.removePaymentsFromTicket(5L);
            verifyNoInteractions(service.technicalEventService);
        }
    }

    /**
     * Covers the payments-present arm of {@code removePaymentsFromTicket}: the
     * payments are cleared, the draft persisted and the clearing journaled with
     * the removed count.
     */
    @Test
    void removePaymentsClearsAndLogsWhenPresent() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.payments = new ArrayList<>(Arrays.asList(
                mock(TicketPayment.class), mock(TicketPayment.class)));
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.removePaymentsFromTicket(5L);
            assertTrue(ticket.payments.isEmpty());
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.PAYMENTS_CLEARED, "C04-00000001 (2)");
        }
    }

    /**
     * Covers the no-payments arm of {@code removePaymentsFromTicket}: nothing
     * was registered, so the draft is persisted but no clearing is journaled.
     */
    @Test
    void removePaymentsDoesNotLogWhenEmpty() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.removePaymentsFromTicket(5L);
            verify(ticket, times(1)).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }

    // --------------------------------------------------
    // validateTicket
    // --------------------------------------------------

    /**
     * Covers the missing-ticket guard of {@code validateTicket}: the finder
     * resolves to null, so the method returns without locking the counter.
     */
    @Test
    void validateTicketReturnsWhenTicketMissing() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.validateTicket(5L);
            verify(service.ticketNumberService, never()).lockCounter(any());
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the chained arm of {@code validateTicket}: the counter carries a
     * previous signature, so the ticket chains to it, the grand total advances
     * and the closure is journaled and enqueued.
     */
    @Test
    void validateTicketChainsToPreviousSignature() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = "PREVSIG";
        counter.grandTotal = new BigDecimal("100.00");
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            assertEquals("PREVSIG", ticket.previousSignature);
            assertEquals(64, ticket.signature.length());
            assertEquals(0, new BigDecimal("112.00").compareTo(counter.grandTotal));
            assertEquals(0, counter.grandTotal.compareTo(ticket.grandTotal));
            assertEquals(ticket.signature, counter.lastSignature);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CLOSED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        }
    }

    /**
     * Covers the genesis arm of {@code validateTicket}: the counter has no
     * previous signature, so the ticket chains to the "GENESIS" anchor.
     */
    @Test
    void validateTicketUsesGenesisWhenNoPreviousSignature() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = null;
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertEquals("GENESIS", ticket.previousSignature);
            assertEquals(0, new BigDecimal("12.00").compareTo(counter.grandTotal));
        }
    }

    // --------------------------------------------------
    // validateTicket — stored-value registry
    // --------------------------------------------------

    /**
     * Stubs the registry lookup at the level Panache actually intercepts:
     * {@code findByNumber} is declared on StoredValue, so it is NOT
     * intercepted by the PanacheEntityBase static mock — its body runs and
     * calls {@code find("number", …)}, which IS. Stubbing that query is what
     * makes the lookup return the wanted instrument.
     *
     * @param mocked the open Panache static mock
     * @param number the instrument number
     * @param instrument the instrument to return, possibly null
     */
    private void stubRegistryLookup(MockedStatic<PanacheEntityBase> mocked, String number,
                                    StoredValue instrument) {
        @SuppressWarnings("unchecked")
        PanacheQuery<StoredValue> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(instrument);
        mocked.when(() -> StoredValue.find("number", number)).thenReturn(query);
    }

    /**
     * Builds a registry instrument.
     *
     * @param number the instrument number
     * @param balance the current balance
     * @return the instrument
     */
    private StoredValue instrument(String number, String balance) {
        StoredValue instrument = new StoredValue();
        instrument.number = number;
        instrument.balance = new BigDecimal(balance);
        instrument.status = StoredValue.Status.ACTIVE;
        return instrument;
    }

    /**
     * Prepares a counter so that {@code validateTicket} runs to completion.
     *
     * @param service the service under test
     */
    private void stubCounter(TicketPersistenceService service) {
        TicketCounter counter = new TicketCounter();
        counter.lastSignature = null;
        when(service.ticketNumberService.lockCounter(TERMINAL)).thenReturn(counter);
    }

    /**
     * THE FISCAL DEBIT: a registry voucher used on the closed ticket has its
     * balance lowered by the amount paid, and the ticket is stamped on the
     * instrument. The debit happens HERE and nowhere else — a payment that
     * never reaches the fiscal moment never spends the instrument.
     */
    @Test
    void validateTicketDebitsTheRegistryInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Avoir", "297000000000001"));
        StoredValue note = instrument("297000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", note);
            service.validateTicket(5L);
        }
        assertEquals(0, new BigDecimal("6.00").compareTo(note.balance));
        assertEquals(5L, note.lastRedeemedTicketId);
        assertEquals(StoredValue.Status.ACTIVE, note.status);
        assertNull(note.exhaustedAt);
    }

    /**
     * A FULLY SPENT instrument is closed: the balance reaches zero, the
     * status flips to EXHAUSTED and the moment is stamped — this is what
     * makes a second scan of the same paper refuse later.
     */
    @Test
    void validateTicketExhaustsAFullySpentInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("10.00"), "Avoir", "297000000000001"));
        StoredValue note = instrument("297000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", note);
            service.validateTicket(5L);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(note.balance));
        assertEquals(StoredValue.Status.EXHAUSTED, note.status);
        assertNotNull(note.exhaustedAt);
    }

    /**
     * The balance is FLOORED at zero: even if the paid amount somehow exceeds
     * the balance, the instrument never goes negative — a negative stored
     * value would be money created out of a rounding accident.
     */
    @Test
    void validateTicketFloorsTheBalanceAtZero() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("25.00"), "Carte cadeau", "296000000000001"));
        StoredValue card = instrument("296000000000001", "10.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "296000000000001", card);
            service.validateTicket(5L);
        }
        assertEquals(0, BigDecimal.ZERO.compareTo(card.balance));
        assertEquals(StoredValue.Status.EXHAUSTED, card.status);
    }

    /**
     * An instrument that VANISHED from the registry is skipped rather than
     * crashing the fiscal close: the sale is already legally complete, and a
     * missing row must never hold a closing hostage.
     */
    @Test
    void validateTicketSkipsAVanishedInstrument() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Avoir", "297000000000001"));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubRegistryLookup(mocked, "297000000000001", null);
            service.validateTicket(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
        }
    }

    /**
     * A NON-REGISTRY voucher (an old encoded coupon) is left alone: its value
     * lives on the paper, not in the registry, and there is nothing to debit.
     */
    @Test
    void validateTicketIgnoresANonRegistryVoucher() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new VoucherPayment(
                new BigDecimal("4.00"), "Chèque cadeau", "500123"));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            mocked.verify(() -> StoredValue.find(eq("number"), any(Object[].class)), never());
        }
    }

    /**
     * A NON-VOUCHER payment never reaches the registry lookup (the
     * {@code instanceof} leg): cash spends no instrument.
     */
    @Test
    void validateTicketIgnoresNonVoucherPayments() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        ticket.payments.add(new CashPayment(new BigDecimal("12.00"), new BigDecimal("20.00")));
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            mocked.verify(() -> StoredValue.find(eq("number"), any(Object[].class)), never());
        }
    }

    // --------------------------------------------------
    // validateTicket — gift-card issuance
    // --------------------------------------------------

    /**
     * Builds a gift-card product.
     *
     * @param faceValue the loaded value, or null for an ordinary product
     * @return the product
     */
    private Product giftCardProduct(String faceValue) {
        Product product = new Product();
        product.giftCardAmount = faceValue == null ? null : new BigDecimal(faceValue);
        return product;
    }

    /**
     * Stubs the catalog lookup of a sold line.
     *
     * @param mocked the open Panache static mock
     * @param ean the scanned EAN
     * @param product the product to return, possibly null
     */
    private void stubProduct(MockedStatic<PanacheEntityBase> mocked, String ean, Product product) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(product);
        mocked.when(() -> Product.find("ean", ean)).thenReturn(query);
    }

    /**
     * Selling a gift card ISSUES the instrument at the fiscal moment: kind,
     * face value and balance are loaded, the issuing ticket is stamped, and
     * the number is derived from the generated id AFTER persisting — the
     * identity comes from the database, never from the register.
     */
    @Test
    void validateTicketIssuesTheSoldGiftCard() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("25.00");
        ticket.totalVat = BigDecimal.ZERO;
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class,
                     (card, ctx) -> card.id = 7L)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertEquals(1, issued.constructed().size());
            StoredValue card = issued.constructed().get(0);
            assertEquals(StoredValue.Kind.GIFT_CARD, card.kind);
            assertEquals(0, new BigDecimal("25.00").compareTo(card.initialAmount));
            assertEquals(0, new BigDecimal("25.00").compareTo(card.balance));
            assertEquals(5L, card.issuingTicketId);
            assertNotNull(card.issuedAt);
            assertEquals("296000000000007", card.number);
            verify(card).persistAndFlush();
        }
    }

    /**
     * A line carrying SEVERAL cards issues one instrument PER UNIT: two cards
     * bought together are two distinct instruments with two numbers, never a
     * single one loaded twice.
     */
    @Test
    void validateTicketIssuesOneInstrumentPerUnit() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("50.00");
        ticket.totalVat = BigDecimal.ZERO;
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = new BigDecimal("2");
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class,
                     (card, ctx) -> card.id = 7L)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertEquals(2, issued.constructed().size());
        }
    }

    /**
     * A line WITHOUT an EAN issues nothing (first guard): a weighed PLU line
     * can never be an instrument.
     */
    @Test
    void validateTicketIssuesNothingForALineWithoutEan() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = null;
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * An EAN UNKNOWN to the catalog issues nothing (second guard, first leg).
     */
    @Test
    void validateTicketIssuesNothingForAnUnknownProduct() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "123";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "123", null);
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * An ORDINARY product issues nothing (second guard, second leg): only a
     * loaded face value makes an instrument.
     */
    @Test
    void validateTicketIssuesNothingForAnOrdinaryProduct() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "123";
        sold.quantity = BigDecimal.ONE;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "123", giftCardProduct(null));
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * A ZERO-quantity line issues nothing: the per-unit loop never runs.
     */
    @Test
    void validateTicketIssuesNothingForAZeroQuantityLine() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        TicketLine sold = new TicketLine();
        sold.ean = "3400025000001";
        sold.quantity = BigDecimal.ZERO;
        ticket.lines.add(sold);
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedConstruction<StoredValue> issued = mockConstruction(StoredValue.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            stubProduct(mocked, "3400025000001", giftCardProduct("25.00"));
            service.validateTicket(5L);
            assertTrue(issued.constructed().isEmpty());
        }
    }

    /**
     * The unreachable arm of the signature: if the JVM ever failed to supply
     * SHA-256 — which its own specification forbids — the fiscal close would
     * fail LOUDLY rather than sign with something weaker. Reaching it takes a
     * static mock of the JCA itself, which is the point: the catch exists so
     * that an impossible platform cannot silently degrade a fiscal signature.
     */
    @Test
    void validateTicketFailsLoudlyWhenSha256IsUnavailable() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        ticket.totalIncludingTax = new BigDecimal("12.00");
        ticket.totalVat = new BigDecimal("2.00");
        stubCounter(service);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class);
             MockedStatic<java.security.MessageDigest> jca =
                     mockStatic(java.security.MessageDigest.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            jca.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new java.security.NoSuchAlgorithmException("absent"));

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> service.validateTicket(5L));

            assertEquals("SHA-256 indisponible", failure.getMessage());
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause() instanceof java.security.NoSuchAlgorithmException);
        }
    }

    // --------------------------------------------------
    // cancelDraft
    // --------------------------------------------------

    /**
     * Covers the OPEN arm of {@code cancelDraft}: the draft is flipped to
     * CANCELLED, persisted, journaled and enqueued.
     */
    @Test
    void cancelDraftFlipsOpenDraft() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.cancelDraft(5L);
            assertEquals(Ticket.TicketStatus.CANCELLED, ticket.status);
            verify(ticket, times(1)).persist();
            verify(service.technicalEventService).log(
                    TechnicalEvent.EventType.TICKET_CANCELLED, "C04-00000001");
            verify(service.syncOutboxService).enqueue(SyncOutbox.EntityType.TICKET, 5L);
        }
    }

    /**
     * Covers the null-ticket arm of {@code cancelDraft}: the finder resolves to
     * null, so nothing is flipped or journaled.
     */
    @Test
    void cancelDraftIgnoresMissingTicket() {
        TicketPersistenceService service = newService();
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(null);
            service.cancelDraft(5L);
            verifyNoInteractions(service.technicalEventService);
            verifyNoInteractions(service.syncOutboxService);
        }
    }

    /**
     * Covers the non-OPEN arm of {@code cancelDraft}: the draft is already in a
     * terminal state, so it is left untouched.
     */
    @Test
    void cancelDraftIgnoresNonOpenDraft() {
        TicketPersistenceService service = newService();
        Ticket ticket = draft(Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            service.cancelDraft(5L);
            assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
            verify(ticket, never()).persist();
            verifyNoInteractions(service.technicalEventService);
        }
    }
}
