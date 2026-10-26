package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.service.sync.SyncPayloads;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Unit tests for {@link StoredValueLookupClient} (BO-03-06-48), targeting 100%
 * branch coverage.
 *
 * <p>Branch enumeration — lookup: no node, a null and a blank number, a token
 * and none, a status other than 200, a readable answer, an interruption, a
 * failure; adopt: no copy, a copy without number, without kind, a full copy,
 * a copy of an exhausted card with every optional field absent.
 */
class StoredValueLookupClientTest {

    /**
     * A client over a mocked outbox and a mocked HTTP client.
     *
     * @param enabled whether a store node is wired
     * @param token the shared token, or null for none
     * @return the client
     */
    private StoredValueLookupClient client(boolean enabled, String token) {
        StoredValueLookupClient client = new StoredValueLookupClient();
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
    private void answer(StoredValueLookupClient client, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    /**
     * Without a node, or without a number, nothing is asked.
     */
    @Test
    void nothingIsAskedWithoutANodeOrANumber() {
        StoredValueLookupClient off = client(false, null);
        assertNull(off.lookup("297000000000001"));
        verifyNoInteractions(off.httpClient);
        StoredValueLookupClient on = client(true, null);
        assertNull(on.lookup(null));
        assertNull(on.lookup("  "));
        verifyNoInteractions(on.httpClient);
    }

    /**
     * The node's answer is read, the number encoded in the path and the token
     * presented when one is configured.
     *
     * @throws Exception never, the HTTP client is mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void theNodesCopyIsRead() throws Exception {
        StoredValueLookupClient client = client(true, "secret");
        answer(client, 200, "{\"number\":\"98 01\",\"kind\":\"CREDIT_NOTE\",\"balance\":4.5,\"future\":1}");
        SyncPayloads.StoredValueDto copy = client.lookup(" 98 01 ");
        assertEquals("98 01", copy.number);
        assertEquals(new BigDecimal("4.5"), copy.balance);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://node:8090/api/sync/stored-value/98+01", request.getValue().uri().toString());
        assertEquals(Optional.of("secret"), request.getValue().headers().firstValue("X-Sync-Token"));
    }

    /**
     * A blank token is not presented, and a status other than 200 answers no copy.
     *
     * @throws Exception never, the HTTP client is mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnknownNumberAnswersNoCopy() throws Exception {
        StoredValueLookupClient client = client(true, " ");
        answer(client, 404, "");
        assertNull(client.lookup("297000000000001"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertFalse(request.getValue().headers().firstValue("X-Sync-Token").isPresent());
        StoredValueLookupClient none = client(true, null);
        answer(none, 404, "");
        assertNull(none.lookup("297000000000001"));
    }

    /**
     * An interrupted call answers no copy and keeps the interruption; an
     * unreachable node answers no copy.
     *
     * @throws Exception never, the HTTP client is mocked
     */
    @Test
    @SuppressWarnings("unchecked")
    void anUnreachableNodeAnswersNoCopy() throws Exception {
        StoredValueLookupClient interrupted = client(true, null);
        when(interrupted.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        assertNull(interrupted.lookup("297000000000001"));
        assertTrue(Thread.interrupted());
        StoredValueLookupClient down = client(true, null);
        when(down.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("refused"));
        assertNull(down.lookup("297000000000001"));
    }

    /**
     * Nothing is adopted when the node knows nothing usable: no copy, a copy
     * without number, a copy without kind.
     */
    @Test
    void nothingIsAdoptedWithoutAUsableCopy() {
        StoredValueLookupClient client = spy(client(true, null));
        doReturn(null).when(client).lookup("A");
        SyncPayloads.StoredValueDto numberless = new SyncPayloads.StoredValueDto();
        numberless.kind = "CREDIT_NOTE";
        doReturn(numberless).when(client).lookup("B");
        SyncPayloads.StoredValueDto kindless = new SyncPayloads.StoredValueDto();
        kindless.number = "C";
        doReturn(kindless).when(client).lookup("C");
        try (MockedConstruction<StoredValue> rows = mockConstruction(StoredValue.class)) {
            assertNull(client.adopt("A"));
            assertNull(client.adopt("B"));
            assertNull(client.adopt("C"));
            assertTrue(rows.constructed().isEmpty());
        }
    }

    /**
     * A full copy is adopted as it is, under the issuer's register and number.
     */
    @Test
    void aFullCopyIsAdopted() {
        StoredValueLookupClient client = spy(client(true, null));
        SyncPayloads.StoredValueDto copy = new SyncPayloads.StoredValueDto();
        copy.number = "9800100000314";
        copy.kind = "GIFT_CARD";
        copy.initialAmount = new BigDecimal("30.00");
        copy.balance = new BigDecimal("12.00");
        copy.status = "ACTIVE";
        copy.issuedAt = "2026-10-24T14:05:09";
        copy.validUntil = "2027-10-24T00:00";
        copy.exhaustedAt = "2026-10-25T10:00";
        copy.issuingTerminalId = "POS02";
        copy.issuingDocumentNumber = "POS02-00000003";
        doReturn(copy).when(client).lookup("9800100000314");
        try (MockedConstruction<StoredValue> rows = mockConstruction(StoredValue.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> doNothing().when(row).persist())) {
            StoredValue adopted = client.adopt("9800100000314");
            assertSame(rows.constructed().get(0), adopted);
            assertEquals("9800100000314", adopted.number);
            assertEquals(StoredValue.Kind.GIFT_CARD, adopted.kind);
            assertEquals(new BigDecimal("30.00"), adopted.initialAmount);
            assertEquals(new BigDecimal("12.00"), adopted.balance);
            assertEquals(StoredValue.Status.ACTIVE, adopted.status);
            assertEquals(LocalDateTime.of(2026, 10, 24, 14, 5, 9), adopted.issuedAt);
            assertEquals(LocalDateTime.of(2027, 10, 24, 0, 0), adopted.validUntil);
            assertEquals(LocalDateTime.of(2026, 10, 25, 10, 0), adopted.exhaustedAt);
            assertEquals("POS02", adopted.issuingTerminalId);
            assertEquals("POS02-00000003", adopted.issuingDocumentNumber);
            verify(adopted).persist();
        }
    }

    /**
     * An exhausted copy with no amounts and no dates is adopted exhausted, at
     * zero, issued now.
     */
    @Test
    void aBareExhaustedCopyIsAdopted() {
        StoredValueLookupClient client = spy(client(true, null));
        SyncPayloads.StoredValueDto copy = new SyncPayloads.StoredValueDto();
        copy.number = "297000000000009";
        copy.kind = "CREDIT_NOTE";
        copy.status = "EXHAUSTED";
        doReturn(copy).when(client).lookup("297000000000009");
        try (MockedConstruction<StoredValue> rows = mockConstruction(StoredValue.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (row, context) -> doNothing().when(row).persist())) {
            StoredValue adopted = client.adopt("297000000000009");
            assertEquals(BigDecimal.ZERO, adopted.initialAmount);
            assertEquals(BigDecimal.ZERO, adopted.balance);
            assertEquals(StoredValue.Status.EXHAUSTED, adopted.status);
            assertNotNull(adopted.issuedAt);
            assertNull(adopted.validUntil);
            assertNull(adopted.exhaustedAt);
        }
    }
}
