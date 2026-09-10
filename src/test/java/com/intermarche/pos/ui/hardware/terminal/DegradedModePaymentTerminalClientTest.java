package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DegradedModePaymentTerminalClient} (BO-03-12-05).
 * <p>
 * Branch enumeration (every arm exercised — 100%): the single
 * {@code active()} ternary (degraded / not-degraded) is exercised through
 * {@code requestDebit}, {@code requestCredit}, {@code abort} and
 * {@code name}; the session hooks always forward to the configured terminal
 * regardless of the toggle, so each is proved with the toggle on to show it
 * does not follow {@code active()}.
 */
class DegradedModePaymentTerminalClientTest {

    /** The configured (non-degraded) terminal mock. */
    private final PaymentTerminalClient configured = mock(PaymentTerminalClient.class);

    /** The auto-accept terminal mock reached while degraded. */
    private final PaymentTerminalClient autoAccept = mock(PaymentTerminalClient.class);

    /** The settings catalog mock holding the toggle. */
    private final PosSettingsService posSettingsService = mock(PosSettingsService.class);

    /** The register state carrying the operator's own forcing (LC-07-08-02). */
    private final com.intermarche.pos.ui.PosState state = new com.intermarche.pos.ui.PosState();

    /** The gate under test. */
    private final DegradedModePaymentTerminalClient gate =
            new DegradedModePaymentTerminalClient(configured, autoAccept, posSettingsService, state);

    /**
     * {@code configured} returns the wrapped configured terminal, as used by
     * the producer's own tests.
     */
    @Test
    void configuredReturnsTheWrappedTerminal() {
        assertEquals(configured, gate.configured());
    }

