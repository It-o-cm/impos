package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact gesture grants, not reusable supervisor bypasses (LC-01-05-01). */
class FunctionApprovalServiceTest {
    /**
     * Wires a real cart and inherited policy fixture.
     * @param rules method/path rules
     * @return service under test
     */
    FunctionApprovalService service(String rules) {
        FunctionApprovalService service = new FunctionApprovalService();
        service.state = new PosState();service.settings = mock(PosSettingsService.class);
        when(service.settings.functionEndorsements()).thenReturn(rules);
        return service;
    }

    /** Last rule wins, subtree boundaries and methods remain distinct; malformed rules do not match. */
    @Test
    void policyRules() {
        FunctionApprovalService service = service("bad\nGET=REQUIRED\nPOST /x=REQUIRED\nGET /a/*=REQUIRED\nGET /a/free=NONE");
        assertTrue(service.required("GET", "/a"));
        assertTrue(service.required("GET", "/a/child"));
        assertTrue(service.required("GET", "/a/child/"));
        assertTrue(service("GET /manual/=REQUIRED").required("GET", "/manual"));
        assertFalse(service.required("GET", "/a/free"));
        assertFalse(service.required("GET", "/abc"));
        assertFalse(service.required("POST", "/a"));
        assertTrue(service.required("POST", "/x"));
        assertFalse(service("").required("GET", "/a"));
        assertTrue(service("GET /a=REQUIRED\nGET /a=UNKNOWN").required("GET", "/a"));
    }

    /** No pending request, or a changed operator/cart, can be approved. */
    @Test
    void staleAndAbsentPending() {
        FunctionApprovalService service = service("");
        assertEquals("/", service.approve().getLocation().toString());
        service.hold("GET", "/manual", "");
        service.state.auth.operatorBadgeId = "other";
        assertEquals("/", service.approve().getLocation().toString());
    }

    /** GET continuations preserve the query and consume their grant once. */
    @Test
    void getIsSingleUseAndQueryBound() {
        for (String target : new String[]{"/manual", "/search?q=a%26b"}) {
            FunctionApprovalService service = service("");
            service.hold("GET", target, "");
            String next = service.approve().getLocation().toString();
            assertTrue(next.startsWith(target + (target.contains("?") ? "&" : "?")));
            String token = next.substring(next.indexOf("_posApproval=") + 13);
            assertNull(service.continuation("wrong"));
            assertNotNull(service.continuation(token));
            assertFalse(service.consume("POST", target, "", token));
            assertFalse(service.consume("GET", "/other", "", token));
            assertFalse(service.consume("GET", target, "changed=yes", token));
            assertTrue(service.consume("GET", target, "", token));
            assertFalse(service.consume("GET", target, "", token));
        }
    }

    /** Form values, including repeated fields, survive approval; changing the sale revokes its usefulness. */
    @Test
    void postFormAndCartBinding() {
        FunctionApprovalService service = service("");
        service.state.ticket.addItem("EAN", null, "MILK", BigDecimal.ONE, BigDecimal.ONE, null);
        service.state.payment.addPayment("CASH", BigDecimal.ONE);
        service.hold("POST", "/sale-tax-id", "taxId=123&tag=a&tag=b&empty");
        String next = service.approve().getLocation().toString();
        assertTrue(next.startsWith("/approved-function?token="));
        String token = next.substring(next.indexOf("token=") + 6);
        assertEquals(List.of("a", "b"), service.fields(service.continuation(token).body()).get("tag"));
        assertEquals(List.of(""), service.fields("empty").get("empty"));
        service.state.ticket.customerTaxId = "different";
        assertNull(service.continuation(token));
        service.state.ticket.customerTaxId = null;
        assertFalse(service.consume("POST", "/sale-tax-id", "taxId=999", token));
        assertTrue(service.consume("POST", "/sale-tax-id", "taxId=123&tag=a&tag=b&empty=", token));
        service.hold("GET", "/manual", "");service.approve();
        service.hold("GET", "/another", "");
        assertNull(service.continuation(token));
    }

    /**
     * BO-04-02-01: a REQUIRED rule may name its validating profiles after the
     * mode; the last matching REQUIRED rule gives them, malformed rules and
     * other methods do not.
     */
    @Test
    void requiredRulesNameTheirProfiles() {
        FunctionApprovalService service = service("bad\nGET=REQUIRED:X\nPOST /action/pay-tender=REQUIRED:Chef de caisse;Gérant\n"
                + "POST /action/pay/*=REQUIRED\nGET /action/pay-tender=REQUIRED:Autre\nPOST /free=NONE:Y");
        assertTrue(service.required("POST", "/action/pay-tender"));
        assertEquals(List.of("Chef de caisse", "Gérant"), service.profiles("POST", "/action/pay-tender/"));
        assertTrue(service.profiles("POST", "/action/pay/x").isEmpty());
        assertTrue(service.profiles("POST", "/action/pay").isEmpty());
        assertTrue(service.profiles("POST", "/free").isEmpty());
        assertTrue(service.profiles("POST", "/other").isEmpty());
        assertFalse(service.required("POST", "/free"));
    }
}
