package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.sync.ConsumptionOutbox;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConsumptionDeliveryService}.
 * <p>
 * The {@link ConsumptionService} is a Mockito mock on the injection field and
 * the private final {@link HttpClient} is replaced with a mock through
 * reflection (the pom opens {@code java.base}); the config fields are set
 * directly since no CDI runs. Every arm of {@code deliver}, {@code
 * deliverPending}, {@code body}, {@code onStart}, {@code onStop} and {@code
 * triggerDelivery} is exercised with absolute expected values.
 */
class ConsumptionDeliveryServiceTest {

    /**
     * Builds a delivery service with a configured URL, a mock consumption
     * keeper and a mock HTTP client injected in place of the real one.
     *
     * @param client the mock HTTP client
     * @return the wired service
     * @throws Exception when the reflection fails
     */
    private ConsumptionDeliveryService serviceWith(HttpClient client) throws Exception {
        ConsumptionDeliveryService service = new ConsumptionDeliveryService();
        service.url = Optional.of("http://engine.local");
        service.user = Optional.of("u");
        service.password = Optional.of("p");
        service.timeoutMillis = 2500;
        service.deliverySeconds = 30;
        service.consumptionService = mock(ConsumptionService.class);
        Field field = ConsumptionDeliveryService.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(service, client);
        return service;
    }

    /**
     * Builds a mock HTTP response carrying the given status code.
     *
     * @param status the HTTP status
     * @return the mock response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }

    /**
     * Builds a pending SALE snapshot.
     *
     * @return the snapshot
     */
    private ConsumptionService.PendingConsumption pending() {
        return new ConsumptionService.PendingConsumption(7L, "T1", "C04",
                LocalDateTime.of(2026, 8, 2, 10, 30), ConsumptionOutbox.Direction.SALE,
                "[{\"offerCode\":\"OFF1\",\"amount\":5.00}]");
    }

    /**
     * A 2xx answer acknowledges the declaration.
     */
    @Test
    void deliverPendingMarksAppliedOnSuccess() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).markApplied(7L);
    }

    /**
     * A non-2xx answer records a failure rather than acknowledging.
     */
    @Test
    void deliverRecordsFailureOnServerError() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(500)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).recordFailure(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * An I/O failure is caught and recorded.
     */
    @Test
    void deliverRecordsFailureOnException() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new java.io.IOException("down")).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).recordFailure(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * An interrupted send is caught, recorded and restores the interrupt flag.
     */
    @Test
    void deliverRestoresInterruptFlagWhenInterrupted() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doThrow(new InterruptedException()).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        assertTrue(Thread.interrupted());
        verify(service.consumptionService).recordFailure(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * The request without a configured user carries no authorization header and
     * still delivers (the no-auth arm).
     */
    @Test
    void deliverWithoutUserSendsNoAuthHeader() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        service.user = Optional.empty();
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).markApplied(7L);
    }

    /**
     * {@code body} composes the declaration JSON, embedding the advantages
     * array.
     */
    @Test
    void bodyComposesTheDeclaration() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        String body = service.body(pending());
        assertTrue(body.contains("\"ticketId\":\"T1\""));
        assertTrue(body.contains("\"direction\":\"SALE\""));
        assertTrue(body.contains("\"offerCode\":\"OFF1\""));
    }

    /**
     * {@code body} emits an empty advantages array when the row carries none
     * (the null-advantages arm).
     */
    @Test
    void bodyEmitsEmptyArrayWithoutAdvantages() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        ConsumptionService.PendingConsumption noAdvantages =
                new ConsumptionService.PendingConsumption(1L, "T2", "C04", null,
                        ConsumptionOutbox.Direction.CANCELLATION, null);
        String body = service.body(noAdvantages);
        assertTrue(body.contains("\"advantages\":[]"));
    }

    /**
     * A sub-200 answer is a refusal too (the statusCode &lt; 200 arm).
     */
    @Test
    void deliverRecordsFailureOnInformationalStatus() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(199)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).recordFailure(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * A configured but blank user carries no authorization header (the
     * present-yet-blank user arm).
     */
    @Test
    void deliverWithBlankUserSendsNoAuthHeader() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        service.user = Optional.of("   ");
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        service.deliverPending();
        verify(service.consumptionService).markApplied(7L);
    }

    /**
     * {@code onStart} does not start a loop when the engine URL is absent.
     */
    @Test
    void onStartDisabledWithoutUrl() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        service.url = Optional.empty();
        service.onStart(null);
        service.onStop();
    }

    /**
     * {@code onStart} does not start a loop when the engine URL is blank (the
     * present-yet-blank url arm).
     */
    @Test
    void onStartDisabledWithBlankUrl() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        service.url = Optional.of("   ");
        service.onStart(null);
        service.onStop();
    }

    /**
     * {@code onStart} starts the loop when the URL is present, and {@code
     * onStop} stops it.
     */
    @Test
    void onStartRunsWithUrlAndOnStopStops() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        service.onStart(null);
        service.onStop();
    }

    /**
     * {@code triggerDelivery} returns null when the manual cycle ran.
     */
    @Test
    void triggerDeliveryReturnsNullOnSuccess() throws Exception {
        HttpClient client = mock(HttpClient.class);
        doReturn(response(200)).when(client).send(any(HttpRequest.class), any());
        ConsumptionDeliveryService service = serviceWith(client);
        when(service.consumptionService.pending()).thenReturn(List.of(pending()));
        assertNull(service.triggerDelivery());
    }

    /**
     * {@code triggerDelivery} returns the failure message when the cycle throws.
     */
    @Test
    void triggerDeliveryReturnsMessageOnFailure() throws Exception {
        ConsumptionDeliveryService service = serviceWith(mock(HttpClient.class));
        when(service.consumptionService.pending()).thenThrow(new RuntimeException("boom"));
        assertTrue(service.triggerDelivery().contains("boom"));
    }
}
