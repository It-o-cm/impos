package com.intermarche.pos.ui.hardware.terminal;

import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integrated Verifone monetique client (LC-07-04-01) — SKELETON. The
 * register talks to a Verifone monetique client installed locally; this
 * class owns the exchange thread, the in-flight discipline and the outcome
 * mapping, and delegates the wire work to {@link VerifoneTransport} and
 * {@link VerifoneFrameCodec}.
 * <p>
 * WHAT WORKS NOW: selection via {@code pos.tpe.mode=verifone}, reachability
 * probe, single-transaction discipline, abandon anti-repeat (LC-07-07-02),
 * clean operator-facing failure when the exchange cannot run.
 * <p>
 * WHAT IS PENDING THE PROTOCOL SPECIFICATION (all marked TODO): the debit,
 * credit and abandon request layouts; the response tag dictionary beyond
 * {@link VerifoneTags}; the print frames and TNA handling (LC-07-04-10/12);
 * the session requests c18 with {D5Y} serial capture (LC-01-01-06), the
 * conditional c2g maintenance (LC-01-01-07) and the c2h logoff
 * (LC-01-02-09); the degraded-mode server forcing {VWAY}4 (LC-07-08-02).
 * Every exchange currently ends on the callback's {@code onError} with an
 * explicit message instead of guessing frames against a real terminal.
 */
public class VerifoneTerminalClient implements PaymentTerminalClient {

    private static final Logger LOG = Logger.getLogger(VerifoneTerminalClient.class);

    /** Operator-facing message while the protocol is not implemented. */
    static final String MSG_NOT_IMPLEMENTED =
            "MONETIQUE VERIFONE NON DISPONIBLE - SPECIFICATION PROTOCOLE REQUISE";

    /** Operator-facing message when the monetique client is unreachable. */
    static final String MSG_UNREACHABLE = "CLIENT MONETIQUE INJOIGNABLE";

    /** The TCP transport to the local monetique client. */
    private final VerifoneTransport transport;

    /**
     * Single exchange thread: the terminal handles one transaction at a
     * time, and serializing here is what makes the abandon anti-repeat
     * (LC-07-07-02) structural rather than defensive.
     */
    private final ExecutorService exchanges =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "verifone-terminal");
                t.setDaemon(true);
                return t;
            });

    /** True while a transaction is in flight (busy discipline). */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /** True when an abandon was requested for the in-flight transaction. */
    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    /**
     * Creates the client over the given transport.
     *
     * @param transport the TCP transport to the local monetique client
     */
    public VerifoneTerminalClient(VerifoneTransport transport) {
        this.transport = transport;
    }

    /**
     * Starts a debit exchange on the terminal thread.
     *
     * @param amount the amount to debit
     * @param callback the decision receiver
     */
    @Override
    public void requestDebit(BigDecimal amount, TerminalTransactionCallback callback) {
        submitExchange("debit", amount, callback);
    }

    /**
     * Starts a credit (refund) exchange on the terminal thread
     * (LC-05-06-03).
     *
     * @param amount the amount to credit
     * @param callback the decision receiver
     */
    @Override
    public void requestCredit(BigDecimal amount, TerminalTransactionCallback callback) {
        submitExchange("credit", amount, callback);
    }

    /**
     * Requests the abandon of the in-flight transaction. Repeated presses
     * set the same flag once — the exchange thread emits at most one
     * abandon to the monetique client (LC-07-07-02).
     */
    @Override
    public void abort() {
        if (busy.get()) {
            abortRequested.set(true);
            // TODO(spec Verifone): send the abandon request to the monetique
            // client instead of only flagging the in-flight exchange.
        }
    }

    /**
     * Session-opening hook: c18 serial-number request and conditional c2g
     * maintenance (LC-01-01-06/07).
     */
    @Override
    public void onRegisterOpened() {
        // TODO(spec Verifone): send c18, capture {D5Y} into the technical
        // journal, then trigger c2g when the maintenance conditions hold.
        LOG.info("Verifone session hooks (c18/c2g) pending protocol specification");
    }

    /**
     * Session-closing hook: c2h logoff (LC-01-02-09).
     */
    @Override
    public void onRegisterClosed() {
        // TODO(spec Verifone): send the c2h logoff request.
        LOG.info("Verifone logoff (c2h) pending protocol specification");
    }

    /**
     * Returns the implementation name.
     *
     * @return "verifone"
     */
    @Override
    public String name() {
        return "verifone";
    }

    /**
     * Queues one exchange, enforcing the single-transaction discipline, and
     * maps its termination onto the callback.
     *
     * @param operation "debit" or "credit"
     * @param amount the transaction amount
     * @param callback the decision receiver
     */
    private void submitExchange(String operation, BigDecimal amount,
                                TerminalTransactionCallback callback) {
        if (!busy.compareAndSet(false, true)) {
            callback.onError("TRANSACTION MONETIQUE DEJA EN COURS");
            return;
        }
        abortRequested.set(false);
        exchanges.submit(() -> {
            try {
                if (!transport.isReachable()) {
                    callback.onError(MSG_UNREACHABLE);
                    return;
                }
                String response = transport.exchange(buildRequest(operation, amount));
                handleResponse(response, amount, callback);
            } catch (UnsupportedOperationException e) {
                LOG.warnf("Verifone exchange not implemented: %s", e.getMessage());
                callback.onError(MSG_NOT_IMPLEMENTED);
            } catch (Exception e) {
                LOG.errorf(e, "Verifone %s exchange failed", operation);
                callback.onError(MSG_UNREACHABLE);
            } finally {
                busy.set(false);
                abortRequested.set(false);
            }
        });
    }

    /**
     * Builds the request frame of an operation.
     *
     * @param operation "debit" or "credit"
     * @param amount the transaction amount
     * @return the encoded request frame
     */
    private String buildRequest(String operation, BigDecimal amount) {
        // TODO(spec Verifone): real request layout (message code, POS
        // number, amount format, currency, operation discriminator).
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("OPERATION-PLACEHOLDER", operation);
        tags.put("AMOUNT-PLACEHOLDER", amount.toPlainString());
        return VerifoneFrameCodec.encode(tags);
    }

    /**
     * Maps a final response frame onto the callback: accepted, refused with
     * a TNA print frame, or the real payment mean via {D16}/{D46}.
     *
     * @param response the raw response frame
     * @param amount the transaction amount
     * @param callback the decision receiver
     */
    private void handleResponse(String response, BigDecimal amount,
                                TerminalTransactionCallback callback) {
        // TODO(spec Verifone): decision tag, TNA detection (LC-07-04-12),
        // print frames (LC-07-04-10/11), mean mapping {D16}/{D46}
        // (LC-07-07-04). Until then a response cannot be trusted:
        Map<String, String> tags = VerifoneFrameCodec.decode(response);
        TerminalOutcome outcome = TerminalOutcome.ofAmount(amount);
        outcome.rawResponse = response;
        outcome.meanCode = tags.getOrDefault(VerifoneTags.PAYMENT_MEAN,
                tags.get(VerifoneTags.PAYMENT_MEAN_FALLBACK));
        callback.onError(MSG_NOT_IMPLEMENTED);
    }
}
