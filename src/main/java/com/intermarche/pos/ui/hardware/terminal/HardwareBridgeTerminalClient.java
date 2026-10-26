package com.intermarche.pos.ui.hardware.terminal;

import com.intermarche.pos.ui.hardware.HardwareClient;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The real payment terminal, reached through the hardware bridge.
 * <p>
 * The register does not speak to the card reader and never will: a certified
 * client process owns it, and the hardware daemon owns that process. So this
 * implementation is thin on purpose — it asks the bridge to start a payment
 * and then follows it. Everything that is protocol (tags, services, session,
 * result-code ranges) lives on the other side of the HTTP boundary, exactly
 * like the scale and the drawer.
 * <p>
 * The follow-up is a poll rather than one long call, because a card payment
 * waits for the cardholder: presenting the card, the PIN, the authorization,
 * and on a Diebold Nixdorf till a maintenance pass that the client sometimes
 * runs in the middle. Holding an HTTP request open for all that would tie a
 * worker thread and force the register's client timeout past the point where
 * it can still tell a slow payment from a dead daemon.
 * <p>
 * Unlike {@link VerifoneTerminalClient}, which dials a monetique client
 * directly over TCP, this one goes through the same {@code hardware-api}
 * boundary as every other peripheral.
 */
public class HardwareBridgeTerminalClient implements PaymentTerminalClient {


    private static final Logger LOGGER = Logger.getLogger(HardwareBridgeTerminalClient.class);

    /** Cents in one unit of currency. */
    private static final BigDecimal CENTS = new BigDecimal(100);

    /** Status key naming where the payment stands. */
    private static final String STATE_KEY = "state";

    /** Status value meaning the payment is over. */
    private static final String STATE_DONE = "DONE";

    /** Status key telling whether the payment was accepted. */
    private static final String APPROVED_KEY = "approved";

    /** Status key naming why the payment could not run at all. */
    private static final String FAILURE_KEY = "failure";

    /** Status key carrying the whole terminal answer. */
    private static final String ANSWER_KEY = "answer";

    /** Status key carrying the result code. */
    private static final String RESULT_KEY = "result";

    /** Operator-facing message of an exchange that never reached a decision. */
    private static final String UNREACHABLE_KEY = "op.terminalPaiementInjoignable";

    /** The bridge in front of the payment client. */
    private final HardwareClient hardware;

    /** How long to wait between two readings of the payment state. */
    private final long pollMillis;

    /** How long to follow one payment before giving up on it. */
    private final long deadlineMillis;

    /** Follows the payment away from the request thread. */
    private final ExecutorService follower = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tpe-follow");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The transaction being followed, or {@code null} when none is in flight:
     * the follower of an older transaction may only forget its own, never the
     * one started after it.
     */
    private final java.util.concurrent.atomic.AtomicReference<TerminalTransactionCallback> callback =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * The localised operator messages (LC-13-06).
     *
     * <p>HANDED OVER AND NOT INJECTED: this client is built by
     * {@link TerminalClientProducer}, not by the container, so an injected field
     * would stay null. The producer is a bean and passes its own resolver.
     */
    private final com.intermarche.pos.ui.Messages i18n;

    /**
     * Builds the client over a hardware bridge.
     *
     * @param hardware       the bridge in front of the payment client
     * @param pollMillis     how long to wait between two readings
     * @param deadlineMillis how long to follow one payment before giving up
     * @param i18n           the resolver of the operator-facing messages
     */
    public HardwareBridgeTerminalClient(HardwareClient hardware, long pollMillis,
            long deadlineMillis, com.intermarche.pos.ui.Messages i18n) {
        this.hardware = hardware;
        this.pollMillis = pollMillis;
        this.deadlineMillis = deadlineMillis;
        this.i18n = i18n;
    }

