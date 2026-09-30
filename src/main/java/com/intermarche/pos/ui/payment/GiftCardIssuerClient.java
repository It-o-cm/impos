package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.Optional;

/**
 * The seam to a supplier gift-card ISSUER REGISTRY (EXP-66): a supplier gift
 * card is never simply administered and trusted — before it settles anything,
 * the issuer is asked whether the card is valid and what balance it still
 * carries, and the payment is capped at that balance.
 *
 * <p>The real registry lives behind {@code pos.giftcard.issuer-url}: when a URL
 * is configured the register issues {@code GET <url>/api/giftcard/{cardNumber}}
 * carrying the shared {@code X-Sync-Token}, and reads {@code {valid, balance}}.
 * When no URL is configured this client answers a LOCAL stub verdict so a
 * standalone lane still behaves deterministically in demonstration and test: a
 * card whose number is blank is refused, any other is accepted with a nil balance
 * (the store must configure the registry to obtain real balances).
 *
 * <p>Robustness never lets a payment path fall over: on the CONNECTED lane a node
 * down, unreachable, a non-2xx answer or an empty body all resolve to a REFUSAL
 * (card not honoured, nil balance) rather than an exception — an unverified card
 * must not pay. The endpoint is guarded node-side by {@code pos.role=store} + the
 * token; the connected query is [N] e2e residue (it needs imedge running).
 */
@ApplicationScoped
public class GiftCardIssuerClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(GiftCardIssuerClient.class);

    /** The issuer registry base URL; absent = the local stub verdict applies. */
    @ConfigProperty(name = "pos.giftcard.issuer-url")
    Optional<String> issuerUrl;

    /** Shared ingestion token presented to the store node; absent = none. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /** The JSON reader for the issuer's answer. */
    @jakarta.inject.Inject
    ObjectMapper objectMapper;

    /** Shared HTTP client toward the registry (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * The issuer's verdict on a gift card: whether it is honoured and, when it
     * is, the balance available to spend.
     *
     * @param valid true when the issuer recognises the card and it may pay
     * @param balance the balance available on the card, never null
     */
    public record IssuerVerdict(boolean valid, BigDecimal balance) {
    }

    /** The issuer's answer body (tolerant subset). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class IssuerAnswer {
        /** Whether the issuer honours the card. */
        public boolean valid;
        /** The balance available on the card, or null. */
        public BigDecimal balance;
    }

    /**
     * Interrogates the issuer registry for a gift card (EXP-66).
     *
     * @param cardNumber the scanned or keyed gift-card number
     * @return the issuer's verdict (validity and available balance)
     */
    public IssuerVerdict query(String cardNumber) {
        LOGGER.info("Entering method query");
        if (cardNumber == null || cardNumber.isBlank()) {
            LOGGER.info("Exiting method query with invalid card");
            return new IssuerVerdict(false, BigDecimal.ZERO);
        }
        if (issuerUrl.isEmpty() || issuerUrl.get().isBlank()) {
            // No registry configured: the local stub honours the card with no
            // balance — a real balance requires the administered registry.
            LOGGER.info("Exiting method query with local stub verdict");
            return new IssuerVerdict(true, BigDecimal.ZERO);
        }
        try {
            String encoded = URLEncoder.encode(cardNumber.trim(), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(issuerUrl.get().trim() + "/api/giftcard/" + encoded))
                    .timeout(Duration.ofSeconds(3))
                    .GET();
            token.filter(t -> !t.isBlank()).ifPresent(t -> builder.header("X-Sync-Token", t));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method query (issuer refused, card not honoured)");
                return new IssuerVerdict(false, BigDecimal.ZERO);
            }
            IssuerAnswer answer = objectMapper.readValue(response.body(), IssuerAnswer.class);
            BigDecimal balance = answer.balance != null ? answer.balance : BigDecimal.ZERO;
            LOGGER.info("Exiting method query (issuer answered)");
            return new IssuerVerdict(answer.valid, balance);
        } catch (Exception e) {
            // Robust: an unreachable issuer must NOT honour an unverified card.
            LOGGER.warnf("Registre émetteur injoignable pour la carte %s : %s",
                    cardNumber, e.getMessage());
            LOGGER.info("Exiting method query (issuer unreachable, card not honoured)");
            return new IssuerVerdict(false, BigDecimal.ZERO);
        }
    }
}
