package com.intermarche.pos.service;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.payment.PaymentState;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TicketPersistenceService {

    @Inject
    Instance<TicketPayment.Factory> factoryInstances;

    private final Map<String, TicketPayment.Factory> paymentFactories = new HashMap<>();

    @PostConstruct
    public void init() {
        for (TicketPayment.Factory factory : factoryInstances) {
            paymentFactories.put(factory.getKey(), factory);
        }
    }

    /**
     * 1. CREATION : Sauvegarde l'en-tête et les lignes dès l'entrée en paiement.
     */
    @Transactional
    public Long createDraftTicket(PosState state, Store store, Employee cashier) {
        Ticket ticket = new Ticket();
        ticket.ticketNumber = generateTicketNumber();
        ticket.creationDate = LocalDateTime.now();
        ticket.status = Ticket.TicketStatus.OPEN;
        ticket.store = store;
        ticket.cashier = cashier;
        ticket.fidelityCard = state.fidelity.active ? state.fidelity.label : null;
        // Sauvegarde des lignes (le panier est figé)
        for (TicketState.TicketItem item : state.ticket.items) {
            ticket.addLine(mapLine(item, ticket.lines.size() + 1));
        }
        ticket.itemCount = state.ticket.items.size();
        // Totaux initiaux
        ticket.totalIncludingTax = BigDecimal.valueOf(state.ticket.totalAmount).setScale(2, RoundingMode.HALF_UP);
        ticket.totalExcludingTax = calculateHT(ticket.totalIncludingTax);
        ticket.totalVat = ticket.totalIncludingTax.subtract(ticket.totalExcludingTax);
        ticket.persist();
        return ticket.id;
    }

    /**
     * 2. MISE A JOUR : Ajoute un paiement au ticket existant.
     */
    @Transactional
    public void addPaymentToTicket(Long ticketId, PaymentState.PaymentEntry entry) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) throw new IllegalArgumentException("Ticket introuvable : " + ticketId);
        BigDecimal amount = BigDecimal.valueOf(entry.amount);
        BigDecimal tendered = entry.tenderedAmount != null ? BigDecimal.valueOf(entry.tenderedAmount) : null;
        String factoryKey = entry.isVoucher() ? "VOUCHER" : entry.method;
        TicketPayment.Factory factory = paymentFactories.get(factoryKey);
        if (factory == null) throw new IllegalArgumentException("Mode inconnu : " + factoryKey);
        TicketPayment payment = factory.create(amount, tendered);
        if (payment instanceof VoucherPayment voucherPayment) {
            voucherPayment.voucherLabel = entry.method;
            voucherPayment.voucherNumber = entry.voucherNumber;
        }
        payment.paymentIndex = ticket.payments.size() + 1;
        ticket.addPayment(payment);
        ticket.persist();
    }

    /**
     * 3. VALIDATION : Marque le ticket comme terminé.
     */
    @Transactional
    public void validateTicket(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket != null) {
            ticket.status = Ticket.TicketStatus.CLOSED;
            ticket.persist();
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    private TicketLine mapLine(TicketState.TicketItem item, int lineNumber) {
        TicketLine line = new TicketLine();
        line.lineNumber = lineNumber;
        if (item.plu != null) {
            line.product = Product.findByPlu(item.plu);
        } else if (item.ean != null) {
            line.product = Product.findByEan(item.ean);
        }
        line.productLabel = item.label;
        line.quantity = BigDecimal.valueOf(item.quantity);
        line.unitPrice = BigDecimal.valueOf(item.unitPrice);
        line.totalPrice = BigDecimal.valueOf(item.getTotalPrice());
        line.vatRate = BigDecimal.valueOf(0.055);
        line.deposit = item.isNegative();
        return line;
    }

    private String generateTicketNumber() {
        return "TCK-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private BigDecimal calculateHT(BigDecimal ttc) {
        return ttc.divide(BigDecimal.valueOf(1.055), 2, RoundingMode.HALF_UP);
    }
}
