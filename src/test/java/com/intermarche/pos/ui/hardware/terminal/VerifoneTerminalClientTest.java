package com.intermarche.pos.ui.hardware.terminal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link VerifoneTerminalClient} SKELETON: what is
 * testable today is the discipline around the missing protocol — the
 * unreachable-client error leg, the not-implemented error leg, the busy
 * rejection and the inert session hooks. The exchange runs on the client's
 * own thread, so the tests await the callback with a latch.
 */
class VerifoneTerminalClientTest {

    /**
     * A transport stub whose reachability is fixed and whose exchange
     * always hits the not-implemented barrier (the real one's behavior
     * until the protocol specification is available).
     */
    private static class StubTransport extends VerifoneTransport {

        /** The fixed reachability answer. */
        private final boolean reachable;

        /**
         * Creates the stub with a fixed reachability.
         *
         * @param reachable the answer {@code isReachable()} returns
         */
        StubTransport(boolean reachable) {
            super("127.0.0.1", 1, 10);
            this.reachable = reachable;
        }

        /**
         * Returns the fixed reachability.
         *
         * @return the configured answer
         */
        @Override
        public boolean isReachable() {
            return reachable;
        }

        /**
         * Behaves like the real transport pending the specification.
         *
         * @param requestFrame the encoded request
         * @return never returns
         * @throws IOException never
         */
        @Override
        public String exchange(String requestFrame) throws IOException {
            throw new UnsupportedOperationException("spec required");
        }
    }

    /**
     * A transport that parks the exchange thread inside {@code isReachable()}
     * until released, so the client stays in flight ({@code busy == true})
     * long enough for the calling thread to exercise the second-exchange
     * rejection and the abort-while-busy legs.
     */
    private static class BlockingTransport extends VerifoneTransport {

        /** Counts down once the exchange thread has entered the probe. */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** Releases the parked exchange thread when counted down. */
        private final CountDownLatch release;

        /**
         * Creates the blocking stub over the given release latch.
         *
         * @param release the latch whose count-down unblocks the probe
         */
        BlockingTransport(CountDownLatch release) {
            super("127.0.0.1", 1, 10);
            this.release = release;
        }

        /**
         * Signals entry then parks until released, holding the client busy;
         * returns unreachable so the parked exchange ends deterministically.
         *
         * @return {@code false} once released or interrupted
         */
        @Override
        public boolean isReachable() {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        /**
         * Never reached: the parked probe returns unreachable first.
         *
         * @param requestFrame the encoded request
         * @return never returns
         * @throws IOException never
         */
        @Override
        public String exchange(String requestFrame) throws IOException {
            throw new UnsupportedOperationException("spec required");
        }
    }

    /**
     * A callback that latches on any terminal leg and captures the error
     * message, used when a test only needs the exchange to terminate.
     */
    private static class LatchingCallback implements TerminalTransactionCallback {

        /** Fires once any terminal leg has been received. */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** Captures the message received on the error leg, if any. */
        private final AtomicReference<String> error = new AtomicReference<>();

        /**
         * Unexpected accept leg: only releases the latch.
         *
         * @param outcome the unexpected outcome
         */
        @Override
        public void onAccepted(TerminalOutcome outcome) {
            latch.countDown();
        }

        /**
         * Unexpected refuse leg: only releases the latch.
         *
         * @param outcome the unexpected outcome
         */
        @Override
        public void onRefused(TerminalOutcome outcome) {
            latch.countDown();
        }

        /**
         * Captures the error message and releases the latch.
         *
         * @param message the operator-facing message
         */
        @Override
        public void onError(String message) {
            error.set(message);
            latch.countDown();
        }
    }

