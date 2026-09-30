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
 * Unit tests for {@link RestoFlashClient} (EXP-67), targeting 100% branch coverage.
 * <p>
 * The client refuses a null/blank token or a non-positive amount, confirms via the
 * local stub when no URL is configured, and — with a URL — posts to Resto Flash,
 * refusing (voucher not accepted) on any transport failure. The private final
 * {@code httpClient} is swapped by reflection so the HTTP branches run without a
 * socket; a real {@link ObjectMapper} makes the (de)serialization genuine.
 * Coverage exercises each leg of the input guard, both legs of the URL guard, the
 * token filter (blank vs non-blank), every condition of the status/body compound,
 * the accepted true/false answers, and the catch arm.
 */
class RestoFlashClientTest {

    /**
     * Builds a client with the given URL and sync token, a real mapper and the
     * mocked HTTP client forced onto the private final field.
     *
     * @param url the Resto Flash service URL Optional
     * @param syncToken the shared-token Optional
     * @param http the mocked HTTP client
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private RestoFlashClient client(Optional<String> url, Optional<String> syncToken,
            HttpClient http) throws Exception {
        RestoFlashClient client = new RestoFlashClient();
        client.serviceUrl = url;
        client.syncToken = syncToken;
        client.objectMapper = new ObjectMapper();
        Field field = RestoFlashClient.class.getDeclaredField("httpClient");
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
     * A null token is refused (first guard, token-null leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesNullToken() throws Exception {
        Assertions.assertFalse(client(Optional.empty(), Optional.empty(), mock(HttpClient.class))
                .confirm(null, new BigDecimal("5")));
    }

    /**
     * A blank token is refused (token-isBlank leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesBlankToken() throws Exception {
        Assertions.assertFalse(client(Optional.empty(), Optional.empty(), mock(HttpClient.class))
                .confirm("  ", new BigDecimal("5")));
    }

    /**
     * A null amount is refused (amount-null leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesNullAmount() throws Exception {
        Assertions.assertFalse(client(Optional.empty(), Optional.empty(), mock(HttpClient.class))
                .confirm("TOK", null));
    }

    /**
     * A non-positive amount is refused (amount-signum leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesNonPositiveAmount() throws Exception {
        Assertions.assertFalse(client(Optional.empty(), Optional.empty(), mock(HttpClient.class))
                .confirm("TOK", BigDecimal.ZERO));
    }

    /**
     * With no service URL configured, a valid token confirms via the local stub
     * (empty-URL arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmConfirmsLocallyWhenNoUrl() throws Exception {
        Assertions.assertTrue(client(Optional.empty(), Optional.empty(), mock(HttpClient.class))
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * With a blank URL string present, the stub arm still confirms (URL-isBlank leg).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmConfirmsLocallyWhenUrlBlank() throws Exception {
        Assertions.assertTrue(client(Optional.of("   "), Optional.empty(), mock(HttpClient.class))
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A 200 answer with accepted=true confirms (success arm; token filter true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmAcceptsA200True() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"accepted\":true}")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertTrue(client(Optional.of("http://restoflash"), Optional.of("TOK"), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A 200 answer with accepted=false refuses (answer false arm); a blank present
     * token is filtered out (token filter false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesA200False() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"accepted\":false}")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.of("   "), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A status below 200 refuses (compound first condition true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesOnStatusBelow200() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.empty(), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A non-2xx answer refuses (status &ge; 300 arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.empty(), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A 200 answer with a null body refuses (null-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesOnNullBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, null)).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.empty(), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A 200 answer with a blank body refuses (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.empty(), http)
                .confirm("TOK", new BigDecimal("5")));
    }

    /**
     * A thrown transport error refuses (catch arm): an unreachable service must
     * not accept an unconfirmed voucher.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void confirmRefusesOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        Assertions.assertFalse(client(Optional.of("http://restoflash"), Optional.empty(), http)
                .confirm("TOK", new BigDecimal("5")));
    }
}
