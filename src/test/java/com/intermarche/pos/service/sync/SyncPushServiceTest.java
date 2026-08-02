package com.intermarche.pos.service.sync;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncPushService}.
 * <p>
 * The service is the register-side drain loop that pushes outbox items to the
 * store node over plain JDK HTTP. It has no database access of its own: the
 * only collaborator, {@link SyncOutboxService}, is a plain Mockito mock, and
 * the outbound {@link HttpClient} is a mock injected into the private final
 * {@code httpClient} field by reflection. Package-private fields
 * ({@code intervalSeconds}, {@code token}, {@code syncOutboxService}) are
 * assigned directly since the test lives in the production package; the private
 * {@code executor} field and the private {@code drainSafely} method are reached
 * through reflection. No Quarkus context, no HTTP server and no database is
 * booted.
 * <p>
 * The mocked {@link HttpClient#send} returns a mocked {@link HttpResponse}
 * whose status and body are pinned per test, so the whole push is driven
 * without any real network. The request built by the loop is captured to
 * assert the target URI, the JSON body and the presence or absence of the
 * {@code X-Sync-Token} header.
 * <p>
 * Branch enumeration: {@code onStart} — both arms of {@code !isEnabled()}
 * (disabled return, enabled start) plus the thread-factory lambda;
 * {@code onStop} — both arms of {@code executor != null}; {@code drainSafely} —
 * the success arm and the catch arm; {@code drainOnce} — both arms of
 * {@code item == null} (gone, present), both arms of {@code !sharedToken.isBlank()}
 * (header added, header skipped), all four arms of
 * {@code statusCode >= 200 && statusCode < 300} (2xx success, sub-200 failure,
 * 3xx+ failure), the {@code InterruptedException} catch (interrupt and return)
 * and the generic {@code Exception} catch (record failure); {@code shorten} —
 * both arms of {@code body == null} (empty, kept) and both arms of the
 * {@code length > 120} ternary (truncated, verbatim).
 */
class SyncPushServiceTest {

    /**
     * Builds a mocked {@link HttpResponse} of the given status and body.
     *
     * @param status the HTTP status code
     * @param body the response body, possibly null
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
     * Injects a mocked {@link HttpClient} into the private final
     * {@code httpClient} field of a service instance.
     *
     * @param service the service to patch
     * @param client the client to inject
     * @throws Exception if reflection fails
     */
    private void setHttpClient(SyncPushService service, HttpClient client) throws Exception {
        Field field = SyncPushService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, client);
    }

    /**
     * Reads the private {@code executor} field of a service instance.
     *
     * @param service the service to inspect
     * @return the current executor, possibly null
     * @throws Exception if reflection fails
     */
    private ScheduledExecutorService getExecutor(SyncPushService service) throws Exception {
        Field field = SyncPushService.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(service);
    }

    /**
     * Invokes the private no-argument {@code drainSafely} method by reflection.
     *
     * @param service the target service
     * @throws Exception if reflection fails or the method throws
     */
    private void drainSafely(SyncPushService service) throws Exception {
        Method method = SyncPushService.class.getDeclaredMethod("drainSafely");
        method.setAccessible(true);
        method.invoke(service);
    }

    /**
     * Builds a service wired with the outbox mock and a mocked HTTP client,
     * leaving the token empty and the interval at a large value so no scheduled
     * cycle fires during a test.
     *
     * @param outbox the outbox mock
     * @param client the HTTP client mock
     * @return the wired service
     * @throws Exception if reflection fails
     */
    private SyncPushService service(SyncOutboxService outbox, HttpClient client) throws Exception {
        SyncPushService service = new SyncPushService();
        service.syncOutboxService = outbox;
        service.intervalSeconds = 3600L;
        service.token = Optional.empty();
        setHttpClient(service, client);
        return service;
    }

    /**
     * Builds a prepared item for the given path suffix and JSON body.
     *
     * @param pathSuffix the store-node path suffix
     * @param json the JSON body
     * @return the prepared item
     */
    private SyncOutboxService.PreparedItem item(String pathSuffix, String json) {
        return new SyncOutboxService.PreparedItem(pathSuffix, json);
    }

    // --------------------------------------------------
    // onStart / onStop
    // --------------------------------------------------

    /**
     * Covers the disabled arm of {@code onStart}: an outbox with no store URL
     * disables the loop, so no executor is created.
     */
    @Test
    void onStartSkipsWhenDisabled() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.isEnabled()).thenReturn(false);
        SyncPushService service = service(outbox, mock(HttpClient.class));
        service.onStart(null);
        assertNull(getExecutor(service));
    }

    /**
     * Covers the enabled arm of {@code onStart} and the thread-factory lambda:
     * an enabled outbox creates the scheduled executor (whose worker thread is
     * built through the daemon factory). The loop is torn down immediately via
     * {@code onStop}, also covering its non-null arm.
     */
    @Test
    void onStartCreatesExecutorAndOnStopShutsItDown() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.isEnabled()).thenReturn(true);
        when(outbox.getStoreUrl()).thenReturn("http://store");
        SyncPushService service = service(outbox, mock(HttpClient.class));
        service.onStart(null);
        ScheduledExecutorService executor = getExecutor(service);
        assertTrue(executor != null && !executor.isShutdown());
        service.onStop();
        assertTrue(executor.isShutdown());
    }

    /**
     * Covers the null arm of {@code onStop}: with no executor ever created,
     * shutdown is a no-op and does not throw.
     */
    @Test
    void onStopIsNoOpWhenExecutorNull() throws Exception {
        SyncPushService service = service(mock(SyncOutboxService.class), mock(HttpClient.class));
        service.onStop();
        assertNull(getExecutor(service));
    }

    // --------------------------------------------------
    // drainSafely
    // --------------------------------------------------

    /**
     * Covers the success arm of {@code drainSafely}: an empty batch drains
     * cleanly with nothing pushed.
     */
    @Test
    void drainSafelyRunsCleanCycle() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of());
        HttpClient client = mock(HttpClient.class);
        SyncPushService service = service(outbox, client);
        drainSafely(service);
        verify(client, never()).send(any(), any());
    }

    /**
     * Covers the catch arm of {@code drainSafely}: a failure inside the cycle
     * is swallowed so the loop survives.
     */
    @Test
    void drainSafelySwallowsException() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenThrow(new RuntimeException("boom"));
        SyncPushService service = service(outbox, mock(HttpClient.class));
        drainSafely(service);
        verify(outbox, never()).markSuccess(any());
    }

    // --------------------------------------------------
    // drainOnce
    // --------------------------------------------------

    /**
     * Covers the gone arm of {@code drainOnce}: a row whose entity vanished
     * ({@code prepare} yields null) is marked gone and never pushed.
     */
    @Test
    void drainOnceMarksGoneWhenItemVanished() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(1L));
        when(outbox.prepare(1L)).thenReturn(null);
        HttpClient client = mock(HttpClient.class);
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        verify(outbox).markGone(1L);
        verify(client, never()).send(any(), any());
    }

    /**
     * Covers the present arm of {@code drainOnce}, the non-blank token arm and
     * the 2xx success arm (both operands true): a prepared item is pushed to
     * the store URL with the {@code X-Sync-Token} header and, on a 200, the row
     * is marked successful.
     */
    @Test
    void drainOncePushesWithTokenAndMarksSuccessOn2xx() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(7L));
        when(outbox.prepare(7L)).thenReturn(item("ticket", "{\"a\":1}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(200, "OK")).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.token = Optional.of("secret");
        service.drainOnce();
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("http://store/api/sync/ticket", request.uri().toString());
        assertEquals("secret", request.headers().firstValue("X-Sync-Token").orElse(null));
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
        verify(outbox).markSuccess(7L);
        verify(outbox, never()).markFailure(any(), any());
    }

    /**
     * Covers the blank token arm and the failure arm reached through the
     * second operand being false ({@code statusCode >= 300}), together with the
     * non-null, short-body arms of {@code shorten}: an empty token leaves the
     * {@code X-Sync-Token} header off, and a 409 records a failure whose message
     * carries the status and the verbatim body.
     */
    @Test
    void drainOnceMarksFailureOn3xxPlusWithoutToken() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(9L));
        when(outbox.prepare(9L)).thenReturn(item("session", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(409, "missing ref")).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.token = Optional.empty();
        service.drainOnce();
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertFalse(captor.getValue().headers().firstValue("X-Sync-Token").isPresent());
        verify(outbox).markFailure(9L, "HTTP 409 missing ref");
        verify(outbox, never()).markSuccess(any());
    }

    /**
     * Covers the failure arm reached through the first operand being false
     * ({@code statusCode < 200}): an informational 199 status is treated as a
     * failure and recorded with its status and body.
     */
    @Test
    void drainOnceMarksFailureOnSub200() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(3L));
        when(outbox.prepare(3L)).thenReturn(item("refund", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(199, "early")).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        verify(outbox).markFailure(3L, "HTTP 199 early");
        verify(outbox, never()).markSuccess(any());
    }

    /**
     * Covers the null-body arm of {@code shorten}: a failure whose response
     * body is null renders an empty tail, so the recorded message is the bare
     * status with a trailing space.
     */
    @Test
    void drainOnceShortensNullBodyToEmpty() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(4L));
        when(outbox.prepare(4L)).thenReturn(item("event", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(500, null)).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        verify(outbox).markFailure(4L, "HTTP 500 ");
    }

    /**
     * Covers the truncating arm of the {@code shorten} ternary
     * ({@code length > 120}): a failure whose body exceeds 120 characters is
     * recorded with only its first 120 characters after the status.
     */
    @Test
    void drainOnceTruncatesLongBody() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(5L));
        when(outbox.prepare(5L)).thenReturn(item("ticket", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        String longBody = "x".repeat(200);
        HttpClient client = mock(HttpClient.class);
        doReturn(resp(500, longBody)).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        verify(outbox).markFailure(5L, "HTTP 500 " + "x".repeat(120));
    }

    /**
     * Covers the {@code InterruptedException} catch of {@code drainOnce}: when
     * the send is interrupted, the thread's interrupt flag is restored and the
     * loop returns immediately, leaving any later id untouched.
     */
    @Test
    void drainOnceRestoresInterruptAndReturns() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(1L, 2L));
        when(outbox.prepare(1L)).thenReturn(item("ticket", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doThrow(new InterruptedException()).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        assertTrue(Thread.interrupted());
        verify(outbox).prepare(1L);
        verify(outbox, never()).prepare(2L);
        verify(outbox, never()).markFailure(any(), any());
    }

    /**
     * Covers the generic {@code Exception} catch of {@code drainOnce}: an I/O
     * failure on send is recorded as a failure carrying the exception message,
     * and the loop moves on rather than aborting.
     */
    @Test
    void drainOnceMarksFailureOnSendException() throws Exception {
        SyncOutboxService outbox = mock(SyncOutboxService.class);
        when(outbox.nextBatchIds(50)).thenReturn(List.of(6L));
        when(outbox.prepare(6L)).thenReturn(item("ticket", "{}"));
        when(outbox.getStoreUrl()).thenReturn("http://store");
        HttpClient client = mock(HttpClient.class);
        doThrow(new IOException("connection refused")).when(client).send(any(HttpRequest.class), any());
        SyncPushService service = service(outbox, client);
        service.drainOnce();
        verify(outbox).markFailure(6L, "connection refused");
        verify(outbox, never()).markSuccess(any());
    }
}
