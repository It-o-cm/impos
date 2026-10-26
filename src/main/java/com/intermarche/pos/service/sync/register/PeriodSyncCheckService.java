package com.intermarche.pos.service.sync.register;

import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TechnicalEventService;
import com.intermarche.pos.service.sync.RefPullService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * CHECKS this register's data against the server's during the end of period
 * (BO-09-03-05), on the order the store node leaves where the store activated
 * the check.
 *
 * <p>The order carries what the server holds of this register's day — the
 * number of its closed tickets and their total, {@code DAY;COUNT;TOTAL}. The
 * register counts its own: when the two differ, it pushes its sales of the day
 * again — the node upserts by number, so a sale it already holds is merely
 * rewritten and a missing one arrives. The referential is checked in the same
 * pass, every domain whose fingerprint differs from the node's being pulled
 * again. The outcome is written to the electronic journal, which the node
 * receives with the register's other events.
 */
@ApplicationScoped
public class PeriodSyncCheckService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(PeriodSyncCheckService.class);

    /** Pushes the sales again. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Pulls the referential domains that differ. */
    @Inject
    RefPullService refPullService;

    /** The electronic journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /** This register's identifier. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Checks the register against the server's figures and resynchronizes
     * what differs: the sales in one transaction, the referential afterwards,
     * outside it, as its own pull cycle does.
     *
     * @param serverFigures the order's reason, {@code DAY;COUNT;TOTAL}
     * @return how many sales were pushed again, -1 when the figures cannot be read
     */
    public int check(String serverFigures) {
        LOGGER.info("Entering method check with serverFigures: " + serverFigures);
        String[] parts = serverFigures == null ? new String[0] : serverFigures.split(";");
        LocalDate day;
        long serverCount;
        BigDecimal serverTotal;
        try {
            day = LocalDate.parse(parts[0].trim());
            serverCount = Long.parseLong(parts[1].trim());
            serverTotal = new BigDecimal(parts[2].trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException unreadable) {
            technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                    "Contrôle caisse / serveur impossible : ordre illisible");
            LOGGER.info("Exiting method check: unreadable order");
            return -1;
        }
        Outcome sales = compare(day, serverCount, serverTotal);
        String referential = refPullService.triggerPull();
        technicalEventService.log(TechnicalEvent.EventType.SESSION_CLOSED,
                "Contrôle caisse / serveur du " + day + " : " + sales.text()
                        + (referential == null ? ", référentiel resynchronisé" : ", référentiel en erreur : " + referential));
        LOGGER.info("Exiting method check");
        return sales.pushed();
    }

    /**
     * What a comparison of the sales came to.
     *
     * @param pushed how many sales were pushed again
     * @param text the outcome, in French, for the journal
     */
    record Outcome(int pushed, String text) {
    }

    /**
     * Compares the register's sales of a day with the server's figures and
     * pushes them again when they differ.
     *
     * @param day the day checked
     * @param serverCount the closed tickets the server holds
     * @param serverTotal their total on the server
     * @return the outcome
     */
    @Transactional
    Outcome compare(LocalDate day, long serverCount, BigDecimal serverTotal) {
        List<Ticket> tickets = Ticket.list(
                "terminalId = ?1 and status = ?2 and closingDate >= ?3 and closingDate < ?4", terminalId,
                Ticket.TicketStatus.CLOSED, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        BigDecimal total = BigDecimal.ZERO;
        for (Ticket ticket : tickets) {
            total = total.add(ticket.totalIncludingTax == null ? BigDecimal.ZERO : ticket.totalIncludingTax);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);
        boolean differs = tickets.size() != serverCount || total.compareTo(serverTotal) != 0;
        if (differs) {
            for (Ticket ticket : tickets) {
                syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
            }
        }
        return new Outcome(differs ? tickets.size() : 0, "caisse " + tickets.size() + " ticket(s) " + total.toPlainString() + ", serveur " + serverCount
                + " ticket(s) " + serverTotal.toPlainString()
                + (differs ? " — écart, " + tickets.size() + " vente(s) renvoyée(s)" : " — conforme"));
    }
}
