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
        return url.isPresent() && posSettingsService.fidelityExternalEnabled();
    }

    /**
     * Returns the configured base URL for boot-time announcement.
     *
     * @return the base URL, or null when fidelity is disabled
     */
    public String targetUrl() {
        return url.orElse(null);
    }

    /**
     * Probes the service health endpoint (public, unauthenticated — spec
     * §2.2).
     *
     * @return true when imfid answers 200 within the timeout
     */
    public boolean health() {
        if (url.isEmpty()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.get() + "/q/health"))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
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
        String body = "{\"valuationRequest\":" + valuationRequestJson
                + ",\"valuationResponse\":" + valuationResponseJson + "}";
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
            throw new IllegalStateException("imfid /api/earn answered "
                    + response.statusCode() + " " + response.body());
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
            throw new IllegalStateException("imfid /api/accounts answered " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), AccountInfo.class);
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
        StringBuilder qs = new StringBuilder();
        appendParam(qs, "phone", phone);
        appendParam(qs, "email", email);
        appendParam(qs, "name", name);
        appendParam(qs, "firstName", firstName);
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
        throw new IllegalStateException("imfid /api/cards/lookup answered " + response.statusCode());
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
     * @return the HTTP status (200 confirmed, 410 expired — tolerated, 404 unknown)
     * @throws Exception on transport failure
     */
    public int confirm(long reservationId, String fiscalDate) throws Exception {
        HttpRequest request = authenticated("/api/burn/reservations/" + reservationId + "/confirm")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString("{\"fiscalDate\":\"" + fiscalDate + "\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
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

    /**
     * Posts a fiscal event body to an events endpoint (spec §6-7).
     *
     * @param path the endpoint path (e.g. /api/events/ticket-closed)
     * @param payloadJson the composed event body
     * @return the HTTP status (202 = accepted)
     * @throws Exception on transport failure
     */
    public int postEvent(String path, String payloadJson) throws Exception {
        HttpRequest request = authenticated(path)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(2500))
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.orElseThrow() + path));
        if (user.isPresent() && password.isPresent()) {
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user.get() + ":" + password.get()).getBytes()));
        }
        return builder;
    }

    /** The parsed earn projection (spec §3 — tolerant subset the POS uses). */
    public static class EarnProjection {
        /** Total earn after caps — the amount to display. */
        public BigDecimal total;
        /** Per-rule entries; labels are the ONLY rule data printed on tickets. */
        public List<EarnEntry> entries = new ArrayList<>();
        /** Burnable base of this ticket (caps a fidelity payment with the balance). */
        public BigDecimal burnableBase;
    }

    /** One earn entry (rule) of the projection. */
    public static class EarnEntry {
        /** The rule code (trace; re-sent as displayedEarn at close). */
        public String ruleCode;
        /** The label to display and print — never invented POS-side. */
        public String label;
        /** The rule's earn amount after caps. */
        public BigDecimal amount;
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
            throw new IllegalStateException("imfid /movements answered " + response.statusCode());
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
