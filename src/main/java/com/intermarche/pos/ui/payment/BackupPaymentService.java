package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.Store;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Takes a card settlement on a mobile terminal when the integrated monetics is down
 * ({@code LC-07-07-06} to {@code -09}).
 *
 * <p>THE TWO MACHINES NEVER TALK. The register shows a QR code carrying what to
 * charge; the handheld takes the card and shows a QR code carrying what it accepted;
 * the register reads that one back. The customer's card never touches this till and
 * this till never touches the monetics — which is the whole point, since the
 * monetics is exactly what has failed.
 *
 * <p>What the register owes in return is suspicion. A code on a screen can be
 * photographed, replayed at the next till, or simply be the one still up from the
 * previous sale, so an answer is accepted only when its signature holds AND its
 * transaction, point of sale and register match the request this till emitted. Every
 * other outcome is one refusal with one message: the operator cannot act on the
 * difference between a forged code and a stale one.
 *
 * <p>The manual path ({@code LC-07-07-09}) exists because a till whose scanner
 * cannot read 2D codes still has to be able to finish the sale. It trades the
 * signature for an operator's word, so the shop may require a supervisor for it, and
 * the settlement records which of the two it was.
 */
@ApplicationScoped
public class BackupPaymentService {

    private static final Logger LOG = Logger.getLogger(BackupPaymentService.class);

    /** Refusal shown when the scanned answer cannot be trusted. */
    static final String REFUSED = "SECOURS MONETIQUE : CODE INVALIDE OU ETRANGER A CETTE VENTE";

    /** Refusal shown when no request has been emitted yet. */
    static final String NO_REQUEST = "SECOURS MONETIQUE : AUCUNE DEMANDE EN COURS";

    /** Refusal shown when the manual amount typed is unusable. */
    static final String BAD_AMOUNT = "MONTANT ACCEPTE INVALIDE";

    /** The label a settlement carries when the scheme is not in the administered table. */
    static final String GENERIC_LABEL = "SECOURS MONETIQUE";

    /** The action code journalled when a supervisor allows a manual validation. */
    static final String MANUAL_ACTION = "BACKUP_PAYMENT_MANUAL";

    /** Registers the settlement once this service has accepted it. */
    @Inject
    PaymentService paymentService;

    /** Names this register and its ticket sequence in the payloads. */
    @Inject
    TicketNumberService ticketNumberService;

    /** The administered scheme table and the manual-endorsement rule. */
    @Inject
    PosSettingsService posSettingsService;

    /** Checks the supervisor credential and journals the decision. */
    @Inject
    EndorsementService endorsementService;

    /** Renders the request payload as the square the handheld reads. */
    @Inject
    com.intermarche.pos.ui.customer.QrCodeService qrCodeService;

    /** Prints the card slip the mobile terminal sent back. */
    @Inject
    com.intermarche.pos.ui.hardware.HardwareService hardwareService;

    /** The secret shared with the backup-monetics application; absent = a default. */
    @ConfigProperty(name = "pos.backup-payment.secret")
    Optional<String> secret;

    /**
     * Opens the backup-monetics panel and emits the request for the remaining due
     * ({@code LC-07-07-06/07}).
     *
     * @param state the current POS state
     */
    public void openPanel(PosState state) {
        state.payment.clearCreditPanel();
        state.payment.clearCurrencyPanel();
        state.payment.clearBackupPanel();
        state.payment.backupPanelOpen = true;
        BigDecimal remaining = state.getRemaining();
        if (remaining.signum() <= 0) {
            state.payment.backupError = "RIEN A REGLER";
            state.touch();
            return;
        }
        BackupPaymentTicket.Request request = new BackupPaymentTicket.Request(
                "D",
                BackupPaymentTicket.toCents(remaining),
                BackupPaymentTicket.toCents(mealEligible(state)),
                transactionNumber(state),
                pdvNumber(),
                ticketNumberService.getTerminalId(),
                LocalDateTime.now());
        state.payment.backupRequest = request;
        state.payment.backupRequestSvg = qrCodeService.toSvg(request.encode(secret.orElse("")));
        state.touch();
    }

    /**
     * Closes the panel, abandoning any pending request.
     *
     * @param state the current POS state
     */
    public void closePanel(PosState state) {
        state.payment.clearBackupPanel();
        state.touch();
    }

    /**
     * Validates the answer scanned off the mobile terminal ({@code LC-07-07-08}).
     *
     * @param state the current POS state
     * @param payload the scanned text
     * @return true when the settlement was registered
     */
    public boolean validateScanned(PosState state, String payload) {
        BackupPaymentTicket.Request request = state.payment.backupRequest;
        if (request == null) {
            state.payment.backupError = NO_REQUEST;
            state.touch();
            return false;
        }
        BackupPaymentTicket.Response response =
                BackupPaymentTicket.decodeResponse(payload, secret.orElse(""));
        if (response == null || !BackupPaymentTicket.matches(request, response)) {
            // ONE message for every way it can fail. A forged signature, a code from
            // the till next door and a code from the previous sale are all "this is
            // not the answer to what I asked", and telling them apart on screen would
            // only tell an attacker which part they got wrong.
            state.payment.backupError = REFUSED;
            state.touch();
            LOG.warnf("Secours monétique refusé pour la transaction %s", request.transactionNumber());
            return false;
        }
        register(state, BackupPaymentTicket.toEuro(response.acceptedCents()),
                methodLabel(response.methodId()), request.transactionNumber(), false);
        printCardReceipt(response.receiptBase64());
        return true;
    }

