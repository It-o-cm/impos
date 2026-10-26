package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NexoHubClient} (LC-02-09-01 to -03, LC-05-03-01,
 * LC-07-11-01), targeting 100% branch coverage.
 *
 * <p>The HTTP client is a Mockito mock answering canned Nexo Retailer responses;
 * the mapper is real, so the requests are really written and the responses
 * really read. Branch enumeration: the hub configured, absent or blank; the
 * transport answering out of range on both sides, with a null, a blank or an
 * unreadable body, with no response of the expected name, interrupted or
 * failing; each of the six exchanges accepted, refused with and without an
 * error condition, and unavailable; the optional request members (provider,
 * product code, a null code, a null reference); and the response members absent
 * or not of the expected type.
 */
class NexoHubClientTest {

    /** The hub's base URL used across the tests. */
    private static final String URL = "http://hub.test";

    /**
     * Collects what a request body publisher sends.
     */
    static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {

        /** The bytes received. */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /** Completed with the body once the publisher is done. */
        final CompletableFuture<String> body = new CompletableFuture<>();

        /**
         * Asks for everything.
         *
         * @param subscription the subscription
         */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        /**
         * Keeps a chunk.
         *
         * @param item the chunk
         */
        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            out.write(bytes, 0, bytes.length);
        }

