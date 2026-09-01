package com.intermarche.pos.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for {@link CashMovement}.
 * <p>
 * The entity is a branch-free data carrier: public fields, a five-value
 * {@link CashMovement.MovementType} enum and a single {@code getChecksum}
 * whose only logic is an {@link Objects#hash} of the salient business fields.
 * The tests pin the checksum to its exact expected value (so a silent
 * constant-hash regression — the RefundLine lesson — would fail), prove it
 * changes when a hashed field changes, and assert the enum's constants and
 * ordering, which drives no DB ordinal used elsewhere but is part of the
 * entity's public contract. No database and no Quarkus context is booted.
 */
class CashMovementTest {

    /**
     * Builds a fully populated movement.
     *
     * @return the movement fixture
     */
    private CashMovement movement() {
        CashMovement movement = new CashMovement();
        movement.movementUid = "M-1";
        movement.terminalId = "C04";
        movement.type = CashMovement.MovementType.WITHDRAWAL;
        movement.amount = new BigDecimal("30.00");
        movement.movementDate = LocalDateTime.of(2026, 9, 1, 15, 42, 0);
        movement.reason = "coffre";
        movement.endorsedBy = "11111111";
        return movement;
    }

    /**
     * The checksum is the exact {@link Objects#hash} of the identifying and
     * financial fields — uid, terminal, type, amount and date — and nothing
     * else (the reason and the endorser are excluded on purpose).
     */
    @Test
    void checksumHashesTheSalientFields() {
        CashMovement movement = movement();
        int expected = Objects.hash("M-1", "C04", CashMovement.MovementType.WITHDRAWAL,
                new BigDecimal("30.00"), LocalDateTime.of(2026, 9, 1, 15, 42, 0));
        assertEquals(expected, movement.getChecksum());
    }

    /**
     * A change to a hashed field (the amount) changes the checksum: the field
     * is not silently dropped from the hash.
     */
    @Test
    void checksumChangesWithAmount() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.amount = new BigDecimal("31.00");
        assertNotEquals(before, movement.getChecksum());
    }

    /**
     * A change to a NON-hashed field (the reason) leaves the checksum
     * unchanged: the checksum tracks business identity, not free text.
     */
    @Test
    void checksumIgnoresReason() {
        CashMovement movement = movement();
        int before = movement.getChecksum();
        movement.reason = "autre motif";
        assertEquals(before, movement.getChecksum());
    }

    /**
     * The checksum tolerates null fields (a bare movement) without throwing.
     */
    @Test
    void checksumToleratesNulls() {
        CashMovement bare = new CashMovement();
        assertEquals(Objects.hash(null, null, null, null, null), bare.getChecksum());
    }

    /**
     * The five movement types are exactly those the campaign enumerates, in a
     * stable declaration order.
     */
    @Test
    void movementTypeEnumeratesTheFiveKinds() {
        assertEquals(5, CashMovement.MovementType.values().length);
        assertEquals(CashMovement.MovementType.WITHDRAWAL, CashMovement.MovementType.valueOf("WITHDRAWAL"));
        assertEquals(CashMovement.MovementType.DEPOSIT, CashMovement.MovementType.valueOf("DEPOSIT"));
        assertEquals(CashMovement.MovementType.EXPENSE, CashMovement.MovementType.valueOf("EXPENSE"));
        assertEquals(CashMovement.MovementType.CUSTOMER_DEPOSIT, CashMovement.MovementType.valueOf("CUSTOMER_DEPOSIT"));
        assertEquals(CashMovement.MovementType.DECLARATION, CashMovement.MovementType.valueOf("DECLARATION"));
    }
}
