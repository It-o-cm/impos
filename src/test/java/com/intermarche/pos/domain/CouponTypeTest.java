package com.intermarche.pos.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mockStatic;

/**
 * Unit tests for {@link CouponType}, targeting 100% branch coverage.
 * <p>
 * The three static finders resolve the Panache {@code list} finder, which under
 * plain {@code mvn test} falls back to {@link PanacheEntityBase}, so they are
 * intercepted with {@link org.mockito.Mockito#mockStatic}. Instance methods are
 * exercised directly on plain instances; every ternary and null/short-circuit
 * guard is covered on both arms. Each test is fully isolated and asserts
 * absolute expected values.
 */
class CouponTypeTest {

    /**
     * Builds a coupon type with the given match pattern.
     *
     * @param matchPattern the recognition pattern, possibly null or blank
     * @return the configured coupon type
     */
    private CouponType withPattern(String matchPattern) {
        CouponType type = new CouponType();
        type.matchPattern = matchPattern;
        return type;
    }

    /**
     * A fresh coupon type carries the declared field defaults.
     */
    @Test
    void fieldDefaults() {
        CouponType type = new CouponType();
        Assertions.assertTrue(type.active);
        Assertions.assertFalse(type.depositLine);
        Assertions.assertEquals(100, type.priority);
    }

    /**
     * matches returns false when the number is null (first OR arm true).
     */
    @Test
    void matchesNullNumberIsFalse() {
        CouponType type = withPattern("\\d+");
        Assertions.assertFalse(type.matches(null));
    }

    /**
     * matches returns false when the pattern is null (second OR arm true).
     */
    @Test
    void matchesNullPatternIsFalse() {
        CouponType type = withPattern(null);
        Assertions.assertFalse(type.matches("1234"));
    }

    /**
     * matches returns true when a non-null number satisfies a non-null pattern.
     */
    @Test
    void matchesMatchingNumberIsTrue() {
        CouponType type = withPattern("\\d{4}");
        Assertions.assertTrue(type.matches("1234"));
    }

    /**
     * matches returns false when a non-null number fails a non-null pattern.
     */
    @Test
    void matchesNonMatchingNumberIsFalse() {
        CouponType type = withPattern("\\d{4}");
        Assertions.assertFalse(type.matches("abcd"));
    }

    /**
     * requiresManualAmount is true when the amount source is MANUAL.
     */
    @Test
    void requiresManualAmountTrueForManual() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.MANUAL;
        Assertions.assertTrue(type.requiresManualAmount());
    }

    /**
     * requiresManualAmount is false when the amount source is ENCODED.
     */
    @Test
    void requiresManualAmountFalseForEncoded() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        Assertions.assertFalse(type.requiresManualAmount());
    }

    /**
     * hasNumber is false when the pattern is null (first AND arm false).
     */
    @Test
    void hasNumberFalseWhenPatternNull() {
        Assertions.assertFalse(withPattern(null).hasNumber());
    }

    /**
     * hasNumber is false when the pattern is blank (second AND arm false).
     */
    @Test
    void hasNumberFalseWhenPatternBlank() {
        Assertions.assertFalse(withPattern("   ").hasNumber());
    }

    /**
     * hasNumber is true when the pattern is present and non-blank.
     */
    @Test
    void hasNumberTrueWhenPatternPresent() {
        Assertions.assertTrue(withPattern("\\d+").hasNumber());
    }

    /**
     * extractAmount returns null when the amount source is not ENCODED
     * (first OR arm true).
     */
    @Test
    void extractAmountNullWhenNotEncoded() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.MANUAL;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount returns null when the amount pattern is null
     * (second OR arm true).
     */
    @Test
    void extractAmountNullWhenPatternNull() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = null;
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount returns null when the number is null (third OR arm true).
     */
    @Test
    void extractAmountNullWhenNumberNull() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount(null));
    }

    /**
     * extractAmount returns null when the pattern does not match the number
     * (first arm of the second guard: find fails).
     */
    @Test
    void extractAmountNullWhenNoMatch() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("abc"));
    }

    /**
     * extractAmount returns null when the matching pattern has no capturing
     * group (second arm of the second guard: groupCount below one).
     */
    @Test
    void extractAmountNullWhenNoCapturingGroup() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "\\d+";
        Assertions.assertNull(type.extractAmount("1050"));
    }

    /**
     * extractAmount reads the first capturing group as cents and scales it to a
     * two-decimal amount.
     */
    @Test
    void extractAmountReadsEncodedCents() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "AMT(\\d+)";
        Assertions.assertEquals(new BigDecimal("10.50"), type.extractAmount("AMT01050"));
    }

    /**
     * extractAmount returns null when the captured group overflows a long and
     * cannot be parsed (NumberFormatException arm).
     */
    @Test
    void extractAmountNullWhenGroupNotParsable() {
        CouponType type = new CouponType();
        type.amountSource = CouponType.AmountSource.ENCODED;
        type.amountPattern = "(\\d+)";
        Assertions.assertNull(type.extractAmount("99999999999999999999"));
    }

    /**
     * listActiveByPriority delegates to the priority-ordered active finder.
     */
    @Test
    void listActiveByPriorityDelegatesToFinder() {
        List<CouponType> expected = List.of(new CouponType());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.list("active = true order by priority"))
                    .thenReturn(expected);
            Assertions.assertSame(expected, CouponType.listActiveByPriority());
        }
    }

    /**
     * listActivePaymentTypes delegates to the non-deposit active finder.
     */
    @Test
    void listActivePaymentTypesDelegatesToFinder() {
        List<CouponType> expected = List.of(new CouponType());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.list("active = true and depositLine = false order by priority"))
                    .thenReturn(expected);
            Assertions.assertSame(expected, CouponType.listActivePaymentTypes());
        }
    }

    /**
     * listActiveDepositTypes delegates to the deposit active finder.
     */
    @Test
    void listActiveDepositTypesDelegatesToFinder() {
        List<CouponType> expected = List.of(new CouponType());
        try (MockedStatic<PanacheEntityBase> mocked = mockStatic(PanacheEntityBase.class)) {
            mocked.when(() -> CouponType.list("active = true and depositLine = true order by priority"))
                    .thenReturn(expected);
            Assertions.assertSame(expected, CouponType.listActiveDepositTypes());
        }
    }

    /**
     * The AmountSource enum exposes exactly its two declared constants and
     * round-trips through valueOf.
     */
    @Test
    void amountSourceEnumHasTwoConstants() {
        Assertions.assertEquals(2, CouponType.AmountSource.values().length);
        Assertions.assertEquals(CouponType.AmountSource.ENCODED,
                CouponType.AmountSource.valueOf("ENCODED"));
        Assertions.assertEquals(CouponType.AmountSource.MANUAL,
                CouponType.AmountSource.valueOf("MANUAL"));
    }
}
