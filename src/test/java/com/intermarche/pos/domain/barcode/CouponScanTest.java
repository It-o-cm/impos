package com.intermarche.pos.domain.barcode;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CouponScan}, targeting 100% branch coverage.
 * <p>
 * The single static finder resolves the Panache {@code count} finder, which
 * under plain {@code mvn test} falls back to {@link PanacheEntityBase}, so it
 * is intercepted with {@link org.mockito.Mockito#mockStatic}.
 * <p>
 * Branch enumeration (every leg exercised): {@code alreadySeen} covers the null
 * range leg, the null identity leg, the blank identity leg, and both arms of
 * the count comparison at its boundary.
 */
class CouponScanTest {

    /**
     * A scan row carries what the duplicate control compares and the numbers
     * the code held.
     */
    @Test
    void fieldsAreCarriedVerbatim() {
        CouponScan scan = new CouponScan();
        scan.code = "2981234561234";
        scan.typeCode = "DEPOSIT_VOUCHER";
        scan.identity = "123456";
        scan.ticketNumber = "0042";
        scan.tpvNumber = "03";
        Assertions.assertEquals("2981234561234", scan.code);
        Assertions.assertEquals("DEPOSIT_VOUCHER", scan.typeCode);
        Assertions.assertEquals("123456", scan.identity);
        Assertions.assertEquals("0042", scan.ticketNumber);
        Assertions.assertEquals("03", scan.tpvNumber);
        Assertions.assertNull(scan.scannedAt);
    }

    /**
     * alreadySeen is false without a range (first leg of the guard).
     */
    @Test
    void alreadySeenWithoutARangeIsFalse() {
        Assertions.assertFalse(CouponScan.alreadySeen(null, "123456"));
    }

    /**
     * alreadySeen is false without an identity (second leg of the guard).
     */
    @Test
    void alreadySeenWithoutAnIdentityIsFalse() {
        Assertions.assertFalse(CouponScan.alreadySeen("GIFT", null));
    }

    /**
     * alreadySeen is false on a blank identity (third leg of the guard).
     */
    @Test
    void alreadySeenOnABlankIdentityIsFalse() {
        Assertions.assertFalse(CouponScan.alreadySeen("GIFT", "  "));
    }

    /**
     * alreadySeen is false when nothing matches (count of zero, at the boundary).
     */
    @Test
    void alreadySeenIsFalseWhenNothingMatches() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "typeCode = ?1 and identity = ?2", "GIFT", "123456")).thenReturn(0L);
            Assertions.assertFalse(CouponScan.alreadySeen("GIFT", "123456"));
        }
    }

    /**
     * alreadySeen is true from the first match on (count of one, at the boundary).
     */
    @Test
    void alreadySeenIsTrueFromTheFirstMatchOn() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "typeCode = ?1 and identity = ?2", "GIFT", "123456")).thenReturn(1L);
            Assertions.assertTrue(CouponScan.alreadySeen("GIFT", "123456"));
        }
    }
}
