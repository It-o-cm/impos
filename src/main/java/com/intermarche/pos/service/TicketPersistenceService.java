package com.intermarche.pos.service;

import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Product;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.CashSession;
import com.intermarche.pos.domain.SyncOutbox;
import com.intermarche.pos.domain.ticket.TechnicalEvent;
import com.intermarche.pos.domain.ticket.Ticket;
import com.intermarche.pos.domain.ticket.TicketCounter;
import com.intermarche.pos.domain.ticket.TicketLine;
import com.intermarche.pos.domain.ticket.TicketPayment;
import com.intermarche.pos.domain.ticket.VatBreakdown;
import com.intermarche.pos.domain.ticket.VoucherPayment;
import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.ticket.TicketState;
import com.intermarche.pos.ui.payment.PaymentState;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import com.intermarche.pos.service.sync.SyncOutboxService;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persists the in-memory ticket into the register's database.
 * <p>
 * Phase 0 lot 2: the draft is persisted from the first article and kept in
 * sync after every cart mutation ({@link #syncDraft(PosState)}), so a register
 * restart never loses the cart. Existing lines are reconciled by their stable
 * uid (in-place updates for quantity merges and price modifications, orphan
 * removal for cancelled lines). An emptied or abandoned cart cancels the
 * draft, documenting the gap in the per-terminal number sequence.
 * <p>
 * Phase 1: each line carries the real VAT rate captured at sale time, the
 * ticket totals come from the per-rate ventilation ({@link VatBreakdown}),
 * and ticket validation chains the ticket to the previous closed ticket of
 * the same register (SHA-256 signature, perpetual grand total) under the
 * counter row lock. Lifecycle transitions are recorded in the technical
 * event journal.
 * <p>
 * {@code syncDraft} is the SINGLE WRITE FUNNEL of the sale: every cart
 * mutation, the payment entry, the parking, the digital receipt and the
 * store sync all flow through the draft it maintains, and
 * {@code ticketDbId} is the pivot everything keys on. This is why the
 * phase 6 training mode needs exactly ONE guard — syncDraft returning null
 * neutralizes the whole fiscal surface downstream (no number burnt, no
 * chain, no outbox row, no digital receipt, nothing to recover), with no
 * scattered ifs to maintain.
 * <p>
 * {@code validateTicket} is the FISCAL MOMENT, entirely under the counter
 * row lock: status flip, closing date, signature chained over
 * number|terminal|date|totals|previous, perpetual grand total incremented
 * and snapshotted on the ticket, journal entry and outbox row — one
 * transaction, atomic per register. The signature input order is part of
 * the fiscal contract: changing it breaks verifiability of the whole
 * history.
 */
@ApplicationScoped
public class TicketPersistenceService {

    private static final Logger LOG = Logger.getLogger(TicketPersistenceService.class);

    @Inject
    Instance<TicketPayment.Factory> factoryInstances;

    @Inject
    TicketNumberService ticketNumberService;

    @Inject
    CashSessionService cashSessionService;

    @Inject
    TechnicalEventService technicalEventService;

    @Inject
    SyncOutboxService syncOutboxService;

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

    // --------------------------------------------------
    // 1. DRAFT SYNCHRONIZATION (called after every cart mutation)
    // --------------------------------------------------

    /**
     * Synchronizes the in-memory cart with its database draft.
     * <ul>
     *   <li>No draft yet and a non-empty cart: creates the draft (reserving the
     *       next sequential ticket number) and stores its id in
     *       {@code state.payment.ticketDbId} — this method is the single owner
     *       of that field on the creation path.</li>
     *   <li>Existing draft: reconciles lines by uid (update in place, add new,
     *       orphan-remove cancelled ones) and refreshes totals and fidelity.</li>
     *   <li>Existing draft and an emptied cart: cancels the draft and clears
     *       {@code state.payment.ticketDbId}.</li>
     * </ul>
     *
     * @param state the current POS state
     * @return the database id of the draft, or null when no draft exists anymore
     */
    @Transactional
    public Long syncDraft(PosState state) {
        // Training mode: nothing fiscal ever reaches the database
        if (state.trainingMode) return null;

        Long ticketId = state.payment.ticketDbId;

        // Emptied cart: cancel the draft, if any
        if (state.ticket.items.isEmpty()) {
            if (ticketId != null) {
                doCancelDraft(ticketId);
                state.payment.ticketDbId = null;
            }
            return null;
        }

        if (ticketId == null) {
            Long newId = createDraft(state);
            state.payment.ticketDbId = newId;
            return newId;
        }

        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null || ticket.status != Ticket.TicketStatus.OPEN) {
            LOG.warnf("Draft %d introuvable ou non OPEN, resynchronisation par création", ticketId);
            Long newId = createDraft(state);
            state.payment.ticketDbId = newId;
            return newId;
        }

        reconcileLines(ticket, state);
        applyHeaderAndTotals(ticket, state);
        ticket.persist();
        return ticket.id;
    }

    /**
     * Creates the database draft from the current cart, reserving the next
     * sequential ticket number of this terminal.
     *
     * @param state the current POS state (non-empty cart)
     * @return the database id of the created draft, or null when the store or
     *         the cashier cannot be resolved
     */
    private Long createDraft(PosState state) {
        Store store = Store.findAll().firstResult();
        Employee cashier = (state.auth.operatorId != null) ? Employee.findById(state.auth.operatorId) : null;
        if (store == null || cashier == null) {
            LOG.error("Impossible de créer le ticket (Store ou Cashier manquant)");
            return null;
        }
        CashSession session = cashSessionService.getOpenSession();
        if (session == null) {
            LOG.error("Impossible de créer le ticket : aucune session de caisse ouverte");
            return null;
        }

        Ticket ticket = new Ticket();
        ticket.ticketNumber = ticketNumberService.nextTicketNumber();
        ticket.terminalId = ticketNumberService.getTerminalId();
        ticket.creationDate = LocalDateTime.now();
        ticket.status = Ticket.TicketStatus.OPEN;
        // Access key of the online digital receipt (short, printable, unguessable)
        ticket.digitalKey = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ticket.store = store;
        ticket.cashier = cashier;
        ticket.session = session;
        for (TicketState.TicketItem item : state.ticket.items) {
            ticket.addLine(mapLine(item, ticket.lines.size() + 1));
        }
        applyHeaderAndTotals(ticket, state);
        ticket.persist();
        LOG.infof("Ticket créé en BDD (Draft) ID: %d (%s)", ticket.id, ticket.ticketNumber);
        return ticket.id;
    }

    /**
     * Reconciles the draft lines with the in-memory cart by their stable uid:
     * updates matched lines in place, removes vanished ones (orphan removal),
     * appends new ones.
     *
     * @param ticket the managed draft ticket
     * @param state the current POS state
     */
    private void reconcileLines(Ticket ticket, PosState state) {
        Set<String> memoryUids = new HashSet<>();
        for (TicketState.TicketItem item : state.ticket.items) {
            memoryUids.add(item.uid);
        }

        // Remove lines that no longer exist in memory (cancelled lines)
        ticket.lines.removeIf(line -> line.lineUid == null || !memoryUids.contains(line.lineUid));

        Map<String, TicketLine> existingByUid = new HashMap<>();
        for (TicketLine line : ticket.lines) {
            existingByUid.put(line.lineUid, line);
        }

        int lineNumber = 1;
        for (TicketState.TicketItem item : state.ticket.items) {
            TicketLine line = existingByUid.get(item.uid);
            if (line == null) {
                ticket.addLine(mapLine(item, lineNumber));
            } else {
                line.lineNumber = lineNumber;
                line.productLabel = item.label;
                line.quantity = item.quantity;
                line.unitPrice = item.unitPrice;
        line.modifierLabel = item.modifierLabel;
        line.originalUnitPrice = item.modifierLabel != null ? item.originalUnitPrice : null;
        line.modifierType = item.modifierType;
        line.modifierValue = item.modifierValue;
                line.vatRate = item.vatRate;
                line.modifierLabel = item.modifierLabel;
                line.originalUnitPrice = item.modifierLabel != null ? item.originalUnitPrice : null;
                line.modifierType = item.modifierType;
                line.modifierValue = item.modifierValue;
                line.totalPrice = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
            }
            lineNumber++;
        }
    }

    /**
     * Refreshes the header fields and financial totals of the draft from the
     * in-memory state (fidelity card included, so a card scanned mid-cart is
     * picked up by the next synchronization). Totals come from the per-rate
     * VAT ventilation of the lines — the same rule the in-memory total and
     * the printed ticket follow.
     *
     * @param ticket the managed draft ticket
     * @param state the current POS state
     */
    private void applyHeaderAndTotals(Ticket ticket, PosState state) {
        ticket.fidelityCard = state.fidelity.active ? state.fidelity.label : null;
        ticket.globalDiscountType = state.ticket.globalDiscountType;
        ticket.globalDiscountValue = state.ticket.globalDiscountValue;
        ticket.globalDiscountApplied = state.ticket.globalDiscountApplied;
        ticket.itemCount = state.ticket.items.size();

        VatBreakdown breakdown = new VatBreakdown();
        for (TicketState.TicketItem item : state.ticket.items) {
            breakdown.add(item.vatRate, item.getTotalPrice().setScale(2, RoundingMode.HALF_UP));
        }
        ticket.totalIncludingTax = breakdown.getTotalIncludingTax();
        ticket.totalExcludingTax = breakdown.getTotalExcludingTax();
        ticket.totalVat = breakdown.getTotalVat();
    }

    // --------------------------------------------------
    // 2. PAYMENTS
    // --------------------------------------------------

    /**
     * Adds a payment to the existing draft ticket.
     *
     * @param ticketId the database id of the draft ticket
     * @param entry the in-memory payment entry to persist
     */
    @Transactional
    public void addPaymentToTicket(Long ticketId, PaymentState.PaymentEntry entry) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) throw new IllegalArgumentException("Ticket introuvable : " + ticketId);
        BigDecimal amount = entry.amount;
        BigDecimal tendered = entry.tenderedAmount;
        String factoryKey = entry.isVoucher() ? "VOUCHER" : entry.method;
        TicketPayment.Factory factory = paymentFactories.get(factoryKey);
        if (factory == null) throw new IllegalArgumentException("Mode inconnu : " + factoryKey);
        TicketPayment payment = factory.create(amount, tendered);
        if (payment instanceof VoucherPayment voucherPayment) {
            voucherPayment.voucherLabel = entry.method;
            voucherPayment.voucherNumber = entry.voucherNumber;
        }
        if (payment instanceof com.intermarche.pos.domain.ticket.CardPayment cardPayment) {
            cardPayment.authorizationNumber = entry.authorizationNumber;
            cardPayment.degradedMode = entry.degradedMode;
        }
        payment.paymentIndex = ticket.payments.size() + 1;
        ticket.addPayment(payment);
        ticket.persist();
    }

    /**
     * Removes every payment persisted on the draft (payment-cancellation
     * coherence: the in-memory list is cleared by the caller, the database
     * must follow, otherwise a later recovery would resurrect ghost payments).
     *
     * @param ticketId the database id of the draft ticket
     */
    @Transactional
    public void removePaymentsFromTicket(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) return;
        int removed = ticket.payments.size();
        ticket.payments.clear();
        ticket.persist();
        if (removed > 0) {
            technicalEventService.log(TechnicalEvent.EventType.PAYMENTS_CLEARED,
                    ticket.ticketNumber + " (" + removed + ")");
        }
    }

    // --------------------------------------------------
    // 3. FINALIZATION / CANCELLATION
    // --------------------------------------------------

    /**
     * Marks the ticket as closed and chains it to the previous closed ticket
     * of the same register: under the counter row lock (serializing closings
     * per terminal), the closing date is set, the SHA-256 signature is
     * computed over the fiscal fields and the previous signature, and the
     * perpetual grand total is advanced. The closure is recorded in the
     * technical event journal.
     *
     * @param ticketId the database id of the ticket to close
     */
    @Transactional
    public void validateTicket(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket == null) {
            return;
        }
        TicketCounter counter = ticketNumberService.lockCounter(ticket.terminalId);

        ticket.status = Ticket.TicketStatus.CLOSED;
        ticket.closingDate = LocalDateTime.now();
        ticket.previousSignature = (counter.lastSignature != null) ? counter.lastSignature : "GENESIS";
        ticket.signature = sha256Hex(String.join("|",
                ticket.ticketNumber,
                ticket.terminalId,
                ticket.closingDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                ticket.totalIncludingTax.toPlainString(),
                ticket.totalVat.toPlainString(),
                ticket.previousSignature));

        counter.grandTotal = counter.grandTotal.add(ticket.totalIncludingTax);
        counter.lastSignature = ticket.signature;

        // --- Stored-value settlement, INSIDE the fiscal transaction
        //     (phase: credit notes & gift cards) ---
        // (1) Debit the redeemed registry instruments: the balance only
        //     moves HERE — a cancelled payment or a crash before this point
        //     never burns stored value.
        for (com.intermarche.pos.domain.ticket.TicketPayment payment : ticket.payments) {
            if (payment instanceof com.intermarche.pos.domain.ticket.VoucherPayment voucher
                    && com.intermarche.pos.domain.StoredValue.isRegistryNumber(voucher.voucherNumber)) {
                com.intermarche.pos.domain.StoredValue instrument =
                        com.intermarche.pos.domain.StoredValue.findByNumber(voucher.voucherNumber);
                if (instrument != null) {
                    instrument.balance = instrument.balance.subtract(payment.amount).max(java.math.BigDecimal.ZERO);
                    instrument.lastRedeemedTicketId = ticket.id;
                    if (instrument.balance.signum() == 0) {
                        instrument.status = com.intermarche.pos.domain.StoredValue.Status.EXHAUSTED;
                        instrument.exhaustedAt = LocalDateTime.now();
                    }
                }
            }
        }
        // (2) Issue the gift cards sold on this ticket: one ACTIVE registry
        //     instrument per unit, numbered from its own row id. Issued at
        //     the fiscal moment only — an abandoned cart never creates value.
        for (com.intermarche.pos.domain.ticket.TicketLine line : ticket.lines) {
            if (line.ean == null) continue;
            com.intermarche.pos.domain.Product product =
                    com.intermarche.pos.domain.Product.find("ean", line.ean).firstResult();
            if (product == null || product.giftCardAmount == null) continue;
            int units = line.quantity.intValue();
            for (int i = 0; i < units; i++) {
                com.intermarche.pos.domain.StoredValue card = new com.intermarche.pos.domain.StoredValue();
                card.kind = com.intermarche.pos.domain.StoredValue.Kind.GIFT_CARD;
                card.initialAmount = product.giftCardAmount;
                card.balance = product.giftCardAmount;
                card.issuedAt = LocalDateTime.now();
                card.issuingTicketId = ticket.id;
                // Same NOT NULL, 20-character column as the credit note: a
                // SHORT unique placeholder carries the INSERT, the definitive
                // number is derived from the generated id right after.
                card.number = "T" + Long.toUnsignedString(
                        java.util.UUID.randomUUID().getMostSignificantBits(), 36);
                card.persistAndFlush();
                card.number = com.intermarche.pos.domain.StoredValue.GIFT_CARD_PREFIX
                        + String.format("%012d", card.id);
            }
        }
        ticket.grandTotal = counter.grandTotal;

        ticket.persist();
        technicalEventService.log(TechnicalEvent.EventType.TICKET_CLOSED, ticket.ticketNumber);
        syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
    }

    /**
     * Marks the draft as cancelled (abandoned cart); the reserved sequence
     * number stays consumed and the row documents the gap.
     *
     * @param ticketId the database id of the draft to cancel
     */
    @Transactional
    public void cancelDraft(Long ticketId) {
        doCancelDraft(ticketId);
    }

    /**
     * Cancellation body shared by {@link #cancelDraft(Long)} and
     * {@link #syncDraft(PosState)} (already inside a transaction).
     *
     * @param ticketId the database id of the draft to cancel
     */
    private void doCancelDraft(Long ticketId) {
        Ticket ticket = Ticket.findById(ticketId);
        if (ticket != null && ticket.status == Ticket.TicketStatus.OPEN) {
            ticket.status = Ticket.TicketStatus.CANCELLED;
            ticket.persist();
            technicalEventService.log(TechnicalEvent.EventType.TICKET_CANCELLED, ticket.ticketNumber);
            syncOutboxService.enqueue(SyncOutbox.EntityType.TICKET, ticket.id);
            LOG.infof("Draft annulé ID: %d (%s)", ticket.id, ticket.ticketNumber);
        }
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Maps an in-memory ticket item to a persistent ticket line, carrying its
     * stable uid and the EAN / PLU snapshots needed for restart recovery.
     *
     * @param item the in-memory item
     * @param lineNumber the 1-based line number
     * @return the persistent line
     */
    private TicketLine mapLine(TicketState.TicketItem item, int lineNumber) {
        TicketLine line = new TicketLine();
        line.lineNumber = lineNumber;
        line.lineUid = item.uid;
        line.ean = item.ean;
        line.plu = item.plu;
        if (item.plu != null) {
            line.product = Product.findByPlu(item.plu);
        } else if (item.ean != null) {
            line.product = Product.findByEan(item.ean);
        }
        // Nomenclature snapshot (BO-04-01-11): the family the article was sold
        // under, captured here at line creation exactly as the price is, and
        // never re-resolved on later reconciliations — a referential
        // re-parenting after the sale must not move a consolidated line.
        if (line.product != null) {
            com.intermarche.pos.domain.ProductFamily family =
                    com.intermarche.pos.domain.ProductFamily.findDirectFamily(line.product);
            if (family != null) {
                line.familyCode = family.code;
                line.familyLabel = family.description;
            }
        }
        line.productLabel = item.label;
        line.quantity = item.quantity;
        line.unitPrice = item.unitPrice;
        line.totalPrice = item.getTotalPrice().setScale(2, RoundingMode.HALF_UP);
        line.vatRate = item.vatRate;
        line.deposit = item.isNegative();
        line.moneyProduct = item.moneyProduct;
        line.priceEmbedded = item.priceEmbedded;
        return line;
    }

    /**
     * Computes the SHA-256 signature of a chaining payload, as lowercase hex.
     *
     * @param payload the pipe-joined fiscal payload
     * @return the 64-character hex signature
     */
    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM specification; this cannot happen
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
