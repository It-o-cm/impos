package com.intermarche.pos.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.PriceModState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketService;
import com.intermarche.pos.ui.ticket.TicketState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HomeService}.
 * <p>
 * Every collaborator is a Mockito mock. {@link PosState} carries plain public
 * fields ({@code selectedTicketIndex}, {@code lastEnteredItemId},
 * {@code trainingMode}, {@code lastClosedTicketId}) that the tests set directly
 * on the mock, plus decision methods ({@code getTargetItem()},
 * {@code getOperatorName()}) that are stubbed; its {@code ticket},
 * {@code priceModState} and {@code payment} sub-states are mocked so no direct
 * field access hits a null, and {@code ticket.items} is backed by a real list
 * carrying real {@link TicketState.TicketItem} lines. The private static final
 * {@code SUPERVISOR_CLIENT} is swapped with a mock {@link HttpClient} through
 * {@code sun.misc.Unsafe} for the real-time supervisor-call branches, then
 * restored after each test so the class stays isolated. Tests assert absolute
 * expected values and verify delegation, covering both arms of every guard,
 * ternary, compound condition and {@code null}/empty short-circuit.
 */
class HomeServiceTest {

    /** The service under test, rebuilt fresh for each test. */
    private HomeService service;

    /** The original supervisor HTTP client, restored after each test. */
    private HttpClient originalClient;

