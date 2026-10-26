package com.intermarche.pos.service;

import com.intermarche.pos.service.sync.register.SyncOutboxService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Plain isolated tests of BO-04-02-03/06 upward notices, including unavailable store nodes. */
class SupervisorSettleServiceTest {
    /**
     * Loads only the service afresh so its static client is built from a mocked JDK builder.
     * No static-final mutation or real transport is required.
     */
    private static final class Fixture {
        final HttpClient client = mock(HttpClient.class);
        final SyncOutboxService outbox = mock(SyncOutboxService.class);
        final Class<?> type;
        final Object service;

        /** Builds an independently initialized service with every collaborator mocked. */
        Fixture() throws Exception {
            String name = SupervisorSettleService.class.getName();
            ClassLoader parent = SupervisorSettleService.class.getClassLoader();
            byte[] bytes;
            try (var input = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                bytes = input.readAllBytes();
            }
            ClassLoader loader = new ClassLoader(parent) {
                /** Defines only the tested service locally; collaborators retain their parent identity. */
                @Override
                protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                    if (!name.equals(requested)) return super.loadClass(requested, resolve);
                    Class<?> loaded = findLoadedClass(requested);
                    if (loaded == null) loaded = defineClass(requested, bytes, 0, bytes.length, SupervisorSettleService.class.getProtectionDomain());
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            };
            HttpClient.Builder builder = mock(HttpClient.Builder.class);
            when(builder.connectTimeout(Duration.ofSeconds(3))).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            try (var clients = mockStatic(HttpClient.class)) {
                clients.when(HttpClient::newBuilder).thenReturn(builder);
                type = Class.forName(name, true, loader);
                service = type.getConstructor().newInstance();
            }
            field("syncOutboxService", outbox);
            field("terminalId", "C04");
            field("token", Optional.empty());
            when(outbox.getStoreUrl()).thenReturn("http://store");
        }

        /** Sets a package-private injected collaborator across the isolated class loader boundary. */
        void field(String name, Object value) throws Exception {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }

        /** Invokes the public settled-at-till notice. */
        void settled() throws Exception {
            type.getMethod("settledAtTill").invoke(service);
        }

        /** Invokes the public unknown-article notice with its two nullable inputs. */
        void unknown(String code, String operator) throws Exception {
            type.getMethod("unknownItem", String.class, String.class).invoke(service, code, operator);
        }

        /** Invokes the LC-01-05-08 authorisation request and returns whether it was sent. */
        boolean authorization(String gesture, String operator) throws Exception {
            return (Boolean) type.getMethod("authorizationRequested", String.class, String.class)
                    .invoke(service, gesture, operator);
        }

        /** Invokes the LC-01-05-08 withdrawal of the lane's pending requests. */
        void withdrawn() throws Exception {
            type.getMethod("authorizationWithdrawn").invoke(service);
        }

        /**
         * Invokes the body builder directly to assert the JSON document, independently of the wire.
         *
         * @param operator who asked, possibly null
         * @param reason the reason of the request
         * @param message the message, possibly null
         * @return the JSON body
         * @throws Exception when reflection fails
         */
        String call(String operator, String reason, String message) throws Exception {
            var method = type.getDeclaredMethod("call", String.class, String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, operator, reason, message);
        }
    }

    /** BO-04-02-06: standalone registers accept null, blank and actual codes without HTTP traffic. */
    @Test
    void standaloneNoticesRemainSilentAndEscapeJson() throws Exception {
        Fixture fixture = new Fixture();
        fixture.settled();
        fixture.unknown(null, null);
        fixture.unknown(" ", "operator");
        fixture.unknown(" 123 ", "A\\B\"C");
        verifyNoInteractions(fixture.client);
        com.fasterxml.jackson.databind.ObjectMapper parser = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode empty = parser.readTree(fixture.call(null, "R", null));
        assertEquals("C04", empty.get("terminalId").asText());
        assertEquals("", empty.get("operator").asText());
        assertEquals("R", empty.get("reason").asText());
        assertEquals("", empty.get("message").asText());
        com.fasterxml.jackson.databind.JsonNode odd = parser.readTree(fixture.call("A\\B\"C", "R", "line\nnext"));
        assertEquals("A\\B\"C", odd.get("operator").asText());
        assertEquals("line\nnext", odd.get("message").asText());
    }

