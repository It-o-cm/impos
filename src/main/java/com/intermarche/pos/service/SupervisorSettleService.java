package com.intermarche.pos.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The register's UPWARD NOTICES to the store node: a supervisor settled
 * this lane's open requests at the till ({@code BO-04-02-03}), and an
 * article the referential does not know was just scanned
 * ({@code BO-04-02-06}).
 *
 * <p>A cashier calls for a supervisor, and more often than not the
 * supervisor is standing three metres away and settles it before anybody at
 * the back office has read the banner. Until here the banner stayed up, and
 * a manager walked to a lane whose problem was already solved. The register
 * is the only one who knows, so the register says so.
 *
 * <p>FIRE AND FORGET, and deliberately so: neither an endorsement nor a
 * scan must fail because the node is down or slow. Every call carries a
 * three-second timeout and swallows everything — the worst case is a banner
 * a manager acknowledges by hand, or an alert that never shows, which is
 * exactly the behaviour of a shop that administers none.
 */
@ApplicationScoped
public class SupervisorSettleService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SupervisorSettleService.class);

    /** How long the register waits for the node before giving up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** The client talking to the node, shared like the supervisor call's. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /** Says whether this register has a store node, and where it is. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** This register's identifier, the address the node files the calls under. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /** Shared token sent with the notice; absent = the node is open. */
    @ConfigProperty(name = "pos.sync.token")
    Optional<String> token;

    /**
     * Tells the node that this register's open requests were settled at the
     * till; silent when the register stands alone.
     */
    public void settledAtTill() {
        LOGGER.info("Entering method settledAtTill");
        post("/api/supervisor/settled", JsonNodeFactory.instance.objectNode().put("terminalId", terminalId).toString(),
                "l'avis de traitement en caisse");
        LOGGER.info("Exiting method settledAtTill");
    }

    /** The reason a supervisor-authorisation request is filed under at the back office. */
    public static final String AUTHORIZATION_REASON = "AUTORISATION SUPERVISEUR";

    /**
     * Sends a supervisor-authorisation request to the back office the moment
     * the register asks for one ({@code LC-01-05-08}).
     *
     * <p>The SAME board as a supervisor call, where the back office already
     * authorises, refuses or sends the request back to the till: the request
     * arrives there without the cashier having to call anybody, and the
     * answer comes down the order channel the register already collects.
     *
     * @param gesture what the authorisation is asked for, as the cashier reads it
     * @param operator who asked for it, possibly null
     * @return true when this register has a back office to send it to
     */
    public boolean authorizationRequested(String gesture, String operator) {
        LOGGER.info("Entering method authorizationRequested with gesture: " + gesture);
        if (!syncOutboxService.isEnabled()) {
            LOGGER.info("Exiting method authorizationRequested");
            return false;
        }
        post("/api/supervisor/call",
                call(operator, AUTHORIZATION_REASON, gesture),
                "la demande d'autorisation");
        LOGGER.info("Exiting method authorizationRequested");
        return true;
    }

    /**
     * Withdraws this register's pending requests from the back office when the
     * cashier abandons the gesture that asked for an authorisation
     * ({@code LC-01-05-08}).
     */
    public void authorizationWithdrawn() {
        LOGGER.info("Entering method authorizationWithdrawn");
        post("/api/supervisor/cancel", JsonNodeFactory.instance.objectNode().put("terminalId", terminalId).toString(),
                "le retrait de la demande d'autorisation");
        LOGGER.info("Exiting method authorizationWithdrawn");
    }

    /**
     * Raises a service request at the back office for an article the
     * referential does not know ({@code BO-04-02-06}).
     *
     * <p>The SAME channel as a supervisor call, and on purpose: an unknown
     * article is a lane asking for help, and the back office already has a
     * board for that — with its priority, its status and its three answers.
     * A second channel would have meant a second board.
     *
     * @param code the code the cashier scanned, possibly null
     * @param operator who scanned it, possibly null
     */
    public void unknownItem(String code, String operator) {
        LOGGER.info("Entering method unknownItem with code: " + code);
        String scanned = code == null || code.isBlank() ? "?" : code.trim();
        post("/api/supervisor/call",
                call(operator, "ARTICLE INCONNU", "Code " + scanned),
                "l'alerte article inconnu");
        LOGGER.info("Exiting method unknownItem");
    }

    /**
     * Raises an information alert at the back office for an administered
     * register alert ({@code BO-04-03-09}, type « alerte information »): the
     * same board as a supervisor call, the alert's target as its reason.
     *
     * @param target what the alert watches, e.g. {@code payment:CHEQUE}
     * @param message the alert's message
     * @param operator who triggered it, possibly null
     */
    public void controlAlert(String target, String message, String operator) {
        LOGGER.info("Entering method controlAlert with target: " + target);
        post("/api/supervisor/call",
                call(operator, "ALERTE CAISSE", target + " — " + message),
                "l'alerte de caisse");
        LOGGER.info("Exiting method controlAlert");
    }

    /**
     * Posts a notice to the node, swallowing whatever it fails on; silent
     * when this register stands alone.
     *
     * @param path the node path to post to
     * @param body the JSON body
     * @param what the notice's name, for the log of a failure
     */
    private void post(String path, String body, String what) {
        if (!syncOutboxService.isEnabled()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(syncOutboxService.getStoreUrl() + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            String sharedToken = token.orElse("");
            if (!sharedToken.isBlank()) {
                builder.header("X-Sync-Token", sharedToken);
            }
            HttpResponse<String> response =
                    CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warnf("Le nœud a refusé %s (%d)", what, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warnf("%s interrompu", what);
        } catch (Exception e) {
            // The gesture has already happened at the till: the notice is a
            // courtesy to the back office, never a condition.
            LOGGER.warnf("%s impossible (%s)", what, e.getMessage());
        }
    }

    /**
     * Builds the body of a request filed on the supervisor board, by the JSON
     * library: a quote, a backslash or a line break in the operator's name or
     * the message cannot break the document.
     *
     * @param operator who asked, possibly null (sent empty)
     * @param reason the reason the request is filed under
     * @param message what the request is about, possibly null (sent empty)
     * @return the JSON body
     */
    private String call(String operator, String reason, String message) {
        return JsonNodeFactory.instance.objectNode()
                .put("terminalId", terminalId)
                .put("operator", operator == null ? "" : operator)
                .put("reason", reason)
                .put("message", message == null ? "" : message)
                .toString();
    }
}
