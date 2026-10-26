package com.intermarche.pos.ui.fidelity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * HTTP client of the imfid loyalty service (integration spec v1.0), the
 * fidelity sibling of {@code ValuationClient}: base URL and Basic
 * credentials from configuration, short timeouts (a loyalty display must
 * never slow a sale), tolerant JSON reader (unknown properties ignored, per
 * the spec's forward-compatibility contract).
 * <p>
 * DOCTRINE (spec §1): imfid never computes a price — the earn projection is
 * fed the {@code /valuation} couple VERBATIM. This client therefore embeds
 * the two RAW JSON strings as captured on the valuation path, without any
 * reserialization: {@link #earn(String, String)} builds its body by string
 * composition, not by object mapping.
 * <p>
 * Holder lookup contract: docs/addendum-imfid-lookup-v1.1.md (verbatim copy
 * of the imfid addendum this client implements).
 */
@ApplicationScoped
public class ImfidClient {

    /** The benefit the REGISTER applies; the other one belongs to imfid. */
    public static final String BENEFIT_IMMEDIATE_DISCOUNT = "IMMEDIATE_DISCOUNT";

    /** Base URL of the loyalty service; absent = fidelity earn/burn disabled. */
    @ConfigProperty(name = "pos.fid.url")
    Optional<String> url;

    /** Basic-auth user of the POS machine account (role pos). */
    @ConfigProperty(name = "pos.fid.user")
    Optional<String> user;

    /** Basic-auth password of the POS machine account. */
    @ConfigProperty(name = "pos.fid.password")
    Optional<String> password;

    /** The back-office parameters (external loyalty activation — BO-10-03-07). */
    @jakarta.inject.Inject
    com.intermarche.pos.service.PosSettingsService posSettingsService;

    /** The connection timeout applying when none is administered (BO-10-03-10). */
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 1500;

    /** The per-call response timeout applying when none is administered (BO-10-03-10). */
    static final int DEFAULT_READ_TIMEOUT_MS = 2500;

    /**
     * Shared HTTP client (connection reuse), built for the connection timeout
     * administered when it was built and rebuilt when that timeout changes
     * (BO-10-03-10): the timeout is a property of the client, not of a request.
     */
    private HttpClient httpClient;

    /** The connection timeout {@link #httpClient} was built with, in milliseconds. */
    private int httpClientConnectMs;

    /** The writer of the request bodies: decimals in plain notation. */
    private static final ObjectWriter WRITER = new ObjectMapper()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).writer();

    /** Tolerant reader: unknown JSON properties are ignored (spec §2). */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Tells whether the loyalty service is configured AND administratively
     * active (BO-10-03-07): a base URL must be present and the back office must
     * not have switched the external loyalty off. The single gate every caller
     * already funnels through, so flipping the setting disables earn, burn,
     * lookup and health in one place — the register then behaves as if no
     * loyalty service existed, even with credentials still on file.
     *
     * @return true when a base URL is present and the external loyalty is active
     */
    public boolean isConfigured() {
        return baseUrl() != null && posSettingsService.fidelityExternalEnabled();
    }

    /**
     * Resolves the loyalty service base URL (BO-11-04-04): the ADMINISTERED
     * value wins, so an echelon can point its registers at another endpoint
     * without a redeployment, and the {@code pos.fid.url} deployment property
     * remains the fallback for a node that administers none.
     *
     * @return the base URL, or null when none is configured
     */
    String baseUrl() {
        String administered = posSettingsService != null ? posSettingsService.fidelityUrl() : null;
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return url.orElse(null);
    }

    /**
     * Resolves the Basic-auth user of the POS machine account (BO-11-04-04):
     * the ADMINISTERED value wins, the {@code pos.fid.user} deployment property
     * is the fallback. The password has its own resolution, {@link #authPassword()}.
     *
     * @return the Basic-auth user, or null when none is configured
     */
    String authUser() {
        String administered = posSettingsService != null ? posSettingsService.fidelityUser() : null;
        if (administered != null && !administered.isBlank()) {
            return administered.trim();
        }
        return user.orElse(null);
    }

    /**
     * Resolves the Basic-auth password of the POS machine account
     * (BO-10-03-08): the ADMINISTERED value wins — the back office edits it
     * masked and the referential distributes it with the other loyalty call
     * settings — and the {@code pos.fid.password} deployment property is the
     * fallback for a node administering none. Not trimmed: a password is a
     * secret, every character of it counts.
     *
     * @return the Basic-auth password, or null when none is configured
     */
    String authPassword() {
        String administered = posSettingsService != null ? posSettingsService.fidelityPassword() : null;
        if (administered != null && !administered.isEmpty()) {
            return administered;
        }
        return password.orElse(null);
    }

    /**
     * Resolves the connection timeout towards the loyalty service
     * (BO-10-03-10): the administered value, or the historical 1500 ms when
     * none is wired or a non-positive one is read.
     *
     * @return the connection timeout in milliseconds, strictly positive
     */
    int connectTimeoutMs() {
        int administered = posSettingsService != null ? posSettingsService.fidelityConnectTimeoutMs() : 0;
        return administered > 0 ? administered : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    /**
     * Resolves the response timeout of one loyalty call (BO-10-03-10): past it
     * the call fails, the caller's breaker opens and the register shows the
     * loyalty service as unavailable — the sale goes on without it.
     *
     * @return the response timeout, strictly positive
     */
    Duration readTimeout() {
        int administered = posSettingsService != null ? posSettingsService.fidelityReadTimeoutMs() : 0;
        return Duration.ofMillis(administered > 0 ? administered : DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Returns the shared HTTP client, built for the CURRENT administered
     * connection timeout (BO-10-03-10): the same client is reused while the
     * timeout stands, and a new one is built the first call after it changed —
     * a back-office edit applies without a restart.
     *
     * @return the HTTP client
     */
    synchronized HttpClient http() {
        int connectMs = connectTimeoutMs();
        if (httpClient == null || connectMs != httpClientConnectMs) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectMs))
                    .build();
            httpClientConnectMs = connectMs;
        }
        return httpClient;
    }

    /**
     * Returns the configured base URL for boot-time announcement.
     *
     * @return the base URL, or null when fidelity is disabled
     */
    public String targetUrl() {
        return baseUrl();
    }


    /**
     * Requests the earn projection for the current cart (spec §3): the
     * {@code /valuation} couple travels VERBATIM — the body is composed from
     * the two raw JSON strings captured on the valuation path, never
     * reconstructed. Pure read: no credit, no visit, no trace.
     *
     * @param valuationRequestJson the raw request JSON sent to imvaluation
     * @param valuationResponseJson the raw response JSON received from it
     * @return the parsed projection
     * @throws Exception on transport failure or non-200 answer (the caller's
     *         breaker turns this into the degraded display)
     */
    public EarnProjection earn(String valuationRequestJson, String valuationResponseJson)
            throws Exception {
        return earn(valuationRequestJson, valuationResponseJson, null);
    }

    /**
     * Requests the earn projection in an explicit projection mode (imfid
     * response C): {@code CARD} (nominal) or {@code ANONYMOUS} (non-holder
     * projection — « ce que vous auriez gagné avec la carte »).
     * <p>
     * The NOMINAL flow is byte-identical to before: a null {@code
     * projectionMode} sends NO {@code projectionMode} member (the contract
     * says the behaviour without the field is unchanged), so the CARD path
     * never gains a field. The {@code projectionMode} member is added ONLY for
     * the anonymous projection.
     *
     * @param valuationRequestJson the raw request JSON sent to imvaluation
     * @param valuationResponseJson the raw response JSON received from it
     * @param projectionMode {@code ANONYMOUS} to project for a non-holder, or
     *        null/{@code CARD} for the unchanged nominal call
     * @return the parsed projection
     * @throws Exception on transport failure or non-200 answer
     */
    public EarnProjection earn(String valuationRequestJson, String valuationResponseJson,
            String projectionMode) throws Exception {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.putRawValue("valuationRequest", new RawValue(String.valueOf(valuationRequestJson)));
        payload.putRawValue("valuationResponse", new RawValue(String.valueOf(valuationResponseJson)));
        if (projectionMode != null && !projectionMode.isBlank()) {
            payload.put("projectionMode", projectionMode);
        }
        String body = json(payload);
        HttpRequest request = authenticated("/api/earn")
                .header("Content-Type", "application/json")
                .timeout(readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // The body is appended unconditionally: a String body handler
            // yields "" for a bodiless answer, never null — the guarded
            // version could not take its false arm.
            throw new ImfidUnavailableException("/api/earn", response.statusCode(), response.body());
        }
        EarnProjection projection = objectMapper.readValue(response.body(), EarnProjection.class);
        projection.sortByCategoryPriority();
        return projection;
    }

    /**
     * Reads a loyalty account (spec §4) — status, balance and the AVAILABLE
     * balance (balance minus active leases), the one that caps a fidelity
     * payment.
     *
     * @param card the card number
     * @return the account, or null when the card is unknown (404)
     * @throws Exception on transport failure or unexpected status
     */
    public AccountInfo account(String card) throws Exception {
        HttpRequest request = authenticated("/api/accounts/" + card)
                .timeout(readTimeout())
                .GET()
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new ImfidUnavailableException("/api/accounts", response.statusCode());
        }
        return objectMapper.readValue(response.body(), AccountInfo.class);
    }

    /**
     * Reads the IMMEDIATE-DISCOUNT promotions of a card (addendum
     * « Promotions spécialisées par carte » §2) — the EAN-level
     * specializations the register carries into the basket so the valuation
     * engine applies them as discounts.
     * <p>
     * The benefit filter travels in the REQUEST rather than being applied to
     * the answer, and that is the whole point: the addendum's rule is that
     * imfid serves the {@code CAGNOTTE} promotions itself and the register
     * applies only the {@code IMMEDIATE_DISCOUNT} ones, never both, never
     * the other way round. Asking imfid for the discounts alone states that
     * boundary where it is enforced, so no cagnotte promotion can travel to
     * the engine and be granted twice.
     * <p>
     * An unknown card answers an EMPTY list rather than null: a card without
     * promotions and a card imfid does not know lead to the same basket, and
     * the caller has no decision to make between them.
     *
     * @param card the card number
     * @return the immediate-discount promotions, possibly empty
     * @throws Exception on transport failure or unexpected status
     */
    public List<CardPromotion> promotions(String card) throws Exception {
        HttpRequest request = authenticated("/api/accounts/" + card
                        + "/promotions?benefit=" + BENEFIT_IMMEDIATE_DISCOUNT)
                .timeout(readTimeout())
                .GET()
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return new ArrayList<>();
        }
        if (response.statusCode() != 200) {
            throw new ImfidUnavailableException("/api/accounts/{card}/promotions", response.statusCode());
        }
        return objectMapper.readValue(response.body(),
                objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, CardPromotion.class));
    }

    /**
     * Searches cards by holder identity (addendum §3, {@code /api/cards/lookup}).
     * <p>
     * ONE criterion per call, by the addendum's priority: phone, else e-mail,
     * else name (+ optional firstName). Matching (addendum v1.1): EXACT for
     * phone and e-mail, PREFIX for name and firstName ("dur" finds Durand).
     * Normalization (phone formats, case, accents) is imfid's job — the
     * register sends the operator's input as typed. Outcomes are TYPED, never exceptions for business answers:
     * 200 = matches (possibly empty, capped at 20 by imfid), 422 with the
     * CRM reason = identity lives in the CRM (addendum §2), any other
     * non-200 throws (the caller's breaker turns it into the degraded
     * message).
     *
     * @param phone the holder's phone, any format, or null
     * @param email the holder's e-mail, or null
     * @param name the holder's last name, or null
     * @param firstName the optional first name refining a name search, or null
     * @return the typed lookup outcome
     * @throws Exception on transport failure or unexpected status
     */
    public LookupResult lookup(String phone, String email, String name, String firstName)
            throws Exception {
        return lookup(phone, email, name, firstName, null);
    }

    /**
     * Looks up cards by identity, city included (EXP-74). Same contract as the
     * four-argument overload; the {@code city} criterion is one more query
     * parameter, sent only when non-blank.
     *
     * @param phone the holder's phone, any format, or null
     * @param email the holder's e-mail, or null
     * @param name the holder's last name, or null
     * @param firstName the optional first name refining a name search, or null
     * @param city the holder's city, or null
     * @return the typed lookup outcome
     * @throws Exception on transport failure or unexpected status
     */
    public LookupResult lookup(String phone, String email, String name, String firstName,
            String city) throws Exception {
        StringBuilder qs = new StringBuilder();
        appendParam(qs, "phone", phone);
        appendParam(qs, "email", email);
        appendParam(qs, "name", name);
        appendParam(qs, "firstName", firstName);
        appendParam(qs, "city", city);
        HttpRequest request = authenticated("/api/cards/lookup" + qs)
                .timeout(readTimeout())
                .GET()
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        LookupResult result = new LookupResult();
        if (response.statusCode() == 200) {
            result.matches = objectMapper.readValue(response.body(),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, LookupMatch.class));
            return result;
        }
        if (response.statusCode() == 422) {
            RefusalBody refusal = objectMapper.readValue(response.body(), RefusalBody.class);
            result.crmManaged = refusal.reason != null
                    && refusal.reason.startsWith("Holder identity is managed by the CRM");
            result.refusalReason = refusal.reason;
            return result;
        }
        throw new ImfidUnavailableException("/api/cards/lookup", response.statusCode());
    }

    /**
     * Appends one URL-encoded query parameter when its value is non-blank.
     *
     * @param qs the query string under construction
     * @param key the parameter name
     * @param value the raw value, or null
     */
    private void appendParam(StringBuilder qs, String key, String value) {
        if (value == null || value.isBlank()) return;
        qs.append(qs.length() == 0 ? '?' : '&').append(key).append('=')
          .append(java.net.URLEncoder.encode(value.trim(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Reserves a fidelity-payment lease (spec §5.1). Business refusals are
     * TYPED results, never exceptions — only transport failures throw.
     *
     * @param card the card number
     * @param amount the amount to reserve
     * @param ticketRef the stable per-ticket reference (renewal key)
     * @return the typed reservation outcome
     * @throws Exception on transport failure (caller refuses the payment)
     */
    public ReservationResult reserve(String card, BigDecimal amount, String ticketRef)
            throws Exception {
        String body = json(JsonNodeFactory.instance.objectNode()
                .put("card", card).put("amount", amount).put("ticketRef", ticketRef));
        HttpRequest request = authenticated("/api/burn/reservations")
                .header("Content-Type", "application/json")
                .timeout(readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        ReservationResult result = new ReservationResult();
        result.httpStatus = response.statusCode();
        switch (response.statusCode()) {
            case 201, 200 -> {
                ReservationBody parsed =
                        objectMapper.readValue(response.body(), ReservationBody.class);
                result.reservationId = parsed.reservationId;
                result.expiresAt = parsed.expiresAt;
            }
            case 422 -> {
                RefusalBody refusal = objectMapper.readValue(response.body(), RefusalBody.class);
                result.reason = refusal.reason;
            }
            default -> { /* 404, 409: the status alone says it all */ }
        }
        return result;
    }

    /**
     * Confirms a lease at the fiscal moment (spec §5.2, idempotent).
     *
     * @param reservationId the lease id
     * @param fiscalDate the ticket's fiscal date (yyyy-MM-dd)
     * @return the typed outcome: the HTTP status (200 confirmed, 410 expired —
     *         tolerated, 404 unknown) and, on 200, the ledger balances
     *         (imfid response B); 410/404 carry the status alone (no body)
     * @throws Exception on transport failure
     */
    public ConfirmResult confirm(long reservationId, String fiscalDate) throws Exception {
        HttpRequest request = authenticated("/api/burn/reservations/" + reservationId + "/confirm")
                .header("Content-Type", "application/json")
                .timeout(readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(json(JsonNodeFactory.instance.objectNode()
                        .put("fiscalDate", fiscalDate))))
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        ConfirmResult result = new ConfirmResult();
        result.httpStatus = response.statusCode();
        if (response.statusCode() == 200 && !response.body().isBlank()) {
            // imfid response B: the 200 now carries the ledger figures. On an
            // idempotent replay balanceBefore is null (no longer knowable) —
            // tolerated; burnedAmount and balanceAfter stay served. balanceAfter
            // is READ from the ledger, never recomputed by the register.
            ConfirmResult body = objectMapper.readValue(response.body(), ConfirmResult.class);
            result.balanceBefore = body.balanceBefore;
            result.burnedAmount = body.burnedAmount;
            result.balanceAfter = body.balanceAfter;
            result.asOf = body.asOf;
        }
        return result;
    }

    /**
     * The typed outcome of a lease confirmation (imfid response B). Jackson
     * populates the balance fields from the 200 body; the register only reads
     * them, never recomputes {@link #balanceAfter}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfirmResult {
        /** The HTTP status: 200 confirmed, 410 expired (tolerated), 404 unknown. */
        public int httpStatus;
        /** The balance before the burn, or null on an idempotent replay. */
        public BigDecimal balanceBefore;
        /** The amount burned by this confirmation, or null when no body. */
        public BigDecimal burnedAmount;
        /** The balance AFTER the burn, from the ledger — printed as SOLDE APRES. */
        public BigDecimal balanceAfter;
        /** The ledger read instant (ISO local date-time), or null. */
        public String asOf;
    }

    /**
     * Releases a lease (spec §5.3, idempotent, 204 always). Transport
     * failures are swallowed: an unreleased lease simply expires by TTL.
     *
     * @param reservationId the lease id
     */
    public void release(long reservationId) {
        try {
            HttpRequest request = authenticated("/api/burn/reservations/" + reservationId)
                    .timeout(readTimeout())
                    .DELETE()
                    .build();
            http().send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The TTL is the safety net (spec §5.1): nothing to do.
        }
    }


    /** Typed outcome of a reservation attempt (spec §5.1 codes). */
    public static class ReservationResult {
        /** The raw HTTP status: 201/200 granted, 404 unknown card, 409 busy, 422 refused. */
        public int httpStatus;
        /** The lease id when granted. */
        public Long reservationId;
        /** The lease expiry when granted (ISO local date-time). */
        public String expiresAt;
        /** The refusal reason on 422 (closed nomenclature). */
        public String reason;
    }

    /** The 201/200 reservation body. */
    public static class ReservationBody {
        /** The lease id. */
        public Long reservationId;
        /** The lease expiry. */
        public String expiresAt;
    }

    /** The 422 refusal body. */
    /**
     * One card-specialized promotion, as imfid serves it. Serializable
     * because it is held by the session-scoped fidelity state between the
     * card attachment and every later valuation of the same sale.
     */
    public static class CardPromotion implements java.io.Serializable {
        /** Serialization identity of the promotion view. */
        private static final long serialVersionUID = 1L;
        /** The promoted product EAN. */
        public String ean;
        /** How {@link #value} reads: PERCENT (fraction) or AMOUNT (euro per unit). */
        public String promotionType;
        /** The promotion value. */
        public BigDecimal value;
        /** How the benefit is served: CAGNOTTE or IMMEDIATE_DISCOUNT. */
        public String benefit;
    }

    public static class RefusalBody {
        /** INSUFFICIENT_BALANCE | DAILY_RULE | ACCOUNT_STATUS. */
        public String reason;
    }

    /**
     * Starts an authenticated request builder on an API path.
     *
     * @param path the path under the base URL
     * @return the builder, Basic header set when credentials are configured
     */
    private HttpRequest.Builder authenticated(String path) {
        String base = baseUrl();
        if (base == null) {
            throw new java.util.NoSuchElementException("No value present");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path));
        String authUser = authUser();
        String authPassword = authPassword();
        if (authUser != null && authPassword != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((authUser + ":" + authPassword).getBytes()));
        }
        return builder;
    }

    /** The parsed earn projection (spec §3 — tolerant subset the POS uses). */
    public static class EarnProjection {
        /** The projection mode echoed by imfid: CARD (default) or ANONYMOUS. */
        public String projectionMode;
        /** Total earn after caps — the amount to display. */
        public BigDecimal total;
        /** Per-rule entries; labels are the ONLY rule data printed on tickets. */
        public List<EarnEntry> entries = new ArrayList<>();

        /**
         * Orders the entries by the CATEGORY PRIORITY the programme
         * communicated (BO-10-03-20), the entries of a category with no rank
         * after the ranked ones; the sort is stable, so equal ranks keep the
         * order they arrived in.
         *
         * <p>Done once, at reception: the order then travels with the entries
         * onto the frozen advantage lines, so the receipt groups its
         * categories in that priority and a duplicata regroups them
         * identically, without a rank column to carry.
         */
        public void sortByCategoryPriority() {
            if (entries == null) {
                return;
            }
            entries.sort(java.util.Comparator.comparingInt(entry -> entry.advantageCategoryOrder == null
                    ? Integer.MAX_VALUE : entry.advantageCategoryOrder));
        }
        /** Burnable base of this ticket (caps a fidelity payment with the balance). */
        public BigDecimal burnableBase;
        /**
         * The caps imfid applied, each carrying a free-text {@code scope}. The
         * scope nomenclature is OPEN (a new one such as {@code EGALIM:<lineId>}
         * may appear): parsing tolerates any value and the register displays
         * nothing specific for now (imfid response §5 forward-compatibility).
         */
        public List<CapApplied> capsApplied = new ArrayList<>();
        /** Non-blocking warnings, or empty. */
        public List<String> warnings = new ArrayList<>();
        /**
         * The transaction balances (imfid response B) — present ONLY in CARD
         * mode with a resolved card, null in ANONYMOUS or when no card is
         * resolved.
         */
        public Balances balances;
        /**
         * The short ADVANTAGE MESSAGE of the sale, two lines at most
         * (BO-03-03-26), printed before the advantages; null when the programme
         * sends none.
         */
        public List<String> shortMessage;
        /**
         * The COMMERCIAL MESSAGES of the sale (BO-03-03-27): up to five
         * messages of up to ten lines each, printed at the end of the loyalty
         * section; null when the programme sends none.
         */
        public List<List<String>> commercialMessages;
    }

    /** One applied cap; {@code scope} is a free string (open nomenclature). */
    public static class CapApplied {
        /** The cap scope, e.g. GLOBAL or EGALIM:&lt;lineId&gt; — never interpreted POS-side. */
        public String scope;
        /** The amount the cap trimmed, or null. */
        public BigDecimal amount;
    }

    /** The transaction balances block (imfid response B, option (a)). */
    public static class Balances {
        /** The available balance (book balance minus active reservations). */
        public BigDecimal available;
        /** The projection {@code available + total} — a FORECAST, credited only at ingestion. */
        public BigDecimal projectedAfterEarn;
        /** The ledger read instant (ISO local date-time). */
        public String asOf;
    }

    /** One earn entry (rule) of the projection. */
    public static class EarnEntry {
        /** The rule code (trace; re-sent as displayedEarn at close). */
        public String ruleCode;
        /** The label to display and print — never invented POS-side. */
        public String label;
        /** The rule's earn amount after caps. */
        public BigDecimal amount;
        /** The base amount the earn was computed on, or null (imfid response A). */
        public BigDecimal baseAmount;
        /** The ids of the lines the earn covers, or null. */
        public List<String> lineIds;
        /**
         * The advantage TYPE code the entry belongs to (imfid response A,
         * BO-03-03-25/-33), or null on a rule predating the referential — the
         * five advantage fields are then all null and the register falls back
         * to its flat list.
         */
        public String advantageType;
        /** The advantage type LABEL, printed verbatim (never translated), or null. */
        public String advantageTypeLabel;
        /** The order the advantage-type groups print in (never the evaluation priority), or null. */
        public Integer advantageTypeOrder;
        /** The advantage CATEGORY code, or null when the rule carries none. */
        public String advantageCategory;
        /** The advantage category LABEL, printed verbatim, or null. */
        public String advantageCategoryLabel;
        /**
         * The PRIORITY of the advantage category, the smaller first, as the
         * programme ranks its categories (BO-10-03-20), or null when it ranks
         * none.
         */
        public Integer advantageCategoryOrder;
    }

    /**
     * Reads a page of account movements (spec §4 — the in-store history).
     *
     * @param card the card number
     * @param page the zero-based page
     * @param size the page size
     * @return the parsed page, or null when the card is unknown (404)
     * @throws Exception on transport failure or unexpected status
     */
    public MovementsPage movements(String card, int page, int size) throws Exception {
        HttpRequest request = authenticated("/api/accounts/" + card
                        + "/movements?page=" + page + "&size=" + size)
                .timeout(readTimeout())
                .GET()
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new ImfidUnavailableException("/movements", response.statusCode());
        }
        return objectMapper.readValue(response.body(), MovementsPage.class);
    }

    /** A movements page (tolerant subset). */
    public static class MovementsPage {
        /** The movements of the page, newest first. */
        public List<Movement> items = new ArrayList<>();
    }

    /** One account movement (tolerant subset the POS displays). */
    public static class Movement {
        /** EARN | BURN | RETURN_DEBIT | REFUND_CREDIT | ADJUSTMENT | EXPIRY | ... */
        public String type;
        /** The signed amount. */
        public BigDecimal amount;
        /** The fiscal date when the movement carries one. */
        public String fiscalDate;
        /** The creation instant (fallback display date). */
        public String createdAt;
        /** The rule behind an EARN, when any. */
        public String ruleCode;
    }

    /** The parsed account (spec §4 — subset the POS uses). */
    /** Typed outcome of a holder lookup (addendum §3.3/§3.4). */
    public static class LookupResult {
        /** The matches (200), empty when none; null on a 422 refusal. */
        public List<LookupMatch> matches;
        /** True when imfid runs in CRM mode: identity is not its business. */
        public boolean crmManaged;
        /** The verbatim 422 reason, for the log. */
        public String refusalReason;
    }

    /** One holder match (tolerant reader — unknown fields ignored). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class LookupMatch {
        /** The card number — the only key the ticket flow ever uses. */
        public String card;
        /** ACTIVE | PENDING_ACTIVATION | RESILIATED. */
        public String status;
        /** The holder's last name, for the operator's verbal check only. */
        public String lastName;
        /** The holder's first name, for the operator's verbal check only. */
        public String firstName;
        /**
         * The holder's phone as registered, or null (addendum v1.1) — shown
         * to the OPERATOR to discriminate homonyms, never read to the client.
         */
        public String phone;
        /** The holder's e-mail (lowercase), or null (addendum v1.1) — same rule. */
        public String email;
    }

    public static class AccountInfo {
        /** ACTIVE | PENDING_ACTIVATION | RESILIATED. */
        public String status;
        /** The book balance. */
        public BigDecimal balance;
        /** Balance minus active leases — the payment cap. */
        public BigDecimal availableBalance;
        /**
         * The language the holder speaks, as the loyalty base records it (an
         * IETF tag such as {@code nl} or {@code pt-PT}), or null when the base
         * records none (LC-13-06-09, -14).
         */
        public String language;
        /**
         * The holder's last name when the programme returns it with the
         * account, or null (BO-10-03-04): a programme managing the identity
         * answers a scanned card with its holder, and the register shows it
         * on the cashier screen where the store administers it.
         */
        public String lastName;
        /** The holder's first name, same provenance and rule as the last name (BO-10-03-04). */
        public String firstName;
    }

    /**
     * Reads the balance of EACH pot of a card (BO-10-02-33 — the FID Portugal
     * cagnottes), as the programme computes it with the current transaction:
     * the advantages this ticket earns and the loyalty payment already settled
     * travel in the body, so the answer is what each pot will hold once the
     * ticket is closed. Pure read, like the earn projection.
     *
     * @param card the card number
     * @param earnAmount the advantages the current ticket earns, or null for none
     * @param burnAmount the loyalty payment settled on the current ticket, or null for none
     * @return the balances in the programme's order, empty when the card is unknown (404)
     * @throws Exception on transport failure or unexpected status
     */
    public List<PotBalance> pots(String card, BigDecimal earnAmount, BigDecimal burnAmount)
            throws Exception {
        String body = json(JsonNodeFactory.instance.objectNode()
                .put("earnAmount", plain(earnAmount)).put("burnAmount", plain(burnAmount)));
        HttpRequest request = authenticated("/api/accounts/" + card + "/pots")
                .header("Content-Type", "application/json")
                .timeout(readTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return new ArrayList<>();
        }
        if (response.statusCode() != 200) {
            throw new ImfidUnavailableException("/api/accounts/{card}/pots", response.statusCode());
        }
        return objectMapper.readValue(response.body(),
                objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, PotBalance.class));
    }

    /**
     * Writes an amount as a JSON number, zero for none.
     *
     * @param amount the amount, or null
     * @return the amount, zero for none
     */
    private static BigDecimal plain(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /**
     * Writes a request body built by the JSON library: compact, decimals in
     * plain notation ({@code 10.00}, never {@code 1E+1}), raw members — the
     * valuation couple — written verbatim.
     *
     * @param body the document
     * @return its JSON text
     * @throws IllegalStateException when the document cannot be written
     */
    public static String json(JsonNode body) {
        try {
            return WRITER.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("Corps JSON impossible à écrire", unwritable);
        }
    }

    /**
     * The MESSAGES the loyalty programme addresses to the customer with a sale
     * (BO-03-03-26, BO-03-03-27): the short two-line advantage message and up
     * to five commercial messages of up to ten lines.
     *
     * <p>The bounds are the programme's own formats and are enforced at
     * reception: a third line of a short message or a sixth commercial message
     * is a message the paper has no room for, and cutting it here means the
     * frozen text, the receipt and every duplicata agree on what was kept.
     * Serializable because it is held by the fidelity state of the sale.
     */
    public static class LoyaltyMessages implements java.io.Serializable {
        /** Serialization identity of the messages. */
        private static final long serialVersionUID = 1L;
        /** The most lines a short advantage message carries. */
        public static final int SHORT_LINES = 2;
        /** The most commercial messages a sale carries. */
        public static final int COMMERCIAL_MESSAGES = 5;
        /** The most lines one commercial message carries. */
        public static final int COMMERCIAL_LINES = 10;
        /** The lines of the short advantage message, empty when none. */
        public List<String> shortLines = new ArrayList<>();
        /** The commercial messages, each a list of lines, empty when none. */
        public List<List<String>> commercial = new ArrayList<>();

        /**
         * Reads the messages of an earn projection, within the programme's
         * bounds; blank lines and empty messages are dropped.
         *
         * @param projection the parsed projection, or null
         * @return the messages, empty when the projection carries none
         */
        public static LoyaltyMessages of(EarnProjection projection) {
            LoyaltyMessages messages = new LoyaltyMessages();
            if (projection == null) {
                return messages;
            }
            messages.shortLines = lines(projection.shortMessage, SHORT_LINES);
            if (projection.commercialMessages != null) {
                for (List<String> message : projection.commercialMessages) {
                    List<String> kept = lines(message, COMMERCIAL_LINES);
                    if (!kept.isEmpty() && messages.commercial.size() < COMMERCIAL_MESSAGES) {
                        messages.commercial.add(kept);
                    }
                }
            }
            return messages;
        }

        /**
         * Keeps the non-blank lines of a message, within a bound, without the
         * tabs and line breaks the frozen text uses as separators.
         *
         * @param raw the lines as received, possibly null
         * @param max the most lines kept
         * @return the kept lines
         */
        private static List<String> lines(List<String> raw, int max) {
            List<String> kept = new ArrayList<>();
            if (raw == null) {
                return kept;
            }
            for (String line : raw) {
                if (line != null && !line.isBlank() && kept.size() < max) {
                    kept.add(line.replaceAll("[\\t\\r\\n]", " ").strip());
                }
            }
            return kept;
        }

        /**
         * Tells whether the sale carries no message at all.
         *
         * @return true when there is nothing to print
         */
        public boolean isEmpty() {
            return shortLines.isEmpty() && commercial.isEmpty();
        }

        /**
         * Writes the messages as the text frozen on the ticket: one line per
         * printed line, {@code S} and a tab before a line of the short message,
         * {@code C} followed by the message's rank and a tab before a line of a
         * commercial message.
         *
         * @return the frozen text, or null when there is no message
         */
        public String encode() {
            if (isEmpty()) {
                return null;
            }
            List<String> rows = new ArrayList<>();
            for (String line : shortLines) {
                rows.add("S\t" + line);
            }
            for (int rank = 0; rank < commercial.size(); rank++) {
                for (String line : commercial.get(rank)) {
                    rows.add("C" + rank + "\t" + line);
                }
            }
            return String.join("\n", rows);
        }

        /**
         * Reads back the messages frozen by {@link #encode()}; a row in no known
         * shape is ignored rather than printed.
         *
         * @param text the frozen text, possibly null or blank
         * @return the messages, empty when none was frozen
         */
        public static LoyaltyMessages decode(String text) {
            LoyaltyMessages messages = new LoyaltyMessages();
            if (text == null || text.isBlank()) {
                return messages;
            }
            java.util.Map<String, List<String>> byRank = new java.util.LinkedHashMap<>();
            for (String row : text.split("\n")) {
                int tab = row.indexOf('\t');
                if (tab < 1) {
                    continue;
                }
                String kind = row.substring(0, tab);
                String line = row.substring(tab + 1);
                if ("S".equals(kind)) {
                    messages.shortLines.add(line);
                } else if (kind.startsWith("C")) {
                    byRank.computeIfAbsent(kind, absent -> new ArrayList<>()).add(line);
                }
            }
            messages.commercial.addAll(byRank.values());
            return messages;
        }
    }

    /**
     * The balance of one pot of a card (BO-10-02-33), tolerant reader.
     * Serializable because it is held by the fidelity state of the sale.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class PotBalance implements java.io.Serializable {
        /** Serialization identity of the pot view. */
        private static final long serialVersionUID = 1L;
        /** The pot code, e.g. CAGNOTTE or a FID Portugal pot code. */
        public String code;
        /** The pot label, displayed and printed verbatim. */
        public String label;
        /** The pot balance once the current transaction is taken into account. */
        public BigDecimal balance;

        /**
         * Returns the pot label, falling back on its code when the programme
         * sent none.
         *
         * @return the display label, never null
         */
        public String getDisplayLabel() {
            if (label != null && !label.isBlank()) {
                return label;
            }
            return code == null ? "" : code;
        }

        /**
         * Returns the balance formatted with the French comma and the euro sign.
         *
         * @return the formatted balance, empty when the programme sent none
         */
        public String getBalanceFormatted() {
            if (balance == null) {
                return "";
            }
            return balance.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    .replace('.', ',') + " \u20ac";
        }

        /**
         * Writes the pots a sale leaves behind as the text frozen on the ticket
         * (BO-10-02-33): one pot per line, its display label, a tab, then its
         * balance as a plain decimal \u2014 empty when the programme sent none. The
         * tabs and line breaks a label might carry are turned into blanks, so
         * the text always reads back into the same pots.
         *
         * @param pots the pots, possibly null
         * @return the frozen text, or null when there is no pot to state
         */
        public static String encode(List<PotBalance> pots) {
            if (pots == null || pots.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (PotBalance pot : pots) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(pot.getDisplayLabel().replaceAll("[\\t\\r\\n]", " ")).append('\t')
                        .append(pot.balance == null ? "" : pot.balance.toPlainString());
            }
            return text.toString();
        }

        /**
         * Reads back the pots frozen on a ticket by {@link #encode(List)}
         * (BO-10-02-33). Each pot comes back with its label and balance; the
         * code is not frozen and stays null.
         *
         * @param text the frozen text, possibly null or blank
         * @return the pots in their frozen order, empty when none was frozen
         */
        public static List<PotBalance> decode(String text) {
            List<PotBalance> pots = new java.util.ArrayList<>();
            if (text == null || text.isBlank()) {
                return pots;
            }
            for (String row : text.split("\n")) {
                String[] parts = row.split("\t", -1);
                PotBalance pot = new PotBalance();
                pot.label = parts[0];
                pot.balance = parts.length > 1 && !parts[1].isBlank() ? new BigDecimal(parts[1].trim()) : null;
                pots.add(pot);
            }
            return pots;
        }
    }
}
