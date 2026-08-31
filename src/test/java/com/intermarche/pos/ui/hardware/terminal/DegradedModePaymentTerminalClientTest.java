package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** The gate under test. */
    private final DegradedModePaymentTerminalClient gate =
            new DegradedModePaymentTerminalClient(configured, autoAccept, posSettingsService);

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
     * {@code requestDebit} reaches the auto-accept terminal when degraded
     * mode is on (degraded arm).
     */
    @Test
    void requestDebitReachesAutoAcceptWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        BigDecimal amount = new BigDecimal("10.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestDebit(amount, callback);
        verify(autoAccept).requestDebit(amount, callback);
        verify(configured, never()).requestDebit(amount, callback);
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
     * {@code requestCredit} reaches the auto-accept terminal when degraded
     * mode is on (degraded arm).
     */
    @Test
    void requestCreditReachesAutoAcceptWhenDegraded() {
        when(posSettingsService.paymentDegradedMode()).thenReturn(true);
        BigDecimal amount = new BigDecimal("4.00");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        gate.requestCredit(amount, callback);
        verify(autoAccept).requestCredit(amount, callback);
        verify(configured, never()).requestCredit(amount, callback);
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
}
