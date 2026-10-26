package com.intermarche.pos.ui.home;

import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SingleQueueService} (BO-03-09-10): the view, the flip
 * of an activated and a non-activated register, and the publication —
 * without address, acknowledged, refused, unreachable and interrupted.
 */
class SingleQueueServiceTest {

    /**
     * Builds a service over a mocked client.
     *
     * @param enabled whether the register takes part
     * @param url the administered address
     * @return the service
     */
    private SingleQueueService service(boolean enabled, String url) {
        SingleQueueService service = new SingleQueueService();
        service.posSettingsService = mock(PosSettingsService.class);
        when(service.posSettingsService.singleQueueEnabled()).thenReturn(enabled);
        when(service.posSettingsService.singleQueueUrl()).thenReturn(url);
        service.terminalId = "C04";
        service.httpClient = mock(HttpClient.class);
        return service;
    }

    /**
     * Makes the client answer one status.
     *
     * @param service the service
     * @param status the status
     * @throws Exception never, the client being a mock
     */
    @SuppressWarnings("unchecked")
    private void answer(SingleQueueService service, int status) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(service.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    /** A register that does not take part flips nothing and shows no key. */
    @Test
    void aRegisterOutOfTheQueueFlipsNothing() {
        SingleQueueService service = service(false, "");
        assertNull(service.toggle());
        assertFalse(service.view().enabled());
        assertFalse(service.view().available());
    }

    /** The availability starts off and flips at each gesture. */
    @Test
    void theAvailabilityFlips() {
        SingleQueueService service = service(true, "");
        assertEquals("AVAILABLE", service.toggle());
        assertTrue(service.view().available());
        assertEquals("UNAVAILABLE", service.toggle());
        assertFalse(service.view().available());
    }

    /** Without an address nothing is published. */
    @Test
    void withoutAnAddressNothingIsPublished() {
        SingleQueueService service = service(true, "");
        assertFalse(service.publish());
        verifyNoInteractions(service.httpClient);
    }

    /**
     * The published state is a document a real parser reads: the terminal,
     * the state and the moment, under their field names.
     *
     * @throws Exception never, the client being a mock
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePublishedStateIsAValidDocument() throws Exception {
        SingleQueueService service = service(true, "http://queue/state");
        answer(service, 200);
        service.toggle();
        assertTrue(service.publish());
        org.mockito.ArgumentCaptor<HttpRequest> sent = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(service.httpClient).send(sent.capture(), any(HttpResponse.BodyHandler.class));
        StringBuilder text = new StringBuilder();
        sent.getValue().bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            /** Requests every chunk at once. */
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            /** Appends one chunk. */
            @Override
            public void onNext(java.nio.ByteBuffer item) {
                text.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
            }
            /** Never reached by an in-memory body. */
            @Override
            public void onError(Throwable throwable) {
                throw new IllegalStateException(throwable);
            }
            /** Nothing left to collect. */
            @Override
            public void onComplete() {
            }
        });
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.toString());
        assertEquals("C04", body.get("terminal").asText());
        assertEquals("AVAILABLE", body.get("state").asText());
        assertTrue(body.get("at").isTextual());
    }

    /** A 2xx acknowledges, anything else refuses, whatever the state. */
    @Test
    void theAnswerDecides() throws Exception {
        SingleQueueService service = service(true, "http://queue/state");
        answer(service, 204);
        assertTrue(service.publish());
        service.toggle();
        assertTrue(service.publish());
        answer(service, 500);
        assertFalse(service.publish());
        answer(service, 199);
        assertFalse(service.publish());
    }

    /** An unreachable system and an interrupted call are a refusal, not a failure. */
    @Test
    @SuppressWarnings("unchecked")
    void failuresAreRefusals() throws Exception {
        SingleQueueService service = service(true, "http://queue/state");
        when(service.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("down"));
        assertFalse(service.publish());
        SingleQueueService interrupted = service(true, "http://queue/state");
        when(interrupted.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        assertFalse(interrupted.publish());
        assertTrue(Thread.interrupted());
    }
}
