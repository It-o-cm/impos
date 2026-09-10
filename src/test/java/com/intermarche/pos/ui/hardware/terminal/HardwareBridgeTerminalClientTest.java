package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.intermarche.pos.ui.hardware.HardwareClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HardwareBridgeTerminalClient}.
 * <p>
 * Branch enumeration (every arm exercised): {@code requestDebit}'s
 * start try/catch (success and thrown); {@code follow}'s while entry
 * (entered and skipped-on-deadline), the {@code callback != expected}
 * guard (both arms), the status-read try/catch (both arms), the
 * {@code STATE_DONE} guard (both arms) and the {@code !sleep()} guard
 * (return and continue); {@code report}'s {@code failure != null} guard,
 * its {@code !failure.isBlank()} guard and the {@code approved} ternary
 * (all arms); {@code sleep}'s try/catch (completed and interrupted);
 * {@code parse}'s null guard and its {@code separator > 0} guard (both
 * arms). The follow-up runs on the daemon "tpe-follow" thread, so the
 * asynchronous outcomes are awaited with Mockito {@code timeout}/{@code after}.
 */
class HardwareBridgeTerminalClientTest {

    /** Operator message for an exchange that never reached a decision. */
    private static final String UNREACHABLE = "TERMINAL DE PAIEMENT INJOIGNABLE";

    /** The hardware bridge in front of the payment client. */
    private final HardwareClient hardware = mock(HardwareClient.class);

    /** The daemon follow thread, captured from a status read. */
    private volatile Thread followThread;

    /**
     * Builds a client over the mocked bridge with the given cadences.
     *
     * @param pollMillis     wait between two readings
     * @param deadlineMillis how long to follow before giving up
     * @return the client under test
     */
    private HardwareBridgeTerminalClient newClient(long pollMillis, long deadlineMillis) {
        return new HardwareBridgeTerminalClient(hardware, pollMillis, deadlineMillis);
    }

    /**
     * Interrupts the captured follow thread while it sits in its poll wait,
     * deterministically: first waits for it to enter {@code TIMED_WAITING}
     * (inside {@code Thread.sleep}), then interrupts until it leaves that state,
     * which proves the sleep threw and the catch arm ran. Both waits are bounded
     * so a misbehaving run cannot spin forever.
     */
    private void interruptFollowerDuringSleep() {
        long enter = System.currentTimeMillis() + 3000;
        while (followThread.getState() != Thread.State.TIMED_WAITING
                && System.currentTimeMillis() < enter) {
            Thread.yield();
        }
        long leave = System.currentTimeMillis() + 3000;
        while (followThread.getState() == Thread.State.TIMED_WAITING
                && System.currentTimeMillis() < leave) {
            followThread.interrupt();
        }
    }

    /**
     * {@code name} identifies the bridge terminal (no branch, contract proof).
     */
    @Test
    void nameDescribesTheBridgeTerminal() {
        assertEquals("TPE via le pont materiel", newClient(10, 10000).name());
    }

    /**
     * {@code requestCredit} is unsupported: it fails the callback with the
     * dedicated message and never touches the bridge.
     */
    @Test
    void requestCreditIsNotSupported() {
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestCredit(new BigDecimal("5.00"), callback);
        verify(callback).onError("REMBOURSEMENT NON SUPPORTE PAR LE TERMINAL");
        verifyNoInteractions(hardware);
    }

    /**
     * {@code abort} only drops the callback and never touches the bridge.
     */
    @Test
    void abortDoesNotTouchTheBridge() {
        newClient(10, 10000).abort();
        verifyNoInteractions(hardware);
    }

    /**
     * The session hooks do nothing and never touch the bridge (daemon owns the
     * session lifetime).
     */
    @Test
    void sessionHooksDoNothing() {
        HardwareBridgeTerminalClient client = newClient(10, 10000);
        client.onRegisterOpened();
        client.onRegisterClosed();
        verifyNoInteractions(hardware);
    }

    /**
     * When the bridge refuses to start the payment (thrown), the debit reports
     * the unreachable message synchronously and never follows anything —
     * {@code requestDebit}'s catch arm, and the cents rounding (12.34 -> 1234).
     */
    @Test
    void requestDebitReportsUnreachableWhenStartFails() {
        doThrow(new RuntimeException("boom")).when(hardware).startPayment(anyString());
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestDebit(new BigDecimal("12.34"), callback);
        verify(callback).onError(UNREACHABLE);
        verify(hardware).startPayment("1234");
        verify(hardware, never()).getPaymentStatus();
    }

    /**
     * A DONE, approved status with no failure reports the accepted outcome —
     * start success, loop entry, non-abandoned guard, successful parse (a
     * malformed line without '=' exercising the {@code separator > 0} false
     * arm), STATE_DONE true arm, failure-absent arm, approved-true ternary arm;
     * the raw answer is carried verbatim.
     */
    @Test
    void requestDebitReportsAcceptedOutcome() {
        when(hardware.getPaymentStatus())
                .thenReturn("state=DONE\ngarbage\napproved=true\nresult=0000\nanswer=RAW ANSWER");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> captor = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestDebit(new BigDecimal("12.34"), callback);
        verify(callback, timeout(2000)).onAccepted(captor.capture());
        assertEquals(new BigDecimal("12.34"), captor.getValue().amount);
        assertEquals("RAW ANSWER", captor.getValue().rawResponse);
        verify(callback, never()).onRefused(any());
        verify(callback, never()).onError(anyString());
    }

