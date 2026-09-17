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

        /** Parked tickets are still waiting and an endorsement is required. */
        PENDING_TICKETS,

        /** The operator must key their password. */
        PASSWORD
    }

    /** The administered parameters governing the close. */
    @Inject PosSettingsService posSettingsService;

    /** The credentials check, shared with the opening. */
    @Inject AuthService authService;

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
        LOGGER.info("Entering method obstacle");
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
        if (state.auth.operatorBadgeId == null || password == null || password.isBlank()) {
            LOGGER.info("Exiting method passwordMatches with false");
            return false;
        }
        boolean ok = authService.checkCredentials(state.auth.operatorBadgeId, password).isSuccess();
        LOGGER.info("Exiting method passwordMatches with " + ok);
        return ok;
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
        String badge = state.auth.operatorBadgeId;
        String name = state.auth.operatorName;
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