    /**
     * Builds a {@link HomeService} whose collaborators are fresh mocks wired
     * onto its package-private fields, including the {@link PosState} sub-state
     * holders and a real {@code ticket.items} list. Captures the original
     * static supervisor client for later restoration.
     *
     * @throws Exception if the static client field cannot be read
     */
    @BeforeEach
    void setUp() throws Exception {
        service = new HomeService();
        service.state = mock(PosState.class);
        service.state.ticket = mock(TicketState.class);
        service.state.ticket.items = new ArrayList<>();
        service.state.priceModState = mock(PriceModState.class);
        service.state.payment = mock(PaymentState.class);
        service.ticketService = mock(TicketService.class);
        service.endorsementService = mock(EndorsementService.class);
        service.technicalEventService = mock(TechnicalEventService.class);
        service.ticketNumberService = mock(TicketNumberService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.objectMapper = mock(ObjectMapper.class);
        service.supervisorToken = Optional.empty();
        originalClient = (HttpClient) readStaticField("SUPERVISOR_CLIENT");
    }

    /**
     * Restores the original supervisor client and clears any interrupt flag so
     * the supervisor-call tests leave no residual static or thread state.
     *
     * @throws Exception if the static client field cannot be restored
     */
    @AfterEach
    void tearDown() throws Exception {
        writeStaticFinal("SUPERVISOR_CLIENT", originalClient);
        Thread.interrupted();
    }

    /**
     * Reads a static field of {@link HomeService} by reflection.
     *
     * @param name the field name
     * @return the current field value
     * @throws Exception if the field cannot be accessed
     */
    private Object readStaticField(String name) throws Exception {
        Field field = HomeService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /**
     * Overwrites a private static final field of {@link HomeService} using
     * {@code sun.misc.Unsafe}, the only way to replace a final reference field
     * on JDK 21.
     *
     * @param name the field name
     * @param value the value to store
     * @throws Exception if the field or Unsafe cannot be accessed
     */
    private void writeStaticFinal(String name, Object value) throws Exception {
        Field field = HomeService.class.getDeclaredField(name);
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        unsafe.putObject(base, offset, value);
    }

    /**
     * Builds a real ticket line with a controlled uid.
     *
     * @param uid the line uid
     * @param ean the EAN code, or null
     * @param plu the PLU code, or null
     * @param price the unit price including tax
     * @param qty the quantity
     * @return the ticket line
     */
    private TicketState.TicketItem item(String uid, String ean, String plu, BigDecimal price, BigDecimal qty) {
        TicketState.TicketItem it = new TicketState.TicketItem(ean, plu, "L", price, qty, BigDecimal.ZERO);
        it.uid = uid;
        return it;
    }

    // --- Navigation ---

    /**
     * {@code toggleSecondaryMenu()} stores the flag and touches the state.
     */
    @Test
    void toggleSecondaryMenuStoresFlagAndTouches() {
        service.toggleSecondaryMenu(true);
        assertTrue(service.state.showSecondaryMenu);
        verify(service.state).touch();
    }

    /**
     * {@code selectLine()} clears the selection when the tapped index is already
     * selected (first arm true).
     */
    @Test
    void selectLineDeselectsWhenAlreadySelected() {
        service.state.selectedTicketIndex = 2;
        service.selectLine(2);
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    /**
     * {@code selectLine()} selects the tapped index when a different line was
     * selected (first arm false).
     */
    @Test
    void selectLineSelectsWhenDifferent() {
        service.state.selectedTicketIndex = 0;
        service.selectLine(2);
        assertEquals(2, service.state.selectedTicketIndex);
        verify(service.state).touch();
    }

    // --- Cancel line ---

    /**
     * {@code cancelLine()} cancels the selected line directly when it is the
     * last entered line (index guard both true, uid equals last entered true).
     */
    @Test
    void cancelLineCancelsSelectedLastEnteredDirectly() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "A";
        service.cancelLine();
        verify(service.ticketService).cancelItemById(service.state, "A");
        assertNull(service.state.lastEnteredItemId);
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code cancelLine()} requests a manager endorsement when the selected line
     * is not the last entered one (uid equals last entered false).
     */
    @Test
    void cancelLineRequestsEndorsementWhenNotLastEntered() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.state.selectedTicketIndex = 0;
        service.state.lastEnteredItemId = "OTHER";
        service.cancelLine();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_LINE_A");
        assertEquals(-1, service.state.selectedTicketIndex);
        verify(service.state).touch();
        verifyNoInteractions(service.ticketService);
    }

    /**
     * {@code cancelLine()} targets the last line when nothing is selected
     * (index guard first arm false, non-empty second arm true).
     */
    @Test
    void cancelLineTargetsLastLineWhenNoSelection() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.ticket.items.add(item("B", "456", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = -1;
        service.state.lastEnteredItemId = null;
        service.cancelLine();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_LINE_B");
    }

    /**
     * {@code cancelLine()} targets the last line when the selected index is out
     * of range (index guard first arm true, second arm false).
     */
    @Test
    void cancelLineTargetsLastLineWhenIndexOutOfRange() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.state.selectedTicketIndex = 5;
        service.state.lastEnteredItemId = "A";
        service.cancelLine();
        verify(service.ticketService).cancelItemById(service.state, "A");
    }

    /**
     * {@code cancelLine()} does nothing when the ticket is empty and no line is
     * selected (target uid stays null, early return).
     */
    @Test
    void cancelLineDoesNothingWhenEmpty() {
        service.state.selectedTicketIndex = -1;
        service.cancelLine();
        verifyNoInteractions(service.ticketService);
        verifyNoInteractions(service.endorsementService);
        verify(service.state, never()).touch();
    }

    /**
     * {@code cancelTicket()} requests a manager endorsement for the whole
     * ticket.
     */
    @Test
    void cancelTicketRequestsEndorsement() {
        service.cancelTicket();
        verify(service.endorsementService).requestAuthorization(service.state, "CANCEL_TICKET");
    }

    // --- Print last ticket ---

    /**
     * {@code printLastTicket()} refuses the reprint in training mode
     * (training arm true).
     */
    @Test
    void printLastTicketRefusedInTraining() {
        service.state.trainingMode = true;
        service.printLastTicket();
        verify(service.state.ticket).setError("RÉIMPRESSION INDISPONIBLE EN FORMATION");
        verify(service.state).touch();
        verifyNoInteractions(service.ticketService);
    }

    /**
     * {@code printLastTicket()} reprints the last closed ticket when one exists
     * (training false, id non-null).
     */
    @Test
    void printLastTicketReprintsWhenIdPresent() {
        service.state.trainingMode = false;
        service.state.lastClosedTicketId = 42L;
        service.printLastTicket();
        verify(service.ticketService).reprintTicket(42L);
    }

    /**
     * {@code printLastTicket()} does nothing when there is no last closed ticket
     * (training false, id null).
     */
    @Test
    void printLastTicketDoesNothingWhenNoId() {
        service.state.trainingMode = false;
        service.state.lastClosedTicketId = null;
        service.printLastTicket();
        verifyNoInteractions(service.ticketService);
    }

    // --- Open / cancel price modification ---

    /**
     * {@code openPriceMod()} sets an error when no line is targeted
     * (target null).
     */
    @Test
    void openPriceModErrorsWhenNoTarget() {
        when(service.state.getTargetItem()).thenReturn(null);
        service.openPriceMod("remise");
        verify(service.state.ticket).setError("AUCUNE LIGNE SÉLECTIONNÉE");
        verify(service.state).touch();
        verifyNoInteractions(service.state.priceModState);
    }

    /**
     * {@code openPriceMod()} opens the modal on the targeted line, upper-casing
     * the type (target non-null).
     */
    @Test
    void openPriceModOpensModalWhenTargeted() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(it);
        service.openPriceMod("remise");
        verify(service.state.priceModState).set("REMISE", "A", "L");
        verify(service.state).touch();
    }

