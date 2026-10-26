package com.intermarche.pos.ui.payment;

import com.intermarche.pos.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VoucherBurnClient} (BO-11-01-18), targeting 100%
 * branch coverage: no node, a null and a blank number, a token and none, a
 * status other than 200, a readable answer, an interruption and a failure.
 */
class VoucherBurnClientTest {

    /**
     * A client over a mocked outbox and a mocked HTTP client.
     *
     * @param enabled whether a store node is wired
     * @param token the shared token, or null for none
     * @return the client
     */
    private VoucherBurnClient client(boolean enabled, String token) {
        VoucherBurnClient client = new VoucherBurnClient();
        client.syncOutboxService = mock(SyncOutboxService.class);
        when(client.syncOutboxService.isEnabled()).thenReturn(enabled);
        when(client.syncOutboxService.getStoreUrl()).thenReturn("http://node:8090");
        client.token = Optional.ofNullable(token);
        client.httpClient = mock(HttpClient.class);
        return client;
    }

    /**
     * Makes the HTTP client answer the given status and body.
     *
     * @param client the client
     * @param status the HTTP status
     * @param body the body
     * @throws Exception never, the call is a stub
     */
    @SuppressWarnings("unchecked")
    private void answer(VoucherBurnClient client, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    /** Without a node, or without a number, nothing is asked. */
    @Test
    void nothingIsAskedWithoutANodeOrANumber() {
        VoucherBurnClient off = client(false, null);
        assertNull(off.lookup("B1"));
        verifyNoInteractions(off.httpClient);
        VoucherBurnClient on = client(true, null);
        assertNull(on.lookup(null));
        assertNull(on.lookup(" "));
        verifyNoInteractions(on.httpClient);
    }

    /**
     * A burned voucher is read back with where it was consumed, the token
     * presented and the number encoded in the path.
     *
     * @throws Exception never, the call is a stub
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBurnedVoucherIsReadBack() throws Exception {
        VoucherBurnClient client = client(true, "secret");
        answer(client, 200, "{\"number\":\"B 1\",\"terminalId\":\"C02\",\"ticketNumber\":\"T9\",\"burnedAt\":null,\"extra\":1}");
        VoucherBurnClient.Burn burn = client.lookup(" B 1 ");
        assertEquals("C02", burn.terminalId);
        assertEquals("T9", burn.ticketNumber);
        assertEquals("B 1", burn.number);
        assertNull(burn.burnedAt);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://node:8090/api/sync/voucher-burn/B+1", sent.getValue().uri().toString());
        assertEquals("secret", sent.getValue().headers().firstValue("X-Sync-Token").orElse(null));
    }

    /**
     * A voucher the node does not know burned answers nothing, a blank token is
     * not presented.
     *
     * @throws Exception never, the call is a stub
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnburnedVoucherAnswersNothing() throws Exception {
        VoucherBurnClient client = client(true, " ");
        answer(client, 404, "");
        assertNull(client.lookup("B1"));
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        assertTrue(sent.getValue().headers().firstValue("X-Sync-Token").isEmpty());
        VoucherBurnClient untokened = client(true, null);
        answer(untokened, 404, "");
        assertNull(untokened.lookup("B1"));
    }

    /**
     * An interrupted call restores the interruption and answers nothing; an
     * unreachable node answers nothing.
     *
     * @throws Exception never, the call is a stub
     */
    @Test
    @SuppressWarnings("unchecked")
    void aNodeThatDoesNotAnswerBurnsNothing() throws Exception {
        VoucherBurnClient interrupted = client(true, null);
        when(interrupted.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        assertNull(interrupted.lookup("B1"));
        assertTrue(Thread.interrupted());
        VoucherBurnClient down = client(true, null);
        when(down.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        assertNull(down.lookup("B1"));
    }
}
