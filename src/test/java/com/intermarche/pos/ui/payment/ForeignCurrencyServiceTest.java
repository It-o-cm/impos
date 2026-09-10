package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.Currency;
import com.intermarche.pos.ui.PosState;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForeignCurrencyService} and the conversion arithmetic of
 * {@link Currency}.
 * <p>
 * Every refusal has its own leg here: no currency administered at all, none
 * selected, a code the referential does not know or has deactivated, a null or
 * non-positive amount, and the misconfigured rate that turns a handful of notes
 * into less than a cent. The nominal path checks that the euro value handed to the
 * payment service is the amount times the administered rate, rounded to the cent,
 * and that opening the panel closes the customer-credit one — both bind the same
 * on-screen keypad and only one may be up. The conversion itself is checked in both
 * directions, including the zero rate a corrupt row could leave behind.
 */
class ForeignCurrencyServiceTest {

    /** The Swiss franc, at a rate that is not a round number. */
    private static final BigDecimal RATE = new BigDecimal("1.053000");

    /**
     * Builds a service whose payment collaborator is a mock.
     *
     * @return the wired service
     */
    private ForeignCurrencyService newService() {
        ForeignCurrencyService service = new ForeignCurrencyService();
        service.paymentService = mock(PaymentService.class);
        return service;
    }

    /**
     * Assembles a POS state whose payment sub-state is real and whose remaining due
     * is the given amount.
     *
     * @param remaining the amount still due on the sale
     * @return the wired state mock
     */
    private PosState newState(String remaining) {
        PosState state = mock(PosState.class);
        state.payment = new PaymentState();
        when(state.getRemaining()).thenReturn(new BigDecimal(remaining));
        return state;
    }

    /**
     * Builds an active currency.
     *
     * @param code the ISO code
     * @param rate the euros-for-one-unit rate
     * @return the assembled currency
     */
    private Currency newCurrency(String code, BigDecimal rate) {
        Currency currency = new Currency();
        currency.id = 1L;
        currency.code = code;
        currency.label = "FRANC SUISSE";
        currency.symbol = "CHF";
        currency.euroPerUnit = rate;
        currency.active = true;
        return currency;
    }

    /**
     * Builds a Panache query returning the given first result.
     *
     * @param <T> the entity type
     * @param result the first result, possibly null
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryReturning(T result) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.firstResult()).thenReturn(result);
        return query;
    }

    // --------------------------------------------------
    // Currency arithmetic
    // --------------------------------------------------

    /**
     * A currency amount becomes euros at the administered rate, rounded to the cent.
     */
    @Test
    void conversionToEuroAppliesTheRate() {
        Currency chf = newCurrency("CHF", RATE);
        assertEquals(0, new BigDecimal("52.65").compareTo(chf.toEuro(new BigDecimal("50.00"))));
    }

