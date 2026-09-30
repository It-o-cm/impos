package com.intermarche.pos.ui.recall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
 * Unit tests for {@link BackOfficeDocumentClient}, targeting 100% branch coverage.
 * <p>
 * The client fetches a recalled document from the store node and is tolerant by
 * construction: a blank code, no URL, a non-2xx answer, an empty body or a thrown
 * transport error all resolve to null. The private final {@code httpClient} is
 * swapped by reflection so the HTTP branches run without a socket; a real
 * {@link ObjectMapper} makes the deserialization genuine. Each test is isolated
 * and asserts absolute expected values, covering BOTH arms of every guard: the
 * two legs of the {@code code}/{@code documentUrl} guards, the token filter (blank
 * vs non-blank), each condition of the status/body compound (status < 200, status
 * &ge; 300, null body, blank body, all false) and the catch arm.
 */
class BackOfficeDocumentClientTest {

    /**
     * Builds a client with the given URL and token, a real mapper and the mocked
     * HTTP client forced onto the private final field.
     *
     * @param url the document-store URL Optional
     * @param token the shared-token Optional
     * @param http the mocked HTTP client
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private BackOfficeDocumentClient client(Optional<String> url, Optional<String> token,
            HttpClient http) throws Exception {
        BackOfficeDocumentClient client = new BackOfficeDocumentClient();
        client.documentUrl = url;
        client.token = token;
        client.objectMapper = new ObjectMapper();
        Field field = BackOfficeDocumentClient.class.getDeclaredField("httpClient");
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
     * A null code resolves to no document (first guard true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchNullCodeReturnsNull() throws Exception {
        BackOfficeDocumentClient client = client(Optional.empty(), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetch(null));
    }

    /**
     * A blank code resolves to no document (first guard: non-null, blank arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchBlankCodeReturnsNull() throws Exception {
        BackOfficeDocumentClient client = client(Optional.empty(), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetch("   "));
    }

    /**
     * With no store URL configured the local stub knows no document, so a real
     * code still resolves to null (empty-URL arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchWithoutStoreUrlReturnsNull() throws Exception {
        BackOfficeDocumentClient client = client(Optional.empty(), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A blank store URL is treated as no store (blank-URL arm): still null.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchWithBlankStoreUrlReturnsNull() throws Exception {
        BackOfficeDocumentClient client = client(Optional.of("   "), Optional.empty(), mock(HttpClient.class));
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A 200 answer with a JSON body is parsed into the document (success arm:
     * status in range, body non-blank; a non-blank token adds the header — token
     * filter true arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchParsesA200Body() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"code\":\"CMD1\",\"kind\":\"COMMANDE\",\"acompteAlreadyPaid\":10.00,"
                + "\"lines\":[{\"ean\":\"123\",\"label\":\"Article\",\"quantity\":2,"
                + "\"unitPrice\":3.50,\"vatRate\":5.5}]}"))
                .when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.of("TOK"), http);
        RecalledDocument document = client.fetch("CMD1");
        Assertions.assertEquals("CMD1", document.code);
        Assertions.assertEquals(RecalledDocument.Kind.COMMANDE, document.kind);
        Assertions.assertEquals(1, document.lines.size());
        Assertions.assertEquals("123", document.lines.get(0).ean());
        Assertions.assertTrue(document.hasAcompte());
    }

    /**
     * A status below 200 resolves to null (compound first condition true arm); a
     * blank present token is filtered out (token filter false arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnStatusBelow200() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(199, "irrelevant")).when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.of("   "), http);
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A non-2xx answer resolves to null (status &ge; 300 arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.empty(), http);
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A 200 answer with a null body resolves to null (null-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnNullBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, null)).when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.of("TOK"), http);
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A 200 answer with a blank body resolves to null (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.empty(), http);
        Assertions.assertNull(client.fetch("CMD1"));
    }

    /**
     * A thrown transport error resolves to null (catch arm) rather than
     * propagating — a node down never disturbs the lane.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchReturnsNullOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        BackOfficeDocumentClient client = client(Optional.of("http://store"), Optional.empty(), http);
        Assertions.assertNull(client.fetch("CMD1"));
    }
}
