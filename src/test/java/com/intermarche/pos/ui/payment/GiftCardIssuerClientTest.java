package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GiftCardIssuerClient} (EXP-66), targeting 100% branch
 * coverage.
 * <p>
 * The client refuses a null or blank card, answers the permissive local stub when
 * no registry URL is configured, and — with a URL — queries the issuer over HTTP,
 * refusing (card not honoured) on any transport failure. The private final
 * {@code httpClient} is swapped by reflection so the HTTP branches run without a
 * socket; a real {@link ObjectMapper} makes the deserialization genuine. Coverage
 * exercises both legs of the card and URL guards, the token filter (blank vs
 * non-blank), every condition of the status/body compound, both arms of the
 * balance null-guard, and the catch arm. Each test asserts absolute values.
 */
class GiftCardIssuerClientTest {

    /**
     * Builds a client with the given URL and token, a real mapper and the mocked
     * HTTP client forced onto the private final field.
     *
     * @param url the issuer-registry URL Optional
     * @param token the shared-token Optional
     * @param http the mocked HTTP client
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private GiftCardIssuerClient client(Optional<String> url, Optional<String> token,
            HttpClient http) throws Exception {
        GiftCardIssuerClient client = new GiftCardIssuerClient();
        client.issuerUrl = url;
        client.token = token;
        client.objectMapper = new ObjectMapper();
        Field field = GiftCardIssuerClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, http);
        return client;
    }

    /**
     * Builds a mocked string HTTP response with the given status and body.
     *
     * @param status the HTTP status
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
     * A null card number is refused with a nil balance (first guard, null leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesNullCard() throws Exception {
        GiftCardIssuerClient.IssuerVerdict verdict =
                client(Optional.empty(), Optional.empty(), mock(HttpClient.class)).query(null);
        Assertions.assertFalse(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }

    /**
     * A blank card number is refused (first guard, isBlank leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesBlankCard() throws Exception {
        Assertions.assertFalse(
                client(Optional.empty(), Optional.empty(), mock(HttpClient.class)).query("   ").valid());
    }

    /**
     * With no registry URL configured, the local stub honours a non-blank card
     * with a nil balance (empty-URL arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryHonoursLocallyWhenNoUrl() throws Exception {
        GiftCardIssuerClient.IssuerVerdict verdict =
                client(Optional.empty(), Optional.empty(), mock(HttpClient.class)).query("GC1");
        Assertions.assertTrue(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }

    /**
     * With a blank URL string present, the stub arm still applies (isBlank leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryHonoursLocallyWhenUrlBlank() throws Exception {
        Assertions.assertTrue(
                client(Optional.of("   "), Optional.empty(), mock(HttpClient.class)).query("GC1").valid());
    }

    /**
     * A 200 answer with a valid card and a balance yields the honoured verdict
     * with that balance (success arm; balance non-null; token filter true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryParsesValidCardWithBalance() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"valid\":true,\"balance\":25.00}"))
                .when(http).send(any(HttpRequest.class), any());
        GiftCardIssuerClient.IssuerVerdict verdict =
                client(Optional.of("http://issuer"), Optional.of("TOK"), http).query("GC1");
        Assertions.assertTrue(verdict.valid());
        Assertions.assertEquals(0, new BigDecimal("25.00").compareTo(verdict.balance()));
    }

    /**
     * A 200 answer with a missing balance defaults the balance to zero (balance
     * null-guard false arm); a blank present token is filtered out (token filter
     * false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryDefaultsBalanceWhenMissing() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"valid\":true}")).when(http).send(any(HttpRequest.class), any());
        GiftCardIssuerClient.IssuerVerdict verdict =
                client(Optional.of("http://issuer"), Optional.of("   "), http).query("GC1");
        Assertions.assertTrue(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }

    /**
     * A status below 200 refuses the card (compound first condition true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesOnStatusBelow200() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(
                client(Optional.of("http://issuer"), Optional.empty(), http).query("GC1").valid());
    }

    /**
     * A non-2xx answer refuses the card (status &ge; 300 arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(
                client(Optional.of("http://issuer"), Optional.empty(), http).query("GC1").valid());
    }

    /**
     * A 200 answer with a null body refuses the card (null-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesOnNullBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, null)).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(
                client(Optional.of("http://issuer"), Optional.empty(), http).query("GC1").valid());
    }

    /**
     * A 200 answer with a blank body refuses the card (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(
                client(Optional.of("http://issuer"), Optional.empty(), http).query("GC1").valid());
    }

    /**
     * A thrown transport error refuses the card (catch arm): an unverified card
     * must not pay when the issuer is unreachable.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void queryRefusesOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        GiftCardIssuerClient.IssuerVerdict verdict =
                client(Optional.of("http://issuer"), Optional.empty(), http).query("GC1");
        Assertions.assertFalse(verdict.valid());
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(verdict.balance()));
    }
}
