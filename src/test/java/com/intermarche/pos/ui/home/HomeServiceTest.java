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
import com.intermarche.pos.domain.Employee;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.mockito.MockedStatic;
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
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        service.ticketPrinterService = mock(com.intermarche.pos.ui.hardware.TicketPrinterService.class);
        service.endorsementService = mock(EndorsementService.class);
        // Back-office parameters at their catalog defaults: the endorsement
        // ceremony applies, so the historical routing assertions hold.
        service.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(true);
        // Discounts and rebates active at their catalog default (BO-03-07-01),
        // so the historical remise/discount routing assertions hold.
        when(service.posSettingsService.discountEnabled()).thenReturn(true);
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
     * {@code selectMenu()} shows the asked menu and bumps the state version so the
     * screen redraws.
     */
    @Test
    void selectMenuShowsTheAskedMenu() {
        service.selectMenu(com.intermarche.pos.ui.PosMenu.TICKET);
        assertEquals(com.intermarche.pos.ui.PosMenu.TICKET, service.state.menu);
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
     * {@code printLastTicket()} prints nothing when the guard refuses.
     *
     * <p>The guard's own legs — training mode, no closed ticket — are covered
     * once in {@code PosStateTest}; what belongs here is that the service asks
     * it and obeys it.
     */
    @Test
    void printLastTicketPrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastTicket();
        // The RECOVERY still happens, and must: the DERNIER keys work on the last
        // sale this REGISTER closed, not the last one this PROCESS closed, so the
        // service looks the ticket up BEFORE asking the guard. What the refusal
        // forbids is the printing.
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastTicket()} reprints the last closed ticket when the guard
     * allows it.
     */
    @Test
    void printLastTicketReprintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        service.printLastTicket();
        verify(service.ticketService).reprintTicket(42L);
        verify(service.state.ticket).setNotice("TICKET RÉIMPRIMÉ");
    }

    // --- Print last ticket identity barcode (LC-08-01-04) ---

    /**
     * {@code printLastTicketBarcode()} prints nothing when the guard refuses.
     */
    @Test
    void printLastTicketBarcodePrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastTicketBarcode();
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastTicketBarcode()} prints the identity of the last closed
     * ticket when the guard allows it.
     */
    @Test
    void printLastTicketBarcodePrintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        service.printLastTicketBarcode();
        verify(service.ticketService).printTicketIdentityBarcode(42L);
        verify(service.state.ticket).setNotice("CODE-BARRES IMPRIMÉ");
    }

    // --- Duplicata du dernier ticket carte bancaire (LC-08-05-09) ---

    /**
     * {@code printLastCardReceiptDuplicate()} prints nothing when the guard
     * refuses.
     */
    @Test
    void printLastCardReceiptDuplicatePrintsNothingWhenGuardRefuses() {
        when(service.state.requireLastClosedTicket()).thenReturn(false);
        service.printLastCardReceiptDuplicate();
        verify(service.ticketService).resolveLastClosedTicketId(service.state);
        verifyNoMoreInteractions(service.ticketService);
    }

    /**
     * {@code printLastCardReceiptDuplicate()} prints the slip of the last closed
     * ticket when the guard allows it.
     */
    @Test
    void printLastCardReceiptDuplicatePrintsWhenGuardAllows() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        when(service.ticketService.printCardReceiptDuplicate(42L)).thenReturn(1);
        service.printLastCardReceiptDuplicate();
        verify(service.ticketService).printCardReceiptDuplicate(42L);
        verify(service.state.ticket).setNotice("DUPLICATA CB IMPRIMÉ");
    }

    /**
     * A sale settled without a card prints no slip: the key answers, and it
     * answers a refusal rather than a confirmation (printed == 0 arm).
     */
    @Test
    void printLastCardReceiptDuplicateTellsWhenTheSaleCarriedNoCard() {
        when(service.state.requireLastClosedTicket()).thenReturn(true);
        service.state.lastClosedTicketId = 42L;
        when(service.ticketService.printCardReceiptDuplicate(42L)).thenReturn(0);
        service.printLastCardReceiptDuplicate();
        verify(service.state.ticket).setError("AUCUN PAIEMENT CARTE SUR CE TICKET");
        verify(service.state.ticket, never()).setNotice(org.mockito.ArgumentMatchers.anyString());
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
        verify(service.state.priceModState).set("REMISE", "A", "L", it.getHtml(),
                it.getPriceFormatted(), it.getModifierLabel());
        verify(service.state).touch();
    }

    /**
     * A GLOBAL_ gesture targets the WHOLE ticket: the modal opens with a null
     * line uid and the "TICKET COMPLET" label, and — the point of the branch
     * — NO line selection is required. Selecting a line first would be
     * meaningless for a sale-level discount, and demanding one would block
     * the gesture on an empty selection.
     */
    @Test
    void openPriceModGlobalTargetsTheWholeTicket() {
        service.openPriceMod("global_remise");
        verify(service.state.priceModState).set("GLOBAL_REMISE", null, "TICKET COMPLET");
        verify(service.state).touch();
        verify(service.state, never()).getTargetItem();
        verifyNoInteractions(service.state.ticket);
    }

    /**
     * The percentage flavour takes the same branch — the prefix is what
     * routes, not the full type.
     */
    @Test
    void openPriceModGlobalDiscountTargetsTheWholeTicketToo() {
        service.openPriceMod("global_discount");
        verify(service.state.priceModState).set("GLOBAL_DISCOUNT", null, "TICKET COMPLET");
        verify(service.state).touch();
    }

    /**
     * A GLOBAL_ gesture opens even when a line IS selected: the selection is
     * simply irrelevant here, and the modal must not silently retarget the
     * gesture onto that line.
     */
    @Test
    void openPriceModGlobalIgnoresAnySelectedLine() {
        TicketState.TicketItem selected = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(selected);
        service.openPriceMod("GLOBAL_REMISE");
        verify(service.state.priceModState).set("GLOBAL_REMISE", null, "TICKET COMPLET");
        verify(service.state.priceModState, never()).set("GLOBAL_REMISE", "A", "L");
    }

    /**
     * A GLOBAL_ gesture never raises the no-selection error — the guard that
     * governs per-line gestures is short-circuited by the early return.
     */
    @Test
    void openPriceModGlobalNeverComplainsAboutSelection() {
        when(service.state.getTargetItem()).thenReturn(null);
        service.openPriceMod("global_remise");
        verify(service.state.ticket, never()).setError(any());
    }

    /**
     * The prefix match is on the UPPER-CASED type, so the lower-case form
     * used by the screen links routes exactly like the canonical one.
     */
    @Test
    void openPriceModGlobalPrefixMatchesOnTheUpperCasedType() {
        service.openPriceMod("Global_Remise");
        verify(service.state.priceModState).set("GLOBAL_REMISE", null, "TICKET COMPLET");
    }

    /**
     * A per-line type that merely CONTAINS "GLOBAL" without starting with the
     * prefix keeps the line path: the router is a prefix test, and a future
     * gesture named e.g. "REMISE_GLOBALE" must not silently become a
     * whole-ticket one.
     */
    @Test
    void openPriceModOnlyThePrefixRoutesToTheWholeTicket() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        when(service.state.getTargetItem()).thenReturn(it);
        service.openPriceMod("remise_global");
        verify(service.state.priceModState).set("REMISE_GLOBAL", "A", "L", it.getHtml(),
                it.getPriceFormatted(), it.getModifierLabel());
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
     * {@code submitPriceMod()} refuses every discount and rebate gesture when
     * the back office deactivated them (BO-03-07-01): each of the four remise /
     * discount types — line and global (the four operands of
     * {@code isDiscountGesture}) — sets the deactivation error and never reaches
     * the endorsement or the ticket service.
     */
    @Test
    void submitPriceModRefusesDiscountGesturesWhenDeactivated() {
        when(service.posSettingsService.discountEnabled()).thenReturn(false);
        BigDecimal value = new BigDecimal("5");
        service.submitPriceMod("REMISE", "A", value);
        service.submitPriceMod("DISCOUNT", "A", value);
        service.submitPriceMod("GLOBAL_REMISE", null, value);
        service.submitPriceMod("GLOBAL_DISCOUNT", null, value);
        verify(service.state.ticket, times(4)).setError("REMISES DÉSACTIVÉES");
        verifyNoInteractions(service.endorsementService);
        verifyNoInteractions(service.ticketService);
        verify(service.state.priceModState, times(4)).clear();
    }

    /**
     * {@code submitPriceMod()} still routes a price forcing normally when
     * discounts are deactivated (BO-03-07-01): FORCE_PRICE is not a discount
     * gesture (the false arm of {@code isDiscountGesture}), so it reaches the
     * endorsement and no deactivation error is raised.
     */
    @Test
    void submitPriceModAllowsForcePriceWhenDiscountsDeactivated() {
        when(service.posSettingsService.discountEnabled()).thenReturn(false);
        BigDecimal value = new BigDecimal("9");
        service.submitPriceMod("FORCE_PRICE", "A", value);
        verify(service.endorsementService).requestPriceModification(service.state, "FORCE_PRICE", "A", value);
        verify(service.state.ticket, never()).setError("REMISES DÉSACTIVÉES");
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
     * {@code submitPriceMod()} applies a DECIMAL weight on a weighed line
     * (LC-02-13-02: plu carried, ean present, not price-embedded — the typed
     * value is the weight in kilograms, stored at scale 3).
     */
    @Test
    void submitPriceModQuantityAppliesDecimalWeightOnWeighedLine() {
        TicketState.TicketItem it = item("A", "123", "1000", BigDecimal.ONE, BigDecimal.ONE);
        service.state.ticket.items.add(it);
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("0.85"));
        assertEquals(0, new BigDecimal("0.850").compareTo(it.quantity));
        assertEquals(3, it.quantity.scale());
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * {@code submitPriceMod()} refuses an out-of-range weight on a weighed
     * line (zero, above 99.999 kg, or finer than the gram) with the weight
     * message — one test per leg of the composed guard.
     */
    @Test
    void submitPriceModQuantityRefusesInvalidWeightLegs() {
        service.state.ticket.items.add(item("A", "123", "1000", BigDecimal.ONE, BigDecimal.ONE));
        service.submitPriceMod("QUANTITY", "A", BigDecimal.ZERO);
        verify(service.state.ticket).setError("POIDS INVALIDE (0,001-99,999 KG)");
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("100.000"));
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("0.0005"));
        service.submitPriceMod("QUANTITY", "A", null);
        verify(service.state.ticket, times(4)).setError("POIDS INVALIDE (0,001-99,999 KG)");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a PRICE-EMBEDDED sticker
     * line: one physical sticker is one object at its printed total, never a
     * multipliable line.
     */
    @Test
    void submitPriceModQuantityRefusedOnPriceEmbeddedLine() {
        TicketState.TicketItem it = item("A", "123", "1000", BigDecimal.ONE, BigDecimal.ONE);
        it.priceEmbedded = true;
        service.state.ticket.items.add(it);
        service.submitPriceMod("QUANTITY", "A", new BigDecimal("3"));
        verify(service.state.ticket).setError("QUANTITÉ NON MODIFIABLE SUR CETTE LIGNE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * {@code submitPriceMod()} refuses a quantity on a MONEY-PRODUCT line
     * (gift card): money is not multiplied by a gesture.
     */
    @Test
    void submitPriceModQuantityRefusedOnMoneyProductLine() {
        TicketState.TicketItem it = item("A", "123", null, BigDecimal.ONE, BigDecimal.ONE);
        it.moneyProduct = true;
        service.state.ticket.items.add(it);
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

    // --- submitPriceMod without endorsement (applyGestureDirectly) ---

    /**
     * Adds a ticket line with the given uid to the real items list.
     *
     * @param uid the uid to assign
     * @return the created item
     */
    private TicketState.TicketItem addLine(String uid) {
        TicketState.TicketItem item = new TicketState.TicketItem(
                "3000", null, "Milk", new BigDecimal("1.00"), BigDecimal.ONE, new BigDecimal("0.2000"));
        item.uid = uid;
        service.state.ticket.items.add(item);
        return item;
    }

    /**
     * With the endorsement ceremony disabled, a global gesture is applied
     * directly (disabled-routing arm, GLOBAL_ arm) without a manager request.
     */
    @Test
    void submitPriceModDisabledAppliesGlobalDirectly() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        BigDecimal value = new BigDecimal("10");
        service.submitPriceMod("GLOBAL_PERCENT", "A", value);
        verify(service.ticketService).applyGlobalDiscount(service.state, "GLOBAL_PERCENT", value);
        verify(service.endorsementService, never()).requestPriceModification(any(), any(), any(), any());
        verify(service.state.priceModState).clear();
        verify(service.state).touch();
    }

    /**
     * The direct gesture reports an unknown line when the uid matches nothing
     * (type-null arm of the global guard, item-null arm).
     */
    @Test
    void gestureDirectlyReportsMissingLine() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        service.submitPriceMod(null, "MISSING", new BigDecimal("1"));
        verify(service.state.ticket).setError("LIGNE INTROUVABLE");
        verify(service.ticketService, never()).recalculateTotal(any());
    }

    /**
     * The direct gesture applies a REMISE on the target line (REMISE arm) and
     * recomputes.
     */
    @Test
    void gestureDirectlyAppliesRemise() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("2");
        service.submitPriceMod("REMISE", "A", value);
        verify(service.ticketService).applyRemise(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture applies a DISCOUNT on the target line (DISCOUNT arm).
     */
    @Test
    void gestureDirectlyAppliesDiscount() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("15");
        service.submitPriceMod("DISCOUNT", "A", value);
        verify(service.ticketService).applyDiscount(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture forces a price on the target line (FORCE_PRICE arm).
     */
    @Test
    void gestureDirectlyForcesPrice() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        TicketState.TicketItem item = addLine("A");
        BigDecimal value = new BigDecimal("5");
        service.submitPriceMod("FORCE_PRICE", "A", value);
        verify(service.ticketService).forcePrice(item, value);
        verify(service.ticketService).recalculateTotal(service.state);
    }

    /**
     * The direct gesture on an unrecognized type applies nothing yet still
     * recomputes (none-of-the-three arm).
     */
    @Test
    void gestureDirectlyUnknownTypeStillRecalculates() {
        when(service.posSettingsService.gestureEndorsementRequired()).thenReturn(false);
        TicketState.TicketItem item = addLine("A");
        service.submitPriceMod("MYSTERY", "A", new BigDecimal("1"));
        verify(service.ticketService, never()).applyRemise(any(), any());
        verify(service.ticketService, never()).applyDiscount(any(), any());
        verify(service.ticketService, never()).forcePrice(any(), any());
        verify(service.ticketService).recalculateTotal(service.state);
    }

    // --- printOperatorBadge ---

    /**
     * Builds a Panache query whose {@code firstResult} resolves to the value.
     *
     * @param employee the employee the query returns, or null
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<Employee> employeeQuery(Employee employee) {
        PanacheQuery<Employee> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(employee);
        return query;
    }

    /**
     * Builds an employee with the given activity.
     *
     * @param active whether the employee is active
     * @return the employee mock
     */
    private Employee activeEmployee(boolean active) {
        Employee employee = mock(Employee.class);
        employee.active = active;
        return employee;
    }

    /**
     * {@code printOperatorBadge} resolves an operator by badge id, prints the
     * badge and confirms (badge-found arm, active arm).
     */
    @Test
    void printOperatorBadgeByBadgeId() {
        Employee employee = activeEmployee(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "5")).thenReturn(byBadge);
            service.printOperatorBadge("5");
        }
        verify(service.ticketPrinterService).printOperatorBadge(employee);
        verify(service.state.ticket).setError("BADGE OPÉRATEUR IMPRIMÉ");
    }

    /**
     * {@code printOperatorBadge} falls back to the login name when the badge
     * id matches nothing (badge-null arm, login-found arm).
     */
    @Test
    void printOperatorBadgeByLoginName() {
        Employee employee = activeEmployee(true);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(null);
            PanacheQuery<Employee> byLogin = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "jdupont")).thenReturn(byBadge);
            ms.when(() -> Employee.find("loginName", "jdupont")).thenReturn(byLogin);
            service.printOperatorBadge("jdupont");
        }
        verify(service.ticketPrinterService).printOperatorBadge(employee);
        verify(service.state.ticket).setError("BADGE OPÉRATEUR IMPRIMÉ");
    }

    /**
     * {@code printOperatorBadge} reports an unknown operator when neither
     * lookup matches (both-null arm), printing nothing.
     */
    @Test
    void printOperatorBadgeNotFound() {
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(null);
            PanacheQuery<Employee> byLogin = employeeQuery(null);
            ms.when(() -> Employee.find("badgeId", "x")).thenReturn(byBadge);
            ms.when(() -> Employee.find("loginName", "x")).thenReturn(byLogin);
            service.printOperatorBadge("x");
        }
        verify(service.state.ticket).setError("OPÉRATEUR INTROUVABLE (x)");
        verify(service.ticketPrinterService, never()).printOperatorBadge(any());
    }

    /**
     * {@code printOperatorBadge} reports an unknown operator for a deactivated
     * one (inactive arm), printing nothing.
     */
    @Test
    void printOperatorBadgeInactive() {
        Employee employee = activeEmployee(false);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Employee> byBadge = employeeQuery(employee);
            ms.when(() -> Employee.find("badgeId", "5")).thenReturn(byBadge);
            service.printOperatorBadge("5");
        }
        verify(service.state.ticket).setError("OPÉRATEUR INTROUVABLE (5)");
        verify(service.ticketPrinterService, never()).printOperatorBadge(any());
    }
}
