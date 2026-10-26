package com.intermarche.pos.ui.payment;

import com.ibm.icu.text.RuleBasedNumberFormat;
import com.intermarche.pos.ui.Messages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Localised amounts in words for cheque printing (LC-07-05-03/-06). */
public final class ChequeAmountWords {

    /** Prevents instantiation of this pure formatting helper. */
    private ChequeAmountWords() {
    }

    /**
     * Spells the amount rounded to cents, using the operator's language and bundles
     * (LC-07-05-03). A formatter is local because ICU formatters are mutable.
     * @param amount the amount to print, null for no amount
     * @param i18n the message resolver
     * @return the amount in words, or empty for no amount
     */
    public static String format(BigDecimal amount, Messages i18n) {
        return format(amount, i18n, null, null);
    }

    /**
     * Spells the amount with the administered currency words (BO-10-07-05):
     * the word after the units, {@code Euro(s)} for instance, and the word
     * after the cents, {@code Cent(s)}. A blank word leaves the language's own,
     * agreed with the number.
     *
     * @param amount the amount to print, null for no amount
     * @param i18n the message resolver
     * @param unitText the administered word after the units, possibly null or blank
     * @param centText the administered word after the cents, possibly null or blank
     * @return the amount in words, or empty for no amount
     */
    public static String format(BigDecimal amount, Messages i18n, String unitText, String centText) {
        if (amount == null) {
            return "";
        }
        BigDecimal rounded = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long euros = rounded.setScale(0, RoundingMode.DOWN).longValueExact();
        int cents = rounded.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        RuleBasedNumberFormat words = new RuleBasedNumberFormat(
                Locale.forLanguageTag(i18n.getLang()), RuleBasedNumberFormat.SPELLOUT);
        String unitWord = isBlank(unitText) ? i18n.get(euros == 1 ? "cheque.euro" : "cheque.euros") : unitText.trim();
        String centWord = isBlank(centText) ? i18n.get(cents == 1 ? "cheque.cent" : "cheque.cents") : centText.trim();
        return i18n.format("cheque.amountWords", words.format(euros), unitWord, words.format(cents), centWord);
    }

    /**
     * Whether an administered word is absent.
     *
     * @param text the word, possibly null
     * @return true when null or blank
     */
    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
