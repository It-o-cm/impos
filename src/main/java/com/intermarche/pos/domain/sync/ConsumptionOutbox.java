package com.intermarche.pos.domain.sync;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A campaign-consumption declaration awaiting delivery to the valuation engine.
 *
 * <p>It is the persisted, replayable queue behind the spec's {@code
 * POST /consumptions}: a finalized sale carrying engine advantages leaves one
 * SALE row here, a later cancellation a symmetric CANCELLATION row, and a
 * delivery loop drains them to the engine. Like the other plumbing tables
 * ({@code SyncOutbox}, {@code EngineFeed}) it extends {@link PanacheEntity}
 * directly — no audit noise, no checksum.
 *
 * <p>The identity {@code (storeCode, ticketNumber, direction)} is unique, which
 * is the same key the engine is idempotent on: a row is enqueued at most once
 * per (ticket, direction), so a replay by any link never double-counts.
 */
@Entity
@Table(name = "consumption_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_consumption_key",
                columnNames = {"store_code", "ticket_number", "direction"}))
public class ConsumptionOutbox extends PanacheEntity {

    /**
     * Which way a declaration moves the engine's counters: a sale adds, a
     * cancellation reverses.
     */
    public enum Direction {

        /** A finalized sale's consumption. */
        SALE,

        /** The reversal of a previously declared sale. */
        CANCELLATION
    }

    /** The fiscal number of the ticket the declaration is derived from. */
    @Column(name = "ticket_number", nullable = false)
    public String ticketNumber;

    /** The code of the point of sale that closed the ticket. */
    @Column(name = "store_code")
    public String storeCode;

    /** When the ticket was finalized (its closing date). */
    @Column(name = "finalized_at")
    public LocalDateTime finalizedAt;

    /** The direction of the declaration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    public Direction direction;

    /** The advantages, as the JSON array {@code [{"offerCode":..,"amount":..}]}. */
    @Lob
    @Column(name = "advantages_json")
    public String advantagesJson;

    /** Whether the engine has acknowledged this declaration. */
    @Column(name = "applied", nullable = false)
    public boolean applied;

    /** How many delivery attempts have been made. */
    @Column(name = "attempts", nullable = false)
    public int attempts;

    /** The last delivery error toward the engine, or null. */
    @Column(name = "last_error")
    public String lastError;

    /**
     * Finds the declaration carrying a given idempotency key, if any.
     *
     * @param storeCode the point of sale code
     * @param ticketNumber the ticket number
     * @param direction the declaration direction
     * @return the row, or null when none carries that key
     */
    public static ConsumptionOutbox findByKey(String storeCode, String ticketNumber,
            Direction direction) {
        return find("storeCode = ?1 and ticketNumber = ?2 and direction = ?3",
                storeCode, ticketNumber, direction).firstResult();
    }

    /**
     * Lists the declarations still awaiting engine acknowledgement, oldest
     * first.
     *
     * @return the pending declarations, never null
     */
    public static List<ConsumptionOutbox> pending() {
        return find("applied = false order by id").list();
    }
}
