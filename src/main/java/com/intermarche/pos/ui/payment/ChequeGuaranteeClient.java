package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.service.PosSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The seam to the CHEQUE-GUARANTEE service (BO-03-02-24): the provider a
 * register asks, before a cheque is taken, whether it guarantees that cheque.
 *
 * <p>The service is ADMINISTERED: the back office names the provider
 * ({@code cheque.guarantee-provider}) and, when it is not the register's own,
 * its address ({@code cheque.guarantee-url}); with no address administered,
 * the register's configured {@code pos.cheque-guarantee.url} applies, which
 * in development is the simulated provider the register itself serves. No
 * provider named, the service is not configured and the register keeps the
 * supervisor authorization it always had.
 *
 * <p>The exchange is {@code POST <url>/api/cheque-guarantee/authorize} with
 * {@code {provider, reference, amount, accountNumber}}, answered
 * {@code {approved, authorization, message}}. ROBUSTNESS: a service down, slow
 * past the administered delay, answering a non-2xx status or an unreadable
 * body is UNAVAILABLE — never an approval, never a refusal: the caller then
 * falls back on the supervisor, who can still take the cheque on their own
 * responsibility.
 */
@ApplicationScoped
public class ChequeGuaranteeClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ChequeGuaranteeClient.class);

    /** What a guarantee request ends with. */
    public enum Status {

        /** The provider guarantees the cheque. */
        APPROVED,

        /** The provider refuses the cheque. */
        REFUSED,

        /** No answer could be had: the supervisor decides. */
        UNAVAILABLE
    }

    /**
     * The answer of one guarantee request.
     *
     * @param status what the request ended with
     * @param authorization the authorization number of an approval, or null
     * @param message the provider's wording, or null
     * @param provider the provider asked
     */
    public record Verdict(Status status, String authorization, String message, String provider) {
    }

    /** The register's configured service address, used when none is administered. */
    @ConfigProperty(name = "pos.cheque-guarantee.url")
    Optional<String> configuredUrl;

    /** The administered provider, address and delay. */
    @Inject
    PosSettingsService posSettingsService;

    /** The JSON reader/writer of the exchange. */
    @Inject
    ObjectMapper objectMapper;

    /** The HTTP client toward the service. */
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** The service's answer body (tolerant subset). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class Answer {

        /** Whether the provider guarantees the cheque. */
        public boolean approved;

        /** The authorization number of an approval. */
        public String authorization;

        /** The provider's wording. */
        public String message;
    }

    /**
     * Whether a guarantee service is administered: a provider is named and an
     * address is known.
     *
     * @return true when cheques are submitted to a provider
     */
    public boolean isConfigured() {
        return !posSettingsService.chequeGuaranteeProvider().isEmpty() && !url().isEmpty();
    }

    /**
     * The address the requests go to: the administered one, else the
     * register's configured one.
     *
     * @return the base address, trimmed, possibly empty
     */
    String url() {
        String administered = posSettingsService.chequeGuaranteeUrl();
        if (!administered.isEmpty()) {
            return administered;
        }
        return configuredUrl.orElse("").trim();
    }

    /**
     * Asks the provider to guarantee one cheque (BO-03-02-24).
     *
     * @param reference the sale reference the cheque settles
     * @param amount the cheque amount
     * @param accountNumber the account number keyed, or null
     * @return the verdict, never null
     */
    public Verdict authorize(String reference, BigDecimal amount, String accountNumber) {
        LOGGER.info("Entering method authorize with reference: " + reference + ", amount: " + amount);
        String provider = posSettingsService.chequeGuaranteeProvider();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("provider", provider);
            body.put("reference", reference);
            body.put("amount", amount);
            body.put("accountNumber", accountNumber);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url() + "/api/cheque-guarantee/authorize"))
                    .timeout(Duration.ofMillis(posSettingsService.chequeGuaranteeTimeoutMillis()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.info("Exiting method authorize (service unavailable)");
                return new Verdict(Status.UNAVAILABLE, null, null, provider);
            }
            Answer answer = objectMapper.readValue(response.body(), Answer.class);
            LOGGER.info("Exiting method authorize (approved: " + answer.approved + ")");
            return new Verdict(answer.approved ? Status.APPROVED : Status.REFUSED,
                    answer.authorization, answer.message, provider);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Exiting method authorize (interrupted)");
            return new Verdict(Status.UNAVAILABLE, null, null, provider);
        } catch (Exception e) {
            LOGGER.warnf("Service de garantie chèque injoignable : %s", e.getMessage());
            LOGGER.info("Exiting method authorize (service unreachable)");
            return new Verdict(Status.UNAVAILABLE, null, null, provider);
        }
    }
}
