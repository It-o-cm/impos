package com.intermarche.pos.ui.home;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.payment.PaymentService;
import com.intermarche.pos.ui.payment.PaymentState;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link PosHardwareResource}.
 * <p>
 * The resource is a thin JAX-RS facade over {@link TicketService},
 * {@link PaymentService} and {@link PosState}. Every collaborator is a Mockito
 * mock, with a real {@link PaymentState} wired onto the mocked {@code PosState}
 * so its {@code pendingCardAmount} public field can be driven directly. Tests
 * assert absolute status codes, JSON payloads and delegation, covering both arms
 * of every {@code null}/empty short-circuit, the pending ternary and the two
 * "no pending request" conflict guards.
 */
class PosHardwareResourceTest {

    /**
     * Builds a {@link PosHardwareResource} whose collaborators are fresh mocks
     * wired onto its package-private fields, with a real {@link PaymentState} on
     * the mocked state so {@code pendingCardAmount} is addressable.
     *
     * @return a resource with fully mocked services and state
     */
    private PosHardwareResource newResource() {
        PosHardwareResource resource = new PosHardwareResource();
        resource.ticketService = mock(TicketService.class);
        resource.paymentService = mock(PaymentService.class);
        resource.state = mock(PosState.class);
        resource.state.payment = new PaymentState();
        return resource;
    }

    // --- handleScan ---

    /**
     * {@code handleScan()} returns 400 without delegating when the code is null
     * (first arm of the guard true).
     */
    @Test
    void handleScanRejectsNullCode() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleScan(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code handleScan()} returns 400 without delegating when the code is empty
     * (second arm of the guard true).
     */
    @Test
    void handleScanRejectsEmptyCode() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleScan("");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code handleScan()} processes a non-empty code and returns 200 (both arms
     * of the guard false).
     */
    @Test
    void handleScanProcessesValidCode() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleScan("3456789012345");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(resource.ticketService).processScan("3456789012345");
    }

    // --- handleWeight ---

    /**
     * {@code handleWeight()} returns 400 without delegating when the weight is
     * null (first arm of the guard true).
     */
    @Test
    void handleWeightRejectsNullWeight() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleWeight(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code handleWeight()} returns 400 without delegating when the weight is
     * empty (second arm of the guard true).
     */
    @Test
    void handleWeightRejectsEmptyWeight() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleWeight("");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        verifyNoInteractions(resource.ticketService);
    }

    /**
     * {@code handleWeight()} processes a non-empty weight and returns 200 (both
     * arms of the guard false).
     */
    @Test
    void handleWeightProcessesValidWeight() {
        PosHardwareResource resource = newResource();
        Response response = resource.handleWeight("0,750");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(resource.ticketService).processWeight("0,750");
    }

    // --- tpeStatus ---

    /**
     * {@code tpeStatus()} reports a pending request with the formatted amount when
     * a card amount is set (ternary true arm).
     */
    @Test
    void tpeStatusReportsPendingWithFormattedAmount() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = new BigDecimal("12.5");
        Map<String, Object> result = resource.tpeStatus();
        assertEquals(true, result.get("pending"));
        assertEquals("12,50", result.get("amount"));
    }

    /**
     * {@code tpeStatus()} reports no pending request with an empty amount when no
     * card amount is set (ternary false arm).
     */
    @Test
    void tpeStatusReportsNoPendingWhenAmountNull() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = null;
        Map<String, Object> result = resource.tpeStatus();
        assertEquals(false, result.get("pending"));
        assertEquals("", result.get("amount"));
    }

    // --- tpeAccept ---

    /**
     * {@code tpeAccept()} returns 409 without delegating when no card request is
     * pending (guard true arm).
     */
    @Test
    void tpeAcceptConflictsWhenNoPending() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = null;
        Response response = resource.tpeAccept();
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals("Aucune demande en attente", response.getEntity());
        verifyNoInteractions(resource.paymentService);
    }

    /**
     * {@code tpeAccept()} confirms the pending card payment and returns 200 when a
     * request is pending (guard false arm).
     */
    @Test
    void tpeAcceptConfirmsWhenPending() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = new BigDecimal("5.00");
        Response response = resource.tpeAccept();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(resource.paymentService).confirmPendingCard(resource.state);
    }

    // --- tpeRefuse ---

    /**
     * {@code tpeRefuse()} returns 409 without delegating when no card request is
     * pending (guard true arm).
     */
    @Test
    void tpeRefuseConflictsWhenNoPending() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = null;
        Response response = resource.tpeRefuse();
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals("Aucune demande en attente", response.getEntity());
        verifyNoInteractions(resource.paymentService);
    }

    /**
     * {@code tpeRefuse()} drops the pending card payment and returns 200 when a
     * request is pending (guard false arm).
     */
    @Test
    void tpeRefuseDropsWhenPending() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = new BigDecimal("5.00");
        Response response = resource.tpeRefuse();
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(resource.paymentService).refusePendingCard(resource.state);
    }

    /**
     * Sanity guard on the JSON shape: {@code tpeStatus()} always carries both the
     * {@code pending} flag and an {@code amount} entry regardless of state.
     */
    @Test
    void tpeStatusAlwaysCarriesBothKeys() {
        PosHardwareResource resource = newResource();
        resource.state.payment.pendingCardAmount = null;
        Map<String, Object> result = resource.tpeStatus();
        assertTrue(result.containsKey("pending"));
        assertTrue(result.containsKey("amount"));
        assertFalse(result.isEmpty());
    }
}
