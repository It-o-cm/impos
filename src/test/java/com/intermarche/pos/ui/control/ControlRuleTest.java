package com.intermarche.pos.ui.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link ControlRule}: the parsing of the administered register
 * alerts and what each rule watches (BO-04-03-08/09).
 */
class ControlRuleTest {

    /**
     * A null or empty text gives no rule; unreadable lines are skipped.
     */
    @Test
    void parseIgnoresWhatItCannotRead() {
        assertTrue(ControlRule.parse(null).isEmpty());
        assertTrue(ControlRule.parse("").isEmpty());
        assertTrue(ControlRule.parse("no equals sign\n = YESNO\nbarcode:20* = UNKNOWN").isEmpty());
    }

    /**
     * Every option is read: message, notification, maximum and profiles; the
     * literal {@code \n} separates lines like a real one.
     */
    @Test
    void parseReadsEveryOption() {
        List<ControlRule> rules = ControlRule.parse(
                "function:POST /action/cancelTicket = yesno ; text=Annuler ? ; notify ; max=2 ; profiles=Chef, ,Gérant ; other\\n"
                        + "offline=BLOCK;text=;max=x;max");
        assertEquals(2, rules.size());
        ControlRule first = rules.get(0);
        assertEquals("function", first.kind());
        assertEquals("POST /action/cancelTicket", first.value());
        assertEquals("function:POST /action/cancelTicket", first.key());
        assertEquals("offline:", rules.get(1).key());
        assertEquals("payment:CHEQUE", ControlRule.parseLine("payment:cheque=BLOCK").key());
        assertEquals(ControlRule.Type.YESNO, first.type());
        assertEquals("Annuler ?", first.text());
        assertTrue(first.notifies());
        assertEquals(2, first.max());
        assertEquals(List.of("Chef", "Gérant"), first.profiles());
        ControlRule second = rules.get(1);
        assertEquals("offline", second.kind());
        assertEquals("", second.value());
        assertEquals(ControlRule.Type.BLOCK, second.type());
        assertNull(second.text());
        assertFalse(second.notifies());
        assertEquals(0, second.max());
    }

    /**
     * A negative maximum reads as none.
     */
    @Test
    void numberClampsNegatives() {
        assertEquals(0, ControlRule.number("-3"));
        assertEquals(4, ControlRule.number("4"));
    }

    /**
     * A function rule matches its method and path, exactly or with sub-paths.
     */
    @Test
    void watchesFunctionRoutes() {
        ControlRule exact = ControlRule.parseLine("function:POST /action/cancelTicket/=BLOCK");
        assertTrue(exact.watchesFunction("POST", "/action/cancelTicket"));
        assertFalse(exact.watchesFunction("GET", "/action/cancelTicket"));
        assertFalse(exact.watchesFunction("POST", "/action/other"));
        ControlRule wild = ControlRule.parseLine("function:POST /action/pay/*=BLOCK");
        assertTrue(wild.watchesFunction("POST", "/action/pay"));
        assertTrue(wild.watchesFunction("POST", "/action/pay/abandon-ticket"));
        assertFalse(wild.watchesFunction("POST", "/action/payx"));
        ControlRule broken = ControlRule.parseLine("function:POST=BLOCK");
        assertFalse(broken.watchesFunction("POST", "/"));
        assertFalse(ControlRule.parseLine("barcode:1=BLOCK").watchesFunction("POST", "/action/pay-cash"));
    }

    /**
     * A payment rule watches the settlement route of its method, posted.
     */
    @Test
    void watchesPaymentRoutes() {
        ControlRule cheque = ControlRule.parseLine("payment:CHEQUE=SUPERVISOR");
        assertTrue(cheque.watchesFunction("POST", "/action/pay-cheque"));
        assertFalse(cheque.watchesFunction("GET", "/action/pay-cheque"));
        assertFalse(cheque.watchesFunction("POST", "/action/pay-cash"));
    }

    /**
     * A payment rule also watches a tender the store added, by the code posted
     * with it; the plain routes are still watched through the form overload.
     */
    @Test
    void watchesAdministeredTenders() {
        ControlRule bon = ControlRule.parseLine("payment:BONKDO=YESNO");
        assertTrue(bon.watchesFunction("POST", "/action/pay-tender", "code=bonkdo&amount=5"));
        assertFalse(bon.watchesFunction("GET", "/action/pay-tender", "code=BONKDO"));
        assertFalse(bon.watchesFunction("POST", "/action/pay-tender", "code=AUTRE"));
        assertFalse(bon.watchesFunction("POST", "/action/pay-tender", ""));
        assertFalse(bon.watchesFunction("POST", "/action/pay-other", "code=BONKDO"));
        assertTrue(bon.watchesFunction("POST", "/action/pay-bonkdo", ""));
        assertFalse(ControlRule.parseLine("barcode:1=BLOCK").watchesFunction("POST", "/action/pay-tender", "code=1"));
    }

    /**
     * An article rule watches an EAN, or a family behind an F.
     */
    @Test
    void watchesArticles() {
        ControlRule ean = ControlRule.parseLine("article:3017620422003=YESNO");
        assertTrue(ean.watchesArticle("3017620422003", null));
        assertFalse(ean.watchesArticle("1", null));
        ControlRule family = ControlRule.parseLine("article:F1020=YESNO");
        assertTrue(family.watchesArticle("1", "1020"));
        assertFalse(family.watchesArticle("1", "1030"));
        ControlRule lower = ControlRule.parseLine("article:f10=YESNO");
        assertTrue(lower.watchesArticle("1", "10"));
        ControlRule single = ControlRule.parseLine("article:F=YESNO");
        assertTrue(single.watchesArticle("F", null));
        assertFalse(ControlRule.parseLine("article:=YESNO").watchesArticle("", null));
        assertFalse(ControlRule.parseLine("barcode:1=YESNO").watchesArticle("1", "1"));
    }

    /**
     * A barcode rule watches a prefix or an exact code.
     */
    @Test
    void watchesBarcodes() {
        ControlRule prefix = ControlRule.parseLine("barcode:20*=BACKOFFICE");
        assertTrue(prefix.watchesBarcode("2012345"));
        assertFalse(prefix.watchesBarcode("3012345"));
        assertFalse(prefix.watchesBarcode(null));
        ControlRule exact = ControlRule.parseLine("barcode:123=BACKOFFICE");
        assertTrue(exact.watchesBarcode("123"));
        assertFalse(exact.watchesBarcode("1234"));
        assertFalse(ControlRule.parseLine("barcode:=BACKOFFICE").watchesBarcode("1"));
        assertFalse(ControlRule.parseLine("article:1=BACKOFFICE").watchesBarcode("1"));
    }
}
