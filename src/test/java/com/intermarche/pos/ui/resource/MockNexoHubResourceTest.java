package com.intermarche.pos.ui.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MockNexoHubResource}, the simulated payment hub
 * (LC-02-09-01 to -03, LC-05-03-01, LC-07-11-01), targeting 100% branch
 * coverage.
 *
 * <p>Plain JUnit 5 over a real mapper: each test sends Nexo envelopes and reads
 * the answers. Branch enumeration: the four categories and an unknown one; the
 * three stored-value types and an unknown one, with an amount and without; each
 * refusal of an activation, an unload, a reverse, an inquiry, a payment and a
 * payment reversal; the account read for the panel, known and unknown.
 */
class MockNexoHubResourceTest {

    /** Builds the requests. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * A fresh hub with its two seeded cards.
     *
     * @return the hub
     */
    private MockNexoHubResource hub() {
        MockNexoHubResource hub = new MockNexoHubResource();
        hub.objectMapper = mapper;
        return hub;
    }

    /**
     * Sends a request body under its category and returns the response body.
     *
     * @param hub the hub
     * @param category the message category
     * @param requestName the request name
     * @param body the request body JSON
     * @param responseName the response name
     * @return the response body
     * @throws Exception when the JSON cannot be read
     */
    private JsonNode send(MockNexoHubResource hub, String category, String requestName, String body,
                          String responseName) throws Exception {
        JsonNode envelope = mapper.readTree("{\"SaleToPOIRequest\":{\"MessageHeader\":{\"ProtocolVersion\":\"3.1\","
                + "\"MessageCategory\":\"" + category + "\",\"ServiceID\":\"7\",\"SaleID\":\"POS01\",\"POIID\":\"HUB\"},\""
                + requestName + "\":" + body + "}}");
        JsonNode response = hub.exchange(envelope).path("SaleToPOIResponse");
        assertEquals("Response", response.path("MessageHeader").path("MessageType").asText());
        assertEquals(category, response.path("MessageHeader").path("MessageCategory").asText());
        assertEquals("7", response.path("MessageHeader").path("ServiceID").asText());
        return response.path(responseName);
    }

    /**
     * A stored-value request.
     *
     * @param hub the hub
     * @param data the stored-value data JSON
     * @return the response body
     * @throws Exception when the JSON cannot be read
     */
    private JsonNode storedValue(MockNexoHubResource hub, String data) throws Exception {
        return send(hub, "StoredValue", "StoredValueRequest", "{\"StoredValueData\":" + data + "}",
                "StoredValueResponse");
    }

    /**
     * The stored-value data of an operation on a card.
     *
     * @param type the operation type
     * @param card the card code
     * @param provider the provider
     * @param amount the amount JSON, or null for none
     * @return the data JSON
     */
    private static String data(String type, String card, String provider, String amount) {
        return "{\"StoredValueTransactionType\":\"" + type + "\",\"StoredValueAccountID\":{\"StoredValueID\":\""
                + card + "\",\"StoredValueProvider\":\"" + provider + "\"}"
                + (amount == null ? "" : ",\"ItemAmount\":" + amount) + "}";
    }

    /**
     * Whether a response is a refusal with the given condition.
     *
     * @param response the response body
     * @param condition the expected condition
     */
    private static void refused(JsonNode response, String condition) {
        assertEquals("Failure", response.path("Response").path("Result").asText());
        assertEquals(condition, response.path("Response").path("ErrorCondition").asText());
    }

    /**
     * The hub's transaction identifier of an accepted response.
     *
     * @param response the response body
     * @return the identifier
     */
    private static String transaction(JsonNode response) {
        assertEquals("Success", response.path("Response").path("Result").asText());
        return response.path("POIData").path("POITransactionID").path("TransactionID").asText();
    }

