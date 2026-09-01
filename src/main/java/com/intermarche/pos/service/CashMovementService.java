package com.intermarche.pos.service;

import com.intermarche.pos.domain.CashMovement;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.service.sync.SyncOutboxService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records the cash movements of this register (withdrawal, deposit, expense,
 * customer down-payment, cash-count declaration). Movements live in the
 * register's own database and are pushed to the store node THROUGH THE OUTBOX
 * (entity type {@code MOVEMENT}); nothing here makes a synchronous network
 * call in the sale thread, so a register cut off from the network keeps
 * recording movements.
 * <p>
 * A movement above the endorsement threshold requires a manager endorsement:
 * the endorsing manager's badge must be supplied, else the recording is
 * refused. This service READS NO SCREEN — the session, the cashier and the
 * endorsement decision are all handed to it by the caller. The threshold is a
 * DOCUMENTED DEFAULT ({@link #DEFAULT_ENDORSEMENT_THRESHOLD}) until lot C5b
 * moves it to the administered catalog of {@code PosSettingsService} (the key
 * that serves BO-04-01-44); this lot ships the object and its transport only,
 * so it serves ZERO journal requirement on its own.
 */
@ApplicationScoped
public class CashMovementService {

    private static final Logger LOG = Logger.getLogger(CashMovementService.class);

    /**
     * The default endorsement threshold in euros, applied until lot C5b makes
     * it an administered parameter. A movement strictly above it needs a
     * manager endorsement.
     */
    static final BigDecimal DEFAULT_ENDORSEMENT_THRESHOLD = new BigDecimal("100.00");

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    SyncOutboxService syncOutboxService;

    /**
     * Indicates whether a movement of the given amount requires a manager
     * endorsement (strictly above the threshold). A null amount never requires
     * one.
     *
     * @param amount the movement amount, or null
     * @return true when the amount is strictly above the endorsement threshold
     */
    public boolean requiresEndorsement(BigDecimal amount) {
        return amount != null && amount.compareTo(DEFAULT_ENDORSEMENT_THRESHOLD) > 0;
    }

    /**
     * Records a cash movement and enqueues it for store synchronization,
     * joining the caller's transaction. When the amount requires an endorsement
     * and none is supplied, the movement is refused and nothing is recorded.
     *
     * @param session the cash session the movement belongs to
     * @param cashier the cashier who performed the movement
     * @param type the kind of movement
     * @param amount the movement amount
     * @param reason the free-text reason, or null
     * @param endorsedBy the badge of the endorsing manager, or null when none
     * @return the recorded movement, or null when an endorsement was required
     *         but not supplied
     */
    @Transactional
    public CashMovement record(CashSession session, Employee cashier, CashMovement.MovementType type,
                               BigDecimal amount, String reason, String endorsedBy) {
        if (requiresEndorsement(amount) && endorsedBy == null) {
            LOG.warnf("Mouvement refuse : aval manager requis au-dela de %s",
                    DEFAULT_ENDORSEMENT_THRESHOLD.toPlainString());
            return null;
        }
        CashMovement movement = new CashMovement();
        movement.movementUid = UUID.randomUUID().toString();
        movement.terminalId = ticketNumberService.getTerminalId();
        movement.session = session;
        movement.cashier = cashier;
        movement.type = type;
        movement.amount = amount;
        movement.reason = reason;
        movement.movementDate = LocalDateTime.now();
        movement.endorsedBy = endorsedBy;
        movement.persist();
        syncOutboxService.enqueue(SyncOutbox.EntityType.MOVEMENT, movement.id);
        return movement;
    }
}
