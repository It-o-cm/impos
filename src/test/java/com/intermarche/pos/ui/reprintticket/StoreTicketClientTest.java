package com.intermarche.pos.ui.reprintticket;

import com.intermarche.pos.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreTicketClient}.
 * <p>
 * The client asks the store node for a foreign ticket already rendered
 * (LC-08-05-05). Its collaborators are a {@link SyncOutboxService} (concrete
 * class, mocked to report availability and the store URL) wired onto the
 * package-private {@code syncOutboxService} field, and a shared token wired onto
 * the package-private {@code token} field. The outbound {@link HttpClient} is a
 * {@code private final} field built inline; it is replaced by a Mockito mock
 * through reflection (the sole route that leaves {@code src/main} untouched) so
 * every response shape — success, 4xx refusal, sub-200 status, null body, blank
 * body — and every transport failure — {@link InterruptedException}, generic
 * {@link IOException} — can be driven. Tests cover BOTH arms of each of the three
 * guard conditions on line 70, both arms of the token-header guard, all four
 * arms of the {@code >=200 && <300} status test, and all arms of the
 * {@code body == null || body.isBlank()} ternary (16 branches total).
 */
class StoreTicketClientTest {

    /**
     * Builds a client with a mocked {@link SyncOutboxService}, the given shared
     * token, and the given {@link HttpClient} forced onto the {@code private
     * final httpClient} field via reflection.
     *
     * @param sync       the mocked synchronization service collaborator
     * @param sharedToken the shared token Optional to wire on the token field
     * @param httpClient the HttpClient mock to force in place of the inline one
     * @return a fully wired client ready for a single call
     */
    private StoreTicketClient newClient(SyncOutboxService sync, Optional<String> sharedToken,
            HttpClient httpClient) {
        StoreTicketClient client = new StoreTicketClient();
        client.syncOutboxService = sync;
        client.token = sharedToken;
        setHttpClient(client, httpClient);
        return client;
    }

    /**
     * Forces a mock {@link HttpClient} onto the target's {@code private final
     * httpClient} field, the only field not injection-visible.
     *
     * @param client     the client whose field is replaced
     * @param httpClient the replacement HttpClient
     */
    private void setHttpClient(StoreTicketClient client, HttpClient httpClient) {
        try {
            Field field = StoreTicketClient.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(client, httpClient);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds a mocked {@link HttpResponse} with the given status code and body.
     *
     * @param status the HTTP status code to report
     * @param body   the response body to report
     * @return the stubbed HttpResponse
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * {@code isAvailable()} reflects the synchronization service's enabled flag
     * (true arm).
     */
    @Test
    void isAvailableReflectsEnabledService() {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        StoreTicketClient client = newClient(sync, Optional.empty(), mock(HttpClient.class));
        assertTrue(client.isAvailable());
    }

    /**
     * {@code isAvailable()} reflects the synchronization service's enabled flag
     * (false arm).
     */
    @Test
    void isAvailableReflectsDisabledService() {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(false);
        StoreTicketClient client = newClient(sync, Optional.empty(), mock(HttpClient.class));
        assertFalse(client.isAvailable());
    }

    /**
     * {@code fetchDuplicate()} returns empty and never touches the network when no
     * store node is configured ({@code !isAvailable()} true arm).
     */
    @Test
    void fetchDuplicateWithoutStoreNodeReturnsEmpty() {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(false);
        HttpClient httpClient = mock(HttpClient.class);
        StoreTicketClient client = newClient(sync, Optional.empty(), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
        verifyNoInteractions(httpClient);
    }

    /**
     * {@code fetchDuplicate()} returns empty for a null ticket number
     * ({@code !isAvailable()} false arm, {@code ticketNumber == null} true arm).
     */
    @Test
    void fetchDuplicateWithNullNumberReturnsEmpty() {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        HttpClient httpClient = mock(HttpClient.class);
        StoreTicketClient client = newClient(sync, Optional.empty(), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate(null));
        verifyNoInteractions(httpClient);
    }

    /**
     * {@code fetchDuplicate()} returns empty for a blank ticket number
     * ({@code ticketNumber == null} false arm, {@code isBlank()} true arm).
     */
    @Test
    void fetchDuplicateWithBlankNumberReturnsEmpty() {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        HttpClient httpClient = mock(HttpClient.class);
        StoreTicketClient client = newClient(sync, Optional.empty(), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("   "));
        verifyNoInteractions(httpClient);
    }

    /**
     * {@code fetchDuplicate()} returns the rendered body on a 2xx answer with a
     * present non-blank token ({@code isBlank()} false arm so the guard falls
     * through, {@code !sharedToken.isBlank()} true arm, {@code >=200} true,
     * {@code <300} true, {@code body == null} false, {@code body.isBlank()} false).
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsBodyWithTokenHeader() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(200, "TICKET RENDU");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.of("TICKET RENDU"), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} succeeds without adding the token header when the
     * token resolves to blank ({@code !sharedToken.isBlank()} false arm), still on
     * a 2xx answer with a non-blank body.
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsBodyWithoutTokenHeader() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(299, "COPIE");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.empty(), httpClient);
        assertEquals(Optional.of("COPIE"), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} returns empty when the node refuses with a 4xx
     * ({@code >=200} true arm, {@code <300} false arm).
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsEmptyOnRefusal() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(404, "nope");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} returns empty on a sub-200 status ({@code >=200}
     * false arm, short-circuiting the {@code <300} test).
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsEmptyOnInformationalStatus() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(100, "early");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} returns empty on a 2xx answer whose body is null
     * ({@code body == null} true arm).
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsEmptyOnNullBody() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(200, null);
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} returns empty on a 2xx answer whose body is blank
     * ({@code body == null} false arm, {@code body.isBlank()} true arm).
     *
     * @throws Exception never; the mocked send does not throw here
     */
    @Test
    void fetchDuplicateReturnsEmptyOnBlankBody() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> stubbed = response(200, "   ");
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(stubbed);
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
    }

    /**
     * {@code fetchDuplicate()} swallows an {@link InterruptedException}, restores
     * the thread's interrupt flag and returns empty (interrupted catch leg).
     *
     * @throws Exception never; the mocked send throws the checked interruption
     */
    @Test
    void fetchDuplicateReturnsEmptyOnInterruption() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenThrow(new InterruptedException("boom"));
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        Optional<String> result = client.fetchDuplicate("C09-000012");
        boolean interrupted = Thread.interrupted();
        assertEquals(Optional.empty(), result);
        assertTrue(interrupted);
    }

    /**
     * {@code fetchDuplicate()} swallows a generic transport failure and returns
     * empty (generic Exception catch leg).
     *
     * @throws Exception never; the mocked send throws the checked IOException
     */
    @Test
    void fetchDuplicateReturnsEmptyOnTransportFailure() throws Exception {
        SyncOutboxService sync = mock(SyncOutboxService.class);
        when(sync.isEnabled()).thenReturn(true);
        when(sync.getStoreUrl()).thenReturn("http://store");
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenThrow(new IOException("down"));
        StoreTicketClient client = newClient(sync, Optional.of("secret"), httpClient);
        assertEquals(Optional.empty(), client.fetchDuplicate("C09-000012"));
    }
}
