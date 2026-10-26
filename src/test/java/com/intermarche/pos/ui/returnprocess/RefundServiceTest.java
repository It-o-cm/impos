package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.catalog.Product;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.domain.sale.Refund;
import com.intermarche.pos.domain.sale.RefundLine;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.sale.TicketLine;
import com.intermarche.pos.domain.session.CashSession;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.TestMessages;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RefundService}.
 * <p>
 * The service is a plain CDI bean orchestrating the refund flow over the
 * in-memory {@link PosState}/{@link RefundState} (used as real value objects so
 * their mutations and computations are exercised for real) and seven mocked
 * collaborators. Under plain {@code mvn test} the Panache static finders resolve
 * to {@link PanacheEntityBase}: {@code Ticket.find}/{@code Ticket.findById},
 * {@code RefundLine.list} (double-refund guard) and {@code Refund.list} (ticket
 * cap) are intercepted with {@link org.mockito.Mockito#mockStatic}. The refund
 * created inside {@link RefundService#performRefund} is intercepted with
 * {@link org.mockito.Mockito#mockConstruction} so {@code persist()} is a no-op
 * and its {@code lines}/{@code id} fields are seeded; the constructed instance
 * is then asserted field by field. Every branch of every method is covered with
 * absolute expected values.
 */
class RefundServiceTest {

    /** The exact JPQL fragment issued by the ticket search. */
    private static final String SEARCH_QUERY =
            "status = ?1 and lower(ticketNumber) like lower(?2) and creationDate > ?3";

    /**
     * Builds a {@link RefundService} whose collaborators are fresh mocks
     * wired onto its package-private injection fields.
     *
     * @return a service with fully mocked collaborators
     */
    private RefundService newService() {
        RefundService s = new RefundService();
        s.terminalClient = new com.intermarche.pos.ui.hardware.terminal.AutoAcceptTerminalClient();
        // The operator messages come from the French bundle, so every
        // assertion below keeps the literal sentence the register shows
        // (LC-13-06).
        s.i18n = TestMessages.french();
        s.endorsementService = mock(EndorsementService.class);
        s.ticketPrinterService = mock(TicketPrinterService.class);
        s.portugueseFiscalService = mock(com.intermarche.pos.ui.invoice.PortugueseFiscalService.class);
        s.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        // BO-03-02-34: the card receipt is printed, as it always was.
        org.mockito.Mockito.when(s.posSettingsService.cardReceiptPrinted()).thenReturn(true);
        // BO-10-01-04 / -05: the e-commerce return rules over the same parameters,
        // which administer no e-commerce register: no ticket here is concerned.
        s.channelRules = new com.intermarche.pos.ui.recall.ChannelRules(s.posSettingsService);
        s.ecommerceTicketClient = mock(EcommerceTicketClient.class);
        s.posListService = mock(com.intermarche.pos.service.PosListService.class);
        s.cashSessionService = mock(CashSessionService.class);
        s.ticketNumberService = mock(TicketNumberService.class);
        s.technicalEventService = mock(TechnicalEventService.class);
        s.hardwareService = mock(HardwareService.class);
        s.syncOutboxService = mock(SyncOutboxService.class);
        s.ticketSearchService = mock(com.intermarche.pos.ui.ticket.TicketSearchService.class);
        // BO-03-06-30 to -38: no issuing range administered, so the note keeps
        // its historical number, the prefix and its own row id.
        s.storedValueNumberService = mock(com.intermarche.pos.service.StoredValueNumberService.class);
        when(s.storedValueNumberService.numberOf(any(), any())).thenAnswer(
                invocation -> StoredValue.CREDIT_NOTE_PREFIX
                        + String.format("%012d", ((StoredValue) invocation.getArgument(0)).id));
        // A return on a card-bearing ticket enqueues the loyalty event that
        // feeds imfid's RETURN_DEBIT recomputation — in the refund's own
        // transaction, so the collaborator belongs to the fixture.
        s.fidEventOutboxService = mock(com.intermarche.pos.service.sync.register.FidEventOutboxService.class);
        // The conditional-printing rule (LC-08-03): left un-stubbed, it forces
        // nothing, which is the behaviour every case here was written against.
        s.printPolicy = mock(com.intermarche.pos.ui.hardware.PrintPolicy.class);
        // LC-05-02-13: the recalculation of the kept basket runs inside the
        // refund's transaction. Left un-stubbed it answers null, which the cases
        // below would read as a missing collaborator, so the fixture makes it say
        // what an unadministered engine says: nothing to recompute.
        s.returnValuationService = mock(ReturnValuationService.class);
        when(s.returnValuationService.recompute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ReturnValuationService.Recalculation
                        .none(ReturnValuationService.RecalculationStatus.NOT_APPLICABLE));
        // LC-05-07-02: the loyalty half answers nothing taken back; LC-05-03-01:
        // no payment hub is administered — every pre-existing case.
        s.returnLoyaltyService = mock(ReturnLoyaltyService.class);
        s.nexoHubClient = mock(com.intermarche.pos.ui.payment.NexoHubClient.class);
        return s;
    }

    /**
     * Builds a real ticket line with the given identity and financials.
     *
     * @param id the line database id
     * @param qty the sold quantity
     * @param unitPrice the tax-included unit price
     * @param vat the VAT rate
     * @param label the product label
     * @return the ticket line
     */
    private TicketLine line(long id, String qty, String unitPrice, String vat, String label) {
        TicketLine l = new TicketLine();
        l.id = id;
        l.quantity = new BigDecimal(qty);
        l.unitPrice = new BigDecimal(unitPrice);
        l.vatRate = new BigDecimal(vat);
        l.productLabel = label;
        return l;
    }

    /**
     * Builds a real ticket carrying the given lines.
     *
     * @param id the ticket database id
     * @param number the ticket number
     * @param totalIncl the tax-included ticket total
     * @param lines the ticket lines
     * @return the ticket
     */
    private Ticket ticket(long id, String number, String totalIncl, TicketLine... lines) {
        Ticket t = new Ticket();
        t.id = id;
        t.ticketNumber = number;
        t.totalIncludingTax = new BigDecimal(totalIncl);
        t.lines = new ArrayList<>(List.of(lines));
        return t;
    }

    /**
     * Builds a real refund line carrying only a quantity (double-refund stub).
     *
     * @param qty the already-refunded quantity
     * @return the refund line
     */
    private RefundLine refundLineQty(String qty) {
        RefundLine rl = new RefundLine();
        rl.quantity = new BigDecimal(qty);
        return rl;
    }

    // --- searchTickets ---

    /**
     * {@code searchTickets} delegates to the shared engine (EXP-47) and stores
     * whatever it returns — here the empty list the engine gives for a null pattern.
     */
    @Test
    void searchTicketsClearsOnNullPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = null;
        state.refund.foundTickets = new ArrayList<>(List.of(mock(Ticket.class)));
        when(s.ticketSearchService.searchByPattern(null)).thenReturn(List.of());
        s.searchTickets(state);
        assertTrue(state.refund.foundTickets.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * {@code searchTickets} delegates a short pattern to the engine, which returns
     * an empty list (the engine owns the minimum-length guard now).
     */
    @Test
    void searchTicketsClearsOnShortPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = "  ab  ";
        state.refund.foundTickets = new ArrayList<>(List.of(mock(Ticket.class)));
        when(s.ticketSearchService.searchByPattern("  ab  ")).thenReturn(List.of());
        s.searchTickets(state);
        assertTrue(state.refund.foundTickets.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * {@code searchTickets} stores the engine's results for a valid pattern
     * (EXP-47: number, date or amount, resolved by the shared engine).
     */
    @Test
    void searchTicketsStoresResultsOnValidPattern() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = "abc";
        List<Ticket> results = List.of(mock(Ticket.class), mock(Ticket.class));
        when(s.ticketSearchService.searchByPattern("abc")).thenReturn(results);
        s.searchTickets(state);
        assertSame(results, state.refund.foundTickets);
        assertEquals(1L, state.version);
    }

    // --- selectTicket ---

    /**
     * {@code selectTicket} selects the found ticket and resets the refund
     * selection (non-null arm of the lookup guard).
     */
    @Test
    void selectTicketSelectsAndResets() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.returnQuantities.put(9L, BigDecimal.ONE);
        state.refund.detailPage = 3;
        state.refund.selectedLineId = 9L;
        state.refund.isEditingAmount = true;
        state.refund.manualTotalAmount = BigDecimal.TEN;
        state.refund.returnReason = "Erreur de caisse";
        Ticket found = ticket(10L, "T-1", "50.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(0, state.refund.detailPage);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertNull(state.refund.manualTotalAmount);
        assertNull(state.refund.returnReason);
        assertEquals(1L, state.version);
    }

    /**
     * Builds a store carrying a code, for the point-of-sale check of a recalled
     * ticket (LC-05-02-07).
     *
     * @param code the store code, or null
     * @return the store
     */
    private com.intermarche.pos.domain.store.Store store(String code) {
        com.intermarche.pos.domain.store.Store store =
                new com.intermarche.pos.domain.store.Store();
        store.code = code;
        return store;
    }

    /**
     * Mocks {@code Store.findAll().firstResult()} to the register's own store.
     *
     * @param panache the active static mock
     * @param own the register's store, or null when the referential is empty
     */
    private void registerStore(MockedStatic<PanacheEntityBase> panache,
                               com.intermarche.pos.domain.store.Store own) {
        @SuppressWarnings("unchecked")
        PanacheQuery<com.intermarche.pos.domain.store.Store> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(own);
        panache.when(com.intermarche.pos.domain.store.Store::findAll).thenReturn(query);
    }

    /**
     * A ticket issued by ANOTHER point of sale is refused when the back office
     * does not accept one (LC-05-02-07): the refusal is said out loud and the
     * selection stays empty, instead of the ticket simply never resolving.
     */
    @Test
    void selectTicketRefusesAForeignStoreTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        when(s.posSettingsService.returnForeignStoreAccepted()).thenReturn(false);
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store("0002");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            registerStore(panache, store("0001"));
            s.selectTicket(state, 10L);
        }
        assertNull(state.refund.selectedTicket);
        assertEquals("TICKET D'UN AUTRE MAGASIN", state.refund.errorMessage);
    }

    /**
     * A store that accepts foreign tickets selects it like any other
     * (LC-05-02-07, parameter on).
     */
    @Test
    void selectTicketAcceptsAForeignStoreTicketWhenAdministered() {
        RefundService s = newService();
        PosState state = new PosState();
        when(s.posSettingsService.returnForeignStoreAccepted()).thenReturn(true);
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store("0002");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            registerStore(panache, store("0001"));
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
        assertNull(state.refund.errorMessage);
    }

    /**
     * A ticket of THIS point of sale is not foreign (the codes match).
     */
    @Test
    void selectTicketAcceptsATicketOfItsOwnStore() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store("0001");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            registerStore(panache, store("0001"));
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
    }

    /**
     * A ticket whose store carries no code is not foreign: the comparison has
     * nothing to stand on (second leg of the ticket-side guard).
     */
    @Test
    void selectTicketAcceptsATicketWhoseStoreHasNoCode() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
    }

    /**
     * A register whose own store is unknown refuses nothing: a referential that
     * was not loaded must not cost the customer their return (first leg of the
     * register-side guard).
     */
    @Test
    void selectTicketAcceptsWhenTheRegisterStoreIsUnknown() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store("0002");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            registerStore(panache, null);
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
    }

    /**
     * A register whose own store carries no code refuses nothing either (second
     * leg of the register-side guard).
     */
    @Test
    void selectTicketAcceptsWhenTheRegisterStoreHasNoCode() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket found = ticket(10L, "T-1", "50.00");
        found.store = store("0002");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(found);
            registerStore(panache, store(null));
            s.selectTicket(state, 10L);
        }
        assertSame(found, state.refund.selectedTicket);
    }

    /**
     * A lookup that resolves to NOTHING never reaches the store check: there is
     * no ticket to place (the null-ticket leg of the guard).
     */
    @Test
    void selectTicketIgnoresAnUnknownTicketWithoutCheckingTheStore() {
        RefundService s = newService();
        PosState state = new PosState();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(99L)).thenReturn(null);
            s.selectTicket(state, 99L);
        }
        assertNull(state.refund.selectedTicket);
        assertNull(state.refund.errorMessage);
        verify(s.posSettingsService, never()).returnForeignStoreAccepted();
    }

    // --- selectReason (LC-05-05-01) ---

    /**
     * {@code selectReason} keeps a reason the referential names, and records
     * its CODE beside the label (BO-06-06-05).
     */
    @Test
    void selectReasonKeepsAdministeredReason() {
        RefundService s = newService();
        PosState state = new PosState();
        when(s.posListService.returnReason("Article périmé"))
                .thenReturn(new com.intermarche.pos.service.PosListService.ListChoice("PERIME", "Article périmé"));
        s.selectReason(state, "Article périmé");
        assertEquals("Article périmé", state.refund.returnReason);
        assertEquals("PERIME", state.refund.returnReasonCode);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectReason} clears an off-list reason (the referential names
     * nothing under that label), so the screen cannot smuggle a reason the
     * store does not administer — nor leave a code behind.
     */
    @Test
    void selectReasonClearsOffListReason() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.returnReason = "Article périmé";
        state.refund.returnReasonCode = "PERIME";
        s.selectReason(state, "Motif inventé");
        assertNull(state.refund.returnReason);
        assertNull(state.refund.returnReasonCode);
    }

    /**
     * {@code selectReason} clears the reason and its code on a blank input:
     * the resolver answers nothing for a blank label.
     */
    @Test
    void selectReasonClearsOnBlank() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.returnReason = "Erreur de caisse";
        s.selectReason(state, "   ");
        assertNull(state.refund.returnReason);
        assertNull(state.refund.returnReasonCode);
    }

    /**
     * {@code selectReason} clears the reason and its code on a null input:
     * the resolver answers nothing for a null label.
     */
    @Test
    void selectReasonClearsOnNull() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.returnReason = "Erreur de caisse";
        s.selectReason(state, null);
        assertNull(state.refund.returnReason);
        assertNull(state.refund.returnReasonCode);
    }

    /**
     * {@code selectTicket} leaves the state untouched but still refreshes the UI
     * when the ticket is not found (null arm of the lookup guard).
     */
    @Test
    void selectTicketDoesNothingWhenNotFound() {
        RefundService s = newService();
        PosState state = new PosState();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(99L)).thenReturn(null);
            s.selectTicket(state, 99L);
        }
        assertNull(state.refund.selectedTicket);
        assertEquals(1L, state.version);
    }

    // --- selectLine ---

    /**
     * {@code selectLine} deselects the line when the given id is already the
     * selected one (both arms of the AND true).
     */
    @Test
    void selectLineTogglesOffWhenAlreadySelected() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        state.refund.isEditingAmount = true;
        s.selectLine(state, 5L);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectLine} selects the line when a different id is given (first
     * arm true, second arm false of the AND).
     */
    @Test
    void selectLineSelectsWhenDifferent() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.selectLine(state, 6L);
        assertEquals(6L, state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code selectLine} sets a null selection when the id is null (first arm of
     * the AND false — short-circuit).
     */
    @Test
    void selectLineSetsNullWhenIdNull() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.selectLine(state, null);
        assertNull(state.refund.selectedLineId);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- startAmountEdit ---

    /**
     * {@code startAmountEdit} switches to global-amount edition and clears the
     * line selection.
     */
    @Test
    void startAmountEditSwitchesToAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 5L;
        s.startAmountEdit(state);
        assertNull(state.refund.selectedLineId);
        assertTrue(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- submitLineQuantity ---

    /**
     * {@code submitLineQuantity} parses the comma-decimal value and applies it
     * as a refund quantity (try arm), then clears the line selection.
     */
    @Test
    void submitLineQuantityAppliesParsedValue() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.selectedLineId = 1L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.submitLineQuantity(state, 1L, "2,5");
        }
        assertEquals(new BigDecimal("2.5"), state.refund.returnQuantities.get(1L));
        assertNull(state.refund.selectedLineId);
    }

    /**
     * {@code submitLineQuantity} swallows a parse failure (catch arm) and only
     * clears the line selection.
     */
    @Test
    void submitLineQuantityIgnoresInvalidValue() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedLineId = 1L;
        s.submitLineQuantity(state, 1L, "not-a-number");
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertNull(state.refund.selectedLineId);
        assertEquals(1L, state.version);
    }

    // --- submitManualAmount ---

    /**
     * {@code submitManualAmount} parses the comma-decimal amount (try arm) and
     * leaves amount edition.
     */
    @Test
    void submitManualAmountStoresParsedAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.isEditingAmount = true;
        s.submitManualAmount(state, "12,50");
        assertEquals(new BigDecimal("12.50"), state.refund.manualTotalAmount);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code submitManualAmount} falls back to zero on a parse failure (catch
     * arm) and leaves amount edition.
     */
    @Test
    void submitManualAmountFallsBackToZero() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.isEditingAmount = true;
        s.submitManualAmount(state, "xyz");
        assertEquals(BigDecimal.ZERO, state.refund.manualTotalAmount);
        assertFalse(state.refund.isEditingAmount);
        assertEquals(1L, state.version);
    }

    // --- enterArticleByCode ---

    /**
     * Builds a ticket line carrying an article code, so the code-driven entry has
     * something to match (LC-05-02-11).
     *
     * @param id the line database id
     * @param qty the sold quantity
     * @param ean the article barcode, or null
     * @param plu the produce number, or null
     * @return the line
     */
    private TicketLine coded(long id, String qty, String ean, String plu) {
        TicketLine l = line(id, qty, "10.00", "0.20", "MILK");
        l.ean = ean;
        l.plu = plu;
        return l;
    }

    /**
     * Stubs the gift-card lookup of a code to "not an instrument", the lookup
     * every staging of a line with an EAN goes through.
     *
     * @param panache the active static mock
     * @param ean the article barcode
     */
    private void noGiftCard(MockedStatic<PanacheEntityBase> panache, String ean) {
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(null);
        panache.when(() -> Product.find("ean", ean)).thenReturn(query);
    }

    /**
     * Without a selected ticket there is nothing to match a code against, and
     * nothing is said (first leg of the entry guard).
     */
    @Test
    void enterArticleByCodeDoesNothingWithoutATicket() {
        RefundService s = newService();
        PosState state = new PosState();
        s.enterArticleByCode(state, "123");
        assertNull(state.refund.errorMessage);
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * A selected ticket without its lines is no better (second leg of the entry
     * guard).
     */
    @Test
    void enterArticleByCodeDoesNothingWithoutLines() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "50.00");
        original.lines = null;
        state.refund.selectedTicket = original;
        s.enterArticleByCode(state, "123");
        assertNull(state.refund.errorMessage);
    }

    /**
     * A null code is refused as an empty entry (first leg of the blank guard).
     */
    @Test
    void enterArticleByCodeRefusesANullCode() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        s.enterArticleByCode(state, null);
        assertEquals("AUCUN CODE SAISI", state.refund.errorMessage);
    }

    /**
     * A blank code is refused too (second leg of the blank guard: trimmed empty).
     */
    @Test
    void enterArticleByCodeRefusesABlankCode() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        s.enterArticleByCode(state, "   ");
        assertEquals("AUCUN CODE SAISI", state.refund.errorMessage);
    }

    /**
     * A code the recalled ticket does not carry is refused: the control of the
     * origin ticket is exactly what this entry keeps (LC-05-02-11).
     */
    @Test
    void enterArticleByCodeRefusesACodeAbsentFromTheTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        s.enterArticleByCode(state, "999");
        assertEquals("ARTICLE ABSENT DU TICKET D'ORIGINE", state.refund.errorMessage);
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * A code matching a CANCELLED line only is as absent as any other: a
     * cancelled article was never sold (the cancelled leg of the line filter).
     */
    @Test
    void enterArticleByCodeIgnoresACancelledLine() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine cancelled = coded(1L, "2", "123", null);
        cancelled.cancelled = true;
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", cancelled);
        s.enterArticleByCode(state, "123");
        assertEquals("ARTICLE ABSENT DU TICKET D'ORIGINE", state.refund.errorMessage);
    }

    /**
     * A matching EAN takes one unit back, selects the line and carries the detail
     * page onto it, so the cashier sees what they scanned (LC-05-02-11).
     */
    @Test
    void enterArticleByCodeTakesOneUnitBackOnTheMatchingEan() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.detailPage = 3;
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            noGiftCard(panache, "123");
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.enterArticleByCode(state, "123");
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
        assertEquals(1L, state.refund.selectedLineId);
        assertEquals(0, state.refund.detailPage);
    }

    /**
     * A produce number the cashier types matches the line's PLU (second leg of
     * the code match), and a line without an EAN skips the instrument lookup.
     */
    @Test
    void enterArticleByCodeTakesOneUnitBackOnTheMatchingPlu() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", null, "4011"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.enterArticleByCode(state, "4011");
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
    }

    /**
     * A code whose lines are all exhausted is refused as already returned, never
     * silently ignored: the cashier must know why nothing moved (LC-05-02-11).
     */
    @Test
    void enterArticleByCodeSaysWhenTheArticleIsAlreadyFullyReturned() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L))
                    .thenReturn(List.of(refundLineQty("2")));
            s.enterArticleByCode(state, "123");
        }
        assertEquals("ARTICLE DÉJÀ ENTIÈREMENT RETOURNÉ", state.refund.errorMessage);
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * An article staged up to its sold quantity is exhausted too, past refunds or
     * not: the staged quantity is part of the same cap.
     */
    @Test
    void enterArticleByCodeSaysWhenTheStagedQuantityIsAlreadyTheWholeLine() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.enterArticleByCode(state, "123");
        }
        assertEquals("ARTICLE DÉJÀ ENTIÈREMENT RETOURNÉ", state.refund.errorMessage);
        assertEquals(0, new BigDecimal("2").compareTo(state.refund.returnQuantities.get(1L)));
    }

    /**
     * An article sold on SEVERAL lines is served in order: the first line being
     * exhausted, the unit falls on the second (the continue arm of the search).
     */
    @Test
    void enterArticleByCodeFallsToTheNextLineCarryingTheSameArticle() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00",
                coded(1L, "1", "123", null), coded(2L, "1", "123", null));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            noGiftCard(panache, "123");
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> RefundLine.list("originalLineId", 2L)).thenReturn(List.of());
            s.enterArticleByCode(state, "123");
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(2L)));
        assertEquals(2L, state.refund.selectedLineId);
    }

    /**
     * A code surrounded by spaces is the same code: the scanner and the pad both
     * hand over what the cashier gave, and neither is asked to be tidy.
     */
    @Test
    void enterArticleByCodeTrimsTheCode() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", coded(1L, "2", "123", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            noGiftCard(panache, "123");
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.enterArticleByCode(state, "  123  ");
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
    }

    // --- setReturnQuantity ---

    /**
     * {@code setReturnQuantity} returns immediately when no ticket is selected
     * (null-ticket guard true arm).
     */
    @Test
    void setReturnQuantityReturnsWithoutTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(0L, state.version);
    }

    /**
     * {@code setReturnQuantity} stores nothing when the line id matches no line
     * (line-not-found arm).
     */
    @Test
    void setReturnQuantityIgnoresUnknownLine() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 99L)).thenReturn(List.of());
            s.setReturnQuantity(state, 99L, BigDecimal.ONE);
        }
        assertTrue(state.refund.returnQuantities.isEmpty());
        assertEquals(1L, state.version);
    }

    /**
     * A CANCELLED article (lot C4, BO-04-01-16) is never returnable, even
     * against a hand-posted line id: the {@code !l.cancelled} filter excludes
     * it, so the line resolves to null and nothing is staged.
     */
    @Test
    void setReturnQuantityRefusesACancelledLine() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine cancelled = line(1L, "5", "10.00", "0.20", "MILK");
        cancelled.cancelled = true;
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", cancelled);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * A line WITHOUT an EAN skips the gift-card lookup entirely (first guard
     * false arm): a weighed PLU line can never be an instrument.
     */
    @Test
    void setReturnQuantityWithoutEanSkipsTheGiftCardLookup() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "5", "10.00", "0.20", "MILK");
        milk.ean = null;
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", milk);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
    }

    /**
     * An EAN whose product is UNKNOWN to the catalog is returnable (second
     * guard, {@code product == null} leg): a delisted article must not become
     * un-refundable.
     */
    @Test
    void setReturnQuantityWithUnknownProductStaysReturnable() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "5", "10.00", "0.20", "MILK");
        milk.ean = "123";
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", milk);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean", "123")).thenReturn(query);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
    }

    /**
     * An ORDINARY product is returnable (second guard, {@code giftCardAmount
     * == null} leg).
     */
    @Test
    void setReturnQuantityWithOrdinaryProductStaysReturnable() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "5", "10.00", "0.20", "MILK");
        milk.ean = "123";
        Product p = new Product();
        p.giftCardAmount = null;
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", milk);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean", "123")).thenReturn(query);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertNull(state.refund.errorMessage);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
    }

    /**
     * A GIFT CARD line is REFUSED and no quantity is staged: refunding a sold
     * card while its registry instrument stays ACTIVE would double the value.
     */
    @Test
    void setReturnQuantityRefusesGiftCardLine() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine card = line(1L, "1", "25.00", "0.00", "CARTE CADEAU 25");
        card.ean = "3400025000001";
        Product p = new Product();
        p.giftCardAmount = new BigDecimal("25.00");
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", card);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean", "3400025000001")).thenReturn(query);
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertEquals("RETOUR INTERDIT SUR CARTE CADEAU", state.refund.errorMessage);
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * {@code setReturnQuantity} stores a positive quantity within the refundable
     * cap unchanged (refundable non-negative, quantity non-negative, quantity
     * not above the cap — all three false arms).
     */
    @Test
    void setReturnQuantityStoresWithinCap() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.manualTotalAmount = BigDecimal.TEN;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("3"));
        }
        assertEquals(new BigDecimal("3"), state.refund.returnQuantities.get(1L));
        assertNull(state.refund.manualTotalAmount);
        assertEquals(1L, state.version);
    }

    /**
     * {@code setReturnQuantity} caps a quantity above the refundable amount
     * (quantity-above-cap true arm).
     */
    @Test
    void setReturnQuantityCapsAboveRefundable() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("10"));
        }
        assertEquals(new BigDecimal("5"), state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code setReturnQuantity} clamps a negative quantity to zero
     * (negative-quantity true arm).
     */
    @Test
    void setReturnQuantityClampsNegativeToZero() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, new BigDecimal("-2"));
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code setReturnQuantity} floors an over-refunded line's refundable amount
     * to zero (refundable-negative true arm): with more already refunded than
     * sold, any positive request is capped to zero.
     */
    @Test
    void setReturnQuantityFloorsNegativeRefundable() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "2", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(refundLineQty("3")));
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    // --- incrementQty / decrementQty ---

    /**
     * {@code incrementQty} adds one unit to the current quantity of a line.
     */
    @Test
    void incrementQtyAddsOne() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.incrementQty(state, 1L);
        }
        assertEquals(new BigDecimal("3"), state.refund.returnQuantities.get(1L));
    }

    /**
     * {@code decrementQty} removes one unit from the current quantity of a line,
     * clamped to zero.
     */
    @Test
    void decrementQtyRemovesOne() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.decrementQty(state, 1L);
        }
        assertEquals(BigDecimal.ZERO, state.refund.returnQuantities.get(1L));
    }

    // --- requestRefund ---

    /**
     * {@code requestRefund} refuses the refund in training mode (training guard
     * true arm) without contacting the endorsement service.
     */
    @Test
    void requestRefundBlockedInTraining() {
        RefundService s = newService();
        PosState state = new PosState();
        state.trainingMode = true;
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertEquals("RETOURS INDISPONIBLES EN FORMATION", state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(1L, state.version);
    }

    /**
     * {@code requestRefund} returns silently when no ticket is selected
     * (training guard false arm, ticket guard true arm).
     */
    @Test
    void requestRefundReturnsWithoutTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertNull(state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(0L, state.version);
    }

    /**
     * {@code requestRefund} refuses an empty refund (amount guard true arm)
     * without contacting the endorsement service.
     */
    @Test
    void requestRefundRefusesEmptyAmount() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        s.requestRefund(state, Refund.RefundMethod.CASH);
        assertEquals("RIEN À REMBOURSER", state.refund.errorMessage);
        verifyNoInteractions(s.endorsementService);
        assertEquals(1L, state.version);
    }

    /**
     * {@code requestRefund} requests a manager endorsement carrying the method
     * and ticket id when the refund is non-empty (all guards false arm).
     */
    @Test
    void requestRefundRequestsEndorsement() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            s.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertNull(state.refund.errorMessage);
        verify(s.endorsementService).requestAuthorization(state, "REFUND_CASH_10");
        assertEquals(1L, state.version);
    }

    /**
     * Stubs the TENDER referential for one settlement key (BO-03-02-15).
     *
     * @param panache the active Panache static mock
     * @param code the settlement key
     * @param tender the administered row, or null when none administers it
     */
    @SuppressWarnings("unchecked")
    private void stubTender(MockedStatic<PanacheEntityBase> panache, String code,
            com.intermarche.pos.domain.payment.TenderDefinition tender) {
        PanacheQuery<com.intermarche.pos.domain.payment.TenderDefinition> query =
                mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(tender);
        panache.when(() -> com.intermarche.pos.domain.payment.TenderDefinition
                .find("code", code)).thenReturn(query);
    }

    /**
     * Builds an administered tender for a settlement key.
     *
     * @param code the settlement key
     * @param refundAllowed whether the back office allows refunds on it
     * @return the administered row
     */
    private com.intermarche.pos.domain.payment.TenderDefinition tender(String code,
            boolean refundAllowed) {
        com.intermarche.pos.domain.payment.TenderDefinition tender =
                new com.intermarche.pos.domain.payment.TenderDefinition();
        tender.code = code;
        tender.functionalId = "010";
        tender.label = code;
        tender.active = true;
        tender.refundAllowed = refundAllowed;
        return tender;
    }

    /**
     * A tender the back office does NOT allow for refunds is refused before the
     * endorsement is even asked for (BO-03-02-15).
     */
    @Test
    void requestRefundRefusesAForbiddenTender() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", tender("CASH", false));
            s.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertEquals("MOYEN NON AUTORISE AU REMBOURSEMENT", state.refund.errorMessage);
        verify(s.endorsementService, never()).requestAuthorization(any(), any());
    }

    /**
     * {@code isReturnableTicket} answers false for a null code without a lookup
     * (EXP-37).
     */
    @Test
    void isReturnableTicketFalseForNullCode() {
        org.junit.jupiter.api.Assertions.assertFalse(newService().isReturnableTicket(null));
    }

    /**
     * {@code isReturnableTicket} is true when a CLOSED ticket carries the number,
     * false when none does (EXP-37) — both arms.
     */
    @Test
    void isReturnableTicketMatchesClosedTicket() {
        RefundService s = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> q = mock(PanacheQuery.class);
            when(q.firstResult()).thenReturn(ticket(10L, "C04-00000042", "10.00"));
            panache.when(() -> Ticket.find("ticketNumber = ?1 and status = ?2",
                    "C04-00000042", Ticket.TicketStatus.CLOSED)).thenReturn(q);
            org.junit.jupiter.api.Assertions.assertTrue(s.isReturnableTicket("C04-00000042"));
        }
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Ticket> q = mock(PanacheQuery.class);
            when(q.firstResult()).thenReturn(null);
            panache.when(() -> Ticket.find("ticketNumber = ?1 and status = ?2",
                    "C04-99999999", Ticket.TicketStatus.CLOSED)).thenReturn(q);
            org.junit.jupiter.api.Assertions.assertFalse(s.isReturnableTicket("C04-99999999"));
        }
    }

    /**
     * {@code selectTicket} marks the lines already fully returned by past refunds
     * (EXP-37) and leaves the others selectable — both arms.
     */
    @Test
    void selectTicketMarksFullyReturnedLines() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket t = ticket(10L, "T-1", "30.00",
                line(1L, "2", "10.00", "0.20", "MILK"),
                line(2L, "1", "10.00", "0.20", "EGG"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(t);
            panache.when(() -> RefundLine.list("originalLineId", 1L))
                    .thenReturn(List.of(refundLineQty("2")));
            panache.when(() -> RefundLine.list("originalLineId", 2L)).thenReturn(List.of());
            s.selectTicket(state, 10L);
        }
        org.junit.jupiter.api.Assertions.assertTrue(state.refund.isFullyReturned(1L));
        org.junit.jupiter.api.Assertions.assertFalse(state.refund.isFullyReturned(2L));
    }

    /**
     * {@code requestRefund} performs the refund DIRECTLY when the gesture is
     * authorized without a modal (EXP-32): a Refund is written and no endorsement
     * is parked.
     */
    @Test
    void requestRefundDirectWhenAuthorized() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(s.endorsementService.gestureAuthorized(state, "refund")).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            stubTender(panache, "CASH", tender("CASH", true));
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.requestRefund(state, Refund.RefundMethod.CASH);
            org.junit.jupiter.api.Assertions.assertFalse(mc.constructed().isEmpty());
        }
        verify(s.endorsementService, never()).requestAuthorization(any(), any());
    }

    /**
     * A tender the back office DOES allow goes through — the other arm, and the
     * proof that the flag is read rather than the refusal hard-coded.
     */
    @Test
    void requestRefundAcceptsAnAllowedTender() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", tender("CASH", true));
            s.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertNull(state.refund.errorMessage);
        verify(s.endorsementService).requestAuthorization(state, "REFUND_CASH_10");
    }

    /**
     * Each refund method asks about ITS OWN tender: the card about the card, the
     * voucher about the voucher, the loyalty credit about the purse — and a null
     * method asks about nothing at all.
     */
    @Test
    void eachRefundMethodAsksAboutItsOwnTender() {
        RefundService s = newService();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CARD", tender("CARD", false));
            stubTender(panache, "VOUCHER", tender("VOUCHER", true));
            stubTender(panache, "FIDELITY", tender("FIDELITY", false));
            assertFalse(s.isRefundAllowed(Refund.RefundMethod.CARD));
            assertTrue(s.isRefundAllowed(Refund.RefundMethod.VOUCHER));
            assertFalse(s.isRefundAllowed(Refund.RefundMethod.LOYALTY));
            assertTrue(s.isRefundAllowed(null));
        }
    }

    // --- cancelPaidTicket (EXP-49) ---

    /**
     * {@code cancelPaidTicket} does nothing when no ticket is recalled (null
     * guard true arm): no reversal, no journal.
     */
    @Test
    void cancelPaidTicketDoesNothingWithoutSelectedTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = null;
        s.cancelPaidTicket(state, Refund.RefundMethod.CASH, "M1");
        verify(s.technicalEventService, never())
                .log(eq(TechnicalEvent.EventType.PAID_TICKET_CANCELLED), any(), any());
        assertTrue(state.refund.returnQuantities.isEmpty());
    }

    /**
     * {@code cancelPaidTicket} reverses every NON-cancelled line of the recalled
     * paid ticket (the {@code cancelled} skip arm and the {@code refundable > 0}
     * arm), produces the reversal document through the refund machinery and
     * journals {@code PAID_TICKET_CANCELLED} naming the original ticket and the
     * endorsing supervisor.
     */
    @Test
    void cancelPaidTicketReversesNonCancelledLinesAndJournals() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine kept = line(1L, "3", "10.00", "0.20", "MILK");
        TicketLine dropped = line(2L, "1", "5.00", "0.20", "BREAD");
        dropped.cancelled = true;
        state.refund.selectedTicket = ticket(10L, "T-9", "35.00", kept, dropped);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            stubTender(panache, "CASH", tender("CASH", true));
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> RefundLine.list("originalLineId", 2L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.cancelPaidTicket(state, Refund.RefundMethod.CASH, "M1");
            assertFalse(mc.constructed().isEmpty());
        }
        verify(s.technicalEventService).log(
                eq(TechnicalEvent.EventType.PAID_TICKET_CANCELLED), contains("T-9"), eq("M1"));
    }

    // --- performRefund ---

    /**
     * {@code performRefund} returns immediately when no ticket is selected
     * (null-original guard true arm): nothing is persisted or printed.
     */
    @Test
    void performRefundReturnsWithoutOriginal() {
        RefundService s = newService();
        PosState state = new PosState();
        try (MockedConstruction<Refund> mc = mockConstruction(Refund.class)) {
            s.performRefund(state, Refund.RefundMethod.CASH);
            assertTrue(mc.constructed().isEmpty());
        }
        verifyNoInteractions(s.ticketNumberService, s.cashSessionService, s.technicalEventService,
                s.syncOutboxService, s.ticketPrinterService, s.hardwareService);
    }

    /**
     * A cancelled article (lot C4, BO-04-01-16) never backs a refund line, even
     * if its id reaches {@code performRefund} through a hand-posted quantity:
     * the registration filter's {@code !l.cancelled} arm drops it, so the
     * refund carries no line for it.
     */
    @Test
    void performRefundSkipsACancelledOriginalLine() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine cancelled = line(1L, "3", "10.00", "0.20", "MILK");
        cancelled.cancelled = true;
        Ticket original = ticket(10L, "T-1", "100.00", cancelled);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            assertTrue(mc.constructed().get(0).lines.isEmpty());
        }
    }

    /**
     * A recomputed recalculation is carried onto the refund (LC-05-02-13): the
     * status, the advantage withdrawn and the surviving advantages joined in one
     * column, plus the note that the loyalty programme was asked to recompute
     * (the origin ticket carries a card).
     */
    @Test
    void performRefundCarriesTheRecomputedRecalculation() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        original.fidelityCard = "CARD-9";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.returnValuationService.recompute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReturnValuationService.Recalculation(
                        ReturnValuationService.RecalculationStatus.RECOMPUTED,
                        new BigDecimal("2.50"), List.of("REMISE PANIER", "OFFRE-7")));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("RECOMPUTED", refund.recalculationStatus);
            assertEquals(new BigDecimal("2.50"), refund.withdrawnGenerosity);
            assertEquals("REMISE PANIER | OFFRE-7", refund.keptAdvantages);
            assertTrue(refund.loyaltyRecalculationSent);
        }
    }

    /**
     * A recomputed recalculation that found no surviving advantage leaves the
     * column null rather than an empty string (the join is skipped), and a ticket
     * without a card is not announced to the loyalty programme.
     */
    @Test
    void performRefundLeavesNoKeptAdvantageWhenNoneSurvives() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.returnValuationService.recompute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReturnValuationService.Recalculation(
                        ReturnValuationService.RecalculationStatus.RECOMPUTED,
                        new BigDecimal("1.00"), List.of()));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("RECOMPUTED", refund.recalculationStatus);
            assertNull(refund.keptAdvantages);
            assertFalse(refund.loyaltyRecalculationSent);
        }
    }

    /**
     * An engine that could not answer leaves UNAVAILABLE on the refund and no
     * amount at all — and the refund goes through: the customer is at the till
     * (LC-05-02-13, degraded arm).
     */
    @Test
    void performRefundGoesThroughWhenTheEngineCannotAnswer() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.returnValuationService.recompute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ReturnValuationService.Recalculation
                        .none(ReturnValuationService.RecalculationStatus.UNAVAILABLE));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("UNAVAILABLE", refund.recalculationStatus);
            assertNull(refund.withdrawnGenerosity);
            assertNull(refund.keptAdvantages);
            verify(refund).persist();
        }
        verify(s.ticketPrinterService).printRefund(55L);
        // LC-05-07-03: the return is stated on its credit note before its paper.
        verify(s.portugueseFiscalService).issueCreditNoteForRefund(55L);
    }

    /**
     * The recalculation is asked about what the ticket KEEPS, past refunds and
     * this one deducted (LC-05-02-13): three sold, one already returned, one
     * returned now leaves one.
     */
    @Test
    void performRefundAsksTheRecalculationAboutWhatIsKept() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L))
                    .thenReturn(List.of(refundLineQty("1")));
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            org.mockito.ArgumentCaptor<java.util.Map<Long, BigDecimal>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(s.returnValuationService).recompute(
                    org.mockito.ArgumentMatchers.same(original), captor.capture());
            assertEquals(0, new BigDecimal("1").compareTo(captor.getValue().get(1L)));
        }
    }

    /**
     * A line that was CANCELLED during the sale is not named in the kept map at
     * all: it was never sold, so it is neither kept nor returned.
     */
    @Test
    void performRefundLeavesACancelledLineOutOfTheKeptMap() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine cancelled = line(2L, "1", "4.00", "0.20", "VOID");
        cancelled.cancelled = true;
        Ticket original = ticket(10L, "T-1", "100.00",
                line(1L, "3", "10.00", "0.20", "MILK"), cancelled);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            org.mockito.ArgumentCaptor<java.util.Map<Long, BigDecimal>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(s.returnValuationService).recompute(
                    org.mockito.ArgumentMatchers.same(original), captor.capture());
            assertFalse(captor.getValue().containsKey(2L));
            assertEquals(0, new BigDecimal("2").compareTo(captor.getValue().get(1L)));
        }
    }

    /**
     * A line that was over-returned by past refunds is kept at ZERO, never at a
     * negative quantity the engine would choke on.
     */
    @Test
    void performRefundClampsTheKeptQuantityAtZero() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00",
                line(1L, "1", "10.00", "0.20", "MILK"), line(2L, "1", "4.00", "0.20", "BREAD"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> RefundLine.list("originalLineId", 2L))
                    .thenReturn(List.of(refundLineQty("3")));
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            org.mockito.ArgumentCaptor<java.util.Map<Long, BigDecimal>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(s.returnValuationService).recompute(
                    org.mockito.ArgumentMatchers.same(original), captor.capture());
            assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().get(1L)));
            assertEquals(0, BigDecimal.ZERO.compareTo(captor.getValue().get(2L)));
        }
    }

    /**
     * BO-10-02-18: the refund's ventilation is cut by the administered rounding
     * rule — 8,19 at 20 % is 6,82 excluding tax rounded down where half up gives
     * 6,83 — as its printed return ticket is.
     */
    @Test
    void performRefundCutsTheVentilationWithTheAdministeredRounding() {
        RefundService s = newService();
        when(s.posSettingsService.calcRoundingMode()).thenReturn(java.math.RoundingMode.DOWN);
        PosState state = new PosState();
        TicketLine sold = line(1L, "1", "8.19", "0.20", "MILK");
        sold.totalPrice = new BigDecimal("8.19");
        Ticket original = ticket(10L, "T-1", "8.19", sold);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("8.19"), refund.totalAmount);
            assertEquals(new BigDecimal("6.82"), refund.totalExcludingTax);
            assertEquals(new BigDecimal("1.37"), refund.totalVat);
        }
    }

    /**
     * A line that carried an advantage at the sale — its total is below unit
     * price times quantity — is refunded at the price ACTUALLY PAID
     * (LC-05-02-12): the refund line carries that price, the total is its
     * product, and the restituted VAT follows the same amount. Refunding the
     * unit price would hand back 20,00 EUR on a line the customer paid 16,00 EUR
     * for.
     */
    @Test
    void performRefundHandsBackThePricePaidNotTheUnitPrice() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine discounted = line(1L, "3", "10.00", "0.20", "MILK");
        discounted.totalPrice = new BigDecimal("24.00");
        Ticket original = ticket(10L, "T-1", "24.00", discounted);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("16.00"), refund.totalAmount);
            assertEquals(new BigDecimal("13.33"), refund.totalExcludingTax);
            assertEquals(new BigDecimal("2.67"), refund.totalVat);
            assertEquals(1, refund.lines.size());
            assertEquals(new BigDecimal("8.0000"), refund.lines.get(0).price);
            assertEquals(new BigDecimal("2"), refund.lines.get(0).quantity);
        }
        verify(s.technicalEventService).log(TechnicalEvent.EventType.REFUND_CREATED, "T-1 CASH 16.00");
    }

    /**
     * {@code performRefund} for a cash refund persists the document with its
     * method, VAT restitution and session, enqueues the sync, opens the drawer
     * (CASH switch arm), prints the refund and clears the state. Exercises the
     * positive-quantity, line-found and within-cap arms, a non-empty
     * already-refunded history, and the HT/VAT branch (lines present, no manual
     * amount).
     */
    @Test
    void performRefundCashPersistsAndOpensDrawer() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(refundLineQty("1")));
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("R-1", refund.refundNumber);
            assertEquals(10L, refund.originalTicketId);
            assertEquals(Refund.RefundMethod.CASH, refund.refundMethod);
            assertEquals("C04", refund.terminalId);
            assertSame(session, refund.session);
            assertEquals(Refund.RefundStatus.CLOSED, refund.status);
            assertNotNull(refund.creationDate);
            assertEquals(new BigDecimal("20.00"), refund.totalAmount);
            assertEquals(new BigDecimal("16.67"), refund.totalExcludingTax);
            assertEquals(new BigDecimal("3.33"), refund.totalVat);
            assertEquals(1, refund.lines.size());
            RefundLine rl = refund.lines.get(0);
            assertEquals(1L, rl.originalLineId);
            assertEquals("MILK", rl.productLabel);
            assertEquals(new BigDecimal("2"), rl.quantity);
            assertEquals(new BigDecimal("10.00"), rl.price);
            assertEquals(new BigDecimal("0.20"), rl.vatRate);
            verify(refund).persist();
        }
        verify(s.technicalEventService).log(TechnicalEvent.EventType.REFUND_CREATED, "T-1 CASH 20.00");
        verify(s.syncOutboxService).enqueue(SyncOutbox.EntityType.REFUND, 55L);
        verify(s.hardwareService).openDrawer();
        verify(s.ticketPrinterService).printRefund(55L);
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), anyString(), any());
        assertNull(state.refund.selectedTicket);
    }

    /**
     * {@code performRefund} carries the chosen return reason onto the refund and
     * appends it to the REFUND_CREATED journal (LC-05-05-01): the reason-present
     * arm of the journal ternary, complementing the reason-null arm covered by
     * {@link #performRefundCashPersistsAndOpensDrawer()}.
     */
    @Test
    void performRefundCarriesReasonAndJournalsIt() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.returnReason = "Article périmé";
        CashSession session = mock(CashSession.class);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(session);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals("Article périmé", refund.returnReason);
        }
        verify(s.technicalEventService).log(TechnicalEvent.EventType.REFUND_CREATED,
                "T-1 CASH 20.00 MOTIF:Article périmé");
    }

    /**
     * {@code performRefund} for a voucher refund issues a CREDIT_NOTE in the
     * stored-value registry (ACTIVE, balance = refund total, linked to the
     * refund) and prints the voucher with the registry number derived from
     * the instrument's row id (VOUCHER switch arm — registry doctrine: the
     * number is a pure identifier).
     */
    @Test
    void performRefundVoucherIssuesRegistryNote() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             });
             MockedConstruction<StoredValue> mcNote = mockConstruction(StoredValue.class,
                     (mock, ctx) -> mock.id = 77L)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.VOUCHER);
            Refund refund = mc.constructed().get(0);
            StoredValue note = mcNote.constructed().get(0);
            assertEquals(StoredValue.Kind.CREDIT_NOTE, note.kind);
            assertEquals(new BigDecimal("20.00"), note.initialAmount);
            assertEquals(new BigDecimal("20.00"), note.balance);
            assertEquals(Long.valueOf(55L), note.issuingRefundId);
            assertEquals(refund.terminalId, note.issuingTerminalId);
            assertEquals(refund.refundNumber, note.issuingDocumentNumber);
            verify(s.storedValueNumberService).numberOf(note, refund.refundNumber);
            verify(s.syncOutboxService).enqueue(SyncOutbox.EntityType.STORED_VALUE, 77L);
            assertNotNull(note.issuedAt);
            // persistAndFlush, not persist: the number column is NOT NULL, so
            // the INSERT carries a provisional value and the definitive number
            // is derived from the generated id right after the flush.
            verify(note).persistAndFlush();
            // Number = prefix + the instrument's own row id, zero-padded.
            verify(s.ticketPrinterService).printRefundVoucher(eq(refund), eq("297000000000077"), any());
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a voucher refund driven by a manual amount
     * skips the VAT restitution (HT/VAT branch: lines present but a manual
     * amount is set) and still issues the registry note — the historical
     * 99,99 € encoded cap is gone, any amount gets a scannable number.
     */
    @Test
    void performRefundVoucherManualAmountSkipsVat() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "500.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.manualTotalAmount = new BigDecimal("150.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             });
             MockedConstruction<StoredValue> mcNote = mockConstruction(StoredValue.class,
                     (mock, ctx) -> mock.id = 78L)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.VOUCHER);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("150.00"), refund.totalAmount);
            assertNull(refund.totalExcludingTax);
            assertNull(refund.totalVat);
            StoredValue note = mcNote.constructed().get(0);
            assertEquals(new BigDecimal("150.00"), note.balance);
            verify(s.ticketPrinterService).printRefundVoucher(eq(refund), eq("297000000000078"), any());
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a loyalty refund hands the customer a printed
     * proof and announces the credit (LOYALTY switch arm): no drawer, no
     * voucher. The credit itself is born at imfid's ingestion of the
     * ticket-return event, never written by the register.
     */
    @Test
    void performRefundLoyaltyJournalsOnly() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        // The origin ticket MUST carry a card: crediting a loyalty balance
        // needs one, and its absence is a refusal (see the test below).
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.LOYALTY);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), anyString(), any());
        verify(s.ticketPrinterService).printLoyaltyCredit(any());
        verify(s.ticketPrinterService).printRefund(55L);
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN), anyString());
    }

    /**
     * The return event carries ONE entry per refunded line, keyed by the
     * ORIGIN line's {@code lineUid} — the identity the engine echoed in the
     * valuation couple, so imfid can recompute the RETURN_DEBIT against the
     * very lines it credited.
     */
    @Test
    void performRefundReturnEventCarriesOriginLineUids() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = "U-1";
        Ticket original = ticket(10L, "T-1", "100.00", milk);
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> TicketLine.findById(1L)).thenReturn(milk);
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN),
                payload.capture());
        assertTrue(payload.getValue().contains("\"lineId\":\"U-1\""));
        assertTrue(payload.getValue().contains("\"quantity\":2"));
    }

    /**
     * TWO refunded lines are SEPARATED by a comma — the separator flag's true
     * arm. A single-line return never exercises it, yet a missing comma would
     * produce malformed JSON that imfid rejects wholesale: the return would
     * silently never be credited back.
     */
    @Test
    void performRefundReturnEventSeparatesSeveralLines() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = "U-1";
        TicketLine bread = line(2L, "2", "5.00", "0.055", "BREAD");
        bread.lineUid = "U-2";
        Ticket original = ticket(10L, "T-1", "100.00", milk, bread);
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.returnQuantities.put(2L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> RefundLine.list("originalLineId", 2L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> TicketLine.findById(1L)).thenReturn(milk);
            panache.when(() -> TicketLine.findById(2L)).thenReturn(bread);
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN),
                payload.capture());
        String json = payload.getValue();
        assertTrue(json.contains("\"lineId\":\"U-1\""));
        assertTrue(json.contains("\"lineId\":\"U-2\""));
        assertTrue(json.contains("},{"), "les entrées doivent être séparées par une virgule: " + json);
    }

    /**
     * A refunded line whose ORIGIN carries no {@code lineUid} is SKIPPED
     * rather than sent with a null identity: imfid could not match it, and a
     * malformed entry would poison the whole event.
     */
    @Test
    void performRefundReturnEventSkipsLinesWithoutUid() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = null;
        Ticket original = ticket(10L, "T-1", "100.00", milk);
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> TicketLine.findById(1L)).thenReturn(milk);
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN),
                payload.capture());
        assertTrue(payload.getValue().contains("\"lines\":[]"));
    }

    /**
     * A CASH refund carries NO {@code refundToCard} block: the money left the
     * drawer, nothing is credited on the loyalty balance (ternary false arm).
     */
    @Test
    void performRefundCashEventCarriesNoRefundToCard() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = "U-1";
        Ticket original = ticket(10L, "T-1", "100.00", milk);
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
                 mock.totalAmount = new BigDecimal("20.00");
                 mock.refundMethod = Refund.RefundMethod.CASH;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> TicketLine.findById(1L)).thenReturn(milk);
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN),
                payload.capture());
        assertFalse(payload.getValue().contains("refundToCard"));
    }

    /**
     * A LOYALTY refund carries the {@code refundToCard} block naming the
     * ORIGIN ticket's card and the refunded amount (ternary true arm): the
     * REFUND_CREDIT is born at imfid's ingestion, never written here.
     */
    @Test
    void performRefundLoyaltyEventCarriesRefundToCard() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = "U-1";
        Ticket original = ticket(10L, "T-1", "100.00", milk);
        original.fidelityCard = "2990000000019";
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        org.mockito.ArgumentCaptor<String> payload =
                org.mockito.ArgumentCaptor.forClass(String.class);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
                 mock.totalAmount = new BigDecimal("20.00");
                 mock.refundMethod = Refund.RefundMethod.LOYALTY;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> TicketLine.findById(1L)).thenReturn(milk);
            s.performRefund(state, Refund.RefundMethod.LOYALTY);
        }
        verify(s.fidEventOutboxService).enqueue(
                eq(com.intermarche.pos.domain.sync.FidEvent.EventType.TICKET_RETURN),
                payload.capture());
        assertTrue(payload.getValue().contains("\"refundToCard\""));
        assertTrue(payload.getValue().contains("\"card\":\"2990000000019\""));
        assertTrue(payload.getValue().contains("\"amount\":20.00"));
        com.fasterxml.jackson.databind.JsonNode event = readJson(payload.getValue());
        assertEquals("2990000000019", event.get("refundToCard").get("card").asText());
        assertEquals(0, new BigDecimal("20.00").compareTo(event.get("refundToCard").get("amount").decimalValue()));
        assertTrue(event.get("lines").isArray());
    }

    /**
     * Reads a JSON text with a real parser, so a malformed document fails.
     *
     * @param text the JSON text
     * @return the parsed document
     */
    private static com.fasterxml.jackson.databind.JsonNode readJson(String text) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new AssertionError(malformed);
        }
    }

    /**
     * A ticket WITHOUT a fidelity card enqueues NO return event at all: there
     * is no balance to recompute.
     */
    @Test
    void performRefundWithoutCardEnqueuesNoReturnEvent() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine milk = line(1L, "3", "10.00", "0.20", "MILK");
        milk.lineUid = "U-1";
        Ticket original = ticket(10L, "T-1", "100.00", milk);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        verifyNoInteractions(s.fidEventOutboxService);
    }

    /**
     * A loyalty refund on a ticket WITHOUT a card is refused before anything
     * is written: no refund persisted, no printing — the cashier is told to
     * pick another method (imfid spec §28).
     */
    @Test
    void performRefundLoyaltyRefusedWithoutCardOnOrigin() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        s.performRefund(state, Refund.RefundMethod.LOYALTY);
        assertEquals("AUCUNE CARTE FIDÉLITÉ SUR LE TICKET D'ORIGINE", state.refund.errorMessage);
        verifyNoInteractions(s.ticketPrinterService);
        verifyNoInteractions(s.hardwareService);
        verifyNoInteractions(s.fidEventOutboxService);
        verifyNoInteractions(s.syncOutboxService);
    }

    /**
     * {@code performRefund} for a card refund only logs the terminal gesture
     * (CARD switch arm): no drawer, no voucher.
     */
    @Test
    void performRefundCardLogsOnly() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), anyString(), any());
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} for a card refund whose back office FORCES the
     * credit-card slip (LC-08-03-10) additionally prints the card credit
     * receipt ({@code isCreditCardReceiptForced} true arm): no drawer, no
     * voucher, but the forced slip is emitted.
     */
    @Test
    void performRefundCardPrintsForcedCreditReceipt() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(s.printPolicy.isCreditCardReceiptForced()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            Refund refund = mc.constructed().get(0);
            verify(s.ticketPrinterService).printCardCreditReceipt(refund);
        }
        verify(s.hardwareService, never()).openDrawer();
        verify(s.ticketPrinterService, never()).printRefundVoucher(any(), anyString(), any());
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} skips a zero-quantity entry (positive-quantity guard
     * false arm) and, with no line kept, skips the VAT restitution (lines-empty
     * arm) while still persisting the empty refund.
     */
    @Test
    void performRefundSkipsZeroQuantityLine() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ZERO);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertTrue(refund.lines.isEmpty());
            assertEquals(BigDecimal.ZERO, refund.totalAmount);
            assertNull(refund.totalExcludingTax);
            assertNull(refund.totalVat);
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} skips a quantity keyed on a line absent from the
     * original ticket (line-not-found guard true arm) and keeps no refund line.
     */
    @Test
    void performRefundSkipsUnknownLine() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(99L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 99L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertTrue(refund.lines.isEmpty());
        }
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * {@code performRefund} re-validates the double-refund cap inside the
     * transaction: a quantity exceeding the still-refundable amount surfaces the
     * refusal message and rolls back (per-line cap true arm), persisting and
     * printing nothing.
     */
    @Test
    void performRefundThrowsOnDoubleRefund() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "2", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("5"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            assertThrows(IllegalStateException.class,
                    () -> s.performRefund(state, Refund.RefundMethod.CASH));
            verify(mc.constructed().get(0), never()).persist();
        }
        assertEquals("QUANTITÉ DÉJÀ REMBOURSÉE (MILK)", state.refund.errorMessage);
        verifyNoInteractions(s.technicalEventService, s.syncOutboxService,
                s.ticketPrinterService, s.hardwareService);
    }

    /**
     * {@code performRefund} re-validates the ticket-level cap inside the
     * transaction: when past refunds plus the new one exceed the ticket total,
     * it surfaces the plafond message and rolls back (ticket-cap true arm),
     * persisting and printing nothing. Exercises a non-empty refund history.
     */
    @Test
    void performRefundThrowsOnTicketCapExceeded() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "5.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        Refund prior = mock(Refund.class);
        prior.totalAmount = new BigDecimal("3.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of(prior));
            assertThrows(IllegalStateException.class,
                    () -> s.performRefund(state, Refund.RefundMethod.CASH));
            verify(mc.constructed().get(0), never()).persist();
        }
        assertEquals("PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 3.00 €)", state.refund.errorMessage);
        verifyNoInteractions(s.technicalEventService, s.syncOutboxService,
                s.ticketPrinterService, s.hardwareService);
    }

    /**
     * BO-03-13-07: a return may cover the ticket ENTIRELY. Every unit of the
     * only line comes back, the refund equals the ticket total to the cent, and
     * it goes through — no exception, the row is persisted, journalled, pushed
     * and printed.
     *
     * <p>The ticket-cap guard is a STRICT {@code >}, and nothing else in this
     * class exercises the equality: every nominal case refunds a fraction, and
     * the only cap case is a plain overshoot. Turn the {@code >} into a
     * {@code >=} and the suite stays green without this test — while a customer
     * bringing back everything they bought would be refused at the register.
     */
    @Test
    void performRefundAcceptsFullTicketCoverage() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "30.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("3"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("30.00"), refund.totalAmount);
            assertEquals(new BigDecimal("3"), refund.lines.get(0).quantity);
            verify(refund).persist();
        }
        assertNull(state.refund.errorMessage);
        assertNull(state.refund.selectedTicket);
        verify(s.syncOutboxService).enqueue(SyncOutbox.EntityType.REFUND, 55L);
        verify(s.ticketPrinterService).printRefund(55L);
    }

    /**
     * BO-03-13-07: one cent PAST the ticket total is refused. Paired with the
     * case above, the two bracket the boundary exactly — full coverage yes,
     * beyond it never.
     */
    @Test
    void performRefundRefusesOneCentBeyondTheTicketTotal() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "29.99", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("3"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            assertThrows(IllegalStateException.class,
                    () -> s.performRefund(state, Refund.RefundMethod.CASH));
            verify(mc.constructed().get(0), never()).persist();
        }
        assertEquals("PLAFOND DU TICKET DÉPASSÉ (DÉJÀ REMBOURSÉ : 0.00 €)", state.refund.errorMessage);
    }

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the given
     * account.
     *
     * @param account the account the query returns, or null
     * @return the configured mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> accountQuery(
            com.intermarche.pos.domain.payment.AccountCustomer account) {
        PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> q = mock(PanacheQuery.class);
        when(q.firstResult()).thenReturn(account);
        return q;
    }

    /**
     * {@code selectCreditAccount} resolves the account by number and stores its
     * number and display name (LC-05-06-05, found arm).
     */
    @Test
    void selectCreditAccountFindsAndStores() {
        RefundService s = newService();
        PosState state = new PosState();
        com.intermarche.pos.domain.payment.AccountCustomer account =
                mock(com.intermarche.pos.domain.payment.AccountCustomer.class);
        account.accountNumber = "CC1";
        when(account.getDisplayName()).thenReturn("SARL DUPONT");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> aq = accountQuery(account);
            panache.when(() -> com.intermarche.pos.domain.payment.AccountCustomer
                    .find("accountNumber", "CC1")).thenReturn(aq);
            s.selectCreditAccount(state, " CC1 ");
        }
        assertEquals("CC1", state.refund.creditAccountNumber);
        assertEquals("SARL DUPONT", state.refund.creditAccountName);
        assertNull(state.refund.errorMessage);
    }

    /**
     * {@code selectCreditAccount} clears the selection and refuses when the
     * number matches no account (not-found arm).
     */
    @Test
    void selectCreditAccountUnknownClears() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.creditAccountNumber = "OLD";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> aq = accountQuery(null);
            panache.when(() -> com.intermarche.pos.domain.payment.AccountCustomer
                    .find("accountNumber", "ZZZ")).thenReturn(aq);
            s.selectCreditAccount(state, "ZZZ");
        }
        assertNull(state.refund.creditAccountNumber);
        assertEquals("CLIENT INTROUVABLE", state.refund.errorMessage);
    }

    /**
     * {@code selectCreditAccount} clears the selection and refuses on a blank
     * number without querying (blank arm).
     */
    @Test
    void selectCreditAccountBlankClears() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.creditAccountNumber = "OLD";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            s.selectCreditAccount(state, "   ");
            panache.verifyNoInteractions();
        }
        assertNull(state.refund.creditAccountNumber);
        assertEquals("CLIENT INTROUVABLE", state.refund.errorMessage);
    }

    /**
     * {@code requestRefund} refuses a credit refund when no in-account customer
     * is selected (LC-05-06-05 guard true arm): no endorsement is asked.
     */
    @Test
    void requestRefundCreditWithoutAccountRefused() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CREDIT", null);
            s.requestRefund(state, Refund.RefundMethod.CREDIT);
        }
        assertEquals("AUCUN CLIENT EN COMPTE SÉLECTIONNÉ", state.refund.errorMessage);
        verify(s.endorsementService, never()).requestAuthorization(any(), anyString());
    }

    /**
     * {@code requestRefund} asks the endorsement for a credit refund once an
     * account is selected (guard false arm).
     */
    @Test
    void requestRefundCreditWithAccountRequestsEndorsement() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.creditAccountNumber = "CC1";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CREDIT", null);
            s.requestRefund(state, Refund.RefundMethod.CREDIT);
        }
        verify(s.endorsementService).requestAuthorization(state, "REFUND_CREDIT_10");
        assertNull(state.refund.errorMessage);
    }

    /**
     * {@code performRefund} for a credit refund lowers the in-account customer's
     * outstanding balance by the refund amount and names the account on the
     * refund (LC-05-06-05, CREDIT switch arm).
     */
    @Test
    void performRefundCreditCreditsTheAccount() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.creditAccountNumber = "CC1";
        com.intermarche.pos.domain.payment.AccountCustomer account =
                mock(com.intermarche.pos.domain.payment.AccountCustomer.class);
        account.creditBalance = new BigDecimal("50.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> aq = accountQuery(account);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> com.intermarche.pos.domain.payment.AccountCustomer
                    .find("accountNumber", "CC1")).thenReturn(aq);
            s.performRefund(state, Refund.RefundMethod.CREDIT);
            Refund refund = mc.constructed().get(0);
            assertEquals("CC1", refund.creditAccountNumber);
        }
        assertEquals(new BigDecimal("30.00"), account.creditBalance);
        verify(account).persist();
        verify(s.hardwareService, never()).openDrawer();
    }

    /**
     * {@code performRefund} for a credit refund with no account selected refuses
     * inside the transaction and builds nothing (LC-05-06-05 guard).
     */
    @Test
    void performRefundCreditWithoutAccountRefused() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class)) {
            s.performRefund(state, Refund.RefundMethod.CREDIT);
            assertTrue(mc.constructed().isEmpty());
        }
        assertEquals("AUCUN CLIENT EN COMPTE SÉLECTIONNÉ", state.refund.errorMessage);
    }

    /**
     * {@code performRefund} of a hand-keyed PARTIAL amount keeps the ticket
     * selected and announces the remaining, so the rest can be handed back on
     * another mean (LC-05-06-01 split across means, partial-keep arm).
     */
    @Test
    void performRefundPartialKeepsTicketSelected() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.manualTotalAmount = new BigDecimal("30.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        assertSame(original, state.refund.selectedTicket);
        assertNull(state.refund.manualTotalAmount);
        assertEquals("REMBOURSEMENT PARTIEL - RESTE 70.00 €", state.refund.errorMessage);
    }

    /**
     * {@code performRefund} of a full manual amount clears the screen as before
     * (partial-keep false arm: no remaining left).
     */
    @Test
    void performRefundFullManualAmountClears() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.manualTotalAmount = new BigDecimal("100.00");
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
        }
        assertNull(state.refund.selectedTicket);
    }

    /**
     * {@code performRefund} for a voucher refund stamps the credit note with an
     * end of validity (LC-05-06-07): the avoir carries a validity date.
     */
    @Test
    void performRefundVoucherSetsValidUntil() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             });
             MockedConstruction<StoredValue> mcNote = mockConstruction(StoredValue.class,
                     (mock, ctx) -> mock.id = 77L)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.VOUCHER);
            StoredValue note = mcNote.constructed().get(0);
            assertNotNull(note.validUntil);
            assertTrue(note.validUntil.isAfter(note.issuedAt));
        }
    }

    // --------------------------------------------------
    // Campaign 2 — retours added branches
    // --------------------------------------------------

    /**
     * {@code selectTicket} tolerates an origin ticket whose line list is null
     * (the {@code t.lines != null} false leg of the fully-returned marking): the
     * ticket is selected and no line is marked.
     */
    @Test
    void selectTicketToleratesANullLineList() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket t = new Ticket();
        t.id = 10L;
        t.ticketNumber = "T-1";
        t.totalIncludingTax = new BigDecimal("30.00");
        t.lines = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(t);
            s.selectTicket(state, 10L);
        }
        assertSame(t, state.refund.selectedTicket);
        assertTrue(state.refund.fullyReturnedLineIds.isEmpty());
    }

    /**
     * {@code selectTicket} does not mark a CANCELLED origin line as fully returned
     * (the {@code !line.cancelled} false leg): a cancelled line was never sold, so
     * the fully-returned test is short-circuited and never consults past refunds.
     */
    @Test
    void selectTicketSkipsACancelledLineWhenMarking() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine sold = line(1L, "2", "10.00", "0.20", "MILK");
        TicketLine cancelled = line(3L, "1", "5.00", "0.20", "VOID");
        cancelled.cancelled = true;
        Ticket t = ticket(10L, "T-1", "25.00", sold, cancelled);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(10L)).thenReturn(t);
            panache.when(() -> RefundLine.list("originalLineId", 1L))
                    .thenReturn(List.of(refundLineQty("2")));
            s.selectTicket(state, 10L);
        }
        assertTrue(state.refund.isFullyReturned(1L));
        assertFalse(state.refund.isFullyReturned(3L));
    }

    /**
     * {@code selectCreditAccount} treats a null account number as empty (the
     * {@code accountNumber == null} true arm of the trim ternary): the selection
     * is cleared and CLIENT INTROUVABLE is set.
     */
    @Test
    void selectCreditAccountClearsOnNullNumber() {
        RefundService s = newService();
        PosState state = new PosState();
        s.selectCreditAccount(state, null);
        assertNull(state.refund.creditAccountNumber);
        assertNull(state.refund.creditAccountName);
        assertEquals("CLIENT INTROUVABLE", state.refund.errorMessage);
    }

    /**
     * {@code performRefund} journals no MOTIF suffix when the return reason is
     * blank (the {@code !returnReason.isBlank()} false leg of the suffix guard):
     * a reason present in name only must not pollute the REFUND_CREATED event.
     */
    @Test
    void performRefundJournalsNoSuffixForABlankReason() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.returnReason = "   ";
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            org.mockito.ArgumentCaptor<String> cap = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(s.technicalEventService).log(eq(TechnicalEvent.EventType.REFUND_CREATED), cap.capture());
            assertFalse(cap.getValue().contains("MOTIF"));
        }
    }

    /**
     * {@code cancelPaidTicket} skips a line with nothing left to reverse (the
     * {@code refundable.signum() > 0} false leg) and, with no endorser badge,
     * journals the cancellation under "?" (the {@code endorserBadge != null}
     * false arm). One live line still carries the reversal.
     */
    @Test
    void cancelPaidTicketSkipsExhaustedLinesAndJournalsWithoutBadge() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine live = line(1L, "1", "10.00", "0.20", "MILK");
        TicketLine exhausted = line(2L, "2", "10.00", "0.20", "EGG");
        state.refund.selectedTicket = ticket(10L, "T-9", "30.00", live, exhausted);
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            stubTender(panache, "CASH", tender("CASH", true));
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> RefundLine.list("originalLineId", 2L))
                    .thenReturn(List.of(refundLineQty("2")));
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.cancelPaidTicket(state, Refund.RefundMethod.CASH, null);
            assertFalse(mc.constructed().isEmpty());
        }
        verify(s.technicalEventService).log(
                eq(TechnicalEvent.EventType.PAID_TICKET_CANCELLED), contains("par ?"), isNull());
    }

    /**
     * {@code performRefund} for a credit refund whose account is not found (the
     * {@code account != null} false leg) still writes the refund but credits no
     * balance — the account row is owned by the nightly pull.
     */
    @Test
    void performRefundCreditWithUnknownAccountCreditsNothing() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.creditAccountNumber = "CC1";
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> aq = accountQuery(null);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> com.intermarche.pos.domain.payment.AccountCustomer
                    .find("accountNumber", "CC1")).thenReturn(aq);
            s.performRefund(state, Refund.RefundMethod.CREDIT);
            Refund refund = mc.constructed().get(0);
            assertEquals("CC1", refund.creditAccountNumber);
        }
    }

    /**
     * {@code performRefund} for a credit refund treats a null account balance as
     * zero (the {@code creditBalance == null} true arm) before subtracting the
     * refund, so the account falls into an advance in the customer's favour.
     */
    @Test
    void performRefundCreditTreatsANullBalanceAsZero() {
        RefundService s = newService();
        PosState state = new PosState();
        Ticket original = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        state.refund.creditAccountNumber = "CC1";
        com.intermarche.pos.domain.payment.AccountCustomer account =
                mock(com.intermarche.pos.domain.payment.AccountCustomer.class);
        account.creditBalance = null;
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            PanacheQuery<com.intermarche.pos.domain.payment.AccountCustomer> aq = accountQuery(account);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            panache.when(() -> com.intermarche.pos.domain.payment.AccountCustomer
                    .find("accountNumber", "CC1")).thenReturn(aq);
            s.performRefund(state, Refund.RefundMethod.CREDIT);
        }
        assertEquals(0, new BigDecimal("-20.00").compareTo(account.creditBalance));
        verify(account).persist();
    }

    /**
     * {@code performRefund} leaves an origin line with no database id out of the
     * kept map (the {@code line.id == null} true arm of the kept-quantities
     * guard), the way the cancelled line is left out, so the engine is asked only
     * about the persistent lines.
     */
    @Test
    void performRefundLeavesANullIdLineOutOfTheKeptMap() {
        RefundService s = newService();
        PosState state = new PosState();
        TicketLine idless = new TicketLine();
        idless.quantity = new BigDecimal("1");
        idless.unitPrice = new BigDecimal("5.00");
        idless.vatRate = new BigDecimal("0.20");
        idless.productLabel = "NOID";
        Ticket original = ticket(10L, "T-1", "100.00",
                line(1L, "3", "10.00", "0.20", "MILK"), idless);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, new BigDecimal("1"));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            org.mockito.ArgumentCaptor<java.util.Map<Long, BigDecimal>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(s.returnValuationService).recompute(same(original), captor.capture());
            assertTrue(captor.getValue().containsKey(1L));
            assertEquals(1, captor.getValue().size());
        }
    }
    /** LC-07: a real asynchronous credit creates no refund before approval and prints exact attached frames once. */
    @Test
    void asynchronousCardCreditWaitsForDecision() {
        RefundService s = newService();
        s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        when(s.posSettingsService.cardReceiptAttached()).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> {
                 value.lines = new ArrayList<>();
                 value.id = 55L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            Refund prepared = created.constructed().get(0);
            verify(prepared, never()).persist();
            s.performRefund(state, Refund.RefundMethod.CARD);
            assertEquals(1, created.constructed().size());
            var callback = ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
            verify(s.terminalClient).requestCredit(eq(new BigDecimal("20.00")), callback.capture());
            callback.getValue().onDisplay("INFORMATION", "WAITING");
            verify(prepared, never()).persist();
            var outcome = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("20.00"));
            outcome.server = "2";
            outcome.customerReceiptFrame = "CLIENT\n";
            outcome.merchantReceiptFrame = "MERCHANT\n";
            callback.getValue().onAccepted(outcome);
            verify(prepared, never()).persist();
            s.completeCardRefund(state);
            callback.getValue().onAccepted(outcome);
            s.completeCardRefund(state);
            verify(prepared, times(1)).persist();
            assertEquals("2", prepared.moneticServer);
            verify(s.ticketPrinterService).printRefund(55L, false);
            verify(s.ticketPrinterService).printTerminalFrame("CLIENT\n", null);
            verify(s.ticketPrinterService).printTerminalFrame("MERCHANT\n", null);
            verify(s.ticketPrinterService).archiveCustomerDocument(
                    com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, 55L, "CLIENT\n");
            verify(s.ticketPrinterService, never()).archiveCustomerDocument(any(), any(), eq("MERCHANT\n"));
            verify(s.ticketPrinterService, never()).printCardCreditReceipt(any());
        }
    }

    /**
     * BO-03-02-34: a store printing no card receipt still archives the
     * terminal's customer frame, prints the return alone and no frame.
     */
    @Test
    void cardCreditWithoutCardReceiptPrintsNoFrame() {
        RefundService s = newService();
        s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
        when(s.posSettingsService.cardReceiptPrinted()).thenReturn(false);
        when(s.posSettingsService.cardReceiptAttached()).thenReturn(true);
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> {
                 value.lines = new ArrayList<>();
                 value.id = 56L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            var callback = ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
            verify(s.terminalClient).requestCredit(eq(new BigDecimal("20.00")), callback.capture());
            var outcome = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("20.00"));
            outcome.customerReceiptFrame = "CLIENT\n";
            callback.getValue().onAccepted(outcome);
            s.completeCardRefund(state);
            verify(s.ticketPrinterService).printRefund(56L);
            verify(s.ticketPrinterService).archiveCustomerDocument(
                    com.intermarche.pos.domain.sale.CustomerDocument.Kind.CARD, 56L, "CLIENT\n");
            verify(s.ticketPrinterService, never()).printTerminalFrame(any(), any());
        }
    }

    /** LC-07: credit refusal or transport failure leaves the selected return unpaid and records no refund. */
    @Test
    void asynchronousCardCreditFailureDoesNotPersist() {
        RefundService s = newService();
        s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> value.lines = new ArrayList<>())) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            var callback = ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
            verify(s.terminalClient).requestCredit(eq(new BigDecimal("20.00")), callback.capture());
            callback.getValue().onRefused(null);
            callback.getValue().onError("LATE");
            s.completeCardRefund(state);
            assertNull(state.refund.pendingCardRefund);
            assertNotNull(state.refund.selectedTicket);
            verify(created.constructed().get(0), never()).persist();
            verifyNoInteractions(s.ticketPrinterService);
        }
    }

    /** LC-07-04-11/-17-05: customer-only, merchant-only and additional-only credit frames print separately by default. */
    @Test
    void cardCreditIndependentFrameLegs() {
        for (String kind : new String[] {"customer", "merchant", "additional"}) {
            RefundService s = newService();
            s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
            var outcome = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("20.00"));
            if ("customer".equals(kind)) outcome.customerReceiptFrame = "FRAME";
            if ("merchant".equals(kind)) outcome.merchantReceiptFrame = "FRAME";
            if ("additional".equals(kind)) outcome.additionalReceiptFrames.add("FRAME");
            doAnswer(invocation -> {
                invocation.getArgument(1, com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class).onAccepted(outcome);
                return null;
            }).when(s.terminalClient).requestCredit(any(), any());
            PosState state = new PosState();
            state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
            state.refund.returnQuantities.put(1L, new BigDecimal("2"));
            try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                 MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> {
                     value.lines = new ArrayList<>();
                     value.id = 55L;
                 })) {
                panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
                panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
                s.performRefund(state, Refund.RefundMethod.CARD);
                verify(created.constructed().get(0)).persist();
                verify(s.ticketPrinterService).printRefund(55L);
                verify(s.ticketPrinterService).printTerminalFrame("FRAME", null);
            }
        }
    }


    /** LC-05: a blocked refund neither persists nor requests supervisor authorization. */
    @Test
    void requestRefundBlockingControlStopsBeforePersistence() {
        RefundService service = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        when(service.endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.REFUND))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK);
        when(service.endorsementService.controlMessage(com.intermarche.pos.ui.endorsement.ControlPoint.REFUND,
                com.intermarche.pos.ui.endorsement.ControlLevel.BLOCK)).thenReturn("REFUND BLOCKED");
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            service.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertEquals("REFUND BLOCKED", state.refund.errorMessage);
        verify(service.endorsementService, never()).requestAuthorization(any(), any());
        verifyNoInteractions(service.syncOutboxService);
    }

    /** LC-05: a warning proceeds directly, while an independent refund guard still prevents persistence. */
    @Test
    void requestRefundWarningRetainsGuardFailure() {
        RefundService service = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        when(service.endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.REFUND))
                .thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.WARN);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            RefundLine previous = new RefundLine();
            previous.quantity = new BigDecimal("5");
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(previous));
            service.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertEquals("QUANTITÉ DÉJÀ REMBOURSÉE (MILK)", state.refund.errorMessage);
        verify(service.endorsementService, never()).requestAuthorization(any(), any());
        verifyNoInteractions(service.syncOutboxService);
    }

    /** LC-05: an already-authorized refund retains an independent guard's error without reopening supervisor authorization. */
    @Test
    void authorizedRefundRetainsAnIndependentGuardRefusal() {
        var service = spy(newService());
        var state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        when(service.endorsementService.gestureAuthorized(state, "refund")).thenReturn(true);
        doAnswer(call -> {
            state.refund.errorMessage = "GUARD REFUSAL";
            throw new IllegalStateException("GUARD REFUSAL");
        }).when(service).performRefund(state, Refund.RefundMethod.CASH);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            assertDoesNotThrow(() -> service.requestRefund(state, Refund.RefundMethod.CASH));
        }
        assertEquals("GUARD REFUSAL", state.refund.errorMessage);
        verify(service).performRefund(state, Refund.RefundMethod.CASH);
        verify(service.endorsementService, never()).requestAuthorization(any(), any());
    }
    /** LC-05 and BO-10-02-03: a warning permits the selected refund while preserving its informational wording. */
    @Test
    void warningControlProceedsWithoutSupervisorAuthorization() {
        var service = spy(newService());
        var state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "50.00", line(1L, "5", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        when(service.endorsementService.check(state, com.intermarche.pos.ui.endorsement.ControlPoint.REFUND)).thenReturn(com.intermarche.pos.ui.endorsement.ControlLevel.WARN);
        when(service.endorsementService.controlMessage(com.intermarche.pos.ui.endorsement.ControlPoint.REFUND, com.intermarche.pos.ui.endorsement.ControlLevel.WARN)).thenReturn("REFUND WARNING");
        doNothing().when(service).performRefund(state, Refund.RefundMethod.CASH);
        try (var panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            service.requestRefund(state, Refund.RefundMethod.CASH);
        }
        assertEquals("REFUND WARNING", state.refund.errorMessage);
        verify(service).performRefund(state, Refund.RefundMethod.CASH);
        verify(service.endorsementService, never()).requestAuthorization(any(), any());
    }

    /** LC-04-05-01: a fresh combined return search drops any stale selected ticket while preserving its entered criteria and results. */
    @Test
    void combinedSearchSupersedesAStaleReturnSelection() {
        var service = newService();
        service.ticketSearchService = mock(com.intermarche.pos.ui.ticket.TicketSearchService.class);
        var criteria = new com.intermarche.pos.ui.ticket.TicketSearchCriteria();
        criteria.terminalId = "C04";
        var state = new PosState();
        state.refund.selectedTicket = new Ticket();
        var results = List.of(new Ticket());
        when(service.ticketSearchService.searchCriteria(criteria, false)).thenReturn(results);
        service.searchCriteria(state, criteria);
        assertNull(state.refund.selectedTicket);
        assertSame(criteria, state.refund.searchCriteria);
        assertSame(results, state.refund.foundTickets);
    }


    // --------------------------------------------------
    // LC-05-03-01: a dematerialised article handed back
    // --------------------------------------------------

    /**
     * A dematerialised line of the origin ticket, at 25,00.
     *
     * @param code the activation code the sale recorded, or null
     * @param provider the provider, or null
     * @return the line
     */
    private TicketLine dematerialised(String code, String provider) {
        TicketLine line = line(1L, "1", "25.00", "0.00", "CARTE CADEAU");
        line.totalPrice = new BigDecimal("25.00");
        line.activationCode = code;
        line.activationProvider = provider;
        return line;
    }

    /**
     * A service whose payment hub is administered.
     *
     * @return the service
     */
    private RefundService hubService() {
        RefundService s = newService();
        when(s.nexoHubClient.isConfigured()).thenReturn(true);
        return s;
    }

    /**
     * An accepted hub answer.
     *
     * @param balance the balance, or null
     * @param provider the provider, or null
     * @return the answer
     */
    private com.intermarche.pos.ui.payment.NexoHubClient.HubResult hubOk(String balance, String provider) {
        return new com.intermarche.pos.ui.payment.NexoHubClient.HubResult(true, null, null, "HUB5", null,
                balance == null ? null : new BigDecimal(balance), provider, null);
    }

    /**
     * Without a hub, a dematerialised line comes back like any other.
     */
    @Test
    void withoutAHubADematerialisedLineComesBackPlainly() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised("9001", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        }
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
        assertNull(state.refund.pendingActivationLineId);
    }

    /**
     * With a hub, taking a dematerialised line back asks for its code; a line
     * already read is left as it is; taking it out again forgets it.
     */
    @Test
    void withAHubADematerialisedLineAsksForItsCode() {
        RefundService s = hubService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised(null, "TRANSACTIS"));
        state.refund.errorMessage = "AVANT";
        s.setReturnQuantity(state, 1L, BigDecimal.ONE);
        assertEquals(1L, state.refund.pendingActivationLineId);
        assertNull(state.refund.errorMessage);
        assertEquals("SCANNEZ LE CODE UNIQUE DE L'ARTICLE DÉMATÉRIALISÉ", state.refund.infoMessage);
        assertFalse(state.refund.returnQuantities.containsKey(1L));
        state.refund.pendingActivationLineId = null;
        state.refund.deactivations.put(1L, new RefundState.Deactivation("9001", null, BigDecimal.TEN));
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        s.setReturnQuantity(state, 1L, new BigDecimal("2"));
        assertNull(state.refund.pendingActivationLineId);
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
        s.setReturnQuantity(state, 1L, BigDecimal.ZERO);
        assertTrue(state.refund.deactivations.isEmpty());
        assertFalse(state.refund.returnQuantities.containsKey(1L));
    }

    /**
     * The code awaited must be the one the sale recorded; a line that vanished
     * closes the wait.
     */
    @Test
    void theAwaitedCodeMustBeTheArticles() {
        RefundService s = hubService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised("9001", "TRANSACTIS"));
        state.refund.pendingActivationLineId = 1L;
        s.enterArticleByCode(state, "9002");
        assertEquals("CE CODE N'EST PAS CELUI DE L'ARTICLE VENDU", state.refund.errorMessage);
        assertEquals(1L, state.refund.pendingActivationLineId);
        state.refund.pendingActivationLineId = 7L;
        s.enterArticleByCode(state, "9001");
        assertEquals("ARTICLE ABSENT DU TICKET D'ORIGINE", state.refund.errorMessage);
        assertNull(state.refund.pendingActivationLineId);
        verify(s.nexoHubClient, never()).balance(any());
    }

    /**
     * The hub refusing, absent, or saying the article carries nothing leaves it
     * out of the return and says why.
     */
    @Test
    void theHubMustSayTheArticleStillCarriesSomething() {
        RefundService s = hubService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised("9001", "TRANSACTIS"));
        String[] expected = {"HUB DE PAIEMENT INJOIGNABLE", "ARTICLE REFUSÉ PAR LE HUB (INCONNU)",
                "ARTICLE REFUSÉ PAR LE HUB (InvalidCard)", "ARTICLE SANS SOLDE RESTANT : AUCUN REMBOURSEMENT",
                "ARTICLE SANS SOLDE RESTANT : AUCUN REMBOURSEMENT"};
        com.intermarche.pos.ui.payment.NexoHubClient.HubResult[] answers = {
                com.intermarche.pos.ui.payment.NexoHubClient.HubResult.failure(
                        com.intermarche.pos.ui.payment.NexoHubClient.UNAVAILABLE, null),
                com.intermarche.pos.ui.payment.NexoHubClient.HubResult.failure("InvalidCard", "INCONNU"),
                com.intermarche.pos.ui.payment.NexoHubClient.HubResult.failure("InvalidCard", null),
                hubOk(null, null), hubOk("0.00", null)};
        for (int i = 0; i < answers.length; i++) {
            when(s.nexoHubClient.balance("9001")).thenReturn(answers[i]);
            state.refund.pendingActivationLineId = 1L;
            state.refund.infoMessage = "AVANT";
            s.enterArticleByCode(state, "9001");
            assertEquals(expected[i], state.refund.errorMessage);
            assertNull(state.refund.infoMessage);
            assertTrue(state.refund.deactivations.isEmpty());
        }
    }

    /**
     * An article the hub says still carries something is staged for
     * deactivation, its refund capped at what was paid, its provider the sale's
     * or the hub's.
     */
    @Test
    void anArticleStillCarryingSomethingIsStaged() {
        RefundService s = hubService();
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised(null, null));
        state.refund.pendingActivationLineId = 1L;
        state.refund.manualTotalAmount = BigDecimal.TEN;
        when(s.nexoHubClient.balance("ANY")).thenReturn(hubOk("30.00", "HUBSIM"));
        s.enterArticleByCode(state, "ANY");
        RefundState.Deactivation staged = state.refund.deactivations.get(1L);
        assertEquals("ANY", staged.code());
        assertEquals("HUBSIM", staged.provider());
        assertEquals(0, new BigDecimal("30.00").compareTo(staged.balance()));
        assertEquals(0, BigDecimal.ONE.compareTo(state.refund.returnQuantities.get(1L)));
        assertNull(state.refund.manualTotalAmount);
        assertEquals(1L, state.refund.selectedLineId);
        assertNull(state.refund.pendingActivationLineId);
        assertEquals("SOLDE RESTANT 30,00 € : 25,00 € REMBOURSÉS À LA DÉSACTIVATION", state.refund.infoMessage);
        assertEquals(new BigDecimal("25.00"), state.refund.getTotalRefundAmount());
        state.refund.selectedTicket = ticket(10L, "T-1", "25.00", dematerialised("9001", "TRANSACTIS"));
        state.refund.pendingActivationLineId = 1L;
        when(s.nexoHubClient.balance("9001")).thenReturn(hubOk("12.50", "HUBSIM"));
        s.enterArticleByCode(state, "9001");
        assertEquals("TRANSACTIS", state.refund.deactivations.get(1L).provider());
        assertEquals(new BigDecimal("12.50"), state.refund.getTotalRefundAmount());
    }

    /**
     * The refund deactivates each staged article on the hub before it is
     * written, and refunds what each still carried; a refusal undoes the
     * deactivations already made and writes nothing.
     */
    @Test
    void theRefundDeactivatesTheArticlesFirst() {
        RefundService s = hubService();
        PosState state = new PosState();
        TicketLine second = dematerialised("9002", null);
        second.id = 2L;
        Ticket original = ticket(10L, "T-1", "50.00", dematerialised("9001", "TRANSACTIS"), second);
        state.refund.selectedTicket = original;
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        state.refund.deactivations.put(1L, new RefundState.Deactivation("9001", "TRANSACTIS", new BigDecimal("12.50")));
        when(s.ticketNumberService.nextRefundNumber()).thenReturn("R-1");
        when(s.ticketNumberService.getTerminalId()).thenReturn("C04");
        when(s.cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
        when(s.nexoHubClient.unload("R-1-1", "TRANSACTIS", "9001", new BigDecimal("12.50")))
                .thenReturn(hubOk("0.00", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 55L;
             })) {
            panache.when(() -> RefundLine.list(eq("originalLineId"), any(Object[].class))).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CASH);
            Refund refund = mc.constructed().get(0);
            assertEquals(new BigDecimal("12.50"), refund.totalAmount);
            assertEquals(0, new BigDecimal("12.50").compareTo(refund.lines.get(0).price));
            verify(refund).persist();
        }
        PosState refused = new PosState();
        refused.refund.selectedTicket = original;
        refused.refund.returnQuantities.put(1L, BigDecimal.ONE);
        refused.refund.returnQuantities.put(2L, BigDecimal.ONE);
        refused.refund.deactivations.put(1L, new RefundState.Deactivation("9001", "TRANSACTIS", new BigDecimal("12.50")));
        refused.refund.deactivations.put(2L, new RefundState.Deactivation("9002", null, new BigDecimal("5.00")));
        when(s.nexoHubClient.unload("R-1-2", null, "9002", new BigDecimal("5.00")))
                .thenReturn(com.intermarche.pos.ui.payment.NexoHubClient.HubResult.failure("Refusal", null));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> mc = mockConstruction(Refund.class, (mock, ctx) -> {
                 mock.lines = new ArrayList<>();
                 mock.id = 56L;
             })) {
            panache.when(() -> RefundLine.list(eq("originalLineId"), any(Object[].class))).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            refused.refund.infoMessage = "AVANT";
            s.performRefund(refused, Refund.RefundMethod.CASH);
            verify(mc.constructed().get(0), never()).persist();
        }
        verify(s.nexoHubClient).reverseStoredValue("R-1-R", "HUB5");
        assertEquals("ARTICLE REFUSÉ PAR LE HUB (Refusal)", refused.refund.errorMessage);
        assertNull(refused.refund.infoMessage);
    }

    /**
     * BO-03-08-06: a deactivation the provider refuses shows the shop's own
     * sentence for that answer where the shop wrote one.
     *
     * @throws Exception when the helper cannot be reached
     */
    @Test
    void aRefusedDeactivationShowsTheShopsSentence() throws Exception {
        RefundService s = hubService();
        java.lang.reflect.Method refusal = RefundService.class.getDeclaredMethod("hubRefusal",
                com.intermarche.pos.ui.payment.NexoHubClient.HubResult.class, String.class);
        refusal.setAccessible(true);
        com.intermarche.pos.domain.setting.ProviderMessage message =
                new com.intermarche.pos.domain.setting.ProviderMessage();
        message.screenText = "Carte déjà utilisée";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            io.quarkus.hibernate.orm.panache.PanacheQuery<Object> found =
                    mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
            when(found.firstResult()).thenReturn(message);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Object[].class))).thenReturn(found);
            assertEquals("Carte déjà utilisée", refusal.invoke(s,
                    com.intermarche.pos.ui.payment.NexoHubClient.HubResult.failure("Refusal", "USED"),
                    "TRANSACTIS"));
        }
    }

    // --- BO-10-01-04 / -05: the return types of an e-commerce ticket ---

    /**
     * An e-commerce ticket of two lines, the first already partly returned.
     *
     * @return the ticket, booked on the virtual register ECOM
     */
    private Ticket ecommerceTicket() {
        Ticket ticket = ticket(20L, "ECOM-W1", "30.00", line(1L, "2", "10.00", "0.20", "MILK"),
                line(2L, "1", "10.00", "0.20", "BREAD"));
        TicketLine cancelled = line(3L, "1", "5.00", "0.20", "GONE");
        cancelled.cancelled = true;
        ticket.lines.add(cancelled);
        ticket.terminalId = "ECOM";
        return ticket;
    }

    /**
     * Runs a refund request on the e-commerce ticket, one MILK already returned.
     *
     * @param s the service
     * @param state the state, its return quantities set
     */
    private void requestOnEcommerce(RefundService s, PosState state) {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            stubTender(panache, "CASH", null);
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of(refundLineQty("1")));
            panache.when(() -> RefundLine.list("originalLineId", 2L)).thenReturn(List.of());
            s.requestRefund(state, Refund.RefundMethod.CASH);
        }
    }

    /**
     * Returning the WHOLE e-commerce ticket — what remained of every line not
     * cancelled — takes one of the ticket-return types: another type, or none,
     * is refused before any endorsement, an allowed one goes on, whatever its
     * case (BO-10-01-04).
     */
    @Test
    void theWholeEcommerceTicketTakesATicketReturnType() {
        RefundService s = newService();
        when(s.posSettingsService.ecommerceTerminal()).thenReturn("ECOM");
        when(s.posSettingsService.ecommerceReturnTicketTypes()).thenReturn("Retour web");
        when(s.posSettingsService.ecommerceReturnArticleTypes()).thenReturn("Défectueux");
        PosState state = new PosState();
        state.refund.selectedTicket = ecommerceTicket();
        state.refund.returnQuantities.put(1L, BigDecimal.ONE);
        state.refund.returnQuantities.put(2L, BigDecimal.ONE);
        requestOnEcommerce(s, state);
        assertEquals("TYPE DE RETOUR NON AUTORISÉ POUR UN TICKET E-COMMERCE", state.refund.errorMessage);
        state.returnTypeLabel = "Défectueux";
        requestOnEcommerce(s, state);
        assertEquals("TYPE DE RETOUR NON AUTORISÉ POUR UN TICKET E-COMMERCE", state.refund.errorMessage);
        verify(s.endorsementService, never()).requestAuthorization(any(), any());
        state.returnTypeLabel = " retour WEB ";
        requestOnEcommerce(s, state);
        assertNull(state.refund.errorMessage);
        verify(s.endorsementService).requestAuthorization(state, "REFUND_CASH_20");
    }

    /**
     * Returning PART of the e-commerce ticket takes one of the article-return
     * types (BO-10-01-05).
     */
    @Test
    void partOfAnEcommerceTicketTakesAnArticleReturnType() {
        RefundService s = newService();
        when(s.posSettingsService.ecommerceTerminal()).thenReturn("ECOM");
        when(s.posSettingsService.ecommerceReturnTicketTypes()).thenReturn("Retour web");
        when(s.posSettingsService.ecommerceReturnArticleTypes()).thenReturn("Défectueux");
        PosState state = new PosState();
        state.refund.selectedTicket = ecommerceTicket();
        state.refund.returnQuantities.put(2L, BigDecimal.ONE);
        state.returnTypeLabel = "Retour web";
        requestOnEcommerce(s, state);
        assertEquals("TYPE DE RETOUR NON AUTORISÉ POUR UN TICKET E-COMMERCE", state.refund.errorMessage);
        state.returnTypeLabel = "Défectueux";
        requestOnEcommerce(s, state);
        assertNull(state.refund.errorMessage);
    }

    /**
     * An e-commerce ticket whose store lists no type takes every return, and a
     * ticket of another register is not concerned whatever the lists say.
     */
    @Test
    void theEcommerceTypesConcernTheEcommerceTicketsOnly() {
        RefundService s = newService();
        when(s.posSettingsService.ecommerceTerminal()).thenReturn("ECOM");
        PosState state = new PosState();
        state.refund.selectedTicket = ecommerceTicket();
        state.refund.returnQuantities.put(2L, BigDecimal.ONE);
        requestOnEcommerce(s, state);
        assertNull(state.refund.errorMessage);
        when(s.posSettingsService.ecommerceReturnArticleTypes()).thenReturn("Défectueux");
        state.refund.selectedTicket.terminalId = "C04";
        requestOnEcommerce(s, state);
        assertNull(state.refund.errorMessage);
    }

    /**
     * BO-10-01-04 / -05: a search finding nothing here asks the store node for
     * the e-commerce ticket the number names, and lists it when it comes; a
     * search finding a local ticket asks nothing.
     */
    @Test
    void aSearchFindingNothingBringsTheEcommerceTicketBack() {
        RefundService s = newService();
        PosState state = new PosState();
        state.refund.searchPattern = "ECOM-W1";
        state.auth.operatorId = 7L;
        when(s.ticketSearchService.searchByPattern("ECOM-W1")).thenReturn(List.of());
        s.searchTickets(state);
        assertTrue(state.refund.foundTickets.isEmpty());
        Ticket imported = ticket(30L, "ECOM-W1", "10.00");
        when(s.ecommerceTicketClient.importTicket("ECOM-W1", 7L)).thenReturn(imported);
        s.searchTickets(state);
        assertEquals(List.of(imported), state.refund.foundTickets);
        Ticket local = ticket(31L, "C04-1", "10.00");
        state.refund.searchPattern = "C04-1";
        when(s.ticketSearchService.searchByPattern("C04-1")).thenReturn(List.of(local));
        s.searchTickets(state);
        verify(s.ecommerceTicketClient, never()).importTicket(eq("C04-1"), any());
    }

    // --------------------------------------------------
    // BO-03-13-04: a TICKET-mode return type forces the identification
    // --------------------------------------------------

    /**
     * Puts the state under a TICKET-mode return type.
     *
     * @param s the service whose list resolver answers the type
     * @param state the state choosing the type
     */
    private void underTicketMode(RefundService s, PosState state) {
        state.returnTypeLabel = "Ticket";
        when(s.posListService.returnType("Ticket"))
                .thenReturn(new com.intermarche.pos.service.PosSettingsService.ReturnType("Ticket", true));
    }

    /**
     * Builds a Panache query whose list is the given tickets.
     *
     * @param tickets the tickets listed
     * @return the query
     */
    @SuppressWarnings("unchecked")
    private io.quarkus.hibernate.orm.panache.PanacheQuery<PanacheEntityBase> listing(Ticket... tickets) {
        io.quarkus.hibernate.orm.panache.PanacheQuery<PanacheEntityBase> query =
                mock(io.quarkus.hibernate.orm.panache.PanacheQuery.class);
        when(query.list()).thenReturn(List.of(tickets));
        return query;
    }

    /**
     * BO-03-13-04: only a type administered in TICKET mode forces the
     * identification; no type, or an in-sale type, does not.
     */
    @Test
    void identificationIsForcedOnlyByATicketModeType() {
        RefundService s = newService();
        PosState state = new PosState();
        assertFalse(s.identificationForced(state));
        state.returnTypeLabel = "Contenant";
        when(s.posListService.returnType("Contenant"))
                .thenReturn(new com.intermarche.pos.service.PosSettingsService.ReturnType("Contenant", false));
        assertFalse(s.identificationForced(state));
        underTicketMode(s, state);
        assertTrue(s.identificationForced(state));
    }

    /**
     * BO-03-13-04: in TICKET mode a fragment finds nothing and says how to
     * identify the ticket; the full number of a closed ticket selects it at
     * once; two tickets of that number are listed without a selection.
     */
    @Test
    void aForcedSearchIdentifiesTheTicketByItsNumber() {
        RefundService s = newService();
        PosState state = new PosState();
        underTicketMode(s, state);
        state.refund.searchPattern = null;
        s.searchTickets(state);
        assertTrue(state.refund.foundTickets.isEmpty());
        assertEquals("TYPE DE RETOUR TICKET : SCANNEZ LE TICKET D'ORIGINE OU SAISISSEZ SON NUMERO ET SA DATE",
                state.refund.errorMessage);
        verify(s.ticketSearchService, never()).searchByPattern(any());
        Ticket original = ticket(40L, "C04-00000040", "12.00");
        Ticket twin = ticket(41L, "C04-00000040", "8.00");
        var single = listing(original);
        var pair = listing(original, twin);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ticketNumber = ?1 and status = ?2",
                    "C04-00000040", Ticket.TicketStatus.CLOSED)).thenReturn(single, pair);
            panache.when(() -> Ticket.findById(40L)).thenReturn(original);
            state.refund.searchPattern = " C04-00000040 ";
            state.refund.errorMessage = null;
            s.searchTickets(state);
            assertSame(original, state.refund.selectedTicket);
            state.refund.clearSelection();
            s.searchTickets(state);
            assertEquals(2, state.refund.foundTickets.size());
            assertNull(state.refund.selectedTicket);
            assertNull(state.refund.errorMessage);
        }
        state.refund.searchPattern = "  ";
        s.searchTickets(state);
        assertTrue(state.refund.foundTickets.isEmpty());
    }

    /**
     * BO-03-13-04: in TICKET mode a ticket the identification did not find
     * cannot be selected; one it found can.
     */
    @Test
    void aForcedSelectionRefusesAnUnidentifiedTicket() {
        RefundService s = newService();
        PosState state = new PosState();
        underTicketMode(s, state);
        Ticket original = ticket(50L, "C04-00000050", "5.00");
        s.selectTicket(state, 50L);
        assertNull(state.refund.selectedTicket);
        assertEquals("TYPE DE RETOUR TICKET : SCANNEZ LE TICKET D'ORIGINE OU SAISISSEZ SON NUMERO ET SA DATE",
                state.refund.errorMessage);
        state.refund.foundTickets = new java.util.ArrayList<>(List.of(ticket(51L, "C04-00000051", "1.00"), original));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(50L)).thenReturn(original);
            s.selectTicket(state, 50L);
        }
        assertSame(original, state.refund.selectedTicket);
    }

    /**
     * BO-03-13-04: in TICKET mode the criteria identify the ticket only with
     * both its number and its date — a missing one (empty or absent) is
     * refused before any search; both select the single ticket found.
     */
    @Test
    void forcedCriteriaNeedTheNumberAndTheDate() {
        RefundService s = newService();
        PosState state = new PosState();
        underTicketMode(s, state);
        var noDate = new com.intermarche.pos.ui.ticket.TicketSearchCriteria();
        noDate.number = "00000060";
        s.searchCriteria(state, noDate);
        assertEquals("TYPE DE RETOUR TICKET : SAISISSEZ LE NUMERO ET LA DATE DU TICKET D'ORIGINE",
                state.refund.errorMessage);
        assertTrue(noDate.submitted);
        var noNumber = new com.intermarche.pos.ui.ticket.TicketSearchCriteria();
        noNumber.number = null;
        noNumber.date = "25/10/2026";
        s.searchCriteria(state, noNumber);
        assertTrue(state.refund.foundTickets.isEmpty());
        verify(s.ticketSearchService, never()).searchCriteria(any(), eq(false));
        var both = new com.intermarche.pos.ui.ticket.TicketSearchCriteria();
        both.number = "00000060";
        both.date = "25/10/2026";
        Ticket original = ticket(60L, "C04-00000060", "9.00");
        when(s.ticketSearchService.searchCriteria(both, false)).thenReturn(List.of(original));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Ticket.findById(60L)).thenReturn(original);
            s.searchCriteria(state, both);
        }
        assertSame(original, state.refund.selectedTicket);
        assertNull(state.refund.errorMessage);
    }

    // --------------------------------------------------
    // lot 21: the card credit decision serialized with the return screen
    // --------------------------------------------------

    /**
     * Waits, bounded, until a thread is blocked on a monitor.
     *
     * @param thread the thread expected to block
     */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.currentTimeMillis() + 5000;
        while (thread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    /**
     * Waits, bounded, until a condition holds.
     *
     * @param condition the condition awaited
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * Runs a decision on another thread while this thread holds the register's
     * lock, and proves it waits for it: blocked and without effect while the
     * lock is held, applied once it is released.
     *
     * @param state the register state whose lock is held
     * @param decision the decision run on the other thread
     * @param applied tells whether the decision's effect is visible
     * @throws InterruptedException when the joining thread is interrupted
     */
    private static void assertWaitsForTheRegisterLock(PosState state, Runnable decision,
            java.util.function.BooleanSupplier applied) throws InterruptedException {
        Thread other = new Thread(decision, "terminal-decision");
        synchronized (state) {
            other.start();
            awaitBlocked(other);
            assertEquals(Thread.State.BLOCKED, other.getState());
            assertFalse(applied.getAsBoolean());
        }
        other.join(5000);
        assertFalse(other.isAlive());
        assertTrue(applied.getAsBoolean());
    }

    /**
     * Prepares a twenty-euro return of two MILK lines on a fresh state.
     *
     * @return the state carrying the selected ticket and the quantities
     */
    private PosState cardReturnState() {
        PosState state = new PosState();
        state.refund.selectedTicket = ticket(10L, "T-1", "100.00", line(1L, "3", "10.00", "0.20", "MILK"));
        state.refund.returnQuantities.put(1L, new BigDecimal("2"));
        return state;
    }

    /**
     * Lot 21: the accept leg of a card credit waits for the register's lock
     * before depositing its outcome; the first outcome is the one kept — a
     * second accept and a late error are ignored — and the refund is written
     * once ({@code pendingCardOutcome == null} false leg of both guards).
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void cardCreditAcceptWaitsForTheLockAndIsKeptOnce() throws InterruptedException {
        RefundService s = newService();
        s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
        PosState state = cardReturnState();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> {
                 value.lines = new ArrayList<>();
                 value.id = 57L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            var callback = ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
            verify(s.terminalClient).requestCredit(eq(new BigDecimal("20.00")), callback.capture());
            var first = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("20.00"));
            first.server = "1";
            var second = com.intermarche.pos.ui.hardware.terminal.TerminalOutcome.ofAmount(new BigDecimal("20.00"));
            second.server = "4";
            assertWaitsForTheRegisterLock(state, () -> callback.getValue().onAccepted(first),
                    () -> state.refund.pendingCardOutcome != null);
            callback.getValue().onAccepted(second);
            callback.getValue().onError("LATE");
            assertSame(first, state.refund.pendingCardOutcome);
            assertNotNull(state.refund.pendingCardRefund);
            assertNull(state.refund.errorMessage);
            s.completeCardRefund(state);
            Refund prepared = created.constructed().get(0);
            verify(prepared, times(1)).persist();
            assertEquals("1", prepared.moneticServer);
        }
    }

    /**
     * Lot 21: the error leg of a card credit waits for the register's lock
     * before dropping the pending return.
     *
     * @throws InterruptedException when the joining thread is interrupted
     */
    @Test
    void cardCreditErrorWaitsForTheRegisterLock() throws InterruptedException {
        RefundService s = newService();
        s.terminalClient = mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class);
        PosState state = cardReturnState();
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> value.lines = new ArrayList<>())) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            s.performRefund(state, Refund.RefundMethod.CARD);
            var callback = ArgumentCaptor.forClass(com.intermarche.pos.ui.hardware.terminal.TerminalTransactionCallback.class);
            verify(s.terminalClient).requestCredit(eq(new BigDecimal("20.00")), callback.capture());
            assertWaitsForTheRegisterLock(state, () -> callback.getValue().onError("INJOIGNABLE"),
                    () -> state.refund.pendingCardRefund == null);
            assertEquals("INJOIGNABLE", state.refund.errorMessage);
            verify(created.constructed().get(0), never()).persist();
        }
    }

    /**
     * Plays a card return through the real terminal chain — the degraded-mode
     * gate in front of the hardware bridge, whose terminal decides at the
     * first reading — while the test holds the register's lock as the return
     * screen does: the decision read by the follower thread waits for the
     * lock, is then deposited, and the request side writes the refund once.
     *
     * @param forced whether this register forced the degraded mode
     * @return the mocked bridge, for the request it was sent
     * @throws InterruptedException when the waiting thread is interrupted
     */
    private com.intermarche.pos.ui.hardware.HardwareClient playBridgeCardReturn(boolean forced)
            throws InterruptedException {
        RefundService s = newService();
        com.intermarche.pos.ui.hardware.HardwareClient hardware = mock(com.intermarche.pos.ui.hardware.HardwareClient.class);
        Thread[] follower = new Thread[1];
        when(hardware.getPaymentStatus()).thenAnswer(invocation -> {
            follower[0] = Thread.currentThread();
            return "state=DONE\napproved=true\nresult=00\nanswer={RS}00";
        });
        PosState state = cardReturnState();
        state.moneticsDegradedUntil = forced ? LocalDateTime.now().plusMinutes(10) : null;
        s.terminalClient = new com.intermarche.pos.ui.hardware.terminal.DegradedModePaymentTerminalClient(
                new com.intermarche.pos.ui.hardware.terminal.HardwareBridgeTerminalClient(hardware, 1, 10000,
                        TestMessages.french()),
                mock(com.intermarche.pos.ui.hardware.terminal.PaymentTerminalClient.class), s.posSettingsService, state);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedConstruction<Refund> created = mockConstruction(Refund.class, (value, context) -> {
                 value.lines = new ArrayList<>();
                 value.id = 58L;
             })) {
            panache.when(() -> RefundLine.list("originalLineId", 1L)).thenReturn(List.of());
            panache.when(() -> Refund.list("originalTicketId", 10L)).thenReturn(List.of());
            synchronized (state) {
                s.performRefund(state, Refund.RefundMethod.CARD);
                verify(hardware, timeout(5000).atLeastOnce()).getPaymentStatus();
                awaitTrue(() -> follower[0] != null);
                awaitBlocked(follower[0]);
                assertEquals(Thread.State.BLOCKED, follower[0].getState());
                assertNull(state.refund.pendingCardOutcome);
            }
            awaitTrue(() -> state.refund.pendingCardOutcome != null);
            s.completeCardRefund(state);
            s.completeCardRefund(state);
            verify(created.constructed().get(0), times(1)).persist();
            assertNull(state.refund.pendingCardRefund);
        }
        return hardware;
    }

    /**
     * Lot 21, return refund credited through the bridge: serialized, written
     * once.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeCardReturnDecisionWaitsForTheRegisterLock() throws InterruptedException {
        verify(playBridgeCardReturn(false)).startCredit("2000");
    }

    /**
     * Lot 21, return refund credited through the bridge on a register that
     * forced the degraded mode (LC-07-08-02): serialized, written once.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    @Test
    void bridgeForcedDegradedCardReturnDecisionWaitsForTheRegisterLock() throws InterruptedException {
        verify(playBridgeCardReturn(true)).startDegradedCredit("2000");
    }
}