    /**
     * An activation opens an account for its amount, under the provider asked
     * for or the hub's own; a second one of an active code, a blank code, a
     * missing or non-positive amount and a refused prefix are refused; an
     * inactive code is activated again.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void activationsOpenAccountsAndAreRefusedWhenTheyMust() throws Exception {
        MockNexoHubResource hub = hub();
        JsonNode accepted = storedValue(hub, data("Activate", "9001", "TRANSACTIS", "25.00"));
        assertEquals("HUB00000001", transaction(accepted));
        JsonNode result = accepted.path("StoredValueResult").path(0);
        assertEquals("Activate", result.path("StoredValueTransactionType").asText());
        assertEquals("S1", result.path("HostTransactionID").path("TransactionID").asText());
        assertEquals("TRANSACTIS", result.path("StoredValueAccountStatus").path("StoredValueAccountID")
                .path("StoredValueProvider").asText());
        assertEquals(0, new BigDecimal("25.00").compareTo(result.path("StoredValueAccountStatus")
                .path("CurrentBalance").decimalValue()));
        refused(storedValue(hub, data("Activate", "9001", "TRANSACTIS", "25.00")), "NotAllowed");
        refused(storedValue(hub, data("Activate", "", "P", "25.00")), "NotAllowed");
        refused(storedValue(hub, data("Activate", "9002", "P", null)), "NotAllowed");
        refused(storedValue(hub, data("Activate", "9002", "P", "0")), "NotAllowed");
        refused(storedValue(hub, data("Activate", "99990001", "P", "5")), "Refusal");
        storedValue(hub, data("Activate", "9003", "", "10"));
        assertEquals(MockNexoHubResource.DEFAULT_PROVIDER, hub.card("9003").get("provider"));
        storedValue(hub, data("Unload", "9003", "", "10"));
        assertEquals(false, hub.card("9003").get("active"));
        transaction(storedValue(hub, data("Activate", "9003", "P", "7")));
        assertEquals(true, hub.card("9003").get("active"));
    }

    /**
     * An unload takes from an active account, deactivating it once empty; an
     * unknown or inactive account, a missing, non-positive or excessive amount
     * are refused; a reverse puts the unloaded amount back.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void unloadsAndTheirReversal() throws Exception {
        MockNexoHubResource hub = hub();
        String partial = transaction(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", "10.00")));
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) hub.card(MockNexoHubResource.CARD_FUNDED).get("balance")));
        assertEquals(true, hub.card(MockNexoHubResource.CARD_FUNDED).get("active"));
        refused(storedValue(hub, data("Unload", "UNKNOWN", "", "1")), "InvalidCard");
        refused(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", null)), "Refusal");
        refused(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", "-1")), "Refusal");
        refused(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", "99")), "Refusal");
        transaction(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", "20.00")));
        assertEquals(false, hub.card(MockNexoHubResource.CARD_FUNDED).get("active"));
        refused(storedValue(hub, data("Unload", MockNexoHubResource.CARD_FUNDED, "", "1")), "InvalidCard");
        JsonNode reversed = storedValue(hub, "{\"StoredValueTransactionType\":\"Reverse\",\"OriginalPOITransaction\":"
                + "{\"POITransactionID\":{\"TransactionID\":\"" + partial + "\"}}}");
        transaction(reversed);
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) hub.card(MockNexoHubResource.CARD_FUNDED).get("balance")));
        assertEquals(true, hub.card(MockNexoHubResource.CARD_FUNDED).get("active"));
    }

    /**
     * A reverse undoes an activation once; an unknown operation, one already
     * reversed and a payment are refused; an unknown stored-value type too.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void reversesUndoOnceAndOnlyStoredValue() throws Exception {
        MockNexoHubResource hub = hub();
        String activation = transaction(storedValue(hub, data("Activate", "9001", "P", "25")));
        String reverse = "{\"StoredValueTransactionType\":\"Reverse\",\"OriginalPOITransaction\":"
                + "{\"POITransactionID\":{\"TransactionID\":\"%s\"}}}";
        transaction(storedValue(hub, String.format(reverse, activation)));
        assertEquals(false, hub.card("9001").get("active"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) hub.card("9001").get("balance")));
        refused(storedValue(hub, String.format(reverse, activation)), "NotAllowed");
        refused(storedValue(hub, String.format(reverse, "NONE")), "NotAllowed");
        JsonNode paid = send(hub, "Payment", "PaymentRequest", payment(MockNexoHubResource.CARD_FUNDED, "5"),
                "PaymentResponse");
        refused(storedValue(hub, String.format(reverse, transaction(paid))), "NotAllowed");
        refused(storedValue(hub, data("Load", "9001", "P", "5")), "NotAllowed");
    }

    /**
     * A payment request on a card.
     *
     * @param card the card code
     * @param amount the requested amount JSON
     * @return the request JSON
     */
    private static String payment(String card, String amount) {
        return "{\"PaymentTransaction\":{\"AmountsReq\":{\"RequestedAmount\":" + amount + "}},"
                + "\"PaymentData\":{\"PaymentInstrumentData\":{\"StoredValueAccountID\":{\"StoredValueID\":\""
                + card + "\"}}}}";
    }

