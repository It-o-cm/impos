package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.service.valuation.ValuationService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.HardwareService;
import com.intermarche.pos.ui.scanner.ScanContext;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketService}.
 * <p>
 * Plain JUnit 5 + Mockito: every collaborator is mocked while the real
 * {@link PosState}/{@link TicketState} graph is exercised so cart mutations
 * are observed on live state. Panache static finders resolve to
 * {@link PanacheEntityBase} under plain {@code mvn test}: {@code Product.find}
 * is intercepted with {@link org.mockito.Mockito#mockStatic} on
 * {@link PanacheEntityBase}, while the entity-declared statics
 * {@code Product.findActiveByPlu} and {@code Price.findCurrentPrice} are
 * intercepted on {@link Product}/{@link Price} respectively. Every branch of
 * the target class is enumerated and covered, including the unreachable-by-API
 * {@code displayItem(null)} arm reached through reflection.
 */
class TicketServiceTest {

    /** Service under test with hand-wired collaborators. */
    private TicketService service;

    /** Real POS state graph shared as both the injected and the parameter state. */
    private PosState state;

    /** Mocked hardware boundary (display and scale). */
    private HardwareService hardwareService;

    /** Mocked draft persistence collaborator. */
    private TicketPersistenceService ticketPersistenceService;

    /** Mocked valuation engine collaborator. */
    private ValuationService valuationService;

    /** Mocked cash session collaborator. */
    private CashSessionService cashSessionService;

    /** Mocked ticket printer collaborator. */
    private TicketPrinterService ticketPrinterService;

    /** Mocked CDI handler instance feeding the scan chain. */
    @SuppressWarnings("unchecked")
    private Instance<ScanContext.ScanHandler> scanHandlers = mock(Instance.class);

    /**
     * Builds a fresh service, an unlocked-agnostic real state and all mocks.
     */
    @BeforeEach
    void setUp() {
        service = new TicketService();
        state = new PosState();
        hardwareService = mock(HardwareService.class);
        ticketPersistenceService = mock(TicketPersistenceService.class);
        valuationService = mock(ValuationService.class);
        cashSessionService = mock(CashSessionService.class);
        ticketPrinterService = mock(TicketPrinterService.class);
        service.hardwareService = hardwareService;
        service.ticketPersistenceService = ticketPersistenceService;
        service.valuationService = valuationService;
        service.cashSessionService = cashSessionService;
        service.ticketPrinterService = ticketPrinterService;
        service.state = state;
        service.scanHandlers = scanHandlers;
        service.defaultVatRate = new BigDecimal("0.20");
    }

    /**
     * Builds a standalone ticket line whose original unit price equals its
     * unit price (constructor default).
     *
     * @param label the display label
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @return the wired line
     */
    private TicketState.TicketItem line(String label, BigDecimal unitPrice, BigDecimal quantity) {
        return new TicketState.TicketItem(null, null, label, unitPrice, quantity, BigDecimal.ZERO);
    }

    /**
     * Builds an active product with an id and name, not forbidden to sale.
     *
     * @param name the product name
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @return the wired product
     */
    private Product product(String name, String ean, String plu) {
        Product p = new Product();
        p.id = 42L;
        p.name = name;
        p.ean = ean;
        p.plu = plu;
        p.active = true;
        p.forbiddenToSale = false;
        return p;
    }

    /**
     * Builds a current price row.
     *
     * @param incTax the price including tax
     * @param vat the VAT rate
     * @return the wired price
     */
    private Price price(String incTax, String vat) {
        Price pr = new Price();
        pr.priceIncludingTax = new BigDecimal(incTax);
        pr.vatRate = new BigDecimal(vat);
        return pr;
    }

    /**
     * Stubs an open cash session on the register.
     */
    private void openSession() {
        when(cashSessionService.getOpenSession()).thenReturn(mock(CashSession.class));
    }

    // --- processScan ---

    /**
     * {@code processScan} while locked bypasses the session gate (first
     * operand false), runs the whole handler chain (exercising every
     * {@code getPriority} branch), clears a pending transient error and skips
     * the trailing sync (locked arm of the sync guard).
     */
    @Test
    void processScanLockedRunsHandlersClearsErrorAndSkipsSync() {
        state.auth.isLocked = true;
        state.ticket.transientError = "old";
        ScanContext.ScanHandler jdkProxy = (ScanContext.ScanHandler) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScanContext.ScanHandler.class},
                new InvocationHandler() {
                    /**
                     * No-op invocation handler for the JDK proxy handler.
                     *
                     * @param proxy the proxy instance
                     * @param method the invoked method
                     * @param args the arguments
                     * @return always null
                     */
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        List<ScanContext.ScanHandler> handlers = List.of(
                new PriorityHandler(), new NoPriorityHandler(), new Marker_ClientProxy(), jdkProxy);
        when(scanHandlers.spliterator()).thenReturn(handlers.spliterator());
        service.processScan("123");
        assertNull(state.ticket.transientError);
        assertEquals(-1, state.selectedTicketIndex);
        verify(ticketPersistenceService, never()).syncDraft(state);
        verify(valuationService, never()).revalue(state);
    }

    /**
     * {@code processScan} unlocked with no open session refuses the scan: the
     * session gate returns (both gate operands true), an error is shown and
     * the handler chain never runs. A null transient error takes the
     * skip arm of the error reset.
     */
    @Test
    void processScanUnlockedNoSessionRefusesBeforeHandlers() {
        state.auth.isLocked = false;
        state.trainingMode = false;
        state.ticket.transientError = null;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.processScan("123");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        verify(scanHandlers, never()).spliterator();
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    /**
     * {@code processScan} unlocked with an open session, empty cart and no
     * draft id runs the chain but skips the sync (both sync sub-conditions
     * false).
     */
    @Test
    void processScanUnlockedSessionEmptyCartSkipsSync() {
        state.auth.isLocked = false;
        openSession();
        state.payment.ticketDbId = null;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, never()).syncDraft(state);
        verify(valuationService, never()).revalue(state);
    }

    /**
     * {@code processScan} unlocked with an open session and a non-empty cart
     * runs the chain and syncs (first sync sub-condition true).
     */
    @Test
    void processScanUnlockedSessionNonEmptyCartSyncs() {
        state.auth.isLocked = false;
        openSession();
        state.ticket.items.add(line("X", new BigDecimal("1.00"), BigDecimal.ONE));
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, times(1)).syncDraft(state);
        verify(valuationService, times(1)).revalue(state);
    }

    /**
     * {@code processScan} unlocked with an open session, empty cart but a
     * present draft id syncs (second sync sub-condition true).
     */
    @Test
    void processScanUnlockedSessionEmptyCartWithDraftIdSyncs() {
        state.auth.isLocked = false;
        openSession();
        state.payment.ticketDbId = 7L;
        when(scanHandlers.spliterator()).thenReturn(List.<ScanContext.ScanHandler>of().spliterator());
        service.processScan("123");
        verify(ticketPersistenceService, times(1)).syncDraft(state);
        verify(valuationService, times(1)).revalue(state);
    }

    // --- processWeight ---

    /**
     * {@code processWeight} does nothing while the register is locked.
     */
    @Test
    void processWeightLockedIsNoOp() {
        state.auth.isLocked = true;
        state.ticket.currentWeight = 5.0;
        service.processWeight("1,5");
        assertEquals(5.0, state.ticket.currentWeight);
    }

    /**
     * {@code processWeight} parses a comma-separated weight and records it.
     */
    @Test
    void processWeightValidRecordsWeight() {
        state.auth.isLocked = false;
        service.processWeight("2,5");
        assertEquals(2.5, state.ticket.currentWeight);
    }

    /**
     * {@code processWeight} swallows an unparsable weight (catch arm) and
     * leaves the recorded weight untouched.
     */
    @Test
    void processWeightInvalidIsSwallowed() {
        state.auth.isLocked = false;
        state.ticket.currentWeight = 3.0;
        service.processWeight("abc");
        assertEquals(3.0, state.ticket.currentWeight);
    }

    // --- requireOpenSession training arm (via addDeposit) ---

    /**
     * In training mode a mutation proceeds without any open session
     * (training arm of the session guard).
     */
    @Test
    void addDepositInTrainingModeProceedsWithoutSession() {
        state.trainingMode = true;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addDeposit(state);
        assertEquals(1, state.ticket.items.size());
        assertEquals("DECONSIGNATION", state.ticket.items.get(0).label);
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code addDeposit} refuses the line when no session is open outside
     * training (session-null non-training arm).
     */
    @Test
    void addDepositWithoutSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addDeposit(state);
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    // --- applyRemise ---

    /**
     * {@code applyRemise} returns on a null line (first guard operand).
     */
    @Test
    void applyRemiseNullItemReturns() {
        service.applyRemise(null, new BigDecimal("1.00"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyRemise} returns on a null amount (second guard operand).
     */
    @Test
    void applyRemiseNullAmountReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyRemise(item, null);
        assertNull(item.modifierType);
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyRemise} returns on a non-positive amount (third guard operand).
     */
    @Test
    void applyRemiseNonPositiveAmountReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyRemise(item, BigDecimal.ZERO);
        assertNull(item.modifierType);
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyRemise} with a zero original price (first orig operand),
     * a non-negative resulting total and a non-zero quantity divides the new
     * total back to a unit price.
     */
    @Test
    void applyRemiseZeroOriginalPositiveTotalDivides() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = BigDecimal.ZERO;
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("7.5").compareTo(item.unitPrice));
        assertEquals("REMISE", item.modifierType);
        assertEquals(0, new BigDecimal("5").compareTo(item.modifierValue));
        assertEquals("Remise -5,00€", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * {@code applyRemise} with an untouched original (second orig operand)
     * and an over-large amount floors the total to zero (negative arm).
     */
    @Test
    void applyRemiseUntouchedOriginalNegativeTotalFloorsToZero() {
        TicketState.TicketItem item = line("X", new BigDecimal("2"), BigDecimal.ONE);
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
        assertEquals("REMISE", item.modifierType);
    }

    /**
     * {@code applyRemise} on an already-modified line (both orig operands
     * false) with a zero quantity assigns the floored total directly as the
     * unit price (zero-quantity arm).
     */
    @Test
    void applyRemiseModifiedLineZeroQuantityAssignsTotal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ZERO);
        item.originalUnitPrice = new BigDecimal("99");
        service.applyRemise(item, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("99").compareTo(item.originalUnitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
    }

    // --- applyDiscount ---

    /**
     * {@code applyDiscount} returns on a null line (first guard operand).
     */
    @Test
    void applyDiscountNullItemReturns() {
        service.applyDiscount(null, new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code applyDiscount} returns on a null percent (second guard operand).
     */
    @Test
    void applyDiscountNullPercentReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, null);
        assertNull(item.modifierType);
    }

    /**
     * {@code applyDiscount} returns on a non-positive percent (third guard operand).
     */
    @Test
    void applyDiscountNonPositivePercentReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, BigDecimal.ZERO);
        assertNull(item.modifierType);
    }

    /**
     * {@code applyDiscount} returns on a percent above 100 (fourth guard operand).
     */
    @Test
    void applyDiscountPercentAbove100Returns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("150"));
        assertNull(item.modifierType);
    }

    /**
     * {@code applyDiscount} with a zero original price (first orig operand)
     * reduces the unit price by the percentage.
     */
    @Test
    void applyDiscountZeroOriginalReducesUnitPrice() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.originalUnitPrice = BigDecimal.ZERO;
        service.applyDiscount(item, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("9").compareTo(item.unitPrice));
        assertEquals("DISCOUNT", item.modifierType);
        assertEquals(0, new BigDecimal("10").compareTo(item.modifierValue));
        assertEquals("Discount -10,00%", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * {@code applyDiscount} with an untouched original (second orig operand)
     * reduces the unit price.
     */
    @Test
    void applyDiscountUntouchedOriginalReducesUnitPrice() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.applyDiscount(item, new BigDecimal("50"));
        assertEquals(0, new BigDecimal("5").compareTo(item.unitPrice));
    }

    /**
     * {@code applyDiscount} on an already-modified line (both orig operands
     * false) keeps the recorded original.
     */
    @Test
    void applyDiscountModifiedLineKeepsOriginal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.originalUnitPrice = new BigDecimal("99");
        service.applyDiscount(item, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("99").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("9").compareTo(item.unitPrice));
    }

    // --- forcePrice ---

    /**
     * {@code forcePrice} returns on a null line (first guard operand).
     */
    @Test
    void forcePriceNullItemReturns() {
        service.forcePrice(null, new BigDecimal("10"));
        verifyNoInteractions(hardwareService);
    }

    /**
     * {@code forcePrice} returns on a null new total (second guard operand).
     */
    @Test
    void forcePriceNullTotalReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.forcePrice(item, null);
        assertNull(item.modifierType);
    }

    /**
     * {@code forcePrice} returns on a negative new total (third guard operand).
     */
    @Test
    void forcePriceNegativeTotalReturns() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        service.forcePrice(item, new BigDecimal("-1"));
        assertNull(item.modifierType);
    }

    /**
     * {@code forcePrice} with a zero original price (first orig operand) and a
     * non-zero quantity divides the forced total into a unit price and records
     * the old line total.
     */
    @Test
    void forcePriceZeroOriginalNonZeroQuantityDivides() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = BigDecimal.ZERO;
        service.forcePrice(item, new BigDecimal("30"));
        assertEquals(0, new BigDecimal("10").compareTo(item.originalUnitPrice));
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
        assertEquals("FORCE_PRICE", item.modifierType);
        assertEquals(0, new BigDecimal("30").compareTo(item.modifierValue));
        assertEquals("Prix initial: 20,00€", item.modifierLabel);
        verify(hardwareService).displayMessage(anyString());
    }

    /**
     * {@code forcePrice} with an untouched original (second orig operand), a
     * zero quantity and a zero new total assigns the total directly
     * (zero-quantity arm, non-negative total).
     */
    @Test
    void forcePriceUntouchedOriginalZeroQuantityAssignsTotal() {
        TicketState.TicketItem item = line("X", new BigDecimal("5"), BigDecimal.ZERO);
        service.forcePrice(item, BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(item.unitPrice));
        assertEquals("Prix initial: 0,00€", item.modifierLabel);
    }

    /**
     * {@code forcePrice} on an already-modified line (both orig operands
     * false) computes the old total from the recorded original.
     */
    @Test
    void forcePriceModifiedLineUsesRecordedOriginal() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        item.originalUnitPrice = new BigDecimal("99");
        service.forcePrice(item, new BigDecimal("30"));
        assertEquals("Prix initial: 198,00€", item.modifierLabel);
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
    }

    // --- recalculateTotal ---

    /**
     * {@code recalculateTotal} recomputes the ticket total, bumps the version
     * and syncs the draft.
     */
    @Test
    void recalculateTotalRecomputesAndSyncs() {
        state.ticket.items.add(line("A", new BigDecimal("2.00"), new BigDecimal("3")));
        long before = state.version;
        service.recalculateTotal(state);
        assertEquals(0, new BigDecimal("6.00").compareTo(state.ticket.totalAmount));
        assertTrue(state.version > before);
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    // --- addItemByEan ---

    /**
     * {@code addItemByEan} returns on a null EAN (first guard operand).
     */
    @Test
    void addItemByEanNullEanReturns() {
        service.addItemByEan(state, null, BigDecimal.ONE);
        assertTrue(state.ticket.items.isEmpty());
        verifyNoInteractions(cashSessionService);
    }

    /**
     * {@code addItemByEan} returns on an empty EAN (second guard operand).
     */
    @Test
    void addItemByEanEmptyEanReturns() {
        service.addItemByEan(state, "", BigDecimal.ONE);
        assertTrue(state.ticket.items.isEmpty());
        verifyNoInteractions(cashSessionService);
    }

    /**
     * {@code addItemByEan} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addItemByEanNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addItemByEan(state, "123", BigDecimal.ONE);
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} shows an error when the product is not found and
     * clears a pending transient error (non-null reset arm).
     */
    @Test
    void addItemByEanProductNotFound() {
        openSession();
        state.ticket.transientError = "old";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertEquals("PRODUIT INTROUVABLE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} refuses a product forbidden to sale (forbidden arm).
     */
    @Test
    void addItemByEanForbiddenProduct() {
        openSession();
        Product p = product("BEER", "123", null);
        p.forbiddenToSale = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertEquals("PRODUIT INTERDIT À LA VENTE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code addItemByEan} with a current price adds a line at that price and
     * VAT rate (price non-null arm), takes the null transient-error skip arm
     * and syncs.
     */
    @Test
    void addItemByEanWithPriceAddsLine() {
        openSession();
        state.ticket.transientError = null;
        Product p = product("MILK", "123", null);
        Price pr = price("1.50", "0.055");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByEan(state, "123", new BigDecimal("2"));
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("MILK", added.label);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.055").compareTo(added.vatRate));
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    /**
     * {@code addItemByEan} without a current price falls back to a zero price
     * and the default VAT rate (price null arm).
     */
    @Test
    void addItemByEanWithoutPriceUsesDefaults() {
        openSession();
        Product p = product("MILK", "123", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    // --- addItemByPlu ---

    /**
     * {@code addItemByPlu} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addItemByPluNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addItemByPlu(state, "1234");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} shows an error when the PLU resolves to nothing
     * (product-null arm) and clears a pending transient error.
     */
    @Test
    void addItemByPluNotFound() {
        openSession();
        state.ticket.transientError = "old";
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("PLU INTROUVABLE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} refuses a product forbidden to sale (forbidden arm).
     */
    @Test
    void addItemByPluForbiddenProduct() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.forbiddenToSale = true;
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("PRODUIT INTERDIT À LA VENTE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} refuses a non-positive weighing (weight-guard arm).
     */
    @Test
    void addItemByPluInvalidWeight() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        when(hardwareService.requestWeighing()).thenReturn(0.0);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("POIDS INVALIDE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} rejects a weighing identical to the last recorded
     * one (not-NaN and equal arm).
     */
    @Test
    void addItemByPluDuplicateWeight() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        state.ticket.lastRecordedWeight = 1.5;
        when(hardwareService.requestWeighing()).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertEquals("ERREUR POIDS IDENTIQUE", state.ticket.transientError);
    }

    /**
     * {@code addItemByPlu} adds a weighed line with a current price when the
     * last weight differs (not-NaN and not-equal arm, price non-null arm),
     * clears a null transient error and syncs.
     */
    @Test
    void addItemByPluAddsWeighedLineWithPrice() {
        openSession();
        state.ticket.transientError = null;
        state.ticket.lastRecordedWeight = 2.0;
        Product p = product("APPLE", "111", "1234");
        Price pr = price("3.00", "0.055");
        when(hardwareService.requestWeighing()).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByPlu(state, "1234");
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("APPLE", added.label);
        assertEquals("1234", added.plu);
        assertEquals(0, new BigDecimal("1.500").compareTo(added.quantity));
        assertEquals(0, new BigDecimal("3.00").compareTo(added.unitPrice));
        assertEquals(1.5, state.ticket.lastRecordedWeight);
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code addItemByPlu} with the default NaN last weight (NaN short-circuit
     * arm) and no current price falls back to zero price and the default VAT
     * rate (price null arm).
     */
    @Test
    void addItemByPluFirstWeighingWithoutPriceUsesDefaults() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        when(hardwareService.requestWeighing()).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(added.unitPrice));
        assertEquals(0, new BigDecimal("0.20").compareTo(added.vatRate));
    }

    // --- addUnknownItem ---

    /**
     * {@code addUnknownItem} refuses when no session is open (session-guard
     * false arm).
     */
    @Test
    void addUnknownItemNoSessionRefuses() {
        state.trainingMode = false;
        when(cashSessionService.getOpenSession()).thenReturn(null);
        service.addUnknownItem(state, "LABEL", "1.00");
        assertEquals("AUCUNE SESSION OUVERTE - MENU CAISSE", state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds a line when price, label and non-empty label
     * all hold (all-true arm), clearing a pending transient error.
     */
    @Test
    void addUnknownItemAddsLine() {
        openSession();
        state.ticket.transientError = "old";
        service.addUnknownItem(state, "candy", "1,50");
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("CANDY", added.label);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertNull(state.ticket.transientError);
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code addUnknownItem} adds nothing on a negative price (first inner
     * operand false) without raising an error.
     */
    @Test
    void addUnknownItemNegativePriceAddsNothing() {
        openSession();
        service.addUnknownItem(state, "candy", "-1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds nothing on a null label (second inner
     * operand false).
     */
    @Test
    void addUnknownItemNullLabelAddsNothing() {
        openSession();
        service.addUnknownItem(state, null, "1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} adds nothing on an empty label (third inner
     * operand false).
     */
    @Test
    void addUnknownItemEmptyLabelAddsNothing() {
        openSession();
        service.addUnknownItem(state, "", "1.00");
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * {@code addUnknownItem} shows an error on an unparsable price (catch arm).
     */
    @Test
    void addUnknownItemInvalidPrice() {
        openSession();
        service.addUnknownItem(state, "candy", "abc");
        assertEquals("ERREUR PRIX SAISI", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    // --- cancelItemById ---

    /**
     * {@code cancelItemById} removes a line, leaving the remaining last line
     * on the customer display (non-empty display arm).
     */
    @Test
    void cancelItemByIdShowsRemainingLastItem() {
        TicketState.TicketItem first = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        TicketState.TicketItem second = line("B", new BigDecimal("2.00"), BigDecimal.ONE);
        state.ticket.items.add(first);
        state.ticket.items.add(second);
        service.cancelItemById(state, second.uid);
        assertEquals(1, state.ticket.items.size());
        assertEquals(-1, state.selectedTicketIndex);
        verify(hardwareService).displayMessage(anyString());
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * {@code cancelItemById} removing the last remaining line shows the
     * welcome message (empty display arm).
     */
    @Test
    void cancelItemByIdEmptyShowsWelcome() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        service.cancelItemById(state, only.uid);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    // --- cancelTicket ---

    /**
     * {@code cancelTicket} cancels the draft when a draft id is present
     * (non-null arm) then clears the ticket.
     */
    @Test
    void cancelTicketWithDraftCancelsDraft() {
        state.payment.ticketDbId = 9L;
        state.ticket.items.add(line("A", new BigDecimal("1.00"), BigDecimal.ONE));
        service.cancelTicket(state);
        verify(ticketPersistenceService).cancelDraft(9L);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    /**
     * {@code cancelTicket} without a draft id skips the draft cancellation
     * (null arm) and still clears the ticket.
     */
    @Test
    void cancelTicketWithoutDraftSkipsCancel() {
        state.payment.ticketDbId = null;
        state.ticket.items.add(line("A", new BigDecimal("1.00"), BigDecimal.ONE));
        service.cancelTicket(state);
        verify(ticketPersistenceService, never()).cancelDraft(anyLong());
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    // --- reprintTicket ---

    /**
     * {@code reprintTicket} prints when the id is present (non-null arm).
     */
    @Test
    void reprintTicketWithIdPrints() {
        service.reprintTicket(5L);
        verify(ticketPrinterService).printTicket(5L);
    }

    /**
     * {@code reprintTicket} does nothing on a null id (null arm).
     */
    @Test
    void reprintTicketNullIdDoesNothing() {
        service.reprintTicket(null);
        verifyNoInteractions(ticketPrinterService);
    }

    // --- displayItem null arm (unreachable through the public API) ---

    /**
     * {@code displayItem(null)} shows the welcome message — the null arm is
     * unreachable through the public API and is covered reflectively.
     *
     * @throws Exception if the reflective invocation fails
     */
    @Test
    void displayItemNullShowsWelcome() throws Exception {
        Method m = TicketService.class.getDeclaredMethod("displayItem", TicketState.TicketItem.class);
        m.setAccessible(true);
        m.invoke(service, new Object[]{null});
        verify(hardwareService).displayMessage("INTERMARCHE");
    }

    // --- test scan handlers exercising getPriority ---

    /**
     * A scan handler carrying an explicit {@link Priority} annotation.
     */
    @Priority(5)
    static class PriorityHandler implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }

    /**
     * A scan handler without any {@link Priority} annotation (default 100).
     */
    static class NoPriorityHandler implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }

    /**
     * A scan handler whose class name contains {@code _ClientProxy} so the
     * CDI-proxy unwrap branch (second operand) is taken.
     */
    static class Marker_ClientProxy implements ScanContext.ScanHandler {
        /**
         * No-op handler.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
        }
    }
}
