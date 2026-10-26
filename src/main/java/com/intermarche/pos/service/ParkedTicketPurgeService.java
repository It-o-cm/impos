package com.intermarche.pos.service;

import com.intermarche.pos.domain.sale.ParkedTicketRecord;
import com.intermarche.pos.domain.sale.Ticket;
import com.intermarche.pos.domain.session.TechnicalEvent;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Resets the TICKETS ON HOLD of this register on the back office's order
 * (BO-09-01-08): the « RAZ ticket en attente » of the end of day, ordered from
 * the end-of-day screen or by the automatic end of period.
 *
 * <p>Exactly what the Z closing does to a cart nobody resumed: the ticket is
 * cancelled and pushed to the store node, and its purge is traced, with what
 * it carried, in the register's inalterable chain of parked-ticket records
 * (LC-04-01-03) — the electronic journal says how many went and why.
 */
@ApplicationScoped
public class ParkedTicketPurgeService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(ParkedTicketPurgeService.class);

    /** The author written on the purge records. */
    static final String AUTHOR = "RAZ BACK-OFFICE";

    /** Pushes the cancelled tickets to the store node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /** Traces each purge in the parked-ticket chain. */
    @Inject
    ParkedTicketArchiveService parkedTicketArchiveService;

    /** The electronic journal. */
    @Inject
    TechnicalEventService technicalEventService;

    /** This register's identifier. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Cancels every ticket on hold of this register and traces each purge.
     *
     * @param orderedBy who ordered the reset, for the journal
     * @return how many tickets were reset
     */
    @Transactional
    public int purge(String orderedBy) {
        LOGGER.info("Entering method purge with orderedBy: " + orderedBy);
        List<Ticket> parked = Ticket.list("terminalId = ?1 and status = ?2", terminalId, Ticket.TicketStatus.PARKED);
        for (Ticket ticket : parked) {
            ticket.status = Ticket.TicketStatus.CANCELLED;
            ticket.persist();
            syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
            parkedTicketArchiveService.record(ticket, ParkedTicketRecord.Event.PURGED, AUTHOR);
        }
        technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED,
                parked.size() + " ticket(s) en attente remis à zéro sur ordre du back-office"
                        + (orderedBy == null || orderedBy.isBlank() ? "" : " (" + orderedBy.trim() + ")"));
        LOGGER.info("Exiting method purge with " + parked.size() + " ticket(s)");
        return parked.size();
    }
}
