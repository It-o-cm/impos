package com.intermarche.pos.ui.hardware;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChequeReadingService}.
 * <p>
 * The service starts a cheque reading on the {@link HardwareClient} and then
 * follows it on a private daemon executor, reporting the outcome through one of
 * two callbacks. The follow loop, the status parsing and the outcome reporting
 * are private, so they are exercised end-to-end through {@link
 * ChequeReadingService#read} while the collaborators are driven to walk each
 * branch: the package-private {@code hardware} field is set directly from within
 * the same package, {@code pollMillis} and {@code deadlineMillis} are pinned so
 * the follow loop is fast and terminates, and asynchronous outcomes are awaited
 * on a latch fed by the callbacks (never on a sleep). Callbacks that must not
 * fire are Mockito mocks verified for no interaction; a bounded {@code timeout}
 * verify proves the loop stopped.
 */
class ChequeReadingServiceTest {

    /**
     * Builds a service wired to the supplied mock client with a fast poll and a
     * generous but finite deadline, bypassing CDI by assigning the
     * package-private field directly.
     *
     * @param client the mock hardware client to inject
     * @return a service under test bound to that client
     */
    private ChequeReadingService serviceWith(HardwareClient client) {
        ChequeReadingService service = new ChequeReadingService();
        service.hardware = client;
        service.pollMillis = 1L;
        service.deadlineMillis = 2000L;
        return service;
    }

    /**
     * Waits for one callback to fire.
     *
     * @param latch the latch a callback counts down
     */
    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting the outcome", e);
        }
    }

    /**
     * A failing START is not followed: the ternary maps a {@code null} endorsement
     * to the empty string sent to the bridge, and the thrown failure takes the
     * catch arm, reporting the reader unreachable without ever polling.
     */
    @Test
    @SuppressWarnings("unchecked")
    void readWithNullEndorsementReportsUnreachableWhenStartThrows() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("bridge down")).when(client).startCheque("");
        Consumer<String> onRead = mock(Consumer.class);
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client).read(null, onRead, onError);
        verify(client).startCheque("");
        verify(onError).accept("LECTEUR DE CHEQUES INJOIGNABLE");
        verifyNoInteractions(onRead);
    }

    /**
     * A non-null endorsement travels verbatim to the START (non-null arm of the
     * ternary); a failing START still takes the catch arm and reports the reader
     * unreachable.
     */
    @Test
    @SuppressWarnings("unchecked")
    void readWithEndorsementReportsUnreachableWhenStartThrows() {
        HardwareClient client = mock(HardwareClient.class);
        doThrow(new RuntimeException("bridge down")).when(client).startCheque("PAYE LE 10/09");
        Consumer<String> onRead = mock(Consumer.class);
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client).read("PAYE LE 10/09", onRead, onError);
        verify(client).startCheque("PAYE LE 10/09");
        verify(onError).accept("LECTEUR DE CHEQUES INJOIGNABLE");
        verifyNoInteractions(onRead);
    }

    /**
     * A reading finished with a magnetic line and no failure is reported as read:
     * the loop enters, the status is DONE, the failure key is absent (first guard
     * false), the raw line is present and non-blank (second guard false), so the
     * raw line reaches {@code onRead}. A line with no separator proves the parser
     * skips it (separator not greater than zero).
     */
    @Test
    void readReportsTheMagneticLineOnASuccessfulReading() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=DONE\nraw=ABC123\nmalformed");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("ABC123", read.get());
        assertEquals(null, error.get());
    }

    /**
     * A reading finished with a named failure is reported in upper case: the
     * failure key is present (first guard true) and non-blank (second guard true),
     * so it short-circuits the raw check and reaches {@code onError}.
     */
    @Test
    void readReportsTheFailureInUpperCase() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=DONE\nfailure=cheque vole");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("CHEQUE VOLE", error.get());
        assertEquals(null, read.get());
    }

    /**
     * A blank failure is not a failure: the failure key is present (first guard
     * true) but blank (second guard false), so the compound is false and the raw
     * line — present and non-blank — is reported as read instead.
     */
    @Test
    void readIgnoresABlankFailureAndReportsTheLine() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=DONE\nfailure=   \nraw=XYZ789");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("XYZ789", read.get());
        assertEquals(null, error.get());
    }

    /**
     * A reading finished with neither a failure nor a line reports the neutral
     * unread message: the failure key is absent (first guard false) and the raw
     * key is absent (raw is null, first leg of the second guard true).
     */
    @Test
    void readReportsUnreadWhenNoLineAndNoFailure() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=DONE");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("CHEQUE NON LU", error.get());
        assertEquals(null, read.get());
    }

    /**
     * A blank magnetic line is treated as no line: the raw key is present (first
     * leg false) but blank (second leg true), so the neutral unread message is
     * reported.
     */
    @Test
    void readReportsUnreadWhenTheLineIsBlank() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=DONE\nraw=   ");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("CHEQUE NON LU", error.get());
        assertEquals(null, read.get());
    }

    /**
     * A reading not yet done is polled again: the first status is not DONE (equals
     * guard false), the wait completes (sleep true), and the second status is DONE
     * and read. This walks the loop twice and the not-yet-done arm.
     */
    @Test
    void readPollsAgainWhenNotYetDone() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn("state=RUNNING", "state=DONE\nraw=LINE42");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("LINE42", read.get());
        assertEquals(null, error.get());
    }

    /**
     * A status read that throws reports the reader unreachable: the catch arm of
     * the follow loop maps any client failure to the unreachable message.
     */
    @Test
    void readReportsUnreachableWhenStatusThrows() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenThrow(new RuntimeException("bus reset"));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        serviceWith(client).read("", onRead, onError);
        await(latch);
        assertEquals("LECTEUR DE CHEQUES INJOIGNABLE", error.get());
        assertEquals(null, read.get());
    }

    /**
     * A deadline already in the past skips the loop entirely (while guard false on
     * the first evaluation) and reports the no-answer timeout without ever polling.
     */
    @Test
    @SuppressWarnings("unchecked")
    void readReportsTimeoutWhenDeadlineHasPassed() {
        HardwareClient client = mock(HardwareClient.class);
        ChequeReadingService service = serviceWith(client);
        service.deadlineMillis = 0L;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = mock(Consumer.class);
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        service.read("", onRead, onError);
        await(latch);
        assertEquals("PAS DE REPONSE DU LECTEUR DE CHEQUES", error.get());
        verifyNoInteractions(onRead);
    }

    /**
     * A null status block parses to an empty map (null arm of the parser): the
     * state is never DONE, the loop spins under a short deadline and finally
     * reports the no-answer timeout, walking the enter-then-exit arms of the while
     * guard.
     */
    @Test
    void readReportsTimeoutWhenStatusBlockIsNull() {
        HardwareClient client = mock(HardwareClient.class);
        when(client.getChequeStatus()).thenReturn(null);
        ChequeReadingService service = serviceWith(client);
        service.deadlineMillis = 20L;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> read = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        Consumer<String> onRead = v -> {
            read.set(v);
            latch.countDown();
        };
        Consumer<String> onError = v -> {
            error.set(v);
            latch.countDown();
        };
        service.read("", onRead, onError);
        await(latch);
        assertEquals("PAS DE REPONSE DU LECTEUR DE CHEQUES", error.get());
        assertEquals(null, read.get());
    }

    /**
     * An interruption during the wait stops the follow silently: the not-yet-done
     * status leaves the interrupt flag set so the next {@code Thread.sleep} throws
     * (catch arm of the wait, false return), the loop returns without a further
     * poll and without invoking either callback.
     */
    @Test
    @SuppressWarnings("unchecked")
    void readStopsSilentlyWhenTheFollowThreadIsInterrupted() {
        HardwareClient client = mock(HardwareClient.class);
        CountDownLatch probed = new CountDownLatch(1);
        when(client.getChequeStatus()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            probed.countDown();
            return "state=RUNNING";
        });
        Consumer<String> onRead = mock(Consumer.class);
        Consumer<String> onError = mock(Consumer.class);
        serviceWith(client).read("", onRead, onError);
        await(probed);
        verify(client, timeout(1000).times(1)).getChequeStatus();
        verifyNoInteractions(onRead);
        verifyNoInteractions(onError);
    }
}
