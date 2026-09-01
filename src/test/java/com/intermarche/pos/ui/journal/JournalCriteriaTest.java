package com.intermarche.pos.ui.journal;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JournalCriteria} parsing.
 * <p>
 * Branch enumeration (100%): {@code fromParams} covers the null-params arm and
 * the populated arm, and the {@code descending} guard on both values;
 * {@code blankToNull} covers null / blank / non-blank; {@code parseAmount}
 * covers null / valid / malformed; {@code parseDateTime} covers null / full
 * datetime / bare date / fully-malformed (both catch arms); {@code addNonBlank}
 * covers the null-list arm and the blank-skip / kept arms; {@code addFlags}
 * covers the null-list arm, blank-skip, valid-add and unknown-catch arms;
 * {@code parsePage} covers blank / valid / non-positive / malformed.
 */
class JournalCriteriaTest {

    /**
     * Null params yield an empty match-everything criteria (null-params arm).
     */
    @Test
    void nullParamsYieldEmptyCriteria() {
        JournalCriteria criteria = JournalCriteria.fromParams(null);
        assertNull(criteria.text);
        assertNull(criteria.amountMin);
        assertNull(criteria.familyMin);
        assertNull(criteria.familyMax);
        assertNull(criteria.refundPluMin);
        assertNull(criteria.refundAmountMin);
        assertTrue(criteria.methods.isEmpty());
        assertTrue(criteria.flags.isEmpty());
        assertFalse(criteria.descending);
        assertEquals(1, criteria.page);
    }

    /**
     * A fully populated map parses every field: trimmed strings, comma
     * amounts, a full datetime and a bare date, the non-blank method values,
     * and the valid flag among a blank and an unknown one; {@code dir=desc}
     * sets descending.
     */
    @Test
    void populatedParamsParseEveryField() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "  lait  ");
        params.putSingle("cashierMin", " 100 ");
        params.putSingle("cashierMax", "200");
        params.putSingle("terminalMin", "C01");
        params.putSingle("terminalMax", "C09");
        params.putSingle("txMin", "C04-00000001");
        params.putSingle("txMax", "C04-00000999");
        params.putSingle("amountMin", "12,50");
        params.putSingle("amountMax", "99.00");
        params.putSingle("dateFrom", "2026-08-31T14:30");
        params.putSingle("dateTo", "2026-08-31");
        params.putSingle("pluMin", "40");
        params.putSingle("pluMax", "50");
        params.putSingle("familyMin", " FRUITS ");
        params.putSingle("familyMax", "LEGUMES");
        params.putSingle("refundPluMin", "40");
        params.putSingle("refundPluMax", "60");
        params.putSingle("refundAmountMin", "2,00");
        params.putSingle("refundAmountMax", "20.00");
        params.putSingle("vatRate", "0,2000");
        params.putSingle("reductionMin", "1,00");
        params.putSingle("reductionMax", "5,00");
        params.putSingle("authMin", " 100000 ");
        params.putSingle("authMax", "999999");
        params.put("method", List.of("CARD", "  "));
        params.put("eventType", List.of("SESSION_CLOSED"));
        params.put("flag", List.of("CARD", "DEGRADED", "DEGRADED_MANUAL", "  ", "XXX"));
        params.putSingle("sort", "amount");
        params.putSingle("dir", "desc");
        JournalCriteria criteria = JournalCriteria.fromParams(params);
        assertEquals("lait", criteria.text);
        assertEquals("100", criteria.cashierMin);
        assertEquals("200", criteria.cashierMax);
        assertEquals("C01", criteria.terminalMin);
        assertEquals("C09", criteria.terminalMax);
        assertEquals("C04-00000001", criteria.txMin);
        assertEquals("C04-00000999", criteria.txMax);
        assertEquals(new BigDecimal("12.50"), criteria.amountMin);
        assertEquals(new BigDecimal("99.00"), criteria.amountMax);
        assertEquals(LocalDateTime.of(2026, 8, 31, 14, 30), criteria.dateFrom);
        assertEquals(LocalDateTime.of(2026, 8, 31, 0, 0), criteria.dateTo);
        assertEquals("40", criteria.pluMin);
        assertEquals("50", criteria.pluMax);
        assertEquals("FRUITS", criteria.familyMin);
        assertEquals("LEGUMES", criteria.familyMax);
        assertEquals("40", criteria.refundPluMin);
        assertEquals("60", criteria.refundPluMax);
        assertEquals(new BigDecimal("2.00"), criteria.refundAmountMin);
        assertEquals(new BigDecimal("20.00"), criteria.refundAmountMax);
        assertEquals(new BigDecimal("0.2000"), criteria.vatRate);
        assertEquals(new BigDecimal("1.00"), criteria.reductionMin);
        assertEquals(new BigDecimal("5.00"), criteria.reductionMax);
        assertEquals("100000", criteria.authMin);
        assertEquals("999999", criteria.authMax);
        assertEquals("[CARD]", criteria.methods.toString());
        assertEquals("[SESSION_CLOSED]", criteria.eventTypes.toString());
        assertEquals(3, criteria.flags.size());
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.CARD));
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.DEGRADED));
        assertTrue(criteria.flags.contains(JournalCriteria.Flag.DEGRADED_MANUAL));
        assertEquals("amount", criteria.sort);
        assertTrue(criteria.descending);
    }

    /**
     * Absent, blank and malformed inputs are all dropped: a blank text becomes
     * null (blank arm), a malformed amount and a fully-malformed date become
     * null (catch arms), the absent multi-value keys hit the null-list arms of
     * {@code addNonBlank}/{@code addFlags}, and an absent {@code dir} leaves
     * descending false.
     */
    @Test
    void blankAndMalformedInputsAreDropped() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("text", "   ");
        params.putSingle("amountMin", "abc");
        params.putSingle("dateFrom", "xx");
        JournalCriteria criteria = JournalCriteria.fromParams(params);
        assertNull(criteria.text);
        assertNull(criteria.amountMin);
        assertNull(criteria.dateFrom);
        assertTrue(criteria.methods.isEmpty());
        assertTrue(criteria.eventTypes.isEmpty());
        assertTrue(criteria.flags.isEmpty());
        assertFalse(criteria.descending);
        assertEquals(1, criteria.page);
    }

    /**
     * A page parameter is read when it is a positive number (valid arm) and
     * reaches the criteria through {@code fromParams}.
     */
    @Test
    void positivePageIsRead() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("page", " 4 ");
        assertEquals(4, JournalCriteria.fromParams(params).page);
    }

    /**
     * A blank, non-positive or malformed page falls back to the first page
     * (blank arm, below-one arm, catch arm).
     */
    @Test
    void blankNonPositiveAndMalformedPagesFallBackToTheFirst() {
        assertEquals(1, JournalCriteria.parsePage(null));
        assertEquals(1, JournalCriteria.parsePage("   "));
        assertEquals(1, JournalCriteria.parsePage("0"));
        assertEquals(1, JournalCriteria.parsePage("-3"));
        assertEquals(1, JournalCriteria.parsePage("abc"));
    }
}
