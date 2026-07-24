package com.intermarche.pos.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes a known type of voucher (gift voucher, loyalty cheque, Catalina, etc.).
 * <p>
 * Rows are loaded from the database / configuration; there is no in-application
 * administration screen. At scan or manual entry time, a voucher number is matched
 * against each active type (ordered by {@link #priority}) to determine its type and,
 * when possible, its amount.
 */
@Entity
@Table(name = "coupon_type")
public class CouponType extends PanacheEntity {

    /**
     * The stable technical code of the type (for example, CATALINA, GIFT_VOUCHER).
     */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    /**
     * The human-readable label shown to the cashier (for example, "Chèque cadeau").
     */
    @Column(name = "label", nullable = false, length = 100)
    public String label;

    /**
     * The regular expression a voucher number must match to be recognized as this type.
     * <p>
     * Also used in manual entry to detect typing mistakes: a number matching no pattern
     * is rejected.
     */
    @Column(name = "match_pattern", nullable = false, length = 255)
    public String matchPattern;

    /**
     * Indicates whether the amount is encoded in the number or must be entered manually.
     */
    @Column(name = "amount_source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public AmountSource amountSource;

    /**
     * The regular expression used to extract the amount from the number when
     * {@link #amountSource} is {@link AmountSource#ENCODED}. The first capturing group
     * must hold the amount in cents. Ignored for {@link AmountSource#MANUAL}.
     */
    @Column(name = "amount_pattern", length = 255)
    public String amountPattern;

    /**
     * Whether this type is currently active and should be considered at scan time.
     */
    @Column(name = "active", nullable = false)
    public boolean active = true;

    /**
     * Distinguishes deposit-return vouchers from payment vouchers.
     * <p>
     * When true, a scanned voucher of this type adds a negative line to the
     * ticket (déconsigne) instead of registering a payment; such a type must
     * carry its amount encoded ({@link AmountSource#ENCODED}). The two
     * families never mix: payment resolution and the payment panel only see
     * non-deposit types, the deposit scan handler only sees deposit types.
     */
    @Column(name = "deposit_line", nullable = false)
    public boolean depositLine = false;

    /**
     * The order in which types are tested against a number; lower runs first.
     */
    @Column(name = "priority", nullable = false)
    public int priority = 100;

    /**
     * Tests whether the given voucher number is recognized as this type.
     *
     * @param number the voucher number, scanned or typed
     * @return true if the number matches this type's pattern
     */
    public boolean matches(String number) {
        if (number == null || matchPattern == null) {
            return false;
        }
        return number.matches(matchPattern);
    }

    /**
     * Indicates whether the cashier must enter the amount for this type.
     *
     * @return true if the amount cannot be derived from the number
     */
    public boolean requiresManualAmount() {
        return amountSource == AmountSource.MANUAL;
    }

    /**
     * Indicates whether this type carries a voucher number.
     * <p>
     * A type without a match pattern represents a numberless voucher (generic /
     * ephemeral coupon) for which only an amount is entered.
     *
     * @return true if a number is expected for this type
     */
    public boolean hasNumber() {
        return matchPattern != null && !matchPattern.isBlank();
    }

    /**
     * Extracts the amount encoded in the given number, when applicable.
     *
     * @param number the voucher number
     * @return the extracted amount, or null if the amount is not encoded or cannot be read
     */
    public BigDecimal extractAmount(String number) {
        if (amountSource != AmountSource.ENCODED || amountPattern == null || number == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(amountPattern).matcher(number);
        if (!matcher.find() || matcher.groupCount() < 1) {
            return null;
        }
        try {
            long cents = Long.parseLong(matcher.group(1));
            return BigDecimal.valueOf(cents, 2);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the active coupon types ordered by ascending priority,
     * regardless of their kind.
     *
     * @return the ordered list of active types
     */
    public static List<CouponType> listActiveByPriority() {
        return list("active = true order by priority");
    }

    /**
     * Returns the active payment voucher types (deposit-return types excluded)
     * ordered by ascending priority.
     *
     * @return the ordered list of active payment types
     */
    public static List<CouponType> listActivePaymentTypes() {
        return list("active = true and depositLine = false order by priority");
    }

    /**
     * Returns the active deposit-return voucher types ordered by ascending
     * priority.
     *
     * @return the ordered list of active deposit-return types
     */
    public static List<CouponType> listActiveDepositTypes() {
        return list("active = true and depositLine = true order by priority");
    }

    /**
     * Defines how the monetary value of a voucher is obtained.
     */
    public enum AmountSource {

        /**
         * The amount is encoded within the voucher number and can be extracted from it.
         */
        ENCODED,

        /**
         * The amount cannot be derived from the number and must be entered by the cashier
         * (for example, Catalina coupons).
         */
        MANUAL
    }
}