    /**
     * A null amount converts to zero rather than throwing at the till.
     */
    @Test
    void conversionOfNullIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(newCurrency("CHF", RATE).toEuro(null)));
    }

    /**
     * The reverse conversion answers what the sale costs in the customer's own
     * money ({@code LC-07-14-03}).
     */
    @Test
    void conversionFromEuroAnswersTheAmountDue() {
        Currency chf = newCurrency("CHF", RATE);
        assertEquals(0, new BigDecimal("28.49").compareTo(chf.fromEuro(new BigDecimal("30.00"))));
    }

    /**
     * A null euro amount converts back to zero.
     */
    @Test
    void reverseConversionOfNullIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(newCurrency("CHF", RATE).fromEuro(null)));
    }

    /**
     * A zero rate, which only a corrupt referential row could leave behind, answers
     * zero instead of dividing by it.
     */
    @Test
    void reverseConversionWithAZeroRateIsZero() {
        Currency broken = newCurrency("CHF", BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(broken.fromEuro(new BigDecimal("30.00"))));
    }

    /**
     * A null rate takes the same leg.
     */
    @Test
    void reverseConversionWithANullRateIsZero() {
        Currency broken = newCurrency("CHF", null);
        assertEquals(0, BigDecimal.ZERO.compareTo(broken.fromEuro(new BigDecimal("30.00"))));
    }

    /**
     * The display unit is the symbol when the shop gave one.
     */
    @Test
    void displayUnitPrefersTheSymbol() {
        assertEquals("CHF", newCurrency("CHF", RATE).displayUnit());
    }

    /**
     * With no symbol the ISO code is printed instead.
     */
    @Test
    void displayUnitFallsBackToTheCode() {
        Currency currency = newCurrency("USD", RATE);
        currency.symbol = "  ";
        assertEquals("USD", currency.displayUnit());
    }

    // --------------------------------------------------
    // The panel
    // --------------------------------------------------

    /**
     * Opening the panel with currencies administered leaves no error and closes the
     * customer-credit panel, which binds the same keypad.
     */
    @Test
    void openingThePanelClosesTheCreditOne() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        state.payment.creditPanelOpen = true;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.list(anyString()))
                    .thenReturn(List.of(newCurrency("CHF", RATE)));
            service.openPanel(state);
        }
        assertTrue(state.payment.currencyPanelOpen);
        assertFalse(state.payment.creditPanelOpen);
        assertNull(state.payment.currencyError);
    }

    /**
     * Opening the panel in a shop that administers no currency says so at once,
     * rather than showing an empty list the operator has to interpret.
     */
    @Test
    void openingThePanelWithoutCurrenciesSaysSo() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.list(anyString())).thenReturn(List.of());
            service.openPanel(state);
        }
        assertEquals(ForeignCurrencyService.NO_CURRENCY, state.payment.currencyError);
    }

    /**
     * Closing the panel forgets the selected currency.
     */
    @Test
    void closingThePanelForgetsTheSelection() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        state.payment.currencyPanelOpen = true;
        state.payment.selectedCurrency = newCurrency("CHF", RATE);
        service.closePanel(state);
        assertFalse(state.payment.currencyPanelOpen);
        assertNull(state.payment.selectedCurrency);
    }

    /**
     * A known code selects the currency, which is what reveals the rate.
     */
    @Test
    void aKnownCodeSelectsTheCurrency() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        Currency chf = newCurrency("CHF", RATE);
        PanacheQuery<Currency> query = queryReturning(chf);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.find("code = ?1 and active = true", "CHF"))
                    .thenReturn(query);
            service.selectCurrency(state, " chf ");
        }
        assertSame(chf, state.payment.selectedCurrency);
        assertNull(state.payment.currencyError);
    }

    /**
     * A code the referential does not know — or has deactivated — selects nothing.
     */
    @Test
    void anUnknownCodeSelectsNothing() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        PanacheQuery<Currency> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> Currency.find("code = ?1 and active = true", "XXX"))
                    .thenReturn(query);
            service.selectCurrency(state, "XXX");
        }
        assertNull(state.payment.selectedCurrency);
        assertEquals(ForeignCurrencyService.NO_SELECTION, state.payment.currencyError);
    }

    /**
     * A null code takes the same leg, without a lookup.
     */
    @Test
    void aNullCodeSelectsNothing() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        service.selectCurrency(state, null);
        assertEquals(ForeignCurrencyService.NO_SELECTION, state.payment.currencyError);
    }

    /**
     * A blank code takes the same leg — the second condition of the same guard.
     */
    @Test
    void aBlankCodeSelectsNothing() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        service.selectCurrency(state, "   ");
        assertEquals(ForeignCurrencyService.NO_SELECTION, state.payment.currencyError);
    }

    // --------------------------------------------------
    // Settling
    // --------------------------------------------------

    /**
     * Settling before a currency is chosen is refused.
     */
    @Test
    void settlingWithoutACurrencyIsRefused() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        assertFalse(service.processCurrency(state, new BigDecimal("50.00")));
        assertEquals(ForeignCurrencyService.NO_SELECTION, state.payment.currencyError);
        verify(service.paymentService, never()).processForeignCurrency(any(), any(), any(), any());
    }

    /**
     * A null amount is refused.
     */
    @Test
    void aNullAmountIsRefused() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        state.payment.selectedCurrency = newCurrency("CHF", RATE);
        assertFalse(service.processCurrency(state, null));
        assertEquals(ForeignCurrencyService.BAD_AMOUNT, state.payment.currencyError);
    }

    /**
     * A zero amount is refused — the second condition of the same guard.
     */
    @Test
    void aZeroAmountIsRefused() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        state.payment.selectedCurrency = newCurrency("CHF", RATE);
        assertFalse(service.processCurrency(state, BigDecimal.ZERO));
        assertEquals(ForeignCurrencyService.BAD_AMOUNT, state.payment.currencyError);
    }

    /**
     * A rate so small that what was handed over is worth less than a cent is a
     * misconfigured rate, and refusing says so rather than swallowing the money.
     */
    @Test
    void anAmountWorthLessThanACentIsRefused() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        state.payment.selectedCurrency = newCurrency("XXX", new BigDecimal("0.000001"));
        assertFalse(service.processCurrency(state, new BigDecimal("1.00")));
        assertEquals(ForeignCurrencyService.BAD_AMOUNT, state.payment.currencyError);
        verify(service.paymentService, never()).processForeignCurrency(any(), any(), any(), any());
    }

    /**
     * A settlement is handed to the payment service with its euro value, and the
     * panel closes behind it.
     */
    @Test
    void aSettlementIsRegisteredAtItsEuroValue() {
        ForeignCurrencyService service = newService();
        PosState state = newState("30.00");
        Currency chf = newCurrency("CHF", RATE);
        state.payment.selectedCurrency = chf;
        state.payment.currencyPanelOpen = true;
        assertTrue(service.processCurrency(state, new BigDecimal("50.00")));
        verify(service.paymentService).processForeignCurrency(eq(state), eq(chf),
                eq(new BigDecimal("50.00")), eq(new BigDecimal("52.65")));
        assertFalse(state.payment.currencyPanelOpen);
        assertNull(state.payment.selectedCurrency);
    }
}
