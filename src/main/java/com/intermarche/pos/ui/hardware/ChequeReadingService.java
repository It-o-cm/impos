package com.intermarche.pos.ui.hardware;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Drives one cheque reading on the hardware bridge, and tells the caller how it ended.
 * <p>
 * Reading a cheque waits for a person: the reader asks for the document, pulls it in,
 * reads it and gives it back. So the bridge does not answer a reading in one call — it
 * starts one and is then followed — and this service does the following, off the request
 * thread, so no HTTP worker is held for the length of a human gesture.
 * <p>
 * Deliberately not a CDI-visible port like the payment terminal: there is one reader
 * and one way to reach it, and inventing an interface for a single implementation would
 * buy nothing.
 */
@ApplicationScoped
public class ChequeReadingService {

    private static final Logger LOGGER = Logger.getLogger(ChequeReadingService.class);

    /** Status key naming where the reading stands. */
    private static final String STATE_KEY = "state";

    /** Status value meaning the reading is over. */
    private static final String STATE_DONE = "DONE";

    /** Status key naming why nothing was read. */
    private static final String FAILURE_KEY = "failure";

    /** Status key carrying the magnetic line. */
    private static final String RAW_KEY = "raw";

    /** Operator-facing message of a reader that could not be reached at all. */
    private static final String UNREACHABLE = "LECTEUR DE CHEQUES INJOIGNABLE";

    /** The bridge in front of the reader. */
    @Inject
    @RestClient
    HardwareClient hardware;

    /** How long to wait between two readings of the state. */
    @ConfigProperty(name = "pos.cheque.poll-ms", defaultValue = "500")
    long pollMillis;

    /**
     * How long to follow one reading before giving up.
     * <p>
     * Generous on purpose: it bounds a cashier and a customer, not a machine.
     */
    @ConfigProperty(name = "pos.cheque.deadline-ms", defaultValue = "60000")
    long deadlineMillis;

    /** Follows the reading away from the request thread. */
    private final ExecutorService follower = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cheque-follow");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Starts a reading and reports how it ended.
     * <p>
     * Exactly one of the two callbacks is invoked, from the following thread.
     * <p>
     * The endorsement travels with the START, not with a later call: the bridge can
     * only print on the cheque while the reader holds it, which is over before this
     * service learns the reading succeeded.
     *
     * @param endorsement what to print on the cheque while the reader holds it, one
     *                    line per newline; empty or {@code null} prints nothing
     * @param onRead  receives the magnetic line when the cheque was read
     * @param onError receives the operator-facing message when it was not
     */
    public void read(String endorsement, Consumer<String> onRead, Consumer<String> onError) {
        LOGGER.info("Entering method read with endorsement: " + endorsement + ", onRead: " + onRead + ", onError: " + onError);
        try {
            hardware.startCheque(endorsement == null ? "" : endorsement);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Le pont materiel a refuse la lecture du cheque");
            onError.accept(UNREACHABLE);
            LOGGER.info("Exiting method read");
            return;
        }
        follower.execute(() -> follow(onRead, onError));
        LOGGER.info("Exiting method read");
    }

    /**
     * Follows one reading until it ends, then reports it.
     *
     * @param onRead  receives the magnetic line when the cheque was read
     * @param onError receives the operator-facing message when it was not
     */
    private void follow(Consumer<String> onRead, Consumer<String> onError) {
        long deadline = System.currentTimeMillis() + deadlineMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> status;
            try {
                status = parse(hardware.getChequeStatus());
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Lecture de l'etat du cheque impossible");
                onError.accept(UNREACHABLE);
                return;
            }
            if (STATE_DONE.equals(status.get(STATE_KEY))) {
                report(status, onRead, onError);
                return;
            }
            if (!sleep()) {
                return;
            }
        }
        LOGGER.errorf("Aucune lecture de cheque apres %d ms", deadlineMillis);
        onError.accept("PAS DE REPONSE DU LECTEUR DE CHEQUES");
    }

    /**
     * Reports a finished reading.
     *
     * @param status  the final status block
     * @param onRead  receives the magnetic line when the cheque was read
     * @param onError receives the operator-facing message when it was not
     */
    private void report(Map<String, String> status, Consumer<String> onRead,
            Consumer<String> onError) {
        String failure = status.get(FAILURE_KEY);
        if (failure != null && !failure.isBlank()) {
            LOGGER.infof("Cheque non lu: %s", failure);
            onError.accept(failure.toUpperCase());
            return;
        }
        String raw = status.get(RAW_KEY);
        if (raw == null || raw.isBlank()) {
            LOGGER.errorf("Lecture terminee sans ligne magnetique ni cause");
            onError.accept("CHEQUE NON LU");
            return;
        }
        onRead.accept(raw);
    }

    /**
     * Waits before the next reading of the state.
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
