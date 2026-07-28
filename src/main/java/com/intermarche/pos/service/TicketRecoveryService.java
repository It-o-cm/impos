package com.intermarche.pos.service;

import com.intermarche.pos.domain.ticket.CashPayment;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.DiscriminatorValue;
import jakarta.transaction.Transactional;
import org.hibernate.Hibernate;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Restores the in-memory cart from the database at register startup.
 * <p>
 * Phase 0 lot 2: since the draft is persisted from the first article, a crash
 * or restart must not lose the sale. At startup this service looks for the
 * most recent OPEN draft of this terminal in its database,
 * rebuilds the cart (lines with their stable uids, totals, fidelity card) and
 * the already-registered payments, then reattaches the draft id. Older OPEN
 * drafts of the same terminal (crash leftovers) are cancelled.
 * <p>
 * The register comes back locked: the cashier logs in again and simply
 * continues the restored sale.
 * <p>
 * Cancelling the older OPEN drafts is not cleanup, it ENFORCES the
 * single-draft invariant the whole persistence relies on (one OPEN draft
 * per terminal). The payment restoration also covers the narrow crash
 * window between payment completion and ticket closing: when the restored
 * payments already settle the total, the completion flag is set back and
 * the cashier lands on the completion modal, not on a half-paid screen.
 * The restore machinery ({@code restoreDraft}) is deliberately shared with
 * the parked-ticket resume — one code path to trust for rebuilding a cart.
 */
@ApplicationScoped
public class TicketRecoveryService {

    private static final Logger LOG = Logger.getLogger(TicketRecoveryService.class);

    @Inject
    PosState state;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    TechnicalEventService technicalEventService;

    /**
     * Startup hook running the recovery after the dev/test data seeder
     * (default observer priority is 2500; 2600 runs after it).
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes @Priority(2600) StartupEvent event) {
        try {
            recover();
        } catch (Exception e) {
            LOG.error("Échec de la reprise du ticket en cours", e);
        }
    }

    /**
     * Performs the recovery: finds the most recent OPEN draft of this
     * terminal, cancels older OPEN leftovers, and rebuilds the in-memory
     * cart and payments from it.
     */
    @Transactional
    public void recover() {
        String terminalId = ticketNumberService.getTerminalId();

        List<Ticket> openDrafts = Ticket.list("status = ?1 and terminalId = ?2 order by id desc",
                Ticket.TicketStatus.OPEN, terminalId);
        if (openDrafts.isEmpty()) {
            return;
        }

        Ticket draft = openDrafts.get(0);

        // Cancel older leftovers of this terminal (crash loops, legacy)
        for (int i = 1; i < openDrafts.size(); i++) {
            Ticket stale = openDrafts.get(i);
            stale.status = Ticket.TicketStatus.CANCELLED;
            stale.persist();
            LOG.warnf("Draft périmé annulé ID: %d (%s)", stale.id, stale.ticketNumber);
        }

        restoreDraft(draft);
        technicalEventService.log(TechnicalEvent.EventType.DRAFT_RECOVERED, draft.ticketNumber);
        LOG.infof("Ticket en cours restauré ID: %d (%s), %d ligne(s), %d paiement(s)",
                draft.id, draft.ticketNumber, draft.lines.size(), draft.payments.size());
    }

    /**
     * Restores a persisted draft into the in-memory state: cart lines with
     * their stable uids, total, fidelity card, already-registered payments and
     * draft id. Shared by the startup recovery and the parked-ticket resume.
     *
     * @param draft the persisted draft to restore
     */
    public void restoreDraft(Ticket draft) {
        restoreCart(draft);
        restorePayments(draft);
        state.payment.ticketDbId = draft.id;
        state.touch();
    }

    /**
     * Rebuilds the in-memory cart lines and total from the persisted draft.
     * Lines keep their stable uid; a legacy line without uid gets a fresh one.
     *
     * @param draft the persisted draft ticket
     */
    private void restoreCart(Ticket draft) {
        state.ticket.items.clear();

        List<TicketLine> orderedLines = draft.lines.stream()
                .sorted(Comparator.comparingInt(l -> l.lineNumber))
                .toList();

        for (TicketLine line : orderedLines) {
            TicketState.TicketItem item = new TicketState.TicketItem(
                    line.ean, line.plu, line.productLabel, line.unitPrice, line.quantity, line.vatRate);
            if (line.lineUid != null) {
                item.uid = line.lineUid;
            }
            // A price modification survives the restart with its label and
            // original price (endorsed once, never re-asked)
            if (line.modifierLabel != null) {
                item.modifierLabel = line.modifierLabel;
                item.modifierType = line.modifierType;
                item.modifierValue = line.modifierValue;
                if (line.originalUnitPrice != null) {
                    item.originalUnitPrice = line.originalUnitPrice;
                }
            }
            state.ticket.items.add(item);
        }
        state.ticket.recomputeTotal();

        if (draft.fidelityCard != null) {
            state.fidelity.assignCard(draft.fidelityCard);
        }
    }

    /**
     * Rebuilds the in-memory payment entries from the persisted payments, in
     * their registration order, and restores the completion flag when the
     * remaining due is already settled (crash between completion and closing).
     *
     * @param draft the persisted draft ticket
     */
    private void restorePayments(Ticket draft) {
        List<TicketPayment> orderedPayments = draft.payments.stream()
                .sorted(Comparator.comparingInt(p -> p.paymentIndex))
                .toList();

        for (TicketPayment payment : orderedPayments) {
            if (payment instanceof VoucherPayment voucher) {
                state.payment.addVoucherPayment(voucher.voucherLabel, voucher.voucherNumber, voucher.amount);
            } else if (payment instanceof CashPayment cash) {
                state.payment.addCashPayment(cash.amount, cash.tenderedAmount);
            } else {
                state.payment.addPayment(methodKeyOf(payment), payment.amount);
            }
        }

        if (!state.payment.payments.isEmpty() && state.getRemaining().signum() <= 0) {
            state.payment.transactionComplete = true;
        }
    }

    /**
     * Resolves the payment method key of a persisted payment from its JPA
     * discriminator value.
     *
     * @param payment the persisted payment
     * @return the method key (e.g. "CARD"), or "UNKNOWN" when unresolvable
     */
    private String methodKeyOf(TicketPayment payment) {
        Class<?> entityClass = Hibernate.getClass(payment);
        DiscriminatorValue discriminator = entityClass.getAnnotation(DiscriminatorValue.class);
        return discriminator != null ? discriminator.value() : "UNKNOWN";
    }
}
