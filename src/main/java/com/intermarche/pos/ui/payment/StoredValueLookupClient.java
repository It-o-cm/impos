package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.service.sync.SyncPayloads;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Asks the store node about an instrument this register never printed — a
 * credit note or a gift card printed by another register of the shop — and
 * ADOPTS it when the node knows it (BO-03-06-48).
 *
 * <p>The register's registry stays the one that settles: the node's copy is
 * written into it, under the issuer's register and number, and redeemed like
 * any other; the next push reports the new balance to the node, under the same
 * key. A number the node does not know either is unknown to the shop, and the
 * caller says so at the level the back office administers.
 */
@ApplicationScoped
public class StoredValueLookupClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoredValueLookupClient.class);

    /** Carries the store node's URL and whether the synchronization is wired at all. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** HTTP client toward the store node. */
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Tolerant reader: a newer store node may send properties this register ignores. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Asks the store node for an instrument by its number.
     *
     * @param number the scanned number
     * @return the node's copy, or null when there is no node, it is unreachable
     *         or it does not know the number
     */
    public SyncPayloads.StoredValueDto lookup(String number) {
        LOGGER.info("Entering method lookup with number: " + number);
        if (!syncOutboxService.isEnabled() || number == null || number.isBlank()) {
            LOGGER.info("Exiting method lookup (nothing to ask)");
            return null;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl() + "/api/sync/stored-value/"
                            + URLEncoder.encode(number.trim(), StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.info("Exiting method lookup (HTTP " + response.statusCode() + ")");
                return null;
            }
            SyncPayloads.StoredValueDto found =
                    objectMapper.readValue(response.body(), SyncPayloads.StoredValueDto.class);
            LOGGER.info("Exiting method lookup");
            return found;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Exiting method lookup (interrupted)");
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Nœud magasin injoignable pour l'instrument %s : %s", number, e.getMessage());
            LOGGER.info("Exiting method lookup (unreachable)");
            return null;
        }
    }

    /**
     * Adopts the node's copy of an instrument into this register's registry.
     *
     * @param number the scanned number
     * @return the adopted instrument, or null when the node does not know it
     */
    @Transactional
    public StoredValue adopt(String number) {
        LOGGER.info("Entering method adopt with number: " + number);
        SyncPayloads.StoredValueDto copy = lookup(number);
        if (copy == null || copy.number == null || copy.kind == null) {
            LOGGER.info("Exiting method adopt (unknown)");
            return null;
        }
        StoredValue instrument = new StoredValue();
        instrument.number = copy.number;
        instrument.kind = StoredValue.Kind.valueOf(copy.kind);
        instrument.initialAmount = copy.initialAmount == null ? BigDecimal.ZERO : copy.initialAmount;
        instrument.balance = copy.balance == null ? BigDecimal.ZERO : copy.balance;
        instrument.status = "EXHAUSTED".equals(copy.status) ? StoredValue.Status.EXHAUSTED : StoredValue.Status.ACTIVE;
        instrument.issuedAt = copy.issuedAt == null ? LocalDateTime.now() : LocalDateTime.parse(copy.issuedAt);
        instrument.validUntil = copy.validUntil == null ? null : LocalDateTime.parse(copy.validUntil);
        instrument.exhaustedAt = copy.exhaustedAt == null ? null : LocalDateTime.parse(copy.exhaustedAt);
        instrument.issuingTerminalId = copy.issuingTerminalId;
        instrument.issuingDocumentNumber = copy.issuingDocumentNumber;
        instrument.persist();
        LOGGER.info("Exiting method adopt");
        return instrument;
    }
}
