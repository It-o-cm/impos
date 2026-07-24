package com.intermarche.pos.service.sync;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.ticket.Refund;
import com.intermarche.pos.domain.ticket.RefundLine;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Store-node half of the synchronization: idempotent upserts of the payloads
 * pushed by the registers, resolving every natural key against the store
 * database (cashiers by login, store by code, products by EAN/PLU, sessions
 * and tickets by number, refunded lines by uid). An unresolvable mandatory
 * reference raises an {@link IllegalStateException}, turned into a retryable
 * failure by the ingestion endpoint — the missing entity usually arrives on
 * a later cycle (drain order pushes sessions before tickets before refunds).
 * <p>
 * Idempotency is achieved by WHOLESALE GRAPH REPLACEMENT: on a ticket or
 * refund upsert the child collections are cleared and rebuilt from the
 * payload (orphan removal deletes the old rows), so re-pushing the same
 * document any number of times converges to the same state — there is no
 * per-line diffing to get wrong. Product references on ingested lines are
 * best-effort decoration (resolved by PLU first, then EAN, possibly null):
 * the line's snapshot fields are authoritative, the store-side product link
 * only serves reporting joins like the top-sales query.
 */
@ApplicationScoped
public class SyncIngestService {

    private static final Logger LOG = Logger.getLogger(SyncIngestService.class);

    @Inject
    Instance<TicketPayment.Factory> factoryInstances;

    /** Payment factories indexed by their method key. */
    private final Map<String, TicketPayment.Factory> paymentFactories = new HashMap<>();

    /**
     * Indexes the discovered payment factories by their method key.
     */
    @PostConstruct
    public void init() {
        for (TicketPayment.Factory factory : factoryInstances) {
            paymentFactories.put(factory.getKey(), factory);
        }
    }

    /**
     * Upserts a cash session by its number.
     *
     * @param dto the pushed session payload
     */
    @Transactional
    public void ingestSession(SyncPayloads.SessionDto dto) {
        CashSession session = CashSession.find("sessionNumber", dto.sessionNumber).firstResult();
        boolean created = false;
        if (session == null) {
            session = new CashSession();
            session.sessionNumber = dto.sessionNumber;
            created = true;
        }
        session.terminalId = dto.terminalId;
        session.status = CashSession.SessionStatus.valueOf(dto.status);
        session.openingDate = parse(dto.openingDate);
        session.closingDate = parse(dto.closingDate);
        session.openingCashier = requireEmployee(dto.openingCashierLogin);
        session.closingCashier = dto.closingCashierLogin != null ? requireEmployee(dto.closingCashierLogin) : null;
        session.openingFloat = dto.openingFloat;
        session.countedAmount = dto.countedAmount;
        session.theoreticalAmount = dto.theoreticalAmount;
        session.variance = dto.variance;
        session.withdrawnAmount = dto.withdrawnAmount;
        session.countDetail = dto.countDetail;
        session.persist();
        LOG.infof("Session %s %s (%s)", dto.sessionNumber, created ? "créée" : "mise à jour", dto.status);
    }

    /**
     * Upserts a ticket and its graph by its number.
     *
     * @param dto the pushed ticket payload
     */
    @Transactional
    public void ingestTicket(SyncPayloads.TicketDto dto) {
        Ticket ticket = Ticket.find("ticketNumber", dto.ticketNumber).firstResult();
        boolean created = false;
        if (ticket == null) {
            ticket = new Ticket();
            ticket.ticketNumber = dto.ticketNumber;
            created = true;
        }
        ticket.terminalId = dto.terminalId;
        ticket.status = Ticket.TicketStatus.valueOf(dto.status);
        ticket.creationDate = parse(dto.creationDate);
        ticket.closingDate = parse(dto.closingDate);
        ticket.store = requireStore(dto.storeCode);
        ticket.cashier = requireEmployee(dto.cashierLogin);
        ticket.session = dto.sessionNumber != null
                ? CashSession.<CashSession>find("sessionNumber", dto.sessionNumber).firstResult()
                : null;
        ticket.fidelityCard = dto.fidelityCard;
        ticket.digitalKey = dto.digitalKey;
        ticket.customerEmail = dto.customerEmail;
        ticket.itemCount = dto.itemCount;
        ticket.totalExcludingTax = dto.totalExcludingTax;
        ticket.totalIncludingTax = dto.totalIncludingTax;
        ticket.totalVat = dto.totalVat;
        ticket.signature = dto.signature;
        ticket.previousSignature = dto.previousSignature;
        ticket.grandTotal = dto.grandTotal;
        ticket.valuationStatus = Ticket.ValuationStatus.valueOf(dto.valuationStatus);

        ticket.lines.clear();
        for (SyncPayloads.LineDto lineDto : dto.lines) {
            TicketLine line = new TicketLine();
            line.lineNumber = lineDto.lineNumber;
            line.lineUid = lineDto.lineUid;
            line.ean = lineDto.ean;
            line.plu = lineDto.plu;
            if (lineDto.plu != null) {
                line.product = Product.findByPlu(lineDto.plu);
            } else if (lineDto.ean != null) {
                line.product = Product.findByEan(lineDto.ean);
            }
            line.productLabel = lineDto.productLabel;
            line.quantity = lineDto.quantity;
            line.unitPrice = lineDto.unitPrice;
            line.vatRate = lineDto.vatRate;
            line.modifierLabel = lineDto.modifierLabel;
            line.originalUnitPrice = lineDto.originalUnitPrice;
            line.totalPrice = lineDto.totalPrice;
            line.deposit = lineDto.deposit;
            ticket.addLine(line);
        }

        ticket.payments.clear();
        for (SyncPayloads.PaymentDto paymentDto : dto.payments) {
            String factoryKey = paymentDto.voucherLabel != null ? "VOUCHER" : paymentDto.methodKey;
            TicketPayment.Factory factory = paymentFactories.get(factoryKey);
            if (factory == null) {
                throw new IllegalStateException("Mode de paiement inconnu: " + paymentDto.methodKey);
            }
            TicketPayment payment = factory.create(paymentDto.amount, paymentDto.tenderedAmount);
            if (payment instanceof VoucherPayment voucher) {
                voucher.voucherLabel = paymentDto.voucherLabel;
                voucher.voucherNumber = paymentDto.voucherNumber;
            }
            payment.paymentIndex = paymentDto.paymentIndex;
            ticket.addPayment(payment);
        }

        ticket.persist();
        LOG.infof("Ticket %s %s (%s)", dto.ticketNumber, created ? "créé" : "mis à jour", dto.status);
    }

