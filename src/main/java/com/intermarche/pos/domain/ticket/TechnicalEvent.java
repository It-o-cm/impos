package com.intermarche.pos.domain.ticket;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A technical event journal entry (NF525 direction): ticket closures and
 * cancellations, duplicata printing, payment resets, draft recoveries.
 * <p>
 * One row per event, stamped with the register that produced it. Extends
 * {@link PanacheEntity} directly (pure append-only journal, no audit fields).
 * <p>
 * Every log entry is enqueued for store synchronization (EVENT is the last
 * drained kind, after sessions, tickets and refunds) and upserted on the
 * store node by {@link #eventUid} — a UUID minted at creation precisely
 * because journal entries have no natural business number; re-pushing is
 * therefore idempotent. On the store node itself the enqueue is a no-op (no
 * store URL configured), so ingestion cannot loop.
 */
@Entity
@Table(name = "technical_events",
        indexes = {
                @Index(name = "idx_tech_event_terminal", columnList = "terminal_id, event_date"),
                @Index(name = "idx_tech_event_type", columnList = "event_type")
        }
)
public class TechnicalEvent extends PanacheEntity {

    /** Event types recorded in the journal. */
    public enum EventType {
        /** A ticket was closed (validated). */
        TICKET_CLOSED,
        /** A draft was cancelled (abandoned cart). */
        TICKET_CANCELLED,
        /** A duplicata of an already-printed ticket was printed. */
        DUPLICATA_PRINTED,
        /** The payments of a draft were cleared. */
        PAYMENTS_CLEARED,
        /** An in-progress draft was recovered at register startup. */
        DRAFT_RECOVERED,
        /** A cash session was opened with its initial float. */
        SESSION_OPENED,
        /** A cash session was closed by a Z report (count, variance, withdrawal). */
        SESSION_CLOSED,
        /** An X report (read-only session snapshot) was printed. */
        X_REPORT_PRINTED,
        /** A manager endorsement was granted. */
        ENDORSEMENT_GRANTED,
        /** A manager endorsement was refused. */
        ENDORSEMENT_DENIED,
        /** An account was locked after repeated PIN failures. */
        AUTH_LOCKED,
        /** The current cart was parked, waiting to be resumed. */
        TICKET_PARKED,
        /** A parked cart was resumed on this register. */
        TICKET_RESUMED,
        /** A refund was created (method and amount in the detail). */
        REFUND_CREATED,
        /** The digital receipt was sent to a customer email. */
        DIGITAL_TICKET_SENT,
        /** The cashier called a supervisor (reason in the detail). */
        SUPERVISOR_CALLED,
        /** The register entered training mode. */
        TRAINING_STARTED,
        /** The register left training mode. */
        TRAINING_ENDED
    }

    /** The type of the event. */
    @Column(name = "event_type", nullable = false, length = 30)
    public EventType eventType;

    /** The identifier of the register that produced the event. */
    @Column(name = "terminal_id", nullable = false, length = 20)
    public String terminalId;

    /** The timestamp of the event. */
    @Column(name = "event_date", nullable = false)
    public LocalDateTime eventDate;

    /** A short human-readable detail (ticket number, count...). */
    @Column(name = "detail", length = 255)
    public String detail;

    /** Stable identity of the event across nodes (store-sync upsert key). */
    @Column(name = "event_uid", unique = true, length = 36)
    public String eventUid;
}
