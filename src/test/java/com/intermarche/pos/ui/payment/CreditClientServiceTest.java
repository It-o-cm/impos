package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.AccountCustomer;
import com.intermarche.pos.domain.store.Address;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.sync.RefPullService;
import com.intermarche.pos.service.sync.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreditClientService}.
 * <p>
 * Customer credit is the one method where the goods leave without anything being
 * collected, and every guard here exists because of that. The four refusals are
 * checked one leg at a time — no account named, an account the back office granted
 * no ceiling, a client referential too old to be trusted, and the ceiling itself —
 * and so are the three legs that make a referential "degraded" (no store node, the
 * shop allowing it anyway, a pull that never completed or completed too long ago).
 * The ceiling is the only refusal a supervisor can pass, and when they do it is the
 * BALANCE that moves; both arms of that authorization are covered, as is the
 * connected-supervisor shortcut which short-circuits the credential check. Panache
 * finders are neutralized on {@link PanacheEntityBase}; every collaborator is a
 * Mockito mock, so nothing here touches a database or a screen.
 */
class CreditClientServiceTest {

    /** The account number used throughout. */
    private static final String NUMBER = "C-0001";

    /** The business name shown and printed. */
    private static final String NAME = "MAIRIE DE VAUCRESSON";

    /**
     * Builds a service whose collaborators are all mocks, wired for the nominal
     * case: a store node present, a fresh pull, credit refused in degraded mode.
     *
     * @return the wired service
     */
    private CreditClientService newService() {
        CreditClientService service = new CreditClientService();
        service.paymentService = mock(PaymentService.class);
        service.posSettingsService = mock(PosSettingsService.class);
        service.syncOutboxService = mock(SyncOutboxService.class);
        service.refPullService = mock(RefPullService.class);
        service.endorsementService = mock(EndorsementService.class);
        when(service.syncOutboxService.isEnabled()).thenReturn(true);
        when(service.posSettingsService.creditAllowedInDegraded()).thenReturn(false);
        when(service.posSettingsService.creditDegradedAfterMinutes()).thenReturn(60);
        // BO-03-06-52: no account range administered by default, so every case
        // below keeps accepting the numbers it always did.
        when(service.posSettingsService.customerAccountPattern()).thenReturn("");
        when(service.refPullService.getLastSuccessfulPull())
                .thenReturn(LocalDateTime.now().minusMinutes(1));
        return service;
    }

    /**
     * Assembles a POS state whose payment sub-state is real (the panel is what the
     * assertions read) and whose remaining due is the given amount.
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
     * Builds an account with the given ceiling and outstanding balance.
     *
     * <p>A SPY and not a plain instance: the service charges the account and calls
     * {@code persist()} on it, which needs a live Panache session. A spy keeps every
     * real method — the ceiling and availability computations the assertions rely on
     * — while that one call becomes a no-op, whereas a plain mock would answer false
     * to {@code isCreditAllowed()} and make every test meaningless.
     *
     * @param limit the administered ceiling, or null when credit is not granted
     * @param balance the outstanding balance
     * @return the assembled account
     */
    private AccountCustomer newCustomer(String limit, String balance) {
        AccountCustomer customer = new AccountCustomer();
        customer.id = 7L;
        customer.accountNumber = NUMBER;
        customer.companyName = NAME;
        customer.address = new Address();
        customer.creditLimit = limit == null ? null : new BigDecimal(limit);
        customer.creditBalance = new BigDecimal(balance);
        AccountCustomer spied = spy(customer);
        doNothing().when(spied).persist();
        return spied;
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

    /**
     * Builds a Panache query returning the given page of results.
     *
     * @param <T> the entity type
     * @param results the page content
     * @return the query mock
     */
    @SuppressWarnings("unchecked")
    private <T> PanacheQuery<T> queryListing(List<T> results) {
        PanacheQuery<T> query = mock(PanacheQuery.class);
        when(query.page(0, 20)).thenReturn(query);
        when(query.list()).thenReturn(results);
        return query;
    }

    // --------------------------------------------------
    // Naming the account
    // --------------------------------------------------

    /**
     * Opening the panel forgets whatever the previous sale left on it.
     */
    @Test
    void openingThePanelStartsClean() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        state.payment.creditCustomer = newCustomer("100.00", "0.00");
        state.payment.creditError = "VIEILLE ERREUR";
        service.openPanel(state);
        assertTrue(state.payment.creditPanelOpen);
        assertNull(state.payment.creditCustomer);
        assertNull(state.payment.creditError);
    }

