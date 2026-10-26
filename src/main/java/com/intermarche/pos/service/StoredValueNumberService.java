package com.intermarche.pos.service;

import com.intermarche.pos.domain.barcode.CouponField;
import com.intermarche.pos.domain.barcode.CouponType;
import com.intermarche.pos.domain.payment.StoredValue;
import com.intermarche.pos.domain.store.Store;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Numbers the instruments the register issues — credit notes and gift cards —
 * from the barcode range the back office administers for issuing them
 * (BO-03-06-30 to -38).
 *
 * <p>The range is the one the register already READS those instruments with:
 * a prefix, a total length and positioned fields. Printing is the reverse of
 * reading, field by field: the register number where the range puts the
 * register number, the issuing ticket's number where it puts a ticket number,
 * the time of issue in the administered HHMM or HHMMSS layout, the dates, the
 * amount, the store and its check character, the instrument's own sequence.
 * The code printed is therefore the code the same range recognises when the
 * customer hands the paper back.
 *
 * <p>Without such a range, or when the range cannot hold the instrument — a
 * field outside the code, a number already taken — the historical number is
 * kept: the prefix of the kind and the row id on twelve digits.
 */
@ApplicationScoped
public class StoredValueNumberService {

    /** Technical log of this class. */
    private static final Logger LOGGER = Logger.getLogger(StoredValueNumberService.class);

    /** This register's identifier, whose digits are the register number printed. */
    @ConfigProperty(name = "pos.terminal.id", defaultValue = "POS01")
    String terminalId;

    /**
     * Numbers an instrument already persisted, so its row id exists.
     *
     * @param instrument the issued instrument, with its kind, amounts and dates
     * @param documentNumber the number of the issuing sale or refund, or null
     * @return the number to print and store, never null
     */
    public String numberOf(StoredValue instrument, String documentNumber) {
        LOGGER.info("Entering method numberOf with documentNumber: " + documentNumber);
        CouponType range = CouponType.findIssuingRange(instrument.kind.name());
        String composed = range == null ? null : compose(range, instrument, documentNumber);
        if (composed != null && StoredValue.findByNumber(composed) == null) {
            LOGGER.info("Exiting method numberOf (composed)");
            return composed;
        }
        LOGGER.info("Exiting method numberOf (historical)");
        return (instrument.kind == StoredValue.Kind.GIFT_CARD
                ? StoredValue.GIFT_CARD_PREFIX : StoredValue.CREDIT_NOTE_PREFIX)
                + String.format("%012d", instrument.id);
    }

    /**
     * Composes the code of an instrument from a range's fields.
     *
     * @param range the issuing range
     * @param instrument the instrument
     * @param documentNumber the number of the issuing sale or refund, or null
     * @return the code, or null when the range cannot hold the instrument
     */
    String compose(CouponType range, StoredValue instrument, String documentNumber) {
        if (range.codeLength == null || range.codeLength <= 0) {
            return null;
        }
        char[] code = new char[range.codeLength];
        Arrays.fill(code, '0');
        String prefix = range.prefix == null ? "" : range.prefix;
        if (prefix.length() > code.length) {
            return null;
        }
        prefix.getChars(0, prefix.length(), code, 0);
        for (CouponField field : range.fields) {
            String value = valueOf(field, instrument, documentNumber);
            if (value == null) {
                continue;
            }
            if (field.offsetPosition < prefix.length() || field.endPosition() > code.length) {
                return null;
            }
            fit(value, field.fieldLength).getChars(0, field.fieldLength, code, field.offsetPosition);
        }
        return new String(code);
    }

    /**
     * The characters one field prints for an instrument.
     *
     * @param field the administered field
     * @param instrument the instrument
     * @param documentNumber the number of the issuing sale or refund, or null
     * @return the characters, or null when the field has nothing to print
     */
    String valueOf(CouponField field, StoredValue instrument, String documentNumber) {
        CouponField.DateFormat layout = field.dateFormat;
        return switch (field.role) {
            case PRICE -> instrument.initialAmount == null ? null
                    : instrument.initialAmount.movePointRight(field.effectiveDecimals())
                    .setScale(0, RoundingMode.HALF_UP).toPlainString();
            case TPV_NUMBER -> digits(terminalId);
            case TICKET_NUMBER -> sequenceOf(documentNumber);
            case SEQUENCE_NUMBER -> instrument.id == null ? null : String.valueOf(instrument.id);
            case TIME -> layout == null || instrument.issuedAt == null ? null
                    : layout.format(instrument.issuedAt.toLocalTime());
            case DATE_START -> layout == null || instrument.issuedAt == null ? null
                    : layout.format(instrument.issuedAt.toLocalDate());
            case DATE_END -> layout == null || instrument.validUntil == null ? null
                    : layout.format(instrument.validUntil.toLocalDate());
            case STORE_NUMBER -> digits(storeCode());
            case STORE_CHECK_DIGIT -> checkOf(storeCode());
            default -> null;
        };
    }

    /**
     * The point of sale number of this register.
     *
     * @return the store code, or null when the register holds no store row
     */
    String storeCode() {
        Store store = Store.findAll().firstResult();
        return store == null ? null : store.code;
    }

    /**
     * The store check character the shop control verifies: the sum of the
     * store number's digits, modulo ten.
     *
     * @param storeCode the store code, or null
     * @return the check character, or null without a store number
     */
    static String checkOf(String storeCode) {
        String digits = digits(storeCode);
        if (digits == null) {
            return null;
        }
        int sum = 0;
        for (char c : digits.toCharArray()) {
            sum += c - '0';
        }
        return String.valueOf(sum % 10);
    }

    /**
     * The sequence of a document number: the digits after its last dash, or
     * all its digits when it has none.
     *
     * @param documentNumber the number, possibly null
     * @return the digits, or null when there are none
     */
    static String sequenceOf(String documentNumber) {
        if (documentNumber == null) {
            return null;
        }
        int dash = documentNumber.lastIndexOf('-');
        return digits(dash < 0 ? documentNumber : documentNumber.substring(dash + 1));
    }

    /**
     * The digits of a text, in order.
     *
     * @param text the text, possibly null
     * @return the digits, or null when there are none
     */
    static String digits(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Fits characters into a field: zeros on the left when they are fewer,
     * the rightmost ones when they are more.
     *
     * @param value the characters
     * @param length the field's length
     * @return exactly {@code length} characters
     */
    static String fit(String value, int length) {
        if (value.length() >= length) {
            return value.substring(value.length() - length);
        }
        return "0".repeat(length - value.length()) + value;
    }
}
