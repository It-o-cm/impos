package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One code of an administered range read at a till of this point of sale
 * (BO-03-06-39/49).
 *
 * <p>The duplicate control has to remember: a code is "already used" only
 * against a trace of what came before. This is that trace — the register's own
 * ledger of the coupons it has accepted, kept per point of sale, which is the
 * scope the requirement names.
 *
 * <p>The identity checked for a duplicate is the SEQUENCE NUMBER when the range
 * administers one, because that is what the issuer varies from one voucher to
 * the next; a range without a sequence falls back on the whole code. Both are
 * stored, so a range that gains a sequence position later keeps its history
 * usable.
 */
@Entity
@Table(name = "coupon_scan", indexes = {
        @Index(name = "idx_coupon_scan_code", columnList = "code"),
        @Index(name = "idx_coupon_scan_identity", columnList = "type_code,identity")
})
public class CouponScan extends PanacheEntity {

    /**
     * The code as it was read.
     */
    @Column(name = "code", nullable = false, length = 64)
    public String code;

    /**
     * The code of the range that recognised it.
     */
    @Column(name = "type_code", nullable = false, length = 50)
    public String typeCode;

    /**
     * What the duplicate control compares: the sequence number when the range
     * administers one, the whole code otherwise.
     */
    @Column(name = "identity", nullable = false, length = 64)
    public String identity;

    /**
     * The ticket number the code carried, or null when it carries none.
     */
    @Column(name = "ticket_number", length = 32)
    public String ticketNumber;

    /**
     * The register number the code carried, or null when it carries none.
     */
    @Column(name = "tpv_number", length = 32)
    public String tpvNumber;

    /**
     * When the code was read.
     */
    @Column(name = "scanned_at", nullable = false)
    public LocalDateTime scannedAt;

    /**
     * Tells whether a range has already seen that identity on this point of sale.
     *
     * @param typeCode the code of the range, or null
     * @param identity the compared identity, or null
     * @return true when a matching scan is already recorded
     */
    public static boolean alreadySeen(String typeCode, String identity) {
        if (typeCode == null || identity == null || identity.isBlank()) {
            return false;
        }
        return count("typeCode = ?1 and identity = ?2", typeCode, identity) > 0;
    }
}