    /**
     * Awaits the single callback leg fired by a debit request.
     *
     * @param client the client under test
     * @param amount the amount to request
     * @return the error message received on the error leg
     * @throws InterruptedException when the wait is interrupted
     */
    private String awaitError(VerifoneTerminalClient client, String amount)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        client.requestDebit(new BigDecimal(amount), new TerminalTransactionCallback() {
            /**
             * Fails the test path: the skeleton never accepts.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onAccepted(TerminalOutcome outcome) {
                error.set("UNEXPECTED-ACCEPT");
                latch.countDown();
            }

            /**
             * Fails the test path: the skeleton never refuses.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onRefused(TerminalOutcome outcome) {
                error.set("UNEXPECTED-REFUSE");
                latch.countDown();
            }

            /**
             * Captures the expected error leg.
             *
             * @param message the operator-facing message
             */
            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback never fired");
        return error.get();
    }

    /**
     * An unreachable monetique client ends on the error leg with the
     * unreachable message (reachability guard arm).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void unreachableClientReportsError() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(false));
        assertEquals(VerifoneTerminalClient.MSG_UNREACHABLE, awaitError(client, "10.00"));
    }

    /**
     * A reachable client still ends on the not-implemented error leg until
     * the protocol specification is available (UnsupportedOperation arm).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void reachableClientReportsNotImplemented() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(true));
        assertEquals(VerifoneTerminalClient.MSG_NOT_IMPLEMENTED, awaitError(client, "10.00"));
    }

    /**
     * The credit path shares the exchange discipline (same error leg).
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void creditSharesExchangeDiscipline() throws InterruptedException {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(false));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        client.requestCredit(new BigDecimal("3.00"), new TerminalTransactionCallback() {
            /**
             * Unexpected leg.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onAccepted(TerminalOutcome outcome) {
                latch.countDown();
            }

            /**
             * Unexpected leg.
             *
             * @param outcome the unexpected outcome
             */
            @Override
            public void onRefused(TerminalOutcome outcome) {
                latch.countDown();
            }

            /**
             * Expected error leg.
             *
             * @param message the operator-facing message
             */
            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(VerifoneTerminalClient.MSG_UNREACHABLE, error.get());
    }

    /**
     * The session hooks are inert until the specification arrives, and the
     * name identifies the implementation; abort with nothing in flight is a
     * no-op.
     */
    @Test
    void hooksNameAndIdleAbortAreInert() {
        VerifoneTerminalClient client = new VerifoneTerminalClient(new StubTransport(true));
        client.onRegisterOpened();
        client.onRegisterClosed();
        client.abort();
        assertEquals("verifone", client.name());
    }

    /**
     * A second exchange requested while one is in flight is rejected
     * synchronously with the already-in-course message (the {@code !CAS}
     * true arm on L150): the first debit parks the exchange thread with
     * {@code busy == true}, so the second CAS from the calling thread fails.
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void secondExchangeWhileBusyIsRejected() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        BlockingTransport transport = new BlockingTransport(release);
        VerifoneTerminalClient client = new VerifoneTerminalClient(transport);
        LatchingCallback first = new LatchingCallback();
        client.requestDebit(new BigDecimal("5.00"), first);
        assertTrue(transport.entered.await(5, TimeUnit.SECONDS), "exchange never started");
        LatchingCallback second = new LatchingCallback();
        client.requestDebit(new BigDecimal("7.00"), second);
        assertTrue(second.latch.await(5, TimeUnit.SECONDS), "rejection never fired");
        assertEquals("TRANSACTION MONETIQUE DEJA EN COURS", second.error.get());
        release.countDown();
        assertTrue(first.latch.await(5, TimeUnit.SECONDS), "first exchange never ended");
        assertEquals(VerifoneTerminalClient.MSG_UNREACHABLE, first.error.get());
    }

    /**
     * An abort issued while a transaction is in flight takes the busy true
     * arm on L103 and only flags the exchange (a no-op observable outcome):
     * the parked first debit keeps {@code busy == true}, so {@code abort()}
     * enters the guard, and the exchange still ends on the unreachable leg.
     *
     * @throws InterruptedException never under the test timeout
     */
    @Test
    void abortWhileBusyFlagsInFlightExchange() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        BlockingTransport transport = new BlockingTransport(release);
        VerifoneTerminalClient client = new VerifoneTerminalClient(transport);
        LatchingCallback callback = new LatchingCallback();
        client.requestDebit(new BigDecimal("9.00"), callback);
        assertTrue(transport.entered.await(5, TimeUnit.SECONDS), "exchange never started");
        client.abort();
        release.countDown();
        assertTrue(callback.latch.await(5, TimeUnit.SECONDS), "exchange never ended");
        assertEquals(VerifoneTerminalClient.MSG_UNREACHABLE, callback.error.get());
    }
}
