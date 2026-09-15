package com.intermarche.pos.ui.hardware.terminal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import com.intermarche.pos.ui.hardware.HardwareClient;

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
    private static final String UNREACHABLE = "TERMINAL DE PAIEMENT INJOIGNABLE";

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

    /** The transaction being followed, or {@code null} when none is in flight. */
    private volatile TerminalTransactionCallback callback;

    /**
     * Builds the client over a hardware bridge.
     *
     * @param hardware       the bridge in front of the payment client
     * @param pollMillis     how long to wait between two readings
     * @param deadlineMillis how long to follow one payment before giving up
     */
    public HardwareBridgeTerminalClient(HardwareClient hardware, long pollMillis, long deadlineMillis) {
        this.hardware = hardware;
        this.pollMillis = pollMillis;
        this.deadlineMillis = deadlineMillis;
    }

    /** {@inheritDoc} */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        this.callback = callback;
        long cents = amount.multiply(CENTS).setScale(0, RoundingMode.HALF_UP).longValueExact();
        try {
            hardware.startPayment(Long.toString(cents));
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Le pont materiel a refuse le demarrage du paiement de %s cts", cents);
            fail(callback, UNREACHABLE);
            return;
        }
        follower.execute(() -> follow(amount, callback));
    }

    /**
     * Refunds are not supported by this bridge yet.
     * <p>
     * The daemon exposes a debit and nothing else, and inventing a refusal
     * would be worse than saying so: the operator must know the refund has to
     * be made another way, not believe the terminal declined it.
     *
     * @param amount   the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        LOGGER.warnf("Remboursement de %s demande: non supporte par le pont materiel", amount);
        fail(callback, "REMBOURSEMENT NON SUPPORTE PAR LE TERMINAL");
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
        callback = null;
    }

    /** The session is opened by the hardware daemon when it starts, not here. */
    @Override
    public void onRegisterOpened() {
        // Nothing: the daemon logs in once at start-up and reopens by itself.
    }

    /** The session outlives the register session. */
    @Override
    public void onRegisterClosed() {
        // Nothing: the daemon owns the session's lifetime.
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
            if (callback != expected) {
                LOGGER.info("Paiement abandonne cote caisse: suivi interrompu");
                return;
            }
            Map<String, String> status;
            try {
                status = parse(hardware.getPaymentStatus());
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Lecture de l'etat du paiement impossible");
                fail(expected, UNREACHABLE);
                return;
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
        fail(expected, "PAS DE REPONSE DU TERMINAL");
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
        outcome.rawResponse = status.get(ANSWER_KEY);
        String failure = status.get(FAILURE_KEY);
        // A payment that could not run is not a refusal: the card was never
        // asked. Registering it as declined would tell the operator to try
        // another mean when the terminal is simply out of service.
        if (failure != null && !failure.isBlank()) {
            LOGGER.errorf("Paiement impossible: %s", failure);
            fail(expected, UNREACHABLE);
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
     * Reports a failure and forgets the transaction.
     *
     * @param target  the callback to tell
     * @param message the operator-facing message
     */
    private void fail(TerminalTransactionCallback target, String message) {
        callback = null;
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
