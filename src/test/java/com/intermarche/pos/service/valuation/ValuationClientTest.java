package com.intermarche.pos.service.valuation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ValuationClient}.
 * <p>
 * The client is the HTTP half of the remote valuation engine: it posts a
 * {@link ValuationPayloads.BasketDto} to {@code {pos.valuation.url}/valuation}
 * with optional HTTP Basic authentication and parses the response with a
 * BigDecimal-safe, tolerant mapper. The only network collaborator, the private
 * final {@code httpClient} field, is replaced by a Mockito mock through
 * reflection; the real {@code objectMapper} is kept so the actual JSON
 * serialization and deserialization are exercised. The configuration fields
 * ({@code url}, {@code user}, {@code password}, {@code timeoutMillis}) are
 * package-private and assigned directly since the test lives in the production
 * package. No Quarkus context, no HTTP server and no database is booted.
 * <p>
 * The mocked {@link HttpClient#send} returns a mocked {@link HttpResponse}
 * whose status and body are pinned per test, so the whole call is driven
 * without any real network. The request built by {@code valuate} is captured
 * to assert the target URI, the JSON content type and the presence, absence or
 * exact value of the {@code Authorization} header.
 * <p>
 * Branch enumeration: {@code isEnabled} — url absent, url present but blank,
 * url present and non-blank (all resolving through the blank filter);
 * {@code targetUrl} — url present and url absent; {@code valuate} — both arms
 * of {@code !basicUser.isBlank()} (auth header added with password present,
 * auth header added with password absent, auth header skipped) and all four
 * arms of {@code statusCode < 200 || statusCode >= 300} (sub-200 failure,
 * 3xx+ failure and 2xx success).
 */
class ValuationClientTest {

    /**
     * Builds a mocked {@link HttpResponse} of the given status and body.
     *
     * @param status the HTTP status code
     * @param body the response body
     * @return the mocked response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> resp(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * Injects a mocked {@link HttpClient} into the private final
     * {@code httpClient} field of a client instance.
     *
     * @param client the client to patch
     * @param http the HTTP client to inject
     * @throws Exception if reflection fails
     */
    private void setHttpClient(ValuationClient client, HttpClient http) throws Exception {
        Field field = ValuationClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, http);
    }

    /**
     * Builds a client wired with a mocked HTTP client and default enabled
     * configuration (url present, no credentials, default timeout).
     *
     * @param http the HTTP client mock
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private ValuationClient client(HttpClient http) throws Exception {
        ValuationClient client = new ValuationClient();
        client.url = Optional.of("http://engine");
        client.user = Optional.empty();
        client.password = Optional.empty();
        client.timeoutMillis = 2500L;
        setHttpClient(client, http);
        return client;
    }

    /**
     * Builds a minimal basket with a single line for the request body.
     *
     * @return the basket
     */
    private ValuationPayloads.BasketDto basket() {
        ValuationPayloads.BasketDto basket = new ValuationPayloads.BasketDto();
        basket.storeCode = "0001";
        ValuationPayloads.ItemDto item = new ValuationPayloads.ItemDto();
        item.lineId = "L1";
        item.produceEan = "3000000000001";
        item.quantity = new BigDecimal("1");
        basket.items.add(item);
        return basket;
    }

    /**
     * {@code isEnabled} returns false when the url is absent.
     */
    @Test
    void isEnabledUrlAbsentFalse() {
        ValuationClient client = new ValuationClient();
        client.url = Optional.empty();
        assertFalse(client.isEnabled());
    }

    /**
     * {@code isEnabled} returns false when the url is present but blank.
     */
    @Test
    void isEnabledUrlBlankFalse() {
        ValuationClient client = new ValuationClient();
        client.url = Optional.of("   ");
        assertFalse(client.isEnabled());
    }

    /**
     * {@code isEnabled} returns true when the url is present and non-blank.
     */
    @Test
    void isEnabledUrlPresentTrue() {
        ValuationClient client = new ValuationClient();
        client.url = Optional.of("http://engine");
        assertTrue(client.isEnabled());
    }

    /**
     * {@code targetUrl} returns the configured url when it is present.
     */
    @Test
    void targetUrlPresentReturnsUrl() {
        ValuationClient client = new ValuationClient();
        client.url = Optional.of("http://engine");
        assertEquals("http://engine", client.targetUrl());
    }

    /**
     * {@code targetUrl} returns an empty string when the url is absent.
     */
    @Test
    void targetUrlAbsentReturnsEmpty() {
        ValuationClient client = new ValuationClient();
        client.url = Optional.empty();
        assertEquals("", client.targetUrl());
    }

    /**
     * {@code valuate} posts to {@code {url}/valuation} with a JSON content
     * type, adds an {@code Authorization} header carrying the Base64 of
     * {@code user:password} when the user is set, and parses the response as
     * BigDecimal amounts through the tolerant reader (unknown fields ignored).
     *
     * @throws Exception on transport or parsing failure
     */
    @Test
    void valuateSuccessWithAuthPasswordPresent() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ValuationClient client = client(http);
        client.user = Optional.of("alice");
        client.password = Optional.of("secret");
        String json = "{\"totalPrice\":{\"amountIncludingTax\":12.50,\"amountExcludingTax\":10.00,"
                + "\"vatRate\":20.0},\"offers\":[{\"type\":\"BOGO\",\"amount\":{\"amountIncludingTax\":5.0}}],"
                + "\"advantages\":[],\"unknownField\":true}";
        doReturn(resp(200, json)).when(http).send(any(HttpRequest.class), any());
        ValuationPayloads.ValuationResponseDto result = client.valuate(basket());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verifyRequest(http, captor);
        HttpRequest request = captor.getValue();
        assertEquals("http://engine/valuation", request.uri().toString());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""));
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "alice:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, request.headers().firstValue("Authorization").orElse(""));
        assertEquals(new BigDecimal("12.50"), result.totalPrice.amountIncludingTax);
        assertEquals(new BigDecimal("10.00"), result.totalPrice.amountExcludingTax);
        assertEquals("BOGO", result.offers.get(0).type);
        assertEquals(new BigDecimal("5.0"), result.offers.get(0).amount.amountIncludingTax);
    }

    /**
     * {@code valuate} still emits an {@code Authorization} header when the user
     * is set but the password is absent, folding the empty password into the
     * {@code user:} token.
     *
     * @throws Exception on transport or parsing failure
     */
    @Test
    void valuateSuccessWithAuthPasswordAbsent() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ValuationClient client = client(http);
        client.user = Optional.of("alice");
        client.password = Optional.empty();
        doReturn(resp(200, "{}")).when(http).send(any(HttpRequest.class), any());
        client.valuate(basket());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verifyRequest(http, captor);
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "alice:".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, captor.getValue().headers().firstValue("Authorization").orElse(""));
    }

    /**
     * {@code valuate} skips the {@code Authorization} header when the user is
     * blank and returns an empty response parsed by the tolerant reader.
     *
     * @throws Exception on transport or parsing failure
     */
    @Test
    void valuateSuccessNoAuthUserBlank() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ValuationClient client = client(http);
        client.user = Optional.of("   ");
        doReturn(resp(299, "{}")).when(http).send(any(HttpRequest.class), any());
        ValuationPayloads.ValuationResponseDto result = client.valuate(basket());
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verifyRequest(http, captor);
        assertFalse(captor.getValue().headers().firstValue("Authorization").isPresent());
        assertTrue(result.offers.isEmpty());
    }

    /**
     * {@code valuate} throws when the engine answers below 200 (first arm of
     * the status guard).
     *
     * @throws Exception never on the arranging path
     */
    @Test
    void valuateStatusBelow200Throws() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ValuationClient client = client(http);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        ValuationPayloads.BasketDto basket = basket();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.valuate(basket));
        assertTrue(error.getMessage().contains("199"));
    }

    /**
     * {@code valuate} throws when the engine answers 300 or above (second arm
     * of the status guard).
     *
     * @throws Exception never on the arranging path
     */
    @Test
    void valuateStatusAtLeast300Throws() throws Exception {
        HttpClient http = mock(HttpClient.class);
        ValuationClient client = client(http);
        doReturn(resp(500, "boom")).when(http).send(any(HttpRequest.class), any());
        ValuationPayloads.BasketDto basket = basket();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> client.valuate(basket));
        assertTrue(error.getMessage().contains("500"));
    }

    /**
     * Verifies a single {@code send} occurred and captures the built request.
     *
     * @param http the mocked HTTP client
     * @param captor the request captor to fill
     * @throws Exception if the mocked send signature is not satisfied
     */
    private void verifyRequest(HttpClient http, ArgumentCaptor<HttpRequest> captor) throws Exception {
        org.mockito.Mockito.verify(http).send(captor.capture(), any());
    }
}