    /**
     * {@code cancelPriceMod()} closes the modal and touches the state.
     */
    @Test
    void cancelPriceModClearsModal() {
        service.cancelPriceMod();
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    // --- Training toggle request ---

    /**
     * {@code requestTrainingToggle()} refuses over a non-empty cart
     * (first arm true).
     */
    @Test
    void requestTrainingToggleRefusedWhenCartNotEmpty() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.requestTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code requestTrainingToggle()} refuses during an active payment
     * (first arm false, second arm true).
     */
    @Test
    void requestTrainingToggleRefusedWhenPaymentInProgress() {
        service.state.payment.paymentInProgress = true;
        service.requestTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verify(service.state).touch();
        verifyNoInteractions(service.endorsementService);
    }

    /**
     * {@code requestTrainingToggle()} requests the endorsement on an empty cart
     * with no payment (both arms false).
     */
    @Test
    void requestTrainingToggleRequestsEndorsementWhenIdle() {
        service.state.payment.paymentInProgress = false;
        service.requestTrainingToggle();
        verify(service.endorsementService).requestAuthorization(service.state, "TRAINING_TOGGLE");
        verify(service.state).touch();
        verify(service.state.ticket, never()).setError(any());
    }

    // --- Training toggle perform ---

    /**
     * {@code performTrainingToggle()} refuses over a non-empty cart
     * (first arm true).
     */
    @Test
    void performTrainingToggleRefusedWhenCartNotEmpty() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.performTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * {@code performTrainingToggle()} refuses during an active payment
     * (first arm false, second arm true).
     */
    @Test
    void performTrainingToggleRefusedWhenPaymentInProgress() {
        service.state.payment.paymentInProgress = true;
        service.performTrainingToggle();
        verify(service.state.ticket).setError("TERMINEZ OU ANNULEZ LE TICKET D'ABORD");
        verifyNoInteractions(service.technicalEventService);
    }

