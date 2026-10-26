package com.intermarche.pos.ui.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link MockChequeGuaranteeResource}, the simulated
 * cheque-guarantee provider (BO-03-02-24): the provider down, an opposed
 * account, an amount above the ceiling, an absent amount and an approval
 * numbered in sequence.
 */
class MockChequeGuaranteeResourceTest {

    /**
     * Builds a request body.
     *
     * @param account the account number, or null for none
     * @param amount the amount, or null for none
     * @return the body
     */
    private ObjectNode request(String account, String amount) {
        ObjectNode body = new ObjectMapper().createObjectNode();
        if (account != null) {
            body.put("accountNumber", account);
        }
        if (amount != null) {
            body.put("amount", new java.math.BigDecimal(amount));
        }
        return body;
    }

    /**
     * Reads the answer map of a response.
     *
     * @param response the response
     * @return its entity
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    /**
     * Every rule of the simulated provider.
     */
    @Test
    void theSimulatedProviderPlaysEveryOutcome() {
        MockChequeGuaranteeResource resource = new MockChequeGuaranteeResource();
        assertEquals(503, resource.authorize(request("9991", "10")).getStatus());
        Map<String, Object> opposed = body(resource.authorize(request("0001", "10")));
        assertEquals(false, opposed.get("approved"));
        assertEquals("CHEQUE EN OPPOSITION", opposed.get("message"));
        Map<String, Object> ceiling = body(resource.authorize(request("123", "1500.01")));
        assertEquals(false, ceiling.get("approved"));
        assertEquals("MONTANT SUPERIEUR AU PLAFOND GARANTI", ceiling.get("message"));
        Map<String, Object> first = body(resource.authorize(request(null, null)));
        assertEquals(true, first.get("approved"));
        assertEquals("GAR000001", first.get("authorization"));
        Map<String, Object> second = body(resource.authorize(request("123", "1500")));
        assertEquals("GAR000002", second.get("authorization"));
        assertEquals("CHEQUE GARANTI", second.get("message"));
    }
}
