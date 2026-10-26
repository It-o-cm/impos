package com.intermarche.pos.ui.auth;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.service.PosSettingsService;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Closing the register: what has to be true first, and what happens once it is
 * (LC-01-02-01/02/03/06/10/11).
 *
 * <p>Closing used to be a LINK to the lock screen — one gesture, no question
 * asked. The requirement puts two questions in front of it, both administered:
 * the operator's password (BO-10-02-24) and the parked tickets nobody resumed
 * (BO-10-02-08). Neither is a refusal: a password can be forgotten and a
 * supervisor can then force the close (LC-01-02-06), and that forcing is
 * journalled as such.
 *
 * <p>The checks are asked in a fixed order — pending tickets first, password
 * second — so an operator is never asked for a code only to be told afterwards
 * that they cannot leave anyway.
 */
@ApplicationScoped
public class SessionCloseService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(SessionCloseService.class);

    /** The action code a forced close is endorsed under. */
    public static final String FORCE_CLOSE_ACTION = "SESSION_FORCE_CLOSE";

    /** Receipt timestamp format. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** What stands between the operator and a closed register. */
    public enum Obstacle {

        /** Nothing: the register may close now. */
        NONE,

        /**
         * A sale is in progress (EXP-11): the register cannot be fermée while a
         * ticket is being built. Unlike the two below this is NOT forceable — the
         * operator finishes, parks or abandons the ticket first, or verrouille
         * instead of fermer.
         */
        IN_TRANSACTION,

        /** Parked tickets are still waiting and an endorsement is required. */
        PENDING_TICKETS,

        /** The operator must key their password. */
        PASSWORD
    }

    /** The administered parameters governing the close. */
    @Inject PosSettingsService posSettingsService;

    /** The credentials check, shared with the opening. */
    @Inject AuthService authService;
    @Inject com.intermarche.pos.service.CashSessionService cashSessionService;
    @Inject com.intermarche.pos.service.TicketPersistenceService ticketPersistenceService;

    /** The journal, where an opening, a close and a forcing are recorded. */
    @Inject TechnicalEventService technicalEventService;

    /** The printer, for the opening and closing receipts. */
    @Inject TicketPrinterService ticketPrinterService;

    /** This register's identifier, printed on both receipts. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Returns what stands between the operator and a closed register.
     *
     * @return the first obstacle, or {@link Obstacle#NONE}
     */
    public Obstacle obstacle() {
        return obstacle(null);
    }

    /**
     * Returns what stands between the operator and a closed register, the sale in
     * progress checked first (EXP-11).
     *
     * @param state the current register state, read for the in-progress ticket;
     *        null skips the in-transaction check (the historical no-arg contract)
     * @return the first obstacle, or {@link Obstacle#NONE}
     */
    public Obstacle obstacle(PosState state) {
        LOGGER.info("Entering method obstacle");
        if (state != null && !state.ticket.items.isEmpty()) {
            // EXP-11: a fermeture during a transaction is impossible. This is a
            // hard block, ordered before the forceable obstacles.
            LOGGER.info("Exiting method obstacle with IN_TRANSACTION");
            return Obstacle.IN_TRANSACTION;
        }
        if (posSettingsService.closeEndorsementOnPending() && pendingCount() > 0) {
            LOGGER.info("Exiting method obstacle with PENDING_TICKETS");
            return Obstacle.PENDING_TICKETS;
        }
        if (posSettingsService.passwordRequiredOnClose()) {
            LOGGER.info("Exiting method obstacle with PASSWORD");
            return Obstacle.PASSWORD;
        }
        LOGGER.info("Exiting method obstacle with NONE");
        return Obstacle.NONE;
    }

    /**
     * Counts the tickets parked on this register and never resumed.
     *
     * @return the number of waiting carts
     */
    public long pendingCount() {
        LOGGER.info("Entering method pendingCount");
        long count = Ticket.count("status = ?1 and terminalId = ?2",
                Ticket.TicketStatus.PARKED, terminalId);
        LOGGER.info("Exiting method pendingCount with " + count);
        return count;
    }

    /**
     * Checks the password the operator keyed to close.
     *
     * @param state the current register state, read for the operator
     * @param password the keyed password
     * @return true when it matches the signed-in operator's credentials
     */
    public boolean passwordMatches(PosState state, String password) {
        LOGGER.info("Entering method passwordMatches");
        boolean ok = passwordOutcome(state, password) == AuthService.CheckOutcome.GRANTED;
        LOGGER.info("Exiting method passwordMatches with " + ok);
        return ok;
    }

    /**
     * Checks the password the operator keyed to close, telling a WRONG CODE from
     * a LOCKED ACCOUNT.
     *
     * <p>THE CLOSING IS WHERE THE LOCKOUT HURTS MOST. The operator is signed in,
     * their shift is over, and the only thing between them and the end of the day
     * is their own code. Answering "code opérateur incorrect" to an account under
     * PIN lockout sends them retyping a code that is right, at a register that
     * will keep refusing it for the whole lockout window — a definitive block as
     * far as they can tell. Naming the lockout is what turns it back into a wait,
     * or a reason to call the supervisor.
     *
     * @param state the current register state, read for the operator
     * @param password the keyed password
     * @return which of the three outcomes the keyed password got
     */
    public AuthService.CheckOutcome passwordOutcome(PosState state, String password) {
        LOGGER.info("Entering method passwordOutcome");
        if (state.auth.operatorBadgeId == null || password == null || password.isBlank()) {
            // Nothing was presented, so nothing is locked: an empty field is a
            // refusal the operator can act on by filling it.
            LOGGER.info("Exiting method passwordOutcome with DENIED");
            return AuthService.CheckOutcome.DENIED;
        }
        AuthService.CredentialStatus status =
                authService.checkCredentials(state.auth.operatorBadgeId, password);
        AuthService.CheckOutcome outcome = status.isSuccess()
                ? AuthService.CheckOutcome.GRANTED
                : (status.locked ? AuthService.CheckOutcome.LOCKED
                                 : AuthService.CheckOutcome.DENIED);
        LOGGER.info("Exiting method passwordOutcome with " + outcome);
        return outcome;
    }

    /**
     * Closes the register: journals the close, prints the receipt when the
     * back office asked for one, and logs the operator out.
     *
     * @param state the current register state
     * @param forcedBy who or what forced this close, null when the operator
     *        closed the register himself
     */
    public void close(PosState state, String forcedBy) {
        LOGGER.info("Entering method close with forcedBy: " + forcedBy);
        // A forced close from the PAUSED lock screen (EXP-96) has no logged-in
        // operator: the abandoned post's identity is held in the locked* fields.
        String badge = state.auth.operatorBadgeId != null
                ? state.auth.operatorBadgeId : state.auth.lockedOperatorBadgeId;
        String name = state.auth.operatorBadgeId != null
                ? state.auth.operatorName : state.auth.lockedOperatorName;
        if (forcedBy != null) {
            technicalEventService.log(TechnicalEvent.EventType.SUPERVISOR_CALLED,
                    "Fermeture forcée de la caisse " + terminalId + " — " + forcedBy, badge);
        }
        if (posSettingsService.printCloseReceipt()) {
            printReceipt("FERMETURE DE CAISSE", badge, name, forcedBy);
        }
        authService.logout(state);
        LOGGER.info("Exiting method close");
    }

    /**
     * LC-01-02-07: persist the actual session close before acknowledging a remote order.
     * An unfinished settlement is retained until it can be completed or cancelled.
     * @param state current register, including a paused operator
     * @param cause source of the remote order
     * @return false while an unsettled payment prevents safe closure
     */
    @jakarta.transaction.Transactional
    public boolean closeRemotely(PosState state, String cause) {
        return closeRemotely(state, cause, false);
    }

    /**
     * Closes the register on a remote order, the host's declaration made
     * automatically where the order asks for it (BO-09-03-01), and prints the
     * technical ticket of the forced close where the store administers it
     * (BO-09-01-06, BO-09-02-03).
     *
     * <p>An automatic declaration closes the session on its THEORETICAL
     * amount, as the island's own automatic declaration does, and prints its
     * Z; any other remote close is a close without count.
     *
     * @param state current register, including a paused operator
     * @param cause source of the remote order
     * @param autoDeclaration whether the host is declared automatically
     * @return false while an unsettled payment prevents safe closure
     */
    @jakarta.transaction.Transactional
    public boolean closeRemotely(PosState state, String cause, boolean autoDeclaration) {
        if (!state.payment.transactionComplete
                && (state.payment.paymentInProgress || state.payment.paidAmount.signum() > 0)) {
            return false;
        }
        Long cashierId = state.auth.operatorId != null
                ? state.auth.operatorId : state.auth.lockedOperatorId;
        // An unpaid draft must not reappear attached to a closed session.
        if (!state.payment.transactionComplete && state.payment.ticketDbId != null) {
            ticketPersistenceService.cancelDraft(state.payment.ticketDbId);
        }
        long parked = pendingCount();
        com.intermarche.pos.domain.session.CashSession open = cashSessionService.getOpenSession();
        String sessionNumber = open == null ? null : open.sessionNumber;
        String badge = state.auth.operatorBadgeId != null
                ? state.auth.operatorBadgeId : state.auth.lockedOperatorBadgeId;
        String name = state.auth.operatorBadgeId != null
                ? state.auth.operatorName : state.auth.lockedOperatorName;
        if (autoDeclaration) {
            com.intermarche.pos.service.CashSessionService.SessionReport report =
                    cashSessionService.closeSession(cashierId, null, null, null, true);
            if (report != null) {
                ticketPrinterService.printSessionReport(report);
            }
        } else {
            cashSessionService.forceCloseSession(cashierId);
        }
        // BO-09-01-06, BO-09-02-03: the forced close leaves its technical
        // ticket at the till and its line in the electronic journal.
        // A post left locked or signed out is closed all the same: its ticket
        // then names the host who opened the session being closed.
        com.intermarche.pos.domain.people.Employee opener = open == null ? null : open.openingCashier;
        String shownBadge = badge;
        String shownName = name;
        if (badge == null && opener != null) {
            shownBadge = opener.badgeId;
            shownName = opener.getFullName();
        }
        if (posSettingsService.periodForcedCloseTicket()) {
            printTechnicalTicket(sessionNumber, shownBadge, shownName, cause, autoDeclaration, parked);
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Ticket technique de fermeture forcée imprimé — " + cause, shownBadge);
        }
        if (badge != null) {
            close(state, cause);
        } else {
            // A recovered operator-free draft must not survive its cash-session closure.
            authService.logout(state);
        }
        return true;
    }

    /**
     * Prints the TECHNICAL TICKET of a forced close (BO-09-01-06,
     * BO-09-02-03): the register, the session it closed, its operator, what
     * forced it, how the drawer was declared and the tickets on hold the close
     * purged.
     *
     * @param sessionNumber the session closed, or null when none was open
     * @param badge the operator's badge, or null
     * @param name the operator's name, or null
     * @param cause what forced the close
     * @param autoDeclaration whether the host was declared automatically
     * @param parked the tickets on hold the close purged
     */
    void printTechnicalTicket(String sessionNumber, String badge, String name, String cause,
                              boolean autoDeclaration, long parked) {
        List<String> lines = List.of(
                "TICKET TECHNIQUE",
                "FERMETURE FORCÉE DE CAISSE",
                "",
                "Caisse      : " + terminalId,
                "Session     : " + (sessionNumber == null ? "—" : sessionNumber),
                "Opérateur   : " + (badge == null ? "—" : badge),
                "Nom         : " + (name == null ? "—" : name),
                "Cause       : " + cause,
                "Déclaration : " + (autoDeclaration ? "automatique (théorique)" : "sans comptage"),
                "En attente  : " + parked + " ticket(s) annulé(s)",
                "Le          : " + STAMP.format(LocalDateTime.now()));
        ticketPrinterService.printRenderedTicket(String.join("\n", lines));
    }

    /**
     * Prints the opening receipt, when the back office asked for one.
     *
     * @param state the current register state, read for the operator
     */
    public void printOpening(PosState state) {
        LOGGER.info("Entering method printOpening");
        if (posSettingsService.printOpenReceipt()) {
            printReceipt("OUVERTURE DE CAISSE", state.auth.operatorBadgeId,
                    state.auth.operatorName, null);
        }
        LOGGER.info("Exiting method printOpening");
    }

    /**
     * Renders and prints one opening or closing receipt.
     *
     * <p>Deliberately spare — register, operator, moment. The requirement asks
     * for « des informations telles que le n° caisse, le n° opérateur et nom
     * opérateur »; anything more would be a session report, which is the Z
     * ticket's job and carries figures this slip must not be confused with.
     *
     * @param title the receipt's heading
     * @param badge the operator's badge identifier, possibly null
     * @param name the operator's full name, possibly null
     * @param forcedBy who or what forced the close, null when nothing did
     */
    private void printReceipt(String title, String badge, String name, String forcedBy) {
        List<String> lines = List.of(
                title,
                "",
                "Caisse    : " + terminalId,
                "Opérateur : " + (badge == null ? "—" : badge),
                "Nom       : " + (name == null ? "—" : name),
                "Le        : " + STAMP.format(LocalDateTime.now()),
                forcedBy == null ? "" : "FERMETURE FORCÉE — " + forcedBy);
        ticketPrinterService.printRenderedTicket(String.join("\n", lines));
    }
}
