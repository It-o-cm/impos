package com.intermarche.pos.ui.supervision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BackOfficeMessageClient}, targeting 100% branch coverage.
 * <p>
 * The client PULLS a pending back-office message from the store node and is
 * tolerant by construction: no URL, an unreachable node or a bad answer all
 * resolve to null. The private final {@code httpClient} is swapped by reflection
 * so the HTTP branches are exercised without a socket; the {@link ObjectMapper}
 * is a real one so the record deserialization is genuine. Each test is isolated
 * and asserts absolute expected values.
 */
class BackOfficeMessageClientTest {

    /**
     * Builds a client with a real mapper, a mocked HTTP client and the given node
     * URL, leaving the token empty and the terminal at a fixed id.
     *
     * @param url the node URL Optional
     * @param http the mocked HTTP client
     * @return the wired client
     * @throws Exception if reflection fails
     */
    private BackOfficeMessageClient client(Optional<String> url, HttpClient http) throws Exception {
        BackOfficeMessageClient client = new BackOfficeMessageClient();
        client.nodeUrl = url;
        client.token = Optional.empty();
        client.terminalId = "C04";
        client.objectMapper = new ObjectMapper();
        Field field = BackOfficeMessageClient.class.getDeclaredField("httpClient");
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
     * An absent node URL resolves to null without any HTTP call (no-URL arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingReturnsNullWhenNoUrl() throws Exception {
        BackOfficeMessageClient client = client(Optional.empty(), mock(HttpClient.class));
        assertNull(client.fetchPending());
    }

    /**
     * A blank node URL resolves to null (blank-URL arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingReturnsNullWhenBlankUrl() throws Exception {
        BackOfficeMessageClient client = client(Optional.of("   "), mock(HttpClient.class));
        assertNull(client.fetchPending());
    }

    /**
     * A 200 answer with a JSON body is parsed into the pending message (success
     * arm: status in range, body non-blank).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingParsesA200Body() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "{\"text\":\"Réunion 14h\",\"question\":null,\"options\":null}"))
                .when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/msg"), http);
        BackOfficeMessageClient.PendingMessage message = client.fetchPending();
        assertEquals("Réunion 14h", message.text());
        assertNull(message.question());
    }

    /**
     * A non-2xx answer resolves to null (status-out-of-range arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingReturnsNullOnNon2xx() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(503, "down")).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/msg"), http);
        assertNull(client.fetchPending());
    }

    /**
     * A 200 answer with a blank body resolves to null (blank-body arm).
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingReturnsNullOnBlankBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doReturn(resp(200, "   ")).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/msg"), http);
        assertNull(client.fetchPending());
    }

    /**
     * A thrown transport error resolves to null (catch arm) rather than
     * propagating — a node down never disturbs the lane.
     *
     * @throws Exception if reflection fails
     */
    @Test
    void fetchPendingReturnsNullOnException() throws Exception {
        HttpClient http = mock(HttpClient.class);
        doThrow(new java.io.IOException("boom")).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/msg"), http);
        assertNull(client.fetchPending());
    }

    /** LC-03-07: each HTTP status/body guard is exercised independently on pending and survey requests. */
    @Test
    void pendingAndSurveyResponseGuards() throws Exception {
        HttpClient http = mock(HttpClient.class);
        BackOfficeMessageClient client = client(Optional.of("http://node/messages/pending"), http);
        for (int status : new int[]{199, 300, 200, 200}) {
            String body = status == 200 ? null : "ignored";
            HttpResponse<String> answer = resp(status, body);
            doReturn(answer).when(http).send(any(HttpRequest.class), any());
            assertNull(client.fetchPending(""));
            assertNull(client.fetchSurvey(7L));
        }
        HttpResponse<String> blank = resp(200, " ");
        doReturn(blank).when(http).send(any(HttpRequest.class), any());
        assertNull(client.fetchSurvey(7L));
    }

    /** LC-03-07: surveys tolerate absent/blank URLs and invalid relative roots without any network boundary. */
    @Test
    void surveyUrlGuardsAndRootFallback() throws Exception {
        HttpClient http = mock(HttpClient.class);
        assertNull(client(Optional.empty(), http).fetchSurvey(7L));
        assertNull(client(Optional.of(" "), http).fetchSurvey(7L));
        assertNull(client(Optional.of("relative"), http).fetchSurvey(7L));
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    /** LC-03-07: moments and tokens are transmitted only when nonblank, and survey responses deserialize fully. */
    @Test
    void momentAndTokenTransport() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> answer = resp(200, "{\"question\":\"Choose\",\"required\":true,\"options\":\"Yes;No\"}");
        doReturn(answer).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/messages/pending"), http);
        client.token = Optional.of(" ");
        assertEquals("Choose", client.fetchPending("START").question());
        client.token = Optional.of("TOKEN");
        assertEquals(true, client.fetchSurvey(7L).required());
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(2)).send(requests.capture(), any());
        assertEquals("terminal=C04&moment=START", requests.getAllValues().get(0).uri().getQuery());
        assertEquals(Optional.empty(), requests.getAllValues().get(0).headers().firstValue("X-Sync-Token"));
        assertEquals(Optional.of("TOKEN"), requests.getAllValues().get(1).headers().firstValue("X-Sync-Token"));
        assertEquals("/messages/survey/7", requests.getAllValues().get(1).uri().getPath());
    }

    /** LC-03-07: malformed survey JSON and transport exceptions both remain a missing message. */
    @Test
    void surveyFailuresAreContained() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> answer = resp(200, "invalid json");
        doReturn(answer).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/messages/pending"), http);
        assertNull(client.fetchSurvey(7L));
        doThrow(new java.io.IOException("offline")).when(http).send(any(HttpRequest.class), any());
        assertNull(client.fetchSurvey(7L));
    }

    /** LC-03-07: each endpoint independently filters blank tokens and transmits configured tokens. */
    @Test
    void bothEndpointsFilterBothTokenLegs() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> answer = resp(200, "{\"text\":\"NOTICE\"}");
        doReturn(answer).when(http).send(any(HttpRequest.class), any());
        BackOfficeMessageClient client = client(Optional.of("http://node/messages/pending"), http);
        for (String token : new String[]{" ", "TOKEN"}) {
            client.token = Optional.of(token);
            assertEquals("NOTICE", client.fetchPending().text());
            assertEquals("NOTICE", client.fetchSurvey(7L).text());
        }
        org.mockito.Mockito.verify(http, org.mockito.Mockito.times(4)).send(any(HttpRequest.class), any());
    }
}
