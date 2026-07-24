package com.intermarche.pos.domain.ticket;

import com.intermarche.pos.domain.BaseEntity;
import com.intermarche.pos.domain.Employee;
import com.intermarche.pos.domain.Store;
import com.intermarche.pos.domain.CashSession;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A sale ticket, persisted as a draft from the first scanned article
 * (phase 0 lot 2) and closed at payment completion.
 * <p>
 * The register that owns the ticket is recorded in {@link #terminalId}; this
 * is what restart recovery uses to find the open draft of this terminal in
 * the register's database, and what the phase 1 per-register fiscal
 * chaining ({@link #signature}, {@link #previousSignature},
 * {@link #grandTotal}) keys on.
 * <p>
 * Lifecycle: OPEN from the first article; OPEN → PARKED (cart parked, only
 * with no payment registered) → OPEN again on resume; OPEN → CLOSED at
 * payment completion (number chained, totals frozen); OPEN → CANCELLED on an
 * abandoned cart; PARKED → CANCELLED at the Z closing of the session. CLOSED
 * and CANCELLED are terminal. In training mode (phase 6) no row is ever
 * created: the draft sync is the single guard everything else derives from.
 * <p>
 * Store synchronization (phase 5): pushed at CLOSED and CANCELLED, upserted
 * on the store node by {@link #ticketNumber} — the only portable identity
 * (database ids are local to each register). {@link #digitalKey} is 16 hex
 * characters generated at draft creation: unguessable, short enough to print,
 * and independent of the closing so the online-receipt link exists before the
 * signature does; the public page only serves CLOSED tickets.
 */
@Entity
@Table(name = "tickets",
        indexes = {
                @Index(name = "idx_ticket_number", columnList = "ticket_number", unique = true),
                @Index(name = "idx_ticket_date", columnList = "creation_date"),
                @Index(name = "idx_ticket_cashier", columnList = "cashier_id"),
                @Index(name = "idx_ticket_terminal_status", columnList = "terminal_id, status")
        }
)
public class Ticket extends BaseEntity {

    // --------------------------------------------------
    // Ticket status
    // --------------------------------------------------

    /**
     * Lifecycle status of a ticket.
     */
    public enum TicketStatus {
        /** Draft in progress (cart being built or payment in progress). */
        OPEN,
        /** Parked cart, waiting to be resumed on this register. */
        PARKED,
        /** Finalized and validated. */
        CLOSED,
        /** Abandoned before validation (cancelled cart); documents sequence gaps. */
        CANCELLED
    }

    /** The lifecycle status; defaults to OPEN. */
    @Column(name = "status", nullable = false)
    @NotNull
    public TicketStatus status = TicketStatus.OPEN;

    // --------------------------------------------------
    // Identity & date
    // --------------------------------------------------

    /** The sequential ticket number, e.g. "C04-00000123", unique per register database. */
    @Column(name = "ticket_number", unique = true, nullable = false, length = 30)
    @NotBlank
    public String ticketNumber;

    /** The identifier of the register that created the ticket (pos.terminal.id). */
    @Column(name = "terminal_id", nullable = false, length = 20)
    @NotNull
    public String terminalId;

    /** The creation timestamp of the draft. */
    @Column(name = "creation_date", nullable = false)
    @NotNull
    public LocalDateTime creationDate;

    /** The closing timestamp, set when the ticket is validated, or null. */
    @Column(name = "closing_date")
    public LocalDateTime closingDate;

    // --------------------------------------------------
    // Fiscal chaining (phase 1)
    // --------------------------------------------------

    /** SHA-256 signature of this ticket, chained per register; set at closing. */
    @Column(name = "signature", length = 64)
    public String signature;

    /** Signature of the previous closed ticket of the same register, or "GENESIS". */
    @Column(name = "previous_signature", length = 64)
    public String previousSignature;

    /** Snapshot of the register's perpetual grand total after this ticket. */
    @Column(name = "grand_total", precision = 19, scale = 4)
    public BigDecimal grandTotal;

    /** Number of times this ticket was printed (beyond 1 = duplicata). */
    @Column(name = "print_count", nullable = false)
    public int printCount = 0;

    // --------------------------------------------------
    // Valuation (dormant until phase 7)
    // --------------------------------------------------

    /** Valuation status of a ticket toward the external valuation engine. */
    public enum ValuationStatus {
        /** Catalog prices only; the valuation engine was not consulted. */
        NOT_VALUATED,
        /** Successfully valuated by the engine. */
        VALUATED,
        /** Engine unreachable or inconsistent; catalog prices applied (degraded mode). */
        DEGRADED
    }

    /** The valuation status; defaults to NOT_VALUATED until phase 7 wires the engine. */
    @Column(name = "valuation_status", nullable = false, length = 20)
    @NotNull
    public ValuationStatus valuationStatus = ValuationStatus.NOT_VALUATED;

    // --------------------------------------------------
    // Relations (unidirectional)
    // --------------------------------------------------

    /** The store the ticket belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    @NotNull
    public Store store;

    /** The cashier who opened the ticket. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cashier_id", nullable = false)
    @NotNull
    public Employee cashier;

    /** The cash session the ticket was sold under (null on pre-phase-2 rows). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    public CashSession session;

    /** The fidelity card presented for this ticket, or null. */
    @Column(name = "fidelity_card", length = 50)
    public String fidelityCard;

    /** Access key of the online digital receipt, generated at draft creation. */
    @Column(name = "digital_key", length = 16)
    public String digitalKey;

    /** The email the digital receipt was sent to, or null. */
    @Column(name = "customer_email", length = 120)
    public String customerEmail;

    /** The ticket lines (FK held by the child table, orphan removal on). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketLine> lines = new ArrayList<>();

    /** The registered payments (FK held by the child table, orphan removal on). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "ticket_id", nullable = false)
    public List<TicketPayment> payments = new ArrayList<>();

    // --------------------------------------------------
    // Financial totals
    // --------------------------------------------------

    /** The number of lines on the ticket. */
    @Column(name = "item_count", nullable = false)
    public int itemCount;

    /** The total excluding tax. */
    @Column(name = "total_ht", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalExcludingTax;

    /** The total including tax. */
    @Column(name = "total_ttc", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalIncludingTax;

    /** The total VAT amount. */
    @Column(name = "total_vat", nullable = false, precision = 19, scale = 4)
    @NotNull
    public BigDecimal totalVat;

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Adds a line to the ticket.
     *
     * @param line the line to add
     */
    public void addLine(TicketLine line) {
        this.lines.add(line);
    }

    /**
     * Adds a payment to the ticket.
     *
     * @param payment the payment to add
     */
    public void addPayment(TicketPayment payment) {
        this.payments.add(payment);
    }

    /**
     * Returns the tax-included total formatted for display (2 decimals, French comma).
     * Formatting is done server-side on purpose.
     *
     * @return the formatted total
     */
    public String getTotalFormatted() {
        if (this.totalIncludingTax == null) return "0,00";
        BigDecimal rounded = this.totalIncludingTax.setScale(2, RoundingMode.HALF_UP);
        return String.format("%.2f", rounded).replace(".", ",");
    }

    /**
     * Returns the audit checksum of the ticket.
     *
     * @return a hash of the identifying, financial and chaining fields
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ticketNumber, creationDate, status, totalIncludingTax, signature);
    }
}
