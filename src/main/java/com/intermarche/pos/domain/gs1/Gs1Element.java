package com.intermarche.pos.domain.gs1;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * One decoded element of a GS1 payload: an identifier, its raw value, and the reading
 * that identifier calls for.
 *
 * <p>THE RAW VALUE IS KEPT WHATEVER HAPPENS, including for an identifier this version
 * does not know. {@code LC-11-03-02} asks that every decoded identifier be recorded in
 * the transactional data and the technical journal "même s'il n'y a pas d'usage ou règle
 * de gestion défini(e)" — an element the register cannot act on is still an element it
 * must be able to show, and an element it drops is one nobody can investigate later.
 *
 * <p>The typed readings never throw. A date that is not a date, or an amount that is not
 * a number, comes back empty: a badly encoded element must not stop the article being
 * registered ({@code LC-11-03-03}), and a caller that gets nothing simply has no rule to
 * apply.
 *
 * @param code       the identifier's digits as the payload carried them, decimal-place
 *                   digit included ({@code 3103})
 * @param identifier the identifier this version knows, or null when it knows none
 * @param value      the element's data, exactly as encoded
 * @param decimals   the number of decimal places the identifier's last digit gave, zero
 *                   for an identifier that carries none
 */
public record Gs1Element(String code, Gs1ApplicationIdentifier identifier, String value,
                         int decimals) {

    /**
     * Tells whether this version knows what the identifier means.
     *
     * @return true when the element carries a known identifier
     */
    public boolean isKnown() {
        return identifier != null;
    }

    /**
     * Returns what the identifier means, for the technical journal.
     *
     * @return the identifier's label, or a wording naming it unknown
     */
    public String getLabel() {
        return identifier == null ? "AI inconnu" : identifier.label();
    }

    /**
     * Reads the element as a GS1 date, {@code YYMMDD}.
     *
     * <p>Two rules of the standard are honoured here. A DAY OF {@code 00} means "the end
     * of that month" and not "the zeroth day", which is how a best-before is encoded
     * when only the month matters — reading it as invalid would silently drop a whole
     * class of expiry dates. And the two-digit year names the closest century: the
     * standard's window is fifty years back and forty-nine forward, so {@code 991231}
     * scanned in 2026 is 1999 and not 2099.
     *
     * @return the date, or null when the element is not a readable date
     */
    public LocalDate asDate() {
        if (value == null || value.length() != 6 || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int year = century(Integer.parseInt(value.substring(0, 2)));
        int month = Integer.parseInt(value.substring(2, 4));
        int day = Integer.parseInt(value.substring(4, 6));
        if (month < 1 || month > 12) {
            return null;
        }
        try {
            return day == 0
                    ? YearMonth.of(year, month).atEndOfMonth()
                    : LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            // A day the month does not have — 31 in April, 30 in February. The element
            // is unreadable, which is not a reason to refuse the article.
            return null;
        }
    }

    /**
     * Resolves the century of a two-digit GS1 year.
     *
     * @param twoDigits the year's last two digits
     * @return the full year, in the standard's fifty-year window around today
     */
    private static int century(int twoDigits) {
        int currentYear = LocalDate.now().getYear();
        int candidate = (currentYear / 100) * 100 + twoDigits;
        if (candidate - currentYear > 49) {
            return candidate - 100;
        }
        if (currentYear - candidate > 50) {
            return candidate + 100;
        }
        return candidate;
    }

    /**
     * Reads the element as a number, the identifier's decimal-place digit applied.
     *
     * <p>{@code (3103)000195} is 0.195 kg and {@code (3922)1250} is 12.50 € — the digits
     * are the same integer and only the identifier says where the point goes, which is
     * why shifting it belongs here and not to each caller.
     *
     * @return the value, or null when the element is not a number
     */
    public BigDecimal asDecimal() {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return new BigDecimal(value).movePointLeft(decimals);
    }

    /**
     * Reads the element as a whole number, for an identifier carrying a count.
     *
     * @return the count, or null when the element is not a whole number
     */
    public Integer asInteger() {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            // More digits than an int holds: a count that large is not a count.
            return null;
        }
    }

    /**
     * Returns the element as the technical journal writes it ({@code LC-11-03-02}).
     *
     * @return the identifier, its meaning and its value
     */
    @Override
    public String toString() {
        return "(" + code + ")" + value + " [" + getLabel() + "]";
    }
}
