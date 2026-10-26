package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.pos.service.PosSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChequeGuaranteeClient}, the seam to the
 * cheque-guarantee service (BO-03-02-24).
 *
 * <p>The HTTP client is a mock wired on the package-private field, so every
 * answer of the service is played without a network: an approval, a refusal,
 * a non-2xx status on both sides of the range, an empty and an absent body,
 * an unreachable service and an interrupted wait. Both legs of the
 * configuration test and both sources of the address are covered.
 */
class ChequeGuaranteeClientTest {

    /**
     * Builds a client over administered values and a configured address.
     *
     * @param provider the administered provider
     * @param url the administered address
     * @param configured the register's configured address, or null for none
     * @return the client, its HTTP client a mock
     */
    private ChequeGuaranteeClient client(String provider, String url, String configured) {
        ChequeGuaranteeClient client = new ChequeGuaranteeClient();
        client.posSettingsService = mock(PosSettingsService.class);
        when(client.posSettingsService.chequeGuaranteeProvider()).thenReturn(provider);
        when(client.posSettingsService.chequeGuaranteeUrl()).thenReturn(url);
        when(client.posSettingsService.chequeGuaranteeTimeoutMillis()).thenReturn(1000);
        client.configuredUrl = Optional.ofNullable(configured);
        client.objectMapper = new ObjectMapper();
        client.httpClient = mock(HttpClient.class);
        return client;
    }

    /**
     * Makes the mocked HTTP client answer a status and a body.
     *
     * @param client the client
     * @param status the HTTP status
     * @param body the body, possibly null
     * @throws Exception never, the mock does not throw
     */
    @SuppressWarnings("unchecked")
    private void answer(ChequeGuaranteeClient client, int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    /**
     * The service is configured only with a provider AND an address, the
     * administered address first, the configured one otherwise.
     */
    @Test
    void theServiceNeedsAProviderAndAnAddress() {
        assertFalse(client("", "http://g", null).isConfigured());
        assertFalse(client("SIM", "", null).isConfigured());
        assertTrue(client("SIM", "http://g", null).isConfigured());
        ChequeGuaranteeClient configured = client("SIM", "", " http://local ");
        assertTrue(configured.isConfigured());
        assertEquals("http://local", configured.url());
        assertEquals("http://g", client("SIM", "http://g", "http://local").url());
    }

    /**
     * An approval carries its authorization number and the provider, and the
     * request goes to the authorize endpoint of the administered address.
     *
     * @throws Exception never
     */
    @Test
    @SuppressWarnings("unchecked")
    void anApprovalCarriesItsAuthorization() throws Exception {
        ChequeGuaranteeClient client = client("SIM", "http://g", null);
        answer(client, 200, "{\"approved\":true,\"authorization\":\"GAR000001\",\"message\":\"OK\",\"extra\":1}");
        ChequeGuaranteeClient.Verdict verdict = client.authorize("12", new BigDecimal("20.00"), "123");
        assertEquals(ChequeGuaranteeClient.Status.APPROVED, verdict.status());
        assertEquals("GAR000001", verdict.authorization());
        assertEquals("OK", verdict.message());
        assertEquals("SIM", verdict.provider());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client.httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://g/api/cheque-guarantee/authorize", request.getValue().uri().toString());
    }

    /**
     * A refusal carries the provider's wording and no authorization.
     *
     * @throws Exception never
     */
    @Test
    void aRefusalCarriesTheWording() throws Exception {
        ChequeGuaranteeClient client = client("SIM", "http://g", null);
        answer(client, 200, "{\"approved\":false,\"message\":\"CHEQUE EN OPPOSITION\"}");
        ChequeGuaranteeClient.Verdict verdict = client.authorize("12", BigDecimal.TEN, null);
        assertEquals(ChequeGuaranteeClient.Status.REFUSED, verdict.status());
        assertNull(verdict.authorization());
        assertEquals("CHEQUE EN OPPOSITION", verdict.message());
    }

    /**
     * A status out of the 2xx range on either side, an empty and an absent
     * body all make the service unavailable.
     *
     * @throws Exception never
     */
    @Test
    void anUnusableAnswerMakesTheServiceUnavailable() throws Exception {
        for (int status : new int[] {199, 503}) {
            ChequeGuaranteeClient client = client("SIM", "http://g", null);
            answer(client, status, "{\"approved\":true}");
            assertEquals(ChequeGuaranteeClient.Status.UNAVAILABLE,
                    client.authorize("1", BigDecimal.ONE, null).status());
        }
        for (String body : new String[] {null, "  "}) {
            ChequeGuaranteeClient client = client("SIM", "http://g", null);
            answer(client, 200, body);
            assertEquals(ChequeGuaranteeClient.Status.UNAVAILABLE,
                    client.authorize("1", BigDecimal.ONE, null).status());
        }
    }

    /**
     * An unreachable service and an interrupted wait are unavailable, the
     * interruption being restored on the thread.
     *
     * @throws Exception never
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFailedExchangeMakesTheServiceUnavailable() throws Exception {
        ChequeGuaranteeClient down = client("SIM", "http://g", null);
        when(down.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("down"));
        assertEquals(ChequeGuaranteeClient.Status.UNAVAILABLE, down.authorize("1", BigDecimal.ONE, null).status());
        ChequeGuaranteeClient interrupted = client("SIM", "http://g", null);
        when(interrupted.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("stop"));
        assertEquals(ChequeGuaranteeClient.Status.UNAVAILABLE,
                interrupted.authorize("1", BigDecimal.ONE, null).status());
        assertTrue(Thread.interrupted());
    }
}