    /**
     * Upserts a refund by its number; the refunded ticket and lines must
     * already be present (drain order guarantees it eventually).
     *
     * @param dto the pushed refund payload
     */
    @Transactional
    public void ingestRefund(SyncPayloads.RefundDto dto) {
        Ticket original = Ticket.find("ticketNumber", dto.originalTicketNumber).firstResult();
        if (original == null) {
            throw new IllegalStateException("Ticket d'origine absent: " + dto.originalTicketNumber);
        }

        Refund refund = Refund.find("refundNumber", dto.refundNumber).firstResult();
        boolean created = false;
        if (refund == null) {
            refund = new Refund();
            refund.refundNumber = dto.refundNumber;
            created = true;
        }
        refund.terminalId = dto.terminalId;
        refund.status = Refund.RefundStatus.valueOf(dto.status);
        refund.refundMethod = dto.refundMethod != null ? Refund.RefundMethod.valueOf(dto.refundMethod) : null;
        refund.originalTicketId = original.id;
        refund.session = dto.sessionNumber != null
                ? CashSession.<CashSession>find("sessionNumber", dto.sessionNumber).firstResult()
                : null;
        refund.creationDate = parse(dto.creationDate);
        refund.totalAmount = dto.totalAmount;
        refund.totalExcludingTax = dto.totalExcludingTax;
        refund.totalVat = dto.totalVat;

        refund.lines.clear();
        for (SyncPayloads.RefundLineDto lineDto : dto.lines) {
            RefundLine line = new RefundLine();
            TicketLine originalLine = (lineDto.originalLineUid != null)
                    ? original.lines.stream()
                        .filter(l -> lineDto.originalLineUid.equals(l.lineUid))
                        .findFirst().orElse(null)
                    : null;
            if (originalLine == null) {
                throw new IllegalStateException("Ligne d'origine absente: " + lineDto.originalLineUid);
            }
            line.originalLineId = originalLine.id;
            line.productLabel = lineDto.productLabel;
            line.quantity = lineDto.quantity;
            line.price = lineDto.price;
            line.vatRate = lineDto.vatRate;
            refund.lines.add(line);
        }

        refund.persist();
        LOG.infof("Remboursement %s %s", dto.refundNumber, created ? "créé" : "mis à jour");
    }

    /**
     * Upserts a technical journal event by its uid.
     *
     * @param dto the pushed event payload
     */
    @Transactional
    public void ingestEvent(SyncPayloads.EventDto dto) {
        TechnicalEvent event = TechnicalEvent.find("eventUid", dto.eventUid).firstResult();
        if (event == null) {
            event = new TechnicalEvent();
            event.eventUid = dto.eventUid;
        }
        event.terminalId = dto.terminalId;
        event.eventType = TechnicalEvent.EventType.valueOf(dto.type);
        event.detail = dto.detail;
        event.eventDate = parse(dto.eventDate);
        event.persist();
    }

    // --------------------------------------------------
    // Natural key resolution
    // --------------------------------------------------

    /**
     * Resolves an employee by login, active or not (historical documents).
     *
     * @param login the employee login
     * @return the employee
     * @throws IllegalStateException when the login is unknown on this node
     */
    private Employee requireEmployee(String login) {
        Employee employee = login != null
                ? Employee.<Employee>find("loginName", login.toLowerCase()).firstResult()
                : null;
        if (employee == null) {
            throw new IllegalStateException("Employé inconnu sur le nœud magasin: " + login);
        }
        return employee;
    }

    /**
     * Resolves the store by code, falling back to the single local store.
     *
     * @param code the store code, or null
     * @return the store
     * @throws IllegalStateException when no store exists on this node
     */
    private Store requireStore(String code) {
        Store store = code != null ? Store.<Store>find("code", code).firstResult() : null;
        if (store == null) {
            store = Store.findAll().firstResult();
        }
        if (store == null) {
            throw new IllegalStateException("Aucun magasin sur le nœud magasin");
        }
        return store;
    }

    /**
     * Parses an ISO-8601 timestamp, tolerating null.
     *
     * @param value the ISO string, or null
     * @return the timestamp, or null
     */
    private LocalDateTime parse(String value) {
        return value != null ? LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }
}
