package com.intermarche.pos.ui.ticket;

import com.intermarche.pos.ui.PriceModType;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Price;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.service.CashSessionService;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import com.intermarche.pos.ui.valuation.ValuationService;
import com.intermarche.pos.domain.ticket.Ticket;
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
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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

    /** Journal of the age-check decisions (confirmed / refused). */
    private com.intermarche.pos.service.TechnicalEventService technicalEventService;

    /** Mocked register identity — whose last sale to recover. */
    private com.intermarche.pos.service.TicketNumberService ticketNumberService;

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
        // Back-office parameters: caps at their catalog defaults, so every
        // historical assertion (the 100 % ceiling included) is unchanged.
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(service.posSettingsService.lineMaxDiscountPercent()).thenReturn(100);
        when(service.posSettingsService.globalMaxDiscountPercent()).thenReturn(100);
        // Original price shown at its catalog default (BO-10-07-12), so the
        // historical "Prix initial" assertions on forcePrice hold.
        when(service.posSettingsService.priceShowOriginalOnForce()).thenReturn(true);
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
        // LC-04-04-12: cancelling a draft asks the abandon rules whether a ticket is
        // printed. The stand-in answers no, which is the shop that prints none — the
        // default of every case below.
        service.ticketAbandonService = mock(TicketAbandonService.class);
        service.state = state;
        technicalEventService = mock(com.intermarche.pos.service.TechnicalEventService.class);
        service.technicalEventService = technicalEventService;
        ticketNumberService = mock(com.intermarche.pos.service.TicketNumberService.class);
        service.ticketNumberService = ticketNumberService;
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
     * BO-02-03-09: {@code applyRemise} refuses a discount-forbidden line — the
     * line is untouched, exactly as for a money product.
     */
    @Test
    void applyRemiseRefusesDiscountForbiddenLine() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.discountForbidden = true;
        service.applyRemise(item, new BigDecimal("1.00"));
        assertNull(item.modifierType);
        assertEquals(new BigDecimal("10"), item.unitPrice);
        verifyNoInteractions(hardwareService);
    }

    /**
     * BO-02-03-09: {@code applyDiscount} refuses a discount-forbidden line — the
     * unit price is untouched.
     */
    @Test
    void applyDiscountRefusesDiscountForbiddenLine() {
        TicketState.TicketItem item = line("X", new BigDecimal("10"), BigDecimal.ONE);
        item.discountForbidden = true;
        service.applyDiscount(item, new BigDecimal("20"));
        assertNull(item.modifierType);
        assertEquals(new BigDecimal("10"), item.unitPrice);
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
        assertEquals(PriceModType.REMISE, item.modifierType);
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
        assertEquals(PriceModType.REMISE, item.modifierType);
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
        assertEquals(PriceModType.DISCOUNT, item.modifierType);
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
        assertEquals(PriceModType.FORCE_PRICE, item.modifierType);
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
     * {@code forcePrice} masks the original price when the back office
     * deactivated its display (BO-10-07-12, false arm of the ternary): the line
     * carries no "Prix initial" label, yet it still forces the price and is
     * marked as a FORCE_PRICE modification.
     */
    @Test
    void forcePriceMasksOriginalWhenAdministeredHidden() {
        when(service.posSettingsService.priceShowOriginalOnForce()).thenReturn(false);
        TicketState.TicketItem item = line("X", new BigDecimal("10"), new BigDecimal("2"));
        service.forcePrice(item, new BigDecimal("30"));
        assertNull(item.modifierLabel);
        assertEquals(PriceModType.FORCE_PRICE, item.modifierType);
        assertEquals(0, new BigDecimal("15").compareTo(item.unitPrice));
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
            // BO-02-03-04: the EAN miss falls back to the internal-code lookup,
            // which also misses here — both empty yields PRODUIT INTROUVABLE.
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "123")).thenReturn(query);
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

    /**
     * BO-02-03-04: {@code addItemByEan} falls back to the internal-code lookup
     * when the EAN misses, and rings the line under the product's own EAN.
     */
    @Test
    void addItemByEanResolvesByInternalCodeWhenEanMisses() {
        openSession();
        Product p = product("MILK", "3760001", null);
        p.internalCode = "INT-42";
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> miss = mock(PanacheQuery.class);
            when(miss.firstResult()).thenReturn(null);
            panache.when(() -> Product.find("ean = ?1 and active = true", "INT-42")).thenReturn(miss);
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> hit = mock(PanacheQuery.class);
            when(hit.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("internalCode = ?1 and active = true", "INT-42")).thenReturn(hit);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "INT-42", BigDecimal.ONE);
        }
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals("MILK", added.label);
        assertEquals("3760001", added.ean);
    }

    /**
     * BO-02-03-26/27: {@code addItemByEan} on a VAT-exempt article rings the
     * line at VAT rate 0 while keeping the catalog price (amount due unchanged).
     */
    @Test
    void addItemByEanVatExemptVentilatesAtZero() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        Price pr = price("1.50", "0.055");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(pr);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("1.50").compareTo(added.unitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(added.vatRate));
    }

    /**
     * BO-02-03-11: {@code addItemByEan} refuses a recalled article with the
     * recall error and adds no line.
     */
    @Test
    void addItemByEanRefusesRecalledArticle() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.RECALL, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("ARTICLE EN RETRAIT/RAPPEL", state.ticket.transientError);
    }

    /**
     * BO-02-03-09: {@code addItemByEan} snapshots the discount ban onto the
     * added line so later price gestures refuse it.
     */
    @Test
    void addItemByEanSnapshotsDiscountForbidden() {
        openSession();
        Product p = product("MILK", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertTrue(state.ticket.items.get(0).discountForbidden);
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

    /**
     * BO-02-03-11: {@code addItemByPlu} refuses a recalled article with the
     * recall error (true arm of the PLU recall guard) and — the check sitting
     * BEFORE the scale read — never weighs and adds no line.
     */
    @Test
    void addItemByPluRefusesRecalledArticle() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.RECALL, "true");
        try (MockedStatic<Product> productStatic = mockStatic(Product.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            service.addItemByPlu(state, "1234");
        }
        assertTrue(state.ticket.items.isEmpty());
        assertEquals("ARTICLE EN RETRAIT/RAPPEL", state.ticket.transientError);
        verify(hardwareService, never()).requestWeighing();
    }

    /**
     * BO-02-03-26/27: {@code addItemByPlu} on a VAT-exempt weighed article rings
     * the line at VAT rate 0 (true arm of the PLU vat-exempt guard) while keeping
     * the catalog price.
     */
    @Test
    void addItemByPluVatExemptVentilatesAtZero() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.VAT_EXEMPT, "true");
        when(hardwareService.requestWeighing()).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("3.00", "0.055"));
            service.addItemByPlu(state, "1234");
        }
        TicketState.TicketItem added = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("3.00").compareTo(added.unitPrice));
        assertEquals(0, BigDecimal.ZERO.compareTo(added.vatRate));
    }

    /**
     * BO-02-03-09: {@code addItemByPlu} snapshots the discount ban onto the
     * added weighed line (true arm of the PLU discount-forbidden guard) so later
     * price gestures refuse it.
     */
    @Test
    void addItemByPluSnapshotsDiscountForbidden() {
        openSession();
        Product p = product("APPLE", "111", "1234");
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog.DISCOUNT_FORBIDDEN, "true");
        when(hardwareService.requestWeighing()).thenReturn(1.5);
        try (MockedStatic<Product> productStatic = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            productStatic.when(() -> Product.findActiveByPlu("1234")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            service.addItemByPlu(state, "1234");
        }
        assertTrue(state.ticket.items.get(0).discountForbidden);
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

    // --- applyGlobalDiscount ---

    /**
     * A payment in progress REFUSES the whole-ticket gesture: the totals are
     * already committed to the payment screen, so moving them under the
     * cashier's feet is forbidden (first guard true).
     */
    @Test
    void applyGlobalDiscountRefusedDuringPayment() {
        state.payment.paymentInProgress = true;
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("10"));
        assertEquals("TERMINEZ OU ANNULEZ LE TICKET D'ABORD", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A NULL value is invalid (second guard, first leg).
     */
    @Test
    void applyGlobalDiscountRejectsNullValue() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, null);
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A NEGATIVE value is invalid (second guard, second leg): a gesture may
     * lower a ticket, never inflate it.
     */
    @Test
    void applyGlobalDiscountRejectsNegativeValue() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("-1"));
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * A percentage ABOVE 100 is invalid (second guard, third leg): the guard
     * is percent-only — the same figure in euros is a legitimate request that
     * the allocation will cap at the base.
     */
    @Test
    void applyGlobalDiscountRejectsPercentAbove100() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("101"));
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertNull(state.ticket.globalDiscountType);
    }

    /**
     * Exactly 100 % is ACCEPTED (third leg false at the boundary): giving the
     * whole ticket away is a legitimate, endorsed gesture.
     */
    @Test
    void applyGlobalDiscountAcceptsExactly100Percent() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("100"));
        assertEquals("PERCENT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("100").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * A euro amount ABOVE 100 is accepted (the percent-only leg does not fire
     * on GLOBAL_REMISE): the allocation caps it at the ticket base.
     */
    @Test
    void applyGlobalDiscountAcceptsEurosAbove100() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("150"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("150").compareTo(state.ticket.globalDiscountValue));
    }

    /**
     * ZERO is accepted and ERASES the request through the state setter (the
     * guard is {@code < 0}, not {@code <= 0}): typing 0 is how a cashier
     * cancels the gesture.
     */
    @Test
    void applyGlobalDiscountZeroErasesRequest() {
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, new BigDecimal("10"));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_DISCOUNT, BigDecimal.ZERO);
        assertNull(state.ticket.globalDiscountType);
        assertNull(state.ticket.globalDiscountValue);
    }

    /**
     * The type is mapped to the STATE's vocabulary — GLOBAL_DISCOUNT becomes
     * PERCENT, anything else becomes AMOUNT — and the totals are recomputed
     * so the allocation happens immediately.
     */
    @Test
    void applyGlobalDiscountMapsTypeAndRecomputes() {
        state.ticket.items.add(line("MILK", new BigDecimal("10.00"), BigDecimal.ONE));
        service.applyGlobalDiscount(state, PriceModType.GLOBAL_REMISE, new BigDecimal("2.00"));
        assertEquals("AMOUNT", state.ticket.globalDiscountType);
        assertEquals(0, new BigDecimal("2.00").compareTo(state.ticket.globalDiscountApplied));
        assertEquals(0, new BigDecimal("8.00").compareTo(state.ticket.totalAmount));
    }

    // --- suspendForAgeCheck ---

    /**
     * An UNRESTRICTED product never parks the scan (first leg true).
     */
    @Test
    void suspendForAgeCheckPassesUnrestrictedProduct() {
        Product p = product("MILK", "123", null);
        p.ageRestriction = null;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
        verifyNoInteractions(hardwareService);
    }

    /**
     * A restriction ALREADY covered by this ticket's verified threshold lets
     * the scan through (second leg true, {@code <} case): the ID is checked
     * once per ticket, not once per bottle.
     */
    @Test
    void suspendForAgeCheckPassesWhenThresholdAlreadyCleared() {
        Product p = product("WINE", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 21;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
    }

    /**
     * An EQUAL threshold also lets the scan through (second leg true,
     * {@code ==} boundary): the guard is {@code <=}.
     */
    @Test
    void suspendForAgeCheckPassesOnEqualThreshold() {
        Product p = product("WINE", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 18;
        assertFalse(service.suspendForAgeCheck(state, p, "SCAN", "123", null));
        assertFalse(state.ageCheck.active);
    }

    /**
     * A HIGHER restriction parks the gesture with everything needed to replay
     * it (both legs false): kind, code, quantity, label and threshold — the
     * customer display is warned and the polling woken.
     */
    @Test
    void suspendForAgeCheckParksTheGesture() {
        Product p = product("vin rouge", "123", null);
        p.ageRestriction = 18;
        state.ticket.ageVerifiedThreshold = 0;
        long version = state.version;
        assertTrue(service.suspendForAgeCheck(state, p, "EAN_QTY", "123", new BigDecimal("2")));
        assertTrue(state.ageCheck.active);
        assertEquals("VIN ROUGE", state.ageCheck.productLabel);
        assertEquals(18, state.ageCheck.threshold);
        assertEquals("EAN_QTY", state.ageCheck.kind);
        assertEquals("123", state.ageCheck.code);
        assertEquals(0, new BigDecimal("2").compareTo(state.ageCheck.quantity));
        verify(hardwareService).displayMessage("CONTROLE D'AGE EN COURS");
        assertTrue(state.version > version);
    }

    // --- confirmAgeCheck / refuseAgeCheck ---

    /**
     * Confirming with NO pending prompt is a silent no-op (guard true).
     */
    @Test
    void confirmAgeCheckWithoutPendingIsNoOp() {
        service.confirmAgeCheck(state);
        verifyNoInteractions(technicalEventService);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * Confirming RAISES the ticket's verified threshold, journals the check
     * and REPLAYS the parked gesture by kind — here the quantity add, whose
     * EAN and quantity come back verbatim from the parked state.
     */
    @Test
    void confirmAgeCheckRaisesThresholdJournalsAndReplaysEanQty() {
        openSession();
        state.auth.operatorName = "Marie";
        state.ticket.ageVerifiedThreshold = 0;
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "EAN_QTY";
        state.ageCheck.code = "123";
        state.ageCheck.quantity = new BigDecimal("2");
        Product p = product("VIN ROUGE", "123", null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("5.00", "0.20"));
            service.confirmAgeCheck(state);
        }
        assertEquals(18, state.ticket.ageVerifiedThreshold);
        assertFalse(state.ageCheck.active);
        assertNull(state.ageCheck.code);
        assertEquals(1, state.ticket.items.size());
        assertEquals(0, new BigDecimal("2").compareTo(state.ticket.items.get(0).quantity));
        verify(technicalEventService).log(
                eq(TechnicalEvent.EventType.AGE_CHECK_CONFIRMED), anyString());
    }

    /**
     * The verified threshold only ever GROWS: confirming an 18+ check on a
     * ticket already cleared for 21 keeps 21 ({@code Math.max}).
     */
    @Test
    void confirmAgeCheckNeverLowersTheVerifiedThreshold() {
        openSession();
        state.auth.operatorName = "Marie";
        state.ticket.ageVerifiedThreshold = 21;
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "PLU";
        state.ageCheck.code = "99";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu("99")).thenReturn(null);
            service.confirmAgeCheck(state);
        }
        assertEquals(21, state.ticket.ageVerifiedThreshold);
    }

    /**
     * Refusing with NO pending prompt is a silent no-op (guard true).
     */
    @Test
    void refuseAgeCheckWithoutPendingIsNoOp() {
        service.refuseAgeCheck(state);
        verifyNoInteractions(technicalEventService);
        assertNull(state.ticket.transientError);
    }

    /**
     * Refusing JOURNALS the refusal, clears the parked gesture and shows the
     * cashier message — and it does NOT raise the verified threshold, so the
     * next restricted scan will ask again.
     */
    @Test
    void refuseAgeCheckJournalsClearsAndWarns() {
        state.auth.operatorName = "Marie";
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "SCAN";
        state.ageCheck.code = "123";
        service.refuseAgeCheck(state);
        verify(technicalEventService).log(
                eq(TechnicalEvent.EventType.AGE_CHECK_REFUSED), anyString());
        assertFalse(state.ageCheck.active);
        assertNull(state.ageCheck.code);
        assertEquals(0, state.ticket.ageVerifiedThreshold);
        assertEquals("VENTE REFUSÉE - CONTRÔLE D'ÂGE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    // --- money product flag ---

    /**
     * A GIFT CARD added by EAN is flagged as a money product: the line
     * carries VALUE, not goods — the flag is what later excludes it from
     * discounts, gestures and valuation, and forbids its refund.
     */
    @Test
    void addItemByEanFlagsGiftCardAsMoneyProduct() {
        openSession();
        Product p = product("CARTE CADEAU 25", "3400025000001", null);
        p.giftCardAmount = new BigDecimal("25.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "3400025000001"))
                    .thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong()))
                    .thenReturn(price("25.00", "0.00"));
            service.addItemByEan(state, "3400025000001", BigDecimal.ONE);
        }
        assertEquals(1, state.ticket.items.size());
        assertTrue(state.ticket.items.get(0).moneyProduct);
    }

    /**
     * An ordinary product is NOT flagged (null giftCardAmount arm).
     */
    @Test
    void addItemByEanLeavesOrdinaryProductUnflagged() {
        openSession();
        Product p = product("MILK", "123", null);
        p.giftCardAmount = null;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "123")).thenReturn(query);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("1.50", "0.055"));
            service.addItemByEan(state, "123", BigDecimal.ONE);
        }
        assertFalse(state.ticket.items.get(0).moneyProduct);
    }

    /**
     * Confirming a SCAN-kind gesture replays it through the recognition
     * CHAIN ({@code default} arm of the replay switch), not through a direct
     * add: the confirmed code re-enters where it came from, so every handler
     * priority applies again exactly as on the first pass.
     */
    @Test
    void confirmAgeCheckReplaysScanThroughTheChain() {
        state.auth.operatorName = "Marie";
        state.ageCheck.active = true;
        state.ageCheck.productLabel = "VIN ROUGE";
        state.ageCheck.threshold = 18;
        state.ageCheck.kind = "SCAN";
        state.ageCheck.code = "3400018000001";
        RecordingHandler handler = new RecordingHandler();
        when(scanHandlers.spliterator())
                .thenReturn(List.<ScanContext.ScanHandler>of(handler).spliterator());
        service.confirmAgeCheck(state);
        assertEquals("3400018000001", handler.seenCode);
        assertEquals(18, state.ticket.ageVerifiedThreshold);
        assertFalse(state.ageCheck.active);
    }

    /**
     * {@code addItemByEan} on an age-restricted product PARKS the gesture and
     * adds nothing (true arm of the suspend call site): the line only appears
     * after the ID check is confirmed.
     */
    @Test
    void addItemByEanParksRestrictedProduct() {
        openSession();
        Product p = product("VIN ROUGE", "3400018000001", null);
        p.ageRestriction = 18;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Product> query = mock(PanacheQuery.class);
            when(query.firstResult()).thenReturn(p);
            panache.when(() -> Product.find("ean = ?1 and active = true", "3400018000001"))
                    .thenReturn(query);
            service.addItemByEan(state, "3400018000001", new BigDecimal("2"));
        }
        assertTrue(state.ticket.items.isEmpty());
        assertTrue(state.ageCheck.active);
        assertEquals("EAN_QTY", state.ageCheck.kind);
        assertEquals(0, new BigDecimal("2").compareTo(state.ageCheck.quantity));
        verify(ticketPersistenceService, never()).syncDraft(state);
    }

    /**
     * {@code addItemByPlu} on an age-restricted product PARKS the gesture and
     * — the point of placing the gate BEFORE the scale read — NEVER WEIGHS:
     * the balance is consumed on the replay only, so a confirmed check
     * weighs exactly once.
     */
    @Test
    void addItemByPluParksRestrictedProductWithoutWeighing() {
        openSession();
        Product p = product("VIN ROUGE", null, "99");
        p.ageRestriction = 18;
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByPlu("99")).thenReturn(p);
            service.addItemByPlu(state, "99");
        }
        assertTrue(state.ageCheck.active);
        assertEquals("PLU", state.ageCheck.kind);
        assertEquals("99", state.ageCheck.code);
        assertNull(state.ageCheck.quantity);
        assertTrue(state.ticket.items.isEmpty());
        verify(hardwareService, never()).requestWeighing();
    }

    // --- money products are never discounted ---

    /**
     * {@code applyRemise} REFUSES a money product: a gift card is value, not
     * goods — discounting it would mint money (money-product guard, true arm).
     */
    @Test
    void applyRemiseRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.applyRemise(card, new BigDecimal("5.00"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
    }

    /**
     * {@code applyDiscount} REFUSES a money product (same guard, percentage
     * flavour).
     */
    @Test
    void applyDiscountRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.applyDiscount(card, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
    }

    /**
     * {@code forcePrice} REFUSES a money product: forcing the price of an
     * instrument would break the equality between its face value and the
     * amount loaded on it.
     */
    @Test
    void forcePriceRefusesMoneyProduct() {
        TicketState.TicketItem card = line("CARTE CADEAU 25", new BigDecimal("25.00"), BigDecimal.ONE);
        card.moneyProduct = true;
        service.forcePrice(card, new BigDecimal("1.00"));
        assertEquals(0, new BigDecimal("25.00").compareTo(card.unitPrice));
        assertNull(card.modifierLabel);
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

    /**
     * {@code cancelItemById} conserves the cancelled article (lot C4,
     * BO-04-01-16): with a persisted draft (non-null id arm), it marks the draft
     * line with the operator badge BEFORE removing it from the cart, so the
     * reconciliation keeps it as a witness.
     */
    @Test
    void cancelItemByIdMarksTheDraftLineWhenDraftExists() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        state.payment.ticketDbId = 5L;
        state.auth.operatorBadgeId = "12341234";
        service.cancelItemById(state, only.uid);
        verify(ticketPersistenceService).markLineCancelled(5L, only.uid, "12341234");
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * {@code cancelItemById} skips the marking when there is no draft yet
     * (null id arm, e.g. training or pre-first-article), and never touches the
     * persistence witness.
     */
    @Test
    void cancelItemByIdSkipsMarkingWithoutDraft() {
        TicketState.TicketItem only = line("A", new BigDecimal("1.00"), BigDecimal.ONE);
        state.ticket.items.add(only);
        state.payment.ticketDbId = null;
        service.cancelItemById(state, only.uid);
        verify(ticketPersistenceService, never()).markLineCancelled(anyLong(), anyString(), anyString());
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

    // --- printTicketIdentityBarcode (LC-08-01-04) ---

    /**
     * {@code printTicketIdentityBarcode} prints when the id is present (non-null arm).
     */
    @Test
    void printTicketIdentityBarcodeWithIdPrints() {
        service.printTicketIdentityBarcode(5L);
        verify(ticketPrinterService).printTicketIdentityBarcode(5L);
    }

    /**
     * {@code printTicketIdentityBarcode} does nothing on a null id (null arm).
     */
    @Test
    void printTicketIdentityBarcodeNullIdDoesNothing() {
        service.printTicketIdentityBarcode(null);
        verifyNoInteractions(ticketPrinterService);
    }

    // --- resolveLastClosedTicketId: the DERNIER keys survive a restart ---

    /**
     * The id already in memory is returned untouched and the database is never
     * asked (in-memory arm).
     */
    @Test
    void resolveLastClosedTicketIdKeepsTheOneInMemory() {
        PosState state = new PosState();
        state.lastClosedTicketId = 42L;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            assertEquals(42L, service.resolveLastClosedTicketId(state));
            panache.verifyNoInteractions();
        }
    }

    /**
     * With nothing in memory — a register that restarted — the last CLOSED
     * ticket of this terminal is recovered from the database and remembered
     * (recovery arm).
     */
    @Test
    void resolveLastClosedTicketIdRecoversFromTheDatabase() {
        PosState state = new PosState();
        state.lastClosedTicketId = null;
        when(ticketNumberService.getTerminalId()).thenReturn("C04");
        Ticket closed = new Ticket();
        closed.id = 77L;
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class)) {
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(closed);
            assertEquals(77L, service.resolveLastClosedTicketId(state));
        }
        assertEquals(77L, state.lastClosedTicketId);
    }

    /**
     * A register that has closed no sale at all still answers null, and stores
     * nothing — the guard then says AUCUN TICKET, which is the truth
     * (nothing-found arm).
     */
    @Test
    void resolveLastClosedTicketIdStaysNullWhenNothingWasEverClosed() {
        PosState state = new PosState();
        state.lastClosedTicketId = null;
        when(ticketNumberService.getTerminalId()).thenReturn("C04");
        try (MockedStatic<Ticket> tickets = mockStatic(Ticket.class)) {
            tickets.when(() -> Ticket.findLastClosedByTerminal("C04")).thenReturn(null);
            assertNull(service.resolveLastClosedTicketId(state));
        }
        assertNull(state.lastClosedTicketId);
    }

    // --- printCardReceiptDuplicate (LC-08-05-09) ---

    /**
     * {@code printCardReceiptDuplicate} prints the slip with the DUPLICATA mention
     * when the id is present (non-null arm).
     */
    @Test
    void printCardReceiptDuplicateWithIdPrints() {
        when(ticketPrinterService.printCardReceipt(5L, false, "DUPLICATA")).thenReturn(1);
        assertEquals(1, service.printCardReceiptDuplicate(5L));
        verify(ticketPrinterService).printCardReceipt(5L, false, "DUPLICATA");
    }

    /**
     * {@code printCardReceiptDuplicate} passes the printer's count back, zero
     * included: a sale settled without a card prints nothing, and the caller is
     * what turns that into a message (printed-zero arm).
     */
    @Test
    void printCardReceiptDuplicateReportsNothingPrinted() {
        when(ticketPrinterService.printCardReceipt(5L, false, "DUPLICATA")).thenReturn(0);
        assertEquals(0, service.printCardReceiptDuplicate(5L));
    }

    /**
     * {@code printCardReceiptDuplicate} does nothing on a null id and reports
     * zero (null arm).
     */
    @Test
    void printCardReceiptDuplicateNullIdDoesNothing() {
        assertEquals(0, service.printCardReceiptDuplicate(null));
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
     * A chain handler that records the code it was handed, to prove a
     * confirmed SCAN gesture really re-enters the recognition chain.
     */
    static class RecordingHandler implements ScanContext.ScanHandler {

        /** The code seen by the chain, or null if never called. */
        String seenCode;

        /**
         * Records the scanned code and lets the chain continue.
         *
         * @param context the scan context
         */
        @Override
        public void handle(ScanContext context) {
            seenCode = context.code;
        }
    }

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

    // ------------------------------------------------- Entry prompt (LC-02-03)

    /**
     * An article the referential prices and measures needs no prompt: the add goes
     * straight through — the arm where both attributes are absent.
     */
    @Test
    void suspendForEntryLetsAnOrdinaryArticleThrough() {
        Product p = product("MILK", "123", null);
        assertFalse(service.suspendForEntry(state, p, null));
        assertFalse(state.entryPrompt.active);
    }

    /**
     * An article whose quantity must be keyed parks the add on the QUANTITY prompt,
     * carrying its unit of measure and its price per unit ({@code LC-02-03-01/02}).
     */
    @Test
    void suspendForEntryAsksForADecimalQuantity() {
        Product p = product("CABLE", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .QUANTITY_TO_ENTER, "true");
        p.unitName = "m";
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertTrue(state.entryPrompt.active);
        assertEquals(PosState.EntryPromptState.QUANTITY, state.entryPrompt.kind);
        assertEquals("123", state.entryPrompt.ean);
        assertEquals("CABLE", state.entryPrompt.label);
        assertEquals("m", state.entryPrompt.unitName);
        assertEquals("4,99", state.entryPrompt.unitPriceFormatted);
        assertEquals(0, BigDecimal.ONE.compareTo(state.entryPrompt.quantity));
    }

    /**
     * An article whose PRICE must be keyed parks on the PRICE prompt straight away —
     * the arm where the quantity is known and only the price is not
     * ({@code LC-02-03-03}).
     */
    @Test
    void suspendForEntryAsksForAPriceWhenOnlyThePriceIsMissing() {
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            assertTrue(service.suspendForEntry(state, p, null));
        }
        assertEquals(PosState.EntryPromptState.PRICE, state.entryPrompt.kind);
        assertEquals("", state.entryPrompt.unitPriceFormatted);
        assertEquals("", state.entryPrompt.unitName);
    }

    /**
     * A quantity already keyed or armed is carried INTO the prompt, so an article that
     * also wants its price is rung at the quantity the operator gave and not at one
     * ({@code LC-02-13-08}).
     */
    @Test
    void suspendForEntryCarriesTheQuantityTheOperatorAlreadyGave() {
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
            assertTrue(service.suspendForEntry(state, p, new BigDecimal("3")));
        }
        assertEquals(0, new BigDecimal("3").compareTo(state.entryPrompt.quantity));
    }

    /**
     * {@code confirmEntry} on a closed prompt does nothing: a stale page posting a
     * figure must not ring a line nobody asked for.
     */
    @Test
    void confirmEntryDoesNothingWhenNoPromptIsOpen() {
        service.confirmEntry(state, new BigDecimal("2"));
        assertTrue(state.ticket.items.isEmpty());
        assertNull(state.ticket.transientError);
    }

    /**
     * A figure that is not a figure is refused and the prompt stays open, on both arms
     * that can produce one: nothing keyed, and zero or less.
     */
    @Test
    void confirmEntryRefusesAFigureThatIsNotOne() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        service.confirmEntry(state, null);
        assertEquals("VALEUR INVALIDE", state.ticket.transientError);
        assertTrue(state.entryPrompt.active);
        service.confirmEntry(state, BigDecimal.ZERO);
        assertTrue(state.entryPrompt.active);
        service.confirmEntry(state, new BigDecimal("-1"));
        assertTrue(state.entryPrompt.active);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * An article that vanished between the prompt and the answer closes the prompt and
     * says so, rather than ringing a line for nothing.
     */
    @Test
    void confirmEntryClosesThePromptWhenTheArticleIsGone() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(null);
            service.confirmEntry(state, new BigDecimal("2"));
        }
        assertFalse(state.entryPrompt.active);
        assertEquals("PRODUIT INTROUVABLE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
    }

    /**
     * A keyed decimal quantity rings the line at the catalog price, carrying the unit
     * of measure onto it ({@code LC-02-03-01/02}).
     */
    @Test
    void confirmEntryRingsTheLineAtTheKeyedQuantity() {
        openSession();
        Product p = product("CABLE", "123", null);
        p.unitName = "m";
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.20"));
            service.confirmEntry(state, new BigDecimal("2.36"));
        }
        assertFalse(state.entryPrompt.active);
        assertEquals(1, state.ticket.items.size());
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("2.36").compareTo(line.quantity));
        assertEquals(0, new BigDecimal("4.99").compareTo(line.unitPrice));
        assertEquals("m", line.unitName);
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationService).revalue(state);
    }

    /**
     * An article that wants BOTH figures asks the second one after the first, and rings
     * nothing until it has them ({@code LC-02-13-08}).
     */
    @Test
    void confirmEntryChainsTheQuantityIntoThePrice() {
        openSession();
        Product p = product("FLEURS", "123", null);
        p.attributes.put(com.intermarche.pos.domain.attribute.ProductAttributeCatalog
                .PRICE_TO_ENTER, "true");
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        try (MockedStatic<Product> products = mockStatic(Product.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            service.confirmEntry(state, new BigDecimal("3"));
            assertTrue(state.entryPrompt.active);
            assertEquals(PosState.EntryPromptState.PRICE, state.entryPrompt.kind);
            assertTrue(state.ticket.items.isEmpty());
            try (MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
                priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(null);
                service.confirmEntry(state, new BigDecimal("12.50"));
            }
        }
        assertFalse(state.entryPrompt.active);
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("3").compareTo(line.quantity));
        assertEquals(0, new BigDecimal("12.50").compareTo(line.unitPrice));
    }

    /**
     * A keyed price wins over the catalog's, which is the whole point of asking for it
     * ({@code LC-02-03-03}).
     */
    @Test
    void confirmEntryPrefersTheKeyedPriceToTheCatalogOne() {
        openSession();
        Product p = product("FLEURS", "123", null);
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.PRICE;
        state.entryPrompt.ean = "123";
        state.entryPrompt.quantity = new BigDecimal("2");
        try (MockedStatic<Product> products = mockStatic(Product.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class)) {
            products.when(() -> Product.findActiveByEan("123")).thenReturn(p);
            priceStatic.when(() -> Price.findCurrentPrice(anyLong())).thenReturn(price("4.99", "0.055"));
            service.confirmEntry(state, new BigDecimal("12.50"));
        }
        TicketState.TicketItem line = state.ticket.items.get(0);
        assertEquals(0, new BigDecimal("12.50").compareTo(line.unitPrice));
        assertEquals(0, new BigDecimal("0.055").compareTo(line.vatRate));
    }

    /**
     * {@code cancelEntry} closes the prompt, registers nothing and says the add was
     * given up.
     */
    @Test
    void cancelEntryRegistersNothing() {
        state.entryPrompt.active = true;
        state.entryPrompt.kind = PosState.EntryPromptState.QUANTITY;
        state.entryPrompt.ean = "123";
        long version = state.version;
        service.cancelEntry(state);
        assertFalse(state.entryPrompt.active);
        assertNull(state.entryPrompt.ean);
        assertEquals("SAISIE ABANDONNÉE", state.ticket.transientError);
        assertTrue(state.ticket.items.isEmpty());
        assertTrue(state.version > version);
    }
}
