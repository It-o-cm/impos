package com.intermarche.pos.service.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Register-side pull loop of the centralized referentials (phase 6 lot 3):
 * every {@code pos.referential.pull-seconds}, fetches the per-domain
 * fingerprints from the store node and, for each domain whose fingerprint
 * differs from the last applied one, downloads the full snapshot page by
 * page and applies it. A fresh register bootstraps itself the same way (no
 * fingerprint recorded yet, everything differs). Active only on a register
 * with a configured store URL.
 * <p>
 * Consistency note: the pages of one snapshot are fetched without a shared
 * transaction, so an import running on the store node DURING a pull can
 * yield a torn snapshot. This is accepted by design: the recorded
 * fingerprint then no longer matches the store's next computation, and the
 * following cycle re-pulls a clean one — the fingerprint loop is
 * self-healing against its own race.
 */
@ApplicationScoped
public class RefPullService {

    private static final Logger LOG = Logger.getLogger(RefPullService.class);

    /** Snapshot page size. */
    private static final int PAGE_SIZE = 1000;

    /** Seconds between two pull cycles. */
    @ConfigProperty(name = "pos.referential.pull-seconds", defaultValue = "300")
    long pullSeconds;

    /** The role of this node: only registers pull. */
    @ConfigProperty(name = "pos.role", defaultValue = "register")
    String role;

    /** Shared token sent to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    @Inject
    SyncOutboxService syncOutboxService;

    @Inject
    RefApplyService refApplyService;

    @Inject
    ObjectMapper objectMapper;

    /** The pull loop executor, or null when the pull is disabled. */
    private ScheduledExecutorService executor;

    /** The HTTP client used toward the store node. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Starts the pull loop at register startup when a store URL is
     * configured; the first cycle runs shortly after boot so a fresh
     * register fills itself quickly.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (!"register".equalsIgnoreCase(role) || !syncOutboxService.isEnabled()) {
            LOG.info("Tirage des référentiels désactivé (rôle store ou pos.sync.store-url absent)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "referential-pull");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pullSafely, 15, pullSeconds, TimeUnit.SECONDS);
        LOG.infof("Tirage des référentiels actif depuis %s (toutes les %ds)",
                syncOutboxService.getStoreUrl(), pullSeconds);
    }

    /**
     * Stops the pull loop at shutdown.
     */
    @PreDestroy
    void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one pull cycle, never letting an exception kill the loop.
     */
    private void pullSafely() {
        try {
            pullOnce();
        } catch (Exception e) {
            LOG.errorf("Cycle de tirage des référentiels en erreur: %s", e.getMessage());
        }
    }

    /**
     * Runs one pull cycle: compares the fingerprints and applies every
     * changed domain in dependency order.
     */
    void pullOnce() throws Exception {
        Map<String, String> remote = objectMapper.readValue(
                get("/api/referential/versions"), new TypeReference<Map<String, String>>() {});

        for (String domain : RefExportService.DOMAINS) {
            String remoteFingerprint = remote.get(domain);
            if (remoteFingerprint == null || remoteFingerprint.equals(refApplyService.lastApplied(domain))) {
                continue;
            }
            LOG.infof("Référentiel %s modifié: tirage du snapshot", domain);
            applyDomain(domain, remoteFingerprint);
        }
    }

    /**
     * Downloads and applies the full snapshot of one domain, then records
     * the applied fingerprint.
     *
     * @param domain the referential domain
     * @param fingerprint the remote fingerprint being applied
     */
    private void applyDomain(String domain, String fingerprint) throws Exception {
        switch (domain) {
            case "FAMILIES" -> refApplyService.applyFamilies(
                    this.<RefPayloads.FamilyDto>pages(domain, new TypeReference<List<RefPayloads.FamilyDto>>() {}));
            case "PRODUCTS" -> refApplyService.applyProducts(
                    this.<RefPayloads.ProductDto>pages(domain, new TypeReference<List<RefPayloads.ProductDto>>() {}));
            case "PRICES" -> refApplyService.applyPrices(
                    this.<RefPayloads.PriceDto>pages(domain, new TypeReference<List<RefPayloads.PriceDto>>() {}));
            case "EMPLOYEES" -> refApplyService.applyEmployees(
                    this.<RefPayloads.EmployeeDto>pages(domain, new TypeReference<List<RefPayloads.EmployeeDto>>() {}));
            case "COUPON_TYPES" -> refApplyService.applyCouponTypes(
                    this.<RefPayloads.CouponTypeDto>pages(domain, new TypeReference<List<RefPayloads.CouponTypeDto>>() {}));
            default -> throw new IllegalArgumentException("Domaine inconnu: " + domain);
        }
        refApplyService.recordApplied(domain, fingerprint);
    }

    /**
     * Downloads every page of a domain's snapshot.
     *
     * @param <T> the payload type
     * @param domain the referential domain
     * @param pageType the Jackson type of one page
     * @return all rows of the snapshot
     */
    private <T> List<T> pages(String domain, TypeReference<List<T>> pageType) throws Exception {
        List<T> all = new ArrayList<>();
        int page = 0;
        while (true) {
            List<T> rows = objectMapper.readValue(
                    get("/api/referential/" + domain + "?page=" + page + "&size=" + PAGE_SIZE), pageType);
            if (rows.isEmpty()) break;
            all.addAll(rows);
            page++;
        }
        return all;
    }

    /**
     * Runs an authenticated GET against the store node.
     *
     * @param path the path to fetch
     * @return the response body
     * @throws IllegalStateException on a non-2xx status
     */
    private String get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(syncOutboxService.getStoreUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        String sharedToken = token.orElse("");
        if (!sharedToken.isBlank()) {
            builder.header("X-Sync-Token", sharedToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " sur " + path);
        }
        return response.body();
    }
}
