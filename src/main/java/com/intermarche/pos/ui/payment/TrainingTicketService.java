package com.intermarche.pos.ui.payment;

import com.intermarche.pos.domain.people.Employee;
import com.intermarche.pos.domain.sale.TrainingTicket;
import com.intermarche.pos.domain.sync.SyncOutbox;
import com.intermarche.pos.service.TicketNumberService;
import com.intermarche.pos.service.sync.register.SyncOutboxService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.hardware.TicketPrinterService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * Sends the sales made in TRAINING mode up to the back office (LC-13-05-06).
 *
 * <p>A training sale stays what it always was on the register: no ticket number,
 * no fiscal chain, no draft. When it ends, its receipt and its figures are kept
 * in the dedicated {@link TrainingTicket} table and pushed to the store node,
 * where the electronic journal shows them on a tab of their own — and where no
 * revenue report, no Z and no accounting export reads that table.
 *
 * <p>Tolerant by construction: a training sale whose copy could not be kept is
 * logged, and the lane goes on.
 */
@ApplicationScoped
public class TrainingTicketService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(TrainingTicketService.class);

    /** Lays out the training receipt. */
    @Inject
    TicketPrinterService ticketPrinterService;

    /** The register's identifier. */
    @Inject
    TicketNumberService ticketNumberService;

    /** Queues the push to the store node. */
    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Keeps the training sale ending now and queues its push.
     *
     * @param state the current POS state, its training sale still in it
     * @return the training sale kept, or null when there was none or it could not be kept
     */
    public TrainingTicket record(PosState state) {
        LOGGER.info("Entering method record");
        if (!state.trainingMode || state.ticket.items.isEmpty()) {
            LOGGER.info("Exiting method record (no training sale)");
            return null;
        }
        Employee operator = state.auth.operatorId == null ? null : Employee.findById(state.auth.operatorId);
        String content = ticketPrinterService.renderTrainingReceipt(state);
        try {
            TrainingTicket kept = QuarkusTransaction.requiringNew().call(() -> {
                TrainingTicket training = new TrainingTicket();
                training.terminalId = ticketNumberService.getTerminalId();
                training.cashierLogin = operator == null ? null : operator.loginName;
                training.issuedAt = LocalDateTime.now();
                training.itemCount = state.ticket.items.size();
                training.totalIncludingTax = state.ticket.getTotalAmount();
                training.content = content;
                training.persist();
                syncOutboxService.enqueue(SyncOutbox.EntityType.TRAINING, training.id);
                return training;
            });
            LOGGER.info("Exiting method record (kept)");
            return kept;
        } catch (RuntimeException e) {
            LOGGER.warnf("Vente de formation non conservée : %s", e.getMessage());
            LOGGER.info("Exiting method record (not kept)");
            return null;
        }
    }
}
