package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.service.TicketPersistenceService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ValuationService}.
 * <p>
 * The service orchestrates the remote valuation: it wires a
 * {@link ValuationClient} (HTTP half), a {@link ValuationReconciler}
 * (line-borne apply/revert), a {@link TicketPersistenceService} (draft sync)
 * and an {@link ObjectMapper} (hint parsing and request logging). The three
 * bean collaborators are Mockito mocks; the mapper is the real, plain
 * {@link ObjectMapper} so the JSON round-trip of {@code valuate} →
 * {@code extractHints} is genuinely exercised. All four fields are
 * package-private and assigned directly since the test lives in the
 * production package, and the private {@code engineSkipUntil} circuit-breaker
 * gauge is set through reflection to open the circuit deterministically.
 * <p>
 * The two Panache static finders reached indirectly — {@code Store.findAll}
 * in {@code buildBasket} and {@code Product.find} in {@code extractHints} —
 * resolve to {@link PanacheEntityBase} under plain {@code mvn test} and are
 * intercepted with {@link org.mockito.Mockito#mockStatic}; a mocked
 * {@link PanacheQuery} returns the pinned {@code firstResult}. No Quarkus
 * context, no H2 and no application boot.
 * <p>
 * Branch map covered here: {@code onStart} (enabled / disabled);
 * {@code extractHints} (null json, MEAL_VOUCHER, upsell with resolved product
 * / with fallback ean and null qty / null offer, suggestion-null and
 * ean-null skips, unreadable json catch); {@code isEnabled} delegation;
 * {@code revalue} (training skip, payment-in-progress skip, proceed);
 * {@code revalueForPayment} (training skip, proceed); {@code doRevalue}
 * (disabled early return, circuit open with and without a prior valuation,
 * ENGINE with and without an engine total and with active / inactive
 * fidelity, DEGRADED, LOCAL default); {@code valuate} (disabled, empty
 * eligible basket, engine success, InterruptedException, generic exception);
 * {@code buildBasket} (customer code null / blank / present, store present /
 * absent, creation date present / absent, the three line-eligibility skips);
 * and {@code applyGesture} (guard both arms, REMISE, DISCOUNT, FORCE_PRICE
 * with non-zero and zero quantity, unknown gesture default).
 */
class ValuationServiceTest {

    /** Local mapper used to serialize response DTOs into the engine JSON fed to extractHints. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A stable draft database id used by the draft-bearing scenarios. */
    private static final Long DB_ID = 42L;

    /** The mocked HTTP half of the engine. */
    private final ValuationClient valuationClient = mock(ValuationClient.class);

    /** The mocked line-borne reconciler. */
    private final ValuationReconciler valuationReconciler = mock(ValuationReconciler.class);

    /** The mocked draft persistence collaborator. */
    private final TicketPersistenceService ticketPersistenceService = mock(TicketPersistenceService.class);

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    /**
     * Builds a service wired with the mocks, the real mapper and a fixed
     * circuit-breaker retry window.
     *
     * @return the wired service under test
     */
    private ValuationService newService() {
        ValuationService service = new ValuationService();
        service.valuationClient = valuationClient;
        service.valuationReconciler = valuationReconciler;
        service.ticketPersistenceService = ticketPersistenceService;
        service.objectMapper = new ObjectMapper();
        service.retrySeconds = 10L;
        return service;
    }

    /**
     * Sets the private {@code engineSkipUntil} circuit-breaker gauge.
     *
     * @param service the service to patch
     * @param value the epoch-millis skip horizon
     * @throws Exception if reflection fails
     */
    private void setEngineSkipUntil(ValuationService service, long value) throws Exception {
        Field field = ValuationService.class.getDeclaredField("engineSkipUntil");
        field.setAccessible(true);
        field.setLong(service, value);
    }

    /**
     * Builds a cart line with a fixed uid, ean, unit price and quantity.
     *
     * @param uid the line uid (contractual lineId)
     * @param ean the ean, or null
     * @param unitPrice the unit price including tax
     * @param quantity the quantity
     * @return the ticket line
     */
    private TicketState.TicketItem item(String uid, String ean, String unitPrice, String quantity) {
        TicketState.TicketItem it = new TicketState.TicketItem(ean, null, "L-" + uid,
                new BigDecimal(unitPrice), new BigDecimal(quantity), new BigDecimal("0.20"));
        it.uid = uid;
        return it;
    }

    /**
     * Builds an in-memory ticket state holding the given lines.
     *
     * @param items the lines
     * @return the ticket state
     */
    private TicketState ticketWith(TicketState.TicketItem... items) {
        TicketState t = new TicketState();
        for (TicketState.TicketItem it : items) t.items.add(it);
        return t;
    }

    /**
     * Builds a POS state carrying the given cart lines, out of training and
     * out of payment, with no fidelity card and no draft id.
     *
     * @param items the cart lines
     * @return the POS state
     */
    private PosState posStateWith(TicketState.TicketItem... items) {
        PosState state = new PosState();
        for (TicketState.TicketItem it : items) state.ticket.items.add(it);
        state.ticket.recomputeTotal();
        return state;
    }

    /**
     * Builds a mocked {@link PanacheQuery} whose {@code firstResult} is pinned.
     *
     * @param first the value to return from firstResult, or null
     * @param <T> the entity type
     * @return the mocked query
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> query(T first) {
        PanacheQuery<T> q = mock(PanacheQuery.class);
        when(q.firstResult()).thenReturn(first);
        return q;
    }

    /**
     * Builds an amount carrying only the tax-included figure.
     *
     * @param incl the tax-included amount, or null
     * @return the amount DTO
     */
    private ValuationPayloads.AmountDto amount(String incl) {
        ValuationPayloads.AmountDto a = new ValuationPayloads.AmountDto();
        a.amountIncludingTax = incl == null ? null : new BigDecimal(incl);
        return a;
    }

    /**
     * Builds a response holding the given advantages and an optional total.
     *
     * @param total the tax-included engine total, or null
     * @param advantages the advantages
     * @return the response DTO
     */
    private ValuationPayloads.ValuationResponseDto response(String total,
                                                           ValuationPayloads.AdvantageDto... advantages) {
        ValuationPayloads.ValuationResponseDto r = new ValuationPayloads.ValuationResponseDto();
        for (ValuationPayloads.AdvantageDto a : advantages) r.advantages.add(a);
        r.totalPrice = total == null ? null : amount(total);
        return r;
    }

    // --------------------------------------------------
    // onStart
    // --------------------------------------------------

    /**
     * onStart announces the remote engine when it is enabled (isEnabled true
     * arm), reading the target url and the retry window.
     */
    @Test
    void onStartAnnouncesRemoteWhenEnabled() {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.targetUrl()).thenReturn("http://engine");
        service.onStart(null);
        verify(valuationClient).targetUrl();
    }

    /**
     * onStart announces the local-only engine when it is disabled (isEnabled
     * false arm), touching no target url.
     */
    @Test
    void onStartAnnouncesLocalWhenDisabled() {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(false);
        service.onStart(null);
        verify(valuationClient, never()).targetUrl();
    }

    // --------------------------------------------------
    // extractHints
    // --------------------------------------------------

    /**
     * extractHints returns an empty, non-null bundle for a null response
     * (responseJson null arm).
     */
    @Test
    void extractHintsReturnsEmptyForNullJson() {
        ValuationService service = newService();
        ValuationService.EngineHints hints = service.extractHints(null);
        assertNull(hints.mealEligible);
        assertNull(hints.mealThreshold);
        assertTrue(hints.upsells.isEmpty());
    }

    /**
     * extractHints reads a MEAL_VOUCHER advantage into the meal fields
     * (type-match arm), leaving the upsells empty.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void extractHintsReadsMealVoucher() throws Exception {
        ValuationService service = newService();
        ValuationPayloads.AdvantageDto meal = new ValuationPayloads.AdvantageDto();
        meal.type = "MEAL_VOUCHER";
        meal.totalEligibleAmount = new BigDecimal("19.00");
        meal.threshold = new BigDecimal("25.00");
        String json = MAPPER.writeValueAsString(response(null, meal));
        ValuationService.EngineHints hints = service.extractHints(json);
        assertEquals(new BigDecimal("19.00"), hints.mealEligible);
        assertEquals(new BigDecimal("25.00"), hints.mealThreshold);
        assertTrue(hints.upsells.isEmpty());
    }

    /**
     * extractHints resolves an upsell suggestion against the local catalog:
     * the product is found (product non-null arm → its name), the quantity is
     * present (quantity non-null arm → stripped) and the offer code is present
     * (offerCode non-null arm → parenthesized).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void extractHintsResolvesUpsellWithProductQuantityAndOffer() throws Exception {
        ValuationService service = newService();
        ValuationPayloads.AdvantageDto up = new ValuationPayloads.AdvantageDto();
        up.type = "UPSELL";
        up.suggestion = new ValuationPayloads.SuggestionDto();
        up.suggestion.ean = "EAN1";
        up.suggestion.quantity = new BigDecimal("2.00");
        up.suggestion.offerCode = "3x2";
        String json = MAPPER.writeValueAsString(response(null, up));
        Product product = new Product();
        product.name = "Yaourt";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> productQuery = query(product);
            ms.when(() -> Product.find("ean", "EAN1")).thenReturn(productQuery);
            ValuationService.EngineHints hints = service.extractHints(json);
            assertEquals(1, hints.upsells.size());
            assertEquals("+2 x Yaourt (3x2)", hints.upsells.get(0));
        }
    }

    /**
     * extractHints falls back to the ean as the label when the product is
     * unknown (product null arm), defaults the quantity to "1" (quantity null
     * arm) and omits the offer suffix (offerCode null arm).
     *
     * @throws Exception if serialization fails
     */
    @Test
    void extractHintsFallsBackToEanWithDefaultQuantityAndNoOffer() throws Exception {
        ValuationService service = newService();
        ValuationPayloads.AdvantageDto up = new ValuationPayloads.AdvantageDto();
        up.type = "UPSELL";
        up.suggestion = new ValuationPayloads.SuggestionDto();
        up.suggestion.ean = "EAN9";
        String json = MAPPER.writeValueAsString(response(null, up));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Product> productQuery = query(null);
            ms.when(() -> Product.find("ean", "EAN9")).thenReturn(productQuery);
            ValuationService.EngineHints hints = service.extractHints(json);
            assertEquals(1, hints.upsells.size());
            assertEquals("+1 x EAN9", hints.upsells.get(0));
        }
    }

    /**
     * extractHints skips non-meal advantages that carry no usable suggestion:
     * a wholly absent suggestion (suggestion-null arm) and a present
     * suggestion with a null ean (ean-null arm), producing no upsell.
     *
     * @throws Exception if serialization fails
     */
    @Test
    void extractHintsSkipsAdvantagesWithoutSuggestionEan() throws Exception {
        ValuationService service = newService();
        ValuationPayloads.AdvantageDto noSuggestion = new ValuationPayloads.AdvantageDto();
        noSuggestion.type = "OTHER";
        ValuationPayloads.AdvantageDto nullEan = new ValuationPayloads.AdvantageDto();
        nullEan.type = "OTHER";
        nullEan.suggestion = new ValuationPayloads.SuggestionDto();
        String json = MAPPER.writeValueAsString(response(null, noSuggestion, nullEan));
        ValuationService.EngineHints hints = service.extractHints(json);
        assertTrue(hints.upsells.isEmpty());
    }

    /**
     * extractHints swallows an unreadable response and returns an empty bundle
     * (catch arm).
     */
    @Test
    void extractHintsReturnsEmptyOnUnreadableJson() {
        ValuationService service = newService();
        ValuationService.EngineHints hints = service.extractHints("}{ not json");
        assertNull(hints.mealEligible);
        assertTrue(hints.upsells.isEmpty());
    }

    // --------------------------------------------------
    // isEnabled
    // --------------------------------------------------

    /**
     * isEnabled delegates a true answer to the client.
     */
    @Test
    void isEnabledDelegatesTrue() {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        assertTrue(service.isEnabled());
    }

    /**
     * isEnabled delegates a false answer to the client.
     */
    @Test
    void isEnabledDelegatesFalse() {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(false);
        assertEquals(false, service.isEnabled());
    }

    // --------------------------------------------------
    // revalue / revalueForPayment guards
    // --------------------------------------------------

    /**
     * revalue skips entirely in training mode (trainingMode true arm),
     * touching no collaborator.
     */
    @Test
    void revalueSkipsInTrainingMode() {
        ValuationService service = newService();
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.trainingMode = true;
        service.revalue(state);
        verifyNoInteractions(valuationClient, valuationReconciler, ticketPersistenceService);
    }

    /**
     * revalue skips while a payment is in progress (trainingMode false,
     * paymentInProgress true arm), touching no collaborator.
     */
    @Test
    void revalueSkipsWhenPaymentInProgress() {
        ValuationService service = newService();
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.payment.paymentInProgress = true;
        service.revalue(state);
        verifyNoInteractions(valuationClient, valuationReconciler, ticketPersistenceService);
    }

    /**
     * revalue proceeds when neither guard fires and stops in doRevalue because
     * the engine is disabled (isEnabled false arm of doRevalue), leaving the
     * valuation state untouched.
     */
    @Test
    void revalueProceedsButStopsWhenDisabled() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(false);
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        service.revalue(state);
        assertNull(state.payment.valuationStatus);
        verify(valuationClient, never()).valuate(any());
    }

    /**
     * revalueForPayment skips in training mode (trainingMode true arm).
     */
    @Test
    void revalueForPaymentSkipsInTrainingMode() {
        ValuationService service = newService();
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.trainingMode = true;
        service.revalueForPayment(state);
        verifyNoInteractions(valuationClient, valuationReconciler, ticketPersistenceService);
    }

    // --------------------------------------------------
    // doRevalue — circuit breaker
    // --------------------------------------------------

    /**
     * doRevalue degrades in memory without calling the engine when the circuit
     * is open (currentTimeMillis &lt; engineSkipUntil arm) and, a prior
     * valuation being present (revertInMemory hadValuation true arm), reverts
     * it, recomputes and re-syncs the draft, stamping DEGRADED.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void doRevalueCircuitOpenRevertsPriorValuationAndDegrades() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        setEngineSkipUntil(service, Long.MAX_VALUE);
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.payment.ticketDbId = DB_ID;
        state.payment.valuationAdjustment = new BigDecimal("-1.00");
        service.revalue(state);
        assertEquals("DEGRADED", state.payment.valuationStatus);
        assertNull(state.payment.valuationAdjustment);
        verify(valuationReconciler).revert(state.ticket, DB_ID);
        verify(ticketPersistenceService).syncDraft(state);
        verify(valuationClient, never()).valuate(any());
    }

    /**
     * doRevalue degrades on an open circuit with no prior valuation
     * (revertInMemory hadValuation false arm): it neither reverts nor syncs,
     * only clearing the hint fields and stamping DEGRADED.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void doRevalueCircuitOpenWithoutPriorValuationDegrades() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        setEngineSkipUntil(service, Long.MAX_VALUE);
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        service.revalue(state);
        assertEquals("DEGRADED", state.payment.valuationStatus);
        verify(valuationReconciler, never()).revert(any(), any());
        verify(ticketPersistenceService, never()).syncDraft(any());
    }

    // --------------------------------------------------
    // doRevalue — ENGINE / DEGRADED / LOCAL
    // --------------------------------------------------

    /**
     * doRevalue on an ENGINE outcome with an active fidelity card
     * (fidelity.active true arm) applies the reconciliation, recomputes,
     * syncs the draft, feeds the hints and logs the total present arm
     * (engineTotalInclTax non-null).
     *
     * @throws Exception on serialization
     */
    @Test
    void doRevalueEngineSuccessWithTotalAndFidelity() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        ValuationPayloads.ValuationResponseDto engineResponse = response("18.00");
        when(valuationClient.valuate(any())).thenReturn(engineResponse);
        when(valuationReconciler.apply(any(), any(), any())).thenReturn(new BigDecimal("-2.00"));
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.payment.ticketDbId = DB_ID;
        state.fidelity.active = true;
        state.fidelity.label = "CARD123";
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            service.revalueForPayment(state);
        }
        assertEquals("ENGINE", state.payment.valuationStatus);
        assertEquals(new BigDecimal("18.00"), state.payment.valuationEngineTotal);
        assertEquals(new BigDecimal("-2.00"), state.payment.valuationAdjustment);
        assertTrue(state.payment.valuationUpsells.isEmpty());
        verify(valuationReconciler).apply(eq(state.ticket), eq(DB_ID), any());
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * doRevalue on an ENGINE outcome with no engine total (engineTotalInclTax
     * null arm) and no fidelity card (fidelity.active false arm) still values,
     * syncs and clears the hints.
     *
     * @throws Exception on serialization
     */
    @Test
    void doRevalueEngineSuccessWithoutTotalNoFidelity() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenReturn(response(null));
        when(valuationReconciler.apply(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            service.revalueForPayment(state);
        }
        assertEquals("ENGINE", state.payment.valuationStatus);
        assertNull(state.payment.valuationEngineTotal);
        assertEquals(BigDecimal.ZERO, state.payment.valuationAdjustment);
        verify(ticketPersistenceService).syncDraft(state);
    }

    /**
     * doRevalue on a DEGRADED outcome opens the circuit, degrades in memory
     * (reverting the prior valuation, hadValuation true arm) and marks the
     * draft degraded.
     */
    @Test
    void doRevalueDegradedOpensCircuitAndMarksDraft() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenThrow(new RuntimeException("boom"));
        PosState state = posStateWith(item("L1", "1", "10", "1"));
        state.payment.ticketDbId = DB_ID;
        state.payment.valuationAdjustment = new BigDecimal("-1.00");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            service.revalue(state);
        }
        assertEquals("DEGRADED", state.payment.valuationStatus);
        assertNull(state.payment.valuationAdjustment);
        verify(valuationReconciler).revert(state.ticket, DB_ID);
        verify(valuationReconciler).markDegraded(DB_ID);
    }

    /**
     * doRevalue on a LOCAL outcome (no eligible line, engine not called) takes
     * the default branch and reverts in memory with no prior valuation
     * (hadValuation false arm), leaving the status LOCAL.
     */
    @Test
    void doRevalueLocalRevertsInMemory() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        PosState state = posStateWith(item("L1", null, "10", "1"));
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            service.revalue(state);
        }
        assertEquals("LOCAL", state.payment.valuationStatus);
        assertNull(state.payment.valuationAdjustment);
        verify(valuationReconciler, never()).revert(any(), any());
        verify(valuationClient, never()).valuate(any());
    }

    // --------------------------------------------------
    // valuate
    // --------------------------------------------------

    /**
     * valuate returns LOCAL without touching the engine when it is disabled
     * (isEnabled false arm).
     */
    @Test
    void valuateReturnsLocalWhenDisabled() {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(false);
        ValuationService.ValuationOutcome outcome =
                service.valuate(ticketWith(item("L1", "1", "10", "1")), null, LocalDateTime.now());
        assertEquals("LOCAL", outcome.status);
        assertNull(outcome.responseJson);
        assertNull(outcome.engineTotalInclTax);
    }

    /**
     * valuate returns LOCAL when no line is eligible (empty basket arm),
     * exercising the three build-basket skips — null ean, empty ean and a
     * non-positive total — with a null fidelity card (customerCode null arm)
     * and a null creation date (date fallback arm), the engine untouched.
     */
    @Test
    void valuateReturnsLocalWhenNoEligibleLine() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        TicketState.TicketItem nullEan = item("A1", null, "10", "1");
        TicketState.TicketItem emptyEan = item("A2", "", "10", "1");
        TicketState.TicketItem zeroTotal = item("A3", "9", "0", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            ValuationService.ValuationOutcome outcome =
                    service.valuate(ticketWith(nullEan, emptyEan, zeroTotal), null, null);
            assertEquals("LOCAL", outcome.status);
        }
        verify(valuationClient, never()).valuate(any());
    }

    /**
     * valuate builds the basket, calls the engine and returns ENGINE with the
     * engine total (totalPrice non-null arm). The basket carries the store
     * code resolved from the store (store non-null arm), a blank fidelity card
     * folded to a null customer code (blank arm), and every gesture shape:
     * REMISE, DISCOUNT, FORCE_PRICE on a non-zero quantity (divide arm), a
     * plain line (gesture guard modifierType-null arm), a line with a type but
     * a null value (guard modifierValue-null arm) and an unknown gesture
     * (default arm).
     *
     * @throws Exception on transport or serialization
     */
    @Test
    void valuateEngineSuccessBuildsBasketWithGestures() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenReturn(response("42.00"));
        TicketState.TicketItem remise = item("R", "10", "10", "1");
        remise.modifierType = "REMISE";
        remise.modifierValue = new BigDecimal("1.00");
        TicketState.TicketItem discount = item("D", "11", "10", "1");
        discount.modifierType = "DISCOUNT";
        discount.modifierValue = new BigDecimal("5");
        TicketState.TicketItem force = item("F", "12", "10", "2");
        force.modifierType = "FORCE_PRICE";
        force.modifierValue = new BigDecimal("9.00");
        TicketState.TicketItem plain = item("P", "13", "10", "1");
        TicketState.TicketItem typeNoValue = item("N", "14", "10", "1");
        typeNoValue.modifierType = "REMISE";
        typeNoValue.modifierValue = null;
        TicketState.TicketItem unknown = item("U", "15", "10", "1");
        unknown.modifierType = "MYSTERY";
        unknown.modifierValue = new BigDecimal("1");
        Store store = new Store();
        store.code = "0034";
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(store);
            ms.when(Store::findAll).thenReturn(storeQuery);
            ValuationService.ValuationOutcome outcome = service.valuate(
                    ticketWith(remise, discount, force, plain, typeNoValue, unknown),
                    "   ", LocalDateTime.of(2026, 8, 3, 10, 0));
            assertEquals("ENGINE", outcome.status);
            assertEquals(new BigDecimal("42.00"), outcome.engineTotalInclTax);
        }
        verify(valuationClient).valuate(captor.capture());
        ValuationPayloads.BasketDto basket = captor.getValue();
        assertNull(basket.customerCode);
        assertEquals("0034", basket.storeCode);
        assertEquals(6, basket.items.size());
        assertEquals(new BigDecimal("1.00"), basket.items.get(0).manualDiscountAmount);
        assertEquals(new BigDecimal("5"), basket.items.get(1).manualDiscountPercent);
        assertEquals(new BigDecimal("4.50"), basket.items.get(2).manualForcedPrice);
        assertNull(basket.items.get(3).manualDiscountAmount);
        assertNull(basket.items.get(4).manualDiscountAmount);
        assertNull(basket.items.get(5).manualDiscountAmount);
    }

    /**
     * valuate builds a customer code from a non-blank fidelity card (present
     * arm) and defaults the store code to "0000" when no store exists (store
     * null arm), applying a FORCE_PRICE gesture on a zero-quantity line whose
     * engine-valued total keeps it eligible (quantity signum zero arm).
     *
     * @throws Exception on transport or serialization
     */
    @Test
    void valuateForcePriceZeroQuantityAndStoreFallbackAndCustomerCode() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenReturn(response(null));
        TicketState.TicketItem forceZero = item("Z", "20", "10", "0");
        forceZero.valuedTotal = new BigDecimal("7.00");
        forceZero.modifierType = "FORCE_PRICE";
        forceZero.modifierValue = new BigDecimal("7.00");
        ArgumentCaptor<ValuationPayloads.BasketDto> captor =
                ArgumentCaptor.forClass(ValuationPayloads.BasketDto.class);
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            ValuationService.ValuationOutcome outcome =
                    service.valuate(ticketWith(forceZero), "CARD9", LocalDateTime.now());
            assertEquals("ENGINE", outcome.status);
            assertNull(outcome.engineTotalInclTax);
        }
        verify(valuationClient).valuate(captor.capture());
        ValuationPayloads.BasketDto basket = captor.getValue();
        assertEquals("CARD9", basket.customerCode);
        assertEquals("0000", basket.storeCode);
        assertEquals(new BigDecimal("7.00"), basket.items.get(0).manualForcedPrice);
    }

    /**
     * valuate degrades on an InterruptedException, restoring the interrupt
     * flag (InterruptedException catch arm).
     *
     * @throws Exception on the arranging path
     */
    @Test
    void valuateDegradesOnInterruptedException() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenThrow(new InterruptedException("stop"));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            ValuationService.ValuationOutcome outcome =
                    service.valuate(ticketWith(it), null, LocalDateTime.now());
            assertEquals("DEGRADED", outcome.status);
            assertNull(outcome.responseJson);
        }
        assertTrue(Thread.interrupted());
    }

    /**
     * valuate degrades on any other failure (generic Exception catch arm).
     *
     * @throws Exception on the arranging path
     */
    @Test
    void valuateDegradesOnGenericException() throws Exception {
        ValuationService service = newService();
        when(valuationClient.isEnabled()).thenReturn(true);
        when(valuationClient.valuate(any())).thenThrow(new RuntimeException("down"));
        TicketState.TicketItem it = item("L1", "1", "10", "1");
        try (MockedStatic<PanacheEntityBase> ms = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<Store> storeQuery = query(null);
            ms.when(Store::findAll).thenReturn(storeQuery);
            ValuationService.ValuationOutcome outcome =
                    service.valuate(ticketWith(it), null, LocalDateTime.now());
            assertEquals("DEGRADED", outcome.status);
            assertNull(outcome.engineTotalInclTax);
        }
    }

    /**
     * The EngineHints default field values are exposed as a plain data bundle
     * (constructor coverage of the nested type).
     */
    @Test
    void engineHintsAndOutcomeAreDataBundles() {
        ValuationService.EngineHints hints = new ValuationService.EngineHints();
        assertNull(hints.mealEligible);
        assertTrue(hints.upsells.isEmpty());
        ValuationService.ValuationOutcome outcome =
                new ValuationService.ValuationOutcome("ENGINE", "{}", new BigDecimal("1.00"));
        assertEquals("ENGINE", outcome.status);
        assertEquals("{}", outcome.responseJson);
        assertSame("{}", outcome.responseJson);
    }
}
