package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.store.Store;
import com.intermarche.pos.ui.recall.ChannelRules;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EcommerceTicketClient}: only a number of the e-commerce
 * virtual register, keyed by an identified operator in a store this register
 * knows, is asked of the node; the ticket the node serves is kept closed here,
 * its lines and totals as booked; every failure toward the node imports
 * nothing.
 */
class EcommerceTicketClientTest {

    /** The node's answer for ticket ECOM-W1. */
    private static final String VIEW = "{\"ticketNumber\":\"ECOM-W1\",\"terminalId\":\"ECOM\","
            + "\"creationDate\":\"2026-10-24T09:00\",\"closingDate\":\"2026-10-24T09:05\","
            + "\"totalIncludingTax\":12.00,\"totalExcludingTax\":10.00,\"totalVat\":2.00,\"extra\":1,"
            + "\"lines\":[{\"lineNumber\":1,\"lineUid\":\"U1\",\"ean\":\"3001\",\"label\":\"LAIT\","
            + "\"quantity\":2,\"unitPrice\":6.00,\"vatRate\":0.2,\"totalPrice\":12.00},{\"lineNumber\":2}]}";

    /**
     * A spied client toward http://store, terminal ECOM, the save neutralized.
     *
     * @param token the shared token
     * @return the client
     */
    private EcommerceTicketClient client(Optional<String> token) {
        EcommerceTicketClient client = spy(new EcommerceTicketClient());
        client.documentUrl = Optional.of(" http://store ");
        client.token = token;
        client.channelRules = mock(ChannelRules.class);
        client.httpClient = mock(HttpClient.class);
        when(client.channelRules.ecommerceTerminal()).thenReturn("ECOM");
        doNothing().when(client).save(any());
        return client;
    }

    /**
     * Makes the node answer.
     *
     * @param client the client
     * @param status the status
     * @param body the body
     * @throws Exception never, the client being a mock
     */
    @SuppressWarnings("unchecked")
    private void answer(EcommerceTicketClient client, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        doReturn(response).when(client.httpClient).send(any(HttpRequest.class), any());
    }

    /**
     * Runs an import with the given operator and store present or not.
     *
     * @param client the client
     * @param number the keyed number
     * @param operator the operator found, or null
     * @param store the store found, or null
     * @return the imported ticket, or null
     */
    @SuppressWarnings("unchecked")
    private Ticket importWith(EcommerceTicketClient client, String number, Employee operator, Store store) {
        PanacheQuery<Store> stores = mock(PanacheQuery.class);
        when(stores.firstResult()).thenReturn(store);
        try (MockedStatic<PanacheEntityBase> base = mockStatic(PanacheEntityBase.class)) {
            base.when(() -> PanacheEntityBase.findById(7L)).thenReturn(operator);
            base.when(Store::findAll).thenReturn(stores);
            return client.importTicket(number, 7L);
        }
    }

    /**
     * A number that is not the virtual register's, no virtual register, or no
     * operator asks the node nothing.
     *
     * @throws Exception never
     */
    @Test
    void onlyAnEcommerceNumberIsAsked() throws Exception {
        EcommerceTicketClient client = client(Optional.empty());
        assertNull(client.importTicket(null, 7L));
        assertNull(client.importTicket("C04-0001", 7L));
        assertNull(client.importTicket("ECOM-W1", null));
        when(client.channelRules.ecommerceTerminal()).thenReturn("");
        assertNull(client.importTicket("ECOM-W1", 7L));
        verify(client.httpClient, never()).send(any(HttpRequest.class), any());
    }

    /**
     * An operator or a store this register does not know imports nothing.
     *
     * @throws Exception never
     */
    @Test
    void anUnknownOperatorOrStoreImportsNothing() throws Exception {
        EcommerceTicketClient client = client(Optional.empty());
        assertNull(importWith(client, "ECOM-W1", null, new Store()));
        assertNull(importWith(client, "ECOM-W1", new Employee(), null));
        verify(client.httpClient, never()).send(any(HttpRequest.class), any());
    }

    /**
     * The ticket the node serves is kept closed here, under the operator and
     * the store, its totals and lines as booked — a missing label and amounts
     * read as empty and zero; the request carries the token.
     *
     * @throws Exception never
     */
    @Test
    void theServedTicketIsKeptClosedHere() throws Exception {
        EcommerceTicketClient client = client(Optional.of("TOK"));
        answer(client, 200, VIEW);
        Employee operator = new Employee();
        Store store = new Store();
        Ticket ticket = importWith(client, " ECOM-W1 ", operator, store);
        verify(client).save(ticket);
        assertEquals("ECOM-W1", ticket.ticketNumber);
        assertEquals("ECOM", ticket.terminalId);
        assertEquals(Ticket.TicketStatus.CLOSED, ticket.status);
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 5), ticket.closingDate);
        assertSame(operator, ticket.cashier);
        assertSame(store, ticket.store);
        assertEquals(new BigDecimal("12.00"), ticket.totalIncludingTax);
        assertEquals(2, ticket.itemCount);
        assertEquals("LAIT", ticket.lines.get(0).productLabel);
        assertEquals(new BigDecimal("0.2"), ticket.lines.get(0).vatRate);
        assertEquals("", ticket.lines.get(1).productLabel);
        assertEquals(BigDecimal.ZERO, ticket.lines.get(1).totalPrice);
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(sent.capture(), any());
        assertEquals("http://store/api/ecommerce/tickets/ECOM-W1", sent.getValue().uri().toString());
        assertEquals("TOK", sent.getValue().headers().firstValue("X-Sync-Token").orElseThrow());
    }

    /**
     * A ticket the node serves without dates nor totals is dated now and totals
     * zero.
     *
     * @throws Exception never
     */
    @Test
    void anUndatedTicketIsDatedNow() throws Exception {
        EcommerceTicketClient client = client(Optional.of(" "));
        answer(client, 200, "{\"ticketNumber\":\"ECOM-W2\"}");
        LocalDateTime before = LocalDateTime.now();
        Ticket ticket = importWith(client, "ECOM-W2", new Employee(), new Store());
        assertTrue(!ticket.creationDate.isBefore(before));
        assertEquals(BigDecimal.ZERO, ticket.totalVat);
        assertTrue(ticket.lines.isEmpty());
    }

    /**
     * No node configured, a non-2xx answer or an unreachable node import
     * nothing.
     *
     * @throws Exception never
     */
    @Test
    void everyFailureTowardTheNodeImportsNothing() throws Exception {
        EcommerceTicketClient none = client(Optional.empty());
        none.documentUrl = Optional.empty();
        assertNull(none.fetch("ECOM-W1"));
        none.documentUrl = Optional.of(" ");
        assertNull(none.fetch("ECOM-W1"));
        EcommerceTicketClient low = client(Optional.empty());
        answer(low, 199, VIEW);
        assertNull(low.fetch("ECOM-W1"));
        EcommerceTicketClient high = client(Optional.empty());
        answer(high, 404, VIEW);
        assertNull(high.fetch("ECOM-W1"));
        EcommerceTicketClient down = client(Optional.empty());
        doThrow(new java.io.IOException("down")).when(down.httpClient).send(any(HttpRequest.class), any());
        assertNull(down.fetch("ECOM-W1"));
        assertNull(importWith(down, "ECOM-W1", new Employee(), new Store()));
    }

    /**
     * The save persists the ticket.
     */
    @Test
    void theSavePersists() {
        Ticket ticket = mock(Ticket.class);
        new EcommerceTicketClient().save(ticket);
        verify(ticket).persist();
    }
}
