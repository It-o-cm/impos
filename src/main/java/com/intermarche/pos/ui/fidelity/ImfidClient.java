package com.intermarche.pos.ui.fidelity;

import com.fasterxml.jackson.databind.DeserializationFeature;
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

    /** Shared HTTP client (connection reuse, connect timeout). */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();

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
     * is the fallback. The PASSWORD is deliberately NOT administrable — it
     * would travel in clear in the referential snapshot and show on the admin
     * page — and stays a deployment secret.
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
        String modeMember = (projectionMode == null || projectionMode.isBlank())
                ? "" : ",\"projectionMode\":\"" + projectionMode + "\"";
        String body = "{\"valuationRequest\":" + valuationRequestJson
                + ",\"valuationResponse\":" + valuationResponseJson + modeMember + "}";
        HttpRequest request = authenticated("/api/earn")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // The body is appended unconditionally: a String body handler
            // yields "" for a bodiless answer, never null — the guarded
            // version could not take its false arm.
            throw new ImfidUnavailableException("/api/earn", response.statusCode(), response.body());
        }
        return objectMapper.readValue(response.body(), EarnProjection.class);
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
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        String body = "{\"card\":\"" + card + "\",\"amount\":" + amount.toPlainString()
                + ",\"ticketRef\":\"" + ticketRef + "\"}";
        HttpRequest request = authenticated("/api/burn/reservations")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString("{\"fiscalDate\":\"" + fiscalDate + "\"}"))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ConfirmResult result = new ConfirmResult();
        result.httpStatus = response.statusCode();
        if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
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
                    .timeout(Duration.ofMillis(2500))
                    .DELETE()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
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
        if (authUser != null && password.isPresent()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((authUser + ":" + password.get()).getBytes()));
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
                .timeout(Duration.ofMillis(2500))
                .GET()
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
    }
}
