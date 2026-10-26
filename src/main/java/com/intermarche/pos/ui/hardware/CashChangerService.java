package com.intermarche.pos.ui.hardware;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Drives the cash changer on the hardware bridge: one deposit at a time, and
 * the levels the machine reports (LC-07-15-01 to -04, LC-13-01-01).
 * <p>
 * A deposit waits for a person: the customer feeds coins and notes until the
 * amount due is reached, then the machine dispenses the change by itself. So
 * the bridge does not answer a deposit in one call — it starts one and is then
 * followed — and this service does the following, off the request thread, like
 * the cheque reading: no HTTP worker is held for the length of a human gesture.
 * <p>
 * The service only REPORTS. The amount inserted so far, the end of the deposit
 * or its failure reach the caller through callbacks fired on the following
 * thread; registering the payment is the caller's business, on its own thread.
 */
@ApplicationScoped
public class CashChangerService {

    private static final Logger LOGGER = Logger.getLogger(CashChangerService.class);

    /** Status key naming where the deposit stands. */
    private static final String STATE_KEY = "state";

    /** Status value meaning the deposit is over. */
    private static final String STATE_DONE = "DONE";

    /** Status key carrying why the deposit failed. */
    private static final String FAILURE_KEY = "failure";

    /** Operator-facing message of a machine that could not be reached at all. */
    private static final String UNREACHABLE_KEY = "op.monnayeurInjoignable";

    /** The localised operator messages (LC-13-06). */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** Whether the store runs a cash changer at this till. */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The bridge in front of the cash changer. */
    @Inject
    @RestClient
    HardwareClient hardware;

    /** How long to wait between two readings of the deposit. */
    @ConfigProperty(name = "pos.cashchanger.poll-ms", defaultValue = "300")
    long pollMillis;

    /**
     * How long to follow one deposit before giving up and giving the money
     * back. Generous on purpose: it bounds a customer counting coins.
     */
    @ConfigProperty(name = "pos.cashchanger.deadline-ms", defaultValue = "300000")
    long deadlineMillis;

    /** Follows the deposit away from the request thread. */
    private final ExecutorService follower = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cashchanger-follow");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * How a deposit ended.
     *
     * @param inserted what the customer fed into the machine
     * @param change what the machine gave back, everything when cancelled
     */
    public record Deposit(BigDecimal inserted, BigDecimal change) {

        /**
         * What the deposit actually settles: inserted minus given back.
         *
         * @return the settled amount, zero when everything was given back
         */
        public BigDecimal settled() {
            return inserted.subtract(change);
        }
    }

    /**
     * Starts a deposit for the amount due and follows it.
     * <p>
     * {@code onProgress} fires each time the amount inserted changes; then
     * exactly one of {@code onDone} and {@code onError} fires, from the
     * following thread.
     *
     * @param due the amount due
     * @param onProgress receives the amount inserted so far
     * @param onDone receives the outcome of a deposit the machine closed
     * @param onError receives the operator-facing message of a failure
     */
    public void deposit(BigDecimal due, Consumer<BigDecimal> onProgress, Consumer<Deposit> onDone,
            Consumer<String> onError) {
        LOGGER.info("Entering method deposit with due: " + due);
        try {
            hardware.startCashChangerDeposit(cents(due));
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Le pont materiel a refuse le depot monnayeur");
            onError.accept(i18n.get(UNREACHABLE_KEY));
            LOGGER.info("Exiting method deposit");
            return;
        }
        follower.execute(() -> follow(onProgress, onDone, onError));
        LOGGER.info("Exiting method deposit");
    }