    /** BO-04-02-03: every HTTP status boundary and both token legs preserve the completed till action. */
    @Test
    void noticesPostWithOptionalTokenAtEveryStatusBoundary() throws Exception {
        for (int status : new int[]{199, 200, 299, 300}) {
            Fixture fixture = new Fixture();
            when(fixture.outbox.isEnabled()).thenReturn(true);
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(fixture.client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            if (status == 300) fixture.field("token", Optional.of("secret"));
            fixture.settled();
            ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(fixture.client).send(request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("http://store/api/supervisor/settled", request.getValue().uri().toString());
            assertEquals("POST", request.getValue().method());
            assertEquals(Optional.of(Duration.ofSeconds(3)), request.getValue().timeout());
            assertEquals(status == 300 ? Optional.of("secret") : Optional.empty(), request.getValue().headers().firstValue("X-Sync-Token"));
        }
    }

    /** BO-04-02-06: transport failure is swallowed and interruption restores the caller's interrupt flag. */
    @Test
    void transportFailuresNeverUndoTheTillGesture() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.outbox.isEnabled()).thenReturn(true);
        when(fixture.client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("offline"), new InterruptedException("stopped"));
        fixture.unknown("123", "OP");
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            fixture.settled();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Reads the body a request was built with.
     *
     * @param request the captured request
     * @return the body as UTF-8 text
     */
    private static String body(HttpRequest request) {
        StringBuilder text = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
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
        return text.toString();
    }

    /** LC-01-05-08: a standalone register sends no authorisation request and says so. */
    @Test
    void authorizationRequestIsNotSentWithoutAStoreNode() throws Exception {
        Fixture fixture = new Fixture();
        assertFalse(fixture.authorization("CANCEL_TICKET", "Jean Dupont"));
        fixture.withdrawn();
        verifyNoInteractions(fixture.client);
    }

    /** LC-01-05-08: the request lands on the supervisor board under its reason, and its withdrawal on the cancel path. */
    @Test
    void authorizationRequestAndWithdrawalReachTheBoard() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.outbox.isEnabled()).thenReturn(true);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(fixture.client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertTrue(fixture.authorization("DISCOUNT \"10\"", null));
        fixture.withdrawn();
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://store/api/supervisor/call", requests.getAllValues().get(0).uri().toString());
        assertEquals("{\"terminalId\":\"C04\",\"operator\":\"\",\"reason\":\"AUTORISATION SUPERVISEUR\","
                + "\"message\":\"DISCOUNT \\\"10\\\"\"}", body(requests.getAllValues().get(0)));
        assertEquals("http://store/api/supervisor/cancel", requests.getAllValues().get(1).uri().toString());
        assertEquals("{\"terminalId\":\"C04\"}", body(requests.getAllValues().get(1)));
    }

    /** Invokes the BO-04-03-09 information alert across the class loader. */
    @Test
    void aControlAlertReachesTheBoard() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.outbox.isEnabled()).thenReturn(true);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(fixture.client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        fixture.type.getMethod("controlAlert", String.class, String.class, String.class)
                .invoke(fixture.service, "payment:CHEQUE", "Chèque", "Jean");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://store/api/supervisor/call", request.getValue().uri().toString());
        assertEquals("{\"terminalId\":\"C04\",\"operator\":\"Jean\",\"reason\":\"ALERTE CAISSE\","
                + "\"message\":\"payment:CHEQUE — Chèque\"}", body(request.getValue()));
    }
}
