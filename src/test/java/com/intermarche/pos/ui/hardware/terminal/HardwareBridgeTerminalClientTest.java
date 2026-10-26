package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.TestMessages;
import com.intermarche.pos.ui.hardware.HardwareClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        return new HardwareBridgeTerminalClient(hardware, pollMillis, deadlineMillis,
                TestMessages.french());
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

    /** LC-07: c2 refunds follow the documented bridge result instead of failing locally. */
    @Test
    void requestCreditReportsAcceptedOutcome() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer=REFUNDED");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> result = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestCredit(new BigDecimal("5.00"), callback);
        verify(hardware).startCredit("500");
        verify(hardware, never()).startPayment(anyString());
        verify(callback, timeout(2000)).onAccepted(result.capture());
        assertEquals("REFUNDED", result.getValue().rawResponse);
        assertEquals(new BigDecimal("5.00"), result.getValue().amount);
        verify(callback, never()).onError(anyString());
    }

    /** LC-07-04-10: receipt newlines and equals signs survive the bridge transport. */
    @Test
    void preservesEncodedResponse() {
        String frame = "{RS}01{D3L}CLIENT\nTOTAL=5.00\n--/--MERCHANT\n";
        String encoded = java.util.Base64.getEncoder().encodeToString(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer64=" + encoded);
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        ArgumentCaptor<TerminalOutcome> result = ArgumentCaptor.forClass(TerminalOutcome.class);
        newClient(10, 10000).requestDebit(new BigDecimal("5.00"), callback);
        verify(callback, timeout(2000)).onAccepted(result.capture());
        assertEquals(frame, result.getValue().rawResponse);
    }

    /** LC-07-04-10: malformed encoded responses cannot register an accepted payment. */
    @Test
    void rejectsMalformedEncodedResponse() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer64=!");
        TerminalTransactionCallback callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestDebit(BigDecimal.ONE, callback);
        verify(callback, timeout(2000)).onError(UNREACHABLE);
        verify(callback, never()).onAccepted(any());
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
        verify(hardware).maintenance("terminal", "login", "1");
        verify(hardware).maintenance("terminal", "logoff", "1");
        verifyNoMoreInteractions(hardware);
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
        verify(callback).onError("TERMINAL DE PAIEMENT INJOIGNABLE");
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
        verify(callback, timeout(2000)).onError("TERMINAL DE PAIEMENT INJOIGNABLE");
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
        verify(callback, timeout(2000)).onError("TERMINAL DE PAIEMENT INJOIGNABLE");
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
    /** LC-07-08-02: forced degraded debit reaches WCL and still waits for a refusal or acceptance. */
    @Test
    void forcedDegradedUsesBridgeDecision() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=false\nanswer={RS}10");
        var callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestDegradedDebit(new BigDecimal("5.00"), callback);
        verify(hardware).startDegradedPayment("500");
        verify(callback, timeout(2000)).onRefused(any());
        verify(callback, never()).onAccepted(any());
    }

    /** LC-07-06-02: combined eligible cents are bounded by the requested total. */
    @Test
    void combinedAmountsAreBounded() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer={RS}01");
        var callback = mock(TerminalTransactionCallback.class);
        newClient(10, 10000).requestCombinedDebit(new BigDecimal("5.00"), new BigDecimal("10.00"), callback);
        verify(hardware).startCombinedPayment("500;500");
        verify(callback, timeout(2000)).onAccepted(any());
    }

    /** LC-07-08-01: both independent display-line presence legs reach the waiting screen callback. */
    @Test
    void liveDisplayLinesAreRelayed() {
        when(hardware.getPaymentStatus()).thenReturn("state=RUNNING\nline1=OFFLINE INFORMATION",
                "state=DONE\napproved=true\nline2=PRESENT CARD");
        var callback = mock(TerminalTransactionCallback.class);
        newClient(1, 10000).requestDebit(BigDecimal.ONE, callback);
        verify(callback, timeout(2000)).onAccepted(any());
        verify(callback).onDisplay("OFFLINE INFORMATION", "");
        verify(callback).onDisplay("", "PRESENT CARD");
    }

    /** LC-07: invalid settled cents cannot be turned into an accepted payment. */
    @Test
    void invalidSettledAmountFails() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer={RS}01{D10}oops");
        var callback = mock(TerminalTransactionCallback.class);
        newClient(1, 10000).requestDebit(BigDecimal.ONE, callback);
        verify(callback, timeout(2000)).onError(UNREACHABLE);
        verify(callback, never()).onAccepted(any());
    }

    /** LC-07-08-02: offline refunds and combined debits both still reach WCL and await its approval. */
    @Test
    void forcedCreditAndCombinedReachBridge() {
        when(hardware.getPaymentStatus()).thenReturn("state=DONE\napproved=true\nanswer={RS}01");
        var credit = mock(TerminalTransactionCallback.class);
        newClient(1, 10000).requestDegradedCredit(BigDecimal.ONE, credit);
        verify(hardware).startDegradedCredit("100");
        verify(credit, timeout(2000)).onAccepted(any());
        var combined = mock(TerminalTransactionCallback.class);
        newClient(1, 10000).requestDegradedCombinedDebit(BigDecimal.ONE, new BigDecimal("0.50"), combined);
        verify(hardware).startCombinedPayment("100;50;4");
        verify(combined, timeout(2000)).onAccepted(any());
    }

    /**
     * Lot 21: the follower of an older transaction that fails forgets ITS
     * transaction only — the newer one, requested while the older status read
     * was still on the wire, keeps being followed and receives its own decision
     * (it used to be abandoned, the failure clearing whatever was followed).
     *
     * @throws InterruptedException never; only the latch awaits declare it
     */
    @Test
    void aStaleFailureDoesNotDropTheNewerTransaction() throws InterruptedException {
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch newerRequested = new CountDownLatch(1);
        when(hardware.getPaymentStatus()).thenAnswer(invocation -> {
            firstRead.countDown();
            assertTrue(newerRequested.await(2000, TimeUnit.MILLISECONDS));
            throw new IllegalStateException("bridge down");
        }).thenReturn("state=DONE\napproved=true\nanswer={RS}00");
        HardwareBridgeTerminalClient client = newClient(1, 10000);
        TerminalTransactionCallback older = mock(TerminalTransactionCallback.class);
        TerminalTransactionCallback newer = mock(TerminalTransactionCallback.class);
        client.requestDebit(new BigDecimal("3.00"), older);
        assertTrue(firstRead.await(2000, TimeUnit.MILLISECONDS));
        client.requestDebit(new BigDecimal("4.00"), newer);
        newerRequested.countDown();
        verify(older, timeout(2000)).onError(UNREACHABLE);
        ArgumentCaptor<TerminalOutcome> captor = ArgumentCaptor.forClass(TerminalOutcome.class);
        verify(newer, timeout(2000)).onAccepted(captor.capture());
        assertEquals(new BigDecimal("4.00"), captor.getValue().amount);
        verify(older, never()).onAccepted(any());
        verify(newer, never()).onError(anyString());
    }

}
