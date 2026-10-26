package com.intermarche.pos.ui.endorsement;

import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.ui.PosState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Holds one exact HTTP gesture until the existing supervisor ceremony approves it (LC-01-05-01). */
@ApplicationScoped
public class FunctionApprovalService {
    /** Registry action; execution is resumed by a single-use grant, never by guessing a callback. */
    public static final String ACTION = "HTTP_FUNCTION";
    /** Same state monitor as settlements and the existing approval modal. */
    @Inject PosState state;
    /** Administered method/path policy, inherited and distributed normally. */
    @Inject PosSettingsService settings;
    /** The one original gesture and its approved continuation. */
    private Gesture pending;
    private Gesture granted;

    /**
     * Immutable continuation, bound to the sale and operator that requested approval.
     * @param method original HTTP method
     * @param target local path and query
     * @param body original form fields, without credentials from the approval modal
     * @param cart snapshot of operator/cart/settlements
     * @param token unpredictable, consumed on execution
     */
    public record Gesture(String method, String target, String body, String cart, String token) {}

    /**
     * Matches the last exact or subtree rule; NONE removes only this additional approval layer.
     * @param method GET or POST
     * @param path absolute local path
     * @return whether the function requires supervisor approval
     */
    public boolean required(String method, String path) {
        return Boolean.TRUE.equals(decision(method, path));
    }

    /**
     * Distinguishes an explicit exemption from the absence of a store rule.
     * @param method requested method
     * @param path requested local path
     * @return true for REQUIRED, false for NONE, null when legacy behavior must apply
     */
    public Boolean decision(String method, String path) {
        Boolean required = null;
        path = path.replaceAll("/+$", "");
        for (String raw : settings.functionEndorsements().lines().toList()) {
            String[] parts = raw.trim().split("=", 2);
            if (parts.length != 2) continue;
            String[] route = parts[0].trim().split("\\s+", 2);
            if (route.length != 2 || !route[0].equalsIgnoreCase(method)) continue;
            String target = route[1].replaceAll("/+$", "");
            boolean matches = target.endsWith("/*")
                    ? path.equals(target.substring(0, target.length() - 2))
                        || path.startsWith(target.substring(0, target.length() - 1))
                    : path.equals(target);
            if (matches) {
                String mode = parts[1].trim().split(":", 2)[0].trim();
                if ("REQUIRED".equalsIgnoreCase(mode)) required = true;
                else if ("NONE".equalsIgnoreCase(mode)) required = false;
            }
        }
        return required;
    }

    /**
     * The validating PROFILES the last REQUIRED rule matching a function names
     * after its mode, e.g. {@code POST /action/pay-tender=REQUIRED:Chef de caisse}
     * (BO-04-02-01).
     * @param method the HTTP method
     * @param path the function path
     * @return the profile names, empty when the rule names none
     */
    public List<String> profiles(String method, String path) {
        List<String> profiles = new ArrayList<>();
        path = path.replaceAll("/+$", "");
        for (String raw : settings.functionEndorsements().lines().toList()) {
            String[] parts = raw.trim().split("=", 2);
            if (parts.length != 2) continue;
            String[] route = parts[0].trim().split("\\s+", 2);
            if (route.length != 2 || !route[0].equalsIgnoreCase(method)) continue;
            String target = route[1].replaceAll("/+$", "");
            boolean matches = target.endsWith("/*")
                    ? path.equals(target.substring(0, target.length() - 2))
                        || path.startsWith(target.substring(0, target.length() - 1))
                    : path.equals(target);
            String[] mode = parts[1].trim().split(":", 2);
            if (matches && "REQUIRED".equalsIgnoreCase(mode[0].trim())) {
                profiles = EndorsementService.names(mode.length == 2 ? mode[1] : null);
            }
        }
        return profiles;
    }

    /**
     * Freezes a pending request without executing it; a new gesture revokes an older grant.
     * @param method original method
     * @param target local path/query
     * @param body urlencoded form body
     */
    public void hold(String method, String target, String body) {
        pending = new Gesture(method, target, body, snapshot(), UUID.randomUUID().toString());
        granted = null;
    }

    /**
     * Moves the exact pending gesture into a one-use continuation after successful credentials.
     * @return the original GET or the form continuation page; stale requests return home
     */
    public Response approve() {
        if (pending == null || !pending.cart().equals(snapshot())) {
            pending = null;
            granted = null;
            return Response.seeOther(URI.create("/")).build();
        }
        granted = pending;
        pending = null;
        String next = granted.method().equals("GET")
                ? granted.target() + (granted.target().contains("?") ? "&" : "?") + "_posApproval=" + granted.token()
                : "/approved-function?token=" + granted.token();
        return Response.seeOther(URI.create(next)).build();
    }

    /**
     * Returns only the current approved POST continuation, never an expired or arbitrary form.
     * @param token continuation token
     * @return the frozen gesture, or null when the request is not its owner
     */
    public Gesture continuation(String token) {
        return granted != null && granted.token().equals(token) && granted.cart().equals(snapshot()) ? granted : null;
    }

    /**
     * Consumes approval only for the exact original method, target and form on the same sale.
     * @param method incoming method
     * @param target local path/query, excluding the transport token
     * @param body incoming form without its transport token
     * @param token presented approval
     * @return true once, false for a different or replayed request
     */
    public boolean consume(String method, String target, String body, String token) {
        Gesture approved = continuation(token);
        if (approved == null || !approved.method().equals(method) || !approved.target().equals(target)
                || !fields(approved.body()).equals(fields(body))) return false;
        granted = null;
        return true;
    }

    /**
     * Decodes a form preserving repeated values; no entered values are written to logs.
     * @param body urlencoded original form
     * @return fields suitable for escaped template attributes and equality checks
     */
    public Map<String, List<String>> fields(String body) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (body.isEmpty()) return values;
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return values;
    }

    /**
     * Binds an approval to its operator, ticket content and settlements, not to unrelated UI polls.
     * @return immutable cart identity used only in memory (LC-01-05-01)
     */
    private String snapshot() {
        StringBuilder value = new StringBuilder().append(state.auth.operatorBadgeId).append('|')
                .append(state.payment.ticketDbId).append('|').append(state.trainingMode).append('|')
                .append(state.ticket.customerTaxId).append('|').append(state.payment.paidAmount)
                .append('|').append(state.payment.paymentInProgress).append('|').append(state.payment.transactionComplete)
                .append('|').append(state.payment.pendingCardAmount).append('|').append(state.payment.pendingChequeAmount);
        for (var item : state.ticket.items) value.append('|').append(item.uid).append(':')
                .append(item.quantity).append(':').append(item.getTotalPrice());
        for (var payment : state.payment.payments) value.append('|').append(payment.method).append(':')
                .append(payment.amount).append(':').append(payment.voucherNumber).append(':').append(payment.authorizationNumber);
        return value.toString();
    }
}
