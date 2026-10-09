package com.intermarche.pos.ui.supervision;

import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import com.intermarche.pos.domain.sync.RefState;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.EngineFeedService;
import com.intermarche.pos.service.sync.RefPullService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Read-only assembly of the "Santé des échanges" panel (spec-sante-caisse): the
 * health of THIS register's own exchange channels, laid side by side so a
 * supervisor sees, without a query, whether the till is exchanging correctly.
 * <ul>
 *   <li>TICKET UPLINK — the store-synchronization outbox that pushes this
 *       register's business entities up to the node: queue size, oldest waiting
 *       element, last push error.</li>
 *   <li>CONSUMPTION — the consumption-declaration queue delivered to the local
 *       valuation engine: how many wait, how many failed, the last error.</li>
 *   <li>ENGINE FEEDS — the referential/valuation feeds delivered to the local
 *       engine (a role the register holds in the demo stack): a read-only
 *       per-feed status (up to date / pending / error). The fine piloting lives
 *       at the node, never here.</li>
 * </ul>
 * <p>
 * The register knows ONLY itself: this is its local exchange state, never a
 * store-wide view. Everything is pre-formatted here so the template stays a dumb
 * renderer. Transactional so the direct outbox reads have a valid persistence
 * session on whatever thread renders the panel or its refresh fragments.
 */
