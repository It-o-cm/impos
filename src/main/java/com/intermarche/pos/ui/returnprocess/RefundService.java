package com.intermarche.pos.ui.returnprocess;

import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.service.TicketPrinterService;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.endorsement.EndorsementService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@ApplicationScoped
public class RefundService {

    private static final int EXPIRATION_DAYS = 30;
    private static final Logger LOG = Logger.getLogger(RefundService.class);

    @Inject
    EndorsementService endorsementService;

    @Inject
    TicketPrinterService ticketPrinterService;

    public void searchTickets(PosState state) {
        String pattern = state.refund.searchPattern;

        if (pattern == null || pattern.trim().length() < 3) {
            state.refund.foundTickets.clear();
            state.touch();
            return;
        }

        LocalDateTime limit = LocalDateTime.now().minusDays(EXPIRATION_DAYS);

        List<Ticket> results = Ticket.find(
                "status = ?1 and lower(ticketNumber) like lower(?2) and creationDate > ?3",
                Ticket.TicketStatus.CLOSED,
                "%" + pattern + "%",
                limit
        ).list();

        LOG.info("Recherche Retour: Pattern=" + pattern + ", Résultats=" + results.size());

        state.refund.foundTickets = results;
        state.touch();
    }

    public void selectTicket(PosState state, Long ticketId) {
        Ticket t = Ticket.findById(ticketId);
        if (t != null) {
            state.refund.selectedTicket = t;
            state.refund.returnQuantities.clear();
            state.refund.detailPage = 0;
            state.refund.selectedLineId = null;
            state.refund.isEditingAmount = false;
            state.refund.manualTotalAmount = null;
        }
        state.touch();
    }

    public void selectLine(PosState state, Long lineId) {
        if (lineId != null && lineId.equals(state.refund.selectedLineId)) {
            state.refund.selectedLineId = null;
        } else {
            state.refund.selectedLineId = lineId;
        }
        state.refund.isEditingAmount = false;
        state.touch();
    }

    public void startAmountEdit(PosState state) {
        state.refund.selectedLineId = null;
        state.refund.isEditingAmount = true;
        state.touch();
    }

    public void submitLineQuantity(PosState state, Long lineId, String rawValue) {
        try {
            BigDecimal qty = new BigDecimal(rawValue.replace(",", "."));
            setReturnQuantity(state, lineId, qty);
        } catch (Exception e) { }
        state.refund.selectedLineId = null;
        state.touch();
    }

    public void submitManualAmount(PosState state, String rawValue) {
        try {
            BigDecimal amount = new BigDecimal(rawValue.replace(",", "."));
            state.refund.manualTotalAmount = amount;
        } catch (Exception e) {
            state.refund.manualTotalAmount = BigDecimal.ZERO;
        }
        state.refund.isEditingAmount = false;
        state.touch();
    }

    public void setReturnQuantity(PosState state, Long lineId, BigDecimal quantity) {
        if (state.refund.selectedTicket == null) return;

        TicketLine line = state.refund.selectedTicket.lines.stream()
                .filter(l -> l.id.equals(lineId)).findFirst().orElse(null);

        if (line != null) {
            if (quantity.compareTo(BigDecimal.ZERO) < 0) quantity = BigDecimal.ZERO;
            if (quantity.compareTo(line.quantity) > 0) quantity = line.quantity;

            state.refund.returnQuantities.put(lineId, quantity);
            state.refund.manualTotalAmount = null;
        }
        state.touch();
    }

    public void incrementQty(PosState state, Long lineId) {
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.add(BigDecimal.ONE));
        state.touch();
    }

    public void decrementQty(PosState state, Long lineId) {
        BigDecimal current = state.refund.returnQuantities.getOrDefault(lineId, BigDecimal.ZERO);
        setReturnQuantity(state, lineId, current.subtract(BigDecimal.ONE));
        state.touch();
    }

    public void validateRefund(PosState state) {
        if (state.refund.selectedTicket == null) return;
        endorsementService.requestAuthorization(state, "REFUND_VALIDATION_" + state.refund.selectedTicket.id);
        state.touch();
    }

    @Transactional
    public void performRefundCreation(PosState state) {
        Refund refund = new Refund();
        refund.originalTicketId = state.refund.selectedTicket.id;
        refund.creationDate = LocalDateTime.now();
        refund.totalAmount = state.refund.getTotalRefundAmount();

        state.refund.returnQuantities.forEach((lineId, qty) -> {
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                TicketLine orig = state.refund.selectedTicket.lines.stream()
                        .filter(l -> l.id.equals(lineId)).findFirst().orElse(null);
                if(orig != null) {
                    RefundLine rl = new RefundLine();
                    rl.originalLineId = lineId;
                    rl.productLabel = orig.productLabel;
                    rl.quantity = qty;
                    rl.price = orig.unitPrice;
                    refund.lines.add(rl);
                }
            }
        });

        refund.persist();

        ticketPrinterService.printRefund(refund.id);

        state.refund.clear();
        state.touch();
    }

    public void generateVoucher(PosState state) {
        System.out.println("MOCK: Génération Bon d'achat pour " + state.refund.getTotalRefundAmount());
        state.refund.clear();
        state.touch();
    }

    public void addToLoyalty(PosState state) {
        System.out.println("MOCK: Ajout Cagnotte pour " + state.refund.getTotalRefundAmount());
        state.refund.clear();
        state.touch();
    }
}