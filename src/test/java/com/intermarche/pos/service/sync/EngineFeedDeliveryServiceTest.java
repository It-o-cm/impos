package com.intermarche.pos.service.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngineFeedDeliveryService}: the delivery of the
 * pending snapshots (POST verbatim with Basic auth, ACK on 2xx), the
 * stop-at-first-failure discipline that preserves the application order,
 * and the survival of an unreachable engine. The engine is a real local
 * {@code HttpServer} (pure JDK) so the HTTP path is exercised for real;
 * the pending snapshots come from a mocked {@link EngineFeedService} —
 * the skipping of absent and acknowledged feeds is the keeper's job,
 * tested in {@code EngineFeedServiceTest}.
 */
class EngineFeedDeliveryServiceTest {

    /** The service under test. */
    private EngineFeedDeliveryService service;

    /** The mocked feed keeper handing snapshots and recording outcomes. */
    private EngineFeedService engineFeedService;

    /** The local fake engine. */
    private HttpServer server;

    /** Status the fake engine answers per path. */
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();

    /** Bodies received by the fake engine per path. */
    private final Map<String, String> bodyByPath = new ConcurrentHashMap<>();

    /** Authorization headers received per path. */
    private final Map<String, String> authByPath = new ConcurrentHashMap<>();

    /**
     * Starts the fake engine and wires a fresh service on it.
     *
     * @throws Exception when the local server cannot start
     */
    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodyByPath.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) authByPath.put(path, auth);
            int status = statusByPath.getOrDefault(path, 200);
            byte[] answer = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        service = new EngineFeedDeliveryService();
        service.url = Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        service.user = Optional.of("pos");
        service.password = Optional.of("secret");
        service.timeoutMillis = 5000;
        engineFeedService = mock(EngineFeedService.class);
        service.engineFeedService = engineFeedService;
    }

    /**
     * Stops the fake engine.
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * Builds a detached pending snapshot for a catalog code.
     *
     * @param code the feed code, resolved against the catalog for its path
     * @param version the version awaiting delivery
     * @param lastError the previously recorded error, or null
     * @return the snapshot
     */
    private static EngineFeedService.PendingFeed pending(String code, String version, String lastError) {
        return new EngineFeedService.PendingFeed(code,
                EngineFeedService.catalogEntry(code).orElseThrow().enginePath(),
                "CONTENT-" + code, version, lastError);
    }

    /**
     * A pending snapshot is POSTed verbatim with Basic auth to its engine
     * path and acknowledged on the 2xx answer.
     */
    @Test
    void deliverPendingPostsAndAcknowledges() {
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null)));
        service.deliverPending();
        verify(engineFeedService).markApplied("PRODUCTS", "v2");
        assertEquals("CONTENT-PRODUCTS", bodyByPath.get("/products/import"));
        assertTrue(authByPath.get("/products/import").startsWith("Basic "));
        assertEquals(1, bodyByPath.size());
    }

    /**
     * A non-2xx answer records the error and STOPS the walk: the feeds
     * after the failing one are not delivered this cycle (application-order
     * preservation).
     */
    @Test
    void deliverPendingStopsAtFirstFailure() {
        statusByPath.put("/products/import", 503);
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null), pending("OFFERS", "v9", null)));
        service.deliverPending();
        verify(engineFeedService).markError(eq("PRODUCTS"), contains("HTTP 503"));
        verify(engineFeedService, never()).markApplied(eq("OFFERS"), any());
        assertTrue(bodyByPath.keySet().stream().noneMatch(p -> p.contains("offers")));
    }

    /**
     * An unreachable engine records the connection error without killing
     * the loop (exception arm of {@code deliver} + {@code deliverSafely}).
     */
    @Test
    void deliverSafelySurvivesUnreachableEngine() {
        service.url = Optional.of("http://127.0.0.1:1");
        when(engineFeedService.pendingFeeds())
                .thenReturn(List.of(pending("PRODUCTS", "v2", null)));
        service.deliverSafely();
        verify(engineFeedService).markError(eq("PRODUCTS"), any());
        verify(engineFeedService, never()).markApplied(any(), any());
    }
}
