package com.intermarche.pos.ui.customer;

import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.service.QrCodeService;
import com.intermarche.pos.service.TechnicalEventService;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DigitalTicketResource}, the public capability-URL
 * digital receipt. All three endpoints funnel through the private
 * {@code load} (key match + CLOSED-only gate) and {@code render} (VAT
 * ventilation) helpers. Collaborators are mocked; the {@code Ticket.findById}
 * static finder resolves to {@link PanacheEntityBase} under plain
 * {@code mvn test} and is intercepted with {@link org.mockito.Mockito#mockStatic}.
 * Tests cover the four {@code load} guards (each arm), the three-part email
 * send condition (each arm), the QR 404-vs-OK branch with both base-url arms,
 * and the render ticket-present/absent, loop and ternary branches
 * (22 branches).
 */
class DigitalTicketResourceTest {

    /** Access key used across the happy-path tests. */
    private static final String KEY = "ABCDEF0123456789";

    /** Captures the data bound to the template by {@code render}. */
    private Map<String, Object> captured;

    /** The sentinel instance returned by the stubbed template chain. */
    private TemplateInstance sentinel;

    /**
     * Builds a resource with a stubbed template chain (recording every bound
     * key into {@link #captured} and returning {@link #sentinel}) and fresh
     * mock collaborators wired onto its package-private fields.
     *
     * @param baseUrl the configured public base URL
     * @return a ready-to-exercise resource
     */
    private DigitalTicketResource newResource(Optional<String> baseUrl) {
        captured = new HashMap<>();
        sentinel = mock(TemplateInstance.class);
        Template template = mock(Template.class);
        when(template.data(anyString(), any())).thenAnswer(inv -> {
            captured.put(inv.getArgument(0), inv.getArgument(1));
            return sentinel;
        });
        when(sentinel.data(anyString(), any())).thenAnswer(inv -> {
            captured.put(inv.getArgument(0), inv.getArgument(1));
            return sentinel;
        });
        DigitalTicketResource resource = new DigitalTicketResource();
        resource.digitalTicket = template;
        resource.technicalEventService = mock(TechnicalEventService.class);
        resource.qrCodeService = mock(QrCodeService.class);
        resource.baseUrl = baseUrl;
        return resource;
    }

    /**
     * Builds a mock closed ticket carrying the given key and one VAT line so
     * the render loop and ventilation are exercised.
     *
     * @param key the digital key to expose, or null
     * @param status the lifecycle status
     * @return the mock ticket
     */
    private Ticket closableTicket(String key, Ticket.TicketStatus status) {
        Ticket ticket = mock(Ticket.class);
        ticket.digitalKey = key;
        ticket.status = status;
        ticket.ticketNumber = "C04-1";
        TicketLine line = new TicketLine();
        line.vatRate = new BigDecimal("0.2000");
        line.totalPrice = new BigDecimal("12.00");
        List<TicketLine> lines = new ArrayList<>();
        lines.add(line);
        ticket.lines = lines;
        return ticket;
    }

    // --- view / load happy path + render ticket-present ---

    /**
     * {@code view} renders the receipt when the key matches a closed ticket
     * (all four load guards false), exercising the render present-arm, the
     * populated line loop and the ternary true-arm.
     */
    @Test
    void viewRendersReceiptForClosedTicket() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(7L)).thenReturn(ticket);
            assertSame(sentinel, resource.view(7L, KEY));
        }
        assertSame(ticket, captured.get("ticket"));
        assertEquals("/t/7/" + KEY, captured.get("path"));
        assertEquals(Boolean.FALSE, captured.get("sent"));
        assertFalse(((List<?>) captured.get("buckets")).isEmpty());
    }

    /**
     * {@code view} renders the unavailable variant when no ticket is found
     * (first load guard true), exercising the render absent-arm, the skipped
     * loop and the ternary false-arm (empty buckets).
     */
    @Test
    void viewRendersUnavailableWhenTicketMissing() {
        DigitalTicketResource resource = newResource(Optional.empty());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(1L)).thenReturn(null);
            assertSame(sentinel, resource.view(1L, KEY));
        }
        assertNull(captured.get("ticket"));
        assertEquals("/t/1/" + KEY, captured.get("path"));
        assertEquals(Boolean.FALSE, captured.get("sent"));
        assertTrue(((List<?>) captured.get("buckets")).isEmpty());
    }

    // --- load: remaining guards (via qr, cleanest 404-vs-OK) ---

    /**
     * {@code qr} returns 404 when the found ticket has a null digital key
     * (second load guard true).
     */
    @Test
    void qrNotFoundWhenDigitalKeyNull() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(null, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(2L)).thenReturn(ticket);
            Response response = resource.qr(2L, KEY);
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }
        verifyNoInteractions(resource.qrCodeService);
    }

    /**
     * {@code qr} returns 404 when the presented key does not match the
     * ticket's digital key (third load guard true).
     */
    @Test
    void qrNotFoundWhenKeyMismatch() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(3L)).thenReturn(ticket);
            Response response = resource.qr(3L, "WRONGKEY00000000");
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }
        verifyNoInteractions(resource.qrCodeService);
    }

    /**
     * {@code qr} returns 404 when the ticket exists with a matching key but is
     * not closed (fourth load guard true).
     */
    @Test
    void qrNotFoundWhenNotClosed() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.OPEN);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(4L)).thenReturn(ticket);
            Response response = resource.qr(4L, KEY);
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }
        verifyNoInteractions(resource.qrCodeService);
    }

    // --- qr: OK arm, both base-url arms ---

    /**
     * {@code qr} serves the SVG with an absolute target when a base URL is
     * configured (load all-false, {@code Optional} present).
     */
    @Test
    void qrServesSvgWithConfiguredBaseUrl() {
        DigitalTicketResource resource = newResource(Optional.of("http://caisse04:8080"));
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        when(resource.qrCodeService.toSvg("http://caisse04:8080/t/5/" + KEY)).thenReturn("<svg/>");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(5L)).thenReturn(ticket);
            Response response = resource.qr(5L, KEY);
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals("<svg/>", response.getEntity());
        }
        verify(resource.qrCodeService).toSvg("http://caisse04:8080/t/5/" + KEY);
    }

    /**
     * {@code qr} serves the SVG with a relative target when no base URL is
     * configured ({@code Optional} empty, LAN demo mode).
     */
    @Test
    void qrServesSvgWithRelativePathWhenBaseUrlAbsent() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        when(resource.qrCodeService.toSvg("/t/6/" + KEY)).thenReturn("<svg/>");
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(6L)).thenReturn(ticket);
            Response response = resource.qr(6L, KEY);
            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertEquals("<svg/>", response.getEntity());
        }
        verify(resource.qrCodeService).toSvg("/t/6/" + KEY);
    }

    // --- sendByEmail: the three-part condition ---

    /**
     * {@code sendByEmail} stores the email, journals the send and reports it
     * sent when the ticket is present and the email is well-formed (all three
     * condition arms true).
     */
    @Test
    void sendByEmailSendsWhenTicketAndEmailValid() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(8L)).thenReturn(ticket);
            assertSame(sentinel, resource.sendByEmail(8L, KEY, "a@b.co"));
        }
        assertEquals("a@b.co", ticket.customerEmail);
        verify(ticket).persist();
        verify(resource.technicalEventService)
                .log(eq(TechnicalEvent.EventType.DIGITAL_TICKET_SENT), eq("C04-1 -> a@b.co"));
        assertEquals(Boolean.TRUE, captured.get("sent"));
    }

    /**
     * {@code sendByEmail} does nothing and reports not sent when no ticket is
     * found (first condition arm false).
     */
    @Test
    void sendByEmailNotSentWhenTicketMissing() {
        DigitalTicketResource resource = newResource(Optional.empty());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(9L)).thenReturn(null);
            assertSame(sentinel, resource.sendByEmail(9L, KEY, "a@b.co"));
        }
        assertNull(captured.get("ticket"));
        assertEquals(Boolean.FALSE, captured.get("sent"));
        verifyNoInteractions(resource.technicalEventService);
    }

    /**
     * {@code sendByEmail} reports not sent when the email is null (second
     * condition arm false), leaving the ticket untouched.
     */
    @Test
    void sendByEmailNotSentWhenEmailNull() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(10L)).thenReturn(ticket);
            assertSame(sentinel, resource.sendByEmail(10L, KEY, null));
        }
        assertNull(ticket.customerEmail);
        verify(ticket, never()).persist();
        verifyNoInteractions(resource.technicalEventService);
        assertEquals(Boolean.FALSE, captured.get("sent"));
    }

    /**
     * {@code sendByEmail} reports not sent when the email is malformed (third
     * condition arm false), leaving the ticket untouched.
     */
    @Test
    void sendByEmailNotSentWhenEmailMalformed() {
        DigitalTicketResource resource = newResource(Optional.empty());
        Ticket ticket = closableTicket(KEY, Ticket.TicketStatus.CLOSED);
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> Ticket.findById(11L)).thenReturn(ticket);
            assertSame(sentinel, resource.sendByEmail(11L, KEY, "not-an-email"));
        }
        assertNull(ticket.customerEmail);
        verify(ticket, never()).persist();
        verifyNoInteractions(resource.technicalEventService);
        assertEquals(Boolean.FALSE, captured.get("sent"));
    }
}
