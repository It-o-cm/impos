package com.intermarche.pos.ui.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The register's side of the external PAYMENT HUB, spoken in the Nexo Retailer
 * protocol — the "Sale to POI" messages in their JSON form (LC-02-09-01 to -03,
 * LC-05-03-01, LC-07-11-01 and -03).
 *
 * <p>Six exchanges, each one request and its response under the same message
 * header ({@code ProtocolVersion}, {@code MessageClass} Service,
 * {@code MessageCategory}, {@code MessageType}, {@code ServiceID}, {@code SaleID},
 * {@code POIID}):
 * <ul>
 *   <li>{@code StoredValue} {@code Activate}: a dematerialised article sold is
 *       activated for its amount;</li>
 *   <li>{@code StoredValue} {@code Reverse}: an activation is undone, the line
 *       being cancelled in the transaction in progress;</li>
 *   <li>{@code StoredValue} {@code Unload}: a returned article is deactivated by
 *       unloading what it still carries;</li>
 *   <li>{@code BalanceInquiry}: what a card or an article still carries;</li>
 *   <li>{@code Payment} on a {@code StoredValue} instrument: a gift card pays;</li>
 *   <li>{@code Reversal}: a gift-card payment is given back.</li>
 * </ul>
 *
 * <p>The hub answers at {@code POST <pos.nexo.hub-url>/api/nexo}. Its absence is
 * the absence of the hub: {@link #isConfigured()} says so and the callers keep the
 * behaviour the register had without it. A hub that cannot be reached, answers
 * out of the 2xx range or says nothing readable is a FAILURE with the condition
 * {@code UnavailableService} — never an exception, and never a success: money
 * that the hub did not confirm did not move.
 */
@ApplicationScoped
public class NexoHubClient {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(NexoHubClient.class);

    /** The Nexo Retailer protocol version this register speaks. */
    public static final String PROTOCOL_VERSION = "3.1";

    /** The error condition of a hub the register could not reach or read. */
    public static final String UNAVAILABLE = "UnavailableService";

    /**
     * The message code of an accepted request that carried no words of its
     * own, the one a provider message table declares a success sentence under
     * (BO-03-08-07).
     */
    public static final String SUCCESS_CODE = "SUCCESS";

    /** The result the hub returns for an accepted request. */
    private static final String SUCCESS = "Success";

    /** The base URL of the payment hub; absent or blank = no hub. */
    @ConfigProperty(name = "pos.nexo.hub-url")
    Optional<String> hubUrl;

    /** The POI identifier the register addresses on the hub. */
    @ConfigProperty(name = "pos.nexo.poi-id", defaultValue = "NEXO-HUB")
    String poiId;

    /** The register identifier, sent as the Nexo {@code SaleID}. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String saleId;

    /** Builds the requests and reads the responses. */
    @Inject
    ObjectMapper objectMapper;

    /**
     * The administered address and POI of the NEXO Retailer server
     * (BO-11-03-04), which win over the deployment properties; null outside
     * the container, the properties then apply alone.
     */
    @Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** Shared HTTP client toward the hub. */
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** The source of the message identifiers ({@code ServiceID}). */
    private final AtomicLong serviceCounter = new AtomicLong();

    /**
     * What the hub answered to one request.
     *
     * @param success true when the hub accepted the request
     * @param errorCondition the Nexo error condition of a refusal, or null
     * @param message the hub's own words on a refusal ({@code AdditionalResponse}), or null
     * @param transactionId the hub's transaction identifier ({@code POITransactionID}), or null
     * @param amount the amount the hub acted on (item, authorized or reversed amount), or null
     * @param balance the balance the account carries after the request, or null
     * @param provider the provider of the stored-value account, as the hub names it, or null
     * @param approvalCode the provider's own code for the operation, or null
     */
    public record HubResult(boolean success, String errorCondition, String message,
                            String transactionId, BigDecimal amount, BigDecimal balance,
                            String provider, String approvalCode) {

        /**
         * A refusal or a failure.
         *
         * @param condition the error condition
         * @param message the hub's words, or null
         * @return the failed result
         */
        public static HubResult failure(String condition, String message) {
            return new HubResult(false, condition, message, null, null, null, null, null);
        }

        /**
         * Whether the failure is the hub's absence rather than its refusal.
         *
         * @return true when the hub could not be reached or read
         */
        public boolean unavailable() {
            return UNAVAILABLE.equals(errorCondition);
        }

        /**
         * The CODES a provider message table may know this answer by
         * (BO-03-08-06, -07), in the order they are tried: the provider's own
         * words — what a provider such as Illicado sends, {@code ERROR CODE 4
         * ITEM} — then the Nexo error condition, then {@link #SUCCESS_CODE} for
         * an accepted request that said nothing else.
         *
         * @return the candidate codes, never empty for an accepted request
         */
        public java.util.List<String> messageCodes() {
            java.util.List<String> codes = new java.util.ArrayList<>();
            if (message != null) {
                codes.add(message.trim());
            }
            if (errorCondition != null) {
                codes.add(errorCondition.trim());
            }
            if (success) {
                codes.add(SUCCESS_CODE);
            }
            return codes;
        }

        /**
         * What the CASHIER reads for this answer (BO-03-08-06): the shop's
         * screen text for the first of its codes the shop declared for this
         * provider, or null when the shop declared none.
         *
         * @param provider the provider the request went to, or null
         * @return the shop's sentence, or null to keep the register's own message
         */
        public String screenText(String provider) {
            for (String code : messageCodes()) {
                String text = com.intermarche.pos.domain.setting.ProviderMessage
                        .screenTextFor(provider, code, null);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }

        /**
         * What the TICKET prints for this answer (BO-03-08-07): the shop's
         * ticket text for the first of its codes the shop declared for this
         * provider, or null — a provider's own answer never reaches the paper.
         *
         * @param provider the provider the request went to, or null
         * @return the shop's sentence, or null when nothing is printed
         */
        public String ticketText(String provider) {
            for (String code : messageCodes()) {
                String text = com.intermarche.pos.domain.setting.ProviderMessage
                        .ticketTextFor(provider, code);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
    }

    /**
     * Whether a hub is administered for this register.
     *
     * @return true when the hub URL is set
     */
    public boolean isConfigured() {
        return !hubBase().isEmpty();
    }

    /**
     * The base URL of the NEXO Retailer server (BO-11-03-04): the address the
     * back office administers, the deployment property otherwise. Read on every
     * exchange, so an address changed in the back office applies at the next
     * one.
     *
     * @return the base URL, trimmed, empty when neither is set
     */
    String hubBase() {
        String administered = posSettingsService == null ? "" : posSettingsService.nexoHubUrl();
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return hubUrl.orElse("").trim();
    }

    /**
     * The POI identifier addressed on the server (BO-11-03-04): the
     * administered one, the deployment property otherwise.
     *
     * @return the POI identifier
     */
    String poi() {
        String administered = posSettingsService == null ? "" : posSettingsService.nexoPoiId();
        return administered != null && !administered.isBlank() ? administered.trim() : poiId;
    }

    /**
     * Acknowledges a prepaid-card payment the server validated, once the
     * ticket it settled is closed (BO-11-03-04): a {@code Payment} request of
     * type {@code Completion} naming the original transaction, so the provider
     * books the debit as final.
     *
     * @param saleReference the register's reference of the closed sale
     * @param originalTransactionId the server's identifier of the validated payment
     * @param amount the amount the payment settled
     * @return the server's answer
     */
    public HubResult acknowledge(String saleReference, String originalTransactionId, BigDecimal amount) {
        LOGGER.info("Entering method acknowledge with saleReference: " + saleReference);
        ObjectNode request = objectMapper.createObjectNode();
        request.set("SaleData", saleData(saleReference));
        ObjectNode transaction = request.putObject("PaymentTransaction");
        ObjectNode amounts = transaction.putObject("AmountsReq");
        amounts.put("Currency", "EUR");
        amounts.put("RequestedAmount", amount);
        transaction.putObject("OriginalPOITransaction").putObject("POITransactionID")
                .put("TransactionID", originalTransactionId);
        request.putObject("PaymentData").put("PaymentType", "Completion");
        JsonNode response = exchange("Payment", "PaymentRequest", request, "PaymentResponse");
        if (response == null) {
            LOGGER.info("Exiting method acknowledge (hub unavailable)");
            return HubResult.failure(UNAVAILABLE, null);
        }
        HubResult refusal = refusal(response);
        if (refusal != null) {
            LOGGER.info("Exiting method acknowledge (refused)");
            return refusal;
        }
        LOGGER.info("Exiting method acknowledge");
        return new HubResult(true, null, words(response), transactionId(response), amount, null, null, null);
    }

    /**
     * Activates a dematerialised article for its amount (LC-02-09-01).
     *
     * @param saleReference the register's reference of the operation
     * @param provider the provider that owes the activation, or null
     * @param productCode the article code, or null
     * @param cardId the unique code of the article (its activation code)
     * @param amount the amount to activate
     * @return the hub's answer
     */
    public HubResult activate(String saleReference, String provider, String productCode,
                              String cardId, BigDecimal amount) {
        LOGGER.info("Entering method activate with saleReference: " + saleReference);
        ObjectNode data = storedValueData("Activate", provider, cardId);
        data.put("ItemAmount", amount);
        data.put("Currency", "EUR");
        if (productCode != null) {
            data.put("ProductCode", productCode);
        }
        HubResult result = storedValue(saleReference, data);
        LOGGER.info("Exiting method activate (" + result.success() + ")");
        return result;
    }

    /**
     * Deactivates a returned article by unloading what it still carries
     * (LC-05-03-01).
     *
     * @param saleReference the register's reference of the operation
     * @param provider the provider of the article, or null
     * @param cardId the unique code of the article
     * @param amount the amount to unload
     * @return the hub's answer
     */
    public HubResult unload(String saleReference, String provider, String cardId, BigDecimal amount) {
        LOGGER.info("Entering method unload with saleReference: " + saleReference);
        ObjectNode data = storedValueData("Unload", provider, cardId);
        data.put("ItemAmount", amount);
        data.put("Currency", "EUR");
        HubResult result = storedValue(saleReference, data);
        LOGGER.info("Exiting method unload (" + result.success() + ")");
        return result;
    }

    /**
     * Undoes a stored-value operation of the hub — an activation the operator
     * cancels, an unload a refund could not complete (LC-02-09-02).
     *
     * @param saleReference the register's reference of the operation
     * @param originalTransactionId the hub's identifier of the operation to undo
     * @return the hub's answer
     */
    public HubResult reverseStoredValue(String saleReference, String originalTransactionId) {
        LOGGER.info("Entering method reverseStoredValue with saleReference: " + saleReference);
        ObjectNode data = objectMapper.createObjectNode();
        data.put("StoredValueTransactionType", "Reverse");
        data.putObject("OriginalPOITransaction").putObject("POITransactionID")
                .put("TransactionID", originalTransactionId);
        HubResult result = storedValue(saleReference, data);
        LOGGER.info("Exiting method reverseStoredValue (" + result.success() + ")");
        return result;
    }

    /**
     * Asks what a card or an article still carries (LC-05-03-01, LC-07-11-01).
     *
     * @param cardId the unique code of the card or article
     * @return the hub's answer, its balance carried in {@link HubResult#balance()}
     */
    public HubResult balance(String cardId) {
        LOGGER.info("Entering method balance");
        ObjectNode request = objectMapper.createObjectNode();
        ObjectNode instrument = request.putObject("PaymentAccountReq").putObject("PaymentInstrumentData");
        instrument.put("PaymentInstrumentType", "StoredValue");
        instrument.set("StoredValueAccountID", accountId(null, cardId));
        JsonNode response = exchange("BalanceInquiry", "BalanceInquiryRequest", request,
                "BalanceInquiryResponse");
        if (response == null) {
            LOGGER.info("Exiting method balance (hub unavailable)");
            return HubResult.failure(UNAVAILABLE, null);
        }
        HubResult refusal = refusal(response);
        if (refusal != null) {
            LOGGER.info("Exiting method balance (refused)");
            return refusal;
        }
        JsonNode status = response.path("PaymentAccountStatus");
        LOGGER.info("Exiting method balance");
        return new HubResult(true, null, words(response), transactionId(response), null,
                decimal(status.path("CurrentBalance")),
                text(status.path("PaymentAcquirerData").path("AcquirerID")),
                text(status.path("PaymentAcquirerData").path("ApprovalCode")));
    }

    /**
     * Pays with a gift card for the amount the operator chose (LC-07-11-01).
     *
     * @param saleReference the register's reference of the operation
     * @param cardId the unique code of the card
     * @param amount the amount requested
     * @return the hub's answer, the authorized amount in {@link HubResult#amount()}
     */
    public HubResult pay(String saleReference, String cardId, BigDecimal amount) {
        LOGGER.info("Entering method pay with saleReference: " + saleReference);
        ObjectNode request = objectMapper.createObjectNode();
        request.set("SaleData", saleData(saleReference));
        ObjectNode amounts = request.putObject("PaymentTransaction").putObject("AmountsReq");
        amounts.put("Currency", "EUR");
        amounts.put("RequestedAmount", amount);
        ObjectNode paymentData = request.putObject("PaymentData");
        paymentData.put("PaymentType", "Normal");
        ObjectNode instrument = paymentData.putObject("PaymentInstrumentData");
        instrument.put("PaymentInstrumentType", "StoredValue");
        instrument.set("StoredValueAccountID", accountId(null, cardId));
        JsonNode response = exchange("Payment", "PaymentRequest", request, "PaymentResponse");
        if (response == null) {
            LOGGER.info("Exiting method pay (hub unavailable)");
            return HubResult.failure(UNAVAILABLE, null);
        }
        HubResult refusal = refusal(response);
        if (refusal != null) {
            LOGGER.info("Exiting method pay (refused)");
            return refusal;
        }
        JsonNode result = response.path("PaymentResult");
        JsonNode acquirer = result.path("PaymentAcquirerData");
        LOGGER.info("Exiting method pay");
        return new HubResult(true, null, words(response), transactionId(response),
                decimal(result.path("AmountsResp").path("AuthorizedAmount")), null,
                text(acquirer.path("AcquirerID")), text(acquirer.path("ApprovalCode")));
    }

    /**
     * Gives a gift-card payment back (LC-07-11-01): the payment is cancelled
     * before the sale closes.
     *
     * @param saleReference the register's reference of the operation
     * @param originalTransactionId the hub's identifier of the payment
     * @param amount the amount to give back
     * @return the hub's answer, the reversed amount in {@link HubResult#amount()}
     */
    public HubResult reversePayment(String saleReference, String originalTransactionId,
                                    BigDecimal amount) {
        LOGGER.info("Entering method reversePayment with saleReference: " + saleReference);
        ObjectNode request = objectMapper.createObjectNode();
        request.putObject("OriginalPOITransaction").putObject("POITransactionID")
                .put("TransactionID", originalTransactionId);
        request.put("ReversalReason", "MerchantCancel");
        request.put("ReversedAmount", amount);
        request.set("SaleData", saleData(saleReference));
        JsonNode response = exchange("Reversal", "ReversalRequest", request, "ReversalResponse");
        if (response == null) {
            LOGGER.info("Exiting method reversePayment (hub unavailable)");
            return HubResult.failure(UNAVAILABLE, null);
        }
        HubResult refusal = refusal(response);
        if (refusal != null) {
            LOGGER.info("Exiting method reversePayment (refused)");
            return refusal;
        }
        LOGGER.info("Exiting method reversePayment");
        return new HubResult(true, null, words(response), transactionId(response),
                decimal(response.path("ReversedAmount")), null, null, null);
    }

    /**
     * Sends one {@code StoredValueRequest} and reads its result.
     *
     * @param saleReference the register's reference of the operation
     * @param data the {@code StoredValueData} of the request
     * @return the hub's answer
     */
    private HubResult storedValue(String saleReference, ObjectNode data) {
        ObjectNode request = objectMapper.createObjectNode();
        request.set("SaleData", saleData(saleReference));
        request.set("StoredValueData", data);
        JsonNode response = exchange("StoredValue", "StoredValueRequest", request,
                "StoredValueResponse");
        if (response == null) {
            return HubResult.failure(UNAVAILABLE, null);
        }
        HubResult refusal = refusal(response);
        if (refusal != null) {
            return refusal;
        }
        JsonNode result = response.path("StoredValueResult").path(0);
        JsonNode status = result.path("StoredValueAccountStatus");
        return new HubResult(true, null, words(response), transactionId(response),
                decimal(result.path("ItemAmount")), decimal(status.path("CurrentBalance")),
                text(status.path("StoredValueAccountID").path("StoredValueProvider")),
                text(result.path("HostTransactionID").path("TransactionID")));
    }

    /**
     * The {@code StoredValueData} of an operation on an account.
     *
     * @param type the stored-value transaction type
     * @param provider the provider of the account, or null
     * @param cardId the unique code of the account
     * @return the data node
     */
    private ObjectNode storedValueData(String type, String provider, String cardId) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("StoredValueTransactionType", type);
        data.set("StoredValueAccountID", accountId(provider, cardId));
        return data;
    }

    /**
     * The identification of a stored-value account.
     *
     * @param provider the provider of the account, or null
     * @param cardId the unique code of the account
     * @return the account node
     */
    private ObjectNode accountId(String provider, String cardId) {
        ObjectNode account = objectMapper.createObjectNode();
        account.put("StoredValueAccountType", "GiftCard");
        if (provider != null) {
            account.put("StoredValueProvider", provider);
        }
        account.put("IdentificationType", "BarCode");
        account.putArray("EntryMode").add("Scanned");
        account.put("StoredValueID", cardId == null ? "" : cardId.trim());
        return account;
    }

    /**
     * The {@code SaleData} naming the register's side of the operation.
     *
     * @param saleReference the register's reference of the operation
     * @return the sale data node
     */
    private ObjectNode saleData(String saleReference) {
        ObjectNode saleData = objectMapper.createObjectNode();
        ObjectNode transaction = saleData.putObject("SaleTransactionID");
        transaction.put("TransactionID", saleReference == null ? "" : saleReference);
        transaction.put("TimeStamp", OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return saleData;
    }

    /**
     * Sends a request under its message header and returns the body of the
     * response, or null when the hub could not be reached or read.
     *
     * @param category the Nexo message category
     * @param requestName the name of the request body
     * @param request the request body
     * @param responseName the name of the expected response body
     * @return the response body, or null
     */
    JsonNode exchange(String category, String requestName, ObjectNode request, String responseName) {
        if (!isConfigured()) {
            return null;
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            ObjectNode message = envelope.putObject("SaleToPOIRequest");
            ObjectNode header = message.putObject("MessageHeader");
            header.put("ProtocolVersion", PROTOCOL_VERSION);
            header.put("MessageClass", "Service");
            header.put("MessageCategory", category);
            header.put("MessageType", "Request");
            header.put("ServiceID", String.valueOf(serviceCounter.incrementAndGet() % 10_000_000_000L));
            header.put("SaleID", saleId);
            header.put("POIID", poi());
            message.set(requestName, request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(hubBase() + "/api/nexo"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(envelope)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().isBlank()) {
                LOGGER.warnf("Hub de paiement : reponse %d a %s", response.statusCode(), category);
                return null;
            }
            JsonNode body = objectMapper.readTree(response.body()).path("SaleToPOIResponse").path(responseName);
            return body.isObject() ? body : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.warnf("Hub de paiement injoignable (%s) : %s", category, e.getMessage());
            return null;
        }
    }

    /**
     * The refusal a response carries, or null when the hub accepted.
     *
     * @param response the response body
     * @return the failed result, or null
     */
    private HubResult refusal(JsonNode response) {
        JsonNode answer = response.path("Response");
        if (SUCCESS.equals(text(answer.path("Result")))) {
            return null;
        }
        String condition = text(answer.path("ErrorCondition"));
        return HubResult.failure(condition == null ? "Refusal" : condition,
                text(answer.path("AdditionalResponse")));
    }

    /**
     * The provider's own WORDS on an accepted request ({@code AdditionalResponse}),
     * which a provider message table may translate (BO-03-08-07).
     *
     * @param response the response body
     * @return the words, or null when the hub carried none
     */
    private static String words(JsonNode response) {
        return text(response.path("Response").path("AdditionalResponse"));
    }

    /**
     * The hub's identifier of the transaction a response answers.
     *
     * @param response the response body
     * @return the identifier, or null
     */
    private static String transactionId(JsonNode response) {
        return text(response.path("POIData").path("POITransactionID").path("TransactionID"));
    }

    /**
     * A text value, null when absent or blank.
     *
     * @param node the node, a missing node when absent
     * @return the text, or null
     */
    private static String text(JsonNode node) {
        if (!node.isValueNode()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * A decimal value, null when absent or not numeric.
     *
     * @param node the node, a missing node when absent
     * @return the decimal, or null
     */
    private static BigDecimal decimal(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }
}