    /**
     * Follows one deposit until it ends, then reports it.
     *
     * @param onProgress receives the amount inserted so far
     * @param onDone receives the outcome of a deposit the machine closed
     * @param onError receives the operator-facing message of a failure
     */
    private void follow(Consumer<BigDecimal> onProgress, Consumer<Deposit> onDone,
            Consumer<String> onError) {
        long deadline = System.currentTimeMillis() + deadlineMillis;
        BigDecimal shown = BigDecimal.ZERO;
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> status;
            try {
                status = parse(hardware.getCashChangerDeposit());
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Lecture de l'etat du monnayeur impossible");
                onError.accept(i18n.get(UNREACHABLE_KEY));
                return;
            }
            String failure = status.get(FAILURE_KEY);
            if (failure != null && !failure.isBlank()) {
                LOGGER.infof("Depot monnayeur en echec: %s", failure);
                onError.accept(failure.toUpperCase());
                return;
            }
            BigDecimal inserted = amount(status.get("inserted"));
            if (STATE_DONE.equals(status.get(STATE_KEY))) {
                onDone.accept(new Deposit(inserted, amount(status.get("change"))));
                return;
            }
            if (inserted.compareTo(shown) != 0) {
                shown = inserted;
                onProgress.accept(inserted);
            }
            if (!sleep()) {
                return;
            }
        }
        LOGGER.errorf("Depot monnayeur non conclu apres %d ms", deadlineMillis);
        cancel();
        onError.accept(i18n.get("op.monnayeurPasDeReponse"));
    }

    /**
     * Closes the running deposit on what was inserted, without change. The
     * end is reported by the following thread like any other.
     */
    public void fix() {
        LOGGER.info("Entering method fix");
        try {
            hardware.fixCashChangerDeposit();
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Cloture du depot monnayeur impossible");
        }
        LOGGER.info("Exiting method fix");
    }

    /**
     * Cancels the running deposit: the machine gives everything back. The end
     * is reported by the following thread like any other.
     */
    public void cancel() {
        LOGGER.info("Entering method cancel");
        try {
            hardware.cancelCashChangerDeposit();
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Annulation du depot monnayeur impossible");
        }
        LOGGER.info("Exiting method cancel");
    }

    /**
     * Words what the cash changer reports out of its thresholds (LC-13-01-01),
     * for the alert shown at register opening and after a sale.
     * <p>
     * The thresholds are administered on the machine's own back office: the
     * register does not judge the counts, it says what the machine flags —
     * each denomination below its low level or above its high level, and the
     * machine-wide near-empty and near-full states.
     *
     * @return the alert, or null when there is no cash changer or nothing to say
     */
    public String levelAlert() {
        LOGGER.info("Entering method levelAlert");
        if (!posSettingsService.hardwareEnabled("cashchanger")) {
            LOGGER.info("Exiting method levelAlert");
            return null;
        }
        Map<String, String> levels;
        try {
            levels = parse(hardware.getCashChangerLevels());
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Lecture des niveaux du monnayeur impossible");
            LOGGER.info("Exiting method levelAlert");
            return i18n.get(UNREACHABLE_KEY);
        }
        List<String> parts = new ArrayList<>();
        String low = denominations(levels.get("low"));
        if (!low.isEmpty()) {
            parts.add(i18n.format("op.monnayeurNiveauBas", low));
        }
        String high = denominations(levels.get("high"));
        if (!high.isEmpty()) {
            parts.add(i18n.format("op.monnayeurNiveauHaut", high));
        }
        String status = levels.get("status");
        if ("EMPTY".equals(status)) {
            parts.add(i18n.get("op.monnayeurVide"));
        } else if ("NEAREMPTY".equals(status)) {
            parts.add(i18n.get("op.monnayeurPresqueVide"));
        }
        String full = levels.get("full");
        if ("FULL".equals(full)) {
            parts.add(i18n.get("op.monnayeurPlein"));
        } else if ("NEARFULL".equals(full)) {
            parts.add(i18n.get("op.monnayeurPresquePlein"));
        }
        LOGGER.info("Exiting method levelAlert");
        return parts.isEmpty() ? null : i18n.format("op.monnayeurAlerte", String.join(" - ", parts));
    }

    /**
     * One denomination of the cash level (BO-11-03-03); the screen writes the
     * amounts with the register's amount writing ({@code units.money}).
     *
     * @param unit the denomination, in euros
     * @param count how many the machine holds
     * @param amount what they amount to, in euros
     */
    public record Level(BigDecimal unit, long count, BigDecimal amount) {
    }

    /**
     * The cash level of the machine (BO-11-03-03).
     *
     * @param total what the machine holds in all, in euros
     * @param levels each denomination, by decreasing value
     */
    public record Inventory(BigDecimal total, List<Level> levels) {
    }

    /**
     * Whether the cash loans and withdrawals go through the machine
     * (BO-11-03-03): a cash changer at this till and the store asking for it.
     *
     * @return true when the machine counts and dispenses them
     */
    public boolean movementsViaMachine() {
        return posSettingsService.hardwareEnabled("cashchanger") && posSettingsService.cashChangerMovements();
    }

    /**
     * Has the machine dispense a withdrawal (BO-11-03-03).
     *
     * @param amount the amount asked for
     * @return what the machine dispensed, or null when it could not be asked
     */
    public BigDecimal dispense(BigDecimal amount) {
        LOGGER.info("Entering method dispense");
        try {
            BigDecimal dispensed = amount(parse(hardware.dispenseFromCashChanger(cents(amount))).get("dispensed"));
            LOGGER.info("Exiting method dispense");
            return dispensed;
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Distribution par le monnayeur impossible");
            return null;
        }
    }

    /**
     * Has the machine take a loan in (BO-11-03-03).
     *
     * @param amount the amount brought
     * @return what the machine accepted, or null when it could not be asked
     */
    public BigDecimal replenish(BigDecimal amount) {
        LOGGER.info("Entering method replenish");
        try {
            BigDecimal accepted = amount(parse(hardware.replenishCashChanger(cents(amount))).get("accepted"));
            LOGGER.info("Exiting method replenish");
            return accepted;
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Apport dans le monnayeur impossible");
            return null;
        }
    }

    /**
     * Reads the cash level of the machine (BO-11-03-03), the denominations in
     * decreasing value.
     *
     * @return the level, or null when there is no machine or it cannot be read
     */
    public Inventory inventory() {
        LOGGER.info("Entering method inventory");
        if (!posSettingsService.hardwareEnabled("cashchanger")) {
            LOGGER.info("Exiting method inventory: no machine");
            return null;
        }
        Map<String, String> block;
        try {
            block = parse(hardware.getCashChangerInventory());
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Lecture du niveau du monnayeur impossible");
            return null;
        }
        java.util.TreeMap<Long, Long> counts = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (Map.Entry<String, String> entry : block.entrySet()) {
            if (!"total".equals(entry.getKey())) {
                try {
                    counts.put(Long.parseLong(entry.getKey()), Long.parseLong(entry.getValue()));
                } catch (NumberFormatException e) {
                    LOGGER.warnf("Coupure illisible dans le niveau du monnayeur: %s", entry.getKey());
                }
            }
        }
        List<Level> levels = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : counts.entrySet()) {
            BigDecimal unit = BigDecimal.valueOf(entry.getKey()).movePointLeft(2);
            levels.add(new Level(unit, entry.getValue(), unit.multiply(BigDecimal.valueOf(entry.getValue()))));
        }
        LOGGER.info("Exiting method inventory");
        return new Inventory(amount(block.get("total")), levels);
    }

    /**
     * Words a comma-separated list of denominations in cents as amounts.
     *
     * @param list the denominations in cents, possibly null or empty
     * @return the amounts, space separated, each kept whole with a non-breaking
     *         space before its euro sign, empty when there are none
     */
    private static String denominations(String list) {
        List<String> words = new ArrayList<>();
        if (list != null) {
            for (String item : list.split(",")) {
                if (!item.isBlank()) {
                    // A non-breaking space keeps each amount whole when the alert wraps.
                    words.add(amount(item).toPlainString().replace('.', ',') + "\u00a0€");
                }
            }
        }
        return String.join(" ", words);
    }

    /**
     * Turns an amount into the cents the bridge speaks.
     *
     * @param value the amount
     * @return the cents, as text
     */
    private static String cents(BigDecimal value) {
        return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Reads cents from the bridge as an amount.
     *
     * @param cents the cents, possibly null or malformed
     * @return the amount, zero when nothing readable was given
     */
    private static BigDecimal amount(String cents) {
        try {
            return new BigDecimal(cents.trim()).movePointLeft(2);
        } catch (RuntimeException e) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    /**
     * Waits before the next reading of the deposit.
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
     * Reads a status block into its keys.
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
                status.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return status;
    }
}
