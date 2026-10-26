package com.intermarche.pos.ui.control;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosListService;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.SupervisorSettleService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.Messages;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import com.intermarche.pos.ui.endorsement.FunctionApprovalService;
import com.intermarche.pos.ui.ticket.TicketService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The register's CONTROL GATE (BO-04-03-08/09/10/12/14): the administered
 * alerts on register functions, payment methods, articles, barcodes, the
 * offline register and expired dates, and the justifications asked before a
 * register function.
 * <p>
 * A gesture the gate stops is HELD — its route and form, or the code scanned,
 * or the article keyed — while the cashier answers the question or picks the
 * justification on the {@code /control-gate} screen; it is then replayed once,
 * through the very road it came by, and that replay passes the gate. A
 * supervisor's validation goes through the register's endorsement ceremony,
 * restricted to the rule's profiles when it names some. Every alert that fires
 * and every justification chosen is journalled, so the electronic journal and
 * the dashboards address every use of a control (BO-04-03-14/15).
 */
@ApplicationScoped
public class ControlGateService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ControlGateService.class);

    /** The endorsement action of a scan or an article held by a supervisor alert. */
    public static final String ACTION = "CONTROL_ALERT";

    /** What a gate decision tells its caller. */
    public enum Outcome {
        /** No rule watches the gesture: the caller does as it always did. */
        NO_RULE,
        /** A rule watched it and lets it go on. */
        PASS,
        /** The gesture is refused or held: the caller stops. */
        STOP
    }

    /** How a held gesture is replayed. */
    public enum Kind {
        /** An HTTP request, replayed with its method, target and form. */
        HTTP,
        /** A code scanned, read again. */
        SCAN,
        /** An article keyed by its EAN, rung again. */
        EAN,
        /** An article keyed by its PLU, weighed and rung again. */
        PLU
    }

    /**
     * A gesture the gate holds.
     *
     * @param kind how it is replayed
     * @param method the HTTP method, for an HTTP gesture
     * @param target the path and query, for an HTTP gesture
     * @param body the form, for an HTTP gesture
     * @param code the code scanned or the EAN keyed
     * @param quantity the quantity keyed with the EAN, possibly null
     * @param ruleKey the alert holding it, or null for a justification
     * @param function the function a justification is asked for, or null
     * @param question what the screen asks
     * @param token the one-use token of its replay
     */
    public record Held(Kind kind, String method, String target, String body, String code,
                       BigDecimal quantity, String ruleKey, String function, String question,
                       String token) {
    }

    @Inject
    PosState state;

    @Inject
    PosSettingsService settings;

    @Inject
    PosListService posListService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    SupervisorSettleService supervisorSettleService;

    @Inject
    EndorsementService endorsementService;

    @Inject
    FunctionApprovalService functionApprovals;

    @Inject
    TicketService ticketService;

    @Inject
    Messages i18n;

    /** The gesture waiting for an answer on the control screen, or null. */
    Held pending;

    /** The gesture answered, waiting for its one replay, or null. */
    Held passed;

    /** The rule a replay passes once, or null. */
    String bypass;

    /** Whether the passed gesture was already replayed once by its token. */
    boolean replayed;

    /**
     * The gesture waiting for an answer, for the control screen.
     *
     * @return the held gesture, or null
     */
    public Held pending() {
        return pending;
    }

    /**
     * The administered alerts (BO-04-03-08/09).
     *
     * @return the readable rules
     */
    List<ControlRule> rules() {
        return ControlRule.parse(settings.controlAlertRules());
    }

    /**
     * The function code a justification is asked for before a route
     * (BO-04-03-10/12), when a justification list serves that code.
     *
     * @param method the HTTP method
     * @param path the request path
     * @return the function code, or null when nothing is to be justified
     */
    public String justifiedFunction(String method, String path) {
        String function = null;
        for (String raw : settings.justificationRoutes().split("\\r?\\n|\\\\n")) {
            String[] parts = raw.trim().split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String[] route = parts[0].trim().split("\\s+", 2);
            if (route.length == 2 && route[0].equalsIgnoreCase(method)
                    && route[1].trim().replaceAll("/+$", "").equals(path)) {
                function = parts[1].trim();
            }
        }
        if (function == null || function.isEmpty() || posListService.justificationsOf(function).isEmpty()) {
            return null;
        }
        return function;
    }

    /**
     * The justifications offered for the held function (BO-04-03-10/12).
     *
     * @return the choices, empty when no justification is pending
     */
    public List<PosListService.ListChoice> justifications() {
        if (pending == null || pending.function() == null) {
            return List.of();
        }
        return posListService.justificationsOf(pending.function());
    }

    // --------------------------------------------------
    // HTTP functions
    // --------------------------------------------------

    /**
     * Whether a request is the one replay of a gesture the gate let through:
     * then it passes, once.
     *
     * @param method the HTTP method
     * @param target the path and query, without the gate token
     * @param token the gate token the replay carries, or null
     * @param approvalReplay whether the request carries an endorsement continuation
     * @return true when the request passes the gate
     */
    public boolean replay(String method, String target, String token, boolean approvalReplay) {
        if (passed == null || !passed.method().equals(method) || !samePath(passed.target(), target)) {
            return false;
        }
        boolean byToken = passed.token().equals(token);
        if (!byToken && !approvalReplay) {
            return false;
        }
        if (!replayed && passed.ruleKey() != null) {
            count(passed.ruleKey());
        }
        if (byToken && !approvalReplay) {
            // The supervisor-endorsement filter may hold this very replay: its
            // own continuation passes the gate once more, never a token replay.
            replayed = true;
            passed = new Held(passed.kind(), passed.method(), passed.target(), passed.body(), passed.code(),
                    passed.quantity(), passed.ruleKey(), passed.function(), passed.question(), token());
        } else {
            replayed = false;
            passed = null;
        }
        return true;
    }

    /**
     * Compares two targets by their path and the query fields other than the
     * continuation tokens.
     *
     * @param kept the target the gate held
     * @param seen the target of the request
     * @return true when they name the same function
     */
    static boolean samePath(String kept, String seen) {
        return strip(kept).equals(strip(seen));
    }

    /**
     * Removes the continuation tokens from a target.
     *
     * @param target the target
     * @return the target without them
     */
    static String strip(String target) {
        return target.replaceAll("[?&]_pos(Approval|Gate)=[^&]*", "").replaceAll("\\?$", "");
    }

    /**
     * Decides a register function the gate watches (BO-04-03-08/09/10/12):
     * an alert first, then the justification.
     *
     * @param method the HTTP method
     * @param path the request path
     * @param target the path and query
     * @param body the form, empty for a GET
     * @return where the browser goes when the function is stopped, or null when it goes on
     */
    public String onFunction(String method, String path, String target, String body) {
        for (ControlRule rule : rules()) {
            if (rule.watchesFunction(method, path, body)) {
                Held held = new Held(Kind.HTTP, method, target, body, null, null, rule.key(), null,
                        message(rule), token());
                Outcome outcome = fire(rule, held);
                if (outcome == Outcome.STOP) {
                    return rule.type() == ControlRule.Type.YESNO ? "/control-gate" : "/";
                }
            }
        }
        String function = justifiedFunction(method, path);
        if (function != null) {
            passed = null;
            replayed = false;
            pending = new Held(Kind.HTTP, method, target, body, null, null, null, function,
                    i18n.get("ctl.justificationDemandee"), token());
            state.touch();
            return "/control-gate";
        }
        return null;
    }

    // --------------------------------------------------
    // Scans and articles
    // --------------------------------------------------

    /**
     * Decides a code read by the scanner (BO-04-03-08): the barcode rules, and
     * the offline rule while the store node is lost.
     *
     * @param code the code as read
     * @param offline whether the register has lost the store node
     * @return what the caller does
     */
    public Outcome onScan(String code, boolean offline) {
        Outcome result = Outcome.NO_RULE;
        for (ControlRule rule : rules()) {
            if (rule.watchesBarcode(code) || (offline && "offline".equals(rule.kind()))) {
                Outcome outcome = fire(rule, new Held(Kind.SCAN, null, null, null, code, null, rule.key(),
                        null, message(rule), token()));
                if (outcome == Outcome.STOP) {
                    return outcome;
                }
                result = Outcome.PASS;
            }
        }
        return result;
    }

    /**
     * Decides a GS1 reading whose date has passed (BO-04-03-08, the « date
     * expirée » control).
     *
     * @param code the code as read
     * @return what the caller does; NO_RULE leaves the GS1 expiry rule of the shop in charge
     */
    public Outcome onExpired(String code) {
        Outcome result = Outcome.NO_RULE;
        for (ControlRule rule : rules()) {
            if ("expired".equals(rule.kind())) {
                Outcome outcome = fire(rule, new Held(Kind.SCAN, null, null, null, code, null, rule.key(),
                        null, message(rule), token()));
                if (outcome == Outcome.STOP) {
                    return outcome;
                }
                result = Outcome.PASS;
            }
        }
        return result;
    }

    /**
     * Decides an article about to be rung (BO-04-03-08), by its EAN or its
     * family — the family read only when an article rule is administered.
     *
     * @param product the article
     * @param quantity the quantity keyed, possibly null
     * @return what the caller does
     */
    public Outcome onArticle(com.intermarche.pos.domain.catalog.Product product, BigDecimal quantity) {
        return onArticle(product, quantity, Kind.EAN, product.ean);
    }

    /**
     * Decides an article about to be rung (BO-04-03-08), replayed the way it
     * came in: a scanned article by its code as read, through the whole scan
     * chain, a keyed one by its EAN and quantity, a weighed one by its PLU.
     *
     * @param product the article
     * @param quantity the quantity keyed, possibly null
     * @param kind how the article came in: SCAN, EAN or PLU
     * @param code the code replayed: as scanned, the EAN or the PLU
     * @return what the caller does
     */
    public Outcome onArticle(com.intermarche.pos.domain.catalog.Product product, BigDecimal quantity,
                             Kind kind, String code) {
        Outcome result = Outcome.NO_RULE;
        String familyCode = null;
        boolean familyRead = false;
        for (ControlRule rule : rules()) {
            if (!"article".equals(rule.kind())) {
                continue;
            }
            if (!familyRead) {
                com.intermarche.pos.domain.catalog.ProductFamily family =
                        com.intermarche.pos.domain.catalog.ProductFamily.findDirectFamily(product);
                familyCode = family == null ? null : family.code;
                familyRead = true;
            }
            if (rule.watchesArticle(product.ean, familyCode)) {
                Held held = new Held(kind, null, null, null, code, quantity, rule.key(), null,
                        message(rule), token());
                Outcome outcome = fire(rule, held);
                if (outcome == Outcome.STOP) {
                    return outcome;
                }
                result = Outcome.PASS;
            }
        }
        return result;
    }

    // --------------------------------------------------
    // Firing
    // --------------------------------------------------

    /**
     * Fires one rule on a gesture (BO-04-03-09).
     * <p>
     * The rule's replay passes once; a rule that has fired its administered
     * number of times in the transaction passes. Otherwise: a refusal stops
     * the gesture; an information alert reaches the back office and lets it
     * go on; a question holds it for the control screen; a supervisor's
     * validation holds it for the endorsement ceremony, kept to the rule's
     * profiles. The cashier is notified where the rule says so, and the alert
     * is journalled.
     *
     * @param rule the rule
     * @param held the gesture as it would be held
     * @return what the caller does
     */
    Outcome fire(ControlRule rule, Held held) {
        if (rule.key().equals(bypass)) {
            bypass = null;
            count(rule.key());
            return Outcome.PASS;
        }
        if (rule.max() > 0 && state.ticket.alertCounts.getOrDefault(rule.key(), 0) >= rule.max()) {
            return Outcome.PASS;
        }
        String message = held.question();
        if (rule.notifies() && rule.type() != ControlRule.Type.BLOCK) {
            state.ticket.setWarning(message);
        }
        LOGGER.infof("Alerte de caisse %s (%s)", rule.key(), rule.type());
        trace(held, rule.type().name());
        switch (rule.type()) {
            case BLOCK -> {
                count(rule.key());
                state.ticket.setError(message);
                state.touch();
                return Outcome.STOP;
            }
            case BACKOFFICE -> {
                count(rule.key());
                supervisorSettleService.controlAlert(rule.kind() + ":" + rule.value(), message,
                        state.getOperatorName());
                return Outcome.PASS;
            }
            case YESNO -> {
                passed = null;
                replayed = false;
                pending = held;
                if (held.kind() != Kind.HTTP) {
                    state.functionPath = "/control-gate";
                }
                state.touch();
                return Outcome.STOP;
            }
            default -> {
                // LC-01-05-07: a supervisor at the till validates by being there,
                // unless the rule keeps the validation to named profiles.
                if (rule.profiles().isEmpty() && endorsementService.selfAuthorized(state)) {
                    count(rule.key());
                    return Outcome.PASS;
                }
                hold(held);
                state.endorsement.requiredProfiles = new java.util.ArrayList<>(rule.profiles());
                return Outcome.STOP;
            }
        }
    }

    /**
     * Holds a gesture for a supervisor's validation (BO-04-03-09).
     *
     * @param held the gesture
     */
    private void hold(Held held) {
        if (held.kind() == Kind.HTTP) {
            functionApprovals.hold(held.method(), held.target(), held.body());
            passed = held;
            replayed = false;
            endorsementService.requestAuthorization(state, FunctionApprovalService.ACTION);
        } else {
            pending = null;
            passed = held;
            endorsementService.requestAuthorization(state, ACTION);
        }
    }

    /**
     * Resumes a scan or an article a supervisor validated (BO-04-03-09): read
     * again once, the rule letting it through.
     */
    public void resumeAfterEndorsement() {
        Held held = passed;
        passed = null;
        if (held == null || held.kind() == Kind.HTTP) {
            return;
        }
        replayOutsideHttp(held);
    }

    /**
     * Records the cashier's answer on the control screen.
     *
     * @param choice YES or NO for a question, a justification code for a justification
     * @return where the browser goes next
     */
    public String answer(String choice) {
        Held held = pending;
        pending = null;
        if (held == null) {
            return "/";
        }
        if (held.function() != null) {
            PosListService.ListChoice justification = choice == null ? null
                    : posListService.justificationOf(held.function(), choice);
            if (justification == null) {
                state.ticket.setError(i18n.get("ctl.justificationAbandonnee"));
                state.touch();
                return "/";
            }
            technicalEventService.record(TechnicalEvent.EventType.JUSTIFICATION_CHOSEN,
                    justification.code() + " - " + justification.label(), state.auth.operatorBadgeId,
                    draftNumber(), null, held.function());
        } else if (!"YES".equals(choice)) {
            trace(held, "NON");
            state.ticket.setError(i18n.get("ctl.fonctionAbandonnee"));
            state.touch();
            return "/";
        } else {
            trace(held, "OUI");
        }
        if (held.kind() != Kind.HTTP) {
            replayOutsideHttp(held);
            return "/";
        }
        passed = held;
        replayed = false;
        if ("GET".equals(held.method())) {
            return held.target() + (held.target().contains("?") ? "&" : "?") + "_posGate=" + held.token();
        }
        return "/control-gate/continue?token=" + held.token();
    }

    /**
     * The gesture an answered POST replays, when its token is the one given.
     *
     * @param token the token of the continuation
     * @return the gesture, or null
     */
    public Held continuation(String token) {
        return passed != null && passed.token().equals(token) ? passed : null;
    }

    /**
     * Replays a scan or an article, its rule passing once.
     *
     * @param held the gesture
     */
    private void replayOutsideHttp(Held held) {
        bypass = held.ruleKey();
        if (held.kind() == Kind.SCAN) {
            ticketService.processScan(held.code());
        } else if (held.kind() == Kind.PLU) {
            ticketService.addItemByPlu(state, held.code());
        } else {
            ticketService.addItemByEan(state, held.code(), held.quantity());
        }
        bypass = null;
    }

    /**
     * Counts one firing of a rule in the transaction.
     *
     * @param ruleKey the rule
     */
    private void count(String ruleKey) {
        state.ticket.alertCounts.merge(ruleKey, 1, Integer::sum);
    }

    /**
     * Journals an alert (BO-04-03-14) under the ticket under way; an alert on
     * an article read before the ticket exists waits for the ticket to name
     * it, while one on a function outside a sale names none.
     *
     * @param held the gesture the alert decides
     * @param outcome what it did or what was answered
     */
    private void trace(Held held, String outcome) {
        TechnicalEvent event = technicalEventService.record(TechnicalEvent.EventType.CONTROL_ALERT,
                held.ruleKey() + " " + outcome, state.auth.operatorBadgeId, draftNumber(), null, held.ruleKey());
        if (held.kind() != Kind.HTTP) {
            state.ticket.awaitTicket(event);
        }
    }

    /**
     * The number of the ticket under way, or null before its first article.
     *
     * @return the number
     */
    private String draftNumber() {
        if (state.payment.ticketDbId == null) {
            return null;
        }
        Ticket draft = Ticket.findById(state.payment.ticketDbId);
        return draft == null ? null : draft.ticketNumber;
    }

    /**
     * The message a rule shows: its own, or the register's naming the target
     * — its kind in the register's language, then what it names.
     *
     * @param rule the rule
     * @return the message
     */
    private String message(ControlRule rule) {
        if (rule.text() != null) {
            return rule.text();
        }
        String kind = i18n.code("ctl.rs21-cible-", rule.kind());
        return i18n.format("ctl.alerte", rule.value().isEmpty() ? kind : kind + " " + rule.value());
    }

    /**
     * A fresh one-use token.
     *
     * @return the token
     */
    private static String token() {
        return UUID.randomUUID().toString();
    }
}