    /**
     * {@code requestDebit} reaches the configured terminal when degraded
     * mode is off (not-degraded arm).
     */
    @Test
    void requestDebitReachesConfiguredWhenNotDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestDebit(amount, callback);
        verify(configured).requestDebit(amount, callback);
        verifyNoInteractions(autoAccept);
    }

    /**
     * {@code requestDebit} reaches the auto-accept terminal when degraded mode
     * is on (degraded arm), wrapping the callback so the outcome gets stamped;
     * the register's own callback is NOT passed through verbatim.
     */
    @Test
    void requestDebitReachesAutoAcceptWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        gate.requestDebit(amount, callback);
        verify(autoAccept).requestDebit(eq(amount), captor.capture());
        verify(configured, never()).requestDebit(eq(amount), captor.capture());
        assertNotSame(callback, captor.getValue());
    }

    /**
     * {@code requestCredit} reaches the configured terminal when degraded
     * mode is off (not-degraded arm).
     */
    @Test
    void requestCreditReachesConfiguredWhenNotDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        BigDecimal amount = new BigDecimal("4.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestCredit(amount, callback);
        verify(configured).requestCredit(amount, callback);
        verifyNoInteractions(autoAccept);
    }

    /**
     * {@code requestCredit} reaches the auto-accept terminal when degraded mode
     * is on (degraded arm), wrapping the callback so the outcome gets stamped.
     */
    @Test
    void requestCreditReachesAutoAcceptWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        BigDecimal amount = new BigDecimal("4.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        gate.requestCredit(amount, callback);
        verify(autoAccept).requestCredit(eq(amount), captor.capture());
        verify(configured, never()).requestCredit(eq(amount), captor.capture());
        assertNotSame(callback, captor.getValue());
    }

    /**
     * The wrapped callback's accept leg stamps the outcome degraded and
     * forwards it to the register's callback (BO-04-01-47/49).
     */
    @Test
    void degradedWrapperStampsAcceptedOutcome() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        gate.requestDebit(new BigDecimal("10.00"), callback);
        verify(autoAccept).requestDebit(eq(new BigDecimal("10.00")), captor.capture());
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("10.00"));
        captor.getValue().onAccepted(outcome);
        assertTrue(outcome.degradedMode);
        verify(callback).onAccepted(outcome);
    }

    /**
     * The wrapped callback's refuse leg stamps the outcome degraded and
     * forwards it (BO-04-01-47/49).
     */
    @Test
    void degradedWrapperStampsRefusedOutcome() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        gate.requestDebit(new BigDecimal("10.00"), callback);
        verify(autoAccept).requestDebit(eq(new BigDecimal("10.00")), captor.capture());
        TerminalOutcome outcome = TerminalOutcome.ofAmount(new BigDecimal("10.00"));
        captor.getValue().onRefused(outcome);
        assertTrue(outcome.degradedMode);
        verify(callback).onRefused(outcome);
    }

    /**
     * The wrapped callback's error leg carries no outcome to stamp and is
     * forwarded unchanged.
     */
    @Test
    void degradedWrapperForwardsError() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalTransactionCallback> captor =
                ArgumentCaptor.forClass(TerminalTransactionCallback.class);
        gate.requestDebit(new BigDecimal("10.00"), callback);
        verify(autoAccept).requestDebit(eq(new BigDecimal("10.00")), captor.capture());
        captor.getValue().onError("TPE INJOIGNABLE");
        verify(callback).onError("TPE INJOIGNABLE");
    }

    /**
     * {@code abort} reaches the configured terminal when degraded mode is
     * off (not-degraded arm).
     */
    @Test
    void abortReachesConfiguredWhenNotDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        gate.abort();
        verify(configured).abort();
        verifyNoInteractions(autoAccept);
    }

    /**
     * {@code abort} reaches the auto-accept terminal when degraded mode is
     * on (degraded arm).
     */
    @Test
    void abortReachesAutoAcceptWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        gate.abort();
        verify(autoAccept).abort();
        verify(configured, never()).abort();
    }

    /**
     * {@code name} returns the configured terminal's name when degraded
     * mode is off (not-degraded arm).
     */
    @Test
    void nameReturnsConfiguredNameWhenNotDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        when(configured.name()).thenReturn("virtual");
        assertEquals("virtual", gate.name());
    }

    /**
     * {@code name} returns the auto-accept terminal's name when degraded
     * mode is on (degraded arm).
     */
    @Test
    void nameReturnsAutoAcceptNameWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        when(autoAccept.name()).thenReturn("auto");
        assertEquals("auto", gate.name());
    }

    /**
     * {@code onRegisterOpened} always forwards to the configured terminal,
     * even while degraded mode is on.
     */
    @Test
    void onRegisterOpenedAlwaysForwardsToConfigured() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        gate.onRegisterOpened();
        verify(configured).onRegisterOpened();
        verifyNoInteractions(autoAccept);
    }

    /**
     * {@code onRegisterClosed} always forwards to the configured terminal,
     * even while degraded mode is on.
     */
    @Test
    void onRegisterClosedAlwaysForwardsToConfigured() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        gate.onRegisterClosed();
        verify(configured).onRegisterClosed();
        verifyNoInteractions(autoAccept);
    }

    /**
     * The operator's own forcing (LC-07-08-02) bypasses the monetics on its own,
     * without the shop-wide parameter being on — the second leg of the predicate.
     */
    @Test
    void requestDebitReachesAutoAcceptWhenTheOperatorForcedIt() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().plusMinutes(30);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestDebit(amount, callback);
        verify(autoAccept).requestDebit(eq(amount), any(TerminalTransactionCallback.class));
        verifyNoInteractions(configured);
    }

    /**
     * A forcing whose delay has run out no longer bypasses anything: the request
     * goes back to the configured terminal, with no gesture needed to put it there.
     */
    @Test
    void requestDebitReachesConfiguredOnceTheForcingExpired() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().minusMinutes(1);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestDebit(amount, callback);
        verify(configured).requestDebit(amount, callback);
        verifyNoInteractions(autoAccept);
    }

    /**
     * The forcing applies to a credit exactly as it does to a debit.
     */
    @Test
    void requestCreditReachesAutoAcceptWhenTheOperatorForcedIt() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(false);
        state.moneticsDegradedUntil = java.time.LocalDateTime.now().plusMinutes(30);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestCredit(amount, callback);
        verify(autoAccept).requestCredit(eq(amount), any(TerminalTransactionCallback.class));
        verifyNoInteractions(configured);
    }
}
