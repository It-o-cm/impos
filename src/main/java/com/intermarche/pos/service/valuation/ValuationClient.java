package com.intermarche.pos.service.valuation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * HTTP half of the remote valuation (phase 7 lot 1): posts a basket to
 * {@code {pos.valuation.url}/valuation} with HTTP Basic authentication and
 * parses the response.
 * <p>
 * Engine selection is BY CONFIGURATION: {@code pos.valuation.url} absent =
 * the local engine (current catalog behavior) runs alone, present = the
 * remote engine is called at payment entry with the local engine as the
 * degraded fallback — same absence-means-off pattern as the store sync.
 * The mapper reads floating numbers as {@code BigDecimal} (money never
 * touches a double) and the DTOs ignore unknown fields (tolerant reader):
 * the engine can grow without breaking a deployed register fleet.
 */
@ApplicationScoped
public class ValuationClient {

    /** Base URL of the remote engine; absent = remote valuation disabled. */
    @ConfigProperty(name = "pos.valuation.url")
    Optional<String> url;

    /** Basic-auth user of the engine; absent = no authentication header. */
    @ConfigProperty(name = "pos.valuation.user")
    Optional<String> user;

    /** Basic-auth password of the engine. */
    @ConfigProperty(name = "pos.valuation.password")
    Optional<String> password;

    /** Call timeout in milliseconds (short: the cashier is waiting). */
    @ConfigProperty(name = "pos.valuation.timeout-millis", defaultValue = "2500")
    long timeoutMillis;

    /** The HTTP client toward the engine. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** BigDecimal-safe, tolerant JSON mapper. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Tells whether the remote engine is configured.
     *
     * @return true when {@code pos.valuation.url} is set and non-blank
     */
    public boolean isEnabled() {
        return url.filter(u -> !u.isBlank()).isPresent();
    }

    /**
     * Returns the configured engine base URL for boot-log announcement.
     *
     * @return the base URL, or an empty string when disabled
     */
    public String targetUrl() {
        return url.orElse("");
    }

    /**
     * Posts a basket to the engine and parses the valuation.
     *
     * @param basket the basket to value
     * @return the parsed valuation response
     * @throws Exception on any transport, authentication or parsing failure
     *         (the caller degrades to the local engine)
     */
    public ValuationPayloads.ValuationResponseDto valuate(ValuationPayloads.BasketDto basket) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.orElseThrow() + "/valuation"))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(basket)));
        String basicUser = user.orElse("");
        if (!basicUser.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (basicUser + ":" + password.orElse("")).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " du moteur de valorisation");
        }
        return objectMapper.readValue(response.body(), ValuationPayloads.ValuationResponseDto.class);
    }
}