    /**
     * Closing the panel forgets the account and any held-back amount: an
     * authorization that outlived the panel would apply to the next thing typed.
     */
    @Test
    void closingThePanelForgetsEverything() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        state.payment.creditPanelOpen = true;
        state.payment.creditCustomer = newCustomer("100.00", "0.00");
        state.payment.creditPendingAmount = new BigDecimal("10.00");
        service.closePanel(state);
        assertFalse(state.payment.creditPanelOpen);
        assertNull(state.payment.creditCustomer);
        assertNull(state.payment.creditPendingAmount);
    }

    /**
     * An empty account number is refused before any lookup.
     */
    @Test
    void blankAccountNumberIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        service.selectByNumber(state, "  ");
        assertEquals("NUMERO DE COMPTE REQUIS", state.payment.creditError);
        assertNull(state.payment.creditCustomer);
    }

    /**
     * A null account number takes the same leg as a blank one.
     */
    @Test
    void nullAccountNumberIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        service.selectByNumber(state, null);
        assertEquals("NUMERO DE COMPTE REQUIS", state.payment.creditError);
    }

    /**
     * An account number matching nothing says so, and names nobody.
     */
    @Test
    void unknownAccountNumberIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        PanacheQuery<AccountCustomer> query = queryReturning(null);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("accountNumber", "C-9999")).thenReturn(query);
            service.selectByNumber(state, "C-9999");
        }
        assertEquals("COMPTE INTROUVABLE : C-9999", state.payment.creditError);
        assertNull(state.payment.creditCustomer);
    }

    /**
     * A known account number names the account for confirmation.
     */
    @Test
    void knownAccountNumberNamesTheAccount() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        PanacheQuery<AccountCustomer> query = queryReturning(customer);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("accountNumber", NUMBER)).thenReturn(query);
            service.selectByNumber(state, "  " + NUMBER + "  ");
        }
        assertSame(customer, state.payment.creditCustomer);
        assertNull(state.payment.creditError);
    }

    /**
     * An account with no administered ceiling is named all the same, and said to be
     * unusable at once — knowing before typing an amount beats a refusal after.
     */
    @Test
    void accountWithoutCeilingIsNamedAndFlagged() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer(null, "0.00");
        PanacheQuery<AccountCustomer> query = queryReturning(customer);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find("accountNumber", NUMBER)).thenReturn(query);
            service.selectByNumber(state, NUMBER);
        }
        assertSame(customer, state.payment.creditCustomer);
        assertEquals(CreditClientService.NO_CREDIT_GRANTED, state.payment.creditError);
    }

    /**
     * A one-character search is refused: it would bring back the whole base.
     */
    @Test
    void tooShortSearchIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        service.searchByName(state, "M");
        assertEquals("SAISIR AU MOINS 2 CARACTERES", state.payment.creditError);
        assertFalse(state.payment.creditSearched);
    }

    /**
     * A null search takes the same leg as a too-short one.
     */
    @Test
    void nullSearchIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        service.searchByName(state, null);
        assertEquals("SAISIR AU MOINS 2 CARACTERES", state.payment.creditError);
    }

    /**
     * A search matching nothing is a searched-and-empty list, which the panel can
     * tell from "not searched yet".
     */
    @Test
    void searchWithoutMatchIsMarkedAsSearched() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        PanacheQuery<AccountCustomer> query = queryListing(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find(anyString(), any(Object.class)))
                    .thenReturn(query);
            service.searchByName(state, "ZZZ");
        }
        assertTrue(state.payment.creditSearched);
        assertTrue(state.payment.isCreditSearchWithoutMatch());
        assertNull(state.payment.creditCustomer);
    }

    /**
     * A search matching exactly one account names it straight away
     * ({@code LC-07-09-08}).
     */
    @Test
    void singleMatchIsNamedStraightAway() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        PanacheQuery<AccountCustomer> query = queryListing(List.of(customer));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find(anyString(), any(Object.class)))
                    .thenReturn(query);
            service.searchByName(state, "MAIRIE");
        }
        assertSame(customer, state.payment.creditCustomer);
    }

    /**
     * Several matches are offered for the operator to pick from, none named yet.
     */
    @Test
    void severalMatchesAreOffered() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer first = newCustomer("1000.00", "0.00");
        AccountCustomer second = newCustomer("500.00", "0.00");
        PanacheQuery<AccountCustomer> query = queryListing(List.of(first, second));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.find(anyString(), any(Object.class)))
                    .thenReturn(query);
            service.searchByName(state, "MAIRIE");
        }
        assertNull(state.payment.creditCustomer);
        assertEquals(2, state.payment.creditCustomers.size());
    }

    /**
     * Picking an account that no longer exists says so rather than naming null.
     */
    @Test
    void pickingAVanishedAccountIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(99L)).thenReturn(null);
            service.selectById(state, 99L);
        }
        assertEquals("COMPTE INTROUVABLE", state.payment.creditError);
    }

    /**
     * A null id takes the same leg, without a lookup.
     */
    @Test
    void pickingWithoutAnIdIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        service.selectById(state, null);
        assertEquals("COMPTE INTROUVABLE", state.payment.creditError);
    }

    /**
     * Picking a listed account names it and empties the result list.
     */
    @Test
    void pickingAnAccountNamesIt() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomers.add(customer);
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            service.selectById(state, 7L);
        }
        assertSame(customer, state.payment.creditCustomer);
        assertTrue(state.payment.creditCustomers.isEmpty());
    }

    // --------------------------------------------------
    // Charging the account
    // --------------------------------------------------

    /**
     * Charging without an account named is refused ({@code LC-07-09-02}).
     */
    @Test
    void chargingWithoutAnAccountIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        assertFalse(service.processCredit(state, new BigDecimal("10.00")));
        assertEquals(CreditClientService.NO_ACCOUNT, state.payment.creditError);
        verify(service.paymentService, never()).processCredit(any(), any(), any(), eq(false));
    }

    /**
     * Charging an account the back office granted no ceiling is refused.
     */
    @Test
    void chargingAnAccountWithoutCeilingIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        state.payment.creditCustomer = newCustomer(null, "0.00");
        assertFalse(service.processCredit(state, new BigDecimal("10.00")));
        assertEquals(CreditClientService.NO_CREDIT_GRANTED, state.payment.creditError);
    }

    /**
     * With a store node configured, the shop's setting refusing it and a pull that
     * never completed, the referential is degraded and credit is refused
     * ({@code LC-07-09-05}).
     */
    @Test
    void neverPulledIsDegradedAndRefused() {
        CreditClientService service = newService();
        when(service.refPullService.getLastSuccessfulPull()).thenReturn(null);
        PosState state = newState("10.00");
        state.payment.creditCustomer = newCustomer("1000.00", "0.00");
        assertFalse(service.processCredit(state, new BigDecimal("10.00")));
        assertEquals(CreditClientService.DEGRADED, state.payment.creditError);
    }

    /**
     * A pull older than the tolerated staleness is degraded too.
     */
    @Test
    void staleReferentialIsDegradedAndRefused() {
        CreditClientService service = newService();
        when(service.refPullService.getLastSuccessfulPull())
                .thenReturn(LocalDateTime.now().minusMinutes(120));
        PosState state = newState("10.00");
        state.payment.creditCustomer = newCustomer("1000.00", "0.00");
        assertFalse(service.processCredit(state, new BigDecimal("10.00")));
        assertEquals(CreditClientService.DEGRADED, state.payment.creditError);
    }

    /**
     * A register with NO store node is never degraded: it is not supposed to pull,
     * so a stale-referential refusal there would be about a link it never had.
     */
    @Test
    void standaloneRegisterIsNotDegraded() {
        CreditClientService service = newService();
        when(service.syncOutboxService.isEnabled()).thenReturn(false);
        when(service.refPullService.getLastSuccessfulPull()).thenReturn(null);
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("10.00")));
        }
    }

    /**
     * A shop that accepts the risk keeps credit available on a stale referential.
     */
    @Test
    void shopAllowingDegradedKeepsCreditAvailable() {
        CreditClientService service = newService();
        when(service.posSettingsService.creditAllowedInDegraded()).thenReturn(true);
        when(service.refPullService.getLastSuccessfulPull()).thenReturn(null);
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("10.00")));
        }
    }

    /**
     * Nothing left to settle means nothing to charge.
     */
    @Test
    void nothingDueChargesNothing() {
        CreditClientService service = newService();
        PosState state = newState("0.00");
        state.payment.creditCustomer = newCustomer("1000.00", "0.00");
        assertFalse(service.processCredit(state, new BigDecimal("10.00")));
        verify(service.paymentService, never()).processCredit(any(), any(), any(), eq(false));
    }

    /**
     * A settlement within the ceiling is registered and charged to the account.
     */
    @Test
    void settlementWithinTheCeilingIsRegistered() {
        CreditClientService service = newService();
        PosState state = newState("40.00");
        AccountCustomer customer = newCustomer("1000.00", "100.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("40.00")));
        }
        verify(service.paymentService).processCredit(eq(state), eq(customer),
                eq(new BigDecimal("40.00")), eq(false));
        assertEquals(0, new BigDecimal("140.00").compareTo(customer.creditBalance));
        assertFalse(state.payment.creditPanelOpen);
    }

    /**
     * A settlement landing EXACTLY on the ceiling passes: the rule is "over", not
     * "at".
     */
    @Test
    void settlementExactlyOnTheCeilingPasses() {
        CreditClientService service = newService();
        PosState state = newState("100.00");
        AccountCustomer customer = newCustomer("1000.00", "900.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("100.00")));
        }
        verify(service.paymentService).processCredit(any(), any(), any(), eq(false));
    }

    /**
     * A blank amount charges the whole remaining due — the ordinary case.
     */
    @Test
    void noAmountChargesTheWholeDue() {
        CreditClientService service = newService();
        PosState state = newState("57.30");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, null));
        }
        verify(service.paymentService).processCredit(any(), any(),
                eq(new BigDecimal("57.30")), eq(false));
    }

    /**
     * An amount above the remaining due is capped at it: credit does not overpay,
     * there is no change to give back on a debt.
     */
    @Test
    void amountAboveTheDueIsCapped() {
        CreditClientService service = newService();
        PosState state = newState("20.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("999.00")));
        }
        verify(service.paymentService).processCredit(any(), any(),
                eq(new BigDecimal("20.00")), eq(false));
    }

    /**
     * A non-null but non-positive typed amount charges the whole remaining due: the
     * "signum &lt;= 0" leg of the amount guard, distinct from the null-amount leg
     * already covered.
     */
    @Test
    void nonPositiveTypedAmountChargesTheWholeDue() {
        CreditClientService service = newService();
        PosState state = newState("57.30");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("0.00")));
        }
        verify(service.paymentService).processCredit(any(), any(),
                eq(new BigDecimal("57.30")), eq(false));
    }

    /**
     * Charging an account whose stored balance is null starts it from zero rather
     * than throwing — the null arm of the balance carried forward in
     * {@code chargeAccount} and, in passing, of {@code exceedsCeiling}.
     */
    @Test
    void chargingAnAccountWithNullBalanceStartsFromZero() {
        CreditClientService service = newService();
        PosState state = newState("30.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        customer.creditBalance = null;
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.processCredit(state, new BigDecimal("30.00")));
        }
        verify(service.paymentService).processCredit(any(), any(),
                eq(new BigDecimal("30.00")), eq(false));
        assertEquals(0, new BigDecimal("30.00").compareTo(customer.creditBalance));
    }

    /**
     * An account whose balance is null is treated as owing nothing when the ceiling
     * is tested, and the held-back message formats that null balance as zero — the
     * null arm of {@code exceedsCeiling} and the null arm of the amount formatter.
     */
    @Test
    void nullBalanceOverTheCeilingIsHeldBackAndFormattedAsZero() {
        CreditClientService service = newService();
        PosState state = newState("200.00");
        AccountCustomer customer = newCustomer("100.00", "0.00");
        customer.creditBalance = null;
        state.payment.creditCustomer = customer;
        assertFalse(service.processCredit(state, new BigDecimal("200.00")));
        assertTrue(state.payment.creditError.contains("PLAFOND DEPASSE"));
        assertTrue(state.payment.creditError.contains("ENCOURS 0.00"));
        assertEquals(0, new BigDecimal("200.00").compareTo(state.payment.creditPendingAmount));
    }

    /**
     * A charge taking the account over its ceiling is HELD BACK, not lost: the
     * amount survives for the supervisor to allow ({@code LC-07-09-03/04}).
     */
    @Test
    void overTheCeilingIsHeldBack() {
        CreditClientService service = newService();
        PosState state = newState("200.00");
        AccountCustomer customer = newCustomer("10000.00", "9900.00");
        state.payment.creditCustomer = customer;
        assertFalse(service.processCredit(state, new BigDecimal("200.00")));
        assertEquals(0, new BigDecimal("200.00").compareTo(state.payment.creditPendingAmount));
        assertTrue(state.payment.isCreditOverLimitPending());
        assertTrue(state.payment.creditError.contains("PLAFOND DEPASSE"));
        verify(service.paymentService, never()).processCredit(any(), any(), any(), eq(false));
        assertEquals(0, new BigDecimal("9900.00").compareTo(customer.creditBalance));
    }

    // --------------------------------------------------
    // Passing the ceiling
    // --------------------------------------------------

    /**
     * Authorizing with nothing held back is refused rather than charging blind.
     */
    @Test
    void authorizingWithNothingHeldBackIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        state.payment.creditCustomer = newCustomer("100.00", "0.00");
        assertFalse(service.authorizeOverLimit(state, "chef", "mdp"));
        assertEquals(CreditClientService.NO_ACCOUNT, state.payment.creditError);
    }

    /**
     * Authorizing with an amount held back but no account is refused too — the
     * second leg of the same guard.
     */
    @Test
    void authorizingWithoutAnAccountIsRefused() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        state.payment.creditPendingAmount = new BigDecimal("10.00");
        assertFalse(service.authorizeOverLimit(state, "chef", "mdp"));
        assertEquals(CreditClientService.NO_ACCOUNT, state.payment.creditError);
    }

    /**
     * A credential the endorsement service rejects leaves the amount held back, so
     * a supervisor can still come and type theirs.
     */
    @Test
    void refusedCredentialKeepsTheAmountHeldBack() {
        CreditClientService service = newService();
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(false);
        when(service.endorsementService.authorize(anyString(), anyString(), anyString()))
                .thenReturn(false);
        PosState state = newState("200.00");
        state.payment.creditCustomer = newCustomer("10000.00", "9900.00");
        state.payment.creditPendingAmount = new BigDecimal("200.00");
        assertFalse(service.authorizeOverLimit(state, "stagiaire", "mdp"));
        assertEquals("AUTORISATION REFUSEE", state.payment.creditError);
        assertTrue(state.payment.isCreditOverLimitPending());
    }

    /**
     * A supervisor's credential charges the account over its ceiling, and marks the
     * settlement as authorized — the ceiling itself is untouched.
     */
    @Test
    void supervisorCredentialChargesOverTheCeiling() {
        CreditClientService service = newService();
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(false);
        when(service.endorsementService.authorize(eq("chef"), eq("mdp"),
                eq(CreditClientService.OVER_LIMIT_ACTION))).thenReturn(true);
        PosState state = newState("200.00");
        AccountCustomer customer = newCustomer("10000.00", "9900.00");
        state.payment.creditCustomer = customer;
        state.payment.creditPendingAmount = new BigDecimal("200.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.authorizeOverLimit(state, "chef", "mdp"));
        }
        verify(service.paymentService).processCredit(eq(state), eq(customer),
                eq(new BigDecimal("200.00")), eq(true));
        assertEquals(0, new BigDecimal("10100.00").compareTo(customer.creditBalance));
        assertEquals(0, new BigDecimal("10000.00").compareTo(customer.creditLimit));
    }

    /**
     * A logged operator who is already a supervisor authorizes without a second
     * credential — the first leg of the compound grant, which short-circuits the
     * credential check entirely.
     */
    @Test
    void connectedSupervisorAuthorizesWithoutCredential() {
        CreditClientService service = newService();
        when(service.endorsementService.operatorIsSupervisor(any())).thenReturn(true);
        PosState state = newState("200.00");
        AccountCustomer customer = newCustomer("10000.00", "9900.00");
        state.payment.creditCustomer = customer;
        state.payment.creditPendingAmount = new BigDecimal("200.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(customer);
            assertTrue(service.authorizeOverLimit(state, null, null));
        }
        verify(service.endorsementService, never()).authorize(any(), any(), any());
        verify(service.paymentService).processCredit(any(), any(), any(), eq(true));
    }

    /**
     * Giving up on the held-back amount keeps the account named, so the operator can
     * simply type a smaller one.
     */
    @Test
    void cancellingTheOverrunKeepsTheAccount() {
        CreditClientService service = newService();
        PosState state = newState("200.00");
        AccountCustomer customer = newCustomer("10000.00", "9900.00");
        state.payment.creditCustomer = customer;
        state.payment.creditPendingAmount = new BigDecimal("200.00");
        state.payment.creditError = "PLAFOND DEPASSE";
        service.cancelOverLimit(state);
        assertNull(state.payment.creditPendingAmount);
        assertNull(state.payment.creditError);
        assertSame(customer, state.payment.creditCustomer);
    }

    /**
     * Charging an account that vanished from the database between the naming and the
     * settlement leaves the balance alone rather than throwing at the till.
     */
    @Test
    void chargingAVanishedAccountDoesNotThrow() {
        CreditClientService service = newService();
        PosState state = newState("10.00");
        AccountCustomer customer = newCustomer("1000.00", "0.00");
        state.payment.creditCustomer = customer;
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> AccountCustomer.findById(7L)).thenReturn(null);
            assertTrue(service.processCredit(state, new BigDecimal("10.00")));
        }
        verify(service.paymentService).processCredit(any(), any(), any(), eq(false));
    }

    // --- Plage des numéros de compte client (BO-03-06-52) ---

    /**
     * A number OUTSIDE the administered range is refused before the database is
     * even asked, with a message naming the real problem.
     */
    @Test
    void aNumberOutsideTheAdministeredRangeIsRefused() {
        CreditClientService service = newService();
        when(service.posSettingsService.customerAccountPattern()).thenReturn("^CC\\d{4}$");
        PosState state = newState("10.00");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            service.selectByNumber(state, "2990000000019");
            panache.verifyNoInteractions();
        }
        assertEquals("NUMERO DE COMPTE HORS PLAGE : 2990000000019", state.payment.creditError);
    }

    /**
     * A number INSIDE the administered range reaches the database as before.
     */
    @Test
    void aNumberInsideTheAdministeredRangeIsLookedUp() {
        CreditClientService service = newService();
        when(service.posSettingsService.customerAccountPattern()).thenReturn("^CC\\d{4}$");
        assertTrue(service.matchesAccountRange("CC1234"));
        assertFalse(service.matchesAccountRange("CC12345"));
    }

    /**
     * A SECOND administered range accepts another shape, which is what proves
     * the setting is read rather than a literal returned; padding is trimmed.
     */
    @Test
    void aSecondAdministeredRangeAcceptsAnotherShape() {
        CreditClientService service = newService();
        when(service.posSettingsService.customerAccountPattern()).thenReturn("  ^\\d{8}$  ");
        assertTrue(service.matchesAccountRange("12345678"));
        assertFalse(service.matchesAccountRange("CC1234"));
    }

    /**
     * A BLANK administered range accepts every number — the leg a null check
     * alone would miss — and a NULL one behaves the same.
     */
    @Test
    void anEmptyAdministeredRangeAcceptsEveryNumber() {
        CreditClientService blank = newService();
        when(blank.posSettingsService.customerAccountPattern()).thenReturn("   ");
        assertTrue(blank.matchesAccountRange("n'importe quoi"));
        CreditClientService missing = newService();
        when(missing.posSettingsService.customerAccountPattern()).thenReturn(null);
        assertTrue(missing.matchesAccountRange("n'importe quoi"));
    }
}
