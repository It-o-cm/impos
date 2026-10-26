package com.intermarche.pos.ui.payment;

import com.intermarche.pos.ui.PosState;
import com.intermarche.pos.ui.TestMessages;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Amount spelling, pluralisation and locale coverage for LC-07-05-03/-06. */
class ChequeAmountWordsTest {

    /** LC-07-05-03: absence of an amount produces no text. */
    @Test
    void nullAmount() {
        assertEquals("", ChequeAmountWords.format(null, TestMessages.french()));
    }

    /** LC-07-05-03: one euro and one centime each use their singular unit. */
    @Test
    void singularUnits() {
        assertEquals("un euro et un centime",
                ChequeAmountWords.format(new BigDecimal("1.01"), TestMessages.french()));
    }

    /** LC-07-05-03: both plural legs carry an independently spelled amount. */
    @Test
    void pluralUnits() {
        assertEquals("deux euros et cinquante centimes",
                ChequeAmountWords.format(new BigDecimal("2.50"), TestMessages.french()));
    }

    /** LC-07-05-03: integer amounts include a zero-centime part. */
    @Test
    void wholeAmount() {
        assertEquals("vingt euros et zéro centimes",
                ChequeAmountWords.format(new BigDecimal("20"), TestMessages.french()));
    }

    /** LC-07-05-03: cash precision is applied before splitting euros and cents. */
    @Test
    void roundingCarriesToEuros() {
        assertEquals("deux euros et zéro centimes",
                ChequeAmountWords.format(new BigDecimal("1.995"), TestMessages.french()));
    }

    /** LC-07-05-03: signed amounts still state the absolute cheque face value. */
    @Test
    void negativeAmount() {
        assertEquals("deux euros et cinquante centimes",
                ChequeAmountWords.format(new BigDecimal("-2.50"), TestMessages.french()));
    }

    /** LC-07-05-03: Dutch words and units follow the operator's selected bundle. */
    @Test
    void dutchAmount() {
        PosState state = new PosState();
        state.language = "nl";
        assertEquals("twee euro en vijftig cent",
                ChequeAmountWords.format(new BigDecimal("2.50"), TestMessages.french(state)));
    }

    /** LC-07-05-03: Portuguese words and units follow the selected bundle. */
    @Test
    void portugueseAmount() {
        PosState state = new PosState();
        state.language = "pt";
        assertEquals("dois euros e cinquenta cêntimos",
                ChequeAmountWords.format(new BigDecimal("2.50"), TestMessages.french(state)));
    }

    /** BO-10-07-05: the administered currency words replace the language's, trimmed. */
    @Test
    void administeredCurrencyWords() {
        assertEquals("deux Euro(s) et cinquante Cent(s)",
                ChequeAmountWords.format(new BigDecimal("2.50"), TestMessages.french(), " Euro(s) ", "Cent(s)"));
    }

    /** BO-10-07-05: a blank or absent administered word leaves the language's own. */
    @Test
    void blankCurrencyWordsKeepTheLanguages() {
        assertEquals("un euro et un centime",
                ChequeAmountWords.format(new BigDecimal("1.01"), TestMessages.french(), " ", null));
        assertEquals("un euro et un centime",
                ChequeAmountWords.format(new BigDecimal("1.01"), TestMessages.french(), null, ""));
    }
}