@ApplicationScoped
public class ExchangeHealthService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ExchangeHealthService.class);

    /** How a waiting element's timestamp is shortened for the screen. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");


    /**
     * The localised operator messages: every sentence this class shows the
     * operator is a bundle key, never a French literal (LC-13-06).
     */
    @Inject
    com.intermarche.pos.ui.Messages i18n;

    /** The engine feed keeper (per-feed acknowledged state). */
    @Inject
    EngineFeedService engineFeedService;

    /** The referential pull loop (per-domain last error, last successful cycle, cadence). */
    @Inject
    RefPullService refPullService;

    /** The ticket-uplink channel health (store-synchronization outbox). */
    public static class UplinkHealth {
        /** How many entities await push to the store node. */
        public long count;
        /** The oldest waiting element's enqueue time, formatted, or a dash. */
        public String oldest;
        /** The last push error, or null when the channel is clean. */
        public String lastError;
    }

    /** The consumption-delivery channel health. */
    public static class ConsumptionHealth {
        /** How many declarations await engine acknowledgement. */
        public long pending;
        /** How many of those carry a delivery error. */
        public long failed;
        /** The last delivery error, or null when the channel is clean. */
        public String lastError;
    }

    /** One engine-feed row, pre-formatted, read-only. */
    public static class FeedHealth {
        /** The feed code (PRODUCTS, PRICES, OFFERS...). */
        public String code;
        /** The status label: up to date, pending, or error. */
        public String status;
        /** The last delivery error toward the engine, or null when clean. */
        public String lastError;
    }

    /** One referential-pull row: a domain and its last applied fingerprint. */
    public static class PullHealth {
        /** The referential domain (FAMILIES, PRODUCTS, COUNTRIES...). */
        public String domain;
        /** The last applied fingerprint, shortened, or a dash when none. */
        public String fingerprint;
        /** When the snapshot was applied, formatted, or a dash when none. */
        public String appliedAt;
        /**
         * The last integration failure of this domain, or null when it applied.
         *
         * <p>A pull domain that failed leaves NO {@code RefState} row (one is
         * written only on success), so without this the register showed a
         * fingerprint for the domains that went through and nothing at all for
         * the one that did not — {@link RefPullService#getLastErrors()} is what
         * carries the failure to the screen.
         */
        public String lastError;
    }

    /** The whole assembled panel. */
    public static class HealthView {
        /** The ticket-uplink channel health. */
        public UplinkHealth uplink = new UplinkHealth();
        /** The consumption-delivery channel health. */
        public ConsumptionHealth consumption = new ConsumptionHealth();
        /** The engine-feed per-code statuses, in catalog order. */
        public List<FeedHealth> feeds = new ArrayList<>();
        /** The referential-pull per-domain statuses, ordered by domain. */
        public List<PullHealth> pulls = new ArrayList<>();
        /** When the last full pull cycle succeeded, or a dash when none. */
        public String lastPull;
        /** The referential-pull cadence, in seconds. */
        public long pullSeconds;
        /** Whether every channel is clean (empty queues, no feed error). */
        public boolean healthy;
    }

    /**
     * Builds the whole exchange-health panel over this register's local state.
     *
     * @return the assembled, pre-formatted panel
     */
    @Transactional
    public HealthView build() {
        LOGGER.info("Entering method build");
        HealthView view = new HealthView();
        view.uplink = uplink();
        view.consumption = consumption();
        boolean feedError = false;
        for (EngineFeedService.FeedState state : engineFeedService.feedStates()) {
            FeedHealth row = new FeedHealth();
            row.code = state.code();
            row.status = feedStatus(state);
            row.lastError = state.lastError();
            if (state.lastError() != null) {
                feedError = true;
            }
            view.feeds.add(row);
        }
        view.pulls = pulls();
        view.lastPull = stamp(refPullService.getLastSuccessfulPull());
        view.pullSeconds = refPullService.getPullSeconds();
        view.healthy = view.uplink.count == 0 && view.consumption.pending == 0 && !feedError;
        LOGGER.info("Exiting method build");
        return view;
    }

    /**
     * Assembles the referential-pull rows: one per applied domain from
     * {@link RefState}, carrying its last error from
     * {@link RefPullService#getLastErrors()}, plus a synthetic row for a domain
     * that failed before it ever applied (so it has no {@code RefState} yet).
     *
     * @return the pull rows, ordered by domain then by first-failure
     */
    private List<PullHealth> pulls() {
        LOGGER.info("Entering method pulls");
        List<PullHealth> rows = new ArrayList<>();
        for (RefState state : RefState.<RefState>list("order by domain")) {
            PullHealth row = new PullHealth();
            row.domain = state.domain;
            row.fingerprint = shortHash(state.fingerprint);
            row.appliedAt = stamp(state.appliedAt);
            row.lastError = refPullService.getLastError(state.domain);
            rows.add(row);
        }
        for (Map.Entry<String, String> failure : refPullService.getLastErrors().entrySet()) {
            boolean alreadyListed = false;
            for (PullHealth listed : rows) {
                if (failure.getKey().equals(listed.domain)) {
                    alreadyListed = true;
                }
            }
            if (!alreadyListed) {
                PullHealth row = new PullHealth();
                row.domain = failure.getKey();
                row.fingerprint = "—";
                row.appliedAt = "—";
                row.lastError = failure.getValue();
                rows.add(row);
            }
        }
        LOGGER.info("Exiting method pulls");
        return rows;
    }

    /**
     * Shortens a fingerprint hash to what the screen needs, tolerating null so
     * a domain that failed before applying (no fingerprint) shows a dash.
     *
     * @param hash the fingerprint, or null
     * @return its first twelve characters at most, or a dash when null
     */
    private String shortHash(String hash) {
        return hash == null ? "—" : hash.substring(0, Math.min(12, hash.length()));
    }

    /**
     * Formats a timestamp for the screen, tolerating null.
     *
     * @param dateTime the timestamp, or null
     * @return the formatted stamp, or a dash when there is none
     */
    private String stamp(LocalDateTime dateTime) {
        return dateTime == null ? "—" : dateTime.format(STAMP);
    }

    /**
     * Reads the ticket-uplink channel health straight from the store-sync outbox:
     * queue size, oldest waiting element, last recorded error.
     *
     * @return the uplink health
     */
    private UplinkHealth uplink() {
        LOGGER.info("Entering method uplink");
        UplinkHealth uplink = new UplinkHealth();
        uplink.count = SyncOutbox.count();
        SyncOutbox oldest = SyncOutbox.<SyncOutbox>find("order by createdAt").firstResult();
        uplink.oldest = oldest == null ? "—" : STAMP.format(oldest.createdAt);
        SyncOutbox errored = SyncOutbox.<SyncOutbox>find("lastError is not null order by id desc").firstResult();
        uplink.lastError = errored == null ? null : errored.lastError;
        LOGGER.info("Exiting method uplink");
        return uplink;
    }

    /**
     * Reads the consumption-delivery channel health straight from the consumption
     * outbox: how many wait (not yet applied), how many carry an error, the last
     * error.
     *
     * @return the consumption health
     */
    private ConsumptionHealth consumption() {
        LOGGER.info("Entering method consumption");
        ConsumptionHealth consumption = new ConsumptionHealth();
        consumption.pending = ConsumptionOutbox.count("applied = false");
        consumption.failed = ConsumptionOutbox.count("applied = false and lastError is not null");
        ConsumptionOutbox errored = ConsumptionOutbox.<ConsumptionOutbox>find(
                "applied = false and lastError is not null order by id desc").firstResult();
        consumption.lastError = errored == null ? null : errored.lastError;
        LOGGER.info("Exiting method consumption");
        return consumption;
    }

    /**
     * Maps one feed's acknowledged state to a screen status label.
     *
     * @param state the per-feed state from the keeper
     * @return the localised "Erreur" when it carries an error, "À jour" when
     *         acknowledged, "En attente" otherwise
     */
    private String feedStatus(EngineFeedService.FeedState state) {
        LOGGER.info("Entering method feedStatus");
        if (state.lastError() != null) {
            LOGGER.info("Exiting method feedStatus");
            return i18n.get("op.erreur");
        }
        LOGGER.info("Exiting method feedStatus");
        return state.applied() ? i18n.get("op.jour") : i18n.get("supervisor.attente");
    }
}