    /**
     * A DONE, not-approved status with no failure reports the refused outcome —
     * the approved-false ternary arm — and still carries the raw answer.
     */
    @Test
    void requestDebitReportsRefusedOutcome() {
        when(hardware.getPaymentStatus())
                .thenReturn("state=DONE\napproved=false\nresult=0100\nanswer=NO");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> captor = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestDebit(new BigDecimal("7.00"), callback);
        verify(callback, timeout(2000)).onRefused(captor.capture());
        assertEquals(new BigDecimal("7.00"), captor.getValue().amount);
        assertEquals("NO", captor.getValue().rawResponse);
        verify(callback, never()).onAccepted(any());
        verify(callback, never()).onError(anyString());
    }

    /**
     * A non-blank failure marks the payment impossible, not declined: the
     * unreachable message is reported even though {@code approved=true} — the
     * failure-present true arm and the {@code !isBlank()} true arm.
     */
    @Test
    void requestDebitReportsUnreachableOnNonBlankFailure() {
        when(hardware.getPaymentStatus())
                .thenReturn("state=DONE\nfailure=OUT OF SERVICE\napproved=true");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestDebit(new BigDecimal("3.00"), callback);
        verify(callback, timeout(2000)).onError(UNREACHABLE);
        verify(callback, never()).onAccepted(any());
        verify(callback, never()).onRefused(any());
    }

    /**
     * A blank failure is not a failure: the flow falls through to the approval
     * decision and accepts — the {@code !isBlank()} false arm.
     */
    @Test
    void requestDebitTreatsBlankFailureAsNoFailure() {
        when(hardware.getPaymentStatus())
                .thenReturn("state=DONE\nfailure=\napproved=true\nanswer=OK");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> captor = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestDebit(new BigDecimal("9.00"), callback);
        verify(callback, timeout(2000)).onAccepted(captor.capture());
        assertEquals("OK", captor.getValue().rawResponse);
        verify(callback, never()).onError(anyString());
    }

    /**
     * A null status block parses to an empty map (STATE_DONE false arm), the
     * client sleeps ({@code !sleep()} false arm, loop continues), and the
     * second reading (DONE) accepts — covering the parse null arm and the
     * poll-again path.
     */
    @Test
    void requestDebitPollsAgainAfterNullStatus() {
        when(hardware.getPaymentStatus())
                .thenReturn(null, "state=DONE\napproved=true\nanswer=SECOND");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> captor = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestDebit(new BigDecimal("2.00"), callback);
        verify(callback, timeout(2000)).onAccepted(captor.capture());
        assertEquals("SECOND", captor.getValue().rawResponse);
    }

    /**
     * When reading the status throws (thrown), the follow reports the
     * unreachable message — the status-read catch arm.
     */
    @Test
    void requestDebitReportsUnreachableWhenStatusReadFails() {
        when(hardware.getPaymentStatus()).thenThrow(new RuntimeException("io"));
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestDebit(new BigDecimal("4.00"), callback);
        verify(callback, timeout(2000)).onError(UNREACHABLE);
        verify(callback, never()).onAccepted(any());
        verify(callback, never()).onRefused(any());
    }

    /**
     * A zero deadline skips the loop entirely (while-entry false arm) and
     * reports the no-response message without ever reading the status.
     */
    @Test
    void requestDebitReportsNoResponseWhenDeadlineElapsedImmediately() {
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 0).requestDebit(new BigDecimal("6.00"), callback);
        verify(callback, timeout(2000)).onError("PAS DE REPONSE DU TERMINAL");
        verify(hardware, never()).getPaymentStatus();
    }

    /**
     * When the register aborts mid-follow, the next loop turn sees
     * {@code callback != expected} (true arm) and stops silently: the status is
     * read exactly once and no outcome is reported. The abort is fired from the
     * status answer so the follow thread observes it on its second turn.
     */
    @Test
    void requestDebitStopsFollowingWhenAborted() {
        HardwareBridgeTerminalClient client = newClient(20, 10000);
        when(hardware.getPaymentStatus()).thenAnswer(invocation -> {
            client.abort();
            return "state=RUNNING";
        });
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        client.requestDebit(new BigDecimal("8.00"), callback);
        verify(hardware, timeout(2000).times(1)).getPaymentStatus();
        verify(callback, after(200).never()).onAccepted(any());
        verify(callback, never()).onRefused(any());
        verify(callback, never()).onError(anyString());
    }

    /**
     * When the follow thread is interrupted during its wait, {@code sleep}
     * returns false (catch arm) and the follow stops via the {@code !sleep()}
     * true arm: the status is read once and no outcome is reported. A long poll
     * keeps the thread parked in the wait until the interrupt lands.
     *
     * @throws InterruptedException never; only the latch await declares it
     */
    @Test
    void requestDebitStopsFollowingWhenInterrupted() throws InterruptedException {
        CountDownLatch statusRead = new CountDownLatch(1);
        when(hardware.getPaymentStatus()).thenAnswer(invocation -> {
            followThread = Thread.currentThread();
            statusRead.countDown();
            return "state=RUNNING";
        });
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10000, 60000).requestDebit(new BigDecimal("11.00"), callback);
        assertTrue(statusRead.await(2000, TimeUnit.MILLISECONDS));
        interruptFollowerDuringSleep();
        verify(hardware, timeout(2000).times(1)).getPaymentStatus();
        verify(callback, after(200).never()).onError(anyString());
        verify(callback, never()).onAccepted(any());
        verify(callback, never()).onRefused(any());
    }
}