    /**
     * {@code performTrainingToggle()} enters training mode when it was off
     * (ternary true arm).
     */
    @Test
    void performTrainingToggleEntersTraining() {
        service.state.trainingMode = false;
        service.performTrainingToggle();
        assertTrue(service.state.trainingMode);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TRAINING_STARTED, null);
        verify(service.state.ticket).setError("MODE FORMATION ACTIVÉ");
    }

    /**
     * {@code performTrainingToggle()} leaves training mode when it was on
     * (ternary false arm).
     */
    @Test
    void performTrainingToggleLeavesTraining() {
        service.state.trainingMode = true;
        service.performTrainingToggle();
        assertFalse(service.state.trainingMode);
        verify(service.technicalEventService).log(TechnicalEvent.EventType.TRAINING_ENDED, null);
        verify(service.state.ticket).setError("MODE FORMATION TERMINÉ");
    }

    // --- Supervisor call ---

    /**
     * {@code callSupervisor()} journals the call and reports the missing
     * configuration when the outbox is disabled (enabled false).
     */
    @Test
    void callSupervisorReportsNotConfigured() {
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        service.callSupervisor("no-change");
        verify(service.technicalEventService).log(TechnicalEvent.EventType.SUPERVISOR_CALLED, "no-change");
        verify(service.state.ticket).setError("SUPERVISION NON CONFIGURÉE SUR CETTE CAISSE");
        verify(service.state).touch();
    }

    /**
     * {@code callSupervisor()} reports success on a 2xx response with a
     * non-blank shared token (token added, status both arms true).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsSuccessWithToken() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.of("secret");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("SUPERVISEUR PRÉVENU");
        verify(service.state).touch();
    }

    /**
     * {@code callSupervisor()} reports a refusal on a 500 response with a blank
     * token (token skipped, status first arm true / second arm false).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsRefusedWhenServerError() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.of("");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (500)");
    }

    /**
     * {@code callSupervisor()} reports a refusal on a sub-200 response
     * (status first arm false).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    @SuppressWarnings("unchecked")
    void callSupervisorReportsRefusedWhenInformational() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        service.supervisorToken = Optional.empty();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(100);
        when(client.send(any(), any())).thenReturn(response);
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR REFUSÉ (100)");
    }

    /**
     * {@code callSupervisor()} reports an interruption and re-raises the thread
     * interrupt flag when the send is interrupted (InterruptedException catch).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    void callSupervisorReportsInterrupted() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any())).thenThrow(new InterruptedException());
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR INTERROMPU");
        assertTrue(Thread.currentThread().isInterrupted());
        verify(service.state).touch();
    }

    /**
     * {@code callSupervisor()} reports a generic failure when the send throws an
     * I/O error (generic Exception catch).
     *
     * @throws Exception if the HTTP send stub cannot be wired
     */
    @Test
    void callSupervisorReportsImpossibleOnIoError() throws Exception {
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.syncOutboxService.getStoreUrl()).thenReturn("http://localhost:9999");
        when(service.objectMapper.writeValueAsString(any())).thenReturn("{}");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any())).thenThrow(new java.io.IOException("down"));
        writeStaticFinal("SUPERVISOR_CLIENT", client);
        service.callSupervisor("theft");
        verify(service.state.ticket).setError("APPEL SUPERVISEUR IMPOSSIBLE");
        verify(service.state).touch();
    }

    // --- Submit price modification ---

    /**
     * {@code submitPriceMod()} routes a non-quantity type through the manager
     * endorsement (type not QUANTITY).
     */
    @Test
    void submitPriceModRoutesNonQuantityToEndorsement() {
        BigDecimal value = new BigDecimal("1.5");
        service.submitPriceMod("REMISE", "A", value);
        verify(service.endorsementService).requestPriceModification(service.state, "REMISE", "A", value);
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * {@code submitPriceMod()} applies a valid quantity directly on a unit line
     * (type QUANTITY: unit line, non-negative, whole value in range).
     */
    @Test
    void submitPriceModAppliesValidQuantity() {
        TicketState.TicketItem it = item("A", "123", null, new BigDecimal("2.00"), BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(it.quantity));
        verify(service.ticketService).recalculateTotal(service.state);
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * {@code submitPriceMod()} reports an unknown line when the uid matches no
     * item (item null).
     */
    @Test
    void submitPriceModQuantityReportsLineNotFound() {
        service.submitPriceMod("QUANTITY", "MISSING", new BigDecimal("3"));
        verify(service.state.ticket).setError("LIGNE INTROUVABLE");
        verify(service.ticketService, never()).recalculateTotal(any());
        verify(service.state.priceModState).clear();
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a weighed line
     * (plu non-empty: unit-line first paren false, not a unit line).
     */
    @Test
    void submitPriceModQuantityRefusedOnWeighedLine() {
        service.state.ticket.items.add(item("A", "123", "1000", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a line with no EAN
     * (empty plu, ean null: first paren true, ean-present arm false).
     */
    @Test
    void submitPriceModQuantityRefusedWhenEanNull() {
        service.state.ticket.items.add(item("A", null, "", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a line with an empty EAN
     * (empty plu, empty ean: ean-present true, ean-non-empty arm false).
     */
    @Test
    void submitPriceModQuantityRefusedWhenEanEmpty() {
        service.state.ticket.items.add(item("A", "", "", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a negative unit line
     * (unit line true, negative total: signum arm true).
     */
    @Test
    void submitPriceModQuantityRefusedWhenNegativeTotal() {
        service.state.ticket.items.add(item("A", "123", null, new BigDecimal("-1.00"), BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
    }

    /**
     * {@code submitPriceMod()} rejects a null quantity value
     * (value null arm true).
     */
    @Test
    void submitPriceModQuantityRejectsNullValue() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", null);
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} rejects a fractional quantity
     * (scale arm true).
     */
    @Test
    void submitPriceModQuantityRejectsFractionalValue() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("1.5"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }

    /**
     * {@code submitPriceMod()} rejects a quantity below one
     * (below-one arm true).
     */
    @Test
    void submitPriceModQuantityRejectsBelowOne() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("0"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }

    /**
     * {@code submitPriceMod()} rejects a quantity above 999
     * (above-999 arm true).
     */
    @Test
    void submitPriceModQuantityRejectsAboveMax() {
        service.state.ticket.items.add(item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("1000"));
        verify(service.state.ticket).setError("QUANTITÉ INVALIDE (1-999)");
    }
}