        /**
         * Fails the body.
         *
         * @param throwable the failure
         */
        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        /**
         * Completes the body.
         */
        @Override
        public void onComplete() {
            body.complete(out.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * Builds a client configured on the test hub over a mocked transport.
     *
     * @param http the mocked transport
     * @return the client
     */
    private NexoHubClient client(HttpClient http) {
        NexoHubClient client = new NexoHubClient();
        client.hubUrl = Optional.of(URL);
        client.poiId = "NEXO-HUB";
        client.saleId = "POS01";
        client.objectMapper = new ObjectMapper();
        client.httpClient = http;
        return client;
    }

    /**
     * A mocked response.
     *
     * @param status the HTTP status
     * @param body the body
     * @return the response
     */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    /**
     * A transport answering one body with HTTP 200.
     *
     * @param body the body
     * @return the transport
     * @throws Exception never, the mock's signature
     */
    private HttpClient answering(String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = response(200, body);
        when(http.send(any(), any())).thenAnswer(call -> response);
        return http;
    }

    /**
     * Wraps a response body under its name in the Nexo envelope.
     *
     * @param name the response name
     * @param body the response body
     * @return the envelope
     */
    private static String envelope(String name, String body) {
        return "{\"SaleToPOIResponse\":{\"MessageHeader\":{\"MessageType\":\"Response\"},\"" + name + "\":" + body + "}}";
    }

    /**
     * The request the transport was given, read back as JSON.
     *
     * @param http the transport
     * @return the request envelope
     * @throws Exception when the body cannot be read
     */
    private JsonNode sent(HttpClient http) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals(URL + "/api/nexo", request.uri().toString());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
        BodyCollector collector = new BodyCollector();
        request.bodyPublisher().orElseThrow().subscribe(collector);
        return new ObjectMapper().readTree(collector.body.get(5, TimeUnit.SECONDS));
    }

    /**
     * The hub is configured only by a non-blank URL.
     */
    @Test
    void theHubIsConfiguredByANonBlankUrl() {
        NexoHubClient client = client(mock(HttpClient.class));
        assertTrue(client.isConfigured());
        client.hubUrl = Optional.of("  ");
        assertFalse(client.isConfigured());
        client.hubUrl = Optional.empty();
        assertFalse(client.isConfigured());
    }

    /**
     * Without a hub nothing is sent and every exchange is unavailable.
     */
    @Test
    void withoutAHubEveryExchangeIsUnavailable() {
        HttpClient http = mock(HttpClient.class);
        NexoHubClient client = client(http);
        client.hubUrl = Optional.empty();
        assertTrue(client.activate("R", null, null, "C", BigDecimal.TEN).unavailable());
        assertTrue(client.balance("C").unavailable());
        assertTrue(client.pay("R", "C", BigDecimal.TEN).unavailable());
        assertTrue(client.reversePayment("R", "T", BigDecimal.TEN).unavailable());
        verifyNoInteractions(http);
    }

    /**
     * An activation writes the full Nexo request — header, sale data, the
     * stored-value data with its provider, product code, amount and account —
     * and reads the accepted answer.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void anActivationIsWrittenAndRead() throws Exception {
        HttpClient http = answering(envelope("StoredValueResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"POIData\":{\"POITransactionID\":{\"TransactionID\":\"HUB1\"}},"
                + "\"StoredValueResult\":[{\"ItemAmount\":25.00,\"HostTransactionID\":{\"TransactionID\":\"S1\"},"
                + "\"StoredValueAccountStatus\":{\"CurrentBalance\":25.00,"
                + "\"StoredValueAccountID\":{\"StoredValueProvider\":\"ILLICADO\"}}}]}"));
        NexoHubClient.HubResult result = client(http).activate("42-uid", "ILLICADO", "3560", " 9001 ",
                new BigDecimal("25.00"));
        assertTrue(result.success());
        assertNull(result.errorCondition());
        assertEquals("HUB1", result.transactionId());
        assertEquals(0, new BigDecimal("25.00").compareTo(result.amount()));
        assertEquals(0, new BigDecimal("25.00").compareTo(result.balance()));
        assertEquals("ILLICADO", result.provider());
        assertEquals("S1", result.approvalCode());
        assertFalse(result.unavailable());
        JsonNode request = sent(http).path("SaleToPOIRequest");
        JsonNode header = request.path("MessageHeader");
        assertEquals("3.1", header.path("ProtocolVersion").asText());
        assertEquals("Service", header.path("MessageClass").asText());
        assertEquals("StoredValue", header.path("MessageCategory").asText());
        assertEquals("Request", header.path("MessageType").asText());
        assertEquals("1", header.path("ServiceID").asText());
        assertEquals("POS01", header.path("SaleID").asText());
        assertEquals("NEXO-HUB", header.path("POIID").asText());
        JsonNode body = request.path("StoredValueRequest");
        assertEquals("42-uid", body.path("SaleData").path("SaleTransactionID").path("TransactionID").asText());
        assertFalse(body.path("SaleData").path("SaleTransactionID").path("TimeStamp").asText().isEmpty());
        JsonNode data = body.path("StoredValueData");
        assertEquals("Activate", data.path("StoredValueTransactionType").asText());
        assertEquals(0, new BigDecimal("25.00").compareTo(data.path("ItemAmount").decimalValue()));
        assertEquals("EUR", data.path("Currency").asText());
        assertEquals("3560", data.path("ProductCode").asText());
        JsonNode account = data.path("StoredValueAccountID");
        assertEquals("GiftCard", account.path("StoredValueAccountType").asText());
        assertEquals("ILLICADO", account.path("StoredValueProvider").asText());
        assertEquals("BarCode", account.path("IdentificationType").asText());
        assertEquals("Scanned", account.path("EntryMode").path(0).asText());
        assertEquals("9001", account.path("StoredValueID").asText());
    }

    /**
     * An activation without provider nor product code leaves both out; a null
     * code and a null reference are sent empty; a result with no member reads
     * as nulls.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void anActivationWithoutOptionalMembersLeavesThemOut() throws Exception {
        HttpClient http = answering(envelope("StoredValueResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"POIData\":{\"POITransactionID\":{\"TransactionID\":\" \"}},"
                + "\"StoredValueResult\":[{\"ItemAmount\":\"x\",\"StoredValueAccountStatus\":{\"CurrentBalance\":\"\"}}]}"));
        NexoHubClient.HubResult result = client(http).activate(null, null, null, null, BigDecimal.ONE);
        assertTrue(result.success());
        assertNull(result.transactionId());
        assertNull(result.amount());
        assertNull(result.balance());
        assertNull(result.provider());
        assertNull(result.approvalCode());
        JsonNode body = sent(http).path("SaleToPOIRequest").path("StoredValueRequest");
        assertEquals("", body.path("SaleData").path("SaleTransactionID").path("TransactionID").asText());
        JsonNode data = body.path("StoredValueData");
        assertTrue(data.path("ProductCode").isMissingNode());
        assertTrue(data.path("StoredValueAccountID").path("StoredValueProvider").isMissingNode());
        assertEquals("", data.path("StoredValueAccountID").path("StoredValueID").asText());
    }

    /**
     * A refusal carries its condition and the hub's words; one without a
     * condition is a plain refusal.
     *
     * @throws Exception never
     */
    @Test
    void aRefusalCarriesItsConditionAndWords() throws Exception {
        NexoHubClient refused = client(answering(envelope("StoredValueResponse",
                "{\"Response\":{\"Result\":\"Failure\",\"ErrorCondition\":\"NotAllowed\","
                        + "\"AdditionalResponse\":\"DEJA ACTIVE\"}}")));
        NexoHubClient.HubResult result = refused.activate("R", "P", null, "C", BigDecimal.ONE);
        assertFalse(result.success());
        assertEquals("NotAllowed", result.errorCondition());
        assertEquals("DEJA ACTIVE", result.message());
        assertFalse(result.unavailable());
        NexoHubClient plain = client(answering(envelope("StoredValueResponse",
                "{\"Response\":{\"Result\":\"Failure\"}}")));
        NexoHubClient.HubResult bare = plain.unload("R", "P", "C", BigDecimal.ONE);
        assertEquals("Refusal", bare.errorCondition());
        assertNull(bare.message());
    }

    /**
     * Every transport failure is the hub's unavailability: a status below and
     * above the 2xx range, a null, blank or unreadable body, a response not of
     * the expected name, an interruption and an I/O failure.
     *
     * @throws Exception never
     */
    @Test
    void everyTransportFailureIsUnavailability() throws Exception {
        for (HttpResponse<String> response : java.util.List.of(response(199, "{}"), response(300, "{}"),
                response(200, null), response(200, "  "), response(200, "not json"),
                response(200, envelope("OtherResponse", "{}")),
                response(200, envelope("StoredValueResponse", "\"text\"")))) {
            HttpClient http = mock(HttpClient.class);
            when(http.send(any(), any())).thenAnswer(call -> response);
            assertTrue(client(http).reverseStoredValue("R", "T").unavailable());
        }
        HttpClient interrupted = mock(HttpClient.class);
        when(interrupted.send(any(), any())).thenThrow(new InterruptedException("stop"));
        assertTrue(client(interrupted).activate("R", null, null, "C", BigDecimal.ONE).unavailable());
        assertTrue(Thread.interrupted());
        HttpClient broken = mock(HttpClient.class);
        when(broken.send(any(), any())).thenThrow(new IOException("down"));
        assertTrue(client(broken).unload("R", null, "C", BigDecimal.ONE).unavailable());
    }

    /**
     * An unload and a reversal write their types, amount and original
     * transaction.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void anUnloadAndAReversalAreWritten() throws Exception {
        String accepted = envelope("StoredValueResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"StoredValueResult\":[{\"StoredValueAccountStatus\":{\"CurrentBalance\":0}}]}");
        HttpClient unloadHttp = answering(accepted);
        NexoHubClient.HubResult unloaded = client(unloadHttp).unload("R1", "P", "C1", new BigDecimal("12.50"));
        assertEquals(0, BigDecimal.ZERO.compareTo(unloaded.balance()));
        JsonNode unload = sent(unloadHttp).path("SaleToPOIRequest").path("StoredValueRequest").path("StoredValueData");
        assertEquals("Unload", unload.path("StoredValueTransactionType").asText());
        assertEquals(0, new BigDecimal("12.50").compareTo(unload.path("ItemAmount").decimalValue()));
        HttpClient reverseHttp = answering(accepted);
        assertTrue(client(reverseHttp).reverseStoredValue("R2", "HUB9").success());
        JsonNode reverse = sent(reverseHttp).path("SaleToPOIRequest").path("StoredValueRequest").path("StoredValueData");
        assertEquals("Reverse", reverse.path("StoredValueTransactionType").asText());
        assertEquals("HUB9", reverse.path("OriginalPOITransaction").path("POITransactionID")
                .path("TransactionID").asText());
    }

    /**
     * A balance inquiry is written on the stored-value instrument and reads the
     * balance, the provider and its code; refused, it says so.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void aBalanceInquiryIsWrittenAndRead() throws Exception {
        HttpClient http = answering(envelope("BalanceInquiryResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"POIData\":{\"POITransactionID\":{\"TransactionID\":\"HUB2\"}},"
                + "\"PaymentAccountStatus\":{\"CurrentBalance\":30.00,"
                + "\"PaymentAcquirerData\":{\"AcquirerID\":\"ILLICADO\",\"ApprovalCode\":\"B2\"}}}"));
        NexoHubClient.HubResult result = client(http).balance("6299");
        assertTrue(result.success());
        assertEquals(0, new BigDecimal("30.00").compareTo(result.balance()));
        assertEquals("ILLICADO", result.provider());
        assertEquals("B2", result.approvalCode());
        assertEquals("HUB2", result.transactionId());
        JsonNode request = sent(http).path("SaleToPOIRequest");
        assertEquals("BalanceInquiry", request.path("MessageHeader").path("MessageCategory").asText());
        JsonNode instrument = request.path("BalanceInquiryRequest").path("PaymentAccountReq")
                .path("PaymentInstrumentData");
        assertEquals("StoredValue", instrument.path("PaymentInstrumentType").asText());
        assertEquals("6299", instrument.path("StoredValueAccountID").path("StoredValueID").asText());
        NexoHubClient.HubResult refused = client(answering(envelope("BalanceInquiryResponse",
                "{\"Response\":{\"Result\":\"Failure\",\"ErrorCondition\":\"InvalidCard\"}}"))).balance("X");
        assertEquals("InvalidCard", refused.errorCondition());
    }

    /**
     * A payment is written with its amount and instrument and reads the
     * authorized amount, the acquirer and its code; refused, it says so.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void aPaymentIsWrittenAndRead() throws Exception {
        HttpClient http = answering(envelope("PaymentResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"POIData\":{\"POITransactionID\":{\"TransactionID\":\"HUB3\"}},"
                + "\"PaymentResult\":{\"AmountsResp\":{\"AuthorizedAmount\":12.40},"
                + "\"PaymentAcquirerData\":{\"AcquirerID\":\"ILLICADO\",\"ApprovalCode\":\"P3\"}}}"));
        NexoHubClient.HubResult result = client(http).pay("42-GC1", "6299", new BigDecimal("12.40"));
        assertTrue(result.success());
        assertEquals(0, new BigDecimal("12.40").compareTo(result.amount()));
        assertEquals("ILLICADO", result.provider());
        assertEquals("P3", result.approvalCode());
        assertEquals("HUB3", result.transactionId());
        JsonNode request = sent(http).path("SaleToPOIRequest");
        assertEquals("Payment", request.path("MessageHeader").path("MessageCategory").asText());
        JsonNode payment = request.path("PaymentRequest");
        assertEquals("42-GC1", payment.path("SaleData").path("SaleTransactionID").path("TransactionID").asText());
        assertEquals(0, new BigDecimal("12.40").compareTo(payment.path("PaymentTransaction").path("AmountsReq").path("RequestedAmount").decimalValue()));
        assertEquals("Normal", payment.path("PaymentData").path("PaymentType").asText());
        assertEquals("StoredValue", payment.path("PaymentData").path("PaymentInstrumentData")
                .path("PaymentInstrumentType").asText());
        NexoHubClient.HubResult refused = client(answering(envelope("PaymentResponse",
                "{\"Response\":{\"Result\":\"Failure\",\"ErrorCondition\":\"Refusal\"}}"))).pay("R", "X", BigDecimal.ONE);
        assertEquals("Refusal", refused.errorCondition());
    }

    /**
     * A payment reversal is written with its original transaction, reason and
     * amount and reads the reversed amount; refused, it says so.
     *
     * @throws Exception when the request cannot be read
     */
    @Test
    void aPaymentReversalIsWrittenAndRead() throws Exception {
        HttpClient http = answering(envelope("ReversalResponse", "{\"Response\":{\"Result\":\"Success\"},"
                + "\"POIData\":{\"POITransactionID\":{\"TransactionID\":\"HUB4\"}},\"ReversedAmount\":12.40}"));
        NexoHubClient.HubResult result = client(http).reversePayment("HUB3-R", "HUB3", new BigDecimal("12.40"));
        assertTrue(result.success());
        assertEquals(0, new BigDecimal("12.40").compareTo(result.amount()));
        assertEquals("HUB4", result.transactionId());
        JsonNode reversal = sent(http).path("SaleToPOIRequest").path("ReversalRequest");
        assertEquals("HUB3", reversal.path("OriginalPOITransaction").path("POITransactionID")
                .path("TransactionID").asText());
        assertEquals("MerchantCancel", reversal.path("ReversalReason").asText());
        assertEquals(0, new BigDecimal("12.40").compareTo(reversal.path("ReversedAmount").decimalValue()));
        NexoHubClient.HubResult refused = client(answering(envelope("ReversalResponse",
                "{\"Response\":{\"Result\":\"Failure\",\"ErrorCondition\":\"NotAllowed\"}}")))
                .reversePayment("R", "T", BigDecimal.ONE);
        assertEquals("NotAllowed", refused.errorCondition());
    }

    /**
     * Each exchange, the hub gone, is unavailable rather than failing.
     *
     * @throws Exception never
     */
    @Test
    void eachExchangeWithTheHubGoneIsUnavailable() throws Exception {
        HttpClient gone = mock(HttpClient.class);
        when(gone.send(any(), any())).thenThrow(new IOException("down"));
        NexoHubClient client = client(gone);
        assertTrue(client.balance("C").unavailable());
        assertTrue(client.pay("R", "C", BigDecimal.ONE).unavailable());
        assertTrue(client.reversePayment("R", "T", BigDecimal.ONE).unavailable());
        assertTrue(NexoHubClient.HubResult.failure(NexoHubClient.UNAVAILABLE, null).unavailable());
    }

    // --- BO-11-03-04: administered address and the acknowledgement ---

    /**
     * The administered address and POI win over the deployment properties; a
     * blank or absent administered value keeps them.
     *
     * @throws Exception when the request cannot be read back
     */
    @Test
    void theAdministeredAddressAndPoiWin() throws Exception {
        HttpClient http = answering(envelope("BalanceInquiryResponse", "{\"Response\":{\"Result\":\"Success\"}}"));
        NexoHubClient client = client(http);
        client.hubUrl = Optional.empty();
        client.posSettingsService = mock(com.intermarche.pos.service.PosSettingsService.class);
        when(client.posSettingsService.nexoHubUrl()).thenReturn(" " + URL + " ");
        when(client.posSettingsService.nexoPoiId()).thenReturn(" POI-ADM ");
        assertTrue(client.isConfigured());
        assertTrue(client.balance("C").success());
        assertEquals("POI-ADM", sent(http).path("SaleToPOIRequest").path("MessageHeader").path("POIID").asText());
        when(client.posSettingsService.nexoHubUrl()).thenReturn(" ");
        when(client.posSettingsService.nexoPoiId()).thenReturn(" ");
        assertFalse(client.isConfigured());
        assertEquals("NEXO-HUB", client.poi());
        when(client.posSettingsService.nexoHubUrl()).thenReturn(null);
        when(client.posSettingsService.nexoPoiId()).thenReturn(null);
        client.hubUrl = Optional.of(URL);
        assertEquals(URL, client.hubBase());
        assertEquals("NEXO-HUB", client.poi());
    }

    /**
     * The acknowledgement of a prepaid payment is a Payment of type Completion
     * naming the original transaction; accepted, refused or unavailable, the
     * answer says so.
     *
     * @throws Exception when the request cannot be read back
     */
    @Test
    void aPrepaidPaymentIsAcknowledged() throws Exception {
        HttpClient http = answering(envelope("PaymentResponse",
                "{\"Response\":{\"Result\":\"Success\"},\"POIData\":{\"POITransactionID\":{\"TransactionID\":\"T9\"}}}"));
        NexoHubClient.HubResult accepted = client(http).acknowledge("9-ACK", "T1", new BigDecimal("15.00"));
        assertTrue(accepted.success());
        assertEquals(new BigDecimal("15.00"), accepted.amount());
        JsonNode request = sent(http).path("SaleToPOIRequest");
        assertEquals("Payment", request.path("MessageHeader").path("MessageCategory").asText());
        JsonNode payment = request.path("PaymentRequest");
        assertEquals("Completion", payment.path("PaymentData").path("PaymentType").asText());
        assertEquals("T1", payment.path("PaymentTransaction").path("OriginalPOITransaction")
                .path("POITransactionID").path("TransactionID").asText());
        assertEquals(0, new BigDecimal("15.00").compareTo(
                payment.path("PaymentTransaction").path("AmountsReq").path("RequestedAmount").decimalValue()));
        HttpClient refusing = answering(envelope("PaymentResponse",
                "{\"Response\":{\"Result\":\"Failure\",\"ErrorCondition\":\"Refusal\"}}"));
        assertEquals("Refusal", client(refusing).acknowledge("9-ACK", "T1", BigDecimal.ONE).errorCondition());
        NexoHubClient none = client(mock(HttpClient.class));
        none.hubUrl = Optional.empty();
        assertTrue(none.acknowledge("9-ACK", "T1", BigDecimal.ONE).unavailable());
    }
}
