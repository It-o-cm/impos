package com.intermarche.pos.ui.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SIMULATED PAYMENT HUB: the external hub the register speaks Nexo Retailer
 * to for dematerialised articles and gift cards, answered by the register itself
 * so that a lane with no hub can still be sold, tested and demonstrated
 * (LC-02-09-01 to -03, LC-05-03-01, LC-07-11-01).
 *
 * <p>It lives beside the hardware simulator and under the same build-time filter:
 * the bean exists only under the profiles it names, a production jar exposes no
 * {@code /api/nexo} endpoint. It keeps its accounts in memory, seeded with two
 * gift cards — {@value #CARD_FUNDED} carrying 30,00 EUR and {@value #CARD_EMPTY}
 * carrying nothing — and refuses, as a provider would, any activation of a code
 * starting with {@value #REFUSED_PREFIX}.
 */
@Path("/api/nexo")
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test", "demo"})
public class MockNexoHubResource {

    /** A seeded gift card carrying 30,00 EUR. */
    static final String CARD_FUNDED = "6299000000000017";

    /** A seeded gift card carrying nothing. */
    static final String CARD_EMPTY = "6299000000000025";

    /** The code prefix whose activation the simulated provider refuses. */
    static final String REFUSED_PREFIX = "9999";

    /** The provider the hub names when the request names none. */
    static final String DEFAULT_PROVIDER = "HUBSIM";

    /** One stored-value account of the hub. */
    static final class Account {

        /** The provider of the account. */
        String provider;

        /** What the account carries. */
        BigDecimal balance;

        /** Whether the account may be used. */
        boolean active;

        /**
         * Opens an account.
         *
         * @param provider the provider
         * @param balance the balance
         */
        Account(String provider, BigDecimal balance) {
            this.provider = provider;
            this.balance = balance;
            this.active = true;
        }
    }

    /** One operation of the hub, kept so it can be reversed. */
    static final class Operation {

        /** The operation type: Activate, Unload or Payment. */
        final String type;

        /** The account it moved. */
        final String cardId;

        /** The amount it moved. */
        final BigDecimal amount;

        /** Whether it was reversed. */
        boolean reversed;

        /** Whether the register acknowledged it with a completion (BO-11-04-02). */
        boolean completed;

        /**
         * Records an operation.
         *
         * @param type the operation type
         * @param cardId the account
         * @param amount the amount
         */
        Operation(String type, String cardId, BigDecimal amount) {
            this.type = type;
            this.cardId = cardId;
            this.amount = amount;
        }
    }

    /** Builds the responses. */
    @Inject
    ObjectMapper objectMapper;

    /** The accounts, by unique code. */
    final Map<String, Account> accounts = new LinkedHashMap<>();

    /** The operations, by hub transaction identifier. */
    final Map<String, Operation> operations = new LinkedHashMap<>();

    /** The source of the hub's identifiers. */
    private long counter;

    /**
     * Opens the hub with its two seeded gift cards.
     */
    public MockNexoHubResource() {
        accounts.put(CARD_FUNDED, new Account("ILLICADO", new BigDecimal("30.00")));
        accounts.put(CARD_EMPTY, new Account("ILLICADO", BigDecimal.ZERO.setScale(2)));
    }

    /**
     * Answers one Nexo Retailer request.
     *
     * @param envelope the {@code SaleToPOIRequest} envelope
     * @return the {@code SaleToPOIResponse} envelope
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized JsonNode exchange(JsonNode envelope) {
        JsonNode message = envelope.path("SaleToPOIRequest");
        JsonNode header = message.path("MessageHeader");
        String category = header.path("MessageCategory").asText();
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode out = response.putObject("SaleToPOIResponse");
        ObjectNode outHeader = out.putObject("MessageHeader");
        outHeader.put("ProtocolVersion", header.path("ProtocolVersion").asText());
        outHeader.put("MessageClass", "Service");
        outHeader.put("MessageCategory", category);
        outHeader.put("MessageType", "Response");
        outHeader.put("ServiceID", header.path("ServiceID").asText());
        outHeader.put("SaleID", header.path("SaleID").asText());
        outHeader.put("POIID", header.path("POIID").asText());
        switch (category) {
            case "StoredValue" -> out.set("StoredValueResponse",
                    storedValue(message.path("StoredValueRequest").path("StoredValueData")));
            case "BalanceInquiry" -> out.set("BalanceInquiryResponse",
                    balance(message.path("BalanceInquiryRequest")));
            case "Payment" -> out.set("PaymentResponse", payment(message.path("PaymentRequest")));
            case "Reversal" -> out.set("ReversalResponse", reversal(message.path("ReversalRequest")));
            default -> out.set("ErrorResponse", failure("MessageFormat", "CATEGORIE INCONNUE"));
        }
        return response;
    }

    /**
     * What the hub holds for one account, for the demonstration panel and the
     * acceptance script.
     *
     * @param id the unique code
     * @return the account's provider, balance and activity
     */
    @GET
    @Path("/cards/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized Map<String, Object> card(@PathParam("id") String id) {
        Account account = accounts.get(id);
        if (account == null) {
            throw new NotFoundException();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("provider", account.provider);
        view.put("balance", account.balance);
        view.put("active", account.active);
        return view;
    }

    /**
     * Answers a {@code StoredValueRequest}.
     *
     * @param data its {@code StoredValueData}
     * @return the {@code StoredValueResponse}
     */
    private ObjectNode storedValue(JsonNode data) {
        String type = data.path("StoredValueTransactionType").asText();
        JsonNode accountId = data.path("StoredValueAccountID");
        String cardId = accountId.path("StoredValueID").asText();
        BigDecimal amount = data.path("ItemAmount").isNumber() ? data.path("ItemAmount").decimalValue() : null;
        return switch (type) {
            case "Activate" -> activate(cardId, accountId.path("StoredValueProvider").asText(), amount);
            case "Unload" -> unload(cardId, amount);
            case "Reverse" -> reverse(data.path("OriginalPOITransaction").path("POITransactionID")
                    .path("TransactionID").asText());
            default -> failure("NotAllowed", "OPERATION NON SUPPORTEE");
        };
    }

    /**
     * Activates an article for its amount.
     *
     * @param cardId the unique code
     * @param provider the provider requested, possibly blank
     * @param amount the amount, or null
     * @return the response
     */
    private ObjectNode activate(String cardId, String provider, BigDecimal amount) {
        if (cardId.isBlank() || amount == null || amount.signum() <= 0) {
            return failure("NotAllowed", "CODE OU MONTANT INVALIDE");
        }
        if (cardId.startsWith(REFUSED_PREFIX)) {
            return failure("Refusal", "ACTIVATION REFUSEE PAR LE FOURNISSEUR");
        }
        Account existing = accounts.get(cardId);
        if (existing != null && existing.active) {
            return failure("NotAllowed", "ARTICLE DEJA ACTIVE");
        }
        Account account = new Account(provider.isBlank() ? DEFAULT_PROVIDER : provider, amount);
        accounts.put(cardId, account);
        return storedValueSuccess("Activate", cardId, account, amount, record("Activate", cardId, amount));
    }

    /**
     * Unloads what a returned article carries.
     *
     * @param cardId the unique code
     * @param amount the amount, or null
     * @return the response
     */
    private ObjectNode unload(String cardId, BigDecimal amount) {
        Account account = accounts.get(cardId);
        if (account == null || !account.active) {
            return failure("InvalidCard", "ARTICLE INCONNU OU INACTIF");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(account.balance) > 0) {
            return failure("Refusal", "MONTANT SUPERIEUR AU SOLDE");
        }
        account.balance = account.balance.subtract(amount);
        account.active = account.balance.signum() > 0;
        return storedValueSuccess("Unload", cardId, account, amount, record("Unload", cardId, amount));
    }

    /**
     * Undoes an activation or an unload.
     *
     * @param original the hub identifier of the operation to undo
     * @return the response
     */
    private ObjectNode reverse(String original) {
        Operation operation = operations.get(original);
        if (operation == null || operation.reversed || "Payment".equals(operation.type)) {
            return failure("NotAllowed", "OPERATION INCONNUE OU DEJA ANNULEE");
        }
        Account account = accounts.get(operation.cardId);
        if ("Activate".equals(operation.type)) {
            account.balance = BigDecimal.ZERO.setScale(2);
            account.active = false;
        } else {
            account.balance = account.balance.add(operation.amount);
            account.active = true;
        }
        operation.reversed = true;
        return storedValueSuccess("Reverse", operation.cardId, account, operation.amount,
                record("Reverse", operation.cardId, operation.amount));
    }

    /**
     * Answers a {@code BalanceInquiryRequest}.
     *
     * @param request the request
     * @return the {@code BalanceInquiryResponse}
     */
    private ObjectNode balance(JsonNode request) {
        String cardId = request.path("PaymentAccountReq").path("PaymentInstrumentData")
                .path("StoredValueAccountID").path("StoredValueID").asText();
        Account account = accounts.get(cardId);
        if (account == null || !account.active) {
            return failure("InvalidCard", "CARTE INCONNUE OU INACTIVE");
        }
        ObjectNode response = success(nextId());
        ObjectNode status = response.putObject("PaymentAccountStatus");
        status.put("Currency", "EUR");
        status.put("CurrentBalance", account.balance);
        ObjectNode acquirer = status.putObject("PaymentAcquirerData");
        acquirer.put("AcquirerID", account.provider);
        acquirer.put("ApprovalCode", "B" + counter);
        return response;
    }

    /**
     * Answers a {@code PaymentRequest} on a gift card.
     *
     * @param request the request
     * @return the {@code PaymentResponse}
     */
    private ObjectNode payment(JsonNode request) {
        if ("Completion".equals(request.path("PaymentData").path("PaymentType").asText())) {
            return completion(request);
        }
        String cardId = request.path("PaymentData").path("PaymentInstrumentData")
                .path("StoredValueAccountID").path("StoredValueID").asText();
        JsonNode requested = request.path("PaymentTransaction").path("AmountsReq").path("RequestedAmount");
        BigDecimal amount = requested.isNumber() ? requested.decimalValue() : null;
        Account account = accounts.get(cardId);
        if (account == null || !account.active) {
            return failure("InvalidCard", "CARTE INCONNUE OU INACTIVE");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(account.balance) > 0) {
            return failure("Refusal", "SOLDE INSUFFISANT");
        }
        account.balance = account.balance.subtract(amount);
        String id = record("Payment", cardId, amount);
        ObjectNode response = success(id);
        ObjectNode result = response.putObject("PaymentResult");
        ObjectNode amounts = result.putObject("AmountsResp");
        amounts.put("Currency", "EUR");
        amounts.put("AuthorizedAmount", amount);
        ObjectNode acquirer = result.putObject("PaymentAcquirerData");
        acquirer.put("AcquirerID", account.provider);
        acquirer.put("ApprovalCode", "P" + counter);
        return response;
    }

    /**
     * Answers the completion by which the register acknowledges a payment it
     * kept on the ticket (BO-11-04-02): the original payment is marked
     * acknowledged, an unknown, reversed or non-payment one is refused.
     *
     * @param request the {@code PaymentRequest} of type {@code Completion}
     * @return the {@code PaymentResponse}
     */
    private ObjectNode completion(JsonNode request) {
        String original = request.path("PaymentTransaction").path("OriginalPOITransaction")
                .path("POITransactionID").path("TransactionID").asText();
        Operation operation = operations.get(original);
        if (operation == null || operation.reversed || !"Payment".equals(operation.type)) {
            return failure("NotAllowed", "PAIEMENT INCONNU OU DEJA ANNULE");
        }
        operation.completed = true;
        ObjectNode response = success(record("Completion", operation.cardId, operation.amount));
        response.putObject("PaymentResult").putObject("AmountsResp").put("AuthorizedAmount", operation.amount);
        return response;
    }

    /**
     * Answers a {@code ReversalRequest} on a gift-card payment.
     *
     * @param request the request
     * @return the {@code ReversalResponse}
     */
    private ObjectNode reversal(JsonNode request) {
        String original = request.path("OriginalPOITransaction").path("POITransactionID")
                .path("TransactionID").asText();
        Operation operation = operations.get(original);
        if (operation == null || operation.reversed || !"Payment".equals(operation.type)) {
            return failure("NotAllowed", "PAIEMENT INCONNU OU DEJA ANNULE");
        }
        Account account = accounts.get(operation.cardId);
        account.balance = account.balance.add(operation.amount);
        operation.reversed = true;
        ObjectNode response = success(record("Reversal", operation.cardId, operation.amount));
        response.put("ReversedAmount", operation.amount);
        return response;
    }

    /**
     * The success of a stored-value operation.
     *
     * @param type the operation type
     * @param cardId the account
     * @param account its state after the operation
     * @param amount the amount moved
     * @param id the hub identifier of the operation
     * @return the response
     */
    private ObjectNode storedValueSuccess(String type, String cardId, Account account,
                                          BigDecimal amount, String id) {
        ObjectNode response = success(id);
        ObjectNode result = response.putArray("StoredValueResult").addObject();
        result.put("StoredValueTransactionType", type);
        result.put("ItemAmount", amount);
        result.put("Currency", "EUR");
        result.putObject("HostTransactionID").put("TransactionID", "S" + counter);
        ObjectNode status = result.putObject("StoredValueAccountStatus");
        ObjectNode accountId = status.putObject("StoredValueAccountID");
        accountId.put("StoredValueProvider", account.provider);
        accountId.put("StoredValueID", cardId);
        status.put("CurrentBalance", account.balance);
        return response;
    }

    /**
     * A response accepted by the hub.
     *
     * @param id the hub identifier of the transaction
     * @return the response, its result and POI data set
     */
    private ObjectNode success(String id) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("Response").put("Result", "Success");
        ObjectNode transaction = response.putObject("POIData").putObject("POITransactionID");
        transaction.put("TransactionID", id);
        transaction.put("TimeStamp", OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return response;
    }

    /**
     * A response refused by the hub.
     *
     * @param condition the Nexo error condition
     * @param text the hub's words
     * @return the response
     */
    private ObjectNode failure(String condition, String text) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode answer = response.putObject("Response");
        answer.put("Result", "Failure");
        answer.put("ErrorCondition", condition);
        answer.put("AdditionalResponse", text);
        return response;
    }

    /**
     * Records an operation under a new identifier.
     *
     * @param type the operation type
     * @param cardId the account
     * @param amount the amount moved
     * @return the identifier
     */
    private String record(String type, String cardId, BigDecimal amount) {
        String id = nextId();
        operations.put(id, new Operation(type, cardId, amount));
        return id;
    }

    /**
     * The next identifier of the hub.
     *
     * @return the identifier
     */
    private String nextId() {
        counter++;
        return String.format("HUB%08d", counter);
    }
}