    /**
     * A balance inquiry states what an active card carries, with its provider;
     * an unknown or inactive card is refused.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void balanceInquiries() throws Exception {
        MockNexoHubResource hub = hub();
        String inquiry = "{\"PaymentAccountReq\":{\"PaymentInstrumentData\":{\"StoredValueAccountID\":"
                + "{\"StoredValueID\":\"%s\"}}}}";
        JsonNode funded = send(hub, "BalanceInquiry", "BalanceInquiryRequest",
                String.format(inquiry, MockNexoHubResource.CARD_FUNDED), "BalanceInquiryResponse");
        transaction(funded);
        assertEquals(0, new BigDecimal("30.00").compareTo(funded.path("PaymentAccountStatus")
                .path("CurrentBalance").decimalValue()));
        assertEquals("ILLICADO", funded.path("PaymentAccountStatus").path("PaymentAcquirerData")
                .path("AcquirerID").asText());
        assertEquals("B1", funded.path("PaymentAccountStatus").path("PaymentAcquirerData")
                .path("ApprovalCode").asText());
        refused(send(hub, "BalanceInquiry", "BalanceInquiryRequest", String.format(inquiry, "NONE"),
                "BalanceInquiryResponse"), "InvalidCard");
        storedValue(hub, data("Activate", "9001", "P", "5"));
        storedValue(hub, data("Unload", "9001", "P", "5"));
        refused(send(hub, "BalanceInquiry", "BalanceInquiryRequest", String.format(inquiry, "9001"),
                "BalanceInquiryResponse"), "InvalidCard");
    }

    /**
     * A payment debits an active card up to its balance and is reversed once; an
     * unknown or inactive card, a missing, non-positive or excessive amount are
     * refused, and so is the reversal of an unknown, already reversed or
     * non-payment operation.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void paymentsAndTheirReversal() throws Exception {
        MockNexoHubResource hub = hub();
        JsonNode paid = send(hub, "Payment", "PaymentRequest", payment(MockNexoHubResource.CARD_FUNDED, "12.40"),
                "PaymentResponse");
        String id = transaction(paid);
        assertEquals(0, new BigDecimal("12.40").compareTo(paid.path("PaymentResult").path("AmountsResp")
                .path("AuthorizedAmount").decimalValue()));
        assertEquals("ILLICADO", paid.path("PaymentResult").path("PaymentAcquirerData").path("AcquirerID").asText());
        assertEquals(0, new BigDecimal("17.60").compareTo((BigDecimal) hub.card(MockNexoHubResource.CARD_FUNDED).get("balance")));
        refused(send(hub, "Payment", "PaymentRequest", payment("NONE", "1"), "PaymentResponse"), "InvalidCard");
        refused(send(hub, "Payment", "PaymentRequest", payment(MockNexoHubResource.CARD_FUNDED, "\"x\""),
                "PaymentResponse"), "Refusal");
        refused(send(hub, "Payment", "PaymentRequest", payment(MockNexoHubResource.CARD_FUNDED, "0"),
                "PaymentResponse"), "Refusal");
        refused(send(hub, "Payment", "PaymentRequest", payment(MockNexoHubResource.CARD_EMPTY, "1"),
                "PaymentResponse"), "Refusal");
        String reversal = "{\"OriginalPOITransaction\":{\"POITransactionID\":{\"TransactionID\":\"%s\"}}}";
        JsonNode reversed = send(hub, "Reversal", "ReversalRequest", String.format(reversal, id), "ReversalResponse");
        transaction(reversed);
        assertEquals(0, new BigDecimal("12.40").compareTo(reversed.path("ReversedAmount").decimalValue()));
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) hub.card(MockNexoHubResource.CARD_FUNDED).get("balance")));
        refused(send(hub, "Reversal", "ReversalRequest", String.format(reversal, id), "ReversalResponse"), "NotAllowed");
        refused(send(hub, "Reversal", "ReversalRequest", String.format(reversal, "NONE"), "ReversalResponse"), "NotAllowed");
        String activation = transaction(storedValue(hub, data("Activate", "9001", "P", "5")));
        refused(send(hub, "Reversal", "ReversalRequest", String.format(reversal, activation), "ReversalResponse"),
                "NotAllowed");
        storedValue(hub, data("Unload", "9001", "P", "5"));
        refused(send(hub, "Payment", "PaymentRequest", payment("9001", "1"), "PaymentResponse"), "InvalidCard");
    }

    /**
     * The completion acknowledging a kept payment marks it acknowledged; the
     * completion of an unknown, reversed or non-payment operation is refused.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void completionsAcknowledgeAKeptPayment() throws Exception {
        MockNexoHubResource hub = hub();
        String id = transaction(send(hub, "Payment", "PaymentRequest",
                payment(MockNexoHubResource.CARD_FUNDED, "5"), "PaymentResponse"));
        String completion = "{\"PaymentTransaction\":{\"AmountsReq\":{\"RequestedAmount\":5},"
                + "\"OriginalPOITransaction\":{\"POITransactionID\":{\"TransactionID\":\"%s\"}}},"
                + "\"PaymentData\":{\"PaymentType\":\"Completion\"}}";
        JsonNode completed = send(hub, "Payment", "PaymentRequest", String.format(completion, id), "PaymentResponse");
        transaction(completed);
        assertEquals(0, new BigDecimal("5").compareTo(completed.path("PaymentResult").path("AmountsResp")
                .path("AuthorizedAmount").decimalValue()));
        assertTrue(hub.operations.get(id).completed);
        assertEquals(0, new BigDecimal("25.00").compareTo((BigDecimal) hub.card(MockNexoHubResource.CARD_FUNDED).get("balance")));
        refused(send(hub, "Payment", "PaymentRequest", String.format(completion, "NONE"), "PaymentResponse"),
                "NotAllowed");
        String activation = transaction(storedValue(hub, data("Activate", "9001", "P", "5")));
        refused(send(hub, "Payment", "PaymentRequest", String.format(completion, activation), "PaymentResponse"),
                "NotAllowed");
        String reversal = "{\"OriginalPOITransaction\":{\"POITransactionID\":{\"TransactionID\":\"%s\"}}}";
        send(hub, "Reversal", "ReversalRequest", String.format(reversal, id), "ReversalResponse");
        refused(send(hub, "Payment", "PaymentRequest", String.format(completion, id), "PaymentResponse"),
                "NotAllowed");
    }

    /**
     * An unknown category is refused as malformed; the panel reads a known
     * account and refuses an unknown one.
     *
     * @throws Exception when the JSON cannot be read
     */
    @Test
    void anUnknownCategoryAndThePanel() throws Exception {
        MockNexoHubResource hub = hub();
        refused(send(hub, "Diagnosis", "DiagnosisRequest", "{}", "ErrorResponse"), "MessageFormat");
        Map<String, Object> card = hub.card(MockNexoHubResource.CARD_EMPTY);
        assertEquals(MockNexoHubResource.CARD_EMPTY, card.get("id"));
        assertEquals("ILLICADO", card.get("provider"));
        assertTrue((Boolean) card.get("active"));
        assertThrows(NotFoundException.class, () -> hub.card("NONE"));
        assertFalse(hub.accounts.isEmpty());
    }
}