    /** {@inheritDoc} */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        request(amount, callback, false);
    }

    /** Starts LC-07 debit or credit and follows the same terminal decision.
     * @param amount requested amount
     * @param callback decision receiver
     * @param credit whether this is a refund
     */
    private void request(BigDecimal amount, TerminalTransactionCallback callback, boolean credit) {
        request(amount, callback, credit, false, null);
    }

    /** Sends LC-07-08-02 through WCL instead of locally accepting a card.
     * @param amount requested amount
     * @param callback decision receiver
     */
    public void requestDegradedDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        request(amount, callback, false, true, null);
    }

    /** LC-07-06-02: sends one combined request; the terminal chooses the actual application. */
    @Override
    public void requestCombinedDebit(BigDecimal amount, BigDecimal eligible, TerminalTransactionCallback callback) {
        request(amount, callback, false, false, eligible);
    }

    /** Forces LC-07-08-02 routing for a credit, preserving the actual terminal decision.
     * @param amount credited amount
     * @param callback decision receiver
     */
    public void requestDegradedCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        request(amount, callback, true, true, null);
    }

    /** Combines eligible cents and forced VWAY4 in the same documented debit.
     * @param amount requested total
     * @param eligible eligible meal amount
     * @param callback decision receiver
     */
    public void requestDegradedCombinedDebit(BigDecimal amount, BigDecimal eligible, TerminalTransactionCallback callback) {
        request(amount, callback, false, true, eligible);
    }

    /** Sends a documented WCL operation and follows its decision.
     * @param amount requested amount
     * @param callback decision receiver
     * @param credit refund operation
     * @param offline force VWAY4
     * @param eligible combined meal amount, null for ordinary debit
     */
    private void request(BigDecimal amount, TerminalTransactionCallback callback, boolean credit,
            boolean offline, BigDecimal eligible) {
        this.callback.set(callback);
        long cents = amount.multiply(CENTS).setScale(0, RoundingMode.HALF_UP).longValueExact();
        try {
            if (eligible != null) {
                long mealCents = eligible.multiply(CENTS).setScale(0, RoundingMode.HALF_UP).longValueExact();
                hardware.startCombinedPayment(cents + ";" + Math.max(0, Math.min(cents, mealCents))
                        + (offline ? ";4" : ""));
            } else if (offline) {
                if (credit) {
                    hardware.startDegradedCredit(Long.toString(cents));
                } else {
                    hardware.startDegradedPayment(Long.toString(cents));
                }
            } else if (credit) {
                hardware.startCredit(Long.toString(cents));
            } else {
                hardware.startPayment(Long.toString(cents));
            }
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Le pont materiel a refuse le demarrage du paiement de %s cts", cents);
            fail(callback, i18n.get(UNREACHABLE_KEY));
            return;
        }
        follower.execute(() -> follow(amount, callback));
    }

    /**
     * Requests the documented LC-07 c2 refund through the bridge.
     *
     * @param amount   the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        request(amount, callback, true);
    }

    /**
     * Stops following the transaction from the register side.
     * <p>
     * The payment itself is NOT cancelled: the bridge has no abort endpoint,
     * and the cardholder keeps whatever the reader is showing. Dropping the
     * callback only stops the register from registering a payment it no longer
     * expects.
     */
    @Override
    public void abort() {
        callback.set(null);
    }

    /** The session is opened by the hardware daemon when it starts, not here. */
    @Override
    public void onRegisterOpened() {
        // LC-01-02-09: re-open after a previous register session logged off.
        hardware.maintenance("terminal", "login", "1");
    }

    /** The session outlives the register session. */
    @Override
    public void onRegisterClosed() {
        // LC-01-02-09: request the documented c2h even though the daemon stays alive.
        hardware.maintenance("terminal", "logoff", "1");
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "TPE via le pont materiel";
    }

    /**
     * Follows one payment until it ends, then reports it.
     *
     * @param amount   the amount asked for
     * @param expected the callback of the transaction being followed
     */
    private void follow(BigDecimal amount, TerminalTransactionCallback expected) {
        long deadline = System.currentTimeMillis() + deadlineMillis;
        while (System.currentTimeMillis() < deadline) {
            if (callback.get() != expected) {
                LOGGER.info("Paiement abandonne cote caisse: suivi interrompu");
                return;
            }
            Map<String, String> status;
            try {
                status = parse(hardware.getPaymentStatus());
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Lecture de l'etat du paiement impossible");
                fail(expected, i18n.get(UNREACHABLE_KEY));
                return;
            }
            if (status.containsKey("line1") || status.containsKey("line2")) {
                expected.onDisplay(status.getOrDefault("line1", ""), status.getOrDefault("line2", ""));
            }
            if (STATE_DONE.equals(status.get(STATE_KEY))) {
                report(amount, expected, status);
                return;
            }
            if (!sleep()) {
                return;
            }
        }
        LOGGER.errorf("Aucune decision du terminal apres %d ms", deadlineMillis);
        fail(expected, i18n.get("op.pasReponseTerminal"));
    }

    /**
     * Reports a finished payment to the register.
     *
     * @param amount   the amount asked for
     * @param expected the callback of the transaction being followed
     * @param status   the final status block
     */
    private void report(BigDecimal amount, TerminalTransactionCallback expected,
            Map<String, String> status) {
        TerminalOutcome outcome = TerminalOutcome.ofAmount(amount);
        String encoded = status.get("answer64");
        try {
            outcome.rawResponse = encoded == null ? status.get(ANSWER_KEY)
                    : new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            fail(expected, i18n.get(UNREACHABLE_KEY));
            return;
        }
        try {
            outcome.readVerifone(outcome.rawResponse);
        } catch (IllegalArgumentException malformed) {
            fail(expected, i18n.get(UNREACHABLE_KEY));
            return;
        }
        String failure = status.get(FAILURE_KEY);
        // A payment that could not run is not a refusal: the card was never
        // asked. Registering it as declined would tell the operator to try
        // another mean when the terminal is simply out of service.
        if (failure != null && !failure.isBlank()) {
            LOGGER.errorf("Paiement impossible: %s", failure);
            fail(expected, i18n.get(UNREACHABLE_KEY));
            return;
        }
        if (Boolean.parseBoolean(status.get(APPROVED_KEY))) {
            LOGGER.infof("Paiement accepte (resultat %s)", status.get(RESULT_KEY));
            expected.onAccepted(outcome);
            return;
        }
        LOGGER.infof("Paiement refuse (resultat %s)", status.get(RESULT_KEY));
        expected.onRefused(outcome);
    }

    /**
     * Waits before the next reading.
     *
     * @return true when the wait completed, false when the thread was interrupted
     */
    private boolean sleep() {
        try {
            Thread.sleep(pollMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Reports a failure and forgets the transaction — its own only: a newer
     * transaction started meanwhile keeps being followed.
     *
     * @param target  the callback to tell
     * @param message the operator-facing message
     */
    private void fail(TerminalTransactionCallback target, String message) {
        callback.compareAndSet(target, null);
        target.onError(message);
    }

    /**
     * Reads the status block into its keys.
     *
     * @param block the {@code key=value} block returned by the bridge
     * @return the keys and their values, empty when the block is null
     */
    private static Map<String, String> parse(String block) {
        Map<String, String> status = new HashMap<>();
        if (block == null) {
            return status;
        }
        for (String line : block.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                status.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return status;
    }
}
