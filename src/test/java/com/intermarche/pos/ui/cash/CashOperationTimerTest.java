package com.intermarche.pos.ui.cash;

import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CashOperationTimer} (BO-06-06-03): the clock started
 * on a cash screen, kept while the screen is reloaded, dropped on the sale or
 * lock screen, stopped and journaled by a successful recording, and left
 * running — or dropped unjournaled — otherwise.
 */
class CashOperationTimerTest {

    /**
     * Builds a timer over a real state, a mocked journal and a settable clock.
     *
     * @param clock the clock's reading, in milliseconds
     * @return the timer
     */
    private CashOperationTimer timer(long[] clock) {
        CashOperationTimer timer = new CashOperationTimer();
        timer.state = new PosState();
        timer.state.auth.operatorBadgeId = "111";
        timer.technicalEventService = mock(TechnicalEventService.class);
        timer.now = () -> clock[0];
        return timer;
    }

    /**
     * Builds a request.
     *
     * @param method the HTTP method
     * @param path the path, with or without its leading slash
     * @return the request
     */
    private ContainerRequestContext request(String method, String path) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        return request;
    }

    /**
     * Builds a response.
     *
     * @param status the status
     * @param location the redirect's location, or null
     * @return the response
     */
    private ContainerResponseContext response(int status, String location) {
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getStatus()).thenReturn(status);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        if (location != null) {
            headers.add("Location", location);
        }
        when(response.getHeaders()).thenReturn(headers);
        return response;
    }

    /**
     * A withdrawal timed from its screen to its recording is journaled with
     * its kind and seconds, under the cashier's badge; the clock then stops.
     */
    @Test
    void aWithdrawalIsTimed() {
        long[] clock = {10_000L};
        CashOperationTimer timer = timer(clock);
        timer.filter(request("GET", "withdrawal"));
        assertEquals(10_000L, timer.state.cashOperationStartedAt);
        clock[0] = 15_000L;
        timer.filter(request("GET", "/withdrawal/confirm"));
        assertEquals(10_000L, timer.state.cashOperationStartedAt);
        clock[0] = 55_000L;
        timer.filter(request("POST", "/action/withdrawal/record"), response(303, "/withdrawal?ok=1"));
        verify(timer.technicalEventService).log(TechnicalEvent.EventType.CASH_OPERATION_TIMED, "PRELEVEMENT;45",
                "111");
        assertEquals(0L, timer.state.cashOperationStartedAt);
    }

    /**
     * The sale and lock screens drop the clock; a POST, or a screen that is
     * not a cash one, leaves it as it is.
     */
    @Test
    void theClockIsDroppedOrKept() {
        long[] clock = {1_000L};
        CashOperationTimer timer = timer(clock);
        timer.filter(request("GET", "/apport"));
        timer.filter(request("GET", "/lock"));
        assertEquals(0L, timer.state.cashOperationStartedAt);
        timer.filter(request("GET", "/session"));
        timer.filter(request("GET", "/"));
        assertEquals(0L, timer.state.cashOperationStartedAt);
        timer.filter(request("GET", "/search"));
        assertEquals(0L, timer.state.cashOperationStartedAt);
        timer.filter(request("GET", "/cash-count"));
        timer.filter(request("POST", "/"));
        assertEquals(1_000L, timer.state.cashOperationStartedAt);
    }

    /**
     * Nothing is journaled for a GET, without a clock, for a route that is
     * no recording, for a failed or refused recording; a recording timed over
     * an hour, or before its start, stops the clock unjournaled.
     */
    @Test
    void nothingIsJournaledOtherwise() {
        long[] clock = {0L};
        CashOperationTimer timer = timer(clock);
        timer.filter(request("GET", "/x"), response(200, null));
        timer.filter(request("POST", "/action/apport/record"), response(303, null));
        timer.state.cashOperationStartedAt = 1_000L;
        timer.filter(request("POST", "/action/sale/record"), response(303, null));
        timer.filter(request("POST", "/action/apport/record"), response(500, null));
        timer.filter(request("POST", "/action/apport/record"), response(303, "/apport?error=1"));
        assertEquals(1_000L, timer.state.cashOperationStartedAt);
        clock[0] = 1_000L + (CashOperationTimer.LONGEST + 1) * 1000L;
        timer.filter(request("POST", "/action/apport/record"), response(303, null));
        assertEquals(0L, timer.state.cashOperationStartedAt);
        timer.state.cashOperationStartedAt = clock[0] + 5_000L;
        timer.filter(request("POST", "/action/session/close"), response(200, null));
        assertEquals(0L, timer.state.cashOperationStartedAt);
        verify(timer.technicalEventService, never()).log(any(), any(), any());
    }

    /**
     * The recordings name their kind, any other route none; a success is a
     * status below 400 whose redirect names no error.
     */
    @Test
    void kindsAndSuccesses() {
        assertEquals("COMPTAGE", CashOperationTimer.kind("/action/session/close-endorsed"));
        assertEquals("TRANSFERT", CashOperationTimer.kind("/action/transfer/record"));
        assertNull(CashOperationTimer.kind("/action/other"));
        assertTrue(CashOperationTimer.succeeded(response(200, null)));
        assertTrue(CashOperationTimer.succeeded(response(303, "/cash-movement?ok=1")));
        assertFalse(CashOperationTimer.succeeded(response(303, "/cash-movement?error=x")));
        assertFalse(CashOperationTimer.succeeded(response(404, null)));
    }
}