    /**
     * Registers the settlement from an amount the operator keyed in
     * ({@code LC-07-07-09}), the scanner being unable to read the answer.
     *
     * @param state the current POS state
     * @param acceptedAmount the amount the mobile terminal accepted
     * @param login the supervisor's login, ignored when none is required
     * @param password the supervisor's password
     * @return true when the settlement was registered
     */
    public boolean validateManually(PosState state, BigDecimal acceptedAmount, String login,
            String password) {
        BackupPaymentTicket.Request request = state.payment.backupRequest;
        if (request == null) {
            state.payment.backupError = NO_REQUEST;
            state.touch();
            return false;
        }
        if (acceptedAmount == null || acceptedAmount.signum() <= 0) {
            state.payment.backupError = BAD_AMOUNT;
            state.touch();
            return false;
        }
        if (posSettingsService.backupManualEndorsement()
                && !endorsementService.operatorIsSupervisor(state)
                && !endorsementService.authorize(login, password, MANUAL_ACTION)) {
            state.payment.backupError = "AUTORISATION REFUSEE";
            state.touch();
            return false;
        }
        register(state, acceptedAmount, GENERIC_LABEL, request.transactionNumber(), true);
        return true;
    }

    /**
     * Registers the settlement and closes the panel.
     *
     * @param state the current POS state
     * @param amount the amount the mobile terminal accepted
     * @param label the scheme to record
     * @param transactionNumber the sale the two codes were matched on
     * @param manual true when the outcome was keyed in rather than scanned
     */
    private void register(PosState state, BigDecimal amount, String label,
            String transactionNumber, boolean manual) {
        paymentService.processBackupPayment(state, amount, label, transactionNumber, manual);
        state.payment.clearBackupPanel();
        state.touch();
    }

    /**
     * Prints the card slip the mobile terminal sent back, when it sent one.
     *
     * <p>A slip that cannot be decoded is dropped rather than printed as noise: the
     * settlement itself is already registered, and a page of mojibake handed to a
     * customer is worse than no slip at all.
     *
     * @param base64 the slip as the response carried it, possibly empty
     */
    private void printCardReceipt(String base64) {
        if (base64 == null || base64.isBlank()) {
            return;
        }
        try {
            String text = new String(java.util.Base64.getDecoder().decode(base64),
                    java.nio.charset.StandardCharsets.UTF_8);
            hardwareService.printReceipt(text);
            hardwareService.cutPaper();
        } catch (IllegalArgumentException e) {
            LOG.warnf("Ticket carte du secours monétique illisible : %s", e.getMessage());
        }
    }

    /**
     * Resolves a scheme identifier against the administered table
     * ({@code LC-07-07-08}); an identifier the table does not carry falls back to
     * the generic label rather than being lost.
     *
     * @param methodId the identifier the mobile terminal reported
     * @return the label to record on the settlement
     */
    String methodLabel(String methodId) {
        if (methodId == null || methodId.isBlank()) {
            return GENERIC_LABEL;
        }
        return methodLabels().getOrDefault(methodId.trim().toUpperCase(), GENERIC_LABEL);
    }

    /**
     * Parses the administered scheme table, one {@code ID=LABEL} pair per comma.
     *
     * @return the table, empty when nothing is administered
     */
    private Map<String, String> methodLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        String administered = posSettingsService.backupMethodLabels();
        if (administered == null || administered.isBlank()) {
            return labels;
        }
        for (String pair : administered.split(",")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            labels.put(pair.substring(0, equals).trim().toUpperCase(),
                    pair.substring(equals + 1).trim());
        }
        return labels;
    }

    /**
     * The part of the remaining due eligible for meal vouchers, as the engine last
     * reported it; zero when it reported none.
     *
     * @param state the current POS state
     * @return the meal-eligible base, never null
     */
    private BigDecimal mealEligible(PosState state) {
        return state.payment.valuationMealEligible == null
                ? BigDecimal.ZERO : state.payment.valuationMealEligible;
    }

    /**
     * The number identifying the sale in both payloads: the draft ticket number when
     * there is one, and the register's own identifier otherwise — training mode
     * writes no draft, and the panel must still be demonstrable.
     *
     * @param state the current POS state
     * @return the transaction number carried by the codes
     */
    private String transactionNumber(PosState state) {
        if (state.payment.ticketDbId != null) {
            com.intermarche.pos.domain.ticket.Ticket ticket =
                    com.intermarche.pos.domain.ticket.Ticket.findById(state.payment.ticketDbId);
            if (ticket != null && ticket.ticketNumber != null) {
                return ticket.ticketNumber;
            }
        }
        return "FORMATION-" + ticketNumberService.getTerminalId();
    }

    /**
     * The point-of-sale number carried by both payloads.
     *
     * @return the shop code, or an empty string when the shop is not configured
     */
    private String pdvNumber() {
        Store store = Store.findAll().firstResult();
        return store == null || store.code == null ? "" : store.code;
    }
}
